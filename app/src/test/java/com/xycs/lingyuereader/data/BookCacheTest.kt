package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val longParagraph = "长".repeat(40_000) // UTF-8 超过 64KB，writeUTF 写不下

    private val content = BookContent(
        title = "书",
        author = "作者",
        description = "简介",
        cover = "img/0.jpg",
        chapters = listOf(
            Chapter("第一章 起", listOf(Block.Para("正文"), Block.Para(longParagraph, prose = false), Block.SceneBreak), number = 1),
            Chapter("第二章 承", listOf(
                Block.Heading(2, "小节"), Block.Quote("引文"), Block.ListItem("1.", "条目", 1),
                Block.Table(listOf(listOf("甲", "乙"), listOf("丙", "丁"))), Block.Rule, Block.Image("img/1.png", "图"),
            ), number = 2, part = 3),
        ),
    )

    @Test
    fun roundTripLoadsChaptersOnlyWhenNeeded() {
        val file = tmp.newFile("cache.bin")
        BookCache.write(file, content, "fp")
        val read = BookCache.read(file, "fp")!!
        assertEquals("书", read.title)
        assertEquals("作者", read.author)
        assertEquals("简介", read.description)
        assertEquals("img/0.jpg", read.cover)
        assertEquals(listOf("第一章 起", "第二章 承"), read.chapters.map { it.title })
        assertEquals(listOf(1, 2), read.chapters.map { it.number })
        assertEquals(3, read.chapters[1].part)
        // 字数来自索引，不需要加载正文。
        assertEquals(content.charCount, read.charCount)
        assertFalse(read.chapters[0].isLoaded)
        assertFalse(read.chapters[1].isLoaded)

        assertEquals(content.chapters[1].blocks, read.chapters[1].blocks)
        assertTrue(read.chapters[1].isLoaded)
        assertFalse(read.chapters[0].isLoaded)
        assertEquals(content.chapters[0].blocks, read.chapters[0].blocks)
    }

    @Test
    fun staleOrBrokenCacheIsIgnored() {
        val file = tmp.newFile("cache.bin")
        BookCache.write(file, content, "fp-old")
        assertNull(BookCache.read(file, "fp-new"))
        file.writeText("garbage")
        assertNull(BookCache.read(file, "fp-old"))
        assertNull(BookCache.read(tmp.root.resolve("missing.bin"), "fp"))
    }
}
