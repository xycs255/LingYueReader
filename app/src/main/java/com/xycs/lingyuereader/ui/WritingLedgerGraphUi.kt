package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.LedgerChapter
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.StoryLedger
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

internal enum class LedgerVisualTab(val title: String) {
    PEOPLE("人物"), PROMISES("伏笔"), KNOWLEDGE("知情"), RECORDS("全部记录")
}

internal data class LedgerQuickState(val subject: String, val aspect: String, val current: LedgerEntry?)
internal data class LedgerQuickKnowledge(val subject: String, val fact: LedgerEntry)
internal data class LedgerGraphUndo(val before: LedgerEntry?, val after: LedgerEntry, val label: String)

/** Old chapter states remain in the ledger; cards show the latest value per aspect. */
internal fun ledgerGraphStates(entries: List<LedgerEntry>, chapter: LedgerChapter?): List<LedgerEntry> =
    if (chapter != null) StoryLedger.snapshot(entries, chapter.no, chapter.volume).states
    else entries.filter { it.kind == LedgerKind.STATE }
        .groupBy { it.subject.trim() to it.aspect.trim() }
        .values.map { it.maxWith(compareBy<LedgerEntry>({ e -> e.from ?: -1 }, { e -> e.updatedAt })) }

/** An intervening edit prevents undo from silently overwriting that edit. */
internal fun ledgerGraphUndoOp(current: List<LedgerEntry>, before: LedgerEntry?, after: LedgerEntry): LedgerOp? {
    val actual = current.firstOrNull { it.id == after.id } ?: return null
    if (actual.copy(updatedAt = 0) != after.copy(updatedAt = 0)) return null
    return if (before == null) LedgerOp.Remove(after.id) else LedgerOp.Update(before)
}

@Composable
internal fun LedgerVisualTabs(selected: LedgerVisualTab, onSelect: (LedgerVisualTab) -> Unit) {
    SegmentedControl(LedgerVisualTab.entries.map { it to it.title }, selected, onSelect = onSelect)
}

@Composable
private fun LedgerGraphCard(content: @Composable () -> Unit) {
    val c = LocalGroupedColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp)).background(c.cell).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

internal fun LazyListScope.ledgerPeopleGraph(
    entries: List<LedgerEntry>,
    chapter: LedgerChapter?,
    personFilter: String?,
    onState: (LedgerQuickState) -> Unit,
    onRecords: () -> Unit,
) {
    val states = ledgerGraphStates(entries, chapter).filter { it.subject.isNotBlank() }
    val people = entries.map { it.subject.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        .filter { personFilter == null || it == personFilter }
    if (people.isEmpty()) {
        item(key = "graph:people:empty") { GroupedSection { row { EmptyRow("还没有人物记录。可手动添加或从设定包导入。") } } }
        return
    }
    item(key = "graph:people:intro") { GroupedListHeader("人物状态 · 点方面直接调整") }
    items(people, key = { "graph:person:$it" }) { who ->
        val current = states.filter { it.subject == who }
        val aspects = (LEDGER_ASPECTS + current.map { it.aspect }.filter { it.isNotBlank() }).distinct()
        val c = LocalGroupedColors.current
        LedgerGraphCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.fill), contentAlignment = Alignment.Center) {
                    Text(who.take(1), color = c.tint, fontSize = 18.sp)
                }
                Column {
                    Text(who, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(current.size.toString() + " 个状态方面", color = c.secondary, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                aspects.forEach { aspect ->
                    val latest = current.firstOrNull { it.aspect == aspect }
                    PillButton(
                        aspect + " · " + (latest?.text?.take(8) ?: "添加"),
                        onClick = { onState(LedgerQuickState(who, aspect, latest)) },
                        modifier = Modifier.semantics {
                            contentDescription = who + "的" + aspect + "，" + (latest?.text ?: "尚未记录") + "，点按修改"
                        },
                    )
                }
            }
            current.firstOrNull()?.let { e ->
                Text(
                    listOf(e.whenLabel, e.origin.label, e.quote.takeIf { it.isNotBlank() }?.let { "有原文依据" })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    color = c.secondary, fontSize = 12.sp,
                )
            }
        }
    }
    item(key = "graph:people:all") {
        GroupedSection(footer = "不同章的旧状态仍在全部记录里；新状态不会覆盖已发生的前文。") {
            row { ActionRow("查看全部人物记录", onClick = onRecords) }
        }
    }
}

