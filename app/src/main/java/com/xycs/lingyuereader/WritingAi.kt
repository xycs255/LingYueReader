package com.xycs.lingyuereader

import android.content.SharedPreferences
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiCost
import com.xycs.lingyuereader.ai.AiEmptyAnswerException
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.AiMeter
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiPricing
import com.xycs.lingyuereader.ai.AiRequestBudget
import com.xycs.lingyuereader.ai.AiRequestBudgetPreview
import com.xycs.lingyuereader.ai.AiTool
import com.xycs.lingyuereader.ai.AiTruncatedException
import com.xycs.lingyuereader.ai.AiUsage
import com.xycs.lingyuereader.ai.AiWire
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.ProseCut
import com.xycs.lingyuereader.ai.ProseCutoff
import com.xycs.lingyuereader.ai.ProseSampling
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.ai.ProseScope
import com.xycs.lingyuereader.ai.ProseTask
import com.xycs.lingyuereader.data.BeatTask
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.beats
import com.xycs.lingyuereader.data.plannedCard
import com.xycs.lingyuereader.data.withBeatDone
import com.xycs.lingyuereader.data.withBeats
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteDirection
import com.xycs.lingyuereader.data.AiPack
import com.xycs.lingyuereader.data.PackKnowledge
import com.xycs.lingyuereader.data.ProseBudget
import com.xycs.lingyuereader.data.ProseLengths
import com.xycs.lingyuereader.data.ProsePack
import com.xycs.lingyuereader.data.PackTools
import com.xycs.lingyuereader.data.aiPack
import com.xycs.lingyuereader.data.chapterHints
import com.xycs.lingyuereader.data.packTools
import com.xycs.lingyuereader.data.AiCliche
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
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.EditLearning
import com.xycs.lingyuereader.data.FixReason
import com.xycs.lingyuereader.data.LearnCandidate
import com.xycs.lingyuereader.data.LearnKind
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.WriteRecord
import com.xycs.lingyuereader.data.brief
import com.xycs.lingyuereader.data.briefPresent
import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.PackSync
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.data.SyncMark
import com.xycs.lingyuereader.data.SyncPlace
import com.xycs.lingyuereader.data.applySync
import com.xycs.lingyuereader.data.chapterNo
import com.xycs.lingyuereader.data.syncLedger
import com.xycs.lingyuereader.data.syncNames
import com.xycs.lingyuereader.data.volumeNumber
import com.xycs.lingyuereader.data.unsynced
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftAssessment
import com.xycs.lingyuereader.data.DraftChecks
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.DraftWorkflow
import com.xycs.lingyuereader.data.reviewEvidence
import com.xycs.lingyuereader.data.DraftReferenceProvenance
import com.xycs.lingyuereader.data.DraftContent
import com.xycs.lingyuereader.data.DraftBeats
import com.xycs.lingyuereader.data.Beat
import com.xycs.lingyuereader.data.DraftRevisionSnapshot
import com.xycs.lingyuereader.data.WritingPhase
import com.xycs.lingyuereader.data.writingTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
    /** 写的是细纲卡里的第几拍（[InlineMode.BEAT]，从 1 数）；采纳时记进细纲卡的“已写”。 */
    val beat: Int? = null,
    /** 写的时候细纲卡一共几拍：采纳时条数变了（节拍改过）就不记，免得记错拍。 */
    val beatTotal: Int = 0,
    /**
     * 一拍出几个方向的版本时的各版（空是只写一版）。这时上面的 [text]、[loading]、[error]、[note]、[referenceValidated]
     * 是选中那一版（[selected]）的，[usage]、[cost] 是各版合计；采纳、重写都对选中的那一版。
     */
    val variants: List<InlineVariant> = emptyList(),
    val selected: Int = 0,
) {
    /** 要改的是哪一版：只写一版时为空，出几个版本时是选中的那一版。 */
    val slot: Int? get() = if (variants.isEmpty()) null else selected

    /** 还有在写的（出几个版本时任一版在写）：“停止”要能停下它们。 */
    val anyLoading: Boolean get() = if (variants.isEmpty()) loading else variants.any { it.loading }

    /** 改第 [slot] 版（空是只写一版时的那一版），再把选中那一版同步到上面。 */
    fun edit(slot: Int?, transform: (InlineVariant) -> InlineVariant): InlineWrite {
        if (slot == null) {
            val v = transform(InlineVariant(null, text, loading, error, note, usage, cost, referenceValidated))
            return copy(text = v.text, loading = v.loading, error = v.error, note = v.note, usage = v.usage, cost = v.cost,
                referenceValidated = v.referenceValidated)
        }
        if (slot !in variants.indices) return this
        return copy(variants = variants.mapIndexed { i, v -> if (i == slot) transform(v) else v }).synced()
    }

    /** 上面那几项换成选中那一版的，用量和花费是各版合计（三版按三次算钱）。 */
    fun synced(): InlineWrite {
        val v = variants.getOrNull(selected) ?: return this
        val usages = variants.mapNotNull { it.usage }
        val costs = variants.mapNotNull { it.cost }
        return copy(text = v.text, loading = v.loading, error = v.error, note = v.note, referenceValidated = v.referenceValidated,
            usage = usages.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b },
            cost = costs.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b })
    }
}

/** 一拍出几个版本时的一版：哪个方向（只写一版时为空）、写了什么、在写没有、出的错、写完的说明、这一版的用量。 */
data class InlineVariant(
    val direction: WriteDirection?,
    val text: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val note: String? = null,
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
    val referenceValidated: Boolean = false,
) {
    val label: String get() = direction?.label.orEmpty()
}

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
    /** 正在去 AI 腔（作者点的，结果作为 [SuggestKind.CLICHE] 的建议放进 [issues]）。 */
    val cliche: Boolean = false,
    /** 去 AI 腔没成：原因。 */
    val clicheError: String? = null,
    /** 去 AI 腔做完了的一句话（“AI 觉得这几处不用改”这类）。 */
    val clicheNote: String? = null,
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
 * 编辑器里写一段的输出上限：一般压到 8192，写一段用不了整章那么多。开着“写正文时先思考”、又是会思考的 V4
 * （[AiWire.switchableThinking]）时不压：思考的 token 也算进上限，压到 8192 会全用在思考上、一个字也没写。
 */
internal fun inlineOutputTokenLimit(config: AiConfig, thinking: Boolean): Int? {
    val draft = draftOutputTokenLimit(config)
    return if (thinking && AiWire.switchableThinking(config)) draft else draft?.coerceAtMost(8_192)
}

/**
 * 写正文的请求没写出正文时怎么说：[truncated] 是到了输出上限（正文一个字没有，上限全用在思考上了），
 * 否则是模型正常收尾、却没写正文。[suggestThinkingOff] 为真（作品开着“写正文时先思考”、模型能关）时再告诉作者可以关掉它。
 */
internal fun emptyProseMessage(truncated: Boolean, suggestThinkingOff: Boolean): String = when {
    !truncated -> "AI 没有写出正文，再试一次"
    suggestThinkingOff -> "模型把这次的输出上限都用在思考上，没写出正文。可以在作品 AI 设置里关掉“写正文时先思考”再试"
    else -> "模型把这次的输出上限都用在思考上，没写出正文"
}

/**
 * 流式出字时的节流：[due] 为真才刷新界面，第一块马上刷，之后至少隔 [every] 毫秒。
 * 模型去查资料、回答从头来时 [reset]，新的第一块也马上刷。
 */
internal class StreamTick(private val every: Long = WritingAi.STREAM_REFRESH_MS, private val now: () -> Long = System::currentTimeMillis) {
    private var last = 0L
    private var started = false

    fun due(): Boolean {
        val t = now()
        if (started && t - last < every) return false
        started = true
        last = t
        return true
    }

    fun reset() {
        started = false
    }
}

/**
 * 编本章任务时出的 Error（内存不够、栈溢出）换成的普通异常：各处只接 Exception 的地方也接得住，
 * 界面上显示 [message]，不闪退。原来的 Error 在 [cause] 里，已经记进闪退记录。
 */
