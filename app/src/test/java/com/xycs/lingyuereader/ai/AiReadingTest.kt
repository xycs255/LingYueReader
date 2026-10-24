package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.InlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiReadingTest {
    private val content = BookContent(
        "书", null,
        listOf(
            Chapter("第一章", listOf(Block.Para("甲".repeat(9_000)))),
            Chapter("第二章", listOf(Block.Para("乙".repeat(9_000)))),
            Chapter("第三章", listOf(Block.Para(InlineText.withTranslation("丙丙丙", "译文不该发出去")))),
            Chapter("第四章", listOf(Block.Para("丁".repeat(30_000)))),
        ),
    )

    @Test
    fun summariesPackChaptersAndTrimLongOnes() {
        val packs = AiSummaries.packs(content, listOf(3, 0, 1, 2))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), packs.map { it.chapters })
        assertFalse(packs.any { "译文" in it.text })
        assertEquals("统计字数、检索只按原文", "丙丙丙", content.chapters[2].blocks[0].plainText)
        assertTrue(packs[2].text.contains("中间略"))
        assertTrue(packs[2].text.length < AiSummaries.PER_CHAPTER + 200)
        assertTrue(AiSummaries.prompt(packs[1]).contains("【第 2 章 第二章】"))
    }

    @Test
    fun summariesParseOnlyRequestedChapters() {
        val pack = AiSummaries.Pack(listOf(1, 2), "")
        val got = AiSummaries.parse("""```json
            {"summaries":[{"i":2,"s":"乙的故事。"},{"i":3,"s":"丙。"},{"i":1,"s":"不在这次请求里"},{"i":3,"s":""}]}
            ```""", pack)
        assertEquals(mapOf(1 to "乙的故事。", 2 to "丙。"), got)
        val dir = Files.createTempDirectory("s").toFile()
        val file = File(dir, AiSummaries.FILE)
        AiSummaries.write(file, mapOf(1 to ("第二章" to "乙的故事。"), 2 to ("改过名的章" to "丙。")))
        assertEquals("章名对不上的不显示", mapOf(1 to "乙的故事。"), AiSummaries.valid(content, AiSummaries.read(file)))
        dir.deleteRecursively()
    }

    @Test
    fun companionParsesAndRoundTrips() {
        val c = AiCompanion.parse("""好的 {"points":["渔人迷路"," "],"questions":["桃花源为何隔绝？"]}""")!!
        assertEquals(listOf("渔人迷路"), c.points)
        assertEquals(listOf("桃花源为何隔绝？"), c.questions)
        assertNull(AiCompanion.parse("{}"))
        assertTrue(AiCompanion.prompt("第一章", "原文", "前一章的事").contains("前情"))
        val dir = Files.createTempDirectory("c").toFile()
        val file = File(dir, AiCompanion.FILE)
        AiCompanion.write(file, mapOf(0 to ("第一章" to c)))
        assertEquals(c, AiCompanion.read(file)[0]?.second)
        dir.deleteRecursively()
    }
}
