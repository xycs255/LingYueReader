package com.xycs.lingyuereader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.ChapterTreeMarks
import com.xycs.lingyuereader.data.CheckRule
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Digests
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** 点章节点上的伏笔记号：去故事账本，筛到这一章。 */
    val onOpenLedger: (WorkChapter) -> Unit = {},
    /** 拖动松手：把这一章移到某卷的第几个（从 0 数，按去掉这一章后的那一卷算）。 */
    val onDropChapter: (WorkChapter, String, Int) -> Unit = { _, _, _ -> },
    /** 总纲上面的“故事至今”、卷菜单里的“卷摘要”、“线索与节奏体检”：看、改。 */
    val onOpenStory: () -> Unit = {},
    val onVolumeDigest: (Volume) -> Unit = {},
    val onOpenPulse: () -> Unit = {},
)

/** Save only stable IDs, using a Bundle-compatible list so folds survive configuration changes. */
private val treeIdsSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)

@Composable
internal fun rememberWritingTreeIds(workId: String, kind: String): MutableState<Set<String>> =
    rememberSaveable(workId, kind, saver = treeIdsSaver) { mutableStateOf(emptySet()) }

// ---------- 拖动调顺序 ----------

/** 列表里一行的上下边（列表自己的坐标，像素），算落点用。[key] 是懒列表的 key。 */
internal data class TreeSlot(val key: Any, val top: Float, val bottom: Float)

/**
 * 拖到这里松手的结果：移到 [volumeId] 卷的第 [index] 个（从 0 数，按去掉被拖的那章算）。
 * 落点指示线画在 [anchor] 那一行的上边（[before]）或下边；[same] 是拖回了原位，松手什么都不改，也不画线。
 */
internal data class TreeDrop(val volumeId: String, val index: Int, val anchor: Any, val before: Boolean, val same: Boolean = false)

private fun volumeKey(id: String) = "volume:$id"
private fun addKey(id: String) = "add:$id"

/**
 * 手指在 [y] 时的落点。[slots] 是现在排在列表里的行（按顺序）；只看卷、章、“新的一章”三种行，
 * 在最上一行上面算第一行的上半，在最下一行下面算最后一行的下半。
 * - 章：上半落在它前面，下半落在它后面；
 * - “新的一章”：落到这一卷末尾；
 * - 收起的卷：落到这一卷末尾；展开的卷：下半落到卷首，上半落到上一卷末尾（第一卷落到卷首）。
 */
internal fun treeDropAt(work: Work, draggedId: String, collapsed: Set<String>, slots: List<TreeSlot>, y: Float): TreeDrop? {
    val home = work.volumeOf(draggedId) ?: return null
    val homeIndex = home.chapters.indexOfFirst { it.id == draggedId }
    val volumes = work.volumes.associateBy { it.id }
    fun without(v: Volume) = v.chapters.filterNot { it.id == draggedId }
    fun drop(v: Volume, index: Int, anchor: Any, before: Boolean) =
        TreeDrop(v.id, index, anchor, before, same = v.id == home.id && index == homeIndex)

    val rows = slots.filter { s ->
        val k = s.key as? String ?: return@filter false
        k.startsWith("volume:") || k.startsWith("add:") || work.chapter(k) != null
    }
    if (rows.isEmpty()) return null
    val slot = rows.firstOrNull { y < it.bottom } ?: rows.last()
    val upper = y < (slot.top + slot.bottom) / 2
    val key = slot.key as String
    return when {
        key.startsWith("add:") -> volumes[key.removePrefix("add:")]?.let { v -> drop(v, without(v).size, key, before = true) }
        key.startsWith("volume:") -> {
            val v = volumes[key.removePrefix("volume:")] ?: return null
            val i = work.volumes.indexOf(v)
            when {
                v.id in collapsed -> drop(v, without(v).size, key, before = false)
                upper && i > 0 -> work.volumes[i - 1].let { prev -> drop(prev, without(prev).size, key, before = true) }
                else -> drop(v, 0, key, before = false)
            }
        }
        key == draggedId -> drop(home, homeIndex, key, before = true)
        else -> {
            val v = work.volumeOf(key) ?: return null
            val i = without(v).indexOfFirst { it.id == key }
            if (upper) drop(v, i, key, before = true) else drop(v, i + 1, key, before = false)
        }
    }
}

