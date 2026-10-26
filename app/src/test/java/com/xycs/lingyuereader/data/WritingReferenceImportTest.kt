package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WritingReferenceImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun body(chapter: Chapter): String = chapter.blocks.joinToString("\n") { it.plainText }

    @Test fun exactChapterBoundariesKeepNumbersAndDoNotRepeatHeadings() {
        val book = WritingReferenceImport.parse("《范本》（精校版）作者：合成作者.txt", """
            前言
            这里说明这份合成范本的用途。
            第一卷 河岸
            第一章 门外
            他站在门外，听见里面有人说话。
            第二章 回信
            第一章里提过的那个人，今天没有来。
            纸上只有一行字。
            第一〇二章 归来？
            门又开了。
            第１０３章 灯下
            灯下放着一封信。
            Chapter 104: The Road
            They waited by the road.
        """.trimIndent())
        assertEquals("范本", book.title)
        assertEquals("合成作者", book.author)
        assertEquals(listOf(null, 1, 2, 102, 103, 104), book.chapters.map { it.number })
        assertEquals("前言", book.chapters.first().title)
        assertEquals("他站在门外，听见里面有人说话。", body(book.chapters[1]))
        assertTrue(body(book.chapters[2]).startsWith("第一章里提过的那个人"))
        assertFalse(book.chapters.any { it.title.startsWith("第一卷") })
    }

    @Test fun longActualChapterIsNeverReplacedWithAutomaticReadingSections() {
        val longBody = ("这是一段用于测试长章边界的合成叙述。\n").repeat(9_000)
        val book = WritingReferenceImport.parse("范本.txt", "第一章 长夜\n$longBody\n第二章 天明\n天亮以后，他们离开了。")
        assertEquals(2, book.chapters.size)
        assertEquals(1, book.chapters.first().number)
        assertTrue(book.chapters.first().charCount > 120_000)
        assertEquals(longBody.trim(), body(book.chapters.first()))
        assertFalse(book.chapters.any { "节" in it.title })
    }

    @Test fun unknownBoundaryRemainsExplicitAndDoesNotBecomeFirstChapter() {
        val text = ("这段合成原文没有可识别的章标题。\n").repeat(1_200)
        val book = WritingReferenceImport.parse("无目录.txt", text)
        assertEquals(1, book.chapters.size)
        assertEquals(WritingReferenceImport.UNSPLIT_TITLE, book.chapters.single().title)
        assertNull(book.chapters.single().number)
        assertEquals(text.trim(), body(book.chapters.single()))
    }

    @Test fun tableOfContentsEntriesNeverAppearAsChapterBody() {
        val book = WritingReferenceImport.parse("范本.txt", """
            目录
            第一章 开门 ........ 1
            第二章 等候 ........ 9
            正文
            第一章 开门
            门口放着一只旧木箱。
            第二章 等候
            他们等到天黑才听见脚步。
        """.trimIndent())
        assertEquals(listOf(1, 2), book.chapters.map { it.number })
        assertEquals(listOf("门口放着一只旧木箱。", "他们等到天黑才听见脚步。"), book.chapters.map(::body))
        assertFalse(book.chapters.any { body(it).contains("目录") || body(it).contains("........") })
    }

    @Test fun contentsOnlyCannotBeImportedAsAProseChapter() {
        val error = runCatching {
            WritingReferenceImport.parse("范本.txt", "合成范本\n作者说明\n目录\n第一章 开门\n第二章 等候")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("目录"))
    }

    @Test fun byteImportsReuseChineseEncodingDetectionAndFileImportsMatch() {
        val text = "第一章 旧信\n信纸是白的，墨迹还没有干。\n第二章 远路\n他把信收进衣内。"
        val gb = WritingReferenceImport.parse("范本.txt", text.toByteArray(Charset.forName("GB18030")))
        val utf16 = WritingReferenceImport.parse("范本.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))
        assertEquals(listOf(1, 2), gb.chapters.map { it.number })
        assertEquals(gb.chapters.map(::body), utf16.chapters.map(::body))
        val file = tmp.newFile("source.txt").apply { writeBytes(text.toByteArray(Charsets.UTF_8)) }
        assertEquals(gb.chapters.map(::body), WritingReferenceImport.parse("范本.txt", file).chapters.map(::body))
    }

    @Test fun epubFollowsSpineAndSplitsMultipleHighLevelChapterHeadingsInsideOneFile() {
        val epub = epub(
            "a.xhtml" to "<html><body><h1>第二章 后到</h1><p>后到的人推开门。</p></body></html>",
            "b.xhtml" to "<html><body><h1>第一章 先到</h1><p>先到的人正在等。</p><h3>第一节 灯下</h3><p>灯下没有别人。</p><h2>第三章 远行</h2><p>第二天他们出发。</p></body></html>",
            order = listOf("b.xhtml", "a.xhtml"),
        )
        val book = WritingReferenceImport.parse("合成.epub", epub)
        assertEquals("合成范本", book.title)
        assertEquals(listOf(1, 3, 2), book.chapters.map { it.number })
        assertEquals("第一章 先到", book.chapters.first().title)
        assertTrue(body(book.chapters.first()).contains("第一节 灯下"))
        assertFalse(body(book.chapters.first()).contains("第三章"))
        assertEquals("第二天他们出发。", body(book.chapters[1]))
        assertEquals("后到的人推开门。", body(book.chapters[2]))
    }

    @Test fun epubContentsPageIsNotAReferenceChapterAndMissingProseIsNotInvented() {
        val book = WritingReferenceImport.parse("范本.epub", epub(
            "toc.xhtml" to "<html><body><h1>目录</h1><p><a href=\"a.xhtml\">第一章 门前</a></p></body></html>",
            "a.xhtml" to "<html><body><h1>第一章 门前</h1><p>这里是一段独立的合成正文。</p></body></html>",
        ))
        assertEquals(listOf(1), book.chapters.map { it.number })
        assertEquals("这里是一段独立的合成正文。", body(book.chapters.single()))
        val missing = epub("a.xhtml" to "<html><body><h1>第一章</h1><p>正文。</p></body></html>", order = listOf("a.xhtml", "lost.xhtml"))
        assertTrue(runCatching { WritingReferenceImport.parse("缺失.epub", missing) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun sourceLimitsAreAvailableBeforeReadingUnsupportedOrOversizedFiles() {
        assertEquals(128L * 1024 * 1024, WritingReferenceImport.byteLimit("大书.TXT"))
        assertEquals(256L * 1024 * 1024, WritingReferenceImport.byteLimit("大书.epub"))
        assertTrue(runCatching { WritingReferenceImport.byteLimit("漫画.pdf") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { WritingReferenceImport.parse("范本.epub", "不是压缩文件") }.exceptionOrNull() is IllegalArgumentException)
        val tooLarge = tmp.newFile("large.txt")
        java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(WritingReferenceImport.MAX_TEXT_BYTES + 1) }
        assertTrue(runCatching { WritingReferenceImport.parse("large.txt", tooLarge) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun epub(vararg files: Pair<String, String>, order: List<String> = files.map { it.first }): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""")
            val names = (files.map { it.first } + order).distinct()
            put("book.opf", """<package><metadata><dc:title>合成范本</dc:title><dc:creator>测试作者</dc:creator></metadata><manifest>${names.mapIndexed { i, name -> """<item id="c$i" href="$name" media-type="application/xhtml+xml"/>""" }.joinToString("")}</manifest><spine>${order.joinToString("") { name -> """<itemref idref="c${names.indexOf(name)}"/>""" }}</spine></package>""")
            files.forEach { (name, html) -> put(name, html) }
        }
        return out.toByteArray()
    }
}
