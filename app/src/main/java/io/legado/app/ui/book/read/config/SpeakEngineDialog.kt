package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.readaloud.ReadAloudServicePresets
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.readaloud.speech.SpeechVoiceOption
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.ai.AI_API_MODE_CHAT_COMPLETIONS
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.Request
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * TTS 引擎管理。
 */
class SpeakEngineDialog : BaseDialogFragment(0), SpeakEngineDialogActions {

    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var ttsEngine by mutableStateOf(ReadAloud.ttsEngine)
    private var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    private var pickerGroupKey by mutableStateOf<String?>(null)
    private var showAdvancedEngineList by mutableStateOf(false)
    private var llmDraft by mutableStateOf(ReadAloudServicePresets.loadLlmDraft())
    private var ttsDraft by mutableStateOf(ReadAloudServicePresets.loadTtsDraft())
    private var busyHint by mutableStateOf("")

    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportHttpTtsDialog(uri.toString())) }
    }

    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                hint = getString(R.string.path),
                initialValue = url,
                message = DirectLinkUpload.getSummary().takeIf { url.isAbsUrl() },
                readOnly = true,
                positiveText = getString(R.string.copy_text),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { requireContext().sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.TTS_RULE) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SpeakEngineScreen(
                    ttsEngine = ttsEngine,
                    httpTtsList = httpTtsList,
                    pickerGroupKey = pickerGroupKey,
                    showAdvanced = showAdvancedEngineList,
                    llmDraft = llmDraft,
                    ttsDraft = ttsDraft,
                    busyHint = busyHint,
                    actions = this@SpeakEngineDialog
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll()
                .catch { AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it) }
                .flowOn(IO)
                .conflate()
                .collect {
                    httpTtsList = it
                }
        }
    }

    override fun openSpeakerPicker(group: SpeechVoiceEngineGroup) {
        pickerGroupKey = group.key
    }

    override fun closeSpeakerPicker() {
        pickerGroupKey = null
    }

    override fun selectRoute(route: SpeechRoute) {
        ttsEngine = route.toJson()
        pickerGroupKey = null
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = ttsEngine
        callBack?.upSpeakEngineSummary()
        notifyReadAloudEngineChanged()
        route.engineValue.toLongOrNull()
            ?.let { appDb.httpTTSDao.get(it) }
            ?.takeIf { !it.loginUrl.isNullOrBlank() && it.getLoginInfo().isNullOrBlank() }
            ?.let { loginKey ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", loginKey.id.toString())
                }
            }
    }

    override fun addHttpTts() {
        showDialogFragment<HttpTtsEditDialog>()
    }

    override fun editHttpTts(id: Long) {
        showDialogFragment(HttpTtsEditDialog(id))
    }

    override fun deleteHttpTts(httpTTS: HttpTTS) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + httpTTS.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                val appContext = requireContext().applicationContext
                lifecycleScope.launch(IO) {
                    appDb.httpTTSDao.delete(httpTTS)
                    val result = SpeechRouteSanitizer.cleanDeletedHttpTts(httpTTS)
                    if (result.changed) {
                        val message = buildList {
                            if (result.characterCount > 0) add("${result.characterCount} 个角色")
                            if (result.bookCount > 0) add("${result.bookCount} 本书")
                            if (result.speakerGroupItemCount > 0) add("${result.speakerGroupItemCount} 个发言人分组条目")
                            if (result.globalCleared) add("通用朗读引擎")
                        }.joinToString("、")
                        appContext.toastOnUi("已清理 $message 的失效朗读配置")
                    }
                    notifyReadAloudEngineChanged()
                }
            }
        )
    }

    private fun notifyReadAloudEngineChanged() {
        ReadAloud.refreshReadAloudClass()
        postEvent(
            EventBus.READ_ALOUD_CONFIG_CHANGED,
            Bundle().apply {
                putString(
                    EventBus.READ_ALOUD_CONFIG_SCOPE,
                    EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                )
            }
        )
    }

    override fun login(group: SpeechVoiceEngineGroup) {
        group.loginKey.takeIf { it.isNotBlank() }?.let { key ->
            startActivity<SourceLoginActivity> {
                putExtra("type", "httpTts")
                putExtra("key", key)
            }
        }
    }

    override fun importDefault() {
        viewModel.importDefault()
    }

    override fun importLocal() {
        importDocResult.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun importOnline() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    override fun exportAll() {
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts.json",
                GSON.toJson(httpTtsList).toByteArray(),
                "application/json"
            )
        }
    }

    override fun exportSelected() {
        val id = SpeechRoute.fromTtsEngineValue(ttsEngine).engineValue.toLongOrNull()
        val tts = id?.let { appDb.httpTTSDao.get(it) }
        if (tts == null) {
            toastOnUi(R.string.is_system_tts_no_export)
            return
        }
        exportHttpTts(tts)
    }

    override fun exportHttpTts(httpTTS: HttpTTS) {
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts_${httpTTS.name}.json",
                GSON.toJson(httpTTS).toByteArray(),
                "application/json"
            )
        }
    }

    override fun updateLlmDraft(draft: ReadAloudServicePresets.LlmDraft) {
        llmDraft = draft
    }

    override fun updateTtsDraft(draft: ReadAloudServicePresets.TtsDraft) {
        ttsDraft = draft
    }

    override fun saveServiceConfig() {
        lifecycleScope.launch {
            val llmError = withContext(IO) { ReadAloudServicePresets.saveLlmDraft(llmDraft) }
            if (llmError != null) {
                toastOnUi(llmError)
                return@launch
            }
            val ttsError = withContext(IO) { ReadAloudServicePresets.saveTtsDraft(ttsDraft) }
            if (ttsError != null) {
                toastOnUi(ttsError)
                return@launch
            }
            ttsEngine = ReadAloud.ttsEngine
            ReadBook.book?.setTtsEngine(null)
            notifyReadAloudEngineChanged()
            callBack?.upSpeakEngineSummary()
            toastOnUi("已保存 LLM / TTS 配置")
        }
    }

    override fun testLlm() {
        lifecycleScope.launch {
            busyHint = "测试 LLM…"
            val result = withContext(IO) {
                runCatching {
                    val err = ReadAloudServicePresets.saveLlmDraft(llmDraft)
                    if (err != null) error(err)
                    val provider = AppConfig.aiProviderForModel(AppConfig.aiReadAloudRoleModelConfig)
                        ?: AppConfig.aiCurrentProvider
                        ?: error("未找到 LLM 提供商")
                    val models = AiChatService.fetchModels(provider)
                    "LLM 连通成功 · 模型目录 ${models.size} 个"
                }
            }
            busyHint = ""
            toastOnUi(result.getOrElse { it.localizedMessage ?: "LLM 测试失败" })
        }
    }

    override fun testTts() {
        lifecycleScope.launch {
            busyHint = "测试 TTS…"
            val result = withContext(IO) {
                runCatching {
                    val err = ReadAloudServicePresets.saveTtsDraft(ttsDraft)
                    if (err != null) error(err)
                    val base = ReadAloudServicePresets.normalizeBaseUrl(ttsDraft.baseUrl)
                    val url = base.trimEnd('/') + "/models"
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json")
                        .apply {
                            ttsDraft.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                                header("Authorization", "Bearer $it")
                            }
                        }
                        .build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("TTS ${resp.code} ${resp.message}")
                        }
                        "TTS 连通成功 · ${resp.code}"
                    }
                }
            }
            busyHint = ""
            toastOnUi(result.getOrElse { it.localizedMessage ?: "TTS 测试失败" })
        }
    }

    override fun fetchLlmModels() {
        lifecycleScope.launch {
            busyHint = "拉取 LLM 模型…"
            val models = withContext(IO) {
                runCatching {
                    val provider = AiProviderConfig(
                        name = "temp",
                        baseUrl = llmDraft.baseUrl,
                        apiKey = llmDraft.apiKey,
                        apiMode = AI_API_MODE_CHAT_COMPLETIONS
                    )
                    AiChatService.fetchModels(provider)
                }
            }
            busyHint = ""
            models.onSuccess { list ->
                if (list.isEmpty()) {
                    toastOnUi("未返回模型列表")
                } else {
                    showComposeTextInputDialog(
                        title = "LLM 模型（点确定使用首项，或改后保存）",
                        hint = "模型 ID",
                        initialValue = list.first(),
                        message = list.take(12).joinToString("\n"),
                        onPositive = { llmDraft = llmDraft.copy(modelId = it.trim()) }
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "拉取失败")
            }
        }
    }

    override fun fetchTtsModels() {
        lifecycleScope.launch {
            busyHint = "拉取 TTS 模型…"
            val models = withContext(IO) {
                runCatching {
                    val provider = AiProviderConfig(
                        name = "temp-tts",
                        baseUrl = ttsDraft.baseUrl,
                        apiKey = ttsDraft.apiKey,
                        apiMode = AI_API_MODE_CHAT_COMPLETIONS
                    )
                    AiChatService.fetchModels(provider)
                }
            }
            busyHint = ""
            models.onSuccess { list ->
                if (list.isEmpty()) {
                    toastOnUi("未返回模型列表，可手动填写")
                } else {
                    val ttsLike = list.filter {
                        it.contains("tts", true) ||
                                it.contains("voice", true) ||
                                it.contains("speech", true)
                    }.ifEmpty { list }
                    showComposeTextInputDialog(
                        title = "TTS 模型",
                        hint = "模型 ID",
                        initialValue = ttsLike.first(),
                        message = ttsLike.take(12).joinToString("\n"),
                        onPositive = { ttsDraft = ttsDraft.copy(modelId = it.trim()) }
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "拉取失败")
            }
        }
    }

    override fun openAdvancedEngines() {
        showAdvancedEngineList = true
    }

    override fun closeAdvancedEngines() {
        showAdvancedEngineList = false
    }

    override fun clearCache() {
        execute {
            notifyReadAloudEngineChanged()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    override fun close() {
        dismissAllowingStateLoss()
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}

private interface SpeakEngineDialogActions {
    fun openSpeakerPicker(group: SpeechVoiceEngineGroup)
    fun closeSpeakerPicker()
    fun selectRoute(route: SpeechRoute)
    fun addHttpTts()
    fun editHttpTts(id: Long)
    fun deleteHttpTts(httpTTS: HttpTTS)
    fun login(group: SpeechVoiceEngineGroup)
    fun importDefault()
    fun importLocal()
    fun importOnline()
    fun exportAll()
    fun exportSelected()
    fun exportHttpTts(httpTTS: HttpTTS)
    fun clearCache()
    fun close()
    fun updateLlmDraft(draft: ReadAloudServicePresets.LlmDraft)
    fun updateTtsDraft(draft: ReadAloudServicePresets.TtsDraft)
    fun saveServiceConfig()
    fun testLlm()
    fun testTts()
    fun fetchLlmModels()
    fun fetchTtsModels()
    fun openAdvancedEngines()
    fun closeAdvancedEngines()
}

@Composable
private fun SpeakEngineScreen(
    ttsEngine: String?,
    httpTtsList: List<HttpTTS>,
    pickerGroupKey: String?,
    showAdvanced: Boolean,
    llmDraft: ReadAloudServicePresets.LlmDraft,
    ttsDraft: ReadAloudServicePresets.TtsDraft,
    busyHint: String,
    actions: SpeakEngineDialogActions
) {
    val context = LocalContext.current
    val colors = rememberSpeakEngineColors()
    val groups = rememberSpeechGroups(httpTtsList)
    val currentRoute = SpeechRoute.fromTtsEngineValue(ttsEngine)
    var importDialogVisible by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily(context.uiTypeface()))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.page,
            shape = RoundedCornerShape(context.composePanelRadius())
        ) {
            if (showAdvanced) {
                AdvancedEngineListScreen(
                    ttsEngine = ttsEngine,
                    httpTtsList = httpTtsList,
                    pickerGroupKey = pickerGroupKey,
                    groups = groups,
                    colors = colors,
                    importDialogVisible = importDialogVisible,
                    onImportDialogChange = { importDialogVisible = it },
                    actions = actions
                )
            } else {
                ServiceConfigFormScreen(
                    llmDraft = llmDraft,
                    ttsDraft = ttsDraft,
                    busyHint = busyHint,
                    currentSummary = speechRouteSummary(
                        currentRoute,
                        groups,
                        defaultText = "系统默认 / 未配置托管 TTS"
                    ),
                    colors = colors,
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun ServiceConfigFormScreen(
    llmDraft: ReadAloudServicePresets.LlmDraft,
    ttsDraft: ReadAloudServicePresets.TtsDraft,
    busyHint: String,
    currentSummary: String,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "朗读服务",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = actions::close) {
                Text("关闭", color = colors.subText)
            }
        }
        Text(
            text = currentSummary,
            color = colors.subText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (busyHint.isNotBlank()) {
            Text(
                busyHint,
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(
                title = "LLM 配置（角色分析）",
                action = "测试",
                colors = colors,
                onAction = actions::testLlm
            )
            FormField(
                label = "Base URL",
                value = llmDraft.baseUrl,
                onValueChange = { actions.updateLlmDraft(llmDraft.copy(baseUrl = it)) },
                hint = "https://api.example.com/v1",
                colors = colors
            )
            Text(
                "支持局域网 Ollama / LM Studio / vLLM 等 OpenAI 兼容服务，如 http://192.168.1.10:11434/v1",
                color = colors.subText,
                fontSize = 11.sp
            )
            FormField(
                label = "API Key（本地无鉴权可留空）",
                value = llmDraft.apiKey,
                onValueChange = { actions.updateLlmDraft(llmDraft.copy(apiKey = it)) },
                hint = "sk-…",
                colors = colors,
                password = true
            )
            FormField(
                label = "模型",
                value = llmDraft.modelId,
                onValueChange = { actions.updateLlmDraft(llmDraft.copy(modelId = it)) },
                hint = "mimo-v2.5",
                colors = colors,
                trailing = {
                    MiniAction("刷新", colors) { actions.fetchLlmModels() }
                }
            )

            SectionHeader(
                title = "TTS 配置（语音合成）",
                action = "测试",
                colors = colors,
                onAction = actions::testTts
            )
            PresetTabs(
                selectedId = ttsDraft.presetId,
                colors = colors,
                onSelect = { id ->
                    val next = ReadAloudServicePresets.applyPresetDefaults(
                        presetId = id,
                        keepKey = true,
                        current = ttsDraft
                    )
                    actions.updateTtsDraft(next)
                }
            )
            FormField(
                label = "Base URL",
                value = ttsDraft.baseUrl,
                onValueChange = { actions.updateTtsDraft(ttsDraft.copy(baseUrl = it)) },
                hint = "https://api.xiaomimimo.com/v1",
                colors = colors
            )
            FormField(
                label = "API Key",
                value = ttsDraft.apiKey,
                onValueChange = { actions.updateTtsDraft(ttsDraft.copy(apiKey = it)) },
                hint = "sk-…",
                colors = colors,
                password = true
            )
            FormField(
                label = "模型",
                value = ttsDraft.modelId,
                onValueChange = { actions.updateTtsDraft(ttsDraft.copy(modelId = it)) },
                hint = "mimo-v2.5-tts-voicedesign",
                colors = colors,
                trailing = {
                    MiniAction("刷新", colors) { actions.fetchTtsModels() }
                }
            )
            Text(
                "保存后会写入「配音 TTS（OpenAI 兼容）」HTTP 引擎，并设为当前朗读引擎。",
                color = colors.subText,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallEngineAction("高级引擎列表", actions::openAdvancedEngines, colors)
                SmallEngineAction("清缓存", actions::clearCache, colors)
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clickable(onClick = actions::saveServiceConfig),
            color = colors.accent,
            shape = RoundedCornerShape(LocalContext.current.composeActionRadius())
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("保存设置", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AdvancedEngineListScreen(
    ttsEngine: String?,
    httpTtsList: List<HttpTTS>,
    pickerGroupKey: String?,
    groups: List<SpeechVoiceEngineGroup>,
    colors: SpeakEngineColors,
    importDialogVisible: Boolean,
    onImportDialogChange: (Boolean) -> Unit,
    actions: SpeakEngineDialogActions
) {
    val currentRoute = SpeechRoute.fromTtsEngineValue(ttsEngine)
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions::closeAdvancedEngines) {
                Text("← 返回", color = colors.accent)
            }
            Text(
                text = "高级 · 朗读引擎",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = actions::close) {
                Text("关闭", color = colors.subText)
            }
        }
        EngineTopActions(
            colors = colors,
            onAdd = actions::addHttpTts,
            onImport = { onImportDialogChange(true) },
            onExportAll = actions::exportAll,
            onClearCache = actions::clearCache,
            modifier = Modifier.padding(top = 8.dp)
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groups, key = { it.key }) { group ->
                val httpTts = httpTtsForGroup(group, httpTtsList)
                EngineGroupRow(
                    group = group,
                    selected = routeMatchesGroup(currentRoute, group),
                    colors = colors,
                    onClick = { actions.openSpeakerPicker(group) },
                    onLogin = if (!group.loginUrl.isNullOrBlank()) {
                        { actions.login(group) }
                    } else {
                        null
                    },
                    onEdit = httpTts?.let { { actions.editHttpTts(it.id) } },
                    onExport = httpTts?.let { { actions.exportHttpTts(it) } },
                    onDelete = httpTts?.let { { actions.deleteHttpTts(it) } }
                )
            }
        }
    }
    pickerGroupKey?.let { key ->
        SpeechVoiceRoutePickerDialog(
            title = "选择发言人",
            groups = groups,
            currentRoute = currentRoute,
            initialGroupKey = key,
            onDismiss = actions::closeSpeakerPicker,
            onRouteSelected = actions::selectRoute,
            onLogin = actions::login
        )
    }
    if (importDialogVisible) {
        ImportChoiceDialog(
            colors = colors,
            onDismiss = { onImportDialogChange(false) },
            onDefault = {
                onImportDialogChange(false)
                actions.importDefault()
            },
            onLocal = {
                onImportDialogChange(false)
                actions.importLocal()
            },
            onOnline = {
                onImportDialogChange(false)
                actions.importOnline()
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
    colors: SpeakEngineColors,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        MiniAction(action, colors, onAction)
    }
}

@Composable
private fun MiniAction(
    text: String,
    colors: SpeakEngineColors,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(32.dp)
            .clickable(onClick = onClick),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, color = colors.accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PresetTabs(
    selectedId: String,
    colors: SpeakEngineColors,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ReadAloudServicePresets.ttsPresets.forEach { preset ->
            val selected = preset.id == selectedId
            Surface(
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onSelect(preset.id) },
                color = if (selected) colors.page else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                border = if (selected) BorderStroke(1.dp, colors.accent) else null
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        preset.label,
                        color = if (selected) colors.accent else colors.subText,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    colors: SpeakEngineColors,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = colors.subText, fontSize = 12.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) {
                    Text(hint, color = colors.subText.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.text,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = if (password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun rememberSpeechGroups(httpTtsList: List<HttpTTS>): List<SpeechVoiceEngineGroup> {
    val context = LocalContext.current
    return SpeechVoiceCatalogRepository.allGroups(context, httpTtsList)
}

private fun selectedKeyFromEngine(ttsEngine: String?, httpTtsList: List<HttpTTS>): String {
    val current = ttsEngine ?: return "system:"
    if (current.isJsonObject()) {
        val value = GSON.fromJsonObject<SelectItem<String>>(current).getOrNull()?.value.orEmpty()
        return "system:$value"
    }
    return "http:$current".takeIf { httpTtsList.any { item -> item.id.toString() == current } } ?: "system:"
}

private fun httpTtsForGroup(group: SpeechVoiceEngineGroup, httpTtsList: List<HttpTTS>): HttpTTS? {
    val id = group.loginKey.toLongOrNull() ?: return null
    return httpTtsList.firstOrNull { it.id == id }
}

@Composable
private fun EngineTopActions(
    colors: SpeakEngineColors,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallEngineAction("新增", onAdd, colors)
        SmallEngineAction("导入", onImport, colors)
        SmallEngineAction("导出全部", onExportAll, colors)
        SmallEngineAction("清缓存", onClearCache, colors)
    }
}

@Composable
private fun ImportChoiceDialog(
    colors: SpeakEngineColors,
    onDismiss: () -> Unit,
    onDefault: () -> Unit,
    onLocal: () -> Unit,
    onOnline: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            color = colors.page,
            shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
            border = BorderStroke(1.dp, colors.stroke)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "导入朗读规则",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭", color = colors.subText) }
                }
                ImportChoiceRow("默认规则", "导入内置 HTTP TTS 规则", colors, onDefault)
                ImportChoiceRow("本地导入", "从本机 txt/json 文件导入", colors, onLocal)
                ImportChoiceRow("在线导入", "通过 URL 导入朗读规则", colors, onOnline)
            }
        }
    }
}

@Composable
private fun ImportChoiceRow(
    title: String,
    subtitle: String,
    colors: SpeakEngineColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EngineGroupRow(
    group: SpeechVoiceEngineGroup,
    selected: Boolean,
    colors: SpeakEngineColors,
    onClick: () -> Unit,
    onLogin: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) colors.accent.copy(alpha = 0.15f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.title, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(group.subtitle, color = colors.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected) {
                    Text("当前", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val explicitCount = group.options.count { it.explicitSpeaker }
            if (explicitCount > 0 || group.emotions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (explicitCount > 0) {
                        InfoPill("${explicitCount}发言人", colors)
                    }
                    if (group.emotions.isNotEmpty()) {
                        InfoPill("${group.emotions.size}情绪", colors)
                    }
                }
            }
            if (onLogin != null || onEdit != null || onExport != null || onDelete != null) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    onLogin?.let { InlineEngineAction("登录", colors.accent, it) }
                    onEdit?.let { InlineEngineAction("编辑", colors.accent, it) }
                    onExport?.let { InlineEngineAction("导出", colors.accent, it) }
                    onDelete?.let { InlineEngineAction("删除", colors.danger, it) }
                }
            }
        }
    }
}

@Composable
private fun EngineManagementActions(
    compact: Boolean,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions,
    modifier: Modifier = Modifier
) {
    if (!compact) {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallEngineAction("新增", actions::addHttpTts, colors)
            SmallEngineAction("默认规则", actions::importDefault, colors)
            SmallEngineAction("本地导入", actions::importLocal, colors)
            SmallEngineAction("在线导入", actions::importOnline, colors)
            SmallEngineAction("导出全部", actions::exportAll, colors)
            SmallEngineAction("导出当前", actions::exportSelected, colors)
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("管理", color = colors.subText, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactEngineAction("新增", colors, Modifier.weight(1f), actions::addHttpTts)
                CompactEngineAction("默认", colors, Modifier.weight(1f), actions::importDefault)
                CompactEngineAction("在线", colors, Modifier.weight(1f), actions::importOnline)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactEngineAction("本地", colors, Modifier.weight(1f), actions::importLocal)
                CompactEngineAction("导出全部", colors, Modifier.weight(1f), actions::exportAll)
                CompactEngineAction("导出当前", colors, Modifier.weight(1f), actions::exportSelected)
            }
        }
    }
}

@Composable
private fun CompactEngineAction(
    text: String,
    colors: SpeakEngineColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(34.dp).clickable(onClick = onClick),
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EngineDetailCard(
    group: SpeechVoiceEngineGroup?,
    httpTts: HttpTTS?,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        EngineDetail(group = group, httpTts = httpTts, colors = colors, actions = actions)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EngineDetail(
    group: SpeechVoiceEngineGroup?,
    httpTts: HttpTTS?,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions
) {
    if (group == null) {
        Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text("暂无朗读引擎", color = colors.subText, fontSize = 14.sp)
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(group.subtitle, color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!group.loginUrl.isNullOrBlank() || httpTts != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!group.loginUrl.isNullOrBlank()) {
                    DetailEngineAction("登录", colors.accent) { actions.login(group) }
                }
                httpTts?.let {
                    DetailEngineAction("编辑", colors.accent) { actions.editHttpTts(it.id) }
                    DetailEngineAction("删除", colors.danger) { actions.deleteHttpTts(it) }
                }
            }
        }
        Text(
            "发言人列表",
            color = colors.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp, max = 300.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(group.options, key = { it.key }) { option ->
                SpeakerOptionRow(option = option, colors = colors)
            }
        }
        if (group.emotions.isNotEmpty()) {
            Text(
                "情绪",
                color = colors.subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp)
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.emotions.forEach { emotion ->
                    Surface(
                        color = colors.page,
                        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
                        border = BorderStroke(1.dp, colors.stroke)
                    ) {
                        Text(emotion.emotionName, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        Text(
            text = "选择此引擎后，普通朗读会使用该引擎；角色配音可在角色编辑页按发言人单独指定。",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun SpeakerOptionRow(option: SpeechVoiceOption, colors: SpeakEngineColors) {
    Surface(
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(option.speakerName, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    option.groupName.ifBlank { option.engineName },
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (option.toneID.isNotBlank()) {
                Text(
                    option.toneID,
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, colors: SpeakEngineColors) {
    Surface(
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Text(text, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun InlineEngineAction(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(32.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun DetailEngineAction(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(34.dp).clickable(onClick = onClick),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SmallEngineAction(text: String, onClick: () -> Unit, colors: SpeakEngineColors) {
    Surface(
        modifier = Modifier.height(34.dp).clickable(onClick = onClick),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = colors.text, fontSize = 12.sp)
        }
    }
}

private data class SpeakEngineColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color,
    val danger: Color
)

@Composable
private fun rememberSpeakEngineColors(): SpeakEngineColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt())
    val card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt())
    val text = Color(context.primaryTextColor)
    val subText = Color(context.secondaryTextColor)
    val stroke = Color(if (night) 0x26ffffff else 0x18000000)
    return SpeakEngineColors(
        page = page,
        card = card,
        text = text,
        subText = subText,
        stroke = stroke,
        accent = Color(accent),
        danger = Color(ColorUtils.blendColors(0xffff4444.toInt(), accent, 0.08f))
    )
}
