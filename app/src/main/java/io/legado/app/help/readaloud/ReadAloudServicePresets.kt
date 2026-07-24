package io.legado.app.help.readaloud

import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.ui.main.ai.AI_API_MODE_CHAT_COMPLETIONS
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 朗读/配音「LLM + TTS」简化配置：对接 AI 提供商与 OpenAI 兼容 HTTP TTS。
 */
object ReadAloudServicePresets {

    /** 固定正 ID，避免 importDefaultHttpTTS 清理 id<0 时被删掉 */
    const val MANAGED_HTTP_TTS_ID = 710_001_000_001L
    private const val MANAGED_HTTP_TTS_NAME = "配音 TTS（OpenAI 兼容）"

    const val PRESET_MIMO = "mimo"
    const val PRESET_TTS_AI = "tts_ai"
    const val PRESET_QWEN = "qwen_tts"
    const val PRESET_SILICONFLOW = "siliconflow"

    data class TtsPreset(
        val id: String,
        val label: String,
        val defaultBaseUrl: String,
        val defaultModel: String,
        val contentType: String = "audio/mpeg"
    )

    val ttsPresets: List<TtsPreset> = listOf(
        TtsPreset(
            id = PRESET_MIMO,
            label = "MiMo",
            defaultBaseUrl = "https://api.xiaomimimo.com/v1",
            defaultModel = "mimo-v2.5-tts-voicedesign"
        ),
        TtsPreset(
            id = PRESET_TTS_AI,
            label = "tts.ai",
            defaultBaseUrl = "https://api.tts.ai/v1",
            defaultModel = "tts-1"
        ),
        TtsPreset(
            id = PRESET_QWEN,
            label = "Qwen-TTS",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-tts"
        ),
        TtsPreset(
            id = PRESET_SILICONFLOW,
            label = "SiliconFlow",
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "fnlp/MOSS-TTSD-v0.5"
        )
    )

    var selectedTtsPresetId: String
        get() = appCtx.getPrefString(PreferKey.readAloudTtsPresetId)
            ?.takeIf { id -> ttsPresets.any { it.id == id } }
            ?: PRESET_MIMO
        set(value) {
            val id = ttsPresets.firstOrNull { it.id == value }?.id ?: PRESET_MIMO
            appCtx.putPrefString(PreferKey.readAloudTtsPresetId, id)
        }

    fun preset(id: String = selectedTtsPresetId): TtsPreset {
        return ttsPresets.firstOrNull { it.id == id } ?: ttsPresets.first()
    }

    data class LlmDraft(
        val baseUrl: String = "",
        val apiKey: String = "",
        val modelId: String = ""
    )

    data class TtsDraft(
        val presetId: String = PRESET_MIMO,
        val baseUrl: String = "",
        val apiKey: String = "",
        val modelId: String = ""
    )

    fun loadLlmDraft(): LlmDraft {
        val model = AppConfig.aiReadAloudRoleModelConfig
            ?: AppConfig.aiCurrentModelConfig
            ?: AppConfig.aiModelConfigList.firstOrNull()
        val provider = AppConfig.aiProviderForModel(model)
            ?: AppConfig.aiCurrentProvider
            ?: AppConfig.aiProviderList.firstOrNull()
        return LlmDraft(
            baseUrl = provider?.baseUrl.orEmpty(),
            apiKey = provider?.apiKey.orEmpty(),
            modelId = model?.modelId.orEmpty()
        )
    }

    fun loadTtsDraft(): TtsDraft {
        val preset = preset()
        val managed = appDb.httpTTSDao.get(MANAGED_HTTP_TTS_ID)
        if (managed != null) {
            val parsed = parseManagedHttpTts(managed)
            return TtsDraft(
                presetId = selectedTtsPresetId,
                baseUrl = parsed.baseUrl.ifBlank { preset.defaultBaseUrl },
                apiKey = parsed.apiKey,
                modelId = parsed.modelId.ifBlank { preset.defaultModel }
            )
        }
        return TtsDraft(
            presetId = preset.id,
            baseUrl = preset.defaultBaseUrl,
            apiKey = "",
            modelId = preset.defaultModel
        )
    }

    fun saveLlmDraft(draft: LlmDraft): String? {
        val baseUrl = normalizeBaseUrl(draft.baseUrl)
        val modelId = draft.modelId.trim()
        if (baseUrl.isBlank()) return "请填写 LLM Base URL"
        if (modelId.isBlank()) return "请填写 LLM 模型"
        val providers = AppConfig.aiProviderList.toMutableList()
        val models = AppConfig.aiModelConfigList.toMutableList()
        val currentModel = AppConfig.aiReadAloudRoleModelConfig
            ?: AppConfig.aiCurrentModelConfig
            ?: models.firstOrNull()
        val currentProvider = AppConfig.aiProviderForModel(currentModel)
            ?: AppConfig.aiCurrentProvider
            ?: providers.firstOrNull()

        val provider = if (currentProvider == null) {
            AiProviderConfig(
                name = "配音 LLM",
                baseUrl = baseUrl,
                apiKey = draft.apiKey.trim(),
                apiMode = AI_API_MODE_CHAT_COMPLETIONS
            ).also { providers += it }
        } else {
            currentProvider.copy(
                baseUrl = baseUrl,
                apiKey = draft.apiKey.trim(),
                apiMode = currentProvider.apiMode.ifBlank { AI_API_MODE_CHAT_COMPLETIONS }
            ).also { updated ->
                val index = providers.indexOfFirst { it.id == updated.id }
                if (index >= 0) providers[index] = updated else providers += updated
            }
        }

        val model = if (currentModel == null || currentModel.providerId != provider.id) {
            AiModelConfig(providerId = provider.id, modelId = modelId).also { models += it }
        } else {
            currentModel.copy(modelId = modelId).also { updated ->
                val index = models.indexOfFirst { it.id == updated.id }
                if (index >= 0) models[index] = updated else models += updated
            }
        }

        AppConfig.aiProviderList = providers
        AppConfig.aiModelConfigList = models
        AppConfig.aiReadAloudRoleModelId = model.id
        if (AppConfig.aiCurrentModelId.isNullOrBlank()) {
            AppConfig.aiCurrentModelId = model.id
        }
        if (!AppConfig.aiReadAloudRoleEnabled && AppConfig.aiReadAloudRoleModelConfig != null) {
            // 不强制开启，只保证模型可用
        }
        return null
    }

