package com.xycs.lingyuereader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

/**
 * 章节色块带的排布：一格边长 [tile]、格间 [gap]（像素），一行 [columns] 格，从左到右、从上到下排。
 * 整片色块用一个 Canvas 画，点中哪一格按坐标算（[indexAt]），不给每章一个 composable：几百章也不卡。
 */
internal data class TileGrid(val columns: Int, val tile: Float, val gap: Float) {
    private val step get() = tile + gap

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns - 1) / columns

    fun height(count: Int): Float = rows(count).let { if (it == 0) 0f else it * tile + (it - 1) * gap }

    fun origin(index: Int): Offset = Offset((index % columns) * step, (index / columns) * step)

    /**
     * 坐标（相对色块区左上角）点中的是第几格（从 0 数）。格子右边、下边的缝算这一格：缝只有几个像素，手指本来就盖住好几格，
     * 不让点在缝里落空。点到最后一格后面的空位、或出了色块区是空。
     */
    fun indexAt(x: Float, y: Float, count: Int): Int? {
        if (x < 0f || y < 0f || count <= 0) return null
        val col = (x / step).toInt()
        val row = (y / step).toInt()
        if (col >= columns || row >= rows(count)) return null
        return (row * columns + col).takeIf { it < count }
    }

    companion object {
        /** 在 [width] 宽里能排几格就排几格（至少一格）。 */
        fun fit(width: Float, tile: Float, gap: Float): TileGrid =
            TileGrid(maxOf(1, ((width + gap) / (tile + gap)).toInt()), tile, gap)

        /** 章少时格子大一点、好点；几百章时缩小，整片仍在一两屏里。 */
        fun tileFor(chapters: Int): Pair<Float, Float> = when {
            chapters <= 120 -> 18f to 4f
            chapters <= 400 -> 14f to 3f
            else -> 10f to 3f
        }
    }
}

/** 一卷色块的读屏说明：“第一卷 30 章：定稿 12、修订 3、草稿 15，2 章没整理”。 */
internal fun volumeMapSummary(volume: Volume, unsyncedIds: Set<String>): String {
    val chapters = volume.chapters
    if (chapters.isEmpty()) return "${volume.title}：还没有章"
    val counts = listOf(ChapterStatus.FINAL, ChapterStatus.REVISION, ChapterStatus.DRAFT, ChapterStatus.OUTLINE)
        .mapNotNull { s -> chapters.count { it.status == s }.takeIf { it > 0 }?.let { "${s.label} $it" } }
    val unsynced = chapters.count { it.id in unsyncedIds }
    return "${volume.title} ${chapters.size} 章：" + counts.joinToString("、") + if (unsynced > 0) "，$unsynced 章没整理" else ""
}

/** 一章色块的读屏说明（逐章的自定义操作名）：“打开第3章 夜渡，草稿，没整理”。 */
internal fun chapterTileLabel(chapter: WorkChapter, unsynced: Boolean): String =
    "打开${chapter.title}，${statusWord(chapter.status)}" + if (unsynced) "，没整理" else ""

private fun statusWord(status: ChapterStatus): String = if (status == ChapterStatus.FINAL) "定稿（锁定）" else status.label

/**
 * 画一格状态色块：颜色之外还用形状分，墨水屏灰阶、色弱也分得开——细纲是空心框，草稿下半截实心，
 * 修订实心中间留一个小空心，定稿整格实心。[hole] 是挖空处的底色（卡片色）。
 */
internal fun DrawScope.drawStatusTile(status: ChapterStatus, color: Color, hole: Color, topLeft: Offset, side: Float) {
    val r = CornerRadius(side * 0.22f)
    val size = Size(side, side)
    when (status) {
        ChapterStatus.OUTLINE -> {
            val w = maxOf(1f, side * 0.14f)
            drawRoundRect(color, topLeft + Offset(w / 2, w / 2), Size(side - w, side - w), r, style = Stroke(w))
        }
        ChapterStatus.DRAFT -> {
            val w = maxOf(1f, side * 0.14f)
            drawRoundRect(color, topLeft + Offset(w / 2, w / 2), Size(side - w, side - w), r, style = Stroke(w))
            drawRoundRect(color, topLeft + Offset(0f, side / 2), Size(side, side / 2), r)
        }
        ChapterStatus.REVISION -> {
            drawRoundRect(color, topLeft, size, r)
            val d = side * 0.36f
            drawRoundRect(hole, topLeft + Offset((side - d) / 2, (side - d) / 2), Size(d, d), CornerRadius(d / 2))
        }
        ChapterStatus.FINAL -> drawRoundRect(color, topLeft, size, r)
    }
}

