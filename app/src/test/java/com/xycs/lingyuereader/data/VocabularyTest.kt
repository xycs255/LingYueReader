package com.xycs.lingyuereader.data

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
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

    @Test
    fun reviewsSpreadOutUntilTheWordIsMastered() {
        val v = Vocabulary(File(tmp.root, "srs.json"))
        v.add(VocabWord("萧索", "xiāo suǒ", "bleak", "", "b", "书", 0))
        var now = java.util.Calendar.getInstance().apply { set(2026, 9, 23, 21, 0, 0) }.timeInMillis
        assertTrue(v.list().single().isDue(now))
        val intervals = mutableListOf<Int>()
        repeat(6) {
            v.review("萧索", known = true, now = now)
            val w = v.list().single()
            intervals += w.interval
            assertFalse(w.isDue(now))
            // 到期日是那天零点：第二天一早就能复习。
            assertEquals(Vocabulary.startOfDay(now) + w.interval * Vocabulary.DAY, w.due)
            now = w.due + 8 * 60 * 60 * 1000L
        }
        assertEquals(listOf(1, 3, 8, 20, 50, 125), intervals)
        assertTrue(v.list().single().mastered)

        // 忘了：从头来，马上又到期。
        v.review("萧索", known = false, now = now)
        val w = v.list().single()
        assertEquals(0, w.interval)
        assertFalse(w.mastered)
        assertTrue(w.isDue(now))
    }

    @Test
    fun failedWriteKeepsThePreviousVocabularyBytes() {
        val file = File(tmp.root, "v.json")
        val v = Vocabulary(file)
        v.add(word("银行"))
        val original = file.readBytes()
        // 阻断 AtomicFile 的新文件：写入必须报错，而不能删掉旧生词本。
        val blocker = File(tmp.root, "v.json.new")
        assertTrue(blocker.mkdir())
        File(blocker, "keep").writeText("阻止清理")

        assertTrue(runCatching { v.add(word("门口")) }.exceptionOrNull() is IOException)
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, v.raw())
        assertEquals(listOf("银行"), v.list().map { it.word })

        assertTrue(blocker.deleteRecursively())
        v.add(word("门口"))
        assertEquals(listOf("门口", "银行"), v.list().map { it.word })
    }

    @Test
    @Config(sdk = [26, 28, 29, 36])
    fun readFailureAfterCommitNeverRollsBackVocabulary() {
        val file = File(tmp.root, "v.json")
        Vocabulary(file).add(word("银行"))
        val original = file.readBytes()
        val injected = object : AtomicFile(file) {
            var committed = false
            var failWrites = 0

            override fun finishWrite(str: FileOutputStream?) {
                super.finishWrite(str)
                committed = true
            }

            override fun readFully(): ByteArray {
                if (committed) {
                    committed = false
                    throw IOException("injected readFully after finishWrite")
                }
                return super.readFully()
            }

            override fun failWrite(str: FileOutputStream?) {
                failWrites++
                super.failWrite(str)
            }
        }

        val error = runCatching { Vocabulary(file, injected).add(word("门口")) }.exceptionOrNull()

        assertTrue(error is IOException && error.message == "injected readFully after finishWrite")
        assertEquals(0, injected.failWrites)
        assertTrue(file.isFile)
        assertFalse(original.contentEquals(file.readBytes()))
        assertEquals(listOf("门口", "银行"), Vocabulary(file).list().map { it.word })
    }
}
