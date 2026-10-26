package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
)

/**
 * Compact entry group for the work page. Secondary destinations stay one expansion away; none
 * of the former work-page actions disappear when the chapter list becomes a planning tree.
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (work.words >= 10_000) "%.1f 万字".format(work.words / 10_000f) else "${work.words} 字",
                        color = c.label,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        " · ${chapters.size} 章" +
                            (if (work.volumes.size > 1) " · ${work.volumes.size} 卷" else "") +
                            (if (work.author.isNotBlank()) " · ${work.author}" else ""),
                        color = c.secondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (statusSummary.isNotEmpty()) {
                    Text(statusSummary, color = c.secondary, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.fill),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(c.tint))
                }
                if (unsyncedChapters > 0 || pendingChanges > 0) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (unsyncedChapters > 0) Tag("$unsyncedChapters 章待整理", c.warning)
                        if (pendingChanges > 0) Tag("$pendingChanges 条待同步设定", c.warning)
                    }
                }
            }
        }
        row {
            NavRow(
                if (lastChapter == null) "新的一章" else "继续写",
                value = lastChapter?.title,
                icon = LingYueIcons.Write,
                iconBackground = c.tint,
            ) {
                if (lastChapter == null) actions.newChapter() else actions.openChapter(lastChapter)
            }
        }
        row {
            NavRow("设定包", value = packCount?.let { if (it == 0) "空" else "$it 份" }, icon = LingYueIcons.Notes, iconBackground = c.tint) {
                actions.openPack()
            }
        }
        row {
            NavRow("故事账本", value = ledgerCount?.let { if (it == 0) "空" else "$it 条" }, icon = LingYueIcons.ListView, iconBackground = c.tint) {
                actions.openLedger()
            }
        }
        row {
            NavRow("写作 AI", value = if (aiAdviceEnabled) "写作建议已开" else null, icon = LingYueIcons.Sparkle, iconBackground = c.tint) {
                actions.openAi()
            }
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
