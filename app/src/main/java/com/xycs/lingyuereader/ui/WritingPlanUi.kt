package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.PlannedCard
import com.xycs.lingyuereader.data.outlineHash
import com.xycs.lingyuereader.data.withOutline
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 各状态的颜色：细纲用次要色，草稿用强调色，修订用提醒色，定稿用成功色——都从配色里取，深浅色、自选强调色下都读得清。
 * 小标签的字画在它自己的淡色底上，按卡片底和淡色底一起调到够对比度（强调色很浅时压深一点）。
 */
internal fun statusColor(status: ChapterStatus, c: GroupedColors): Color {
    val base = when (status) {
        ChapterStatus.OUTLINE -> c.secondary
        ChapterStatus.DRAFT -> c.tint
        ChapterStatus.REVISION -> c.warning
        ChapterStatus.FINAL -> c.success
    }
    // 字压深（或提亮）了，它的淡色底也跟着变：多调两遍，直到在卡片和淡色底上都够。
    var color = base
    repeat(3) { color = color.readableOn(listOf(c.cell, color.copy(alpha = TAG_FILL_ALPHA).compositeOver(c.cell))) }
    return color
}

/** [Tag] 的淡色底用的透明度（和 Grouped.kt 里一致）。 */
private const val TAG_FILL_ALPHA = 0.14f

/** 状态小标签。 */
@Composable
internal fun StatusLabel(status: ChapterStatus) {
    val c = LocalGroupedColors.current
    Tag(status.label, statusColor(status, c))
}

/**
 * 改章节状态。定稿后正文锁定；从定稿改出去要写原因，并说明改动涉不涉及设定（涉及的话登记里会提醒同步设定包）。
 */
@Composable
internal fun StatusDialog(chapter: WorkChapter, ruleHits: Int? = null, onDismiss: () -> Unit, onConfirm: (ChapterStatus, String, Boolean) -> Unit) {
    // 定稿的章打开这里多半是要解锁：先选好“修订”。
    var status by remember { mutableStateOf(if (chapter.locked) ChapterStatus.REVISION else chapter.status) }
    var reason by remember { mutableStateOf("") }
    var canon by remember { mutableStateOf(false) }
    val unlocking = chapter.locked && status != ChapterStatus.FINAL
    GlassDialog(show = true, title = "“${chapter.title}”的状态", onDismissRequest = onDismiss) {
        // 配色在对话框里面读（对话框把调用处的配色带进来了）。
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 四个互斥的短选项：分段控件。
            SegmentedControl(ChapterStatus.entries.map { it to it.label }, status) { status = it }
            Text(
                when {
                    unlocking -> "这一章已定稿。改之前写下为什么要改，会记进变更登记。"
                    status == ChapterStatus.FINAL && !chapter.locked -> "定稿后正文锁定，AI 改稿也不能动它；要改得先解锁并登记原因。定稿时会存一个历史版本。"
                    else -> "细纲：只有细纲卡；草稿：在写；修订：改稿中；定稿：锁定。"
                },
                color = c.secondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            // 定稿前提醒一句检查规则的结果，不拦着。
            if (status == ChapterStatus.FINAL && !chapter.locked && (ruleHits ?: 0) > 0) {
                Text("按检查规则，这一章还有 $ruleHits 条不合。仍然可以定稿。", color = c.warning, fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (unlocking) {
                TextField(value = reason, onValueChange = { reason = it }, label = "为什么要改（必填）", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp))
                // 只改写法 / 涉及设定：一个开关（关着就是只改写法）。
                Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                    ToggleRow(
                        "涉及设定，要同步设定包",
                        checked = canon,
                        subtitle = "改动会动到不变量、细纲、人物档案或察觉表里的事实时打开。登记里会一直提醒，直到你把设定包改好、标为已同步。",
                    ) { canon = it }
                }
            }
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(status, reason, canon) },
            confirmText = if (unlocking) "解锁" else "好",
            confirmEnabled = !unlocking || reason.isNotBlank(),
        )
    }
}

private val CARD_FIELDS: List<Triple<String, String, (ChapterCard) -> String>> = listOf(
    Triple("本章事实与结果", "发生了什么、结果（数字、生死、得失）", { it.facts }),
    Triple("后文承接边界", "后面的章节不能和哪些点冲突", { it.boundary }),
    Triple("视角人物", "", { it.pov }),
    Triple("倒计时", "章首交代：离某件事还有多久", { it.countdown }),
    Triple("章末钩子", "一个动作、一句没说完的话、一点异样", { it.hook }),
    Triple("推进的线", "比如：明线、暗线、主角线，或某个配角的弧线", { it.threads }),
    Triple("细纲章号", "发布章号顺延时填（比如拆成上下章）", { it.plan }),
)

