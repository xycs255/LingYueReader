package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.BudgetMiss
import com.xycs.lingyuereader.data.PackBudget
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.PackKindStat
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 每类设定文档的图标：十三类各不相同（图标见 docs/icons.md），墨水屏、色弱下靠图标和名字分，不只靠颜色。
 */
internal fun packKindIcon(kind: PackKind): ImageVector = when (kind) {
    PackKind.INSTRUCTION -> LingYueIcons.Info
    PackKind.OUTLINE -> LingYueIcons.Book
    PackKind.INVARIANT -> LingYueIcons.Lock
    PackKind.VOLUME -> LingYueIcons.ListView
    PackKind.CANON -> LingYueIcons.MapFold
    PackKind.CHARACTER -> LingYueIcons.ContactsCircle
    PackKind.AWARENESS -> LingYueIcons.Preview
    PackKind.FORESHADOW -> LingYueIcons.Flag
    PackKind.STYLE -> LingYueIcons.Write
    PackKind.VOICE -> LingYueIcons.Chat
    PackKind.RULES -> LingYueIcons.Check
    PackKind.TECHNIQUE -> LingYueIcons.Bulb
    PackKind.REFERENCE -> LingYueIcons.Folder
}

/**
 * 每类的分类色（[CategoryTone]）：意思相近的几类同一个色系——说明用强调色，纲用蓝，硬事实用提醒色，
 * 人物与语气用紫，知情与伏笔这类表格用青，写法用成功色，杂项资料用次要色。
 */
internal fun packKindTone(kind: PackKind): CategoryTone = when (kind) {
    PackKind.INSTRUCTION -> CategoryTone.TINT
    PackKind.OUTLINE, PackKind.VOLUME -> CategoryTone.BLUE
    PackKind.INVARIANT, PackKind.CANON -> CategoryTone.WARNING
    PackKind.CHARACTER, PackKind.VOICE -> CategoryTone.PURPLE
    PackKind.AWARENESS, PackKind.FORESHADOW -> CategoryTone.CYAN
    PackKind.STYLE, PackKind.RULES, PackKind.TECHNIQUE -> CategoryTone.SUCCESS
    PackKind.REFERENCE -> CategoryTone.NEUTRAL
}

internal fun packKindColor(kind: PackKind?, c: GroupedColors): Color =
    kind?.let { categoryColor(packKindTone(it), c) } ?: c.tertiary

/** 字数给人看：空、几百字、几千字、几万字。 */
internal fun packWords(words: Int): String = when {
    words == 0 -> "空"
    words >= 10_000 -> "%.1f 万字".format(words / 10_000f)
    else -> "$words 字"
}

/**
 * 设定包的分类格：十三类一类一张彩色小卡片（[StatTile]），写着几份、多少字，右上“常用 / 按需”。
 * 点有文档的一类只看这一类（再点一次看全部），点空的一类新建这一类的文档。
 */
@Composable
internal fun PackKindGrid(stats: List<PackKindStat>, selected: PackKind?, onPick: (PackKindStat) -> Unit) {
    val c = LocalGroupedColors.current
    AdaptiveTileGrid(stats.size, Modifier.padding(horizontal = 12.dp)) { i, modifier ->
        val s = stats[i]
        StatTile(
            title = s.kind.label,
            icon = packKindIcon(s.kind),
            color = packKindColor(s.kind, c),
            lines = if (s.empty) listOf("空 · 点了新建") else listOf("${s.docs} 份 · ${packWords(s.words)}"),
            badge = s.mode,
            selected = s.kind == selected,
            dimmed = s.empty,
            description = packKindDescription(s),
            modifier = modifier,
        ) { onPick(s) }
    }
}

/**
 * 顶上“AI 每次带多少”：写正文时随请求带上的资料一共多少字，按类分段的一根条（[SegmentBar]）和图例；
 * 本章资料、知识库各自用了多少、上限多少；放不下、被截掉的用删除色写一行。整张能点，点开看明细（[PackBudgetDialog]）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackBudgetRow(budget: PackBudget?, error: String?, onOpen: () -> Unit) {
    val c = LocalGroupedColors.current
    val source = androidx.compose.runtime.remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    if (budget == null) {
        if (error != null) ErrorText("算不出 AI 每次带多少：$error", Modifier.padding(16.dp)) else LoadingRow("正在算 AI 每次带多少……")
        return
    }
    val slices = budget.slices
    val legend = slices.take(LEGEND)
    val rest = slices.drop(LEGEND).sumOf { it.chars }
    val describe = buildString {
        append("写正文每次带 ${packWords(budget.proseChars)}")
        budget.chapterTitle?.let { append("，按「$it」算") }
        append("：").append(slices.joinToString("，") { "${it.label} ${packWords(it.chars)}" })
        append("。本章资料 ${packWords(budget.briefChars)}，上限 ${packWords(budget.briefLimit)}")
        append("；知识库 ${packWords(budget.knowledgeChars)}，上限 ${packWords(budget.knowledgeLimit)}")
        if (budget.misses.isNotEmpty()) append("。${budget.misses.size} 份放不下或被截掉")
        append("。点开看明细")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(pressHighlight(pressed))
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = describe
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("写正文每次带 ${packWords(budget.proseChars)}", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(budget.chapterTitle?.let { "按「$it」算 · 文风范例、正文另算" } ?: "还没有章：只算作者说明", color = c.secondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        SegmentBar(slices.map { it.chars to packKindColor(it.kind, c) }, description = "")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            legend.forEach { LegendDot(it.label, packWords(it.chars), packKindColor(it.kind, c)) }
            if (rest > 0) LegendDot("其余", packWords(rest), c.tertiary, hollow = true)
        }
        LimitLine("本章资料", budget.briefChars, budget.briefLimit)
        LimitLine("知识库（审稿、整理用）", budget.knowledgeChars, budget.knowledgeLimit)
        if (budget.misses.isNotEmpty()) {
            Text("${budget.misses.size} 份放不下或被截掉 · 点开看是哪几份", color = c.destructive, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** 图例里的一项：分类色小圆点、名字、字数；“其余”（几类并在一起）用空心圈，不和哪一类的颜色混。 */
