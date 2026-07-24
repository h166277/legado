package io.legado.app.help.readaloud

import android.media.MediaPlayer
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.TTS
import io.legado.app.help.ai.AiReadAloudVoiceStyleService
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.help.readaloud.speech.SpeechVoiceAssigner
import io.legado.app.model.ReadAloud
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 按角色绑定的发言人路由试听，避免落到系统默认 TTS。
 */
object SpeechRoutePreviewPlayer {

    data class PreviewResult(
        val ok: Boolean,
        val message: String = "",
        val engineLabel: String = ""
    )

    private val systemTts = TTS()
    private val mediaPlayerRef = AtomicReference<MediaPlayer?>(null)

    fun stop() {
        runCatching { systemTts.stop() }
        mediaPlayerRef.getAndSet(null)?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
    }

    fun clear() {
        stop()
        runCatching { systemTts.clearTts() }
    }

    fun resolveRoute(character: BookCharacter): SpeechRoute {
        val characterRoute = SpeechRouteSanitizer.validOrNull(
            SpeechRoute.fromJson(character.speechRouteJson)
        )
        if (characterRoute != null && characterRoute.isConfigured) {
            return characterRoute.copy(voiceStyle = character.personality.trim())
        }
        val httpList = appDb.httpTTSDao.all
        val assigned = SpeechVoiceAssigner.assignRoute(character, httpList)
        if (assigned.isConfigured) {
            return assigned.copy(voiceStyle = character.personality.trim())
        }
        return SpeechRouteSanitizer.validOrDefault(ReadAloud.speechRoute)
            .copy(voiceStyle = character.personality.trim())
    }

    suspend fun preview(
        character: BookCharacter,
        text: String = AiReadAloudVoiceStyleService.previewSentence(character)
    ): PreviewResult = withContext(IO) {
        stop()
        val speakText = text.trim().ifBlank {
            AiReadAloudVoiceStyleService.previewSentence(character)
        }
        val route = resolveRoute(character)
        val httpTts = httpTtsFor(route)
        if (route.engineType == SpeechRoute.ENGINE_HTTP && httpTts != null) {
            return@withContext playHttp(httpTts, route, speakText, character.personality)
        }
        withContext(Main) {
            systemTts.speak(speakText)
        }
        PreviewResult(
            ok = true,
            message = "系统 TTS 试听",
            engineLabel = route.speakerName.ifBlank { "系统默认" }
        )
    }

    private fun httpTtsFor(route: SpeechRoute): HttpTTS? {
        if (route.engineType != SpeechRoute.ENGINE_HTTP) return null
        val id = route.engineValue.toLongOrNull() ?: return null
        return appDb.httpTTSDao.get(id) ?: appDb.httpTTSDao.all.firstOrNull()
    }

    private suspend fun playHttp(
        httpTts: HttpTTS,
        route: SpeechRoute,
        speakText: String,
        voiceStyle: String
    ): PreviewResult {
        val speechRate = if (AppConfig.ttsFlowSys) 5 else AppConfig.ttsSpeechRate
        val analyzeUrl = AnalyzeUrl(
            httpTts.url,
            speakText = speakText,
            speakSpeed = speechRate,
            currentToneID = route.toneID.ifBlank { "alloy" },
            currentSpeakerName = route.speakerName.ifBlank { characterLabel(voiceStyle) },
            currentEmotionName = route.emotionName,
            currentEmotionTag = route.emotionTag,
            currentVoiceStyle = voiceStyle,
            currentSpeechRouteJson = route.toJson(),
            source = httpTts,
            readTimeout = 120_000L,
            allowWebSocket = true,
            coroutineContext = currentCoroutineContext()
        )
        val response = try {
            analyzeUrl.getResponseAwait()
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return PreviewResult(false, t.localizedMessage ?: "HTTP TTS 请求失败")
        }
        currentCoroutineContext().ensureActive()
        val bytes = runCatching {
            HttpTtsAudioResponse.openStream(response).use { it.readBytes() }
        }.getOrElse {
            return PreviewResult(false, it.localizedMessage ?: "读取音频失败")
        }
        if (bytes.isEmpty()) {
            return PreviewResult(false, "TTS 返回空音频")
        }
        val file = File(
            appCtx.externalCache,
            "tts_preview_${MD5Utils.md5Encode16(speakText + route.toJson())}.mp3"
        )
        file.writeBytes(bytes)
        withContext(Main) {
            val player = MediaPlayer()
            mediaPlayerRef.getAndSet(player)?.release()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release()
                mediaPlayerRef.compareAndSet(player, null)
                runCatching { file.delete() }
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                mediaPlayerRef.compareAndSet(player, null)
                runCatching { file.delete() }
                true
            }
            player.prepare()
            player.start()
        }
        val label = listOfNotNull(
            route.speakerName.takeIf { it.isNotBlank() },
            httpTts.name.takeIf { it.isNotBlank() },
            "HTTP"
        ).joinToString(" · ")
        return PreviewResult(ok = true, message = "HTTP TTS 试听", engineLabel = label)
    }

    private fun characterLabel(voiceStyle: String): String {
        return voiceStyle.lineSequence().firstOrNull()?.take(40).orEmpty()
    }
}
