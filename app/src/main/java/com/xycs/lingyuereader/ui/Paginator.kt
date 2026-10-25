package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.InlineText

/** 一页上的一项。 */
internal sealed interface PageItem {
    data object Title : PageItem

    /**
     * 某块文字的一段：[start] 到 [end]（不含）是显示文字（含段首缩进）里的下标。
     * 表格按行切，这时 [start] 到 [end]（不含）是行号。
     */
    data class Slice(val block: Int, val start: Int, val end: Int, val isStart: Boolean, val isEnd: Boolean) : PageItem

    /** 不拆分的块：插图、分隔线等，以及放得进一页、不必拆开的标题。 */
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
 * 按实际排版把一章切成若干页。正文段落、引用、列表按行切开，跨页时下一页从断开的那一行接着排；
 * 标题放得进一页时整块放置，放不下就挪到下一页，比一页还高才按行切；表格按行切；插图、分隔线整块放置。
 */
internal class Paginator(
    private val fitter: TextFitter,
    private val styles: ReaderStyles,
    private val density: Density,
    /** 插图的高宽比；不知道时按 3:4 估计。 */
    private val imageAspect: (String) -> Float? = { null },
) {
    /** [checkpoint] 每排一块调用一次：后台分页被取消时由它抛出取消异常，及早停下。 */
    fun paginate(chapter: Chapter, widthPx: Int, heightPx: Int, checkpoint: () -> Unit = {}): List<Page> {
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

        val titleHeight = chapterOpenerHeight(chapter.title, styles, fitter, widthPx, density.density)
        place(PageItem.Title, titleHeight)

        /**
         * 按行切开一块文字：[before] 是块上方的留白（只在第一段前），[after] 是块下方的留白（只在最后一段后）。
         * [keepTogether] 为真时，整块放得进一页就不拆开，当前页放不下整块就挪到下一页（标题不要半截留在页底）。
         */
        fun lines(index: Int, text: AnnotatedString, style: TextStyle, width: Int, before: Float, after: Float, keepTogether: Boolean = false) {
            val lines = fitter.lines(text, style, width)
            if (lines.isEmpty()) return
            if (keepTogether) {
                val whole = before + lines.last().bottom - lines.first().top + after
                if (whole <= avail && used > 0 && used + whole > avail) newPage()
            }
            var from = 0
            while (from < lines.size) {
                val top = lines[from].top
                val lead = if (from == 0) before else 0f
                val room = avail - used - lead
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
                used += lead + lines[last].bottom - top + if (isEnd) after else 0f
                from = last + 1
                if (!isEnd) newPage()
            }
        }

        val gap = px(styles.gap.value)
        chapter.blocks.forEachIndexed { index, block ->
            checkpoint()
            when (block) {
                is Block.Para -> {
                    val text = blockText(block, emptyList(), indent = styles.settings.indent) ?: return@forEachIndexed
                    lines(index, text, styles.para, widthPx, 0f, gap)
                }
                // 标题、引用、列表和段落一样按行切；留白、可用宽度与 BlockView 里一致。
                is Block.Heading -> lines(index, blockText(block, emptyList())!!, styles.heading(block.level), widthPx, gap * 1.5f, gap, keepTogether = true)
                is Block.Quote -> lines(index, blockText(block, emptyList())!!, styles.quote, widthPx - px(styles.quoteIndent.value).toInt(), 0f, gap)
                is Block.ListItem -> lines(
                    index,
                    blockText(block, emptyList())!!,
                    styles.list,
                    widthPx - px(block.indent * 16f + styles.listMarkerWidth.value).toInt(),
                    0f,
                    gap / 2,
                )
                is Block.Table -> {
                    // 表格按行切：每行的高度按这一行最高的格子算，这一页放不下的行挪到下一页接着排。
                    val rowHeights = block.rows.mapIndexed { r, row -> tableRowHeight(row, r == 0, widthPx) }
                    var from = 0
                    while (from < rowHeights.size) {
                        var last = from - 1
                        var h = 0f
                        while (last + 1 < rowHeights.size && used + h + rowHeights[last + 1] <= avail) h += rowHeights[++last]
                        if (last < from) {
                            if (used == 0f) h = rowHeights[++last] else {
                                newPage()
                                continue
                            }
                        }
                        val isEnd = last == rowHeights.lastIndex
                        items += PageItem.Slice(index, from, last + 1, from == 0, isEnd)
                        used += h + if (isEnd) gap else 0f
                        from = last + 1
                        if (!isEnd) newPage()
                    }
                }
                is Block.Image -> {
                    val h = minOf(widthPx * (imageAspect(block.src) ?: 0.75f), avail - gap)
                    place(PageItem.Whole(index), h + gap)
                }
                else -> place(PageItem.Whole(index), wholeHeight(block, widthPx, gap))
            }
        }
        newPage()
        return pages.ifEmpty { listOf(Page(listOf(PageItem.Title))) }
    }

    private fun wholeHeight(block: Block, widthPx: Int, gap: Float): Float = when (block) {
        Block.Rule -> gap * 2 + px(1f)
        Block.SceneBreak -> px(styles.settings.fontSize * 1.6f) + fitter.height(AnnotatedString("＊"), styles.sceneBreak, widthPx)
        else -> 0f
    }

    /** 表格一行的高度：最高的那格（表格可横向滚动，格子最宽 260dp、四周留 8dp），加一道 1dp 的分隔线。 */
    private fun tableRowHeight(row: List<String>, header: Boolean, widthPx: Int): Float {
        val style = if (header) styles.table.copy(fontWeight = FontWeight.Bold) else styles.table
        val oneLine = fitter.height(AnnotatedString("表"), style, widthPx)
        val tallest = row.maxOfOrNull { cell -> fitter.height(AnnotatedString(InlineText.parse(cell).text), style, px(260f - 16f).toInt()) } ?: 0f
        return maxOf(oneLine, tallest) + px(17f)
    }

    private fun px(dp: Float): Float = dp * density.density
}

/**
 * 分好的页，按章留着。翻进下一章时，阅读区按新的位置重建，这时新的本章、上一章都已分过，只要再分新的下一章，
 * 换章那一下不必一口气分三章。[key] 里是影响分页的一切（排版设置、字体、页面大小……），变了就全部作废。
 *
 * 分页在后台线程做，界面线程只 [peek]：分页本身不持锁（不然界面线程要等整章分完），
 * 分好的几章放在一份只读快照里，[peek] 直接读快照，不等锁。竖排的排版结果也用它缓存。
 */
internal open class ChapterCache<T : Any>(private val keep: Int = 5) {
    private class Snapshot<T>(val key: Any?, val chapters: Map<Int, T>)

