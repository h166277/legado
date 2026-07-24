package io.legado.app.help.readaloud

import android.util.Base64
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/** 支持普通音频响应，以及 MiMo Chat Completions 返回的 Base64 音频。 */
object HttpTtsAudioResponse {

    private const val MAX_JSON_BYTES = 32L * 1024L * 1024L

    fun openStream(response: Response): InputStream {
        val contentType = response.headers["Content-Type"]
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()
        if (contentType != "application/json" && !contentType.startsWith("text/")) {
            return response.body.byteStream()
        }
        val body = response.peekBody(MAX_JSON_BYTES).string()
        val audioData = runCatching {
            val root = JSONObject(body)
            root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optJSONObject("audio")
                ?.optString("data")
                .orEmpty()
        }.getOrDefault("")
        if (audioData.isBlank()) {
            throw IllegalStateException(extractError(body))
        }
        val clean = audioData.substringAfter("base64,", audioData)
        val bytes = runCatching { Base64.decode(clean, Base64.DEFAULT) }
            .getOrElse { throw IllegalStateException("TTS 返回的音频编码无效", it) }
        if (bytes.isEmpty()) throw IllegalStateException("TTS 返回空音频")
        return ByteArrayInputStream(bytes)
    }

    private fun extractError(body: String): String {
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.let { error ->
                error.optString("message").ifBlank { error.toString() }
            } ?: root.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.take(500)
            ?: body.take(500).ifBlank { "TTS 返回文本错误" }
    }
}
