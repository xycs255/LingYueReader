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
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferenceExcerpt
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 草稿那张卡片第一行右边：在写（转圈、写到哪 / 在查什么），写完按检查规则的结果。 */
private data class DraftStatus(val loading: Boolean, val lookup: String?, val hits: Int?, val stage: String? = null)

/** 草稿页的主按钮是哪一个：停止、写本章、重写一版。 */
private enum class DraftMode { STOP, WRITE, REWRITE }

/**
 * AI 写本章：按细纲卡、本章资料、上一章结尾、写作规范和检查规则写一版草稿。边写边显示，写完存成草稿；
 * 作者看过以后放进正文（替换或接在后面，原来的正文先存一版），也可以接着写、重写。AI 写的字不算码字数。
 */
@Composable
fun ChapterDraftScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val drafts by vm.writingAi.drafts.collectAsStateWithLifecycle()
    val methodMapState by vm.writingMethodMap.state.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    val draft = drafts[workId to chapterId]
    var instruction by rememberSaveable(workId, chapterId) { mutableStateOf(draft?.instruction.orEmpty()) }
    var targetLength by rememberSaveable(workId, chapterId) { mutableStateOf(draft?.targetLength.orEmpty()) }
    var workflowChoice by rememberSaveable(workId, chapterId) { mutableStateOf<Boolean?>(true.takeIf { draft?.workflow == true }) }
    var task by remember(workId, chapterId) { mutableStateOf<ChapterWritingTask?>(null) }
    var taskError by remember(workId, chapterId) { mutableStateOf<String?>(null) }
    var showTask by remember { mutableStateOf(false) }
    var planContinuationAccepted by rememberSaveable(workId, chapterId) { mutableStateOf(false) }
    var pendingContinuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    var placing by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf(false) }
    var editedDraft by remember { mutableStateOf("") }
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
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadDraft(workId, chapterId)
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
    LaunchedEffect(workId, chapterId, instruction, targetLength) {
        delay(250)
        runCatching { vm.writingAi.writingTask(workId, chapterId, instruction, targetLength) }
            .onSuccess { task = it; taskError = null }
            .onFailure { taskError = it.message ?: "本章任务读取失败" }
    }
    val text = draft?.text.orEmpty()
    val loading = draft?.loading == true
    val workflow = workflowChoice ?: (task?.project == true)
    val canReview = aiConfig.ready && chapter?.locked != true && taskError == null && task != null && task?.blocking?.isEmpty() == true
    val canWrite = canReview &&
        (referenceId == null || !sourceLoading && sourceExcerpt != null && sourceError == null)
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
    fun startDraft(more: Boolean = false, reviewOnly: Boolean = false) {
        val action = {
            if (reviewOnly) vm.writingAi.reviewDraft(workId, chapterId, instruction, targetLength)
            else vm.writingAi.writeDraft(workId, chapterId, instruction, more = more, workflow = workflow,
                targetLength = targetLength, referenceAnalysisId = referenceId)
        }
        if (task?.project == true && task?.previousLabel?.startsWith("以细纲结果承接") == true && !planContinuationAccepted) {
            pendingContinuation = action
        } else action()
    }
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // 写完以后按检查规则看一遍（本机算）。
    val hits by produceState<Int?>(null, text, loading) {
        value = if (loading || text.isBlank()) null else vm.writing.rules(workId).takeIf { !it.isEmpty }?.let { CheckRules.check(text, it).hits.size }
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

    SubPageScaffold("AI 写本章", onBack = { vm.back() }, snackbar = snackbar) { padding ->
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
                GroupedSection(
                    header = "你的构思",
                    footer = when {
                        locked -> "这一章已定稿，不能让 AI 重写。要改先解锁并登记原因。"
                        !aiConfig.ready -> "先在“我的 → AI 助手”里设置服务商。"
                        taskError != null -> taskError
                        task == null -> "正在核对本章任务与资料……"
                        workflow && task?.blocking?.isNotEmpty() == true -> task?.blocking?.joinToString("；")
                        card?.isEmpty == true -> "这一章还没有细纲卡。项目包写作需要先确定本章事实与章界。"
                        workflow -> "创作、审读与修订分开进行；最多自动修订一轮，并重新审读。会发起多次 AI 请求，实际用量显示在稿件下方。通过后仍等你采纳，不会自动定稿。"
                        else -> "按本章资料写一版候选稿。篇幅依本章细纲或这次明确要求；参考样章、规划和未定稿正文不能当成已定前文。"
                    },
                ) {
                    row { DraftIdeaSummary(card) }
                    row {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextField(
                                value = instruction,
                                onValueChange = { instruction = it },
                                label = "这一章想怎么写（可空）· 比如：多写对白，节奏快一点",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
                            )
                        }
                    }
                    row {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextField(
                                value = targetLength, onValueChange = { targetLength = it },
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
                    row { NavRow(if (card?.isEmpty == true) "先写细纲卡" else "编辑细纲卡") { vm.open(Screen.ChapterCard(workId, chapterId)) } }
                }
                GroupedSection(
                    header = if (text.isBlank()) "开始创作" else "继续处理",
                    footer = if (text.isBlank() && !loading) "AI 先写一版候选稿。你通读并审定后，才会进入正文。" else null,
                ) {
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
                                DraftMode.REWRITE -> Text("AI 已写出候选稿，请在下方审定。", color = c.secondary, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                        }
                    }
                    if (text.isNotBlank() && !loading) {
                        row { ActionRow("接着写", enabled = canWrite) { startDraft(more = true) } }
                        row { ActionRow("审读并修订此稿", enabled = canReview) { startDraft(reviewOnly = true) } }
                    }
                    if (sent != null && sent?.label in setOf("写本章", "接着写", "本稿审读", "本稿修订")) {
                        row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.size} 字" }) { showSent = true } }
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
                                row { Text(map.note.orEmpty(), color = c.secondary, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            } else if (map.loading || map.error != null || map.stale || verifiedMap === map) {
                                row { VerifiedMethodMapPanel(map) }
                            } else {
                                row { Text("正在复核这张写法图的来源与本章资料……", color = c.secondary, fontSize = 13.sp,
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
                    visible = text.isNotBlank() || loading || draft?.error != null,
                    enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                    exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                ) {
                    GroupedSection(header = "AI 正文草稿") {
                        row {
                            Column {
                                DraftStatusRow(WordCount.of(text), DraftStatus(loading, lookup, hits, draft?.takeIf { it.workflow }?.stage?.label))
                                if (!loading) UsageNote(draft?.usage, draft?.cost, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                            }
                        }
                        draft?.referenceAnalysisId?.let { usedId ->
                            analyses.firstOrNull { it.id == usedId }?.let { used ->
                                row { Text("本稿写作参考：《${used.referenceTitle}》 · ${used.chapterTitle}（${used.part.label}）",
                                    color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                            }
                        }
                        draft?.error?.let { e -> row { ErrorText((if (draft.workflow) "本次流程未完成：" else "没写成：") + e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                        if (text.isNotBlank()) {
                            row {
                                SelectionContainer {
                                    Text(
                                        text,
                                        color = c.label,
                                        fontSize = 17.sp,
                                        lineHeight = 30.sp,
                                        style = androidx.compose.ui.text.TextStyle(textIndent = TextIndent(firstLine = 2.em)),
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                draft?.assessment?.let { report ->
                    GroupedSection(header = "本稿审读", footer = "结果只对应这一版稿件与项目资料。改稿或资料变化后要重新审读；流程通过不代表作者已定稿。") {
                        row {
                            NavRow(if (reviewExpanded) "收起审读证据与建议" else "展开审读证据与建议",
                                value = if (draft.stage == DraftStage.READY && report.passed && task?.fingerprint == report.materials) "可供审定" else "待核对") {
                                reviewExpanded = !reviewExpanded
                            }
                        }
                        if (reviewExpanded) row {
                            SelectionContainer {
                                Text(
                                    report.render().ifBlank { "本次审读未提出必改问题，仍需你通读和采纳。" },
                                    color = c.label, fontSize = 14.sp, lineHeight = 23.sp,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                }
                if (text.isNotBlank() && !loading) {
                    val reviewed = draft?.workflow != true || (draft.stage == DraftStage.READY && draft.assessment?.passed == true && task?.fingerprint == draft.assessment.materials)
                    val sourceChecked = draft?.let { !it.referenceProvenanceBlocked && (it.referenceAnalysisId == null || it.referenceValidated) } == true
                    val sourceSummary = when {
                        !sourceChecked -> "来源：范本片段待核验"
                        draft.referenceAnalysisId != null -> "来源：范本片段已核验 · 本章任务资料"
                        else -> "来源：本章任务资料 · 未使用范本"
                    }
                    val reviewSummary = when {
                        draft?.workflow != true -> "审读：未启用自动审读，请自行通读"
                        reviewed -> "审读：已通过，上方可展开证据"
                        else -> "审读：未通过或已失效"
                    }
                    GroupedSection(header = "你来审定", footer = "草稿由 AI 写出。你决定修改方向、是否采纳；采纳后仍需确认定稿。") {
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(sourceSummary, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                                Text(reviewSummary, color = c.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                        row { ActionRow("放进正文", enabled = !locked && text.isNotBlank() && reviewed && draft?.error == null && sourceChecked) { placing = true } }
                        if (!sourceChecked && draft?.error == null) row { Text("这份候选稿所用范本尚未完成核验，请重新写一版后再采纳。", color = c.secondary, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                        if (!reviewed && draft.error == null) row { Text("本稿还不能采纳：先处理必改问题、资料缺项，或重新审读。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                        row { ActionRow("修改此稿", enabled = !locked) { editedDraft = text; editingDraft = true } }
                        row { ActionRow("修改方向") { directionText = instruction; directionDialog = true } }
                        row { ActionRow("重写一版", enabled = canWrite) { startDraft() } }
                        row { ActionRow("不要这版", destructive = true) { vm.writingAi.clearDraft(workId, chapterId) } }
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
            GlassDialog(show = true, title = "修改方向", summary = "写给 AI 的新要求。保存后点“重写一版”；当前候选稿不会立刻改变。",
                onDismissRequest = { directionDialog = false }) {
                TextField(value = directionText, onValueChange = { directionText = it },
                    label = "比如：对白增加试探，仍遵守本章结果", modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 250.dp))
                DialogButtons(onDismiss = { directionDialog = false }, onConfirm = {
                    instruction = directionText
                    directionDialog = false
                }, confirmText = "保存方向")
            }
        }
        if (editingDraft) {
            GlassDialog(show = true, title = "修改候选稿", summary = "只修改这份候选稿。保存后旧审读结果失效，需要针对新稿重新审读。", onDismissRequest = { editingDraft = false }) {
                TextField(value = editedDraft, onValueChange = { editedDraft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp))
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
                    Text(currentTask.render(), fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()))
                }
                DialogOkButton("好") { showTask = false }
            }
        }
        if (placing) {
            val empty = (chapter?.words ?: 0) == 0
            fun place(replace: Boolean) {
                placing = false
                scope.launch {
                    val blocked = vm.writingAi.draftBlocked(workId, chapterId, instruction, targetLength)
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
    Column(modifier.background(c.fill, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = c.secondary, fontSize = 11.sp)
        Text(value.trim().ifBlank { "未填写" }, color = if (value.isBlank()) c.tertiary else c.label,
            fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
    }
}

/** 离底多远以内才跟着滚（像素，约一屏多）：再远说明作者往上翻着看，不拽回去。 */
private const val FOLLOW_LIMIT = 1600

/** 草稿卡片第一行：左边字数，右边在写的转圈和进度，或写完后按检查规则的结果（合规则用成功色，有不合的用提醒色）。 */
@Composable
private fun DraftStatusRow(words: Int, status: DraftStatus) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Text("$words 字", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                    Text(s.lookup?.let { "正在查：$it" } ?: s.stage ?: "正在写……", color = c.secondary, fontSize = 14.sp, maxLines = 1)
                }
                s.stage != null -> Tag(s.stage, if (s.stage == DraftStage.READY.label) c.success else c.warning)
                s.hits == 0 -> Tag("合规则", c.success)
                s.hits != null -> Tag("${s.hits} 条不合规则", c.warning)
            }
        }
    }
}
