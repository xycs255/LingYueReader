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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** AI 面板里的一问一答。 */
data class AiTurn(
    /** 面板里显示的问题（“解释”、读者打的字）。 */
    val question: String,
    val answer: String = "",
    /** 问这本书时引用的原文。 */
    val sources: List<AiSource> = emptyList(),
    val loading: Boolean = true,
    /** 回答出来之前在做什么（“正在找相关原文”“正在建立索引 120/800”）。 */
    val status: String? = null,
    val error: String? = null,
    /** 追问时作为对话历史重发的问题：问这本书时不重发原文片段，省钱。 */
    val replay: String = question,
)

/** 面板在做什么。 */
sealed interface AiTask {
    /**
     * 划选后问 AI。[chapter] 和 [pieces]（每段里选中的范围）用来把回答存成批注；从对话记录里打开的旧对话，章节可能已经变了。
     */
    data class Selection(
        val text: String,
        val context: String,
        val chapterTitle: String,
        val chapter: Int = -1,
        val pieces: List<NoteRange> = emptyList(),
    ) : AiTask
    data class Recap(val pos: ReadPos, val chapterTitle: String) : AiTask
    data class AskBook(val pos: ReadPos) : AiTask
}

/** 选区在某一块里的范围（[end] 不含），存批注用。 */
data class NoteRange(val block: Int, val start: Int, val end: Int)

