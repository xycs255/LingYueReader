package com.xycs.lingyuereader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookParser
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Bookmark
import com.xycs.lingyuereader.data.MarkColor
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Note
import com.xycs.lingyuereader.data.displayText
import com.xycs.lingyuereader.dict.DictSettings
import com.xycs.lingyuereader.dict.Dictionaries
import com.xycs.lingyuereader.dict.LookupResult
import com.xycs.lingyuereader.dict.WordFinder
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.TextRules
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.ReadingStats
import com.xycs.lingyuereader.data.HtmlText
import com.xycs.lingyuereader.data.VocabWord
import com.xycs.lingyuereader.data.Vocabulary
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReadingProgress
import com.xycs.lingyuereader.data.SearchHit
import com.xycs.lingyuereader.data.ShelfPrefs
import com.xycs.lingyuereader.data.ShelfSort
import com.xycs.lingyuereader.tts.ReadAloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Shelf : Screen
    /** [chapterIndex] 为空时从上次读到的位置开始。 */
    data class Reader(
        val bookId: String,
        val chapterIndex: Int? = null,
        val blockIndex: Int? = null,
        /** 翻页模式下从上一章翻回来时，停在该章最后一页。 */
        val fromEnd: Boolean = false,
    ) : Screen
    data class Search(val bookId: String) : Screen
    data class Detail(val bookId: String) : Screen
    data object Dictionaries : Screen
    data object Vocabulary : Screen
    data object Stats : Screen
    data class BookRules(val bookId: String) : Screen
}

/** 阅读页里正在查的词：在哪一块的哪一段（[range] 为空表示从相关词语、链接跳来的，不在正文里）。 */
data class Lookup(
    val chapterIndex: Int,
    val block: Int,
    val range: IntRange?,
    val word: String,
    val result: LookupResult? = null,
    /** 词在书里的原句（从正文选的才有），收进生词本时用。 */
    val context: String = "",
)

/**
 * 正文里划选的文字（只在一段之内）。[anchor] 是长按选出的词，拖动时选区总包含它；
 * [dragging] 为真表示手指还没松开。
 */
data class TextSelection(
    val chapterIndex: Int,
    val block: Int,
    val range: IntRange,
    val anchor: IntRange,
    val dragging: Boolean,
    /** 长按处是不是一个能查的词（标点、空白不是）。 */
    val anchorIsWord: Boolean,
)

