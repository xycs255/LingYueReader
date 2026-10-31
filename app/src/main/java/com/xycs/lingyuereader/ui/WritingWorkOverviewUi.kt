package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.StoryDigest
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** The work page keeps one obvious route for each daily writing task. */
internal data class WritingWorkOverviewActions(
    val openChapter: (WorkChapter) -> Unit,
    val newChapter: () -> Unit,
    val openPack: () -> Unit,
    val openLedger: () -> Unit,
    val openAi: () -> Unit,
    val preview: () -> Unit,
    val openChanges: () -> Unit,
    val openRules: () -> Unit,
    val organizeUnsynced: () -> Unit,
    /** 长按色块：弹出和规划树长按一样的章节菜单。 */
    val openChapterMenu: (WorkChapter) -> Unit = {},
    /** 点进度卡的标题区：改作品信息（书名、笔名、简介）。 */
    val editInfo: () -> Unit = {},
    /** “更多功能”里的故事至今、线索与节奏体检；体检有冷了的线时进度卡上也有一枚胶囊打开它。 */
    val openStory: () -> Unit = {},
    val openPulse: () -> Unit = {},
)

/** 少于这么多章时作品页不画章节色块和图例：几章的时候环和数字已经够了，几个小方块加一整行图例反而显得空。 */
internal const val CHAPTER_MAP_MIN = 10

/**
 * 作品页顶部：进度环（中间总字数，环是定稿章数占总章数）、每章一格的色块带（[CHAPTER_MAP_MIN] 章以上才有）、四张大入口卡片；
 * 不常用的收在“更多功能”里（在本页展开）：故事至今、线索与节奏体检、当书预览、变更登记、检查规则。
 * 进度卡上的“N 章待整理”“N 条待同步设定”“体检：N 条冷了”能点；点标题区改作品信息。[chapterMenu] 是色块长按弹出的菜单（和规划树同一份，见 [ChapterMenuPopup]）。
 * [story] 是故事至今（还没有为空），[pulse] 是体检的一句结果（还没算完为空），[pulseWarn] 为真时有冷了、原地踏步的（[pulseAlerts] 条）。
 * [promises] 是账本和伏笔表合起来的伏笔数（还没算完为空），故事账本卡片上优先写“N 条该回收”。
 */
@Composable
internal fun WritingWorkOverview(
    work: Work,
    packCount: Int?,
    ledgerCount: Int?,
    aiAdviceEnabled: Boolean,
    pendingChanges: Int,
    unsyncedChapters: Int,
    ruleCount: Int?,
    busy: Boolean,
    actions: WritingWorkOverviewActions,
    unsyncedIds: Set<String> = emptySet(),
    promises: PromiseCounts? = null,
    aiProvider: String? = null,
    todayWords: Int = 0,
    dailyGoal: Int = 0,
    mapMenuId: String? = null,
    chapterMenu: @Composable (WorkChapter) -> Unit = {},
    story: StoryDigest? = null,
    pulse: String? = null,
    pulseWarn: Boolean = false,
    /** 冷了、原地踏步的有几条：大于 0 时进度卡上一枚“N 条线索冷了 ›”。 */
    pulseAlerts: Int = 0,
) {
    val c = LocalGroupedColors.current
    val chapters = work.chapters
    val lastChapter = chapters.maxByOrNull { it.updatedAt }
    val finalized = chapters.count { it.status == ChapterStatus.FINAL }
    val progress = if (chapters.isEmpty()) 0f else finalized.toFloat() / chapters.size
    val statusSummary = ChapterStatus.entries.reversed().mapNotNull { status ->
        chapters.count { it.status == status }.takeIf { it > 0 }?.let { "${status.label} $it" }
    }.joinToString(" · ")
    var moreExpanded by rememberSaveable(work.id) { mutableStateOf(false) }

    GroupedSection(header = "作品进度", footer = work.synopsis.ifBlank { null }) {
        row {
            ProgressHead(
                work, finalized, progress, statusSummary, todayWords, dailyGoal, unsyncedChapters, pendingChanges,
                onUnsynced = actions.organizeUnsynced, onChanges = actions.openChanges, onInfo = actions.editInfo,
                pulseAlert = pulseAlerts, onPulse = actions.openPulse,
            )
        }
        if (chapters.size >= CHAPTER_MAP_MIN) {
            row {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    ChapterMap(
                        work = work,
                        unsyncedIds = unsyncedIds,
                        menuChapterId = mapMenuId,
                        onOpen = actions.openChapter,
                        onLongPress = actions.openChapterMenu,
                        menu = chapterMenu,
                    )
                    Spacer(Modifier.height(10.dp))
                    ChapterMapLegend()
                }
            }
        }
    }

    // 四个日常入口：2×2 大卡片，卡片上直接写要紧的状态，不用点进去才知道。
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntryCard(
                if (lastChapter == null) "新的一章" else "继续写",
                value = lastChapter?.title ?: "还没有章",
                icon = LingYueIcons.Write,
                modifier = Modifier.weight(1f),
            ) {
                if (lastChapter == null) actions.newChapter() else actions.openChapter(lastChapter)
            }
            EntryCard(
                "设定包",
                value = packCount?.let { if (it == 0) "还没有文档" else "$it 份文档" },
                icon = LingYueIcons.Notes,
                modifier = Modifier.weight(1f),
            ) { actions.openPack() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntryCard(
                "故事账本",
                value = ledgerValue(ledgerCount, promises),
                icon = LingYueIcons.ListView,
                modifier = Modifier.weight(1f),
            ) { actions.openLedger() }
            EntryCard(
                "写作 AI",
                value = (aiProvider ?: "未设置") + if (aiAdviceEnabled) " · 写作建议已开" else "",
                icon = LingYueIcons.Sparkle,
                modifier = Modifier.weight(1f),
            ) { actions.openAi() }
        }
    }

    // 不常用的在本页展开（不跳页，用 ExpandRow）。伏笔在故事账本的“伏笔”里；整理没整理的章节点进度卡上的“N 章待整理”。
    // 故事至今、线索体检是回头看全书用的，也收在这里（以前接在规划树下面，长篇要翻过几百章才找得到）；体检有冷了的线时进度卡上另有胶囊。
    GroupedSection {
        row { ExpandRow("更多功能", moreExpanded, value = if (moreExpanded) null else "故事至今、体检等 5 项") { moreExpanded = !moreExpanded } }
        if (moreExpanded) {
            row { TreeStoryRow(story, actions.openStory) }
            row { TreePulseRow(pulse, pulseWarn, actions.openPulse) }
            row { NavRow("当书预览", value = "排版、听稿", icon = LingYueIcons.Book, iconBackground = c.tint, enabled = !busy) { actions.preview() } }
            row {
                NavRow(
                    "变更登记",
                    value = pendingChanges.takeIf { it > 0 }?.let { "$it 条待同步设定" },
                    icon = LingYueIcons.History,
                    iconBackground = c.tint,
                ) { actions.openChanges() }
            }
            row {
                NavRow("检查规则", value = ruleCount?.let { if (it == 0) "未设" else "$it 条" }, icon = LingYueIcons.Check, iconBackground = c.tint) {
                    actions.openRules()
                }
            }
        }
    }
}