data class AiSession(
    val task: AiTask,
    val turns: List<AiTurn> = emptyList(),
    /** 前情提要是之前生成、存下来的：显示生成时间和“重新生成”。 */
    val cachedAt: Long = 0L,
    /** 对话记录里按它覆盖。 */
    val id: String = java.util.UUID.randomUUID().toString(),
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
    val client: AiClient = AiClient(),
    /** 某本书目录里的文件：前情提要、对话记录、人物表等缓存都放在书的目录里，删书时一起删。 */
    private val bookFile: (bookId: String, name: String) -> File?,
) {
    private fun recapFile(bookId: String): File? = bookFile(bookId, "ai-recap.json")

    /** 当前这本书的对话记录。 */
    fun history(bookId: String): AiHistory = AiHistory { bookFile(bookId, AiHistory.FILE) }

    /** 新功能（人物表、读书笔记、翻译等）共用的请求：同一套设置，系统提示带上书名。 */
    /**
     * 一次性的整理（速读、翻译、人物表、讲书稿等）。[whole] 为真时回答被长度上限截断就报错，
     * 不把半截的 JSON、半句译文当成完整结果存下来。
     */
    suspend fun complete(
        book: AiBook?,
        prompt: String,
        length: AnswerLength? = null,
        whole: Boolean = true,
        feature: AiFeature = AiFeature.READING,
        onText: (String) -> Unit = {},
    ): String {
        val c = _config.value
        val system = AiPrompts.system(book?.title.orEmpty(), book?.author, length ?: c.length)
        // 用量记在哪个功能上（翻译、阅读的其他 AI）；外面已经套了计量器的（写作）不再套。
        return metered(feature) { client.stream(c, system, listOf(AiMessage("user", prompt)), whole, onText) }
    }

    /** 外面没有计量器时套一个（记账按 [feature] 分）；有就用外面的。 */
    private suspend fun <T> metered(feature: AiFeature, block: suspend () -> T): T =
        if (kotlin.coroutines.coroutineContext[AiMeter] != null) block() else withContext(AiMeter(feature)) { block() }

    val currentConfig: AiConfig get() = _config.value

    /** 自带系统提示的一次请求（写作助手用：它的系统提示是编辑，不是读书助手）。 */
    suspend fun ask(system: String, prompt: String, whole: Boolean = false, onText: (String) -> Unit = {}): String =
        client.stream(_config.value, system, listOf(AiMessage("user", prompt)), whole, onText)

    /** 带工具的一次请求（写作助手查设定包用）：见 [AiClient.converse]。 */
    suspend fun converse(
        system: String,
        prompt: String,
        tools: List<AiTool>,
        run: suspend (AiToolCall) -> String,
        cachePrefix: Int = 0,
        whole: Boolean = false,
        maxTokens: Int? = null,
        maxRounds: Int = 6,
        cacheExtra: Int = 0,
        onLookup: (AiToolCall) -> Unit = {},
        onText: (String) -> Unit = {},
    ): String = client.converse(_config.value, system, prompt, tools, run, cachePrefix, maxRounds = maxRounds, whole = whole, maxTokens = maxTokens, cacheExtra = cacheExtra, onLookup = onLookup, onText = onText)

    /** 合成一句语音（AI 朗读、AI 讲书）：用设置里的语音服务商。 */
    /** 合成一句。[sp] 给定时按它合成（听书开始时的设置，和缓存键一致），否则按当前设置。 */
    suspend fun speech(text: String, voice: String? = null, sp: SpeechConfig = _config.value.speech): ByteArray =
        client.speech(sp, text, voice ?: sp.voice)

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

    fun startSelection(book: AiBook, text: String, context: String, chapterTitle: String, chapter: Int = -1, pieces: List<NoteRange> = emptyList()) {
        open(book, AiSession(AiTask.Selection(text.trim(), context, chapterTitle, chapter, pieces)))
    }

    /** 从对话记录里打开一段旧对话，可以接着问。 */
    fun resume(book: AiBook, id: String): Boolean {
        val saved = history(book.id).load(id) ?: return false
        open(book, saved)
        return true
    }

    fun startRecap(book: AiBook, pos: ReadPos) {
        val title = book.content.chapters.getOrNull(pos.chapter)?.title.orEmpty()
        val read = runCatching { readRecap(book.id) }
        if (read.isFailure) {
            open(book, AiSession(AiTask.Recap(pos, title), listOf(AiTurn(RECAP, loading = false,
                error = "本机提要缓存读取失败；请检查存储后手动重新生成"))))
            return
        }
        val cached = read.getOrNull()?.takeIf {
            it.chapter == pos.chapter && (it.block < pos.block || (it.block == pos.block && it.char <= pos.char))
        }
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
    fun ask(q: QuickAsk) {
        val task = _session.value?.task as? AiTask.Selection ?: return
        send(q.label) { Prepared(AiPrompts.selection(task.chapterTitle, task.context, task.text, q.instruction)) }
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
            is AiTask.AskBook -> {
                val previous = s.turns.filter { it.error == null && !it.loading }.map { it.question }
                send(q) {
                    val sources = findSources(b, task.pos, q, previous) + nearby(b, task.pos, nextIndex = 0)
                    val numbered = sources.mapIndexed { i, src -> src.copy(index = i + 1) }
                    Prepared(AiPrompts.askBook(numbered, q), numbered, replay = q)
                }
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

    private fun send(question: String, onDone: suspend (String) -> Unit = {}, build: suspend () -> Prepared) {
        val s = _session.value ?: return
        val b = book ?: return
        val config = _config.value
        job?.cancel()
        lastSend = question to build
        val done = s.turns.filter { it.error == null && !it.loading }
        val history = done.flatMap { listOf(AiMessage("user", it.replay), AiMessage("assistant", it.answer)) }
        _session.value = s.copy(turns = done + AiTurn(question))
        job = scope.launch(AiMeter(AiFeature.ASK)) {
            try {
                val p = withContext(Dispatchers.IO) { build() }
                updateLast { it.copy(sources = p.sources, replay = p.replay ?: p.prompt) }
                val answer = client.stream(config, AiPrompts.system(b.title, b.author, config.length), history + AiMessage("user", p.prompt)) { piece ->
                    updateLast { it.copy(answer = it.answer + piece) }
                }
                updateLast { it.copy(answer = answer, loading = false) }
                onDone(answer)
                _session.value?.let { done -> withContext(Dispatchers.IO) { history(b.id).save(done) } }
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

    /**
     * 问这本书时找原文：先让模型把问题改写成检索词（补上别称、把追问里的“他”换成具体的人），按关键词（BM25）排；
     * 设了向量模型时，再按向量相似度排，两种排序融合。改写或向量出错时退回只按问题本身的关键词。
     */
    private suspend fun findSources(b: AiBook, pos: ReadPos, question: String, previous: List<String>): List<AiSource> {
        val config = _config.value
        val index = indexFor(b, pos)
        status("正在找相关原文……")
        val keywords = runCatching {
            client.stream(config, "", listOf(AiMessage("user", AiPrompts.keywords(question, previous)))).take(200)
        }.getOrElse { e ->
            // 读者关了面板：别吞掉取消，接着排序、读向量缓存都是白干。
            if (e is CancellationException) throw e
            ""
        }
        val byWords = index.ranked("$question $keywords", 30)
        val byVector = if (config.embeddingModel.isNotBlank() && config.protocol == AiProtocol.OPENAI) {
            runCatching { vectorRanked(b, index, config, "$question $keywords", byWords, 30) }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (e is VectorCacheException) throw AiException("本机向量缓存读写失败，已停止检索，避免重复发送可能收费的向量请求：${e.message}")
                emptyList()
            }
        } else emptyList()
        status(null)
        return index.sources(VectorCache.fuse(byWords, byVector).take(8))
    }

    /** 只索引关键词前 30 段和最近 10 段，避免第一次提问把整部长篇发给可能计费的服务。 */
    private suspend fun vectorRanked(b: AiBook, index: BookIndex, config: AiConfig, query: String, byWords: List<Int>, limit: Int): List<Int> {
        val path = bookFile(b.id, VectorCache.FILE) ?: throw VectorCacheException("找不到书的缓存目录")
        val cache = VectorCache(path)
        val model = config.embeddingModel.trim()
        val have = cache.load(model).toMutableMap()
        val texts = index.texts
        val candidates = vectorCandidates(byWords, texts.size)
        val keys = candidates.associateWith { VectorCache.key(texts[it]) }
        val missing = candidates.filter { keys.getValue(it) !in have }
        val batches = (missing.size + AiClient.EMBED_BATCH - 1) / AiClient.EMBED_BATCH
        status("向量检索 ${candidates.size}/${texts.size} 段；本次最多 ${batches + 1} 次向量请求${if (texts.size > candidates.size) "，其余段落未发送" else ""}")
        missing.chunked(AiClient.EMBED_BATCH).forEachIndexed { n, batch ->
            status("正在建立向量索引 ${n + 1}/$batches 批（最多检索 ${candidates.size}/${texts.size} 段）")
            val vectors = client.embed(config, model, batch.map { texts[it] })
            val added = batch.indices.associate { keys.getValue(batch[it]) to vectors[it] }
            cache.put(added)
            have.putAll(added)
        }
        val q = client.embed(config, model, listOf(query)).single()
        return candidates.sortedByDescending { have[keys.getValue(it)]?.let { v -> VectorCache.cosine(q, v) } ?: -1f }.take(limit)
    }

    internal fun vectorCandidates(byWords: List<Int>, textCount: Int): List<Int> =
        (byWords.take(30) + (textCount - 1 downTo (textCount - 10).coerceAtLeast(0)).toList())
            .filter { it in 0 until textCount }.distinct().take(40)

    private fun status(text: String?) = updateLast { it.copy(status = text) }

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
        glossJob = scope.launch(AiMeter(AiFeature.ASK)) {
            val system = if (book != null) AiPrompts.system(book.title, book.author, AnswerLength.SHORT) else AiPrompts.system("", null, AnswerLength.SHORT)
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
        val answer = withContext(AiMeter(AiFeature.TEST)) { client.stream(c, "", listOf(AiMessage("user", "请只回复“好”。"))) }
        val ms = System.currentTimeMillis() - start
        return "连接正常，用时 ${"%.1f".format(ms / 1000f)} 秒，回复：${answer.trim().take(20)}"
    }

    // ---------- 前情提要缓存 ----------

    private class CachedRecap(val chapter: Int, val block: Int, val char: Int, val text: String, val at: Long)

    private fun readRecap(bookId: String): CachedRecap? {
        val file = recapFile(bookId) ?: return null
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        // 旧缓存含整块正文：同一块内只有读到块末才能安全复用。
        return CachedRecap(o.getInt("chapter"), o.getInt("block"), o.optInt("char", Int.MAX_VALUE), o.getString("text"), o.getLong("at"))
    }

    private suspend fun saveRecap(bookId: String, pos: ReadPos, text: String): Unit = withContext(Dispatchers.IO) {
        val file = recapFile(bookId) ?: throw AiException("提要已生成，但找不到本机保存位置；请勿重复生成")
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = JSONObject().put("chapter", pos.chapter).put("block", pos.block).put("char", pos.char)
            .put("text", text).put("at", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        try {
            if (file.parentFile?.isDirectory == false && file.parentFile?.mkdirs() != true) throw IOException("无法创建提要目录")
            FileOutputStream(tmp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw AiException("提要已生成，但本机保存失败；请检查存储后重试：${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
        Unit
    }

    companion object {
        const val RECAP = "前情提要"
    }
}
