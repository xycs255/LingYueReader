package com.xycs.lingyuereader

import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.CheckRule
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.RuleSet
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.brief
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

/** 写作助手面板在做的事。 */
sealed interface WriteAiTask {
    val title: String

    /**
     * 改一段：[original] 是原文（续写时为空），在正文的 [start]..[end]（不含）。
     * 采纳时按位置换；正文在那之后又改过、位置对不上时，按原文找。
     */
    data class Edit(val action: WriteAction, val original: String, val start: Int, val end: Int, val instruction: String = "") : WriteAiTask {
        override val title: String get() = if (action == WriteAction.CUSTOM) "按要求改" else action.label
    }

    data class Idea(val kind: IdeaKind, val extra: String = "") : WriteAiTask {
        override val title: String get() = kind.label
    }

    data object Review : WriteAiTask {
        override val title = "读者点评"
    }

    data object Consistency : WriteAiTask {
        override val title = "前后矛盾检查"
    }
}

/** 写作助手面板：结果一段段长出来。 */
data class WriteAiState(
    val workId: String,
    val chapterId: String,
    val task: WriteAiTask,
    val text: String = "",
    val loading: Boolean = true,
    val error: String? = null,
)

/** 实时检查的状态：哪一章、找出的问题、正在查没有、上次出的错。 */
data class RealtimeState(
    val chapterId: String = "",
    val issues: List<WriteIssue> = emptyList(),
    val checking: Boolean = false,
    val error: String? = null,
)

/**
 * 写作的 AI：选中文字改写、灵感、读者点评、矛盾检查、实时检查、整理设定集。
 * 除了实时检查（每部作品单独开，默认关），都是作者点了才发请求；结果只给作者看，作者点“采纳”才改稿。
 */
