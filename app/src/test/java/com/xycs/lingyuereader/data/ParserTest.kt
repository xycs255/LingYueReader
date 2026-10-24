package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParserTest {

    // 与作者云盘里“已定正文_第X-Y章.md”同样的格式。
    private val manuscript = """
        # 已定正文（第十六至二十章）

        > 承《已定正文\_第11-15章》。以下章节已定稿，是后续章节必须接上的前文。

        ## 第十六章 车轮之外

        　　田槐是天亮前回来的，裤腿湿到膝盖。

        　　“往西北走了十来里，过了一道干渠。”他蹲下来，拿草棍在地上划了几道。

        ---

        　　晌午前后，车队走到一片开阔地上。

        ## 第十八章（上） 石梁

        　　上半章。

        ## 第十八章（下） 石梁

        　　下半章，有 **加粗**。

        ## 第十七章 清沟

        　　第十七章正文。
    """.trimIndent()

    private fun md(name: String, text: String) = BookParser.SourceBytes(name, text.toByteArray())

    @Test
    fun manuscriptMarkdownSkipsAuthorNotes() {
        val chapters = MarkdownParser.parse(manuscript, "x")
        assertEquals(listOf(16, 18, 18, 17), chapters.map { it.number })
        assertEquals(listOf(0, 1, 3, 0), chapters.map { it.part })
        assertEquals(
            listOf(
                Block.Para("田槐是天亮前回来的，裤腿湿到膝盖。"),
                Block.Para("“往西北走了十来里，过了一道干渠。”他蹲下来，拿草棍在地上划了几道。"),
                Block.SceneBreak,
                Block.Para("晌午前后，车队走到一片开阔地上。"),
            ),
            chapters[0].blocks,
        )
    }

    @Test
    fun mergedManuscriptsSortByChapterAndLaterFileWins() {
        val book = BookParser.parse(
            listOf(
                md("已定正文_第17章修订.md", "## 第十七章 清沟（改）\n　　新稿。"),
                md("已定正文_第16-18章.md", manuscript),
            ),
        )
        // 自然排序：“第16-18章”在“第17章修订”前，所以修订稿覆盖旧稿。
        assertEquals(
            listOf("第十六章 车轮之外", "第十七章 清沟（改）", "第十八章（上） 石梁", "第十八章（下） 石梁"),
            book.chapters.map { it.title },
        )
        assertEquals(listOf(Block.Para("新稿。")), book.chapters[1].blocks)
    }

    @Test
    fun documentMarkdownSplitsByRepeatedHeading() {
        val chapters = MarkdownParser.parse(
            """
            # 人物设定档案
            > 说明
            ## 韩临安
            - 字沐平
            | 项 | 内容 |
            | --- | --- |
            | 籍贯 | 待考 |
            ## 田槐
            缺指头的老卒。
            """.trimIndent(),
            "人物设定档案",
        )
        assertEquals(listOf("人物设定档案", "韩临安", "田槐"), chapters.map { it.title })
        assertEquals(
            listOf(Block.ListItem("•", "字沐平"), Block.Table(listOf(listOf("项", "内容"), listOf("籍贯", "待考")))),
            chapters[1].blocks,
        )
        assertEquals(listOf(Block.Para("缺指头的老卒。", prose = false)), chapters[2].blocks)
    }

    @Test
    fun txtDetectsChaptersAndGbk() {
        val text = """
            书名：测试
            作者：某人

            第一章 开端
            　　第一次见他，是在雨里。
            　　他说：“走吧。”
            第二卷 远行
            第2章　路上
            　　路很长。
            番外 后来
            　　后来的事。
        """.trimIndent()
        val bytes = text.toByteArray(Charset.forName("GBK"))
        val book = BookParser.parse(listOf(BookParser.SourceBytes("测试.txt", bytes)))
        assertEquals(listOf("开篇", "第一章 开端", "第2章 路上", "番外 后来"), book.chapters.map { it.title })
        assertEquals(Block.Para("第一次见他，是在雨里。"), book.chapters[1].blocks[0])
        assertEquals(2, book.chapters[2].number)
    }

    @Test
    fun longChineseTxtKeepsParagraphsInCommonEncodings() {
        val text = buildString {
            append("第一章 风起\n")
            repeat(240) { append("　　这是第一章第 ${it + 1} 段，风从山上吹来。\n") }
            append("第二章 雨落\n")
            repeat(240) { append("　　这是第二章第 ${it + 1} 段，雨落在旧城。\n") }
        }
        val encodings = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE, Charset.forName("GB18030"))
        encodings.forEach { charset ->
            val book = BookParser.parse(listOf(BookParser.SourceBytes("中文长篇.txt", text.toByteArray(charset))))
            assertEquals(charset.name(), listOf("第一章 风起", "第二章 雨落"), book.chapters.map { it.title })
            assertEquals(charset.name(), 240, book.chapters[0].blocks.size)
            assertEquals(charset.name(), "这是第二章第 240 段，雨落在旧城。", book.chapters[1].blocks.last().plainText)
        }
    }

    @Test
    fun textDecoderAcceptsValidBomsAndGb18030SupplementaryCharacters() {
        val text = "第一章 星河\n　　他看见了𠀀字。\n"
        val marked = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            text.toByteArray(Charset.forName("GB18030")),
        )
        marked.forEach { assertEquals(text, TextDecoder.decode(it)) }
    }

    /** 网上流传的长篇 TXT 常有个别坏字节、末尾 Ctrl-Z、夹杂的控制字符：照样能读，控制字符去掉。 */
    @Test
    fun sparseDamageInALongTxtIsTolerated() {
        val text = buildString {
            append("第一章 风起\n")
            repeat(300) { append("　　这是第 ${it + 1} 段，风从山上吹来。\n") }
        }
        val gbk = text.toByteArray(Charset.forName("GB18030"))
        // GBK：中间插一个落单的高位字节，末尾截掉半个汉字，再补 DOS 的 Ctrl-Z。
        val broken = gbk.copyOfRange(0, 400) + byteArrayOf(0x81.toByte()) + gbk.copyOfRange(400, gbk.size) +
            byteArrayOf(0xB7.toByte(), 0x1A)
        val book = BookParser.parse(listOf(BookParser.SourceBytes("坏了一点.txt", broken)))
        assertEquals("第一章 风起", book.chapters.single().title)
        assertEquals("这是第 300 段，风从山上吹来。", book.chapters.single().blocks[299].plainText)
        // UTF-8：夹一个控制字符和一个坏字节。
        val utf8 = text.replaceFirst("风从", "风\u0007从").toByteArray() + byteArrayOf(0xE4.toByte())
        val decoded = TextDecoder.decode(utf8)
        assertTrue(decoded.contains("这是第 1 段，风从山上吹来。"))
        assertTrue(decoded.none { it == '\u0007' })
    }

    @Test
    fun malformedTextIsRejectedInsteadOfImportingReplacementOrControlCharacters() {
        val badInputs = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xE4.toByte()), // 断尾 UTF-8 BOM
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x2D, 0x4E, 0x00), // 断尾 UTF-16
            byteArrayOf(0x81.toByte()), // UTF-8、GB18030 都不能解码
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), // 非文本控制字节
            "第一章\n正文含有\uFFFD损坏字符。".toByteArray(),
        )
        badInputs.forEachIndexed { index, bytes ->
            val error = runCatching { BookParser.parse(listOf(BookParser.SourceBytes("损坏.txt", bytes))) }.exceptionOrNull()
            assertTrue("坏文本 $index 应有明确错误", error is IllegalArgumentException && !error.message.isNullOrBlank())
        }
    }

    @Test
    fun txtWithoutChaptersIsSplitIntoSections() {
        val text = (1..400).joinToString("\n") { "这是第 $it 段，没有任何章节标题，只有很长很长的一段正文内容。" }
        val chapters = TxtParser.parse(text)
        assertTrue(chapters.size > 1)
        assertEquals("第 1 节", chapters[0].title)
        assertEquals(400, chapters.sumOf { it.blocks.size })
    }

    @Test
    fun chapterTitleHeuristics() {
        assertTrue(ChapterTitles.isTxtChapterTitle("第一章"))
        assertTrue(ChapterTitles.isTxtChapterTitle("第一百零二章 归来"))
        assertTrue(ChapterTitles.isTxtChapterTitle("楔子"))
        assertTrue(ChapterTitles.isTxtChapterTitle("Chapter 12 The End"))
        assertFalse(ChapterTitles.isTxtChapterTitle("第一次见他，是在雨里。"))
        assertFalse(ChapterTitles.isTxtChapterTitle("第三章说的就是这件事。"))
        assertFalse(ChapterTitles.isTxtChapterTitle("序幕拉开了，众人各就各位。"))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(18, ChapterTitles.parseChineseNumber("十八"))
        assertEquals(34, ChapterTitles.parseChineseNumber("三十四"))
        assertEquals(102, ChapterTitles.parseChineseNumber("一百零二"))
        assertEquals(200, ChapterTitles.parseChineseNumber("两百"))
        assertEquals(12000, ChapterTitles.parseChineseNumber("一万二千"))
        assertEquals(68, ChapterTitles.parseChineseNumber("68"))
        assertNull(ChapterTitles.parseChineseNumber("甲"))
    }

    @Test
    fun epubUsesSpineAndToc() {
        val epub = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package><metadata><dc:title>示例书</dc:title><dc:creator>作者甲</dc:creator></metadata>
                <manifest>
                  <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c1" href="Text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest>
                <spine toc="ncx"><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/></spine></package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap>
                  <navPoint id="a"><navLabel><text>第一章 起</text></navLabel><content src="Text/ch%201.xhtml"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "OEBPS/Text/cover.xhtml" to """<html><body><img src="c.jpg"/></body></html>""",
            "OEBPS/Text/ch 1.xhtml" to """<html><head><title>x</title><style>p{}</style></head><body>
                <h2>第一章 起</h2><p>　　他说&ldquo;好&rdquo;&amp;走了。</p><p>第二段<br/>换行</p></body></html>""",
            "OEBPS/Text/ch2.xhtml" to """<html><body><h1>第二章 承</h1><blockquote><p>引文</p></blockquote><ul><li>条目</li></ul></body></html>""",
        )
        val book = BookParser.parse(listOf(BookParser.SourceBytes("x.epub", epub)))
        assertEquals("示例书", book.title)
        assertEquals("作者甲", book.author)
        assertEquals(listOf("第一章 起", "第二章 承"), book.chapters.map { it.title })
        assertEquals(
            listOf(Block.Para("他说“好”&走了。"), Block.Para("第二段"), Block.Para("换行")),
            book.chapters[0].blocks,
        )
        assertEquals(listOf(Block.Quote("引文"), Block.ListItem("•", "条目")), book.chapters[1].blocks)
    }

    @Test
    fun longChineseEpubKeepsAllChaptersAndParagraphs() {
        val first = (1..240).joinToString("") { "<p>春风吹过山岗，第 $it 段。</p>" }
        val second = (1..240).joinToString("") { "<p>雨落在旧城，第 $it 段。</p>" }
        val epub = zip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "a.xhtml" to "<html><body><h1>第一章 风起</h1>$first</body></html>",
            "b.xhtml" to "<html><body><h1>第二章 雨落</h1>$second</body></html>",
        )
        val book = BookParser.parse(listOf(BookParser.SourceBytes("长篇.epub", epub)))
        assertEquals(listOf("第一章 风起", "第二章 雨落"), book.chapters.map { it.title })
        assertEquals(240, book.chapters[0].blocks.size)
        assertEquals("雨落在旧城，第 240 段。", book.chapters[1].blocks.last().plainText)
    }

    /** 缺了一章文件的 EPUB 照样能读，缺的那章留一段说明，不悄悄少一章；阅读顺序引用清单里没有的项目时略过。 */
    @Test
    fun epubWithMissingSpineFilesKeepsReadableChaptersAndMarksTheGap() {
        val container = """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>"""
        val opf = """<package><manifest>
            <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
            <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
        val missingChapter = zip(
            "META-INF/container.xml" to container,
            "book.opf" to opf,
            "a.xhtml" to "<html><body><p>第一章仍然有效。</p></body></html>",
        )
        val book = EpubParser.parse(missingChapter, "x")
        assertEquals(2, book.chapters.size)
        assertEquals("第一章仍然有效。", book.chapters[0].blocks.single().plainText)
        assertTrue(book.chapters[1].blocks.single().plainText.contains("b.xhtml"))

        val missingManifest = zip(
            "META-INF/container.xml" to container,
            "book.opf" to opf.replace("<item id=\"b\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/>", ""),
            "a.xhtml" to "<html><body><p>第一章仍然有效。</p></body></html>",
        )
        assertEquals(1, EpubParser.parse(missingManifest, "x").chapters.size)

        // 正文文件全都缺失时才算无效。
        val nothing = zip("META-INF/container.xml" to container, "book.opf" to opf)
        val error = runCatching { EpubParser.parse(nothing, "x") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("正文文件都缺失"))
    }

    /** 两种常见注释：EPUB3 的 noteref + 正文里的 aside；以及指向尾注章节的 [1] 链接。 */
    internal fun footnoteEpub(): ByteArray = zip(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
        "OPS/book.opf" to """
            <package><metadata><dc:title>有注释的书</dc:title></metadata>
            <manifest>
              <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              <item id="n" href="notes.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="c1"/><itemref idref="n"/></spine></package>
        """.trimIndent(),
        "OPS/c1.xhtml" to """<html><body><h1>第一章</h1>
            <p>他读了《论语》<a epub:type="noteref" href="#fn1"><sup>1</sup></a>，又去了长安<a href="notes.xhtml#n2" id="r2">[2]</a>。<a href="notes.xhtml#n2">看注释</a></p>
            <aside epub:type="footnote" id="fn1"><p><a href="#r1">1</a> 儒家经典，记孔子及其弟子言行。</p></aside>
            </body></html>""",
        "OPS/notes.xhtml" to """<html><body><h1>注释</h1>
            <p><a id="n2" href="c1.xhtml#r2">[2]</a> 长安：今陕西西安。<a href="c1.xhtml#r2">↩</a></p></body></html>""",
    )

    @Test
    fun epubFootnotesBecomeTappableMarkers() {
        val book = BookParser.parse(listOf(BookParser.SourceBytes("x.epub", footnoteEpub())))
        val para = book.chapters[0].blocks.single() as Block.Para
        val parsed = InlineText.parse(para.text)
        // 角标留在正文里（显示为 1、[2]），普通链接文字照常；脚注正文不混进正文。
        assertEquals("他读了《论语》1，又去了长安[2]。看注释", parsed.text)
        assertEquals("他读了《论语》1，又去了长安[2]。看注释", para.plainText)
        assertEquals(listOf("OPS/c1.xhtml#fn1", "OPS/notes.xhtml#n2"), parsed.footnotes.map { it.second })
        assertEquals("1", parsed.text.substring(parsed.footnotes[0].first.first, parsed.footnotes[0].first.last + 1))
        assertEquals("儒家经典，记孔子及其弟子言行。", book.notes["OPS/c1.xhtml#fn1"])
        assertEquals("长安：今陕西西安。", book.notes["OPS/notes.xhtml#n2"])
        // 尾注章节本身仍可阅读。
        assertEquals(listOf("第一章", "注释"), book.chapters.map { it.title })
    }

    @Test
    fun inlineTextKeepsBoldAndNotesAligned() {
        val raw = "前**加粗**" + InlineText.footnote("[3]", "k") + "后**落单"
        val p = InlineText.parse(raw)
        assertEquals("前加粗[3]后**落单", p.text)
        assertEquals(listOf(1..2), p.bold)
        assertEquals(listOf((3..5) to "k"), p.footnotes)
    }

    @Test
    fun epubImagesCoverAndDescription() {
        val epub = zip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """
                <package><metadata><dc:title>图书</dc:title><dc:description>&lt;p&gt;一本&lt;b&gt;好书&lt;/b&gt;&lt;/p&gt;</dc:description>
                <meta name="cover" content="cov"/></metadata>
                <manifest>
                  <item id="cov" href="images/cover.jpg" media-type="image/jpeg"/>
                  <item id="p0" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                  <item id="p1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="p2" href="text/plate.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="p0"/><itemref idref="p1"/><itemref idref="p2"/></spine></package>
            """.trimIndent(),
            "OPS/text/cover.xhtml" to """<html><body><svg><image xlink:href="../images/cover.jpg"/></svg></body></html>""",
            "OPS/text/c1.xhtml" to """<html><body><h1>第一章</h1><p>开头</p><p><img src="../images/map.png" alt="地图"/></p><p>结尾</p></body></html>""",
            "OPS/text/plate.xhtml" to """<html><body><img src="../images/plate%201.png"/></body></html>""",
        )
        val book = BookParser.parse(listOf(BookParser.SourceBytes("x.epub", epub)))
        assertEquals("0|OPS/images/cover.jpg", book.cover)
        assertEquals("一本好书", book.description)
        // 封面页不单独成章；插图页成为“插图”章。
        assertEquals(listOf("第一章", "插图"), book.chapters.map { it.title })
        assertEquals(
            listOf(Block.Para("开头"), Block.Image("0|OPS/images/map.png", "地图"), Block.Para("结尾")),
            book.chapters[0].blocks,
        )
        assertEquals(listOf(Block.Image("0|OPS/images/plate 1.png")), book.chapters[1].blocks)
    }

    @Test
    fun searchFindsEveryOccurrence() {
        val book = BookParser.parse(listOf(md("a.md", manuscript)))
        val hits = BookParser.search(book, "干渠")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].blockIndex)
    }

    @Test
    fun naturalOrder() {
        val names = listOf("已定正文_第29-34章.md", "已定正文_第6-10章.md", "已定正文_第1-5章.md", "已定正文_第11-15章.md")
        assertEquals(
            listOf("已定正文_第1-5章.md", "已定正文_第6-10章.md", "已定正文_第11-15章.md", "已定正文_第29-34章.md"),
            names.sortedWith(BookParser.NaturalOrder),
        )
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
