package com.xycs.lingyuereader.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.ui.BookImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormatsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val titles = listOf("第一章 起", "第二章 承", "第三章 转")

    private fun fixture(name: String) = File("src/test/resources/formats/$name")

    private fun parse(name: String): BookContent = BookParser.parse(listOf(BookParser.SourceBytes(name, fixture(name))))

    @After
    fun tearDown() {
        BookParser.pdfAspects = PdfPages::aspects
    }

    /** Calibre 转出来的三种 Kindle / FB2 文件，读出来应当一样。 */
    private fun assertSameBook(content: BookContent, name: String) {
        assertEquals(name, "测试之书", content.title)
        assertEquals(name, "某作者", content.author)
        assertEquals(name, titles, content.chapters.map { it.title })
        val first = content.chapters[0].blocks.map { it.plainText }
        // 章名不在正文里重复出现。
        assertEquals(name, listOf("甲一段落，春天来了。", "甲二段落，花开了。"), first.filter { it.isNotEmpty() })
        assertEquals(name, "丙段落，最后一章。", content.chapters[2].blocks.first().plainText)
        val image = content.chapters[1].blocks.filterIsInstance<Block.Image>().single()
        assertTrue(name, image.src.startsWith("0|@res/"))
        val bytes = BookParser.resource(BookParser.SourceBytes(name, fixture(name)), image.src.substringAfter("|@res/"))!!
        // 老式 MOBI 里 Calibre 会把 PNG 转成 JPEG。
        assertTrue(name, PageBooks.imageExtension(bytes) in setOf("png", "jpg"))
    }

    @Test
    fun mobiAzw3AndFb2ReadTheSameChapters() {
        assertSameBook(parse("book.mobi"), "book.mobi")
        assertSameBook(parse("book.azw3"), "book.azw3")
        assertSameBook(parse("book-both.mobi"), "book-both.mobi")
        assertSameBook(parse("book.fb2"), "book.fb2")
    }

    @Test
    fun drmProtectedMobiIsRefusedWithAReason() {
        val bytes = fixture("book.mobi").readBytes()
        val r0 = ((bytes[78].toInt() and 0xFF) shl 24) or ((bytes[79].toInt() and 0xFF) shl 16) or
            ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
        bytes[r0 + 13] = 2
        val error = runCatching { MobiParser.parse(bytes, "x") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("DRM"))
    }

    @Test
    fun fb2WithTitlesNestedSectionsPoemsAndNotes() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><author><first-name>三</first-name><last-name>张</last-name></author>
            <book-title>诗与注</book-title><annotation><p>一本小书。</p></annotation>
            <coverpage><image l:href="#c.png"/></coverpage></title-info></description>
            <body><title><p>诗与注</p></title>
              <section>
                <section><title><p>卷一</p><p>春</p></title>
                  <p>正文一<a l:href="#n1" type="note">[1]</a>。</p>
                  <poem><stanza><v>床前明月光</v><v>疑是地上霜</v></stanza></poem>
                </section>
                <section><title><p>卷二</p></title><subtitle>小标题</subtitle><p>正文二。</p>
                  <section><title><p>二之一</p></title><p>更深一层。</p></section>
                </section>
              </section>
            </body>
            <body name="notes"><section id="n1"><title><p>1</p></title><p>这是注释。</p></section></body>
            <binary id="c.png" content-type="image/png">iVBORw0KGgo=</binary>
            </FictionBook>"""
        val content = BookParser.parse(listOf(BookParser.SourceBytes("诗与注.fb2", xml.toByteArray())))
        assertEquals("诗与注", content.title)
        assertEquals("张 三", content.author)
        assertEquals("一本小书。", content.description)
        assertEquals("0|@res/bin/c.png", content.cover)
        // 一个顶层 section 包着两卷：按里面的分章；多段标题合成一行。
        assertEquals(listOf("卷一 春", "卷二"), content.chapters.map { it.title })
        val one = content.chapters[0].blocks
        assertEquals("正文一[1]。", one[0].plainText)
        assertTrue(one.any { it is Block.Quote && it.text == "床前明月光" })
        val key = InlineText.parse((one[0] as Block.Para).text).footnotes.single().second
        assertEquals("这是注释。", content.notes[key])
        val two = content.chapters[1].blocks
        assertTrue(two.any { it is Block.Heading && it.text == "小标题" })
        assertTrue(two.any { it is Block.Heading && it.text == "二之一" })
        assertTrue(two.any { it.plainText == "更深一层。" })
    }

    private fun cbz(names: List<String>): File {
        val png = fixturePng()
        val file = File(context.cacheDir, "comic.cbz")
        ZipOutputStream(file.outputStream()).use { z ->
            names.forEach { n -> z.putNextEntry(ZipEntry(n)); z.write(png); z.closeEntry() }
            z.putNextEntry(ZipEntry("ComicInfo.xml")); z.write("<x/>".toByteArray()); z.closeEntry()
        }
        return file
    }

    private fun fixturePng(): ByteArray {
        val bytes = fixture("book.fb2").readText()
        val b64 = Regex("""<binary[^>]*>(.*?)</binary>""", RegexOption.DOT_MATCHES_ALL).find(bytes)!!.groupValues[1]
        return java.util.Base64.getMimeDecoder().decode(b64.trim())
    }

    @Test
    fun comicPagesAreSortedNaturallyAndGroupedIntoChapters() {
        val flat = cbz((1..25).map { "p$it.png" }.shuffled())
        val content = BookParser.parse(listOf(BookParser.SourceBytes("漫画.cbz", flat)))
        assertEquals(listOf("第 1–20 页", "第 21–25 页"), content.chapters.map { it.title })
        assertEquals("0|@zip/p2.png", (content.chapters[0].blocks[1] as Block.Image).src)
        assertEquals("0|@zip/p1.png", content.cover)

        val folders = cbz(listOf("第1话/01.png", "第1话/02.png", "第2话/01.png"))
        val byFolder = BookParser.parse(listOf(BookParser.SourceBytes("漫画.cbz", folders)))
        assertEquals(listOf("第1话", "第2话"), byFolder.chapters.map { it.title })
    }

    @Test
    fun pdfPagesCarryTheirAspectRatio() {
        val file = File(context.cacheDir, "notes.pdf").apply { writeBytes(ByteArray(10)) }
        BookParser.pdfAspects = { listOf(1.5f, 0.75f) }
        val content = BookParser.parse(listOf(BookParser.SourceBytes("讲义.pdf", file)))
        assertEquals("讲义", content.title)
        assertEquals(listOf("全部"), content.chapters.map { it.title })
        assertEquals(listOf("0|@pdf/0/1.5", "0|@pdf/1/0.75"), content.chapters[0].blocks.map { (it as Block.Image).src })
        assertEquals(1.5f, PageBooks.pdfRef("pdf:s0.pdf:0:1.5")!!.aspect)
        assertEquals("s0.pdf" to "a:b/c.png", PageBooks.zipRef("zip:s0.pdf:a:b/c.png"))
    }

    @Test
    fun importedMobiHasItsImagesExtractedAndComicsReadFromTheArchive() {
        val library = Library(context)
        fun import(file: File) = library.add(library.describe(listOf(Uri.fromFile(file))), null)

        val mobiFile = File(context.cacheDir, "book.mobi").apply { writeBytes(fixture("book.mobi").readBytes()) }
        val mobi = import(mobiFile)
        assertEquals(BookFormat.MOBI, mobi.format)
        assertEquals("测试之书", mobi.title)
        val image = library.content(mobi).chapters[1].blocks.filterIsInstance<Block.Image>().single()
        assertTrue(library.bookFile(mobi.id, image.src).isFile)

        val comic = import(cbz(listOf("a/1.png", "a/2.png", "b/1.png")))
        assertEquals(BookFormat.CBZ, comic.format)
        assertTrue(comic.format.imageOnly)
        // 封面另存一张；正文里的页仍从压缩包里读。
        assertTrue(library.bookFile(comic.id, comic.cover!!).isFile)
        val page = library.content(comic).chapters[0].blocks[0] as Block.Image
        assertEquals("zip:s0.cbz:a/1.png", page.src)
        val images = BookImages { library.bookFile(comic.id, it) }
        assertEquals(0.5f, images.aspect(page.src)!!, 0.001f)
        assertNotNull(images.load(page.src, 20))
        images.close()
    }
}
