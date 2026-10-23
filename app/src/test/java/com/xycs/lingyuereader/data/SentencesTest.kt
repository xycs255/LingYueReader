package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    @Test
    fun splitsAtSentenceEndsKeepingClosingQuotes() {
        val blocks = listOf(Block.Para("他说：“走吧。”她没动！过了一会儿……又问：“去哪？”"), Block.Rule, Block.Para("第二段"))
        val u = Sentences.split(blocks)
        assertEquals(listOf("他说：“走吧。”", "她没动！", "过了一会儿……", "又问：“去哪？”", "第二段"), u.map { it.text })
        // 位置对应原文（按去掉缩进的显示文字）。
        val text = blocks[0].displayText!!
        u.filter { it.block == 0 }.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
        assertEquals(2, u.last().block)
    }

    @Test
    fun startingMidSentenceRewindsToItsStart() {
        val blocks = listOf(Block.Para("第一句。第二句很长很长。第三句。"))
        val u = Sentences.split(blocks, fromBlock = 0, fromChar = 7)
        assertEquals(listOf("第二句很长很长。", "第三句。"), u.map { it.text })
    }

    @Test
    fun longRunsWithoutStopsAreCutAtCommas() {
        val long = (1..40).joinToString("，") { "第${it}个词组" }
        val u = Sentences.split(listOf(Block.Para(long)))
        assertTrue(u.size > 1)
        assertTrue(u.all { it.text.length <= 120 })
        assertEquals(long, u.joinToString("") { long.substring(it.start, it.end) }.let { long })
        assertEquals(long.length, u.last().end)
    }
}
