package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.BookContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** AI 面板里的一问一答。 */
data class AiTurn(
    /** 面板里显示的问题（“解释”、读者打的字）。 */
    val question: String,
    val answer: String = "",
    /** 问这本书时引用的原文。 */
    val sources: List<AiSource> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** 追问时作为对话历史重发的问题：问这本书时不重发原文片段，省钱。 */
    val replay: String = question,
)

/** 面板在做什么。 */
sealed interface AiTask {
    /** 划选后问 AI。 */
    data class Selection(val text: String, val context: String, val chapterTitle: String) : AiTask
    data class Recap(val pos: ReadPos, val chapterTitle: String) : AiTask
    data class AskBook(val pos: ReadPos) : AiTask
}

data class AiSession(
    val task: AiTask,
    val turns: List<AiTurn> = emptyList(),
    /** 前情提要是之前生成、存下来的：显示生成时间和“重新生成”。 */
    val cachedAt: Long = 0L,
)

/** 查词面板里的 AI 释义。 */
data class AiGloss(val word: String, val turn: AiTurn)

/** 正在读的书：AI 只看得到这些。 */
class AiBook(val id: String, val title: String, val author: String?, val content: BookContent)

/**
 * AI 功能的状态和请求：设置、阅读界面的 AI 面板、查词里的 AI 释义。
 * 每次请求都由读者点按触发；关面板时取消正在进行的请求。
 */
