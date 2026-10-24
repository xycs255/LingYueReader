package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 各状态的颜色：细纲灰、草稿蓝、修订橙、定稿绿。 */
internal fun statusColor(status: ChapterStatus, gray: Color): Color = when (status) {
    ChapterStatus.OUTLINE -> gray
    ChapterStatus.DRAFT -> Color(0xFF007AFF)
    ChapterStatus.REVISION -> Color(0xFFFF9500)
    ChapterStatus.FINAL -> Color(0xFF34C759)
}

/** 状态小标签。 */
@Composable
internal fun StatusLabel(status: ChapterStatus) {
    val c = LocalGroupedColors.current
    val color = statusColor(status, c.secondary)
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(status.label, color = color, fontSize = 12.sp)
    }
}

/**
 * 改章节状态。定稿后正文锁定；从定稿改出去要写原因，并说明改动涉不涉及设定（涉及的话登记里会提醒同步设定包）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusDialog(chapter: WorkChapter, ruleHits: Int? = null, onDismiss: () -> Unit, onConfirm: (ChapterStatus, String, Boolean) -> Unit) {
    // 定稿的章打开这里多半是要解锁：先选好“修订”。
    var status by remember { mutableStateOf(if (chapter.locked) ChapterStatus.REVISION else chapter.status) }
    var reason by remember { mutableStateOf("") }
    var canon by remember { mutableStateOf(false) }
    val c = LocalGroupedColors.current
    val scheme = MiuixTheme.colorScheme
    val unlocking = chapter.locked && status != ChapterStatus.FINAL
    GlassDialog(show = true, title = "“${chapter.title}”的状态", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChapterStatus.entries.forEach { s ->
                    val on = s == status
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) statusColor(s, c.secondary) else c.fill)
                            .clickable { status = s }.semantics { contentDescription = "状态${s.label}" }.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text(s.label, color = if (on) Color.White else c.label, fontSize = 14.sp) }
                }
            }
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
                Text("按检查规则，这一章还有 $ruleHits 条不合。仍然可以定稿。", color = Color(0xFFFF9500), fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (unlocking) {
                TextField(value = reason, onValueChange = { reason = it }, label = "为什么要改（必填）", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "只改写法", true to "涉及设定，要同步设定包").forEach { (v, label) ->
                        val on = v == canon
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) scheme.primary else c.fill)
                                .clickable { canon = v }.semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(label, color = if (on) scheme.onPrimary else c.label, fontSize = 14.sp) }
                    }
                }
                if (canon) {
                    Text(
                        "改动会动到不变量、细纲、人物档案或察觉表里的事实时选这个。登记里会一直提醒，直到你把设定包改好、标为已同步。",
                        color = c.secondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = if (unlocking) "解锁" else "好",
                enabled = !unlocking || reason.isNotBlank(),
                onClick = { onConfirm(status, reason, canon) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private val CARD_FIELDS: List<Triple<String, String, (ChapterCard) -> String>> = listOf(
    Triple("本章事实与结果", "发生了什么、结果（数字、生死、得失）", { it.facts }),
    Triple("后文承接边界", "后面的章节不能和哪些点冲突", { it.boundary }),
    Triple("视角人物", "", { it.pov }),
    Triple("倒计时", "章首交代：离某件事还有多久", { it.countdown }),
    Triple("章末钩子", "一个动作、一句没说完的话、一点异样", { it.hook }),
    Triple("推进的线", "比如：明线、暗线、主角线，或某个配角的弧线", { it.threads }),
)

private fun ChapterCard.with(field: String, value: String) = when (field) {
    "本章事实与结果" -> copy(facts = value)
    "后文承接边界" -> copy(boundary = value)
    "视角人物" -> copy(pov = value)
    "倒计时" -> copy(countdown = value)
    "章末钩子" -> copy(hook = value)
    else -> copy(threads = value)
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
    var statusDialog by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = rememberWritingMessages(vm)
    LaunchedEffect(workId, chapterId) {
        val (mine, prev) = vm.writing.cards(workId, chapterId)
        card = mine
        saved = mine
        previous = prev
    }
    // 停一下再存；离开时存。
    LaunchedEffect(card) {
        val now = card ?: return@LaunchedEffect
        if (now == saved) return@LaunchedEffect
        delay(600)
        vm.writing.saveCard(workId, chapterId, now)
        saved = now
    }
    val latest by rememberUpdatedState(card)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(workId, chapterId) {
        onDispose { latest?.takeIf { it != latestSaved }?.let { vm.writing.saveCard(workId, chapterId, it) } }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "细纲卡",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().imePadding().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (chapter != null) {
                        GroupedSection {
                            row {
                                GroupedRow(onClick = { statusDialog = true }) {
                                    Text(chapter.title, color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                    StatusLabel(chapter.status)
                                    Icon(LingYueIcons.ChevronForward, contentDescription = "改状态", tint = c.tertiary, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
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
                        GroupedSection(
                            header = "本章",
                            footer = "细纲只划事实边界，不管怎么写。写作 AI 改这一章、给灵感、查矛盾时都会带上它，以及上一章的钩子和承接边界。",
                        ) {
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
                                ActionRow(if (summarizing) "AI 正在概括……" else "AI 概括正文", enabled = !summarizing && aiConfig.ready && (chapter?.words ?: 0) > 0) {
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
            if (statusDialog && chapter != null) {
                StatusDialog(chapter, onDismiss = { statusDialog = false }) { s, reason, canon ->
                    statusDialog = false
                    vm.writing.setStatus(workId, chapterId, s, reason, canon)
                }
            }
        }
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

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "变更登记",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(LingYueIcons.Add, contentDescription = "登记设定变更") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    val pending = list.orEmpty().filter { it.pending }
                    if (pending.isNotEmpty()) {
                        GroupedSection(header = "待同步设定", footer = "这些改动涉及设定：把设定包里的不变量、细纲、人物档案、察觉表改好后，点开标为已同步。") {
                            pending.forEach { e -> row { ChangeRow(e) { open = e } } }
                        }
                    }
                    GroupedSection(
                        header = "全部",
                        footer = "定稿、解锁修改会自动登记；改了设定（比如人数、生死、谁知道什么）也可以点右上角手动登记一条。",
                    ) {
                        if (list != null && list.isEmpty()) row { Text("还没有登记。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                        list.orEmpty().forEach { e -> row { ChangeRow(e) { open = e } } }
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
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (e.canon) {
                            TextButton(text = "去改设定包", onClick = { open = null; vm.open(Screen.WorkPack(workId)) }, modifier = Modifier.weight(1f))
                            TextButton(
                                text = if (e.synced) "标为未同步" else "标为已同步",
                                onClick = { open = null; vm.writing.markSynced(workId, e.id, !e.synced) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        } else {
                            TextButton(text = "好", onClick = { open = null }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                        }
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
}

@Composable
private fun ChangeRow(e: ChangeEntry, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.kind.label + if (e.chapterTitle.isNotBlank()) " · ${e.chapterTitle}" else "", color = c.label, fontSize = 16.sp)
            Text(
                listOf(whenLabel(e.at), e.reason.take(40)).filter { it.isNotBlank() }.joinToString(" · "),
                color = c.secondary,
                fontSize = 13.sp,
                maxLines = 2,
            )
        }
        if (e.pending) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF9500).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("待同步", color = Color(0xFFFF9500), fontSize = 12.sp)
            }
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp))
    }
}
