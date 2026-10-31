package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.FinishRun
import com.xycs.lingyuereader.FinishStage
import com.xycs.lingyuereader.FinishWrite
import com.xycs.lingyuereader.Jump
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.StepState
import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.EditLearning
import com.xycs.lingyuereader.data.LearnKind
import com.xycs.lingyuereader.data.SyncItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 整理结果超过这么多条时，确认页先折叠成一行“新设定 N 条、账本 N 条、冲突 N 条”。 */
internal const val FINISH_FOLD_AT = 6

/**
 * “这章写完了”收尾页：开始前勾选要做的（定稿固定要做；整理入账、写本章摘要、顺带三道审稿按作品记住），本机检查还有问题时提醒一句；
 * 点“开始”后一步步显示进度（可以停，审出问题时先停下问）；最后在同一页确认摘要和整理结果，点“完成收尾”依次写入、定稿。
 * 进度存在 [com.xycs.lingyuereader.WritingAi.finish] 里：离开再回来还在。
 */
@Composable
fun ChapterFinishScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val all by vm.writingAi.finish.collectAsStateWithLifecycle()
    val run = all?.takeIf { it.workId == workId && it.chapterId == chapterId }
    // 别的章还在收尾：同一时间只收一章，不掐掉它（写入时更不能断）。它完成或停下以后这里再打开。
    val blockedBy = all?.takeIf { it.busy && run == null }
    LaunchedEffect(workId, chapterId, blockedBy == null) {
        vm.writingAi.loadAssist(workId)
        vm.writingAi.openFinish(workId, chapterId)
    }
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    val snackbar = rememberWritingMessages(vm)
    // 本机检查：存盘的正文按检查规则、AI 腔清单再数一遍（和编辑器顶上那行一样的算法）。
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val assist = assists[workId]
    var local by remember(workId, chapterId) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(workId, chapterId, assist?.aiCliche, assist?.banned) {
        local = runCatching {
            val text = vm.writing.savedText(workId, chapterId)
            val rules = vm.writing.rules(workId) + AiCliche.rules(assist)
            if (rules.isEmpty) 0 to 0
            else withContext(Dispatchers.Default) { CheckRules.check(text, rules) }.hits.let { hits -> hits.count { !AiCliche.isVoice(it) } to AiCliche.count(hits) }
        }.getOrNull()
    }

    fun backToEditor() {
        val below = vm.stack.value.dropLast(1).lastOrNull()
        if (below is Screen.Editor && below.workId == workId && below.chapterId == chapterId) vm.back()
        else vm.replaceTop(Screen.Editor(workId, chapterId))
    }

    fun jump(item: SyncItem) {
        vm.writing.jump.value = Jump(item.chapterId, item.quote)
        backToEditor()
    }

    SubPageScaffold("这章写完了", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            if (run == null && blockedBy != null) {
                item(key = "blocked") {
                    val title = works.firstOrNull { it.id == blockedBy.workId }?.chapter(blockedBy.chapterId)?.title ?: "另一章"
                    FinishBlocked(title, blockedBy, onOpen = { vm.replaceTop(Screen.ChapterFinish(blockedBy.workId, blockedBy.chapterId)) }) {
                        vm.writingAi.stopFinish()
                    }
                }
                return@LazyColumn
            }
            if (run == null) {
                item(key = "loading") { GroupedSection { row { LoadingRow("正在准备……") } } }
                return@LazyColumn
            }
            when (run.stage) {
                FinishStage.SETUP -> item(key = "setup") {
                    FinishSetup(vm, run, local, onBack = { backToEditor() })
                }
                FinishStage.RUNNING, FinishStage.REVIEW_FOUND, FinishStage.AI_FAILED -> item(key = "progress") {
                    FinishProgress(vm, run, lookup, onReview = { vm.open(Screen.ChapterReview(workId, chapterId)) })
                }
                FinishStage.CONFIRM, FinishStage.APPLYING, FinishStage.DONE -> {
                    finishConfirm(vm, run, onJump = ::jump) { message ->
                        vm.writing.loadPack(workId)
                        vm.writing.loadChanges(workId)
                        vm.showMessage(message)
                        if ((vm.stack.value.lastOrNull() as? Screen.ChapterFinish)?.chapterId == chapterId) vm.back()
                    }
                }
            }
        }
    }
}