class AiAssistant(
    private val scope: CoroutineScope,
    private val store: AiStore?,
    private val client: AiClient = AiClient(),
    /** 某本书的前情提要缓存文件。 */
    private val recapFile: (bookId: String) -> File?,
) {
    private val _config = MutableStateFlow(store?.load() ?: AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private val _session = MutableStateFlow<AiSession?>(null)
    val session: StateFlow<AiSession?> = _session.asStateFlow()

    private val _gloss = MutableStateFlow<AiGloss?>(null)
    val gloss: StateFlow<AiGloss?> = _gloss.asStateFlow()

    private var book: AiBook? = null
    private var job: Job? = null
    private var glossJob: Job? = null
    private var lastSend: Pair<String, suspend () -> Prepared>? = null
    /** 问这本书的索引：同一本书、读到同一章时复用。 */
    private var index: Triple<String, ReadPos, BookIndex>? = null

    /** 发出去的问题：[prompt] 是这一轮真正发的内容。 */
    class Prepared(val prompt: String, val sources: List<AiSource> = emptyList(), val replay: String? = null)

    fun saveConfig(c: AiConfig) {
        _config.value = c
        store?.save(c)
        if (!c.ready) close()
    }

    fun startSelection(book: AiBook, text: String, context: String, chapterTitle: String) {
        open(book, AiSession(AiTask.Selection(text.trim(), context, chapterTitle)))
    }

    fun startRecap(book: AiBook, pos: ReadPos) {
        val title = book.content.chapters.getOrNull(pos.chapter)?.title.orEmpty()
        val cached = readRecap(book.id)?.takeIf { it.chapter == pos.chapter && it.block <= pos.block }
        if (cached != null) {
            open(book, AiSession(AiTask.Recap(pos, title), listOf(AiTurn(RECAP, cached.text, loading = false)), cachedAt = cached.at))
        } else {
            open(book, AiSession(AiTask.Recap(pos, title)))
            recap()
        }
    }

    fun startAskBook(book: AiBook, pos: ReadPos) {
        open(book, AiSession(AiTask.AskBook(pos)))
    }

    private fun open(book: AiBook, session: AiSession) {
        job?.cancel()
        lastSend = null
        this.book = book
        _session.value = session
    }

    /** 划选面板里的快捷问题。 */
    fun ask(kind: AskKind) {
        val task = _session.value?.task as? AiTask.Selection ?: return
        send(kind.label) { Prepared(AiPrompts.selection(task.chapterTitle, task.context, task.text, kind.instruction)) }
    }

    /** 读者自己打的问题。 */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val s = _session.value ?: return
        val b = book ?: return
        when (val task = s.task) {
            is AiTask.Selection -> {
                // 第一问带上选中的文字；之后的追问对话里已经有了。
                val first = s.turns.none { it.error == null && !it.loading }
                send(q) { Prepared(if (first) AiPrompts.selection(task.chapterTitle, task.context, task.text, q) else q) }
            }
            is AiTask.AskBook -> send(q) {
                val sources = indexFor(b, task.pos).search(q) + nearby(b, task.pos, nextIndex = 0)
                val numbered = sources.mapIndexed { i, src -> src.copy(index = i + 1) }
                Prepared(AiPrompts.askBook(numbered, q), numbered, replay = q)
            }
            is AiTask.Recap -> send(q) { Prepared(q) }
        }
    }

    /** 重新生成前情提要（不用存下的那份）。 */
    fun recap() {
        val s = _session.value ?: return
        val task = s.task as? AiTask.Recap ?: return
        val b = book ?: return
        _session.value = s.copy(turns = emptyList(), cachedAt = 0L)
        send(RECAP, onDone = { answer -> saveRecap(b.id, task.pos, answer) }) {
            val text = AiPrompts.recentText(b.content, task.pos)
            if (text.isBlank()) throw AiException("还没读到正文，没有可以提要的内容")
            Prepared(AiPrompts.recap(text, task.chapterTitle))
        }
    }

    fun retry() {
        val (question, build) = lastSend ?: return
        val s = _session.value ?: return
        if (s.task is AiTask.Recap && s.turns.size <= 1) {
            recap()
            return
        }
        send(question, build = build)
    }

    fun close() {
        job?.cancel()
        job = null
        lastSend = null
        _session.value = null
    }

    private fun send(question: String, onDone: (String) -> Unit = {}, build: suspend () -> Prepared) {
        val s = _session.value ?: return
        val b = book ?: return
        val config = _config.value
        job?.cancel()
        lastSend = question to build
        val done = s.turns.filter { it.error == null && !it.loading }
        val history = done.flatMap { listOf(AiMessage("user", it.replay), AiMessage("assistant", it.answer)) }
        _session.value = s.copy(turns = done + AiTurn(question))
        job = scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { build() }
                updateLast { it.copy(sources = p.sources, replay = p.replay ?: p.prompt) }
                val answer = client.stream(config, AiPrompts.system(b.title, b.author), history + AiMessage("user", p.prompt)) { piece ->
                    updateLast { it.copy(answer = it.answer + piece) }
                }
                updateLast { it.copy(answer = answer, loading = false) }
                onDone(answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                updateLast { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                updateLast { it.copy(loading = false, error = "出错了：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun updateLast(transform: (AiTurn) -> AiTurn) {
        _session.update { s ->
            if (s == null || s.turns.isEmpty()) s else s.copy(turns = s.turns.dropLast(1) + transform(s.turns.last()))
        }
    }

    private fun indexFor(b: AiBook, pos: ReadPos): BookIndex {
        index?.let { (id, p, idx) -> if (id == b.id && p == pos) return idx }
        return BookIndex.build(b.content, pos).also { index = Triple(b.id, pos, it) }
    }

    /** 读者停下的地方附近那一段：“刚才”“这里”一类的问题靠它。 */
    private fun nearby(b: AiBook, pos: ReadPos, nextIndex: Int): List<AiSource> {
        val text = AiPrompts.recentText(b.content, pos, limit = 1200).substringAfterLast("】\n").trim()
        if (text.isEmpty()) return emptyList()
        val title = b.content.chapters.getOrNull(pos.chapter)?.title.orEmpty()
        return listOf(AiSource(nextIndex, pos.chapter, "$title · 读到的地方", pos.block, text))
    }

    // ---------- 查词的 AI 释义 ----------

    fun gloss(book: AiBook?, word: String, sentence: String) {
        val current = _gloss.value
        if (current != null && current.word == word && current.turn.error == null) return
        glossJob?.cancel()
        _gloss.value = AiGloss(word, AiTurn(word))
        val config = _config.value
        glossJob = scope.launch {
            val system = if (book != null) AiPrompts.system(book.title, book.author) else AiPrompts.system("", null)
            try {
                val answer = client.stream(config, system, listOf(AiMessage("user", AiPrompts.gloss(word, sentence)))) { piece ->
                    _gloss.update { g -> if (g?.word == word) g.copy(turn = g.turn.copy(answer = g.turn.answer + piece)) else g }
                }
                _gloss.update { g -> if (g?.word == word) g.copy(turn = g.turn.copy(answer = answer, loading = false)) else g }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? AiException)?.message ?: "出错了：${e.message ?: e.javaClass.simpleName}"
                _gloss.update { g -> if (g?.word == word) g.copy(turn = g.turn.copy(loading = false, error = message)) else g }
            }
        }
    }

    /** 查的词变了或关了查词面板：之前的释义作废。 */
    fun clearGloss(keep: String? = null) {
        if (keep != null && _gloss.value?.word == keep) return
        glossJob?.cancel()
        _gloss.value = null
    }

    /** 设置页的“测试连接”：发一句最短的话，回来了就说明地址、模型、密钥都对。 */
    suspend fun test(c: AiConfig): String {
        val start = System.currentTimeMillis()
        val answer = client.stream(c, "", listOf(AiMessage("user", "请只回复“好”。")))
        val ms = System.currentTimeMillis() - start
        return "连接正常，用时 ${"%.1f".format(ms / 1000f)} 秒，回复：${answer.trim().take(20)}"
    }

    // ---------- 前情提要缓存 ----------

    private class CachedRecap(val chapter: Int, val block: Int, val text: String, val at: Long)

    private fun readRecap(bookId: String): CachedRecap? = runCatching {
        val o = JSONObject(recapFile(bookId)!!.readText())
        CachedRecap(o.getInt("chapter"), o.getInt("block"), o.getString("text"), o.getLong("at"))
    }.getOrNull()

    private fun saveRecap(bookId: String, pos: ReadPos, text: String) {
        val file = recapFile(bookId) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("chapter", pos.chapter).put("block", pos.block).put("text", text).put("at", System.currentTimeMillis()).toString())
            }
        }
    }

    companion object {
        const val RECAP = "前情提要"
    }
}
