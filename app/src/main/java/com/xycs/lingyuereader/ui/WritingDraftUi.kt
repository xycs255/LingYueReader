package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.CrashLog
import com.xycs.lingyuereader.DraftReferenceIssue
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.Beat
import com.xycs.lingyuereader.data.beats
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferenceExcerpt
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.ProseBudget
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.FixReason
import com.xycs.lingyuereader.ParagraphFix
import com.xycs.lingyuereader.ai.AiPricing
import com.xycs.lingyuereader.ai.AiRequestBudgetPreview
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 草稿那张卡片第一行右边：在写（转圈、写到哪 / 在查什么），写完按检查规则的结果。 */
private data class DraftStatus(val loading: Boolean, val lookup: String?, val hits: Int?, val stage: String? = null)

/** 草稿页的主按钮是哪一个：停止、写本章、重写一版。 */
private enum class DraftMode { STOP, WRITE, REWRITE }

/** The preview covers the first call only; later review, revision and lookups have unknown sizes. */
/** 写本章一共发几次请求：写前准备、写正文（逐拍写一拍一次）、审读修订。 */
internal fun draftRequestsLine(p: AiRequestBudgetPreview): String {
    fun range(a: Int, b: Int) = if (a == b) "$a" else "$a～$b"
    val parts = listOfNotNull(
        p.prepRequests.takeIf { it > 0 }?.let { "写前准备 $it 次" },
        if (p.createMax <= 1) "写正文 1 次" else "逐拍写 ${range(p.createMin, p.createMax)} 拍、每拍 1 次",
        "审读、修订 1～3 次".takeIf { p.workflow },
    )
    return "共 ${range(p.minimumStages, p.maximumStages)} 次请求：" + parts.joinToString("，")
}

private fun draftBudgetCost(preview: AiRequestBudgetPreview, usdRate: Double): String = when {
    preview.onDevice -> "本机离线模型，不产生接口费用"
    preview.firstRequestLow == null -> "未设置此模型单价，费用暂不能估算"
    preview.firstRequestHigh == null -> "输出未设置上限，首轮费用上界暂不能估算"
    else -> {
        val low = preview.firstRequestLow
        val high = preview.firstRequestHigh
        val native = "${AiPricing.money(low)}～${AiPricing.money(high)}"
        val converted = if (low.usd > 0 || high.usd > 0) {
            " · 按设置汇率约 ¥${AiPricing.amount(low.inCny(usdRate))}～¥${AiPricing.amount(high.inCny(usdRate))}"
        } else ""
        "首轮粗估：仅输入到用满输出上限 $native$converted"
    }
}

/**
 * AI 写整章草稿：按细纲卡、本章资料、上一章结尾、写作规范和检查规则写一版草稿。边写边显示，写完存成草稿；
 * 作者看过以后放进正文（替换或接在后面，原来的正文先存一版），也可以接着写、重写。AI 写的字不算码字数。
 */