class WritingAi(
    private val store: WorkStore,
    private val ai: AiAssistant,
    private val scope: CoroutineScope,
    private val message: (String) -> Unit,
) {
    val ready: Boolean get() = ai.currentConfig.ready

    private val _assists = MutableStateFlow<Map<String, WorkAssist>>(emptyMap())
    /** 各作品的 AI 设置和设定集（打开过的才有）。 */
    val assists: StateFlow<Map<String, WorkAssist>> = _assists.asStateFlow()

    private val _state = MutableStateFlow<WriteAiState?>(null)
    val state: StateFlow<WriteAiState?> = _state.asStateFlow()
    private var job: Job? = null
    /** 上一次请求的参数：“再来一次”用。 */
    private var lastRun: (() -> Unit)? = null

    private val _realtime = MutableStateFlow(RealtimeState())
    val realtime: StateFlow<RealtimeState> = _realtime.asStateFlow()
    private var realtimeJob: Job? = null
    /** 查过的段落（作品id、章id → 段落原文）：没改过的段不再发。 */
    private val checked = HashMap<Pair<String, String>, MutableSet<String>>()

    fun loadAssist(workId: String) {
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { store.assist(workId) }.getOrDefault(WorkAssist()) }
            _assists.update { it + (workId to a) }
        }
    }

    fun updateAssist(workId: String, transform: (WorkAssist) -> WorkAssist) {
        // 先改界面看到的，再写盘；写盘失败就提示。
        _assists.update { m -> m + (workId to transform(m[workId] ?: WorkAssist())) }
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.updateAssist(workId, transform) }
                _assists.update { it + (workId to saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
    }

    /** 从备份恢复后：缓存的设定作废，用到时重读。 */
    fun forget() {
        _assists.value = emptyMap()
        checked.clear()
    }

    private suspend fun assistOf(workId: String): WorkAssist =
        _assists.value[workId] ?: withContext(Dispatchers.IO) { store.assist(workId) }.also { a -> _assists.update { it + (workId to a) } }

    // ---------- 面板 ----------

    /**
     * 开始一个任务。[text] 是这一章现在的全文（编辑框里的，可能还没存盘）。
     */
    fun run(workId: String, chapterId: String, text: String, task: WriteAiTask) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        job?.cancel()
        lastRun = { run(workId, chapterId, text, task) }
        _state.value = WriteAiState(workId, chapterId, task)
        job = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId)
                val title = chapter?.title.orEmpty()
                val assist = assistOf(workId)
                val previous = withContext(Dispatchers.IO) {
                    val chapters = work.chapters
                    val i = chapters.indexOfFirst { it.id == chapterId }
                    chapters.getOrNull(i - 1)?.let { store.text(workId, it.id) }.orEmpty()
                }
                // 本章资料（常用文档、出场人物、察觉、伏笔、提到这一章的小节），加上这一章的细纲卡：改稿、灵感、矛盾检查都带上。
                val pack = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text) }
                val (system, prompt) = when (task) {
                    is WriteAiTask.Edit -> {
                        val before = text.substring(0, task.start.coerceIn(0, text.length))
                        val after = text.substring(task.end.coerceIn(0, text.length))
                        // 本章开头的话，前文接上一章的结尾。
                        val context = if (before.length < WritePrompts.BEFORE_CHARS && previous.isNotBlank()) previous.takeLast(WritePrompts.BEFORE_CHARS - before.length) + "\n" + before else before
                        WritePrompts.system(work.title, assist, pack, examples = task.action != WriteAction.CONTINUE) to
                            WritePrompts.edit(task.action, task.original, context, after, title, task.instruction)
                    }
                    is WriteAiTask.Idea -> {
                        val recent = (if (text.length < 6_000 && previous.isNotBlank()) previous.takeLast(6_000 - text.length) + "\n【" + title + "】\n" else "") + text.takeLast(6_000)
                        WritePrompts.system(work.title, assist, pack) to WritePrompts.idea(task.kind, recent, title, task.extra)
                    }
                    WriteAiTask.Review -> WritePrompts.system(work.title, assist) to WritePrompts.review(text, title)
                    WriteAiTask.Consistency -> WritePrompts.system(work.title, assist, pack) to WritePrompts.consistency(previous.takeLast(2_000), text, title)
                }
                val answer = ai.ask(system, prompt) { piece -> _state.update { s -> s?.takeIf { it.task === task }?.copy(text = s.text + piece) ?: s } }
                val final = if (task is WriteAiTask.Edit) WritePrompts.clean(answer, task.original) else answer.trim()
                _state.update { s -> s?.takeIf { it.task === task }?.copy(text = final, loading = false) ?: s }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s?.takeIf { it.task === task }?.copy(loading = false, error = e.message ?: "请求失败") ?: s }
            }
        }
    }

    fun retry() {
        lastRun?.invoke()
    }

    /** 关面板。改稿类的结果没采纳就算一次“没用上”。 */
    fun close() {
        val s = _state.value
        job?.cancel()
        _state.value = null
        if (s != null && s.task is WriteAiTask.Edit && !s.loading && s.error == null && s.text.isNotBlank()) {
            updateAssist(s.workId) { it.copy(rejected = it.rejected + 1) }
        }
    }

    /** 采纳了改稿：记下这次改法，以后润色时照这个分寸。 */
    fun accepted(workId: String, action: WriteAction, before: String, after: String) {
        job?.cancel()
        _state.value = null
        updateAssist(workId) { WritePrompts.remember(it, action, before, after, System.currentTimeMillis()) }
    }

    // ---------- 实时检查 ----------

    /**
     * 查这一章里改过、还没查过的段落（最多 [REALTIME_BATCH] 段，从后往前，刚写的多在后面）。
     * 只在作品开了实时检查、AI 可用时调用；上一次还没查完就不发新的。
     */
    fun checkRealtime(workId: String, chapterId: String, text: String) {
        if (!ready || realtimeJob?.isActive == true) return
        val paragraphs = text.lines().map { it.trim() }.filter { it.length >= MIN_PARAGRAPH }
        // 换了一章：问题清空。还在的问题里，段落已经改掉、错的字不在了的去掉。
        _realtime.update { r ->
            if (r.chapterId != chapterId) RealtimeState(chapterId)
            else r.copy(issues = r.issues.filter { i -> i.paragraph in paragraphs && i.wrong in i.paragraph })
        }
        val seen = checked.getOrPut(workId to chapterId) { HashSet() }
        val todo = paragraphs.filter { it !in seen }.takeLast(REALTIME_BATCH)
        if (todo.isEmpty()) return
        realtimeJob = scope.launch {
            _realtime.update { it.copy(checking = true) }
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) }
                val assist = assistOf(workId)
                if (!assist.realtime) return@launch
                val answer = ai.ask(WritePrompts.system(work?.title.orEmpty(), assist), WritePrompts.proofread(todo), whole = true)
                val found = WritePrompts.parseIssues(answer, todo)
                seen += todo
                _realtime.update { r ->
                    if (r.chapterId != chapterId) r else r.copy(issues = (r.issues + found).distinctBy { it.paragraph to it.wrong }, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _realtime.update { it.copy(error = e.message ?: "检查失败") }
            } finally {
                _realtime.update { it.copy(checking = false) }
            }
        }
    }

    /** 改了或忽略了一处：从列表里拿掉（忽略的那段已经算查过，不会再报）。 */
    fun dismissIssue(issue: WriteIssue) {
        _realtime.update { r -> r.copy(issues = r.issues - issue) }
    }

    /** 离开编辑器、关了实时检查：停下正在查的。 */
    fun stopRealtime() {
        realtimeJob?.cancel()
        _realtime.value = RealtimeState()
    }

    // ---------- 设定集 ----------

    /** 让 AI 从最近写的正文里整理人物，返回建议（作者勾选后才加进设定集）。 */
    suspend fun extractCharacters(workId: String): List<BibleProposal> {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val (title, text) = withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            // 从后往前取，最近写的优先，够 EXTRACT_CHARS 字为止。
            val parts = ArrayDeque<String>()
            var total = 0
            for (c in work.chapters.asReversed()) {
                val t = store.text(workId, c.id).trim()
                if (t.isEmpty()) continue
                val piece = "【${c.title}】\n$t"
                if (total + piece.length > EXTRACT_CHARS && parts.isNotEmpty()) break
                parts.addFirst(piece.take(EXTRACT_CHARS))
                total += piece.length
            }
            work.title to parts.joinToString("\n\n")
        }
        if (text.isBlank()) throw IllegalStateException("还没有正文可以整理")
        val assist = assistOf(workId)
        val names = withContext(Dispatchers.IO) { characterNames(workId) }
        val answer = ai.ask(WritePrompts.system(title, assist), WritePrompts.extract(text, names), whole = true)
        return WritePrompts.parseProposals(answer, names)
    }

    /** 让 AI 从写作规范、称谓与语气文档里整理出检查规则，返回规则文本（作者看过、改过再存）。 */
    suspend fun draftRules(workId: String): String {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val (title, docs) = withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            val text = store.pack(workId).filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE }
                .map { "【${it.title}】\n" + store.packText(workId, it.id).trim() }
                .filter { it.lines().size > 1 && it.substringAfter('\n').isNotBlank() }
                .joinToString("\n\n")
            work.title to text.take(RULE_SOURCE_CHARS)
        }
        if (docs.isBlank()) throw IllegalStateException("设定包里还没有写作规范或称谓与语气文档")
        val answer = ai.ask(WritePrompts.system(title, assistOf(workId)), WritePrompts.rules(docs), whole = true)
        return WritePrompts.cleanRules(answer)
    }

    private fun characterDocs(workId: String) = store.pack(workId).filter { it.kind == PackKind.CHARACTER }

    private fun characterNames(workId: String): List<String> =
        characterDocs(workId).flatMap { WritePrompts.characterNames(store.packText(workId, it.id)) }.distinct()

    /** 写这一章要带给 AI 的资料：本章资料（按章挑的设定）加细纲卡和上一章的钩子。 */
    private fun chapterContext(workId: String, chapterId: String, text: String): String? {
        val work = store.get(workId) ?: return null
        val chapters = work.chapters
        val i = chapters.indexOfFirst { it.id == chapterId }
        val prev = chapters.getOrNull(i - 1)
        val card = WritePrompts.card(store.card(workId, chapterId), prev?.title, prev?.let { store.card(workId, it.id) })
        return listOfNotNull(store.brief(workId, chapterId, text)?.render(), card).joinToString("\n\n").ifEmpty { null }
    }

    // ---------- 三道审稿 ----------

    private val _review = MutableStateFlow<ReviewRun?>(null)
    /** 正在看或正在审的那一章的审稿。 */
    val review: StateFlow<ReviewRun?> = _review.asStateFlow()
    private var reviewJob: Job? = null

    /** 读这一章上次的审稿结果（审稿进行中就不动）。 */
    fun loadReview(workId: String, chapterId: String) {
        val r = _review.value
        if (r != null && r.workId == workId && r.chapterId == chapterId && r.running != null) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.review(workId, chapterId) }.getOrNull() }
            _review.value = ReviewRun(workId, chapterId, null, saved)
        }
    }

    /**
     * 按次序审 [text]（存盘的正文）：事实关、写法关请 AI（没设置 AI 就跳过），规则关在本机算。
     * 每道审完就显示出来；全部审完存下，下次打开还在。
     */
    fun startReview(workId: String, chapterId: String, text: String) {
        reviewJob?.cancel()
        val results = mutableListOf<GateResult>()
        fun publish(running: ReviewGate?) {
            _review.value = ReviewRun(workId, chapterId, running, ChapterReview(text.hashCode(), System.currentTimeMillis(), results.toList()))
        }
        publish(ReviewGate.FACT)
        reviewJob = scope.launch {
            val work = withContext(Dispatchers.IO) { store.get(workId) } ?: return@launch
            val title = work.chapter(chapterId)?.title.orEmpty()
            val assist = assistOf(workId)
            for (gate in ReviewGate.entries) {
                publish(gate)
                val result = try {
                    when (gate) {
                        ReviewGate.FACT -> {
                            if (!ready) GateResult(gate, error = "没有设置 AI，跳过", at = System.currentTimeMillis())
                            else {
                                val (pack, previous) = withContext(Dispatchers.IO) {
                                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                                    chapterContext(workId, chapterId, text) to (work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(1_500) }.orEmpty())
                                }
                                val answer = ai.ask(WritePrompts.system(work.title, assist, pack), WritePrompts.factReview(text, title, previous), whole = true)
                                reviewResult(gate, answer)
                            }
                        }
                        ReviewGate.CRAFT -> {
                            val rules = withContext(Dispatchers.IO) {
                                store.pack(workId).filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE }
                                    .map { "【${it.title}】\n" + store.packText(workId, it.id).trim() }
                                    .filter { it.substringAfter('\n').isNotBlank() }
                                    .joinToString("\n\n").take(RULE_SOURCE_CHARS)
                            }
                            when {
                                !ready -> GateResult(gate, error = "没有设置 AI，跳过", at = System.currentTimeMillis())
                                rules.isBlank() -> GateResult(gate, error = "设定包里没有写作规范，跳过", at = System.currentTimeMillis())
                                else -> {
                                    val answer = ai.ask(WritePrompts.system(work.title, assist), WritePrompts.craftReview(text, title, rules), whole = true)
                                    reviewResult(gate, answer)
                                }
                            }
                        }
                        ReviewGate.RULES -> {
                            val rules = withContext(Dispatchers.IO) {
                                store.pack(workId).filter { it.kind == PackKind.RULES }.fold(RuleSet()) { all, d -> all + CheckRules.parse(store.packText(workId, d.id), d.title) }
                            }
                            if (rules.isEmpty) GateResult(gate, error = "没有检查规则，跳过", at = System.currentTimeMillis())
                            else {
                                val hits = withContext(Dispatchers.Default) { CheckRules.check(text, rules).hits }
                                GateResult(gate, hits.map { h -> ReviewIssue(h.ranges.firstOrNull()?.let { text.substring(it.first, it.last + 1) }.orEmpty(), h.message) }, at = System.currentTimeMillis())
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GateResult(gate, error = e.message ?: "审稿失败", at = System.currentTimeMillis())
                }
                results += result
                publish(gate)
            }
            publish(null)
            val done = _review.value?.review ?: return@launch
            withContext(Dispatchers.IO) { runCatching { store.saveReview(workId, chapterId, done) } }
        }
    }

    /** AI 的回答读不出问题列表时如实报错：不能当成“没发现问题”。 */
    private fun reviewResult(gate: ReviewGate, answer: String): GateResult {
        val issues = WritePrompts.parseReview(answer)
        return if (issues == null) GateResult(gate, error = "AI 的回答不是要求的格式，没读懂。可以重新审稿，或换个模型。", at = System.currentTimeMillis())
        else GateResult(gate, issues, at = System.currentTimeMillis())
    }

    // ---------- 章节摘要 ----------

    /** 让 AI 概括一章（存盘的正文），返回摘要文字，不存（作者看过、改过再存进细纲卡）。 */
    suspend fun summarize(workId: String, chapterId: String): String {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val (work, text) = withContext(Dispatchers.IO) { store.get(workId) to store.text(workId, chapterId) }
        if (work == null) throw IllegalStateException("作品不在了")
        if (text.isBlank()) throw IllegalStateException("这一章还没有正文")
        val title = work.chapter(chapterId)?.title.orEmpty()
        return ai.ask(WritePrompts.system(work.title, assistOf(workId)), WritePrompts.summary(text, title), whole = true).trim()
    }

    private val _summaries = MutableStateFlow<SummaryProgress?>(null)
    /** 批量补写摘要的进度。 */
    val summaries: StateFlow<SummaryProgress?> = _summaries.asStateFlow()
    private var summaryJob: Job? = null

    /** 给有正文、还没有摘要的章依次写摘要，写一章存一章（作者之后可以在细纲卡里改）。 */
    fun summarizeAll(workId: String) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        if (summaryJob?.isActive == true) return
        summaryJob = scope.launch {
            val todo = withContext(Dispatchers.IO) {
                val work = store.get(workId) ?: return@withContext emptyList()
                work.chapters.filter { it.words > 0 && store.card(workId, it.id).summary.isBlank() }
            }
            if (todo.isEmpty()) {
                message("有正文的章都已经有摘要了")
                return@launch
            }
            var failed = 0
            try {
                todo.forEachIndexed { i, c ->
                    _summaries.value = SummaryProgress(workId, i, todo.size, c.title)
                    try {
                        val text = summarize(workId, c.id)
                        withContext(Dispatchers.IO) { store.saveCard(workId, c.id, store.card(workId, c.id).copy(summary = text)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        if (failed >= 3) throw e
                    }
                }
                message(if (failed == 0) "写好了 ${todo.size} 章的摘要，可以在各章细纲卡里改" else "写好了 ${todo.size - failed} 章的摘要，$failed 章没写成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("写摘要停下了：${e.message ?: "请求失败"}")
            } finally {
                _summaries.value = null
            }
        }
    }

    fun stopSummaries() {
        summaryJob?.cancel()
        _summaries.value = null
    }

    // ---------- AI 写本章 ----------

    private val _draft = MutableStateFlow<DraftState?>(null)
    /** AI 写的本章草稿（正在写或写好了的）。 */
    val draft: StateFlow<DraftState?> = _draft.asStateFlow()
    private var draftJob: Job? = null

    /** 打开草稿页：读上次没放进正文的草稿。 */
    fun loadDraft(workId: String, chapterId: String) {
        val d = _draft.value
        if (d != null && d.workId == workId && d.chapterId == chapterId) return
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { store.draft(workId, chapterId) }.getOrDefault("") }
            _draft.value = DraftState(workId, chapterId, text)
        }
    }

    /** 写作规范、称谓与语气、检查规则的原文（写正文时照着写）。 */
    private fun writingRules(workId: String): String =
        store.pack(workId).filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE || it.kind == PackKind.RULES }
            .sortedBy { it.priority }
            .map { "【${it.title}】\n" + store.packText(workId, it.id).trim() }
            .filter { it.substringAfter('\n').isNotBlank() }
            .joinToString("\n\n").take(RULE_SOURCE_CHARS)

    /** 篇幅要求：检查规则里写了字数就照它，没写按 3000 到 5000 字。 */
    private fun lengthOf(workId: String): String {
        val rule = store.pack(workId).filter { it.kind == PackKind.RULES }
            .flatMap { CheckRules.parse(store.packText(workId, it.id)).rules }
            .filterIsInstance<CheckRule.Length>().firstOrNull()
        val unit = if (rule?.han == true) "个汉字" else "字"
        return when {
            rule?.min != null && rule.max != null -> "${rule.min} 到 ${rule.max} $unit"
            rule?.min != null -> "不少于 ${rule.min} $unit"
            rule?.max != null -> "不超过 ${rule.max} $unit"
            else -> "3000 到 5000 字"
        }
    }

    /**
     * AI 写本章：[more] 为真时接着已有的草稿往下写，否则从头写一版（替换草稿）。边写边显示，写完存成草稿，
     * 作者看过再放进正文。
     */
    fun writeDraft(workId: String, chapterId: String, instruction: String = "", more: Boolean = false) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        draftJob?.cancel()
        val start = _draft.value?.takeIf { more && it.workId == workId && it.chapterId == chapterId }?.text.orEmpty()
        val prefix = if (start.isEmpty()) "" else start.trimEnd() + "\n"
        _draft.value = DraftState(workId, chapterId, prefix, loading = true, instruction = instruction)
        draftJob = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val (pack, previous, rules, length) = withContext(Dispatchers.IO) {
                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                    listOf(
                        chapterContext(workId, chapterId, store.text(workId, chapterId)).orEmpty(),
                        work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(2_000) }.orEmpty(),
                        writingRules(workId),
                        lengthOf(workId),
                    )
                }
                val prompt = if (prefix.isEmpty()) WritePrompts.draft(chapter.title, previous, rules, length, instruction)
                else WritePrompts.draftMore(chapter.title, prefix, rules, length)
                val system = WritePrompts.system(work.title, assistOf(workId), pack.ifEmpty { null }, examples = true)
                var written = ""
                val answer = ai.ask(system, prompt, whole = true) { piece ->
                    written += piece
                    _draft.update { d -> d?.takeIf { it.chapterId == chapterId }?.copy(text = prefix + written) ?: d }
                }
                val text = prefix + WritePrompts.cleanDraft(answer)
                _draft.update { d -> d?.takeIf { it.chapterId == chapterId }?.copy(text = text, loading = false) ?: d }
                withContext(Dispatchers.IO) { store.saveDraft(workId, chapterId, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _draft.update { d -> d?.takeIf { it.chapterId == chapterId }?.copy(loading = false, error = e.message ?: "请求失败") ?: d }
                // 写了一半也存下来，可以接着写。
                _draft.value?.takeIf { it.chapterId == chapterId && it.text.isNotBlank() }?.let { d ->
                    withContext(Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, d.text) } }
                }
            }
        }
    }

    /** 停下正在写的（写了的留着，存成草稿）。 */
    fun stopDraft() {
        draftJob?.cancel()
        val d = _draft.value ?: return
        _draft.value = d.copy(loading = false)
        if (d.text.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { runCatching { store.saveDraft(d.workId, d.chapterId, d.text) } } }
    }

    /** 草稿放进正文以后（或作者不要了）：清掉。 */
    fun clearDraft(workId: String, chapterId: String) {
        draftJob?.cancel()
        _draft.value = DraftState(workId, chapterId, "")
        scope.launch { withContext(Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, "") } } }
    }

    /** 停下正在审的（已经审完的几道留着，不存）。 */
    fun stopReview() {
        reviewJob?.cancel()
        _review.update { it?.copy(running = null) }
    }

    /** 把勾选的建议写进设定包的人物档案（没有就新建一份）。旧的文字先存一个历史版本。 */
    fun addProposals(workId: String, picked: List<BibleProposal>, then: () -> Unit = {}) {
        if (picked.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val date = java.time.LocalDate.now().toString()
                    val doc = characterDocs(workId).firstOrNull() ?: store.addPackDoc(workId, PackKind.CHARACTER, PackKind.CHARACTER.label)
                    val old = store.packText(workId, doc.id)
                    if (old.isNotBlank()) store.snapshot(workId, store.docKey(doc.id), old)
                    store.savePackText(workId, doc.id, WritePrompts.appendCharacters(old, picked, date))
                }
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
    }

    companion object {
        /** 实时检查一次最多发几段。 */
        const val REALTIME_BATCH = 6
        /** 太短的段（一两个字、一句短对话）不查。 */
        const val MIN_PARAGRAPH = 8
        const val EXTRACT_CHARS = 15_000

        /** 整理检查规则时最多带多少字的规范。 */
        const val RULE_SOURCE_CHARS = 30_000
    }
}

/** 一章的审稿：[running] 是正在审的那一道（审完是 null），[review] 是已经有的结果（可能是上次存下的）。 */
data class ReviewRun(val workId: String, val chapterId: String, val running: ReviewGate?, val review: ChapterReview?)

/** 批量写摘要的进度：第 [done] 章（从 0 数）/ 共 [total] 章，正在写 [title]。 */
data class SummaryProgress(val workId: String, val done: Int, val total: Int, val title: String)

/** AI 写的本章草稿。 */
data class DraftState(
    val workId: String,
    val chapterId: String,
    val text: String,
    val loading: Boolean = false,
    val error: String? = null,
    val instruction: String = "",
)
