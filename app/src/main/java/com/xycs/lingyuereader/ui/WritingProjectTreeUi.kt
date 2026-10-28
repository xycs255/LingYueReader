package com.xycs.lingyuereader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassMenu
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Navigation and edits still go through the existing WorkStore/Screen flows. */
internal data class WritingTreeActions(
    val onOpenOutline: () -> Unit,
    val onToggleVolume: (String) -> Unit,
    val onOpenVolumeMenu: (Volume) -> Unit,
    val onDismissVolumeMenu: () -> Unit,
    val onRenameVolume: (Volume) -> Unit,
    val onDeleteVolume: (Volume) -> Unit,
    val onOpenChapter: (WorkChapter) -> Unit,
    val onOpenCard: (WorkChapter) -> Unit,
    val onOpenChapterMenu: (WorkChapter) -> Unit,
    val onDismissChapterMenu: () -> Unit,
    val onRenameChapter: (WorkChapter) -> Unit,
    val onStatusChapter: (WorkChapter) -> Unit,
    val onMoveChapter: (WorkChapter, Int) -> Unit,
    val onDeleteChapter: (WorkChapter) -> Unit,
    val onAddChapter: (Volume) -> Unit,
    val onToggleReorder: () -> Unit,
)

/** Save only stable IDs, using a Bundle-compatible list so folds survive configuration changes. */
private val treeIdsSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)

@Composable
internal fun rememberWritingTreeIds(workId: String, kind: String): MutableState<Set<String>> =
    rememberSaveable(workId, kind, saver = treeIdsSaver) { mutableStateOf(emptySet()) }

/**
 * A single lazy tree replaces the separate chapter directory. Chapter rows remain individual
 * LazyColumn items, so hundreds of chapters do not compose all at once. A chapter tap still
 * opens its editor; the adjacent 48 dp task-card control opens that chapter's card directly.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.writingPlanningTreeItems(
    work: Work,
    unsyncedIds: Set<String>,
    showUnsynced: Boolean,
    collapsedVolumeIds: Set<String>,
    reorderMode: Boolean,
    volumeMenuId: String?,
    chapterMenuId: String?,
    actions: WritingTreeActions,
) {
    val chapters = work.chapters
    val positions = chapters.withIndex().associate { it.value.id to it.index }
    item(key = "tree:heading", contentType = "tree-heading") {
        GroupedListHeader("规划树")
    }
    item(key = "tree:outline", contentType = "tree-outline") {
        GroupedListCell(first = true, last = true, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
            TreeOutlineRow(work, actions.onOpenOutline)
            TreeReorderRow(reorderMode, actions.onToggleReorder)
        }
    }
    work.volumes.forEach { volume ->
        val expanded = volume.id !in collapsedVolumeIds
        item(key = "volume:${volume.id}", contentType = "volume") {
            GroupedListCell(first = true, last = !expanded, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                TreeVolumeRow(
                    volume = volume,
                    expanded = expanded,
                    menuOpen = volumeMenuId == volume.id,
                    canDelete = work.volumes.size > 1,
                    actions = actions,
                )
            }
        }
        if (expanded) {
            items(volume.chapters, key = { it.id }, contentType = { "chapter" }) { chapter ->
                GroupedListCell(first = false, last = false, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                    TreeChapterRow(
                        chapter = chapter,
                        unsynced = showUnsynced && chapter.id in unsyncedIds,
                        reorderMode = reorderMode,
                        canMoveUp = (positions[chapter.id] ?: 0) > 0,
                        canMoveDown = (positions[chapter.id] ?: chapters.lastIndex) < chapters.lastIndex,
                        menuOpen = chapterMenuId == chapter.id,
                        actions = actions,
                    )
                }
            }
            item(key = "add:${volume.id}", contentType = "add") {
                GroupedListCell(first = false, last = true, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                    ActionRow("新的一章") { actions.onAddChapter(volume) }
                }
            }
        }
    }
    item(key = "tree:hint", contentType = "tree-hint") {
        Text(
            "点章写正文，点右侧卡片看细纲；长按章节可改名、改状态或删除。",
            color = LocalGroupedColors.current.secondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

@Composable
private fun TreeOutlineRow(work: Work, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        RowIcon(LingYueIcons.Book, c.tint)
        Column(Modifier.weight(1f)) {
            Text("总纲 · ${work.title}", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${work.volumes.size} 卷 · ${work.chapters.size} 章 · 设定包", color = c.secondary, fontSize = 12.sp)
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * 进出调整模式的一行：和上面的总纲一样带行首图标、名字下面一行说明，看得出能点；
 * 调整中换成强调色的“完成调整”，右边挂“调整中”标签，再点一下收起。
 */
