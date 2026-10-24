package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xycs.lingyuereader.data.layoutKey
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.Utterance
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/** 翻页模式的起点：指定块与块内字，或从章末（向前翻进上一章时）。 */
internal data class PageStart(val block: Int?, val char: Int, val fromEnd: Boolean)

@Composable
internal fun PagedReader(
    content: BookContent,
    chapterIndex: Int,
    start: PageStart,
    styles: ReaderStyles,
    mode: PageMode,
    marks: Marks,
    topInset: Dp,
    bottomInset: Dp,
    onTap: (xFraction: Float) -> Unit,
    selector: TextSelector,
    onFootnote: (String) -> Unit = {},
    onPosition: (block: Int, char: Int, fraction: Float, pageLabel: String) -> Unit,
    onChapterChange: (index: Int, fromEnd: Boolean) -> Unit,
    registerTurner: ((Boolean) -> Unit) -> Unit,
    /** 听书时正在读的一句（本章内）。 */
    speaking: Utterance? = null,
    /** 正在查的词：第几块、哪一段。 */
    selected: Highlight? = null,
) {
    // 仿真翻页按手指按下的高度决定掀起哪个角。
    var touchY by remember { mutableFloatStateOf(-1f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press) touchY = e.changes.first().position.y
                }
            }
        },
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        // 横屏且够宽：一次两页，像摊开的书。
        val twoUp = styles.settings.twoPage && maxWidth > maxHeight && maxWidth >= 560.dp
        val per = if (twoUp) 2 else 1
        val pageWidth = if (twoUp) maxWidth / 2 else maxWidth
        val widthPx = with(density) { (pageWidth - styles.horizontalPadding * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - topInset - bottomInset).roundToPx() }
        val imageMaxHeight = maxHeight - topInset - bottomInset - styles.gap
        val images = LocalBookImages.current
        // 配色随环境渐变时不必重新分页；注音的字表读好后行高变了，要重新分。
        val paginator = remember(styles.settings.layoutKey, styles.pinyin != null, density, images) {
            Paginator(ComposeTextFitter(measurer), styles, density) { src -> images?.aspect(src) }
        }

        fun pagesOf(index: Int): List<Page>? = content.chapters.getOrNull(index)?.let { paginator.paginate(it, widthPx, heightPx) }

        val chapter = content.chapters[chapterIndex]
        val pages = remember(chapter, paginator, widthPx, heightPx) { pagesOf(chapterIndex)!! }
        val prev = remember(chapterIndex, content, paginator, widthPx, heightPx) { pagesOf(chapterIndex - 1) }
        val next = remember(chapterIndex, content, paginator, widthPx, heightPx) { pagesOf(chapterIndex + 1) }
        // 双页时两页一组翻。
        val spreads = remember(pages, per) { pages.chunked(per) }
        val prevSpread = remember(prev, per) { prev?.chunked(per)?.last() }
        val nextSpread = remember(next, per) { next?.chunked(per)?.first() }
        // 两端各多放一页，显示相邻章节的末页/首页；翻到那里就切换章节，看起来是连着翻过去的。
        val lead = if (prev != null) 1 else 0
        val count = lead + spreads.size + if (next != null) 1 else 0

        val startPage = remember(pages, start) {
            when {
                start.fromEnd -> pages.lastIndex
                start.block == null -> 0
                else -> pages.indexOfFirst { it.contains(start.block, start.char) }.coerceAtLeast(0)
            }
        }
        val pager = key(per) { rememberPagerState(initialPage = lead + startPage / per) { count } }
        val scope = rememberCoroutineScope()

        DisposableEffect(pager) {
            registerTurner { forward ->
                scope.launch {
                    val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, count - 1)
                    // 墨水屏上翻页不带动画，免得一页页地闪。
                    if (Effects.noMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = tween(Motion.TURN, easing = Motion.Standard))
                }
            }
            onDispose { }
        }

        // 听书：读到下一页时自动翻过去。
        LaunchedEffect(speaking, pages) {
            val u = speaking ?: return@LaunchedEffect
            val indent = indentOf(chapter.blocks[u.block], styles.settings.indent)
            val target = pages.indexOfFirst { it.contains(u.block, u.start + indent) }
            val spread = lead + target / per
            if (target >= 0 && pager.currentPage != spread && !pager.isScrollInProgress) {
                if (Effects.noMotion) pager.scrollToPage(spread) else pager.animateScrollToPage(spread, animationSpec = tween(Motion.TURN, easing = Motion.Standard))
            }
        }

        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.settledPage }.collect { p ->
                when {
                    prev != null && p == 0 -> onChapterChange(chapterIndex - 1, true)
                    next != null && p == count - 1 -> onChapterChange(chapterIndex + 1, false)
                    else -> {
                        val i = ((p - lead) * per).coerceIn(pages.indices)
                        val last = (i + per - 1).coerceAtMost(pages.lastIndex)
                        val label = if (last > i) "${i + 1}-${last + 1}/${pages.size} 页" else "${i + 1}/${pages.size} 页"
                        onPosition(pages[i].firstBlock, pages[i].firstChar, (last + 1f) / pages.size, label)
                    }
                }
            }
        }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            val (pageChapter, spread) = when {
                prevSpread != null && p == 0 -> chapterIndex - 1 to prevSpread
                nextSpread != null && p == count - 1 -> chapterIndex + 1 to nextSpread
                else -> chapterIndex to spreads[p - lead]
            }
            fun offset() = p - (pager.currentPage + pager.currentPageOffsetFraction)
            Box(
                Modifier
                    .fillMaxSize()
                    // 覆盖式：后一页原地不动，当前页像纸一样盖在上面滑走。
                    .zIndex(-p.toFloat())
                    .graphicsLayer {
                        val r = offset()
                        when (mode) {
                            PageMode.COVER -> if (r > 0f) translationX = -r * size.width
                            // 仿真：两页都不动，前一页原地掀起来。
                            PageMode.CURL -> if (r > -1f && r < 1f) translationX = -r * size.width
                            else -> {}
                        }
                    }
                    .then(
                        if (mode == PageMode.CURL) {
                            Modifier.pageCurl({ offset().let { if (it < 0f && it > -1f) -it else 0f } }, { touchY }, styles.palette.background)
                        } else {
                            Modifier
                        },
                    )
                    .drawWithContent {
                        drawContent()
                        if (twoUp) {
                            // 书脊：中间一道浅浅的阴影。
                            val w = 28.dp.toPx()
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f), Color.Transparent),
                                    size.width / 2 - w,
                                    size.width / 2 + w,
                                ),
                                topLeft = Offset(size.width / 2 - w, 0f),
                                size = Size(w * 2, size.height),
                            )
                        }
                        if (mode == PageMode.COVER) {
                            val r = offset()
                            if (r < 0f && r > -1f) {
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent), size.width, size.width + 24.dp.toPx()),
                                    topLeft = Offset(size.width, 0f),
                                    size = Size(24.dp.toPx(), size.height),
                                )
                            }
                        }
                    }
                    .background(styles.palette.background)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x / size.width) }) },
            ) {
                // 插图最高一页，与分页时的估算一致（再减去段距）。
                CompositionLocalProvider(LocalImageMaxHeight provides imageMaxHeight) {
                    Row(Modifier.fillMaxSize()) {
                        spread.forEach { page ->
                            Box(Modifier.weight(1f)) {
                                PageContent(
                                    content.chapters[pageChapter], pageChapter, page, styles, marks, topInset, onTap,
                                    selector.takeIf { pageChapter == chapterIndex },
                                    speaking.takeIf { pageChapter == chapterIndex },
                                    selected.takeIf { pageChapter == chapterIndex },
                                    onFootnote,
                                )
                            }
                        }
                        // 章末只剩一页时右边留白。
                        if (spread.size < per) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(
    chapter: Chapter,
    chapterIndex: Int,
    page: Page,
    styles: ReaderStyles,
    marks: Marks,
    topInset: Dp,
    onTap: (Float) -> Unit,
    selector: TextSelector?,
    speaking: Utterance?,
    selected: Highlight?,
    onFootnote: (String) -> Unit,
) {
    fun speakRange(block: Int) = speaking?.takeIf { it.block == block }?.let { it.start until it.end }
    Column(Modifier.fillMaxWidth().padding(start = styles.horizontalPadding, end = styles.horizontalPadding, top = topInset)) {
        page.items.forEach { item ->
            when (item) {
                PageItem.Title -> Text(
                    chapter.title,
                    style = styles.title,
                    modifier = Modifier.padding(top = styles.titleTop, bottom = styles.titleBottom),
                )
                is PageItem.Slice -> BlockView(
                    chapter.blocks[item.block],
                    styles,
                    marks.notesIn(chapterIndex, item.block),
                    range = item.start until item.end,
                    isEnd = item.isEnd,
                    speaking = speakRange(item.block),
                    highlight = selected,
                    onTap = onTap,
                    blockIndex = item.block,
                    selector = selector,
                    onFootnote = onFootnote,
                )
                is PageItem.Whole -> BlockView(
                    chapter.blocks[item.block],
                    styles,
                    marks.notesIn(chapterIndex, item.block),
                    speaking = speakRange(item.block),
                    highlight = selected,
                    onTap = onTap,
                    blockIndex = item.block,
                    selector = selector,
                    onFootnote = onFootnote,
                )
            }
        }
    }
}
