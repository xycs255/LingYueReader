package com.xycs.lingyuereader

import androidx.core.net.toUri
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.ChineseConvert
import com.xycs.lingyuereader.data.ChineseConverter
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
import com.xycs.lingyuereader.data.AutoBackup
import com.xycs.lingyuereader.data.SafFolder
import com.xycs.lingyuereader.data.FontStore
import com.xycs.lingyuereader.data.TextRules
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.ReadingStats
import com.xycs.lingyuereader.data.DictGloss
import com.xycs.lingyuereader.data.VocabWord
import com.xycs.lingyuereader.data.Vocabulary
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReadingProgress
import com.xycs.lingyuereader.data.TextAnchors
import com.xycs.lingyuereader.data.SearchHit
import com.xycs.lingyuereader.data.ShelfPrefs
import com.xycs.lingyuereader.data.ShelfSort
import com.xycs.lingyuereader.tts.ReadAloud
import com.xycs.lingyuereader.remote.Catalog
import com.xycs.lingyuereader.remote.CatalogKind
import com.xycs.lingyuereader.remote.CatalogStore
import com.xycs.lingyuereader.remote.RemoteClient
import com.xycs.lingyuereader.remote.RemoteFile
import com.xycs.lingyuereader.remote.RemoteItem
import com.xycs.lingyuereader.remote.RemotePage
import androidx.compose.ui.graphics.asImageBitmap
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiBook
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiGloss
import com.xycs.lingyuereader.ai.AiPrompts
import com.xycs.lingyuereader.ai.AiSession
import com.xycs.lingyuereader.ai.AiStore
import com.xycs.lingyuereader.ai.AskKind
import com.xycs.lingyuereader.ai.ReadPos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 首页底部导航的四个标签。 */
enum class HomeTab(val label: String) { Shelf("书架"), Vocabulary("生词本"), Stats("统计"), Me("我的") }

sealed interface Screen {
    /** 首页：书架、生词本、统计、我的，由底部导航切换。 */
    data object Shelf : Screen
    /** [chapterIndex] 为空时从上次读到的位置开始。 */
    data class Reader(
        val bookId: String,
        val chapterIndex: Int? = null,
        val blockIndex: Int? = null,
        /** 翻页模式下从上一章翻回来时，停在该章最后一页。 */
        val fromEnd: Boolean = false,
        /** 回到跳转前的位置时用：滚动模式的像素偏移、翻页模式的块内字。 */
        val offset: Int = 0,
        val char: Int = 0,
        /** 跳到的那一段亮一下，告诉读者落在哪（书签、批注、搜索结果、回到刚才）。 */
        val highlight: Boolean = false,
    ) : Screen
    data class Search(val bookId: String) : Screen
    /** 在书架上所有书的正文里搜索。 */
    data object LibrarySearch : Screen
    data class Detail(val bookId: String) : Screen
    data object Dictionaries : Screen
    /** AI 助手设置：服务商、模型、密钥。 */
    data object Ai : Screen
    /** 书源列表：OPDS 书目、WebDAV 文件夹。 */
    data object Catalogs : Screen
    /** 浏览一个书源的某一页（[url] 为空时是书源的首页）。 */
    /** 添加（[catalogId] 为空）或编辑书源。 */
    data class CatalogEdit(val catalogId: String?) : Screen
    data class CatalogBrowse(val catalogId: String, val url: String = "", val title: String = "") : Screen
    /** 主题设置（界面的深浅色、配色、玻璃效果等）。 */
    data object Theme : Screen
    data class BookRules(val bookId: String) : Screen
}

/** 跳转前读到的地方：从目录、书签、搜索、进度条跳走后，可以一键回来。[block] 为负表示章名处。 */
data class ReturnPoint(
    val bookId: String,
    val chapterIndex: Int,
    val block: Int,
    val offset: Int,
    val char: Int,
    val chapterTitle: String,
)

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

/** 正文里的一个字：第几块里的第几个字（不含段首缩进）。 */
data class TextPos(val block: Int, val char: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int = compareValuesBy(this, other, { it.block }, { it.char })
}

/**
 * 正文里划选的文字，可以跨段落：从 [start] 到 [end]（含）。[anchorStart]..[anchorEnd] 是长按选出的词，
 * 拖动时选区总包含它；[dragging] 为真表示手指还没松开。
 */
data class TextSelection(
    val chapterIndex: Int,
    val start: TextPos,
    val end: TextPos,
    val anchorStart: TextPos,
    val anchorEnd: TextPos,
    val dragging: Boolean,
    /** 长按处是不是一个能查的词（标点、空白不是）。 */
    val anchorIsWord: Boolean,
) {
    val singleBlock: Boolean get() = start.block == end.block
}

/**
 * 划选按词伸缩的规则，参照 Android 系统 TextView（AOSP Editor）和 Compose 的 CharacterWithWordAccelerate：
 * - 长按后不松手拖动，按整词伸缩；
 * - 拖把手时，往外拉、且原来停在词的边界上，就对齐到新词的边界；往回收按单字走，方便微调；换了一行时对齐到词。
 * [wordOf] 给出某个字所在的词（这一块里的范围），还没切好词时为空，按单字处理。
 */
internal object SelectionSnap {
    fun drag(sel: TextSelection, p: TextPos, wordOf: (TextPos) -> IntRange?): TextSelection {
        val w = wordOf(p)
        val lo = if (w != null) TextPos(p.block, w.first) else p
        val hi = if (w != null) TextPos(p.block, w.last) else p
        return sel.copy(start = minOf(sel.anchorStart, lo), end = maxOf(sel.anchorEnd, hi))
    }

    /** 终点把手拖到 [p]（选区的最后一个字）后，终点落在哪。 */
    fun end(sel: TextSelection, p: TextPos, lineChanged: Boolean, wordOf: (TextPos) -> IntRange?): TextPos {
        val w = wordOf(p) ?: return p
        val atBoundary = wordOf(sel.end)?.let { sel.end.char == it.last } ?: true
        return if (lineChanged || (p > sel.end && atBoundary)) TextPos(p.block, w.last) else p
    }

    /** 起点把手拖到 [p]（选区的第一个字）后，起点落在哪。 */
    fun start(sel: TextSelection, p: TextPos, lineChanged: Boolean, wordOf: (TextPos) -> IntRange?): TextPos {
        val w = wordOf(p) ?: return p
        val atBoundary = wordOf(sel.start)?.let { sel.start.char == it.first } ?: true
        return if (lineChanged || (p < sel.start && atBoundary)) TextPos(p.block, w.first) else p
    }
}

