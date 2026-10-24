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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.floor

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
        // 相邻两章：分过的直接拿；没分过的等这一章先画出来、书页停着时再分（见下面），打开书、换章那一下不用一口气分三章。
        val hasPrev = chapterIndex > 0
        val hasNext = chapterIndex < content.chapters.lastIndex
        var prev by remember(chapterIndex, content, paginator, widthPx, heightPx) { mutableStateOf(pageCache.peek(cacheKey, chapterIndex - 1)) }
        var next by remember(chapterIndex, content, paginator, widthPx, heightPx) { mutableStateOf(pageCache.peek(cacheKey, chapterIndex + 1)) }
        // 双页时两页一组翻。
        val spreads = remember(pages, per) { pages.chunked(per) }
        val prevSpread = remember(prev, per) { prev?.chunked(per)?.last() }
        val nextSpread = remember(next, per) { next?.chunked(per)?.first() }
        // 两端各多放一页，显示相邻章节的末页/首页；翻到那里就切换章节，看起来是连着翻过去的。
        // 位置先占好，相邻章分好之前那一页只有纸和章名，页数不会因为分好了而变、当前页不会被挤开。
        val lead = if (hasPrev) 1 else 0
        val count = lead + spreads.size + if (hasNext) 1 else 0

        val startPage = remember(pages, start) {
            when {
                start.fromEnd -> pages.lastIndex
                start.block == null -> 0
                else -> pages.indexOfFirst { it.contains(start.block, start.char) }.coerceAtLeast(0)
            }
        }
        val pager = key(per) { rememberPagerState(initialPage = lead + startPage / per) { count } }
        val scope = rememberCoroutineScope()

        LaunchedEffect(chapterIndex, content, paginator, widthPx, heightPx) {
            if ((!hasNext || next != null) && (!hasPrev || prev != null)) return@LaunchedEffect
            // 先让这一章画出来（打开书的放大动画就在这几帧），再趁书页停着时分；往后翻的多，先分下一章。
            repeat(2) { withFrameNanos { } }
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            if (next == null) next = pagesOf(chapterIndex + 1)
            withFrameNanos { }
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            if (prev == null) prev = pagesOf(chapterIndex - 1)
        }

        // 墨水屏上、选了“无动画”时翻页直接换过去，免得一页页地闪。
        suspend fun turnTo(target: Int) {
            when {
                Effects.noMotion || mode == PageMode.NONE -> pager.scrollToPage(target)
                // 仿真：纸要卷起、翻过、落下，比平移覆盖慢一些，曲线先慢后快再慢。
                mode == PageMode.CURL -> pager.animateScrollToPage(target, animationSpec = tween(Motion.CURL_TURN, easing = Motion.Curl))
                else -> pager.animateScrollToPage(target, animationSpec = tween(Motion.TURN, easing = Motion.Standard))
            }
        }

        DisposableEffect(pager) {
            registerTurner { forward ->
                // 点按、音量键翻页从右下角掀起。
                touch.reset()
                scope.launch { turnTo((pager.currentPage + if (forward) 1 else -1).coerceIn(0, count - 1)) }
            }
            onDispose { }
        }

        // 无动画：书页拖着不动，跨过一半（松手就会换页）时轻轻一震，免得以为没拖上。
        if (mode == PageMode.NONE) {
            val haptic = LocalHapticFeedback.current
            LaunchedEffect(pager) {
                snapshotFlow { pager.currentPage }.drop(1).collect {
                    if (pager.isScrollInProgress) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
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

        // 双页仿真：翻起的右页背面就是下一面的左页。每面边画边录一份，翻页开始时把下一面拍成图给着色器。
        val curlSpreads = twoUp && mode == PageMode.CURL
        val spreadLayers = remember { HashMap<Int, GraphicsLayer>() }
        var backside by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
        if (curlSpreads) {
            LaunchedEffect(pager) {
                snapshotFlow {
                    val pos = pager.currentPage + pager.currentPageOffsetFraction
                    val k = floor(pos).toInt()
                    if (pos - k > 0.001f) k else null
                }.distinctUntilChanged().collectLatest { k ->
                    // 停稳了就丢掉，下次翻页重拍：书页的配色、划线可能变了。
                    if (k == null) {
                        backside = null
                        return@collectLatest
                    }
                    if (backside?.first == k) return@collectLatest
                    // 下一面可能这一帧才开始画，等几帧。
                    repeat(4) {
                        val layer = spreadLayers[k + 1]
                        if (layer != null && layer.size.width > 0 && layer.size.height > 0) {
                            runCatching { layer.toImageBitmap() }.getOrNull()?.let { backside = k to it }
                            return@collectLatest
                        }
                        withFrameNanos { }
                    }
                }
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
                    hasPrev && p == 0 -> onChapterChange(chapterIndex - 1, true)
                    hasNext && p == count - 1 -> onChapterChange(chapterIndex + 1, false)
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
            modifier = Modifier
                .fillMaxSize()
                .clipToBook(inset)
                .then(if (mode == PageMode.NONE) Modifier.dragHint(pager, styles.palette.accent) else Modifier),
        ) { p ->
            val (pageChapter, spread) = when {
                hasPrev && p == 0 -> chapterIndex - 1 to (prevSpread ?: emptyList())
                hasNext && p == count - 1 -> chapterIndex + 1 to (nextSpread ?: emptyList())
                else -> chapterIndex to spreads[p - lead]
            }
            fun offset() = p - (pager.currentPage + pager.currentPageOffsetFraction)
            val spreadLayer = rememberGraphicsLayer()
            if (curlSpreads) {
                DisposableEffect(p, spreadLayer) {
                    spreadLayers[p] = spreadLayer
                    onDispose { spreadLayers.remove(p, spreadLayer) }
                }
            }
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
                            Modifier.pageCurl(
                                progress = { offset().let { if (it < 0f && it > -1f) -it else 0f } },
                                touch = touch,
                                paper = styles.palette.background,
                                spine = if (twoUp) 0.5f else 0f,
                                backside = { backside?.takeIf { it.first == p }?.second },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (curlSpreads) {
                            Modifier.drawWithContent {
                                spreadLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(spreadLayer)
                            }
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

/**
 * 无动画翻页的拖动提示：书页不动，往哪边拖，那一侧边缘就亮起一道强调色的光，拖得越远越亮；
 * 过了一半（松手会换页）最亮。松手停稳就消失。
 */
private fun Modifier.dragHint(pager: androidx.compose.foundation.pager.PagerState, color: Color): Modifier = drawWithContent {
    drawContent()
    if (!pager.isScrollInProgress) return@drawWithContent
    val f = pager.currentPage + pager.currentPageOffsetFraction - pager.settledPage
    val strength = (kotlin.math.abs(f) * 1.6f).coerceIn(0f, 0.55f)
    if (strength < 0.01f) return@drawWithContent
    val w = 22.dp.toPx()
    val forward = f > 0f
    val x0 = if (forward) size.width - w else 0f
    drawRect(
        Brush.horizontalGradient(
            if (forward) listOf(Color.Transparent, color.copy(alpha = strength)) else listOf(color.copy(alpha = strength), Color.Transparent),
            startX = x0,
            endX = x0 + w,
        ),
        topLeft = Offset(x0, 0f),
        size = Size(w, size.height),
    )
}
