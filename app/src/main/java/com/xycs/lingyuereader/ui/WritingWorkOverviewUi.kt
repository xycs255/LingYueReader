package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
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
    val openForeshadows: () -> Unit,
    val openRules: () -> Unit,
    val organizeUnsynced: () -> Unit,
    /** 长按色块：弹出和规划树长按一样的章节菜单。 */
    val openChapterMenu: (WorkChapter) -> Unit = {},
)

/**
 * 作品页顶部：进度环（中间总字数，环是定稿章数占总章数）、每章一格的色块带、四张大入口卡片；
 * 不常用的收在“更多功能”里，一项不少。[chapterMenu] 是色块长按弹出的菜单（和规划树同一份，见 [ChapterMenuPopup]）。
 */
@Composable
internal fun WritingWorkOverview(
    work: Work,
    packCount: Int?,
    ledgerCount: Int?,
    aiAdviceEnabled: Boolean,
    pendingChanges: Int,
    unsyncedChapters: Int,
    foreshadowNote: String?,
    ruleCount: Int?,
    busy: Boolean,
    actions: WritingWorkOverviewActions,
    unsyncedIds: Set<String> = emptySet(),
    openPromises: Int? = null,
    aiProvider: String? = null,
    todayWords: Int = 0,
    dailyGoal: Int = 0,
    mapMenuId: String? = null,
    chapterMenu: @Composable (WorkChapter) -> Unit = {},
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
            ProgressHead(work, finalized, progress, statusSummary, todayWords, dailyGoal, unsyncedChapters, pendingChanges)
        }
        if (chapters.isNotEmpty()) {
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
                value = ledgerValue(ledgerCount, openPromises),
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

    GroupedSection(header = "其他写作工具") {
        row {
            NavRow("更多功能", value = if (moreExpanded) "收起" else "预览 · 变更 · 伏笔 · 规则", icon = LingYueIcons.More, iconBackground = c.tint) {
                moreExpanded = !moreExpanded
            }
        }
        if (moreExpanded) {
            row { NavRow("当书预览", value = "排版、听稿", icon = LingYueIcons.Book, iconBackground = c.tint, enabled = !busy) { actions.preview() } }
            row {
                NavRow(
                    "变更登记",
                    value = pendingChanges.takeIf { it > 0 }?.let { "$it 条待同步设定" },
                    icon = LingYueIcons.History,
                    iconBackground = c.tint,
                ) { actions.openChanges() }
            }
            row { NavRow("伏笔", value = foreshadowNote, icon = LingYueIcons.Star, iconBackground = c.tint) { actions.openForeshadows() } }
            row {
                NavRow("检查规则", value = ruleCount?.let { if (it == 0) "未设" else "$it 条" }, icon = LingYueIcons.Check, iconBackground = c.tint) {
                    actions.openRules()
                }
            }
            if (unsyncedChapters > 0) {
                row {
                    NavRow("整理没整理的章节", value = "$unsyncedChapters 章", icon = LingYueIcons.Check, iconBackground = c.tint) {
                        actions.organizeUnsynced()
                    }
                }
            }
        }
    }
}

/** 故事账本卡片上的一句：没收的伏笔有几条；还没读出伏笔数时退回总条数。 */
internal fun ledgerValue(ledgerCount: Int?, openPromises: Int?): String? = when {
    ledgerCount == 0 -> "还没有记录"
    openPromises != null && openPromises > 0 -> "$openPromises 条伏笔没收"
    openPromises == 0 -> "伏笔都收了"
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
) {
    val c = LocalGroupedColors.current
    val total = work.chapters.size
    val (number, unit) = wordsParts(work.words)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(progress, description = "共 ${number}${unit}，定稿 $finalized / 共 $total 章", size = 88.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number, color = c.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(unit, color = c.secondary, fontSize = 11.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("定稿 $finalized / 共 $total 章", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val meta = listOfNotNull(
                work.volumes.size.takeIf { it > 1 }?.let { "$it 卷" },
                work.author.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (statusSummary.isNotEmpty()) Text(statusSummary, color = c.secondary, fontSize = 12.sp, maxLines = 2)
            if (dailyGoal > 0) {
                Text(
                    "今天写了 $todayWords 字 · 目标 $dailyGoal",
                    color = if (todayWords >= dailyGoal) c.success else c.secondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
            if (unsyncedChapters > 0 || pendingChanges > 0) {
                FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (unsyncedChapters > 0) Tag("$unsyncedChapters 章待整理", c.warning)
                    if (pendingChanges > 0) Tag("$pendingChanges 条待同步设定", c.warning)
                }
            }
        }
    }
}
