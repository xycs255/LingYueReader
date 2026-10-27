package com.xycs.lingyuereader

import androidx.core.net.toUri
import androidx.core.content.edit
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
import com.xycs.lingyuereader.tts.OfflineEngine
import com.xycs.lingyuereader.tts.OfflineModel
import com.xycs.lingyuereader.tts.OfflineModelFiles
import com.xycs.lingyuereader.tts.OfflineModels
import com.xycs.lingyuereader.tts.OfflinePhase
import com.xycs.lingyuereader.tts.OfflineVoiceConfig
import com.xycs.lingyuereader.tts.OfflineVoiceException
import com.xycs.lingyuereader.tts.OfflineVoiceStore
import com.xycs.lingyuereader.tts.ReadAloud
import com.xycs.lingyuereader.tts.AiVoice
import com.xycs.lingyuereader.tts.ReadAloudEngine
import com.xycs.lingyuereader.tts.VoiceOption
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
import com.xycs.lingyuereader.ai.AiTalk
import com.xycs.lingyuereader.ai.Talk
import com.xycs.lingyuereader.ai.TalkScope
import com.xycs.lingyuereader.ai.TalkStyle
import com.xycs.lingyuereader.tts.TalkPlayer
import com.xycs.lingyuereader.ai.AiSummaries
import com.xycs.lingyuereader.ai.AiCompanion
import com.xycs.lingyuereader.ai.ChapterCompanion
import com.xycs.lingyuereader.ai.chapterExcerpt
import com.xycs.lingyuereader.ai.SummaryRange
import com.xycs.lingyuereader.ai.AiTranslate
import com.xycs.lingyuereader.ai.TranslateTarget
import com.xycs.lingyuereader.ai.Translations
import com.xycs.lingyuereader.ai.AiStudy
import com.xycs.lingyuereader.ai.AiTurn
import com.xycs.lingyuereader.ai.AiCharacters
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AnswerLength
import com.xycs.lingyuereader.ai.BookCharacter
import com.xycs.lingyuereader.ai.CharacterTable
import com.xycs.lingyuereader.ai.AiHistoryItem
import com.xycs.lingyuereader.ai.AiTask
import com.xycs.lingyuereader.ai.NoteRange
import com.xycs.lingyuereader.ai.AiPrompts
import com.xycs.lingyuereader.ai.AiSession
import com.xycs.lingyuereader.ai.AiStore
import com.xycs.lingyuereader.ai.QuickAsk
import com.xycs.lingyuereader.ai.ReadPos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 预览的作品在书架上的分组。 */
const val WORKS_GROUP = "我的作品"

/** 首页底部导航的五个标签。 */
enum class HomeTab(val label: String) { Shelf("书架"), Write("写作"), Vocabulary("生词本"), Stats("统计"), Me("我的") }

