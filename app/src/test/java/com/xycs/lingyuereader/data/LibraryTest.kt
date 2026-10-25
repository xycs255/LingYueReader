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
import java.util.concurrent.CancellationException
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

    private fun replacementBackup(
        replacements: Map<String, ByteArray>,
        backedUpMarks: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
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
                backedUpMarks[entry.id]?.let { put("books/${entry.id}/marks.json", it) }
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
    fun duplicateImportKeepsTheExistingBookAndItsReadingProgress() {
        val file = File(context.cacheDir, "重复来源.txt").apply { writeText("第一章 起\n　　同一份正文。\n") }
        val first = library.add(library.describe(listOf(Uri.fromFile(file))), null)
        library.saveProgress(first.id, ReadingProgress(0, "第一章 起", 1, 0, 2))
        val original = File(context.filesDir, "books/index.json").readBytes()

        val error = runCatching { library.add(library.describe(listOf(Uri.fromFile(file))), null) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("已在书架"))
        assertArrayEquals(original, File(context.filesDir, "books/index.json").readBytes())
        assertEquals(first.id, library.list().single().id)
        assertEquals(2, library.list().single().progress?.charOffset)
    }

    @Test
    fun emptyAndCorruptImportsGiveErrorsWithoutLeavingBookDirectories() {
        val empty = runCatching { import("空白.txt", byteArrayOf()) }.exceptionOrNull()
        assertTrue(empty?.message.orEmpty().contains("文件为空"))
        val corrupt = runCatching { import("破损.epub", "不是 EPUB 包".toByteArray()) }.exceptionOrNull()
        assertTrue(corrupt?.message.orEmpty().isNotBlank())
        assertTrue(library.list().isEmpty())
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun cancellingDuringImportLeavesNoPartialBookOrIndexEntry() {
        val existing = import("已有书.txt", "旧正文".toByteArray())
        val before = File(context.filesDir, "books/index.json").readBytes()
        val file = File(context.cacheDir, "取消中的长篇.txt").apply {
            writeText("第一章 开始\n" + "　　这是一段很长的中文正文。\n".repeat(20_000))
        }
        var checks = 0

        val error = runCatching {
            library.add(library.describe(listOf(Uri.fromFile(file))), null) {
                if (++checks >= 3) throw CancellationException("用户取消")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertArrayEquals(before, File(context.filesDir, "books/index.json").readBytes())
        assertEquals(existing.id, library.list().single().id)
        assertEquals(setOf("index.json", existing.id), File(context.filesDir, "books").listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun corruptIndexIsNeverTreatedAsAnEmptyShelfOrOverwritten() {
        import("索引中的书.txt", "有价值的正文".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        index.writeText("{broken index")
        val original = index.readBytes()

        assertTrue(runCatching { library.list() }.exceptionOrNull()?.message.orEmpty().contains("索引损坏"))
        assertTrue(runCatching { import("新书.txt", "新正文".toByteArray()) }.exceptionOrNull()?.message.orEmpty().contains("索引损坏"))
        assertTrue(runCatching { library.backup(ByteArrayOutputStream(), org.json.JSONObject()) }.isFailure)
        assertArrayEquals(original, index.readBytes())
    }

    @Test
    fun backupFailsWhenARequiredSourceCopyIsMissing() {
        val entry = import("失联.txt", "第一章 起\n　　原文。\n".toByteArray())
        val source = library.bookFile(entry.id, entry.sources.single().file)
        assertTrue(source.delete())
        val output = ByteArrayOutputStream()

        val error = runCatching { library.backup(output, org.json.JSONObject()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("来源文件缺失"))
        assertEquals(0, output.size())
        assertEquals(entry.id, library.list().single().id)
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
    fun unreadableRulesLeaveTheOldRulesCacheAndBookIntact() {
        val sourceText = "第一章 起\n　　旧正文。\n"
        val entry = import("规则失败.txt", sourceText.toByteArray())
        val oldRules = TextRules(listOf(ReplaceRule("旧", "原")))
        library.saveRules(entry, oldRules)
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val cache = library.bookFile(entry.id, "cache.bin")
        val oldCache = cache.readBytes()
        val source = library.bookFile(entry.id, entry.sources.single().file)

        val error = runCatching {
            library.saveRules(entry, TextRules(listOf(ReplaceRule(sourceText))))
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("没有读出正文"))
        assertEquals(oldRules, library.rules(entry.id))
        assertArrayEquals(oldIndex, index.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(sourceText.toByteArray(), source.readBytes())
        assertEquals(listOf(Block.Para("原正文。")), library.content(library.list().single()).chapters.single().blocks)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith(".rules-") })
    }

    @Test
    fun savingRulesRollsBackWhenTheIndexCannotBeSaved() {
        val entry = import("规则索引故障.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val cache = library.bookFile(entry.id, "cache.bin")
        val index = File(context.filesDir, "books/index.json")
        val oldSource = source.readBytes()
        val oldCache = cache.readBytes()
        val oldIndex = index.readBytes()
        val blocker = File(context.filesDir, "books/index.json.tmp")
        assertTrue(blocker.mkdir())
        try {
            assertTrue(runCatching { library.saveRules(entry, TextRules(listOf(ReplaceRule("旧", "新")))) }.isFailure)
        } finally {
            blocker.deleteRecursively()
        }

        assertEquals(TextRules(), library.rules(entry.id))
        assertArrayEquals(oldSource, source.readBytes())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(oldIndex, index.readBytes())
        assertEquals(listOf(Block.Para("旧正文。")), library.content(library.list().single()).chapters.single().blocks)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith(".rules-") })
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
    fun restoreWithoutMarksFileKeepsLocalBookmarks() {
        val entry = import("旧备份无书签.txt", "第一章 起\n　　旧文。\n".toByteArray())
        val marks = Marks(bookmarks = listOf(Bookmark("local", 0, "第一章 起", 1, 0, "旧文", 1)))
        library.saveMarks(entry.id, marks)
        val archive = replacementBackup(mapOf(entry.id to "第一章 起\n　　新文。\n".toByteArray()))

        assertEquals(1, library.restore(archive.inputStream()).books)
        assertEquals(marks, library.marks(entry.id))
        assertEquals(listOf(Block.Para("新文。")), library.content(library.list().single()).chapters.single().blocks)
    }

    @Test
    fun restoreRejectsDuplicateBookIdsBeforeReplacingTheLocalBook() {
        val entry = import("重复索引.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val marks = Marks(bookmarks = listOf(Bookmark("keep", 0, "第一章 起", 1, 0, "旧正文", 1)))
        library.saveMarks(entry.id, marks)
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val oldSource = source.readBytes()
        val marksFile = library.bookFile(entry.id, "marks.json")
        val oldMarks = marksFile.readBytes()
        val duplicateIndex = org.json.JSONArray(index.readText()).apply { put(getJSONObject(0)) }
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put("lingyue-backup.json", """{"format":1}""".toByteArray())
                put("books/index.json", duplicateIndex.toString().toByteArray())
                put("books/${entry.id}/${entry.sources.single().file}", "第一章 起\n　　替换正文。\n".toByteArray())
                put("books/${entry.id}/marks.json", Marks().toJson().toString().toByteArray())
            }
        }.toByteArray()

        val error = runCatching { library.restore(archive.inputStream()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("重复书籍 ID"))
        assertArrayEquals(oldIndex, index.readBytes())
        assertArrayEquals(oldSource, source.readBytes())
        assertArrayEquals(oldMarks, marksFile.readBytes())
        assertEquals(marks, library.marks(entry.id))
        assertEquals(listOf(Block.Para("旧正文。")), library.content(library.list().single()).chapters.single().blocks)
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

        val result = library.restore(archive.inputStream())
        assertEquals(1, result.books)
        assertEquals(2, result.skippedBooks)

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
    fun restoreSkipsCorruptMarksAndPreservesExistingBookmarksWhileRestoringOtherBooks() {
        val unsafe = import("旧书签.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val safe = import("有效书.txt", "第一章 起\n　　原正文。\n".toByteArray())
        val oldMarks = Marks(bookmarks = listOf(Bookmark("old", 0, "第一章 起", 1, 0, "旧正文", 1)))
        library.saveMarks(unsafe.id, oldMarks)
        val oldSource = library.bookFile(unsafe.id, unsafe.sources.single().file).readBytes()
        val oldEntry = library.list().first { it.id == unsafe.id }
        val newMarks = Marks(bookmarks = listOf(Bookmark("new", 0, "第一章 起", 1, 0, "新正文", 2)))
        val archive = replacementBackup(
            replacements = mapOf(
                unsafe.id to "第一章 起\n　　替换正文。\n".toByteArray(),
                safe.id to "第一章 起\n　　新正文。\n".toByteArray(),
            ),
            backedUpMarks = mapOf(
                unsafe.id to """{"bookmarks":"已损坏","notes":[]}""".toByteArray(),
                safe.id to newMarks.toJson().toString().toByteArray(),
            ),
        )

        val result = library.restore(archive.inputStream())

        assertEquals(1, result.books)
        assertEquals(1, result.skippedBooks)
        assertEquals(oldEntry, library.list().first { it.id == unsafe.id })
        assertArrayEquals(oldSource, library.bookFile(unsafe.id, unsafe.sources.single().file).readBytes())
        assertEquals(oldMarks, library.marks(unsafe.id))
        assertEquals(newMarks, library.marks(safe.id))
        assertEquals(listOf(Block.Para("新正文。")), library.content(library.list().first { it.id == safe.id }).chapters.single().blocks)
    }

    /** EPUB 目录里的空标题会原样成为章名，这样的书签和引文为空的批注也是应用自己写出来的，恢复时照收。 */
    @Test
    fun restoreKeepsMarksWithEmptyChapterTitles() {
        val entry = import("空章名.txt", "第一章 起\n　　正文。\n".toByteArray())
        val marks = Marks(
            bookmarks = listOf(Bookmark("b", 0, "", 1, 0, "", 1)),
            notes = listOf(Note("n", 0, "", 0, 0, 2, "", "", MarkColor.YELLOW, 1)),
        )
        val archive = replacementBackup(
            mapOf(entry.id to "第一章 起\n　　正文。\n".toByteArray()),
            mapOf(entry.id to marks.toJson().toString().toByteArray()),
        )
        val result = library.restore(archive.inputStream())
        assertEquals(1, result.books)
        assertEquals(0, result.skippedBooks)
        assertEquals(listOf("b"), library.marks(entry.id).bookmarks.map { it.id })
        assertEquals(listOf("n"), library.marks(entry.id).notes.map { it.id })
    }

    @Test
    fun restoreRejectsAllCorruptMarksWithoutChangingTheLocalBook() {
        val entry = import("本机书签.txt", "第一章 起\n　　仍能阅读。\n".toByteArray())
        val marks = Marks(bookmarks = listOf(Bookmark("keep", 0, "第一章 起", 1, 0, "仍能阅读", 1)))
        library.saveMarks(entry.id, marks)
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val oldSource = source.readBytes()
        val marksFile = library.bookFile(entry.id, "marks.json")
        val oldMarks = marksFile.readBytes()

        for (broken in listOf(
            "{broken".toByteArray(),
            """{"bookmarks":[],"notes":"已损坏"}""".toByteArray(),
            """{"bookmarks":[{"id":"缺少位置"}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[{"id":"b","chapter":-1,"title":"第一章 起","item":1,"char":0}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[{"id":"b","chapter":0,"title":"第一章 起","item":1,"char":0,"anchorOffset":"错"}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[{"id":"b","chapter":4294967297,"title":"第一章 起","item":1}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[{"id":"b","chapter":0,"title":"第一章 起","item":1,"at":9223372036854775808}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[{"id":"b","chapter":0,"title":"第一章 起","item":1,"lost":"错"}],"notes":[]}""".toByteArray(),
            """{"bookmarks":[],"notes":[{"id":"n","chapter":0,"title":"第一章 起","block":0,"start":0,"end":2,"quote":"正文","color":"UNKNOWN"}]}""".toByteArray(),
            """{"bookmarks":[],"notes":[{"id":"n","chapter":0,"title":"第一章 起","block":0,"start":5,"end":2,"quote":"正文","color":"YELLOW"}]}""".toByteArray(),
            """{"bookmarks":[],"notes":[{"id":"n","chapter":0,"title":"第一章 起","block":0,"start":0,"end":0,"quote":"","color":"YELLOW"}]}""".toByteArray(),
        )) {
            val archive = replacementBackup(
                mapOf(entry.id to "第一章 起\n　　替换正文。\n".toByteArray()),
                mapOf(entry.id to broken),
            )
            val error = runCatching { library.restore(archive.inputStream()) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("书签损坏"))
            assertArrayEquals(oldIndex, index.readBytes())
            assertArrayEquals(oldSource, source.readBytes())
            assertArrayEquals(oldMarks, marksFile.readBytes())
            assertEquals(marks, library.marks(entry.id))
            assertEquals(listOf(Block.Para("仍能阅读。")), library.content(entry).chapters.single().blocks)
        }
    }

    @Test
    fun restoreRejectsAnIncompleteNonemptyBackupBeforeReturningSettingsOrData() {
        val existing = import("本机书.txt", "第一章 起\n　　本机正文。\n".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val oldSource = library.bookFile(existing.id, existing.sources.single().file).readBytes()
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("lingyue-backup.json", """{"format":1,"settings":{"ui.mode":"DARK"}}""".toByteArray())
            put("books/index.json", oldIndex)
            put("data/vocabulary.json", "[]".toByteArray())
            // 索引声明有一本书，却没有对应的来源文件。
        }

        val error = runCatching { library.restore(archive.toByteArray().inputStream()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("备份中的书籍文件不完整"))
        assertArrayEquals(oldIndex, index.readBytes())
        assertArrayEquals(oldSource, library.bookFile(existing.id, existing.sources.single().file).readBytes())
    }

    @Test
    fun restoreRejectsWhenEveryReplacementIsUnreadable() {
        val existing = import("本机旧书.txt", "第一章 起\n　　仍能阅读。\n".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val source = library.bookFile(existing.id, existing.sources.single().file)
        val oldSource = source.readBytes()
        val archive = replacementBackup(mapOf(existing.id to byteArrayOf()))

        val error = runCatching { library.restore(archive.inputStream()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("均无法读取"))
        assertArrayEquals(oldIndex, index.readBytes())
        assertArrayEquals(oldSource, source.readBytes())
        assertEquals(listOf(Block.Para("仍能阅读。")), library.content(existing).chapters.single().blocks)
    }

    @Test
    fun restoreDoesNotInstallAnUnreadableNewEpub() {
        val entry = import("新书.epub", epub())
        val archive = replacementBackup(mapOf(entry.id to "损坏的 EPUB".toByteArray()))
        library.remove(entry.id)
        assertTrue(library.list().isEmpty())

        val error = runCatching { library.restore(archive.inputStream()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("均无法读取"))
        assertTrue(library.list().isEmpty())
        assertFalse(File(context.filesDir, "books/${entry.id}").exists())
    }

    @Test
    fun restoreStillAcceptsARealEmptyShelfBackupWithSettings() {
        val existing = import("本机书.txt", "第一章 起\n　　本机正文。\n".toByteArray())
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("lingyue-backup.json", """{"format":1,"settings":{"ui.mode":"DARK"}}""")
            put("books/index.json", "[]")
        }

        val result = library.restore(archive.toByteArray().inputStream())

        assertEquals(0, result.books)
        assertEquals(0, result.skippedBooks)
        assertEquals("DARK", result.settings?.getString("ui.mode"))
        assertEquals(existing.id, library.list().single().id)
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
        assertTrue(runCatching { library.restore(evil.toByteArray().inputStream()) }.isFailure)
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
        assertTrue(runCatching { library.restore(unsafe.toByteArray().inputStream()) }.isFailure)
        assertArrayEquals(oldIndex, indexFile.readBytes())
        assertEquals("旧正文", (library.content(existing).chapters.single().blocks.single() as Block.Para).text)
    }

    private companion object {
        // 1×1 的透明 PNG
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }

    @Test
    fun backupCarriesAiDataAndRestoreKeepsWhatTheBackupLacks() {
        val a = import("带笔记.txt", "第一章 起\n　　正文。\n".toByteArray())
        library.bookFile(a.id, "ai-notes.md").writeText("# 读书笔记")
        val out = ByteArrayOutputStream()
        library.backup(out, org.json.JSONObject())
        val names = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue(names.contains("books/${a.id}/ai-notes.md"))

        // 备份之后又生成了翻译：恢复旧备份不能把它删掉，备份里有的笔记照常恢复。
        library.bookFile(a.id, "ai-translation.json").writeText("{}")
        library.bookFile(a.id, "ai-notes.md").writeText("# 改过的笔记")
        assertEquals(1, library.restore(out.toByteArray().inputStream()).books)
        assertEquals("{}", library.bookFile(a.id, "ai-translation.json").readText())
        assertEquals("# 读书笔记", library.bookFile(a.id, "ai-notes.md").readText())
    }

    @Test
    fun unreadableMarksAreKeptAsideInsteadOfOverwritten() {
        val a = import("坏书签.txt", "第一章 起\n　　正文。\n".toByteArray())
        val file = library.bookFile(a.id, "marks.json")
        file.writeText("{\"bookmarks\": [")
        assertTrue(runCatching { library.marks(a.id) }.exceptionOrNull() is Library.CorruptMarksException)
        assertTrue(runCatching { library.saveMarks(a.id, Marks()) }.exceptionOrNull() is Library.CorruptMarksException)
        assertEquals("{\"bookmarks\": [", file.readText())
        assertEquals("{\"bookmarks\": [", library.bookFile(a.id, "marks.json.damaged").readText())
    }

    @Test
    fun malformedOptionalMarksFieldsCannotBeSilentlyRewritten() {
        val entry = import("错误字段书签.txt", "第一章 起\n　　正文。\n".toByteArray())
        val file = library.bookFile(entry.id, "marks.json")
        val broken = """{"bookmarks":[{"id":"b","chapter":0,"title":"第一章 起","item":1,"char":"bad"}],"notes":[]}"""
        file.writeText(broken)

        assertTrue(runCatching { library.marks(entry.id) }.exceptionOrNull() is Library.CorruptMarksException)
        assertTrue(runCatching { library.saveMarks(entry.id, Marks()) }.exceptionOrNull() is Library.CorruptMarksException)
        assertEquals(broken, file.readText())
        assertEquals(broken, library.bookFile(entry.id, "marks.json.damaged").readText())
    }

    @Test
    fun missingIndexWithBookDirectoriesBlocksWritesAndPreservesTheBook() {
        val entry = import("索引丢失.txt", "第一章 起\n　　原文。\n".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        val before = index.readBytes()
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val original = source.readBytes()
        val archive = replacementBackup(mapOf(entry.id to "第一章 起\n　　备份的新文。\n".toByteArray()))
        assertTrue(index.delete())

        val reopened = Library(context)
        assertTrue(runCatching { reopened.list() }.exceptionOrNull()?.message?.contains("索引缺失") == true)
        assertTrue(runCatching { reopened.rename(entry.id, "改名") }.isFailure)
        assertTrue(runCatching { reopened.restore(archive.inputStream()) }.isFailure)
        assertFalse(index.exists())
        assertArrayEquals(original, source.readBytes())

        index.writeBytes(before)
        assertEquals(entry.id, Library(context).list().single().id)
    }

    private fun writeSwapJournal(stage: File, ids: List<String>, before: ByteArray) {
        assertTrue(stage.mkdirs())
        File(stage, "index-before.json").writeBytes(before)
        File(stage, "ids.json").writeText(org.json.JSONArray(ids).toString())
        File(stage, "prepared-swap").writeText("1")
    }

    @Test
    fun interruptedRefreshWithNewDirectoryInstalledRestoresOriginalDirectoryAndIndex() {
        val entry = import("刷新中断后.txt", "第一章 起\n　　原文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val before = index.readBytes()
        val oldSource = library.bookFile(entry.id, entry.sources.single().file).readBytes()
        val stage = File(root, ".refresh-${entry.id}-123e4567-e89b-12d3-a456-426614174001")
        writeSwapJournal(stage, listOf(entry.id), before)
        val previous = File(stage, "previous/${entry.id}")
        previous.parentFile!!.mkdirs()
        assertTrue(File(root, entry.id).renameTo(previous))
        val replacement = File(root, entry.id).apply { mkdirs() }
        File(replacement, entry.sources.single().file).writeText("第一章 起\n　　新文。\n")

        val reopened = Library(context)
        assertArrayEquals(before, index.readBytes())
        assertArrayEquals(oldSource, reopened.bookFile(entry.id, entry.sources.single().file).readBytes())
        assertFalse(stage.exists())
        assertEquals(listOf(Block.Para("原文。")), reopened.content(reopened.list().single()).chapters.single().blocks)
    }

    @Test
    fun interruptedRestoreRollsBackAllReplacementsAndAnAddedBook() {
        val a = import("旧甲.txt", "第一章 起\n　　旧甲。\n".toByteArray())
        val b = import("旧乙.txt", "第一章 起\n　　旧乙。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val before = index.readBytes()
        val aSource = library.bookFile(a.id, a.sources.single().file).readBytes()
        val bSource = library.bookFile(b.id, b.sources.single().file).readBytes()
        val newId = "123e4567-e89b-12d3-a456-426614174002"
        val stage = File(root, ".restore-123e4567-e89b-12d3-a456-426614174003")
        writeSwapJournal(stage, listOf(a.id, b.id, newId), before)
        listOf(a, b).forEach { entry ->
            val previous = File(stage, "previous/${entry.id}")
            previous.parentFile!!.mkdirs()
            assertTrue(File(root, entry.id).renameTo(previous))
            File(root, entry.id).apply { mkdirs() }
            File(root, "${entry.id}/${entry.sources.single().file}").writeText("第一章 起\n　　新文。\n")
        }
        File(root, newId).apply { mkdirs() }
        File(root, "$newId/s0.txt").writeText("第一章 起\n　　新书。\n")

        val reopened = Library(context)
        assertArrayEquals(before, index.readBytes())
        assertArrayEquals(aSource, reopened.bookFile(a.id, a.sources.single().file).readBytes())
        assertArrayEquals(bSource, reopened.bookFile(b.id, b.sources.single().file).readBytes())
        assertFalse(File(root, newId).exists())
        assertFalse(stage.exists())
    }

    @Test
    fun missingCommitMarkerRollsBackEvenAfterNewIndexWasWritten() {
        val entry = import("索引已换.txt", "第一章 起\n　　原文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val before = index.readBytes()
        val stage = File(root, ".refresh-${entry.id}-123e4567-e89b-12d3-a456-426614174004")
        writeSwapJournal(stage, listOf(entry.id), before)
        val previous = File(stage, "previous/${entry.id}")
        previous.parentFile!!.mkdirs()
        assertTrue(File(root, entry.id).renameTo(previous))
        File(root, entry.id).apply { mkdirs() }
        File(root, "${entry.id}/${entry.sources.single().file}").writeText("第一章 起\n　　新文。\n")
        // 模拟事务自己写下的新索引（事务外的写入会先触发回滚，见 anUnfinishedRollbackBlocksWritesUntilItSucceeds）。
        index.writeText(org.json.JSONArray(index.readText()).apply { getJSONObject(0).put("title", "未提交的新标题") }.toString())
        assertFalse(index.readBytes().contentEquals(before))

        val reopened = Library(context)
        assertArrayEquals(before, index.readBytes())
        assertEquals(entry.title, reopened.list().single().title)
        assertEquals(listOf(Block.Para("原文。")), reopened.content(reopened.list().single()).chapters.single().blocks)
    }

    @Test
    fun committedSwapKeepsNewDirectoryAndIndexDuringStartupCleanup() {
        val entry = import("已提交.txt", "第一章 起\n　　原文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val before = index.readBytes()
        val stage = File(root, ".refresh-${entry.id}-123e4567-e89b-12d3-a456-426614174005")
        writeSwapJournal(stage, listOf(entry.id), before)
        val previous = File(stage, "previous/${entry.id}")
        previous.parentFile!!.mkdirs()
        assertTrue(File(root, entry.id).renameTo(previous))
        File(root, entry.id).apply { mkdirs() }
        File(root, "${entry.id}/${entry.sources.single().file}").writeText("第一章 起\n　　新文。\n")
        // 模拟事务自己写下的新索引（事务外的写入会先触发回滚，见 anUnfinishedRollbackBlocksWritesUntilItSucceeds）。
        index.writeText(org.json.JSONArray(index.readText()).apply { getJSONObject(0).put("title", "已提交的新标题") }.toString())
        val installedIndex = index.readBytes()
        File(stage, "index-after.json").writeBytes(installedIndex)
        File(stage, "committed-swap").writeText("1")

        val reopened = Library(context)
        assertArrayEquals(installedIndex, index.readBytes())
        assertEquals("已提交的新标题", reopened.list().single().title)
        assertEquals(listOf(Block.Para("新文。")), reopened.content(reopened.list().single()).chapters.single().blocks)
        assertFalse(stage.exists())
    }

    @Test
    fun committedSwapThatCannotRetireBlocksLaterIndexWritesUntilCleanup() {
        val entry = import("提交后清理失败.txt", "第一章 起\n　　旧文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val archive = replacementBackup(mapOf(entry.id to "第一章 起\n　　新文。\n".toByteArray()))
        library.retireSwapRename = { _, _ -> false }

        assertEquals(1, library.restore(archive.inputStream()).books)
        val installedIndex = index.readBytes()
        val pending = root.listFiles().orEmpty().single { it.name.startsWith(".restore-") && File(it, "committed-swap").isFile }
        assertTrue(File(pending, "committed-swap").isFile)
        assertTrue(runCatching { library.rename(entry.id, "之后的新标题") }.exceptionOrNull()?.message?.contains("停止后续写入") == true)
        assertArrayEquals(installedIndex, index.readBytes())

        library.retireSwapRename = { from, to -> from.renameTo(to) }
        library.rename(entry.id, "之后的新标题")
        assertFalse(pending.exists())
        val reopened = Library(context)
        assertEquals("之后的新标题", reopened.list().single().title)
        assertEquals(listOf(Block.Para("新文。")), reopened.content(reopened.list().single()).chapters.single().blocks)
    }

    @Test
    fun restoreValidatorRejectsDataBeforeAnyBookIsReplaced() {
        val entry = import("外部数据异常.txt", "第一章 起\n　　旧文。\n".toByteArray())
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val before = source.readBytes()
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        val archive = replacementBackup(mapOf(entry.id to "第一章 起\n　　新文。\n".toByteArray()))
        val error = runCatching { library.restore(archive.inputStream()) { throw IllegalArgumentException("扩展数据损坏") } }.exceptionOrNull()
        assertTrue(error?.message?.contains("扩展数据损坏") == true)
        assertArrayEquals(before, source.readBytes())
        assertArrayEquals(oldIndex, index.readBytes())
    }

    @Test
    fun oversizedBackupManifestIsRejectedBeforeReplacingExistingBooks() {
        val entry = import("过大备份.txt", "第一章 起\n　　旧文。\n".toByteArray())
        val source = library.bookFile(entry.id, entry.sources.single().file)
        val before = source.readBytes()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("lingyue-backup.json"))
            zip.write(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() })
            zip.closeEntry()
        }
        val error = runCatching { library.restore(out.toByteArray().inputStream()) }.exceptionOrNull()
        assertTrue(error?.message?.contains("解压内容过大") == true)
        assertArrayEquals(before, source.readBytes())
    }

    @Test
    fun comicsWithGbkFileNamesImportAndShowTheirPages() {
        // Windows 资源管理器打的中文包：文件名是 GBK、没标 UTF-8。
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, java.nio.charset.Charset.forName("GBK")).use { zip ->
            listOf("第一话/001.png", "第一话/002.png", "第二话/001.png").forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(PNG); zip.closeEntry()
            }
        }
        val entry = import("中文漫画.cbz", out.toByteArray())
        val content = library.content(entry)
        assertEquals(listOf("第一话", "第二话"), content.chapters.map { it.title })
        // 页面按同一种编码从包里读得出来。
        val first = (content.chapters[0].blocks[0] as Block.Image).src
        val images = com.xycs.lingyuereader.ui.BookImages { name -> library.bookFile(entry.id, name) }
        assertTrue(images.aspect(first) != null)
    }

    @Test
    fun backupsDoNotCarryDownloadLinksThatMayHoldKeys() {
        val a = import("书源下的.txt", "第一章 起\n　　正文。\n".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        index.writeText(index.readText().replace(Regex(""""uri":"[^"]*""""), """"uri":"https://kavita.example.com/api/opds/SECRET-KEY/series/1/download"""") )
        val out = ByteArrayOutputStream()
        library.backup(out, org.json.JSONObject())
        val packed = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "books/index.json" }.let { z.readBytes().toString(Charsets.UTF_8) }
        }
        assertFalse(packed.contains("SECRET-KEY"))
        assertTrue(packed.contains(a.id))
    }

    @Test
    fun anInterruptedRefreshIsUndoneOnTheNextStart() {
        val a = import("刷新中断.txt", "第一章 起\n　　正文。\n".toByteArray())
        val dir = File(context.filesDir, "books/${a.id}")
        // 刷新正要换上新目录时进程被杀：旧目录在临时目录里，书的目录没了。
        val staging = File(context.filesDir, "books/.refresh-${a.id}-123e4567-e89b-12d3-a456-426614174000")
        staging.mkdirs()
        assertTrue(dir.renameTo(File(staging, "previous")))
        val reopened = Library(context)
        assertTrue(dir.isDirectory)
        assertFalse(staging.exists())
        assertEquals(listOf(Block.Para("正文。")), reopened.content(reopened.list().first { it.id == a.id }).chapters.single().blocks)
    }

    private fun backupBytes(): ByteArray = ByteArrayOutputStream().also { library.backup(it, org.json.JSONObject()) }.toByteArray()

    /** 备份和恢复用同一套上限：恢复时会被拒的文件，备份时当场报错，不写出一个恢复不了的 zip。 */
    @Test
    fun backupRefusesFilesThatRestoreWouldReject() {
        import("顾长风传.txt", ("第一章 起\n　　" + "顾长风走过长街。".repeat(20) + "\n").toByteArray())
        library.shelfSourceLimit = { 64L }
        val output = ByteArrayOutputStream()

        val error = runCatching { library.backup(output, org.json.JSONObject()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("备份后无法恢复"))
        assertTrue(error?.message.orEmpty().contains("顾长风传"))
        assertEquals(0, output.size())
    }

    /** 单本书超过上限只跳过那一本（列在结果里），其余的书照常恢复。 */
    @Test
    fun restoreSkipsAnOversizedBookAndRestoresTheRest() {
        val big = import("沈默长篇.txt", ("第一章 起\n　　" + "沈默在山里走了很久。".repeat(20) + "\n").toByteArray())
        val small = import("老周.txt", "第一章 起\n　　老周。\n".toByteArray())
        val archive = backupBytes()
        library.remove(big.id)
        library.remove(small.id)
        library.shelfSourceLimit = { 64L }

        val result = library.restore(archive.inputStream())

        assertEquals(1, result.books)
        assertEquals(1, result.skippedBooks)
        assertEquals(listOf("沈默长篇"), result.skippedTitles)
        assertEquals(listOf(small.id), library.list().map { it.id })
        assertFalse(File(context.filesDir, "books/${big.id}").exists())
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith(".unpack-") || it.name.startsWith(".restore-") })
    }

    /** 书全都超过上限时，设置和附加数据照常返回，不当成坏备份整份拒绝。 */
    @Test
    fun restoreStillReturnsSettingsWhenEveryBookIsOversized() {
        val big = import("杜娘.txt", ("第一章 起\n　　" + "杜娘把灯点上。".repeat(20) + "\n").toByteArray())
        val archive = ByteArrayOutputStream().also {
            library.backup(it, org.json.JSONObject().put("ui.mode", org.json.JSONObject().put("s", "DARK")), mapOf("vocabulary.json" to "[]".toByteArray()))
        }.toByteArray()
        library.remove(big.id)
        library.shelfSourceLimit = { 64L }

        val result = library.restore(archive.inputStream())

        assertEquals(0, result.books)
        assertEquals(1, result.skippedBooks)
        assertEquals("DARK", result.settings?.getJSONObject("ui.mode")?.getString("s"))
        assertEquals("[]", result.data["vocabulary.json"])
        assertTrue(library.list().isEmpty())
    }

    /** 不再按固定总量拒绝大书架，改为检查剩余空间；空间不够时不改动现有的书。 */
    @Test
    fun restoreStopsWithoutChangesWhenStorageRunsOut() {
        val entry = import("林月.txt", "第一章 起\n　　旧正文。\n".toByteArray())
        val archive = replacementBackup(mapOf(entry.id to "第一章 起\n　　新正文。\n".toByteArray()))
        val index = File(context.filesDir, "books/index.json")
        val oldIndex = index.readBytes()
        library.freeSpace = { 0L }

        val error = runCatching { library.restore(archive.inputStream()) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("存储空间不足"))
        assertArrayEquals(oldIndex, index.readBytes())
        assertEquals(listOf(Block.Para("旧正文。")), library.content(entry).chapters.single().blocks)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith(".unpack-") || it.name.startsWith(".restore-") })
    }

    /** 已在书架上的书不受新的导入上限约束：旧版导入的大文件缓存失效后照样能读。 */
    @Test
    fun shelfSourcesAreNotHeldToTheImportLimit() {
        val file = File.createTempFile("oversized-shelf-book", ".txt")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(129L * 1024 * 1024) }
            assertTrue(runCatching { BookParser.SourceBytes(file.name, file).bytes }.exceptionOrNull()?.message.orEmpty().contains("文件过大"))
            assertEquals(129 * 1024 * 1024, BookParser.SourceBytes(file.name, file, onShelf = true).bytes.size)
        } finally {
            file.delete()
        }
    }

    /** 删书删到一半被杀，留下同 ID 的目录：恢复把它挪开，而不是整份失败。 */
    @Test
    fun restoreMovesAsideAnUnindexedDirectoryWithTheSameId() {
        val entry = import("顾长风.txt", "第一章 起\n　　备份里的正文。\n".toByteArray())
        val archive = backupBytes()
        library.remove(entry.id)
        File(context.filesDir, "books/${entry.id}").mkdirs()
        File(context.filesDir, "books/${entry.id}/s0.txt").writeText("删到一半")

        assertEquals(1, library.restore(archive.inputStream()).books)

        val restored = library.list().single()
        assertEquals(entry.id, restored.id)
        assertEquals(listOf(Block.Para("备份里的正文。")), library.content(restored).chapters.single().blocks)
    }

    /** 导入或删书被杀留下的无主目录、退役的事务目录、解压临时目录，在后台清理；书架上的书不动。 */
    @Test
    fun leftoverDirectoriesAreCleanedUpInTheBackground() {
        val entry = import("老周.txt", "第一章 起\n　　老周的正文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val orphan = File(root, "123e4567-e89b-12d3-a456-426614174010").apply { mkdirs() }
        File(orphan, "s0.txt").writeText("导入到一半")
        val retired = File(root, ".swap-cleanup-123e4567-e89b-12d3-a456-426614174011").apply { mkdirs() }
        File(retired, "old.txt").writeText("旧副本")
        val unpack = File(root, ".unpack-123e4567-e89b-12d3-a456-426614174012").apply { mkdirs() }

        library.cleanUpLeftovers()

        assertFalse(orphan.exists())
        assertFalse(retired.exists())
        assertFalse(unpack.exists())
        assertEquals(setOf("index.json", entry.id), root.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(listOf(Block.Para("老周的正文。")), library.content(entry).chapters.single().blocks)
    }

    /** 索引有读不出的记录时，不认定任何书目录无主。 */
    @Test
    fun cleanupKeepsUnindexedDirectoriesWhileTheIndexIsDamaged() {
        import("杜娘.txt", "第一章 起\n　　正文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val unknown = File(root, "123e4567-e89b-12d3-a456-426614174013").apply { mkdirs() }
        val index = File(root, "index.json")
        index.writeText(org.json.JSONArray(index.readText()).put(org.json.JSONObject().put("id", "坏记录")).toString())

        library.cleanUpLeftovers()

        assertTrue(unknown.isDirectory)
    }

    private fun failedRestoreJournal(entry: BookEntry, snapshot: ByteArray): File {
        val root = File(context.filesDir, "books")
        val stage = File(root, ".restore-123e4567-e89b-12d3-a456-426614174020")
        writeSwapJournal(stage, listOf(entry.id), snapshot)
        val previous = File(stage, "previous/${entry.id}")
        previous.parentFile!!.mkdirs()
        assertTrue(File(root, entry.id).renameTo(previous))
        File(root, entry.id).mkdirs()
        File(root, "${entry.id}/${entry.sources.single().file}").writeText("第一章 起\n　　未提交的新文。\n")
        return stage
    }

    /** 事务失败、回滚也没成功：之后写书架先重试回滚，仍不成功就拒绝写入，不能让期间的改动在下次启动时被旧快照冲掉。 */
    @Test
    fun anUnfinishedRollbackBlocksWritesUntilItSucceeds() {
        val entry = import("林月.txt", "第一章 起\n　　原文。\n".toByteArray())
        val index = File(context.filesDir, "books/index.json")
        val before = index.readBytes()
        // 快照暂时读不出，模拟回滚无法完成。
        val stage = failedRestoreJournal(entry, "{broken".toByteArray())

        val error = runCatching { library.rename(entry.id, "改名") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("已停止写入书架"))
        assertTrue(runCatching { library.saveMarks(entry.id, Marks()) }.isFailure)
        assertArrayEquals(before, index.readBytes())

        // 快照可读以后（比如清出了空间），下一次写入先完成回滚，再写。
        File(stage, "index-before.json").writeBytes(before)
        library.rename(entry.id, "改名")
        assertFalse(stage.exists())
        assertEquals("改名", library.list().single().title)
        assertEquals(listOf(Block.Para("原文。")), library.content(library.list().single()).chapters.single().blocks)
    }

    /** 启动时回滚失败不能让应用每次启动都闪退：书架照常读得出、写入被拒，并给出提示。 */
    @Test
    fun aFailedStartupRecoveryDegradesToReadOnlyInsteadOfThrowing() {
        val entry = import("沈默.txt", "第一章 起\n　　原文。\n".toByteArray())
        failedRestoreJournal(entry, "{broken".toByteArray())

        val reopened = Library(context)

        assertTrue(reopened.startupProblem.orEmpty().contains("暂时无法改动书架"))
        assertEquals(entry.id, reopened.list().single().id)
        assertTrue(runCatching { reopened.rename(entry.id, "改名") }.isFailure)
        assertEquals(entry.title, reopened.list().single().title)
    }

    /** 规则回滚完成后清理目录失败：留下的目录带着“已回滚”标记，不能再拿旧索引快照覆盖之后写下的索引。 */
    @Test
    fun aRolledBackRulesStagingNeverRestoresItsOldIndexSnapshotAgain() {
        val entry = import("规则已回滚.txt", "第一章 起\n　　正文。\n".toByteArray())
        val root = File(context.filesDir, "books")
        val index = File(root, "index.json")
        val stage = File(root, ".rules-${entry.id}-123e4567-e89b-12d3-a456-426614174030").apply { mkdirs() }
        File(stage, "index-before.json").writeBytes(index.readBytes())
        File(stage, "abandoned").mkdirs()
        File(stage, "rolled-back").writeText("1")
        library.rename(entry.id, "之后改的名字")
        val after = index.readBytes()

        val reopened = Library(context)

        assertArrayEquals(after, index.readBytes())
        assertEquals("之后改的名字", reopened.list().single().title)
        assertFalse(stage.exists())
    }

    /** 打开书时正在重建缓存，刷新要等重建写完才换目录：旧内容的缓存不能写进新目录、还被当成有效缓存。 */
    @Test
    fun refreshWaitsForAnOngoingCacheRebuildBeforeSwappingDirectories() {
        val pdf = File(context.cacheDir, "杜娘画册.pdf").apply { writeBytes(ByteArray(10) { 1 }) }
        val pages: (File) -> List<Float> = { f -> List(f.length().toInt() / 10) { 1.4f } }
        try {
            BookParser.pdfAspects = pages
            val entry = library.add(library.describe(listOf(Uri.fromFile(pdf))), null)
            assertTrue(library.bookFile(entry.id, "cache.bin").delete())
            val inBuild = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            BookParser.pdfAspects = { f ->
                if (calls.getAndIncrement() == 0) {
                    inBuild.countDown()
                    proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                pages(f)
            }
            val reader = kotlin.concurrent.thread { library.content(entry) }
            assertTrue(inBuild.await(10, java.util.concurrent.TimeUnit.SECONDS))
            pdf.writeBytes(ByteArray(20) { 2 })
            var refreshed: Library.RefreshResult? = null
            val refresher = kotlin.concurrent.thread { refreshed = library.refresh(entry) }
            Thread.sleep(300)
            // 重建还没写完：刷新只能等着，书目录仍是旧的。
            assertEquals(10L, library.bookFile(entry.id, entry.sources.single().file).length())
            proceed.countDown()
            reader.join(10_000)
            refresher.join(10_000)

            assertEquals(Library.RefreshResult(1, 0), refreshed)
            val pagesNow = library.content(library.list().single()).chapters.flatMap { it.blocks }.filterIsInstance<Block.Image>()
            assertEquals(2, pagesNow.size)
        } finally {
            BookParser.pdfAspects = PdfPages::aspects
        }
    }

    /** 漫画包里的封面条目超过上限时不解出，也不在书籍目录里留下半截文件。 */
    @Test
    fun oversizedComicCoverIsNotExtracted() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("001.png")); zip.write(PNG); zip.write(ByteArray(33 * 1024 * 1024)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.png")); zip.write(PNG); zip.closeEntry()
        }
        val entry = import("超大封面.cbz", out.toByteArray())

        assertEquals(null, entry.cover)
        val images = File(context.filesDir, "books/${entry.id}/img").listFiles().orEmpty()
        assertTrue(images.all { it.length() < 1024 * 1024 })
        assertEquals(2, library.content(entry).chapters.single().blocks.filterIsInstance<Block.Image>().size)
    }
}