    @Volatile
    private var snapshot = Snapshot<T>(null, emptyMap())

    /** 已经分好的就给，没分过的不分；不加锁。 */
    fun peek(key: Any, chapter: Int): T? = snapshot.let { if (it.key == key) it.chapters[chapter] else null }

    /** 没分过就在调用线程上分（[paginate] 在锁外做，可以被取消），分好后放进缓存。 */
    fun get(key: Any, chapter: Int, paginate: () -> T?): T? {
        synchronized(this) {
            if (snapshot.key != key) snapshot = Snapshot(key, emptyMap())
            snapshot.chapters[chapter]?.let { return it }
        }
        val result = paginate() ?: return null
        synchronized(this) {
            val now = snapshot
            // 分页期间排版又变了：这份已经过时，给调用方用，但不放进缓存。
            if (now.key != key) return result
            // 别的线程同时分好了同一章：用先放进去的那份，前后拿到的是同一个对象。
            now.chapters[chapter]?.let { return it }
            val chapters = LinkedHashMap(now.chapters)
            chapters[chapter] = result
            // 只留离这一章最近的几章。
            while (chapters.size > keep) chapters.remove(chapters.keys.maxBy { kotlin.math.abs(it - chapter) })
            snapshot = Snapshot(key, chapters)
        }
        return result
    }
}

/** 翻页模式分好的页。 */
internal class PageCache(keep: Int = 5) : ChapterCache<List<Page>>(keep)
