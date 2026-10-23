package com.xycs.lingyuereader.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
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

    private fun cbz(vararg imageNames: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            imageNames.forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(PNG); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun import(name: String, bytes: ByteArray): BookEntry {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        return library.add(library.describe(listOf(Uri.fromFile(file))), null)
    }

    private fun replacementBackup(replacements: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("lingyue-backup.json", """{"format":1}""".toByteArray())
            put("books/index.json", File(context.filesDir, "books/index.json").readBytes())
            library.list().forEach { entry ->
                entry.sources.forEach { source ->
                    put("books/${entry.id}/${source.file}", replacements[entry.id] ?: library.bookFile(entry.id, source.file).readBytes())
                }
            }
        }
        return out.toByteArray()
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
    fun refreshRejectsEmptySourceWithoutChangingTheBook() {
        val original = "第一章 起\n　　旧正文。\n".toByteArray()
        val entry = import("空稿.txt", original)
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val cache = library.bookFile(entry.id, "cache.bin")
        val oldCache = cache.readBytes()
        val oldIndex = File(context.filesDir, "books/index.json").readBytes()

        File(context.cacheDir, "空稿.txt").writeBytes(byteArrayOf())
        val error = runCatching { library.refresh(entry) }.exceptionOrNull()

        assertEquals("来源文件为空：空稿.txt", error?.message)
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(oldIndex, File(context.filesDir, "books/index.json").readBytes())
        assertEquals(listOf(Block.Para("旧正文。")), library.content(entry).chapters.single().blocks)
    }

    @Test
    fun refreshRejectsInvalidEpubWithoutLosingCacheOrImages() {
        val original = epub()
        val entry = import("损坏前.epub", original)
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val cache = library.bookFile(entry.id, "cache.bin")
        val oldCache = cache.readBytes()
        val oldIndex = File(context.filesDir, "books/index.json").readBytes()
        val cover = library.bookFile(entry.id, entry.cover!!)
        val oldCover = cover.readBytes()

        File(context.cacheDir, "损坏前.epub").writeText("这不是 EPUB")
        assertTrue(runCatching { library.refresh(entry) }.isFailure)

        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(oldIndex, File(context.filesDir, "books/index.json").readBytes())
        assertArrayEquals(oldCover, cover.readBytes())
        assertEquals("正文", library.content(entry).chapters.single().blocks.filterIsInstance<Block.Para>().single().text)
    }

    @Test
    fun refreshKeepsLocalCopyWhenTheSourceCannotBeRead() {
        val original = "第一章 起\n　　离线正文。\n".toByteArray()
        val entry = import("已移走.txt", original)
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val cache = library.bookFile(entry.id, "cache.bin")
        val oldCache = cache.readBytes()
        val oldIndex = File(context.filesDir, "books/index.json").readBytes()

        assertTrue(File(context.cacheDir, "已移走.txt").delete())
        assertEquals(Library.RefreshResult(0, 1), library.refresh(entry))

        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(oldIndex, File(context.filesDir, "books/index.json").readBytes())
        assertEquals(listOf(Block.Para("离线正文。")), library.content(entry).chapters.single().blocks)
    }

    @Test
    fun refreshUpdatesContentAndKeepsProgressMarksAndRules() {
        val entry = import("继续写.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val rules = TextRules(listOf(ReplaceRule("广告词")))
        library.saveRules(entry, rules)
        val progress = ReadingProgress(0, "第一章 起", 1, 3)
        val marks = Marks(
            bookmarks = listOf(Bookmark("b", 0, "第一章 起", 1, 0, "旧正文", 1)),
            notes = listOf(Note("n", 0, "第一章 起", 0, 0, 2, "旧正", "批注", MarkColor.RED, 2)),
        )
        library.saveProgress(entry.id, progress)
        library.saveMarks(entry.id, marks)
        library.setGroups(entry.id, listOf("在读"))

        File(context.cacheDir, "继续写.txt").writeText("第一章 起\n　　新正文广告词。\n第二章 承\n　　又一段。\n")
        assertEquals(Library.RefreshResult(1, 0), library.refresh(entry))

        val updated = library.list().single { it.id == entry.id }
        val content = library.content(updated)
        assertEquals(2, updated.chapterCount)
        assertEquals(content.charCount, updated.charCount)
        assertEquals(progress, updated.progress)
        assertEquals(listOf("在读"), updated.groups)
        assertEquals(marks, library.marks(entry.id))
        assertEquals(rules, library.rules(entry.id))
        assertEquals(listOf(Block.Para("新正文。")), content.chapters[0].blocks)
        assertEquals(listOf(Block.Para("又一段。")), content.chapters[1].blocks)
    }

    @Test
    fun refreshRestoresTheOriginalBookWhenTheIndexCannotBeSaved() {
        val original = "第一章 起\n　　旧正文。\n".toByteArray()
        val entry = import("索引故障.txt", original)
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val cache = library.bookFile(entry.id, "cache.bin")
        val oldCache = cache.readBytes()
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        File(context.cacheDir, "索引故障.txt").writeText("第一章 起\n　　新正文。\n")

        val blocker = File(context.filesDir, "books/index.json.tmp")
        assertTrue(blocker.mkdir())
        try {
            assertTrue(runCatching { library.refresh(entry) }.isFailure)
        } finally {
            blocker.deleteRecursively()
        }

        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(oldIndex, index.readBytes())
        assertEquals(listOf(Block.Para("旧正文。")), library.content(entry).chapters.single().blocks)
    }

    @Test
    fun groupsAreStoredRenamedAndRemoved() {
        val a = import("甲.txt", "正文甲".toByteArray())
        val b = import("乙.txt", "正文乙".toByteArray())
        library.setGroups(a.id, listOf("武侠", "在读", "武侠"))
        library.setGroups(b.id, listOf("武侠"))
        assertEquals(listOf("武侠", "在读"), library.list().first { it.id == a.id }.groups)
        library.renameGroup("武侠", "江湖")
        assertEquals(listOf("江湖", "在读"), library.list().first { it.id == a.id }.groups)
        library.renameGroup("江湖", null)
        assertEquals(listOf("在读"), library.list().first { it.id == a.id }.groups)
        assertEquals(emptyList<String>(), library.list().first { it.id == b.id }.groups)
    }

    @Test
    fun savingRulesReparsesAndInvalidatesTheCache() {
        val e = import("规则.txt", "【一 下山】\n他下了山。广告词\n【二 进城】\n他进了城。\n".toByteArray())
        assertEquals(1, library.content(e).chapters.size)
        val rules = TextRules(listOf(ReplaceRule("广告词")), toc = """^【.+】$""")
        val content = library.saveRules(e, rules)
        assertEquals(listOf("【一 下山】", "【二 进城】"), content.chapters.map { it.title })
        assertEquals(listOf(Block.Para("他下了山。")), content.chapters[0].blocks)
        // 再打开（读缓存）也是新规则的结果；书架上的章数同步更新。
        val again = library.list().first { it.id == e.id }
        assertEquals(2, again.chapterCount)
        assertEquals(2, library.content(again).chapters.size)
        assertEquals(rules, library.rules(e.id))
        // 清空规则恢复自动识别。
        assertEquals(1, library.saveRules(again, TextRules()).chapters.size)
    }

    @Test
    fun backupAndRestoreRoundTrip() {
        val a = import("甲.txt", "第一章 起\n　　甲的正文。\n".toByteArray())
        val b = import("乙.txt", "第一章 起\n　　乙的正文。\n".toByteArray())
        library.saveMarks(a.id, Marks(notes = listOf(Note("n", 0, "第一章 起", 0, 0, 2, "甲的", "批注", MarkColor.RED, 1))))
        library.saveProgress(a.id, ReadingProgress(0, "第一章 起", 1, 0))
        library.saveRules(a, TextRules(listOf(ReplaceRule("甲的", "甲之"))))

        val out = ByteArrayOutputStream()
        val vocab = """[{"word":"银行","addedAt":1}]""".toByteArray()
        assertEquals(2, library.backup(out, org.json.JSONObject().put("fontSize", org.json.JSONObject().put("f", 22.0)), mapOf("vocabulary.json" to vocab)))

        // 换了台手机：书架清空，再从备份恢复。
        library.remove(a.id)
        library.remove(b.id)
        assertTrue(library.list().isEmpty())
        val result = library.restore(out.toByteArray().inputStream())
        assertEquals(2, result.books)
        assertEquals(22.0, result.settings!!.getJSONObject("fontSize").getDouble("f"), 0.0)
        assertEquals(String(vocab), result.data["vocabulary.json"])

        val restored = library.list().first { it.id == a.id }
        assertEquals(1, restored.progress!!.itemIndex)
        assertEquals("批注", library.marks(a.id).notes.single().text)
        // 规则也一起恢复了。
        assertEquals(listOf(Block.Para("甲之正文。")), library.content(restored).chapters[0].blocks)
    }

    @Test
    fun restoreReplacesAnExistingBookAfterPreparingItsFiles() {
        val entry = import("恢复前.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val replacement = "第一章 起\n　　恢复后的正文。\n".toByteArray()
        val archive = replacementBackup(mapOf(entry.id to replacement))

        assertEquals(1, library.restore(archive.inputStream()).books)

        val restored = library.list().single { it.id == entry.id }
        assertArrayEquals(replacement, library.bookFile(entry.id, entry.sources.single().file).readBytes())
        assertEquals(listOf(Block.Para("恢复后的正文。")), library.content(restored).chapters.single().blocks)
    }

    @Test
    fun restoreSkipsUnreadableReplacementsAndKeepsRestoringValidBooks() {
        val empty = import("空来源.txt", "第一章 起\n　　仍能读。\n".toByteArray())
        val epubBytes = epub()
        val invalidEpub = import("有效.epub", epubBytes)
        val valid = import("正常.txt", "第一章 起\n　　旧内容。\n".toByteArray())
        val oldEntries = library.list().associateBy { it.id }
        val oldEmptyCache = library.bookFile(empty.id, "cache.bin").readBytes()
        val oldEpubCache = library.bookFile(invalidEpub.id, "cache.bin").readBytes()
        val cover = library.bookFile(invalidEpub.id, invalidEpub.cover!!)
        val oldCover = cover.readBytes()
        val archive = replacementBackup(mapOf(
            empty.id to byteArrayOf(),
            invalidEpub.id to "不是 EPUB".toByteArray(),
            valid.id to "第一章 起\n　　恢复成功。\n".toByteArray(),
        ))

        assertEquals(1, library.restore(archive.inputStream()).books)

        val after = library.list().associateBy { it.id }
        assertEquals(oldEntries.getValue(empty.id), after.getValue(empty.id))
        assertEquals(oldEntries.getValue(invalidEpub.id), after.getValue(invalidEpub.id))
        assertArrayEquals("第一章 起\n　　仍能读。\n".toByteArray(), library.bookFile(empty.id, empty.sources.single().file).readBytes())
        assertArrayEquals(epubBytes, library.bookFile(invalidEpub.id, invalidEpub.sources.single().file).readBytes())
        assertArrayEquals(oldEmptyCache, library.bookFile(empty.id, "cache.bin").readBytes())
        assertArrayEquals(oldEpubCache, library.bookFile(invalidEpub.id, "cache.bin").readBytes())
        assertArrayEquals(oldCover, cover.readBytes())
        assertEquals(listOf(Block.Para("仍能读。")), library.content(empty).chapters.single().blocks)
        assertEquals("正文", library.content(invalidEpub).chapters.single().blocks.filterIsInstance<Block.Para>().single().text)
        assertEquals(listOf(Block.Para("恢复成功。")), library.content(after.getValue(valid.id)).chapters.single().blocks)
    }

    @Test
    fun restoreAcceptsImageOnlyComicReplacement() {
        val entry = import("漫画.cbz", cbz("1.png"))
        val archive = replacementBackup(mapOf(entry.id to cbz("1.png", "2.png")))

        assertEquals(1, library.restore(archive.inputStream()).books)

        val restored = library.list().single { it.id == entry.id }
        val content = library.content(restored)
        assertEquals(0, restored.charCount)
        assertEquals(2, content.chapters.single().blocks.filterIsInstance<Block.Image>().size)
        assertTrue(library.bookFile(entry.id, restored.cover!!).isFile)
    }

    @Test
    fun restoreRollsBackAllReplacedBooksWhenTheIndexCannotBeSaved() {
        val a = import("旧甲.txt", "第一章 起\n　　旧甲正文。\n".toByteArray())
        val b = import("旧乙.txt", "第一章 起\n　　旧乙正文。\n".toByteArray())
        val marks = Marks(notes = listOf(Note("n", 0, "第一章 起", 0, 0, 2, "旧甲", "保留批注", MarkColor.BLUE, 1)))
        library.saveMarks(a.id, marks)
        val archive = replacementBackup(mapOf(
            a.id to "第一章 起\n　　新甲正文。\n".toByteArray(),
            b.id to "第一章 起\n　　新乙正文。\n".toByteArray(),
        ))
        val oldSources = listOf(a, b).associate { it.id to library.bookFile(it.id, it.sources.single().file).readBytes() }
        val oldCaches = listOf(a, b).associate { it.id to library.bookFile(it.id, "cache.bin").readBytes() }
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()

        // 让最终索引写入失败；此时两本书都已完成目录切换，必须整体回滚。
        val blocker = File(context.filesDir, "books/index.json.tmp")
        assertTrue(blocker.mkdir())
        try {
            assertTrue(runCatching { library.restore(archive.inputStream()) }.isFailure)
        } finally {
            blocker.deleteRecursively()
        }

        assertArrayEquals(oldIndex, index.readBytes())
        for (entry in listOf(a, b)) {
            assertArrayEquals(oldSources.getValue(entry.id), library.bookFile(entry.id, entry.sources.single().file).readBytes())
            assertArrayEquals(oldCaches.getValue(entry.id), library.bookFile(entry.id, "cache.bin").readBytes())
        }
        assertEquals(marks, library.marks(a.id))
        assertEquals(listOf(Block.Para("旧甲正文。")), library.content(a).chapters.single().blocks)
        assertEquals(listOf(Block.Para("旧乙正文。")), library.content(b).chapters.single().blocks)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith(".restore-") })
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

        val existing = import("安全.txt", "旧正文".toByteArray())
        val indexFile = File(context.filesDir, "books/index.json")
        val oldIndex = indexFile.readBytes()
        val unsafeIndex = org.json.JSONArray(indexFile.readText())
        unsafeIndex.getJSONObject(0).getJSONArray("sources").getJSONObject(0).put("file", "../other/s0.txt")
        val unsafe = ByteArrayOutputStream()
        ZipOutputStream(unsafe).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            put("lingyue-backup.json", """{"format":1}""".toByteArray())
            put("books/index.json", unsafeIndex.toString().toByteArray())
            put("books/${existing.id}/s0.txt", "替身".toByteArray())
            put("books/other/s0.txt", "越界来源".toByteArray())
        }
        assertEquals(0, library.restore(unsafe.toByteArray().inputStream()).books)
        assertArrayEquals(oldIndex, indexFile.readBytes())
        assertEquals("旧正文", (library.content(existing).chapters.single().blocks.single() as Block.Para).text)
    }

    private companion object {
        // 1×1 的透明 PNG
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }
}
