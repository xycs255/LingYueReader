package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {

    private fun book(vararg chapters: Pair<String, List<String>>) =
        BookContent(null, null, chapters.map { (t, ps) -> Chapter(t, ps.map { Block.Para(it) }) })

    private fun note(chapter: Int, title: String, block: Int, quote: String, start: Int) =
        Note("n", chapter, title, block, start, start + quote.length, quote, "意见", MarkColor.YELLOW, 0)

    @Test
    fun inlineBoldIsStrippedAndTracked() {
        val p = InlineText.parse("他说**好**，走了")
        assertEquals("他说好，走了", p.text)
        assertEquals(listOf(2..2), p.bold)
        assertEquals("落单**标记", InlineText.parse("落单**标记").text)
    }

    @Test
    fun noteStaysPutWhenTextIsUnchanged() {
        val content = book("第一章 甲" to listOf("田槐是天亮前回来的。", "他蹲下来划了几道。"))
        val m = Marks(notes = listOf(note(0, "第一章 甲", 1, "划了几道", 4))).resolve(content)
        assertFalse(m.notes.single().lost)
        assertEquals(1, m.notes.single().blockIndex)
    }

    @Test
    fun noteFollowsItsQuoteAfterRevision() {
        // 改稿：前面插了一章、本章开头加了一段，原句也挪了位置。
        val content = book(
            "楔子" to listOf("新加的楔子。"),
            "第一章 甲" to listOf("新加的开头。", "田槐是天亮前回来的。", "他先喝了水，又蹲下来划了几道。"),
        )
        val m = Marks(notes = listOf(note(0, "第一章 甲", 1, "划了几道", 4))).resolve(content)
        val n = m.notes.single()
        assertFalse(n.lost)
        assertEquals(1, n.chapterIndex)
        assertEquals(2, n.blockIndex)
        assertEquals("划了几道", content.chapters[1].blocks[2].displayText!!.substring(n.start, n.end))
    }

    @Test
    fun noteIsKeptButMarkedLostWhenQuoteIsGone() {
        val content = book("第一章 甲" to listOf("整段重写了。"))
        val m = Marks(notes = listOf(note(0, "第一章 甲", 1, "划了几道", 4))).resolve(content)
        assertTrue(m.notes.single().lost)
        assertTrue(m.toMarkdown("测试", content).contains("原文已改动的批注"))
    }

    @Test
    fun markdownExportGroupsByChapter() {
        val content = book("第一章 甲" to listOf("田槐是天亮前回来的。"), "第二章 乙" to listOf("他蹲下来划了几道。"))
        val md = Marks(
            notes = listOf(
                note(1, "第二章 乙", 0, "划了几道", 5).copy(id = "b", text = "这里节奏太快"),
                note(0, "第一章 甲", 0, "天亮前", 3).copy(id = "a", text = ""),
            ),
        ).toMarkdown("沐平", content)
        assertEquals(
            "# 《沐平》批注\n\n## 第一章 甲\n\n> 天亮前\n\n## 第二章 乙\n\n> 划了几道\n\n这里节奏太快\n",
            md,
        )
    }

    @Test
    fun jsonRoundTrip() {
        val m = Marks(
            bookmarks = listOf(Bookmark("b", 1, "第二章", 3, 5, "摘录", 9)),
            notes = listOf(note(0, "第一章", 2, "原文", 1).copy(color = MarkColor.BLUE)),
        )
        assertEquals(m, Marks.fromJson(JSONObject(m.toJson().toString())))
    }
}