/**
 * 规划树的拖动：长按一章、再挪开一小段（[DRAG_START]）才算拖；拖着时原位那一行变淡，一张半透明、带阴影的“浮起”的卡片跟着手指
 * （[TreeDragGhost]），落点处画一条强调色的线，拖到列表上下边缘自动滚。只长按不挪就松手，还是弹原来的长按菜单。
 * [list] 是作品页的列表；[topInset]、[bottomInset] 是被标题栏、底边盖住的那一截（像素），自动滚动从露出来的边缘算。
 */
@Stable
internal class TreeDragController(val list: LazyListState) {
    var listCoordinates: LayoutCoordinates? = null
    var topInset = 0f
    var bottomInset = 0f
    /** 正在拖的章；只按住、还没挪开时为空。 */
    var dragging by mutableStateOf<WorkChapter?>(null)
    /** 手指在列表里的 y（像素）。 */
    var pointerY by mutableFloatStateOf(0f)
    /** 手指按在那一行里的 y：浮起的卡片照这个偏移跟手。 */
    var grab by mutableFloatStateOf(0f)
    var drop by mutableStateOf<TreeDrop?>(null)
    /** 由作品页设：拿当前的作品和折叠状态算落点。 */
    var resolve: (WorkChapter, Float) -> TreeDrop? = { _, _ -> null }

    fun slots(): List<TreeSlot> {
        val info = list.layoutInfo
        return info.visibleItemsInfo.map { TreeSlot(it.key, (it.offset - info.viewportStartOffset).toFloat(), (it.offset - info.viewportStartOffset + it.size).toFloat()) }
    }

    fun update(y: Float) {
        pointerY = y
        dragging?.let { drop = resolve(it, y) }
    }

    fun end() {
        dragging = null
        drop = null
    }

    /** 手指在边缘时这一帧滚多少（负的往上）；不在边缘是 0。 */
    fun edgeStep(edge: Float, maxStep: Float): Float {
        val height = listCoordinates?.size?.height?.toFloat() ?: return 0f
        val top = topInset + edge
        val bottom = height - bottomInset - edge
        return when {
            pointerY < top -> -maxStep * ((top - pointerY) / edge).coerceIn(0.2f, 1f)
            pointerY > bottom -> maxStep * ((pointerY - bottom) / edge).coerceIn(0.2f, 1f)
            else -> 0f
        }
    }

    companion object {
        /** 长按后至少挪这么远（dp）才算拖动，防误触。 */
        const val DRAG_START = 12
        /** 离列表上下边缘这么近（dp）时自动滚。 */
        const val EDGE = 56
    }
}

/**
 * 拖着时贴着列表边缘自动滚：手指离露出来的上下边缘不到 [TreeDragController.EDGE] 时每帧滚一点，越近越快，滚完重算落点。
 * 不在边缘时不要帧，界面照常空闲。减少动效时照样滚（这是操作本身，不是动画）。
 */
