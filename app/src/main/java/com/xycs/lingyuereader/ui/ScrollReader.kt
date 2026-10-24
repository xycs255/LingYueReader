package com.xycs.lingyuereader.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Utterance
import com.xycs.lingyuereader.data.displayText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/** 复用正文选字时的真实排版结果，把滚动视口上沿换算为原文字符。 */
private class ScrollAnchorSelector(private val delegate: TextSelector) : TextSelector {
    private data class Entry(
        val block: Int,
        val coords: LayoutCoordinates,
        val layout: TextLayoutResult,
        val toChar: (Int) -> Int,
        val toLayout: (Int) -> Int,
    )

    private val entries = mutableMapOf<Any, Entry>()
    var version by mutableIntStateOf(0)
        private set

    override fun press(block: Int, char: Int) = delegate.press(block, char)

    override fun register(key: Any, block: Int, coords: LayoutCoordinates, layout: TextLayoutResult, toChar: (Int) -> Int, toLayout: (Int) -> Int) {
        delegate.register(key, block, coords, layout, toChar, toLayout)
        entries[key] = Entry(block, coords, layout, toChar, toLayout)
        version++
    }

    override fun unregister(key: Any) {
        delegate.unregister(key)
        if (entries.remove(key) != null) version++
    }

    private fun entry(block: Int): Entry? = entries.values.firstOrNull { it.block == block && it.coords.isAttached }

    fun charAtTop(block: Int, viewport: LayoutCoordinates?): Int? {
        val e = entry(block) ?: return null
        val top = viewport?.takeIf { it.isAttached }?.positionInRoot()?.y ?: return null
        val y = (top - e.coords.positionInRoot().y).coerceIn(0f, e.layout.size.height.toFloat())
        val line = e.layout.getLineForVerticalPosition(y)
        return e.toChar(e.layout.getLineStart(line))
    }