@Composable
private fun TreeReorderRow(active: Boolean, onToggle: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onToggle) {
        RowIcon(if (active) LingYueIcons.Check else LingYueIcons.Sort, c.tint)
        Column(Modifier.weight(1f)) {
            Text(
                if (active) "完成调整" else "调整章节顺序",
                color = if (active) c.tint else c.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(if (active) "用每章右侧的箭头上移、下移" else "进入调整模式，逐章上移、下移", color = c.secondary, fontSize = 12.sp)
        }
        if (active) Tag("调整中", c.tint)
        else Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TreeVolumeRow(
    volume: Volume,
    expanded: Boolean,
    menuOpen: Boolean,
    canDelete: Boolean,
    actions: WritingTreeActions,
) {
    val c = LocalGroupedColors.current
    Box {
        GroupedRow(onClick = { actions.onToggleVolume(volume.id) }) {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.fill), contentAlignment = Alignment.Center) {
                Icon(LingYueIcons.Book, contentDescription = null, tint = c.tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(volume.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${volume.chapters.size} 章", color = c.secondary, fontSize = 12.sp)
            }
            Icon(if (expanded) LingYueIcons.ChevronDown else LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
            IconButton(onClick = { actions.onOpenVolumeMenu(volume) }, modifier = Modifier.size(48.dp)) {
                Icon(LingYueIcons.More, contentDescription = "${volume.title}卷操作", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        }
        OverlayListPopup(show = menuOpen, alignment = PopupPositionProvider.Align.End, onDismissRequest = actions.onDismissVolumeMenu) {
            GlassMenu {
                ListPopupColumn {
                    TreeMenuAction("改名", c.label) { actions.onDismissVolumeMenu(); actions.onRenameVolume(volume) }
                    if (canDelete) TreeMenuAction("删除本卷", c.destructive) { actions.onDismissVolumeMenu(); actions.onDeleteVolume(volume) }
                }
            }
        }
    }
}

@Composable
private fun TreeChapterRow(
    chapter: WorkChapter,
    unsynced: Boolean,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    menuOpen: Boolean,
    actions: WritingTreeActions,
) {
    val c = LocalGroupedColors.current
    Box {
        Column {
            GroupedRow(onClick = { actions.onOpenChapter(chapter) }, onLongClick = { actions.onOpenChapterMenu(chapter) }) {
                Spacer(Modifier.width(26.dp))
                Box(Modifier.width(2.dp).height(29.dp).background(if (unsynced) c.warning else c.separator))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(chapter.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(treeWords(chapter.words), color = c.secondary, fontSize = 12.sp)
                        if (unsynced) Text("· 未整理", color = c.warning, fontSize = 12.sp)
                    }
                }
                StatusLabel(chapter.status)
                if (reorderMode) {
                    // 调整模式：每行右侧一对紧凑的上下箭头，看着小、能点 48dp；读屏念“上移第一章 雪夜”。
                    MoveArrow(up = true, enabled = canMoveUp, label = "上移${chapter.title}") { actions.onMoveChapter(chapter, -1) }
                    MoveArrow(up = false, enabled = canMoveDown, label = "下移${chapter.title}") { actions.onMoveChapter(chapter, 1) }
                } else {
                    // 点一下直接进细纲卡页。原来先展开一行，那一行只有固定的说明字，没有别的信息，去掉了少点一下。
                    IconButton(onClick = { actions.onOpenCard(chapter) }, modifier = Modifier.size(48.dp)) {
                        Icon(LingYueIcons.Notes, contentDescription = "打开${chapter.title}细纲卡", tint = c.tint, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        OverlayListPopup(show = menuOpen, alignment = PopupPositionProvider.Align.End, onDismissRequest = actions.onDismissChapterMenu) {
            GlassMenu {
                ListPopupColumn {
                    TreeMenuAction("改名", c.label) { actions.onDismissChapterMenu(); actions.onRenameChapter(chapter) }
                    TreeMenuAction("状态：${chapter.status.label}", c.label) { actions.onDismissChapterMenu(); actions.onStatusChapter(chapter) }
                    TreeMenuAction("细纲卡", c.label) { actions.onDismissChapterMenu(); actions.onOpenCard(chapter) }
                    TreeMenuAction("上移", c.label) { actions.onDismissChapterMenu(); actions.onMoveChapter(chapter, -1) }
                    TreeMenuAction("下移", c.label) { actions.onDismissChapterMenu(); actions.onMoveChapter(chapter, 1) }
                    TreeMenuAction("删除", c.destructive) { actions.onDismissChapterMenu(); actions.onDeleteChapter(chapter) }
                }
            }
        }
    }
}

/** 调整模式里的上移 / 下移：28dp 的圆底小箭头，能点的范围 48dp；到头了变灰、不能点。 */
@Composable
private fun MoveArrow(up: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            Icon(
                LingYueIcons.ChevronDown,
                contentDescription = null,
                tint = if (enabled) c.tint else c.tertiary,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (up) 180f else 0f },
            )
        }
    }
}

@Composable
private fun TreeMenuAction(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(min = 200.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
    }
}

private fun treeWords(n: Int): String = if (n == 0) "空" else if (n >= 10_000) "%.1f 万字".format(n / 10_000f) else "$n 字"
