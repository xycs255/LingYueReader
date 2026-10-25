package com.xycs.lingyuereader

import android.content.SharedPreferences
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.plannedCard
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.AiPack
import com.xycs.lingyuereader.data.PackKnowledge
import com.xycs.lingyuereader.data.PackTools
import com.xycs.lingyuereader.data.aiPack
import com.xycs.lingyuereader.data.chapterHints
import com.xycs.lingyuereader.data.packTools
import com.xycs.lingyuereader.data.CheckRule
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.RuleSet
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.WriteRecord
import com.xycs.lingyuereader.data.brief
import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.PackSync
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.data.SyncMark
import com.xycs.lingyuereader.data.applySync
import com.xycs.lingyuereader.data.chapterNo
import com.xycs.lingyuereader.data.unsynced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

/**
 * 在编辑器里直接写：AI 写的字先显示在 [at]（开始时的光标处），浅色，不进正文；作者采纳才插进去。
 * [base] 是开始时的正文：采纳时正文没变就插在 [at]，变了（不该发生，写的时候正文只读）就不插。
 */
data class InlineWrite(
    val workId: String,
    val chapterId: String,
    val at: Int,
    val base: String,
    val mode: InlineMode,
    val instruction: String = "",
    val text: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    /** 写完以后的说明（被截断了、停下了）。 */
    val note: String? = null,
)

/** 写作建议的状态：哪一章、找出的问题、正在查没有、上次出的错。 */
data class RealtimeState(
    val chapterId: String = "",
    val issues: List<WriteIssue> = emptyList(),
    val checking: Boolean = false,
    val error: String? = null,
    /** 没查是因为什么（离上次太近、今天的次数用完了）。 */
    val waiting: String? = null,
    /** 今天发了几次。 */
    val today: Int = 0,
)

/**
 * 写作的 AI：选中文字改写、灵感、读者点评、矛盾检查、写作建议、整理设定集。
 * 除了写作建议（每部作品单独开，默认关），都是作者点了才发请求；结果只给作者看，作者点“采纳”才改稿。
 */
