package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VocabularyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun word(w: String, at: Long = 1, context: String = "") = VocabWord(w, "", "释义", context, "b1", "测试书", at)

    @Test
    fun addingTheSameWordAgainCountsLookupsAndKeepsTheNewestContext() {
        val v = Vocabulary(File(tmp.root, "v.json"))
        v.add(word("银行", 1, "旧句子。"))
        v.add(word("门口", 2))
        v.setMastered("银行", true)
        v.add(word("银行", 3, "新句子。"))
        val list = v.list()
        assertEquals(listOf("银行", "门口"), list.map { it.word })
        val bank = list.first()
        assertEquals(2, bank.lookups)
        assertEquals("新句子。", bank.context)
        assertEquals(1L, bank.addedAt)
        // 又查了一次，说明还没掌握。
        assertFalse(bank.mastered)
        v.remove("门口")
        assertEquals(listOf("银行"), v.list().map { it.word })
    }

    @Test
    fun contextIsTheSentenceAroundTheWord() {
        val text = "费介看着他，皱了皱眉。没有等他开口说话，范闲终于没有忍住恶心！他跑了。"
        val at = text.indexOf("恶心")
        assertEquals("没有等他开口说话，范闲终于没有忍住恶心！", Vocabulary.contextOf(text, at..at + 1))
        val quote = "他说：“老师，还有什么要做的？”然后走了。"
        val i = quote.indexOf("老师")
        assertEquals("他说：“老师，还有什么要做的？”", Vocabulary.contextOf(quote, i..i + 1))
        val long = "甲".repeat(200) + "银行" + "乙".repeat(200)
        val c = Vocabulary.contextOf(long, 200..201, maxLength = 40)
        assertTrue(c, c.contains("银行") && c.startsWith("…") && c.endsWith("…"))
    }

    @Test
    fun mergeKeepsTheNewerCopyAndExportsMarkdown() {
        val v = Vocabulary(File(tmp.root, "v.json"))
        v.add(word("银行", 5, "本机的句子。"))
        v.merge("""[{"word":"银行","addedAt":1,"context":"旧备份"},{"word":"排队","addedAt":2,"reading":"pái duì","context":"排着长队。","bookTitle":"测试书"}]""")
        val list = v.list()
        assertEquals(setOf("银行", "排队"), list.map { it.word }.toSet())
        assertEquals("本机的句子。", list.first { it.word == "银行" }.context)
        val md = v.toMarkdown()
        assertTrue(md, md.contains("## 排队（pái duì）") && md.contains("> 排着长队。") && md.contains("——《测试书》"))
    }
}
