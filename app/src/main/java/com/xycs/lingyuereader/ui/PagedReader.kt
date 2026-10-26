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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // 只在后台线程里调；[checkpoint] 在分页途中检查是否已取消（换章、改字号时旧的分页及早停下）。
        fun pagesOf(index: Int, checkpoint: () -> Unit): List<Page>? = content.chapters.getOrNull(index)?.let { c ->
            pageCache.get(cacheKey, index) { paginator.paginate(c, widthPx, heightPx, checkpoint) }
        }

        val chapter = content.chapters[chapterIndex]
        // 分页要逐段量文字、读插图大小，长章节、图多的漫画要好一会儿：放到后台线程分，不卡界面。
        // 分好之前只露出纸（前后章多半已经提前分好，翻过来时直接就有）。
        var ready by remember(chapter, paginator, widthPx, heightPx) { mutableStateOf(pageCache.peek(cacheKey, chapterIndex)) }
        LaunchedEffect(chapter, paginator, widthPx, heightPx) {
            if (ready == null) ready = withContext(Dispatchers.Default) { pagesOf(chapterIndex) { ensureActive() } }
        }
        val pages = ready ?: run {
            // 还在分页：轻点照样能叫出菜单、翻页。
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x / size.width) }) })
            return@BoxWithConstraints
        }
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
        fun spreadAt(p: Int): Pair<Int, List<Page>> = when {
            hasPrev && p == 0 -> chapterIndex - 1 to (prevSpread ?: emptyList())
            hasNext && p == count - 1 -> chapterIndex + 1 to (nextSpread ?: emptyList())
            else -> chapterIndex to spreads[p - lead]
        }

        // 最近停在哪一页的开头（块、字）：同一章重新分页（窗口大小变了）时按它找回所在的页，不按页码。
        val settledAt = remember { arrayOfNulls<Pair<Int, Int>>(1) }
        val startPage = remember(pages, start) {
            val at = settledAt[0]
            when {
                at != null -> pages.indexOfFirst { it.contains(at.first, at.second) }.coerceAtLeast(0)
                start.fromEnd -> pages.lastIndex
                start.block == null -> 0
                else -> pages.indexOfFirst { it.contains(start.block, start.char) }.coerceAtLeast(0)
            }
        }
        // 不用 rememberPagerState：它会在旋转屏幕后恢复旧的页码，而新的排版里那一页已是别的内容。
        // 分页一变就按位置重新定页。
        val pageCount by rememberUpdatedState(count)
        val pager = remember(per, pages) { PagerState(lead + startPage / per) { pageCount } }
        val scope = rememberCoroutineScope()
        // 减少动态效果也要覆盖手势翻页；只让点按/按键直接跳页还不够。
        val visibleMode = if (Effects.noMotion) PageMode.NONE else mode

        LaunchedEffect(chapterIndex, content, paginator, widthPx, heightPx) {
            if ((!hasNext || next != null) && (!hasPrev || prev != null)) return@LaunchedEffect
            // 先让这一章画出来（打开书的放大动画就在这几帧），再趁书页停着时分；往后翻的多，先分下一章。
            repeat(2) { withFrameNanos { } }
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            if (next == null) next = withContext(Dispatchers.Default) { pagesOf(chapterIndex + 1) { ensureActive() } }
            withFrameNanos { }
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            if (prev == null) prev = withContext(Dispatchers.Default) { pagesOf(chapterIndex - 1) { ensureActive() } }
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
        if (mode == PageMode.NONE && !Effects.noMotion) {
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

        // 双页仿真：预先画好下一面，翻起的右页背面才能印出它的左页。
        // Pager 的离屏页虽然已排版，未必会进入绘制阶段；等手势开始后再拍往往拿到 0×0 图层。
        val curlSpreads = twoUp && visibleMode == PageMode.CURL
        var backside by remember(pager) { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
        var priorBackside by remember(pager) { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }

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
                        settledAt[0] = pages[i].firstBlock to pages[i].firstChar
                        onPosition(pages[i].firstBlock, pages[i].firstChar, (last + 1f) / pages.size, label)
                    }
                }
            }
        }

        val prewarmTarget = (pager.settledPage + 1).takeIf { curlSpreads && it < count }
        val prewarmLayer = rememberGraphicsLayer()
        if (prewarmTarget != null) {
            val (prewarmChapter, prewarmSpread) = spreadAt(prewarmTarget)
            val drawn = remember(prewarmTarget, prewarmChapter, prewarmSpread, styles.palette, marks) { Channel<Unit>(Channel.CONFLATED) }
            // 下一面始终在当前纸页下面，既供折角露出，也在真正开始翻页前录好背面。
            Box(
                Modifier.fillMaxSize().bookPage(inset).clearAndSetSemantics { }
                    .drawWithContent {
                        val spineHalfWidth = 28.dp.toPx()
                        prewarmLayer.record {
                            this@drawWithContent.drawContent()
                            drawSpreadSpine(spineHalfWidth)
                        }
                        drawLayer(prewarmLayer)
                        drawn.trySend(Unit)
                    }
                    .background(styles.palette.background),
            ) {
                SpreadContent(
                    content, prewarmChapter, prewarmSpread, per, styles, marks, topInset, imageMaxHeight,
                    onTap = {}, selector = null, speaking = null, selected = null, onFootnote = {},
                )
            }
            LaunchedEffect(prewarmTarget, drawn, prewarmLayer) {
                // 等图层真正画过再转图片；只等固定几帧会在尚未首绘时拍到 0×0。
                drawn.receive()
                repeat(4) {
                    val image = runCatching { prewarmLayer.toImageBitmap() }.getOrNull()
                    if (image != null) {
                        backside = (prewarmTarget - 1) to image
                        return@LaunchedEffect
                    }
                    withFrameNanos { }
                }
            }
        }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .clipToBook(inset)
                .then(if (mode == PageMode.NONE && !Effects.noMotion) Modifier.dragHint(pager, styles.palette.accent) else Modifier),
        ) { p ->
            val (pageChapter, spread) = spreadAt(p)
            fun offset() = p - (pager.currentPage + pager.currentPageOffsetFraction)
            // 当前页本来就会绘制，顺手录下它，供返回上一面时作为翻起纸张的背面。
            // 这样连续返回多次也不依赖更早翻页时留下的位图。
            val captureCurrent = curlSpreads && p == pager.settledPage && p > 0
            val currentLayer = if (captureCurrent) rememberGraphicsLayer() else null
            val currentDrawn = if (captureCurrent) remember(p, pageChapter, spread, styles.palette, marks) { Channel<Unit>(Channel.CONFLATED) } else null
            if (currentLayer != null && currentDrawn != null) {
                LaunchedEffect(p, currentDrawn, currentLayer) {
                    currentDrawn.receive()
                    repeat(4) {
                        val image = runCatching { currentLayer.toImageBitmap() }.getOrNull()
                        if (image != null) {
                            priorBackside = (p - 1) to image
                            return@LaunchedEffect
                        }
                        withFrameNanos { }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("reader-page-$p")
                    // 覆盖式：旧页向左滑走，下一页留在原位；返回时上一页从左侧滑入。
                    .zIndex(-p.toFloat())
                    .graphicsLayer {
                        val r = offset()
                        when (visibleMode) {
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
                        if (visibleMode == PageMode.CURL) {
                            Modifier.pageCurl(
                                progress = { offset().let { if (it < 0f && it > -1f) -it else 0f } },
                                touch = touch,
                                paper = styles.palette.background,
                                spine = if (twoUp) 0.5f else 0f,
                                backside = { (backside?.takeIf { it.first == p } ?: priorBackside?.takeIf { it.first == p })?.second },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .drawWithContent {
                        drawContent()
                        if (twoUp) drawSpreadSpine()
                        if (visibleMode == PageMode.COVER) {
                            val r = offset()
                            if (r > 0f && r < 1f) {
                                // 旧页的右边缘在下一页上的 x。阴影画在静止的下页里，
                                // 避免旧页画出边界的阴影被 graphicsLayer 裁掉，翻到一半突然消失。
                                // Pager 按整屏宽度滑动，bookPage 却将纸面缩去书口厚度。
                                // 用缩后的宽度算接缝会把阴影错开 (1-r)×书口厚度，露出一条亮缝。
                                val insetPx = inset?.total?.roundToPx()?.toFloat() ?: 0f
                                val edge = r * (size.width + insetPx) - insetPx
                                val alpha = 0.18f * 4f * r * (1f - r)
                                val reach = 24.dp.toPx()
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = alpha), Color.Transparent), edge - 1f, edge + reach),
                                    topLeft = Offset(edge - 1f, 0f),
                                    size = Size(reach + 1f, size.height),
                                )
                            }
                        }
                    }
                    .then(if (currentLayer != null && currentDrawn != null) {
                        Modifier.drawWithContent {
                            currentLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(currentLayer)
                            currentDrawn.trySend(Unit)
                        }
                    } else Modifier)
                    .background(styles.palette.background)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x / size.width) }) },
            ) {
                SpreadContent(
                    content, pageChapter, spread, per, styles, marks, topInset, imageMaxHeight, onTap,
                    // 只让停在屏幕上的页参与选字，避免摸到压在下面的字。
                    selector.takeIf { pageChapter == chapterIndex && p == pager.settledPage },
                    speaking.takeIf { pageChapter == chapterIndex },
                    selected.takeIf { pageChapter == chapterIndex },
                    onFootnote,
                )
            }
        }
    }
}