class WritingAi(
    private val store: WorkStore,
    private val ai: AiAssistant,
    private val scope: CoroutineScope,
    /** 记写作建议每天发了几次（按作品、按日期，进程重启也接着算）；为空时只记在内存里。 */
    private val prefs: SharedPreferences? = null,
    private val message: (String) -> Unit,
) {
    val ready: Boolean get() = ai.currentConfig.ready

    /** 用的是本机模型：上下文短，资料、规范都少带。 */
    private val localModel: Boolean get() = ai.currentConfig.onDevice

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
                val pack = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text, style = true) }
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
                val label = when (task) {
                    is WriteAiTask.Edit -> task.action.label
                    is WriteAiTask.Idea -> task.kind.label
                    WriteAiTask.Review -> "读者点评"
                    WriteAiTask.Consistency -> "矛盾检查"
                }
                val onPiece: (String) -> Unit = { piece -> _state.update { s -> s?.takeIf { it.task === task }?.copy(text = s.text + piece) ?: s } }
                // 改稿类的结果要换进正文：被截断（whole）就报错，不给采纳；灵感、点评只给人看，截断了照样显示。
                val answer = if (task == WriteAiTask.Review) ask(label, system, prompt, onText = onPiece)
                else askWithPack(label, workId, system, prompt, whole = task is WriteAiTask.Edit, onReset = { _state.update { s -> s?.takeIf { it.task === task }?.copy(text = "") ?: s } }, onText = onPiece)
                if (task is WriteAiTask.Edit && answer.endsWith(STOPPED_NOTE)) {
                    // 模型中途停了：写出来的只是一半，显示出来但不能采纳，那行说明也不能进正文。
                    _state.update { s ->
                        s?.takeIf { it.task === task }?.copy(
                            text = WritePrompts.clean(answer.removeSuffix(STOPPED_NOTE), task.original),
                            loading = false,
                            error = "模型中途停了，结果不完整，不能采纳；可以再来一次",
                        ) ?: s
                    }
                    return@launch
                }
                val final = if (task is WriteAiTask.Edit) WritePrompts.clean(answer, task.original) else answer.trim()
                _state.update { s -> s?.takeIf { it.task === task }?.copy(text = final, loading = false) ?: s }
                // 灵感、点评、矛盾检查记下来，关了面板还能回头翻。
                if (task !is WriteAiTask.Edit) log(workId, chapterId, label, (task as? WriteAiTask.Idea)?.extra.orEmpty(), final)
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
        val task = s?.task
        if (s != null && task is WriteAiTask.Edit && !s.loading && s.error == null && s.text.isNotBlank()) {
            // 没采纳的改法记下来（续写不算），下次告诉 AI 别这样改。
            val example = StyleExample(task.action.label, task.original.take(300), s.text.take(400), System.currentTimeMillis())
            updateAssist(s.workId) { a ->
                a.copy(
                    rejected = a.rejected + 1,
                    declined = if (task.action == WriteAction.CONTINUE || task.original.isBlank()) a.declined else (a.declined + example).takeLast(WorkAssist.KEEP_REJECTED),
                )
            }
        }
    }

    /** 离开编辑器、旋转屏幕：只停下、收起面板，不算作者“不采纳”（作者没看完，不能当成不要这种改法）。 */
    fun dismiss() {
        job?.cancel()
        _state.value = null
    }

    /** 采纳了改稿：记下这次改法，以后润色时照这个分寸。 */
    fun accepted(workId: String, action: WriteAction, before: String, after: String) {
        job?.cancel()
        _state.value = null
        updateAssist(workId) { WritePrompts.remember(it, action, before, after, System.currentTimeMillis()) }
    }

    // ---------- 写作建议 ----------

    private var lastSuggest = 0L
    /** 作者标了“不改”的（段落、那几个字）：同一处不再提。 */
    private val dismissed = HashSet<Pair<String, String>>()
    /** 写作建议各作品今天发了几次（日期 → 次数），[prefs] 里也存一份。 */
    private val suggestCounts = HashMap<String, Pair<String, Int>>()

    /** [workId] 在 [day]（yyyy-MM-dd）发了几次写作建议。 */
    private fun suggestCount(workId: String, day: String): Int {
        val saved = suggestCounts[workId]
            ?: prefs?.getString(SUGGEST_COUNT_KEY + workId, null)?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) }
        return saved?.takeIf { it.first == day }?.second ?: 0
    }

    /** 记一次，返回今天发到第几次。 */
    private fun countSuggest(workId: String, day: String): Int {
        val n = suggestCount(workId, day) + 1
        suggestCounts[workId] = day to n
        prefs?.edit()?.putString(SUGGEST_COUNT_KEY + workId, "$day|$n")?.apply()
        return n
    }

    private fun paragraphsOf(text: String) = text.lines().map { it.trim() }.filter { it.length >= MIN_PARAGRAPH }

    /** 打开一章：已经写好的段落算查过，写作建议只查打开以后改过、新写的段。 */
    fun baseline(workId: String, chapterId: String, text: String) {
        checked.getOrPut(workId to chapterId) { HashSet() } += paragraphsOf(text)
    }

    /**
     * 查这一章里改过、还没查过的段落（最多 [REALTIME_BATCH] 段，从后往前，刚写的多在后面）。
     * 只在作品开了写作建议、AI 可用时调用；上一次还没查完就不发新的。
     */
    fun checkRealtime(workId: String, chapterId: String, text: String) {
        if (!ready || realtimeJob?.isActive == true) return
        val paragraphs = paragraphsOf(text)
        // 换了一章：问题清空。还在的问题里，段落已经改掉、错的字不在了的去掉。
        _realtime.update { r ->
            if (r.chapterId != chapterId) RealtimeState(chapterId, today = suggestCount(workId, java.time.LocalDate.now().toString()))
            else r.copy(issues = r.issues.filter { i -> i.paragraph in paragraphs && i.wrong in i.paragraph })
        }
        val seen = checked.getOrPut(workId to chapterId) { HashSet() }
        val todo = paragraphs.filter { it !in seen }.takeLast(REALTIME_BATCH)
        if (todo.isEmpty()) return
        realtimeJob = scope.launch {
            val assist = assistOf(workId)
            if (!assist.realtime) return@launch
            val kinds = assist.suggestKinds.mapNotNull { k -> SuggestKind.entries.firstOrNull { it.name == k } }.toSet()
            if (kinds.isEmpty()) return@launch
            // 省钱：两次至少隔几秒，一天最多几次（按作品、按日期记在盘上）。
            val today = java.time.LocalDate.now().toString()
            if (assist.suggestCap > 0 && suggestCount(workId, today) >= assist.suggestCap) {
                _realtime.update { it.copy(waiting = "今天的 ${assist.suggestCap} 次用完了，明天再查（作品的 AI 设置里能改）") }
                return@launch
            }
            val wait = lastSuggest + assist.suggestGap * 1_000L - System.currentTimeMillis()
            if (wait > 0) {
                _realtime.update { it.copy(waiting = null) }
                delay(wait)
            }
            val count = countSuggest(workId, today)
            lastSuggest = System.currentTimeMillis()
            _realtime.update { it.copy(checking = true, waiting = null, today = count) }
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) }
                // 只查错字（或本机模型）：不带设定包，一问一答。要查设定、规范、口吻：带上设定包和本章提示，
                // 但不给工具，一次请求就答（写作建议发得勤，不能每次查好几轮）。
                val typoOnly = !canUsePack || kinds == setOf(SuggestKind.TYPO)
                val answer = if (!typoOnly) {
                    val hints = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text) }
                    askWithPack("写作建议", workId, WritePrompts.system(work?.title.orEmpty(), assist, hints), WritePrompts.suggest(todo, kinds), whole = true, useTools = false)
                } else {
                    ask("写作建议", WritePrompts.system(work?.title.orEmpty(), assist), WritePrompts.proofread(todo), whole = true)
                }
                // 回答到了就算查过：格式不对也不再每次停笔都重发这几段（改过以后再查）。
                seen += todo
                val found = WritePrompts.parseIssues(answer, todo)
                    .filter { (it.paragraph to it.wrong) !in dismissed && SuggestKind.of(it.kind) in (if (typoOnly) setOf(SuggestKind.TYPO) else kinds) }
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

    /**
     * 正文排过版：写作建议是按段落原文认的，段落文字变了就对不上。查过的段换成排好的样子也算查过（不为排版多花一次请求）；
     * 找出的问题在排好的段里还找得到的留着（错的字也排过的按排好的认），找不到的去掉，那段重查。[reformat] 把一段排成排好的样子。
     */
    fun afterFormat(workId: String, chapterId: String, reformat: (String) -> String) {
        val seen = checked.getOrPut(workId to chapterId) { HashSet() }
        seen += seen.map(reformat)
        dismissed += dismissed.map { (p, w) -> reformat(p) to w }
        _realtime.update { r ->
            if (r.chapterId != chapterId) return@update r
            r.copy(issues = r.issues.mapNotNull { i ->
                val p = reformat(i.paragraph)
                when {
                    i.wrong in p -> i.copy(paragraph = p)
                    reformat(i.wrong).let { it.isNotEmpty() && it in p } -> i.copy(paragraph = p, wrong = reformat(i.wrong), right = reformat(i.right))
                    else -> {
                        seen -= p
                        null
                    }
                }
            })
        }
    }

    /** 这一类建议以后不提（作品的 AI 设置里能再打开）。 */
    fun muteKind(workId: String, kind: String) {
        updateAssist(workId) { a -> a.copy(suggestKinds = a.suggestKinds - kind) }
        _realtime.update { r -> r.copy(issues = r.issues.filter { it.kind != kind }) }
    }

    fun dismissIssue(issue: WriteIssue) {
        _realtime.update { r -> r.copy(issues = r.issues - issue) }
    }

    /** 作者看了说“不改”：去掉，同一处以后也不再提。 */
    fun ignoreIssue(issue: WriteIssue) {
        dismissed += issue.paragraph to issue.wrong
        dismissIssue(issue)
    }

    /** 离开编辑器、关了写作建议：停下正在查的。 */
    fun stopRealtime() {
        realtimeJob?.cancel()
        _realtime.value = RealtimeState()
    }

    // ---------- 在编辑器里写 ----------

    private val _inline = MutableStateFlow<InlineWrite?>(null)
    /** 在编辑器里正在写（或写完等作者采纳）的那一段。 */
    val inline: StateFlow<InlineWrite?> = _inline.asStateFlow()
    private var inlineJob: Job? = null
    /** 第几次让 AI 在编辑器里写：每次写只认自己这一次。 */
    private var inlineRun = 0

    /**
     * 从光标 [at] 处接着写：[text] 是编辑框里现在的全文。带设定包、细纲卡、写作规范和检查规则；
     * 写的字一段段显示在光标处，作者采纳才进正文。
     */
    fun writeInline(workId: String, chapterId: String, text: String, at: Int, mode: InlineMode, instruction: String = "") {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        inlineJob?.cancel()
        // 这一次的编号：旧的那次被取消（停下的说明）、出错时，不能去改新的这一次。
        val run = ++inlineRun
        fun update(transform: (InlineWrite) -> InlineWrite) {
            if (run == inlineRun) _inline.update { d -> d?.let(transform) }
        }
        val cursor = at.coerceIn(0, text.length)
        _inline.value = InlineWrite(workId, chapterId, cursor, text, mode, instruction)
        val head = text.substring(0, cursor)
        var written = ""
        inlineJob = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val (pack, rules, length, previous) = withContext(Dispatchers.IO) {
                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                    listOf(
                        chapterContext(workId, chapterId, text).orEmpty(),
                        writingRules(workId, chapterId, text),
                        lengthOf(workId),
                        work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(3_000) }.orEmpty(),
                    )
                }
                // 光标前不够长：前面接上一章的结尾。
                val before = (if (head.length < 4_000 && previous.isNotBlank()) "（上一章结尾）\n" + previous + "\n（本章）\n" else "") + head.takeLast(8_000)
                val after = text.substring(cursor).take(800)
                val system = WritePrompts.system(work.title, assistOf(workId), pack.ifEmpty { null }, examples = true)
                val prompt = WritePrompts.continueAt(chapter.title, before, after, mode, length, instruction, rules)
                // 长章节里每显示一次都要整章重排：流式的字最多每 [INLINE_REFRESH_MS] 毫秒更新一次显示。
                var shown = 0L
                val answer = askWithPack(
                    "在编辑器里写", workId, system, prompt, maxTokens = draftTokens,
                    onReset = {
                        written = ""
                        shown = 0L
                        update { it.copy(text = "") }
                    },
                ) { piece ->
                    written += piece
                    val now = System.currentTimeMillis()
                    if (now - shown >= INLINE_REFRESH_MS) {
                        shown = now
                        update { it.copy(text = joinAt(head, written)) }
                    }
                }
                val note = when {
                    answer.endsWith(TRUNCATED_NOTE) -> "写到长度上限停了，可以采纳后再接着写"
                    answer.endsWith(STOPPED_NOTE) -> "模型中途停了"
                    else -> null
                }
                val clean = WritePrompts.cleanDraft(answer.removeSuffix(TRUNCATED_NOTE).removeSuffix(STOPPED_NOTE))
                update { it.copy(text = joinAt(head, clean), loading = false, note = note) }
                log(workId, chapterId, "在编辑器里写", mode.label + instruction.let { if (it.isBlank()) "" else "：$it" }, clean)
            } catch (e: CancellationException) {
                // 停下：已经写出来的（可能还没显示出来）留着。
                val shownText = joinAt(head, written)
                update { it.copy(text = shownText, loading = false, note = if (shownText.isEmpty()) null else "停下了") }
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.message ?: "请求失败") }
            }
        }
    }

    /**
     * 写的字接在光标处怎么接：光标前一段已经说完（句号、问号、引号收尾）、AI 又没换行，就另起一段；
     * 光标前是一段没写完的话，就接着写。
     */
    private fun joinAt(head: String, written: String): String {
        val w = written.trimEnd()
        if (w.isEmpty() || head.isEmpty() || head.endsWith("\n")) return w.trimStart('\n')
        val done = head.trimEnd().lastOrNull()?.let { it in "。！？…”」』）" } == true
        return if (done && !w.startsWith("\n")) "\n" + w else w
    }

    /** 停下：已经写出来的留着，作者可以采纳或撤回。 */
    fun stopInline() {
        inlineJob?.cancel()
    }

    /** 撤回：AI 写的不要了。 */
    fun discardInline() {
        inlineJob?.cancel()
        _inline.value = null
    }

    /** 重写：同样的位置、同样的要求再写一次。 */
    fun retryInline() {
        val d = _inline.value ?: return
        writeInline(d.workId, d.chapterId, d.base, d.at, d.mode, d.instruction)
    }

    /**
     * 采纳：AI 写的插进正文，存一版（采纳前的正文先存进历史），AI 写的字不算码字数。
     * 返回插好以后的全文和光标位置；正文已经变了、插不进去时返回空。
     */
    fun acceptInline(current: String): Pair<String, Int>? {
        val d = _inline.value ?: return null
        if (d.loading || d.text.isBlank()) return null
        if (current != d.base) return null
        val text = current.substring(0, d.at) + d.text + current.substring(d.at)
        _inline.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (current.isNotBlank()) store.snapshot(d.workId, d.chapterId, current)
                    store.saveText(d.workId, d.chapterId, text, count = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
        return text to d.at + d.text.length
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
        val answer = ask("整理人物", WritePrompts.system(title, assist), WritePrompts.extract(text, names), whole = true)
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
        val answer = ask("整理检查规则", WritePrompts.system(title, assistOf(workId)), WritePrompts.rules(docs), whole = true)
        return WritePrompts.cleanRules(answer)
    }

    private fun characterDocs(workId: String) = store.pack(workId).filter { it.kind == PackKind.CHARACTER }

    private fun characterNames(workId: String): List<String> =
        characterDocs(workId).flatMap { WritePrompts.characterNames(store.packText(workId, it.id)) }.distinct()

    /** 写这一章要带给 AI 的资料：本章资料（按章挑的设定）加细纲卡和上一章的钩子。 */
    private fun chapterContext(workId: String, chapterId: String, text: String, style: Boolean = false): String? {
        val work = store.get(workId) ?: return null
        val chapters = work.chapters
        val i = chapters.indexOfFirst { it.id == chapterId }
        val prev = chapters.getOrNull(i - 1)
        val card = WritePrompts.card(store.plannedCard(workId, chapterId).card, prev?.title, prev?.let { store.plannedCard(workId, it.id).card })
        // 能查设定包的模型：设定包整份在前面，这里只给本章提示（细纲原文、察觉、伏笔、前情、章目录）；
        // 本机模型：按章挑选的资料。
        val brief = if (canUsePack) store.chapterHints(workId, chapterId, text) else store.brief(workId, chapterId, text, if (localModel) LOCAL_BRIEF_LIMIT else null, style)?.render()
        return listOfNotNull(brief, card).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { null }
    }

    // ---------- 三道审稿 ----------

    private val _lastRequest = MutableStateFlow<SentRequest?>(null)
    /** 最近一次发给 AI 的内容（系统提示和请求），作者可以看看到底发了什么。 */
    val lastRequest: StateFlow<SentRequest?> = _lastRequest.asStateFlow()

    /** 发请求，并记下发了什么。 */
    private suspend fun ask(label: String, system: String, prompt: String, whole: Boolean = false, onText: (String) -> Unit = {}): String {
        _lastRequest.value = SentRequest(label, system, prompt, System.currentTimeMillis())
        return ai.ask(system, prompt, whole, onText)
    }

    /** 知识库最多整份放多少字：作品里设了按设的，没设按模型的上下文算。 */
    private suspend fun knowledgeBudget(workId: String): Int {
        val set = assistOf(workId).knowledgeLimit
        val c = ai.currentConfig
        return if (set > 0) set else PackKnowledge.budget(c.contextTokens, c.protocol == AiProtocol.ANTHROPIC)
    }

    private val _lookup = MutableStateFlow<String?>(null)
    /** AI 正在查什么（“读《第一卷细纲》 › 细纲”“搜「渡口」”）：界面上显示，查完清空。 */
    val lookup: StateFlow<String?> = _lookup.asStateFlow()

    /** 能用设定包工具的模型（联网的服务商）；本机模型上下文小、不能用工具，走按章挑选的资料。 */
    private val canUsePack: Boolean get() = !localModel

    /**
     * 带设定包的请求：作者的工作说明和知识库放在系统提示最前面（文档不改就每次一样，服务商能按相同开头缓存），
     * 后面是这次的规矩 [rest]（作品、文风、本章提示、细纲卡）；AI 可以用工具读按需的文档、搜关键词、读某一章正文。
     * AI 决定去查时调 [onReset]：之前流出来的话不是回答，清掉。[useTools] 为假时不给工具，一次请求就答。
     */
    private suspend fun askWithPack(
        label: String,
        workId: String,
        rest: String,
        prompt: String,
        whole: Boolean = false,
        maxTokens: Int? = null,
        useTools: Boolean = true,
        maxRounds: Int = 6,
        toolsUpTo: String? = null,
        onReset: () -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (!canUsePack) return ask(label, rest, prompt, whole, onText)
        val budget = knowledgeBudget(workId)
        val (pack, tools) = withContext(Dispatchers.IO) { store.aiPack(workId, budget) to store.packTools(workId, toolsUpTo) }
        val system = pack.prefix + "\n\n=== 这次的工作 ===\n" + rest.trim() + if (useTools) "\n\n" + WritePrompts.TOOL_GUIDE else ""
        _lastRequest.value = SentRequest(label, system, prompt, System.currentTimeMillis())
        try {
            return ai.converse(
                system, prompt, if (useTools) PackTools.TOOLS else emptyList(),
                run = { call ->
                    val out = withContext(Dispatchers.IO) { tools.run(call) }
                    _lookup.value = tools.lookups.lastOrNull()
                    _lastRequest.update { it?.copy(lookups = tools.lookups.toList()) }
                    out
                },
                cachePrefix = pack.prefix.length,
                whole = whole,
                maxTokens = maxTokens,
                maxRounds = maxRounds,
                onLookup = { onReset() },
                onText = onText,
            )
        } finally {
            _lookup.value = null
        }
    }

    /** 这部作品现在给 AI 的设定包（看“本章资料”用）；本机模型不用这一套，返回空。 */
    suspend fun aiPack(workId: String): AiPack? {
        if (!canUsePack) return null
        val budget = knowledgeBudget(workId)
        return withContext(Dispatchers.IO) { store.aiPack(workId, budget) }
    }

    /** 写一整章时回答的长度上限：这几家能放到 8K token（约五六千字），别家用默认。 */
    private val draftTokens: Int?
        get() = ai.currentConfig.preset.takeIf { it in setOf("deepseek", "qwen", "kimi") }?.let { 8_192 }

    /** 记一条写作 AI 的结果（灵感、点评、矛盾检查、写本章）。 */
    private suspend fun log(workId: String, chapterId: String, kind: String, ask: String, text: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val title = store.get(workId)?.chapter(chapterId)?.title.orEmpty()
                store.addAiLog(workId, WriteRecord(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), chapterId, title, kind, ask, text))
            }
        }
        _logChanged.value = _logChanged.value + 1
    }

    private val _logChanged = MutableStateFlow(0)
    /** 记录有变化时加一（记录页据此刷新）。 */
    val logChanged: StateFlow<Int> = _logChanged.asStateFlow()

    suspend fun aiLog(workId: String): List<WriteRecord> = withContext(Dispatchers.IO) { store.aiLog(workId) }

    fun rateLog(workId: String, id: String, rating: Int) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.rateAiLog(workId, id, rating) } }
            _logChanged.value = _logChanged.value + 1
        }
    }

    fun deleteLog(workId: String, id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.deleteAiLog(workId, id) } }
            _logChanged.value = _logChanged.value + 1
        }
    }

    private val _reviews = MutableStateFlow<Map<Pair<String, String>, ReviewRun>>(emptyMap())
    /** 各章的审稿（看过或正在审的），按（作品id、章id）分开：别的章在审时打开这一章，不会弄乱那边。 */
    val reviews: StateFlow<Map<Pair<String, String>, ReviewRun>> = _reviews.asStateFlow()
    private val reviewJobs = HashMap<Pair<String, String>, Job>()
    /** 正在审的那一轮里作者标过的问题（哪一章 → 哪一道、原文 → 已改或不改）。 */
    private val runMarks = HashMap<Pair<String, String>, HashMap<Pair<ReviewGate, String>, String>>()

    /** 读这一章上次的审稿结果（这一章正在审就不动）。 */
    fun loadReview(workId: String, chapterId: String) {
        val key = workId to chapterId
        if (_reviews.value[key]?.running != null) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.review(workId, chapterId) }.getOrNull() }
            _reviews.update { m -> if (m[key]?.running != null) m else m + (key to ReviewRun(workId, chapterId, null, saved)) }
        }
    }

    /**
     * 按次序审 [text]（存盘的正文）：事实关、写法关请 AI（没设置 AI 就跳过），规则关在本机算。
     * 每道审完就显示出来；全部审完存下，下次打开还在。
     */
    fun startReview(workId: String, chapterId: String, text: String) {
        val key = workId to chapterId
        reviewJobs[key]?.cancel()
        // 上次标了“不改”的，这次再挑出来还是“不改”。
        val ignored = _reviews.value[key]?.review?.gates.orEmpty()
            .flatMap { g -> g.issues.filter { it.state == ReviewIssue.IGNORED }.map { g.gate to it.quote } }.toSet()
        val results = mutableListOf<GateResult>()
        val marks = HashMap<Pair<ReviewGate, String>, String>()
        runMarks[key] = marks
        fun publish(running: ReviewGate?) {
            // 审稿途中作者标的（已改、不改）每次都带上，后面几道审完、最后存盘时不会丢。
            val marked = results.map { g -> g.copy(issues = g.issues.map { i -> marks[g.gate to i.quote]?.let { i.copy(state = it) } ?: i }) }
            val run = ReviewRun(workId, chapterId, running, ChapterReview(text.hashCode(), System.currentTimeMillis(), marked))
            _reviews.update { it + (key to run) }
        }
        publish(ReviewGate.FACT)
        reviewJobs[key] = scope.launch {
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
                                val answer = askWithPack("事实关", workId, WritePrompts.system(work.title, assist, pack), WritePrompts.factReview(text, title, previous), whole = true)
                                reviewResult(gate, answer)
                            }
                        }
                        ReviewGate.CRAFT -> {
                            val rules = withContext(Dispatchers.IO) { styleRules(workId, chapterId, text) }
                            when {
                                !ready -> GateResult(gate, error = "没有设置 AI，跳过", at = System.currentTimeMillis())
                                rules.isBlank() -> GateResult(gate, error = "设定包里没有写作规范，跳过", at = System.currentTimeMillis())
                                else -> {
                                    val answer = ask("写法关", WritePrompts.system(work.title, assist), WritePrompts.craftReview(text, title, rules), whole = true)
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
                results += result.copy(issues = result.issues.map { if (gate to it.quote in ignored) it.copy(state = ReviewIssue.IGNORED) else it })
                publish(gate)
            }
            publish(null)
            val done = _reviews.value[key]?.review ?: return@launch
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
        return ask("章节摘要", WritePrompts.system(work.title, assistOf(workId)), WritePrompts.summary(text, title), whole = true).trim()
    }

    /**
     * AI 拟这一章的细纲卡：带上本章资料（按章挑的总纲、接续索引、人物、察觉、伏笔，前几章的细纲或摘要）、
     * 上一章结尾、下一章已经定下的细纲。结果交给作者看过再用，不直接存。
     */
    suspend fun planCard(workId: String, chapterId: String): ChapterCard {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val (system, prompt) = withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
            val i = work.chapters.indexOfFirst { it.id == chapterId }
            val previous = work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(1_500) }.orEmpty()
            val next = work.chapters.getOrNull(i + 1)
            val text = store.text(workId, chapterId)
            val pack = if (canUsePack) store.chapterHints(workId, chapterId, text) else store.brief(workId, chapterId, text, if (localModel) LOCAL_BRIEF_LIMIT else null)?.render()
            WritePrompts.system(work.title, assistOf(workId), pack) to
                WritePrompts.plan(chapter.title, previous, next?.let { store.plannedCard(workId, it.id).card }, next?.title, text)
        }
        val answer = askWithPack("拟细纲", workId, system, prompt, whole = true)
        val card = WritePrompts.parsePlan(answer)
        if (card.isEmpty) throw IllegalStateException("AI 没按格式回，换个模型或再试一次")
        log(workId, chapterId, "拟细纲", "", answer.trim())
        return card
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
            // 批量写要很久：这期间作者自己在细纲卡里写了摘要的章，跳过，不覆盖。
            var skipped = 0
            try {
                todo.forEachIndexed { i, c ->
                    _summaries.value = SummaryProgress(workId, i, todo.size, c.title)
                    try {
                        if (withContext(Dispatchers.IO) { store.card(workId, c.id).summary.isNotBlank() }) {
                            skipped++
                            return@forEachIndexed
                        }
                        val text = summarize(workId, c.id)
                        // 写入前重新读一次卡片：只填摘要，别的项用最新的；摘要已经有了就不动。
                        // 在同一把锁里读改写：细纲卡页面这时存卡也不会和这里互相盖掉。
                        val wrote = withContext(Dispatchers.IO) {
                            store.updateCard(workId, c.id) { latest -> if (latest.summary.isBlank()) latest.copy(summary = text) else latest }.summary == text
                        }
                        if (!wrote) skipped++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        if (failed >= 3) throw e
                    }
                }
                val done = todo.size - failed - skipped
                val kept = if (skipped > 0) "；$skipped 章你已经写了摘要，没动" else ""
                message((if (failed == 0) "写好了 $done 章的摘要，可以在各章细纲卡里改" else "写好了 $done 章的摘要，$failed 章没写成") + kept)
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

    private val _drafts = MutableStateFlow<Map<Pair<String, String>, DraftState>>(emptyMap())
    /**
     * AI 写的各章草稿（正在写或写好了的），按（作品id、章id）分开：
     * A 章在写时打开 B 章的草稿页，不会把 A 的状态换掉，回到 A 也不会把没写完的当成写好了。
     */
    val drafts: StateFlow<Map<Pair<String, String>, DraftState>> = _drafts.asStateFlow()
    private val draftJobs = HashMap<Pair<String, String>, Job>()
    /** 各章当前这一次写的编号：被新的一次或“不要这版”顶掉的旧任务，不能再改状态、存草稿。 */
    private val draftRuns = HashMap<Pair<String, String>, Int>()
    private var draftSeq = 0

    /** 改这一章的草稿状态（[run] 不是这一章当前的那一次就不改）。 */
    private fun updateDraft(key: Pair<String, String>, run: Int, transform: (DraftState) -> DraftState) {
        if (draftRuns[key] != run) return
        _drafts.update { m -> m[key]?.let { m + (key to transform(it)) } ?: m }
    }

    /** 打开草稿页：读上次没放进正文的草稿（这一章已经有状态，比如正在写，就不动）。 */
    fun loadDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        if (key in _drafts.value) return
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { store.draft(workId, chapterId) }.getOrDefault("") }
            _drafts.update { m -> if (key in m) m else m + (key to DraftState(workId, chapterId, text)) }
        }
    }

    /** 写作规范、称谓与语气（太长时节选：写到本章出场人物的小节、后补的小节优先）。 */
    private fun styleRules(workId: String, chapterId: String, text: String): String {
        val docs = store.pack(workId).filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE }.map { it to store.packText(workId, it.id) }
        if (docs.none { it.second.isNotBlank() }) return ""
        val present = store.brief(workId, chapterId, text)?.present.orEmpty()
        return ChapterBriefs.styleFor(docs, present, if (localModel) LOCAL_STYLE_LIMIT else ChapterBriefs.STYLE_LIMIT)
    }

    /** 写正文时照着写的：写作规范、称谓与语气（节选），加上检查规则原文。 */
    private fun writingRules(workId: String, chapterId: String, text: String): String {
        val rules = store.pack(workId).filter { it.kind == PackKind.RULES }
            .map { "【${it.title}】\n" + store.packText(workId, it.id).trim() }
            .filter { it.substringAfter('\n').isNotBlank() }
        return (listOf(styleRules(workId, chapterId, text)) + rules).filter { it.isNotBlank() }.joinToString("\n\n")
    }

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
        val key = workId to chapterId
        draftJobs[key]?.cancel()
        val run = ++draftSeq
        draftRuns[key] = run
        val start = _drafts.value[key]?.takeIf { more }?.text.orEmpty()
        val prefix = if (start.isEmpty()) "" else start.trimEnd() + "\n"
        _drafts.update { it + (key to DraftState(workId, chapterId, prefix, loading = true, instruction = instruction)) }
        var written = ""
        draftJobs[key] = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val (pack, previous, rules, length) = withContext(Dispatchers.IO) {
                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                    listOf(
                        chapterContext(workId, chapterId, store.text(workId, chapterId)).orEmpty(),
                        // 上一章结尾：能查设定包的模型多给些（要更多它自己读），本机模型少给。
                        work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(if (canUsePack) 6_000 else 2_000) }.orEmpty(),
                        writingRules(workId, chapterId, store.text(workId, chapterId)),
                        lengthOf(workId),
                    )
                }
                val prompt = if (prefix.isEmpty()) WritePrompts.draft(chapter.title, previous, rules, length, instruction)
                else WritePrompts.draftMore(chapter.title, prefix, rules, length)
                val system = WritePrompts.system(work.title, assistOf(workId), pack.ifEmpty { null }, examples = true)
                val answer = askWithPack(
                    if (prefix.isEmpty()) "写本章" else "接着写", workId, system, prompt, whole = true, maxTokens = draftTokens,
                    onReset = {
                        written = ""
                        updateDraft(key, run) { it.copy(text = prefix) }
                    },
                ) { piece ->
                    written += piece
                    updateDraft(key, run) { it.copy(text = prefix + written) }
                }
                val text = prefix + WritePrompts.cleanDraft(answer)
                updateDraft(key, run) { it.copy(text = text, loading = false) }
                withContext(Dispatchers.IO) { store.saveDraft(workId, chapterId, text) }
                log(workId, chapterId, if (prefix.isEmpty()) "写本章" else "接着写", instruction, text.removePrefix(prefix))
            } catch (e: CancellationException) {
                // 被取消（应用退出之类）：已经写出来的留着，存成草稿，可以接着写。
                // 被顶掉的（新的一次、“不要这版”，或者点了停止、[stopDraft] 已经存过）不管。
                if (draftRuns[key] == run) {
                    val partial = prefix + WritePrompts.cleanDraft(written)
                    updateDraft(key, run) { it.copy(text = partial, loading = false) }
                    if (partial.isNotBlank()) withContext(NonCancellable + Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, partial) } }
                }
                throw e
            } catch (e: Exception) {
                updateDraft(key, run) { it.copy(loading = false, error = e.message ?: "请求失败") }
                // 写了一半也存下来，可以接着写。
                _drafts.value[key]?.takeIf { draftRuns[key] == run && it.text.isNotBlank() }?.let { d ->
                    withContext(Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, d.text) } }
                }
            }
        }
    }

    /** 停下这一章正在写的：停在作者看到的这一刻，写了的留着，存成草稿。 */
    fun stopDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        // 换个编号：取消以后还在路上的字、取消时的处理都不再改它。
        draftRuns[key] = ++draftSeq
        draftJobs.remove(key)?.cancel()
        val d = _drafts.value[key] ?: return
        _drafts.update { it + (key to d.copy(loading = false)) }
        if (d.text.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, d.text) } } }
    }

    /** 草稿放进正文以后（或作者不要了）：清掉。 */
    fun clearDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        // 换个编号：正在写的那次被取消时不再存草稿。
        draftRuns[key] = ++draftSeq
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to DraftState(workId, chapterId, "")) }
        scope.launch { withContext(Dispatchers.IO) { runCatching { store.saveDraft(workId, chapterId, "") } } }
    }

    /** 作者把一处问题标为已改、不改（[state] 为空是改回没处理）。 */
    fun markIssue(workId: String, chapterId: String, gate: ReviewGate, issue: ReviewIssue, state: String) {
        val key = workId to chapterId
        val run = _reviews.value[key] ?: return
        val review = run.review ?: return
        if (run.running != null) runMarks[key]?.set(gate to issue.quote, state)
        val next = review.copy(gates = review.gates.map { g -> if (g.gate != gate) g else g.copy(issues = g.issues.map { if (it == issue) it.copy(state = state) else it }) })
        _reviews.update { it + (key to run.copy(review = next)) }
        if (run.running == null) scope.launch { withContext(Dispatchers.IO) { runCatching { store.saveReview(workId, chapterId, next) } } }
    }

    /** 停下这一章正在审的（已经审完的几道留着，不存）。 */
    fun stopReview(workId: String, chapterId: String) {
        val key = workId to chapterId
        reviewJobs.remove(key)?.cancel()
        _reviews.update { m -> m[key]?.let { m + (key to it.copy(running = null)) } ?: m }
    }

    /** 把勾选的建议写进设定包的人物档案（没有就新建一份）。旧的文字先存一个历史版本。 */
    fun addProposals(workId: String, picked: List<BibleProposal>, then: () -> Unit = {}) {
        if (picked.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val date = java.time.LocalDate.now().toString()
                    val docs = characterDocs(workId)
                    // 改好的文字（文档id → 全文）：已有的人物在所有人物档案里找他那一节换掉，找不到的和新人物一起追加到第一份。
                    val changed = LinkedHashMap<String, String>()
                    val rest = picked.filterNot { p ->
                        p.existing && docs.any { d ->
                            WritePrompts.replaceCharacter(changed[d.id] ?: store.packText(workId, d.id), p.name, p.text, date)?.also { changed[d.id] = it } != null
                        }
                    }
                    if (rest.isNotEmpty()) {
                        val doc = docs.firstOrNull() ?: store.addPackDoc(workId, PackKind.CHARACTER, PackKind.CHARACTER.label)
                        changed[doc.id] = WritePrompts.appendCharacters(changed[doc.id] ?: store.packText(workId, doc.id), rest, date)
                    }
                    changed.forEach { (id, text) ->
                        val old = store.packText(workId, id)
                        if (old.isNotBlank()) store.snapshot(workId, store.docKey(id), old)
                        store.savePackText(workId, id, text)
                    }
                }
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
    }

    // ---------- 整理进设定包 ----------

    private val _sync = MutableStateFlow<SyncSession?>(null)
    /** 整理进设定包的结果（一章，或批量整理的几章），作者勾选、改过再写进设定包；写完清空。 */
    val sync: StateFlow<SyncSession?> = _sync.asStateFlow()
    private var syncJob: Job? = null

    /** 整理一章：读这一章（[text] 是编辑框里现在的正文），带上设定包，一次请求，最多查两轮。 */
    private suspend fun syncOne(workId: String, chapterId: String, text: String): ChapterSyncResult {
        if (!canUsePack) throw IllegalStateException("本机模型上下文太小、不能查设定包，整理本章要用联网的 AI 服务商")
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
        val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
        val number = withContext(Dispatchers.IO) { store.chapterNo(work, chapterId) } ?: (work.chapters.indexOfFirst { it.id == chapterId } + 1)
        val base = ChapterSyncResult(chapterId, chapter.title, number, text.hashCode())
        if (text.isBlank()) return base.copy(error = "这一章还没有正文")
        val hints = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text) }
        val answer = askWithPack(
            "整理本章", workId, WritePrompts.system(work.title, assistOf(workId), hints), WritePrompts.sync(chapter.title, number, text),
            whole = true, maxTokens = SYNC_TOKENS, maxRounds = SYNC_ROUNDS, toolsUpTo = chapterId,
        )
        val parsed = WritePrompts.parseSync(answer, text, chapterId, number)
        val docs = withContext(Dispatchers.IO) { store.pack(workId).map { it to store.packText(workId, it.id) } }
        val planned = PackSync.plan(parsed.items, docs)
        return base.copy(items = planned, dropped = parsed.dropped, known = parsed.items.size - planned.size)
    }

    /** 现在不能整理的原因（没设 AI、用的是本机模型）；能整理时为空。 */
    val syncBlocked: String?
        get() = when {
            !ready -> "先在“我的 → AI 助手”里开启并填好服务商和密钥"
            !canUsePack -> "本机模型上下文太小、不能查设定包，整理进设定包要用联网的 AI 服务商"
            else -> null
        }

    /** 整理这一章（编辑器“更多”里、定稿时的提示）。[text] 为空时用存盘的正文。 */
    fun syncChapter(workId: String, chapterId: String, text: String? = null) {
        syncBlocked?.let {
            message(it)
            return
        }
        if (syncJob?.isActive == true && _sync.value?.batch == true) {
            message("正在批量整理章节，等它整理完或停下再来")
            return
        }
        syncJob?.cancel()
        _sync.value = SyncSession(workId, running = "", total = 1)
        syncJob = scope.launch {
            val (title, body) = withContext(Dispatchers.IO) { store.get(workId)?.chapter(chapterId)?.title.orEmpty() to (text ?: store.text(workId, chapterId)) }
            _sync.update { s -> s?.copy(running = title) }
            val result = try {
                syncOne(workId, chapterId, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChapterSyncResult(chapterId, title, 0, body.hashCode(), error = e.message ?: "请求失败")
            }
            _sync.update { s -> s?.takeIf { it.workId == workId }?.with(result)?.copy(running = null, done = 1) ?: s }
        }
    }

    /**
     * 整理没整理的章（作品页）：按目录顺序一章一次请求，整理一章攒一章的提议，最后统一勾选。
     * 连着 3 章没整理成就停下；这期间作者自己整理了的章跳过。
     */
    fun syncAll(workId: String) {
        syncBlocked?.let {
            message(it)
            return
        }
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            val todo = withContext(Dispatchers.IO) { store.unsynced(workId, assistOf(workId).synced) }
            if (todo.isEmpty()) {
                message("有正文的章都整理过了")
                return@launch
            }
            _sync.value = SyncSession(workId, batch = true, total = todo.size)
            var failed = 0
            try {
                todo.forEachIndexed { i, c ->
                    _sync.update { it?.copy(running = c.title, done = i) }
                    val text = withContext(Dispatchers.IO) { store.text(workId, c.id) }
                    if (ChapterSyncs.isSynced(assistOf(workId).synced[c.id], text)) return@forEachIndexed
                    val result = try {
                        syncOne(workId, c.id, text).also { failed = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        ChapterSyncResult(c.id, c.title, 0, text.hashCode(), error = e.message ?: "请求失败")
                    }
                    _sync.update { it?.with(result) }
                    if (failed >= 3) throw IllegalStateException(result.error)
                }
                val s = _sync.value
                message("整理完 ${s?.results?.size ?: 0} 章，有 ${s?.items?.size ?: 0} 条提议，勾选后写进设定包")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("整理停下了：${e.message ?: "请求失败"}")
            } finally {
                _sync.update { s -> s?.copy(running = null, done = s.results.size) }
            }
        }
    }

    /** 停下：已经整理完的章留着，可以勾选写进去。 */
    fun stopSync() {
        syncJob?.cancel()
        _sync.update { s -> s?.copy(running = null) }
    }

    /** 不要这次的结果了。 */
    fun clearSync() {
        syncJob?.cancel()
        _sync.value = null
    }

    /** 勾上、取消一条。 */
    fun toggleSync(key: String, on: Boolean) {
        _sync.update { s -> s?.copy(picked = if (on) s.picked + key else s.picked - key) }
    }

    /** 改一条要写进去的文字。 */
    fun editSync(key: String, text: String) {
        _sync.update { s -> s?.copy(edits = s.edits + (key to text)) }
    }

    /**
     * 把勾选的写进设定包（改过的按改过的写），写之前存历史版本、每条记一笔变更登记；
     * 这次整理的各章（没出错的）记成已整理。[then] 拿到一句“写了几条进哪几份文档”。
     */
    fun applySync(workId: String, then: (String) -> Unit = {}) {
        val session = _sync.value?.takeIf { it.workId == workId && it.running == null } ?: return
        val picked = session.items.filter { it.writable && it.key in session.picked }.map { i -> session.edits[i.key]?.let { i.copy(text = it) } ?: i }
        scope.launch {
            try {
                val counts = withContext(Dispatchers.IO) { store.applySync(workId, picked) }
                val now = System.currentTimeMillis()
                val marks = session.results.filter { it.error == null }.associate { it.chapterId to SyncMark(it.hash, now) }
                updateAssist(workId) { it.copy(synced = it.synced + marks) }
                _sync.value = null
                then(
                    if (counts.isEmpty()) "没有写进设定包，${marks.size} 章记为已整理"
                    else "写进了 ${counts.values.sum()} 条：" + counts.entries.joinToString("、") { "《${it.key}》${it.value} 条" },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("写进设定包失败：${e.message ?: "写入出错"}")
            }
        }
    }

    companion object {
        /** 写作建议一次最多发几段。 */
        const val REALTIME_BATCH = 6
        /** 太短的段（一两个字、一句短对话）不查。 */
        const val MIN_PARAGRAPH = 8
        const val EXTRACT_CHARS = 15_000

        /** 整理检查规则时最多带多少字的规范。 */
        const val RULE_SOURCE_CHARS = 30_000

        /** 本机模型上下文短：写作规范、本章资料各只带这么多字（放不下的再由 [com.xycs.lingyuereader.ai.DevicePrompt] 截短）。 */
        const val LOCAL_STYLE_LIMIT = 1_200
        const val LOCAL_BRIEF_LIMIT = 1_500

        /** 整理本章：回答最长多少 token、最多查几轮设定包。 */
        const val SYNC_TOKENS = 4_096
        const val SYNC_ROUNDS = 2

        /** AI 在编辑器里写时，流式的字最多隔这么久刷新一次显示（每次都要整章重排）。 */
        const val INLINE_REFRESH_MS = 150L

        /** [com.xycs.lingyuereader.ai.AiClient] 在回答后面加的说明：被截断了、模型中途停了。 */
        private const val TRUNCATED_NOTE = "\n\n（回答太长，后面被截断了。）"
        private const val STOPPED_NOTE = "\n\n（模型中途停止了回答。）"

        /** 写作建议今天发了几次存在偏好里的键（后面接作品id）。 */
        private const val SUGGEST_COUNT_KEY = "suggest_count."
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

/** 发给 AI 的一次请求：做什么、系统提示、请求正文。 */
data class SentRequest(
    val label: String,
    val system: String,
    val prompt: String,
    val at: Long,
    /** AI 用工具查了什么（读了哪份文档、哪一节，搜了什么，读了哪一章）。 */
    val lookups: List<String> = emptyList(),
) {
    val size: Int get() = system.length + prompt.length
}

/** 一章的整理结果：[hash] 是整理时正文的哈希；[dropped] 是正文里找不到原句丢掉的，[known] 是设定包里已有、没再列的。 */
data class ChapterSyncResult(
    val chapterId: String,
    val title: String,
    val number: Int,
    val hash: Int,
    val items: List<SyncItem> = emptyList(),
    val dropped: Int = 0,
    val known: Int = 0,
    val error: String? = null,
)

/**
 * 一次整理（一章，或批量的几章）：[running] 是正在整理的章名（整理完是 null）；
 * [picked] 是勾上的提议（按 [SyncItem.key]），[edits] 是作者改过的文字。
 */
data class SyncSession(
    val workId: String,
    val results: List<ChapterSyncResult> = emptyList(),
    val running: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val batch: Boolean = false,
    val picked: Set<String> = emptySet(),
    val edits: Map<String, String> = emptyMap(),
) {
    val items: List<SyncItem> get() = results.flatMap { it.items }

    /** 加上一章的结果：默认勾上新设定和察觉。 */
    fun with(r: ChapterSyncResult): SyncSession =
        copy(results = results.filterNot { it.chapterId == r.chapterId } + r, picked = picked + r.items.filter { it.defaultOn }.map { it.key })
}

