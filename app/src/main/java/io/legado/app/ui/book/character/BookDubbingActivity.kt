package io.legado.app.ui.book.character

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.TTS
import io.legado.app.help.ai.AiReadAloudVoiceStyleService
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.BookDubbingChapterHelper
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.speech.SpeechVoiceAssigner
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.character.compose.BookDubbingScreen
import io.legado.app.ui.book.character.compose.DubbingChapterUi
import io.legado.app.ui.book.character.compose.DubbingRoleUi
import io.legado.app.ui.book.character.compose.buildRoleUiList
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 书级「配音」工作台：角色列表、一键分析音色、章节 AI 分析与状态。
 * 目标交互对齐更直观的多角色听书流程。
 */
class BookDubbingActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    private var bookUrl: String = ""
    private var characterBookKey: String = ""
    private var book: Book? = null
    private var roles by mutableStateOf<List<DubbingRoleUi>>(emptyList())
    private var chapters by mutableStateOf<List<DubbingChapterUi>>(emptyList())
    private var searchQuery by mutableStateOf("")
    private var busyText by mutableStateOf("")
    private var chapterConcurrency by mutableIntStateOf(1)
    private var segmentConcurrency by mutableIntStateOf(AppConfig.aiReadAloudRoleThreadCount.coerceIn(1, 8))
    private var workJob: Job? = null
    private val tts by lazy { TTS() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(EXTRA_BOOK_URL)
            ?: ReadBook.book?.bookUrl
            ?: ""
        book = appDb.bookDao.getBook(bookUrl)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val filtered = if (searchQuery.isBlank()) {
                roles
            } else {
                roles.filter {
                    it.character.displayName().contains(searchQuery, ignoreCase = true) ||
                            it.voiceSummary.contains(searchQuery, ignoreCase = true)
                }
            }
            BookDubbingScreen(
                bookName = book?.name.orEmpty(),
                author = book?.author.orEmpty(),
                roles = filtered,
                chapters = chapters,
                busyText = busyText,
                chapterConcurrency = chapterConcurrency,
                segmentConcurrency = segmentConcurrency,
                onBack = ::finish,
                onOpenSettings = {
                    showDialogFragment<ReadAloudConfigDialog>()
                },
                onAnalyzeAll = ::analyzeAllChapters,
                onDubAll = ::assignAllVoices,
                onOneClickAnalyzeRoles = ::analyzeAllRoles,
                onRefresh = ::load,
                onSearchChange = { searchQuery = it },
                searchQuery = searchQuery,
                onMergeRoles = {
                    toastOnUi("请在角色资料页手动合并同名角色；一键合并后续版本补充")
                    openCharacterManage()
                },
                onDeleteRole = ::deleteRole,
                onAnalyzeRole = ::analyzeRole,
                onPreviewRole = ::previewRole,
                onReassignRole = ::reassignRole,
                onOpenRole = { openCard(it.id) },
                onAnalyzeChapter = ::analyzeOneChapter,
                onOpenChapter = { index ->
                    toastOnUi("章节 #${index + 1} · 可在阅读页听书验证配音")
                },
                onChapterConcurrencyChange = { chapterConcurrency = it },
                onSegmentConcurrencyChange = {
                    segmentConcurrency = it
                    AppConfig.aiReadAloudRoleThreadCount = it
                }
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        tts.stop()
        tts.clearTts()
        workJob?.cancel()
        super.onDestroy()
    }

    private fun load() {
        if (bookUrl.isBlank()) {
            roles = emptyList()
            chapters = emptyList()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                val b = appDb.bookDao.getBook(bookUrl)
                book = b
                val key = BookCharacterIdentityMigrator.migrate(b)
                val chars = if (key.isBlank()) emptyList() else appDb.bookCharacterDao.characters(key)
                val chapterUi = b?.let {
                    BookDubbingChapterHelper.listChapterStatuses(it).map { st -> DubbingChapterUi(st) }
                }.orEmpty()
                Triple(key, buildRoleUiList(ensureNarrator(chars, key)), chapterUi)
            }
            characterBookKey = data.first
            roles = data.second
            chapters = data.third
        }
    }

    private fun ensureNarrator(chars: List<BookCharacter>, bookKey: String): List<BookCharacter> {
        if (bookKey.isBlank()) return chars
        if (chars.any { it.name == "旁白" }) return chars
        // 展示层不落库；真实旁白在分析流程中按默认路由处理
        return listOf(
            BookCharacter(
                bookUrl = bookKey,
                name = "旁白",
                gender = BookCharacter.GENDER_MALE,
                personality = "男性，约40岁，50%沉稳叙述，30%中性清晰，20%克制不抢戏。",
                roleLevel = BookCharacter.ROLE_IMPORTANT
            )
        ) + chars
    }

    private fun openCharacterManage() {
        if (bookUrl.isBlank()) return
        startActivity<BookCharacterManageActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl)
        }
    }

    private fun openCard(id: Long) {
        if (id <= 0L) {
            toastOnUi("旁白为系统角色，请直接分析或重配音")
            return
        }
        startActivity<BookCharacterCardActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl)
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, id)
        }
    }

    private fun deleteRole(character: BookCharacter) {
        if (character.id <= 0L || character.name == "旁白") {
            toastOnUi("系统旁白不可删除")
            return
        }
        alert("删除角色") {
            setMessage("确定删除「${character.displayName()}」？")
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookCharacterDao.deleteCharacterWithRelations(character)
                    }
                    ReadAloudConfigChangeNotifier.notifySpeech()
                    load()
                }
            }
            noButton()
        }
    }

    private fun analyzeRole(character: BookCharacter) {
        val b = book ?: return toastOnUi("书籍不存在")
        if (character.id <= 0L) {
            toastOnUi("请先通过章节分析创建实体角色卡")
            return
        }
        runWork("正在分析「${character.displayName()}」音色…") { _ ->
            val result = AiReadAloudVoiceStyleService.analyzeAndSave(b, character.id, overwrite = true)
            if (result.ok) toastOnUi("已更新：${result.style.take(40)}")
            else toastOnUi(result.message.ifBlank { "分析失败" })
            load()
        }
    }

    private fun analyzeAllRoles() {
        val b = book ?: return toastOnUi("书籍不存在")
        val targets = roles.map { it.character }.filter { it.id > 0 }
        if (targets.isEmpty()) {
            toastOnUi("没有可分析的角色卡")
            return
        }
        runWork("一键分析角色音色 0/${targets.size}") { updateBusy ->
            var done = 0
            targets.forEach { c ->
                AiReadAloudVoiceStyleService.analyzeAndSave(b, c.id, overwrite = false)
                done += 1
                updateBusy("一键分析角色音色 $done/${targets.size}")
            }
            toastOnUi("音色分析完成 $done 个")
            load()
        }
    }

    private fun previewRole(character: BookCharacter) {
        val text = AiReadAloudVoiceStyleService.previewSentence(character)
        tts.speak(text)
        toastOnUi("试听：${character.displayName()}")
    }

    private fun reassignRole(character: BookCharacter) {
        if (character.id <= 0L) {
            toastOnUi("旁白请在发言人管理中配置默认引擎")
            return
        }
        runWork("重新分配「${character.displayName()}」发言人…") { _ ->
            val ok = withContext(IO) {
                val http = appDb.httpTTSDao.all
                val route = SpeechVoiceAssigner.assignRoute(character, http)
                if (!route.isConfigured) return@withContext false
                appDb.bookCharacterDao.updateCharacter(
                    character.copy(
                        speechRouteJson = route.toJson(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                ReadAloudConfigChangeNotifier.notifySpeech()
                true
            }
            toastOnUi(if (ok) "已重配音" else "没有可用发言人")
            load()
        }
    }

    private fun assignAllVoices() {
        runWork("批量分配未配置发言人…") { _ ->
            val n = withContext(IO) {
                val http = appDb.httpTTSDao.all
                val list = appDb.bookCharacterDao.characters(characterBookKey)
                var count = 0
                list.forEach { c ->
                    if (c.speechRouteJson.isNotBlank()) return@forEach
                    val route = SpeechVoiceAssigner.assignRoute(c, http)
                    if (route.isConfigured) {
                        appDb.bookCharacterDao.updateCharacter(
                            c.copy(
                                speechRouteJson = route.toJson(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        count += 1
                    }
                }
                if (count > 0) ReadAloudConfigChangeNotifier.notifySpeech()
                count
            }
            toastOnUi(if (n > 0) "已为 $n 个角色分配发言人" else "没有需要分配的角色")
            load()
        }
    }

    private fun analyzeOneChapter(index: Int) {
        val b = book ?: return toastOnUi("书籍不存在")
        if (!AppConfig.aiReadAloudRoleEnabled) {
            toastOnUi("请先在设置中开启多角色")
            showDialogFragment<ReadAloudConfigDialog>()
            return
        }
        runWork("分析章节 #${index + 1}…") { _ ->
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(b.bookUrl, index)
            }
            if (chapter == null) {
                toastOnUi("章节不存在")
                return@runWork
            }
            val outcome = BookDubbingChapterHelper.analyzeChapter(b, chapter, force = true)
            toastOnUi(
                if (outcome.ok) "「${outcome.title}」分析完成 · ${outcome.segmentCount} 段"
                else outcome.message
            )
            load()
        }
    }

    private fun analyzeAllChapters() {
        val b = book ?: return toastOnUi("书籍不存在")
        if (!AppConfig.aiReadAloudRoleEnabled) {
            toastOnUi("请先在设置中开启多角色")
            showDialogFragment<ReadAloudConfigDialog>()
            return
        }
        runWork("全部 AI 分析…") { updateBusy ->
            val list = withContext(IO) { appDb.bookChapterDao.getChapterList(b.bookUrl) }
            if (list.isEmpty()) {
                toastOnUi("没有章节")
                return@runWork
            }
            val limit = chapterConcurrency.coerceIn(1, 8)
            val semaphore = Semaphore(limit)
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val okCounter = java.util.concurrent.atomic.AtomicInteger(0)
            coroutineScope {
                list.map { chapter ->
                    async(IO) {
                        semaphore.withPermit {
                            val outcome = BookDubbingChapterHelper.analyzeChapter(b, chapter, force = false)
                            val done = counter.incrementAndGet()
                            if (outcome.ok) okCounter.incrementAndGet()
                            updateBusy("全部 AI 分析 $done/${list.size} · 成功 ${okCounter.get()}")
                            outcome
                        }
                    }
                }.awaitAll()
            }
            toastOnUi("分析结束：成功 ${okCounter.get()} / ${list.size}")
            load()
        }
    }

    private fun runWork(
        initial: String,
        block: suspend (updateBusy: suspend (String) -> Unit) -> Unit
    ) {
        if (workJob?.isActive == true) {
            toastOnUi("已有任务进行中")
            return
        }
        busyText = initial
        workJob = lifecycleScope.launch {
            try {
                block { msg -> busyText = msg }
            } catch (t: Throwable) {
                toastOnUi(t.localizedMessage ?: "任务失败")
            } finally {
                busyText = ""
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
    }
}
