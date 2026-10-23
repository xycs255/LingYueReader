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
}