private val readerSeq = java.util.concurrent.atomic.AtomicLong()

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
        /** 每次跳转都不同：再跳一次同样的目标（比如在目录里点本章，回到章首）也算新的一页，会真的跳过去。 */
        val seq: Long = readerSeq.incrementAndGet(),
    ) : Screen
    data class Search(val bookId: String) : Screen
    /** 在书架上所有书的正文里搜索。 */
    data object LibrarySearch : Screen
    data class Detail(val bookId: String) : Screen
    data object Dictionaries : Screen
    /** AI 助手设置：服务商、模型、密钥。 */
    data object Ai : Screen
    /** 离线语音：下载模型、选声音。 */
    data object OfflineVoice : Screen
    /** 书源列表：OPDS 书目、WebDAV 文件夹。 */
    data object Catalogs : Screen
    /** 浏览一个书源的某一页（[url] 为空时是书源的首页）。 */
    /** 添加（[catalogId] 为空）或编辑书源。 */
    data class CatalogEdit(val catalogId: String?) : Screen
    data class CatalogBrowse(val catalogId: String, val url: String = "", val title: String = "") : Screen
    /** 主题设置（界面的深浅色、配色、玻璃效果等）。 */
    data object Theme : Screen
    data class BookRules(val bookId: String) : Screen
    /** 一部作品：卷章目录、作品信息、导出与预览。 */
    data class Work(val workId: String) : Screen
    /** 写一章。 */
    /** [check] 为真时打开就展开规则检查（从全书检查点进来）。 */
    data class Editor(val workId: String, val chapterId: String, val check: Boolean = false) : Screen
    /** 一章的历史版本。 */
    /** 一章（或设定包里一份文档，[docId] 不为空时）的历史版本。 */
    data class WorkHistory(val workId: String, val chapterId: String, val docId: String? = null) : Screen
    /** 一部作品的 AI 设置：写作建议、文风、学到的改法。 */
    data class WorkAi(val workId: String) : Screen
    /** 一部作品的设定包：总纲、细纲、人物档案、规范等分类文档。 */
    data class WorkPack(val workId: String) : Screen
    /** 一部作品的故事账本；从编辑器进来时带着 [chapterId]，直接翻到这一章。 */
    data class WorkLedger(val workId: String, val chapterId: String? = null) : Screen
    /** 设定包里的一份文档。 */
    data class PackDoc(val workId: String, val docId: String) : Screen
    /** 一章的细纲卡。 */
    data class ChapterCard(val workId: String, val chapterId: String) : Screen
    /** 一部作品的变更登记。 */
    data class WorkChanges(val workId: String) : Screen

    data class WorkRules(val workId: String) : Screen

    data class ChapterBrief(val workId: String, val chapterId: String) : Screen

    data class ChapterReview(val workId: String, val chapterId: String) : Screen

    data class Foreshadows(val workId: String) : Screen

    data class ChapterDraft(val workId: String, val chapterId: String) : Screen

    data class WorkAiLog(val workId: String) : Screen

    /** AI 用量：今天、本月、按功能、按作品；[workId] 不为空时先只看这部作品。 */
    data class AiUsage(val workId: String?) : Screen

    /** 作品的文风范例：看看挑了哪几段，换一段、不用这段。 */
    data class WorkStyleSamples(val workId: String) : Screen

    /** 作者导入的外部范本：按真实章节与片段分析写法，并对照当前写作章。 */
    data class WritingReference(val workId: String, val chapterId: String? = null) : Screen

    /** 整理进设定包的结果：新设定、冲突、察觉，勾选后写进设定包。 */
    data class ChapterSync(val workId: String) : Screen

    /** 一部作品的排版设置：各条规则、引号风格、输入时自动排版。 */
    data class WorkFormatSettings(val workId: String) : Screen

    /** 全书排版：先看各章要改几处，再按章排（跳过定稿的章）。 */
    data class WorkFormat(val workId: String) : Screen
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

    companion object {
        /** Activity 销毁后仍完成已接收的阅读进度写盘。 */
        private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val progressRevisions = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
        /** 备份时写作里跳过的一项（整句说明），和“生词本”“阅读统计”这类短名分开说。 */
        private const val WRITING_LEFT = "\u0000写作:"
    }

    private val library = com.xycs.lingyuereader.data.SharedLibrary.get(app)
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

    private val workStore = com.xycs.lingyuereader.data.WorkStore(java.io.File(app.filesDir, "writing"))

    /** 写作：作品、正文、码字统计。 */
    val writing = WritingModel(workStore, viewModelScope) { _messages.value = it }

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
    /**
     * 正在进行的耗时操作（导入、刷新、备份、恢复、导入词典……）有几个：一个做完不能把“忙”清掉，
     * 不然别的还在导入时进度条就没了、“+”又能点了。只在主线程上改。
     */
    private var busyCount = 0

    private fun beginBusy() {
        busyCount++
        _busy.value = true
    }

    private fun endBusy() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        _busy.value = busyCount > 0
    }
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

    /** 一次书内搜索的完整状态；查询、结果和运行标记一起更新，避免短暂误报“没有找到”。 */
    data class BookSearchState(
        val query: String = "",
        val hits: List<SearchHit> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
    )

    private val _bookSearch = MutableStateFlow(BookSearchState())
    val bookSearch: StateFlow<BookSearchState> = _bookSearch.asStateFlow()

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
            // 启动时没撤销完的书架事务：书架降为只读，告诉读者。
            library.startupProblem?.let { _messages.value = it }
            // 上次没收拾完的临时目录、无主的书目录，在后台清理。
            launch(Dispatchers.IO) { runCatching { library.cleanUpLeftovers() } }
            _shelfLoaded.value = true
            maybeAutoBackup()
            com.xycs.lingyuereader.reminder.GoalReminder.schedule(getApplication())
        }
        viewModelScope.launch { _dictSettings.value = withContext(Dispatchers.IO) { dictionaries.settings() } }
        reloadVocabulary()
        reloadStats()
        writing.reload()
        // 听书存下的进度（由 ReadAloud 写盘，界面关了也照存）：这里只更新内存里的书架，打开书时从那里读起。
        viewModelScope.launch {
            ReadAloud.saved.collect { saved ->
                val (id, progress) = saved ?: return@collect
                _shelf.update { list -> list.map { if (it.id == id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it } }
                _openBook.update { b -> if (b?.entry?.id == id) b.copy(entry = b.entry.copy(progress = progress)) else b }
            }
        }
    }

    /** 从当前阅读位置开始听书：开了离线语音就用手机上的模型，设了 AI 朗读就用服务商的语音，否则系统语音。 */
    fun startReadAloud(chapterIndex: Int, block: Int, char: Int, startPaused: Boolean = false) {
        val book = _openBook.value ?: return
        ReadAloud.start(
            getApplication(), book.entry.id, book.entry.title, book.content,
            chapterIndex, block.coerceAtLeast(0), char, _settings.value.ttsRate,
            _settings.value.ttsVoice.ifEmpty { null },
            ai = offlineVoice() ?: aiVoice(),
            saveProgress = listeningSaver(book.entry.id, book.sourceContent),
            startPaused = startPaused,
        )
    }

    /** 听书存进度的方法：按原文取锚点，写进书架（在 ReadAloud 的后台线程里调用）。 */
    private fun listeningSaver(id: String, source: com.xycs.lingyuereader.data.BookContent): (Int, Int, Int) -> ReadingProgress? {
        val lib = library
        return { chapterIndex, block, char ->
            source.chapters.getOrNull(chapterIndex)?.takeIf { it.blocks.getOrNull(block) != null }?.let { chapter ->
                ReadingProgress(chapterIndex, chapter.title, block + 1, 0, char, TextAnchors.capture(chapter, block, char)).also { lib.saveProgress(id, it) }
            }
        }
    }


    /** AI 朗读用的语音：AI 助手和 AI 朗读都开着、填全了才有。 */
    private fun aiVoice(): AiVoice? {
        val c = aiConfig.value
        if (!c.speechReady) return null
        val sp = c.speech
        // 听书是全应用的单例，比 ViewModel 活得久：只带上合成要用的客户端，不要引用整个 ViewModel。
        val client = assistant.client
        return AiVoice(
            sp.label,
            sp.voices.map { VoiceOption(it.first, it.second) },
            sp.voice,
            fingerprint = { v -> sp.fingerprint(v) },
            // 按开始听书时的设置合成，和缓存键用的是同一份：中途改了设置，换引擎或重新开始听书后才生效。
            fetch = { v, text -> client.speech(sp, text, v) },
        )
    }

    fun setReadAloudVoice(name: String?) {
        if (ReadAloud.state.value?.offline == true) {
            if (name != null) saveOfflineConfig(_offlineVoice.value.config.withVoice(name))
        } else if (ReadAloud.state.value?.aiLabel != null) {
            // AI 语音：记进 AI 朗读的设置。
            if (name != null) saveAiConfig(aiConfig.value.let { it.copy(speech = it.speech.copy(voice = name)) })
        } else {
            updateSettings { it.copy(ttsVoice = name.orEmpty()) }
        }
        ReadAloud.setVoice(name)
    }

    /** 听书时换声音（系统、离线、AI）：从当前这句接着读。 */
    fun setReadAloudEngine(engine: ReadAloudEngine) {
        setOfflineEnabled(engine == ReadAloudEngine.OFFLINE)
        if (engine != ReadAloudEngine.OFFLINE) saveAiConfig(aiConfig.value.let { it.copy(speech = it.speech.copy(enabled = engine == ReadAloudEngine.ONLINE)) })
        val s = ReadAloud.state.value ?: return
        if (_openBook.value?.entry?.id != s.bookId) return
        val at = s.current
        startReadAloud(s.chapterIndex, at?.block ?: 0, at?.start ?: 0, startPaused = !s.playing)
    }

    /** 设置页的“试听”：用当前的 AI 朗读设置读一句。 */
    private var preview: com.xycs.lingyuereader.tts.AiSpeaker? = null

    fun previewSpeech(onError: (String) -> Unit) {
        val sp = aiConfig.value.speech
        val voice = AiVoice(sp.label, emptyList(), sp.voice, fingerprint = { v -> sp.fingerprint(v) }, fetch = { v, text -> assistant.speech(text, v, sp) })
        playPreview(voice, "你好，我是灵阅的 AI 朗读。晋太元中，武陵人捕鱼为业，缘溪行，忘路之远近。", onError)
    }

    /** 设置页的“试听”：用 [voice] 读一句，和听书走同一个播放器、同一份缓存。 */
    private fun playPreview(voice: AiVoice, text: String, onError: (String) -> Unit) {
        preview?.release()
        preview = com.xycs.lingyuereader.tts.AiSpeaker(
            java.io.File(getApplication<Application>().cacheDir, voice.cacheName),
            fetch = { t, _ -> voice.fetch(voice.voice, t) },
            fingerprint = voice.fingerprint(voice.voice),
            onStart = {},
            onDone = {},
            onError = { _, message -> onError(message) },
        ).also { it.play(listOf("preview" to text)) }
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
                synchronized(reportedWriteFailures) { reportedWriteFailures.remove(what) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (synchronized(reportedWriteFailures) { reportedWriteFailures.add(what) }) _messages.value = "${what}失败：${error.message ?: "写入出错"}"
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
        viewModelScope.launch {
            try {
                _stats.value = withContext(Dispatchers.IO) { stats.days() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "阅读统计读取失败，原文件已保留：${error.message ?: "请从备份恢复"}"
            }
        }
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
            try {
                stats.addChars(chars)
                _stats.value = stats.days()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "阅读统计保存失败，原文件已保留：${error.message ?: "写入出错"}"
            }
        }
    }

    fun addReadingTime(bookId: String, millis: Long) {
        flushReadChars()
        if (millis < 1_000) return
        _shelf.update { list -> list.map { if (it.id == bookId) it.copy(readMillis = it.readMillis + millis) else it } }
        viewModelScope.launch(Dispatchers.IO) {
            // 书架索引写不进去时，阅读统计照样记。
            runCatching { library.addReadingTime(bookId, millis) }
            try {
                stats.addTime(bookId, millis)
                _stats.value = stats.days()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "阅读统计保存失败，原文件已保留：${error.message ?: "写入出错"}"
            }
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
            beginBusy()
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
                    endBusy()
                    _importing.value = false
                    importJob = null
                }
            }
        }
    }

    fun refresh(entry: BookEntry) {
        viewModelScope.launch {
            beginBusy()
            val result = withContext(Dispatchers.IO) { runCatching { library.refresh(entry) } }
            loadShelf()
            if (result.getOrNull()?.changed?.let { it > 0 } == true) bookContentChanged(entry.id)
            endBusy()
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

    /** 按 [rules] 试分章，返回章名列表。正则太慢时抛 RegexTooSlowException；取消时不再等正则跑完。 */
    suspend fun previewChapters(entry: BookEntry, rules: TextRules): List<String> =
        kotlinx.coroutines.runInterruptible(Dispatchers.Default) { library.previewWith(entry, rules).chapters.map { it.title } }

    fun saveRules(entry: BookEntry, rules: TextRules) {
        viewModelScope.launch {
            beginBusy()
            // 不管成败（包括正则太慢被放弃），“忙”都要结束，不然导入、下载都会一直提示“正在处理其他任务”。
            val result = try {
                withContext(Dispatchers.IO) { runCatching { library.saveRules(entry, rules) } }.also {
                    loadShelf()
                    if (it.isSuccess) bookContentChanged(entry.id)
                }
            } finally {
                endBusy()
            }
            _messages.value = result.fold({ "已按新规则重新整理：${it.chapters.size} 章" }, { "保存失败：${it.message}" })
            // 整理期间读者可能已经离开规则页（比如打开了这本书）：只在规则页还在最上面时才返回。
            if (result.isSuccess && (_stack.value.lastOrNull() as? Screen.BookRules)?.bookId == entry.id) back()
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
            if (removed.isSuccess) {
                if (ReadAloud.state.value?.bookId == entry.id) ReadAloud.stop()
                if (_openBook.value?.entry?.id == entry.id) _openBook.value = null
                // 这本书的阅读页、详情、搜索、规则页都从返回栈里拿掉，不再返回到已删的书（一直转圈）。
                _stack.update { stack ->
                    stack.filterNot { s ->
                        when (s) {
                            is Screen.Reader -> s.bookId == entry.id
                            is Screen.Detail -> s.bookId == entry.id
                            is Screen.Search -> s.bookId == entry.id
                            is Screen.BookRules -> s.bookId == entry.id
                            else -> false
                        }
                    }.ifEmpty { listOf(Screen.Shelf) }
                }
            }
            loadShelf()
        }
    }

    /**
     * 这本书的正文变了（刷新、改分章规则、从备份恢复）：正在听这本书就停下（按旧章节读、存进度会错位），
     * 按章号缓存的速读、翻译、伴读、讲书状态清掉重读，正打开着就重新加载（不然阅读页一直转圈）。
     */
    private fun bookContentChanged(id: String) {
        if (ReadAloud.state.value?.bookId == id) ReadAloud.stop()
        if (_summaries.value.bookId == id) { summaryJob?.cancel(); _summaries.value = SummaryState() }
        if (_translate.value.bookId == id) { translateJob?.cancel(); _translate.value = TranslateState() }
        if (_companion.value.bookId == id) _companion.value = CompanionState()
        if (_talk.value.bookId == id) { stopTalk(); _talk.value = TalkState() }
        if (_openBook.value?.entry?.id == id || loadingBookId == id) {
            _openBook.value = null
            cancelBookLoad()
            ensureLoaded(id)
        }
    }

    private fun cancelBookLoad() {
        bookLoadGeneration++
        bookLoadJob?.cancel()
        bookLoadJob = null
        loadingBookId = null
    }

    /** 阅读页进来时调用；已加载的同一本书不重复解析。 */
    /** 最近打开的书（重新加载同一本书时 _openBook 会暂时为空，靠它分辨是不是换了书）。 */
    private var lastBookId: String? = null
    private var corruptMarksBookId: String? = null

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
        // 换了一本书：上一本还在跑的整理笔记、速读、翻译停下（已完成的部分已存盘），免得结果写进这本书的状态。
        if (lastBookId != null && lastBookId != bookId) {
            notesJob?.cancel()
            summaryJob?.cancel()
            translateJob?.cancel()
            characterJob?.cancel()
        }
        lastBookId = bookId
        _openBook.value = null
        loadingBookId = bookId
        bookLoadJob = viewModelScope.launch {
            try {
                val mode = _settings.value.chineseConvert
                val (loaded, marks, corruptMarks) = withContext(Dispatchers.IO) {
                    val source = library.content(entry)
                    // AI 译文（开了对照显示时）接在段落后面，原文字位不变。
                    val content = AiTranslate.apply(converted(source, mode), AiTranslate.read(library.bookFile(entry.id, AiTranslate.FILE)), source)
                    val (stored, corruptMarks) = try {
                        library.marks(entry.id) to false
                    } catch (_: Library.CorruptMarksException) {
                        Marks() to true
                    }
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
                    Triple(OpenBook(if (progress != null) entry.copy(progress = progress) else entry, content, source), marks, corruptMarks)
                }
                if (generation != bookLoadGeneration) return@launch
                corruptMarksBookId = if (corruptMarks) entry.id else null
                _marks.value = marks
                _openBook.value = loaded
                if (corruptMarks) _messages.value = "《${entry.title}》的书签批注文件损坏，原文件已保留；已停止写入，可从备份恢复"
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

    /** 最近一次已经到达、存过进度的阅读页（按实例比较：同样的跳转再来一次是新的一页）。 */
    private var landed: Screen.Reader? = null
    private val lastSavedReaderSeq = HashMap<String, Long>()

    private data class ProgressWrite(val bookId: String, val progress: ReadingProgress, val revision: Long)

    fun landedOn(screen: Screen.Reader): Boolean = landed === screen

    fun saveProgress(chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int = 0) {
        val bookId = _openBook.value?.entry?.id ?: return
        saveProgressFor(bookId, chapterIndex, itemIndex, itemOffset, charOffset)
    }

    /** 页面退出或进后台时立即保存，旧页面不能覆盖随后打开的另一本书。 */
    fun saveProgressFor(bookId: String, chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int, screenSeq: Long? = null) {
        val write = recordProgressFor(bookId, chapterIndex, itemIndex, itemOffset, charOffset, screenSeq, force = false) ?: return
        writeLater("保存阅读进度") {
            synchronized(library) {
                if (progressRevisions[write.bookId]?.get() == write.revision) library.saveProgress(write.bookId, write.progress)
            }
        }
    }

    /** 生命周期退出时最多等待 300ms；磁盘被恢复事务占用时，应用级任务继续写盘。 */
    fun flushProgressFor(bookId: String, chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int, screenSeq: Long) {
        val write = recordProgressFor(bookId, chapterIndex, itemIndex, itemOffset, charOffset, screenSeq, force = true) ?: return
        val job = progressScope.launch {
            try {
                synchronized(library) {
                    if (progressRevisions[write.bookId]?.get() == write.revision) library.saveProgress(write.bookId, write.progress)
                }
                synchronized(reportedWriteFailures) { reportedWriteFailures.remove("保存阅读进度") }
            } catch (error: Exception) {
                val report = synchronized(reportedWriteFailures) { reportedWriteFailures.add("保存阅读进度") }
                if (report) _messages.value = "保存阅读进度失败：${error.message ?: "写入出错"}"
            }
        }
        runBlocking { withTimeoutOrNull(300L) { job.join() } }
    }

    private fun recordProgressFor(bookId: String, chapterIndex: Int, itemIndex: Int, itemOffset: Int, charOffset: Int, screenSeq: Long?, force: Boolean): ProgressWrite? {
        val book = _openBook.value ?: return null
        if (book.entry.id != bookId) return null
        if (screenSeq != null) {
            val currentReader = _stack.value.filterIsInstance<Screen.Reader>().lastOrNull { it.bookId == bookId }
            if (currentReader != null && currentReader.seq != screenSeq) return null
            if (currentReader == null && screenSeq < (lastSavedReaderSeq[bookId] ?: Long.MIN_VALUE)) return null
            lastSavedReaderSeq[bookId] = screenSeq
        }
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return null
        val anchor = TextAnchors.capture(chapter, itemIndex - 1, charOffset)
        val progress = ReadingProgress(chapterIndex, chapter.title, itemIndex, itemOffset, charOffset, anchor)
        // 跳转的目标已经到了、存过进度：之后重建阅读页就从进度读起（见 ReaderScreen 的 startOf）。
        (_stack.value.lastOrNull() as? Screen.Reader)?.takeIf { it.bookId == book.entry.id && it.chapterIndex == chapterIndex }?.let { landed = it }
        if (book.entry.progress == progress && !force) return null
        if (book.entry.progress != progress) {
            movesSinceJump++
            _openBook.value = book.copy(entry = book.entry.copy(progress = progress))
            _shelf.update { list -> list.map { if (it.id == book.entry.id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it } }
        }
        val revision = progressRevisions.getOrPut(bookId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        return ProgressWrite(bookId, progress, revision)
    }

    private fun updateMarks(transform: (Marks) -> Marks): Boolean {
        val book = _openBook.value ?: return false
        if (corruptMarksBookId == book.entry.id) {
            _messages.value = "书签批注文件损坏，原文件已保留；请从备份恢复后再编辑"
            return false
        }
        _marks.update(transform)
        val snapshot = _marks.value
        writeLater("保存书签批注") { library.saveMarks(book.entry.id, snapshot) }
        return true
    }

    /** 在某块的某个位置加或删书签（同一块已有书签时删除）。 */
    fun toggleBookmark(chapterIndex: Int, blockIndex: Int, charOffset: Int) {
        val book = _openBook.value ?: return
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return
        val itemIndex = blockIndex + 1
        val existing = _marks.value.bookmarks.filter { it.chapterIndex == chapterIndex && it.itemIndex == itemIndex }
        if (existing.isNotEmpty()) {
            if (updateMarks { m -> m.copy(bookmarks = m.bookmarks - existing.toSet()) }) _messages.value = "已删除书签"
            return
        }
        val excerpt = chapter.blocks.getOrNull(blockIndex)?.displayText?.trim()?.take(40) ?: chapter.title
        val bookmark = Bookmark(
            UUID.randomUUID().toString(), chapterIndex, chapter.title, itemIndex, charOffset, excerpt, System.currentTimeMillis(),
            anchor = TextAnchors.capture(chapter, blockIndex, charOffset),
        )
        if (updateMarks { m -> m.copy(bookmarks = m.bookmarks + bookmark) }) _messages.value = "已加书签"
    }

    fun deleteBookmark(id: String) { updateMarks { m -> m.copy(bookmarks = m.bookmarks.filterNot { it.id == id }) } }

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

    fun deleteNote(id: String) { updateMarks { m -> m.copy(notes = m.notes.filterNot { it.id == id }) } }

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
        val seen = page.items.mapTo(HashSet()) { it.key }
        return page.copy(items = page.items + more.items.filter { seen.add(it.key) }, next = more.next).also { synchronized(remotePages) { remotePages["${catalog.id}|$url"] = it } }
    }

    suspend fun remoteSearch(page: RemotePage, query: String): RemotePage = remote.search(page, query)

    /** 封面缩略图；取不到时为空。 */
    suspend fun remoteCover(url: String): androidx.compose.ui.graphics.ImageBitmap? {
        covers.get(url)?.let { return it }
        val bytes = runCatching { remote.image(url) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 || height <= 0 || width > 32_768 || height > 32_768 || width.toLong() * height > 128_000_000L) return@runCatching null
                var sample = 1
                while (width / sample > 512 || height / sample > 512) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
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

    // ---------- 离线语音 ----------

    /** 离线语音：设置、装好的模型、正在下载或导入的进度、出错说明。 */
    data class OfflineVoiceState(
        val config: OfflineVoiceConfig = OfflineVoiceConfig(),
        val installed: Set<String> = emptySet(),
        /** 正在装的模型、阶段、已完成和总量。 */
        val task: OfflineTask? = null,
        val error: String? = null,
        /** 装好的模型各占多少空间（字节）。 */
        val sizes: Map<String, Long> = emptyMap(),
    )

    data class OfflineTask(val model: String?, val phase: OfflinePhase, val done: Long = 0, val total: Long = 0) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val offlineStore = OfflineVoiceStore(app)
    private val offlineFiles = OfflineModelFiles(java.io.File(app.filesDir, "tts-models"))
    private val _offlineVoice = MutableStateFlow(OfflineVoiceState(config = offlineStore.load()))
    val offlineVoiceState: StateFlow<OfflineVoiceState> = _offlineVoice.asStateFlow()
    private var offlineJob: Job? = null

    init {
        viewModelScope.launch { refreshOfflineModels() }
    }

    private suspend fun refreshOfflineModels() {
        val (ids, sizes) = withContext(Dispatchers.IO) {
            val ids = offlineFiles.installedIds()
            ids to OfflineModels.ALL.filter { it.id in ids }.associate { it.id to offlineFiles.sizeOnDisk(it) }
        }
        _offlineVoice.update { it.copy(installed = ids, sizes = sizes) }
    }

    fun saveOfflineConfig(c: OfflineVoiceConfig) {
        offlineStore.save(c)
        _offlineVoice.update { it.copy(config = c) }
    }

    /** 开关离线语音；开时关掉 AI 朗读（两样只能用一样，免得开关和实际用的对不上）。可以顺便换模型。 */
    fun setOfflineEnabled(on: Boolean, model: String = _offlineVoice.value.config.model) {
        saveOfflineConfig(_offlineVoice.value.config.copy(enabled = on, model = model))
        if (on && aiConfig.value.speech.enabled) saveAiConfig(aiConfig.value.let { it.copy(speech = it.speech.copy(enabled = false)) })
    }

    /** 听书用的离线语音：开着、选的模型装好了才有。 */
    private fun offlineVoice(): AiVoice? {
        val c = _offlineVoice.value.config
        if (!c.enabled) return null
        return installedOfflineVoice(c)
    }

    /** 选中的离线模型（装好了的话）对应的声音，不管开没开。 */
    private fun installedOfflineVoice(c: OfflineVoiceConfig): AiVoice? {
        val m = c.current
        if (m.id !in _offlineVoice.value.installed) return null
        val dir = offlineFiles.dir(m)
        return AiVoice(
            m.label,
            m.voices,
            c.voice(m),
            fingerprint = { v -> "offline|${m.id}|$v" },
            fetch = { v, text -> OfflineEngine.synthesize(dir, m, v.toIntOrNull() ?: 0, text) },
            offline = true,
        )
    }

    /** 下载模型（读者点了才联网）；装好后选用它。 */
    fun downloadOfflineModel(m: OfflineModel) = runOfflineTask(m.id) {
        offlineFiles.download(m) { phase, done, total -> _offlineVoice.update { s -> s.copy(task = OfflineTask(m.id, phase, done, total)) } }
        m
    }

    /** 从读者选的 .tar.bz2 文件导入模型。 */
    fun importOfflineModel(uri: Uri) = runOfflineTask(null) {
        val resolver = getApplication<Application>().contentResolver
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        offlineFiles.import({ resolver.openInputStream(uri) ?: throw OfflineVoiceException("打不开这个文件") }, size) { phase, done, total ->
            _offlineVoice.update { s -> s.copy(task = OfflineTask(null, phase, done, total)) }
        }
    }

    private fun runOfflineTask(model: String?, work: suspend () -> OfflineModel) {
        if (offlineJob?.isActive == true) return
        // 刚取消的任务可能还没停下（正在写文件）：新任务先等它停，免得两个任务写同一个文件。
        val previous = offlineJob
        _offlineVoice.update { it.copy(task = OfflineTask(model, OfflinePhase.DOWNLOAD), error = null) }
        lateinit var job: Job
        // 只有还是当前任务时才改界面：旧任务停下时不能把新任务的进度条清掉。
        fun mine(change: (OfflineVoiceState) -> OfflineVoiceState) {
            if (offlineJob === job) _offlineVoice.update(change)
        }
        job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            previous?.join()
            try {
                val m = work()
                refreshOfflineModels()
                // 刚装好的模型直接选上、开启。
                setOfflineEnabled(true, m.id)
                mine { it.copy(task = null) }
                _messages.value = "「${m.label}」已装好"
            } catch (e: CancellationException) {
                mine { it.copy(task = null) }
                throw e
            } catch (e: Exception) {
                mine { it.copy(task = null, error = (e as? OfflineVoiceException)?.message ?: "出错了：${e.message}") }
            }
        }
        offlineJob = job
        job.start()
    }

    /** 取消下载（下了的部分留着，下次接着下）或导入。 */
    fun cancelOfflineTask() {
        offlineJob?.cancel()
        _offlineVoice.update { it.copy(task = null) }
    }

    fun deleteOfflineModel(m: OfflineModel) {
        viewModelScope.launch {
            if (ReadAloud.state.value?.offline == true && _offlineVoice.value.config.model == m.id) ReadAloud.stop()
            OfflineEngine.release()
            withContext(Dispatchers.IO) { offlineFiles.delete(m) }
            refreshOfflineModels()
            // 删的是正在用的模型：换成还装着的另一个；一个都不剩就关掉离线语音。
            val c = _offlineVoice.value.config
            if (c.model == m.id) {
                val other = OfflineModels.ALL.firstOrNull { it.id in _offlineVoice.value.installed }
                saveOfflineConfig(if (other != null) c.copy(model = other.id) else c.copy(enabled = false))
            }
        }
    }

    /** 离线语音设置页的“试听”。 */
    fun previewOffline(onError: (String) -> Unit) {
        val voice = installedOfflineVoice(_offlineVoice.value.config) ?: return
        playPreview(voice, "你好，我是灵阅的离线语音，不联网也能为你读书。晋太元中，武陵人捕鱼为业。", onError)
    }

    // ---------- AI ----------

    private val assistant = AiAssistant(viewModelScope, AiStore(app), bookFile = { id, name -> library.bookFile(id, name) })

    /** 写作的 AI：改稿、灵感、点评、矛盾检查、写作建议、设定集。 */
    val writingAi = WritingAi(workStore, assistant, viewModelScope, app.getSharedPreferences("writing_ai", android.content.Context.MODE_PRIVATE)) { _messages.value = it }
    val referenceAi = WritingReferenceAi(workStore, assistant, viewModelScope) { _messages.value = it }
    val writingMethodMap = WritingMethodMap(workStore, assistant, viewModelScope) { _messages.value = it }
    private val companionChoices = app.getSharedPreferences("writing_companion", android.content.Context.MODE_PRIVATE)

    /** Keep a chapter's explicit reference choice even after leaving the editor. */
    fun companionReference(workId: String, chapterId: String): String? =
        companionChoices.getString("$workId/$chapterId", null)

    fun chooseCompanionReference(workId: String, chapterId: String, analysisId: String) {
        companionChoices.edit { putString("$workId/$chapterId", analysisId) }
    }

    fun clearCompanionReference(workId: String, chapterId: String) {
        companionChoices.edit { remove("$workId/$chapterId") }
    }
    val aiConfig: StateFlow<AiConfig> = assistant.config
    val ai: StateFlow<AiSession?> = assistant.session
    val aiGloss: StateFlow<AiGloss?> = assistant.gloss

    fun saveAiConfig(c: AiConfig) {
        // 不用本机模型了：放掉它占的内存。
        if (!c.onDevice) assistant.client.device?.release()
        assistant.saveConfig(c)
    }

    // ---------- AI 用量 ----------

    private val usageStore = com.xycs.lingyuereader.ai.UsageStore(java.io.File(app.filesDir, com.xycs.lingyuereader.ai.UsageStore.FILE))
    /** AI 用量账本（按天、按作品、按功能；只有数字）。账本在锁里写盘时一起更新，几个请求同时结束也不会显示旧账。 */
    val aiUsage: StateFlow<com.xycs.lingyuereader.ai.UsageBook> = usageStore.book

    init {
        viewModelScope.launch(Dispatchers.IO) { usageStore.load() }
        // 每发完一次请求记一笔（在发请求的线程上，写盘很小）；超过每天的提醒线提示一次，不会自动停。
        assistant.client.onUsage = { e ->
            val meter = e.meter
            val book = usageStore.add(meter?.workId.orEmpty(), meter?.feature ?: com.xycs.lingyuereader.ai.AiFeature.OTHER, e.usage, e.cost)
            val c = aiConfig.value
            val today = java.time.LocalDate.now()
            val spent = book.day(today).cost.inCny(c.usdRate)
            // 检查和记下“今天提醒过了”在账本的锁里一起做：几个请求同时越线也只提醒一次。
            if (c.dailyAlert > 0 && spent >= c.dailyAlert && usageStore.markAlerted(today)) {
                _messages.value = "今天 AI 已经花了约 ¥${com.xycs.lingyuereader.ai.AiPricing.amount(spent)}，超过了你设的 ¥${com.xycs.lingyuereader.ai.AiPricing.plain(c.dailyAlert)}（不会自动停，用量页能看明细）"
            }
        }
    }

    /** 清除用量记录。 */
    fun clearAiUsage() {
        viewModelScope.launch(Dispatchers.IO) { usageStore.clear() }
    }

    // ---------- 本机（离线）模型 ----------

    private val deviceFiles = com.xycs.lingyuereader.ai.DeviceModelFiles(java.io.File(app.filesDir, "llm"))

    init {
        // 本机模型的引擎只有 64 位的手机有（32 位的没带）。
        if (com.xycs.lingyuereader.ai.DeviceModels.supported) assistant.client.device = com.xycs.lingyuereader.ai.MediaPipeLlm(app, deviceFiles)
    }

    private val _deviceModels = MutableStateFlow(DeviceModelState())
    /** 本机模型：装好了哪些、正在下载或导入哪个。 */
    val deviceModels: StateFlow<DeviceModelState> = _deviceModels.asStateFlow()
    private var deviceJob: Job? = null

    fun refreshDeviceModels() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { deviceFiles.installedIds() }
            _deviceModels.update { it.copy(installed = ids, sizes = ids.associateWith { id -> deviceFiles.size(id) }) }
        }
    }

    /** 下载本机模型（读者点了才联网）；下好后选用它。 */
    fun downloadDeviceModel(m: com.xycs.lingyuereader.ai.DeviceModel) = runDeviceTask(m.id) {
        deviceFiles.download(m) { phase, done, total -> _deviceModels.update { s -> s.copy(task = DeviceTask(m.id, phase, done, total)) } }
        m.id
    }

    /** 从读者选的 .task 文件导入本机模型。 */
    fun importDeviceModel(uri: Uri) = runDeviceTask(null) {
        val resolver = getApplication<Application>().contentResolver
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        deviceFiles.import(name, { resolver.openInputStream(uri) ?: throw com.xycs.lingyuereader.ai.DeviceModelException("打不开这个文件") }, size) { phase, done, total ->
            _deviceModels.update { s -> s.copy(task = DeviceTask(null, phase, done, total)) }
        }
    }

    private fun runDeviceTask(model: String?, work: suspend () -> String) {
        if (deviceJob?.isActive == true) return
        _deviceModels.update { it.copy(task = DeviceTask(model, com.xycs.lingyuereader.ai.DevicePhase.DOWNLOAD), error = null) }
        deviceJob = viewModelScope.launch {
            try {
                val id = work()
                refreshDeviceModels()
                // 刚装好的直接选用；下载期间换成了别家服务商就不动，不然会把那家的模型名改成本机模型的。
                if (aiConfig.value.onDevice) saveAiConfig(aiConfig.value.copy(model = id))
                _deviceModels.update { it.copy(task = null) }
                _messages.value = "「${com.xycs.lingyuereader.ai.DeviceModels.label(id)}」已装好"
            } catch (e: CancellationException) {
                _deviceModels.update { it.copy(task = null) }
                throw e
            } catch (e: Exception) {
                _deviceModels.update { it.copy(task = null, error = (e as? com.xycs.lingyuereader.ai.DeviceModelException)?.message ?: "出错了：${e.message}") }
            }
        }
    }

    /** 取消下载（下了的部分留着，下次接着下）或导入。 */
    fun cancelDeviceTask() {
        deviceJob?.cancel()
        _deviceModels.update { it.copy(task = null) }
    }

    fun deleteDeviceModel(id: String) {
        viewModelScope.launch {
            if (aiConfig.value.onDevice && aiConfig.value.model == id) {
                assistant.client.device?.release()
                saveAiConfig(aiConfig.value.copy(model = ""))
            }
            withContext(Dispatchers.IO) { deviceFiles.delete(id) }
            refreshDeviceModels()
        }
    }

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
        val pieces = selectionPieces(sel).map { (b, r) -> NoteRange(b, r.first, r.last + 1) }
        _selection.value = null
        assistant.startSelection(book, text, context, chapter?.title.orEmpty(), sel.chapterIndex, pieces)
    }

    /** 这本书的 AI 对话记录，新的在前。 */
    suspend fun aiHistory(): List<AiHistoryItem> {
        val id = _openBook.value?.entry?.id ?: return emptyList()
        return withContext(Dispatchers.IO) { assistant.history(id).list() }
    }

    /** 打开一段旧对话，接着问。 */
    fun resumeAi(id: String) {
        val book = aiBook() ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { assistant.resume(book, id) }
            if (!ok) _messages.value = "这段对话打不开了"
        }
    }

    fun removeAiHistory(id: String) {
        val bookId = _openBook.value?.entry?.id ?: return
        viewModelScope.launch(Dispatchers.IO) { assistant.history(bookId).remove(id) }
    }

    /** 把划选问 AI 的一段回答存成批注：选中的每段划上蓝线，回答记在最后一段上。 */
    fun saveAiAsNote(answer: String) {
        val task = ai.value?.task as? AiTask.Selection ?: return
        val chapter = _openBook.value?.content?.chapters?.getOrNull(task.chapter)
        if (chapter == null || task.pieces.isEmpty() || chapter.title != task.chapterTitle) {
            _messages.value = "找不到原来选的那段文字，没能存成批注"
            return
        }
        task.pieces.forEachIndexed { i, p ->
            addNote(task.chapter, p.block, p.start, p.end, if (i == task.pieces.lastIndex) "AI：" + answer.trim().take(2000) else "", MarkColor.BLUE)
        }
        _messages.value = "已存为批注"
    }

    fun startRecap(chapterIndex: Int, block: Int, char: Int = Int.MAX_VALUE) {
        aiBook()?.let { assistant.startRecap(it, ReadPos(chapterIndex, block, char)) }
    }

    // ---------- 读书笔记 ----------

    /** AI 整理的读书笔记：上次生成的（存在书目录里）、生成中边出边显示。 */
    data class NotesState(
        val bookId: String? = null,
        val text: String = "",
        val loading: Boolean = false,
        val error: String? = null,
        val at: Long = 0L,
        /** 这本书有没有划线批注（没有就没法整理）。 */
        val hasMarks: Boolean = false,
    )

    private val _readingNotes = MutableStateFlow(NotesState())
    val readingNotes: StateFlow<NotesState> = _readingNotes.asStateFlow()
    private var notesJob: Job? = null

    fun loadReadingNotes() {
        val book = _openBook.value ?: return
        if (_readingNotes.value.bookId == book.entry.id && _readingNotes.value.loading) return
        viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiStudy.NOTES_FILE)
            val (text, at) = withContext(Dispatchers.IO) { if (file.isFile) file.readText() to file.lastModified() else "" to 0L }
            _readingNotes.value = NotesState(book.entry.id, text, at = at, hasMarks = _marks.value.notes.isNotEmpty())
        }
    }

    fun buildReadingNotes() {
        val book = _openBook.value ?: return
        val marks = notesMarkdown()
        if (_marks.value.notes.isEmpty() || marks.isNullOrBlank()) {
            _readingNotes.update { it.copy(error = "这本书还没有划线和批注") }
            return
        }
        notesJob?.cancel()
        val aiBook = aiBook() ?: return
        _readingNotes.value = NotesState(book.entry.id, "", loading = true, hasMarks = true)
        notesJob = viewModelScope.launch {
            try {
                val text = assistant.complete(aiBook, AiStudy.notes(book.entry.title, marks), AnswerLength.LONG, whole = false) { piece ->
                    _readingNotes.update { if (it.bookId == book.entry.id) it.copy(text = it.text + piece) else it }
                }
                val file = library.bookFile(book.entry.id, AiStudy.NOTES_FILE)
                withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
                _readingNotes.update { if (it.bookId == book.entry.id) it.copy(text = text, loading = false, at = System.currentTimeMillis()) else it }
            } catch (e: CancellationException) {
                _readingNotes.update { if (it.bookId == book.entry.id) it.copy(loading = false) else it }
                throw e
            } catch (e: Exception) {
                _readingNotes.update { if (it.bookId == book.entry.id) it.copy(loading = false, error = (e as? AiException)?.message ?: "整理失败：${e.message}") else it }
            }
        }
    }

    // ---------- 章节速读 ----------

    /** 速读：这本书各章的摘要（章名对得上的）、正在生成时的进度。 */
    data class SummaryState(
        val bookId: String? = null,
        val summaries: Map<Int, String> = emptyMap(),
        val running: Boolean = false,
        val progress: String? = null,
        val error: String? = null,
    )

    private val _summaries = MutableStateFlow(SummaryState())
    val summaries: StateFlow<SummaryState> = _summaries.asStateFlow()
    private var summaryJob: Job? = null

    fun loadSummaries() {
        val book = _openBook.value ?: return
        if (_summaries.value.bookId == book.entry.id) return
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { AiSummaries.valid(book.content, AiSummaries.read(library.bookFile(book.entry.id, AiSummaries.FILE))) }
            _summaries.value = SummaryState(book.entry.id, map)
        }
    }

    /** 要速读哪些章：读过的、从当前起接下来 10 章、全书；已有摘要的不再生成。 */
    fun summaryTargets(range: SummaryRange, chapterIndex: Int): List<Int> {
        val book = _openBook.value ?: return emptyList()
        val all = book.content.chapters.indices
        val wanted = when (range) {
            SummaryRange.READ -> 0..chapterIndex
            SummaryRange.NEXT -> chapterIndex..(chapterIndex + 9).coerceAtMost(all.last)
            SummaryRange.ALL -> all
        }
        val have = _summaries.value.takeIf { it.bookId == book.entry.id }?.summaries.orEmpty()
        return wanted.filter { it !in have }
    }

    /** 这些章大约多少字。 */
    fun summarySize(indices: List<Int>): Int = _openBook.value?.let { AiSummaries.size(it.content, indices) } ?: 0

    fun buildSummaries(indices: List<Int>) {
        val book = _openBook.value ?: return
        if (summaryJob?.isActive == true || indices.isEmpty()) return
        val aiBook = aiBook() ?: return
        // 结果只写回这本书的状态：生成期间换了一本书，不能把这本的摘要显示到那本上。
        fun mine(change: (SummaryState) -> SummaryState) = _summaries.update { if (it.bookId == book.entry.id) change(it) else it }
        summaryJob = viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiSummaries.FILE)
            val packs = withContext(Dispatchers.IO) { AiSummaries.packs(book.content, indices) }
            _summaries.update { it.copy(bookId = book.entry.id, running = true, error = null, progress = "0/${indices.size}") }
            var done = 0
            try {
                packs.forEach { pack ->
                    val got = AiSummaries.parse(assistant.complete(aiBook, AiSummaries.prompt(pack), AnswerLength.SHORT), pack)
                    // 存不下就停：接着生成的也存不下，白花钱。
                    try {
                        withContext(Dispatchers.IO) {
                            val stored = AiSummaries.load(file).toMutableMap()
                            got.forEach { (i, text) -> stored[i] = book.content.chapters[i].title to text }
                            AiSummaries.write(file, stored)
                        }
                    } catch (e: java.io.IOException) {
                        throw AiException("速读没能保存（${e.message ?: e.javaClass.simpleName}），已停止生成")
                    }
                    done += pack.chapters.size
                    mine { it.copy(summaries = it.summaries + got, progress = "$done/${indices.size}") }
                }
                mine { it.copy(running = false, progress = null) }
            } catch (e: CancellationException) {
                mine { it.copy(running = false, progress = null) }
                throw e
            } catch (e: Exception) {
                mine { it.copy(running = false, progress = null, error = (e as? AiException)?.message ?: "生成失败：${e.message}") }
            }
        }
    }

    fun cancelSummaries() {
        summaryJob?.cancel()
    }

    // ---------- AI 讲书 ----------

    /** 讲书：讲哪部分、什么形式、稿子（没生成时为空）、生成中、出错。 */
    data class TalkState(
        val bookId: String? = null,
        val key: String = "",
        val talk: Talk? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _talk = MutableStateFlow(TalkState())
    val talk: StateFlow<TalkState> = _talk.asStateFlow()
    private val _talkPlayer = MutableStateFlow<TalkPlayer?>(null)
    val talkPlayer: StateFlow<TalkPlayer?> = _talkPlayer.asStateFlow()
    private var talkJob: Job? = null

    /**
     * 打开讲书稿：存过的直接用，没有（或要重新生成）就让 AI 写。
     * [cachedOnly]：面板打开、换范围或形式时只看存过的，没有就等读者点“生成”，不自动发请求花钱。
     */
    fun openTalk(chapterIndex: Int, block: Int, scope: TalkScope, style: TalkStyle, regenerate: Boolean = false, char: Int = Int.MAX_VALUE, cachedOnly: Boolean = false) {
        val book = _openBook.value ?: return
        val chapter = book.content.chapters.getOrNull(chapterIndex) ?: return
        val aiBook = aiBook() ?: return
        // A saved “read so far” script must never be replayed at an earlier position.
        val key = AiTalk.key(scope, style, chapterIndex, chapter.title) +
            if (scope == TalkScope.READ) "-$block-$char" else ""
        if (!regenerate && _talk.value.key == key && _talk.value.bookId == book.entry.id && (_talk.value.talk != null || _talk.value.loading)) return
        talkJob?.cancel()
        stopTalk()
        // 只看存过的时不显示“正在写”：读者没点生成。
        _talk.value = TalkState(book.entry.id, key, loading = !cachedOnly)
        talkJob = viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiTalk.FILE)
            try {
                val cached = if (regenerate) null else withContext(Dispatchers.IO) { AiTalk.read(file)[key] }
                if (cached == null && cachedOnly) return@launch
                var saveError: String? = null
                val talk = cached ?: run {
                    val text = withContext(Dispatchers.IO) {
                        if (scope == TalkScope.CHAPTER) chapterExcerpt(chapter, AiTalk.LIMIT)
                        else AiPrompts.recentText(book.content, ReadPos(chapterIndex, block, char), AiTalk.LIMIT)
                    }
                    if (text.isBlank()) throw AiException("还没读到正文")
                    val answer = assistant.complete(aiBook, AiTalk.prompt(style, scope, chapter.title, text), AnswerLength.LONG)
                    val parsed = AiTalk.parse(answer, style)?.copy(at = System.currentTimeMillis()) ?: throw AiException("AI 没有按格式写稿，请重试")
                    // 存不下也先给读者听（已经花了钱），只说一声下次要重新生成。
                    withContext(Dispatchers.IO) { runCatching { AiTalk.write(file, AiTalk.load(file) + (key to parsed)) } }.onFailure { e ->
                        saveError = "讲书稿没能保存（${e.message ?: e.javaClass.simpleName}），下次打开要重新生成"
                    }
                    parsed
                }
                _talk.update { it.copy(talk = talk, loading = false, error = saveError) }
                _talkPlayer.value = TalkPlayer(getApplication(), talkVoices(style)).also { p -> p.load(talk.lines.map { it.speaker to it.text }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _talk.update { it.copy(loading = false, error = (e as? AiException)?.message ?: "生成失败：${e.message}") }
            }
        }
    }

    /** 讲书用的声音：开了离线语音就用它，AI 朗读设好了就用服务商的（对谈时搭档换一个，尽量男女搭配），否则系统语音。 */
    private fun talkVoices(style: TalkStyle): TalkPlayer.Ai? {
        offlineVoice()?.let { v ->
            val voices = listOf(v.voice, partnerVoice(style, v.voice, v.voices.map { it.name to it.label }))
            return TalkPlayer.Ai(voices, v.fingerprint(""), fetch = { text, voice -> v.fetch(voice ?: v.voice, text) }, cacheName = v.cacheName)
        }
        val c = aiConfig.value
        // 听书面板里选了“系统语音”（关了 AI 朗读）时，讲书也不用付费的 AI 语音。
        if (!c.speechReady) return null
        val sp = c.speech
        val voices = listOf(sp.voice, partnerVoice(style, sp.voice, sp.voices))
        return TalkPlayer.Ai(voices, sp.fingerprint(""), fetch = { text, voice -> assistant.speech(text, voice, sp) })
    }

    /** 对谈时搭档的声音：和主讲异性的第一个，没有就换一个，只有一个声音就同一个。 */
    private fun partnerVoice(style: TalkStyle, main: String, voices: List<Pair<String, String>>): String {
        if (style != TalkStyle.DUO) return main
        val female = "女" in voices.firstOrNull { it.first == main }?.second.orEmpty()
        val others = voices.filter { it.first != main }
        return (others.firstOrNull { if (female) "男" in it.second else "女" in it.second } ?: others.firstOrNull())?.first ?: main
    }

    /** 讲书开始播放：先停下听书，免得两个声音一起响。 */
    fun playTalk(from: Int? = null) {
        val player = _talkPlayer.value ?: return
        ReadAloud.state.value?.takeIf { it.playing }?.let { ReadAloud.pause() }
        if (from != null) player.play(from) else player.toggle()
    }

    fun stopTalk() {
        _talkPlayer.value?.release()
        _talkPlayer.value = null
    }

    // ---------- AI 伴读 ----------

    /** 伴读：哪一章、要点和思考题（没生成时为空）、生成中、出错。 */
    data class CompanionState(
        val bookId: String? = null,
        val chapter: Int = -1,
        val data: ChapterCompanion? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _companion = MutableStateFlow(CompanionState())
    val companion: StateFlow<CompanionState> = _companion.asStateFlow()
    private var companionJob: Job? = null

    /** 打开某章的伴读：存过的直接显示，没有就生成。 */
    fun openCompanion(chapterIndex: Int, regenerate: Boolean = false) {
        val book = _openBook.value ?: return
        val chapter = book.content.chapters.getOrNull(chapterIndex) ?: return
        val aiBook = aiBook() ?: return
        companionJob?.cancel()
        _companion.value = CompanionState(book.entry.id, chapterIndex, loading = true)
        companionJob = viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiCompanion.FILE)
            try {
                val cached = if (regenerate) null else withContext(Dispatchers.IO) { AiCompanion.read(file)[chapterIndex]?.takeIf { it.first == chapter.title }?.second }
                var saveError: String? = null
                val data = cached ?: run {
                    val previous = _summaries.value.takeIf { it.bookId == book.entry.id }?.summaries?.get(chapterIndex - 1)
                    val text = withContext(Dispatchers.IO) { chapterExcerpt(chapter, AiCompanion.LIMIT) }
                    val answer = assistant.complete(aiBook, AiCompanion.prompt(chapter.title, text, previous), AnswerLength.MEDIUM)
                    val parsed = AiCompanion.parse(answer) ?: throw AiException("AI 没有按格式回答，请重试")
                    // 存不下也先显示（已经花了钱），只说一声下次要重新生成。
                    withContext(Dispatchers.IO) { runCatching { AiCompanion.write(file, AiCompanion.load(file) + (chapterIndex to (chapter.title to parsed))) } }.onFailure { e ->
                        saveError = "伴读没能保存（${e.message ?: e.javaClass.simpleName}），下次打开要重新生成"
                    }
                    parsed
                }
                _companion.update { it.copy(data = data, loading = false, error = saveError) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _companion.update { it.copy(loading = false, error = (e as? AiException)?.message ?: "生成失败：${e.message}") }
            }
        }
    }

    /** 和 AI 讨论一道思考题：只把当前阅读位置之前的正文发给它。 */
    fun discuss(question: String, chapterIndex: Int, block: Int, char: Int) {
        startAskBook(chapterIndex, block, char)
        askAi(question)
    }

    // ---------- 整章翻译 ----------

    /** 翻译的状态：这本书的译文、正在翻译哪章、进度、出错。 */
    data class TranslateState(
        val bookId: String? = null,
        val translations: Translations = Translations(),
        val running: Int? = null,
        val progress: String? = null,
        val error: String? = null,
    )

    private val _translate = MutableStateFlow(TranslateState())
    val translate: StateFlow<TranslateState> = _translate.asStateFlow()
    private var translateJob: Job? = null

    fun loadTranslations() {
        val book = _openBook.value ?: return
        if (_translate.value.bookId == book.entry.id) return
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { AiTranslate.read(library.bookFile(book.entry.id, AiTranslate.FILE)) }
            _translate.value = TranslateState(book.entry.id, t)
        }
    }

    /** 默认译成什么：看这一章正文以什么文字为主。 */
    fun defaultTranslateTarget(chapterIndex: Int): TranslateTarget =
        _openBook.value?.let { AiTranslate.defaultTarget(it.sourceContent, chapterIndex) } ?: TranslateTarget.CHINESE

    /** 翻译一章：分批发，全部译完存盘，再按对照显示重新排版（回到读到的位置）。 */
    fun translateChapter(chapterIndex: Int, target: TranslateTarget) {
        val book = _openBook.value ?: return
        if (translateJob?.isActive == true) return
        val aiBook = aiBook() ?: return
        val chapter = book.sourceContent.chapters.getOrNull(chapterIndex) ?: return
        fun mine(change: (TranslateState) -> TranslateState) = _translate.update { if (it.bookId == book.entry.id) change(it) else it }
        translateJob = viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiTranslate.FILE)
            val batches = withContext(Dispatchers.IO) { AiTranslate.batches(converted(book.sourceContent, _settings.value.chineseConvert).chapters[chapterIndex].blocks) }
            _translate.update { it.copy(bookId = book.entry.id, running = chapterIndex, progress = "0/${batches.size}", error = null) }
            val result = HashMap<Int, String>()
            try {
                batches.forEachIndexed { i, batch ->
                    mine { it.copy(progress = "${i}/${batches.size}") }
                    val answer = assistant.complete(aiBook, AiTranslate.prompt(batch, target), AnswerLength.LONG, feature = com.xycs.lingyuereader.ai.AiFeature.TRANSLATE)
                    result.putAll(AiTranslate.parse(answer, batch))
                }
                val hashes = result.keys.associateWith { b -> AiTranslate.hash(chapter.blocks.getOrNull(b)) }
                // 存不下就算没译成：不能提示“已翻译”，旧译文原样留着。
                val next = try {
                    withContext(Dispatchers.IO) {
                        val current = AiTranslate.load(file)
                        current.copy(
                            show = true, target = target,
                            chapters = current.chapters + (chapterIndex to (chapter.title to result)),
                            hashes = current.hashes + (chapterIndex to hashes),
                        ).also { AiTranslate.write(file, it) }
                    }
                } catch (e: java.io.IOException) {
                    throw AiException("译文没能保存（${e.message ?: e.javaClass.simpleName}），本章没有翻译成功")
                }
                mine { it.copy(translations = next, running = null, progress = null) }
                val missing = batches.sumOf { it.size } - result.size
                _messages.value = if (missing > 0) "已翻译本章，有 $missing 段没译出来" else "已翻译本章"
                if (_openBook.value?.entry?.id == book.entry.id) reloadOpenBook()
            } catch (e: CancellationException) {
                mine { it.copy(running = null, progress = null) }
                throw e
            } catch (e: Exception) {
                mine { it.copy(running = null, progress = null, error = (e as? AiException)?.message ?: "翻译失败：${e.message}") }
            }
        }
    }

    fun cancelTranslate() {
        translateJob?.cancel()
    }

    /** 显示或隐藏译文（不删）。 */
    fun setShowTranslation(show: Boolean) = updateTranslations { it.copy(show = show) }

    fun deleteTranslation(chapterIndex: Int) = updateTranslations { it.copy(chapters = it.chapters - chapterIndex, hashes = it.hashes - chapterIndex) }

    private fun updateTranslations(transform: (Translations) -> Translations) {
        val book = _openBook.value ?: return
        viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiTranslate.FILE)
            val next = try {
                withContext(Dispatchers.IO) { transform(AiTranslate.load(file)).also { AiTranslate.write(file, it) } }
            } catch (e: java.io.IOException) {
                _messages.value = "译文设置没能保存：${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            _translate.update { it.copy(bookId = book.entry.id, translations = next) }
            reloadOpenBook()
        }
    }

    /** 重新打开当前这本书（排版要变时）：回到读到的位置。 */
    private fun reloadOpenBook() {
        val id = _openBook.value?.entry?.id ?: return
        _openBook.value = null
        cancelBookLoad()
        ensureLoaded(id)
    }

    // ---------- 生词本 AI ----------

    /** 正在生成的生词例句：词 → 进行中的回答。 */
    private val _vocabAi = MutableStateFlow<Map<String, AiTurn>>(emptyMap())
    val vocabAi: StateFlow<Map<String, AiTurn>> = _vocabAi.asStateFlow()
    private val _vocabFilling = MutableStateFlow(false)
    val vocabFilling: StateFlow<Boolean> = _vocabFilling.asStateFlow()

    /** 生词的 AI 例句和用法：边出边显示，答完存进生词本。 */
    fun vocabExample(word: String) {
        val w = _vocab.value.firstOrNull { it.word == word } ?: return
        if (_vocabAi.value[word]?.loading == true) return
        _vocabAi.update { it + (word to AiTurn(word)) }
        viewModelScope.launch {
            try {
                val text = assistant.complete(null, AiStudy.example(w.word, DictGloss.clean(w.gloss), w.context), AnswerLength.SHORT, whole = false) { piece ->
                    _vocabAi.update { m -> m[word]?.let { t -> m + (word to t.copy(answer = t.answer + piece)) } ?: m }
                }
                _vocab.value = withContext(Dispatchers.IO) { vocabulary.annotate(word, example = text.trim()); vocabulary.list() }
                _vocabAi.update { it - word }
            } catch (e: CancellationException) {
                _vocabAi.update { it - word }
                throw e
            } catch (e: Exception) {
                val message = (e as? AiException)?.message ?: "出错了：${e.message}"
                _vocabAi.update { m -> m + (word to AiTurn(word, loading = false, error = message)) }
            }
        }
    }

    /** 给没有释义的生词一批批补上中文释义（带原句，按语境释义）。 */
    fun fillVocabGlosses() {
        if (_vocabFilling.value) return
        val blanks = _vocab.value.filter { DictGloss.clean(it.gloss).isBlank() }
        if (blanks.isEmpty()) {
            _messages.value = "每个词都有释义了"
            return
        }
        _vocabFilling.value = true
        viewModelScope.launch {
            var filled = 0
            try {
                for (batch in blanks.chunked(AiStudy.GLOSS_BATCH)) {
                    val answer = assistant.complete(null, AiStudy.glosses(batch.map { it.word to it.context }), AnswerLength.SHORT)
                    val got = AiStudy.parseGlosses(answer, batch.map { it.word }.toSet())
                    withContext(Dispatchers.IO) { got.forEach { (w, rg) -> vocabulary.annotate(w, gloss = rg.second, reading = rg.first) } }
                    filled += got.size
                    _vocab.value = withContext(Dispatchers.IO) { vocabulary.list() }
                }
                _messages.value = if (filled > 0) "已给 $filled 个词补上释义" else "没能补上释义，请稍后再试"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.value = ((e as? AiException)?.message ?: "补释义失败：${e.message}") + if (filled > 0) "（已补 $filled 个）" else ""
            } finally {
                _vocabFilling.value = false
            }
        }
    }

    // ---------- 人物表 ----------

    /** 人物表的状态：表（没整理过时为空）、正在整理时的进度、出错、读到处还有多少字没整理。 */
    data class CharacterState(
        val bookId: String? = null,
        val table: CharacterTable? = null,
        val running: Boolean = false,
        val progress: String? = null,
        val error: String? = null,
        val pendingChars: Int = 0,
        val pendingChunks: Int = 0,
    )

    private val _characters = MutableStateFlow(CharacterState())
    val characters: StateFlow<CharacterState> = _characters.asStateFlow()
    private var characterJob: Job? = null

    /** 读这本书的人物表（打开书、打开人物表时调用）；[chapterIndex]、[block] 给出时顺便算还有多少没整理。 */
    fun loadCharacters(chapterIndex: Int? = null, block: Int? = null, char: Int = Int.MAX_VALUE) {
        val book = _openBook.value ?: return
        viewModelScope.launch {
            try {
                val (table, pending) = withContext(Dispatchers.IO) {
                    val table = AiCharacters.read(library.bookFile(book.entry.id, AiCharacters.FILE))
                    val pending = if (chapterIndex != null && block != null) {
                        AiCharacters.chunks(book.content, table?.let { ReadPos(it.chapter, it.block, it.char) }, ReadPos(chapterIndex, block, char))
                    } else null
                    table to pending
                }
                if (_openBook.value?.entry?.id != book.entry.id) return@launch
                _characters.update {
                    val base = if (it.bookId == book.entry.id) it else CharacterState(bookId = book.entry.id)
                    base.copy(
                        table = if (base.running) base.table else table,
                        pendingChars = pending?.sumOf { c -> c.text.length } ?: base.pendingChars,
                        pendingChunks = pending?.size ?: base.pendingChunks,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_openBook.value?.entry?.id == book.entry.id) {
                    _characters.value = CharacterState(book.entry.id, error = "人物表读取失败，原文件已保留：${error.message ?: "请检查文件"}")
                }
            }
        }
    }

    /** 整理人物表：从上次整理到的地方到读者读到的地方，分段发，每段整理完就存盘。 */
    fun buildCharacters(chapterIndex: Int, block: Int, char: Int = Int.MAX_VALUE) {
        val book = _openBook.value ?: return
        if (characterJob?.isActive == true) return
        val aiBook = aiBook() ?: return
        characterJob = viewModelScope.launch {
            val file = library.bookFile(book.entry.id, AiCharacters.FILE)
            try {
                val initial = withContext(Dispatchers.IO) { AiCharacters.read(file) }
                var table = initial
                val chunks = withContext(Dispatchers.IO) {
                    AiCharacters.chunks(book.content, table?.let { ReadPos(it.chapter, it.block, it.char) }, ReadPos(chapterIndex, block, char))
                }
                _characters.value = CharacterState(book.entry.id, table, running = true, pendingChars = chunks.sumOf { it.text.length }, pendingChunks = chunks.size)
                chunks.forEachIndexed { i, chunk ->
                    _characters.update { it.copy(progress = "正在整理第 ${i + 1}/${chunks.size} 段") }
                    val answer = assistant.complete(aiBook, AiCharacters.prompt(table?.characters.orEmpty(), chunk.text), AnswerLength.LONG)
                    val list = AiCharacters.parse(answer)
                    val next = CharacterTable(list, chunk.end.chapter, chunk.end.block, System.currentTimeMillis(), chunk.end.char)
                    withContext(Dispatchers.IO) { AiCharacters.write(file, next) }
                    table = next
                    val rest = chunks.drop(i + 1)
                    _characters.update { it.copy(table = next, pendingChars = rest.sumOf { c -> c.text.length }, pendingChunks = rest.size) }
                }
                _characters.update { it.copy(running = false, progress = null, error = null) }
            } catch (e: CancellationException) {
                _characters.update { it.copy(running = false, progress = null) }
                throw e
            } catch (e: Exception) {
                _characters.update { it.copy(running = false, progress = null, error = (e as? AiException)?.message ?: "整理失败：${e.message}") }
            }
        }
    }

    fun cancelCharacters() {
        characterJob?.cancel()
    }

    /** 查词时认出人名：这本书的人物表里有这个名字或别称。 */
    fun characterFor(word: String): BookCharacter? {
        val s = _characters.value
        if (s.bookId != _openBook.value?.entry?.id) return null
        return s.table?.find(word)
    }

    fun startAskBook(chapterIndex: Int, block: Int, char: Int = Int.MAX_VALUE) {
        aiBook()?.let { assistant.startAskBook(it, ReadPos(chapterIndex, block, char)) }
    }

    fun askAi(q: QuickAsk) = assistant.ask(q)

    fun askAi(question: String) = assistant.ask(question)

    fun regenerateRecap() = assistant.recap()

    fun retryAi() = assistant.retry()

    fun closeAi() = assistant.close()

    override fun onCleared() {
        preview?.release()
        _talkPlayer.value?.release()
        super.onCleared()
    }

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
        viewModelScope.launch {
            try {
                _vocab.value = withContext(Dispatchers.IO) { vocabulary.list() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "生词本读取失败，原文件已保留：${error.message ?: "请从备份恢复"}"
            }
        }
    }

    private fun changeVocabulary(success: String? = null, action: () -> Unit) {
        viewModelScope.launch {
            try {
                _vocab.value = withContext(Dispatchers.IO) { action(); vocabulary.list() }
                if (success != null) _messages.value = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "生词本保存失败，原文件已保留：${error.message ?: "写入出错"}"
            }
        }
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
        changeVocabulary("已收入生词本") { vocabulary.add(entry) }
    }

    fun removeFromVocabulary(word: String) {
        changeVocabulary { vocabulary.remove(word) }
    }

    /** 撤销删除：[w] 是删除前的那条记录，原样放回第 [index] 个位置。 */
    fun restoreToVocabulary(w: VocabWord, index: Int) {
        changeVocabulary { vocabulary.restore(w, index) }
    }

    fun setMastered(word: String, mastered: Boolean) {
        changeVocabulary { vocabulary.setMastered(word, mastered) }
    }

    fun reviewWord(word: String, known: Boolean) {
        changeVocabulary { vocabulary.review(word, known) }
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
            beginBusy()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    dictionaries.import(library.describe(uris)) { p -> resolver.openInputStream(p.uri)!! }
                }
            }
            endBusy()
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
            beginBusy()
            var copyingToDestination = false
            val left = mutableListOf<String>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val staged = File.createTempFile("manual-backup-", ".zip", app.cacheDir)
                    try {
                        // 先完成 ZIP；来源文件读到一半失败时，不会碰已选的目标文件。
                        val count = staged.outputStream().use { library.backup(it, prefs.exportJson(), backupData(left)) }
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
            endBusy()
            _messages.value = result.fold(
                { "已备份 $it 本书" + leftOutNote(left) },
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
            val left = mutableListOf<String>()
            val result = withContext(Dispatchers.IO) {
                autoBackups.run { out -> library.backup(out, prefs.exportJson(), backupData(left)) }
            }
            _autoBackup.value = autoBackups.state()
            // 悄悄做的自动备份少了某项数据时也要说：不然坏文件一直没人发现，好的旧备份被轮换删掉。
            if (announce || (result.isSuccess && left.isNotEmpty())) {
                _messages.value = result.fold({ "已备份到「${_autoBackup.value.label}」：$it" + leftOutNote(left) }, { "备份失败：${it.message}" })
            }
        }
    }

    /**
     * 备份里除了书以外的数据。生词本、阅读统计读不出或解析不过时不打进包（恢复时会被拒），名字记进 [left]，
     * 书和其余数据照常备份。
     */
    private fun backupData(left: MutableList<String>): Map<String, ByteArray> = buildMap {
        fun add(name: String, label: String, raw: () -> ByteArray?, validate: (String) -> Unit) {
            try {
                val bytes = raw() ?: return
                validate(bytes.toString(Charsets.UTF_8))
                put(name, bytes)
            } catch (error: Exception) {
                left += label
            }
        }
        add("vocabulary.json", "生词本", vocabulary::raw, vocabulary::validateBackup)
        add("stats.json", "阅读统计", stats::raw, stats::validateBackup)
        // 写作里读不出的文件单独跳过，不拖垮整份备份；跳过的每一项都说清是哪部作品的哪个文件。
        writing.backupReport()?.let { report ->
            put("writing.json", report.bytes)
            left += report.skipped.map { WRITING_LEFT + it.label }
        }
    }

    private fun leftOutNote(left: List<String>): String {
        val (works, items) = left.partition { it.startsWith(WRITING_LEFT) }
        return buildString {
            if (items.isNotEmpty()) append("；${items.joinToString("、")}读不出，没有放进备份（原文件已保留）")
            works.take(5).forEach { append("；").append(it.removePrefix(WRITING_LEFT)) }
            if (works.size > 5) append("；写作另有 ${works.size - 5} 处没有放进备份")
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            beginBusy()
            cancelBookLoad()
            var restoredBooks: Int? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("无法读取备份文件")
                    // 附加数据有问题不影响恢复书：各项在书恢复之后各自合并，失败的最后一起说。
                    input.use { stream -> library.restore(stream) }
                }
                restoredBooks = result.books
                val failed = mutableListOf<String>()
                val labels = mapOf("vocabulary.json" to "生词本", "stats.json" to "阅读统计", "writing.json" to "写作")
                result.skippedData.forEach { failed += "${labels[it] ?: it}（超过大小上限）" }
                withContext(Dispatchers.IO) {
                    fun merge(name: String, apply: (String) -> Unit) {
                        val json = result.data[name] ?: return
                        try {
                            apply(json)
                        } catch (error: Exception) {
                            failed += "${labels[name] ?: name}（${error.message ?: "读取失败"}）"
                        }
                    }
                    merge("vocabulary.json", vocabulary::merge)
                    merge("stats.json", stats::merge)
                    merge("writing.json") {
                        // Stop old candidate writers before replacing the bytes they were working from.
                        writingAi.forget()
                        val report = writing.restoreBackupReport(it)
                        writingAi.forget()
                        // 本机较新或读不出的作品整部没有恢复：说出来，不然作者以为写作也恢复了。
                        report.skipped.take(5).forEach { skip -> failed += "写作 ${skip.label}" }
                        if (report.skipped.size > 5) failed += "写作另有 ${report.skipped.size - 5} 部没有恢复"
                    }
                }
                reloadVocabulary()
                reloadStats()
                result.settings?.let { settings ->
                    try {
                        prefs.importJson(settings)
                        _settings.value = prefs.settings()
                        _appearance.value = prefs.appearance()
                        _shelfPrefs.value = prefs.shelf()
                        // 设置里可能换了阅读目标或提醒开关：按恢复后的设置重新排闹钟。
                        com.xycs.lingyuereader.reminder.GoalReminder.schedule(getApplication())
                    } catch (error: Exception) {
                        failed += "设置（${error.message ?: "读取失败"}）"
                    }
                }
                _messages.value = buildString {
                    append("已恢复 ${result.books} 本书")
                    if (result.skippedBooks > 0) {
                        append("；备份中另有 ${result.skippedBooks} 本无法读取或过大，已跳过")
                        if (result.skippedTitles.isNotEmpty()) {
                            append("：").append(result.skippedTitles.take(5).joinToString("、") { "《$it》" })
                            if (result.skippedTitles.size > 5) append("等")
                        }
                    }
                    if (failed.isNotEmpty()) append("，但附加数据恢复失败：").append(failed.joinToString("；"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: "请检查备份文件"
                _messages.value = if (restoredBooks == null) "恢复失败：$detail"
                else "已恢复 $restoredBooks 本书，但附加数据恢复失败：$detail"
            } finally {
                // 恢复可能换掉了正在听、正在看的书：停下听书，打开着的书在书架重新读好后重新加载。
                ReadAloud.stop()
                val open = _openBook.value?.entry?.id ?: loadingBookId
                _openBook.value = null
                cancelBookLoad()
                _summaries.value = SummaryState()
                _translate.value = TranslateState()
                _companion.value = CompanionState()
                try {
                    loadShelf()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _messages.value = "刷新书架失败：${error.message ?: "读取失败"}"
                } finally {
                    endBusy()
                }
                open?.let { id -> if (_shelf.value.any { it.id == id }) ensureLoaded(id) }
            }
        }
    }

    fun notesMarkdown(): String? {
        val book = _openBook.value ?: return null
        return _marks.value.toMarkdown(book.entry.title, book.content)
    }

    /** 从别的应用“用灵阅打开”或分享过来的文件：导入（或找回已有的那本）并直接打开。 */
    /**
     * 别的应用分享过来的一段文字（不是文件）：够长的存成一本 TXT 打开（标题用分享的主题或第一行），
     * 太短的多半只是一句话，说一声灵阅只能打开文件。
     */
    fun openSharedText(text: String, subject: String?) {
        val body = text.trim()
        if (body.length < 200) {
            _messages.value = "灵阅只能打开书的文件；这段文字太短，没有存成书"
            return
        }
        viewModelScope.launch {
            val title = (subject?.trim()?.takeIf { it.isNotEmpty() } ?: body.lineSequence().first().trim()).take(30)
                .replace(Regex("""[\\/:*?"<>|]"""), " ").trim().ifEmpty { "分享的文字" }
            val file = withContext(Dispatchers.IO) {
                val shared = java.io.File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
                // 导入时已复制进书库：一小时前分享的可以清掉。
                val stale = System.currentTimeMillis() - 3_600_000L
                shared.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.deleteRecursively() }
                // 每次放进各自的文件夹：同一标题再分享一次是新的地址，不会被当成上次那本（文件名仍是标题，书名不变）；
                // 内容一样的照旧找回书架上那本。
                java.io.File(shared, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                    .let { java.io.File(it, "$title.txt") }.apply { writeText(body) }
            }
            openExternal(listOf(Uri.fromFile(file)))
        }
    }

    fun openExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { library.describe(uris) }.filter { BookParser.isSupported(it.name) }
            if (picked.isEmpty()) {
                _messages.value = "暂不支持这种文件"
                return@launch
            }
            if (picked.size > 1) {
                // 问“合成一本还是分开导入”的对话框在书架页上：在写作、统计等页时先切过去。
                _stack.value = listOf(Screen.Shelf)
                selectTab(HomeTab.Shelf)
                _pendingImport.value = picked
                return@launch
            }
            beginBusy()
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
            endBusy()
            entry.onSuccess {
                _stack.value = listOf(Screen.Shelf, Screen.Reader(it.id))
                if (changedSource) _messages.value = "原文件有改动，打开的是书架上的版本；要更新可在书架长按这本书选“刷新”"
            }.onFailure { _messages.value = "打不开 ${picked.single().name}：${it.message ?: "读取失败"}" }
        }
    }

    /** 在页面底部提示一句。 */
    fun showMessage(text: String) {
        _messages.value = text
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

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    fun search(query: String) {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        val book = _openBook.value
        _bookSearch.value = BookSearchState(
            query = query,
            running = query.isNotBlank() && book != null,
            error = if (query.isNotBlank() && book == null) "这本书尚未打开，请返回阅读页后重试" else null,
        )
        if (query.isBlank() || book == null) return
        val generation = searchGeneration
        searchJob = viewModelScope.launch {
            try {
                val hits = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()[Job]
                    BookParser.search(book.content, query, shouldContinue = { worker?.isActive == true })
                }
                if (generation == searchGeneration && _bookSearch.value.query == query) {
                    _bookSearch.value = if (_openBook.value?.content === book.content) {
                        BookSearchState(query, hits)
                    } else {
                        BookSearchState(query, error = "阅读的书已切换，请返回后重试")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == searchGeneration && _bookSearch.value.query == query) {
                    _bookSearch.value = BookSearchState(query, error = "搜索失败，请重试")
                }
            }
        }
    }

    /** 把作品导出到读者选的文件。 */
    fun exportWork(workId: String, format: ExportFormat, uri: Uri) {
        viewModelScope.launch {
            beginBusy()
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    stagedWritingExport(app.cacheDir, { app.contentResolver.openOutputStream(uri, "wt") }) {
                        writing.export(workId, format, it)
                    }
                }
                _messages.value = "已导出 ${format.label}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "导出失败：${error.message ?: "写入出错"}"
            } finally {
                endBusy()
            }
        }
    }

    // ---------- 写作：设定包的导入导出 ----------

    /** 读者选的文件：文件名和打开方法，交给设定包导入去认。 */
    suspend fun readImportFiles(uris: List<Uri>): List<com.xycs.lingyuereader.data.PackImportItem> {
        val app = getApplication<Application>()
        val files = withContext(Dispatchers.IO) { library.describe(uris).map { p -> p.name to { requireNotNull(app.contentResolver.openInputStream(p.uri)) { "无法打开 ${p.name}" } } } }
        return writing.parseImport(files)
    }

    /** 读者选的文件夹：往下几层里的 .md、.txt、.zip。 */
    suspend fun readImportFolder(tree: Uri): List<com.xycs.lingyuereader.data.PackImportItem> {
        val app = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            com.xycs.lingyuereader.data.SafTree.files(app, tree).map { (name, uri) -> name to { requireNotNull(app.contentResolver.openInputStream(uri)) { "无法打开 $name" } } }
        }
        return writing.parseImport(files)
    }

    fun exportPack(workId: String, uri: Uri) {
        viewModelScope.launch {
            beginBusy()
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    stagedWritingExport(app.cacheDir, { app.contentResolver.openOutputStream(uri, "wt") }) {
                        writing.exportPack(workId, it)
                    }
                }
                _messages.value = "已导出设定包"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "导出失败：${error.message ?: "写入出错"}"
            } finally {
                endBusy()
            }
        }
    }

    // ---------- 写作：当书预览、听稿 ----------

    /**
     * 把作品生成 EPUB 放进书架（分组“我的作品”），用阅读页打开到 [chapterId] 那一章（为空时从上次读到的地方）；
     * [listen] 为真时打开后从那一章开头听。第一次放进书架，之后刷新同一本书，阅读进度、书签照旧。
     */
    fun previewWork(workId: String, chapterId: String?, listen: Boolean = false) {
        viewModelScope.launch {
            beginBusy()
            try {
                writing.flush()
                val (work, file) = writing.writePreview(workId)
                val uri = Uri.fromFile(file).toString()
                val entry = withContext(Dispatchers.IO) {
                    val list = library.list()
                    val existing = list.firstOrNull { it.id == work.previewBookId } ?: list.firstOrNull { e -> e.sources.any { it.uri == uri } }
                    val entry = if (existing != null) {
                        library.refresh(existing)
                        if (existing.title != work.title) library.rename(existing.id, work.title)
                        existing
                    } else {
                        library.add(listOf(Library.Picked(Uri.fromFile(file), "${work.title}.epub")), work.title).also { e ->
                            library.setGroups(e.id, listOf(WORKS_GROUP))
                        }
                    }
                    if (entry.id != work.previewBookId) writing.setPreviewBook(workId, entry.id)
                    entry
                }
                loadShelf()
                bookContentChanged(entry.id)
                val index = chapterId?.let { id -> work.chapters.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                open(Screen.Reader(entry.id, chapterIndex = index))
                if (listen) {
                    val book = kotlinx.coroutines.withTimeoutOrNull(30_000) { _openBook.first { it?.entry?.id == entry.id } }
                    if (book != null) startReadAloud(index ?: book.entry.progress?.chapterIndex ?: 0, 0, 0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.value = "预览失败：${error.message ?: "生成出错"}"
            } finally {
                endBusy()
            }
        }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Reader && screen.bookId != _returnPoint.value?.bookId) _returnPoint.value = null
        if (screen is Screen.Search) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            _bookSearch.value = BookSearchState()
        }
        _stack.update { it + screen }
    }

    /** 在阅读页内换章，不叠加返回栈。 */
    fun replaceTop(screen: Screen) {
        _stack.update { it.dropLast(1) + screen }
    }

    /** 从搜索结果跳到正文：替换掉搜索页和原阅读页，返回时直接回书架。 */
    fun jumpFromSearch(bookId: String, chapterIndex: Int, blockIndex: Int, charOffset: Int = 0) {
        // 搜索前读到的地方就是存下的进度。
        _openBook.value?.entry?.takeIf { it.id == bookId }?.progress?.let { p ->
            markJump(ReturnPoint(bookId, p.chapterIndex, p.itemIndex - com.xycs.lingyuereader.ui.HEADER_ITEMS, p.itemOffset, p.charOffset, p.chapterTitle))
        }
        _stack.update { stack ->
            stack.dropLastWhile { it !is Screen.Reader }.dropLast(1) + Screen.Reader(bookId, chapterIndex, blockIndex, char = charOffset, highlight = true)
        }
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        if (_stack.value.last() is Screen.Reader) _returnPoint.value = null
        _stack.update { it.dropLast(1) }
        return true
    }
}

/** 本机模型的状态：装好了哪些（[sizes] 占多大地方）、正在做的下载或导入、出错的原因。 */
data class DeviceModelState(
    val installed: List<String> = emptyList(),
    val sizes: Map<String, Long> = emptyMap(),
    val task: DeviceTask? = null,
    val error: String? = null,
)

/** 正在下载（[model] 是哪个）或导入（[model] 为空）。 */
data class DeviceTask(val model: String?, val phase: com.xycs.lingyuereader.ai.DevicePhase, val done: Long = 0, val total: Long = 0)