@Composable
private fun LegendDot(label: String, value: String, color: Color, hollow: Boolean = false) {
    val c = LocalGroupedColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).then(if (hollow) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)))
        Spacer(Modifier.width(4.dp))
        Text("$label $value", color = c.secondary, fontSize = 12.sp, maxLines = 1)
    }
}

/** 一样东西用了多少、上限多少：名字和“用了 / 上限”一行，下面一根细条；到了上限的九成五用提醒色。 */
@Composable
private fun LimitLine(label: String, used: Int, limit: Int) {
    val c = LocalGroupedColors.current
    val fraction = if (limit <= 0) 0f else used.toFloat() / limit
    val full = fraction >= 0.95f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, color = c.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${packWords(used)} / 上限 ${packWords(limit)}", color = if (full) c.warning else c.secondary, fontSize = 12.sp, maxLines = 1)
        }
        FillBar(fraction, Modifier.fillMaxWidth(), color = if (full) c.warning else c.tint, animate = false)
    }
}

/**
 * “AI 每次带多少”的明细：放不下、被截掉的一份份列在最前面（删除色）并说清为什么，然后是各类各带了多少字、两个上限分别是什么。
 * 左键“去调上限”进高级设置，右键“好”。
 */
@Composable
internal fun PackBudgetDialog(budget: PackBudget, onDismiss: () -> Unit, onSettings: () -> Unit) {
    GlassDialog(
        show = true,
        title = "AI 每次带多少",
        summary = budget.chapterTitle?.let { "按「$it」算：写正文时随请求带上的设定资料" } ?: "作品还没有章，只算作者写给 AI 的说明",
        onDismissRequest = onDismiss,
    ) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 放不下的最要紧，放在最前面。
            if (budget.misses.isNotEmpty()) {
                Text("放不下、被截掉的 ${budget.misses.size} 份", color = c.destructive, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                budget.misses.forEach { m -> MissLine(m) }
            }
            DialogSectionLabel("写正文带上的 ${packWords(budget.proseChars)}", if (budget.misses.isEmpty()) Modifier else Modifier.padding(top = 8.dp))
            budget.slices.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 28.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(packKindColor(s.kind, c)))
                    Text(s.label, color = c.label, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Text(packWords(s.chars), color = c.secondary, fontSize = 14.sp)
                }
            }
            if (budget.slices.isEmpty()) Text("还没有带上任何资料。", color = c.secondary, fontSize = 14.sp)
            DialogSectionLabel("上限", Modifier.padding(top = 8.dp))
            Text(
                "本章资料：这一章挑了 ${packWords(budget.briefChars)}，写正文最多带 ${packWords(budget.briefLimit)}" +
                    "（作品设的本章资料上限 ${packWords(budget.briefSetting)} 的一半，其余让 AI 需要时自己查）。",
                color = c.label, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Text(
                "知识库：审稿、整理时把标了“常用”的文档整份放进去，现在 ${packWords(budget.knowledgeChars)}，最多 ${packWords(budget.knowledgeLimit)}。",
                color = c.label, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        DialogButtons(onDismiss = onSettings, dismissText = "去调上限", onConfirm = onDismiss, confirmText = "好")
    }
}

@Composable
private fun MissLine(m: BudgetMiss) {
    val c = LocalGroupedColors.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            (if (m.whole) "整份没带 · " else "截掉后面 · ") + m.title,
            color = c.destructive, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(m.reason, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** 放不下的文档在列表里挂的标签：整份没带的“放不下”，截短的“截短”（都用删除色）。按文档标题对：截短的那项标题是“文档 › 小节”。 */
internal fun missTags(budget: PackBudget?): Map<String, String> = budget?.misses.orEmpty()
    .groupBy { it.title.substringBefore(" › ") }
    .mapValues { (_, list) -> if (list.any { it.whole }) "放不下" else "截短" }

/** 图例最多列几类，其余并成“其余”。 */
private const val LEGEND = 5

/** 一类卡片的读屏文字：名字、几份、多少字、常用还是按需；空的说点了新建。 */
internal fun packKindDescription(s: PackKindStat): String =
    if (s.empty) "${s.kind.label}，还没有文档，点了新建" else "${s.kind.label}，${s.docs} 份，${packWords(s.words)}，${s.mode}"
