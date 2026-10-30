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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ChapterSyncResult
import com.xycs.lingyuereader.Jump
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.SyncSession
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.SyncGroup
import com.xycs.lingyuereader.data.SyncItem
import com.xycs.lingyuereader.data.SyncPlace
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 各组下面的说明。 */
private fun SyncGroup.hint(): String = when (this) {
    SyncGroup.NEW -> "正文里新出现、设定包里还没有的人物、地名、物件、规矩。"
    SyncGroup.CONFLICT -> "正文和设定包（或账本）写法对不上的。默认不勾：看过再决定是改设定包，还是去改正文。"
    SyncGroup.STATE -> "人物此刻在哪、身体怎样、带着什么、想要什么，记进故事账本；同一人物同一方面以最新的为准。"
    SyncGroup.EVENT -> "已经发生、会影响后文的事，世界的变化，记进故事账本。"
    SyncGroup.AWARENESS -> "这一章里谁知道了什么、瞒着谁、误会了什么，记进故事账本。"
    SyncGroup.PROMISE -> "这一章新埋下的伏笔、悬念、约定，和收掉的伏笔，记进故事账本。"
    SyncGroup.TIME -> "这一章在故事里是什么时候、离上一章过了多久，记进故事账本。"
}

/**
 * 整理进设定包的结果：“新设定”“冲突”两组写进设定包，人物状态、发生的事、察觉、伏笔、时间几组记进故事账本。
 * 每条一行（勾选、标题、写到哪），点开看要写进去的原文（可以改；人物状态能改方面，新伏笔能改计划第几章收）
 * 和正文里的依据（点了回到编辑器、选中那一句）。底下的按钮只写勾上的。
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
                val repeats = session.repeats
                val failedChapters = session.results.filter { it.error != null }.mapTo(HashSet()) { it.chapterId }
                SyncGroup.entries.forEach { g ->
                    val list = session.items.filter { it.group == g }
                    // 新加的账本类几组没有就不显示，免得一页空组（只回了旧格式的模型就没有这几组）。
                    if (list.isEmpty() && g.ledger && g != SyncGroup.AWARENESS) return@forEach
                    item(key = "group:${g.name}") {
                        GroupedSection(header = g.label, footer = g.hint()) {
                            if (list.isEmpty()) row { EmptyRow("没有。") }
                            list.forEach { item ->
                                row {
                                    SyncItemRow(
                                        item,
                                        edited = session.edited(item),
                                        checked = item.key in session.picked,
                                        expanded = item.key in open,
                                        showChapter = session.results.size > 1,
                                        repeat = item.key in repeats,
                                        selectable = item.chapterId !in failedChapters,
                                        onToggle = { vm.writingAi.toggleSync(item.key, item.key !in session.picked) },
                                        onExpand = { open = if (item.key in open) open - item.key else open + item.key },
                                        onEdit = { vm.writingAi.editSync(item.key, it) },
                                        onAspect = { vm.writingAi.editSyncAspect(item.key, it) },
                                        onPayoff = { vm.writingAi.editSyncPayoff(item.key, it) },
                                        onJump = { jump(item) },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "write") {
                    val chosen = session.items.filter { it.writable && it.key in session.picked }
                    val n = chosen.size
                    val toLedger = chosen.any { it.place == SyncPlace.LEDGER }
                    val toPack = chosen.any { it.place != SyncPlace.LEDGER }
                    PrimaryButton(
                        when {
                            n == 0 -> "不写，标为已整理"
                            toLedger && toPack -> "写进设定包和账本（$n 条）"
                            toLedger -> "记进故事账本（$n 条）"
                            else -> "写进设定包（$n 条）"
                        },
                        onClick = {
                            writing = true
                            vm.writingAi.applySync(workId, failed = { writing = false }) { msg ->
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
        if (known > 0) add("有 $known 条设定包或账本里已经有了，没再列。")
        if (session.results.any { it.error != null }) add("没整理全的章只展示提议供核对；重新整理成功后才能勾选写入。")
        if (session.results.any { it.noTools }) add(ChapterSyncResult.NO_TOOLS_NOTE + "；勾选前请对照设定包看一遍。")
        add("勾上的才会写进去；写设定包之前原来的文字先存一个历史版本，每写一条在变更登记里记一笔；记进账本的一章一批，账本也存历史版本。")
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
                    ErrorText(
                        (if (r.parts > 1) "整理「${r.title}」没整理全，${r.items.size} 条提议仅供核对：" else "整理「${r.title}」没成：") + r.error,
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    TwoLine(
                        r.title,
                        "第 ${r.number} 章 · " + (if (r.parts > 1) "分 ${r.parts} 段整理 · " else "") +
                            (if (r.items.isEmpty()) "没有要整理的" else "${r.items.size} 条提议") +
                            (if (r.noTools) "\n" + ChapterSyncResult.NO_TOOLS_NOTE else ""),
                    )
                }
            }
        }
    }
}

/**
 * 一条提议：左边勾选框（“正文写错了”的冲突不能写进设定包，换成小标签），标题，下面一行写到哪（前面章已经提过同一个的，[repeat] 标出来）；
 * 点开（高度用 [Motion.MEDIUM] 长出来）看设定包和正文各怎么写、要写进去的原文（可以改）、依据原句。
 * 人物状态多一行“方面”（下拉选），新伏笔多一格“计划第几章收”，收伏笔写明收的是账本里哪一条。[edited] 是按作者改过的样子。
 */