@Composable
fun ChapterDraftScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val drafts by vm.writingAi.drafts.collectAsStateWithLifecycle()
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    // 一键写本章：缺细纲先拟、缺节拍先拆（作品级开关，默认开）。
    val prepOn = assists[workId]?.draftPrep ?: true
    val methodMapState by vm.writingMethodMap.state.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    val draft = drafts[workId to chapterId]
    // A saved UI override wins over an asynchronously loaded draft, including an explicit empty value.
    // Null means the author has not edited this field in this screen instance yet.
    var instructionChoice by rememberSaveable(workId, chapterId) { mutableStateOf<String?>(null) }
    var targetLengthChoice by rememberSaveable(workId, chapterId) { mutableStateOf<String?>(null) }
    var workflowChoice by rememberSaveable(workId, chapterId) { mutableStateOf<Boolean?>(null) }
    val instruction = instructionChoice ?: draft?.instruction.orEmpty()
    val targetLength = targetLengthChoice ?: draft?.targetLength.orEmpty()
    var task by remember(workId, chapterId) { mutableStateOf<ChapterWritingTask?>(null) }
    // [task] 是按哪一组（要求、篇幅）编的：预算预估、审读任务要求一样时直接用它，不再各编一次。
    var taskInput by remember(workId, chapterId) { mutableStateOf<Pair<String, String>?>(null) }
    var taskError by remember(workId, chapterId) { mutableStateOf<String?>(null) }
    var budgetPreview by remember(workId, chapterId) { mutableStateOf<AiRequestBudgetPreview?>(null) }
    var budgetPreviewError by remember(workId, chapterId) { mutableStateOf(false) }
    var showTask by remember { mutableStateOf(false) }
    var planContinuationAccepted by rememberSaveable(workId, chapterId) { mutableStateOf(false) }
    var pendingContinuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    var placing by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf(false) }
    var editedDraft by remember { mutableStateOf("") }
    var versionView by rememberSaveable(workId, chapterId) { mutableStateOf(DraftVersionView.CURRENT) }
    var directionDialog by remember { mutableStateOf(false) }
    var directionText by remember { mutableStateOf("") }
    var card by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    var analyses by remember(workId, chapterId) { mutableStateOf<List<ReferenceAnalysis>>(emptyList()) }
    var analysesLoaded by remember(workId, chapterId) { mutableStateOf(false) }
    var referenceId by remember(workId, chapterId) { mutableStateOf(vm.companionReference(workId, chapterId)) }
    var referenceDialog by remember { mutableStateOf(false) }
    var referenceToolsExpanded by rememberSaveable(workId, chapterId) { mutableStateOf(false) }
    var reviewExpanded by rememberSaveable(workId, chapterId) { mutableStateOf(false) }
    var showMethodSent by remember { mutableStateOf(false) }
    var sourceExcerpt by remember(workId, chapterId) { mutableStateOf<ReferenceExcerpt?>(null) }
    var sourceError by remember(workId, chapterId) { mutableStateOf<String?>(null) }
    var sourceLoading by remember(workId, chapterId) { mutableStateOf(false) }
    var basisExpanded by rememberSaveable(workId, chapterId) { mutableStateOf(true) }
    // “这段不行”：点的是哪一行（弹出选原因），选了哪些原因、“其他”写的那一句。
    var rejecting by remember(workId, chapterId) { mutableStateOf<Int?>(null) }
    var rejectReasons by remember(workId, chapterId) { mutableStateOf(setOf<FixReason>()) }
    var rejectNote by remember(workId, chapterId) { mutableStateOf("") }
    val paragraphFixes by vm.writingAi.paragraphFix.collectAsStateWithLifecycle()
    val paragraphFix = paragraphFixes?.takeIf { it.workId == workId && it.chapterId == chapterId }
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadDraft(workId, chapterId)
        vm.writingAi.loadAssist(workId)
        card = vm.writing.cards(workId, chapterId).first.card
        try { analyses = vm.writing.referenceAnalyses(workId, chapterId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { sourceError = "范本分析读取失败：${e.message}" }
        finally { analysesLoaded = true }
    }
    val selectedAnalysis = analyses.firstOrNull { it.id == referenceId }
    LaunchedEffect(workId, chapterId, referenceId, selectedAnalysis, analysesLoaded) {
        sourceExcerpt = null
        if (!analysesLoaded || referenceId == null) return@LaunchedEffect
        if (selectedAnalysis == null) { sourceError = "所选范本分析已不存在，请重新选择。"; return@LaunchedEffect }
        sourceError = null
        sourceLoading = true
        try {
            val maxChars = if (selectedAnalysis.part == ReferencePart.CHAPTER) 12_000 else selectedAnalysis.end - selectedAnalysis.start
            val excerpt = vm.writing.selectedChapterExcerpt(workId, selectedAnalysis.referenceId, selectedAnalysis.chapterId,
                selectedAnalysis.part, maxChars, selectedAnalysis.start, selectedAnalysis.end)
            check(excerpt != null && excerpt.start == selectedAnalysis.start && excerpt.end == selectedAnalysis.end &&
                excerpt.fingerprint == selectedAnalysis.sourceFingerprint) { "范本片段已变化，请重新分析后再写。" }
            sourceExcerpt = excerpt
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { sourceError = e.message ?: "范本片段读取失败" }
        finally { sourceLoading = false }
    }
    // 停笔 [TASK_DEBOUNCE_MS] 毫秒再编：编一次要把设定包过一遍，不能每打一个字就编一次。
    // 改了要求，这里的协程被取消，WritingAi 那边没人等的旧编译也跟着停下（单飞，见 WritingAi.writingTask）。
    LaunchedEffect(workId, chapterId, instruction, targetLength) {
        delay(TASK_DEBOUNCE_MS)
        try {
            task = vm.writingAi.writingTask(workId, chapterId, instruction, targetLength)
            taskInput = instruction to targetLength
            taskError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 内存不够这类 Error 也只是这一次没编成：报一句，不能让界面跟着崩（LaunchedEffect 里漏出去就是闪退）。
            if (e !is Exception) CrashLog.caught(e)
            taskError = e.message ?: "本章任务读取失败"
        }
    }
    // 一键写本章补过细纲卡：页面上的细纲卡换成新的；本章任务直接用那边拟完细纲重编好的（要求、篇幅没改过时），不再另编一次。
    val prepared = draft?.prepared
    LaunchedEffect(prepared) {
        val ready = prepared ?: return@LaunchedEffect
        try { card = vm.writing.cards(workId, chapterId).first.card }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { CrashLog.caught(e) }
        if (draft?.instruction == instruction && draft.targetLength == targetLength) {
            task = ready
            taskInput = instruction to targetLength
            taskError = null
        }
        vm.writingAi.preparedSeen(workId, chapterId)
    }
    // 写完、停下、出错：细纲卡可能刚被写前准备改过（停在拆节拍时也留着拟好的细纲），重读一次。
    val running = draft?.loading == true
    // 上一章的交接卡：写完、停下以后再读一次（一键写本章可能刚整理过）。
    val handoff = rememberHandoff(vm, workId, chapterId, refresh = running)
    LaunchedEffect(running) {
        if (running || card == null) return@LaunchedEffect
        try { card = vm.writing.cards(workId, chapterId).first.card }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { CrashLog.caught(e) }
    }
    // 审读绑定审读时的要求：界面上新改的要求只用于下一次写，不让当前稿的审读失效。
    // 只有审读过的稿子要比对；要求、篇幅和页面上的一样就用 [task]，不一样才另编一次。
    var reviewTask by remember(workId, chapterId) { mutableStateOf<ChapterWritingTask?>(null) }
    /** [reviewTask] 是按哪次审读、哪组要求编的：同一组就不再编。 */
    var reviewTaskFor by remember(workId, chapterId) { mutableStateOf<Any?>(null) }
    val reviewInput = draft?.let { (it.taskInstruction ?: it.instruction) to (it.taskTargetLength ?: it.targetLength) }
    val sameAsPage = reviewInput == taskInput && task != null
    // 页面上的任务还没编好、编的正是审读时的要求：等它，不另编一份。
    val pageWillServe = task == null && taskError == null && reviewInput == instruction to targetLength
    // 键里只在要求一样时才放 [task]：作者改下一次写的要求时页面上的任务一遍遍重编，审读时的要求没变，不跟着重编
    // （以前每次都另编一份，两份同时留在内存里）。
    LaunchedEffect(workId, chapterId, draft?.assessment, reviewInput, task.takeIf { sameAsPage }, pageWillServe) {
        val key = draft?.assessment to reviewInput
        val reuse = reviewTask?.takeIf { reviewTaskFor == key }
        reviewTask = when {
            draft?.assessment == null -> null
            sameAsPage -> task
            pageWillServe -> null
            reuse != null -> reuse
            else -> try { vm.writingAi.reviewTask(workId, chapterId) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { if (e !is Exception) CrashLog.caught(e); null }
        }
        reviewTaskFor = if (reviewTask != null) key else null
    }
    val reviewCurrent = draft?.assessment?.let { report -> reviewTask?.fingerprint == report.materials } == true
    // 项目章一律要审读才能采纳，与 draftBlocked 一致，不看开关。
    val reviewRequired = draft?.workflow == true || task?.project == true || reviewTask?.project == true
    val text = draft?.text.orEmpty()
    val words = WordCount.of(text)
    // 整章草稿比目标长 30% 以上：字数旁标出来，下面可以一键压到目标。全章目标是区间时按区间上限算。
    val lengthText = task?.targetLength ?: draft?.taskTargetLength ?: targetLength
    val lengthBudget = remember(lengthText) { ProseBudget.parse(lengthText) }
    val overBy = lengthBudget?.let { b ->
        val ref = if (b.range) b.high else b.target
        ((words - ref) * 100 / ref).takeIf { words * 10 > ref * 13 }
    }
    // 本章细纲卡的节拍：有就逐拍写（见 WritingAi.writeBeats），这里只读地列出来。
    val beatList = remember(card) { card?.let(::beats).orEmpty() }
    val counterpart = draft?.alternateText ?: draft?.initialText
    val counterpartLabel = if (draft?.alternateText != null) "对照修订稿" else "创作初稿"
    val loading = draft?.loading == true
    val savedWorkflowChoice = workflowChoice ?: draft?.workflowChoice
    val workflow = savedWorkflowChoice ?: (draft?.workflow == true || task?.project == true)
    LaunchedEffect(workId, chapterId, draft != null, instructionChoice, targetLengthChoice, savedWorkflowChoice) {
        if (draft == null || instructionChoice == null && targetLengthChoice == null && workflowChoice == null) return@LaunchedEffect
        delay(250)
        // Persist only an actual author choice. The project default may still be loading here.
        vm.writingAi.saveDraftIntent(workId, chapterId, instruction, targetLength, savedWorkflowChoice)
    }
    val revisionWriteBlocked = draft?.revisionWarning?.contains("无法安全留底") == true
    val canAsk = draft != null && !revisionWriteBlocked && aiConfig.ready && chapter?.locked != true && taskError == null && task != null
    val canReview = canAsk && task?.blocking?.isEmpty() == true
    // 一键写本章时，“没有事实与结果”那条不拦：写之前 AI 先拟一份补上。别的缺项照旧。
    val writeBlocking = task?.let { if (prepOn) it.prepBlocking else it.blocking }.orEmpty()
    val canWrite = canAsk && writeBlocking.isEmpty() &&
        (referenceId == null || !sourceLoading && sourceExcerpt != null && sourceError == null)
    // 写本章前要补的几步：缺事实与结果拟一份、缺节拍拆一次，各一次小请求（算在“拟细纲”类）。
    val prepNames = if (!prepOn || card == null) emptyList() else listOfNotNull(
        "拟细纲".takeIf { card?.facts.isNullOrBlank() }, "拆节拍".takeIf { beatsOf(card).isEmpty() })
    // 预估按 [task] 编好时的要求、篇幅算，直接用编好的任务：改要求时等 [task] 重编完再估一次，不另外编。
    LaunchedEffect(workId, chapterId, taskInput, workflow, referenceId, text,
        sourceExcerpt?.fingerprint, task?.fingerprint, aiConfig, loading) {
        budgetPreview = null
        budgetPreviewError = false
        val input = taskInput
        val compiled = task
        if (!aiConfig.ready || aiConfig.onDevice || loading || text.isNotBlank() || compiled == null || input == null || taskError != null ||
            referenceId != null && (sourceLoading || sourceError != null || sourceExcerpt == null)) return@LaunchedEffect
        delay(300)
        try {
            budgetPreview = vm.writingAi.previewDraftRequest(workId, chapterId, input.first, input.second, workflow, referenceId, compiled)
            if (budgetPreview == null) budgetPreviewError = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 以前只接 Exception：算预估时内存不够（OutOfMemoryError 是 Error）就从这里漏出去闪退。
            if (e !is Exception) CrashLog.caught(e)
            budgetPreviewError = true
        }
    }
    // A map belongs to an exact selected source. A completed graphic is shown only after
    // the store has rechecked both that source and the current chapter task.
    val activeMap = methodMapState?.takeIf { map ->
        map.workId == workId && map.chapterId == chapterId && map.analysisId == referenceId &&
            sourceExcerpt?.let { map.sourceFingerprint.isEmpty() || it.fingerprint == map.sourceFingerprint } == true
    }
    var verifiedMap by remember(workId, chapterId, referenceId) { mutableStateOf<com.xycs.lingyuereader.WritingMethodMapState?>(null) }
    LaunchedEffect(activeMap, sourceExcerpt?.fingerprint, task?.fingerprint) {
        verifiedMap = null
        if (activeMap != null && !activeMap.loading && !activeMap.stale && activeMap.error == null &&
            activeMap.note?.startsWith("已停止") != true &&
            vm.writingMethodMap.current(workId, chapterId, activeMap.analysisId)) {
            verifiedMap = activeMap
        }
    }
    /** [ask] 是这次给 AI 的要求：默认是页面上的；“按这个方向重写”刚改了方向，状态还没重组，直接传新的。 */
    fun startDraft(more: Boolean = false, reviewOnly: Boolean = false, ask: String = instruction) {
        val action = {
            if (reviewOnly) vm.writingAi.reviewDraft(workId, chapterId, ask, targetLength)
            else vm.writingAi.writeDraft(workId, chapterId, ask, more = more, workflow = workflow,
                targetLength = targetLength, referenceAnalysisId = referenceId, prepare = !more)
        }
        if (task?.project == true && task?.previousLabel?.startsWith("以细纲结果承接") == true && !planContinuationAccepted) {
            pendingContinuation = action
        } else action()
    }
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // 写完以后按检查规则看一遍（本机算，整章扫一遍放在后台；写的途中不算）。查不了（规则读不出）就不显示，不能让界面跟着崩。
    val hits by produceState<Int?>(null, text, loading) {
        value = if (loading || text.isBlank()) null else try {
            vm.writing.rules(workId).takeIf { !it.isEmpty }?.let { rules -> withContext(Dispatchers.Default) { CheckRules.check(text, rules).hits.size } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.caught(e)
            null
        }
    }
    val snackbar = rememberWritingMessages(vm)
    val locked = chapter?.locked == true
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()
    var showSent by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 边写边显示时跟着滚到底，看得到新写的字；作者往上翻着看时（离底超过一屏多）不打扰。减少动效时直接跳过去。
    LaunchedEffect(text.length, loading) {
        if (!loading) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (last.index != info.totalItemsCount - 1) return@LaunchedEffect
        val below = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (below in 1..FOLLOW_LIMIT) {
            if (Effects.noMotion) listState.scrollBy(below.toFloat()) else listState.animateScrollBy(below.toFloat(), Motion.standard(Motion.SHORT))
        }
    }

    SubPageScaffold("AI 写整章草稿", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().imePadding().overScrollVertical(),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                DraftWorkflowStrip(when {
                    loading -> 2
                    text.isNotBlank() -> 3
                    sourceExcerpt != null -> 1
                    else -> 0
                })
                // 写过（或正在写）一版：上方列出这一版按的细纲和节拍，AI 拟的挂小标签，作者先看依据再看正文。
                // “接上一章”：有上一章就显示（写之前也能先看、先改），交接卡在这一栏里展开。
                val basis = card
                val showBasis = (text.isNotBlank() || loading) && basis != null && (basis.facts.isNotBlank() || beatList.isNotEmpty())
                if (showBasis || handoff.view != null) {
                    DraftBasisSection(basis, beatList, showBasis, basisExpanded, onToggle = { basisExpanded = !basisExpanded },
                        canRewrite = canWrite && !loading,
                        onRewrite = { startDraft() }) {
                        handoffRows(handoff, aiReady = aiConfig.ready && !aiConfig.onDevice, onEdit = { handoff.editing = true },
                            onRefresh = { handoff.refresh(vm, scope) })
                    }
                }
                GroupedSection(
                    header = "你的构思",
                    footer = when {
                        locked -> "这一章已定稿，不能让 AI 重写。要改先解锁并登记原因。"
                        !aiConfig.ready -> "先在“我的 → AI 服务”里设置服务商。"
                        taskError != null -> taskError
                        draft == null -> "正在读取已保存的候选稿……"
                        task == null -> "正在核对本章任务与资料……"
                        workflow && writeBlocking.isNotEmpty() -> writeBlocking.joinToString("；") +
                            if (prepOn && writeBlocking.any { "章组" in it }) "。" + ChapterWritingTask.GROUP_HINT else ""
                        prepNames.isNotEmpty() -> "写本章时，AI 先" + prepNames.joinToString("、") +
                            "，存进细纲卡、标成“AI 拟的”等你确认，再一拍一拍写。也可以先自己写细纲卡。"
                        card?.isEmpty == true -> "这一章还没有细纲卡。项目包写作需要先确定本章事实与章界。"
                        workflow -> "创作、审读与修订分开进行；最多自动修订一轮，并重新审读。会发起多次 AI 请求，实际用量显示在稿件下方。通过后仍等你采纳，不会自动定稿。"
                        else -> "按本章资料写一版候选稿。篇幅依本章细纲或这次明确要求；参考样章、规划和未定稿正文不能当成已定前文。"
                    },
                ) {
                    row { DraftIdeaSummary(card) }
                    row {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            GroupedTextField(
                                value = instruction,
                                onValueChange = { instructionChoice = it },
                                label = "这一章想怎么写（可空）· 比如：多写对白，节奏快一点",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
                            )
                        }
                    }
                    row {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            GroupedTextField(
                                value = targetLength, onValueChange = { targetLengthChoice = it },
                                label = "目标篇幅（可空，默认依本章细纲）",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 100.dp),
                            )
                        }
                    }
                    row {
                        ToggleRow("创作后审读、修订", checked = workflow, subtitle = "按项目包核事实、专审对白与语气；审美建议另列，不把报告写进正文。") {
                            workflowChoice = it
                        }
                    }
                    row {
                        ToggleRow("缺细纲、节拍时 AI 先补上", checked = prepOn, subtitle = DRAFT_PREP_NOTE) { on ->
                            vm.writingAi.updateAssist(workId) { it.copy(draftPrep = on) }
                        }
                    }
                    task?.let { currentTask ->
                        row {
                            NavRow(
                                "本章写作任务",
                                value = currentTask.targetLength,
                                subtitle = "${currentTask.sourceNames.size} 份材料 · ${currentTask.previousLabel}",
                                subtitleMaxLines = 1,
                            ) { showTask = true }
                        }
                    }
                    // 本页唯一一处去细纲卡的入口（上面“你的构思”就是细纲卡里的几项；节拍也在细纲卡里改）。
                    row { NavRow(if (card?.isEmpty == true) "先写细纲卡" else "编辑细纲卡") { vm.open(Screen.ChapterCard(workId, chapterId)) } }
                }
                if (beatList.isNotEmpty()) {
                    val written = draft?.beats?.takeIf { it.total == beatList.size && text.isNotBlank() }?.written ?: 0
                    GroupedSection(
                        header = "节拍",
                        footer = "本章有节拍：一拍一拍地写，每拍存一次候选稿，中途停下“接着写”从下一拍开始。节拍在细纲卡里改。",
                    ) {
                        beatList.forEach { b ->
                            row {
                                InfoRow(
                                    "${b.index}. ${b.text}",
                                    listOfNotNull(if (b.index <= written) "已写进候选稿" else null, b.length?.let { "约 $it 字" })
                                        .joinToString(" · ").ifEmpty { "篇幅按全章平均分" },
                                )
                            }
                        }
                    }
                }
                GroupedSection(
                    header = if (text.isBlank()) "开始创作" else "继续处理",
                    footer = if (text.isBlank() && !loading) "AI 先写一版候选稿。你通读并审定后，才会进入正文。" else null,
                ) {
                    if (!loading && aiConfig.ready && task != null) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                val preview = if (text.isBlank()) budgetPreview else null
                                Text(if (text.isBlank()) "写本章请求预算" else "后续 AI 请求", color = c.label, fontSize = TypeScale.Caption)
                                when {
                                    text.isNotBlank() -> Text("接着写与审读会另行发送请求，当前暂未估算。", color = c.secondary, fontSize = TypeScale.Footnote)
                                    aiConfig.onDevice -> Text("本机离线模型，不产生接口费用；可写范围仍受设备性能和模型上下文限制。",
                                        color = c.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine)
                                    preview != null -> {
                                        Text("${preview.provider} · ${preview.model} · 首轮输入约 ${AiPricing.tokens(preview.inputTokens)} token" +
                                            (preview.outputTokenLimit?.let { " · 输出最多 ${AiPricing.tokens(it.toLong())} token（含思考）" } ?: ""),
                                            color = c.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine)
                                        Text(draftBudgetCost(preview, aiConfig.usdRate), color = c.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine)
                                        Text(draftRequestsLine(preview) +
                                            if (preview.tools) "；查资料时每次最多追加 ${preview.maxToolRounds} 次请求" else "",
                                            color = c.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine)
                                        if (preview.exceedsPlanningBudget) Text(
                                            "输入与输出预留超出灵阅 ${AiPricing.tokens(preview.planningTokens.toLong())} token 的规划预算；建议减少常驻资料。不会自动截取或阻止发送。",
                                            color = c.warning, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine,
                                        )
                                    }
                                    budgetPreviewError -> Text("暂不能估算本次用量；仍可由你决定是否写作。", color = c.secondary, fontSize = TypeScale.Footnote)
                                    else -> Text("正在按本章资料估算……", color = c.secondary, fontSize = TypeScale.Footnote)
                                }
                                if (text.isBlank() && !aiConfig.onDevice) Text("首轮费用含可能的缓存写入，仅为粗估范围，非封顶；缓存命中不保证。审读、修订与查阅还会产生费用。",
                                    color = c.secondary, fontSize = TypeScale.Micro, lineHeight = TypeScale.MicroLine)
                                if (text.isBlank() && prepNames.isNotEmpty() && writeBlocking.isEmpty()) Text(
                                    "写之前另加约 ${prepNames.size} 次小请求（${prepNames.joinToString("、")}，用量记在“拟细纲”类），已算进上面的请求次数；首轮按现在的细纲卡估。",
                                    color = c.secondary, fontSize = TypeScale.Micro, lineHeight = TypeScale.MicroLine)
                            }
                        }
                    }
                    row {
                        val mode = when {
                            loading -> DraftMode.STOP
                            text.isBlank() -> DraftMode.WRITE
                            else -> DraftMode.REWRITE
                        }
                        // 写本章 ↔ 停止 ↔ 重写一版：原地淡入淡出，不一下换字。
                        AnimatedContent(mode, transitionSpec = { Motion.fadeThrough() }, label = "draftAction") { m ->
                            when (m) {
                                DraftMode.STOP -> ActionRow("停止", destructive = true) { vm.writingAi.stopDraft(workId, chapterId) }
                                DraftMode.WRITE -> ActionRow("写本章", enabled = canWrite) { startDraft() }
                                DraftMode.REWRITE -> Text("AI 已写出候选稿，请在下方审定。", color = c.secondary, fontSize = TypeScale.Caption,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                        }
                    }
                    if (text.isNotBlank() && !loading) {
                        // 逐拍写到一半：从下一拍接着写。
                        val nextBeat = draft?.beats?.takeIf { it.total == beatList.size && it.written < it.total }?.written?.plus(1)
                        row { ActionRow(nextBeat?.let { "接着写第 $it 拍" } ?: "接着写", enabled = canWrite) { startDraft(more = true) } }
                        row { ActionRow("审读并修订此稿", enabled = canReview) { startDraft(reviewOnly = true) } }
                    }
                    if (sent != null && sent?.label in setOf("写本章", "接着写", "本稿审读", "本稿修订")) {
                        row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.size} 字" }) { showSent = true } }
                    }
                }
                // 已写的这一稿过不了范本这一关（标记丢了、范本变了、和范本太像）：给出路，不只说“请从头重写”。
                val sourceIssue = draft?.takeIf { !loading && text.isNotBlank() && it.referenceAnalysisId != null && !it.referenceProvenanceBlocked }
                    ?.let { it.referenceIssue ?: DraftReferenceIssue.UNVERIFIED.takeIf { _ -> !it.referenceValidated } }
                if (sourceIssue != null) {
                    GroupedSection(
                        header = "范本核验",
                        footer = when (sourceIssue) {
                            DraftReferenceIssue.UNVERIFIED -> "这一稿还没核验和范本有没有照抄（改过、压过篇幅，或写到一半停下）。范本没变，重新核验一遍，通过了就能接着写、审读。"
                            DraftReferenceIssue.CHANGED -> "写这一稿时用的范本变了或读不到，没法按原来的范本核验。可以去掉范本：先拿范本现在的原文查一遍有没有照抄，没有就直接审读；查不了再从头重写。"
                            DraftReferenceIssue.COPIED -> "这一稿有地方和范本太像（见上方说明）。改掉那几处再重新核验，或者从头重写。"
                        },
                    ) {
                        if (sourceIssue != DraftReferenceIssue.CHANGED) row {
                            ActionRow("重新核验范本", enabled = !locked && !revisionWriteBlocked) { vm.writingAi.recheckDraftReference(workId, chapterId) }
                        }
                        row {
                            ActionRow("不用范本，直接审读这一稿", enabled = canReview) {
                                val action = {
                                    vm.writingAi.reviewWithoutReference(workId, chapterId, instruction, targetLength) {
                                        // 这一稿不再挂范本：下次写用的范本选择也清掉，免得“接着写”又要求沿用范本。
                                        vm.clearCompanionReference(workId, chapterId)
                                        referenceId = null
                                    }
                                }
                                if (task?.project == true && task?.previousLabel?.startsWith("以细纲结果承接") == true && !planContinuationAccepted) {
                                    pendingContinuation = action
                                } else action()
                            }
                        }
                        row { ActionRow("从头重写", enabled = canWrite) { startDraft() } }
                    }
                }
                GroupedSection(
                    header = "AI 拟写法",
                    footer = when {
                        referenceId == null -> "可不选范本。AI 仍会依据本章细纲和项目资料写正文。"
                        sourceError != null -> sourceError
                        else -> "这项选择用于下一次 AI 写作，不会改变已写稿。只借鉴叙事与对白技法，不照搬人物、事件和句子。"
                    },
                ) {
                    row {
                        NavRow("写作范本", value = selectedAnalysis?.let { "《${it.referenceTitle}》·${it.chapterTitle}" } ?: "不使用范本",
                            subtitle = if (sourceLoading) "正在核对所选原文……" else sourceExcerpt?.let { "${it.part.label} · 第 ${it.start + 1}—${it.end} 字符" }) {
                            referenceDialog = true
                        }
                    }
                    if (referenceId != null && sourceError != null) {
                        row { ActionRow("重新分析范本") { vm.open(Screen.WritingReference(workId, chapterId)) } }
                    }
                    if (sourceExcerpt != null && selectedAnalysis != null) {
                        row {
                            NavRow(
                                title = if (referenceToolsExpanded) "收起写法图与原文范围" else "展开写法图与原文范围",
                                value = when {
                                    activeMap?.loading == true -> "生成中"
                                    activeMap?.error != null || activeMap?.stale == true -> "需核对"
                                    verifiedMap != null -> "已生成"
                                    else -> "未生成"
                                },
                            ) { referenceToolsExpanded = !referenceToolsExpanded }
                        }
                    }
                    if (referenceToolsExpanded) {
                        sourceExcerpt?.let { excerpt ->
                            row {
                                ReferenceRangeRuler(excerpt.start, excerpt.end, excerpt.chapterLength, excerpt.part,
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                            }
                        }
                    }
                    if (referenceToolsExpanded && sourceExcerpt != null && selectedAnalysis != null) {
                        row {
                            if (activeMap?.loading == true) {
                                ActionRow("停止生成写法图", destructive = true) { vm.writingMethodMap.stop() }
                            } else {
                                ActionRow("生成写法图（会发送一次 AI 请求）", enabled = aiConfig.ready && !sourceLoading && sourceError == null) {
                                    vm.writingMethodMap.generate(workId, chapterId, selectedAnalysis.id)
                                }
                            }
                        }
                        activeMap?.let { map ->
                            if (map.note?.startsWith("已停止") == true) {
                                row { Text(map.note.orEmpty(), color = c.secondary, fontSize = TypeScale.Caption,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            } else if (map.loading || map.error != null || map.stale || verifiedMap === map) {
                                row { VerifiedMethodMapPanel(map) }
                            } else {
                                row { Text("正在复核这张写法图的来源与本章资料……", color = c.secondary, fontSize = TypeScale.Caption,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            }
                            map.request?.let { request ->
                                row { NavRow("看写法图发给 AI 的内容", value = "${request.size} 字") { showMethodSent = true } }
                            }
                        }
                    }
                }
                // AI 正文与人的审定分开：候选稿不会自动进入正文。
                AnimatedVisibility(
                    visible = text.isNotBlank() || loading || draft?.error != null || draft?.notice != null,
                    enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                    exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                ) {
                    GroupedSection(header = "AI 正文草稿") {
                        row {
                            Column {
                                val writingBeat = draft?.beatWriting?.takeIf { loading && (!draft.workflow || draft.stage == DraftStage.CREATING) }
                                val preparing = draft?.prep?.takeIf { loading }
                                DraftStatusRow(words, DraftStatus(loading, lookup, hits,
                                    // 写之前先拟细纲、拆节拍；逐拍写时说写到第几拍。
                                    preparing?.label ?: writingBeat?.let { "正在写第 $it / ${beatList.size} 拍" } ?: draft?.takeIf { it.workflow }?.stage?.let { stage ->
                                    // 和“本稿审读”同一个判断：资料或要求变了，不再显示“可供采纳”。
                                    if (stage == DraftStage.READY && !loading && !reviewCurrent) STALE_REVIEW else stage.label
                                }), over = overBy?.takeIf { !loading })
                                if (!loading) UsageNote(draft?.usage, draft?.cost, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                            }
                        }
                        draft?.referenceAnalysisId?.let { usedId ->
                            analyses.firstOrNull { it.id == usedId }?.let { used ->
                                row { Text("本稿写作参考：《${used.referenceTitle}》 · ${used.chapterTitle}（${used.part.label}）",
                                    color = c.secondary, fontSize = TypeScale.Footnote, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                            }
                        }
                        draft?.error?.let { e -> row { ErrorText((if (draft.workflow) "本次流程未完成：" else "没写成：") + e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                        draft?.revisionWarning?.let { warning -> row { ErrorText(warning, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) } }
                        draft?.notice?.takeIf { !loading }?.let { notice -> row { Text(notice, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } }
                        if (text.isNotBlank()) {
                            if (counterpart != null) row {
                                SegmentedControl(listOf(DraftVersionView.CURRENT to "当前稿",
                                    DraftVersionView.COUNTERPART to counterpartLabel,
                                    DraftVersionView.DIFFERENCE to "对比"), versionView,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { versionView = it }
                            }
                            row {
                                // 写完、不在审读时每段能点“这段不行”；在写、在审读、在重写别的段时按不了。
                                DraftRevisionBody(if (counterpart == null) DraftVersionView.CURRENT else versionView,
                                    text, counterpart, counterpartLabel,
                                    onReject = if (loading) null else { i -> rejectReasons = emptySet(); rejectNote = ""; rejecting = i },
                                    rejectEnabled = !locked && !revisionWriteBlocked && aiConfig.ready && draft?.referenceProvenanceBlocked == false &&
                                        paragraphFix?.loading != true)
                            }
                        }
                    }
                }
                paragraphFix?.let { fix -> ParagraphFixSection(fix, locked = locked,
                    onAccept = { vm.writingAi.acceptParagraphFix() }, onDrop = { vm.writingAi.dropParagraphFix() }) }
                draft?.assessment?.let { report ->
                    GroupedSection(header = "本稿审读", footer = "结果只对应这一版稿件与项目资料。改稿或资料变化后要重新审读；流程通过不代表作者已定稿。") {
                        row {
                            NavRow(if (reviewExpanded) "收起审读证据与建议" else "展开审读证据与建议",
                                value = if (draft.stage == DraftStage.READY && report.passed && reviewCurrent) "可供审定" else "待核对") {
                                reviewExpanded = !reviewExpanded
                            }
                        }
                        if (reviewExpanded) row {
                            SelectionContainer {
                                Text(
                                    report.render().ifBlank { "本次审读未提出必改问题，仍需你通读和采纳。" },
                                    color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                }
                if (text.isNotBlank() && !loading) {
                    val reviewed = !reviewRequired || (draft?.stage == DraftStage.READY && draft.assessment?.passed == true && reviewCurrent)
                    val sourceChecked = draft?.referenceProvenanceBlocked == false
                    val sourceSummary = when {
                        !sourceChecked -> "来源：范本片段待核验"
                        draft.referenceAnalysisId != null && draft.referenceValidated -> "来源：范本片段已核验 · 本章任务资料"
                        draft.referenceAnalysisId != null -> "来源：范本片段将在采纳前核验"
                        else -> "来源：本章任务资料 · 未使用范本"
                    }
                    val reviewSummary = when {
                        !reviewRequired -> "审读：未启用自动审读，请自行通读"
                        draft?.workflow != true && !reviewed -> "审读：项目章要先审读本稿才能采纳"
                        reviewed -> "审读：已通过，上方可展开证据"
                        else -> "审读：未通过或已失效"
                    }
                    GroupedSection(header = "你来审定", footer = "草稿由 AI 写出。你决定修改方向、是否采纳；采纳后仍需确认定稿。") {
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(sourceSummary, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
                                Text(reviewSummary, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
                            }
                        }
                        row { ActionRow("放进正文", enabled = !locked && !revisionWriteBlocked && text.isNotBlank() && reviewed && draft?.error == null && sourceChecked) { placing = true } }
                        if (!sourceChecked && draft?.error == null) row { Text("这份候选稿所用范本尚未完成核验，请重新写一版后再采纳。", color = c.secondary, fontSize = TypeScale.Caption,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                        if (!reviewed && draft?.error == null) row { Text("本稿还不能采纳：先处理必改问题、资料缺项，或重新审读。", color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.padding(16.dp)) }
                        row { ActionRow("修改此稿", enabled = !locked && !revisionWriteBlocked) { editedDraft = text; editingDraft = true } }
                        if (overBy != null && lengthBudget != null) row {
                            ActionRow("压到约 ${lengthBudget.target} 字", enabled = !locked && !revisionWriteBlocked) {
                                vm.writingAi.condenseDraft(workId, chapterId, lengthText)
                            }
                        }
                        if (draft?.initialText != null && draft.alternateText == null && text != draft.initialText)
                            row { ActionRow("换回修订前那一版", enabled = !locked && !revisionWriteBlocked) {
                                vm.writingAi.restoreInitialDraft(workId, chapterId)
                            } }
                        if (draft?.alternateText != null && text == draft.initialText)
                            row { ActionRow("切回修订稿", enabled = !locked && !revisionWriteBlocked) {
                                vm.writingAi.switchBackToRevisedDraft(workId, chapterId)
                            } }
                        row { ActionRow("修改方向") { directionText = instruction; directionDialog = true } }
                        row { ActionRow("重写一版", enabled = canWrite) { startDraft() } }
                        row { ActionRow("不要这版", destructive = true, enabled = !revisionWriteBlocked) { vm.writingAi.clearDraft(workId, chapterId) } }
                    }
                }
            }
        }
        if (referenceDialog) {
            DraftReferenceChoiceDialog(
                analyses = analyses,
                selectedId = referenceId,
                onSelect = { id ->
                    referenceId = id
                    if (id == null) vm.clearCompanionReference(workId, chapterId)
                    else vm.chooseCompanionReference(workId, chapterId, id)
                    referenceDialog = false
                },
                onCreate = {
                    referenceDialog = false
                    vm.open(Screen.WritingReference(workId, chapterId))
                },
                onDismiss = { referenceDialog = false },
            )
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
        if (showMethodSent) activeMap?.request?.let { SentDialog(it) { showMethodSent = false } }
        if (directionDialog) {
            GlassDialog(show = true, title = "修改方向", summary = "写给 AI 的新要求，按它重写一版；这一版留着，可以切回来。",
                onDismissRequest = { directionDialog = false }) {
                GroupedTextField(value = directionText, onValueChange = { directionText = it },
                    label = "比如：对白增加试探，仍遵守本章结果", modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 250.dp))
                DialogButtons(onDismiss = { directionDialog = false }, onConfirm = {
                    instructionChoice = directionText
                    directionDialog = false
                    // 保存后直接重写，不用再点“重写一版”；现在写不了（AI 没设置、资料缺项、范本没核对好）就只存下方向。
                    if (canWrite) startDraft(ask = directionText) else vm.showMessage("方向存好了；现在还不能重写，先处理上面的提示")
                }, confirmText = "按这个方向重写")
            }
        }
        rejecting?.let { index ->
            GlassDialog(show = true, title = "这段不行", summary = "哪里不行？可以多选。AI 只重写这一段（前后各带一段作语境），写好了先对比给你看，采纳才换。",
                onDismissRequest = { rejecting = null }) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    FixReason.entries.forEach { r ->
                        CheckboxRow(r.label, checked = r in rejectReasons, dimChecked = false) { on ->
                            rejectReasons = if (on) rejectReasons + r else rejectReasons - r
                        }
                    }
                    if (FixReason.OTHER in rejectReasons) GroupedTextField(value = rejectNote, onValueChange = { rejectNote = it.replace("\n", "") },
                        label = "说一句哪里不行（可空）", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                DialogButtons(onDismiss = { rejecting = null }, onConfirm = {
                    vm.writingAi.fixParagraph(workId, chapterId, index, rejectReasons, rejectNote)
                    rejecting = null
                }, confirmText = "重写这段", confirmEnabled = rejectReasons.isNotEmpty())
            }
        }
        HandoffEditDialog(vm, handoff, scope)
        if (editingDraft) {
            GlassDialog(show = true, title = "修改候选稿", summary = "只修改这份候选稿。保存后旧审读结果失效，需要针对新稿重新审读。", onDismissRequest = { editingDraft = false }) {
                GroupedTextField(value = editedDraft, onValueChange = { editedDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp))
                DialogButtons(onDismiss = { editingDraft = false }, onConfirm = {
                    vm.writingAi.updateDraftText(workId, chapterId, editedDraft)
                    editingDraft = false
                }, confirmText = "保存候选稿", confirmEnabled = editedDraft.isNotBlank())
            }
        }
        pendingContinuation?.let { action ->
            GlassDialog(
                show = true, title = "前文承接依据",
                summary = "紧邻前章还没有用户定稿正文。允许本次按现行细纲中已经确定的结果续接吗？规划和候选稿仍不视为已经发生的正文。",
                onDismissRequest = { pendingContinuation = null },
            ) {
                DialogButtons(onDismiss = { pendingContinuation = null }, onConfirm = {
                    pendingContinuation = null
                    planContinuationAccepted = true
                    action()
                }, confirmText = "按细纲结果承接")
            }
        }
        if (showTask) (draft?.task ?: task)?.let { currentTask ->
            GlassDialog(show = true, title = "本章写作任务", onDismissRequest = { showTask = false }) {
                SelectionContainer {
                    Text(currentTask.render(), fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine, modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()))
                }
                DialogOkButton("好") { showTask = false }
            }
        }
        if (placing) {
            val empty = (chapter?.words ?: 0) == 0
            fun place(replace: Boolean) {
                placing = false
                scope.launch {
                    // 采纳前要重编一次任务核对审读：这里出的任何错（含内存不够）都只报一句，不能漏出去闪退。
                    val blocked = try { vm.writingAi.draftBlocked(workId, chapterId) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) {
                            if (e !is Exception) CrashLog.caught(e)
                            e.message ?: "本章任务读取失败"
                        }
                    if (blocked != null) { vm.showMessage(blocked); return@launch }
                    vm.writing.applyDraft(workId, chapterId, text, replace) {
                        vm.writingAi.clearDraft(workId, chapterId)
                        vm.showMessage("已放进正文，仍待你确认定稿")
                        vm.back()
                    }
                }
            }
            GlassDialog(
                show = true,
                title = "放进正文",
                summary = if (empty) "这一章还没有正文，草稿直接放进去。" else "正文现在有 ${chapter?.words} 字。原来的正文会先存一个历史版本。AI 写的字不算码字数。",
                onDismissRequest = { placing = false },
            ) {
                if (empty) {
                    DialogButtons(onDismiss = { placing = false }, onConfirm = { place(true) }, confirmText = "放进正文")
                } else {
                    // 两种放法一行一项，点了就放。
                    DialogOptionList(listOf(true to "替换正文", false to "接在正文后面"), onPick = { place(it) })
                    DialogOkButton("取消", primary = false) { placing = false }
                }
            }
        }
    }
}

/** “这段不行”的结果：在写、没写成，或者原段 / 新段对比，采纳或放弃。 */
@Composable
private fun ParagraphFixSection(fix: ParagraphFix, locked: Boolean, onAccept: () -> Unit, onDrop: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedSection(
        header = "这段重写",
        footer = if (!fix.loading && fix.error == null) "采纳后草稿里只换这一段，原来那版留着能换回；审读要重新做。" else null,
    ) {
        when {
            fix.loading -> {
                row { LoadingRow("AI 在按“${fix.reasons.sortedBy { it.ordinal }.joinToString("、") { it.label }}”重写这一段……") }
                row { ActionRow("停止", destructive = true, onClick = onDrop) }
            }
            fix.error != null -> {
                row { ErrorText("这段没重写成：${fix.error}", Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
                row { ActionRow("放弃", onClick = onDrop) }
            }
            else -> {
                row {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("原段", color = c.destructive, fontSize = TypeScale.Footnote, modifier = Modifier.padding(bottom = 3.dp))
                        Text(fix.original, color = c.label, fontSize = TypeScale.Headline, lineHeight = TypeScale.HeadlineLine,
                            modifier = Modifier.fillMaxWidth().background(c.destructive.copy(alpha = 0.10f), RoundedCornerShape(Radius.Small)).padding(8.dp))
                        Text("新段", color = c.success, fontSize = TypeScale.Footnote, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
                        Text(fix.text, color = c.label, fontSize = TypeScale.Headline, lineHeight = TypeScale.HeadlineLine,
                            modifier = Modifier.fillMaxWidth().background(c.success.copy(alpha = 0.10f), RoundedCornerShape(Radius.Small)).padding(8.dp))
                        UsageNote(fix.usage, fix.cost, Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("采纳这段", onClick = onAccept, style = PillStyle.Filled, enabled = !locked)
                            PillButton("放弃", onClick = onDrop)
                        }
                    }
                }
            }
        }
    }
}

/** 细纲卡的节拍（没有卡为空）。 */
private fun beatsOf(card: ChapterCard?): List<Beat> = card?.let(::beats).orEmpty()

/**
 * 草稿页上方“这一章是按下面的细纲和节拍写的”：可折叠，列本章事实与结果和各拍；一键写本章时 AI 拟的、拆的挂小标签，
 * 作者在细纲卡里改过或确认过就没了。改细纲卡走“你的构思”里那一行“编辑细纲卡”（本页只留这一处），这里只有“按改过的重写”。
 */
@Composable
private fun DraftBasisSection(card: ChapterCard?, beatList: List<Beat>, showBasis: Boolean, expanded: Boolean, onToggle: () -> Unit,
                              canRewrite: Boolean, onRewrite: () -> Unit,
                              /** 卡片最后加的几行（“接上一章”）。 */
                              extra: GroupedSectionScope.() -> Unit = {}) {
    val c = LocalGroupedColors.current
    val pending = card != null && (card.aiOutline || card.aiBeats)
    GroupedSection(footer = if (showBasis && expanded && pending) "“AI 拟的”“AI 拆的”是写本章时 AI 补的，还没经你确认；在细纲卡里改过或点了确认，标签就没了。" else null) {
        if (showBasis && card != null) row { ExpandRow("这一章是按下面的细纲和节拍写的", expanded, value = if (pending) "待确认" else null, onToggle = onToggle) }
        if (showBasis && card != null && expanded) {
            if (card.facts.isNotBlank()) row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本章事实与结果", color = c.secondary, fontSize = TypeScale.Caption)
                        if (card.aiOutline) Tag("AI 拟的", c.warning)
                    }
                    Text(card.facts.trim(), color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                }
            }
            if (beatList.isNotEmpty()) row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("节拍（${beatList.size} 拍）", color = c.secondary, fontSize = TypeScale.Caption)
                        if (card.aiBeats) Tag("AI 拆的", c.warning)
                    }
                    beatList.forEach { b ->
                        Text("${b.index}. ${b.text}" + (b.length?.let { "（约 $it 字）" } ?: ""), color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                    }
                }
            }
            row { ActionRow("按改过的重写", enabled = canRewrite) { onRewrite() } }
        }
        extra()
    }
}

/** Only the author's actual chapter-card fields appear here; no AI-invented plot nodes. */
@Composable
private fun DraftIdeaSummary(card: ChapterCard?) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftIdeaTile("核心事件与结果", card?.facts.orEmpty(), Modifier.weight(1f))
            DraftIdeaTile("视角人物", card?.pov.orEmpty(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftIdeaTile("承接边界", card?.boundary.orEmpty(), Modifier.weight(1f))
            DraftIdeaTile("章末钩子", card?.hook.orEmpty(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun DraftIdeaTile(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(modifier.background(c.fill, RoundedCornerShape(Radius.Medium)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = c.secondary, fontSize = TypeScale.Micro)
        Text(value.trim().ifBlank { "未填写" }, color = if (value.isBlank()) c.tertiary else c.label,
            fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
    }
}

/** 审读通过，但资料或要求已经变了：状态标签不再显示“可供采纳”。 */
private const val STALE_REVIEW = "审读已过时"

/** 离底多远以内才跟着滚（像素，约一屏多）：再远说明作者往上翻着看，不拽回去。 */
private const val FOLLOW_LIMIT = 1600

/** 改“写作要求 / 篇幅”后停笔多久才重编本章任务（毫秒）。 */
internal const val TASK_DEBOUNCE_MS = 500L

/** 草稿卡片第一行：左边字数，右边在写的转圈和进度，或写完后按检查规则的结果（合规则用成功色，有不合的用提醒色）。 */
@Composable
private fun DraftStatusRow(words: Int, status: DraftStatus, over: Int? = null) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$words 字", color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium)
            // 比目标长太多：提醒色标出来（见上面算的 overBy）。
            if (over != null) Text("比目标长 $over%", color = c.warning, fontSize = TypeScale.Caption, maxLines = 1)
        }
        AnimatedContent(
            status,
            transitionSpec = { Motion.fadeThrough() },
            contentKey = { if (it.loading) "busy:${it.stage}" else "done:${it.stage}:${it.hits}" },
            contentAlignment = Alignment.CenterEnd,
            label = "draftStatus",
        ) { s ->
            when {
                s.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner()
                    Text(s.lookup?.let { "正在查：$it" } ?: s.stage ?: "正在写……", color = c.secondary, fontSize = TypeScale.Caption, maxLines = 1)
                }
                s.stage != null -> Tag(s.stage, if (s.stage == DraftStage.READY.label) c.success else c.warning)
                s.hits == 0 -> Tag("合规则", c.success)
                s.hits != null -> Tag("${s.hits} 条不合规则", c.warning)
            }
        }
    }
}
