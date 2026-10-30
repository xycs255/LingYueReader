package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.LedgerChapter
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.Foreshadow
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

/**
 * An intervening edit prevents undo from silently overwriting that edit. [after] 是刚写进去的那一条（连同写入时间）：
 * 撤销带着它去存储层比对（[LedgerOp.Update.expected]），界面上的列表过期了也不会覆盖别处后来的修改。
 */
internal fun ledgerGraphUndoOp(current: List<LedgerEntry>, before: LedgerEntry?, after: LedgerEntry): LedgerOp? {
    val actual = current.firstOrNull { it.id == after.id } ?: return null
    if (actual.copy(updatedAt = 0) != after.copy(updatedAt = 0)) return null
    return if (before == null) LedgerOp.Remove(after.id, expected = after) else LedgerOp.Update(before, expected = after)
}

/**
 * 知情图里的一件事（按内容归在一起）：[entries] 是这件事所有的知情记录，[truth] 是其中只有作者知道的那几条
 * （察觉表“真相”那一格导入的，意思是视角人物还不知道）——它们不算任何人已知。
 */
internal data class LedgerGraphFact(val text: String, val entries: List<LedgerEntry>) {
    val truth: List<LedgerEntry> get() = entries.filter { it.secret }
}

/** 一个人对一件事：[known] 是到这一章已经知道的那一条，[existing] 是最新的一条记录（还没到的也算），都没有是“尚未知”。 */
internal data class LedgerGraphCell(val known: LedgerEntry?, val existing: LedgerEntry?)

internal fun ledgerGraphFacts(entries: List<LedgerEntry>): List<LedgerGraphFact> =
    entries.filter { it.kind == LedgerKind.KNOW && it.text.isNotBlank() }.groupBy { it.text.trim() }.map { (text, list) -> LedgerGraphFact(text, list) }

/** [who] 对 [fact] 知不知道：只看不是只有作者知道的记录。 */
internal fun ledgerGraphCell(fact: LedgerGraphFact, who: String, chapter: LedgerChapter?): LedgerGraphCell {
    val byPerson = fact.entries.filter { it.subject == who && !it.secret }
    val recentFirst = compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt })
    return LedgerGraphCell(
        known = byPerson.filter { chapter == null || it.validAt(chapter.no, chapter.volume) }.maxWithOrNull(recentFirst),
        existing = byPerson.maxWithOrNull(recentFirst),
    )
}

/** 在知情格上登记的一条：作者登记的是人物得知了，一律不是只有作者知道的（不继承那件事的“真相”标记）。 */
internal fun ledgerQuickKnowledgeEntry(target: LedgerQuickKnowledge, from: Int?, via: String, quote: String): LedgerEntry =
    LedgerEntry(kind = LedgerKind.KNOW, text = target.fact.text, subject = target.subject, from = from, via = via.trim(), quote = quote.trim(), secret = false)

/**
 * 伏笔卡滑块松手时要写的计划收章：只有真的拖过（[dragging] 不为空）、值和账本里的不一样才写；
 * 没定计划收章（[LedgerEntry.payoff] 为空）的伏笔不在这里写，要作者点“定计划收章”、再点保存。
 */
internal fun ledgerPayoffToSave(entry: LedgerEntry, dragging: Float?, start: Int, end: Int): Int? {
    if (entry.payoff == null || dragging == null) return null
    return dragging.roundToInt().coerceIn(start, end).takeIf { it != entry.payoff }
}