@Composable
internal fun TreeDragAutoScroll(drag: TreeDragController) {
    val density = LocalDensity.current
    LaunchedEffect(drag, density) {
        val edge = with(density) { TreeDragController.EDGE.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        // 手指在边缘时每挪一下重新开始滚；滚到头了（列表滚不动）就停，不空转要帧。
        snapshotFlow { drag.pointerY.takeIf { drag.dragging != null && drag.edgeStep(edge, maxStep) != 0f } }
            .distinctUntilChanged()
            .collectLatest { y ->
                if (y == null) return@collectLatest
                while (drag.dragging != null) {
                    withFrameNanos { }
                    val step = drag.edgeStep(edge, maxStep)
                    if (step == 0f || drag.list.scrollBy(step) == 0f) break
                    drag.update(drag.pointerY)
                }
            }
    }
}

/** 拖着的那一章“浮起来”：半透明、带阴影的一行跟着手指；墨水屏、减少动效时也一样直接跟手，没有放大的动画。 */
@Composable
internal fun TreeDragGhost(drag: TreeDragController, modifier: Modifier = Modifier) {
    val chapter = drag.dragging ?: return
    val c = LocalGroupedColors.current
    Box(
        modifier
            .offset { IntOffset(0, (drag.pointerY - drag.grab).roundToInt()) }
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .alpha(0.88f)
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(c.cell)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(chapter.status)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(chapter.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(drag.drop?.let { if (it.same) "在原位松手不改" else "松手移到这里" } ?: "拖到想放的位置", color = c.secondary, fontSize = 12.sp)
            }
        }
    }
}

// ---------- 树 ----------

/** 连线的位置（离卡片左边）：总纲图标中心、卷图标中心，章状态记号的左边。 */
private val RootX = 30.dp
private val VolumeX = 48.dp
private val ChapterDotX = 58.dp
private val LineWidth = 1.5.dp

