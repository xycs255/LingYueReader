package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Jump
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.SyncSession
import com.xycs.lingyuereader.data.SyncGroup
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 三组下面的说明。 */
private fun SyncGroup.hint(): String = when (this) {
    SyncGroup.NEW -> "正文里新出现、设定包里还没有的人物、地名、物件、规矩。"
    SyncGroup.CONFLICT -> "正文和设定包写法对不上的。默认不勾：看过再决定是改设定包，还是去改正文。"
    SyncGroup.AWARENESS -> "这一章里谁知道了什么、瞒着谁、误会了什么，写进察觉表。"
}

/**
 * 整理进设定包的结果：分“新设定”“冲突”“察觉”三组，每条一行（勾选、标题、写进哪份文档），点开看要写进去的原文（可以改）
 * 和正文里的依据（点了回到编辑器、选中那一句）。底下“写进设定包”只写勾上的。
 */
@Composable
fun ChapterSyncScreen(vm: AppViewModel, workId: String) {
    val all by vm.writingAi.sync.collectAsStateWithLifecycle()
    val session = all?.takeIf { it.workId == workId }
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    val snackbar = rememberWritingMessages(vm)
    var open by remember { mutableStateOf(setOf<String>()) }
    var writing by remember { mutableStateOf(false) }

    fun jump(item: SyncItem) {
        vm.writing.jump.value = Jump(item.chapterId, item.quote)
        // 从这一章的编辑器来的：回去；否则打开那一章（回来时结果还在）。
        val below = vm.stack.value.dropLast(1).lastOrNull()
        if (below is Screen.Editor && below.workId == workId && below.chapterId == item.chapterId) vm.back()
        else vm.open(Screen.Editor(workId, item.chapterId))
    }

    SubPageScaffold("整理进设定包", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "head") { SyncHead(vm, session, lookup) }
            if (session != null && session.results.isNotEmpty()) {
                SyncGroup.entries.forEach { g ->
                    item(key = "group:${g.name}") {
                        val list = session.items.filter { it.group == g }
                        GroupedSection(header = g.label, footer = g.hint()) {
                            if (list.isEmpty()) row { EmptyRow("没有。") }
                            list.forEach { item ->
                                row {
                                    SyncItemRow(
                                        item,
                                        checked = item.key in session.picked,
                                        expanded = item.key in open,
                                        text = session.edits[item.key] ?: item.text,
                                        showChapter = session.results.size > 1,
                                        onToggle = { vm.writingAi.toggleSync(item.key, item.key !in session.picked) },
                                        onExpand = { open = if (item.key in open) open - item.key else open + item.key },
                                        onEdit = { vm.writingAi.editSync(item.key, it) },
                                        onJump = { jump(item) },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "write") {
                    val n = session.items.count { it.writable && it.key in session.picked }
                    PrimaryButton(
                        if (n > 0) "写进设定包（$n 条）" else "不写，标为已整理",
                        onClick = {
                            writing = true
                            vm.writingAi.applySync(workId) { msg ->
                                vm.writing.loadPack(workId)
                                vm.writing.loadChanges(workId)
                                vm.showMessage(msg)
                                vm.back()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        enabled = session.running == null && !writing && session.results.any { it.error == null },
                    )
                }
            }
        }
    }
}

/** 头一张卡片：整理到哪了（转圈、可停）、整理了哪几章、出错的章、丢掉了几条。 */
@Composable
private fun SyncHead(vm: AppViewModel, session: SyncSession?, lookup: String?) {
    if (session == null) {
        GroupedSection { row { EmptyRow("没有在整理的章节。") } }
        return
    }
    val dropped = session.results.sumOf { it.dropped }
    val known = session.results.sumOf { it.known }
    val footer = buildList {
        if (dropped > 0) add("有 $dropped 条在正文里找不到 AI 给的原句，没有列出（防止编造）。")
        if (known > 0) add("有 $known 条设定包里已经有了，没再列。")
        add("勾上的才会写进设定包；写之前原来的文字先存一个历史版本，每写一条在变更登记里记一笔。")
    }.joinToString("")
    GroupedSection(footer = footer) {
        if (session.running != null) {
            row {
                LoadingRow(
                    (if (session.batch) "正在整理 ${session.done + 1}/${session.total}：" else "正在读") + "「${session.running}」，对照设定包……" +
                        (lookup?.let { "\n$it" } ?: ""),
                )
            }
            row { ActionRow("停止", destructive = true) { vm.writingAi.stopSync() } }
        }
        session.results.forEach { r ->
            row {
                if (r.error != null) {
                    ErrorText("整理「${r.title}」没成：${r.error}", Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                } else {
                    TwoLine(r.title, "第 ${r.number} 章 · " + if (r.items.isEmpty()) "没有要整理的" else "${r.items.size} 条提议")
                }
            }
        }
    }
}

/**
 * 一条提议：左边勾选框（“正文写错了”的冲突不能写进设定包，换成小标签），标题，下面一行写进哪份文档；
 * 点开（高度用 [Motion.MEDIUM] 长出来）看设定包和正文各怎么写、要写进去的原文（可以改）、依据原句。
 */
@Composable
private fun SyncItemRow(
    item: SyncItem,
    checked: Boolean,
    expanded: Boolean,
    text: String,
    showChapter: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onEdit: (String) -> Unit,
    onJump: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, Motion.standard(Motion.SHORT), label = "syncChevron")
    Column(Modifier.fillMaxWidth()) {
        GroupedRow(onClick = onExpand) {
            if (item.writable) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggle)
                        .semantics {
                            contentDescription = "勾选：${item.title}"
                            stateDescription = if (checked) "已勾选" else "没勾选"
                        },
                ) {
                    Checkbox(state = ToggleableState(checked), onClick = onToggle)
                }
            }
            Column(Modifier.weight(1f).padding(start = if (item.writable) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                Text((if (showChapter) "第 ${item.chapter} 章 · " else "") + item.where, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (!item.writable) Tag(if (item.fixText) "改正文" else "只提示", c.warning, Modifier.padding(start = 8.dp))
            Icon(LingYueIcons.ChevronDown, contentDescription = if (expanded) "收起" else "展开", tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(16.dp).rotate(turn))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.group == SyncGroup.CONFLICT) {
                    if (item.packSays.isNotBlank()) Text("设定包${item.docTitle.takeIf { it.isNotBlank() }?.let { "《$it》" }.orEmpty()}：${item.packSays}", color = c.label, fontSize = 14.sp, lineHeight = 20.sp)
                    if (item.textSays.isNotBlank()) Text("正文：${item.textSays}", color = c.label, fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (item.writable) {
                    TextField(
                        value = text,
                        onValueChange = onEdit,
                        label = "写进去的原文（可以改）",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 220.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onJump).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("依据：「${item.quote}」", color = c.tint, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                if (!item.writable && item.fixText) PillButton("去改正文", onClick = onJump, style = PillStyle.Tinted)
            }
        }
    }
}

/**
 * 把一章标为定稿时问一句：要不要顺手整理进设定包。可以勾“不再提示”（记在这部作品的 AI 设置里）。
 * [onDismiss]、[onSync] 拿到“不再提示”勾没勾。
 */
@Composable
internal fun SyncTipDialog(onDismiss: (Boolean) -> Unit, onSync: (Boolean) -> Unit) {
    var never by remember { mutableStateOf(false) }
    val c = LocalGroupedColors.current
    GlassDialog(
        show = true,
        title = "要不要顺手整理进设定包？",
        summary = "AI 读一遍这一章，列出新出现的人物地名、和设定包对不上的地方、谁知道了什么；你勾选以后才写进设定包。",
        onDismissRequest = { onDismiss(never) },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { never = !never }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state = ToggleableState(never), onClick = { never = !never })
            Text("不再提示", color = c.label, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
        }
        DialogButtons(onDismiss = { onDismiss(never) }, onConfirm = { onSync(never) }, confirmText = "整理", dismissText = "不用了")
    }
}