    fun saveTtsDraft(draft: TtsDraft): String? {
        val preset = preset(draft.presetId)
        selectedTtsPresetId = preset.id
        val baseUrl = normalizeBaseUrl(draft.baseUrl.ifBlank { preset.defaultBaseUrl })
        val modelId = draft.modelId.trim().ifBlank { preset.defaultModel }
        val apiKey = draft.apiKey.trim()
        if (baseUrl.isBlank()) return "请填写 TTS Base URL"
        if (modelId.isBlank()) return "请填写 TTS 模型"
        val httpTts = buildManagedHttpTts(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            contentType = preset.contentType,
            presetId = preset.id
        )
        // REPLACE 冲突策略，创建与更新共用
        appDb.httpTTSDao.insert(httpTts)
        val route = SpeechRoute(
            engineType = SpeechRoute.ENGINE_HTTP,
            engineValue = MANAGED_HTTP_TTS_ID.toString(),
            speakerName = MANAGED_HTTP_TTS_NAME,
            source = SpeechRoute.SOURCE_MANUAL
        )
        AppConfig.ttsEngine = route.toJson()
        return null
    }

    fun applyPresetDefaults(presetId: String, keepKey: Boolean, current: TtsDraft): TtsDraft {
        val preset = preset(presetId)
        return current.copy(
            presetId = preset.id,
            baseUrl = preset.defaultBaseUrl,
            modelId = preset.defaultModel,
            apiKey = if (keepKey) current.apiKey else current.apiKey
        )
    }

    private fun buildManagedHttpTts(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        contentType: String,
        presetId: String
    ): HttpTTS {
        val mimo = presetId == PRESET_MIMO
        val endpoint = baseUrl.trimEnd('/') + if (mimo) "/chat/completions" else "/audio/speech"
        val body = if (mimo) mimoBody(modelId) else openAiTtsBody(modelId)
        val urlRule = buildString {
            append(endpoint)
            append(",{")
            append("\"method\":\"POST\",")
            append("\"body\":")
            append(body.toString())
            append("}")
        }
        val header = if (apiKey.isBlank()) {
            """{"Content-Type":"application/json"}"""
        } else {
            JSONObject().apply {
                put("Content-Type", "application/json")
                put("Authorization", "Bearer $apiKey")
            }.toString()
        }
        return HttpTTS(
            id = MANAGED_HTTP_TTS_ID,
            name = MANAGED_HTTP_TTS_NAME,
            url = urlRule,
            contentType = if (mimo) "application/json" else contentType,
            concurrentRate = "0",
            synthesisThreadCount = 2,
            header = header,
            speakersJson = """[{"speakerName":"默认","toneID":"alloy"}]""",
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    private fun mimoBody(modelId: String) = JSONObject().apply {
        put("model", modelId)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "{{currentVoiceStyle || '自然、清晰、适合长篇听书的普通话音色'}}")
            })
            put(JSONObject().apply {
                put("role", "assistant")
                put("content", "{{speakText}}")
            })
        })
        put("audio", JSONObject().apply { put("format", "wav") })
    }

    private fun openAiTtsBody(modelId: String) = JSONObject().apply {
        put("model", modelId)
        put("input", "{{speakText}}")
        put("voice", "{{currentToneID || 'alloy'}}")
        put("response_format", "mp3")
        put("speed", "{{Math.max(0.25, Math.min(4.0, speakSpeed / 10.0))}}")
    }

    private data class ParsedManaged(
        val baseUrl: String,
        val apiKey: String,
        val modelId: String
    )

    private fun parseManagedHttpTts(httpTTS: HttpTTS): ParsedManaged {
        val rawUrl = httpTTS.url.substringBefore(",").trim()
        val baseUrl = rawUrl
            .removeSuffix("/chat/completions")
            .removeSuffix("/audio/speech")
            .removeSuffix("/v1/audio/speech")
            .let { if (it.endsWith("/v1")) it else it.trimEnd('/') }
        val apiKey = runCatching {
            val header = JSONObject(httpTTS.header.orEmpty())
            header.optString("Authorization")
                .removePrefix("Bearer ")
                .trim()
        }.getOrDefault("")
        val modelId = runCatching {
            val options = httpTTS.url.substringAfter(",", "")
            if (options.isBlank()) return@runCatching ""
            val body = JSONObject(options).opt("body")
            when (body) {
                is JSONObject -> body.optString("model")
                is String -> JSONObject(body).optString("model")
                else -> ""
            }
        }.getOrDefault("")
        return ParsedManaged(baseUrl = baseUrl, apiKey = apiKey, modelId = modelId)
    }

    fun normalizeBaseUrl(raw: String): String {
        return raw.trim().trimEnd('/')
    }
}