    fun offsetForChar(block: Int, char: Int, itemOffset: Int, viewport: LayoutCoordinates?): Int? {
        val e = entry(block) ?: return null
        val top = viewport?.takeIf { it.isAttached }?.positionInRoot()?.y ?: return null
        val layoutChar = e.toLayout(char).coerceIn(0, (e.layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val line = e.layout.getLineForOffset(layoutChar)
        return (e.coords.positionInRoot().y - top - itemOffset + e.layout.getLineTop(line)).toInt().coerceAtLeast(0)
    }
}

/** 列表里第 0 项是章名，正文块从第 1 项开始。 */
internal const val HEADER_ITEMS = 1

/** 滚动时最多多久报一次阅读位置。 */
private const val POSITION_INTERVAL_MS = 200L

/**
 * 滚动模式点一下翻多远：能看清的只有状态栏以下、状态行以上这一段，按这段的高度翻，再少翻一行半——
 * 底边上被状态行挡住一半的那行不会漏掉，刚读完的最后一行也还留在顶上。
 */
internal fun pageScrollDistance(viewport: Float, topInset: Float, bottomInset: Float, lineHeight: Float): Float {
    val readable = (viewport - topInset - bottomInset).coerceAtLeast(lineHeight * 2)
    return (readable - lineHeight * 1.5f).coerceAtLeast(lineHeight)
}

@Composable
internal fun ScrollReader(
    chapter: Chapter,
    chapterIndex: Int,
    nextTitle: String?,
    styles: ReaderStyles,
    marks: Marks,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp,
    onTap: (xFraction: Float) -> Unit,
    selector: TextSelector,
    /** 重排后恢复到这一原文字符；像素偏移只供旧版进度读取。 */
    startItem: Int = 0,
    startChar: Int = 0,
    onFootnote: (String) -> Unit = {},
    /** 登记一个滚动函数，划选拖到屏幕边缘时用来自动滚动。 */
    registerScroller: ((suspend (Float) -> Float)?) -> Unit = {},
    onPosition: (item: Int, offset: Int, char: Int, fraction: Float) -> Unit,
    onNext: () -> Unit,
    registerTurner: ((Boolean) -> Unit) -> Unit,
    /** 听书时正在读的一句（本章内）。 */
    speaking: Utterance? = null,
    /** 正在查的词：第几块、哪一段。 */
    selected: Highlight? = null,
) {
    val palette = styles.palette
    val scope = rememberCoroutineScope()
    val anchorSelector = remember(selector) { ScrollAnchorSelector(selector) }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var ready by remember(listState) { mutableStateOf(startChar <= 0) }
    val lineHeightPx = with(LocalDensity.current) { (styles.settings.fontSize * styles.settings.lineHeight).sp.toPx() }
    val total = chapter.blocks.size + 2

    val density = LocalDensity.current
    val topPx = with(density) { topInset.toPx() }
    val bottomPx = with(density) { bottomInset.toPx() }
    // 往下翻一屏后，在原来的底边（现在到了顶上）左边距划一道短横线：“上次读到这里”，眼睛不用重新找。
    val marker = remember { Animatable(0f) }
    var markerY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(listState) {
        registerTurner { forward ->
            val distance = pageScrollDistance(listState.layoutInfo.viewportSize.height.toFloat(), topPx, bottomPx, lineHeightPx)
            scope.launch {
                if (Effects.noMotion) listState.scrollBy(if (forward) distance else -distance)
                else listState.animateScrollBy(if (forward) distance else -distance, tween<Float>(Motion.TURN, easing = Motion.Standard))
                if (forward && !Effects.noMotion) {
                    val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                    markerY = viewport - bottomPx - distance
                    marker.snapTo(1f)
                    marker.animateTo(0f, tween(Motion.MARKER_FADE, delayMillis = Motion.MARKER_HOLD, easing = Motion.Standard))
                }
            }
        }
        registerScroller { dy -> listState.scrollBy(dy) }
        onDispose { registerScroller(null) }
    }

    // 听书：正在读的段落不在屏幕上时滚过去。
    LaunchedEffect(speaking?.block) {
        val item = (speaking ?: return@LaunchedEffect).block + HEADER_ITEMS
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any {
            it.index == item && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset
        }
        if (!fullyVisible) {
            if (Effects.noMotion) listState.scrollToItem(item) else listState.animateScrollToItem(item)
        }
    }

    LaunchedEffect(listState, startItem, startChar) {
        if (startChar > 0) {
            // 首个正文块排版完成后，把锚点所在行放到视口上沿。
            repeat(12) {
                withFrameNanos { }
                val itemOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == startItem }?.offset
                val offset = itemOffset?.let { anchorSelector.offsetForChar(startItem - HEADER_ITEMS, startChar, it, viewport) }
                if (offset != null) {
                    listState.scrollToItem(startItem, offset)
                    ready = true
                    return@LaunchedEffect
                }
            }
        }
        ready = true
    }

    LaunchedEffect(listState, anchorSelector) {
        // 滚动时每帧都在变；最多每 200 毫秒报一次（找顶上那个字、重组整个阅读页都不便宜），停下来的那一帧之后一定报。
        var reportedAt = 0L
        snapshotFlow { Triple(Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, anchorSelector.version), ready, viewport) }
            .collectLatest { (_, canReport, visibleViewport) ->
                if (!canReport) return@collectLatest
                val now = withFrameMillis { it }
                val wait = POSITION_INTERVAL_MS - (now - reportedAt)
                if (wait > 0) delay(wait)
                val item = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: item
                val fraction = if (last >= total - 1) 1f else item.toFloat() / total
                val char = anchorSelector.charAtTop(item - HEADER_ITEMS, visibleViewport)
                if (char == null && chapter.blocks.getOrNull(item - HEADER_ITEMS)?.displayText?.isNotEmpty() == true) return@collectLatest
                reportedAt = now + wait.coerceAtLeast(0)
                onPosition(item, offset, char ?: 0, fraction)
            }
    }

    // 手指自己滚动时，标记立刻消失（它只标翻屏前的位置）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling && !marker.isRunning) marker.snapTo(0f) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewport = it }
            .drawWithContent {
                drawContent()
                val a = marker.value
                if (a > 0f) {
                    // 左边距里一道短横线，标在原来的底边：线上是读过的，线下接着读。
                    val margin = styles.horizontalPadding.toPx()
                    drawRoundRect(
                        palette.accent.copy(alpha = 0.6f * a),
                        topLeft = Offset(margin * 0.2f, markerY - 1.dp.toPx()),
                        size = Size(margin * 0.6f, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x / size.width) }) },
        contentPadding = PaddingValues(
            start = styles.horizontalPadding,
            end = styles.horizontalPadding,
            top = topInset,
            bottom = bottomInset + 12.dp,
        ),
    ) {
        item { ChapterOpener(chapter.title, styles) }
        itemsIndexed(chapter.blocks) { index, block ->
            BlockView(
                block,
                styles,
                marks.notesIn(chapterIndex, index),
                speaking = speaking?.takeIf { it.block == index }?.let { it.start until it.end },
                highlight = selected,
                onTap = onTap,
                blockIndex = index,
                selector = anchorSelector,
                onFootnote = onFootnote,
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("· 本章完 ·", color = palette.secondary, fontSize = 13.sp, letterSpacing = 0.3.em)
                Spacer(Modifier.height(24.dp))
                if (nextTitle != null) {
                    Button(onClick = onNext, colors = ButtonDefaults.buttonColors(color = palette.bar)) {
                        Text("下一章　$nextTitle", color = palette.accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("全书完", color = palette.secondary, fontSize = 13.sp)
                }
            }
        }
    }
}