class TaskCompileError(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * 写作的 AI：选中文字改写、灵感、读者点评、写作建议、整理设定集。
 * 除了写作建议（每部作品单独开，默认关），都是作者点了才发请求；结果只给作者看，作者点“采纳”才改稿。
 */
class WritingAi(
    private val store: WorkStore,
    private val ai: AiAssistant,
    scope: CoroutineScope,
    /** 记写作建议每天发了几次（按作品、按日期，进程重启也接着算）；为空时只记在内存里。 */
    private val prefs: SharedPreferences? = null,
    private val message: (String) -> Unit,
) {
    /**
     * 写作 AI 的协程都在这里起。外面给的（viewModelScope）没有异常处理器：哪个协程漏出一个没接住的异常，整个 app 就闪退。
     * 写正文的几条路径自己接住了（见 [failure]），这里是最后一道：记进闪退记录，在写的都停下报错，告诉作者一声。
     */
    private val scope = CoroutineScope(scope.coroutineContext + CoroutineExceptionHandler { _, e -> escaped(e) })

    /** 没接住、漏到 [scope] 的异常：记下来（不带正文），在写的都标成出错，不让 app 闪退。 */
    private fun escaped(e: Throwable) {
        CrashLog.caught(e)
        val reason = unexpected(e)
        _inline.update { w ->
            w?.takeIf { it.anyLoading }?.let { d ->
                if (d.variants.isEmpty()) d.copy(loading = false, error = reason)
                else d.copy(variants = d.variants.map { v -> if (v.loading) v.copy(loading = false, error = reason) else v }).synced()
            } ?: w
        }
        _drafts.update { m -> m.mapValues { (_, d) -> if (d.loading) d.copy(loading = false, error = reason) else d } }
        _state.update { s -> s?.takeIf { it.loading }?.copy(loading = false, error = reason) ?: s }
        // 收尾停在“在做”“正在写入”会卡住（停不了、换不了章）：退回能重试的地方。
        _finish.update { r ->
            when (r?.stage) {
                FinishStage.RUNNING -> r.copy(stage = FinishStage.AI_FAILED,
                    sync = if (r.sync == StepState.RUNNING) StepState.FAILED else r.sync,
                    summary = if (r.summary == StepState.RUNNING) StepState.FAILED else r.summary,
                    syncError = if (r.sync == StepState.RUNNING) reason else r.syncError,
                    summaryError = if (r.summary == StepState.RUNNING) reason else r.summaryError)
                FinishStage.APPLYING -> r.copy(stage = FinishStage.CONFIRM, writeError = reason)
                else -> r
            }
        }
        message(reason)
    }

    /**
     * 写正文时出的错换成给作者看的一句话。平常的出错（网络、服务商、资料不全、写盘失败）照原来的说明；
     * 意料之外的（下标越界、空指针、内存不够、栈溢出这类程序的毛病）记进闪退记录，以后好查，界面上只说出了意外、已经停下。
     */
    private fun failure(e: Throwable): String {
        if (e is Exception && !CrashLog.isBug(e)) return e.message ?: "请求失败"
        CrashLog.caught(e)
        return unexpected(e)
    }

    private fun unexpected(e: Throwable) = "出了意外（${e.javaClass.simpleName}），已经停下；详情记在“我的 → 闪退记录”里"

    /** 整理写进了故事账本（作品 id）：账本页据此重读。 */
    var ledgerChanged: (String) -> Unit = {}

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
        dropParagraphFix()
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
                val taskSnapshot = writingTask(workId, chapterId)
                val previous = withContext(Dispatchers.IO) {
                    val chapters = work.chapters
                    val i = chapters.indexOfFirst { it.id == chapterId }
                    if (taskSnapshot?.project == true) taskSnapshot.previous else chapters.getOrNull(i - 1)?.let { store.text(workId, it.id) }.orEmpty()
                }
                // 本章资料（常用文档、出场人物、察觉、伏笔、提到这一章的小节），加上这一章的细纲卡：改稿、灵感都带上。
                val pack = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text, style = true, task = taskSnapshot) }
                // 改稿类（挑错除外）带上定稿章里挑的文风范例。
                val samples = if (task is WriteAiTask.Edit && WritePrompts.usesSamples(task.action)) samplesFor(workId, chapterId) else null
                val (system, prompt) = when (task) {
                    is WriteAiTask.Edit -> {
                        val before = text.substring(0, task.start.coerceIn(0, text.length))
                        val after = text.substring(task.end.coerceIn(0, text.length))
                        // 本章开头的话，前文接上一章的结尾。
                        val context = if (before.length < WritePrompts.BEFORE_CHARS && previous.isNotBlank()) previous.takeLast(WritePrompts.BEFORE_CHARS - before.length) + "\n" + before else before
                        // 文风范例、采纳过的改法、去 AI 腔和黑名单都接在请求最后（挑错不带，见 usesSamples）。
                        val voice = if (WritePrompts.usesSamples(task.action)) WritePrompts.voiceTail(assist, samples, examples = task.action != WriteAction.CONTINUE) else ""
                        WritePrompts.system(work.title, assist, pack) to
                            WritePrompts.edit(task.action, task.original, context, after, title, task.instruction, voice)
                    }
                    is WriteAiTask.Idea -> {
                        val recent = (if (text.length < 6_000 && previous.isNotBlank()) previous.takeLast(6_000 - text.length) + "\n【" + title + "】\n" else "") + text.takeLast(6_000)
                        WritePrompts.system(work.title, assist, pack) to WritePrompts.idea(task.kind, recent, title, task.extra)
                    }
                    WriteAiTask.Review -> WritePrompts.system(work.title, assist) to WritePrompts.review(text, title)
                }
                val label = when (task) {
                    is WriteAiTask.Edit -> task.action.label
                    is WriteAiTask.Idea -> task.kind.label
                    WriteAiTask.Review -> "读者点评"
                }
                val onPiece: (String) -> Unit = { piece -> _state.update { s -> s?.takeIf { it.task === task }?.copy(text = s.text + piece) ?: s } }
                val feature = when (task) {
                    is WriteAiTask.Edit -> if (task.action == WriteAction.CONTINUE) AiFeature.INLINE else AiFeature.EDIT
                    else -> AiFeature.IDEA
                }
                val meter = AiMeter(feature, workId)
                // 改稿类的结果要换进正文：被截断、拒答或中途断开（whole）就报错，不给采纳；灵感、点评只给人看，截断了照样显示。
                val answer = if (task == WriteAiTask.Review) ask(label, workId, system, prompt, meter = meter, onText = onPiece)
                else askWithPack(label, workId, system, prompt, whole = task is WriteAiTask.Edit, toolsUpTo = chapterId,
                    confirmedOnly = taskSnapshot?.project == true, requireTools = taskSnapshot?.project == true,
                    meter = meter,
                    // 润色、扩写、续写这类改正文的用作品设的温度和思考开关；挑错只改错，求稳，不用。灵感也不用。
                    prose = proseOf(assist).takeIf { task is WriteAiTask.Edit && task.action != WriteAction.PROOFREAD },
                    onReset = { _state.update { s -> s?.takeIf { it.task === task }?.copy(text = "") ?: s } }, onText = onPiece)
                val final = if (task is WriteAiTask.Edit) WritePrompts.clean(answer, task.original) else answer.trim()
                _state.update { s -> s?.takeIf { it.task === task }?.copy(text = final, loading = false, usage = meter.usage, cost = meter.cost) ?: s }
                // 灵感、点评记下来，关了面板还能回头翻。
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
            else {
                // 去 AI 腔挑的段不按长短筛（“他嘴角勾起。”这种短段也挑）：只看段还在不在正文里，不受 [MIN_PARAGRAPH] 限制。
                val all by lazy { text.lines().map { it.trim() }.toHashSet() }
                r.copy(issues = r.issues.filter { i ->
                    i.wrong in i.paragraph && (i.paragraph in paragraphs || (i.kind == SuggestKind.CLICHE.name && i.paragraph.trim() in all))
                })
            }
        }
        val seen = checked.getOrPut(workId to chapterId) { HashSet() }
        val todo = paragraphs.filter { it !in seen }.takeLast(REALTIME_BATCH)
        if (todo.isEmpty()) return
        val generation = realtimeGeneration
        var checkedSuccessfully = false
        val launched = scope.launch {
            val assist = assistOf(workId)
            if (!assist.realtime) return@launch
            val kinds = assist.suggestKinds.mapNotNull { k -> SuggestKind.entries.firstOrNull { it.name == k && it.realtime } }.toSet()
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
                // 只查错字（或本机模型）：不带设定包，一问一答。要查规范、口吻：带上设定包和本章提示，
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
        clicheJob?.cancel()
        _realtime.value = RealtimeState()
    }

    // ---------- 去 AI 腔 ----------

    private var clicheJob: Job? = null

    /**
     * 去 AI 腔：[ranges] 是正文 [text] 里本机标出的 AI 腔、黑名单命中，把含它们的段落（最多 [CLICHE_BATCH] 段）发给 AI，
     * 只改标出的说法和它所在的那一句。结果放进写作建议的列表（[SuggestKind.CLICHE]），作者一条条改或忽略；
     * 上一次去 AI 腔的结果换成这一次的。写作建议没开也能用：这是作者自己点的。定稿的章不给。
     */
    fun decliche(workId: String, chapterId: String, text: String, ranges: List<IntRange>) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val picked = AiCliche.paragraphs(text, ranges, CLICHE_BATCH)
        if (picked.isEmpty()) {
            message("这一章没有标出的 AI 腔")
            return
        }
        clicheJob?.cancel()
        _realtime.update { r -> (if (r.chapterId != chapterId) RealtimeState(chapterId, today = r.today) else r).copy(cliche = true, clicheError = null, clicheNote = null) }
        clicheJob = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                if (work.chapter(chapterId)?.locked == true) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val assist = assistOf(workId)
                val paragraphs = picked.map { it.first }
                // 只看这几段，不带设定包、不给工具：一问一答。写法要求（含黑名单）照样接在最后。
                val answer = ask("去 AI 腔", workId, WritePrompts.system(work.title, assist),
                    WritePrompts.decliche(picked, WritePrompts.voiceTail(assist, null)),
                    whole = true, meter = AiMeter(AiFeature.CLICHE, workId))
                val found = WritePrompts.parseIssues(answer, paragraphs)
                    .map { it.copy(kind = SuggestKind.CLICHE.name, source = AiCliche.DOC) }
                    .filter { (it.paragraph to it.wrong) !in dismissed }
                _realtime.update { r ->
                    if (r.chapterId != chapterId) r
                    else r.copy(
                        issues = (r.issues.filter { it.kind != SuggestKind.CLICHE.name } + found).distinctBy { it.paragraph to it.wrong },
                        cliche = false,
                        clicheNote = if (found.isEmpty()) "AI 觉得标出的这几处不用改" else null,
                    )
                }
            } catch (e: CancellationException) {
                _realtime.update { r -> if (r.chapterId == chapterId) r.copy(cliche = false) else r }
                throw e
            } catch (e: Exception) {
                _realtime.update { r -> if (r.chapterId == chapterId) r.copy(cliche = false, clicheError = e.message ?: "请求失败") else r }
            }
        }
    }

    /** 收起去 AI 腔的提示（出错、不用改）。 */
    fun clearClicheNote() {
        _realtime.update { it.copy(clicheError = null, clicheNote = null) }
    }

    /** 作者点“改”采纳了一条去 AI 腔：记进采纳过的修改（那一句的前后），以后当例子学。 */
    fun acceptCliche(workId: String, issue: WriteIssue) {
        val before = AiCliche.sentence(issue.paragraph, issue.wrong)
        val after = before.replaceFirst(issue.wrong, issue.right)
        updateAssist(workId) { WritePrompts.remember(it, "去 AI 腔", before, after, System.currentTimeMillis()) }
    }

    // ---------- 在编辑器里写 ----------

    private val _inline = MutableStateFlow<InlineWrite?>(null)
    /** 在编辑器里正在写（或写完等作者采纳）的那一段。 */
    val inline: StateFlow<InlineWrite?> = _inline.asStateFlow()
    /** 正在写的请求：键是第几版（只写一版时是空）。准备资料时几版共用一个，发出去以后各版一个。 */
    private val inlineJobs = HashMap<Int?, Job>()
    /** 第几次让 AI 在编辑器里写：每次写只认自己这一次。主线程改，流式出字时 IO 线程读。 */
    @Volatile private var inlineRun = 0
    /** 各版这一次写的编号：只重写一版时，那一版旧的请求不能再改它。主线程改，IO 线程读（键里有空，不能用 ConcurrentHashMap）。 */
    private val inlineSlots: MutableMap<Int?, Int> = java.util.Collections.synchronizedMap(HashMap())
    private var inlineSlotSeq = 0

    /** 写之前算好、几版共用的东西：系统提示（和它稳定的开头）、光标前后、任务卡、写法要求、这一拍、范本、写正文的采样。 */
    private class InlinePrep(
        val system: String,
        val stable: String,
        val before: String,
        val after: String,
        val task: ProseTask,
        val voice: String,
        val beat: BeatTask?,
        val reference: ReferenceWritingMaterial?,
        val project: Boolean,
        val prose: ProseSampling,
        /** 本机收住的字数（见 [ProseCutoff]）；为空不收。 */
        val cap: Int? = null,
    )

    /**
     * 从光标 [at] 处接着写：[text] 是编辑框里现在的全文。带设定包、细纲卡、写作规范和检查规则；
     * 写的字一段段显示在光标处，作者采纳才进正文。[variants] 为真时按 [WriteDirection] 的三个方向各写一版（并发，各自显示），作者挑一版采纳。
     */
    fun writeInline(workId: String, chapterId: String, text: String, at: Int, mode: InlineMode, instruction: String = "", referenceAnalysisId: String? = null, beat: Int? = null,
                    variants: Boolean = false) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        cancelInline()
        // 这一次的编号：旧的那次被取消（停下的说明）、出错时，不能去改新的这一次。
        val run = ++inlineRun
        val cursor = at.coerceIn(0, text.length)
        val d = InlineWrite(workId, chapterId, cursor, text, mode, instruction, referenceAnalysisId = referenceAnalysisId, beat = beat,
            variants = if (variants) WriteDirection.entries.map { InlineVariant(it) } else emptyList())
        _inline.value = d
        startInline(run, d, if (variants) d.variants.indices.toList() else listOf(null))
    }

    /** 改第 [slot] 版（空是只写一版）：只认这一次、这一版最新的请求。 */
    private fun updateInline(run: Int, slot: Int?, token: Int, transform: (InlineVariant) -> InlineVariant) {
        if (run == inlineRun && inlineSlots[slot] == token) _inline.update { d -> d?.edit(slot, transform) }
    }

    /** 写 [slots] 这几版：先准备一次资料，再各版各发一个请求（并发）；一版出错不影响别的版。 */
    private fun startInline(run: Int, d: InlineWrite, slots: List<Int?>) {
        val tokens = slots.associateWith { ++inlineSlotSeq }
        inlineSlots.putAll(tokens)
        val head = d.base.substring(0, d.at)
        val prepJob = scope.launch {
            val prep = try {
                prepareInline(run, d)
            } catch (e: CancellationException) {
                slots.forEach { s -> updateInline(run, s, tokens.getValue(s)) { it.copy(loading = false) } }
                throw e
            } catch (e: Throwable) {
                val error = failure(e)
                slots.forEach { s -> updateInline(run, s, tokens.getValue(s)) { it.copy(loading = false, error = error) } }
                return@launch
            }
            if (run != inlineRun) return@launch
            slots.forEach { s ->
                if (inlineSlots[s] != tokens.getValue(s)) return@forEach
                inlineJobs[s] = scope.launch { writeVersion(run, s, tokens.getValue(s), d, head, prep) }
            }
        }
        slots.forEach { inlineJobs[it] = prepJob }
    }

    /** 几版共用的资料：作品、本章、细纲卡、规范、上一章结尾、文风范例、范本。章定稿了、节拍改过了就报错。 */
    private suspend fun prepareInline(run: Int, d: InlineWrite): InlinePrep {
        val workId = d.workId
        val chapterId = d.chapterId
        val text = d.base
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
        val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
        if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
        val taskSnapshot = writingTask(workId, chapterId, d.instruction)
        val reference = d.referenceAnalysisId?.let { withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, it) } }
        if (reference != null && run == inlineRun) _inline.update { w -> w?.copy(referenceFingerprint = reference.excerpt.fingerprint,
            referenceChapterLength = reference.excerpt.chapterLength) }
        val length = chapterLengthOf(workId, taskSnapshot)
        val kit = proseKit(workId, chapterId, text, taskSnapshot, length, reference != null)
        // 写某一拍：按现在细纲卡里的节拍算这一拍、前后两拍和篇幅；节拍改过、这一拍没了就不写。
        val beatTask = d.beat?.let { n ->
            val list = beats(kit.card)
            if (n !in 1..list.size) throw IllegalStateException("细纲卡的节拍改过了，没有第 $n 拍，重新选一拍")
            if (run == inlineRun) _inline.update { w -> w?.copy(beatTotal = list.size) }
            Beats.task(list, n, length)
        }
        val prose = proseOf(assistOf(workId))
        // 下面要扫一遍光标前的全文（估写到第几拍、数已写的字）：长章节里不放在主线程上算。
        return withContext(Dispatchers.Default) {
            val head = text.substring(0, d.at)
            // 光标前不够长：前面接上一章的结尾（截好的最后一段）。
            val withPrevious = head.length < WritePrompts.ENDING_MAX && kit.previous.isNotBlank()
            val before = (if (withPrevious) "（上一章结尾）\n" + kit.previous + "\n（本章）\n" else "") + head.takeLast(INLINE_BEFORE)
            val after = text.substring(d.at).take(800)
            val scope = when (d.mode) {
                InlineMode.SCENE -> ProseScope.SCENE
                InlineMode.TO_END -> ProseScope.TO_END
                InlineMode.WORDS -> ProseScope.WORDS
                InlineMode.CUSTOM -> ProseScope.CUSTOM
                InlineMode.BEAT -> ProseScope.BEAT
            }
            // 写到本章结尾：作者没勾“已写”时，按正文估一估已经写到第几拍，剩下的才算这次要写的；都写了是拍数加一（见 [WritePrompts.taskCard]）。
            val from = if (d.mode != InlineMode.TO_END || kit.card.beatsDone.isNotEmpty()) null
                else beats(kit.card).let { list -> Beats.covered(list, head).takeIf { it > 0 }?.let { minOf(it + 1, list.size + 1) } }
            val task = ProseTask(
                kit.chapter, chapter.title, kit.card, scope, beat = beatTask?.beat?.index ?: from,
                length = when (d.mode) {
                    InlineMode.BEAT -> beatTask?.let(WritePrompts::beatLength).orEmpty()
                    // 写到本章结尾：只给剩下的字数（全章篇幅减去已写的），模型不用自己算。
                    InlineMode.TO_END -> WritePrompts.remaining(length, head)
                    InlineMode.WORDS -> WritePrompts.WORDS_BUDGET.card("这一段")
                    InlineMode.SCENE, InlineMode.CUSTOM -> ""
                },
                opening = head.isBlank(), previous = head.isBlank() && withPrevious, previousHook = kit.previousHook, instruction = d.instruction,
                seeds = kit.seeds,
            )
            // 本机收住：各按自己的篇幅；写下一场卡上不写数字，只在本机把关；按作者要求写的不收（作者可能就要长的）。
            val chapterBudget = ProseBudget.parse(length)
            val cap = when (d.mode) {
                InlineMode.BEAT -> beatTask?.let(WritePrompts::beatBudget)?.cutoff
                InlineMode.TO_END -> WritePrompts.remainingBudget(length, head)?.cutoff ?: chapterBudget?.let { WritePrompts.SCENE_BUDGET.cutoff }
                InlineMode.WORDS -> WritePrompts.WORDS_BUDGET.cutoff
                InlineMode.SCENE -> WritePrompts.SCENE_BUDGET.cutoff
                InlineMode.CUSTOM -> null
            }
            InlinePrep(kit.system, kit.stable, before, after, task, kit.tail, beatTask, reference, kit.project, prose, cap)
        }
    }

    /** 写一版：[slot] 是第几版（空是只写一版）。停下、出错只改这一版。 */
    private suspend fun writeVersion(run: Int, slot: Int?, token: Int, d: InlineWrite, head: String, prep: InlinePrep) {
        val direction = slot?.let { d.variants.getOrNull(it)?.direction }
        val reference = prep.reference
        // 收到的字攒在这里（每块都拼一个新字符串，长回答里是平方级的复制）。
        val written = StringBuilder()
        val meter = AiMeter(AiFeature.INLINE, d.workId)
        fun update(transform: (InlineVariant) -> InlineVariant) = updateInline(run, slot, token, transform)
        try {
            val prompt = withContext(Dispatchers.Default) {
                WritePrompts.continueAt(prep.before, prep.after, prep.task.copy(direction = direction), prep.voice, referenceText(reference))
            }
            if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, d.workId, d.chapterId, reference) }
            // 长章节里每显示一次都要整章重排：流式的字最多每 [STREAM_REFRESH_MS] 毫秒更新一次显示。
            val tick = StreamTick()
            val cut = cutoff(prep.cap)
            var capped = false
            val answer = try {
                askWithPack(
                    "在编辑器里写" + (direction?.let { " · ${it.label}" } ?: ""), d.workId, prep.system, prompt,
                    maxTokens = inlineOutputTokenLimit(ai.currentConfig, prep.prose.thinking), meter = meter,
                    toolsUpTo = d.chapterId, phase = WritingPhase.CREATE, confirmedOnly = true,
                    requireTools = prep.project, prose = prep.prose, lean = true, stable = prep.stable,
                    onReset = {
                        written.setLength(0)
                        cut?.reset()
                        tick.reset()
                        update { it.copy(text = "") }
                    },
                ) { piece ->
                    cut?.feed(piece)
                    written.append(piece)
                    if (tick.due()) update { it.copy(text = joinAt(head, written.toString())) }
                }
            } catch (e: ProseCut) {
                // 写长了：在句末收住，请求已经断开；收住的这一段照常可以采纳。
                capped = true
                e.text
            }
            val truncated = !capped && answer.endsWith(TRUNCATED_NOTE)
            val note = when {
                capped -> cutNote(if (prep.beat != null) "这一拍" else "这次", WordCount.of(WritePrompts.cleanDraft(answer)))
                truncated -> "写到长度上限停了，可以采纳后再接着写"
                answer.endsWith(STOPPED_NOTE) -> "模型中途停了"
                else -> null
            }
            val clean = WritePrompts.cleanDraft(answer.removeSuffix(TRUNCATED_NOTE).removeSuffix(STOPPED_NOTE))
            // 一个字没写（多半是输出上限全用在思考上了）：不是“写了 0 字可以采纳”，是出错；花了的钱照样显示。
            if (clean.isBlank()) {
                update { it.copy(text = "", loading = false, note = null, error = emptyProse(truncated, prep.prose),
                    usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
                return
            }
            if (reference != null) {
                verifyOffMain(clean, reference)
                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, d.workId, d.chapterId, reference) }
            }
            // 写成了：清掉之前兜底处理器标的“出了意外”（那时这一版其实还在写），不然写好的这一版采纳不了。
            update { it.copy(text = joinAt(head, clean), loading = false, note = note, error = null,
                referenceValidated = reference != null, usage = meter.usage, cost = meter.cost) }
            log(d.workId, d.chapterId, "在编辑器里写", (prep.beat?.let { "写第 ${it.beat.index} 拍：${it.beat.text}" } ?: d.mode.label) +
                (direction?.let { "（${it.label}）" } ?: "") + d.instruction.let { if (it.isBlank()) "" else "：$it" }, clean, meter)
        } catch (e: CancellationException) {
            // 停下：已经写出来的（可能还没显示出来）留着。
            val shownText = joinAt(head, written.toString())
            update { it.copy(text = shownText, loading = false, note = if (shownText.isEmpty()) null else "停下了", usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
            throw e
        } catch (e: RejectedReferenceWriting) {
            written.setLength(0)
            update { it.copy(text = "", loading = false, error = e.message, usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
        } catch (e: Throwable) {
            update { it.copy(loading = false, error = failure(e), usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
        }
    }

    /** 取消正在写的各版（准备资料的、各版的请求）。 */
    private fun cancelInline() {
        inlineJobs.values.toSet().forEach { it.cancel() }
        inlineJobs.clear()
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

    /** 停下：已经写出来的留着，作者可以采纳或撤回。出几个版本时几版一起停。 */
    fun stopInline() {
        cancelInline()
    }

    /** 撤回：AI 写的不要了（几个版本都不要）。 */
    fun discardInline() {
        cancelInline()
        _inline.value = null
    }

    /** 出几个版本时换看哪一版：正文里的浅色预览、采纳、重写都跟着换。 */
    fun selectInline(index: Int) {
        _inline.update { d -> d?.takeIf { index in it.variants.indices }?.copy(selected = index)?.synced() ?: d }
    }

    /** 重写：同样的位置、同样的要求再写一次。出几个版本时只重写选中的那一版，别的版留着。 */
    fun retryInline() {
        val d = _inline.value ?: return
        if (d.variants.isEmpty()) {
            writeInline(d.workId, d.chapterId, d.base, d.at, d.mode, d.instruction, d.referenceAnalysisId, d.beat)
            return
        }
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val slot = d.selected
        // 这一版的旧请求（准备资料时几版共用一个，那时别的版还要它，不取消）。
        inlineJobs.remove(slot)?.takeIf { job -> inlineJobs.values.none { it === job } }?.cancel()
        val restarted = d.edit(slot) { InlineVariant(it.direction) }
        _inline.value = restarted
        startInline(inlineRun, restarted, listOf(slot))
    }

    /**
     * 采纳：AI 写的（出几个版本时是选中的那一版）插进正文，存一版（采纳前的正文先存进历史），AI 写的字不算码字数。
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
                _inline.value = d.edit(d.slot) { it.copy(error = e.message ?: "范本来源已变化，请重新生成", referenceValidated = false) }
                return null
            }
        }
        val text = current.substring(0, d.at) + d.text + current.substring(d.at)
        // 别的版还在写：不要了，停下。
        cancelInline()
        _inline.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (current.isNotBlank()) store.snapshot(d.workId, d.chapterId, current)
                    store.saveText(d.workId, d.chapterId, text, count = false)
                    // 记下采纳的 AI 原稿（编辑器里写、逐拍写都走这里），收尾时和定稿比对学作者的改法。记不上不影响正文。
                    runCatching { store.recordAiText(d.workId, d.chapterId, d.text) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
                return@launch
            }
            // 正文存好了才把这一拍记成“已写”；记不上只提示，不影响已经放进正文的字。
            val beat = d.beat ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    store.updateCard(d.workId, d.chapterId) { card ->
                        if (beats(card).size == d.beatTotal) card.withBeatDone(beat, true) else card
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("第 $beat 拍没记成已写：${e.message ?: "写入出错"}")
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

    /**
     * 写这一章要带给 AI 的资料：本章资料（按章挑的设定）加细纲卡和上一章的钩子。[upTo] 为真时章目录只列到这一章（查阅工具也只能读到这一章）。
     * [task] 是调用方刚编好的本章任务（不带要求、篇幅的那份）：有就直接用，不再编一次。
     */
    private suspend fun chapterContext(workId: String, chapterId: String, text: String, style: Boolean = false, upTo: Boolean = false,
                                       task: ChapterWritingTask? = null): String? {
        val work = store.get(workId) ?: return null
        val project = task ?: writingTask(workId, chapterId)
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
        /** 写正文的采样（[proseOf]）：只有写正文、改正文的请求才传，别的都空着。 */
        prose: ProseSampling? = null,
        /** 关掉思考（见 [quiet]）：整理、摘要这类要完整 JSON 或一段短答的请求。 */
        noThinking: Boolean = false,
        onText: (String) -> Unit = {},
    ): String {
        val sent = SentRequest(label, system, prompt, System.currentTimeMillis())
        _lastRequest.value = sent
        checkFits(system, prompt, emptyList())
        try {
            return withContext(meter) { proseErrors(prose) { ai.ask(system, prompt, whole, prose ?: quiet(noThinking), onText) } }
        } finally {
            noteUsage(sent.id, meter)
        }
    }

    /** 作品设的写正文采样：“写正文的温度”和“写正文时先思考”。 */
    private fun proseOf(assist: WorkAssist) = ProseSampling(assist.temperature, assist.thinking)

    /**
     * 整理、摘要这类请求的采样：不带温度，只在能开关思考的官方 V4 上写 `thinking: disabled`（[AiWire.switchableThinking]，别的模型什么都不写）。
     * 它们只是从正文里摘事实、写一段概括，用不着先想；V4 默认开着思考，思考的 token 也算进上限，整理那 5120 的上限
     * 会被思考吃光，作者看到的是“回答太长”。审稿不关：要判断前后矛盾，思考有用，而且审稿不设上限，吃不光。
     * 这里不当成写正文的请求：没写出东西时照原来的报错，不说“没写出正文”。
     */
    private fun quiet(on: Boolean): ProseSampling? = if (on) ProseSampling(temperature = null, thinking = false) else null

    /** 写正文没写出正文时的报错（见 [emptyProseMessage]）：只有开着思考、模型又能关思考时，才建议去关。 */
    private fun emptyProse(truncated: Boolean, prose: ProseSampling): String =
        emptyProseMessage(truncated, prose.thinking && AiWire.switchableThinking(ai.currentConfig))

    /**
     * 写正文的请求（[prose] 不为空）一个字没写就结束了：换成说清楚原因的报错。到了输出上限、一个字没有（要完整结果的整章、改稿）
     * 是上限全用在思考上了；正常结束却没有字是没写出来。别的请求、别的错误照原样抛出。
     */
    private suspend fun <T> proseErrors(prose: ProseSampling?, block: suspend () -> T): T = try {
        block()
    } catch (e: AiTruncatedException) {
        if (prose == null || !e.empty) throw e
        throw AiException(emptyProse(true, prose))
    } catch (e: AiEmptyAnswerException) {
        if (prose == null) throw e
        throw AiException(emptyProse(false, prose))
    }

    /** 把这次的用量写进“最近一次发给 AI 的内容”（还是这一次的话）。 */
    private fun noteUsage(id: String, meter: AiMeter) {
        if (meter.usage.isEmpty) return
        _lastRequest.update { r -> if (r?.id == id) r.copy(usage = meter.usage, cost = meter.cost) else r }
    }

    /** 请求的名字对应的功能（按功能累计用量）。 */
    private fun featureOf(label: String): AiFeature = when (label) {
        "写作建议" -> AiFeature.SUGGEST
        "去 AI 腔" -> AiFeature.CLICHE
        "在编辑器里写" -> AiFeature.INLINE
        "写本章", "接着写" -> AiFeature.DRAFT
        "事实关", "写法关" -> AiFeature.REVIEW
        "整理本章", "从改稿学" -> AiFeature.SYNC
        "重写这段" -> AiFeature.DRAFT
        "拟细纲", "章节摘要", "拆节拍" -> AiFeature.PLAN
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
        /**
         * 写正文：[rest] 已经是完整的系统提示（[WritePrompts.proseSystem]），不再在前面放整份设定包，
         * 只在后面接一两句查阅说明和能查的资料清单；[stable] 是它每次一样的开头（缓存边界）。
         */
        lean: Boolean = false,
        /** [lean] 时接在系统提示后面的查阅说明；为空用写正文的那份（[WritePrompts.proseToolGuide]）。审读用自己的（带来源编号）。 */
        guide: String? = null,
        /** 模型不支持工具、这次没带工具就答了（只有 [requireTools] 为假时会发生）。 */
        onToolsDropped: () -> Unit = {},
        /**
         * 写正文的采样（[proseOf]：温度、写正文时先思考）。只有写正文、改正文的请求传它：编辑器里接着写、写本章、项目包的创作、
         * 润色扩写这类改稿；审稿、修订、拟细纲、拆节拍、整理、写作建议、去 AI 腔都不传（空是服务商默认）。
         */
        prose: ProseSampling? = null,
        /** 关掉思考（见 [quiet]）。 */
        noThinking: Boolean = false,
        onReset: () -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (!canUsePack) {
            if (requireTools) throw AiException("本次项目任务需要设定包查阅工具；本机模型无法完成，请选支持工具调用的模型")
            return ask(label, workId, rest, prompt, whole, meter, prose, noThinking, onText)
        }
        val budget = knowledgeBudget(workId)
        val stage = phase ?: when (label) {
            "写本章", "接着写", "在编辑器里写", "续写" -> WritingPhase.CREATE
            "事实关", "写法关", "读者点评", "写作建议" -> WritingPhase.REVIEW
            "整理本章" -> WritingPhase.SYNC
            "润色", "更生动", "扩写", "缩写", "按要求改", "挑错" -> WritingPhase.REVISE
            else -> WritingPhase.ASSIST
        }
        val (prefix, tools) = withContext(Dispatchers.IO) {
            val tools = store.packTools(workId, toolsUpTo, phase = stage, confirmedOnly = confirmedOnly)
            if (lean) "" to tools else store.aiPack(workId, budget, stage).prefix to tools
        }
        val system = if (lean) rest.trim() + (if (useTools) "\n\n" + (guide ?: WritePrompts.proseToolGuide(proseCatalog(workId))) else "")
            else prefix + "\n\n=== 这次的工作 ===\n" + rest.trim() + if (useTools) "\n\n" + WritePrompts.TOOL_GUIDE else ""
        val sent = SentRequest(label, system, prompt, System.currentTimeMillis())
        _lastRequest.value = sent
        checkFits(system, prompt, if (useTools) PackTools.TOOLS else emptyList())
        try {
            // 工具多轮的几次请求都加在这一个计量器上。
            return withContext(meter) { proseErrors(prose) { ai.converse(
                system, prompt, if (useTools) PackTools.TOOLS else emptyList(),
                run = { call ->
                    val out = withContext(Dispatchers.IO) { tools.run(call) }
                    _lookup.value = tools.lookups.lastOrNull()
                    _lastRequest.update { it?.copy(lookups = tools.lookups.toList()) }
                    out
                },
                // 写正文：稳定的开头是身份、作者的说明和写作规范（[stable]）；别的请求是整份设定包。
                cachePrefix = if (lean) stableLength(system, stable) else prefix.length,
                // 系统提示里知识库之后还有一段稳定的文字时（[stable]），Anthropic 上在它后面再标一处缓存。
                cacheExtra = if (lean) 0 else stable?.let { s -> system.indexOf(s).takeIf { it >= 0 }?.plus(s.length) } ?: 0,
                whole = whole,
                maxTokens = maxTokens,
                maxRounds = maxRounds,
                requireTools = requireTools,
                onToolsDropped = onToolsDropped,
                onLookup = { onReset() },
                prose = prose ?: quiet(noThinking),
                onText = onText,
            ) } }
        } finally {
            _lookup.value = null
            noteUsage(sent.id, meter)
        }
    }

    /**
     * 发之前先估一估：这次要发的（系统提示、请求、工具说明，按真发的请求体估）超过模型的上下文（[AiConfig.contextTokens]，
     * DeepSeek 按 128K）就不发，白发只会被服务商退回来，还可能照样计费。估法和草稿页的预算同一个（[AiRequestBudget]）。
     * 自定义服务商不知道模型多大、本机模型另有截断，不拦。看发给 AI 的内容仍能看到这次要发什么。
     */
    private fun checkFits(system: String, prompt: String, tools: List<AiTool>) {
        val config = ai.currentConfig
        if (config.onDevice || config.preset == AiPresets.CUSTOM) return
        val preview = AiRequestBudget.preview(config, system, prompt, tools, maxTokens = null, workflow = false)
        if (preview.inputTokens > preview.planningTokens) throw AiException(
            "资料太多，超过模型上限：这次要发约 ${AiPricing.tokens(preview.inputTokens)} token（${system.length + prompt.length} 字），" +
                "${config.providerLabel} 按 ${AiPricing.tokens(preview.planningTokens.toLong())} token 算。没有发送；请减少常驻资料或缩小范围")
    }

    /** 系统提示开头那段稳定的文字有多长（[stable] 不是它的开头时为 0，不标缓存）。 */
    private fun stableLength(system: String, stable: String?): Int =
        stable?.trim()?.takeIf { it.isNotEmpty() && system.startsWith(it) }?.length ?: 0

    /** 写正文时能用工具查的资料（文档名）。 */
    private suspend fun proseCatalog(workId: String): List<String> = withContext(Dispatchers.IO) {
        ProsePack.catalog(store.packSources(workId).raw)
    }

    // ---------- 写正文共用的资料 ----------

    /**
     * 几条写正文的路径（编辑器里写、AI 写整章草稿、项目包的创作和修订）共用的：系统提示（[WritePrompts.proseSystem]）
     * 和它稳定的开头、上一章结尾（截好的）、上一章的钩子、写法要求（[WritePrompts.proseTail]）、第几章、细纲卡、全章篇幅。
     */
    private class ProseKit(
        val system: String,
        val stable: String,
        val previous: String,
        val previousHook: String,
        val tail: String,
        val chapter: Int?,
        val card: ChapterCard,
        val length: String,
        val project: Boolean,
        /** 这一章可以埋的伏笔（任务卡里列出，只点到为止）。 */
        val seeds: List<String> = emptyList(),
        /** 本章用得上的资料（写正文的说法已经在 [system] 里）；审读用同一份，换成核对的说法。 */
        val brief: ChapterBrief? = null,
    )

    /**
     * 算 [ProseKit]：[text] 是这一章现在的正文（挑本章资料、出场人物用），[task] 是 [writingTask] 的结果（项目包作品的
     * 上一章只认定稿），[length] 是全章篇幅，[reference] 为真时这次照范本写；[phase] 是创作还是修订（带哪一阶段的作者提示词）。
     * 本章资料不带后文（[brief] 的 `prose`），上限是作品设的本章资料上限的一半：其余的让模型需要时自己查。
     */
    private suspend fun proseKit(workId: String, chapterId: String, text: String, task: ChapterWritingTask?, length: String, reference: Boolean,
                                 phase: WritingPhase = WritingPhase.CREATE): ProseKit {
        val assist = assistOf(workId)
        val samples = samplesFor(workId, chapterId, scene = true)
        return withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            val i = work.chapters.indexOfFirst { it.id == chapterId }
            val prior = work.chapters.getOrNull(i - 1)
            val project = task?.project == true
            val previous = WritePrompts.ending(if (project) task!!.previous else prior?.let { store.text(workId, it.id) }.orEmpty())
            val previousHook = prior?.let { store.plannedCard(workId, it.id).card.hook }.orEmpty()
            val card = store.plannedCard(workId, chapterId).card
            val limit = if (localModel) LOCAL_BRIEF_LIMIT else (assist.briefLimit / 2).coerceIn(PROSE_BRIEF_MIN, PROSE_BRIEF_MAX)
            val brief = store.brief(workId, chapterId, text, limit, confirmedOnly = project, prose = true)
            val material = brief?.render(prose = true, waitingMax = ChapterBrief.WAITING).orEmpty()
            // 作者的写作规范、称谓与语气、检查规则：只放这一份，去掉整章字数（篇幅只在任务卡里说）。
            val norms = ProsePack.tidy(ProseLengths.strip(writingRules(workId, chapterId, text)))
            val author = ProsePack.instructions(store.packSources(workId).raw, phase)
            val stable = WritePrompts.proseSystem(work.title, assist, author, norms, "")
            val system = WritePrompts.proseSystem(work.title, assist, author, norms, material) +
                (if (reference) "\n\n" + ReferenceWriting.SYSTEM_RULE else "")
            ProseKit(system, stable, previous, previousHook, WritePrompts.proseTail(assist, samples, norms + "\n" + author),
                task?.chapter ?: store.chapterNo(work, chapterId), card, length, project, brief?.seeds().orEmpty(), brief)
        }
    }

    /**
     * 读这次要用的范本。[candidate] 为真时是给已写的候选稿接着写、审读：范本变了、删了或读不出来，标成
     * [DraftReferenceIssue.CHANGED]，草稿页给出路（不用范本直接审读、从头重写），不只说“请从头重写”。
     */
    private suspend fun loadForCandidate(workId: String, chapterId: String, analysisId: String, candidate: Boolean): ReferenceWritingMaterial = try {
        withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, analysisId) }
    } catch (e: RejectedReferenceWriting) {
        if (!candidate) throw e
        throw RejectedReferenceWriting("${e.message}。这一稿写的时候用的范本对不上了，没法按原来的范本核验", DraftReferenceIssue.CHANGED)
    }

    /**
     * 已写的候选稿要接着写、审读：范本还是写它时那一份（指纹、章长都对得上），且这一稿核验过。范本变了是
     * [DraftReferenceIssue.CHANGED]；只是“已核验”的标记没了是 [DraftReferenceIssue.UNVERIFIED]（重新核验就行）；
     * 再对全文跑一遍 [ReferenceWriting.verifyDraft]，和范本太像是 [DraftReferenceIssue.COPIED]。
     */
    private suspend fun checkCandidateSource(previous: DraftState, reference: ReferenceWritingMaterial) {
        if (reference.excerpt.fingerprint != previous.referenceFingerprint || reference.excerpt.chapterLength != previous.referenceChapterLength)
            throw RejectedReferenceWriting("范本在这一稿写完以后变了，没法按原来的范本核验", DraftReferenceIssue.CHANGED)
        if (!previous.referenceValidated)
            throw RejectedReferenceWriting("这一稿还没核验和范本有没有照抄（改过、压过篇幅，或标记丢了）：范本没变，点“重新核验范本”就行",
                DraftReferenceIssue.UNVERIFIED)
        withContext(Dispatchers.Default) { ReferenceWriting.verifyDraft(previous.text, reference) }
    }

    /**
     * 留下来的稿子带着范本、却还没标“已核验”（整章写到一半停下、出错，压篇幅压出来的新一版）：现在对全文核一遍，
     * 范本没变、核验通过就标成已核验，之后随正文和范本指纹一起落盘。没过就照旧不标，接着写、审读时再给出路。
     */
    private suspend fun settleReference(key: Pair<String, String>, run: Int) {
        val d = _drafts.value[key]?.takeIf { draftRuns[key] == run } ?: return
        val id = d.referenceAnalysisId ?: return
        if (d.referenceValidated || d.referenceProvenanceBlocked || d.text.isBlank()) return
        val ok = try {
            val source = withContext(Dispatchers.IO) { ReferenceWriting.load(store, key.first, key.second, id) }
            source.excerpt.fingerprint == d.referenceFingerprint && source.excerpt.chapterLength == d.referenceChapterLength &&
                run { verifyOffMain(d.text, source); true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) updateDraft(key, run) { if (it.text == d.text && it.referenceAnalysisId == id) it.copy(referenceValidated = true) else it }
    }

    /**
     * 照范本写的核验（有没有成段抄范本）：要把范本按窗口建集合、把正文逐位置比一遍，一章几万个小字符串，
     * 逐拍写时每拍对累计的全文再做一遍。不放在主线程上。
     */
    private suspend fun verifyOffMain(prose: String, reference: ReferenceWritingMaterial) =
        withContext(Dispatchers.Default) { ReferenceWriting.verifyProse(prose, reference) }

    /** 本机收住用的计数器（[cap] 为空不收）：服务商、客户端追加的说明不算正文。 */
    private fun cutoff(cap: Int?): ProseCutoff? = cap?.takeIf { it > 0 }?.let { ProseCutoff(it, CUT_IGNORE) }

    /** 收住以后给作者看的一句：“这一拍写长了，在 1300 字处收住了”。 */
    private fun cutNote(what: String, words: Int) = "${what}写长了，在 $words 字处收住了"

    /**
     * 写正文用的全章篇幅：本章任务里有就用它（项目包、作者的说明、细纲、检查规则都在里面找过），没有才只看检查规则。
     * 以前只有项目章用本章任务的篇幅，普通作品把“每章3000-4000字”写在说明里时，拆节拍和编辑器里写都拿不到全章目标。
     */
    private suspend fun chapterLengthOf(workId: String, task: ChapterWritingTask?): String =
        task?.targetLength?.takeIf { it.isNotBlank() && (task.project || ProseBudget.parse(it) != null) }
            ?: withContext(Dispatchers.IO) { lengthOf(workId) }

    /** 范本的要求（接在写法要求前面）；没有范本为空。 */
    private fun referenceText(reference: ReferenceWritingMaterial?): String = reference?.let { ReferenceWriting.prompt(it) }.orEmpty()

    /** 逐拍写某一拍的任务。 */
    private fun beatTask(kit: ProseKit, title: String, beat: BeatTask, instruction: String) = ProseTask(
        kit.chapter, title, kit.card, ProseScope.BEAT, beat = beat.beat.index, length = WritePrompts.beatLength(beat),
        previousHook = kit.previousHook, instruction = instruction, seeds = kit.seeds,
    )

    /**
     * 整章写（[written] 为空，从头写）或接着已写的写完（写到本章结尾，篇幅是剩下的）。这是候选稿：写到第几拍只按候选稿估，
     * 不看细纲卡的“已写”——那是作者在编辑器正文里勾的进度，和这份候选稿无关。
     */
    private fun chapterTask(kit: ProseKit, title: String, written: String, instruction: String) = ProseTask(
        kit.chapter, title, kit.card.copy(beatsDone = emptyList()), if (written.isBlank()) ProseScope.CHAPTER else ProseScope.TO_END,
        beat = if (written.isBlank()) null
            else beats(kit.card).let { list -> Beats.covered(list, written).takeIf { it > 0 }?.let { minOf(it + 1, list.size + 1) } },
        length = if (written.isBlank()) WritePrompts.chapterLength(kit.length) else WritePrompts.remaining(kit.length, written),
        previousHook = kit.previousHook, instruction = instruction, seeds = kit.seeds,
    )

    // ---------- 文风范例 ----------

    /**
     * 写 [chapterId] 时带的文风范例（[chapterId] 为空时按“写下一章”挑，设置页预览用）。本机模型只带三分之一。
     * 挑选结果按来源章（id、字数、修改时间、状态）和设置缓存在作品目录里：定稿章没变就不重读正文、不重挑。
     * [scene] 为真时（写正文）避开和这一章细纲、节拍高度重合的段，也不挑上一章结尾里已经带了的段。
     */
    suspend fun stylePick(workId: String, chapterId: String?, local: Boolean = localModel, scene: Boolean = false): SamplePick {
        val assist = assistOf(workId)
        val limit = if (local) StyleSamples.localLimit(assist.sampleLimit) else assist.sampleLimit
        if (limit <= 0) return SamplePick(emptyList())
        return withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: return@withContext SamplePick(emptyList())
            val chapters = work.chapters
            val before = chapterId?.let { id -> chapters.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            val options = assist.sampleOptions.copy(limit = limit).let { o ->
                if (!scene || chapterId == null || before == null) o
                else o.copy(
                    avoid = store.plannedCard(workId, chapterId).card.let { listOf(it.facts, it.beats).joinToString("\n") },
                    shown = chapters.getOrNull(before - 1)?.let { WritePrompts.ending(store.text(workId, it.id)) }.orEmpty(),
                )
            }
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

    /** 接在请求最后的文风范例；没有、挑的时候出错（读不出正文）都不带，不耽误请求。 */
    private suspend fun samplesFor(workId: String, chapterId: String, scene: Boolean = false): String? = try {
        StyleSamples.render(stylePick(workId, chapterId, scene = scene).samples)
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

    /** 整章 V4 思考与正文共用输出上限；编辑器里写一段按 [inlineOutputTokenLimit]。 */
    private val draftTokens: Int?
        get() = draftOutputTokenLimit(ai.currentConfig)

    /** 记一条写作 AI 的结果（灵感、点评、写本章）。 */
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
    fun startReview(workId: String, chapterId: String, text: String): Job {
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
        val job = scope.launch {
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
                                val taskSnapshot = writingTask(workId, chapterId)
                                val (pack, previous, project) = withContext(Dispatchers.IO) {
                                    val i = work.chapters.indexOfFirst { it.id == chapterId }
                                    Triple(chapterContext(workId, chapterId, text, task = taskSnapshot),
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
                                CheckRules.parseDocs(store.pack(workId).filter { it.kind == PackKind.RULES }.map { d -> d.title to store.packText(workId, d.id) })
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
        reviewJobs[key] = job
        return job
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
        return ask("章节摘要", workId, WritePrompts.system(work.title, assistOf(workId)), WritePrompts.summary(text, title), whole = true, noThinking = true).trim()
    }

    /**
     * AI 拟这一章的细纲卡：带上本章资料（人物、前情、账本、伏笔，前几章的细纲或摘要，设定包里写这一章的原文）、
     * 上一章结尾。结果交给作者看过再用（细纲卡页），或由一键写本章存成“AI 拟的”等作者确认。
     *
     * 防剧透：只用本章和以前的。不带下一章的细纲、后面章节的细纲和全书结局（[chapterHints] 的 `noAhead`），
     * 不放整份设定包、不给查阅工具（整份设定包、read_doc 都能读到后面章节的细纲）。求稳：不带写正文的温度和思考开关。
     */
    suspend fun planCard(workId: String, chapterId: String): ChapterCard {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val (system, prompt) = withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
            val i = work.chapters.indexOfFirst { it.id == chapterId }
            val previous = work.chapters.getOrNull(i - 1)?.let { store.text(workId, it.id).takeLast(1_500) }.orEmpty()
            val text = store.text(workId, chapterId)
            val pack = if (canUsePack) store.chapterHints(workId, chapterId, text, upTo = true, noAhead = true)
                else store.brief(workId, chapterId, text, if (localModel) LOCAL_BRIEF_LIMIT else null, prose = true)?.render()
            WritePrompts.system(work.title, assistOf(workId), pack) to WritePrompts.plan(chapter.title, previous, null, null, text)
        }
        val meter = AiMeter(AiFeature.PLAN, workId)
        val answer = askWithPack("拟细纲", workId, system, prompt, whole = true, useTools = false, toolsUpTo = chapterId, lean = true, meter = meter)
        val card = WritePrompts.parsePlan(answer)
        if (card.outlineEmpty) throw IllegalStateException("AI 没按格式回，换个模型或再试一次")
        log(workId, chapterId, "拟细纲", "", answer.trim(), meter)
        return card
    }

    /**
     * AI 拆节拍：按 [card]（细纲卡页上现在的，可能还没存）的其他几项、本章资料和本章目标篇幅，拆成 3 到 8 拍。
     * 返回一行一拍的文字，交给作者改过再存，不直接存。请求方式和记账照拟细纲。
     * [compiled] 是调用方刚编好的本章任务（一键写本章时）：有就拿它的篇幅，不再另编一次。
     */
    suspend fun splitBeats(workId: String, chapterId: String, card: ChapterCard, compiled: ChapterWritingTask? = null): String {
        if (!ready) throw IllegalStateException("先在“我的 → AI 助手”里开启并填好服务商和密钥")
        val task = compiled ?: writingTask(workId, chapterId)
        var chapterLength = ""
        val (system, prompt) = withContext(Dispatchers.IO) {
            val work = store.get(workId) ?: throw IllegalStateException("作品不在了")
            val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
            val text = store.text(workId, chapterId)
            val length = chapterLengthOf(workId, task)
            val pack = if (canUsePack) store.chapterHints(workId, chapterId, text) else store.brief(workId, chapterId, text, if (localModel) LOCAL_BRIEF_LIMIT else null)?.render()
            Triple(WritePrompts.system(work.title, assistOf(workId), pack), WritePrompts.beatsSplit(chapter.title, card, length, text), length)
        }.let { (system, prompt, length) -> chapterLength = length; system to prompt }
        val meter = AiMeter(AiFeature.PLAN, workId)
        val answer = askWithPack("拆节拍", workId, system, prompt, whole = true, meter = meter)
        // AI 分的各拍篇幅加起来超过全章的，按比例压回去（真机上 8 拍各 1100 字，加起来八千多）。
        val beats = WritePrompts.parseBeats(answer, chapterLength)
        if (beats.isBlank()) throw IllegalStateException("AI 没按格式回，换个模型或再试一次")
        log(workId, chapterId, "拆节拍", "", answer.trim(), meter)
        return beats
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

    // ---------- AI 写整章草稿 ----------

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
    /** 这一次（run）的修订稿已经写盘：之后停止或复审失败都保留它。 */
    private val draftRevisionSaved = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Int>()

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
                    candidate?.let { state -> state.initialText?.let { DraftRevisionSnapshot(it, state.alternateText) } },
                    // 进度只跟着它自己那份正文走：存的不是界面上这一版时不带进度。
                    beats = candidate?.takeIf { it.text == text }?.beats)
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
            initialText = disk.revision?.initialText, alternateText = disk.revision?.alternateText, beats = disk.beats,
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
        beats = before?.beats,
    )

    /**
     * 本次失败或停止，用基线换回上一版：连同它自己的审读、阶段和错误一起恢复（它们仍绑定旧稿的哈希），
     * 本次的错误只记在 [DraftState.notice]，不挡采纳。作者刚填的要求保留，只用于下一次写。
     */
    private fun DraftState.restoredFrom(old: DraftState, notice: String): DraftState = old.copy(
        loading = false, instruction = instruction, targetLength = targetLength, workflowChoice = workflowChoice,
        // 写盘失败时磁盘内容待核，仍要挡住采纳。
        error = error?.takeIf { it.contains("保存失败") } ?: old.error,
        notice = notice,
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
                // 只要知道是不是项目包：看设定包就够了，不为这个编一整份任务（打开草稿页时本来就要编一次）。
                val required = saved.text.isNotBlank() && (chapterId in assist.draftWorkflows ||
                    store.get(workId)?.chapter(chapterId) != null && store.isProject(workId))
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
                referenceProvenanceBlocked = saved.blocked,
                beats = saved.beats)) }
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
        // 只要出场人物：不再为它另挑一整份本章资料（人物表和写正文那份资料共用设定包这一版的缓存）。
        val present = store.briefPresent(workId, chapterId, text)
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
     * 逐拍写：从第 [from] 拍（从 1 数）写到最后一拍，一拍一次请求——第一拍用 [first]，后面几拍用 [more]
     * 带上已写的最后一部分接着写。每拍写完，候选稿连同“写到第几拍”在同一次保存里落盘，再通过 [onCommitted] 告诉调用方；
     * 调用方停下、出错时退回最后一次落盘的全文，写了一半的那一拍不留。返回写好的全文。
     *
     * 本机收住（[ProseCutoff]）：一拍写出来超过这一拍上限的 1.3 倍，在句末收住、断开请求，这一拍照常算写完，接着写下一拍；
     * 全章累计超过全章上限（[length]）的 1.3 倍，也在句末收住，后面的拍不再写。收住的说明写进草稿的提示，也交给 [onNote]。
     */
    private suspend fun writeBeats(
        key: Pair<String, String>,
        run: Int,
        plan: List<Beat>,
        from: Int,
        start: String,
        length: String,
        workflow: Boolean,
        reference: ReferenceWritingMaterial?,
        referenceAnalysisId: String?,
        first: (BeatTask) -> String,
        more: (BeatTask, String) -> String,
        request: suspend (first: Boolean, prompt: String, onReset: () -> Unit, onText: (String) -> Unit) -> String,
        onCommitted: (String, DraftBeats) -> Unit,
        onNote: (String) -> Unit = {},
    ): String {
        var committed = start
        val chapterCap = ProseBudget.parse(length)?.cutoff
        val notes = mutableListOf<String>()
        for (n in from..plan.size) {
            val beat = Beats.task(plan, n, length)
            val base = committed
            val prefix = if (base.isBlank()) "" else base.trimEnd() + "\n"
            updateDraft(key, run) { it.copy(beatWriting = n, beatsText = base) }
            val prompt = withContext(Dispatchers.Default) { if (prefix.isEmpty()) first(beat) else more(beat, prefix) }
            if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, key.first, key.second, reference) }
            // 边收边清理（只看没写完的那一行），界面最多每 [STREAM_REFRESH_MS] 毫秒刷新一次。
            val stream = WritePrompts.DraftStream()
            val tick = StreamTick()
            val beatCap = WritePrompts.beatBudget(beat)?.cutoff
            val chapterLeft = chapterCap?.let { (it - WordCount.of(base)).coerceAtLeast(1) }
            val cap = listOfNotNull(beatCap, chapterLeft).minOrNull()
            val cut = cutoff(cap)
            var capped = false
            val answer = try {
                request(prefix.isEmpty(), prompt, {
                    stream.reset()
                    cut?.reset()
                    tick.reset()
                    updateDraft(key, run) { it.copy(text = base) }
                }) { piece ->
                    cut?.feed(piece)
                    stream.append(piece)
                    if (stream.hasText && tick.due()) {
                        val partial = stream.text()
                        updateDraft(key, run) { it.copy(text = prefix + partial) }
                    }
                }
            } catch (e: ProseCut) {
                capped = true
                e.text
            }
            val cleaned = WritePrompts.cleanDraft(answer)
            check(cleaned.isNotBlank()) { "第 $n 拍：" + emptyProseMessage(truncated = false, suggestThinkingOff = false) }
            val text = prefix + cleaned
            // 全章累计到了全章上限的 1.3 倍：这一拍收在句末，后面的拍不再写。
            val chapterFull = capped && chapterLeft != null && (beatCap == null || chapterLeft <= beatCap)
            if (capped) notes += if (chapterFull && n < plan.size)
                "全章写到 ${WordCount.of(text)} 字，超出全章上限太多，在第 $n 拍的句末收住了；第 ${n + 1}—${plan.size} 拍没有再写，可以先压一压篇幅，或点“接着写”写完"
                else cutNote("第 $n 拍", WordCount.of(cleaned))
            if (reference != null) {
                verifyOffMain(text, reference)
                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, key.first, key.second, reference) }
            }
            val progress = DraftBeats(n, plan.size)
            updateDraft(key, run) { it.copy(text = text, beats = progress, beatsText = text, workflow = workflow,
                referenceAnalysisId = referenceAnalysisId,
                referenceFingerprint = reference?.excerpt?.fingerprint,
                referenceChapterLength = reference?.excerpt?.chapterLength,
                referenceValidated = reference != null, referenceProvenanceBlocked = false,
                initialText = null, alternateText = null, revisionWarning = null, assessment = null) }
            persistDraft(key, run, text, workflow)
            // 新的几拍已经落盘，就是新的基线：之后停下、出错都留着它，不再换回上一版。
            forgetDraftBaseline(key, run)
            committed = text
            onCommitted(text, progress)
            if (notes.isNotEmpty()) {
                val note = notes.joinToString("；")
                updateDraft(key, run) { it.copy(notice = note) }
                onNote(note)
            }
            if (chapterFull) break
        }
        // 各拍写完：之后（项目包流程的审读、修订）停下不再退回逐拍写的稿子。
        updateDraft(key, run) { it.copy(beatWriting = null, beatsText = null) }
        return committed
    }

    /**
     * 一键写本章的写前准备：细纲卡没有“本章事实与结果”先让 AI 拟一份（[planCard]），没有节拍先拆（[splitBeats]），
     * 各自一拿到就存进细纲卡、标成 AI 拟的（[ChapterCard.aiOutline]、[ChapterCard.aiBeats]），作者已经写了的不动。
     * 中途停下或出错，存下的留着，下次不再拟；拟细纲那一步还没回来就停，什么都不存。
     *
     * 项目包里别的硬缺项（比如只有章组）拟细纲补不了：先拦住，不白花拟细纲的钱。拟完细纲卡变了，本章任务重编一次（单飞，
     * 见 [writingTask]）；只拆节拍不重编（节拍不进任务的指纹，写的时候现读细纲卡）。返回写正文用的本章任务。
     */
    private suspend fun prepareCard(key: Pair<String, String>, run: Int, compiled: ChapterWritingTask,
                                    instruction: String, targetLength: String): ChapterWritingTask {
        val (workId, chapterId) = key
        if (compiled.project) check(compiled.prepBlocking.isEmpty()) {
            compiled.prepBlocking.joinToString("；") + if (compiled.prepBlocking.any { "章组" in it }) "。" + ChapterWritingTask.GROUP_HINT else ""
        }
        var task = compiled
        var changed = false
        try {
            if (task.card.facts.isBlank()) {
                updateDraft(key, run) { it.copy(prep = DraftPrep.PLAN) }
                val planned = task.card
                val proposal = prepStep("拟细纲") { planCard(workId, chapterId) }
                withContext(draftDisk) {
                    store.updateCard(workId, chapterId) { stored -> if (stored.facts.isNotBlank()) stored else stored.withAiOutline(planned, proposal) }
                }
                changed = true
                forgetTasks(workId, chapterId)
                task = writingTask(workId, chapterId, instruction, targetLength) ?: error("作品或章节不在了")
            }
            if (beats(task.card).isEmpty()) {
                updateDraft(key, run) { it.copy(prep = DraftPrep.BEATS) }
                val split = prepStep("拆节拍") { splitBeats(workId, chapterId, task.card, task) }
                withContext(draftDisk) {
                    store.updateCard(workId, chapterId) { stored -> if (stored.beats.isNotBlank()) stored else stored.withBeats(split).copy(aiBeats = true) }
                }
                changed = true
            }
        } finally {
            // 补过细纲卡（哪怕后一步没成、停下了）：告诉草稿页换上新的细纲卡和任务。
            updateDraft(key, run) { it.copy(prep = null, prepared = if (changed) task else it.prepared) }
        }
        return task
    }

    /** 写前准备的一步出错：说清楚是哪一步没成（停下、程序的毛病照原样往外抛）。 */
    private suspend fun <T> prepStep(label: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (CrashLog.isBug(e)) throw e
        throw IllegalStateException("${label}没成：${e.message ?: "请求失败"}", e)
    }

    /**
     * 细纲卡刚被一键写本章改过：这一章还在编、编好了还没人取走的旧任务不再给后来的人用（它们按旧卡片编的）。
     * 正在等的照样拿到它们那一份；后来的另起一次。
     */
    private fun forgetTasks(workId: String, chapterId: String) {
        synchronized(taskCompiles) { taskCompiles.keys.removeAll { it[0] == workId && it[1] == chapterId } }
    }

    /** 草稿页取走了 [DraftState.prepared]（刷新了细纲卡和任务）：清掉，下次打开不再拿这份旧的。 */
    fun preparedSeen(workId: String, chapterId: String) {
        _drafts.update { m -> m[workId to chapterId]?.takeIf { it.prepared != null }?.let { m + ((workId to chapterId) to it.copy(prepared = null)) } ?: m }
    }

    /** 逐拍写停下、出错后的提示：写到第几拍、接着写从哪一拍开始；各拍都写完了就只说前半句。 */
    private fun beatNotice(head: String, progress: DraftBeats): String =
        if (progress.written >= progress.total) head
        else "$head：已写完第 ${progress.written} / ${progress.total} 拍，接着写从第 ${progress.written + 1} 拍开始"

    /**
     * AI 写整章草稿：[more] 为真时接着已有的草稿往下写，否则从头写一版（替换草稿）。边写边显示，写完存成草稿，
     * 作者看过再放进正文。
     */
    fun writeDraft(workId: String, chapterId: String, instruction: String = "", more: Boolean = false, workflow: Boolean = false, targetLength: String = "", referenceAnalysisId: String? = null,
                   /** 一键写本章（草稿页点“写本章”“重写一版”）：作品开着 [WorkAssist.draftPrep] 时，缺细纲先拟、缺节拍先拆，见 [prepareCard]。 */
                   prepare: Boolean = false) {
        if (workflow) {
            writeProjectDraft(workId, chapterId, instruction, targetLength, more = more, referenceAnalysisId = referenceAnalysisId, prepare = prepare)
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
            workflowChoice = workflow, notice = null, beatWriting = null, beatsText = null, prep = null, prepared = null,
            referenceIssue = null)) }
        // 边收边清理（只看没写完的那一行）：停下时留下的也从这里拿。
        val written = WritePrompts.DraftStream()
        var newCandidateGenerated = false
        // 逐拍写时：已经写完存好的全文和进度。停下、出错时保留它，写了一半的那一拍不留。
        var beatsCommitted: Pair<String, DraftBeats>? = null
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
                val continuing = more && previousCandidate.isNotBlank() && before?.referenceAnalysisId == referenceAnalysisId
                val reference = referenceAnalysisId?.let { loadForCandidate(workId, chapterId, it, continuing) }
                if (continuing && reference != null) checkCandidateSource(requireNotNull(before), reference)
                val taskSnapshot = writingTask(workId, chapterId, instruction, targetLength).let { compiled ->
                    if (prepare && !more && compiled != null && assistOf(workId).draftPrep) prepareCard(key, run, compiled, instruction, targetLength) else compiled
                }
                if (taskSnapshot?.project == true) check(taskSnapshot.blocking.isEmpty()) { taskSnapshot.blocking.joinToString("；") }
                val body = withContext(Dispatchers.IO) { store.text(workId, chapterId) }
                val length = taskSnapshot?.targetLength ?: targetLength.ifBlank { withContext(Dispatchers.IO) { lengthOf(workId) } }
                val kit = proseKit(workId, chapterId, body, taskSnapshot, length, reference != null)
                val assist = assistOf(workId)
                val refText = referenceText(reference)
                // 接着写要扫一遍已写的（估写到第几拍、数字数）：不放在主线程上算。
                val prompt = withContext(Dispatchers.Default) {
                    if (prefix.isEmpty()) WritePrompts.draft(kit.previous, chapterTask(kit, chapter.title, "", instruction), kit.tail, refText)
                    else WritePrompts.draftMore(prefix, chapterTask(kit, chapter.title, prefix, instruction), kit.tail, refText)
                }
                val system = kit.system
                if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                // 本章细纲卡有节拍：逐拍请求，每拍写完连同进度存一次。没有节拍时下面照原来整章写。
                val plan = withContext(Dispatchers.IO) { beats(store.plannedCard(workId, chapterId).card) }
                val beatFrom = Beats.resume(plan.size, more, previousCandidate, before?.beats)
                if (beatFrom != null) {
                    val text = writeBeats(
                        key, run, plan, beatFrom, start = if (beatFrom > 1) previousCandidate else "", length = length,
                        workflow = false, reference = reference, referenceAnalysisId = referenceAnalysisId,
                        first = { beat -> WritePrompts.draft(kit.previous, beatTask(kit, chapter.title, beat, instruction), kit.tail, refText) },
                        more = { beat, sofar -> WritePrompts.draftMore(sofar, beatTask(kit, chapter.title, beat, instruction), kit.tail, refText) },
                        request = { first, prompt, onReset, onText ->
                            askWithPack(
                                if (first) "写本章" else "接着写", workId, system, prompt, whole = true, maxTokens = draftTokens, meter = meter,
                                toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = taskSnapshot?.project == true,
                                requireTools = taskSnapshot?.project == true, prose = proseOf(assist), lean = true, stable = kit.stable,
                                onReset = onReset, onText = onText,
                            )
                        },
                        onCommitted = { committed, progress -> beatsCommitted = committed to progress },
                    )
                    newCandidateGenerated = true
                    updateDraft(key, run) { it.copy(text = text, loading = false, beatWriting = null, beatsText = null, error = null,
                        stage = DraftStage.IDLE, taskInstruction = null, taskTargetLength = null, usage = meter.usage, cost = meter.cost) }
                    forgetDraftBaseline(key, run)
                    log(workId, chapterId, if (beatFrom == 1) "写本章" else "接着写", "逐拍写第 $beatFrom—${plan.size} 拍" +
                        instruction.let { if (it.isBlank()) "" else "：$it" }, text.removePrefix(prefix), meter)
                    return@launch
                }
                val tick = StreamTick()
                // 本机收住：全章（连同已写的）超过全章上限的 1.3 倍，在句末收住。
                val cut = cutoff(ProseBudget.parse(length)?.cutoff?.let { (it - WordCount.of(prefix)).coerceAtLeast(1) })
                var capped = false
                val answer = try { askWithPack(
                    if (prefix.isEmpty()) "写本章" else "接着写", workId, system, prompt, whole = true, maxTokens = draftTokens, meter = meter,
                    toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = taskSnapshot?.project == true,
                    requireTools = taskSnapshot?.project == true, prose = proseOf(assist), lean = true, stable = kit.stable,
                    onReset = {
                        written.reset()
                        cut?.reset()
                        tick.reset()
                        newCandidateGenerated = false
                        updateDraft(key, run) { it.copy(text = previousCandidate,
                            workflow = before?.workflow == true).restoreDraftSource(before) }
                    },
                ) { piece ->
                    cut?.feed(piece)
                    written.append(piece)
                    val generated = written.hasText
                    // 有没有写出字变了马上换；别的时候最多每 [STREAM_REFRESH_MS] 毫秒刷新一次。
                    val changed = generated != newCandidateGenerated
                    newCandidateGenerated = generated
                    val partial = if (generated && (changed || tick.due())) written.text() else null
                    if (changed || partial != null) updateDraft(key, run) {
                        if (!generated) it.copy(text = previousCandidate,
                            workflow = before?.workflow == true).restoreDraftSource(before)
                        else it.copy(text = prefix + partial, workflow = false, referenceAnalysisId = referenceAnalysisId,
                            referenceFingerprint = reference?.excerpt?.fingerprint,
                            referenceChapterLength = reference?.excerpt?.chapterLength,
                            referenceValidated = false, referenceProvenanceBlocked = false,
                            initialText = null, alternateText = null, revisionWarning = null, beats = null,
                            // 旧稿的审读不挂在新文字下面。
                            assessment = null, stage = DraftStage.IDLE)
                    }
                } } catch (e: ProseCut) {
                    capped = true
                    e.text
                }
                val cleaned = WritePrompts.cleanDraft(answer)
                check(cleaned.isNotBlank()) { emptyProseMessage(truncated = false, suggestThinkingOff = false) }
                newCandidateGenerated = true
                val text = prefix + cleaned
                if (reference != null) {
                    verifyOffMain(text, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                updateDraft(key, run) { it.copy(text = text, loading = false, referenceAnalysisId = referenceAnalysisId,
                    workflow = false, error = null,
                    referenceFingerprint = reference?.excerpt?.fingerprint,
                    referenceChapterLength = reference?.excerpt?.chapterLength,
                    referenceValidated = reference != null, referenceProvenanceBlocked = false,
                    initialText = null, alternateText = null, revisionWarning = null, beats = null,
                    assessment = null, stage = DraftStage.IDLE, taskInstruction = null, taskTargetLength = null,
                    notice = if (capped) cutNote("这一章", WordCount.of(text)) else null,
                    usage = meter.usage, cost = meter.cost) }
                persistDraft(key, run, text, workflow = false)
                forgetDraftBaseline(key, run)
                log(workId, chapterId, if (prefix.isEmpty()) "写本章" else "接着写", instruction, text.removePrefix(prefix), meter)
            } catch (e: CancellationException) {
                val kept = beatsCommitted
                if (kept != null && draftRuns[key] == run) {
                    // 逐拍写已经存好了几拍：留着它们，写了一半的这一拍不要；“接着写”从下一拍开始。
                    updateDraft(key, run) { it.copy(text = kept.first, beats = kept.second, loading = false, notice = beatNotice("已停止", kept.second)) }
                    withContext(NonCancellable) { runCatching { persistDraft(key, run, kept.first, workflow = false) } }
                } else if (draftRuns[key] == run) {
                    // An incomplete replacement never displaces a previously complete candidate.
                    val cleaned = written.text()
                    val partial = if (cleaned.isNotBlank()) prefix + cleaned else previousCandidate
                    updateDraft(key, run) { state ->
                        val old = draftBaseline(key, run)
                        if (old != null) state.restoredFrom(old, "已停止，保留上一版")
                        else (if (cleaned.isNotBlank()) state.copy(text = partial, workflow = false)
                            else state.copy(text = partial, workflow = before?.workflow == true).restoreDraftSource(before))
                            .copy(loading = false)
                    }
                    if (_drafts.value[key]?.text?.isNotBlank() == true) withContext(NonCancellable) {
                        // 写了一半停下的那段照样核一遍范本，过了就能接着写、审读。
                        runCatching { settleReference(key, run) }
                        val kept = _drafts.value[key]
                        if (kept != null) runCatching { persistDraft(key, run, kept.text, workflow = kept.workflow) }
                    }
                }
                throw e
            } catch (e: RejectedReferenceWriting) {
                val kept = beatsCommitted
                if (kept != null) {
                    // 逐拍写到第几拍没过核验（或范本在写的途中变了）：前面各拍都核验过、早已存盘，留着；这一拍不要。
                    updateDraft(key, run) { it.copy(text = kept.first, beats = kept.second, loading = false, beatWriting = null, beatsText = null,
                        error = e.message, notice = beatNotice("第 ${kept.second.written + 1} 拍没写成", kept.second),
                        usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
                    _drafts.value[key]?.takeIf { draftRuns[key] == run }?.let { d ->
                        try { persistDraft(key, run, d.text, workflow = false) }
                        catch (saveFailure: Exception) { reconcileDraftAfterSaveFailure(key, run, d, "候选稿保存失败", saveFailure) }
                    }
                    return@launch
                }
                updateDraft(key, run) { (draftBaseline(key, run) ?: before ?: it).copy(
                    text = previousCandidate, loading = false, error = e.message, referenceIssue = e.issue,
                    usage = meter.usage.takeIf { usage -> !usage.isEmpty }, cost = meter.cost) }
                try { persistDraft(key, run, previousCandidate, workflow = before?.workflow == true) }
                catch (saveFailure: Exception) {
                    reconcileDraftAfterSaveFailure(key, run, before ?: DraftState(workId, chapterId, ""),
                        "旧候选稿保存失败", saveFailure)
                }
            } catch (e: Throwable) {
                val reason = failure(e)
                val kept = beatsCommitted
                if (kept != null) {
                    // 逐拍写到一半出错：已经写完的几拍留着（早已存盘），这一拍不要，“接着写”从这一拍重来。
                    updateDraft(key, run) { it.copy(text = kept.first, beats = kept.second, loading = false,
                        notice = beatNotice("第 ${kept.second.written + 1} 拍没写成：$reason", kept.second),
                        usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
                    _drafts.value[key]?.takeIf { draftRuns[key] == run }?.let { d ->
                        try { persistDraft(key, run, d.text, workflow = false) }
                        catch (saveFailure: Exception) { reconcileDraftAfterSaveFailure(key, run, d, "候选稿保存失败", saveFailure) }
                    }
                    return@launch
                }
                // 界面上的字最多晚一次刷新：已经写出来的以收到的为准。
                val partial = written.text()
                updateDraft(key, run) { state ->
                    val old = draftBaseline(key, run)
                    if (old != null) state.restoredFrom(old, "没写成：$reason；已保留上一版")
                    else state.copy(text = if (newCandidateGenerated && partial.isNotBlank()) prefix + partial else if (newCandidateGenerated) state.text else previousCandidate,
                        workflow = false, loading = false, error = reason,
                        initialText = null, alternateText = null,
                        // 没写成也照样花了钱（比如上限全用在思考上），显示这一次花了多少。
                        usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost)
                }
                // With no earlier candidate, a partial remains available for continuation.
                // 写了一半出错留下的那段照样核一遍范本，过了就能接着写、审读。
                settleReference(key, run)
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

    /** 正在编的本章任务，按参数记：参数一样的一起等这一次，没人等了就停掉。 */
    private class TaskCompile(val job: Deferred<ChapterWritingTask?>) { var waiters = 0 }
    private val taskCompiles = HashMap<List<String>, TaskCompile>()

    /**
     * 编本章任务的协程：同一时间只编一份（编一次要把设定包过一遍，几份叠在一起就是真机上内存溢出的原因），
     * 一份出错不连累别的。
     */
    private val compileScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO.limitedParallelism(1) +
        CoroutineExceptionHandler { _, e -> CrashLog.caught(e) })

    /**
     * Build the same explicit task that the next workflow request will use. Never calls an AI.
     *
     * 单飞：参数相同、正在编的，等那一次的结果；等的人都走了（比如草稿页改了要求，旧的那次协程被取消），这次编译就停下。
     * 编的时候内存不够、栈溢出这类 Error 换成 [TaskCompileError]（普通异常，带一句作者能照着做的话），各处照常接住，不闪退。
     */
    suspend fun writingTask(workId: String, chapterId: String, instruction: String = "", targetLength: String = ""): ChapterWritingTask? {
        val key = listOf(workId, chapterId, instruction, targetLength)
        val compile = synchronized(taskCompiles) {
            taskCompiles.getOrPut(key) {
                TaskCompile(compileScope.async(start = CoroutineStart.LAZY) { compileTask(workId, chapterId, instruction, targetLength) })
            }.also { it.waiters++ }
        }
        compile.job.start()
        try {
            return compile.job.await()
        } finally {
            synchronized(taskCompiles) {
                if (--compile.waiters == 0) {
                    if (taskCompiles[key] === compile) taskCompiles.remove(key)
                    compile.job.cancel()
                }
            }
        }
    }

    /** 真正编本章任务的那一步（参数：作品、章、要求、篇幅、检查点）。测试换成数次数的、会抛内存不够的。 */
    internal var compiler: (String, String, String, String, () -> Unit) -> ChapterWritingTask? =
        { workId, chapterId, instruction, targetLength, checkpoint -> store.writingTask(workId, chapterId, instruction, targetLength, checkpoint) }

    private suspend fun compileTask(workId: String, chapterId: String, instruction: String, targetLength: String): ChapterWritingTask? {
        val job = currentCoroutineContext().job
        return try {
            compiler(workId, chapterId, instruction, targetLength) { job.ensureActive() }
        } catch (e: OutOfMemoryError) {
            CrashLog.caught(e)
            throw TaskCompileError(TASK_TOO_LARGE, e)
        } catch (e: Error) {
            CrashLog.caught(e)
            throw TaskCompileError("本章任务没编成（${e.javaClass.simpleName}），详情记在“我的 → 闪退记录”里", e)
        }
    }

    /** Estimate the actual first chapter-draft request locally; no model request is sent. */
    suspend fun previewDraftRequest(
        workId: String,
        chapterId: String,
        instruction: String,
        targetLength: String,
        workflow: Boolean,
        referenceAnalysisId: String? = null,
        /** 草稿页已经按同样的要求、篇幅编好的任务：有就直接用，不再编一次。 */
        compiled: ChapterWritingTask? = null,
    ): AiRequestBudgetPreview? {
        if (!ready || localModel) return null
        val task = compiled ?: writingTask(workId, chapterId, instruction, targetLength) ?: return null
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: return null
        val chapter = work.chapter(chapterId) ?: return null
        val assist = assistOf(workId)
        val reference = referenceAnalysisId?.let { id ->
            withContext(Dispatchers.IO) { ReferenceWriting.load(store, workId, chapterId, id) }
        }
        // 和真发的第一个请求一样：项目包流程只认定稿前文；有节拍时第一个请求是第一拍。
        val body = if (workflow) "" else withContext(Dispatchers.IO) { store.text(workId, chapterId) }
        val kit = proseKit(workId, chapterId, body, task, task.targetLength, reference != null)
        val plan = beats(kit.card)
        val prose = if (plan.isEmpty()) chapterTask(kit, chapter.title, "", instruction)
            else beatTask(kit, chapter.title, Beats.task(plan, 1, kit.length), instruction)
        val fullPrompt = WritePrompts.draft(kit.previous, prose, kit.tail, referenceText(reference))
        val system = kit.system + "\n\n" + WritePrompts.proseToolGuide(proseCatalog(workId))
        // 一键写本章（开着 draftPrep）：缺细纲先拟、缺节拍先拆，各多一次小请求；拆出来几拍就逐拍写几次。
        val planning = assist.draftPrep && kit.card.facts.isBlank()
        val split = assist.draftPrep && plan.isEmpty()
        return withContext(Dispatchers.Default) {
            AiRequestBudget.preview(ai.currentConfig, system, fullPrompt, PackTools.TOOLS, draftTokens, workflow,
                cachePrefix = stableLength(system, kit.stable), prose = proseOf(assist),
                beats = plan.size, splitBeats = split, prepRequests = listOf(planning, split).count { it })
        }
    }

    /** A restored or interrupted candidate has no valid pass; run the actual review on it again. */
    fun reviewDraft(workId: String, chapterId: String, instruction: String = "", targetLength: String = "") =
        writeProjectDraft(workId, chapterId, instruction, targetLength, reviewOnly = true,
            referenceAnalysisId = _drafts.value[workId to chapterId]?.referenceAnalysisId)

    /**
     * “重新核验范本”：已写的这一稿“已核验”的标记丢了（改过、压过篇幅、写到一半停下），范本没变时重跑一遍核验，
     * 过了就恢复（随正文和范本指纹一起落盘），可以接着写、审读。范本变了或这一稿和范本太像，照实说，标出原因。
     */
    fun recheckDraftReference(workId: String, chapterId: String) {
        val key = workId to chapterId
        val d = _drafts.value[key]?.takeIf { !it.loading && it.text.isNotBlank() && !it.referenceProvenanceBlocked } ?: return
        val id = d.referenceAnalysisId ?: return
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to d.copy(loading = true, notice = null)) }
        draftJobs[key] = scope.launch {
            try {
                val source = loadForCandidate(workId, chapterId, id, candidate = true)
                checkCandidateSource(d.copy(referenceValidated = true), source)
                updateDraft(key, run) { it.copy(loading = false, referenceValidated = true, referenceIssue = null,
                    error = it.error.takeIf { _ -> it.referenceIssue == null && d.referenceIssue == null },
                    stage = if (it.stage == DraftStage.ERROR) DraftStage.IDLE else it.stage,
                    notice = "范本核验通过：这一稿没有照抄范本，可以接着写、审读") }
                persistDraft(key, run, d.text, d.workflow)
            } catch (e: CancellationException) {
                if (draftRuns[key] == run) updateDraft(key, run) { it.copy(loading = false) }
                throw e
            } catch (e: RejectedReferenceWriting) {
                updateDraft(key, run) { it.copy(loading = false, error = e.message, referenceIssue = e.issue ?: DraftReferenceIssue.CHANGED) }
            } catch (e: Exception) {
                updateDraft(key, run) { it.copy(loading = false, error = "范本核验没做成：${e.message ?: "读写出错"}") }
            }
        }
    }

    /**
     * “不用范本，直接审读这一稿”：去掉这一稿和范本的关联，正文不动，然后审读。查重防抄不能因此松掉：
     * 先拿范本现在的原文（[ReferenceWriting.current]）对已写正文跑一遍核验，通过了才去掉；和范本太像就说清楚是哪一段，
     * 范本读不到就没法查，只能从头重写。去掉以后 [onDetached] 告诉草稿页（把下次写用的范本选择也清掉）。
     */
    fun reviewWithoutReference(workId: String, chapterId: String, instruction: String = "", targetLength: String = "",
                               onDetached: () -> Unit = {}) {
        val key = workId to chapterId
        val d = _drafts.value[key]?.takeIf { !it.loading && it.text.isNotBlank() && !it.referenceProvenanceBlocked } ?: return
        val id = d.referenceAnalysisId ?: return
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to d.copy(loading = true, notice = null)) }
        draftJobs[key] = scope.launch {
            try {
                val source = withContext(Dispatchers.IO) { ReferenceWriting.current(store, workId, chapterId, id) }
                    ?: throw RejectedReferenceWriting("范本读不到了（删了或改坏了），没法查这一稿有没有照抄范本，不能去掉范本直接审读；只能从头重写",
                        DraftReferenceIssue.CHANGED)
                withContext(Dispatchers.Default) { ReferenceWriting.verifyDraft(d.text, source) }
                updateDraft(key, run) { it.copy(loading = false, referenceAnalysisId = null, referenceFingerprint = null,
                    referenceChapterLength = null, referenceValidated = false, referenceIssue = null, error = null,
                    stage = if (it.stage == DraftStage.ERROR) DraftStage.IDLE else it.stage,
                    notice = "已去掉这一稿和范本的关联（查过没有照抄范本），正文没动") }
                persistDraft(key, run, d.text, d.workflow)
                if (draftRuns[key] != run) return@launch
                onDetached()
                draftJobs.remove(key)
                writeProjectDraft(workId, chapterId, instruction, targetLength, reviewOnly = true, referenceAnalysisId = null)
            } catch (e: CancellationException) {
                if (draftRuns[key] == run) updateDraft(key, run) { it.copy(loading = false) }
                throw e
            } catch (e: RejectedReferenceWriting) {
                updateDraft(key, run) { it.copy(loading = false, error = e.message, referenceIssue = e.issue ?: DraftReferenceIssue.CHANGED) }
            } catch (e: Exception) {
                updateDraft(key, run) { it.copy(loading = false, error = "没去掉范本：${e.message ?: "读写出错"}") }
            }
        }
    }

    /**
     * An author edit creates a new candidate version and invalidates every earlier assessment.
     * 留底照旧：[DraftState.initialText] 仍是上一次修订前的那一版，作者可以拿它换回（手改稿进对照位）；
     * 已换回初稿再手改时，[DraftState.alternateText] 里的修订稿只作对照。再次“审读并修订”前会把手改稿重新留底。
     */
    fun updateDraftText(workId: String, chapterId: String, text: String) {
        if (text.isBlank()) { message("候选稿不能为空"); return }
        val key = workId to chapterId
        val d = _drafts.value[key] ?: return
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to d.copy(text = text, loading = false, assessment = null, stage = DraftStage.IDLE,
            error = if (d.referenceProvenanceBlocked) d.error else null, referenceValidated = false, notice = null)) }
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
            referenceValidated = false, notice = null,
            alternateText = if (first) before.text else null)
        _drafts.update { it + (key to next) }
        scope.launch {
            try { withContext(NonCancellable) { persistDraft(key, run, target, next.workflow) } }
            catch (e: Exception) { reconcileDraftAfterSaveFailure(key, run, before, "切换草稿版本失败", e) }
        }
    }

    /**
     * 判断这一版审读用的任务：按审读它时的要求和篇幅重算（资料取当前的），不用界面上新改、只给下一次写的要求。
     * 没审读过的稿子按它保存的要求算。
     */
    suspend fun reviewTask(workId: String, chapterId: String): ChapterWritingTask? {
        val d = _drafts.value[workId to chapterId] ?: return null
        return writingTask(workId, chapterId, d.taskInstruction ?: d.instruction, d.taskTargetLength ?: d.targetLength)
    }

    /** Validate the manuscript and material versions immediately before the author adopts a candidate. */
    suspend fun draftBlocked(workId: String, chapterId: String): String? {
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
        val task = reviewTask(workId, chapterId) ?: return "作品或章节不在了"
        // 项目章一律要审读才能采纳，和重开后 loadDraft 的判定一致；“创作后审读、修订”开关只决定写完要不要自动走流程。
        if (!d.workflow && !task.project) return null
        if (task.blocking.isNotEmpty()) return task.blocking.joinToString("；")
        val report = d.assessment ?: return "本稿尚未完成审读，请先审读此稿"
        if (!report.matches(d.text, task.fingerprint)) return "正文或项目资料已经变化，旧审读结果已过时，请重新审读"
        return if (report.passed && d.stage == DraftStage.READY) null else "本稿仍有必改问题或资料缺项，请核对后重新审读"
    }

    private fun writeProjectDraft(workId: String, chapterId: String, instruction: String, targetLength: String, more: Boolean = false, reviewOnly: Boolean = false, referenceAnalysisId: String? = null,
                                  prepare: Boolean = false) {
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
            assessment = null, instruction = instruction, targetLength = targetLength, workflowChoice = true, notice = null,
            // The old bytes retain their old source until new prose actually arrives.
            referenceAnalysisId = before?.referenceAnalysisId,
            referenceFingerprint = before?.referenceFingerprint,
            referenceChapterLength = before?.referenceChapterLength,
            referenceValidated = before?.referenceValidated == true,
            referenceProvenanceBlocked = before?.referenceProvenanceBlocked == true,
            beatWriting = null, beatsText = null, prep = null, prepared = null, referenceIssue = null,
        )) }
        val createMeter = AiMeter(AiFeature.DRAFT, workId)
        val reviewMeter = AiMeter(AiFeature.REVIEW, workId)
        val reviseMeter = AiMeter(AiFeature.EDIT, workId)
        val meters = listOf(createMeter, reviewMeter, reviseMeter)
        var newCandidateGenerated = false
        var freshCandidateCommitted = false
        var firstCandidateSaved = false
        // 正在修订的那一版；复审轮看到不同的稿子，说明修订稿已经出来了。
        var revising: String? = null
        var revisionSaved = false
        // 逐拍写时：正在逐拍写（还没进审读），以及已经写完存好的全文和进度。
        var beatsInProgress = false
        // 创作时本机收住了（写长了）：写完给作者看的一句。
        var lengthNote: String? = null
        var beatsCommitted: Pair<String, DraftBeats>? = null
        draftJobs[key] = scope.launch {
            try {
                withContext(draftDisk) {
                    if (draftRuns[key] == run) store.setDraftIntent(workId, chapterId, instruction, targetLength, true)
                }
                if ((reviewOnly || more) && before?.referenceProvenanceBlocked == true)
                    throw RejectedReferenceWriting(before.error ?: "旧草稿来源无法核验，请从头重新生成")
                if ((reviewOnly || more) && previousCandidate.isNotBlank() && before?.referenceAnalysisId != referenceAnalysisId)
                    throw RejectedReferenceWriting("本稿须沿用原候选稿的范本选择；切换范本请从头重写")
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: error("作品不在了")
                check(work.chapter(chapterId)?.locked != true) { "这一章已定稿：要改先解锁并登记原因" }
                val task = writingTask(workId, chapterId, instruction, targetLength).let { compiled ->
                    if (prepare && !more && !reviewOnly && compiled != null && assistOf(workId).draftPrep) prepareCard(key, run, compiled, instruction, targetLength) else compiled
                } ?: error("作品或章节不在了")
                val reviewEvidence = withContext(Dispatchers.IO) { store.reviewEvidence(workId, chapterId, task) }
                updateDraft(key, run) { it.copy(task = task, taskInstruction = instruction, taskTargetLength = targetLength) }
                check(task.blocking.isEmpty()) { task.blocking.joinToString("；") }
                // 接着写、审读已写的稿子：对已写稿只要求范本核验这一关（见 [checkCandidateSource]），没过时给出路。
                val continuing = (more || reviewOnly) && previousCandidate.isNotBlank() && before?.referenceAnalysisId == referenceAnalysisId
                val reference = referenceAnalysisId?.let { loadForCandidate(workId, chapterId, it, continuing) }
                if (continuing && reference != null) checkCandidateSource(requireNotNull(before), reference)
                val rules = withContext(Dispatchers.IO) {
                    CheckRules.parseDocs(store.pack(workId).filter { it.kind == PackKind.RULES }.map { d -> d.title to store.packText(workId, d.id) })
                }
                val proseRules = withContext(Dispatchers.IO) { writingRules(workId, chapterId, "") }
                val assist = assistOf(workId)
                // 创作和修订用写正文的系统提示、写法要求和任务卡：修订时也不能写回 AI 腔。审读照旧带完整的本章任务。
                val kit = proseKit(workId, chapterId, "", task, task.targetLength, reference != null)
                // 修订带作者的章节修订提示词，不带正文创作提示词（各阶段的模板不混用）。
                val reviseKit = proseKit(workId, chapterId, "", task, task.targetLength, reference != null, WritingPhase.REVISE)
                val voice = kit.tail
                val refText = referenceText(reference)
                val system = kit.system
                // 审读照写正文瘦身：作者的说明和审读提示词、本章任务（含本章资料、账本快照）只放一份，不再整份放知识库；
                // 其余资料列成带来源编号的清单，模型核对时用工具查（真机上大设定包审读一次发了 34 万字）。
                val reviewAuthor = withContext(Dispatchers.IO) { ProsePack.instructions(store.packSources(workId).raw, WritingPhase.REVIEW) }
                // 本章资料用写正文挑的那份（[ProseKit.brief]，不带后文），不再另带任务里按整份上限挑的那份。
                val reviewer = WritePrompts.reviewSystem(work.title, assist, reviewAuthor, task.copy(context = kit.brief?.render(waitingMax = ChapterBrief.WAITING).orEmpty()).render())
                val reviewStable = WritePrompts.reviewSystem(work.title, assist, reviewAuthor, "")
                val reviewGuide = WritePrompts.reviewToolGuide(withContext(Dispatchers.IO) { ProsePack.catalog(store.packSources(workId).raw, WritingPhase.REVIEW) })
                val saved = before?.text?.takeIf { it.isNotBlank() } ?: withContext(Dispatchers.IO) {
                    val disk = store.draftContent(workId, chapterId)
                    if ((reviewOnly || more) && disk.text.isNotBlank())
                        throw RejectedReferenceWriting("请先打开草稿，核对范本来源后再继续写或审读")
                    disk.text
                }
                check(!reviewOnly || saved.isNotBlank()) { "还没有可审读的草稿" }
                val prefix = if (more && saved.isNotBlank()) saved.trimEnd() + "\n" else ""
                // 边收边清理（只看没写完的那一行），界面最多每 [STREAM_REFRESH_MS] 毫秒刷新一次。
                val written = WritePrompts.DraftStream()
                val tick = StreamTick()
                // 本章细纲卡有节拍：创作阶段逐拍写，写完各拍再照原来审读、修订。没有节拍时照原来整章写。
                val plan = if (reviewOnly) emptyList() else withContext(Dispatchers.IO) { beats(store.plannedCard(workId, chapterId).card) }
                val beatFrom = Beats.resume(plan.size, more, saved, before?.beats)
                val result = DraftWorkflow.run(
                    task = task, rules = rules, evidence = reviewEvidence, initial = saved.takeIf { reviewOnly },
                    create = create@{
                        if (beatFrom != null) {
                            beatsInProgress = true
                            return@create writeBeats(
                                key, run, plan, beatFrom, start = if (beatFrom > 1) saved else "", length = task.targetLength,
                                workflow = true, reference = reference, referenceAnalysisId = referenceAnalysisId,
                                first = { beat -> WritePrompts.draft(kit.previous, beatTask(kit, task.title, beat, task.instruction), voice, refText) },
                                more = { beat, text -> WritePrompts.draftMore(text, beatTask(kit, task.title, beat, task.instruction), voice, refText) },
                                request = { _, prompt, onReset, onText ->
                                    askWithPack(
                                        "写本章", workId, system, prompt, whole = true, maxTokens = draftTokens,
                                        toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = true, requireTools = true,
                                        meter = createMeter, prose = proseOf(assist), lean = true, stable = kit.stable, onReset = onReset, onText = onText,
                                    )
                                },
                                onCommitted = { text, progress ->
                                    newCandidateGenerated = true
                                    beatsCommitted = text to progress
                                },
                                onNote = { lengthNote = it },
                            ).also { beatsInProgress = false }
                        }
                        val prompt = withContext(Dispatchers.Default) {
                            if (prefix.isBlank()) WritePrompts.draft(kit.previous, chapterTask(kit, task.title, "", task.instruction), voice, refText)
                            else WritePrompts.draftMore(prefix, chapterTask(kit, task.title, prefix, task.instruction), voice, refText)
                        }
                        if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        // 本机收住：全章（连同已写的）超过全章上限的 1.3 倍，在句末收住。
                        val cut = cutoff(ProseBudget.parse(task.targetLength)?.cutoff?.let { (it - WordCount.of(prefix)).coerceAtLeast(1) })
                        val answer = try { askWithPack(
                            "写本章", workId, system, prompt, whole = true, maxTokens = draftTokens,
                            toolsUpTo = chapterId, phase = WritingPhase.CREATE, confirmedOnly = true, requireTools = true,
                            // 创作、修订用作品设的温度和思考开关；审读求稳，不带。
                            meter = createMeter, prose = proseOf(assist), lean = true, stable = kit.stable,
                            onReset = {
                                written.reset()
                                cut?.reset()
                                tick.reset()
                                newCandidateGenerated = false
                                updateDraft(key, run) { it.copy(text = previousCandidate,
                                    workflow = before?.workflow == true).restoreDraftSource(before) }
                            },
                        ) { piece ->
                            cut?.feed(piece)
                            written.append(piece)
                            val generated = written.hasText
                            // 有没有写出字变了马上换；别的时候最多每 [STREAM_REFRESH_MS] 毫秒刷新一次。
                            val changed = generated != newCandidateGenerated
                            newCandidateGenerated = generated
                            val partial = if (generated && (changed || tick.due())) written.text() else null
                            if (changed || partial != null) updateDraft(key, run) {
                                if (!generated) it.copy(text = previousCandidate,
                                    workflow = before?.workflow == true).restoreDraftSource(before)
                                else it.copy(text = prefix + partial, workflow = true, referenceAnalysisId = referenceAnalysisId,
                                    referenceFingerprint = reference?.excerpt?.fingerprint,
                                    referenceChapterLength = reference?.excerpt?.chapterLength,
                                    referenceValidated = false, referenceProvenanceBlocked = false,
                                    initialText = null, alternateText = null, revisionWarning = null, beats = null)
                            }
                        } } catch (e: ProseCut) {
                            e.text.also { lengthNote = cutNote("这一章", WordCount.of(prefix + WritePrompts.cleanDraft(it))) }
                        }
                        val cleaned = WritePrompts.cleanDraft(answer)
                        check(cleaned.isNotBlank()) { emptyProseMessage(truncated = false, suggestThinkingOff = false) }
                        newCandidateGenerated = true
                        (prefix + cleaned).also { candidate ->
                            if (reference != null) {
                                verifyOffMain(candidate, reference)
                                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                                updateDraft(key, run) { it.copy(referenceValidated = true) }
                            }
                        }
                    },
                    review = { manuscript ->
                        if (reference != null) {
                            verifyOffMain(manuscript, reference)
                            withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        }
                        askWithPack(
                            "本稿审读", workId, reviewer, WritePrompts.projectReview(task, manuscript, proseRules, reviewEvidence),
                            whole = true, toolsUpTo = chapterId, phase = WritingPhase.REVIEW, confirmedOnly = true,
                            requireTools = true, meter = reviewMeter, lean = true, stable = reviewStable, guide = reviewGuide,
                        )
                    },
                    revise = { manuscript, findings ->
                        if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                        WritePrompts.cleanDraft(askWithPack(
                            "本稿修订", workId, reviseKit.system,
                            WritePrompts.projectRevision(manuscript, findings, chapterTask(kit, task.title, "", task.instruction), voice, refText),
                            whole = true, maxTokens = draftTokens, toolsUpTo = chapterId, phase = WritingPhase.REVISE,
                            // 修订写出来的是正文：和创作一样的温度、思考开关，修订稿的文风才不会和初稿两样。
                            confirmedOnly = true, requireTools = true, meter = reviseMeter, prose = proseOf(assist), lean = true, stable = reviseKit.stable,
                        )).also { candidate ->
                            if (candidate.isNotBlank()) newCandidateGenerated = true
                            if (reference != null) {
                                verifyOffMain(candidate, reference)
                                withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                                updateDraft(key, run) { it.copy(referenceValidated = true) }
                            }
                        }
                    },
                    onStage = { stage, manuscript, assessment ->
                        if (manuscript.isNotBlank() && manuscript != previousCandidate) newCandidateGenerated = true
                        val firstCreatedCandidate = stage == DraftStage.CHECKING && !reviewOnly && !freshCandidateCommitted
                        if (firstCreatedCandidate) freshCandidateCommitted = true
                        val revisionCommitted = stage == DraftStage.CHECKING && revising != null &&
                            manuscript.isNotBlank() && manuscript != revising
                        if (stage == DraftStage.REVISING) revising = manuscript
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
                                // 修订前无条件留底当前这一版（AI 初稿、作者手改稿或上一轮修订稿），修订后可以换回。
                                stage == DraftStage.REVISING && manuscript.isNotBlank() ->
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
                        // 新稿写盘后就是新的基线：之后失败或停止都保留它，“审读并修订此稿”的修订稿也一样。
                        if (firstCreatedCandidate || revisionCommitted) {
                            firstCandidateSaved = true
                            forgetDraftBaseline(key, run)
                        }
                        if (revisionCommitted) {
                            revisionSaved = true
                            draftRevisionSaved[key] = run
                        }
                    },
                )
                if (reference != null) {
                    verifyOffMain(result.text, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                val current = writingTask(workId, chapterId, instruction, targetLength)
                val stale = current?.fingerprint != task.fingerprint
                updateDraft(key, run) { it.copy(
                    text = result.text, loading = false, assessment = result.assessment, workflow = true,
                    referenceValidated = reference != null,
                    stage = if (stale) DraftStage.NEEDS_REVIEW else result.stage,
                    error = if (stale) "项目资料在写作期间变化，旧审读结果已过时，请重新审读" else null, notice = lengthNote,
                    usage = meters.fold(AiUsage()) { all, m -> all + m.usage }, cost = meters.fold(AiCost()) { all, m -> all + m.cost },
                ) }
                val totalMeter = AiMeter(AiFeature.DRAFT, workId)
                totalMeter.add(meters.fold(AiUsage()) { all, m -> all + m.usage }, meters.fold(AiCost()) { all, m -> all + m.cost })
                log(workId, chapterId, if (reviewOnly) "本稿审读" else "项目包写作", instruction, result.text, totalMeter)
            } catch (e: CancellationException) {
                val kept = beatsCommitted?.takeIf { beatsInProgress }
                if (kept != null && draftRuns[key] == run) {
                    // 逐拍写到一半停下：已经写完的几拍留着，写了一半的这一拍不要；“接着写”从下一拍开始。
                    updateDraft(key, run) { it.copy(text = kept.first, beats = kept.second, workflow = true, loading = false,
                        stage = DraftStage.STOPPED, assessment = null, notice = beatNotice("已停止", kept.second)) }
                    withContext(NonCancellable) { runCatching { persistDraft(key, run, kept.first, workflow = true) } }
                } else if (draftRuns[key] == run) {
                    updateDraft(key, run) { state ->
                        val old = draftBaseline(key, run)
                        if (old != null) state.restoredFrom(old, "已停止，保留上一版")
                        else (if (newCandidateGenerated) state else state.copy(
                            text = previousCandidate, workflow = before?.workflow == true).restoreDraftSource(before))
                            .copy(loading = false, stage = DraftStage.STOPPED, assessment = null,
                                notice = REVISION_SAVED.takeIf { revisionSaved })
                    }
                    if (_drafts.value[key]?.text?.isNotBlank() == true) withContext(NonCancellable) {
                        runCatching { settleReference(key, run) }
                        val candidate = _drafts.value[key]?.text.orEmpty()
                        runCatching { persistDraft(key, run, candidate, workflow = _drafts.value[key]?.workflow == true) }
                    }
                }
                throw e
            } catch (e: RejectedReferenceWriting) {
                // 逐拍写到第几拍没过核验：前面各拍都核验过、早已存盘，留着，“接着写”从这一拍重来。
                val beatsKept = beatsCommitted?.takeIf { beatsInProgress }
                updateDraft(key, run) { state ->
                    val restored = draftBaseline(key, run)
                        ?: beatsKept?.let { state.copy(text = it.first, beats = it.second, workflow = true, beatWriting = null, beatsText = null) }
                        ?: if (firstCandidateSaved) state else before ?: DraftState(workId, chapterId, "")
                    restored.copy(loading = false, stage = DraftStage.ERROR, assessment = null, error = e.message,
                        // 拦下的是已写的这一稿（接着写、审读之前）时，草稿页给出路；这次新写的没过不算。
                        referenceIssue = e.issue.takeIf { beatsKept == null && !newCandidateGenerated },
                        usage = meters.fold(AiUsage()) { all, m -> all + m.usage },
                        cost = meters.fold(AiCost()) { all, m -> all + m.cost })
                }
                val kept = _drafts.value[key]
                // 这里写盘失败不能再往外抛：协程里没接住的异常会让 app 闪退。
                if (kept != null) try { persistDraft(key, run, kept.text, workflow = kept.workflow) }
                catch (saveFailure: Exception) { reconcileDraftAfterSaveFailure(key, run, kept, "候选稿版本保存失败", saveFailure) }
            } catch (e: Throwable) {
                val failed = failure(e)
                val kept = beatsCommitted?.takeIf { beatsInProgress }
                if (kept != null) {
                    // 逐拍写到一半出错：已经写完的几拍留着（早已存盘），这一拍不要，“接着写”从这一拍重来。
                    updateDraft(key, run) { it.copy(text = kept.first, beats = kept.second, workflow = true, loading = false,
                        stage = DraftStage.ERROR, assessment = null,
                        error = beatNotice("第 ${kept.second.written + 1} 拍没写成：$failed", kept.second),
                        usage = meters.fold(AiUsage()) { all, m -> all + m.usage }, cost = meters.fold(AiCost()) { all, m -> all + m.cost }) }
                    _drafts.value[key]?.takeIf { draftRuns[key] == run }?.let { d ->
                        try { persistDraft(key, run, d.text, workflow = true) }
                        catch (saveFailure: Exception) { reconcileDraftAfterSaveFailure(key, run, d, "候选稿版本保存失败", saveFailure) }
                    }
                    return@launch
                }
                updateDraft(key, run) { state ->
                    val usage = meters.fold(AiUsage()) { all, m -> all + m.usage }
                    val cost = meters.fold(AiCost()) { all, m -> all + m.cost }
                    val reason = if (e is Exception) e.message ?: "本章流程未完成" else failed
                    val old = draftBaseline(key, run)
                    if (old != null) state.restoredFrom(old, "本次流程未完成：$reason；已保留上一版").copy(usage = usage, cost = cost)
                    else (if (newCandidateGenerated) state else state.copy(
                        text = previousCandidate, workflow = before?.workflow == true).restoreDraftSource(before)).copy(
                    loading = false, stage = DraftStage.ERROR, assessment = null,
                    error = state.error?.takeIf { it.contains("保存失败") } ?: if (revisionSaved) "$REVISION_SAVED：$reason" else reason,
                    usage = usage, cost = cost,
                ) }
                // 写了一半出错留下的那段照样核一遍范本，过了就能接着写、审读。
                settleReference(key, run)
                val candidate = _drafts.value[key]?.takeIf { draftRuns[key] == run }?.text.orEmpty()
                if (candidate.isNotBlank()) try {
                    persistDraft(key, run, candidate, workflow = _drafts.value[key]?.workflow == true)
                } catch (saveFailure: Exception) {
                    reconcileDraftAfterSaveFailure(key, run, before ?: DraftState(workId, chapterId, ""),
                        "候选稿版本保存失败", saveFailure)
                }
            } finally {
                forgetDraftBaseline(key, run)
                draftRevisionSaved.remove(key, run)
            }
        }
    }

    /** 停下这一章正在写的：停在作者看到的这一刻，写了的留着，存成草稿。 */
    fun stopDraft(workId: String, chapterId: String) {
        val key = workId to chapterId
        val current = draftRuns[key]
        val previous = current?.let { draftBaseline(key, it) }
        val revisionSaved = current != null && draftRevisionSaved[key] == current
        // 换个编号：取消以后还在路上的字、取消时的处理都不再改它。
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        val d = _drafts.value[key] ?: return
        // 逐拍写的途中：退回最后一拍写完时存下的全文，写了一半的这一拍不留（“接着写”从这一拍重来）。
        val beatsText = d.beatsText?.takeIf { d.loading && d.beatWriting != null }
        // 有基线就换回上一版，连同它的审读；新稿已经写盘（包括修订稿）就留着新稿。
        val kept = if (previous != null) d.restoredFrom(previous, "已停止，保留上一版")
            else if (beatsText != null) d.copy(text = beatsText, loading = false,
                stage = if (d.workflow) DraftStage.STOPPED else d.stage, assessment = null,
                notice = d.beats?.let { beatNotice("已停止", it) } ?: "已停止")
            else d.copy(loading = false, stage = if (d.workflow) DraftStage.STOPPED else d.stage, assessment = null,
                notice = REVISION_SAVED.takeIf { revisionSaved })
        _drafts.update { it + (key to kept) }
        if (kept.text.isNotBlank()) scope.launch {
            try {
                withContext(NonCancellable) {
                    // 整章写到一半停下：留下的那段照样核一遍范本，过了就能接着写、审读（逐拍写留下的各拍早已核验过）。
                    settleReference(key, run)
                    val saved = _drafts.value[key]?.takeIf { draftRuns[key] == run } ?: kept
                    persistDraft(key, run, saved.text, saved.workflow)
                }
            }
            catch (e: Exception) { message("停止写作后保存草稿失败：${e.message ?: "写入出错"}") }
        }
    }

    /**
     * 整章草稿写长了：按全章篇幅 [length] 调缩写，把整稿压到约多少字。走写正文的路径（写正文的系统提示、写法要求、温度和思考开关）。
     * 压好的是新的一版，原来那版留底在 [DraftState.initialText]，作者可以换回（同“换回修订前那一版”）；审读要重新做。
     */
    fun condenseDraft(workId: String, chapterId: String, length: String) {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val key = workId to chapterId
        val before = _drafts.value[key] ?: return
        if (before.loading || before.text.isBlank()) return
        val budget = ProseBudget.parse(length) ?: run {
            message("这一章没有写明目标篇幅，压不了")
            return
        }
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _drafts.update { it + (key to before.copy(loading = true, error = null, notice = null)) }
        val meter = AiMeter(AiFeature.DRAFT, workId)
        draftJobs[key] = scope.launch {
            try {
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val task = writingTask(workId, chapterId, before.taskInstruction ?: before.instruction, before.taskTargetLength ?: before.targetLength)
                val body = withContext(Dispatchers.IO) { store.text(workId, chapterId) }
                val kit = proseKit(workId, chapterId, body, task, length, before.referenceAnalysisId != null, WritingPhase.REVISE)
                val prompt = withContext(Dispatchers.Default) { WritePrompts.condense(before.text, chapter.title, budget, kit.tail) }
                val cut = cutoff(budget.cutoff)
                val answer = try {
                    askWithPack("缩写", workId, kit.system, prompt, whole = true, maxTokens = draftTokens, useTools = false,
                        toolsUpTo = chapterId, phase = WritingPhase.REVISE, meter = meter, prose = proseOf(assistOf(workId)),
                        lean = true, stable = kit.stable, onReset = { cut?.reset() }) { piece -> cut?.feed(piece) }
                } catch (e: ProseCut) { e.text }
                val text = WritePrompts.cleanDraft(answer)
                check(text.isNotBlank()) { emptyProseMessage(truncated = false, suggestThinkingOff = false) }
                updateDraft(key, run) { it.copy(text = text, loading = false, error = null, assessment = null, stage = DraftStage.IDLE,
                    referenceValidated = false, initialText = before.text, alternateText = null, revisionWarning = null,
                    notice = "压到了 ${WordCount.of(text)} 字（原来 ${WordCount.of(before.text)} 字），可以换回原来那一版",
                    usage = meter.usage, cost = meter.cost, referenceIssue = null) }
                // 压出来的是新的一版：照范本写的稿子对它重新核一遍，过了才标已核验（以前一律标成未核验，之后接着写、审读都被拦下）。
                settleReference(key, run)
                persistDraft(key, run, text, before.workflow)
                log(workId, chapterId, "缩写", "压到约 ${budget.target} 字", text, meter)
            } catch (e: CancellationException) {
                if (draftRuns[key] == run) updateDraft(key, run) { before.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                // 没压成不算这一版出错：原稿照样可以采纳，只说一句。
                updateDraft(key, run) { before.copy(loading = false, notice = "没压成：${failure(e)}，原稿没动",
                    usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
            }
        }
    }

    // ---------- 草稿页“这段不行” ----------

    private val _paragraphFix = MutableStateFlow<ParagraphFix?>(null)
    /** 草稿页上正在重写（或等作者采纳）的那一段；同一时间只有一段。 */
    val paragraphFix: StateFlow<ParagraphFix?> = _paragraphFix.asStateFlow()
    private var paragraphJob: Job? = null

    /** 把 [text] 的第 [index] 行换成 [paragraph]（可以是几行）。 */
    private fun replaceLine(text: String, index: Int, paragraph: String): String =
        text.split('\n').toMutableList().also { it[index] = paragraph }.joinToString("\n")

    /**
     * 这一稿是写这章时用的范本：读出来，和这一稿写的时候是同一份才往下走（变了按 [DraftReferenceIssue.CHANGED] 报）。
     * 不挂范本为空。重写一段、采纳重写都要过这一关，范本核验不能被绕过去。
     */
    private suspend fun draftReference(d: DraftState): ReferenceWritingMaterial? {
        val id = d.referenceAnalysisId ?: return null
        val reference = loadForCandidate(d.workId, d.chapterId, id, candidate = true)
        if (reference.excerpt.fingerprint != d.referenceFingerprint || reference.excerpt.chapterLength != d.referenceChapterLength)
            throw RejectedReferenceWriting("范本在这一稿写完以后变了，没法按原来的范本核验", DraftReferenceIssue.CHANGED)
        return reference
    }

    /**
     * 草稿页“这段不行”：AI 只重写第 [index] 行那一段，按作者选的原因 [reasons]（“其他”时是 [note]）换成具体的改法要求，
     * 前后各带一段作语境。走写正文的路径（写正文的系统提示、写法要求、温度和思考开关）。草稿正在写、正在审读时不做。
     * 照范本写的稿子：请求前确认范本没变，写回来把换好的整稿再核一遍有没有照抄范本（照逐拍写），没过就照实报错。
     * 点了就给各原因记一次数（按作品存），同一个原因点够几次，收尾学习时提成写法规则。
     */
    fun fixParagraph(workId: String, chapterId: String, index: Int, reasons: Set<FixReason>, note: String = "") {
        if (!ready) {
            message("先在“我的 → AI 助手”里开启并填好服务商和密钥")
            return
        }
        val d = _drafts.value[workId to chapterId] ?: return
        if (d.loading || d.text.isBlank() || d.referenceProvenanceBlocked || reasons.isEmpty()) return
        if (_paragraphFix.value?.loading == true) return
        val lines = d.text.split('\n')
        val target = lines.getOrNull(index)?.trim().orEmpty()
        if (target.isEmpty()) return
        val before = lines.take(index).lastOrNull { it.isNotBlank() }.orEmpty().trim()
        val after = lines.drop(index + 1).firstOrNull { it.isNotBlank() }.orEmpty().trim()
        paragraphJob?.cancel()
        val fix = ParagraphFix(workId, chapterId, index, target, d.text, reasons, note.trim())
        _paragraphFix.value = fix
        val meter = AiMeter(AiFeature.DRAFT, workId)
        // 只改这一次的：放弃了、又点了别的段，旧请求回来不再动它。
        fun update(transform: (ParagraphFix) -> ParagraphFix) = _paragraphFix.update { f -> if (f === fix) transform(f) else f }
        paragraphJob = scope.launch {
            try {
                // 记不上不挡重写。
                withContext(Dispatchers.IO) { runCatching { store.updateLearning(workId) { EditLearning.countReasons(it, reasons, target) } } }
                val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
                val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
                if (chapter.locked) throw IllegalStateException("这一章已定稿：要改先解锁并登记原因")
                val reference = draftReference(d)
                if (reference != null) withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                val task = writingTask(workId, chapterId, d.taskInstruction ?: d.instruction, d.taskTargetLength ?: d.targetLength)
                val body = withContext(Dispatchers.IO) { store.text(workId, chapterId) }
                val kit = proseKit(workId, chapterId, body, task, chapterLengthOf(workId, task), reference != null, WritingPhase.REVISE)
                val fixes = reasons.sortedBy { it.ordinal }.map { r ->
                    if (r != FixReason.OTHER) r.fix
                    else if (fix.note.isNotEmpty()) "作者的意见：${fix.note}" else "作者说不出具体哪里不对：换个写法重写，意思不变"
                }
                val prompt = WritePrompts.fixParagraph(chapter.title, target, before, after, fixes, kit.tail)
                val answer = askWithPack("重写这段", workId, kit.system, prompt, whole = true, maxTokens = draftTokens, useTools = false,
                    toolsUpTo = chapterId, phase = WritingPhase.REVISE, meter = meter, prose = proseOf(assistOf(workId)),
                    lean = true, stable = kit.stable)
                val text = WritePrompts.cleanDraft(answer).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                check(text.isNotBlank()) { emptyProseMessage(truncated = false, suggestThinkingOff = false) }
                if (reference != null) {
                    verifyOffMain(replaceLine(d.text, index, text), reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, workId, chapterId, reference) }
                }
                update { it.copy(loading = false, text = text, usage = meter.usage, cost = meter.cost) }
                log(workId, chapterId, "重写这段", reasons.joinToString("、") { it.label }, text, meter)
            } catch (e: CancellationException) {
                update { it.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                update { it.copy(loading = false, error = failure(e), usage = meter.usage.takeIf { u -> !u.isEmpty }, cost = meter.cost) }
            }
        }
    }

    /** 不要这次重写（在写就停下）。 */
    fun dropParagraphFix() {
        paragraphJob?.cancel()
        paragraphJob = null
        _paragraphFix.value = null
    }

    /**
     * 采纳重写：草稿只换那一段，存一版（原来那版留底在 [DraftState.initialText]，能换回，同压篇幅），审读要重新做。
     * 草稿在这期间变了就不换。照范本写的稿子换好以后整稿再核一遍、确认范本没变，过了才标已核验。
     */
    fun acceptParagraphFix() {
        val fix = _paragraphFix.value?.takeIf { !it.loading && it.error == null && it.text.isNotBlank() } ?: return
        val key = fix.workId to fix.chapterId
        val before = _drafts.value[key] ?: return
        if (before.loading || before.text != fix.base) {
            _paragraphFix.value = fix.copy(error = "草稿在这期间变了，这段重写对不上了：放弃以后再点一次“这段不行”")
            return
        }
        val text = replaceLine(fix.base, fix.index, fix.text)
        val run = ++draftSeq
        draftRuns[key] = run
        draftJobs.remove(key)?.cancel()
        _paragraphFix.value = null
        _drafts.update { it + (key to before.copy(loading = true, notice = null)) }
        draftJobs[key] = scope.launch {
            try {
                val reference = draftReference(before)
                if (reference != null) {
                    verifyOffMain(text, reference)
                    withContext(Dispatchers.IO) { ReferenceWriting.ensureCurrent(store, fix.workId, fix.chapterId, reference) }
                }
                updateDraft(key, run) { it.copy(text = text, loading = false, error = null, assessment = null, stage = DraftStage.IDLE,
                    referenceValidated = reference != null, initialText = before.text, alternateText = null, revisionWarning = null,
                    referenceIssue = null, notice = "按“${fix.reasons.sortedBy { r -> r.ordinal }.joinToString("、") { r -> r.label }}”换了一段，可以换回原来那一版") }
                persistDraft(key, run, text, before.workflow)
                withContext(Dispatchers.IO) { runCatching { store.updateLearning(fix.workId) { EditLearning.reasonExample(it, fix.reasons, fix.original, fix.text) } } }
            } catch (e: CancellationException) {
                if (draftRuns[key] == run) updateDraft(key, run) { before.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                updateDraft(key, run) { before.copy(loading = false, notice = "这段没换：${failure(e)}，草稿没动") }
            }
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
        draftRevisionSaved.remove(key)
        draftJobs.remove(key)?.cancel()
        if (_paragraphFix.value?.let { it.workId == workId && it.chapterId == chapterId } == true) dropParagraphFix()
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
    private suspend fun syncOne(workId: String, chapterId: String, text: String, summary: Boolean = false): ChapterSyncResult {
        if (!canUsePack) throw IllegalStateException("本机模型上下文太小、不能查设定包，整理本章要用联网的 AI 服务商")
        val work = withContext(Dispatchers.IO) { store.get(workId) } ?: throw IllegalStateException("作品不在了")
        val chapter = work.chapter(chapterId) ?: throw IllegalStateException("章节不在了")
        val number = withContext(Dispatchers.IO) { store.chapterNo(work, chapterId) } ?: (work.chapters.indexOfFirst { it.id == chapterId } + 1)
        val base = ChapterSyncResult(chapterId, chapter.title, number, ChapterSyncs.hash(text), sourceDigest = ChapterSyncs.digest(text))
        if (text.isBlank()) return base.copy(error = "这一章还没有正文")
        val hints = withContext(Dispatchers.IO) { chapterContext(workId, chapterId, text, upTo = true) }
        val system = WritePrompts.system(work.title, assistOf(workId), hints)
        // 到上一章为止的账本：开着的伏笔带编号，收伏笔时 AI 用编号指认。人名：核对原句不能只是人名。
        val (ledger, names) = withContext(Dispatchers.IO) { store.syncLedger(workId, chapterId, number, text) to store.syncNames(workId) }
        // 这一章在第几卷：账本里只写了“第几卷起”的知情按卷号判断到没到。
        val volume = work.volumeNumber(chapterId)
        val parts = WritePrompts.syncParts(text)
        // 顺带写摘要（[summary]）只在整章一次发完时：分了段的，最后一段概括不了全章，摘要由调用处另发一次。
        val withSummary = summary && parts.size == 1
        var summaryText = ""
        val meter = AiMeter(AiFeature.SYNC, workId)
        val found = mutableListOf<SyncItem>()
        var dropped = 0
        var answered = 0
        var failed: Pair<Int, Exception>? = null
        var noTools = false
        parts.forEachIndexed { i, part ->
            try {
                val before = parts.getOrNull(i - 1)?.takeLast(WritePrompts.SYNC_OVERLAP).orEmpty()
                // 不要求工具：设定包的常用部分整份在系统提示里，本章资料也带着，模型不支持工具时去掉工具照样能整理；
                // 只是没法按需查，结果页提示“没能查设定包”，由作者勾选前核对。
                val answer = askWithPack(
                    "整理本章", workId, system, WritePrompts.sync(chapter.title, number, part, i + 1, parts.size, before, ledger.text, summary = withSummary),
                    whole = true, maxTokens = if (withSummary) SYNC_TOKENS + SUMMARY_TOKENS else SYNC_TOKENS, maxRounds = SYNC_ROUNDS, toolsUpTo = chapterId,
                    meter = meter, onToolsDropped = { noTools = true }, noThinking = true,
                )
                // 原句在整章里核对（可能落在和前一段接上的那几百字里）。
                val parsed = WritePrompts.parseSync(answer, text, chapterId, number, ledger.codes, names)
                found += parsed.items.map { it.copy(volume = volume) }
                dropped += parsed.dropped
                if (withSummary) summaryText = parsed.summary
                answered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failed == null) failed = i + 1 to e
            }
        }
        // 一段都没整理成：和整章没整理成一样，报错。
        failed?.let { (_, e) -> if (answered == 0) throw e }
        val merged = withContext(Dispatchers.Default) { PackSync.merge(found) }
        val (docs, entries) = withContext(Dispatchers.IO) { store.pack(workId).map { it to store.packText(workId, it.id) } to store.ledger(workId) }
        // 查重要和账本几千条比：不放在主线程上。
        val planned = withContext(Dispatchers.Default) { PackSync.plan(merged, docs, entries) }
        return base.copy(
            items = planned, dropped = dropped, known = merged.size - planned.size, parts = parts.size, noTools = noTools, summary = summaryText,
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
        scope.launch {
            try {
                val summary = commitSync(workId, session)
                _sync.value = null
                then(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("写进设定包失败：${e.message ?: "写入出错"}")
                failed()
            }
        }
    }

    /**
     * 把一次整理里勾选的写进去（[applySync] 和“这章写完了”共用）：写设定包、记账本，各章（没出错、正文没变的）记成已整理。
     * 返回一句“写了几条进哪几份文档”；写失败抛出来。
     */
    private suspend fun commitSync(workId: String, session: SyncSession): String {
        val picked = session.chosen
        val counts = withContext(Dispatchers.IO) { store.applySync(workId, picked, session.results) }
        // 账本页可能已经读过整理前的账本：重读，免得拿旧条目再改、再撤销。
        if (picked.any { it.place == SyncPlace.LEDGER }) ledgerChanged(workId)
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
        val summary = if (counts.isEmpty()) "没有写进设定包，${marks.size} 章记为已整理"
            else "写进了 ${counts.values.sum()} 条：" + counts.entries.joinToString("、") { (k, n) ->
                if (k == PackSync.LEDGER) "$k $n 条" else "《$k》$n 条"
            }
        return summary + if (staleCount > 0) "；$staleCount 章正文已变化，未记为已整理" else ""
    }

    // ---------- 这章写完了 ----------

    private val _finish = MutableStateFlow<FinishRun?>(null)
    /** 正在收尾的一章（同一时间只有一章）：离开收尾页再回来还在，写完、换一章收尾才换掉。 */
    val finish: StateFlow<FinishRun?> = _finish.asStateFlow()
    private var finishJob: Job? = null

    /** 收尾里能不能写摘要、审稿（要 AI）：不能时是原因，能时为空。整理入账另看 [syncBlocked]。 */
    val finishAiBlocked: String?
        get() = if (!ready) "没设置 AI：先在“我的 → AI 助手”里填好服务商和密钥" else null

    /** 这次实际能做的：AI 没配置（或本机模型不能整理）时去掉做不了的；这章没有 AI 原稿时不学。 */
    private fun usable(options: FinishOptions, learnable: Boolean) = options.copy(
        sync = options.sync && syncBlocked == null,
        summary = options.summary && ready,
        review = options.review && ready,
        learn = options.learn && learnable,
    )

    /** 这章有没有采纳进正文的 AI 原稿（读盘，在后台读）。 */
    private suspend fun hasOriginals(workId: String, chapterId: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { store.learning(workId).originals[chapterId].orEmpty().isNotEmpty() }.getOrDefault(false) }

    /**
     * 打开收尾页：同一章接着上次的；上次已经收完或没开始的从头来，勾选项用作品记住的。
     * 别的章还在收尾（[FinishRun.busy]）时不动它：以前这里直接取消，正在写入的那一章会写进去一半、没定稿，也没有重试的入口。
     * 收尾页上看到的是“《A》正在收尾，等它完成或先停下”；它完成或停下以后再打开。返回真表示打开了这一章。
     */
    fun openFinish(workId: String, chapterId: String): Boolean {
        val now = _finish.value
        if (now != null && now.workId == workId && now.chapterId == chapterId && now.stage != FinishStage.DONE) {
            // 还在勾选：回编辑器又采纳了 AI 的字（或原稿被清掉）再回来，“从这章的改稿里学”那一项跟着变。
            if (now.stage == FinishStage.SETUP) scope.launch {
                val learnable = hasOriginals(workId, chapterId)
                _finish.update { r -> if (r?.workId == workId && r.chapterId == chapterId && r.stage == FinishStage.SETUP) r.copy(learnable = learnable) else r }
            }
            return true
        }
        if (now != null && now.busy) return false
        finishJob?.cancel()
        _finish.value = FinishRun(workId, chapterId, _assists.value[workId]?.finish ?: FinishOptions())
        scope.launch {
            val saved = assistOf(workId).finish
            val learnable = hasOriginals(workId, chapterId)
            _finish.update { r -> if (r?.workId == workId && r.chapterId == chapterId && r.stage == FinishStage.SETUP) r.copy(options = saved, learnable = learnable) else r }
        }
        return true
    }

    /** 改勾选项：这一次用，也按作品记住。 */
    fun setFinishOptions(options: FinishOptions) {
        val run = _finish.value?.takeIf { it.stage == FinishStage.SETUP } ?: return
        _finish.value = run.copy(options = options)
        updateAssist(run.workId) { it.copy(finish = options) }
    }

    private fun updateFinish(workId: String, chapterId: String, transform: (FinishRun) -> FinishRun) {
        _finish.update { r -> if (r != null && r.workId == workId && r.chapterId == chapterId) transform(r) else r }
    }

    /** 点“开始”：按勾选的依次审稿（勾了才审）、整理入账和写摘要（合成一次请求）；只勾了定稿就直接到确认。 */
    fun startFinish() {
        val run = _finish.value?.takeIf { it.stage == FinishStage.SETUP } ?: return
        val o = usable(run.options, run.learnable)
        fun state(on: Boolean) = if (on) StepState.WAITING else StepState.SKIPPED
        val next = FinishRun(run.workId, run.chapterId, o, FinishStage.RUNNING, state(o.review), state(o.sync), state(o.summary),
            learnable = run.learnable, learn = state(o.learn))
        _finish.value = next
        finishJob = scope.launch {
            if (o.review && !finishReview(next.workId, next.chapterId)) return@launch
            finishAi(next.workId, next.chapterId)
        }
    }

    /**
     * 审稿这一步：用现有的三道审稿，审完结果照常存下（审稿页上能看到）。审出还没处理的问题时停在 [FinishStage.REVIEW_FOUND]，
     * 返回假；没问题（或几道都跳过了）返回真，接着收尾。
     */
    private suspend fun finishReview(workId: String, chapterId: String): Boolean {
        updateFinish(workId, chapterId) { it.copy(review = StepState.RUNNING) }
        val text = withContext(Dispatchers.IO) { store.text(workId, chapterId) }
        val job = startReview(workId, chapterId, text)
        try {
            job.join()
        } catch (e: CancellationException) {
            stopReview(workId, chapterId)
            throw e
        }
        val review = _reviews.value[workId to chapterId]?.review
        val open = review?.gates.orEmpty().sumOf { g -> g.issues.count { it.open } }
        // 某一道没审成（不是“没设置、没规范”那种跳过）：记下来给作者看，不拦着收尾。
        val failed = review?.gates.orEmpty().filter { g -> g.error?.let { "跳过" !in it } == true }
        updateFinish(workId, chapterId) {
            it.copy(
                review = StepState.DONE, reviewIssues = open,
                reviewError = failed.takeIf { f -> f.isNotEmpty() }?.joinToString("；") { g -> "${g.gate.label}没审成：${g.error}" },
                stage = if (open > 0) FinishStage.REVIEW_FOUND else it.stage,
            )
        }
        return open == 0
    }

    /** 审出问题后选了“不管，继续收尾”。 */
    fun continueFinish() {
        val run = _finish.value?.takeIf { it.stage == FinishStage.REVIEW_FOUND } ?: return
        _finish.value = run.copy(stage = FinishStage.RUNNING)
        finishJob = scope.launch { finishAi(run.workId, run.chapterId) }
    }

    /** 有一步没成（整理、摘要）：只重做没成的。 */
    fun retryFinish() {
        val run = _finish.value?.takeIf { it.stage == FinishStage.AI_FAILED } ?: return
        _finish.value = run.copy(stage = FinishStage.RUNNING)
        finishJob = scope.launch { finishAi(run.workId, run.chapterId) }
    }

    /** 停下、从头来：正在发的请求取消，回到勾选（勾选项用作品记住的）。正在写入时不能停。 */
    fun stopFinish() {
        val run = _finish.value ?: return
        if (run.stage == FinishStage.APPLYING) return
        finishJob?.cancel()
        if (run.review == StepState.RUNNING) stopReview(run.workId, run.chapterId)
        _finish.value = FinishRun(run.workId, run.chapterId, _assists.value[run.workId]?.finish ?: run.options, learnable = run.learnable)
    }

    /**
     * 整理入账和写摘要：两样都要时合成一次请求（整理的提示词里多要一个 summary）；章太长、分了段整理的，
     * 摘要另发一次（照单独写摘要的办法）；只要摘要时只发摘要请求。存盘的正文为准（进收尾页前已经存了）。
     * 已经好了的不再做：重试只补没成的。
     */
    private suspend fun finishAi(workId: String, chapterId: String) {
        val run = _finish.value?.takeIf { it.workId == workId && it.chapterId == chapterId } ?: return
        val o = run.options
        val needSync = o.sync && run.sync != StepState.DONE
        val needSummary = o.summary && run.summary != StepState.DONE
        var summaryText: String? = null
        if (needSync) {
            updateFinish(workId, chapterId) {
                it.copy(
                    sync = StepState.RUNNING, syncError = null,
                    summary = if (needSummary) StepState.RUNNING else it.summary, summaryError = if (needSummary) null else it.summaryError,
                )
            }
            val text = withContext(Dispatchers.IO) { store.text(workId, chapterId) }
            try {
                val result = syncOne(workId, chapterId, text, summary = needSummary)
                summaryText = result.summary.takeIf { it.isNotBlank() }
                updateFinish(workId, chapterId) {
                    if (result.error != null) it.copy(sync = StepState.FAILED, syncError = result.error)
                    else it.copy(sync = StepState.DONE, session = SyncSession(workId, total = 1, done = 1).with(result))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 内存不够这类 Error 也接住：停在“没成、可以重试”，不留在“在做”。
                updateFinish(workId, chapterId) { it.copy(sync = StepState.FAILED, syncError = finishError(e, "请求失败")) }
            }
        }
        if (needSummary) {
            if (summaryText == null) {
                // 分了段整理、整理没成、或者模型没回 summary：另发一次写摘要的请求。
                updateFinish(workId, chapterId) { it.copy(summary = StepState.RUNNING, summaryError = null) }
                summaryText = try {
                    summarize(workId, chapterId).takeIf { it.isNotBlank() } ?: throw IllegalStateException("AI 没写出摘要")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    updateFinish(workId, chapterId) { it.copy(summary = StepState.FAILED, summaryError = finishError(e, "请求失败")) }
                    null
                }
            }
            summaryText?.let { t -> updateFinish(workId, chapterId) { it.copy(summary = StepState.DONE, draft = t) } }
        }
        // 从改稿里学：本机比对加（有 AI 时）AI 归纳。没做全也不拦收尾，原因写在确认页上。
        if (o.learn && run.learn != StepState.DONE) finishLearn(workId, chapterId)
        val now = _finish.value?.takeIf { it.workId == workId && it.chapterId == chapterId } ?: return
        if (now.sync == StepState.FAILED || now.summary == StepState.FAILED) {
            updateFinish(workId, chapterId) { it.copy(stage = FinishStage.AI_FAILED) }
            return
        }
        // 确认前读一次细纲卡：已经有摘要的，确认页上给“用新的 / 留旧的”，默认留旧的，不盖掉作者写的。
        val old = if (o.summary) withContext(Dispatchers.IO) { runCatching { store.card(workId, chapterId).summary }.getOrDefault("") } else ""
        updateFinish(workId, chapterId) { it.copy(stage = FinishStage.CONFIRM, oldSummary = old, keepOld = old.isNotBlank()) }
    }

    /**
     * 收尾里的“从这章的改稿里学”：把这章的 AI 原稿和存盘的定稿按段对齐、数出作者的改法（在后台算），记进跨章的累积数；
     * 有 AI（不是本机模型）时再挑几对“原段 → 改后”请 AI 归纳。候选里本机比对和“这段不行”来的默认勾上（统计站得住），
     * AI 归纳的默认不勾（等作者看过）。AI 归纳没成只记原因，本机的照样给。
     */
    private suspend fun finishLearn(workId: String, chapterId: String) {
        updateFinish(workId, chapterId) { it.copy(learn = StepState.RUNNING, learnError = null) }
        try {
            val (data, text) = withContext(Dispatchers.IO) { store.learning(workId) to store.text(workId, chapterId) }
            val assist = assistOf(workId)
            val edits = withContext(Dispatchers.Default) { EditLearning.compare(data.originals[chapterId].orEmpty(), text) }
            val observed = if (edits == null) emptyList() else withContext(Dispatchers.Default) { EditLearning.observe(edits, text) }
            val now = System.currentTimeMillis()
            val updated = withContext(Dispatchers.IO) { store.updateLearning(workId) { EditLearning.tally(it, chapterId, observed, now) } }
            val notes = mutableListOf<String>()
            if (edits == null) notes += "AI 原稿和定稿对不齐（改动太多或正文是空的），这一章没法逐段比对"
            var ai = emptyList<LearnCandidate>()
            if (edits != null && ready && !localModel) {
                val pairs = EditLearning.pairsForAi(edits)
                if (pairs.isNotEmpty()) try {
                    ai = learnByAi(workId, pairs, assist)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notes += "AI 归纳没成：${finishError(e, "请求失败")}"
                }
            }
            val list = EditLearning.candidates(updated, assist, ai)
            updateFinish(workId, chapterId) {
                it.copy(learn = StepState.DONE, candidates = list, learnError = notes.joinToString("；").ifEmpty { null },
                    learnPicked = list.filter { c -> c.source != EditLearning.FROM_AI }.map { c -> c.key }.toSet())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 学不成不拦收尾：停在“出错”，确认页上照实说。
            updateFinish(workId, chapterId) { it.copy(learn = StepState.FAILED, learnError = finishError(e, "比对出错")) }
        }
    }

    /** 请 AI 归纳改法：不走写正文的路径，不带温度，能关思考的官方 V4 关掉思考（[quiet]）。回的不是约定的 JSON 就照实报错。 */
    private suspend fun learnByAi(workId: String, pairs: List<Pair<String, String>>, assist: WorkAssist): List<LearnCandidate> {
        val title = withContext(Dispatchers.IO) { store.get(workId)?.title.orEmpty() }
        val known = (assist.banned.takeLast(30).map { "「$it」" } + assist.learned.takeLast(WorkAssist.PROMPT_LEARNED))
        val answer = ask("从改稿学", workId, WritePrompts.system(title, assist), WritePrompts.learnRules(pairs, known), whole = true, noThinking = true)
        return EditLearning.parseAi(answer, pairs)
    }

    /** 确认页上勾、不勾一条学到的；写进去以后不能再改。 */
    fun toggleFinishLearn(key: String, on: Boolean) = _finish.update { r ->
        if (r != null && r.stage == FinishStage.CONFIRM && FinishWrite.LEARN !in r.wrote)
            r.copy(learnPicked = if (on) r.learnPicked + key else r.learnPicked - key) else r
    }

    /** 把勾了的记进黑名单、学到的规则，没勾的记进“不再提”。返回一句说明（什么都没提时为空）。 */
    private suspend fun commitLearn(workId: String, run: FinishRun): String? {
        if (run.candidates.isEmpty()) return null
        val picked = run.candidates.filter { it.key in run.learnPicked }
        val rest = run.candidates.filter { it.key !in run.learnPicked }.map { it.key }
        val saved = withContext(Dispatchers.IO) {
            val a = if (picked.isEmpty()) store.assist(workId) else store.updateAssist(workId) { assist ->
                picked.fold(assist) { acc, c -> if (c.kind == LearnKind.BAN) acc.ban(c.text) else acc.learn(c.text) }
            }
            if (rest.isNotEmpty()) store.updateLearning(workId) { EditLearning.dismiss(it, rest) }
            a
        }
        _assists.update { it + (workId to saved) }
        return if (picked.isEmpty()) "学到的这次都没要，以后不再提" else "从改稿里学到 ${picked.size} 条"
    }

    /** 收尾里一步没成的原因：平常的出错照原话；程序的毛病、内存不够这类记进闪退记录，只说出了意外。 */
    private fun finishError(e: Throwable, fallback: String): String =
        if (e is Exception && !CrashLog.isBug(e)) e.message ?: fallback else unexpected(e).also { CrashLog.caught(e) }

    /** 确认页上改摘要。 */
    fun editFinishSummary(text: String) = _finish.update { r -> r?.takeIf { it.stage == FinishStage.CONFIRM }?.copy(draft = text) ?: r }

    /** 细纲卡里已经有摘要时：留旧的（真）还是用新的（假）。 */
    fun keepOldSummary(keep: Boolean) = _finish.update { r -> r?.takeIf { it.stage == FinishStage.CONFIRM }?.copy(keepOld = keep) ?: r }

    /** 确认页上改整理结果（勾选、改文字、改方面、改计划收章），和整理页一样的几样；已经写进去了就不能再改。 */
    private fun editFinishSync(transform: (SyncSession) -> SyncSession) = _finish.update { r ->
        val s = r?.session
        if (r != null && s != null && r.stage == FinishStage.CONFIRM && FinishWrite.SYNC !in r.wrote) r.copy(session = transform(s)) else r
    }

    fun toggleFinishSync(key: String, on: Boolean) = editFinishSync { s ->
        if (on && s.results.any { result -> result.error != null && result.items.any { it.key == key } }) s
        else s.copy(picked = if (on) s.picked + key else s.picked - key)
    }

    fun editFinishSyncText(key: String, text: String) = editFinishSync { it.copy(edits = it.edits + (key to text)) }

    fun editFinishSyncAspect(key: String, aspect: String) = editFinishSync { it.copy(aspects = it.aspects + (key to aspect)) }

    fun editFinishSyncPayoff(key: String, payoff: Int?) = editFinishSync { it.copy(payoffs = it.payoffs + (key to payoff)) }

    /**
     * 点“完成收尾”：依次写入勾选的整理条目（同整理页，变更登记、记为已整理照旧）、把摘要写进细纲卡（只改摘要这一项，
     * 别的项用最新的）、定稿（[finalize]，存历史版本、记登记照旧）。哪一步没成就停下，报出哪步没成；再点只做剩下的，
     * 已经写成的不重复写。全部写完调 [then]，拿到一句说明。
     */
    fun applyFinish(finalize: suspend (workId: String, chapterId: String) -> Unit, then: (String) -> Unit = {}) {
        val run = _finish.value?.takeIf { it.stage == FinishStage.CONFIRM } ?: return
        val (workId, chapterId) = run.workId to run.chapterId
        _finish.value = run.copy(stage = FinishStage.APPLYING, failedWrite = null, writeError = null)
        finishJob = scope.launch {
            val notes = mutableListOf<String>()
            for (step in FinishWrite.entries) {
                if (step in run.wrote) continue
                // 没勾“从这章的改稿里学”：这一步不算做过。
                if (step == FinishWrite.LEARN && !run.options.learn) continue
                try {
                    when (step) {
                        FinishWrite.SYNC -> run.session?.takeIf { run.options.sync }?.let { notes += commitSync(workId, it) }
                        FinishWrite.SUMMARY -> if (run.options.summary && !run.keepOld && run.draft.isNotBlank()) {
                            val text = run.draft.trim()
                            withContext(Dispatchers.IO) {
                                // 在同一把锁里读改写：只填摘要，别的项（作者等待时在细纲卡页改的）用最新的。
                                // 进确认以后摘要又被改过的，不盖掉，请作者再选一次。
                                store.updateCard(workId, chapterId) { latest ->
                                    check(latest.summary.isBlank() || latest.summary == run.oldSummary || latest.summary == text) {
                                        "细纲卡里的摘要刚被改过，看一眼再选用新的还是留旧的"
                                    }
                                    latest.copy(summary = text)
                                }
                            }
                            notes += "摘要写进了细纲卡"
                        }
                        FinishWrite.LEARN -> commitLearn(workId, run)?.let { notes += it }
                        FinishWrite.FINAL -> finalize(workId, chapterId)
                    }
                } catch (e: CancellationException) {
                    // 写入时不会被收尾页取消（停不了、换章也不掐）；真被取消了（页面所在的 ViewModel 没了）也退回确认，
                    // 写成了的已经记在 wrote 里，重试从没写的那步接着。
                    updateFinish(workId, chapterId) { it.copy(stage = FinishStage.CONFIRM, failedWrite = step, writeError = "${step.label}没成：停下了") }
                    throw e
                } catch (e: Throwable) {
                    // Error（写大设定包时内存不够、栈溢出）也接住：以前漏到兜底处理器，页面一直停在“正在写入”，停也停不了。
                    // 摘要被改过：把细纲卡里现在的摘要读回来，确认页上重新给“用新的 / 留旧的”。
                    val latest = if (step == FinishWrite.SUMMARY) withContext(Dispatchers.IO) { runCatching { store.card(workId, chapterId).summary }.getOrNull() } else null
                    updateFinish(workId, chapterId) {
                        it.copy(
                            stage = FinishStage.CONFIRM, failedWrite = step, writeError = "${step.label}没成：${finishError(e, "写入出错")}",
                            oldSummary = latest ?: it.oldSummary, keepOld = if (!latest.isNullOrBlank() && latest != it.oldSummary) true else it.keepOld,
                        )
                    }
                    return@launch
                }
                updateFinish(workId, chapterId) { it.copy(wrote = it.wrote + step) }
            }
            updateFinish(workId, chapterId) { it.copy(stage = FinishStage.DONE) }
            then((listOf("这一章定稿了") + notes).joinToString("；"))
        }
    }

    companion object {
        /** 写作建议一次最多发几段。 */
        const val REALTIME_BATCH = 6
        /** 去 AI 腔一次最多发几段。 */
        const val CLICHE_BATCH = 20
        /** 太短的段（一两个字、一句短对话）不查。 */
        const val MIN_PARAGRAPH = 8
        const val EXTRACT_CHARS = 15_000

        /** 整理检查规则时最多带多少字的规范。 */
        const val RULE_SOURCE_CHARS = 30_000

        /** 本机模型上下文短：写作规范、本章资料各只带这么多字（放不下的再由 [com.xycs.lingyuereader.ai.DevicePrompt] 截短）。 */
        const val LOCAL_STYLE_LIMIT = 1_200
        const val LOCAL_BRIEF_LIMIT = 1_500

        /** 写正文时本章资料的上下限（作品设的本章资料上限的一半，落在这中间）：其余的让模型需要时自己查。 */
        const val PROSE_BRIEF_MIN = 3_000
        const val PROSE_BRIEF_MAX = 16_000

        /** 编辑器里写时带光标前多少字。 */
        const val INLINE_BEFORE = 4_000

        /** 整理本章：回答最长多少 token、最多查几轮设定包。 */
        const val SYNC_TOKENS = 4_096
        const val SYNC_ROUNDS = 2
        /** 整理时顺带写摘要（150 到 300 字）多留的 token。 */
        const val SUMMARY_TOKENS = 1_024

        /**
         * AI 写正文（编辑器里写、AI 写整章草稿、项目包流程）时，流式的字最多隔这么久刷新一次显示：
         * 每刷新一次，编辑器、草稿页都要把整章重新排一遍，一块一刷，长章节里就卡。
         */
        const val STREAM_REFRESH_MS = 150L

        /** [com.xycs.lingyuereader.ai.AiClient] 在回答后面加的说明：被截断了、模型中途停了。 */
        private const val TRUNCATED_NOTE = "\n\n（回答太长，后面被截断了。）"
        private const val STOPPED_NOTE = "\n\n（模型中途停止了回答。）"
        /** 本机收住时不算正文的几句（见 [cutoff]）。 */
        private val CUT_IGNORE = setOf(TRUNCATED_NOTE, STOPPED_NOTE, "模型拒绝回答这个问题。")

        /** 修订稿已写盘、复审没走完时给作者看的状态。 */
        const val REVISION_SAVED = "修订稿已保存，复审没完成"

        /** 编本章任务时内存不够：告诉作者能做什么，不只说出错了。 */
        const val TASK_TOO_LARGE = "资料太大，内存不够，本章任务没编成：先把设定包里用不到的大文档（旧版、重复的合订本）删掉或拆小，再试一次"

        /** 写作建议今天发了几次存在偏好里的键（后面接作品id）。 */
        private const val SUGGEST_COUNT_KEY = "suggest_count."
    }
}

/** 一章的审稿：[running] 是正在审的那一道（审完是 null），[review] 是已经有的结果（可能是上次存下的）。 */
data class ReviewRun(val workId: String, val chapterId: String, val running: ReviewGate?, val review: ChapterReview?)

/** 批量写摘要的进度：第 [done] 章（从 0 数）/ 共 [total] 章，正在写 [title]。 */
data class SummaryProgress(val workId: String, val done: Int, val total: Int, val title: String)

/** AI 写的本章草稿。 */
/**
 * 草稿页“这段不行”：作者点了草稿第 [index] 行那一段（[original]），选了原因 [reasons]（“其他”时写了 [note]），AI 只重写这一段（[text]）。
 * [base] 是点的时候的整份草稿：采纳时草稿没变才换。结果以“原段 / 新段”对比给作者看，采纳或放弃。
 */
data class ParagraphFix(
    val workId: String,
    val chapterId: String,
    val index: Int,
    val original: String,
    val base: String,
    val reasons: Set<FixReason>,
    val note: String = "",
    val loading: Boolean = true,
    val text: String = "",
    val error: String? = null,
    val usage: AiUsage? = null,
    val cost: AiCost? = null,
)

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
    /** 审读这一版时用的本次要求和篇幅：采纳时按它重算任务，界面上新改的要求只用于下一次写。 */
    val taskInstruction: String? = null,
    val taskTargetLength: String? = null,
    /** 这一次运行的提示（比如失败后恢复了上一版）：只给作者看，不参与采纳判断。 */
    val notice: String? = null,
    /** 逐拍写的进度（和正文一起存盘的那份，见 [DraftBeats]）；不是逐拍写的为空。 */
    val beats: DraftBeats? = null,
    /** 正在写第几拍（从 1 数）；只在写的时候有意义。 */
    val beatWriting: Int? = null,
    /** 正在写的这一拍之前、已经写完存好的全文：这时停下就退回它，写了一半的那一拍不留。 */
    val beatsText: String? = null,
    /** 一键写本章正在做的写前准备（拟细纲、拆节拍）；只在写的时候有意义，见 [DraftPrep]。 */
    val prep: DraftPrep? = null,
    /**
     * 一键写本章刚补过细纲卡（拟了细纲或拆了节拍）以后的本章任务：草稿页拿它刷新细纲卡和任务，不再自己另编一次；
     * 用过就清掉（[WritingAi.preparedSeen]）。
     */
    val prepared: ChapterWritingTask? = null,
    /**
     * 接着写、审读这一稿时范本这一关没过的原因（草稿页按它给“重新核验范本”“不用范本，直接审读这一稿”这些出路）；
     * 没拦过为空。不存盘：重开以后再点一次就又有了。
     */
    val referenceIssue: DraftReferenceIssue? = null,
)

/** 一键写本章写之前多出来的两步（进度里显示）。 */
enum class DraftPrep(val label: String) { PLAN("正在拟细纲……"), BEATS("正在拆节拍……") }

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
    /** 模型不支持工具，这次没带工具整理：没能按需查设定包，结果可能漏掉冲突。 */
    val noTools: Boolean = false,
    /** 顺带写的本章摘要（“这章写完了”才要；没要、分了段、模型没回都是空的）。 */
    val summary: String = "",
) {
    companion object {
        /** 没带工具整理时结果页的提示。 */
        const val NO_TOOLS_NOTE = "这次没能查设定包（模型不支持工具调用），结果可能漏掉冲突"
    }
}

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

