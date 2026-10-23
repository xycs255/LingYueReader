package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class ChineseConverterTest {
    private fun load(name: String) = GZIPInputStream(File("src/main/assets/dict/$name").inputStream()).use { ChineseConverter(it) }

    private val s2t by lazy { load("s2t.tsv.gz") }
    private val t2s by lazy { load("t2s.tsv.gz") }

    @Test
    fun simplifiedToTraditionalUsesPhrasesFirst() {
        // 逐字转会错成“頭發”“後來”的地方，按词组转对了；“皇后”的“后”不能转成“後”。
        assertEquals("他的頭髮很長，後來皇后也來了。", s2t.convert("他的头发很长，后来皇后也来了。"))
        assertEquals("發展經濟", s2t.convert("发展经济"))
        // 标点、英文、数字、注释标记原样不动。
        val marked = "说" + InlineText.footnote("[1]", "OPS/a.xhtml#n1") + "abc 123"
        assertEquals("說" + InlineText.footnote("[1]", "OPS/a.xhtml#n1") + "abc 123", s2t.convert(marked))
    }

    @Test
    fun traditionalToSimplified() {
        assertEquals("头发很长，万里长城。", t2s.convert("頭髮很長，萬里長城。"))
    }

    @Test
    fun lengthNeverChangesSoMarksStayInPlace() {
        val sample = "范闲终于没有忍住恶心，跑到地垄下面，哇的一声，开始拼命地呕吐了起来。𠀀𪚥"
        assertEquals(sample.length, s2t.convert(sample).length)
        assertEquals(sample.length, t2s.convert(s2t.convert(sample)).length)
    }

    @Test
    fun bookConversionStaysLazy() {
        var loads = 0
        val chapter = Chapter("第一章 头发", null, 0, 4) { loads++; listOf(Block.Para("头发很长")) }
        val book = s2t.convert(BookContent("书", null, listOf(chapter), notes = mapOf("k" to "后来")))
        assertEquals("第一章 頭髮", book.chapters[0].title)
        assertEquals("後來", book.notes["k"])
        assertEquals(0, loads)
        assertFalse(book.chapters[0].isLoaded)
        assertEquals(listOf(Block.Para("頭髮很長")), book.chapters[0].blocks)
        assertEquals(4, book.chapters[0].charCount)
    }
}