internal fun LazyListScope.ledgerPromiseGraph(
    entries: List<LedgerEntry>,
    chapter: LedgerChapter?,
    chapters: List<LedgerChapter>,
    personFilter: String?,
    onPayoff: (LedgerEntry, Int) -> Unit,
    onOpen: (LedgerEntry) -> Unit,
) {
    val promises = ledgerGraphPromises(entries, chapter, personFilter)
    if (promises.isEmpty()) {
        item(key = "graph:promise:empty") { GroupedSection { row { EmptyRow("还没有账本伏笔。设定包伏笔表可显式导入。") } } }
        return
    }
    item(key = "graph:promise:intro") { GroupedListHeader("账本伏笔 · 拖动计划收章") }
    items(promises, key = { "graph:promise:" + it.id }) { e -> LedgerPromiseCard(e, chapters, onPayoff, onOpen) }
    item(key = "graph:promise:foot") {
        GroupedListFooter("这里调整按章推进的账本；设定包里的原始伏笔计划不会被静默改写，可从右上菜单显式导出。")
    }
}

internal fun ledgerGraphPromises(entries: List<LedgerEntry>, chapter: LedgerChapter?, personFilter: String?): List<LedgerEntry> =
    entries.filter {
        it.kind == LedgerKind.PROMISE &&
            (chapter == null || it.reached(chapter.no, chapter.volume)) &&
            (personFilter == null || it.subject == personFilter)
    }.sortedWith(compareBy({ it.payoff ?: Int.MAX_VALUE }, { it.from ?: -1 }))

@Composable
private fun LedgerPromiseCard(
    entry: LedgerEntry,
    chapters: List<LedgerChapter>,
    onPayoff: (LedgerEntry, Int) -> Unit,
    onOpen: (LedgerEntry) -> Unit,
) {
    val c = LocalGroupedColors.current
    val start = (entry.from ?: 1).coerceAtLeast(1)
    val end = maxOf(start + 2, chapters.maxOfOrNull { it.no } ?: start + 2, (entry.payoff ?: start) + 2)
    var dragging by remember(entry.id, entry.payoff) { mutableFloatStateOf((entry.payoff ?: start + 1).coerceIn(start, end).toFloat()) }
    val selected = dragging.roundToInt().coerceIn(start, end)
    LedgerGraphCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.text, modifier = Modifier.weight(1f), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Tag(if (entry.done) "已收" else "未收", if (entry.done) c.success else c.warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.tint))
            Box(Modifier.weight(1f).height(2.dp).background(c.separator))
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.tint))
        }
        Text(
            "第 $start 章埋下  →  " + if (entry.done) entry.closed?.let { "第 $it 章已收" } ?: "已收" else "计划第 $selected 章收",
            color = c.secondary, fontSize = 12.sp,
        )
        if (!entry.done) {
            LingYueSlider(
                value = dragging,
                onValueChange = { dragging = it },
                valueRange = start.toFloat()..end.toFloat(),
                label = "调整“" + entry.text + "”计划收章",
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                valueText = "第 $selected 章",
                onValueChangeFinished = { if (selected != entry.payoff) onPayoff(entry, selected) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 $start 章", color = c.tertiary, fontSize = 11.sp)
                Text("第 $end 章", color = c.tertiary, fontSize = 11.sp)
            }
        }
        PillButton("查看来源与精确编辑", onClick = { onOpen(entry) })
    }
}