/**
 * A single lazy tree replaces the separate chapter directory. Chapter rows remain individual
 * LazyColumn items, so hundreds of chapters do not compose all at once. A chapter tap still
 * opens its editor; the adjacent 48 dp task-card control opens that chapter's card directly.
 *
 * 画成一棵树：总纲、卷、章在同一张卡片里，用竖线和分叉连起来（第三级灰，深色、墨水屏下也看得清）。
 * 卷节点带定稿进度条和字数；章节点带状态记号、字数小条（检查规则里有篇幅时）、细纲卡标签和伏笔记号。
 * [drag] 不为空、又不在调整模式时，长按章再拖动可以直接调顺序（见 [TreeDragController]）。
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
    marks: Map<String, ChapterTreeMarks> = emptyMap(),
    length: CheckRule.Length? = null,
    drag: TreeDragController? = null,
    /** 分层前情（为空时不显示“故事至今”一行，卷菜单里也没有“卷摘要”）。 */
    digests: Digests? = null,
    /** 线索体检的一句结果（为空时不显示体检那一行）；[pulseWarn] 为真时用提醒色。 */
    pulse: String? = null,
    pulseWarn: Boolean = false,
) {
    val chapters = work.chapters
    val positions = chapters.withIndex().associate { it.value.id to it.index }
    val dragOn = drag != null && !reorderMode
    item(key = "tree:heading", contentType = "tree-heading") {
        GroupedListHeader("规划树")
    }
    item(key = "tree:outline", contentType = "tree-outline") {
        GroupedListCell(first = true, last = work.volumes.isEmpty(), gap = 12.dp, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
            TreeReorderRow(reorderMode, actions.onToggleReorder)
            TreeOutlineRow(work, hasChildren = work.volumes.isNotEmpty(), onClick = actions.onOpenOutline)
        }
    }
    work.volumes.forEachIndexed { vi, volume ->
        val expanded = volume.id !in collapsedVolumeIds
        val lastVolume = vi == work.volumes.lastIndex
        item(key = volumeKey(volume.id), contentType = "volume") {
            GroupedListCell(first = false, last = lastVolume && !expanded, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                TreeVolumeRow(
                    volume = volume,
                    expanded = expanded,
                    lastVolume = lastVolume,
                    menuOpen = volumeMenuId == volume.id,
                    canDelete = work.volumes.size > 1,
                    digest = digests?.let { d -> d.volumes[volume.id]?.let { if (it.edited) TAG_EDITED else "有卷摘要" } ?: "" },
                    actions = actions,
                    indicator = drag?.drop?.takeIf { it.anchor == volumeKey(volume.id) && !it.same },
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
                        lastVolume = lastVolume,
                        marks = marks[chapter.id],
                        length = length,
                        actions = actions,
                        drag = if (dragOn) drag else null,
                    )
                }
            }
            item(key = addKey(volume.id), contentType = "add") {
                GroupedListCell(first = false, last = lastVolume, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                    TreeAddRow(
                        lastVolume = lastVolume,
                        indicator = drag?.drop?.takeIf { it.anchor == addKey(volume.id) && !it.same },
                    ) { actions.onAddChapter(volume) }
                }
            }
        }
    }
    // 故事至今、线索体检单独一张卡片接在树下面：放在总纲上面会把前几章挤出首屏，作者最常点的是章。
    if (digests != null || pulse != null) item(key = "tree:digest", contentType = "tree-digest") {
        GroupedListCell(first = true, last = true, gap = 12.dp, modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
            if (digests != null) TreeStoryRow(digests.story, actions.onOpenStory)
            if (pulse != null) TreePulseRow(pulse, pulseWarn, actions.onOpenPulse)
        }
    }
    item(key = "tree:hint", contentType = "tree-hint") {
        Text(
            "点章写正文，点右侧卡片看细纲；长按章节弹出菜单（改名、改状态、删除），长按后拖动可以直接调整顺序。",
            color = LocalGroupedColors.current.secondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

/** 连线的颜色：第三级灰，深色、墨水屏下都比分隔线清楚。 */
@Composable
private fun treeLine(): Color = LocalGroupedColors.current.tertiary

private fun DrawScope.vertical(x: Float, from: Float, to: Float, color: Color) =
    drawLine(color, Offset(x, from), Offset(x, to), LineWidth.toPx())

private fun DrawScope.horizontal(y: Float, from: Float, to: Float, color: Color) =
    drawLine(color, Offset(from, y), Offset(to, y), LineWidth.toPx())

/** 落点指示线：强调色、2dp，画在这一行的上边或下边，从卷的竖线画到右边。 */
private fun Modifier.dropIndicator(drop: TreeDrop?, color: Color): Modifier = if (drop == null) this else drawWithContent {
    drawContent()
    val w = 2.dp.toPx()
    val y = if (drop.before) w / 2 else size.height - w / 2
    drawLine(color, Offset(VolumeX.toPx(), y), Offset(size.width - 12.dp.toPx(), y), w)
    drawCircle(color, 3.dp.toPx(), Offset(VolumeX.toPx(), y))
}

@Composable
private fun TreeOutlineRow(work: Work, hasChildren: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val line = treeLine()
    GroupedRow(
        onClick = onClick,
        modifier = Modifier.drawBehind {
            // 树根：从总纲图标往下接到各卷。
            if (hasChildren) vertical(RootX.toPx(), size.height / 2 + 14.dp.toPx(), size.height, line)
        },
    ) {
        RowIcon(LingYueIcons.Book, c.tint)
        Column(Modifier.weight(1f)) {
            Text("总纲 · ${work.title}", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${work.volumes.size} 卷 · ${work.chapters.size} 章 · 设定包", color = c.secondary, fontSize = 12.sp)
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * 进出调整模式的一行：和下面的总纲一样带行首图标、名字下面一行说明，看得出能点；
 * 调整中换成强调色的“完成调整”，右边挂“调整中”标签，再点一下收起。拖不动（读屏、手不方便）时用它逐章上移、下移。
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
            Text(if (active) "用每章右侧的箭头上移、下移" else "逐章上移、下移；也可以长按章节直接拖", color = c.secondary, fontSize = 12.sp)
        }
        if (active) Tag("调整中", c.tint)
        else Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TreeVolumeRow(
    volume: Volume,
    expanded: Boolean,
    lastVolume: Boolean,
    menuOpen: Boolean,
    canDelete: Boolean,
    actions: WritingTreeActions,
    indicator: TreeDrop?,
    /** 卷摘要的状况（“有卷摘要”“你改过的”，没有为空串）；为空（null）时菜单里没有“卷摘要”。 */
    digest: String? = null,
) {
    val c = LocalGroupedColors.current
    val line = treeLine()
    val total = volume.chapters.size
    val finals = volume.chapters.count { it.status == ChapterStatus.FINAL }
    Box(Modifier.dropIndicator(indicator, c.tint)) {
        GroupedRow(
            onClick = { actions.onToggleVolume(volume.id) },
            modifier = Modifier.drawBehind {
                val mid = size.height / 2
                // 总纲的竖线穿过卷，最后一卷停在分叉处；分叉接到卷图标；展开时卷的竖线从图标下面往下接章。
                vertical(RootX.toPx(), 0f, if (lastVolume) mid else size.height, line)
                horizontal(mid, RootX.toPx(), VolumeX.toPx() - 14.dp.toPx(), line)
                if (expanded) vertical(VolumeX.toPx(), mid + 14.dp.toPx(), size.height, line)
            },
        ) {
            Spacer(Modifier.width(VolumeX - 16.dp - 14.dp))
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.fill), contentAlignment = Alignment.Center) {
                Icon(LingYueIcons.Book, contentDescription = null, tint = c.tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(volume.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$total 章 · 定稿 $finals · ${treeWords(volume.chapters.sumOf { it.words })}" + digest?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(),
                    color = c.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (total > 0) {
                    FillBar(
                        finals.toFloat() / total,
                        Modifier.fillMaxWidth().padding(top = 2.dp).semantics { contentDescription = "${volume.title}定稿 $finals / $total 章" },
                        color = statusColor(ChapterStatus.FINAL, c),
                    )
                }
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
                    if (digest != null) TreeMenuAction("卷摘要", c.label) { actions.onDismissVolumeMenu(); actions.onVolumeDigest(volume) }
                    if (canDelete) TreeMenuAction("删除本卷", c.destructive) { actions.onDismissVolumeMenu(); actions.onDeleteVolume(volume) }
                }
            }
        }
    }
}

/** 章节点前面的状态记号：和作品页色块同一种形状与颜色（细纲空心、草稿半满、修订中间留空、定稿实心）。 */
@Composable
internal fun StatusGlyph(status: ChapterStatus, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    val color = statusColor(status, c)
    Canvas(modifier.size(10.dp)) { drawStatusTile(status, color, c.cell, Offset.Zero, size.minDimension) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TreeChapterRow(
    chapter: WorkChapter,
    unsynced: Boolean,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    menuOpen: Boolean,
    lastVolume: Boolean,
    marks: ChapterTreeMarks?,
    length: CheckRule.Length?,
    actions: WritingTreeActions,
    drag: TreeDragController?,
) {
    val c = LocalGroupedColors.current
    val line = treeLine()
    val dragged = drag?.dragging?.id == chapter.id
    val indicator = drag?.drop?.takeIf { it.anchor == chapter.id && !it.same }
    Box(Modifier.dropIndicator(indicator, c.tint)) {
        GroupedRow(
            onClick = { actions.onOpenChapter(chapter) },
            modifier = Modifier
                .chapterGestures(chapter, drag, actions)
                .semantics { onLongClick("菜单") { actions.onOpenChapterMenu(chapter); true } }
                .drawBehind {
                    val mid = size.height / 2
                    if (!lastVolume) vertical(RootX.toPx(), 0f, size.height, line)
                    vertical(VolumeX.toPx(), 0f, size.height, line)
                    horizontal(mid, VolumeX.toPx(), ChapterDotX.toPx() - 2.dp.toPx(), line)
                }
                // 拖着的这一章原位变淡，浮起的那张卡片跟着手指（[TreeDragGhost]）。
                .graphicsLayer { alpha = if (dragged) 0.35f else 1f },
        ) {
            Spacer(Modifier.width(ChapterDotX - 16.dp))
            StatusGlyph(chapter.status)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(chapter.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${chapter.status.label} · ${treeWords(chapter.words)}", color = c.secondary, fontSize = 12.sp, maxLines = 1)
                        if (unsynced) Text("· 未整理", color = c.warning, fontSize = 12.sp, maxLines = 1)
                    }
                    lengthFraction(chapter.words, length)?.let { (fraction, over) ->
                        FillBar(
                            fraction,
                            Modifier.width(36.dp).align(Alignment.CenterVertically),
                            color = if (over) c.warning else c.tint,
                            animate = false,
                        )
                    }
                    if (marks != null) {
                        val card = listOfNotNull("有细纲".takeIf { marks.outline }, "${marks.beats} 拍".takeIf { marks.beats > 0 }).joinToString(" · ")
                        if (card.isNotEmpty()) Tag(card, c.tint)
                        if (marks.aiPending) Tag("AI 拟的·待确认", c.warning)
                    }
                }
            }
            if (reorderMode) {
                // 调整模式：每行右侧一对紧凑的上下箭头，看着小、能点 48dp；读屏念“上移第一章 雪夜”。
                MoveArrow(up = true, enabled = canMoveUp, label = "上移${chapter.title}") { actions.onMoveChapter(chapter, -1) }
                MoveArrow(up = false, enabled = canMoveDown, label = "下移${chapter.title}") { actions.onMoveChapter(chapter, 1) }
            } else {
                if (marks != null && (marks.planted > 0 || marks.payoff > 0)) ForeshadowMark(chapter, marks) { actions.onOpenLedger(chapter) }
                // 点一下直接进细纲卡页。原来先展开一行，那一行只有固定的说明字，没有别的信息，去掉了少点一下。
                IconButton(onClick = { actions.onOpenCard(chapter) }, modifier = Modifier.size(48.dp)) {
                    Icon(LingYueIcons.Notes, contentDescription = "打开${chapter.title}细纲卡", tint = c.tint, modifier = Modifier.size(18.dp))
                }
            }
        }
        ChapterMenuPopup(menuOpen, chapter, actions, actions.onDismissChapterMenu)
    }
}

/**
 * 章节点的手势：点一下照旧（行自己的点击）；长按到时有触感，再挪开 [TreeDragController.DRAG_START] 才算拖动，
 * 没挪就松手弹出长按菜单。拖着时这一行在列表里钉住（滚出屏幕也不被回收，手势不断）；
 * 长按以后的事件在最先一轮就消费掉，点击和列表滚动都不会再接手。[drag] 为空（调整模式、没接拖动）时长按只弹菜单。
 */
@Composable
private fun Modifier.chapterGestures(chapter: WorkChapter, drag: TreeDragController?, actions: WritingTreeActions): Modifier {
    val haptics = LocalHapticFeedback.current
    val pinnable = LocalPinnableContainer.current
    val current by rememberUpdatedState(chapter)
    val menu by rememberUpdatedState(actions.onOpenChapterMenu)
    val dropTo by rememberUpdatedState(actions.onDropChapter)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(drag) {
            val start = TreeDragController.DRAG_START.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 等长按：先抬手是点一下，先挪开是滚动列表，都交还给原来的处理。
                val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var stop = false
                    while (!stop) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        stop = change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
                if (interrupted != null) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                fun inList(local: Offset): Float? {
                    val list = drag?.listCoordinates ?: return null
                    val row = coordinates ?: return null
                    if (!list.isAttached || !row.isAttached) return null
                    return list.localPositionOf(row, local).y
                }
                var moved = false
                var drop: TreeDrop? = null
                var handle: PinnableContainer.PinnedHandle? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 长按以后的事件都在最先一轮消费：行的点击、列表的滚动都不再接手。
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                        if (drag == null) continue
                        val y = inList(change.position) ?: continue
                        if (!moved && abs(change.position.y - down.position.y) > start) {
                            moved = true
                            handle = pinnable?.pin()
                            drag.grab = down.position.y
                            drag.dragging = current
                        }
                        if (moved) drag.update(y)
                    }
                    drop = drag?.drop
                } finally {
                    if (moved) drag?.end()
                    handle?.release()
                }
                if (!moved) menu(current)
                else if (drop != null && !drop.same) dropTo(current, drop.volumeId, drop.index)
            }
        }
}

/**
 * 伏笔记号：账本里这一章埋下的（旗子）和计划这一章收的（勾）各几条，点了去账本、筛到这一章。
 * 能点 48dp，读屏念全句。
 */
@Composable
private fun ForeshadowMark(chapter: WorkChapter, marks: ChapterTreeMarks, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val label = listOfNotNull(
        "埋下 ${marks.planted} 条伏笔".takeIf { marks.planted > 0 },
        "计划收 ${marks.payoff} 条".takeIf { marks.payoff > 0 },
    ).joinToString("、")
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${chapter.title}：$label，去故事账本看" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            if (marks.planted > 0) MarkCount(LingYueIcons.Flag, marks.planted, c.tint)
            if (marks.payoff > 0) MarkCount(LingYueIcons.Check, marks.payoff, c.warning)
        }
    }
}