/** 没整理的章：色块右上角一个删除色小圆点，外面一圈卡片色，和色块分开。 */
private fun DrawScope.drawUnsyncedDot(color: Color, ring: Color, topLeft: Offset, side: Float) {
    val radius = maxOf(2.5f, side * 0.2f)
    val center = topLeft + Offset(side - radius * 0.4f, radius * 0.4f)
    drawCircle(ring, radius + maxOf(1f, side * 0.07f), center)
    drawCircle(color, radius, center)
}

/**
 * 作品页的章节色块带：每卷一行卷名和“定稿 N / M”，下面每章一格，按卷分组、自动换行；颜色和形状表示状态，
 * 没整理的右上角一个小红点。点一格打开那章，长按弹出和规划树长按一样的菜单（[menu] 画在那一格上）。
 * 读屏：每卷一段说明（[volumeMapSummary]），逐章用自定义操作打开。
 */
@Composable
internal fun ChapterMap(
    work: Work,
    unsyncedIds: Set<String>,
    menuChapterId: String?,
    onOpen: (WorkChapter) -> Unit,
    onLongPress: (WorkChapter) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable (WorkChapter) -> Unit = {},
) {
    val c = LocalGroupedColors.current
    val density = LocalDensity.current
    val (tileDp, gapDp) = TileGrid.tileFor(work.chapters.size)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        work.volumes.forEach { volume ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val finals = volume.chapters.count { it.status == ChapterStatus.FINAL }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(volume.title, color = c.label, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (volume.chapters.isEmpty()) "还没有章" else "定稿 $finals / ${volume.chapters.size}",
                        color = c.secondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                if (volume.chapters.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val grid = with(density) { TileGrid.fit(maxWidth.toPx(), tileDp.dp.toPx(), gapDp.dp.toPx()) }
                        VolumeTiles(volume, grid, unsyncedIds, onOpen, onLongPress)
                        volume.chapters.firstOrNull { it.id == menuChapterId }?.let { chapter ->
                            val at = grid.origin(volume.chapters.indexOf(chapter))
                            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(tileDp.dp)) { menu(chapter) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeTiles(
    volume: Volume,
    grid: TileGrid,
    unsyncedIds: Set<String>,
    onOpen: (WorkChapter) -> Unit,
    onLongPress: (WorkChapter) -> Unit,
) {
    val c = LocalGroupedColors.current
    val haptics = LocalHapticFeedback.current
    val chapters = volume.chapters
    val open by rememberUpdatedState(onOpen)
    val press by rememberUpdatedState(onLongPress)
    val colors = ChapterStatus.entries.associateWith { statusColor(it, c) }
    val height = with(LocalDensity.current) { grid.height(chapters.size).toDp() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(chapters, grid) {
                detectTapGestures(
                    onTap = { p -> grid.indexAt(p.x, p.y, chapters.size)?.let { open(chapters[it]) } },
                    onLongPress = { p ->
                        grid.indexAt(p.x, p.y, chapters.size)?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            press(chapters[it])
                        }
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = volumeMapSummary(volume, unsyncedIds)
                customActions = chapters.map { ch ->
                    CustomAccessibilityAction(chapterTileLabel(ch, ch.id in unsyncedIds)) { open(ch); true }
                }
            },
    ) {
        chapters.forEachIndexed { i, ch ->
            val at = grid.origin(i)
            drawStatusTile(ch.status, colors.getValue(ch.status), c.cell, at, grid.tile)
            if (ch.id in unsyncedIds) drawUnsyncedDot(c.destructive, c.cell, at, grid.tile)
        }
    }
}

/** 色块的图例：四种状态各一格（同样的形状），再加“没整理”的小红点；放不下就换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChapterMapLegend(modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    FlowRow(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChapterStatus.entries.forEach { s ->
            LegendItem(statusWord(s)) { drawStatusTile(s, statusColor(s, c), c.cell, Offset.Zero, size.minDimension) }
        }
        LegendItem("没整理") {
            drawStatusTile(ChapterStatus.DRAFT, c.tertiary, c.cell, Offset.Zero, size.minDimension)
            drawUnsyncedDot(c.destructive, c.cell, Offset.Zero, size.minDimension)
        }
    }
}

@Composable
private fun LegendItem(label: String, draw: DrawScope.() -> Unit) {
    val c = LocalGroupedColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp), onDraw = draw)
        Spacer(Modifier.width(4.dp))
        Text(label, color = c.secondary, fontSize = 12.sp, maxLines = 1)
    }
}
