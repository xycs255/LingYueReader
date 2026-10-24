package com.xycs.lingyuereader

import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.BibleEntry
import com.xycs.lingyuereader.data.BibleKind
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
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
                val (system, prompt) = when (task) {
                    is WriteAiTask.Edit -> {
                        val before = text.substring(0, task.start.coerceIn(0, text.length))
                        val after = text.substring(task.end.coerceIn(0, text.length))
                        // 本章开头的话，前文接上一章的结尾。
                        val context = if (before.length < WritePrompts.BEFORE_CHARS && previous.isNotBlank()) previous.takeLast(WritePrompts.BEFORE_CHARS - before.length) + "\n" + before else before
                        WritePrompts.system(work.title, assist, examples = task.action != WriteAction.CONTINUE) to
                            WritePrompts.edit(task.action, task.original, context, after, title, task.instruction)
                    }
                    is WriteAiTask.Idea -> {
                        val recent = (if (text.length < 6_000 && previous.isNotBlank()) previous.takeLast(6_000 - text.length) + "\n【" + title + "】\n" else "") + text.takeLast(6_000)
                        WritePrompts.system(work.title, assist) to WritePrompts.idea(task.kind, recent, title, task.extra)
                    }
                    WriteAiTask.Review -> WritePrompts.system(work.title, assist, withBible = false) to WritePrompts.review(text, title)
                    WriteAiTask.Consistency -> WritePrompts.system(work.title, assist) to WritePrompts.consistency(previous.takeLast(2_000), text, title)
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
                val answer = ai.ask(WritePrompts.system(work?.title.orEmpty(), assist, withBible = false), WritePrompts.proofread(todo), whole = true)
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
        val answer = ai.ask(WritePrompts.system(title, assist, withBible = false), WritePrompts.extract(text, assist.entries), whole = true)
        return WritePrompts.parseProposals(answer, assist.entries)
    }

    /** 把勾选的建议加进设定集：同名的人物更新说明，没有的新加。 */
    fun addProposals(workId: String, picked: List<BibleProposal>) {
        if (picked.isEmpty()) return
        updateAssist(workId) { a ->
            var entries = a.entries
            picked.forEach { p ->
                val i = entries.indexOfFirst { it.kind == BibleKind.CHARACTER && it.name.trim() == p.name }
                entries = if (i >= 0) entries.toMutableList().also { it[i] = it[i].copy(text = p.text) }
                else entries + BibleEntry(WorkStore.newId(), BibleKind.CHARACTER, p.name, p.text)
            }
            a.copy(entries = entries)
        }
    }

    companion object {
        /** 实时检查一次最多发几段。 */
        const val REALTIME_BATCH = 6
        /** 太短的段（一两个字、一句短对话）不查。 */
        const val MIN_PARAGRAPH = 8
        const val EXTRACT_CHARS = 15_000
    }
}