@Composable
private fun MarkCount(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text("$count", color = color, fontSize = 11.sp, maxLines = 1)
    }
}

/** 字数小条：本章字数占目标篇幅（检查规则的上限，没有上限按下限）多少；超出上限是 true。没写篇幅规则时为空，不画。 */
internal fun lengthFraction(words: Int, length: CheckRule.Length?): Pair<Float, Boolean>? {
    if (length == null) return null
    val goal = length.max ?: length.min ?: return null
    if (goal <= 0) return null
    val over = length.max != null && words > length.max
    return (words.toFloat() / goal).coerceIn(0f, 1f) to over
}

@Composable
private fun TreeAddRow(lastVolume: Boolean, indicator: TreeDrop?, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val line = treeLine()
    Box(Modifier.dropIndicator(indicator, c.tint)) {
        GroupedRow(
            onClick = onClick,
            modifier = Modifier.drawBehind {
                val mid = size.height / 2
                if (!lastVolume) vertical(RootX.toPx(), 0f, size.height, line)
                vertical(VolumeX.toPx(), 0f, mid, line)
                horizontal(mid, VolumeX.toPx(), ChapterDotX.toPx() - 2.dp.toPx(), line)
            },
        ) {
            Spacer(Modifier.width(ChapterDotX - 16.dp))
            Icon(LingYueIcons.Add, contentDescription = null, tint = c.tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("新的一章", color = c.tint, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 一章的长按菜单：规划树和作品页色块共用一份。弹层贴着调用处的父布局（树上是那一行，色块是那一格）。
 * 每项先关菜单（[onDismiss]）再做事。
 */
@Composable
internal fun ChapterMenuPopup(show: Boolean, chapter: WorkChapter, actions: WritingTreeActions, onDismiss: () -> Unit) {
    val c = LocalGroupedColors.current
    OverlayListPopup(show = show, alignment = PopupPositionProvider.Align.End, onDismissRequest = onDismiss) {
        GlassMenu {
            ListPopupColumn {
                TreeMenuAction("改名", c.label) { onDismiss(); actions.onRenameChapter(chapter) }
                TreeMenuAction("状态：${chapter.status.label}", c.label) { onDismiss(); actions.onStatusChapter(chapter) }
                TreeMenuAction("细纲卡", c.label) { onDismiss(); actions.onOpenCard(chapter) }
                TreeMenuAction("上移", c.label) { onDismiss(); actions.onMoveChapter(chapter, -1) }
                TreeMenuAction("下移", c.label) { onDismiss(); actions.onMoveChapter(chapter, 1) }
                TreeMenuAction("删除", c.destructive) { onDismiss(); actions.onDeleteChapter(chapter) }
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
