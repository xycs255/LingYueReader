package com.xycs.lingyuereader.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** 用仓库里真实的内置数据（src/main/assets/dict）测试。 */
class BuiltinTest {
    companion object {
        lateinit var cedict: Cedict
        lateinit var unihan: Unihan

        @BeforeClass
        @JvmStatic
        fun load() {
            val assets = File("src/main/assets/dict")
            val tsv = File.createTempFile("cedict", ".tsv").apply { deleteOnExit() }
            extract(GZIPInputStream(File(assets, "cedict.tsv.gz").inputStream()), tsv)
            cedict = Cedict(tsv)
            unihan = Unihan(GZIPInputStream(File(assets, "unihan.tsv.gz").inputStream()))
        }
    }

    @Test
    fun wordLookup() {
        assertTrue(cedict.size > 100_000)
        val hello = cedict.lookup("你好").single()
        assertEquals("nǐ hǎo", hello.pinyin)
        assertTrue(hello.definitions.any { "hello" in it })
        assertEquals("銀行", cedict.lookup("银行").first().traditional)
        assertTrue(cedict.lookup("不存在的词语啊").isEmpty())
        assertTrue(cedict.withPrefix("银行", 5).all { it.startsWith("银行") && it != "银行" })
    }

    @Test
    fun charLookupIncludesAllReadingsAndVariants() {
        val xing = unihan["行"]!!
        assertEquals("xíng", xing.readings.first())
        assertTrue("háng" in xing.readings)
        assertEquals(6, xing.totalStrokes)
        val fa = unihan["发"]!!
        assertEquals("又", fa.radical)
        assertTrue("發" in fa.traditional && "髮" in fa.traditional)
        assertNull(unihan["a"])
    }

    @Test
    fun pinyinToneMarks() {
        assertEquals("nǐ hǎo", Pinyin.marks("ni3 hao3"))
        assertEquals("lǜ", Pinyin.marks("lu:4"))
        assertEquals("zhōu", Pinyin.marks("zhou1"))
        assertEquals("guì", Pinyin.marks("gui4"))
        assertEquals("Běi jīng", Pinyin.marks("Bei3 jing1"))
        assertEquals("de", Pinyin.marks("de5"))
    }

    @Test
    fun wordAtPicksTheLongestDictionaryWordCoveringThePosition() {
        val text = "他走进银行取钱，说Hello world。"
        fun word(at: Char) = WordFinder.wordAt(text, text.indexOf(at), cedict::contains)?.let { text.substring(it) }
        assertEquals("银行", word('行'))
        assertEquals("银行", word('银'))
        assertEquals("Hello", word('e'))
        assertNull(WordFinder.wordAt(text, text.indexOf('，'), cedict::contains))
        // 词典里成词的取整词（“取钱”是词），后面跟英文的“说”只取单字
        assertEquals("取钱", word('钱'))
        assertEquals("说", word('说'))
        assertFalse(cedict.contains("说H"))
    }
}