@Composable
internal fun SyncItemRow(
    item: SyncItem,
    edited: SyncItem,
    checked: Boolean,
    expanded: Boolean,
    showChapter: Boolean,
    repeat: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onEdit: (String) -> Unit,
    onAspect: (String) -> Unit,
    onPayoff: (Int?) -> Unit,
    onJump: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, Motion.standard(Motion.SHORT), label = "syncChevron")
    val entry = edited.entry
    val opens = item.group == SyncGroup.PROMISE && item.closes == null && entry != null
    Column(Modifier.fillMaxWidth()) {
        GroupedRow(onClick = onExpand) {
            if (item.writable && selectable) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Radius.Small))
                        .clickable(onClick = onToggle)
                        .semantics {
                            contentDescription = "勾选：${item.title}"
                            stateDescription = if (checked) "已勾选" else "没勾选"
                        },
                ) {
                    Checkbox(state = ToggleableState(checked), onClick = onToggle)
                }
            }
            Column(Modifier.weight(1f).padding(start = if (item.writable && selectable) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, lineHeight = TypeScale.BodyLine)
                val extra = when {
                    opens -> entry.payoff?.let { " · 计划第 $it 章收" }.orEmpty()
                    item.group == SyncGroup.STATE -> entry?.aspect?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    else -> ""
                }
                Text(
                    (if (showChapter) "第 ${item.chapter} 章 · " else "") + (if (repeat) "前面章已有 · " else "") +
                        (if (item.maybeSame != null) "可能和账本里某条重复 · " else "") + item.where + extra,
                    color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine,
                )
            }
            if (item.writable && !selectable) Tag("待重整", c.warning, Modifier.padding(start = 8.dp))
            else if (!item.writable) Tag(if (item.fixText) "改正文" else "只提示", c.warning, Modifier.padding(start = 8.dp))
            Icon(LingYueIcons.ChevronDown, contentDescription = if (expanded) "收起" else "展开", tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(16.dp).rotate(turn))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (item.group == SyncGroup.STATE && item.writable && selectable && entry != null) {
                    // 方面：常用的几项，AI 写了别的也列上；“不分”的不顶替别的状态。
                    val options = (listOf("" to "不分") + (LEDGER_ASPECTS + listOf(entry.aspect).filter { it.isNotBlank() && it !in LEDGER_ASPECTS }).map { it to it })
                    PickerRow("方面", options, entry.aspect, onSelect = onAspect)
                }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.group == SyncGroup.CONFLICT) {
                        val says = if (item.place == SyncPlace.LEDGER) "账本" else "设定包${item.docTitle.takeIf { it.isNotBlank() }?.let { "《$it》" }.orEmpty()}"
                        if (item.packSays.isNotBlank()) Text("$says：${item.packSays}", color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                        if (item.textSays.isNotBlank()) Text("正文：${item.textSays}", color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                    }
                    item.maybeSame?.let { same ->
                        Text("账本里已有：${same.text}" + same.whenLabel.let { if (it.isEmpty()) "" else "（$it）" } + "；确实是另一件事再勾上", color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                    }
                    item.reveals?.let { secret ->
                        Text("账本里原本记着只有作者知道：${secret.text}；勾上记下从这一章起知道了", color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                    }
                    val closes = item.closes
                    if (closes != null) {
                        Text("收的是账本里这一条：${closes.text}" + closes.whenLabel.let { if (it.isEmpty()) "" else "（$it）" }, color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                    } else if (item.writable && selectable) {
                        TextField(
                            value = edited.text,
                            onValueChange = onEdit,
                            label = if (item.place == SyncPlace.LEDGER) "记进账本的内容（可以改）" else "写进去的原文（可以改）",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 220.dp),
                        )
                    } else if (item.writable) {
                        Text("候选内容：${edited.text}", color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
                    }
                    if (opens && item.writable && selectable) {
                        var raw by remember(item.key) { mutableStateOf(entry.payoff?.toString().orEmpty()) }
                        TextField(
                            value = raw,
                            onValueChange = { v ->
                                raw = v.filter { it.isDigit() }.take(5)
                                onPayoff(raw.toIntOrNull()?.takeIf { it > 0 })
                            },
                            label = "计划第几章收（可以空着）",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Small)).clickable(onClick = onJump).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("依据：「${item.quote}」", color = c.tint, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, modifier = Modifier.weight(1f))
                        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    if (!item.writable && item.fixText) PillButton("去改正文", onClick = onJump, style = PillStyle.Tinted)
                }
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
        summary = "AI 读一遍这一章，列出新出现的人物地名、和设定包对不上的地方，以及人物状态的变化、谁知道了什么、埋下和收起的伏笔；你勾选以后才写进去。",
        onDismissRequest = { onDismiss(never) },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Medium)).clickable { never = !never }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state = ToggleableState(never), onClick = { never = !never })
            Text("不再提示", color = c.label, fontSize = TypeScale.Body, modifier = Modifier.padding(start = 12.dp))
        }
        DialogButtons(onDismiss = { onDismiss(never) }, onConfirm = { onSync(never) }, confirmText = "整理", dismissText = "不用了")
    }
}