/** 伏笔有几条（[total]）、没回收的几条（[open]）、其中计划回收的章已经写到了的几条（[due]）。 */
internal data class PromiseCounts(val total: Int, val open: Int, val due: Int)

/**
 * 伏笔数：账本和设定包伏笔表合起来算（[mergeWritingForeshadows]，伏笔表里没导入账本的也算），口径同原来的伏笔页：
 * 没标已回收的算没收，其中计划回收的章不晚于写到的章（[latest]，有字的章里最大的章号）算该回收。
 */
internal fun promiseCounts(merged: List<WritingForeshadow>, latest: Int): PromiseCounts {
    val open = merged.map { it.display }.filter { !it.done }
    return PromiseCounts(merged.size, open.size, open.count { it.payoff != null && it.payoff <= latest })
}

/**
 * 故事账本卡片上的一句：有该回收的伏笔先说（以前单独的“伏笔”入口上的提醒），再说没收的有几条；
 * 账本空、伏笔表也没有伏笔时才说“还没有记录”；伏笔数还没算出来时退回总条数。
 */
internal fun ledgerValue(ledgerCount: Int?, promises: PromiseCounts?): String? = when {
    promises != null && promises.due > 0 -> "${promises.due} 条该回收"
    promises != null && promises.open > 0 -> "${promises.open} 条伏笔没收"
    promises != null && promises.total > 0 -> "伏笔都收了"
    ledgerCount == 0 -> "还没有记录"
    ledgerCount != null -> "$ledgerCount 条"
    else -> null
}

/** 总字数的大字和单位：“12.3”“万字”，不到一万是“8650”“字”。 */
internal fun wordsParts(words: Int): Pair<String, String> =
    if (words >= 10_000) "%.1f".format(words / 10_000f) to "万字" else "$words" to "字"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressHead(
    work: Work,
    finalized: Int,
    progress: Float,
    statusSummary: String,
    todayWords: Int,
    dailyGoal: Int,
    unsyncedChapters: Int,
    pendingChanges: Int,
    onUnsynced: () -> Unit,
    onChanges: () -> Unit,
    onInfo: () -> Unit,
    pulseAlert: Int,
    onPulse: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val total = work.chapters.size
    val (number, unit) = wordsParts(work.words)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(progress, description = "共 ${number}${unit}，定稿 $finalized / 共 $total 章", size = 88.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number, color = c.label, fontSize = TypeScale.Headline, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(unit, color = c.secondary, fontSize = TypeScale.Micro, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // 标题区（定稿几章、笔名）能点：改作品信息。以前只在“⋯”里，作者常找不到；笔名后面一支小笔看得出能改。
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(pressHighlight(source.collectIsPressedAsState().value))
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "改作品信息", onClick = onInfo),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("定稿 $finalized / 共 $total 章", color = c.label, fontSize = TypeScale.Headline, fontWeight = FontWeight.SemiBold)
                val meta = listOfNotNull(
                    work.volumes.size.takeIf { it > 1 }?.let { "$it 卷" },
                    work.author.ifBlank { "作品信息" },
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(LingYueIcons.Write, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
            if (statusSummary.isNotEmpty()) Text(statusSummary, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 2)
            if (dailyGoal > 0) {
                Text(
                    "今天写了 $todayWords 字 · 目标 $dailyGoal",
                    color = if (todayWords >= dailyGoal) c.success else c.secondary,
                    fontSize = TypeScale.Footnote,
                    maxLines = 2,
                )
            }
            if (unsyncedChapters > 0 || pendingChanges > 0 || pulseAlert > 0) {
                FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 看着像能点的就真能点：待整理直接打开整理那一步，待同步设定打开变更登记，体检打开体检表。
                    if (unsyncedChapters > 0) PillButton("$unsyncedChapters 章待整理 ›", onUnsynced, style = PillStyle.Tinted)
                    if (pendingChanges > 0) PillButton("$pendingChanges 条待同步设定 ›", onChanges, style = PillStyle.Tinted)
                    if (pulseAlert > 0) PillButton("$pulseAlert 条线索冷了 ›", onPulse, style = PillStyle.Tinted)
                }
            }
        }
    }
}
