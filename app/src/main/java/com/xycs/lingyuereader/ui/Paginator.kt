package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Chapter

/** 一页上的一项。 */
internal sealed interface PageItem {
    data object Title : PageItem

    /** 某块文字的一段：[start] 到 [end]（不含）是显示文字（含段首缩进）里的下标。 */
    data class Slice(val block: Int, val start: Int, val end: Int, val isStart: Boolean, val isEnd: Boolean) : PageItem

    /** 不拆分的块：标题、引用、表格、分隔线等。 */
    data class Whole(val block: Int) : PageItem
}

internal data class Page(val items: List<PageItem>) {
    /** 本页第一项对应的块（章名页记为 -1）与块内起始字。 */
    val firstBlock: Int
        get() = when (val first = items.firstOrNull()) {
            is PageItem.Slice -> first.block
            is PageItem.Whole -> first.block
            else -> -1
        }
    val firstChar: Int get() = (items.firstOrNull() as? PageItem.Slice)?.start ?: 0

    fun contains(block: Int, char: Int): Boolean = items.any {
        when (it) {
            is PageItem.Slice -> it.block == block && char >= it.start && (char < it.end || it.isEnd)
            is PageItem.Whole -> it.block == block
            PageItem.Title -> block < 0
        }
    }
}

/** 分页时需要的文字测量能力；正式实现用 Compose 的 [TextMeasurer]，测试里可以换成假的。 */
internal interface TextFitter {
    data class Line(val start: Int, val end: Int, val top: Float, val bottom: Float)

    fun lines(text: AnnotatedString, style: TextStyle, widthPx: Int): List<Line>

    fun height(text: AnnotatedString, style: TextStyle, widthPx: Int): Float =
        lines(text, style, widthPx).lastOrNull()?.bottom ?: 0f
}

internal class ComposeTextFitter(private val measurer: TextMeasurer) : TextFitter {
    override fun lines(text: AnnotatedString, style: TextStyle, widthPx: Int): List<TextFitter.Line> {
        val r = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)))
        return (0 until r.lineCount).map { i ->
            TextFitter.Line(r.getLineStart(i), r.getLineEnd(i), r.getLineTop(i), r.getLineBottom(i))
        }
    }
}

/**
 * 按实际排版把一章切成若干页。正文段落按行切开，跨页时下一页从断开的那一行接着排；
 * 标题、引用、表格等整块放置，放不下就挪到下一页。
 */
internal class Paginator(
    private val fitter: TextFitter,
    private val styles: ReaderStyles,
    private val density: Density,
) {
    fun paginate(chapter: Chapter, widthPx: Int, heightPx: Int): List<Page> {
        val pages = mutableListOf<Page>()
        var items = mutableListOf<PageItem>()
        var used = 0f
        // 留一点余量，避免实际渲染与测量的细微差别把最后一行挤出屏幕。
        val avail = heightPx - px(4f)

        fun newPage() {
            if (items.isNotEmpty()) pages += Page(items)
            items = mutableListOf()
            used = 0f
        }

        fun place(item: PageItem, height: Float) {
            if (used > 0 && used + height > avail) newPage()
            items += item
            used += height
        }

        val titleHeight = px(styles.titleTop.value) +
            fitter.height(AnnotatedString(chapter.title), styles.title, widthPx) + px(styles.titleBottom.value)
        place(PageItem.Title, titleHeight)

        val gap = px(styles.gap.value)
        chapter.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Para -> {
                    val text = blockText(block, emptyList()) ?: return@forEachIndexed
                    val lines = fitter.lines(text, styles.para, widthPx)
                    var from = 0
                    while (from < lines.size) {
                        val top = lines[from].top
                        val room = avail - used
                        var last = from - 1
                        while (last + 1 < lines.size && lines[last + 1].bottom - top <= room) last++
                        if (last < from) {
                            if (used == 0f) last = from else {
                                newPage()
                                continue
                            }
                        }
                        val isEnd = last == lines.lastIndex
                        items += PageItem.Slice(index, lines[from].start, if (isEnd) text.length else lines[last].end, from == 0, isEnd)
                        used += lines[last].bottom - top + if (isEnd) gap else 0f
                        from = last + 1
                        if (!isEnd) newPage()
                    }
                }
                else -> place(PageItem.Whole(index), wholeHeight(block, widthPx, gap))
            }
        }
        newPage()
        return pages.ifEmpty { listOf(Page(listOf(PageItem.Title))) }
    }

    private fun wholeHeight(block: Block, widthPx: Int, gap: Float): Float = when (block) {
        is Block.Heading -> gap * 2.5f + fitter.height(blockText(block, emptyList())!!, styles.heading(block.level), widthPx)
        is Block.Quote -> gap + fitter.height(blockText(block, emptyList())!!, styles.quote, widthPx - px(styles.quoteIndent.value).toInt())
        is Block.ListItem -> gap / 2 + fitter.height(
            blockText(block, emptyList())!!,
            styles.list,
            widthPx - px(block.indent * 16f + styles.listMarkerWidth.value).toInt(),
        )
        is Block.Table -> gap + block.rows.size * (fitter.height(AnnotatedString("表"), styles.table, widthPx) + px(17f))
        Block.Rule -> gap * 2 + px(1f)
        Block.SceneBreak -> px(styles.settings.fontSize * 1.6f) + fitter.height(AnnotatedString("＊"), styles.sceneBreak, widthPx)
        is Block.Para -> 0f
    }

    private fun px(dp: Float): Float = dp * density.density
}