private fun DrawScope.drawSpreadSpine(halfWidth: Float = 28.dp.toPx()) {
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f), Color.Transparent),
            size.width / 2 - halfWidth, size.width / 2 + halfWidth,
        ),
        topLeft = Offset(size.width / 2 - halfWidth, 0f),
        size = Size(halfWidth * 2, size.height),
    )
}

@Composable
private fun SpreadContent(
    content: BookContent,
    pageChapter: Int,
    spread: List<Page>,
    per: Int,
    styles: ReaderStyles,
    marks: Marks,
    topInset: Dp,
    imageMaxHeight: Dp,
    onTap: (Float) -> Unit,
    selector: TextSelector?,
    speaking: Utterance?,
    selected: Highlight?,
    onFootnote: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val chapter = content.chapters[pageChapter]
        val bookmarked = marks.bookmarks.any { bm ->
            bm.chapterIndex == pageChapter && !bm.lost && spread.any { page ->
                val block = bm.itemIndex - HEADER_ITEMS
                page.contains(block, pageLayoutChar(chapter, block, bm.charOffset, styles.settings.indent))
            }
        }
        if (bookmarked) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = styles.horizontalPadding).size(14.dp, 36.dp)
                    .background(styles.palette.accent, RibbonShape)
                    .semantics { contentDescription = "本页有书签" },
            )
        }
        if (topInset > PAGE_HEADER && spread.none { PageItem.Title in it.items }) {
            Text(
                chapter.title, color = styles.palette.secondary, fontSize = 12.sp, letterSpacing = 0.12.em,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(
                    start = styles.horizontalPadding, end = styles.horizontalPadding, top = topInset - PAGE_HEADER,
                ).clearAndSetSemantics { },
            )
        }
        CompositionLocalProvider(LocalImageMaxHeight provides imageMaxHeight) {
            Row(Modifier.fillMaxSize()) {
                spread.forEach { page ->
                    Box(Modifier.weight(1f)) {
                        PageContent(chapter, pageChapter, page, styles, marks, topInset, onTap, selector, speaking, selected, onFootnote)
                    }
                }
                if (spread.size < per) Box(Modifier.weight(1f))
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
