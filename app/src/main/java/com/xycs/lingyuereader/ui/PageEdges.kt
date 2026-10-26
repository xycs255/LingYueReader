package com.xycs.lingyuereader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.PageMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 正在翻的那一页翻到哪了。翻页的阅读区写，书页厚度读：拖着翻时，读过和没读的两摞纸跟着手指一页页地换，
 * 翻起的纸在两摞上投下影子。
 */
@Stable
internal class PageTurn {
    /** 相对停稳的那一页：0 没在翻，往后翻趋向 1，往前翻趋向 -1。 */
    var progress by mutableFloatStateOf(0f)

    /** 翻一页（双页时一对）占本章的几分之几。 */
    var chapterShare by mutableFloatStateOf(0f)
}

/** 整本书摞起来有多厚：十万字约 7dp，三十万字约 10dp，百万字约 14dp（最厚），最薄 4dp。 */
internal fun bookThickness(chars: Long): Dp = (4f + 3f * log2(1f + chars / 100_000f)).coerceIn(4f, 14f).dp

/** 翻起的纸在两摞上投的影子有多深：仿真翻页纸真的掀起来，最明显；覆盖、平移淡一些；无动画没有。 */
internal fun turnShadow(mode: PageMode): Float = when (mode) {
    PageMode.CURL -> 0.24f
    PageMode.COVER -> 0.16f
    PageMode.SLIDE, PageMode.VERTICAL -> 0.1f
    PageMode.NONE, PageMode.SCROLL -> 0f
}


/**
 * 书页夹在两摞纸中间时，书页在阅读区里占哪一段：两摞合起来厚 [total]，左边那摞宽 [left] 像素。
 * 阅读区仍占满全宽（从屏幕最边上划也能翻页），只是按书页的宽度排版，书页画在两摞之间，越出去的部分裁掉。
 */
@Stable
internal class BookInset(val total: Dp, val left: Density.() -> Int)

internal val LocalBookInset = staticCompositionLocalOf<BookInset?> { null }

/** 把一页放进两摞纸之间：宽度是全宽减去两摞，左边让出读过那摞的宽度。没有书页厚度时原样。 */
internal fun Modifier.bookPage(inset: BookInset?): Modifier = if (inset == null) this else layout { measurable, constraints ->
    val total = inset.total.roundToPx()
    val width = (constraints.maxWidth - total).coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    val density: Density = this
    layout(constraints.maxWidth, placeable.height) { placeable.place(inset.left(density), 0) }
}

/** 只画两摞纸之间那一段（翻页时滑出去、卷过去的纸不盖到两摞上）。 */
internal fun Modifier.clipToBook(inset: BookInset?): Modifier = if (inset == null) this else drawWithContent {
    val left = inset.left(this).toFloat()
    clipRect(left, 0f, left + size.width - inset.total.toPx(), size.height) { this@drawWithContent.drawContent() }
}

/**
 * 纸书的书口：书页夹在两摞纸中间，左边是读过的，右边是没读的（竖排左右对调）。
 * 两摞合起来的厚度 [thickness] 不变，所以书页的宽度不变、不必重新分页，只是读着读着往右挪一点。
 * 阅读区（[content]）从 [LocalBookInset] 知道书页在哪：平移、覆盖时纸在两摞之间滑，仿真时翻起的纸落到左边那摞上。
 */
@Composable
internal fun BookEdges(
    fraction: Float,
    pageShare: Float,
    turn: PageTurn,
    mode: PageMode,
    palette: ReaderPalette,
    rightToLeft: Boolean,
    thickness: Dp,
    content: @Composable () -> Unit,
) {
    // 跳章、拖进度条时两摞顺滑地换过去；一页页翻时停稳那一下直接接上，不再动画（翻的过程已经跟手走过了）。
    val shown = remember { Animatable(fraction) }
    LaunchedEffect(fraction) {
        if (Effects.noMotion || abs(fraction - shown.value) <= pageShare * 3f) shown.snapTo(fraction)
        else shown.animateTo(fraction, Motion.standard(Motion.MEDIUM))
    }
    // 翻页途中两摞不动：一页的厚度不到一像素，要是跟着翻页进度挪，取整后中途会跳一像素，书页跟着一跳，书口一闪。
    // 停稳以后 [fraction] 更新，两摞再接上。
    fun read() = shown.value
    // 左边那摞的宽度（像素，取整，书页和两摞严丝合缝）。
    fun leftPx(total: Int): Int = (total * if (rightToLeft) 1f - read() else read()).roundToInt()

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val total = thickness.roundToPx()
            val left = leftPx(total).toFloat()
            val right = total - left
            val p = turn.progress
            val lift = if (Effects.noMotion) 0f else sin(PI.toFloat() * abs(p)) * turnShadow(mode)
            // 往后翻：纸从没读的那摞掀起，落到读过的那摞上；往前翻反过来。
            val towardRead = if (p >= 0f) abs(p) else 1f - abs(p)
            val readShade = lift * towardRead
            val unreadShade = lift * (1f - towardRead)
            paperStack(0f, left, outerOnLeft = true, palette, if (rightToLeft) unreadShade else readShade)
            paperStack(size.width - right, size.width, outerOnLeft = false, palette, if (rightToLeft) readShade else unreadShade)
        }
        val inset = remember(thickness) { BookInset(thickness) { leftPx(thickness.roundToPx()) } }
        CompositionLocalProvider(LocalBookInset provides inset, content = content)
    }
}

