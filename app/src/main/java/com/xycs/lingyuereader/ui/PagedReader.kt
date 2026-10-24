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
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.xycs.lingyuereader.data.layoutKey
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.Utterance
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/** 书签丝带：长条，下端剪一个燕尾。 */
private val RibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2, size.height * 0.78f)
    lineTo(0f, size.height)
    close()
}

/** 页眉（章名那一行小字）占的高度：阅读页把正文往下挪这么多，页眉画在挪出来的地方。 */
internal val PAGE_HEADER = 28.dp

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
    /** 分好的页；放在阅读区外面，换章重建阅读区时还在。 */
    pageCache: PageCache = remember { PageCache() },
    /** 翻到哪了，书页厚度跟着走。 */
    turn: PageTurn? = null,
) {
    // 仿真翻页：按下的高度决定掀哪个角，之后角跟着手指上下走。
    val touch = remember { CurlTouch() }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val y = e.changes.first().position.y
                    when (e.type) {
                        PointerEventType.Press -> {
                            touch.pressY = y
                            touch.fingerY = y
                        }
                        PointerEventType.Move -> touch.fingerY = y
                    }
                }
            }
        },
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        // 横屏且够宽：一次两页，像摊开的书。
        val twoUp = styles.settings.twoPage && maxWidth > maxHeight && maxWidth >= 560.dp
        val per = if (twoUp) 2 else 1
        // 书页厚度：书页夹在两摞纸中间，按两摞之间的宽度排版。
        val inset = LocalBookInset.current
        val bookWidth = maxWidth - (inset?.total ?: 0.dp)
        val pageWidth = if (twoUp) bookWidth / 2 else bookWidth
        val widthPx = with(density) { (pageWidth - styles.horizontalPadding * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - topInset - bottomInset).roundToPx() }
        val imageMaxHeight = maxHeight - topInset - bottomInset - styles.gap
        val images = LocalBookImages.current
        // 配色随环境渐变时不必重新分页；注音的字表读好后行高变了，要重新分。
        val paginator = remember(styles.settings.layoutKey, styles.pinyin != null, density, images) {
            Paginator(ComposeTextFitter(measurer), styles, density) { src -> images?.aspect(src) }
        }

        val cacheKey = listOf(styles.settings.layoutKey, styles.pinyin != null, styles.fontFamily, density, images, widthPx, heightPx)

        fun pagesOf(index: Int): List<Page>? = content.chapters.getOrNull(index)?.let { c ->
            pageCache.get(cacheKey, index) { paginator.paginate(c, widthPx, heightPx) }
        }

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

        // 墨水屏上、选了“无动画”时翻页直接换过去，免得一页页地闪。
        suspend fun turnTo(target: Int) {
            if (Effects.noMotion || mode == PageMode.NONE) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = tween(Motion.TURN, easing = Motion.Standard))
        }

        DisposableEffect(pager) {
            registerTurner { forward ->
                // 点按、音量键翻页从右下角掀起。
                touch.reset()
                scope.launch { turnTo((pager.currentPage + if (forward) 1 else -1).coerceIn(0, count - 1)) }
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
                touch.reset()
                turnTo(spread)
            }
        }

        if (turn != null) {
            LaunchedEffect(pager, spreads) {
                turn.chapterShare = 1f / spreads.size
                snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction - pager.settledPage }
                    .collect { turn.progress = it.coerceIn(-1f, 1f) }
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
            modifier = Modifier.fillMaxSize().clipToBook(inset),
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
                            // 无动画：拖动时书页不动，松手停稳后直接换成下一页。
                            PageMode.NONE -> {
                                if (r > -1f && r < 1f) translationX = -r * size.width
                                alpha = if (p == pager.settledPage) 1f else 0f
                            }
                            else -> {}
                        }
                    }
                    .bookPage(inset)
                    .then(
                        if (mode == PageMode.CURL) {
                            Modifier.pageCurl({ offset().let { if (it < 0f && it > -1f) -it else 0f } }, touch, styles.palette.background)
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
                // 加了书签的页，右上角垂下一条书签丝带。
                val pageChapterContent = content.chapters[pageChapter]
                val bookmarked = marks.bookmarks.any { bm ->
                    bm.chapterIndex == pageChapter && !bm.lost && spread.any { page ->
                        val block = bm.itemIndex - HEADER_ITEMS
                        page.contains(block, pageLayoutChar(pageChapterContent, block, bm.charOffset, styles.settings.indent))
                    }
                }
                if (bookmarked) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = styles.horizontalPadding)
                            .size(14.dp, 36.dp)
                            .background(styles.palette.accent, RibbonShape)
                            .semantics { contentDescription = "本页有书签" },
                    )
                }
                // 页眉：每页顶上一行小字写章名，跟着书页一起翻。
                if (topInset > PAGE_HEADER) {
                    Text(
                        content.chapters[pageChapter].title,
                        color = styles.palette.secondary,
                        fontSize = 12.sp,
                        letterSpacing = 0.12.em,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = styles.horizontalPadding, end = styles.horizontalPadding, top = topInset - PAGE_HEADER)
                            .clearAndSetSemantics { },
                    )
                }
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
                PageItem.Title -> ChapterOpener(chapter.title, styles)
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