private fun ChapterCard.with(field: String, value: String) = when (field) {
    "本章事实与结果" -> copy(facts = value)
    "后文承接边界" -> copy(boundary = value)
    "视角人物" -> copy(pov = value)
    "倒计时" -> copy(countdown = value)
    "章末钩子" -> copy(hook = value)
    "细纲章号" -> copy(plan = value)
    else -> copy(threads = value)
}

/**
 * 存细纲卡时按项合并：这一页上改过的项（[edited] 和打开、上次存时的 [base] 不一样的）用这一页的，
 * 没改过的用盘上最新的 [latest]（这期间“补写章节摘要”写进去的摘要之类，不能被这一页的旧卡片清掉）。
 */
internal fun mergeCard(base: ChapterCard, edited: ChapterCard, latest: ChapterCard): ChapterCard {
    fun pick(get: (ChapterCard) -> String) = if (get(edited) != get(base)) get(edited) else get(latest)
    return latest.copy(
        facts = pick { it.facts },
        boundary = pick { it.boundary },
        pov = pick { it.pov },
        countdown = pick { it.countdown },
        hook = pick { it.hook },
        threads = pick { it.threads },
        summary = pick { it.summary },
        plan = pick { it.plan },
        auto = if (edited.auto != base.auto) edited.auto else latest.auto,
    )
}

/**
 * 一章的细纲卡：只划事实边界（发生了什么、后文不能冲突的点、视角、倒计时、钩子、推进哪几条线），不管怎么写。
 * 上面列出上一章留下的钩子和承接边界。写作 AI 改这一章时会带上它。边打边存。
 */