/**
 * 一摞纸的侧面，占 [from]..[to]，[outerOnLeft] 为真时外侧在左（左边那摞）。从里到外：
 * - 贴着书页的一边与书页同高；越往外越矮，上下两头圆过去，像一沓纸的书口；
 * - 纸边比纸面略暗，一张张纸的边是深浅不一的细线（按离外侧第几张取深浅，翻页时外侧的线不跟着闪）；
 * - 书页压在上面，靠书页那边有一道阴影；最外一道轮廓线；
 * - [shade]：翻起的纸投下的影子。
 */
private fun DrawScope.paperStack(from: Float, to: Float, outerOnLeft: Boolean, palette: ReaderPalette, shade: Float) {
    val width = to - from
    if (width < 0.5f) return
    val outer = if (outerOnLeft) from else to
    val inner = if (outerOnLeft) to else from
    val inward = if (outerOnLeft) 1f else -1f
    val h = size.height
    val taper = minOf(width * 0.6f, 3.dp.toPx())
    val shape = Path().apply {
        moveTo(inner, 0f)
        quadraticTo(outer + inward * width * 0.2f, 0f, outer, taper)
        lineTo(outer, h - taper)
        quadraticTo(outer + inward * width * 0.2f, h, inner, h)
        close()
    }
    val edge = lerp(palette.background, palette.text, if (palette.isDark) 0.16f else 0.08f)
    clipPath(shape) {
        drawRect(edge, Offset(minOf(from, to), 0f), androidx.compose.ui.geometry.Size(width, h))
        // 纸边：约 0.7dp 一张，深浅随张而变；线落在整像素上，细而清楚，不糊成一片灰。
        val spacing = maxOf(2, 0.7.dp.roundToPx())
        val sheets = (width / spacing).toInt() + 1
        val start = floor(outer)
        for (i in 0 until sheets) {
            val x = start + inward * (i * spacing + 1) + 0.5f
            val alpha = (if (palette.isDark) 0.05f else 0.04f) + 0.16f * hash(i)
            drawLine(palette.text.copy(alpha = alpha), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        // 中间略亮，像侧面受光。
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = if (palette.isDark) 0.03f else 0.12f),
                1f to Color.Transparent,
                startX = from,
                endX = to,
            ),
            Offset(from, 0f),
            androidx.compose.ui.geometry.Size(width, h),
        )
        // 书页压在这摞上的影子。
        val reach = minOf(width, 5.dp.toPx())
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = if (palette.isDark) 0.35f else 0.16f), Color.Transparent),
                startX = inner,
                endX = inner - inward * reach,
            ),
            Offset(minOf(from, to), 0f),
            androidx.compose.ui.geometry.Size(width, h),
        )
        // 翻起的纸投下的影子：靠书页一侧最深。
        if (shade > 0.001f) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = shade), Color.Black.copy(alpha = shade * 0.35f)),
                    startX = inner,
                    endX = outer,
                ),
                Offset(minOf(from, to), 0f),
                androidx.compose.ui.geometry.Size(width, h),
            )
        }
    }
    // 最外一道轮廓。
    drawLine(palette.text.copy(alpha = 0.18f), Offset(outer + inward * 0.5f, taper), Offset(outer + inward * 0.5f, h - taper), strokeWidth = 1f)
}

/** 0..1 之间、按 [i] 固定的伪随机数。 */
private fun hash(i: Int): Float {
    val v = sin(i * 12.9898f) * 43758.547f
    return v - floor(v)
}
