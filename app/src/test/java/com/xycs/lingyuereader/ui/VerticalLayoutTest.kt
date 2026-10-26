package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class VerticalLayoutTest {
    private fun texts(column: List<VerticalLayout.Cell>) = column.joinToString("") { it.text.ifEmpty { "□" } }

    @Test
    fun columnsRunTopToBottomWithVerticalPunctuationAndIndent() {
        val chapter = Chapter("序", listOf(Block.Para("天下，“好”。"), Block.Para("AB十")))
        val pages = VerticalLayout(rows = 6, columns = 10, indent = true).layout(chapter)
        val columns = pages.single().columns
        // 章名、空一列、正文（段首空两格）。
        assertEquals("序", texts(columns[0]))
        assertTrue(columns[1].isEmpty())
        assertEquals("□□天下︐﹁", texts(columns[2]))
        assertEquals("好﹂︒", texts(columns[3]))
        // 每段另起一列；字母横躺着。
        assertEquals("□□AB十", texts(columns[4]))
        assertEquals(VerticalLayout.Kind.ROTATED, columns[4][2].kind)
        assertEquals(VerticalLayout.Kind.TEXT, columns[4][3].kind)
        // 记下每个字在块里的位置（缩进不算）。
        assertEquals(0 to 0, columns[2][2].block to columns[2][2].char)
        assertEquals(-1, columns[2][0].char)
        assertTrue(pages.single().spokenText().contains("天下，"))
    }

    @Test
    fun punctuationHangsInsteadOfStartingAColumnAndPagesBreak() {
        val chapter = Chapter("章", listOf(Block.Para("一二三四。五六七八九十", prose = false)))
        val pages = VerticalLayout(rows = 4, columns = 3, indent = true).layout(chapter)
        // 第一页：章名、空列、“一二三四。”（句号挂在列尾，不放到下一列开头）。
        assertEquals(listOf("章", "", "一二三四︒"), pages[0].columns.map(::texts))
        assertEquals(listOf("五六七八", "九十"), pages[1].columns.map(::texts))
        assertEquals(0, pages[1].firstBlock)
        assertEquals(5, pages[1].firstChar)
        assertTrue(pages[1].contains(0, 9))
    }

    @Test
    fun imagesGetAPageOfTheirOwn() {
        val chapter = Chapter("图", listOf(Block.Para("前"), Block.Image("img/1.png", "山间小路"), Block.Para("后")))
        val pages = VerticalLayout(rows = 4, columns = 6, indent = false).layout(chapter)
        assertEquals(3, pages.size)
        assertEquals("img/1.png", pages[1].image)
        assertEquals(1, pages[1].firstBlock)
        assertEquals("插图：山间小路", pages[1].spokenText((chapter.blocks[1] as Block.Image).alt))
        assertEquals("后", texts(pages[2].columns[0]))
        assertTrue(pages[2].spokenText().contains("后"))
    }

    @Test
    fun latinRunsShareCellsAndShortNumbersStandUpright() {
        val chapter = Chapter("", listOf(Block.Para("第 12 回 Hello World 2026 年", prose = false)))
        val cells = VerticalLayout(rows = 40, columns = 4, indent = false).layout(chapter).single().columns.flatten()
        // 紧挨中文的空格去掉；两位数字纵中横；英文两字一格横躺，单词间的空格留着。
        assertEquals(listOf("第", "12", "回", "He", "ll", "o ", "Wo", "rl", "d ", "20", "26", "年"), cells.map { it.text })
        assertEquals(VerticalLayout.Kind.TEXT, cells[1].kind)
        assertEquals(VerticalLayout.Kind.ROTATED, cells[9].kind)
        assertEquals(2, cells[1].char)
        assertEquals(3, cells[1].last)
        val page = VerticalLayout(rows = 40, columns = 4, indent = false).layout(chapter).single()
        assertTrue(page.contains(0, 7))
    }

    @Test
    fun aPageOfOnlyTableRowsStillKnowsWhichBlockItIs() {
        val chapter = com.xycs.lingyuereader.data.Chapter("第一章", listOf(
            com.xycs.lingyuereader.data.Block.Para("甲"),
            com.xycs.lingyuereader.data.Block.Table(List(8) { listOf("行$it", "值$it") }),
        ))
        val pages = VerticalLayout(rows = 6, columns = 2, indent = false).layout(chapter)
        // 后面几页全是表格的行：进度要记在表格那一块，不能记成第 0 块。
        assertEquals(1, pages.last().firstBlock)
    }

    @Test
    fun cancelledLongTextStopsWithoutCachingPartialPages() {
        for ((text, limit) in listOf("字".repeat(5_000) to 5, "A".repeat(5_000) to 24)) {
            val chapter = Chapter("章", listOf(Block.Para(text, prose = false)))
            val layout = VerticalLayout(rows = 40, columns = 4, indent = false)
            val cache = ChapterCache<List<VerticalLayout.VPage>>()
            var checks = 0
            val stopped = runCatching {
                cache.get("vertical", 0) {
                    layout.layout(chapter) {
                        if (++checks > limit) throw CancellationException("排版已取消")
                    }
                }
            }
            assertTrue("$checks checkpoints for ${text.first()}", stopped.exceptionOrNull() is CancellationException)
            assertNull("取消后不能留下不完整页", cache.peek("vertical", 0))
            val complete = cache.get("vertical", 0) { layout.layout(chapter) }!!
            assertEquals(0 to text.lastIndex, complete.last().lastPosition())
            assertSame(complete, cache.peek("vertical", 0))
        }
    }
}
