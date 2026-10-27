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
        assertEquals(32L * 1024 * 1024, WritingReferenceImport.byteLimit("大书.TXT"))
        assertEquals(64L * 1024 * 1024, WritingReferenceImport.byteLimit("大书.epub"))
        assertTrue(runCatching { WritingReferenceImport.byteLimit("漫画.pdf") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { WritingReferenceImport.parse("范本.epub", "不是压缩文件") }.exceptionOrNull() is IllegalArgumentException)
        val tooLarge = tmp.newFile("large.txt")
        java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(WritingReferenceImport.MAX_TEXT_BYTES + 1) }
        assertTrue(runCatching { WritingReferenceImport.parse("large.txt", tooLarge) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun volumeLinesKeepInlineChapterTitlesAndOrdinaryBodySentences() {
        val book = WritingReferenceImport.parse("书.txt",
            "第一卷 风起 第一章 少年\n林月在渡口等船。\n第二章 远行\n沈默背着包袱上船。\n第一部手机是老周送的。\n后来他们没再见面。")
        assertEquals(listOf("第一章 少年", "第二章 远行"), book.chapters.map { it.title })
        assertEquals(listOf(1, 2), book.chapters.map { it.number })
        assertEquals("林月在渡口等船。", body(book.chapters[0]))
        assertEquals("沈默背着包袱上船。\n第一部手机是老周送的。\n后来他们没再见面。", body(book.chapters[1]))
        // 卷名独占一行时仍只作分组；部、集、篇后直接接字、或带句读的行都是正文。
        val grouped = WritingReferenceImport.parse("书.txt", """
            第一章 渡口
            老周撑船过来。
            第二部 归途
            第二章 夜雨
            第三篇论文写的是渡口的来历
            第二集里，林月终于开口了。
            第二卷第三章 晨光
            天亮了。
        """.trimIndent())
        assertEquals(listOf("第一章 渡口", "第二章 夜雨", "第三章 晨光"), grouped.chapters.map { it.title })
        assertEquals("第三篇论文写的是渡口的来历\n第二集里，林月终于开口了。", body(grouped.chapters[1]))
    }

    @Test fun fileImportsStreamTextAndMatchByteImports() {
        // 一处控制字符不到全文百分之一，照常读入并去掉；末尾的 Ctrl-Z 填充不算损坏。
        val text = "第一章 旧信\n信纸是白的，墨迹\u0007还没有干。\n" + "林月在渡口等了一夜，老周的船始终没有来。".repeat(8) +
            "\n第二章 远路\n他把信收进衣内。\u001A\u001A"
        listOf(
            text.toByteArray(Charsets.UTF_8),
            text.toByteArray(Charset.forName("GB18030")),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
        ).forEachIndexed { i, bytes ->
            val file = tmp.newFile("stream-$i.txt").apply { writeBytes(bytes) }
            val streamed = WritingReferenceImport.parse("范本.txt", file)
            val whole = WritingReferenceImport.parse("范本.txt", bytes)
            assertEquals(whole.chapters.map { it.title }, streamed.chapters.map { it.title })
            assertEquals(whole.chapters.map(::body), streamed.chapters.map(::body))
            assertTrue(body(streamed.chapters.first()).startsWith("信纸是白的，墨迹还没有干。\n"))
            assertEquals("他把信收进衣内。", body(streamed.chapters.last()))
        }
        // 坏得太多的不是文本，流式读取也要拒绝。
        val binary = tmp.newFile("binary.txt").apply { writeBytes(ByteArray(4096) { (it % 7 + 1).toByte() }) }
        assertTrue(runCatching { WritingReferenceImport.parse("范本.txt", binary) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun epubFileImportOnlyInflatesTextEntriesAndOversizedFilesAreRejectedBeforeReading() {
        val epub = epub(
            "a.xhtml" to "<html><body><h1>第一章 渡口</h1><p>林月在渡口等船。</p><img src=\"images/a.png\"/></body></html>",
            image = ByteArray(8192) { (it * 31 % 251).toByte() },
        )
        // 把图片的压缩数据弄坏：整包解压会读到它而失败，按条目读取根本不碰它。
        val name = "images/a.png".toByteArray()
        val at = (0..epub.size - name.size).first { i -> name.indices.all { epub[i + it] == name[it] } } + name.size
        for (i in at until at + 64) epub[i] = (epub[i].toInt() xor 0x5A).toByte()
        assertTrue(runCatching { WritingReferenceImport.parse("插图.epub", epub) }.isFailure)
        val file = tmp.newFile("插图.epub").apply { writeBytes(epub) }
        val book = WritingReferenceImport.parse("插图.epub", file)
        assertEquals(listOf("第一章 渡口"), book.chapters.map { it.title })
        assertEquals("林月在渡口等船。", body(book.chapters.single()).trim())
        val notZip = tmp.newFile("坏.epub").apply { writeText("不是压缩文件") }
        assertTrue(runCatching { WritingReferenceImport.parse("坏.epub", notZip) }.exceptionOrNull() is IllegalArgumentException)
        val tooLarge = tmp.newFile("large.epub")
        java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(WritingReferenceImport.MAX_EPUB_BYTES + 1) }
        val error = runCatching { WritingReferenceImport.parse("large.epub", tooLarge) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("64 MB"))
    }

    @Test fun pickedFilesAreCopiedWithABoundInsteadOfBufferedInMemory() {
        val limit = 4096L
        val ok = tmp.newFile("ok.tmp")
        WritingReferenceImport.copyBounded(ByteArray(4096) { 1 }.inputStream(), ok, limit)
        assertEquals(4096L, ok.length())
        val over = tmp.newFile("over.tmp")
        val error = runCatching { WritingReferenceImport.copyBounded(ByteArray(4097) { 1 }.inputStream(), over, limit) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(over.length() <= limit)
    }

    private fun epub(vararg files: Pair<String, String>, order: List<String> = files.map { it.first }, image: ByteArray? = null): ByteArray {
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
            image?.let {
                zip.putNextEntry(ZipEntry("images/a.png"))
                zip.write(it)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