/** 别的章还在收尾时：说一声是哪章，可以去看看，或者先停下它（正在写入时停不了，只能等）。 */
@Composable
private fun FinishBlocked(title: String, other: FinishRun, onOpen: () -> Unit, onStop: () -> Unit) {
    val c = LocalGroupedColors.current
    val applying = other.stage == FinishStage.APPLYING
    GroupedSection(
        footer = if (applying) "同一时间只收一章。它正在写入设定包、定稿，不能打断，写完就轮到这一章。"
            else "同一时间只收一章。停下它会丢掉它这次的进度（已经写进去的不受影响），这一章再从头开始。",
    ) {
        row {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("《$title》正在收尾，等它完成或先停下", color = c.warning, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("去看看", onClick = onOpen, style = PillStyle.Filled)
                    if (!applying) PillButton("先停下它", onClick = onStop)
                }
            }
        }
    }
}

/** 开始前：几个勾选项（这章有 AI 原稿时多一项“从这章的改稿里学”）、本机检查的提醒、“开始”。 */
@Composable
private fun FinishSetup(vm: AppViewModel, run: FinishRun, local: Pair<Int, Int>?, onBack: () -> Unit) {
    val c = LocalGroupedColors.current
    val o = run.options
    val syncBlocked = vm.writingAi.syncBlocked
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val aiBlocked = vm.writingAi.finishAiBlocked
    fun set(options: FinishOptions) = vm.writingAi.setFinishOptions(options)
    Column {
        GroupedSection(
            header = "收尾要做的",
            footer = "整理和写摘要合成一次请求；写进去之前都在这一页给你看过、改过，点“完成收尾”才写。",
        ) {
            row { CheckboxRow("定稿", checked = true, subtitle = "固定要做：存一个历史版本，正文锁定", enabled = false, dimChecked = false) {} }
            row {
                CheckboxRow(
                    "整理进设定包和账本",
                    checked = o.sync && syncBlocked == null,
                    subtitle = syncBlocked ?: "新设定、冲突写进设定包；人物状态、发生的事、伏笔记进故事账本",
                    enabled = syncBlocked == null,
                    dimChecked = false,
                ) { set(o.copy(sync = it)) }
            }
            row {
                CheckboxRow(
                    "写本章摘要",
                    checked = o.summary && aiBlocked == null,
                    subtitle = aiBlocked ?: "150 到 300 字，存进细纲卡；写后面的章时带给 AI",
                    enabled = aiBlocked == null,
                    dimChecked = false,
                ) { set(o.copy(summary = it)) }
            }
            row {
                CheckboxRow(
                    "顺带三道审稿",
                    checked = o.review && aiBlocked == null,
                    subtitle = aiBlocked ?: "事实关、写法关、规则关；审出问题先停下问你",
                    enabled = aiBlocked == null,
                    dimChecked = false,
                ) { set(o.copy(review = it)) }
            }
            // 这章有采纳进正文的 AI 原稿才有得比。
            if (run.learnable) row {
                CheckboxRow(
                    "从这章的改稿里学",
                    checked = o.learn,
                    subtitle = "把 AI 原稿和你改后的定稿逐段比对，提出几条黑名单词和写法规则；你勾了才记下，以后写正文照着写" +
                        if (aiBlocked == null && !aiConfig.onDevice) "（另发一次请求请 AI 归纳）" else "（只在本机比对）",
                    dimChecked = false,
                ) { set(o.copy(learn = it)) }
            }
            row {
                // 交接卡要 AI 整理：本机模型不整理，写下一章时本机从结尾抽一份。
                val handoffBlocked = aiBlocked ?: if (aiConfig.onDevice) "本机模型不整理；写下一章时本机从结尾抽人名、地点" else null
                CheckboxRow(
                    "整理交接卡",
                    checked = o.handoff && handoffBlocked == null,
                    subtitle = handoffBlocked ?: "记下这一章结尾的场面、在场人物、情绪和没了结的事，写下一章时开头先接住；你改过的交接卡不盖掉",
                    enabled = handoffBlocked == null,
                    dimChecked = false,
                ) { set(o.copy(handoff = it)) }
            }
        }
        val (rules, cliche) = local ?: (0 to 0)
        if (rules > 0 || cliche > 0) {
            GroupedSection(footer = "只是提醒，不拦着：定稿后正文锁定，要改先回去改。") {
                row {
                    // 提醒是提醒色的标签，按钮是普通的灰底胶囊：以前提醒色字配淡强调色底的胶囊，一行里两种颜色混着。
                    val parts = listOfNotNull(rules.takeIf { it > 0 }?.let { "$it 条不合规则" }, cliche.takeIf { it > 0 }?.let { "$it 处 AI 腔" })
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("还有", color = c.label, fontSize = TypeScale.Body)
                            parts.forEach { Tag(it, c.warning, Modifier.align(Alignment.CenterVertically)) }
                        }
                        PillButton("回去看看", onClick = onBack)
                    }
                }
            }
        }
        PrimaryButton("开始收尾", onClick = { vm.writingAi.startFinish() }, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** 一步的状态对到 [StepRow] 的记号。 */
private fun StepState.mark(): StepMark = when (this) {
    StepState.WAITING -> StepMark.WAITING
    StepState.RUNNING -> StepMark.RUNNING
    StepState.DONE -> StepMark.DONE
    StepState.FAILED -> StepMark.FAILED
    StepState.SKIPPED -> StepMark.SKIPPED
}

/** 进行中：各步的状态，可以停；审出问题时问“回去改”还是“继续收尾”；有一步没成时可以重试。 */
@Composable
private fun FinishProgress(vm: AppViewModel, run: FinishRun, lookup: String?, onReview: () -> Unit) {
    val c = LocalGroupedColors.current
    val together = run.sync == StepState.RUNNING && run.summary == StepState.RUNNING
    Column {
        GroupedSection(header = "进度", footer = if (together) "整理和摘要合成一次请求。" else null) {
            row {
                StepRow("三道审稿", run.review.mark(), run.review.label,
                    run.reviewError ?: run.reviewIssues.takeIf { it > 0 }?.let { "审出 $it 条问题" })
            }
            row { StepRow("整理进设定包和账本", run.sync.mark(), run.sync.label, run.syncError ?: lookup?.takeIf { run.sync == StepState.RUNNING }) }
            row { StepRow("写本章摘要", run.summary.mark(), run.summary.label, run.summaryError) }
            if (run.options.learn) row { StepRow("从改稿里学", run.learn.mark(), run.learn.label, run.learnError.takeIf { run.learn == StepState.FAILED }) }
            if (run.options.handoff) row { StepRow("整理交接卡", run.handoff.mark(), run.handoff.label, run.handoffError ?: run.handoffNote) }
            if (run.digest != StepState.SKIPPED) row { StepRow("整理前情", run.digest.mark(), run.digest.label, run.digestError ?: run.digestNote) }
            row { StepRow("定稿", StepMark.WAITING, StepState.WAITING.label, "确认以后再做") }
        }
        when (run.stage) {
            FinishStage.RUNNING -> GroupedSection { row { ActionRow("停下", destructive = true) { vm.writingAi.stopFinish() } } }
            FinishStage.REVIEW_FOUND -> GroupedSection(footer = "回去改：进审稿页逐条看，改完回这里接着收尾。不管它也可以，审稿结果留在审稿页上。") {
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("审出 ${run.reviewIssues} 条问题", color = c.warning, fontSize = TypeScale.Body)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("回去改", onClick = onReview, style = PillStyle.Filled)
                            PillButton("不管，继续收尾", onClick = { vm.writingAi.continueFinish() })
                        }
                    }
                }
            }
            else -> Column {
                PrimaryButton("重试出错的步骤", onClick = { vm.writingAi.retryFinish() }, modifier = Modifier.padding(horizontal = 12.dp))
                GroupedSection(Modifier.padding(top = 10.dp)) { row { ActionRow("从头再来") { vm.writingAi.stopFinish() } } }
            }
        }
    }
}

