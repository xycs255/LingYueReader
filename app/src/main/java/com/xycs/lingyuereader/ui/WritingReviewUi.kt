package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ReviewFix
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.AiPack
import com.xycs.lingyuereader.data.BriefItem
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.Foreshadow
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.LedgerSnapshot
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 写作这几页共用的骨架：二级页骨架（标题栏带返回），内容是一列分组。
 * 配色由骨架提供：[content]、[overlay] 里读 [LocalGroupedColors]，不能在外面先读好（外面读到的会报错）。
 */
@Composable
private fun WritingPage(vm: AppViewModel, title: String, content: LazyListScope.() -> Unit, overlay: @Composable () -> Unit = {}) {
    val snackbar = rememberWritingMessages(vm)
    SubPageScaffold(title, onBack = { vm.back() }, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
            content = content,
        )
        overlay()
    }
}

/** 只看的一项（不能点）：名字、下面灰色说明，右边可挂小标签（[Tag]、转圈）；字号照 [NavRow]。 */
@Composable
internal fun TwoLine(title: String, subtitle: String?, maxLines: Int = 3, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

/**
 * 本章资料：写这一章时带给 AI 的设定——常用文档、前情、故事账本到这一章为止的快照、出场人物的人物卡、
 * 到这一章为止知道的事、还不该知道的事、伏笔、提到这一章的细纲和设定。只是看，按章自动挑，不用手动选。
 */
@Composable
fun ChapterBriefScreen(vm: AppViewModel, workId: String, chapterId: String) {
    var brief by remember { mutableStateOf<ChapterBrief?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<BriefItem?>(null) }
    var pack by remember { mutableStateOf<AiPack?>(null) }
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    LaunchedEffect(workId, chapterId, aiConfig) {
        brief = vm.writing.brief(workId, chapterId)
        pack = runCatching { vm.writingAi.aiPack(workId) }.getOrNull()
        loaded = true
    }
    WritingPage(vm, "本章资料", content = {
        item {
            // 配色要在 WritingPage 提供的配色里面读，不能在外面先读好。
            val c = LocalGroupedColors.current
            val b = brief
            val p = pack
            if (!loaded) {
                LoadingState("正在挑这一章的资料……")
                return@item
            }
            // 联网的服务商：设定包照作者的工作说明整份给，AI 自己查；这里列出它拿到了什么，下面是随这一章带的提示。
            if (p != null && (p.instructions.isNotEmpty() || p.knowledge.isNotEmpty() || p.onDemand.isNotEmpty())) {
                GroupedSection(
                    header = "AI 拿到的设定包",
                    footer = "照你在设定包里写的工作说明办：工作说明和知识库整份放在每次请求的最前面（文档不改就不变，DeepSeek 这类服务商会缓存，" +
                        "后面几次便宜很多）；没整份给出的文档，AI 看目录自己读、按关键词搜，已写的章也能读全文。" +
                        "知识库按模型的上下文最多放约 ${p.budget / 10_000} 万字，放不下时从最大的一份起改成按需查；上限可以在作品的 AI 设置里改。",
                ) {
                    p.instructions.forEach { d -> row { TwoLine(d.title, "工作说明 · 放在最前面") } }
                    p.knowledge.forEach { d -> row { TwoLine(d.title, "知识库 · 整份 · ${d.kind.label}") } }
                    p.onDemand.forEach { d ->
                        val overflow = d in p.overflow
                        row {
                            TwoLine(
                                d.title,
                                (if (overflow) "知识库放不下，改为按需查" else "按需查阅") + " · ${d.kind.label}",
                                trailing = if (overflow) ({ Tag("放不下", c.warning) }) else null,
                            )
                        }
                    }
                }
            }
            if (b == null || b.isEmpty) {
                GroupedSection(footer = "设定包里放了文档，这里就会按章挑出要带给 AI 的部分。") {
                    row { EmptyRow("还没有可带的资料。") }
                }
            }
            if (b != null && !b.isEmpty) {
                val total = b.render()?.length ?: 0
                GroupedSection(
                    header = if (p != null) "随这一章带的提示" else null,
                    footer = if (p != null) {
                        "设定包里写这一章的原文、出场人物、前情、故事账本、伏笔、还不该知道的事，随每次请求带上，AI 不用自己去找。"
                    } else {
                        "本机模型上下文小、不能自己查，按章挑着带，约 $total 字。人物按名字、称呼在正文和细纲卡里认；" +
                            "察觉表按“第几章”“第几卷”分已知道和还不该知道；前几章没写摘要的用它们的细纲当前情。" +
                            "短的常用文档整份带，长的切成小块，单独写到这一章的、写到出场人物的、和细纲用词相近的先带，隔了一卷以上的后文不带。"
                    },
                ) {
                    row {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("第 ${b.chapter} 章 · 第 ${b.volume} 卷", color = c.label, fontSize = 17.sp)
                            Text(if (b.present.isEmpty()) "正文和细纲卡里还没认出人物" else "出场：" + b.present.joinToString("、"), color = c.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
                // 联网的服务商：设定包整份给了，按块挑的那些不再带，只列前情。故事账本放在前情之后，和发给 AI 的顺序一样。
                @Composable
                fun groups(list: List<BriefItem>) = list.filter { p == null || it.group == "前情" }.groupBy { it.group }.forEach { (group, items) ->
                    GroupedSection(header = group) {
                        items.forEach { item -> row { NavRow(item.title, subtitle = item.reason + " · ${item.text.length} 字", subtitleMaxLines = 1) { open = item } } }
                    }
                }
                groups(b.items.take(b.ledgerAt))
                b.ledger?.let { s ->
                    val rows = ledgerRows(s)
                    GroupedSection(
                        header = "故事账本 · 到第 ${s.chapter} 章为止",
                        footer = "账本里到这一章为止的进展，放在前情后面带给 AI（约 ${b.ledgerText?.length ?: 0} 字，放不下的按离这一章远近省略）；" +
                            "和察觉表、伏笔表重复的条目以账本为准，只有作者知道的另列，不进正文。",
                    ) {
                        rows.take(LEDGER_ROWS).forEach { r -> row { TwoLine(r.title, r.subtitle, trailing = r.tag?.let { t -> { Tag(t, c.warning) } }) } }
                        if (rows.size > LEDGER_ROWS) row { EmptyRow("还有 ${rows.size - LEDGER_ROWS} 条没列出") }
                        row { NavRow("打开故事账本") { vm.open(Screen.WorkLedger(workId, chapterId)) } }
                    }
                }
                groups(b.items.drop(b.ledgerAt))
                if (b.due.isNotEmpty() || b.open.isNotEmpty()) {
                    GroupedSection(header = "伏笔") {
                        b.due.forEach { f -> row { TwoLine(f.what, "计划这一章回收" + ChapterBrief.foreshadowWhen(f), trailing = { Tag("该回收", c.warning) }) } }
                        (b.open - b.due.toSet()).forEach { f -> row { TwoLine(f.what, "还没回收" + ChapterBrief.foreshadowWhen(f)) } }
                    }
                }
                if (b.notYet.isNotEmpty()) {
                    GroupedSection(header = "还不该知道的", footer = "察觉表里时间在这一章之后的条目：提醒 AI 别让人物提前知道或说破。") {
                        b.notYet.forEach { n -> row { TwoLine(n, null) } }
                    }
                }
            }
        }
    }) {
        val c = LocalGroupedColors.current
        open?.let { item ->
            GlassDialog(show = true, title = item.title, summary = item.reason, onDismissRequest = { open = null }) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(item.text, color = c.label, fontSize = 14.sp, lineHeight = 21.sp)
                }
                DialogOkButton("好") { open = null }
            }
        }
    }
}

/** 本章资料页里账本那一组最多列几行。 */
private const val LEDGER_ROWS = 40

/** 账本那一组的一行：内容、类别和时间、右边的小标签（该收、过期）。 */
internal data class LedgerRow(val title: String, val subtitle: String, val tag: String? = null)

/** 账本快照拆成一行行：时间、人物状态、该收的伏笔、发生的事、知情、开着的伏笔、还不知道的事。 */
internal fun ledgerRows(s: LedgerSnapshot): List<LedgerRow> {
    fun at(e: LedgerEntry) = e.whenLabel.let { if (it.isEmpty()) "" else " · $it" }
    fun who(e: LedgerEntry) = if (e.subject.isBlank()) "" else e.subject + (if (e.aspect.isNotBlank()) "·" + e.aspect else "") + "："
    val out = mutableListOf<LedgerRow>()
    s.time.forEach { out += LedgerRow(it.text, "时间" + at(it)) }
    s.states.forEach { out += LedgerRow(who(it) + it.text, "人物状态" + at(it)) }
    s.due.forEach { out += LedgerRow(it.text, "该收的伏笔" + at(it), "该收") }
    s.overdue.forEach { out += LedgerRow(it.text, "该收的伏笔" + at(it), "过了计划") }
    s.events.forEach { out += LedgerRow(it.text, "发生的事" + at(it)) }
    s.known.forEach { out += LedgerRow(who(it) + it.text, "知情" + at(it) + (if (it.via.isNotBlank()) " · 消息来自" + it.via else "")) }
    (s.open - s.due.toSet() - s.overdue.toSet()).forEach { out += LedgerRow(it.text, "开着的伏笔" + at(it)) }
    s.notYet.forEach { out += LedgerRow(who(it) + it.text, "还不知道" + at(it)) }
    return out
}

/** 审稿问题对话框里的动作。 */
private enum class IssueAction { JUMP, AI_FIX }

/** 一道审稿的结果写成一句：右边的小标签和颜色。 */
private data class GateStatus(val text: String, val kind: GateStatusKind)

private enum class GateStatusKind { RUNNING, OPEN, DONE, SKIPPED, ERROR }

/**
 * 三道审稿：事实关（AI 对照本章资料）、写法关（AI 只按作者的写作规范）、规则关（本机的检查规则）。
 * 审的是存盘的正文；点一处问题回到编辑器，选中原文里的那一句。结果存在本机，下次打开还在。
 */
@Composable
fun ChapterReviewScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val runs by vm.writingAi.reviews.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var savedHash by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadReview(workId, chapterId)
        savedHash = vm.writing.savedText(workId, chapterId).hashCode()
    }
    val mine = runs[workId to chapterId]
    val review = mine?.review
    val running = mine?.running
    // 停在这一页时各道问题的先后保持不变：标了“已改 / 不改”的不当场挪到组末（手指下面换成了别的条目），下次进来再按没处理的在前排。
    // 键是这一道的问题（原句和问题，不看处理状态）：重新审稿换了一批问题时再排一次。
    val orders = remember(workId, chapterId) { mutableMapOf<ReviewGate, Pair<List<Pair<String, String>>, List<Int>>>() }
    fun ordered(gate: ReviewGate, issues: List<ReviewIssue>): List<ReviewIssue> {
        val ident = issues.map { it.quote to it.problem }
        val kept = orders[gate]?.takeIf { it.first == ident }?.second
        val order = kept ?: issues.indices.sortedBy { !issues[it].open }.also { orders[gate] = ident to it }
        return order.map { issues[it] }
    }

    fun start() {
        scope.launch {
            val text = vm.writing.savedText(workId, chapterId)
            if (text.isBlank()) {
                vm.showMessage("这一章还没有内容")
                return@launch
            }
            savedHash = text.hashCode()
            vm.writingAi.startReview(workId, chapterId, text)
        }
    }

    fun jump(issue: ReviewIssue, instruction: String? = null, reviewFix: ReviewFix? = null) {
        if (issue.quote.isBlank()) return
        vm.writing.jump.value = com.xycs.lingyuereader.Jump(chapterId, issue.quote, instruction, reviewFix)
        vm.back()
    }
    var acting by remember { mutableStateOf<Pair<ReviewGate, ReviewIssue>?>(null) }
    var showSent by remember { mutableStateOf(false) }
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()

    WritingPage(vm, "三道审稿", content = {
        item {
            // 配色要在 WritingPage 提供的配色里面读，不能在外面先读好。
            val c = LocalGroupedColors.current
            GroupedSection(
                footer = buildString {
                    append("次序：先核事实、因果、视角和承接，再按你的写作规范审写法，最后对照检查规则。")
                    if (!aiConfig.ready) append("没有设置 AI 时只审规则关。")
                    if (review != null && savedHash != null && review.textHash != savedHash && running == null) append("\n正文在审稿后改过，结果可能对不上了。")
                },
            ) {
                row {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(chapter?.title.orEmpty(), color = c.label, fontSize = 17.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (running != null) Spinner(size = 12.dp)
                            Text(
                                when {
                                    running != null -> "正在审${running.label}……"
                                    review != null -> "上次审稿：${whenLabel(review.at)}"
                                    else -> "还没有审过"
                                },
                                color = c.secondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                row {
                    if (running != null) ActionRow("停止", destructive = true) { vm.writingAi.stopReview(workId, chapterId) }
                    else ActionRow(if (review == null) "开始审稿" else "重新审稿") { start() }
                }
                if (sent != null && sent?.label in setOf("事实关", "写法关")) {
                    row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.label} · ${it.size} 字" }) { showSent = true } }
                }
            }
            ReviewGate.entries.forEachIndexed { i, gate ->
                val result = review?.gate(gate)
                val open = result?.issues.orEmpty().count { it.open }
                val status = when {
                    result != null && result.error != null ->
                        if ("跳过" in result.error) GateStatus("跳过", GateStatusKind.SKIPPED) else GateStatus("出错", GateStatusKind.ERROR)
                    result != null -> when {
                        result.issues.isEmpty() -> GateStatus("没发现问题", GateStatusKind.DONE)
                        open == 0 -> GateStatus("都处理了", GateStatusKind.DONE)
                        open == result.issues.size -> GateStatus("$open 处", GateStatusKind.OPEN)
                        else -> GateStatus("$open 处没处理", GateStatusKind.OPEN)
                    }
                    running == gate -> GateStatus("审稿中……", GateStatusKind.RUNNING)
                    else -> null
                }
                // 组名只写是哪一道；结果写在卡片第一行（规范：组名不拿来写状态）。
                GroupedSection(header = "${"一二三"[i]}、${gate.label}", footer = gate.hint) {
                    if (status != null) row { GateStatusRow(gate, status) }
                    if (result?.error != null) {
                        row {
                            if (status?.kind == GateStatusKind.ERROR) ErrorText(result.error, Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                            else EmptyRow(result.error)
                        }
                    }
                    result?.issues?.let { ordered(gate, it) }?.forEach { issue ->
                        row { IssueRow(issue) { acting = gate to issue } }
                    }
                }
            }
        }
    }) {
        acting?.let { (gate, issue) ->
            GlassDialog(show = true, title = issue.problem, summary = if (issue.quote.isNotBlank()) "「${issue.quote}」" else null, onDismissRequest = { acting = null }) {
                val c = LocalGroupedColors.current
                // 能做的事一行一项；下面是这一处的处理状态（没处理 / 已改 / 不改），选了就记下。
                val actions = buildList {
                    if (issue.quote.isNotBlank()) {
                        add(IssueAction.JUMP to "去原文")
                        if (gate != ReviewGate.RULES && chapter?.locked != true) add(IssueAction.AI_FIX to "按这条让 AI 改")
                    }
                }
                if (actions.isNotEmpty()) {
                    DialogOptionList(actions, onPick = { action ->
                        acting = null
                        when (action) {
                            IssueAction.JUMP -> jump(issue)
                            IssueAction.AI_FIX -> {
                                // 发起改稿不等于已经改好：失败、关闭或未采纳时，问题仍应保持未处理。
                                jump(
                                    issue,
                                    listOf(issue.problem, issue.fix).filter { it.isNotBlank() }.joinToString("。改法：", prefix = "审稿意见：") + "。只改这一句和必要的上下文。",
                                    review?.let { ReviewFix(gate, issue, it.at, it.textHash) },
                                )
                            }
                        }
                    })
                }
                Text("处理", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                SegmentedControl(
                    listOf("" to "没处理", ReviewIssue.FIXED to "已改", ReviewIssue.IGNORED to "不改"),
                    issue.state,
                ) { state ->
                    acting = null
                    if (state != issue.state) vm.writingAi.markIssue(workId, chapterId, gate, issue, state)
                }
                DialogOkButton("取消", primary = false) { acting = null }
            }
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
    }
}

/**
 * 一道审稿的结果一行：左边“结果”，右边小标签（几处没处理用提醒色，都处理了用成功色，出错用删除色），审着时转圈。
 * 读屏念成“事实关：1 处没处理”。审完换成结果时淡入淡出，不一下跳。
 */
@Composable
private fun GateStatusRow(gate: ReviewGate, status: GateStatus) {
    val c = LocalGroupedColors.current
    GroupedRow(Modifier.groupedSemantics("${gate.label}：${status.text}")) {
        Text("结果", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        AnimatedContent(status, transitionSpec = { Motion.fadeThrough() }, contentAlignment = Alignment.CenterEnd, label = "gateStatus") { s ->
            when (s.kind) {
                GateStatusKind.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner()
                    Text(s.text, color = c.secondary, fontSize = 14.sp)
                }
                GateStatusKind.OPEN -> Tag(s.text, c.warning)
                GateStatusKind.DONE -> Tag(s.text, c.success)
                GateStatusKind.ERROR -> Tag(s.text, c.destructive)
                GateStatusKind.SKIPPED -> Tag(s.text, c.secondary)
            }
        }
    }
}

/**
 * 审稿的一处问题：问题（处理过的变灰），下面原句、改法；处理过的右边挂“已改 / 不改”，标上时淡入、标题颜色渐变，
 * 位置不动（顺序见 [ChapterReviewScreen]）。
 */
@Composable
private fun IssueRow(issue: ReviewIssue, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val titleColor by animateColorAsState(if (issue.open) c.label else c.secondary, Motion.standard(Motion.SHORT), label = "issueTitle")
    GroupedRow(onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(issue.problem, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
            if (issue.quote.isNotBlank()) Text("「${issue.quote}」", color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (issue.fix.isNotBlank()) Text("改：${issue.fix}", color = c.tint, fontSize = 13.sp, lineHeight = 18.sp)
        }
        AnimatedVisibility(
            visible = !issue.open,
            enter = Effects.enter(fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(fadeOut(Motion.exit())),
        ) {
            val fixed = issue.state == ReviewIssue.FIXED
            Tag(if (fixed) "已改" else "不改", if (fixed) c.success else c.secondary, Modifier.padding(start = 8.dp))
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/** 伏笔统一视图：账本是当前状态，旧表保留为来源；没有导入的旧表条目仍可操作。 */
internal data class WritingForeshadow(
    val display: Foreshadow,
    val ledger: LedgerEntry? = null,
    val legacy: List<Foreshadow> = emptyList(),
) {
    fun close(chapter: Int?): LedgerOp = ledger?.let { LedgerOp.Close(it.id, chapter) }
        ?: LedgerOp.Add(
            LedgerEntry(
                kind = LedgerKind.PROMISE,
                text = display.what.trim().removeSuffix("（只有作者知道）"),
                from = display.planted,
                payoff = display.payoff,
                done = true,
                closed = chapter,
                secret = display.what.trim().endsWith("（只有作者知道）"),
                quote = display.what,
                origin = LedgerOrigin.IMPORT,
            ),
        )
}

/** 和本章资料采用同一匹配方式：已有账本记录时，不再展示旧表里的第二份状态。 */
internal fun mergeWritingForeshadows(legacy: List<Foreshadow>, ledger: List<LedgerEntry>): List<WritingForeshadow> {
    fun same(a: String, b: String) = ChapterBriefs.same(a.removeSuffix("（只有作者知道）"), b.removeSuffix("（只有作者知道）"))
    val promises = ledger.filter { it.kind == LedgerKind.PROMISE }
    val result = promises.map { e ->
        WritingForeshadow(
            display = Foreshadow(e.text, e.from, e.payoff, e.done, closed = e.closed),
            ledger = e,
            legacy = legacy.filter { same(it.what, e.text) },
        )
    }.toMutableList()
    legacy.forEach { f ->
        if (result.none { same(it.display.what, f.what) }) {
            result += WritingForeshadow(f, legacy = legacy.filter { same(it.what, f.what) })
        }
    }
    return result
}

/** 全部伏笔：按账本显示最新回收状态，未导入的旧表条目回收时也记进账本。 */
@Composable
fun ForeshadowScreen(vm: AppViewModel, workId: String) {
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val pack = packs[workId]
    val ledgers by vm.writing.ledgers.collectAsStateWithLifecycle()
    val docs = pack.orEmpty().filter { it.kind == PackKind.FORESHADOW }
    var data by remember { mutableStateOf<Pair<List<Foreshadow>, Int>?>(null) }
    var open by remember { mutableStateOf<WritingForeshadow?>(null) }
    LaunchedEffect(workId) {
        vm.writing.loadPack(workId)
        vm.writing.loadLedger(workId)
    }
    LaunchedEffect(workId, pack) { data = vm.writing.foreshadows(workId) }
    WritingPage(vm, "伏笔", content = {
        item {
            // 配色要在 WritingPage 提供的配色里面读，不能在外面先读好。
            val c = LocalGroupedColors.current
            val loadedData = data
            if (pack == null || loadedData == null || ledgers[workId] == null) {
                LoadingState("正在读伏笔……")
                return@item
            }
            val (legacy, latest) = loadedData
            val list = mergeWritingForeshadows(legacy, ledgers[workId].orEmpty())
            val due = list.filter { !it.display.done && it.display.payoff?.let { n -> n <= latest } == true }
            val waiting = list.filter { !it.display.done } - due.toSet()
            val done = list.filter { it.display.done }
            GroupedSection(footer = "这里合并显示全部伏笔，回收状态以故事账本为准。回收后，后续写作和审稿也会使用同一状态。") {
                row { NavRow("故事账本", value = "${list.size} 条伏笔") { vm.open(Screen.WorkLedger(workId)) } }
            }
            if (list.isEmpty()) {
                GroupedSection(footer = "伏笔可以在故事账本里添加，或从设定包的伏笔表导入。到了计划回收的章节会提醒。") {
                    row {
                        NavRow("添加伏笔") { vm.open(Screen.WorkLedger(workId)) }
                    }
                }
            } else {
                if (due.isNotEmpty()) {
                    GroupedSection(header = "该回收了", footer = "计划回收的章已经写到（写到第 $latest 章），还没标为已回收。") {
                        due.forEach { f ->
                            row { NavRow(f.display.what, subtitle = ChapterBrief.foreshadowWhen(f.display).trim('（', '）'), trailing = { Tag("该回收", c.warning) }) { open = f } }
                        }
                    }
                }
                GroupedSection(header = "还没回收") {
                    if (waiting.isEmpty()) row { EmptyRow("没有。") }
                    waiting.forEach { f -> row { NavRow(f.display.what, subtitle = ChapterBrief.foreshadowWhen(f.display).trim('（', '）')) { open = f } } }
                }
                if (done.isNotEmpty()) {
                    GroupedSection(header = "已回收") {
                        done.forEach { f -> row { TwoLine(f.display.what, ChapterBrief.foreshadowWhen(f.display).trim('（', '）'), trailing = { Tag("已回收", c.success) }) } }
                    }
                }
            }
            if (docs.isNotEmpty()) {
                GroupedSection(header = "来源文档", footer = "表格尚未导入的条目也会显示；与账本重复时，账本里的状态优先。") {
                    docs.forEach { d -> row { NavRow(if (docs.size == 1) "编辑伏笔表" else "编辑“${d.title}”") { vm.open(Screen.PackDoc(workId, d.id)) } } }
                }
            }
        }
    }) {
        open?.let { f ->
            GlassDialog(show = true, title = f.display.what, summary = ChapterBrief.foreshadowWhen(f.display).trim('（', '）').ifEmpty { null }, onDismissRequest = { open = null }) {
                DialogButtons(
                    onDismiss = { open = null },
                    onConfirm = {
                        open = null
                        val chapter = data?.second?.takeIf { it > 0 }
                        val current = mergeWritingForeshadows(data?.first.orEmpty(), vm.writing.ledgers.value[workId].orEmpty())
                            .firstOrNull { ChapterBriefs.same(it.display.what, f.display.what) } ?: f
                        // 先写当前状态；旧表镜像失败也不会让界面与 AI 继续使用不同的回收状态。
                        vm.writing.editLedger(workId, listOf(current.close(chapter)), "回收伏笔") {
                            current.legacy.forEach { old -> vm.writing.markForeshadow(workId, old) }
                        }
                    },
                    confirmText = "标为已回收",
                )
            }
        }
    }
}

/** 编辑器底部的一条提示（比如“计划这一章回收的伏笔”），和写作建议那一行一样放在键盘上方。 */
@Composable
internal fun EditorNote(text: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().background(c.background).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LingYueIcons.Star, contentDescription = null, tint = c.warning, modifier = Modifier.size(16.dp))
        Text(text, color = c.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}
