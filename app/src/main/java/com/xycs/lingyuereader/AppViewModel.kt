package com.xycs.lingyuereader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookParser
import com.xycs.lingyuereader.data.Bookmark
import com.xycs.lingyuereader.data.MarkColor
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Note
import com.xycs.lingyuereader.data.displayText
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReadingProgress
import com.xycs.lingyuereader.data.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/** 正在读的书：书架条目加上解析好的正文。 */
data class OpenBook(val entry: BookEntry, val content: BookContent)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val library = Library(app)
    private val prefs = Prefs(app)

    private val _shelf = MutableStateFlow<List<BookEntry>>(emptyList())
    val shelf: StateFlow<List<BookEntry>> = _shelf.asStateFlow()

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
    }

    private suspend fun loadShelf() {
        _shelf.value = withContext(Dispatchers.IO) { library.list() }
            .sortedByDescending { maxOf(it.openedAt, it.addedAt) }
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