/** 确认：摘要（可以改；细纲卡里已有的给“用新的 / 留旧的”）、整理结果（条目多时先折叠）、“完成收尾”。 */
private fun androidx.compose.foundation.lazy.LazyListScope.finishConfirm(
    vm: AppViewModel,
    run: FinishRun,
    onJump: (SyncItem) -> Unit,
    then: (String) -> Unit,
) {
    val busy = run.stage != FinishStage.CONFIRM
    if (run.options.summary && run.draft.isNotBlank()) item(key = "summary") {
        val c = LocalGroupedColors.current
        val written = FinishWrite.SUMMARY in run.wrote
        GroupedSection(
            header = "本章摘要",
            footer = if (run.oldSummary.isNotBlank()) "细纲卡里已经有摘要了：默认留旧的，选“用新的”才换成下面这段（定稿后又改过、摘要过期了的，默认用新的）。" else "写进细纲卡，写后面的章时带给 AI。",
        ) {
            if (run.oldSummary.isNotBlank()) {
                row {
                    Text("细纲卡里已有：${run.oldSummary}", color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                row {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SegmentedControl(listOf(false to "用新的", true to "留旧的"), run.keepOld, modifier = Modifier.weight(1f)) {
                            if (!busy && !written) vm.writingAi.keepOldSummary(it)
                        }
                    }
                }
            }
            if (!run.keepOld) row {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    GroupedTextField(
                        value = run.draft,
                        onValueChange = { if (!busy && !written) vm.writingAi.editFinishSummary(it) },
                        label = "AI 写的摘要（可以改）",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 260.dp),
                    )
                }
            }
        }
    }
    val session = run.session
    if (run.options.sync && session != null) {
        val items = session.items
        val repeats = session.repeats
        item(key = "sync") {
            var open by remember { mutableStateOf(setOf<String>()) }
            var unfolded by remember { mutableStateOf(items.size <= FINISH_FOLD_AT) }
            val (news, ledger, conflicts) = run.syncCounts
            GroupedSection(
                header = "整理结果",
                footer = "冲突、可能重复、前面章已有的默认不勾。勾上的才写进去；点开一条能改，依据点了回正文。",
            ) {
                if (items.isEmpty()) row { EmptyRow("这一章没有要整理的。") }
                else if (items.size > FINISH_FOLD_AT) row {
                    ExpandRow("新设定 $news 条、账本 $ledger 条、冲突 $conflicts 条", unfolded, value = "勾了 ${session.picked.size} 条") { unfolded = !unfolded }
                }
                if (unfolded) items.forEach { item ->
                    row {
                        SyncItemRow(
                            item,
                            edited = session.edited(item),
                            checked = item.key in session.picked,
                            expanded = item.key in open,
                            showChapter = false,
                            repeat = item.key in repeats,
                            // 写入中、已经写进去以后，勾选和改动在 WritingAi 里不再生效；这里照常显示勾选框，不标“待重整”。
                            selectable = true,
                            onToggle = { vm.writingAi.toggleFinishSync(item.key, item.key !in session.picked) },
                            onExpand = { open = if (item.key in open) open - item.key else open + item.key },
                            onEdit = { vm.writingAi.editFinishSyncText(item.key, it) },
                            onAspect = { vm.writingAi.editFinishSyncAspect(item.key, it) },
                            onPayoff = { vm.writingAi.editFinishSyncPayoff(item.key, it) },
                            onJump = { onJump(item) },
                        )
                    }
                }
            }
        }
    }
    // 交接卡整理好就存下了，不用再确认：这里只说一句结果（没成的原因照实说，不拦收尾）。
    if (run.options.handoff && (run.handoffError ?: run.handoffNote) != null) item(key = "handoff") {
        val c = LocalGroupedColors.current
        GroupedSection(header = "交接卡", footer = "写下一章时在草稿页“接上一章”里能看、能改。") {
            row {
                val error = run.handoffError
                if (error != null) ErrorText(error, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                else Text(run.handoffNote.orEmpty(), color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
    // 前情（故事至今、卷摘要）整理好就存下了：这里只说一句结果，没成的原因照实说，不拦收尾。
    if (run.digest != StepState.SKIPPED && (run.digestError ?: run.digestNote) != null) item(key = "digest") {
        val c = LocalGroupedColors.current
        GroupedSection(header = "前情", footer = "故事至今在规划树下面，卷摘要在卷的菜单里，都能看、能改。") {
            row {
                val error = run.digestError
                if (error != null) ErrorText(error, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                else Text(run.digestNote.orEmpty(), color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
    if (run.options.learn) item(key = "learn") {
        val c = LocalGroupedColors.current
        val written = FinishWrite.LEARN in run.wrote
        GroupedSection(
            header = "从改稿里学到的",
            footer = "勾上的记进“我的黑名单”或“从改稿学到的”，AI 以后照着写；没勾的不再提。都能在写作 AI 里看、删。",
        ) {
            run.learnError?.let { e -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) } }
            if (run.candidates.isEmpty()) row { EmptyRow("这一章的改稿里还没看出反复的改法。") }
            run.candidates.forEach { cand ->
                row {
                    Column {
                        CheckboxRow(
                            (if (cand.kind == LearnKind.BAN) "黑名单词：「${cand.text}」" else "写法规则：${cand.text}"),
                            checked = cand.key in run.learnPicked,
                            subtitle = cand.source,
                            enabled = !busy && !written,
                            dimChecked = false,
                        ) { vm.writingAi.toggleFinishLearn(cand.key, it) }
                        // 例子：一行“原来 → 你改成”，看得懂为什么提这条。
                        if (cand.before.isNotBlank()) Text(
                            when {
                                cand.after == EditLearning.DELETED -> "原来「${cand.before}」→ 你整段删掉了"
                                cand.after.isBlank() -> "原来「${cand.before}」→ 你点了“这段不行”"
                                else -> "原来「${cand.before}」→ 你改成「${cand.after}」"
                            },
                            color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                            modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
                        )
                        // AI 归纳的默认不勾：没勾不等于不要，作者点了“不要”才记进“不再提”。
                        if (cand.source == EditLearning.FROM_AI && cand.key !in run.learnPicked) {
                            val declined = cand.key in run.learnDeclined
                            PillButton(
                                if (declined) "已不要，以后不再提" else "不要",
                                onClick = { vm.writingAi.declineFinishLearn(cand.key, !declined) },
                                modifier = Modifier.padding(start = 40.dp, bottom = 4.dp),
                                style = if (declined) PillStyle.Tinted else PillStyle.Plain,
                                enabled = !busy && !written,
                            )
                        }
                    }
                }
            }
        }
    }
    item(key = "apply") {
        Column {
            val error = run.writeError
            if (error != null) {
                GroupedSection(footer = "写成了的不会再写一遍；再点只做剩下的。") {
                    row { ErrorText(error, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
                    FinishWrite.entries.filter { it in run.wrote }.forEach { w -> row { StepRow(w.label, StepMark.DONE, StepState.DONE.label) } }
                }
            }
            if (run.stage == FinishStage.APPLYING) GroupedSection { row { LoadingRow("正在写入……") } }
            PrimaryButton(
                if (run.failedWrite != null) "重试剩下的步骤" else "完成收尾",
                onClick = { vm.writingAi.applyFinish(finalize = { w, c -> vm.writing.setStatusNow(w, c, ChapterStatus.FINAL) }, then = then) },
                modifier = Modifier.padding(horizontal = 12.dp),
                enabled = !busy,
            )
        }
    }
}
