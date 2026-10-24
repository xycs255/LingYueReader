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
            bookmarks = listOf(Bookmark("b", 1, "第二章", 3, 5, "摘录", 9, TextAnchor("附近原文", 2))),
            notes = listOf(note(0, "第一章", 2, "原文", 1).copy(color = MarkColor.BLUE)),
        )
        assertEquals(m, Marks.fromJson(JSONObject(m.toJson().toString())))
    }

    @Test
    fun anchorFollowsTextAfterParagraphInsertion() {
        val original = book("第一章" to listOf("开头。", "田槐走到打谷场，看见门边那盏旧灯仍然亮着。"))
        val char = 18
        val anchor = TextAnchors.capture(original.chapters[0], 1, char)
        val revised = book("第一章" to listOf("开头。", "新加入的段落。", "田槐走到打谷场，看见门边那盏旧灯仍然亮着。"))
        assertEquals(TextLocation(2, char), TextAnchors.resolve(revised.chapters[0], 1, char, anchor))

        val bookmark = Bookmark("b", 0, "第一章", 2, char, "田槐走到打谷场", 1, anchor)
        val moved = Marks(bookmarks = listOf(bookmark)).resolve(revised).bookmarks.single()
        assertEquals(3, moved.itemIndex)
        assertEquals(char, moved.charOffset)
        assertFalse(moved.lost)
    }

    @Test
    fun repeatedAnchorChoosesNearestOldLocation() {
        val chapter = book("第一章" to listOf("重复句。", "别的。", "重复句。", "别的。", "重复句。", "别的。", "重复句。"))
            .chapters.single()
        assertEquals(TextLocation(4, 2), TextAnchors.resolve(chapter, 5, 2, TextAnchor("重复句。", 2)))
    }

    @Test
    fun unmatchedBookmarkIsRetainedAndMarkedLost() {
        val old = book("旧章" to listOf("已经删除的原文。"))
        val anchor = TextAnchors.capture(old.chapters.single(), 0, 4)
        val bookmark = Bookmark("b", 0, "旧章", 1, 4, "已经删除", 1, anchor)
        val revised = book("新章" to listOf("完全不同的正文。"))
        val restored = Marks(bookmarks = listOf(bookmark)).resolve(revised)
        assertEquals("b", restored.bookmarks.single().id)
        assertTrue(restored.bookmarks.single().lost)
        assertEquals(restored, Marks.fromJson(JSONObject(restored.toJson().toString())))
    }

    @Test
    fun oldTraditionalDisplayAnchorRebasesToSimplifiedSourceAndSurvivesRestart() {
        val source = book("第一章 开端" to listOf("她的头发很长，后来又走远了。"))
        val toTraditional = ChineseConverter("开端\t開端\n头发\t頭髮\n后来\t後來\n".byteInputStream())
        val traditional = toTraditional.convert(source)
        val chapter = traditional.chapters.single()
        val old = Bookmark("b", 0, chapter.title, 1, 2, "頭髮", 1, TextAnchors.capture(chapter, 0, 2))

        val rebased = Marks(bookmarks = listOf(old)).resolve(source, listOf(traditional)).bookmarks.single()

        assertFalse(rebased.lost)
        assertEquals("第一章 开端", rebased.chapterTitle)
        assertEquals(2, rebased.charOffset)
        assertTrue(rebased.anchor.text.contains("头发"))
        val reopened = Marks.fromJson(JSONObject(Marks(bookmarks = listOf(rebased)).toJson().toString())).resolve(source)
        assertFalse(reopened.bookmarks.single().lost)

        val deleted = book("第一章 开端" to listOf("她已经搬走，再无原句。"))
        assertTrue(Marks(bookmarks = listOf(old)).resolve(deleted, listOf(toTraditional.convert(deleted))).bookmarks.single().lost)
    }

    @Test
    fun oldSimplifiedDisplayAnchorRebasesToTraditionalSource() {
        val source = book("第一章 開端" to listOf("她的頭髮很長，後來又走遠了。"))
        val toSimplified = ChineseConverter("開端\t开端\n頭髮\t头发\n後來\t后来\n".byteInputStream())
        val simplified = toSimplified.convert(source)
        val chapter = simplified.chapters.single()
        val old = Bookmark("b", 0, chapter.title, 1, 2, "头发", 1, TextAnchors.capture(chapter, 0, 2))

        val rebased = Marks(bookmarks = listOf(old)).resolve(source, listOf(simplified)).bookmarks.single()

        assertFalse(rebased.lost)
        assertEquals("第一章 開端", rebased.chapterTitle)
        assertEquals(2, rebased.charOffset)
        assertTrue(rebased.anchor.text.contains("頭髮"))
    }

    @Test
    fun convertedLegacyProgressRebasesToSourceChapterAndCharacter() {
        val source = book(
            "第一章 开端" to listOf("先走一段。"),
            "第二章 头发" to listOf("长长的开头。", "她的头发很长，后来又走远了。"),
        )
        val toTraditional = ChineseConverter("开端\t開端\n头发\t頭髮\n后来\t後來\n".byteInputStream())
        val traditional = toTraditional.convert(source)
        val chapter = traditional.chapters[1]
        val old = ReadingProgress(1, chapter.title, 2, 0, 2, TextAnchors.capture(chapter, 1, 2))

        val direct = TextAnchors.rebaseProgress(source, old)
        val rebased = TextAnchors.rebaseProgress(source, old, listOf(traditional))

        assertEquals(null, direct)
        assertEquals(1, rebased?.chapterIndex)
        assertEquals("第二章 头发", rebased?.chapterTitle)
        assertEquals(2, rebased?.itemIndex)
        assertEquals(2, rebased?.charOffset)
        assertTrue(rebased?.anchor?.text.orEmpty().contains("头发"))
    }
}