@Composable
fun ChapterCardScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var card by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    var saved by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    var previous by remember(chapterId) { mutableStateOf<Pair<WorkChapter, ChapterCard>?>(null) }
    var info by remember(chapterId) { mutableStateOf<PlannedCard?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<ChapterCard?>(null) }
    var statusDialog by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = rememberWritingMessages(vm)
    LaunchedEffect(workId, chapterId) {
        val (mine, prev) = vm.writing.cards(workId, chapterId)
        card = mine.card
        saved = mine.card
        info = mine
        previous = prev
    }
    // 停一下再存；离开时存。存的时候以盘上最新的卡片为底，只换这一页改过的项。
    LaunchedEffect(card) {
        val now = card ?: return@LaunchedEffect
        val base = saved ?: return@LaunchedEffect
        if (now == base) return@LaunchedEffect
        delay(600)
        // 合并在写盘队列里、和读最新卡片在同一把锁里做，补写摘要这时写进来也不会丢。
        saved = now
        vm.writing.updateCard(workId, chapterId, { latest -> mergeCard(base, now, latest) }) { merged ->
            saved = merged
            // 别处写进来的（比如 AI 补的摘要）也显示出来；这期间作者又改了的就不动。
            if (merged != now && card == now) card = merged
        }
    }
    val latest by rememberUpdatedState(card)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(workId, chapterId) {
        onDispose {
            val now = latest
            val base = latestSaved
            if (now != null && base != null && now != base) {
                vm.writing.updateCard(workId, chapterId, { latest -> mergeCard(base, now, latest) })
            }
        }
    }

    SubPageScaffold("细纲卡", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        Box {
            LazyColumn(
                Modifier.fillMaxSize().imePadding().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (chapter != null) {
                        GroupedSection {
                            // 点这一行改状态：章名，右边状态小标签。
                            row { NavRow(chapter.title, trailing = { StatusLabel(chapter.status) }) { statusDialog = true } }
                        }
                    }
                    previous?.let { (pc, prev) ->
                        if (prev.hook.isNotBlank() || prev.boundary.isNotBlank()) {
                            GroupedSection(header = "上一章「${pc.title}」留下的") {
                                if (prev.hook.isNotBlank()) row { ValueRow("钩子", prev.hook) }
                                if (prev.boundary.isNotBlank()) row { ValueRow("承接边界", prev.boundary) }
                            }
                        }
                    }
                    val now = card
                    if (now != null) {
                        val planned = info
                        val fromOutline = planned?.source != null && now.auto != 0 && now.auto == now.outlineHash()
                        val outline = planned?.outline?.takeIf { it.outlineHash() != now.outlineHash() }
                        GroupedSection(
                            header = "本章",
                            footer = "细纲只划事实边界，不管怎么写。写作 AI 改这一章、给灵感、查矛盾时都会带上它，以及上一章的钩子和承接边界。",
                        ) {
                            val note = when {
                                fromOutline -> "从${planned?.source}自动提取。细纲文档改了，这里跟着变；在这里改过以后就按你改的来。"
                                outline != null -> "改过了，不再跟着细纲变。细纲里这一章（${planned.outlineSource}）和这里不一样，可以换回去。"
                                planned?.missing != null -> "设定包的卷纲细纲里没找到这一章（${planned.missing}）。章号对不上时填下面的“细纲章号”。"
                                else -> null
                            }
                            if (note != null) row { Text(note, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                            // 细纲里没写到的章（往后要写的），或者想要另一种走法：让 AI 按总纲、前后几章拟一份，看过再用。
                            row {
                                // 在拟的时候换成转圈的一行，拟完淡回按钮。
                                BusyActionRow(planning, busyText = "AI 正在拟细纲……", title = "AI 拟这一章的细纲", enabled = aiConfig.ready) {
                                    planning = true
                                    scope.launch {
                                        try {
                                            proposal = vm.writingAi.planCard(workId, chapterId)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            vm.showMessage(e.message ?: "拟细纲失败")
                                        } finally {
                                            planning = false
                                        }
                                    }
                                }
                            }
                            if (outline != null) row { ActionRow("用细纲里的替换") { replacing = true } }
                            CARD_FIELDS.forEach { (label, hint, get) ->
                                row {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        TextField(
                                            value = get(now),
                                            onValueChange = { v -> card = now.with(label, v) },
                                            label = if (hint.isNotEmpty()) "$label · $hint" else label,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 220.dp),
                                        )
                                    }
                                }
                            }
                        }
                        GroupedSection(
                            header = "写完以后",
                            footer = "本章摘要：写后面的章时当前情带给 AI（最近 ${ChapterBriefs.RECENT_SUMMARIES} 章，以及更早写到出场人物的章），AI 就知道前面写了什么。" +
                                "可以自己写，也可以让 AI 概括正文再改。",
                        ) {
                            row {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    TextField(
                                        value = now.summary,
                                        onValueChange = { v -> card = now.copy(summary = v) },
                                        label = "本章摘要 · 发生了什么、结果、留下什么",
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 260.dp),
                                    )
                                }
                            }
                            row {
                                BusyActionRow(summarizing, busyText = "AI 正在概括……", title = "AI 概括正文", enabled = aiConfig.ready && (chapter?.words ?: 0) > 0) {
                                    summarizing = true
                                    scope.launch {
                                        try {
                                            val text = vm.writingAi.summarize(workId, chapterId)
                                            card = (card ?: now).copy(summary = text)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            vm.showMessage(e.message ?: "概括失败")
                                        } finally {
                                            summarizing = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            proposal?.let { p ->
                GlassDialog(show = true, title = "AI 拟的细纲", summary = "按总纲、前后几章和上一章结尾拟的，看过再用", onDismissRequest = { proposal = null }) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CARD_FIELDS.dropLast(1).forEach { (label, _, get) ->
                            val v = get(p)
                            if (v.isNotBlank()) {
                                Text(label, color = LocalGroupedColors.current.secondary, fontSize = 13.sp)
                                Text(v, color = LocalGroupedColors.current.label, fontSize = 15.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                    DialogButtons(
                        onDismiss = { proposal = null },
                        onConfirm = {
                            proposal = null
                            card = card?.copy(facts = p.facts, boundary = p.boundary, pov = p.pov, countdown = p.countdown, hook = p.hook, threads = p.threads, auto = 0)
                            info = info?.copy(source = null)
                        },
                        confirmText = "用这份",
                        dismissText = "不用",
                    )
                }
            }
            val outline = info?.outline
            if (replacing && outline != null) {
                GlassDialog(show = true, title = "用细纲里的替换", summary = info?.outlineSource, onDismissRequest = { replacing = false }) {
                    Text(
                        "前六项换成细纲文档里这一章现在写的，你在这里改的会被覆盖。以后细纲再改，这里跟着变。摘要和细纲章号不动。",
                        color = LocalGroupedColors.current.secondary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                    DialogButtons(
                        onDismiss = { replacing = false },
                        onConfirm = {
                            replacing = false
                            card = card?.withOutline(outline)
                            info = info?.copy(source = info?.outlineSource)
                        },
                        confirmText = "替换",
                    )
                }
            }
            if (statusDialog && chapter != null) {
                StatusDialog(chapter, onDismiss = { statusDialog = false }) { s, reason, canon ->
                    statusDialog = false
                    vm.writing.setStatus(workId, chapterId, s, reason, canon)
                }
            }
        }
    }
}

/** 一个要等 AI 的动作：在做的时候换成转圈的一行（[busyText]），做完淡回按钮；减少动效时直接换。 */
@Composable
internal fun BusyActionRow(busy: Boolean, busyText: String, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    AnimatedContent(busy, transitionSpec = { Motion.fadeThrough() }, label = "busyAction") { now ->
        if (now) LoadingRow(busyText) else ActionRow(title, enabled = enabled, onClick = onClick)
    }
}

/**
 * 变更登记：定稿、解锁修改和设定变更的记录，新的在前。涉及设定的会一直标“待同步”，直到把设定包改好、标为已同步。
 */
@Composable
fun WorkChangesScreen(vm: AppViewModel, workId: String) {
    val all by vm.writing.changes.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writing.loadChanges(workId) }
    val list = all[workId]
    var open by remember { mutableStateOf<ChangeEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = rememberWritingMessages(vm)

    SubPageScaffold(
        "变更登记",
        onBack = { vm.back() },
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(LingYueIcons.Add, contentDescription = "登记设定变更") }
        },
    ) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                when {
                    list == null -> LoadingState("正在读变更登记……")
                    list.isEmpty() -> EmptyState(
                        LingYueIcons.History,
                        "还没有登记",
                        "定稿、解锁修改会自动登记；改了设定（比如人数、生死、谁知道什么）也可以手动登记一条。",
                        action = "登记设定变更" to { adding = true },
                    )
                    else -> {
                        val pending = list.filter { it.pending }
                        if (pending.isNotEmpty()) {
                            GroupedSection(header = "待同步设定", footer = "这些改动涉及设定：把设定包里的不变量、细纲、人物档案、察觉表改好后，点开标为已同步。") {
                                pending.forEach { e -> row { ChangeRow(e) { open = e } } }
                            }
                        }
                        GroupedSection(
                            header = "全部",
                            footer = "定稿、解锁修改会自动登记；改了设定（比如人数、生死、谁知道什么）也可以点右上角手动登记一条。",
                        ) {
                            list.forEach { e -> row { ChangeRow(e) { open = e } } }
                        }
                    }
                }
            }
        }
        open?.let { e ->
            GlassDialog(
                show = true,
                title = e.kind.label + if (e.chapterTitle.isNotBlank()) " · ${e.chapterTitle}" else "",
                summary = whenLabel(e.at) + (if (e.canon) if (e.synced) " · 涉及设定，已同步" else " · 涉及设定，待同步" else ""),
                onDismissRequest = { open = null },
            ) {
                if (e.reason.isNotBlank()) Text(e.reason, color = c.label, fontSize = 15.sp, lineHeight = 23.sp)
                if (e.canon) {
                    DialogButtons(
                        onDismiss = { open = null; vm.open(Screen.WorkPack(workId)) },
                        dismissText = "去改设定包",
                        onConfirm = { open = null; vm.writing.markSynced(workId, e.id, !e.synced) },
                        confirmText = if (e.synced) "标为未同步" else "标为已同步",
                    )
                } else {
                    DialogOkButton("好") { open = null }
                }
            }
        }
        if (adding) {
            AskDialog("登记设定变更", hint = "改了什么、为什么（比如：老周改为第九章战死）", confirm = "登记", onDismiss = { adding = false }) {
                adding = false
                vm.writing.addCanonChange(workId, it)
            }
        }
    }
}

/** 一条登记：种类和章名，下面时间和原因；涉及设定还没同步的右边挂“待同步”。 */
@Composable
private fun ChangeRow(e: ChangeEntry, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    NavRow(
        e.kind.label + if (e.chapterTitle.isNotBlank()) " · ${e.chapterTitle}" else "",
        subtitle = listOf(whenLabel(e.at), e.reason.take(40)).filter { it.isNotBlank() }.joinToString(" · "),
        subtitleMaxLines = 2,
        trailing = if (e.pending) ({ Tag("待同步", c.warning) }) else null,
        onClick = onClick,
    )
}
