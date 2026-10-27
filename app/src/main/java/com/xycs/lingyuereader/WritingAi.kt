package com.xycs.lingyuereader

import android.content.SharedPreferences
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiCost
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.AiMeter
import com.xycs.lingyuereader.ai.AiRequestBudget
import com.xycs.lingyuereader.ai.AiRequestBudgetPreview
import com.xycs.lingyuereader.ai.AiUsage
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
import com.xycs.lingyuereader.data.SampleChapter
import com.xycs.lingyuereader.data.SamplePick
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.StyleSamples
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
import com.xycs.lingyuereader.data.syncLedger
import com.xycs.lingyuereader.data.unsynced
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftAssessment
import com.xycs.lingyuereader.data.DraftChecks
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.DraftWorkflow
import com.xycs.lingyuereader.data.reviewEvidence
import com.xycs.lingyuereader.data.DraftReferenceProvenance
import com.xycs.lingyuereader.data.DraftContent
import com.xycs.lingyuereader.data.DraftRevisionSnapshot
import com.xycs.lingyuereader.data.WritingPhase
import com.xycs.lingyuereader.data.writingTask
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
/** A proposed edit may resolve one issue from one particular review; a request itself resolves nothing. */
data class ReviewFix(val gate: ReviewGate, val issue: ReviewIssue, val reviewAt: Long, val textHash: Int)

sealed interface WriteAiTask {
    val title: String

