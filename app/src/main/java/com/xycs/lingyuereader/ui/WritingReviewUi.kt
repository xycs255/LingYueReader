package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BriefItem
import com.xycs.lingyuereader.data.AiPack
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.Foreshadow
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 写作这几页共用的骨架：标题栏带返回，内容是一列分组。 */
@Composable
private fun WritingPage(vm: AppViewModel, title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit, overlay: @Composable () -> Unit = {}) {
    val snackbar = rememberWritingMessages(vm)
    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
                content = content,
            )
            overlay()
        }
    }
}

/** 两行的一项（不能点）：标题、灰色说明。 */
@Composable
internal fun TwoLine(title: String, subtitle: String?) = TwoLineRow(title, subtitle)

/** 两行的一项：标题、灰色说明，可以点。 */
@Composable
private fun TwoLineRow(title: String, subtitle: String?, titleColor: Color? = null, maxLines: Int = 3, onClick: (() -> Unit)? = null) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor ?: c.label, fontSize = 15.sp, lineHeight = 21.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * 本章资料：写这一章时带给 AI 的设定——常用文档、出场人物的人物卡、到这一章为止知道的事、还不该知道的事、
 * 伏笔、提到这一章的细纲和设定。只是看，按章自动挑，不用手动选。
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
    val c = LocalGroupedColors.current
    WritingPage(vm, "本章资料", content = {
        item {
            val b = brief
            val p = pack
            // 联网的服务商：设定包照作者的工作说明整份给，AI 自己查；这里列出它拿到了什么，下面是随这一章带的提示。
            if (p != null && (p.instructions.isNotEmpty() || p.knowledge.isNotEmpty() || p.onDemand.isNotEmpty())) {
                GroupedSection(
                    header = "AI 拿到的设定包",
                    footer = "照你在设定包里写的工作说明办：工作说明和知识库整份放在每次请求的最前面（文档不改就不变，DeepSeek 这类服务商会缓存，" +
                        "后面几次便宜很多）；没整份给出的文档，AI 看目录自己读、按关键词搜，已写的章也能读全文。" +
                        "知识库按模型的上下文最多放约 ${p.budget / 10_000} 万字，放不下时从最大的一份起改成按需查；上限可以在作品的 AI 设置里改。",
                ) {
                    p.instructions.forEach { d -> row { TwoLineRow(d.title, "工作说明 · 放在最前面") } }
                    p.knowledge.forEach { d -> row { TwoLineRow(d.title, "知识库 · 整份 · ${d.kind.label}") } }
                    p.onDemand.forEach { d -> row { TwoLineRow(d.title, (if (d in p.overflow) "知识库放不下，改为按需查" else "按需查阅") + " · ${d.kind.label}", titleColor = if (d in p.overflow) Color(0xFFFF9500) else null) } }
                }
            }
            if (loaded && (b == null || b.isEmpty)) {
                GroupedSection(footer = "设定包里放了文档，这里就会按章挑出要带给 AI 的部分。") {
                    row { Text("还没有可带的资料。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (b != null && !b.isEmpty) {
                val total = b.render()?.length ?: 0
                GroupedSection(
                    header = if (p != null) "随这一章带的提示" else null,
                    footer = if (p != null) {
                        "设定包里写这一章的原文、出场人物、前情、伏笔、还不该知道的事，随每次请求带上，AI 不用自己去找。"
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
                // 联网的服务商：设定包整份给了，按块挑的那些不再带，只列前情。
                b.items.filter { p == null || it.group == "前情" }.groupBy { it.group }.forEach { (group, list) ->
                    GroupedSection(header = group) {
                        list.forEach { it -> row { TwoLineRow(it.title, it.reason + " · ${it.text.length} 字", maxLines = 1) { open = it } } }
                    }
                }
                if (b.due.isNotEmpty() || b.open.isNotEmpty()) {
                    GroupedSection(header = "伏笔") {
                        b.due.forEach { f -> row { TwoLineRow(f.what, "计划这一章回收" + ChapterBrief.foreshadowWhen(f), titleColor = Color(0xFFFF9500)) } }
                        (b.open - b.due.toSet()).forEach { f -> row { TwoLineRow(f.what, "还没回收" + ChapterBrief.foreshadowWhen(f)) } }
                    }
                }
                if (b.notYet.isNotEmpty()) {
                    GroupedSection(header = "还不该知道的", footer = "察觉表里时间在这一章之后的条目：提醒 AI 别让人物提前知道或说破。") {
                        b.notYet.forEach { n -> row { TwoLineRow(n, null) } }
                    }
                }
            }
        }
    }) {
        open?.let { item ->
            GlassDialog(show = true, title = item.title, summary = item.reason, onDismissRequest = { open = null }) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(item.text, color = c.label, fontSize = 14.sp, lineHeight = 21.sp)
                }
                TextButton(text = "好", onClick = { open = null }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

/**
 * 三道审稿：事实关（AI 对照本章资料）、写法关（AI 只按作者的写作规范）、规则关（本机的检查规则）。
 * 审的是存盘的正文；点一处问题回到编辑器，选中原文里的那一句。结果存在本机，下次打开还在。
 */
@Composable
fun ChapterReviewScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val run by vm.writingAi.review.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var savedHash by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadReview(workId, chapterId)
        savedHash = vm.writing.savedText(workId, chapterId).hashCode()
    }
    val mine = run?.takeIf { it.workId == workId && it.chapterId == chapterId }
    val review = mine?.review
    val running = mine?.running
    val c = LocalGroupedColors.current

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

    fun jump(issue: ReviewIssue, instruction: String? = null) {
        if (issue.quote.isBlank()) return
        vm.writing.jump.value = com.xycs.lingyuereader.Jump(chapterId, issue.quote, instruction)
        vm.back()
    }
    var acting by remember { mutableStateOf<Pair<ReviewGate, ReviewIssue>?>(null) }
    var showSent by remember { mutableStateOf(false) }
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()

    WritingPage(vm, "三道审稿", content = {
        item {
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
                row {
                    if (running != null) ActionRow("停止", destructive = true) { vm.writingAi.stopReview() }
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
                    result != null && result.error != null -> if ("跳过" in result.error) "跳过" else "出错"
                    result != null -> when {
                        result.issues.isEmpty() -> "没发现问题"
                        open == 0 -> "都处理了"
                        open == result.issues.size -> "$open 处"
                        else -> "$open 处没处理"
                    }
                    running == gate -> "审稿中……"
                    else -> null
                }
                GroupedSection(header = "${"一二三"[i]}、${gate.label}" + (status?.let { " · $it" } ?: ""), footer = gate.hint) {
                    if (result?.error != null) row { Text(result.error, color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                    result?.issues?.sortedBy { !it.open }?.forEach { issue ->
                        row {
                            GroupedRow(onClick = { acting = gate to issue }) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (!issue.open) Text(if (issue.state == ReviewIssue.FIXED) "已改" else "不改", color = c.tertiary, fontSize = 12.sp)
                                    Text(issue.problem, color = if (issue.open) c.label else c.secondary, fontSize = 15.sp, lineHeight = 21.sp)
                                    if (issue.quote.isNotBlank()) Text("「${issue.quote}」", color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (issue.fix.isNotBlank()) Text("改：${issue.fix}", color = c.tint, fontSize = 13.sp, lineHeight = 18.sp)
                                }
                                Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }) {
        acting?.let { (gate, issue) ->
            GlassDialog(show = true, title = issue.problem, summary = if (issue.quote.isNotBlank()) "「${issue.quote}」" else null, onDismissRequest = { acting = null }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (issue.quote.isNotBlank()) {
                        TextButton(text = "去原文", onClick = { acting = null; jump(issue) }, modifier = Modifier.fillMaxWidth())
                        if (gate != ReviewGate.RULES && chapter?.locked != true) {
                            TextButton(
                                text = "按这条让 AI 改",
                                onClick = {
                                    acting = null
                                    vm.writingAi.markIssue(workId, chapterId, gate, issue, ReviewIssue.FIXED)
                                    jump(issue, listOf(issue.problem, issue.fix).filter { it.isNotBlank() }.joinToString("。改法：", prefix = "审稿意见：") + "。只改这一句和必要的上下文。")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = if (issue.state == ReviewIssue.FIXED) "改回没处理" else "已改", onClick = {
                            acting = null
                            vm.writingAi.markIssue(workId, chapterId, gate, issue, if (issue.state == ReviewIssue.FIXED) "" else ReviewIssue.FIXED)
                        }, modifier = Modifier.weight(1f))
                        TextButton(text = if (issue.state == ReviewIssue.IGNORED) "改回没处理" else "不改", onClick = {
                            acting = null
                            vm.writingAi.markIssue(workId, chapterId, gate, issue, if (issue.state == ReviewIssue.IGNORED) "" else ReviewIssue.IGNORED)
                        }, modifier = Modifier.weight(1f))
                    }
                    TextButton(text = "取消", onClick = { acting = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
    }
}

/**
 * 伏笔：伏笔表里的每一条——该回收了（计划回收的章已经写到）、还没回收、已回收。点一条可以标为已回收（改伏笔表那一行）。
 */
@Composable
fun ForeshadowScreen(vm: AppViewModel, workId: String) {
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val pack = packs[workId]
    val docs = pack.orEmpty().filter { it.kind == PackKind.FORESHADOW }
    var data by remember { mutableStateOf<Pair<List<Foreshadow>, Int>?>(null) }
    var open by remember { mutableStateOf<Foreshadow?>(null) }
    LaunchedEffect(workId) { vm.writing.loadPack(workId) }
    LaunchedEffect(workId, pack) { data = vm.writing.foreshadows(workId) }
    val c = LocalGroupedColors.current
    WritingPage(vm, "伏笔", content = {
        item {
            val (list, latest) = data ?: (emptyList<Foreshadow>() to 0)
            val due = list.filter { !it.done && it.payoff != null && it.payoff <= latest }
            val waiting = list.filter { !it.done } - due.toSet()
            val done = list.filter { it.done }
            if (docs.isEmpty()) {
                GroupedSection(footer = "伏笔表是设定包里的一份表格：伏笔、第几章埋下、计划第几章回收、状态。写作 AI 会记着没回收的伏笔，到了计划的那一章会提醒。") {
                    row {
                        ActionRow("新建伏笔表") { vm.writing.addDoc(workId, PackKind.FORESHADOW, PackKind.FORESHADOW.label) { vm.open(Screen.PackDoc(workId, it.id)) } }
                    }
                }
            } else {
                if (due.isNotEmpty()) {
                    GroupedSection(header = "该回收了", footer = "计划回收的章已经写到（写到第 $latest 章），还没标为已回收。") {
                        due.forEach { f -> row { TwoLineRow(f.what, ChapterBrief.foreshadowWhen(f).trim('（', '）'), titleColor = Color(0xFFFF9500)) { open = f } } }
                    }
                }
                GroupedSection(header = "还没回收") {
                    if (waiting.isEmpty()) row { Text("没有。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                    waiting.forEach { f -> row { TwoLineRow(f.what, ChapterBrief.foreshadowWhen(f).trim('（', '）')) { open = f } } }
                }
                if (done.isNotEmpty()) {
                    GroupedSection(header = "已回收") {
                        done.forEach { f -> row { TwoLineRow(f.what, ChapterBrief.foreshadowWhen(f).trim('（', '）'), titleColor = c.secondary) } }
                    }
                }
                GroupedSection(footer = "伏笔写在设定包的伏笔表里：表格要有“伏笔”“埋下”“回收”几列（“状态”可选），或者写成列表“- 玉佩：第3章埋，第20章回收”。") {
                    docs.forEach { d -> row { NavRow(if (docs.size == 1) "编辑伏笔表" else "编辑“${d.title}”") { vm.open(Screen.PackDoc(workId, d.id)) } } }
                }
            }
        }
    }) {
        open?.let { f ->
            GlassDialog(show = true, title = f.what, summary = ChapterBrief.foreshadowWhen(f).trim('（', '）').ifEmpty { null }, onDismissRequest = { open = null }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { open = null }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "标为已回收",
                        onClick = {
                            open = null
                            vm.writing.markForeshadow(workId, f)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
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
        Icon(LingYueIcons.Star, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(16.dp))
        Text(text, color = c.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}
