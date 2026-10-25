package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {

    /** 假的测量：每行 10 个字、行高 10 像素。 */
    private object FakeFitter : TextFitter {
        override fun lines(text: AnnotatedString, style: TextStyle, widthPx: Int): List<TextFitter.Line> =
            (0 until (text.length + 9) / 10).map { i -> TextFitter.Line(i * 10, minOf(text.length, i * 10 + 10), i * 10f, i * 10f + 10f) }
    }

    private val settings = ReaderSettings(paragraphSpacing = 0f)
    private val styles = ReaderStyles(settings, ReaderTheme.PAPER.palette())
    private val paginator = Paginator(FakeFitter, styles, Density(1f))

    private fun para(n: Int, ch: Char) = Block.Para(ch.toString().repeat(n))

    @Test
    fun everyCharacterLandsOnExactlyOnePageInOrder() {
        val chapter = Chapter("第一章", listOf(para(35, '甲'), para(120, '乙'), Block.SceneBreak, para(8, '丙')))
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = 100)

        // 每个段落的切片首尾相接、覆盖全文（显示文字含两格缩进）。
        listOf(0, 1, 3).forEach { b ->
            val slices = pages.flatMap { it.items }.filterIsInstance<PageItem.Slice>().filter { it.block == b }
            val len = (chapter.blocks[b] as Block.Para).text.length + INDENT.length
            assertEquals(0, slices.first().start)
            assertEquals(len, slices.last().end)
            slices.zipWithNext().forEach { (a, c) -> assertEquals(a.end, c.start) }
            assertTrue(slices.first().isStart && slices.last().isEnd)
        }
        assertTrue(pages.first().items.first() is PageItem.Title)
        assertTrue(pages.flatMap { it.items }.any { it is PageItem.Whole && it.block == 2 })
    }

    @Test
    fun noPageOverflows() {
        val chapter = Chapter("第一章", (1..20).map { para(it * 7, '字') })
        val height = 96
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = height)
        pages.forEach { page ->
            val used = page.items.sumOf { item ->
                when (item) {
                    // 章名：上下边距 56 + 一行 10
                    PageItem.Title -> 66.0
                    is PageItem.Slice -> ((item.end - item.start + 9) / 10 * 10).toDouble()
                    is PageItem.Whole -> 0.0
                }
            }
            assertTrue("page uses $used of $height", used <= height)
        }
    }

    @Test
    fun startPositionsFindTheirPage() {
        val chapter = Chapter("第一章", listOf(para(300, '长')))
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = 50)
        assertTrue(pages.size > 3)
        val third = pages[2]
        val slice = third.items.first() as PageItem.Slice
        assertEquals(2, pages.indexOfFirst { it.contains(slice.block, slice.start) })
        assertEquals(0, pages.indexOfFirst { it.contains(-1, 0) })
        assertEquals(slice.start, third.firstChar)
    }

    @Test
    fun pageOffsetsRoundTripWithoutVisualIndent() {
        val chapter = Chapter("第一章", listOf(Block.Para("中文正文")))
        assertEquals(18, pageLayoutChar(chapter, 0, 16, true))
        assertEquals(16, pageSourceChar(chapter, 0, 18, true))
        assertEquals(16, pageLayoutChar(chapter, 0, 16, false))
        assertEquals(16, pageSourceChar(chapter, 0, 16, false))
    }

    @Test
    fun pageCacheReusesChaptersUntilTheLayoutChanges() {
        val cache = PageCache(keep = 3)
        var runs = 0
        fun pages(key: Any, chapter: Int) = cache.get(key, chapter) {
            runs++
            listOf(Page(listOf(PageItem.Title)))
        }
        // 读第 5 章：分 4、5、6 三章；翻进第 6 章：只多分第 7 章。
        (4..6).forEach { pages("A", it) }
        (5..7).forEach { pages("A", it) }
        assertEquals(4, runs)
        // 只留最近的三章：第 4 章已经丢掉。
        pages("A", 4)
        assertEquals(5, runs)
        // 排版一变全部重分。
        pages("B", 5)
        assertEquals(6, runs)
        // 章节不存在时不缓存。
        assertEquals(null, cache.get("B", 99) { null })
    }

    /** 每块的切片首尾相接、覆盖全文，并且确实拆到了不止一页。 */
    private fun assertCoveredAcrossPages(pages: List<Page>, block: Int, length: Int) {
        val slices = pages.flatMap { it.items }.filterIsInstance<PageItem.Slice>().filter { it.block == block }
        assertTrue("块 $block 应拆成多段：$slices", slices.size > 1)
        assertEquals(0, slices.first().start)
        assertEquals(length, slices.last().end)
        slices.zipWithNext().forEach { (a, c) -> assertEquals(a.end, c.start) }
        assertTrue(slices.first().isStart && slices.last().isEnd)
        assertTrue(slices.drop(1).none { it.isStart } && slices.dropLast(1).none { it.isEnd })
        assertEquals(slices.size, pages.count { page -> page.items.any { it is PageItem.Slice && it.block == block } })
    }

    @Test
    fun tallQuotesListsAndHeadingsAreSplitByLines() {
        val quote = Block.Quote("引".repeat(200))
        val item = Block.ListItem("•", "列".repeat(200))
        val heading = Block.Heading(2, "标".repeat(200))
        val chapter = Chapter("第一章", listOf(quote, item, heading))
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = 100)
        assertCoveredAcrossPages(pages, 0, 200)
        assertCoveredAcrossPages(pages, 1, 200)
        assertCoveredAcrossPages(pages, 2, 200)
        // 每页都放得下（假的测量每行 10 像素）。
        pages.forEach { page ->
            val used = page.items.sumOf { if (it is PageItem.Slice) ((it.end - it.start + 9) / 10 * 10).toDouble() else if (it == PageItem.Title) 66.0 else 0.0 }
            assertTrue("page uses $used", used <= 100)
        }
    }

    @Test
    fun shortHeadingMovesToTheNextPageWhole() {
        // 第一页剩下的地方放不下两行标题：整块挪到下一页，不拆开。
        val chapter = Chapter("第一章", listOf(para(18, '甲'), Block.Heading(2, "标".repeat(15)), para(5, '乙')))
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = 100)
        val headingSlices = pages.flatMap { it.items }.filterIsInstance<PageItem.Slice>().filter { it.block == 1 }
        assertEquals(1, headingSlices.size)
        assertTrue(headingSlices.single().isStart && headingSlices.single().isEnd)
    }

    @Test
    fun tallTablesAreSplitByRows() {
        val rows = (1..30).map { listOf("第${it}行", "值") }
        val chapter = Chapter("第一章", listOf(Block.Table(rows)))
        val pages = paginator.paginate(chapter, widthPx = 100, heightPx = 100)
        val slices = pages.flatMap { it.items }.filterIsInstance<PageItem.Slice>().filter { it.block == 0 }
        assertTrue(slices.size > 1)
        assertEquals(0, slices.first().start)
        assertEquals(30, slices.last().end)
        slices.zipWithNext().forEach { (a, c) -> assertEquals(a.end, c.start) }
        // 每页的行（每行 10 + 17 像素）放得下。
        slices.drop(1).forEach { assertTrue((it.end - it.start) * 27 <= 100) }
    }

    @Test
    fun cancelledPaginationStopsEarly() {
        val chapter = Chapter("第一章", (1..100).map { para(30, '字') })
        var blocks = 0
        val stopped = runCatching {
            paginator.paginate(chapter, widthPx = 100, heightPx = 100) {
                if (++blocks > 3) throw java.util.concurrent.CancellationException("取消")
            }
        }
        assertTrue(stopped.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(4, blocks)
    }

    @Test
    fun peekDoesNotWaitForAPaginationInProgress() {
        val cache = PageCache()
        val ready = listOf(Page(listOf(PageItem.Title)))
        cache.get("A", 1) { ready }
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            cache.get("A", 2) {
                started.countDown()
                release.await()
                ready
            }
        }.apply { start() }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // 第 2 章还在分：界面线程照样立刻拿到第 1 章，别的章也能同时分。
        val peeked = java.util.concurrent.Executors.newSingleThreadExecutor().let { pool ->
            pool.submit<List<Page>?> { cache.get("A", 3) { ready }; cache.peek("A", 1) }.get(2, java.util.concurrent.TimeUnit.SECONDS)
                .also { pool.shutdown() }
        }
        assertEquals(ready, peeked)
        assertEquals(null, cache.peek("A", 2))
        release.countDown()
        worker.join(5_000)
        assertEquals(ready, cache.peek("A", 2))
    }

    @Test
    fun aPaginationFinishedAfterTheLayoutChangedIsNotCached() {
        val cache = PageCache()
        val old = listOf(Page(listOf(PageItem.Title)))
        // 按旧排版分第 1 章的途中，字号变了、按新排版分了第 2 章。
        val result = cache.get("旧", 1) {
            cache.get("新", 2) { listOf(Page(emptyList())) }
            old
        }
        assertEquals(old, result)
        assertEquals(null, cache.peek("旧", 1))
        assertEquals(null, cache.peek("新", 1))
        assertTrue(cache.peek("新", 2) != null)
    }
}

class ChineseDetectionTest {
    @org.junit.Test
    fun detectsChineseBooks() {
        org.junit.Assert.assertTrue(looksChinese(listOf(com.xycs.lingyuereader.data.Block.Para("他说：“老师，还有什么要做的？”"))))
        org.junit.Assert.assertFalse(looksChinese(listOf(com.xycs.lingyuereader.data.Block.Para("“Well,” he said, “what else is there to do?”"))))
        // 夹几个英文词的中文仍算中文。
        org.junit.Assert.assertTrue(looksChinese(listOf(com.xycs.lingyuereader.data.Block.Para("他打开 iPhone 看了看 Wi-Fi 信号，信号很弱。"))))
    }
}