    /**
     * 改一段：[original] 是原文（续写时为空），在正文的 [start]..[end]（不含）。
     * 采纳时按位置换；正文在那之后又改过、位置对不上时，按原文找。
     */
    data class Edit(
        val action: WriteAction,
        val original: String,
        val start: Int,
        val end: Int,
        val instruction: String = "",
        val reviewFix: ReviewFix? = null,
        val continuationBase: String? = null,
    ) : WriteAiTask {
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
    /** 这次用了多少、约多少钱（写完才有）。 */
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
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
    /** 这次用了多少、约多少钱（写完、停下以后才有）。 */
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
    val referenceAnalysisId: String? = null,
    val referenceFingerprint: String? = null,
    val referenceChapterLength: Int? = null,
    /** A stopped/failed stream based on a reference has not passed the source-copy check. */
    val referenceValidated: Boolean = false,
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

/** Upper bound for one complete chapter response, including V4's thinking tokens. */
internal fun draftOutputTokenLimit(config: AiConfig): Int? {
    val deepseek = config.preset == "deepseek" ||
        runCatching { java.net.URI(config.baseUrl.trim()).host.equals("api.deepseek.com", ignoreCase = true) }.getOrDefault(false)
    if (deepseek) {
        return when (config.model.trim().lowercase()) {
            "deepseek-v4-pro" -> 32_768
            "deepseek-flash", "deepseek-v4-flash" -> 16_384
            else -> 8_192
        }
    }
    return if (config.preset in setOf("qwen", "kimi")) 8_192 else null
}

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
    /** 停笔事件可能在上一请求尚未结束时到达，只保留最新的正文，避免漏检和重复计费。 */
    private data class PendingRealtime(val workId: String, val chapterId: String, val text: String)
    private var pendingRealtime: PendingRealtime? = null
    /** 离开编辑器后，旧请求的完成回调不能启动新的检查。 */
    private var realtimeGeneration = 0
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
        // Restored candidate bytes must not retain the former in-memory manuscript's passing report.
        draftRuns.clear()
        draftJobs.values.forEach { it.cancel() }
        draftJobs.clear()
        _drafts.value = emptyMap()
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
                val taskSnapshot = withContext(Dispatchers.IO) { store.writingTask(workId, chapterId) }
                val previous = withContext(Dispatchers.IO) {
                    val chapters = work.chapters
                    val i = chapters.indexOfFirst { it.id == chapterId }
                    if (taskSnapshot?.project == true) taskSnapshot.previous else chapters.getOrNull(i - 1)?.let { store.text(workId, it.id) }.orEmpty()
                }
                // 本章资料（常用文档、出场人物、察觉、伏笔、提到这一章的小节），加上这一章的细纲卡：改稿、灵感、矛盾检查都带上。
                val pack = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text, style = true) }
                // 改稿类（挑错除外）带上定稿章里挑的文风范例。
                val samples = if (task is WriteAiTask.Edit && WritePrompts.usesSamples(task.action)) samplesFor(workId, chapterId) else null
                val (system, prompt) = when (task) {
                    is WriteAiTask.Edit -> {
                        val before = text.substring(0, task.start.coerceIn(0, text.length))
                        val after = text.substring(task.end.coerceIn(0, text.length))
                        // 本章开头的话，前文接上一章的结尾。
                        val context = if (before.length < WritePrompts.BEFORE_CHARS && previous.isNotBlank()) previous.takeLast(WritePrompts.BEFORE_CHARS - before.length) + "\n" + before else before
                        WritePrompts.system(work.title, assist, pack, examples = task.action != WriteAction.CONTINUE, samples = samples) to
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
                val feature = when (task) {
                    is WriteAiTask.Edit -> if (task.action == WriteAction.CONTINUE) AiFeature.INLINE else AiFeature.EDIT
                    else -> AiFeature.IDEA
                }
                val meter = AiMeter(feature, workId)
                // 改稿类的结果要换进正文：被截断（whole）就报错，不给采纳；灵感、点评只给人看，截断了照样显示。
                val answer = if (task == WriteAiTask.Review) ask(label, workId, system, prompt, meter = meter, onText = onPiece)
                else askWithPack(label, workId, system, prompt, whole = task is WriteAiTask.Edit, toolsUpTo = chapterId,
                    confirmedOnly = taskSnapshot?.project == true, requireTools = taskSnapshot?.project == true,
                    stable = samples, meter = meter,
                    onReset = { _state.update { s -> s?.takeIf { it.task === task }?.copy(text = "") ?: s } }, onText = onPiece)
                if (task is WriteAiTask.Edit && answer.endsWith(STOPPED_NOTE)) {
                    // 模型中途停了：写出来的只是一半，显示出来但不能采纳，那行说明也不能进正文。
                    _state.update { s ->
                        s?.takeIf { it.task === task }?.copy(
                            text = WritePrompts.clean(answer.removeSuffix(STOPPED_NOTE), task.original),
                            loading = false,
                            error = "模型中途停了，结果不完整，不能采纳；可以再来一次",
                            usage = meter.usage,
                            cost = meter.cost,
                        ) ?: s
                    }
                    return@launch
                }
                val final = if (task is WriteAiTask.Edit) WritePrompts.clean(answer, task.original) else answer.trim()
                _state.update { s -> s?.takeIf { it.task === task }?.copy(text = final, loading = false, usage = meter.usage, cost = meter.cost) ?: s }
                // 灵感、点评、矛盾检查记下来，关了面板还能回头翻。
                if (task !is WriteAiTask.Edit) log(workId, chapterId, label, (task as? WriteAiTask.Idea)?.extra.orEmpty(), final, meter)
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
     * 只在作品开了写作建议、AI 可用时调用；上一次还没查完就记下最新正文，完成后再检查。
     */
    fun checkRealtime(workId: String, chapterId: String, text: String) {
        if (!ready) return
        if (realtimeJob?.isActive == true) {
            pendingRealtime = PendingRealtime(workId, chapterId, text)
            return
        }
        // 当前这次调用已经比先前排队的停笔事件更新，不再回放旧正文。
        pendingRealtime = null
        val paragraphs = paragraphsOf(text)
        // 换了一章：问题清空。还在的问题里，段落已经改掉、错的字不在了的去掉。
        _realtime.update { r ->
            if (r.chapterId != chapterId) RealtimeState(chapterId, today = suggestCount(workId, java.time.LocalDate.now().toString()))
            else r.copy(issues = r.issues.filter { i -> i.paragraph in paragraphs && i.wrong in i.paragraph })
        }
        val seen = checked.getOrPut(workId to chapterId) { HashSet() }
        val todo = paragraphs.filter { it !in seen }.takeLast(REALTIME_BATCH)
        if (todo.isEmpty()) return
        val generation = realtimeGeneration
        var checkedSuccessfully = false
        val launched = scope.launch {
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
            _realtime.update { it.copy(checking = true, waiting = null, error = null, today = count) }
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) }
                // 只查错字（或本机模型）：不带设定包，一问一答。要查设定、规范、口吻：带上设定包和本章提示，
                // 但不给工具，一次请求就答（写作建议发得勤，不能每次查好几轮）。
                val typoOnly = !canUsePack || kinds == setOf(SuggestKind.TYPO)
                val answer = if (!typoOnly) {
                    val hints = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text) }
                    askWithPack("写作建议", workId, WritePrompts.system(work?.title.orEmpty(), assist, hints), WritePrompts.suggest(todo, kinds), whole = true, useTools = false)
                } else {
                    ask("写作建议", workId, WritePrompts.system(work?.title.orEmpty(), assist), WritePrompts.proofread(todo), whole = true)
                }
                // 只有结构正确的回答才算查过；格式错误显示出来，等下一次停笔由作者决定是否重试。
                val found = WritePrompts.parseIssues(answer, todo)
                    .filter { (it.paragraph to it.wrong) !in dismissed && SuggestKind.of(it.kind) in (if (typoOnly) setOf(SuggestKind.TYPO) else kinds) }
                if (generation != realtimeGeneration) return@launch
                seen += todo
                checkedSuccessfully = true
                _realtime.update { r ->
                    if (r.chapterId != chapterId) r else r.copy(issues = (r.issues + found).distinctBy { it.paragraph to it.wrong }, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == realtimeGeneration) {
                    _realtime.update { r -> if (r.chapterId == chapterId) r.copy(error = e.message ?: "检查失败") else r }
                }
            } finally {
                if (generation == realtimeGeneration) {
                    _realtime.update { r -> if (r.chapterId == chapterId) r.copy(checking = false) else r }
                }
            }
        }
        realtimeJob = launched
        launched.invokeOnCompletion { cause ->
            // 完成回调可能不在主线程；交回 ViewModel 的 scope 后才读取排队状态。
            scope.launch {
                if (realtimeJob !== launched) return@launch
                realtimeJob = null
                if (cause is CancellationException || generation != realtimeGeneration) return@launch
                // 失败时保留最新待检正文，但不自动重发收费请求；下一次停笔才重试。
                if (!checkedSuccessfully) return@launch
                val pending = pendingRealtime ?: return@launch
                pendingRealtime = null
                checkRealtime(pending.workId, pending.chapterId, pending.text)
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
        realtimeGeneration++
        pendingRealtime = null
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
    fun writeInline(workId: String, chapterId: String, text: String, at: Int, mode: InlineMode, instruction: String = "", referenceAnalysisId: String? = null) {
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
        _inline.value = InlineWrite(workId, chapterId, cursor, text, mode, instruction, referenceAnalysisId = referenceAnalysisId)
        val head = text.substring(0, cursor)
        var written = ""
        val meter = AiMeter(AiFeature.INLINE, workId)
        inlineJob = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val taskSnapshot = withContext(Dispatchers.IO) { store.writingTask(workId, chapterId, instruction) }
                val reference = referenceAnalysisId?.let { withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, it) } }
                if (reference != null) update { it.copy(referenceFingerprint = reference.excerpt.fingerprint,
                    referenceChapterLength = reference.excerpt.chapterLength) }
                val (pack, rules, length, previous) = withContext(Dispatchers.IO) {
                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                    listOf(
                        chapterContext(workId, chapterId, text).orEmpty(),
                        writingRules(workId, chapterId, text),
                        taskSnapshot?.takeIf { it.project }?.targetLength ?: lengthOf(workId),
                        if (taskSnapshot?.project == true) taskSnapshot.previous.takeLast(3_000) else work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(3_000) }.orEmpty(),
                    )
                }
                // 光标前不够长：前面接上一章的结尾。
                val before = (if (head.length < 4_000 && previous.isNotBlank()) "（上一章结尾）\n" + previous + "\n（本章）\n" else "") + head.takeLast(8_000)
                val after = text.substring(cursor).take(800)
                val samples = samplesFor(workId, chapterId)
                val system = WritePrompts.system(work.title, assistOf(workId), pack.ifEmpty { null }, examples = true, samples = samples) +
                    (if (reference == null) "" else "\n\n" + ReferenceWriting.SYSTEM_RULE)
                val prompt = WritePrompts.continueAt(chapter.title, before, after, mode, length, instruction, rules) +
                    (reference?.let { "\n\n" + ReferenceWriting.prompt(it) } ?: "")
                if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                // 长章节里每显示一次都要整章重排：流式的字最多每 [INLINE_REFRESH_MS] 毫秒更新一次显示。
                var shown = 0L
                val answer = askWithPack(
                    "在编辑器里写", workId, system, prompt, maxTokens = draftTokens?.coerceAtMost(8_192), stable = samples, meter = meter,
                    toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = true,
                    requireTools = taskSnapshot?.project == true,
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
                if (reference != null) {
                    ReferenceWriting.verifyProse(clean, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                update { it.copy(text = joinAt(head, clean), loading = false, note = note,
                    referenceValidated = reference != null, usage = meter.usage, cost = meter.cost) }
                log(workId, chapterId, "在编辑器里写", mode.label + instruction.let { if (it.isBlank()) "" else "：$it" }, clean, meter)
            } catch (e: CancellationException) {
                // 停下：已经写出来的（可能还没显示出来）留着。
                val shownText = joinAt(head, written)
                update { it.copy(text = shownText, loading = false, note = if (shownText.isEmpty()) null else "停下了", usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
                throw e
            } catch (e: RejectedReferenceWriting) {
                written = ""
                update { it.copy(text = "", loading = false, error = e.message, usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.message ?: "请求失败", usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
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
        writeInline(d.workId, d.chapterId, d.base, d.at, d.mode, d.instruction, d.referenceAnalysisId)
    }

    /**
     * 采纳：AI 写的插进正文，存一版（采纳前的正文先存进历史），AI 写的字不算码字数。
     * 返回插好以后的全文和光标位置；正文已经变了、插不进去时返回空。
     */
    fun acceptInline(current: String): Pair<String, Int>? {
        val d = _inline.value ?: return null
        if (d.loading || d.error != null || d.text.isBlank() || (d.referenceAnalysisId != null && !d.referenceValidated)) return null
        if (current != d.base) return null
        if (d.referenceAnalysisId != null) {
            try {
                ReferenceWriting.ensureSnapshot(store, d.workId, d.chapterId, d.referenceAnalysisId,
                    d.referenceFingerprint ?: return null, d.referenceChapterLength ?: return null)
            } catch (e: Exception) {
                _inline.value = d.copy(error = e.message ?: "范本来源已变化，请重新生成", referenceValidated = false)
                return null
            }
        }
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
        val answer = ask("整理人物", workId, WritePrompts.system(title, assist), WritePrompts.extract(text, names), whole = true)
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
        val answer = ask("整理检查规则", workId, WritePrompts.system(title, assistOf(workId)), WritePrompts.rules(docs), whole = true)
        return WritePrompts.cleanRules(answer)
    }

    private fun characterDocs(workId: String) = store.pack(workId).filter { it.kind == PackKind.CHARACTER }

    private fun characterNames(workId: String): List<String> =
        characterDocs(workId).flatMap { WritePrompts.characterNames(store.packText(workId, it.id)) }.distinct()

    /** 写这一章要带给 AI 的资料：本章资料（按章挑的设定）加细纲卡和上一章的钩子。[upTo] 为真时章目录只列到这一章（查阅工具也只能读到这一章）。 */
    private fun chapterContext(workId: String, chapterId: String, text: String, style: Boolean = false, upTo: Boolean = false): String? {
        val work = store.get(workId) ?: return null
        val project = store.writingTask(workId, chapterId)
        if (project?.project == true) return project.render()
        val chapters = work.chapters
        val i = chapters.indexOfFirst { it.id == chapterId }
        val prev = chapters.getOrNull(i - 1)
        val card = WritePrompts.card(store.plannedCard(workId, chapterId).card, prev?.title, prev?.let { store.plannedCard(workId, it.id).card })
        // 能查设定包的模型：设定包整份在前面，这里只给本章提示（细纲原文、察觉、伏笔、前情、章目录）；
        // 本机模型：按章挑选的资料。
        val brief = if (canUsePack) store.chapterHints(workId, chapterId, text, upTo) else store.brief(workId, chapterId, text, if (localModel) LOCAL_BRIEF_LIMIT else null, style)?.render()
        return listOfNotNull(brief, card).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { null }
    }

    // ---------- 三道审稿 ----------

    private val _lastRequest = MutableStateFlow<SentRequest?>(null)
    /** 最近一次发给 AI 的内容（系统提示和请求），作者可以看看到底发了什么。 */
    val lastRequest: StateFlow<SentRequest?> = _lastRequest.asStateFlow()

    /**
     * 发请求，并记下发了什么；用量按 [meter] 记（算在哪个功能、哪部作品上），发完（停下、出错也是）把用量写进“最近一次发给 AI 的内容”。
     */
    private suspend fun ask(
        label: String,
        workId: String,
        system: String,
        prompt: String,
        whole: Boolean = false,
        meter: AiMeter = AiMeter(featureOf(label), workId),
        onText: (String) -> Unit = {},
    ): String {
        val sent = SentRequest(label, system, prompt, System.currentTimeMillis())
        _lastRequest.value = sent
        try {
            return withContext(meter) { ai.ask(system, prompt, whole, onText) }
        } finally {
            noteUsage(sent.id, meter)
        }
    }

    /** 把这次的用量写进“最近一次发给 AI 的内容”（还是这一次的话）。 */
    private fun noteUsage(id: String, meter: AiMeter) {
        if (meter.usage.isEmpty) return
        _lastRequest.update { r -> if (r?.id == id) r.copy(usage = meter.usage, cost = meter.cost) else r }
    }

    /** 请求的名字对应的功能（按功能累计用量）。 */
    private fun featureOf(label: String): AiFeature = when (label) {
        "写作建议" -> AiFeature.SUGGEST
        "在编辑器里写" -> AiFeature.INLINE
        "写本章", "接着写" -> AiFeature.DRAFT
        "事实关", "写法关" -> AiFeature.REVIEW
        "整理本章" -> AiFeature.SYNC
        "拟细纲", "章节摘要" -> AiFeature.PLAN
        "整理人物", "整理检查规则" -> AiFeature.PACK
        else -> AiFeature.OTHER
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
        phase: WritingPhase? = null,
        confirmedOnly: Boolean = false,
        /** Project tasks must not silently continue without access to the confirmed source documents. */
        requireTools: Boolean = false,
        stable: String? = null,
        meter: AiMeter = AiMeter(featureOf(label), workId),
        onReset: () -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (!canUsePack) {
            if (requireTools) throw AiException("本次项目任务需要设定包查阅工具；本机模型无法完成，请选支持工具调用的模型")
            return ask(label, workId, rest, prompt, whole, meter, onText)
        }
        val budget = knowledgeBudget(workId)
        val stage = phase ?: when (label) {
            "写本章", "接着写", "在编辑器里写", "续写" -> WritingPhase.CREATE
            "事实关", "写法关", "矛盾检查", "读者点评", "写作建议" -> WritingPhase.REVIEW
            "整理本章" -> WritingPhase.SYNC
            "润色", "更生动", "扩写", "缩写", "按要求改", "挑错" -> WritingPhase.REVISE
            else -> WritingPhase.ASSIST
        }
        val (pack, tools) = withContext(Dispatchers.IO) {
            store.aiPack(workId, budget, stage) to store.packTools(workId, toolsUpTo, phase = stage, confirmedOnly = confirmedOnly)
        }
        val system = pack.prefix + "\n\n=== 这次的工作 ===\n" + rest.trim() + if (useTools) "\n\n" + WritePrompts.TOOL_GUIDE else ""
        val sent = SentRequest(label, system, prompt, System.currentTimeMillis())
        _lastRequest.value = sent
        try {
            // 工具多轮的几次请求都加在这一个计量器上。
            return withContext(meter) { ai.converse(
                system, prompt, if (useTools) PackTools.TOOLS else emptyList(),
                run = { call ->
                    val out = withContext(Dispatchers.IO) { tools.run(call) }
                    _lookup.value = tools.lookups.lastOrNull()
                    _lastRequest.update { it?.copy(lookups = tools.lookups.toList()) }
                    out
                },
                cachePrefix = pack.prefix.length,
                // 文风范例紧跟在知识库后面、本身也稳定：Anthropic 上在它后面再标一处缓存。
                cacheExtra = stable?.let { s -> system.indexOf(s).takeIf { it >= 0 }?.plus(s.length) } ?: 0,
                whole = whole,
                maxTokens = maxTokens,
                maxRounds = maxRounds,
                requireTools = requireTools,
                onLookup = { onReset() },
                onText = onText,
            ) }
        } finally {
            _lookup.value = null
            noteUsage(sent.id, meter)
        }
    }

    // ---------- 文风范例 ----------

    /**
     * 写 [chapterId] 时带的文风范例（[chapterId] 为空时按“写下一章”挑，设置页预览用）。本机模型只带三分之一。
     * 挑选结果按来源章（id、字数、修改时间、状态）和设置缓存在作品目录里：定稿章没变就不重读正文、不重挑。
     */
    suspend fun stylePick(workId: String, chapterId: String?, local: Boolean = localModel): SamplePick {
        val assist = assistOf(workId)
        val limit = if (local) StyleSamples.localLimit(assist.sampleLimit) else assist.sampleLimit
        if (limit <= 0) return SamplePick(emptyList())
        val options = assist.sampleOptions.copy(limit = limit)
        return withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: return@withContext SamplePick(emptyList())
            val chapters = work.chapters
            val before = chapterId?.let { id -> chapters.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            val (sources, fallback) = StyleSamples.sources(chapters, before, assist.sampleFallback)
            if (sources.isEmpty()) return@withContext SamplePick(emptyList())
            val sig = StyleSamples.signature(sources, fallback, options)
            val cache = StyleSamples.decodeCache(store.sampleCache(workId))
            cache.firstOrNull { it.first == sig }?.let { return@withContext it.second }
            val texts = sources.map { SampleChapter(it.id, it.title, store.text(workId, it.id)) }
            val pick = SamplePick(StyleSamples.pick(texts, options), fallback, sources.size)
            runCatching { store.saveSampleCache(workId, StyleSamples.encodeCache(cache.filter { it.first != sig } + (sig to pick))) }
            pick
        }
    }

    /** 拼进系统提示的文风范例；没有、挑的时候出错（读不出正文）都不带，不耽误请求。 */
    private suspend fun samplesFor(workId: String, chapterId: String): String? = try {
        StyleSamples.render(stylePick(workId, chapterId).samples)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** “换一段”：这一类往后挪一段。 */
    fun shiftSample(workId: String, kind: String) =
        updateAssist(workId) { a -> a.copy(sampleShift = a.sampleShift + (kind to (a.sampleShift[kind] ?: 0) + 1)) }

    /** “不用这段”：记住，以后不再挑它。 */
    fun skipSample(workId: String, key: String) =
        updateAssist(workId) { a -> a.copy(sampleSkip = (a.sampleSkip + key).distinct()) }

    /** 不用的段、换过的段都恢复。 */
    fun resetSamples(workId: String) = updateAssist(workId) { it.copy(sampleSkip = emptyList(), sampleShift = emptyMap()) }

    /** 这部作品现在给 AI 的设定包（看“本章资料”用）；本机模型不用这一套，返回空。 */
    suspend fun aiPack(workId: String): AiPack? {
        if (!canUsePack) return null
        val budget = knowledgeBudget(workId)
        return withContext(Dispatchers.IO) { store.aiPack(workId, budget) }
    }

    /** 整章 V4 思考与正文共用输出上限；局部续写仍维持较短上限。 */
    private val draftTokens: Int?
        get() = draftOutputTokenLimit(ai.currentConfig)

    /** 记一条写作 AI 的结果（灵感、点评、矛盾检查、写本章）。 */
    private suspend fun log(workId: String, chapterId: String, kind: String, ask: String, text: String, meter: AiMeter? = null) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val title = store.get(workId)?.chapter(chapterId)?.title.orEmpty()
                val usage = meter?.usage?.takeIf { !it.isEmpty }
                store.addAiLog(workId, WriteRecord(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), chapterId, title, kind, ask, text, usage = usage, cost = usage?.let { meter.cost }))
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
                                val (pack, previous, project) = withContext(Dispatchers.IO) {
                                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                                    val taskSnapshot = store.writingTask(workId, chapterId)
                                    Triple(chapterContext(workId, chapterId, text),
                                        if (taskSnapshot?.project == true) taskSnapshot.previous.takeLast(1_500)
                                        else work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(1_500) }.orEmpty(),
                                        taskSnapshot?.project == true)
                                }
                                val answer = askWithPack("事实关", workId, WritePrompts.system(work.title, assist, pack),
                                    WritePrompts.factReview(text, title, previous), whole = true, toolsUpTo = chapterId,
                                    phase = WritingPhase.REVIEW, confirmedOnly = true, requireTools = project)
                                reviewResult(gate, answer)
                            }
                        }
                        ReviewGate.CRAFT -> {
                            val rules = withContext(Dispatchers.IO) { styleRules(workId, chapterId, text) }
                            when {
                                !ready -> GateResult(gate, error = "没有设置 AI，跳过", at = System.currentTimeMillis())
                                rules.isBlank() -> GateResult(gate, error = "设定包里没有写作规范，跳过", at = System.currentTimeMillis())
                                else -> {
                                    val answer = ask("写法关", workId, WritePrompts.system(work.title, assist), WritePrompts.craftReview(text, title, rules), whole = true)
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
        return ask("章节摘要", workId, WritePrompts.system(work.title, assistOf(workId)), WritePrompts.summary(text, title), whole = true).trim()
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
        val meter = AiMeter(AiFeature.PLAN, workId)
        val answer = askWithPack("拟细纲", workId, system, prompt, whole = true, meter = meter)
        val card = WritePrompts.parsePlan(answer)
        if (card.isEmpty) throw IllegalStateException("AI 没按格式回，换个模型或再试一次")
        log(workId, chapterId, "拟细纲", "", answer.trim(), meter)
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
    private data class DraftBaseline(val run: Int, val state: DraftState)
    private val draftBaselines = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, DraftBaseline>()
    /** 各章当前这一次写的编号：被新的一次或“不要这版”顶掉的旧任务，不能再改状态、存草稿。 */
    private val draftRuns = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Int>()
    private var draftSeq = 0
    private val draftDisk = Dispatchers.IO.limitedParallelism(1)

    private fun rememberDraftBaseline(key: Pair<String, String>, run: Int, before: DraftState?) {
        val original = if (before?.loading == true) draftBaselines[key]?.state ?: before else before
        if (original?.text?.isNotBlank() == true) draftBaselines[key] = DraftBaseline(run, original)
        else draftBaselines.remove(key)
    }

    private fun draftBaseline(key: Pair<String, String>, run: Int): DraftState? =
        draftBaselines[key]?.takeIf { it.run == run }?.state

    private fun forgetDraftBaseline(key: Pair<String, String>, run: Int) {
        draftBaselines.computeIfPresent(key) { _, old -> if (old.run == run) null else old }
    }

    /** All candidate writes share one queue. A superseded stop/clear cannot overwrite a newer run. */
    private suspend fun persistDraft(key: Pair<String, String>, run: Int, text: String, workflow: Boolean) = withContext(draftDisk) {
        if (draftRuns[key] != run) return@withContext
        synchronized(store) {
            if (draftRuns[key] == run) {
                val candidate = _drafts.value[key]
                val reference = candidate?.let { state -> state.referenceAnalysisId?.let { id ->
                    DraftReferenceProvenance(id, state.referenceFingerprint, state.referenceChapterLength,
                        state.referenceValidated && state.text == text)
                }
                }
                // Fail closed if the second write fails: retain the requirement to review a project candidate.
                if (workflow && text.isNotBlank()) store.setDraftWorkflow(key.first, key.second, true)
                store.saveDraft(key.first, key.second, text, reference, candidate?.referenceProvenanceBlocked == true,
                    candidate?.let { state -> state.initialText?.let { DraftRevisionSnapshot(it, state.alternateText) } })
                if (!workflow || text.isBlank()) store.setDraftWorkflow(key.first, key.second, false)
            }
        }
    }

    /** 改这一章的草稿状态（[run] 不是这一章当前的那一次就不改）。 */
    private fun updateDraft(key: Pair<String, String>, run: Int, transform: (DraftState) -> DraftState) {
        if (draftRuns[key] != run) return
        _drafts.update { m -> m[key]?.let { m + (key to transform(it)) } ?: m }
    }

    /** A metadata write can fail after the candidate transaction committed; show actual disk bytes. */
    private suspend fun reconcileDraftAfterSaveFailure(key: Pair<String, String>, run: Int,
                                                       before: DraftState, label: String, failure: Exception) {
        val disk = withContext(NonCancellable + draftDisk) { runCatching { store.draftContent(key.first, key.second) }.getOrNull() }
        if (disk == null) {
            updateDraft(key, run) { before.copy(error = "草稿保存后无法确认磁盘内容，已暂停采纳", referenceProvenanceBlocked = true) }
            message("$label：${failure.message ?: "写入出错"}；磁盘内容无法确认，已暂停采纳")
            return
        }
        updateDraft(key, run) { before.copy(text = disk.text, loading = false, assessment = null, stage = DraftStage.IDLE,
            initialText = disk.revision?.initialText, alternateText = disk.revision?.alternateText,
            revisionWarning = disk.revisionError, referenceAnalysisId = disk.reference?.analysisId,
            referenceFingerprint = disk.reference?.sourceFingerprint,
            referenceChapterLength = disk.reference?.chapterLength, referenceValidated = disk.reference?.verified == true,
            referenceProvenanceBlocked = disk.blocked,
            error = disk.provenanceError ?: "$label：${failure.message ?: "写入出错"}") }
        message("$label：${failure.message ?: "写入出错"}；已按磁盘实际内容刷新")
    }

    /** Keep the prior candidate's source paired with its bytes until replacement prose exists. */
    private fun DraftState.restoreDraftSource(before: DraftState?): DraftState = copy(
        referenceAnalysisId = before?.referenceAnalysisId,
        referenceFingerprint = before?.referenceFingerprint,
        referenceChapterLength = before?.referenceChapterLength,
        referenceValidated = before?.referenceValidated == true,
        referenceProvenanceBlocked = before?.referenceProvenanceBlocked == true,
        initialText = before?.initialText,
        alternateText = before?.alternateText,
    )

    /** 打开草稿页：读上次没放进正文的草稿（这一章已经有状态，比如正在写，就不动）。 */
    fun loadDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        if (key in _drafts.value) return
        scope.launch {
            val (saved, required, intent) = withContext(draftDisk) {
                val saved = runCatching { store.draftContent(workId, chapterId) }
                    .getOrElse { DraftContent("", provenanceError = "草稿读取失败：${it.message ?: "来源不明"}") }
                val assist = store.assist(workId)
                val required = saved.text.isNotBlank() && (chapterId in assist.draftWorkflows || store.writingTask(workId, chapterId)?.project == true)
                Triple(saved, required, assist.draftIntents[chapterId])
            }
            _drafts.update { m -> if (key in m) m else m + (key to DraftState(workId, chapterId, saved.text,
                workflow = required, instruction = intent?.instruction.orEmpty(), targetLength = intent?.targetLength.orEmpty(),
                workflowChoice = intent?.workflowChoice,
                error = saved.provenanceError,
                revisionWarning = saved.revisionError,
                initialText = saved.revision?.initialText,
                alternateText = saved.revision?.alternateText,
                referenceAnalysisId = saved.reference?.analysisId,
                referenceFingerprint = saved.reference?.sourceFingerprint,
                referenceChapterLength = saved.reference?.chapterLength,
                referenceValidated = saved.reference?.verified == true,
                referenceProvenanceBlocked = saved.blocked)) }
        }
    }

    /** Save author choices even when no candidate has been generated yet. */
    fun saveDraftIntent(workId: String, chapterId: String, instruction: String, targetLength: String, workflowChoice: Boolean?) {
        val key = workId to chapterId
        _drafts.update { drafts -> drafts[key]?.let { drafts + (key to it.copy(
            instruction = instruction, targetLength = targetLength, workflowChoice = workflowChoice)) } ?: drafts }
        scope.launch {
            try {
                withContext(draftDisk) { store.setDraftIntent(workId, chapterId, instruction, targetLength, workflowChoice) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("写作要求保存失败：${e.message ?: "写入出错"}")
            }
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
            else -> "依本章细纲；没有明确数字时，以完整自然的章节为准"
        }
    }

    /**
     * AI 写本章：[more] 为真时接着已有的草稿往下写，否则从头写一版（替换草稿）。边写边显示，写完存成草稿，
     * 作者看过再放进正文。
     */
    fun writeDraft(workId: String, chapterId: String, instruction: String = "", more: Boolean = false, workflow: Boolean = false, targetLength: String = "", referenceAnalysisId: String? = null) {
        if (workflow) {
            writeProjectDraft(workId, chapterId, instruction, targetLength, more = more, referenceAnalysisId = referenceAnalysisId)
            return
        }
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val key = workId to chapterId
        draftJobs[key]?.cancel()
        val run = ++draftSeq
        draftRuns[key] = run
        val before = _drafts.value[key]?.let { visible ->
            if (visible.loading) draftBaselines[key]?.state ?: visible else visible
        }
        rememberDraftBaseline(key, run, before)
        val previousCandidate = before?.text.orEmpty()
        val start = previousCandidate.takeIf { more }.orEmpty()
        val prefix = if (start.isEmpty()) "" else start.trimEnd() + "\n"
        // Keep the saved candidate visible until this request actually produces replacement text.
        _drafts.update { it + (key to (before ?: DraftState(workId, chapterId, previousCandidate)).copy(
            loading = true, error = null, instruction = instruction, targetLength = targetLength,
            workflowChoice = workflow)) }
        var written = ""
        var newCandidateGenerated = false
        val meter = AiMeter(AiFeature.DRAFT, workId)
        draftJobs[key] = scope.launch {
            try {
                withContext(draftDisk) {
                    if (draftRuns[key] == run) store.setDraftIntent(workId, chapterId, instruction, targetLength, workflow)
                }
                if (more && before?.referenceProvenanceBlocked == true)
                    throw RejectedReferenceWriting(before.error ?: "旧草稿来源无法核验，请从头重新生成")
                if (more && previousCandidate.isNotBlank() && before?.referenceAnalysisId != referenceAnalysisId)
                    throw RejectedReferenceWriting("续写须沿用原候选稿的范本选择；切换范本请从头重写")
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val reference = referenceAnalysisId?.let { withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, it) } }
                if (more && reference != null && before?.referenceAnalysisId == referenceAnalysisId) {
                    val previous = requireNotNull(before)
                    if (!previous.referenceValidated || reference.excerpt.fingerprint != previous.referenceFingerprint ||
                        reference.excerpt.chapterLength != previous.referenceChapterLength)
                        throw RejectedReferenceWriting("原候选稿的范本来源未核验或已变化，请从头重写")
                    ReferenceWriting.verifyProse(previous.text, reference)
                }
                val taskSnapshot = writingTask(workId, chapterId, instruction, targetLength)
                if (taskSnapshot?.project == true) check(taskSnapshot.blocking.isEmpty()) { taskSnapshot.blocking.joinToString("；") }
                val (pack, previous, rules, length) = withContext(Dispatchers.IO) {
                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                    listOf(
                        chapterContext(workId, chapterId, store.text(workId, chapterId)).orEmpty(),
                        // 上一章结尾：能查设定包的模型多给些（要更多它自己读），本机模型少给。
                        if (taskSnapshot?.project == true) taskSnapshot.previous.takeLast(if (canUsePack) 6_000 else 2_000) else work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(if (canUsePack) 6_000 else 2_000) }.orEmpty(),
                        writingRules(workId, chapterId, store.text(workId, chapterId)),
                        taskSnapshot?.targetLength ?: targetLength.ifBlank { lengthOf(workId) },
                    )
                }
                val prompt = (if (prefix.isEmpty()) WritePrompts.draft(chapter.title, previous, rules, length, instruction)
                else WritePrompts.draftMore(chapter.title, prefix, rules, length)) +
                    (reference?.let { "\n\n" + ReferenceWriting.prompt(it) } ?: "")
                val samples = samplesFor(workId, chapterId)
                val system = WritePrompts.system(work.title, assistOf(workId), pack.ifEmpty { null }, examples = true, samples = samples) +
                    (if (reference == null) "" else "\n\n" + ReferenceWriting.SYSTEM_RULE)
                if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                val answer = askWithPack(
                    if (prefix.isEmpty()) "写本章" else "接着写", workId, system, prompt, whole = true, maxTokens = draftTokens, stable = samples, meter = meter,
                    toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = taskSnapshot?.project == true,
                    requireTools = taskSnapshot?.project == true,
                    onReset = {
                        written = ""
                        newCandidateGenerated = false
                        updateDraft(key, run) { it.copy(text = previousCandidate,
                            workflow = before?.workflow == true).restoreDraftSource(before) }
                    },
                ) { piece ->
                    written += piece
                    val partial = WritePrompts.cleanDraft(written)
                    newCandidateGenerated = partial.isNotBlank()
                    updateDraft(key, run) {
                        if (!newCandidateGenerated) it.copy(text = previousCandidate,
                            workflow = before?.workflow == true).restoreDraftSource(before)
                        else it.copy(text = prefix + partial, workflow = false, referenceAnalysisId = referenceAnalysisId,
                            referenceFingerprint = reference?.excerpt?.fingerprint,
                            referenceChapterLength = reference?.excerpt?.chapterLength,
                            referenceValidated = false, referenceProvenanceBlocked = false,
                            initialText = null, alternateText = null, revisionWarning = null)
                    }
                }
                val cleaned = WritePrompts.cleanDraft(answer)
                check(cleaned.isNotBlank()) { "AI 没有返回正文" }
                newCandidateGenerated = true
                val text = prefix + cleaned
                if (reference != null) {
                    ReferenceWriting.verifyProse(text, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                updateDraft(key, run) { it.copy(text = text, loading = false, referenceAnalysisId = referenceAnalysisId,
                    workflow = false,
                    referenceFingerprint = reference?.excerpt?.fingerprint,
                    referenceChapterLength = reference?.excerpt?.chapterLength,
                    referenceValidated = reference != null, referenceProvenanceBlocked = false,
                    initialText = null, alternateText = null, revisionWarning = null,
                    usage = meter.usage, cost = meter.cost) }
                persistDraft(key, run, text, workflow = false)
                forgetDraftBaseline(key, run)
                log(workId, chapterId, if (prefix.isEmpty()) "写本章" else "接着写", instruction, text.removePrefix(prefix), meter)
            } catch (e: CancellationException) {
                // An incomplete replacement never displaces a previously complete candidate.
                if (draftRuns[key] == run) {
                    val cleaned = WritePrompts.cleanDraft(written)
                    val partial = if (cleaned.isNotBlank()) prefix + cleaned else previousCandidate
                    updateDraft(key, run) { state ->
                        val old = draftBaseline(key, run)
                        if (old != null) old.copy(loading = false, assessment = null)
                        else (if (cleaned.isNotBlank()) state.copy(text = partial, workflow = false)
                            else state.copy(text = partial, workflow = before?.workflow == true).restoreDraftSource(before))
                            .copy(loading = false)
                    }
                    val kept = _drafts.value[key]
                    if (kept?.text?.isNotBlank() == true) withContext(NonCancellable) {
                        runCatching { persistDraft(key, run, kept.text, workflow = kept.workflow) }
                    }
                }
                throw e
            } catch (e: RejectedReferenceWriting) {
                updateDraft(key, run) { (draftBaseline(key, run) ?: before ?: it).copy(
                    text = previousCandidate, loading = false, error = e.message,
                    usage = meter.usage.takeIf { usage -> !usage.isEmpty }, cost = meter.cost) }
                try { persistDraft(key, run, previousCandidate, workflow = before?.workflow == true) }
                catch (saveFailure: Exception) {
                    reconcileDraftAfterSaveFailure(key, run, before ?: DraftState(workId, chapterId, ""),
                        "旧候选稿保存失败", saveFailure)
                }
            } catch (e: Exception) {
                updateDraft(key, run) { state ->
                    val old = draftBaseline(key, run)
                    if (old != null) old.copy(loading = false, error = e.message ?: "请求失败")
                    else state.copy(text = if (newCandidateGenerated) state.text else previousCandidate,
                        workflow = false, loading = false, error = e.message ?: "请求失败",
                        initialText = null, alternateText = null)
                }
                // With no earlier candidate, a partial remains available for continuation.
                _drafts.value[key]?.takeIf { draftRuns[key] == run && it.text.isNotBlank() }?.let { d ->
                    try { persistDraft(key, run, d.text, workflow = d.workflow) }
                    catch (saveFailure: Exception) { reconcileDraftAfterSaveFailure(key, run, before ?: d,
                        "候选稿保存失败", saveFailure) }
                }
            } finally {
                forgetDraftBaseline(key, run)
            }
        }
    }

    /** Build the same explicit task that the next workflow request will use. Never calls an AI. */
    suspend fun writingTask(workId: String, chapterId: String, instruction: String = "", targetLength: String = ""): ChapterWritingTask? =
        withContext(Dispatchers.IO) { store.writingTask(workId, chapterId, instruction, targetLength) }

    /** Estimate the actual first chapter-draft request locally; no model request is sent. */
    suspend fun previewDraftRequest(
        workId: String,
        chapterId: String,
        instruction: String,
        targetLength: String,
        workflow: Boolean,
        referenceAnalysisId: String? = null,
    ): AiRequestBudgetPreview? {
        if (!ready || localModel) return null
        val task = writingTask(workId, chapterId, instruction, targetLength) ?: return null
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: return null
        val chapter = work.chapter(chapterId) ?: return null
        val assist = assistOf(workId)
        val samples = samplesFor(workId, chapterId)
        val reference = referenceAnalysisId?.let { id ->
            withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, id) }
        }
        val (context, prompt) = if (workflow) {
            val rules = withContext(Dispatchers.IO) { writingRules(workId, chapterId, "") }
            task.render() to WritePrompts.projectDraft(task, rules)
        } else withContext(Dispatchers.IO) {
            val body = store.text(workId, chapterId)
            val index = work.chapters.indexOfFirst { it.id == chapterId }
            val previous = if (task.project) task.previous.takeLast(6_000)
                else work.chapters.getOrNull(index - 1)?.let { store.text(workId, it.id).takeLast(6_000) }.orEmpty()
            val rules = writingRules(workId, chapterId, body)
            val context = chapterContext(workId, chapterId, body).orEmpty()
            context to WritePrompts.draft(chapter.title, previous, rules, task.targetLength, instruction)
        }
        val rest = WritePrompts.system(work.title, assist, context.ifEmpty { null }, examples = true, samples = samples) +
            (if (reference == null) "" else "\n\n" + ReferenceWriting.SYSTEM_RULE)
        val fullPrompt = prompt + (reference?.let { "\n\n" + ReferenceWriting.prompt(it) } ?: "")
        val budget = knowledgeBudget(workId)
        val pack = withContext(Dispatchers.IO) { store.aiPack(workId, budget, WritingPhase.CREATE) }
        val system = pack.prefix + "\n\n=== 这次的工作 ===\n" + rest.trim() + "\n\n" + WritePrompts.TOOL_GUIDE
        val cacheExtra = samples?.let { sample -> system.indexOf(sample).takeIf { it >= 0 }?.plus(sample.length) } ?: 0
        return withContext(Dispatchers.Default) {
            AiRequestBudget.preview(ai.currentConfig, system, fullPrompt, PackTools.TOOLS, draftTokens, workflow,
                cachePrefix = pack.prefix.length, cacheExtra = cacheExtra)
        }
    }

    /** A restored or interrupted candidate has no valid pass; run the actual review on it again. */
    fun reviewDraft(workId: String, chapterId: String, instruction: String = "", targetLength: String = "") =
        writeProjectDraft(workId, chapterId, instruction, targetLength, reviewOnly = true,
            referenceAnalysisId = _drafts.value[workId to chapterId]?.referenceAnalysisId)

    /** An author edit creates a new candidate version and invalidates every earlier assessment. */
    fun updateDraftText(workId: String, chapterId: String, text: String) {
        if (text.isBlank()) { message("候选稿不能为空"); return }
        val key = workId to chapterId
        val d = _drafts.value[key] ?: return
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to d.copy(text = text, loading = false, assessment = null, stage = DraftStage.IDLE,
            error = if (d.referenceProvenanceBlocked) d.error else null, referenceValidated = false)) }
        scope.launch {
            try { persistDraft(key, run, text, d.workflow) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reconcileDraftAfterSaveFailure(key, run, d, "候选稿保存失败", e) }
        }
    }

    /** Swap to the saved first draft while keeping the replaced revision available to switch back. */
    fun restoreInitialDraft(workId: String, chapterId: String) = switchDraftVersion(workId, chapterId, first = true)

    fun switchBackToRevisedDraft(workId: String, chapterId: String) = switchDraftVersion(workId, chapterId, first = false)

    private fun switchDraftVersion(workId: String, chapterId: String, first: Boolean) {
        val key = workId to chapterId
        val before = _drafts.value[key] ?: return
        if (before.loading) return
        // Once the restored first draft has been edited, do not discard those edits by switching.
        val target = if (first && before.alternateText == null) before.initialText
            else if (!first && before.text == before.initialText) before.alternateText else null
        if (target.isNullOrBlank() || target == before.text) return
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        val next = before.copy(text = target, loading = false, assessment = null, stage = DraftStage.IDLE,
            error = if (before.referenceProvenanceBlocked) before.error else null,
            referenceValidated = false,
            alternateText = if (first) before.text else null)
        _drafts.update { it + (key to next) }
        scope.launch {
            try { withContext(NonCancellable) { persistDraft(key, run, target, next.workflow) } }
            catch (e: Exception) { reconcileDraftAfterSaveFailure(key, run, before, "切换草稿版本失败", e) }
        }
    }

    /** Validate the manuscript and material versions immediately before the author adopts a candidate. */
    suspend fun draftBlocked(workId: String, chapterId: String, instruction: String? = null, targetLength: String? = null): String? {
        val d = _drafts.value[workId to chapterId] ?: return "没有可采纳的草稿"
        if (d.loading) return "本章流程还在进行，请先等它完成或停止"
        if (d.revisionWarning?.contains("无法安全留底") == true) return d.revisionWarning
        if (d.referenceProvenanceBlocked) return d.error ?: "草稿来源无法核验，请重新生成"
        if (d.error != null) return d.error
        if (d.text.isBlank()) return "没有可采纳的草稿"
        d.referenceAnalysisId?.let { id ->
            try {
                val source = withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, id) }
                if (source.excerpt.fingerprint != d.referenceFingerprint || source.excerpt.chapterLength != d.referenceChapterLength)
                    return "范本在写作期间变化，请重新生成候选稿"
                withContext(Dispatchers.Default) { ReferenceWriting.verifyProse(d.text, source) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { return e.message ?: "范本来源已变化，请重新选择后写一版" }
        }
        if (!d.workflow) return null
        val task = writingTask(workId, chapterId, instruction ?: d.instruction, targetLength ?: d.targetLength) ?: return "作品或章节不在了"
        if (task.blocking.isNotEmpty()) return task.blocking.joinToString("；")
        val report = d.assessment ?: return "本稿尚未完成审读，请先审读此稿"
        if (!report.matches(d.text, task.fingerprint)) return "正文或项目资料已经变化，旧审读结果已过时，请重新审读"
        return if (report.passed && d.stage == DraftStage.READY) null else "本稿仍有必改问题或资料缺项，请核对后重新审读"
    }

    private fun writeProjectDraft(workId: String, chapterId: String, instruction: String, targetLength: String, more: Boolean = false, reviewOnly: Boolean = false, referenceAnalysisId: String? = null) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val key = workId to chapterId
        draftJobs[key]?.cancel()
        val run = ++draftSeq
        draftRuns[key] = run
        val before = _drafts.value[key]?.let { visible ->
            if (visible.loading) draftBaselines[key]?.state ?: visible else visible
        }
        rememberDraftBaseline(key, run, before)
        val previousCandidate = before?.text.orEmpty()
        _drafts.update { it + (key to (before ?: DraftState(workId, chapterId, "")).copy(
            text = previousCandidate,
            loading = true, error = null, workflow = before?.workflow == true, stage = DraftStage.PREPARING,
            assessment = null, instruction = instruction, targetLength = targetLength, workflowChoice = true,
            // The old bytes retain their old source until new prose actually arrives.
            referenceAnalysisId = before?.referenceAnalysisId,
            referenceFingerprint = before?.referenceFingerprint,
            referenceChapterLength = before?.referenceChapterLength,
            referenceValidated = before?.referenceValidated == true,
            referenceProvenanceBlocked = before?.referenceProvenanceBlocked == true,
        )) }
        val createMeter = AiMeter(AiFeature.DRAFT, workId)
        val reviewMeter = AiMeter(AiFeature.REVIEW, workId)
        val reviseMeter = AiMeter(AiFeature.EDIT, workId)
        val meters = listOf(createMeter, reviewMeter, reviseMeter)
        var newCandidateGenerated = false
        var freshCandidateCommitted = false
        var firstCandidateSaved = false
        draftJobs[key] = scope.launch {
            try {
                withContext(draftDisk) {
                    if (draftRuns[key] == run) store.setDraftIntent(workId, chapterId, instruction, targetLength, true)
                }
                if ((reviewOnly || more) && before?.referenceProvenanceBlocked == true)
                    throw RejectedReferenceWriting(before.error ?: "旧草稿来源无法核验，请从头重新生成")
                if ((reviewOnly || more) && previousCandidate.isNotBlank() && before?.referenceAnalysisId != referenceAnalysisId)
                    throw RejectedReferenceWriting("本稿须沿用原候选稿的范本选择；切换范本请从头重写")
                val task = writingTask(workId, chapterId, instruction, targetLength) ?: error("作品或章节不在了")
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: error("作品不在了")
                check(work.chapter(chapterId)?.locked != true) { "这一章已定稿：要改先解锁并登记原因" }
                val reviewEvidence = withContext(Dispatchers.IO) { store.reviewEvidence(workId, chapterId, task) }
                updateDraft(key, run) { it.copy(task = task) }
                check(task.blocking.isEmpty()) { task.blocking.joinToString("；") }
                val reference = referenceAnalysisId?.let { withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, it) } }
                if ((more || reviewOnly) && reference != null && before?.referenceAnalysisId == referenceAnalysisId && previousCandidate.isNotBlank()) {
                    val previous = requireNotNull(before)
                    if (!previous.referenceValidated || reference.excerpt.fingerprint != previous.referenceFingerprint ||
                        reference.excerpt.chapterLength != previous.referenceChapterLength)
                        throw RejectedReferenceWriting("原候选稿的范本来源未核验或已变化，请从头重写")
                    ReferenceWriting.verifyProse(previous.text, reference)
                }
                val rules = withContext(Dispatchers.IO) {
                    store.pack(workId).filter { it.kind == PackKind.RULES }.fold(RuleSet()) { all, d -> all + CheckRules.parse(store.packText(workId, d.id), d.title) }
                }
                val proseRules = withContext(Dispatchers.IO) { writingRules(workId, chapterId, "") }
                val assist = assistOf(workId)
                val samples = samplesFor(workId, chapterId)
                val system = WritePrompts.system(work.title, assist, task.render(), examples = true, samples = samples) +
                    (if (reference == null) "" else "\n\n" + ReferenceWriting.SYSTEM_RULE)
                val reviewer = WritePrompts.system(work.title, assist, task.render())
                val saved = before?.text?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) {
                    val disk = store.draftContent(workId, chapterId)
                    if ((reviewOnly || more) && disk.text.isNotBlank())
                        throw RejectedReferenceWriting("请先打开草稿，核对范本来源后再继续写或审读")
                    disk.text
                }
                check(!reviewOnly || saved.isNotBlank()) { "还没有可审读的草稿" }
                val prefix = if (more && saved.isNotBlank()) saved.trimEnd() + "\n" else ""
                var written = ""
                val result = DraftWorkflow.run(
                    task = task, rules = rules, evidence = reviewEvidence, initial = saved.takeIf { reviewOnly },
                    create = {
                        val prompt = (if (prefix.isBlank()) WritePrompts.projectDraft(task, proseRules)
                        else WritePrompts.draftMore(task.title, prefix, proseRules, task.targetLength)) +
                            (reference?.let { "\n\n" + ReferenceWriting.prompt(it) } ?: "")
                        if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        val answer = askWithPack(
                            "写本章", workId, system, prompt, whole = true, maxTokens = draftTokens,
                            toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = true, requireTools = true,
                            stable = samples, meter = createMeter,
                            onReset = {
                                written = ""
                                newCandidateGenerated = false
                                updateDraft(key, run) { it.copy(text = previousCandidate,
                                    workflow = before?.workflow == true).restoreDraftSource(before) }
                            },
                        ) { piece ->
                            written += piece
                            val partial = WritePrompts.cleanDraft(written)
                            newCandidateGenerated = partial.isNotBlank()
                            updateDraft(key, run) {
                                if (!newCandidateGenerated) it.copy(text = previousCandidate,
                                    workflow = before?.workflow == true).restoreDraftSource(before)
                                else it.copy(text = prefix + partial, workflow = true, referenceAnalysisId = referenceAnalysisId,
                                    referenceFingerprint = reference?.excerpt?.fingerprint,
                                    referenceChapterLength = reference?.excerpt?.chapterLength,
                                    referenceValidated = false, referenceProvenanceBlocked = false,
                                    initialText = null, alternateText = null, revisionWarning = null)
                            }
                        }
                        val cleaned = WritePrompts.cleanDraft(answer)
                        check(cleaned.isNotBlank()) { "AI 没有返回正文" }
                        newCandidateGenerated = true
                        (prefix + cleaned).also { candidate ->
                            if (reference != null) {
                                ReferenceWriting.verifyProse(candidate, reference)
                                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                                updateDraft(key, run) { it.copy(referenceValidated = true) }
                            }
                        }
                    },
                    review = { manuscript ->
                        if (reference != null) {
                            ReferenceWriting.verifyProse(manuscript, reference)
                            withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        }
                        askWithPack(
                            "本稿审读", workId, reviewer, WritePrompts.projectReview(task, manuscript, proseRules, reviewEvidence),
                            whole = true, toolsUpTo = chapterId, phase = WritingPhase.REVIEW, confirmedOnly = true,
                            requireTools = true, meter = reviewMeter,
                        )
                    },
                    revise = { manuscript, findings ->
                        if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        WritePrompts.cleanDraft(askWithPack(
                            "本稿修订", workId, system, WritePrompts.projectRevision(task, manuscript, findings, proseRules) +
                                (reference?.let { "\n\n" + ReferenceWriting.prompt(it) } ?: ""),
                            whole = true, maxTokens = draftTokens, toolsUpTo = chapterId, phase = WritingPhase.REVISE,
                            confirmedOnly = true, requireTools = true, stable = samples, meter = reviseMeter,
                        )).also { candidate ->
                            if (candidate.isNotBlank()) newCandidateGenerated = true
                            if (reference != null) {
                                ReferenceWriting.verifyProse(candidate, reference)
                                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                                updateDraft(key, run) { it.copy(referenceValidated = true) }
                            }
                        }
                    },
                    onStage = { stage, manuscript, assessment ->
                        if (manuscript.isNotBlank() && manuscript != previousCandidate) newCandidateGenerated = true
                        val firstCreatedCandidate = stage == DraftStage.CHECKING && !reviewOnly && !freshCandidateCommitted
                        if (firstCreatedCandidate) freshCandidateCommitted = true
                        updateDraft(key, run) { state ->
                            val next = if (manuscript.isBlank()) state.copy(stage = stage, assessment = assessment)
                            else state.copy(stage = stage, text = manuscript, assessment = assessment,
                                workflow = newCandidateGenerated || reviewOnly || before?.workflow == true,
                                referenceAnalysisId = if (newCandidateGenerated) referenceAnalysisId else state.referenceAnalysisId,
                                referenceFingerprint = if (newCandidateGenerated) reference?.excerpt?.fingerprint else state.referenceFingerprint,
                                referenceChapterLength = if (newCandidateGenerated) reference?.excerpt?.chapterLength else state.referenceChapterLength,
                                referenceProvenanceBlocked = if (newCandidateGenerated) false else state.referenceProvenanceBlocked)
                            when {
                                firstCreatedCandidate -> next.copy(initialText = null, alternateText = null, revisionWarning = null)
                                stage == DraftStage.REVISING && manuscript.isNotBlank() && next.initialText == null ->
                                    next.copy(initialText = manuscript, alternateText = null)
                                else -> next
                            }
                        }
                        // REVISING is persisted before the revision API request begins. Failure stops revision.
                        if (manuscript.isNotBlank()) try {
                            persistDraft(key, run, manuscript,
                                workflow = newCandidateGenerated || reviewOnly || before?.workflow == true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (saveFailure: Exception) {
                            // Do not publish a stopped state that claims an uncommitted first draft exists.
                            reconcileDraftAfterSaveFailure(key, run, before ?: DraftState(workId, chapterId, ""),
                                "候选稿版本保存失败", saveFailure)
                            throw saveFailure
                        }
                        if (firstCreatedCandidate) {
                            firstCandidateSaved = true
                            forgetDraftBaseline(key, run)
                        }
                    },
                )
                if (reference != null) {
                    ReferenceWriting.verifyProse(result.text, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                val current = writingTask(workId, chapterId, instruction, targetLength)
                val stale = current?.fingerprint != task.fingerprint
                updateDraft(key, run) { it.copy(
                    text = result.text, loading = false, assessment = result.assessment, workflow = true,
                    referenceValidated = reference != null,
                    stage = if (stale) DraftStage.NEEDS_REVIEW else result.stage,
                    error = if (stale) "项目资料在写作期间变化，旧审读结果已过时，请重新审读" else null,
                    usage = meters.fold(AiUsage()) { all, m -> all + m.usage }, cost = meters.fold(AiCost()) { all, m -> all + m.cost },
                ) }
                val totalMeter = AiMeter(AiFeature.DRAFT, workId)
                totalMeter.add(meters.fold(AiUsage()) { all, m -> all + m.usage }, meters.fold(AiCost()) { all, m -> all + m.cost })
                log(workId, chapterId, if (reviewOnly) "本稿审读" else "项目包写作", instruction, result.text, totalMeter)
            } catch (e: CancellationException) {
                if (draftRuns[key] == run) {
                    updateDraft(key, run) { state ->
                        (draftBaseline(key, run) ?: if (newCandidateGenerated) state else state.copy(
                            text = previousCandidate, workflow = before?.workflow == true).restoreDraftSource(before))
                            .copy(loading = false, stage = DraftStage.STOPPED, assessment = null)
                    }
                    val candidate = _drafts.value[key]?.text.orEmpty()
                    if (candidate.isNotBlank()) withContext(NonCancellable) {
                        runCatching { persistDraft(key, run, candidate, workflow = _drafts.value[key]?.workflow == true) }
                    }
                }
                throw e
            } catch (e: RejectedReferenceWriting) {
                updateDraft(key, run) { state ->
                    val restored = draftBaseline(key, run) ?: if (firstCandidateSaved) state
                        else before ?: DraftState(workId, chapterId, "")
                    restored.copy(loading = false, stage = DraftStage.ERROR, assessment = null, error = e.message,
                        usage = meters.fold(AiUsage()) { all, m -> all + m.usage },
                        cost = meters.fold(AiCost()) { all, m -> all + m.cost })
                }
                val kept = _drafts.value[key]
                if (kept != null) persistDraft(key, run, kept.text, workflow = kept.workflow)
            } catch (e: Exception) {
                updateDraft(key, run) { state ->
                    (draftBaseline(key, run) ?: if (newCandidateGenerated) state else state.copy(
                        text = previousCandidate, workflow = before?.workflow == true).restoreDraftSource(before)).copy(
                    loading = false, stage = DraftStage.ERROR, assessment = null,
                    error = state.error?.takeIf { it.contains("保存失败") } ?: e.message ?: "本章流程未完成",
                    usage = meters.fold(AiUsage()) { all, m -> all + m.usage }, cost = meters.fold(AiCost()) { all, m -> all + m.cost },
                ) }
                val candidate = _drafts.value[key]?.takeIf { draftRuns[key] == run }?.text.orEmpty()
                if (candidate.isNotBlank()) try {
                    persistDraft(key, run, candidate, workflow = _drafts.value[key]?.workflow == true)
                } catch (saveFailure: Exception) {
                    reconcileDraftAfterSaveFailure(key, run, before ?: DraftState(workId, chapterId, ""),
                        "候选稿版本保存失败", saveFailure)
                }
            } finally {
                forgetDraftBaseline(key, run)
            }
        }
    }

    /** 停下这一章正在写的：停在作者看到的这一刻，写了的留着，存成草稿。 */
    fun stopDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        val previous = draftRuns[key]?.let { draftBaseline(key, it) }
        // 换个编号：取消以后还在路上的字、取消时的处理都不再改它。
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        val d = _drafts.value[key] ?: return
        val kept = (previous ?: d).copy(loading = false,
            stage = if ((previous ?: d).workflow) DraftStage.STOPPED else (previous ?: d).stage, assessment = null)
        _drafts.update { it + (key to kept) }
        if (kept.text.isNotBlank()) scope.launch {
            try { withContext(NonCancellable) { persistDraft(key, run, kept.text, kept.workflow) } }
            catch (e: Exception) { message("停止写作后保存草稿失败：${e.message ?: "写入出错"}") }
        }
    }

    /** 草稿放进正文以后（或作者不要了）：清掉。 */
    fun clearDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        val before = draftRuns[key]?.let { draftBaseline(key, it) }
            ?: _drafts.value[key] ?: DraftState(workId, chapterId, "")
        // 换个编号：正在写的那次被取消时不再存草稿。
        val run = ++draftSeq
        draftRuns[key] = run
        draftBaselines.remove(key)
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to DraftState(workId, chapterId, "")) }
        scope.launch {
            try { withContext(NonCancellable) { persistDraft(key, run, "", workflow = false) } }
            catch (e: Exception) { reconcileDraftAfterSaveFailure(key, run, before, "删除草稿失败", e) }
        }
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

    /**
     * 整理一章：读这一章（[text] 是编辑框里现在的正文），带上设定包，一次请求，最多查两轮。
     * 太长的章（超过 [WritePrompts.CHAPTER_CHARS] 字）在段落处分几段，每段一次请求（带上前一段的末尾），合起来去重；
     * 花费记在同一个计量器上。有一段没整理成：其余几段的提议仅供核对；整章重试成功前不写入。
     */
    private suspend fun syncOne(workId: String, chapterId: String, text: String): ChapterSyncResult {
        if (!canUsePack) throw IllegalStateException("本机模型上下文太小、不能查设定包，整理本章要用联网的 AI 服务商")
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
        val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
        val number = withContext(Dispatchers.IO) { store.chapterNo(work, chapterId) } ?: (work.chapters.indexOfFirst { it.id == chapterId } + 1)
        val base = ChapterSyncResult(chapterId, chapter.title, number, ChapterSyncs.hash(text), sourceDigest = ChapterSyncs.digest(text))
        if (text.isBlank()) return base.copy(error = "这一章还没有正文")
        val hints = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text, upTo = true) }
        val system = WritePrompts.system(work.title, assistOf(workId), hints)
        // 到上一章为止的账本：开着的伏笔带编号，收伏笔时 AI 用编号指认。
        val ledger = withContext(Dispatchers.IO) { store.syncLedger(workId, chapterId, number, text) }
        val parts = WritePrompts.syncParts(text)
        val meter = AiMeter(AiFeature.SYNC, workId)
        val found = mutableListOf<SyncItem>()
        var dropped = 0
        var answered = 0
        var failed: Pair<Int, Exception>? = null
        parts.forEachIndexed { i, part ->
            try {
                val before = parts.getOrNull(i - 1)?.takeLast(WritePrompts.SYNC_OVERLAP).orEmpty()
                val answer = askWithPack(
                    "整理本章", workId, system, WritePrompts.sync(chapter.title, number, part, i + 1, parts.size, before, ledger.text),
                    whole = true, maxTokens = SYNC_TOKENS, maxRounds = SYNC_ROUNDS, toolsUpTo = chapterId,
                    requireTools = true, meter = meter,
                )
                // 原句在整章里核对（可能落在和前一段接上的那几百字里）。
                val parsed = WritePrompts.parseSync(answer, text, chapterId, number, ledger.codes)
                found += parsed.items
                dropped += parsed.dropped
                answered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failed == null) failed = i + 1 to e
            }
        }
        // 一段都没整理成：和整章没整理成一样，报错。
        failed?.let { (_, e) -> if (answered == 0) throw e }
        val merged = PackSync.merge(found)
        val (docs, entries) = withContext(Dispatchers.IO) { store.pack(workId).map { it to store.packText(workId, it.id) } to store.ledger(workId) }
        val planned = PackSync.plan(merged, docs, entries)
        return base.copy(
            items = planned, dropped = dropped, known = merged.size - planned.size, parts = parts.size,
            error = failed?.let { (i, e) -> "第 $i 段（共 ${parts.size} 段）没整理成：${e.message ?: "请求失败"}；这一章先不记为已整理" },
        )
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
        // 还有没写进设定包的结果（批量整理的几章、别的章）：这一章并进去，不冲掉。
        val kept = _sync.value?.takeIf { s -> s.workId == workId && s.results.any { it.chapterId != chapterId } }
        if (kept != null) {
            val others = kept.results.count { it.chapterId != chapterId }
            _sync.value = kept.copy(running = "", done = others, total = others + 1)
            message("已并进还没写入的整理结果")
        } else {
            _sync.value = SyncSession(workId, running = "", total = 1)
        }
        syncJob = scope.launch {
            val (title, body) = withContext(Dispatchers.IO) { store.get(workId)?.chapter(chapterId)?.title.orEmpty() to (text ?: store.text(workId, chapterId)) }
            _sync.update { s -> s?.copy(running = title) }
            val result = try {
                syncOne(workId, chapterId, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChapterSyncResult(chapterId, title, 0, ChapterSyncs.hash(body), error = e.message ?: "请求失败", sourceDigest = ChapterSyncs.digest(body))
            }
            _sync.update { s -> s?.takeIf { it.workId == workId }?.with(result)?.let { it.copy(running = null, done = it.results.size, total = it.results.size) } ?: s }
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
                        ChapterSyncResult(c.id, c.title, 0, ChapterSyncs.hash(text), error = e.message ?: "请求失败", sourceDigest = ChapterSyncs.digest(text))
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
        if (on && _sync.value?.results?.any { result -> result.error != null && result.items.any { it.key == key } } == true) return
        _sync.update { s -> s?.copy(picked = if (on) s.picked + key else s.picked - key) }
    }

    /** 改一条要写进去的文字。 */
    fun editSync(key: String, text: String) {
        _sync.update { s -> s?.copy(edits = s.edits + (key to text)) }
    }

    /** 改一条人物状态是哪一方面。 */
    fun editSyncAspect(key: String, aspect: String) {
        _sync.update { s -> s?.copy(aspects = s.aspects + (key to aspect)) }
    }

    /** 改一条新伏笔计划第几章收（空着是没定）。 */
    fun editSyncPayoff(key: String, payoff: Int?) {
        _sync.update { s -> s?.copy(payoffs = s.payoffs + (key to payoff)) }
    }

    /**
     * 把勾选的写进设定包、记进故事账本（改过的按改过的写），写设定包之前存历史版本、每条记一笔变更登记，账本一章一批；
     * 这次整理的各章（没出错的）记成已整理。[then] 拿到一句“写了几条进哪几份文档”；写失败调 [failed]（界面把按钮放开，
     * 可以重试，已经写进去的重试时跳过）。
     */
    fun applySync(workId: String, failed: () -> Unit = {}, then: (String) -> Unit = {}) {
        val session = _sync.value?.takeIf { it.workId == workId && it.running == null } ?: return
        val picked = session.chosen
        scope.launch {
            try {
                val counts = withContext(Dispatchers.IO) { store.applySync(workId, picked, session.results) }
                val now = System.currentTimeMillis()
                // Unselected chapters may have changed since review; never mark those stale results as synced.
                val successful = session.results.filter { it.error == null }
                val currentResults = withContext(Dispatchers.IO) {
                    val work = store.get(workId)
                    successful.filter { result ->
                        work?.chapter(result.chapterId) != null && store.text(workId, result.chapterId).let { body ->
                            result.sourceDigest.matches(Regex("[0-9a-f]{64}")) &&
                                ChapterSyncs.digest(body) == result.sourceDigest
                        }
                    }
                }
                val marks = currentResults.associate { it.chapterId to SyncMark(it.hash, now) }
                val staleCount = successful.size - currentResults.size
                updateAssist(workId) { it.copy(synced = it.synced + marks) }
                _sync.value = null
                val summary = if (counts.isEmpty()) "没有写进设定包，${marks.size} 章记为已整理"
                    else "写进了 ${counts.values.sum()} 条：" + counts.entries.joinToString("、") { (k, n) ->
                        if (k == PackSync.LEDGER) "$k $n 条" else "《$k》$n 条"
                    }
                then(summary + if (staleCount > 0) "；$staleCount 章正文已变化，未记为已整理" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("写进设定包失败：${e.message ?: "写入出错"}")
                failed()
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
    /** 这次写用了多少、约多少钱（写完才有）。 */
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
    val workflow: Boolean = false,
    val stage: DraftStage = DraftStage.IDLE,
    val task: ChapterWritingTask? = null,
    val targetLength: String = "",
    val assessment: DraftAssessment? = null,
    val referenceAnalysisId: String? = null,
    val referenceFingerprint: String? = null,
    val referenceChapterLength: Int? = null,
    val referenceValidated: Boolean = false,
    val referenceProvenanceBlocked: Boolean = false,
    val workflowChoice: Boolean? = null,
    /** First complete candidate saved before automatic revision; independent of the current layout. */
    val initialText: String? = null,
    /** Revised candidate retained when the author restores the first version. */
    val alternateText: String? = null,
    val revisionWarning: String? = null,
)

/** 发给 AI 的一次请求：做什么、系统提示、请求正文。 */
data class SentRequest(
    val label: String,
    val system: String,
    val prompt: String,
    val at: Long,
    /** AI 用工具查了什么（读了哪份文档、哪一节，搜了什么，读了哪一章）。 */
    val lookups: List<String> = emptyList(),
    /** 用了多少 token、约多少钱（发完才有；工具多轮的加在一起）。只有数字。 */
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
    /** 认这一次请求用（用量回来时只写进同一次）。 */
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    val size: Int get() = system.length + prompt.length
}

/**
 * 一章的整理结果：[hash] 是整理时正文的哈希（[ChapterSyncs.hash]）；[dropped] 是正文里找不到原句丢掉的，[known] 是设定包里已有、没再列的；
 * [parts] 是分了几段整理（太长的章）。有 [error] 的不记为已整理；其余段的 [items] 仅供核对，重试成功后才能写入。
 */
data class ChapterSyncResult(
    val chapterId: String,
    val title: String,
    val number: Int,
    val hash: Int,
    val items: List<SyncItem> = emptyList(),
    val dropped: Int = 0,
    val known: Int = 0,
    val error: String? = null,
    val parts: Int = 1,
    /** Session-only SHA-256 of exact source text; old normalized SyncMark hashes remain unchanged. */
    val sourceDigest: String = "",
)

/**
 * 一次整理（一章，或批量的几章）：[running] 是正在整理的章名（整理完是 null）；
 * [picked] 是勾上的提议（按 [SyncItem.key]），[edits] 是作者改过的文字，[aspects] 是改过的人物状态方面，[payoffs] 是改过的新伏笔计划第几章收。
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
    val aspects: Map<String, String> = emptyMap(),
    val payoffs: Map<String, Int?> = emptyMap(),
) {
    val items: List<SyncItem> get() = results.flatMap { it.items }

    /** 一条按作者改过的样子：文字、人物状态的方面、新伏笔计划第几章收。 */
    fun edited(item: SyncItem): SyncItem {
        val text = edits[item.key] ?: item.text
        val entry = item.entry?.let { e ->
            e.copy(aspect = aspects[item.key] ?: e.aspect, payoff = if (item.key in payoffs) payoffs[item.key] else e.payoff)
        }
        return item.copy(text = text, entry = entry)
    }

    /** 要写进去的：勾上的、能写的，按作者改过的样子。 */
    val chosen: List<SyncItem> get() = items.filter { it.writable && it.key in picked }.map { edited(it) }

    /**
     * 前面的章已经提过的同一个新人物、同一处地方、同一件事（按章号，同一章号按整理的先后）：
     * 后面章的这几条默认不勾，结果页标“前面章已有”（勾上了，写的时候也会再查一遍，不会写出两份）。
     */
    val repeats: Set<String>
        get() {
            val seen = mutableListOf<SyncItem>()
            val out = HashSet<String>()
            results.withIndex().filter { it.value.error == null }.sortedWith(compareBy({ it.value.number }, { it.index })).forEach { (_, r) ->
                val current = r.items.map(::edited)
                current.forEach { i -> if (PackSync.repeatedBefore(i, seen)) out += i.key }
                seen += current
            }
            return out
        }

    /**
     * 加上一章的结果（同一章再整理一次的换掉原来的）：默认勾上新设定和记进账本的，前面章已有的不勾；
     * 并进来的是更前面的章时，后面章因此变成重复的几条也取消勾选。
     */
    fun with(r: ChapterSyncResult): SyncSession {
        val next = copy(results = results.filterNot { it.chapterId == r.chapterId } + r)
        val repeats = next.repeats
        val writableKeys = next.results.filter { it.error == null }.flatMap { result -> result.items.map { it.key } }.toSet()
        // 后补了中间一章后，原先判重的状态也可能变成了真正的新变化，恢复默认勾选。
        val restored = this.repeats - repeats
        val defaults = next.items.filter { it.key in writableKeys && it.defaultOn && it.key !in repeats && (it.key in restored || it.chapterId == r.chapterId) }.map { it.key }
        return next.copy(picked = picked.intersect(writableKeys) - (repeats - this.repeats) + defaults)
    }
}

