package io.legado.app.help.readaloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudVoiceStyleService
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechVoiceAssigner
import io.legado.app.ui.book.character.compose.DubbingChapterUi
import io.legado.app.ui.book.character.compose.DubbingRoleUi
import io.legado.app.ui.book.character.compose.buildRoleUiList
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配音工作台共享业务：角色加载、音色分析、章节分析、发言人分配。
 * Activity 与朗读面板共用，避免两套逻辑。
 */
object BookDubbingWorkspace {

    data class Snapshot(
        val book: Book?,
        val characterBookKey: String,
        val roles: List<DubbingRoleUi>,
        val chapters: List<DubbingChapterUi>
    )

    suspend fun load(bookUrl: String): Snapshot = withContext(IO) {
        if (bookUrl.isBlank()) {
            return@withContext Snapshot(null, "", emptyList(), emptyList())
        }
        val book = appDb.bookDao.getBook(bookUrl)
        val key = BookCharacterIdentityMigrator.migrate(book)
        val chars = if (key.isBlank()) {
            emptyList()
        } else {
            ensureNarratorEntity(key)
            appDb.bookCharacterDao.characters(key)
        }
        val chapters = book?.let {
            BookDubbingChapterHelper.listChapterStatuses(it).map { st -> DubbingChapterUi(st) }
        }.orEmpty()
        Snapshot(
            book = book,
            characterBookKey = key,
            roles = buildRoleUiList(chars),
            chapters = chapters
        )
    }

    fun ensureNarratorEntity(bookKey: String): BookCharacter {
        if (bookKey.isBlank()) {
            return BookCharacter(name = "旁白")
        }
        appDb.bookCharacterDao.getCharacter(bookKey, "旁白")?.let { return it }
        val now = System.currentTimeMillis()
        val entity = BookCharacter(
            bookUrl = bookKey,
            name = "旁白",
            gender = BookCharacter.GENDER_MALE,
            personality = "男性，约40岁，50%沉稳叙述，30%中性清晰，20%克制不抢戏。",
            roleLevel = BookCharacter.ROLE_IMPORTANT,
            source = "system_narrator",
            sortOrder = -100,
            createdAt = now,
            updatedAt = now
        )
        val id = appDb.bookCharacterDao.insertCharacter(entity)
        return entity.copy(id = id)
    }

    suspend fun ensureCharacterEntity(
        book: Book?,
        character: BookCharacter
    ): BookCharacter = withContext(IO) {
        if (character.id > 0L) {
            return@withContext appDb.bookCharacterDao.getCharacter(character.id) ?: character
        }
        val key = BookCharacterIdentityMigrator.migrate(book)
        if (key.isBlank()) return@withContext character
        if (character.name == "旁白" || character.name.isBlank()) {
            return@withContext ensureNarratorEntity(key)
        }
        appDb.bookCharacterDao.getCharacter(key, character.name)?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val entity = character.copy(
            id = 0L,
            bookUrl = key,
            createdAt = now,
            updatedAt = now
        )
        val id = appDb.bookCharacterDao.insertCharacter(entity)
        entity.copy(id = id)
    }

    suspend fun analyzeRole(
        book: Book?,
        character: BookCharacter,
        overwrite: Boolean = true
    ): AiReadAloudVoiceStyleService.AnalyzeResult {
        val entity = ensureCharacterEntity(book, character)
        if (entity.id <= 0L) {
            return AiReadAloudVoiceStyleService.AnalyzeResult(
                ok = false,
                message = "无法创建角色卡"
            )
        }
        return AiReadAloudVoiceStyleService.analyzeAndSave(
            book = book,
            characterId = entity.id,
            overwrite = overwrite
        )
    }

    suspend fun analyzeAllRoles(
        book: Book?,
        characters: List<BookCharacter>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        var done = 0
        val targets = characters.ifEmpty {
            val key = BookCharacterIdentityMigrator.migrate(book)
            if (key.isBlank()) emptyList() else appDb.bookCharacterDao.characters(key)
        }
        targets.forEach { c ->
            analyzeRole(book, c, overwrite = false)
            done += 1
            onProgress(done, targets.size)
        }
        return done
    }

    suspend fun reassignRole(character: BookCharacter): Boolean = withContext(IO) {
        val entity = if (character.id > 0L) {
            appDb.bookCharacterDao.getCharacter(character.id) ?: character
        } else {
            character
        }
        if (entity.id <= 0L) return@withContext false
        val route = SpeechVoiceAssigner.assignRoute(entity, appDb.httpTTSDao.all)
        if (!route.isConfigured) return@withContext false
        appDb.bookCharacterDao.updateCharacter(
            entity.copy(
                speechRouteJson = route.toJson(),
                updatedAt = System.currentTimeMillis()
            )
        )
        ReadAloudConfigChangeNotifier.notifySpeech()
        true
    }

    suspend fun assignAllVoices(characterBookKey: String): Int = withContext(IO) {
        if (characterBookKey.isBlank()) return@withContext 0
        val http = appDb.httpTTSDao.all
        var count = 0
        appDb.bookCharacterDao.characters(characterBookKey).forEach { c ->
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

    suspend fun analyzeChapter(
        book: Book,
        chapterIndex: Int,
        force: Boolean
    ): BookDubbingChapterHelper.AnalyzeOutcome = withContext(IO) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            ?: return@withContext BookDubbingChapterHelper.AnalyzeOutcome(
                chapterIndex = chapterIndex,
                title = "",
                ok = false,
                status = "failed",
                message = "章节不存在",
                segmentCount = 0
            )
        BookDubbingChapterHelper.analyzeChapter(book, chapter, force = force)
    }

    suspend fun analyzeAllChapters(
        book: Book,
        concurrency: Int,
        onProgress: suspend (done: Int, total: Int, ok: Int) -> Unit
    ): Pair<Int, Int> {
        val list = withContext(IO) { appDb.bookChapterDao.getChapterList(book.bookUrl) }
        if (list.isEmpty()) return 0 to 0
        val limit = concurrency.coerceIn(1, 8)
        val semaphore = Semaphore(limit)
        val counter = AtomicInteger(0)
        val okCounter = AtomicInteger(0)
        coroutineScope {
            list.map { chapter ->
                async(IO) {
                    semaphore.withPermit {
                        val outcome = BookDubbingChapterHelper.analyzeChapter(
                            book,
                            chapter,
                            force = false
                        )
                        val done = counter.incrementAndGet()
                        if (outcome.ok) okCounter.incrementAndGet()
                        onProgress(done, list.size, okCounter.get())
                        outcome
                    }
                }
            }.awaitAll()
        }
        return okCounter.get() to list.size
    }

    suspend fun previewRole(character: BookCharacter): SpeechRoutePreviewPlayer.PreviewResult {
        val entity = if (character.id > 0L) {
            withContext(IO) { appDb.bookCharacterDao.getCharacter(character.id) } ?: character
        } else {
            character
        }
        return SpeechRoutePreviewPlayer.preview(entity)
    }

    fun multiRoleReady(): Boolean {
        return AppConfig.aiReadAloudRoleEnabled && AppConfig.aiReadAloudRoleModelConfig != null
    }
}
