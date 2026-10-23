package com.xycs.lingyuereader.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val library = Library(context)

    private fun epub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
            put("book.opf", """<package><metadata><dc:title>有图的书</dc:title><dc:description>简介</dc:description></metadata>
                <manifest><item id="c" href="cover.png" media-type="image/png" properties="cover-image"/>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/></spine></package>""".toByteArray())
            put("a.xhtml", """<html><body><h1>第一章</h1><p>正文</p><img src="fig.png"/><img src="missing.png"/></body></html>""".toByteArray())
            put("cover.png", PNG)
            put("fig.png", PNG)
        }
        return out.toByteArray()
    }

    private fun import(name: String, bytes: ByteArray): BookEntry {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        return library.add(library.describe(listOf(Uri.fromFile(file))), null)
    }

    @Test
    fun epubImagesAndCoverAreExtracted() {
        val entry = import("有图的书.epub", epub())
        assertEquals("有图的书", entry.title)
        assertEquals("简介", entry.description)
        val cover = entry.cover!!
        assertTrue(library.bookFile(entry.id, cover).isFile)

        val content = library.content(entry)
        val images = content.chapters[0].blocks.filterIsInstance<Block.Image>()
        // 找不到的图片被去掉，找到的改成书籍目录里的文件名。
        assertEquals(1, images.size)
        assertTrue(library.bookFile(entry.id, images[0].src).isFile)
        assertNotEquals(cover, images[0].src)
    }

    @Test
    fun cacheIsReusedUntilTheSourceChanges() {
        val entry = import("长篇.txt", "第一章 起\n　　正文一。\n第二章 承\n　　正文二。\n".toByteArray())
        val cache = library.bookFile(entry.id, "cache.bin")
        assertTrue(cache.isFile)
        val stamp = cache.lastModified()

        // 再次打开直接读缓存：章节正文还没加载，缓存文件也没重写。
        val reopened = library.content(entry)
        assertFalse(reopened.chapters[1].isLoaded)
        assertEquals(listOf(Block.Para("正文二。")), reopened.chapters[1].blocks)
        assertEquals(stamp, cache.lastModified())

        // 来源副本变了（比如刷新后），缓存作废并重建。
        val source = library.bookFile(entry.id, entry.sources[0].file)
        source.writeText("第一章 起\n　　改过的正文。\n")
        source.setLastModified(stamp + 5_000)
        val rebuilt = library.content(entry)
        assertEquals(1, rebuilt.chapters.size)
        assertEquals(listOf(Block.Para("改过的正文。")), rebuilt.chapters[0].blocks)
    }

    @Test
    fun backupAndRestoreRoundTrip() {
        val a = import("甲.txt", "第一章 起\n　　甲的正文。\n".toByteArray())
        val b = import("乙.txt", "第一章 起\n　　乙的正文。\n".toByteArray())
        library.saveMarks(a.id, Marks(notes = listOf(Note("n", 0, "第一章 起", 0, 0, 2, "甲的", "批注", MarkColor.RED, 1))))
        library.saveProgress(a.id, ReadingProgress(0, "第一章 起", 1, 0))

        val out = ByteArrayOutputStream()
        assertEquals(2, library.backup(out, org.json.JSONObject().put("fontSize", org.json.JSONObject().put("f", 22.0))))

        // 换了台手机：书架清空，再从备份恢复。
        library.remove(a.id)
        library.remove(b.id)
        assertTrue(library.list().isEmpty())
        val result = library.restore(out.toByteArray().inputStream())
        assertEquals(2, result.books)
        assertEquals(22.0, result.settings!!.getJSONObject("fontSize").getDouble("f"), 0.0)

        val restored = library.list().first { it.id == a.id }
        assertEquals(1, restored.progress!!.itemIndex)
        assertEquals("批注", library.marks(a.id).notes.single().text)
        assertEquals(listOf(Block.Para("甲的正文。")), library.content(restored).chapters[0].blocks)
    }

    @Test
    fun restoreIgnoresPathsOutsideTheLibraryAndRejectsOtherZips() {
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
            put("lingyue-backup.json", """{"format":1}""")
            put("books/index.json", """[{"id":"../../x","title":"t","format":"TXT","addedAt":1,"sources":[{"name":"a.txt","file":"s0.txt"}]}]""")
            put("books/../../escaped.txt", "pwned")
            put("books/ok/../../../escaped2.txt", "pwned")
        }
        assertEquals(0, library.restore(evil.toByteArray().inputStream()).books)
        assertFalse(File(context.filesDir, "escaped.txt").exists())
        assertFalse(File(context.filesDir.parentFile, "escaped.txt").exists())
        assertFalse(File(context.cacheDir.parentFile, "escaped2.txt").exists())

        val notBackup = ByteArrayOutputStream()
        ZipOutputStream(notBackup).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(1); z.closeEntry() }
        val error = runCatching { library.restore(notBackup.toByteArray().inputStream()) }.exceptionOrNull()
        assertEquals("不是灵阅的备份文件", error?.message)
    }

    private companion object {
        // 1×1 的透明 PNG
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }
}
