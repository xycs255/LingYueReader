package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val widthPx = with(density) { (maxWidth - styles.horizontalPadding * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - topInset - bottomInset).roundToPx() }
        val imageMaxHeight = maxHeight - topInset - bottomInset - styles.gap
        val images = LocalBookImages.current
        val paginator = remember(styles.settings, density, images) {
            Paginator(ComposeTextFitter(measurer), styles, density) { src -> images?.aspect(src) }
        }

        fun pagesOf(index: Int): List<Page>? = content.chapters.getOrNull(index)?.let { paginator.paginate(it, widthPx, heightPx) }

        val chapter = content.chapters[chapterIndex]
        val pages = remember(chapter, paginator, widthPx, heightPx) { pagesOf(chapterIndex)!! }
        val prev = remember(chapterIndex, content, paginator, widthPx, heightPx) { pagesOf(chapterIndex - 1) }
        val next = remember(chapterIndex, content, paginator, widthPx, heightPx) { pagesOf(chapterIndex + 1) }
        // 两端各多放一页，显示相邻章节的末页/首页；翻到那里就切换章节，看起来是连着翻过去的。
        val lead = if (prev != null) 1 else 0
        val count = lead + pages.size + if (next != null) 1 else 0

        val startPage = remember(pages, start) {
            when {
                start.fromEnd -> pages.lastIndex
                start.block == null -> 0
                else -> pages.indexOfFirst { it.contains(start.block, start.char) }.coerceAtLeast(0)
            }
        }
        val pager = rememberPagerState(initialPage = lead + startPage) { count }
        val scope = rememberCoroutineScope()

        DisposableEffect(pager) {
            registerTurner { forward ->
                scope.launch { pager.animateScrollToPage((pager.currentPage + if (forward) 1 else -1).coerceIn(0, count - 1)) }
            }
            onDispose { }
        }

        // 听书：读到下一页时自动翻过去。
        LaunchedEffect(speaking, pages) {
            val u = speaking ?: return@LaunchedEffect
            val indent = indentOf(chapter.blocks[u.block], styles.settings.indent)
            val target = pages.indexOfFirst { it.contains(u.block, u.start + indent) }
            if (target >= 0 && pager.currentPage != lead + target && !pager.isScrollInProgress) pager.animateScrollToPage(lead + target)
        }

        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.settledPage }.collect { p ->
                when {
                    prev != null && p == 0 -> onChapterChange(chapterIndex - 1, true)
                    next != null && p == count - 1 -> onChapterChange(chapterIndex + 1, false)
                    else -> {
                        val i = (p - lead).coerceIn(pages.indices)
                        onPosition(pages[i].firstBlock, pages[i].firstChar, (i + 1f) / pages.size, "${i + 1}/${pages.size} 页")
                    }
                }
            }
        }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            val (pageChapter, page) = when {
                prev != null && p == 0 -> chapterIndex - 1 to prev.last()
                next != null && p == count - 1 -> chapterIndex + 1 to next.first()
                else -> chapterIndex to pages[p - lead]
            }
            Box(
                Modifier
                    .fillMaxSize()
                    // 覆盖式：后一页原地不动，当前页像纸一样盖在上面滑走。
                    .zIndex(-p.toFloat())
                    .graphicsLayer {
                        if (mode == PageMode.COVER) {
                            val r = p - (pager.currentPage + pager.currentPageOffsetFraction)
                            if (r > 0f) translationX = -r * size.width
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (mode == PageMode.COVER) {
                            val r = p - (pager.currentPage + pager.currentPageOffsetFraction)
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
                    PageContent(
                        content.chapters[pageChapter], pageChapter, page, styles, marks, topInset, onTap,
                        selector.takeIf { pageChapter == chapterIndex },
                        speaking.takeIf { pageChapter == chapterIndex },
                        selected.takeIf { pageChapter == chapterIndex },
                        onFootnote,
                    )
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
                    selected = selected?.takeIf { it.block == item.block }?.range,
                    handles = selected?.takeIf { it.block == item.block }?.handles == true,
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
                    selected = selected?.takeIf { it.block == item.block }?.range,
                    handles = selected?.takeIf { it.block == item.block }?.handles == true,
                    onTap = onTap,
                    blockIndex = item.block,
                    selector = selector,
                    onFootnote = onFootnote,
                )
            }
        }
    }
}