/** 人物 / 伏笔 / 知情 / 全部记录：左右和下面的卡片一样缩进 12dp，不贴屏幕边。 */
@Composable
internal fun LedgerVisualTabs(selected: LedgerVisualTab, onSelect: (LedgerVisualTab) -> Unit) {
    SegmentedControl(
        LedgerVisualTab.entries.map { it to it.title },
        selected,
        Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
        onSelect = onSelect,
    )
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
            // 方面多时换行，不横着溢出、也不切掉最后一个；胶囊能点的范围 48dp，行与行之间不再另加间距。
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                aspects.forEach { aspect ->
                    val latest = current.firstOrNull { it.aspect == aspect }
                    PillButton(
                        aspect + " · " + (latest?.text?.let { if (it.length > 8) it.take(8) + "…" else it } ?: "添加"),
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

/**
 * 伏笔标签上从原来“伏笔”页并过来的几样：设定包伏笔表里的条目（[legacy]，还没导入账本的也列出来）、写到了第几章（[latest]，
 * 算哪些该回收了）、伏笔表文档（[docs]，id 到标题）。
 */
internal data class LedgerPromiseSources(
    val legacy: List<Foreshadow> = emptyList(),
    val latest: Int = 0,
    val docs: List<Pair<String, String>> = emptyList(),
)

/** 计划回收的章已经写到、还没标为已回收。 */
private fun promiseDue(done: Boolean, payoff: Int?, latest: Int) = !done && payoff != null && payoff <= latest

/**
 * 账本的“伏笔”标签：该回收了 / 还没回收 / 已回收三组（账本里的一条一张卡片，能拖计划收章、标为已回收；
 * 伏笔表里还没导入账本的一条一行，点了也能标为已回收），最后是来源文档（伏笔表）。回收状态以账本为准。
 */
internal fun LazyListScope.ledgerPromiseGraph(
    entries: List<LedgerEntry>,
    chapter: LedgerChapter?,
    chapters: List<LedgerChapter>,
    personFilter: String?,
    onPayoff: (LedgerEntry, Int, () -> Unit) -> Unit,
    onOpen: (LedgerEntry) -> Unit,
    sources: LedgerPromiseSources = LedgerPromiseSources(),
    onClose: (WritingForeshadow) -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
) {
    val promises = ledgerGraphPromises(entries, chapter, personFilter)
    val merged = mergeWritingForeshadows(sources.legacy, entries)
    val byId = merged.mapNotNull { f -> f.ledger?.let { it.id to f } }.toMap()
    // 伏笔表里还没导入的没有人物，只人物筛选时不列；翻到某一章时只列那一章以前埋下的。
    val legacyOnly = if (personFilter != null) emptyList()
    else merged.filter { it.ledger == null && (chapter == null || (it.display.planted ?: 0) <= chapter.no) }
    val latest = sources.latest
    val dueCards = promises.filter { promiseDue(it.done, it.payoff, latest) }
    val dueRows = legacyOnly.filter { promiseDue(it.display.done, it.display.payoff, latest) }
    val openCards = promises.filter { !it.done } - dueCards.toSet()
    val openRows = legacyOnly.filter { !it.display.done } - dueRows.toSet()
    val doneCards = promises.filter { it.done }
    val doneRows = legacyOnly.filter { it.display.done }

    fun cards(key: String, list: List<LedgerEntry>, due: Boolean) {
        items(list, key = { "graph:promise:$key:" + it.id }) { e ->
            LedgerPromiseCard(e, chapters, onPayoff, onOpen, due = due, onClose = byId[e.id]?.let { f -> { onClose(f) } })
        }
    }
    fun rows(key: String, list: List<WritingForeshadow>, footer: String?) {
        if (list.isEmpty()) return
        item(key = "graph:promise:$key:legacy") {
            val c = LocalGroupedColors.current
            GroupedSection(footer = footer) {
                list.forEach { f ->
                    val whenText = ChapterBrief.foreshadowWhen(f.display).trim('（', '）')
                    row {
                        if (f.display.done) TwoLine(f.display.what, whenText, trailing = { Tag("已回收", c.success) })
                        else NavRow(f.display.what, subtitle = whenText.ifEmpty { null }, trailing = { Tag(if (promiseDue(false, f.display.payoff, latest)) "该回收" else "未导入", c.warning) }) { onClose(f) }
                    }
                }
            }
        }
    }

    if (promises.isEmpty() && legacyOnly.isEmpty()) {
        item(key = "graph:promise:empty") { GroupedSection { row { EmptyRow("还没有伏笔。右上角“+”加一条，或从右上菜单导入设定包的伏笔表。") } } }
    }
    if (dueCards.isNotEmpty() || dueRows.isNotEmpty()) {
        item(key = "graph:promise:due:head") { GroupedListHeader("该回收了") }
        cards("due", dueCards, due = true)
        rows("due", dueRows, null)
        item(key = "graph:promise:due:foot") { GroupedListFooter("计划回收的章已经写到（写到第 $latest 章），还没标为已回收。") }
    }
    if (openCards.isNotEmpty() || openRows.isNotEmpty()) {
        item(key = "graph:promise:intro") { GroupedListHeader("还没回收 · 拖动计划收章") }
        cards("open", openCards, due = false)
        rows("open", openRows, "伏笔表里还没导入账本的；点一条可以标为已回收，从右上菜单“从设定包导入”后才能拖动计划收章。")
        item(key = "graph:promise:foot") {
            GroupedListFooter("这里调整按章推进的账本；设定包里的原始伏笔计划不会被静默改写，可从右上菜单显式导出。")
        }
    }
    if (doneCards.isNotEmpty() || doneRows.isNotEmpty()) {
        item(key = "graph:promise:done:head") { GroupedListHeader("已回收") }
        cards("done", doneCards, due = false)
        rows("done", doneRows, null)
    }
    if (sources.docs.isNotEmpty()) {
        item(key = "graph:promise:docs") {
            GroupedSection(header = "来源文档", footer = "伏笔表尚未导入的条目也会显示；与账本重复时，账本里的状态优先。") {
                sources.docs.forEach { (id, title) ->
                    row { NavRow(if (sources.docs.size == 1) "编辑伏笔表" else "编辑“$title”") { onOpenDoc(id) } }
                }
            }
        }
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
    onPayoff: (LedgerEntry, Int, () -> Unit) -> Unit,
    onOpen: (LedgerEntry) -> Unit,
    due: Boolean = false,
    /** 没收的伏笔：“标为已回收”（先确认）。 */
    onClose: (() -> Unit)? = null,
) {
    val c = LocalGroupedColors.current
    val start = (entry.from ?: 1).coerceAtLeast(1)
    val end = maxOf(start + 2, chapters.maxOfOrNull { it.no } ?: start + 2, (entry.payoff ?: start) + 2)
    // 拖动途中的值；没拖过是空的，显示账本里的计划。保存失败时复位，卡片不留一个没存下来的计划。
    var dragging by remember(entry.id, entry.payoff) { mutableStateOf<Float?>(null) }
    // 没定计划收章的伏笔：点“定计划收章”才出滑块，拖好再点保存；轻点、滚动时碰到都不会写。
    var choosing by remember(entry.id, entry.payoff) { mutableStateOf(false) }
    val selected = (dragging?.roundToInt() ?: entry.payoff ?: (start + 1)).coerceIn(start, end)
    LedgerGraphCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.text, modifier = Modifier.weight(1f), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Tag(if (entry.done) "已收" else if (due) "该回收" else "未收", if (entry.done) c.success else c.warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.tint))
            Box(Modifier.weight(1f).height(2.dp).background(c.separator))
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.tint))
        }
        Text(
            "第 $start 章埋下  →  " + when {
                entry.done -> entry.closed?.let { "第 $it 章已收" } ?: "已收"
                entry.payoff == null && !choosing -> "计划收章未定"
                else -> "计划第 $selected 章收"
            },
            color = c.secondary, fontSize = 12.sp,
        )
        if (!entry.done && entry.payoff == null && !choosing) {
            PillButton("定计划收章", onClick = { choosing = true })
        } else if (!entry.done) {
            LingYueSlider(
                value = selected.toFloat(),
                onValueChange = { dragging = it },
                valueRange = start.toFloat()..end.toFloat(),
                label = "调整“" + entry.text + "”计划收章",
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                valueText = "第 $selected 章",
                // 松手（或读屏调节）时现读 dragging：读屏先改值再调这里，组合时算好的 selected 还是旧值。
                onValueChangeFinished = {
                    val payoff = ledgerPayoffToSave(entry, dragging, start, end)
                    if (payoff != null) onPayoff(entry, payoff) { dragging = null }
                    else if (entry.payoff != null) dragging = null
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 $start 章", color = c.tertiary, fontSize = 11.sp)
                Text("第 $end 章", color = c.tertiary, fontSize = 11.sp)
            }
            if (entry.payoff == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("保存：计划第 $selected 章收", onClick = {
                        onPayoff(entry, selected) {
                            dragging = null
                            choosing = false
                        }
                    })
                    PillButton("取消", onClick = {
                        dragging = null
                        choosing = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!entry.done && onClose != null) PillButton("标为已回收", onClick = onClose, style = PillStyle.Tinted)
            PillButton("查看来源与精确编辑", onClick = { onOpen(entry) })
        }
    }
}

internal fun LazyListScope.ledgerKnowledgeGraph(
    entries: List<LedgerEntry>,
    chapter: LedgerChapter?,
    personFilter: String?,
    onKnown: (LedgerEntry) -> Unit,
    onUnknown: (LedgerQuickKnowledge) -> Unit,
) {
    val facts = ledgerGraphFacts(entries)
    val people = entries.map { it.subject.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        .filter { personFilter == null || it == personFilter }
    if (facts.isEmpty() || people.isEmpty()) {
        item(key = "graph:knowledge:empty") { GroupedSection { row { EmptyRow("还没有知情记录。新增时要写清获知章与消息来源。") } } }
        return
    }
    item(key = "graph:knowledge:intro") { GroupedListHeader("谁知道什么 · 点格子核对") }
    facts.forEach { f ->
        val fact = f.text
        item(key = "graph:knowledge:$fact") {
            val c = LocalGroupedColors.current
            LedgerGraphCard {
                Text(fact, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                // 只有作者知道的真相：单独标出来，不当成谁已经知道。
                f.truth.firstOrNull()?.let { t ->
                    Text(
                        "真相（作者）" + if (t.subject.isNotBlank()) "：${t.subject}还不知道" else "：只有作者知道",
                        color = c.secondary, fontSize = 12.sp,
                    )
                }
                // 一人一格、对齐成一行；人多放不下时左右滑，还有没露出来的那一边渐隐。
                FadingScrollRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { who ->
                        val (known, existing) = ledgerGraphCell(f, who, chapter)
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
                                        else -> onUnknown(LedgerQuickKnowledge(who, f.entries.first()))
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

/**
 * 对话框里填章号的一行：左边名字，右边步进器（[NumberStepper]）。[chapters] 不空时上限是作品现有的最后一章；
 * [optional] 时可以“不填”，按 ＋ 从 [start] 开始。
 */
@Composable
internal fun ChapterStepperRow(
    title: String,
    value: Int?,
    chapters: List<LedgerChapter>,
    optional: Boolean = true,
    start: Int? = null,
    max: Int? = null,
    onChange: (Int?) -> Unit,
) {
    val last = max ?: chapters.maxOfOrNull { it.no }?.coerceAtLeast(1) ?: STEPPER_MAX
    val first = chapters.minOfOrNull { it.no }?.coerceIn(1, last) ?: 1
    LabeledRow(title) {
        NumberStepper(
            value,
            onChange,
            title,
            range = 1..last,
            optional = optional,
            start = (start ?: first).coerceIn(1, last),
            emptyText = if (optional) "不填" else "未填",
        )
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
    var number by remember(target) { mutableStateOf(chapter?.no ?: current?.from) }
    var quote by remember(target) { mutableStateOf(if (current?.from == chapter?.no) current?.quote.orEmpty() else "") }
    val validChapter = number != null && number!! > 0 && chapters.any { it.no == number }
    GlassDialog(show = true, title = target.subject + " · " + target.aspect, summary = "按章登记新状态；之前章节保留原来的状态。", onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = value, onValueChange = { value = it }, label = "本章状态", modifier = Modifier.fillMaxWidth())
            ChapterStepperRow("从第几章生效", number, chapters, optional = false) { number = it }
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
    var number by remember(target) { mutableStateOf(chapter?.no) }
    var via by remember(target) { mutableStateOf("") }
    var quote by remember(target) { mutableStateOf("") }
    val validChapter = number != null && number!! > 0 && chapters.any { it.no == number }
    GlassDialog(show = true, title = target.subject + "得知了什么", summary = target.fact.text, onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChapterStepperRow("从第几章得知", number, chapters, optional = false) { number = it }
            TextField(value = via, onValueChange = { via = it }, label = "消息来源（谁告诉、偷听等）", modifier = Modifier.fillMaxWidth())
            TextField(value = quote, onValueChange = { quote = it }, label = "原文依据（可选）", modifier = Modifier.fillMaxWidth())
            if (!validChapter) Text("请填作品中已有的章号。", color = c.warning, fontSize = 12.sp)
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = { onSave(ledgerQuickKnowledgeEntry(target, number, via, quote)) },
            confirmText = "登记知情",
            confirmEnabled = validChapter && via.isNotBlank(),
        )
    }
}