/** 正在读的书：显示正文和未转换的来源正文共享块坐标；标记与进度锚在来源正文。 */
data class OpenBook(val entry: BookEntry, val content: BookContent, val sourceContent: BookContent = content)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val library = Library(app)
    /**
     * 书签、批注、进度、分组的写盘排成一队，一个写完再写下一个。各开一个 IO 协程并行写时，
     * 先拍的旧快照可能最后落盘，把刚加的批注盖掉（跨段划线一次加好几条时就会丢）。
     */
    private val disk = Dispatchers.IO.limitedParallelism(1)
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

    /** 各段切好的词（书、章、块 → 词的范围），划选按词伸缩时用；在后台切，没切好时按单字。 */
    private val segmented = HashMap<Triple<String, Int, Int>, List<IntRange>>()
    private val segmenting = HashSet<Triple<String, Int, Int>>()

    private fun wordsOf(chapterIndex: Int, block: Int): List<IntRange>? {
        val id = _openBook.value?.entry?.id ?: return null
        val key = Triple(id, chapterIndex, block)
        segmented[key]?.let { return it }
        val text = blockText(chapterIndex, block)?.takeIf { it.isNotEmpty() } ?: return null
        if (segmenting.add(key)) {
            viewModelScope.launch {
                val words = withContext(Dispatchers.IO) { runCatching { WordFinder.words(text, dictionaries::isWord) }.getOrNull() }
                segmenting.remove(key)
                if (words != null) {
                    if (segmented.size > 300) segmented.clear()
                    segmented[key] = words
                }
            }
        }
        return null
    }

    /** 某个字所在的词；还没切好时为空。 */
    private fun wordAt(chapterIndex: Int, p: TextPos): IntRange? {
        val words = wordsOf(chapterIndex, p.block) ?: return null
        var lo = 0
        var hi = words.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = words[mid]
            when {
                p.char < w.first -> hi = mid - 1
                p.char > w.last -> lo = mid + 1
                else -> return w
            }
        }
        return null
    }
    private val prefs = Prefs(app)
    private val autoBackups = AutoBackup(app)
    private val _autoBackup = MutableStateFlow(autoBackups.state())
    val autoBackup: StateFlow<AutoBackup.State> = _autoBackup.asStateFlow()
    private var autoBackupJob: Job? = null


    private val _shelf = MutableStateFlow<List<BookEntry>>(emptyList())
    val shelf: StateFlow<List<BookEntry>> = _shelf.asStateFlow()

    private val _shelfPrefs = MutableStateFlow(prefs.shelf())
    val shelfPrefs: StateFlow<ShelfPrefs> = _shelfPrefs.asStateFlow()

    private val _shelfQuery = MutableStateFlow("")
    val shelfQuery: StateFlow<String> = _shelfQuery.asStateFlow()

    /** 书架上实际显示的书：按搜索词过滤、按所选方式排序。 */
    val visibleShelf: StateFlow<List<BookEntry>> = combine(_shelf, _shelfPrefs, _shelfQuery) { all, p, q ->
        val query = q.trim()
        val books = when (p.group) {
            null -> all
            ShelfPrefs.UNGROUPED -> all.filter { it.groups.isEmpty() }
            else -> all.filter { p.group in it.groups }
        }
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
    private var bookLoadJob: Job? = null
    private var loadingBookId: String? = null
    private var bookLoadGeneration = 0L

    /** 当前打开的书的书签与批注。 */
    private val _marks = MutableStateFlow(Marks())
    val marks: StateFlow<Marks> = _marks.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private var importJob: Job? = null

    /** 一次选了多个文件时，先问是分别导入还是合成一本。 */
    private val _pendingImport = MutableStateFlow<List<Library.Picked>?>(null)
    val pendingImport: StateFlow<List<Library.Picked>?> = _pendingImport.asStateFlow()

    private val _settings = MutableStateFlow(prefs.settings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _appearance = MutableStateFlow(prefs.appearance())
    val appearance: StateFlow<com.xycs.lingyuereader.data.Appearance> = _appearance.asStateFlow()

    fun updateAppearance(transform: (com.xycs.lingyuereader.data.Appearance) -> com.xycs.lingyuereader.data.Appearance) {
        val next = transform(_appearance.value)
        if (next == _appearance.value) return
        _appearance.value = next
        prefs.saveAppearance(next)
    }

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Shelf))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _homeTab = MutableStateFlow(HomeTab.Shelf)
    val homeTab: StateFlow<HomeTab> = _homeTab.asStateFlow()

    fun selectTab(tab: HomeTab) {
        _homeTab.value = tab
    }

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    /** 全书架搜索：一本书一组结果，边搜边出。 */
    data class BookHits(val entry: BookEntry, val hits: List<SearchHit>, val more: Boolean)
    data class LibrarySearchState(
        val query: String = "",
        val results: List<BookHits> = emptyList(),
        /** 已搜完几本、一共几本。 */
        val done: Int = 0,
        val total: Int = 0,
    ) {
        val running: Boolean get() = done < total
    }

    private val _librarySearch = MutableStateFlow(LibrarySearchState())
    val librarySearch: StateFlow<LibrarySearchState> = _librarySearch.asStateFlow()
    private var librarySearchJob: Job? = null

    /** 每本书最多列多少处：结果按书分组，太多了反而难找。 */
    private val hitsPerBook = 20

    fun searchLibrary(query: String) {
        librarySearchJob?.cancel()
        val q = query.trim()
        val books = _shelf.value.filterNot { it.format.imageOnly }
        _librarySearch.value = LibrarySearchState(q, emptyList(), 0, if (q.isEmpty()) 0 else books.size)
        if (q.isEmpty()) return
        librarySearchJob = viewModelScope.launch {
            books.forEachIndexed { i, entry ->
                val found = withContext(Dispatchers.Default) {
                    runCatching {
                        val content = converted(library.content(entry), _settings.value.chineseConvert)
                        BookParser.search(content, q, hitsPerBook + 1)
                    }.getOrDefault(emptyList())
                }
                _librarySearch.update { s ->
                    val results = if (found.isEmpty()) s.results else s.results + BookHits(entry, found.take(hitsPerBook), found.size > hitsPerBook)
                    s.copy(results = results, done = i + 1)
                }
            }
        }
    }

    fun openLibrarySearch(query: String) {
        open(Screen.LibrarySearch)
        searchLibrary(query)
    }

    init {
        viewModelScope.launch {
            try {
                loadShelf()
            } catch (error: Exception) {
                _messages.value = error.message ?: "书架读取失败；原文件已保留"
            }
            _shelfLoaded.value = true
            maybeAutoBackup()
            com.xycs.lingyuereader.reminder.GoalReminder.schedule(getApplication())
        }
        viewModelScope.launch { _dictSettings.value = withContext(Dispatchers.IO) { dictionaries.settings() } }
        reloadVocabulary()
        reloadStats()
        // 听书时跟着朗读位置记进度，锁屏听完再打开也能接着读。
        viewModelScope.launch {
            ReadAloud.state.collect { s ->
                val u = s?.current ?: return@collect
                val book = _openBook.value?.takeIf { it.entry.id == s.bookId } ?: return@collect
                if (book.content.chapters.getOrNull(s.chapterIndex)?.blocks?.getOrNull(u.block) != null) {
                    saveProgress(s.chapterIndex, u.block + 1, 0, u.start)
                }
            }
        }
    }

    /** 从当前阅读位置开始听书。 */
    fun startReadAloud(chapterIndex: Int, block: Int, char: Int) {
        val book = _openBook.value ?: return
        ReadAloud.start(
            getApplication(), book.entry.id, book.entry.title, book.content,
            chapterIndex, block.coerceAtLeast(0), char, _settings.value.ttsRate,
            _settings.value.ttsVoice.ifEmpty { null },
        )
    }

    fun setReadAloudVoice(name: String?) {
        updateSettings { it.copy(ttsVoice = name.orEmpty()) }
        ReadAloud.setVoice(name)
    }

    fun setReadAloudRate(rate: Float) {
        updateSettings { it.copy(ttsRate = rate) }
        ReadAloud.setRate(rate)
    }

    /**
     * 重新读书架。索引整份损坏时书架保持原样并提示（[Library] 已停止写入、保留原文件）；
     * 个别条目读不出时照常显示其余的书，同样提示。读失败不往外抛，免得在协程里让应用崩溃。
     */
    private suspend fun loadShelf() {
        try {
            val (shelf, damaged) = withContext(Dispatchers.IO) { library.list() to library.damagedEntries() }
            _shelf.value = shelf
            if (damaged > 0) _messages.value = "书架索引里有 $damaged 本书的记录读不出，已暂停改动书架，原文件已保留"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _messages.value = error.message ?: "书架读取失败；原文件已保留"
        }
    }

    /** 写失败已经提示过的事（只在 [disk] 上读写）；同一种写入成功一次后再失败才重新提示。 */
    private val reportedWriteFailures = HashSet<String>()

    /**
     * 后台按顺序写盘；写不进去（比如书架索引损坏、拒绝改写）时提示，不让应用崩溃。
     * 每翻一页都存进度，同一种失败只提示一次，不刷屏。
     */
    private fun writeLater(what: String, block: () -> Unit) {
        viewModelScope.launch(disk) {
            try {
                block()
                reportedWriteFailures.remove(what)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reportedWriteFailures.add(what)) _messages.value = "${what}失败：${error.message ?: "写入出错"}"
            }
        }
    }

    fun setShelfQuery(query: String) {
        _shelfQuery.value = query
    }

    fun updateShelfPrefs(transform: (ShelfPrefs) -> ShelfPrefs) {
        _shelfPrefs.update(transform)
        prefs.saveShelf(_shelfPrefs.value)
    }

    // ---------- 书架分组 ----------

    /** 所有分组：按设定的顺序，书上有、顺序里没有的接在后面。 */
    val shelfGroups: StateFlow<List<String>> = combine(_shelf, _shelfPrefs) { books, p ->
        (p.groups + books.flatMap { it.groups }).distinct()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectGroup(group: String?) = updateShelfPrefs { it.copy(group = group) }

    fun setBookGroups(entry: BookEntry, groups: List<String>) {
        val clean = groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        updateShelfPrefs { p -> p.copy(groups = (p.groups + clean).distinct()) }
        _shelf.update { list -> list.map { if (it.id == entry.id) it.copy(groups = clean) else it } }
        writeLater("保存分组") { library.setGroups(entry.id, clean) }
    }

    /** 分组改名；[to] 为空即删除分组（书不删）。 */
    fun renameGroup(from: String, to: String?) {
        val target = to?.trim()?.takeIf { it.isNotEmpty() }
        updateShelfPrefs { p ->
            p.copy(
                groups = p.groups.flatMap { if (it == from) listOfNotNull(target) else listOf(it) }.distinct(),
                group = if (p.group == from) target else p.group,
            )
        }
        _shelf.update { list ->
            list.map { e -> if (from !in e.groups) e else e.copy(groups = e.groups.flatMap { g -> if (g == from) listOfNotNull(target) else listOf(g) }.distinct()) }
        }
        writeLater("修改分组") { library.renameGroup(from, target) }
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
            // 书架索引写不进去时，阅读统计照样记。
            runCatching { library.addReadingTime(bookId, millis) }
            stats.addTime(bookId, millis)
            _stats.value = stats.days()
        }
    }

    fun pick(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { library.describe(uris) }
            val (ok, unsupported) = picked.partition { BookParser.isSupported(it.name) }
            val unsupportedMessage = unsupported.takeIf { it.isNotEmpty() }
                ?.let { "暂不支持：${it.joinToString("、") { file -> file.name }}" }
            if (unsupportedMessage != null) _messages.value = unsupportedMessage
            when {
                ok.size == 1 -> importBooks(ok, merge = false, title = null, precedingMessage = unsupportedMessage)
                ok.size > 1 -> _pendingImport.value = ok
            }
        }
    }

    /** 关掉“分别导入还是合成一本”的对话框；不影响正在进行的导入。 */
    fun dismissImport() {
        _pendingImport.value = null
    }

    /** 取消正在进行的导入（导入进度上的“取消导入”）。 */
    fun cancelImport() {
        _pendingImport.value = null
        importJob?.cancel()
    }

    fun importBooks(files: List<Library.Picked>, merge: Boolean, title: String?, precedingMessage: String? = null) {
        _pendingImport.value = null
        if (files.isEmpty()) return
        if (_busy.value) {
            // 备份、恢复、导入词典等正在进行：不能悄悄丢掉选好的文件。
            _messages.value = "正在处理其他任务，完成后请再导入：${files.joinToString("、") { it.name }}"
            return
        }
        importJob = viewModelScope.launch {
            _busy.value = true
            _importing.value = true
            val groups = if (merge) listOf(files) else files.map { listOf(it) }
            val failed = mutableListOf<String>()
            var added = 0
            var cancelled = false
            try {
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    groups.forEach { group ->
                        context.ensureActive()
                        try {
                            library.add(group, if (merge) title else null) { context.ensureActive() }
                            added++
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failed += group.first().name + "（${error.message ?: "读取失败"}）"
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } finally {
                try {
                    withContext(NonCancellable) { loadShelf() }
                    val message = buildString {
                        if (cancelled) append("已取消导入")
                        if (added > 0) {
                            if (isNotEmpty()) append("；")
                            append("已加入 $added 本书")
                        }
                        if (failed.isNotEmpty()) {
                            if (isNotEmpty()) append("；")
                            append("导入失败：${failed.joinToString("、")}")
                        }
                        if (precedingMessage != null) {
                            if (isNotEmpty()) append("；")
                            append(precedingMessage)
                        }
                    }
                    _messages.value = message.takeIf { it.isNotEmpty() }
                } catch (error: Exception) {
                    _messages.value = "刷新书架失败：${error.message ?: "读取失败"}"
                } finally {
                    _busy.value = false
                    _importing.value = false
                    importJob = null
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
                        it.failed > 0 && entry.sources.any { s -> s.uri?.startsWith("http") == true } -> "有 ${it.failed} 份文件没能从书源重新下载（网络不通，或文件已移走）"
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
            withContext(Dispatchers.IO) { runCatching { library.rename(entry.id, title.trim()) } }
                .onFailure { _messages.value = "改名失败：${it.message ?: "写入出错"}" }
            loadShelf()
        }
    }

    fun remove(entry: BookEntry) {
        viewModelScope.launch {
            if (loadingBookId == entry.id) cancelBookLoad()
            val removed = withContext(Dispatchers.IO) { runCatching { library.remove(entry.id) } }
            removed.onFailure { _messages.value = "移除失败：${it.message ?: "写入出错"}" }
            if (removed.isSuccess && _openBook.value?.entry?.id == entry.id) _openBook.value = null
            loadShelf()
        }
    }

    private fun cancelBookLoad() {
        bookLoadGeneration++
        bookLoadJob?.cancel()
        bookLoadJob = null
        loadingBookId = null
    }

    /** 阅读页进来时调用；已加载的同一本书不重复解析。 */
    fun ensureLoaded(bookId: String) {
        if (_openBook.value?.entry?.id == bookId) {
            // 刚回到这本已加载的书时，先取消仍在后台加载的另一本书。
            if (loadingBookId != null && loadingBookId != bookId) cancelBookLoad()
            return
        }
        if (loadingBookId == bookId && bookLoadJob?.isActive == true) return
        cancelBookLoad()
        val generation = bookLoadGeneration
        val entry = _shelf.value.firstOrNull { it.id == bookId } ?: return
        _openBook.value = null
        loadingBookId = bookId
        bookLoadJob = viewModelScope.launch {
            try {
                val mode = _settings.value.chineseConvert
                val (loaded, marks) = withContext(Dispatchers.IO) {
                    val source = library.content(entry)
                    val content = converted(source, mode)
                    val stored = library.marks(entry.id)
                    val direct = stored.resolve(source)
                    val directProgress = entry.progress?.let { TextAnchors.rebaseProgress(source, it) }
                    // 旧版可能把显示态的繁简文字写进锚点；等长转换视图只作定位，
                    // 成功后统一换算回来源文字，不能在切换显示设置时改写 marks.json。
                    val needsAlternates = direct.bookmarks.any { it.lost } || direct.notes.any { it.lost } ||
                        (entry.progress != null && directProgress == null)
                    val alternates = if (needsAlternates) listOf(
                        converted(source, ChineseConvert.TO_SIMPLIFIED),
                        converted(source, ChineseConvert.TO_TRADITIONAL),
                    ) else emptyList()
                    val marks = if (direct.bookmarks.any { it.lost } || direct.notes.any { it.lost })
                        stored.resolve(source, alternates) else direct
                    val progress = directProgress ?: entry.progress?.let { TextAnchors.rebaseProgress(source, it, alternates) }
                    OpenBook(if (progress != null) entry.copy(progress = progress) else entry, content, source) to marks
                }
                if (generation != bookLoadGeneration) return@launch
                _marks.value = marks
                _openBook.value = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == bookLoadGeneration) {
                    _messages.value = "打不开《${entry.title}》：${error.message ?: "读取失败"}"
                    if ((_stack.value.lastOrNull() as? Screen.Reader)?.bookId == bookId) back()
                }
            } finally {
                if (generation == bookLoadGeneration) {
                    loadingBookId = null
                    bookLoadJob = null
                }
            }
        }
    }

    private val _returnPoint = MutableStateFlow<ReturnPoint?>(null)
    val returnPoint: StateFlow<ReturnPoint?> = _returnPoint.asStateFlow()
    /** 上次跳转后位置又变了几次（停下来存进度算一次）。 */
    private var movesSinceJump = 0

    /** 要跳走了，记下 [from]。刚跳过去还没读几页又跳（比如在目录里来回找），仍回到最早读的那里。 */
    fun markJump(from: ReturnPoint) {
        val current = _returnPoint.value
        if (current == null || current.bookId != from.bookId || movesSinceJump > 2) _returnPoint.value = from
        movesSinceJump = 0
    }

    fun clearReturnPoint() {
        _returnPoint.value = null
    }

    /** 回到跳转前的位置。 */
    fun jumpBack() {
        val p = _returnPoint.value ?: return
        _returnPoint.value = null
        replaceTop(Screen.Reader(p.bookId, p.chapterIndex, p.block, offset = p.offset, char = p.char, highlight = true))
    }

    fun saveProgress(chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int = 0) {
        val book = _openBook.value ?: return
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return
        val anchor = TextAnchors.capture(chapter, itemIndex - 1, charOffset)
        val progress = ReadingProgress(chapterIndex, chapter.title, itemIndex, itemOffset, charOffset, anchor)
        if (book.entry.progress == progress) return
        movesSinceJump++
        _openBook.value = book.copy(entry = book.entry.copy(progress = progress))
        _shelf.update { list -> list.map { if (it.id == book.entry.id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it } }
        writeLater("保存阅读进度") { library.saveProgress(book.entry.id, progress) }
    }

    private fun updateMarks(transform: (Marks) -> Marks) {
        val book = _openBook.value ?: return
        _marks.update(transform)
        val snapshot = _marks.value
        writeLater("保存书签批注") { library.saveMarks(book.entry.id, snapshot) }
    }

    /** 在某块的某个位置加或删书签（同一块已有书签时删除）。 */
    fun toggleBookmark(chapterIndex: Int, blockIndex: Int, charOffset: Int) {
        val book = _openBook.value ?: return
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return
        val itemIndex = blockIndex + 1
        val existing = _marks.value.bookmarks.filter { it.chapterIndex == chapterIndex && it.itemIndex == itemIndex }
        if (existing.isNotEmpty()) {
            updateMarks { m -> m.copy(bookmarks = m.bookmarks - existing.toSet()) }
            _messages.value = "已删除书签"
            return
        }
        val excerpt = chapter.blocks.getOrNull(blockIndex)?.displayText?.trim()?.take(40) ?: chapter.title
        val bookmark = Bookmark(
            UUID.randomUUID().toString(), chapterIndex, chapter.title, itemIndex, charOffset, excerpt, System.currentTimeMillis(),
            anchor = TextAnchors.capture(chapter, blockIndex, charOffset),
        )
        updateMarks { m -> m.copy(bookmarks = m.bookmarks + bookmark) }
        _messages.value = "已加书签"
    }

    fun deleteBookmark(id: String) = updateMarks { m -> m.copy(bookmarks = m.bookmarks.filterNot { it.id == id }) }

    fun addNote(chapterIndex: Int, blockIndex: Int, start: Int, end: Int, text: String, color: MarkColor) {
        val book = _openBook.value ?: return
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return
        val shown = chapter.blocks.getOrNull(blockIndex)?.displayText ?: return
        val s = start.coerceIn(0, shown.length)
        val e = end.coerceIn(s, shown.length)
        val (from, to) = if (s == e) 0 to shown.length else s to e
        val note = Note(UUID.randomUUID().toString(), chapterIndex, chapter.title, blockIndex, from, to, shown.substring(from, to), text.trim(), color, System.currentTimeMillis())
        updateMarks { m -> m.copy(notes = m.notes + note) }
    }

    fun deleteNote(id: String) = updateMarks { m -> m.copy(notes = m.notes.filterNot { it.id == id }) }

    fun bookFile(id: String, name: String): java.io.File = library.bookFile(id, name)

    // ---------- 书源（OPDS、WebDAV） ----------

    private val catalogStore = CatalogStore(app)
    private val _catalogs = MutableStateFlow(catalogStore.load())
    val catalogs: StateFlow<List<Catalog>> = _catalogs.asStateFlow()
    private val remote = RemoteClient { _catalogs.value }.also { client -> library.remote = client::open }

    /** 这次打开应用以来看过的页：返回上一层时不用重新加载。 */
    private val remotePages = object : LinkedHashMap<String, RemotePage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemotePage>?) = size > 40
    }
    private val covers = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(80)

    /** 正在从书源下载的文件地址。 */
    private val _downloading = MutableStateFlow<String?>(null)
    val downloading: StateFlow<String?> = _downloading.asStateFlow()

    fun saveCatalog(c: Catalog) {
        val list = _catalogs.value
        val next = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c
        _catalogs.value = next
        catalogStore.save(next)
        synchronized(remotePages) { remotePages.keys.removeAll { it.startsWith(c.id + "|") } }
    }

    fun removeCatalog(id: String) {
        val next = _catalogs.value.filterNot { it.id == id }
        _catalogs.value = next
        catalogStore.save(next)
    }

    /** “测试连接”：打开首页，回来就说明地址和账号都对。 */
    suspend fun testCatalog(c: Catalog): String {
        val probe = RemoteClient { _catalogs.value.filterNot { it.id == c.id } + c }
        val page = probe.page(c, c.url.trim())
        val books = page.items.count { it is RemoteItem.Book }
        val folders = page.items.size - books
        return "连接正常：「${page.title}」里有 " + listOfNotNull(
            folders.takeIf { it > 0 }?.let { "$it 个${if (c.kind == CatalogKind.OPDS) "分类" else "文件夹"}" },
            books.takeIf { it > 0 }?.let { "$it 本书" },
        ).joinToString("、").ifEmpty { "0 项" }
    }

    /** 已经看过的页（没有时为空）。 */
    fun cachedRemotePage(catalogId: String, url: String): RemotePage? = synchronized(remotePages) { remotePages["$catalogId|$url"] }

    /** 打开书源的一页；[fresh] 时不用缓存。 */
    suspend fun remotePage(catalog: Catalog, url: String, fresh: Boolean = false): RemotePage {
        val key = "${catalog.id}|$url"
        if (!fresh) cachedRemotePage(catalog.id, url)?.let { return it }
        return remote.page(catalog, url).also { synchronized(remotePages) { remotePages[key] = it } }
    }

    /** OPDS 分页：取下一页，接在后面。 */
    suspend fun remoteMore(catalog: Catalog, url: String, page: RemotePage): RemotePage {
        val next = page.next ?: return page
        val more = remote.page(catalog, next)
        return page.copy(items = page.items + more.items, next = more.next).also { synchronized(remotePages) { remotePages["${catalog.id}|$url"] = it } }
    }

    suspend fun remoteSearch(page: RemotePage, query: String): RemotePage = remote.search(page, query)

    /** 封面缩略图；取不到时为空。 */
    suspend fun remoteCover(url: String): androidx.compose.ui.graphics.ImageBitmap? {
        covers.get(url)?.let { return it }
        val bytes = runCatching { remote.image(url) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 240) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return bitmap.asImageBitmap().also { covers.put(url, it) }
    }

    /** 下载书源里的一本书，走和本地导入一样的流程加入书架。 */
    fun downloadRemote(file: RemoteFile, title: String?) {
        if (_downloading.value != null || _busy.value) {
            _messages.value = "正在处理其他任务，稍后再下载"
            return
        }
        _downloading.value = file.url
        importBooks(listOf(Library.Picked(Uri.parse(file.url), file.name)), merge = false, title = title)
        val job = importJob
        viewModelScope.launch {
            job?.join()
            _downloading.value = null
        }
        if (job == null) _downloading.value = null
    }

    // ---------- AI ----------

    private val assistant = AiAssistant(viewModelScope, AiStore(app), recapFile = { id -> library.bookFile(id, "ai-recap.json") })
    val aiConfig: StateFlow<AiConfig> = assistant.config
    val ai: StateFlow<AiSession?> = assistant.session
    val aiGloss: StateFlow<AiGloss?> = assistant.gloss

    fun saveAiConfig(c: AiConfig) = assistant.saveConfig(c)

    suspend fun testAi(c: AiConfig): String = assistant.test(c)

    private fun aiBook(): AiBook? = _openBook.value?.let { AiBook(it.entry.id, it.entry.title, it.entry.author, it.content) }

    /** 划选后问 AI：把选中的文字和所在段落带进面板，选区收起。 */
    fun askAiAboutSelection() {
        val sel = _selection.value ?: return
        val text = selectionText()?.takeIf { it.isNotBlank() } ?: return
        val book = aiBook() ?: return
        val chapter = book.content.chapters.getOrNull(sel.chapterIndex)
        // 上下文：选区所在的段落；太长时取选区前后各一截。
        val paragraphs = (sel.start.block..sel.end.block).mapNotNull { blockText(sel.chapterIndex, it) }.joinToString("\n")
        val context = if (paragraphs.length <= AiPrompts.CONTEXT_CHARS) {
            paragraphs
        } else {
            val half = AiPrompts.CONTEXT_CHARS / 2
            val at = sel.start.char.coerceIn(0, paragraphs.length)
            val from = (at - half).coerceAtLeast(0)
            paragraphs.substring(from, (from + AiPrompts.CONTEXT_CHARS).coerceAtMost(paragraphs.length))
        }
        _selection.value = null
        assistant.startSelection(book, text, context, chapter?.title.orEmpty())
    }

    fun startRecap(chapterIndex: Int, block: Int) {
        aiBook()?.let { assistant.startRecap(it, ReadPos(chapterIndex, block)) }
    }

    fun startAskBook(chapterIndex: Int, block: Int) {
        aiBook()?.let { assistant.startAskBook(it, ReadPos(chapterIndex, block)) }
    }

    fun askAi(kind: AskKind) = assistant.ask(kind)

    fun askAi(question: String) = assistant.ask(question)

    fun regenerateRecap() = assistant.recap()

    fun retryAi() = assistant.retry()

    fun closeAi() = assistant.close()

    /** 查词面板里的“AI 释义”。 */
    fun glossLookup() {
        val l = _lookup.value ?: return
        assistant.gloss(aiBook(), l.word, l.context)
    }

    // ---------- 查词 ----------

    private fun blockText(chapterIndex: Int, block: Int): String? =
        _openBook.value?.content?.chapters?.getOrNull(chapterIndex)?.blocks?.getOrNull(block)?.displayText

    /** 长按正文某个字：先选出它所在的词；松手前接着拖就扩大选区（可以拖到别的段落）。 */
    fun pressText(chapterIndex: Int, block: Int, offset: Int) {
        _lookup.value = null
        // 当前章已在内存里，先同步选中按住的字，免得找词期间的拖动落空。
        val text = blockText(chapterIndex, block)?.takeIf { it.isNotEmpty() } ?: return
        val at = TextPos(block, offset.coerceIn(0, text.lastIndex))
        val pressed = TextSelection(chapterIndex, at, at, at, at, dragging = true, anchorIsWord = false)
        _selection.value = pressed
        // 前后几段也在后台切好词。
        for (b in (block - 2)..(block + 4)) if (b >= 0 && b != block) wordsOf(chapterIndex, b)
        val key = _openBook.value?.entry?.id?.let { Triple(it, chapterIndex, block) }
        pressJob = viewModelScope.launch {
            // 顺手把按住的这段整段切好词，接着拖动时按词伸缩。
            val (word, words) = withContext(Dispatchers.IO) {
                runCatching { WordFinder.wordAt(text, at.char, dictionaries::isWord) }.getOrNull() to
                    runCatching { WordFinder.words(text, dictionaries::isWord) }.getOrNull()
            }
            if (key != null && words != null) segmented[key] = words
            word ?: return@launch
            _selection.update { sel ->
                if (sel == null || sel.anchorStart != pressed.anchorStart || !sel.dragging) return@update sel
                val ws = TextPos(block, word.first)
                val we = TextPos(block, word.last)
                sel.copy(start = minOf(sel.start, ws), end = maxOf(sel.end, we), anchorStart = ws, anchorEnd = we, anchorIsWord = true)
            }
        }
    }

    /** 竖排时长按某个字：直接查它所在的词。 */
    fun lookupAt(chapterIndex: Int, block: Int, char: Int) {
        val text = blockText(chapterIndex, block)?.takeIf { it.isNotEmpty() } ?: return
        val at = char.coerceIn(0, text.lastIndex)
        viewModelScope.launch {
            val word = withContext(Dispatchers.IO) {
                runCatching { WordFinder.wordAt(text, at, dictionaries::isWord) }.getOrNull()
            } ?: return@launch
            lookupRange(chapterIndex, block, word)
        }
    }

    /** 把位置限制在这一块的文字里；空块（插图、分隔线）返回空。 */
    private fun clampPos(chapterIndex: Int, block: Int, char: Int): TextPos? {
        val len = blockText(chapterIndex, block)?.length ?: return null
        if (len == 0) return null
        return TextPos(block, char.coerceIn(0, len - 1))
    }

    /** 长按后拖到某个字：选区从长按的词伸到这个字所在的词。 */
    fun dragText(block: Int, offset: Int) {
        val sel = _selection.value ?: return
        val p = clampPos(sel.chapterIndex, block, offset) ?: return
        _selection.value = SelectionSnap.drag(sel, p) { wordAt(sel.chapterIndex, it) }
    }

    /** 松手：没拖动就直接查长按的词，拖过了就留着选区（显示把手和操作条）。 */
    fun releaseText() {
        val job = pressJob
        viewModelScope.launch {
            job?.join()
            val sel = _selection.value ?: return@launch
            if (!sel.dragging) return@launch
            if (sel.start == sel.anchorStart && sel.end == sel.anchorEnd) {
                _selection.value = null
                if (sel.anchorIsWord) lookupRange(sel.chapterIndex, sel.start.block, sel.start.char..sel.end.char)
            } else {
                _selection.value = sel.copy(dragging = false)
            }
        }
    }

    /** 拖动选区的起点或终点把手到某块的光标位置 [cursor]（字与字之间）。 */
    fun moveSelectionHandle(start: Boolean, block: Int, cursor: Int, lineChanged: Boolean = false) {
        val sel = _selection.value ?: return
        val wordOf = { p: TextPos -> wordAt(sel.chapterIndex, p) }
        val next = if (start) {
            val p = clampPos(sel.chapterIndex, block, cursor) ?: return
            sel.copy(start = minOf(SelectionSnap.start(sel, p, lineChanged, wordOf), sel.end))
        } else {
            val p = clampPos(sel.chapterIndex, block, cursor - 1) ?: return
            sel.copy(end = maxOf(SelectionSnap.end(sel, p, lineChanged, wordOf), sel.start))
        }
        _selection.value = next.copy(anchorStart = next.start, anchorEnd = next.end)
    }

    /** 选区在各块里的文字（块号 → 范围），跨段时每段一份。 */
    private fun selectionPieces(sel: TextSelection): List<Pair<Int, IntRange>> = (sel.start.block..sel.end.block).mapNotNull { b ->
        val len = blockText(sel.chapterIndex, b)?.length ?: return@mapNotNull null
        if (len == 0) return@mapNotNull null
        val s = if (b == sel.start.block) sel.start.char else 0
        val e = if (b == sel.end.block) sel.end.char else len - 1
        if (s > e) null else b to (s.coerceIn(0, len - 1)..e.coerceIn(0, len - 1))
    }

    fun selectionText(): String? = _selection.value?.let { sel ->
        selectionPieces(sel).mapNotNull { (b, r) -> blockText(sel.chapterIndex, b)?.substring(r.first, r.last + 1) }.joinToString("\n")
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** 查划选的文字（只在一段之内时）。 */
    fun lookupSelection() {
        val sel = _selection.value ?: return
        if (!sel.singleBlock) return
        _selection.value = null
        lookupRange(sel.chapterIndex, sel.start.block, sel.start.char..sel.end.char)
    }

    /** 跨段划线：每段一条划线，批注文字记在最后一段上。 */
    fun annotateSelection(color: MarkColor, note: String) {
        val sel = _selection.value ?: return
        val pieces = selectionPieces(sel)
        pieces.forEachIndexed { i, (b, r) ->
            addNote(sel.chapterIndex, b, r.first, r.last + 1, if (i == pieces.lastIndex) note else "", color)
        }
        _selection.value = null
        _messages.value = "已划线 ${pieces.size} 段"
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
        assistant.clearGloss(keep = l.word)
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
        // 导入词典的一条往往先是目录、部首笔画，挑出读音和带义项编号的那几段。
        val imported = r?.imported?.firstOrNull()?.second?.firstOrNull()?.html?.let { DictGloss.summarize(it) }
        val reading = word?.pinyin ?: char?.readings?.firstOrNull() ?: imported?.reading ?: ""
        val gloss = when {
            word != null -> word.definitions.take(3).joinToString("；")
            char != null && char.definition.isNotBlank() -> char.definition
            else -> imported?.gloss ?: ""
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

    fun reviewWord(word: String, known: Boolean) {
        viewModelScope.launch { _vocab.value = withContext(Dispatchers.IO) { vocabulary.review(word, known); vocabulary.list() } }
    }

    fun vocabularyMarkdown(words: List<VocabWord>): String = vocabulary.toMarkdown(words)

    fun closeLookup() {
        assistant.clearGloss()
        _lookup.value = null
    }

    fun dictResource(dictId: String, path: String): ByteArray? = dictionaries.resource(dictId, path)

    private var annotator: com.xycs.lingyuereader.dict.PinyinAnnotator? = null

    /** 注音用的拼音标注器；第一次要读字表，较慢。 */
    suspend fun pinyinAnnotator(): com.xycs.lingyuereader.dict.PinyinAnnotator? = annotator ?: withContext(Dispatchers.IO) {
        runCatching { dictionaries.pinyinAnnotator() }.getOrNull()
    }?.also { annotator = it }

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
            var copyingToDestination = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val staged = File.createTempFile("manual-backup-", ".zip", app.cacheDir)
                    try {
                        // 先完成 ZIP；来源文件读到一半失败时，不会碰已选的目标文件。
                        val count = staged.outputStream().use { library.backup(it, prefs.exportJson(), backupData()) }
                        val output = app.contentResolver.openOutputStream(uri)
                            ?: throw IllegalArgumentException("无法写入备份文件")
                        output.use { dest ->
                            copyingToDestination = true
                            staged.inputStream().use { it.copyTo(dest) }
                        }
                        count
                    } finally {
                        staged.delete()
                    }
                }
            }
            _busy.value = false
            _messages.value = result.fold(
                { "已备份 $it 本书" },
                { "备份失败：${it.message}${if (copyingToDestination) "；目标文件可能不完整，请删除后重试" else ""}" },
            )
        }
    }

    /** 选好了自动备份的文件夹：记住授权，马上备份一次。 */
    fun setBackupFolder(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val label = withContext(Dispatchers.IO) {
                runCatching { SafFolder.remember(app, uri) }
            }.getOrElse {
                _messages.value = "这个文件夹用不了：${it.message}"
                return@launch
            }
            _autoBackup.value.folder?.takeIf { it != uri.toString() }?.let { SafFolder.forget(app, it.toUri()) }
            autoBackups.setFolder(uri.toString(), label)
            _autoBackup.value = autoBackups.state()
            runAutoBackup(announce = true)
        }
    }

    fun disableAutoBackup() {
        _autoBackup.value.folder?.let { SafFolder.forget(getApplication(), it.toUri()) }
        autoBackups.setFolder(null, "")
        _autoBackup.value = autoBackups.state()
    }

    fun backupNow() = runAutoBackup(announce = true)

    /** 打开应用、退到后台时调用：离上次满一天就悄悄备份一次。 */
    fun maybeAutoBackup() {
        if (autoBackups.due()) runAutoBackup(announce = false)
    }

    private fun runAutoBackup(announce: Boolean) {
        if (autoBackupJob?.isActive == true) return
        autoBackupJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                autoBackups.run { out -> library.backup(out, prefs.exportJson(), backupData()) }
            }
            _autoBackup.value = autoBackups.state()
            if (announce) {
                _messages.value = result.fold({ "已备份到「${_autoBackup.value.label}」：$it" }, { "备份失败：${it.message}" })
            }
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
            cancelBookLoad()
            var restoredBooks: Int? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("无法读取备份文件")
                    input.use { library.restore(it) }
                }
                restoredBooks = result.books
                result.data.let { data ->
                    withContext(Dispatchers.IO) {
                        data["vocabulary.json"]?.let(vocabulary::merge)
                        data["stats.json"]?.let(stats::merge)
                    }
                    reloadVocabulary()
                    reloadStats()
                }
                result.settings?.let { settings ->
                    prefs.importJson(settings)
                    _settings.value = prefs.settings()
                    _appearance.value = prefs.appearance()
                    _shelfPrefs.value = prefs.shelf()
                }
                _messages.value = buildString {
                    append("已恢复 ${result.books} 本书")
                    if (result.skippedBooks > 0) append("；备份中另有 ${result.skippedBooks} 本无法读取，已跳过")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: "请检查备份文件"
                _messages.value = if (restoredBooks == null) "恢复失败：$detail"
                else "已恢复 $restoredBooks 本书，但附加数据恢复失败：$detail"
            } finally {
                _openBook.value = null
                try {
                    loadShelf()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _messages.value = "刷新书架失败：${error.message ?: "读取失败"}"
                } finally {
                    _busy.value = false
                }
            }
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
            // 先找内容相同的；再找同一来源的（原文件之后改过，比如作者追更）：打开书架上那本，提示可刷新，
            // 而不是再导入一份（导入会因为“已在书架上”被拒绝，书就打不开了）。
            var changedSource = false
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    library.findSame(picked.single())
                        ?: library.findBySource(picked.single())?.also { changedSource = true }
                        ?: library.add(picked, null)
                }
            }
            loadShelf()
            _busy.value = false
            entry.onSuccess {
                _stack.value = listOf(Screen.Shelf, Screen.Reader(it.id))
                if (changedSource) _messages.value = "原文件有改动，打开的是书架上的版本；要更新可在书架长按这本书选“刷新”"
            }.onFailure { _messages.value = "打不开 ${picked.single().name}：${it.message ?: "读取失败"}" }
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val before = _settings.value.chineseConvert
        val goalBefore = _settings.value.dailyGoal to _settings.value.goalReminder
        _settings.update(transform)
        prefs.saveSettings(_settings.value)
        if (_settings.value.dailyGoal to _settings.value.goalReminder != goalBefore) {
            com.xycs.lingyuereader.reminder.GoalReminder.schedule(getApplication())
        }
        // 换了繁简转换方向：重新打开当前这本书。
        if (_settings.value.chineseConvert != before) {
            (_openBook.value?.entry?.id ?: loadingBookId)?.let { id ->
                _openBook.value = null
                cancelBookLoad()
                ensureLoaded(id)
            }
        }
    }

    // ---------- 字体 ----------

    private val fontStore = FontStore(java.io.File(app.filesDir, "fonts"))
    private val _fonts = MutableStateFlow(fontStore.list())
    val fonts: StateFlow<List<String>> = _fonts.asStateFlow()
    private val fontFamilies = HashMap<String, androidx.compose.ui.text.font.FontFamily>()

    /** 导入字体并直接选用最后一个。 */
    fun importFonts(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val results = withContext(Dispatchers.IO) {
                library.describe(uris).map { p -> runCatching { fontStore.import(p.name, resolver.openInputStream(p.uri)!!) } }
            }
            _fonts.value = withContext(Dispatchers.IO) { fontStore.list() }
            results.lastOrNull { it.isSuccess }?.getOrNull()?.let { name -> updateSettings { it.copy(font = name) } }
            val failed = results.count { it.isFailure }
            if (failed > 0) _messages.value = "有 $failed 个文件不是 TTF/OTF 字体，没有导入"
        }
    }

    fun deleteFont(name: String) {
        if (_settings.value.font == name) updateSettings { it.copy(font = "") }
        synchronized(fontFamilies) { fontFamilies.remove(name) }
        viewModelScope.launch { _fonts.value = withContext(Dispatchers.IO) { fontStore.delete(name); fontStore.list() } }
    }

    /** 画书摘卡片用的字体：导入的字体，或按设置用衬线/无衬线。 */
    fun cardTypeface(settings: ReaderSettings): android.graphics.Typeface =
        fontStore.file(settings.font)?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
            ?: if (settings.serif) android.graphics.Typeface.SERIF else android.graphics.Typeface.SANS_SERIF

    /** 设置里选的导入字体；文件不在了返回空（退回内置字体）。 */
    fun fontFamily(name: String): androidx.compose.ui.text.font.FontFamily? {
        if (name.isEmpty()) return null
        synchronized(fontFamilies) {
            fontFamilies[name]?.let { return it }
            val file = fontStore.file(name) ?: return null
            return androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(file)).also { fontFamilies[name] = it }
        }
    }

    private val converters = HashMap<ChineseConvert, ChineseConverter>()

    /** 按设置做繁简转换；转换表第一次用到时才从安装包读出。 */
    private fun converted(content: BookContent, mode: ChineseConvert): BookContent {
        if (mode == ChineseConvert.NONE) return content
        val converter = synchronized(converters) {
            converters.getOrPut(mode) {
                // 仓库里是 .tsv.gz，打包时 Android Gradle 插件会解压并去掉 .gz。
                val name = if (mode == ChineseConvert.TO_TRADITIONAL) "dict/s2t.tsv" else "dict/t2s.tsv"
                getApplication<Application>().assets.open(name).use { ChineseConverter(it) }
            }
        }
        return converter.convert(content)
    }

    fun search(query: String) {
        _searchQuery.value = query
        val content = _openBook.value?.content ?: return
        viewModelScope.launch {
            _searchHits.value = withContext(Dispatchers.Default) { BookParser.search(content, query) }
        }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Reader && screen.bookId != _returnPoint.value?.bookId) _returnPoint.value = null
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
        // 搜索前读到的地方就是存下的进度。
        _openBook.value?.entry?.takeIf { it.id == bookId }?.progress?.let { p ->
            markJump(ReturnPoint(bookId, p.chapterIndex, p.itemIndex - com.xycs.lingyuereader.ui.HEADER_ITEMS, p.itemOffset, p.charOffset, p.chapterTitle))
        }
        _stack.update { stack ->
            stack.dropLastWhile { it !is Screen.Reader }.dropLast(1) + Screen.Reader(bookId, chapterIndex, blockIndex, highlight = true)
        }
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        if (_stack.value.last() is Screen.Reader) _returnPoint.value = null
        _stack.update { it.dropLast(1) }
        return true
    }
}