/** 正在读的书：书架条目加上解析好的正文。 */
data class OpenBook(val entry: BookEntry, val content: BookContent)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val library = Library(app)
    private val vocabulary = Vocabulary(java.io.File(app.filesDir, "vocabulary.json"))
    private val stats = ReadingStats(java.io.File(app.filesDir, "stats.json"))
    private val _stats = MutableStateFlow<Map<java.time.LocalDate, DayStats>>(emptyMap())
    val readingStats: StateFlow<Map<java.time.LocalDate, DayStats>> = _stats.asStateFlow()
    private val _vocab = MutableStateFlow<List<VocabWord>>(emptyList())
    val vocab: StateFlow<List<VocabWord>> = _vocab.asStateFlow()
    private val dictionaries = Dictionaries(app)

    private val _dictSettings = MutableStateFlow(DictSettings())
    val dictSettings: StateFlow<DictSettings> = _dictSettings.asStateFlow()

    private val _lookup = MutableStateFlow<Lookup?>(null)
    val lookup: StateFlow<Lookup?> = _lookup.asStateFlow()
    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()
    private var pressJob: Job? = null
    private val prefs = Prefs(app)

    private val _shelf = MutableStateFlow<List<BookEntry>>(emptyList())
    val shelf: StateFlow<List<BookEntry>> = _shelf.asStateFlow()

    private val _shelfPrefs = MutableStateFlow(prefs.shelf())
    val shelfPrefs: StateFlow<ShelfPrefs> = _shelfPrefs.asStateFlow()

    private val _shelfQuery = MutableStateFlow("")
    val shelfQuery: StateFlow<String> = _shelfQuery.asStateFlow()

    /** 书架上实际显示的书：按搜索词过滤、按所选方式排序。 */
    val visibleShelf: StateFlow<List<BookEntry>> = combine(_shelf, _shelfPrefs, _shelfQuery) { books, p, q ->
        val query = q.trim()
        val filtered = if (query.isEmpty()) books else books.filter {
            it.title.contains(query, ignoreCase = true) || it.author?.contains(query, ignoreCase = true) == true
        }
        when (p.sort) {
            ShelfSort.RECENT -> filtered.sortedByDescending { maxOf(it.openedAt, it.addedAt) }
            ShelfSort.ADDED -> filtered.sortedByDescending { it.addedAt }
            ShelfSort.TITLE -> {
                val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
                filtered.sortedWith { a, b -> collator.compare(a.title, b.title) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _shelfLoaded = MutableStateFlow(false)
    val shelfLoaded: StateFlow<Boolean> = _shelfLoaded.asStateFlow()

    private val _openBook = MutableStateFlow<OpenBook?>(null)
    val openBook: StateFlow<OpenBook?> = _openBook.asStateFlow()

    /** 当前打开的书的书签与批注。 */
    private val _marks = MutableStateFlow(Marks())
    val marks: StateFlow<Marks> = _marks.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 一次选了多个文件时，先问是分别导入还是合成一本。 */
    private val _pendingImport = MutableStateFlow<List<Library.Picked>?>(null)
    val pendingImport: StateFlow<List<Library.Picked>?> = _pendingImport.asStateFlow()

    private val _settings = MutableStateFlow(prefs.settings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Shelf))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    init {
        viewModelScope.launch {
            loadShelf()
            _shelfLoaded.value = true
        }
        viewModelScope.launch { _dictSettings.value = withContext(Dispatchers.IO) { dictionaries.settings() } }
        reloadVocabulary()
        reloadStats()
        // 听书时跟着朗读位置记进度，锁屏听完再打开也能接着读。
        viewModelScope.launch {
            ReadAloud.state.collect { s ->
                val u = s?.current ?: return@collect
                val book = _openBook.value?.takeIf { it.entry.id == s.bookId } ?: return@collect
                val block = book.content.chapters.getOrNull(s.chapterIndex)?.blocks?.getOrNull(u.block)
                val indent = if (block is Block.Para && block.prose) 2 else 0
                saveProgress(s.chapterIndex, u.block + 1, 0, u.start + indent)
            }
        }
    }

    /** 从当前阅读位置开始听书。 */
    fun startReadAloud(chapterIndex: Int, block: Int, char: Int) {
        val book = _openBook.value ?: return
        ReadAloud.start(
            getApplication(), book.entry.id, book.entry.title, book.content,
            chapterIndex, block.coerceAtLeast(0), char, _settings.value.ttsRate,
        )
    }

    fun setReadAloudRate(rate: Float) {
        updateSettings { it.copy(ttsRate = rate) }
        ReadAloud.setRate(rate)
    }

    private suspend fun loadShelf() {
        _shelf.value = withContext(Dispatchers.IO) { library.list() }
    }

    fun setShelfQuery(query: String) {
        _shelfQuery.value = query
    }

    fun updateShelfPrefs(transform: (ShelfPrefs) -> ShelfPrefs) {
        _shelfPrefs.update(transform)
        prefs.saveShelf(_shelfPrefs.value)
    }

    /** 阅读页在前台的时长，离开或切到后台时累加。 */
    fun reloadStats() {
        viewModelScope.launch { _stats.value = withContext(Dispatchers.IO) { stats.days() } }
    }

    private var pendingChars = 0L

    /** 顺着往下读过的字数（阅读页按位置变化估算）。先攒着，离开阅读页或攒够了再写文件。 */
    fun addReadChars(chars: Long) {
        if (chars <= 0) return
        pendingChars += chars
        if (pendingChars >= 5_000) flushReadChars()
    }

    private fun flushReadChars() {
        val chars = pendingChars
        pendingChars = 0
        if (chars <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            stats.addChars(chars)
            _stats.value = stats.days()
        }
    }

    fun addReadingTime(bookId: String, millis: Long) {
        flushReadChars()
        if (millis < 1_000) return
        _shelf.update { list -> list.map { if (it.id == bookId) it.copy(readMillis = it.readMillis + millis) else it } }
        viewModelScope.launch(Dispatchers.IO) {
            library.addReadingTime(bookId, millis)
            stats.addTime(bookId, millis)
            _stats.value = stats.days()
        }
    }

    fun pick(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { library.describe(uris) }
            val (ok, unsupported) = picked.partition { BookParser.isSupported(it.name) }
            if (unsupported.isNotEmpty()) _messages.value = "暂不支持：${unsupported.joinToString("、") { it.name }}"
            when {
                ok.size == 1 -> importBooks(ok, merge = false, title = null)
                ok.size > 1 -> _pendingImport.value = ok
            }
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun importBooks(files: List<Library.Picked>, merge: Boolean, title: String?) {
        _pendingImport.value = null
        viewModelScope.launch {
            _busy.value = true
            val groups = if (merge) listOf(files) else files.map { listOf(it) }
            val failed = mutableListOf<String>()
            var added = 0
            withContext(Dispatchers.IO) {
                groups.forEach { group ->
                    runCatching { library.add(group, if (merge) title else null) }
                        .onSuccess { added++ }
                        .onFailure { failed += group.first().name + "（${it.message ?: "读取失败"}）" }
                }
            }
            loadShelf()
            _busy.value = false
            _messages.value = buildString {
                if (added > 0) append("已加入 $added 本书")
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("导入失败：${failed.joinToString("、")}")
                }
            }
        }
    }

    fun refresh(entry: BookEntry) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { library.refresh(entry) } }
            loadShelf()
            if (_openBook.value?.entry?.id == entry.id) _openBook.value = null
            _busy.value = false
            _messages.value = result.fold(
                onSuccess = {
                    when {
                        it.failed > 0 -> "有 ${it.failed} 份文件无法从原位置读取（授权可能已失效），请重新导入"
                        it.changed == 0 -> "已是最新"
                        else -> "已更新 ${it.changed} 份文件"
                    }
                },
                onFailure = { "刷新失败：${it.message}" },
            )
        }
    }

    // ---------- 净化与分章规则 ----------

    suspend fun bookRules(id: String): TextRules = withContext(Dispatchers.IO) { library.rules(id) }

    /** 来源文件全文（净化规则预览用）。 */
    suspend fun bookSourceText(entry: BookEntry): String = withContext(Dispatchers.IO) { library.sourceTexts(entry).joinToString("\n") }

    /** 按 [rules] 试分章，返回章名列表。 */
    suspend fun previewChapters(entry: BookEntry, rules: TextRules): List<String> =
        withContext(Dispatchers.Default) { library.previewWith(entry, rules).chapters.map { it.title } }

    fun saveRules(entry: BookEntry, rules: TextRules) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { runCatching { library.saveRules(entry, rules) } }
            loadShelf()
            if (_openBook.value?.entry?.id == entry.id) _openBook.value = null
            _busy.value = false
            _messages.value = result.fold({ "已按新规则重新整理：${it.chapters.size} 章" }, { "保存失败：${it.message}" })
            if (result.isSuccess) back()
        }
    }

    fun rename(entry: BookEntry, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.rename(entry.id, title.trim()) }
            loadShelf()
        }
    }

    fun remove(entry: BookEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.remove(entry.id) }
            if (_openBook.value?.entry?.id == entry.id) _openBook.value = null
            loadShelf()
        }
    }

    /** 阅读页进来时调用；已加载的同一本书不重复解析。 */
    fun ensureLoaded(bookId: String) {
        if (_openBook.value?.entry?.id == bookId) return
        val entry = _shelf.value.firstOrNull { it.id == bookId } ?: return
        _openBook.value = null
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { runCatching { library.content(entry) } }
            content.onSuccess {
                _marks.value = withContext(Dispatchers.IO) { library.marks(entry.id).resolve(it) }
                _openBook.value = OpenBook(entry, it)
            }
                .onFailure {
                    _messages.value = "打不开《${entry.title}》：${it.message}"
                    back()
                }
        }
    }

    fun saveProgress(chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int = 0) {
        val book = _openBook.value ?: return
        val chapter = book.content.chapters.getOrNull(chapterIndex) ?: return
        val progress = ReadingProgress(chapterIndex, chapter.title, itemIndex, itemOffset, charOffset)
        if (book.entry.progress == progress) return
        _openBook.value = book.copy(entry = book.entry.copy(progress = progress))
        _shelf.update { list -> list.map { if (it.id == book.entry.id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it } }
        viewModelScope.launch(Dispatchers.IO) { library.saveProgress(book.entry.id, progress) }
    }

    private fun updateMarks(transform: (Marks) -> Marks) {
        val book = _openBook.value ?: return
        _marks.update(transform)
        val snapshot = _marks.value
        viewModelScope.launch(Dispatchers.IO) { library.saveMarks(book.entry.id, snapshot) }
    }

    /** 在某块的某个位置加或删书签（同一块已有书签时删除）。 */
    fun toggleBookmark(chapterIndex: Int, blockIndex: Int, charOffset: Int) {
        val book = _openBook.value ?: return
        val chapter = book.content.chapters.getOrNull(chapterIndex) ?: return
        val itemIndex = blockIndex + 1
        val existing = _marks.value.bookmarks.filter { it.chapterIndex == chapterIndex && it.itemIndex == itemIndex }
        if (existing.isNotEmpty()) {
            updateMarks { m -> m.copy(bookmarks = m.bookmarks - existing.toSet()) }
            _messages.value = "已删除书签"
            return
        }
        val excerpt = chapter.blocks.getOrNull(blockIndex)?.displayText?.trim()?.take(40) ?: chapter.title
        val bookmark = Bookmark(UUID.randomUUID().toString(), chapterIndex, chapter.title, itemIndex, charOffset, excerpt, System.currentTimeMillis())
        updateMarks { m -> m.copy(bookmarks = m.bookmarks + bookmark) }
        _messages.value = "已加书签"
    }

    fun deleteBookmark(id: String) = updateMarks { m -> m.copy(bookmarks = m.bookmarks.filterNot { it.id == id }) }

    fun addNote(chapterIndex: Int, blockIndex: Int, start: Int, end: Int, text: String, color: MarkColor) {
        val book = _openBook.value ?: return
        val chapter = book.content.chapters.getOrNull(chapterIndex) ?: return
        val shown = chapter.blocks.getOrNull(blockIndex)?.displayText ?: return
        val s = start.coerceIn(0, shown.length)
        val e = end.coerceIn(s, shown.length)
        val (from, to) = if (s == e) 0 to shown.length else s to e
        val note = Note(UUID.randomUUID().toString(), chapterIndex, chapter.title, blockIndex, from, to, shown.substring(from, to), text.trim(), color, System.currentTimeMillis())
        updateMarks { m -> m.copy(notes = m.notes + note) }
    }

    fun deleteNote(id: String) = updateMarks { m -> m.copy(notes = m.notes.filterNot { it.id == id }) }

    fun bookFile(id: String, name: String): java.io.File = library.bookFile(id, name)

    // ---------- 查词 ----------

    private fun blockText(chapterIndex: Int, block: Int): String? =
        _openBook.value?.content?.chapters?.getOrNull(chapterIndex)?.blocks?.getOrNull(block)?.displayText

    /** 长按正文某个字：先选出它所在的词；松手前接着拖就扩大选区。 */
    fun pressText(chapterIndex: Int, block: Int, offset: Int) {
        _lookup.value = null
        // 当前章已在内存里，先同步选中按住的字，免得找词期间的拖动落空。
        val text = blockText(chapterIndex, block)?.takeIf { it.isNotEmpty() } ?: return
        val at = offset.coerceIn(0, text.lastIndex)
        val pressed = TextSelection(chapterIndex, block, at..at, at..at, dragging = true, anchorIsWord = false)
        _selection.value = pressed
        pressJob = viewModelScope.launch {
            val word = withContext(Dispatchers.IO) {
                runCatching { WordFinder.wordAt(text, at, dictionaries::isWord) }.getOrNull()
            } ?: return@launch
            _selection.update { sel ->
                if (sel == null || sel.anchor != pressed.anchor || sel.block != block || !sel.dragging) return@update sel
                sel.copy(
                    range = minOf(sel.range.first, word.first)..maxOf(sel.range.last, word.last),
                    anchor = word,
                    anchorIsWord = true,
                )
            }
        }
    }

    /** 长按后拖到 [offset]：选区从长按的词伸到这个字（同一段之内）。 */
    fun dragText(block: Int, offset: Int) {
        _selection.update { sel ->
            if (sel == null || sel.block != block) return@update sel
            val len = blockText(sel.chapterIndex, block)?.length ?: return@update sel
            val o = offset.coerceIn(0, len - 1)
            sel.copy(range = minOf(sel.anchor.first, o)..maxOf(sel.anchor.last, o))
        }
    }

    /** 松手：没拖动就直接查长按的词，拖过了就留着选区（显示把手和操作条）。 */
    fun releaseText() {
        val job = pressJob
        viewModelScope.launch {
            job?.join()
            val sel = _selection.value ?: return@launch
            if (!sel.dragging) return@launch
            if (sel.range == sel.anchor) {
                _selection.value = null
                if (sel.anchorIsWord) lookupRange(sel.chapterIndex, sel.block, sel.range)
            } else {
                _selection.value = sel.copy(dragging = false)
            }
        }
    }

    /** 拖动选区的起点或终点把手到光标位置 [cursor]。 */
    fun moveSelectionHandle(block: Int, start: Boolean, cursor: Int) {
        _selection.update { sel ->
            if (sel == null || sel.block != block) return@update sel
            val len = blockText(sel.chapterIndex, block)?.length ?: return@update sel
            val range = if (start) {
                cursor.coerceIn(0, sel.range.last)..sel.range.last
            } else {
                sel.range.first..(cursor - 1).coerceIn(sel.range.first, len - 1)
            }
            sel.copy(range = range, anchor = range)
        }
    }

    fun selectionText(): String? = _selection.value?.let { sel ->
        blockText(sel.chapterIndex, sel.block)?.let { t -> t.substring(sel.range.first, minOf(sel.range.last + 1, t.length)) }
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** 查划选的文字。 */
    fun lookupSelection() {
        val sel = _selection.value ?: return
        _selection.value = null
        lookupRange(sel.chapterIndex, sel.block, sel.range)
    }

    private fun lookupRange(chapterIndex: Int, block: Int, range: IntRange) {
        val text = blockText(chapterIndex, block) ?: return
        if (range.last >= text.length) return
        showLookup(
            Lookup(chapterIndex, block, range, text.substring(range.first, range.last + 1).trim(), context = Vocabulary.contextOf(text, range)),
        )
    }

    /** 手动调整选词范围：左右各伸缩一个字。 */
    fun adjustSelection(startDelta: Int, endDelta: Int) {
        val current = _lookup.value ?: return
        val range = current.range ?: return
        val book = _openBook.value ?: return
        val text = book.content.chapters.getOrNull(current.chapterIndex)?.blocks?.getOrNull(current.block)?.displayText ?: return
        val start = (range.first + startDelta).coerceIn(0, text.lastIndex)
        val end = (range.last + endDelta).coerceIn(start, text.lastIndex)
        if (start == range.first && end == range.last) return
        showLookup(current.copy(range = start..end, word = text.substring(start, end + 1), result = null))
    }

    /** 查另一个词（相关词语、词典里的链接）：不再对应正文里的选区。 */
    fun lookupWord(word: String) {
        val current = _lookup.value ?: return
        if (word.isBlank()) return
        showLookup(current.copy(word = word, range = null, result = null))
    }

    private fun showLookup(l: Lookup) {
        _lookup.value = l
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { dictionaries.lookup(l.word) }.getOrNull() }
            _lookup.update { if (it?.word == l.word) it.copy(result = result ?: LookupResult(l.word, emptyList(), emptyList(), emptyList(), emptyList())) else it }
        }
    }

    // ---------- 生词本 ----------

    fun reloadVocabulary() {
        viewModelScope.launch { _vocab.value = withContext(Dispatchers.IO) { vocabulary.list() } }
    }

    /** 把查词面板里的词收进生词本：释义摘要取内置词典或导入词典的第一条。 */
    fun addToVocabulary() {
        val l = _lookup.value ?: return
        val book = _openBook.value
        val r = l.result
        val word = r?.words?.firstOrNull()
        val char = r?.chars?.firstOrNull()?.takeIf { l.word.codePointCount(0, l.word.length) == 1 }
        val reading = word?.pinyin ?: char?.readings?.firstOrNull() ?: ""
        val gloss = when {
            word != null -> word.definitions.take(3).joinToString("；")
            char != null && char.definition.isNotBlank() -> char.definition
            else -> r?.imported?.firstOrNull()?.second?.firstOrNull()?.html?.let { HtmlText.plain(it).take(120) } ?: ""
        }
        val entry = VocabWord(
            word = l.word,
            reading = reading,
            gloss = gloss,
            context = l.context,
            bookId = book?.entry?.id ?: "",
            bookTitle = book?.entry?.title ?: "",
            addedAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            _vocab.value = withContext(Dispatchers.IO) { vocabulary.add(entry); vocabulary.list() }
            _messages.value = "已收入生词本"
        }
    }

    fun removeFromVocabulary(word: String) {
        viewModelScope.launch { _vocab.value = withContext(Dispatchers.IO) { vocabulary.remove(word); vocabulary.list() } }
    }

    fun setMastered(word: String, mastered: Boolean) {
        viewModelScope.launch { _vocab.value = withContext(Dispatchers.IO) { vocabulary.setMastered(word, mastered); vocabulary.list() } }
    }

    fun vocabularyMarkdown(words: List<VocabWord>): String = vocabulary.toMarkdown(words)

    fun closeLookup() {
        _lookup.value = null
    }

    fun dictResource(dictId: String, path: String): ByteArray? = dictionaries.resource(dictId, path)

    fun dictStyles(dictId: String): List<String> = dictionaries.looseStyles(dictId)

    // ---------- 词典管理 ----------

    fun reloadDicts() {
        viewModelScope.launch { _dictSettings.value = withContext(Dispatchers.IO) { dictionaries.settings() } }
    }

    fun importDictionaries(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    dictionaries.import(library.describe(uris)) { p -> resolver.openInputStream(p.uri)!! }
                }
            }
            _busy.value = false
            reloadDicts()
            _messages.value = result.fold(
                { r ->
                    buildString {
                        if (r.added.isNotEmpty()) append("已导入：${r.added.joinToString("、")}")
                        if (r.failed.isNotEmpty()) {
                            if (isNotEmpty()) append("；")
                            append("导入失败：${r.failed.joinToString("、")}")
                        }
                    }
                },
                { "导入失败：${it.message}" },
            )
        }
    }

    fun setBuiltinDictEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { dictionaries.setBuiltinEnabled(enabled); reloadDicts() }
    }

    fun setDictEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { dictionaries.setEnabled(id, enabled); reloadDicts() }
    }

    fun moveDict(id: String, up: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { dictionaries.move(id, up); reloadDicts() }
    }

    fun removeDict(id: String) {
        viewModelScope.launch(Dispatchers.IO) { dictionaries.remove(id); reloadDicts() }
    }

    fun backup(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { library.backup(it, prefs.exportJson(), backupData()) }
                }
            }
            _busy.value = false
            _messages.value = result.fold({ "已备份 $it 本书" }, { "备份失败：${it.message}" })
        }
    }

    /** 备份里除了书以外的数据。 */
    private fun backupData(): Map<String, ByteArray> = buildMap {
        vocabulary.raw()?.let { put("vocabulary.json", it) }
        stats.raw()?.let { put("stats.json", it) }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { library.restore(it) }
                }
            }
            result.getOrNull()?.data?.let { data ->
                withContext(Dispatchers.IO) {
                    data["vocabulary.json"]?.let(vocabulary::merge)
                    data["stats.json"]?.let(stats::merge)
                }
                reloadVocabulary()
                reloadStats()
            }
            result.getOrNull()?.settings?.let { s ->
                prefs.importJson(s)
                _settings.value = prefs.settings()
                _shelfPrefs.value = prefs.shelf()
            }
            _openBook.value = null
            loadShelf()
            _busy.value = false
            _messages.value = result.fold({ "已恢复 ${it.books} 本书" }, { "恢复失败：${it.message}" })
        }
    }

    fun notesMarkdown(): String? {
        val book = _openBook.value ?: return null
        return _marks.value.toMarkdown(book.entry.title, book.content)
    }

    /** 从别的应用“用灵阅打开”或分享过来的文件：导入（或找回已有的那本）并直接打开。 */
    fun openExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { library.describe(uris) }.filter { BookParser.isSupported(it.name) }
            if (picked.isEmpty()) {
                _messages.value = "暂不支持这种文件"
                return@launch
            }
            if (picked.size > 1) {
                _stack.value = listOf(Screen.Shelf)
                _pendingImport.value = picked
                return@launch
            }
            _busy.value = true
            val entry = withContext(Dispatchers.IO) {
                runCatching { library.findSame(picked.single()) ?: library.add(picked, null) }
            }
            loadShelf()
            _busy.value = false
            entry.onSuccess { _stack.value = listOf(Screen.Shelf, Screen.Reader(it.id)) }
                .onFailure { _messages.value = "打不开 ${picked.single().name}：${it.message ?: "读取失败"}" }
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        _settings.update(transform)
        prefs.saveSettings(_settings.value)
    }

    fun search(query: String) {
        _searchQuery.value = query
        val content = _openBook.value?.content ?: return
        viewModelScope.launch {
            _searchHits.value = withContext(Dispatchers.Default) { BookParser.search(content, query) }
        }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Search) {
            _searchQuery.value = ""
            _searchHits.value = emptyList()
        }
        _stack.update { it + screen }
    }

    /** 在阅读页内换章，不叠加返回栈。 */
    fun replaceTop(screen: Screen) {
        _stack.update { it.dropLast(1) + screen }
    }

    /** 从搜索结果跳到正文：替换掉搜索页和原阅读页，返回时直接回书架。 */
    fun jumpFromSearch(bookId: String, chapterIndex: Int, blockIndex: Int) {
        _stack.update { stack ->
            stack.dropLastWhile { it !is Screen.Reader }.dropLast(1) + Screen.Reader(bookId, chapterIndex, blockIndex)
        }
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }
}
