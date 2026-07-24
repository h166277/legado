package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.character.BookCharacterProfileMeta
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.ui.main.ai.AiChatMessage
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 根据角色资料与章节片段生成“音色风格”描述，写入 personality（便于资料卡与配音页展示）。
 */
object AiReadAloudVoiceStyleService {

    data class AnalyzeResult(
        val ok: Boolean,
        val style: String = "",
        val message: String = "",
        val character: BookCharacter? = null
    )

    suspend fun analyzeAndSave(
        book: Book?,
        characterId: Long,
        sampleText: String = "",
        overwrite: Boolean = true
    ): AnalyzeResult = withContext(IO) {
        val currentBook = book
            ?: return@withContext AnalyzeResult(false, message = "书籍为空")
        if (AppConfig.aiReadAloudRoleModelConfig == null) {
            return@withContext AnalyzeResult(false, message = "未配置多角色模型")
        }
        val bookKey = BookCharacterIdentityMigrator.migrate(currentBook)
        val character = appDb.bookCharacterDao.getCharacter(characterId)
            ?.takeIf { it.bookUrl == bookKey }
            ?: return@withContext AnalyzeResult(false, message = "未找到角色")
        if (!overwrite && character.personality.isNotBlank()) {
            return@withContext AnalyzeResult(
                ok = true,
                style = character.personality.trim(),
                message = "已有音色描述，已跳过",
                character = character
            )
        }
        val style = requestVoiceStyle(currentBook, character, sampleText)
            .ifBlank { fallbackStyle(character) }
        val updated = character.copy(
            personality = style.take(400),
            updatedAt = System.currentTimeMillis()
        )
        appDb.bookCharacterDao.updateCharacter(updated)
        ReadAloudConfigChangeNotifier.notifySpeech()
        AnalyzeResult(ok = true, style = style, character = updated)
    }

    suspend fun analyzeMany(
        book: Book?,
        characterIds: List<Long>,
        sampleById: Map<Long, String> = emptyMap(),
        overwrite: Boolean = false
    ): List<AnalyzeResult> = withContext(IO) {
        characterIds.map { id ->
            analyzeAndSave(
                book = book,
                characterId = id,
                sampleText = sampleById[id].orEmpty(),
                overwrite = overwrite
            )
        }
    }

    private suspend fun requestVoiceStyle(
        book: Book,
        character: BookCharacter,
        sampleText: String
    ): String {
        val retries = AppConfig.aiReadAloudFailRetryCount
        var lastError: Throwable? = null
        repeat(retries + 1) { attempt ->
            try {
                if (attempt > 0) {
                    delay(AppConfig.aiReadAloudRetryBackoffMillis(attempt))
                }
                val response = AiChatService.chatStream(
                    messages = listOf(
                        AiChatMessage(
                            role = AiChatMessage.Role.USER,
                            content = buildString {
                                appendLine(AppConfig.aiReadAloudVoiceStylePrompt)
                                appendLine()
                                append(buildUserPrompt(book, character, sampleText))
                            }
                        )
                    ),
                    onPartial = {},
                    includeStructuredBlocks = false,
                    useAllTools = false,
                    modelConfigOverride = AppConfig.aiReadAloudRoleModelConfig,
                    fallbackModelConfigOverride = AppConfig.aiReadAloudRoleBackupModelConfig,
                    firstResponseTimeoutMillis = AppConfig.aiReadAloudRoleFirstResponseTimeoutMillis
                )
                return normalizeStyle(response).ifBlank { fallbackStyle(character) }
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message.orEmpty()
                // 认证错误不重试
                if (msg.contains("401") || msg.contains("403") ||
                    msg.contains("Unauthorized", ignoreCase = true)
                ) {
                    throw t
                }
            }
        }
        throw lastError ?: IllegalStateException("音色分析失败")
    }

    private fun buildUserPrompt(
        book: Book,
        character: BookCharacter,
        sampleText: String
    ): String {
        val age = BookCharacterProfileMeta.ageOf(character)
        val attrs = BookCharacterProfileMeta.attributesWithoutAge(character.attributes)
        return buildString {
            appendLine("书名：${book.name}")
            appendLine("作者：${book.author}")
            appendLine("角色名：${character.displayName()}")
            appendLine("性别：${character.genderLabel()}")
            if (age.isNotBlank()) appendLine("年纪：$age")
            if (character.identity.isNotBlank()) appendLine("身份：${character.identity}")
            if (character.appearance.isNotBlank()) appendLine("外貌：${character.appearance}")
            if (attrs.isNotBlank()) appendLine("属性：$attrs")
            if (character.biography.isNotBlank()) appendLine("简介：${character.biography.take(300)}")
            if (character.personality.isNotBlank()) {
                appendLine("已有音色/性格：${character.personality.take(200)}")
            }
            if (sampleText.isNotBlank()) {
                appendLine("台词/旁白片段：")
                appendLine(sampleText.take(800))
            }
            appendLine("请只输出一行音色风格描述。")
        }.trim()
    }

    fun normalizeStyle(raw: String): String {
        val text = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .lineSequence()
            .map { it.trim().trim('"', '“', '”', '\'', '「', '」') }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("示例") ||
                        it.startsWith("输出") ||
                        it.startsWith("{") ||
                        it.contains("system prompt", ignoreCase = true)
            }
            .firstOrNull()
            .orEmpty()
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }

    fun fallbackStyle(character: BookCharacter): String {
        val gender = when (BookCharacter.normalizeGender(character.gender)) {
            BookCharacter.GENDER_FEMALE -> "女性"
            BookCharacter.GENDER_MALE -> "男性"
            else -> if (character.name == "旁白") "中性旁白" else "未知性别"
        }
        val age = BookCharacterProfileMeta.ageOf(character).ifBlank {
            when {
                character.name == "旁白" -> "约40岁"
                character.roleLevel == BookCharacter.ROLE_MAIN -> "约25岁"
                else -> "约30岁"
            }
        }
        return when (BookCharacter.normalizeGender(character.gender)) {
            BookCharacter.GENDER_FEMALE ->
                "$gender，$age，45%清亮自然，30%柔和亲和，25%沉稳克制。"
            BookCharacter.GENDER_MALE ->
                "$gender，$age，40%沉稳清晰，35%中气足，25%干净利落。"
            else ->
                if (character.name == "旁白") {
                    "男性，约40岁，50%沉稳叙述，30%中性清晰，20%克制不抢戏。"
                } else {
                    "$gender，$age，50%中性清晰，30%平稳自然，20%不夸张。"
                }
        }
    }

    fun previewSentence(character: BookCharacter): String {
        val style = character.personality.ifBlank { fallbackStyle(character) }
        return "我是${character.displayName()}。$style"
    }

    fun debugJson(result: AnalyzeResult): String {
        return JSONObject().apply {
            put("ok", result.ok)
            put("style", result.style)
            put("message", result.message)
            result.character?.let { put("characterId", it.id) }
        }.toString()
    }
}
