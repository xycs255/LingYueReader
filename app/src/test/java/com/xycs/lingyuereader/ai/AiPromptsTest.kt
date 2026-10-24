package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptsTest {
    private val book = BookContent(
        "测试", "某人",
        listOf(
            Chapter("第一章 来客", listOf(Block.Para("老周推开门，看见院子里站着一个穿蓝布衫的年轻人。"), Block.Para("年轻人说他叫林舟，从江南来找舅舅。"))),
            Chapter("第二章 雨夜", listOf(Block.Para("那天夜里下起了雨。"), Block.Para("林舟把一封旧信交给老周。"), Block.Para("后来才知道，林舟其实是老周的儿子。"))),
        ),
    )

    @Test
    fun recentTextStopsWhereTheReaderIs() {
        val text = AiPrompts.recentText(book, ReadPos(1, 1))
        assertTrue(text.contains("【第一章 来客】"))
        assertTrue(text.contains("旧信"))
        assertFalse(text.contains("儿子"))
        assertTrue(text.indexOf("来客") < text.indexOf("雨夜"))
    }

    @Test
    fun recentTextKeepsTheLatestPartWhenTooLong() {
        val text = AiPrompts.recentText(book, ReadPos(1, 1), limit = 12)
        assertTrue(text.contains("旧信"))
        assertFalse(text.contains("老周推开门"))
    }

    @Test
    fun searchFindsRelevantPassagesOnlyInWhatWasRead() {
        val index = BookIndex.build(book, ReadPos(1, 1))
        val hits = index.search("林舟是从哪里来的？")
        assertTrue(hits.isNotEmpty())
        assertEquals(1, hits.first().index)
        assertTrue(hits.any { it.text.contains("江南") })
        assertTrue(hits.none { it.text.contains("儿子") })
        assertTrue(index.search("儿子").none { it.text.contains("儿子") })
    }

    @Test
    fun singleCharacterQuestionsStillFindSomething() {
        val hits = BookIndex.build(book, ReadPos(1, 2)).search("信")
        assertTrue(hits.any { it.text.contains("旧信") })
    }

    @Test
    fun readsCitationNumbers() {
        assertEquals(listOf(2, 1), AiPrompts.citations("他从江南来[2]，带着信[1][2]。"))
    }

    @Test
    fun selectionPromptCarriesContextAndQuestion() {
        val p = AiPrompts.selection("第一章", "上下文段落", "选文", QuickAsk.DEFAULTS[1].instruction)
        assertTrue(p.contains("上下文段落"))
        assertTrue(p.contains("「选文」"))
        assertTrue(p.endsWith(QuickAsk.DEFAULTS[1].instruction))
        assertTrue(AiPrompts.system("书", "作者").contains("剧透") || AiPrompts.system("书", "作者").contains("没读到"))
    }

    @Test
    fun countingTermsInTextMatchesTheTermTable() {
        // 不存词表以后，段长和词频改成现数，结果要和按词表数的一样。
        val text = "林舟从江南来，带着一封旧信。iPhone 和 iphone、phone 不同；He said hello, HELLO again. 2024年"
        val table = BookIndex.terms(text)
        assertEquals(table.values.sum(), BookIndex.termCount(text))
        for ((term, n) in table) assertEquals(term, n, BookIndex.count(text, term))
        assertEquals(0, BookIndex.count(text, "phon"))
    }
}
