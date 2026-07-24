package io.legado.app.help.readaloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.entities.ReadAloudTextCleaner
import io.legado.app.ui.book.read.page.entities.TextChapter
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 书级配音页：加载章节正文并触发多角色分析（不依赖完整排版）。
 */
object BookDubbingChapterHelper {

    data class ChapterDubStatus(
        val chapterIndex: Int,
        val title: String,
        val analyzed: Boolean,
        val status: String,
        val segmentCount: Int,
        val lastError: String
    )

    data class AnalyzeOutcome(
        val chapterIndex: Int,
        val title: String,
        val ok: Boolean,
        val status: String,
        val message: String,
        val segmentCount: Int
    )

    fun listChapterStatuses(book: Book): List<ChapterDubStatus> {
        val bookKey = BookCharacterIdentityMigrator.migrate(book).ifBlank { book.characterBookKey() }
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val caches = appDb.aiReadAloudRoleCacheDao.listByBook(bookKey)
            .groupBy { it.chapterIndex }
        return chapters.map { chapter ->
            val latest = caches[chapter.index]
                ?.maxByOrNull { it.updatedAt }
            val segmentCount = latest?.segmentsJson
                ?.takeIf { it.isNotBlank() }
                ?.let { countSegments(it) }
                ?: 0
            val success = latest != null &&
                    latest.status == AiReadAloudRoleCache.STATUS_SUCCESS &&
                    segmentCount > 0
            ChapterDubStatus(
                chapterIndex = chapter.index,
                title = chapter.title,
                analyzed = success,
                status = latest?.status.orEmpty(),
                segmentCount = segmentCount,
                lastError = latest?.lastError.orEmpty()
            )
        }
    }

    suspend fun loadParagraphs(book: Book, chapter: BookChapter): List<String> = withContext(IO) {
        val raw = BookHelp.getContent(book, chapter)
            ?: runCatching {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                    ?: return@runCatching null
                WebBook.getContentAwait(source, book, chapter)
            }.getOrNull()
            ?: return@withContext emptyList()
        splitParagraphs(raw)
    }

    fun splitParagraphs(raw: String): List<String> {
        return raw
            .lineSequence()
            .map { ReadAloudTextCleaner.cleanVisibleText(it, keepLineBreaks = false) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun textChapterFor(book: Book, chapter: BookChapter): TextChapter {
        val size = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        return TextChapter(
            chapter = chapter,
            position = chapter.index,
            title = chapter.title,
            chaptersSize = size,
            sameTitleRemoved = false,
            isVip = chapter.isVip,
            isPay = chapter.isPay,
            effectiveReplaceRules = null
        ).apply { isCompleted = true }
    }

    suspend fun analyzeChapter(
        book: Book,
        chapter: BookChapter,
        force: Boolean = false
    ): AnalyzeOutcome = withContext(IO) {
        if (!AppConfig.aiReadAloudRoleEnabled) {
            return@withContext AnalyzeOutcome(
                chapterIndex = chapter.index,
                title = chapter.title,
                ok = false,
                status = AiReadAloudRoleState.STATUS_SKIPPED,
                message = "请先开启多角色",
                segmentCount = 0
            )
        }
        if (force) {
            val bookKey = BookCharacterIdentityMigrator.migrate(book)
            AiReadAloudRoleService.clearChapterCache(bookKey, chapter.index)
            AiReadAloudRoleService.clearChapterCache(book.bookUrl, chapter.index)
        }
        val paragraphs = loadParagraphs(book, chapter)
        if (paragraphs.isEmpty()) {
            return@withContext AnalyzeOutcome(
                chapterIndex = chapter.index,
                title = chapter.title,
                ok = false,
                status = AiReadAloudRoleState.STATUS_FAILED,
                message = "章节正文为空或未缓存",
                segmentCount = 0
            )
        }
        val textChapter = textChapterFor(book, chapter)
        val retries = AppConfig.aiReadAloudFailRetryCount
        var last = AiReadAloudRoleService.EnsureResult(
            status = AiReadAloudRoleState.STATUS_FAILED,
            error = "分析失败"
        )
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                delay(AppConfig.aiReadAloudRetryBackoffMillis(attempt))
            }
            last = AiReadAloudRoleService.ensureCache(
                book = book,
                textChapter = textChapter,
                paragraphs = paragraphs,
                stage = AiReadAloudRoleState.STAGE_CURRENT
            )
            val ok = last.status == AiReadAloudRoleState.STATUS_SUCCESS ||
                    (last.status == AiReadAloudRoleState.STATUS_SKIPPED && last.segmentCount > 0)
            if (ok || last.status == AiReadAloudRoleState.STATUS_RUNNING) {
                // RUNNING 时服务内部会写缓存，稍等再读
                if (last.status == AiReadAloudRoleState.STATUS_RUNNING) {
                    delay(800)
                    val bookKey = book.characterBookKey()
                    val cache = appDb.aiReadAloudRoleCacheDao.latestUsableByChapter(
                        bookKey,
                        chapter.index
                    ) ?: appDb.aiReadAloudRoleCacheDao.latestUsableByChapter(
                        book.bookUrl,
                        chapter.index
                    )
                    if (cache != null && cache.segmentsJson.isNotBlank()) {
                        return@withContext AnalyzeOutcome(
                            chapterIndex = chapter.index,
                            title = chapter.title,
                            ok = true,
                            status = cache.status,
                            message = "分析完成",
                            segmentCount = countSegments(cache.segmentsJson)
                        )
                    }
                } else if (ok) {
                    return@withContext AnalyzeOutcome(
                        chapterIndex = chapter.index,
                        title = chapter.title,
                        ok = true,
                        status = last.status,
                        message = last.message.ifBlank { "分析完成" },
                        segmentCount = last.segmentCount
                    )
                }
            }
            val err = last.error.ifBlank { last.message }
            if (err.contains("401") || err.contains("403")) {
                return@withContext AnalyzeOutcome(
                    chapterIndex = chapter.index,
                    title = chapter.title,
                    ok = false,
                    status = last.status,
                    message = err.ifBlank { "认证失败" },
                    segmentCount = last.segmentCount
                )
            }
        }
        AnalyzeOutcome(
            chapterIndex = chapter.index,
            title = chapter.title,
            ok = false,
            status = last.status,
            message = last.error.ifBlank { last.message }.ifBlank { "分析失败" },
            segmentCount = last.segmentCount
        )
    }

    private fun countSegments(json: String): Int {
        return runCatching {
            val arr = org.json.JSONArray(json)
            arr.length()
        }.getOrDefault(0)
    }
}
