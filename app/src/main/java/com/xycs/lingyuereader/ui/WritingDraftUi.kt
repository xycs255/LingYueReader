package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
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
    var card by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadDraft(workId, chapterId)
        card = vm.writing.cards(workId, chapterId).first.card
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
    val canWrite = aiConfig.ready && chapter?.locked != true && taskError == null && task != null && task?.blocking?.isEmpty() == true
    fun startDraft(more: Boolean = false, reviewOnly: Boolean = false) {
        val action = {
            if (reviewOnly) vm.writingAi.reviewDraft(workId, chapterId, instruction, targetLength)
            else vm.writingAi.writeDraft(workId, chapterId, instruction, more = more, workflow = workflow, targetLength = targetLength)
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
                GroupedSection(
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
                        row { NavRow("本章写作任务", value = currentTask.targetLength) { showTask = true } }
                    }
                    if (card?.isEmpty == true) row { NavRow("先写细纲卡") { vm.open(Screen.ChapterCard(workId, chapterId)) } }
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
                                DraftMode.REWRITE -> ActionRow("重写一版", enabled = canWrite) { startDraft() }
                            }
                        }
                    }
                    if (text.isNotBlank() && !loading) {
                        row { ActionRow("接着写", enabled = canWrite) { startDraft(more = true) } }
                        row { ActionRow("审读并修订此稿", enabled = canWrite) { startDraft(reviewOnly = true) } }
                    }
                    if (sent != null && sent?.label in setOf("写本章", "接着写", "本稿审读", "本稿修订")) {
                        row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.size} 字" }) { showSent = true } }
                    }
                }
                // 草稿那一组：开始写时长出来，不要这版时收起。组名只写“草稿”，字数和进度写在卡片第一行。
                AnimatedVisibility(
                    visible = text.isNotBlank() || loading || draft?.error != null,
                    enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                    exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                ) {
                    GroupedSection(header = "草稿") {
                        row {
                            Column {
                                DraftStatusRow(WordCount.of(text), DraftStatus(loading, lookup, hits, draft?.takeIf { it.workflow }?.stage?.label))
                                if (!loading) UsageNote(draft?.usage, draft?.cost, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
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
                        if (text.isNotBlank() && !loading) {
                            val reviewed = draft?.workflow != true || (draft.stage == DraftStage.READY && draft.assessment?.passed == true && task?.fingerprint == draft.assessment.materials)
                            row { ActionRow("修改此稿", enabled = !locked) { editedDraft = text; editingDraft = true } }
                            row { ActionRow("放进正文", enabled = !locked && reviewed) { placing = true } }
                            if (!reviewed) row { Text("本稿还不能采纳：先处理必改问题、资料缺项，或重新审读。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                            row { ActionRow("不要这版", destructive = true) { vm.writingAi.clearDraft(workId, chapterId) } }
                        }
                    }
                }
                draft?.assessment?.let { report ->
                    GroupedSection(header = "本稿审读", footer = "结果只对应这一版稿件与项目资料。改稿或资料变化后要重新审读；流程通过不代表作者已定稿。") {
                        row {
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
            }
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
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