internal fun LazyListScope.ledgerKnowledgeGraph(
    entries: List<LedgerEntry>,
    chapter: LedgerChapter?,
    personFilter: String?,
    onKnown: (LedgerEntry) -> Unit,
    onUnknown: (LedgerQuickKnowledge) -> Unit,
) {
    val facts = entries.filter { it.kind == LedgerKind.KNOW && it.text.isNotBlank() }.groupBy { it.text.trim() }
    val people = entries.map { it.subject.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        .filter { personFilter == null || it == personFilter }
    if (facts.isEmpty() || people.isEmpty()) {
        item(key = "graph:knowledge:empty") { GroupedSection { row { EmptyRow("还没有知情记录。新增时要写清获知章与消息来源。") } } }
        return
    }
    item(key = "graph:knowledge:intro") { GroupedListHeader("谁知道什么 · 点格子核对") }
    facts.forEach { (fact, factEntries) ->
        item(key = "graph:knowledge:$fact") {
            val c = LocalGroupedColors.current
            LedgerGraphCard {
                Text(fact, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { who ->
                        val byPerson = factEntries.filter { it.subject == who }
                        val recentFirst = compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt })
                        val existing = byPerson.maxWithOrNull(recentFirst)
                        val known = byPerson.filter { chapter == null || it.validAt(chapter.no, chapter.volume) }
                            .maxWithOrNull(recentFirst)
                        val cellLabel = when {
                            known != null -> "已知"
                            existing == null -> "尚未知"
                            existing.from != null && chapter != null && existing.from > chapter.no -> "第${existing.from}章后知"
                            else -> "该章未生效"
                        }
                        Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(who, color = c.secondary, fontSize = 12.sp, maxLines = 1)
                            PillButton(
                                cellLabel,
                                onClick = {
                                    when {
                                        known != null -> onKnown(known)
                                        existing != null -> onKnown(existing)
                                        else -> onUnknown(LedgerQuickKnowledge(who, factEntries.first()))
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = who + "对“" + fact + "”" + cellLabel +
                                        (if (existing == null) "，点按登记获知章和来源" else "，已有记录，点按查看或修改来源")
                                },
                                style = if (known == null) PillStyle.Plain else PillStyle.Tinted,
                            )
                        }
                    }
                }
                Text("已知格可查看来源；登记未知格须填写获知章和消息来源。", color = c.secondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun LedgerQuickStateDialog(
    target: LedgerQuickState,
    chapter: LedgerChapter?,
    chapters: List<LedgerChapter>,
    onDismiss: () -> Unit,
    onSave: (LedgerEntry?, LedgerEntry) -> Unit,
) {
    val current = target.current
    var value by remember(target) { mutableStateOf(current?.text.orEmpty()) }
    var from by remember(target) { mutableStateOf((chapter?.no ?: current?.from)?.toString().orEmpty()) }
    var quote by remember(target) { mutableStateOf(if (current?.from == chapter?.no) current?.quote.orEmpty() else "") }
    val number = from.toIntOrNull()
    val validChapter = number != null && number > 0 && chapters.any { it.no == number }
    GlassDialog(show = true, title = target.subject + " · " + target.aspect, summary = "按章登记新状态；之前章节保留原来的状态。", onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = value, onValueChange = { value = it }, label = "本章状态", modifier = Modifier.fillMaxWidth())
            TextField(
                value = from, onValueChange = { from = it.filter(Char::isDigit).take(5) }, label = "从第几章生效",
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            TextField(value = quote, onValueChange = { quote = it }, label = "原文依据（可选）", modifier = Modifier.fillMaxWidth())
            Text("来源：作者手工登记。未填原文依据时不会冒充有正文证据。", color = c.secondary, fontSize = 12.sp)
            if (!validChapter) Text("请填作品中已有的章号。", color = c.warning, fontSize = 12.sp)
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                val old = current?.takeIf { it.from == number && it.origin == LedgerOrigin.MANUAL }
                val next = if (old != null) old.copy(text = value.trim(), quote = quote.trim())
                else LedgerEntry(kind = LedgerKind.STATE, text = value.trim(), subject = target.subject, aspect = target.aspect, from = number, quote = quote.trim())
                onSave(old, next)
            },
            confirmText = "保存状态",
            confirmEnabled = validChapter && value.isNotBlank() &&
                (current == null || value.trim() != current.text || number != current.from || quote.trim() != current.quote),
        )
    }
}

@Composable
internal fun LedgerQuickKnowledgeDialog(
    target: LedgerQuickKnowledge,
    chapter: LedgerChapter?,
    chapters: List<LedgerChapter>,
    onDismiss: () -> Unit,
    onSave: (LedgerEntry) -> Unit,
) {
    var from by remember(target) { mutableStateOf(chapter?.no?.toString().orEmpty()) }
    var via by remember(target) { mutableStateOf("") }
    var quote by remember(target) { mutableStateOf("") }
    val number = from.toIntOrNull()
    val validChapter = number != null && number > 0 && chapters.any { it.no == number }
    GlassDialog(show = true, title = target.subject + "得知了什么", summary = target.fact.text, onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = from, onValueChange = { from = it.filter(Char::isDigit).take(5) }, label = "从第几章得知",
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            TextField(value = via, onValueChange = { via = it }, label = "消息来源（谁告诉、偷听等）", modifier = Modifier.fillMaxWidth())
            TextField(value = quote, onValueChange = { quote = it }, label = "原文依据（可选）", modifier = Modifier.fillMaxWidth())
            if (!validChapter) Text("请填作品中已有的章号。", color = c.warning, fontSize = 12.sp)
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                onSave(
                    LedgerEntry(
                        kind = LedgerKind.KNOW, text = target.fact.text, subject = target.subject,
                        from = number, via = via.trim(), quote = quote.trim(), secret = target.fact.secret,
                    ),
                )
            },
            confirmText = "登记知情",
            confirmEnabled = validChapter && via.isNotBlank(),
        )
    }
}
