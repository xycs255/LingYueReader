package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.time.LocalDate

class WritingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = WorkStore(tmp.newFolder())

    @Test
    fun newWorkHasOneVolumeAndAnEmptyFirstChapter() {
        val s = store()
        val w = s.create("  长夜  ", "某某")
        assertEquals("长夜", w.title)
        assertEquals(1, w.volumes.size)
        assertEquals(WorkStore.DEFAULT_VOLUME, w.volumes[0].title)
        assertEquals(listOf("第1章"), w.chapters.map { it.title })
        assertEquals(listOf(w), s.list())
        assertEquals("", s.text(w.id, w.chapters[0].id))
    }

    @Test
    fun savingCountsWordsAndAddsTheNetChangeToToday() {
        val s = store()
        val w = s.create("书")
        val c = w.chapters[0].id
        val day = LocalDate.of(2026, 10, 24)
        val after = s.saveText(w.id, c, "他走进院子。\n  看见一个人。\n", day)!!
        // 空白不算，标点算。
        assertEquals(12, after.chapter(c)!!.words)
        assertEquals(12, after.words)
        assertEquals(12, s.stats().on(day))
        // 删掉一些字，当天的数扣掉。
        s.saveText(w.id, c, "他走进院子。", day)
        assertEquals(6, s.stats().on(day))
        // 正文没变不重写。
        val same = s.saveText(w.id, c, "他走进院子。", day)!!
        assertEquals(6, same.chapter(c)!!.words)
        assertEquals(6, s.stats().on(day))
    }

    @Test
    fun chaptersCanBeAddedRenamedMovedAcrossVolumesAndDeleted() {
        val s = store()
        val w = s.create("书")
        val v1 = w.volumes[0].id
        val (_, c2) = s.addChapter(w.id, v1)!!
        assertEquals("第2章", c2.title)
        val withVolume = s.addVolume(w.id, "")!!
        assertEquals("第2卷", withVolume.volumes[1].title)
        val v2 = withVolume.volumes[1].id
        val (_, c3) = s.addChapter(w.id, v2, "  风起  ")!!
        assertEquals("风起", c3.title)

        // 卷末往后挪，到下一卷开头。
        var moved = s.moveChapter(w.id, c2.id, 1)!!
        assertEquals(listOf("第1章"), moved.volumes[0].chapters.map { it.title })
        assertEquals(listOf("第2章", "风起"), moved.volumes[1].chapters.map { it.title })
        // 卷首往前挪，回到上一卷末尾。
        moved = s.moveChapter(w.id, c2.id, -1)!!
        assertEquals(listOf("第1章", "第2章"), moved.volumes[0].chapters.map { it.title })
        // 已经在最前面：不动。
        assertEquals(moved.chapters, s.moveChapter(w.id, w.chapters[0].id, -1)!!.chapters)

        s.renameChapter(w.id, c3.id, "风起云涌")
        assertEquals("风起云涌", s.get(w.id)!!.chapter(c3.id)!!.title)

        s.saveText(w.id, c3.id, "要删的这一章。")
        val afterDelete = s.deleteChapter(w.id, c3.id)!!
        assertNull(afterDelete.chapter(c3.id))
        // 删前的正文留在历史版本里。
        val kept = s.history(w.id, c3.id).single()
        assertEquals("要删的这一章。", s.snapshotText(w.id, c3.id, kept.at))

        // 只剩一卷时不能删；两卷时删掉一卷连同卷里的章。
        val two = s.deleteVolume(w.id, v2)!!
        assertEquals(1, two.volumes.size)
        assertEquals(1, s.deleteVolume(w.id, v1)!!.volumes.size)
    }

    @Test
    fun snapshotsSkipDuplicatesKeepTheNewestAndRestoreSavesTheCurrentText() {
        val s = store()
        val w = s.create("书")
        val c = w.chapters[0].id
        assertTrue(s.snapshot(w.id, c, "第一稿", at = 1000))
        assertFalse(s.snapshot(w.id, c, "第一稿", at = 2000))
        assertTrue(s.snapshot(w.id, c, "第二稿", at = 3000))
        assertEquals(listOf(3000L, 1000L), s.history(w.id, c).map { it.at })

        s.saveText(w.id, c, "现在的稿")
        val restored = s.restore(w.id, c, 1000)!!
        assertEquals("第一稿", s.text(w.id, c))
        assertEquals(3, restored.chapter(c)!!.words)
        // 换回之前的正文存了一版。
        val newest = s.history(w.id, c).first()
        assertEquals("现在的稿", s.snapshotText(w.id, c, newest.at))

        repeat(WorkStore.KEEP_SNAPSHOTS + 5) { i -> s.snapshot(w.id, c, "稿$i", at = 10_000L + i) }
        assertEquals(WorkStore.KEEP_SNAPSHOTS, s.history(w.id, c).size)
        assertEquals("稿${WorkStore.KEEP_SNAPSHOTS + 4}", s.snapshotText(w.id, c, s.history(w.id, c).first().at))
    }

    @Test
    fun streakCountsDaysThatMetTheGoal() {
        val today = LocalDate.of(2026, 10, 24)
        val days = mapOf(today to 500, today.minusDays(1) to 3200, today.minusDays(2) to 3000, today.minusDays(3) to 100)
        // 今天还没达标不算断。
        assertEquals(2, WritingStats(days, goal = 3000).streak(today))
        assertEquals(4, WritingStats(days, goal = 0).streak(today))
        assertEquals(0, WritingStats(mapOf(today.minusDays(2) to 5000), goal = 1000).streak(today))
    }

    private fun sample(s: WorkStore): Work {
        var w = s.create("长夜", "某某")
        s.update(w.id) { it.copy(synopsis = "一个关于等待的故事。") }
        val c1 = w.chapters[0].id
        s.renameChapter(w.id, c1, "第一章 雪夜")
        s.saveText(w.id, c1, "他推开门。\n\n　　风雪灌进来，带着**刺骨**的寒意。\n***\n<天亮了> & 一切照旧。")
        w = s.addVolume(w.id, "第二卷 春")!!
        val (_, c2) = s.addChapter(w.id, w.volumes[1].id, "第二章 空章")!!
        return s.get(w.id)!!.also { assertNotNull(it.chapter(c2.id)) }
    }

    @Test
    fun txtExportIndentsParagraphsAndPrintsVolumes() {
        val s = store()
        val w = sample(s)
        val txt = WorkExport.txt(w) { s.text(w.id, it.id) }
        assertTrue(txt.startsWith("长夜\n作者：某某\n"))
        assertTrue("　　一个关于等待的故事。" in txt)
        assertTrue("\n\n正文\n" in txt)
        assertTrue("\n\n第二卷 春\n" in txt)
        assertTrue("第一章 雪夜\n\n　　他推开门。\n　　风雪灌进来，带着刺骨的寒意。\n\n　　* * *\n　　<天亮了> & 一切照旧。\n" in txt)
        val md = WorkExport.markdown(w) { s.text(w.id, it.id) }
        assertTrue(md.startsWith("# 长夜\n"))
        assertTrue("\n## 第一章 雪夜\n\n他推开门。\n" in md)
        assertTrue("\n# 第二卷 春\n" in md)
    }

    @Test
    fun singleDefaultVolumeIsNotPrinted() {
        val s = store()
        val w = s.create("短篇")
        s.saveText(w.id, w.chapters[0].id, "只有一段。")
        val txt = WorkExport.txt(s.get(w.id)!!) { s.text(w.id, it.id) }
        assertFalse("正文" in txt)
        assertTrue("第1章\n\n　　只有一段。\n" in txt)
    }

    @Test
    fun epubExportReadsBackWithTheSameChapters() {
        val s = store()
        val w = sample(s)
        val out = ByteArrayOutputStream()
        WorkExport.epub(w, { s.text(w.id, it.id) }, out, placeholders = true)
        val book = EpubParser.parse(out.toByteArray(), "x")
        assertEquals("长夜", book.title)
        assertEquals("某某", book.author)
        assertEquals("一个关于等待的故事。", book.description)
        // 卷名页没有正文，不单独成章：预览里的章和作品里的章一一对应。
        assertEquals(listOf("第一章 雪夜", "第二章 空章"), book.chapters.map { it.title })
        val blocks = book.chapters[0].blocks
        assertEquals("他推开门。", blocks[0].plainText)
        assertEquals("风雪灌进来，带着刺骨的寒意。", blocks[1].plainText)
        assertEquals(Block.Rule, blocks[2])
        assertEquals("<天亮了> & 一切照旧。", blocks[3].plainText)
        assertEquals("（这一章还没有内容）", book.chapters[1].blocks.single().plainText)

        // 正式导出不放占位的话：空章没有正文，阅读器里就略过了。
        val plain = ByteArrayOutputStream()
        WorkExport.epub(w, { s.text(w.id, it.id) }, plain)
        assertEquals(listOf("第一章 雪夜"), EpubParser.parse(plain.toByteArray(), "x").chapters.map { it.title })
    }

    @Test
    fun backupRestoresWorksAndKeepsNewerLocalCopies() {
        val a = store()
        val w = sample(a)
        a.addWords(LocalDate.of(2026, 10, 1), 800)
        a.setGoal(2000)
        val bytes = a.backup()!!

        // 换了手机：全部恢复。
        val b = store()
        assertEquals(1, b.restoreBackup(String(bytes)))
        val restored = b.get(w.id)!!
        assertEquals(w.chapters.map { it.title }, restored.chapters.map { it.title })
        assertEquals(a.text(w.id, w.chapters[0].id), b.text(w.id, w.chapters[0].id))
        assertEquals(2000, b.stats().goal)
        assertTrue(b.stats().on(LocalDate.of(2026, 10, 1)) >= 800)

        // 本机后来又改过：本机的新，留着本机的。
        Thread.sleep(5)
        b.saveText(w.id, w.chapters[0].id, "本机改过的稿。")
        assertEquals(0, b.restoreBackup(String(bytes)))
        assertEquals("本机改过的稿。", b.text(w.id, w.chapters[0].id))

        // 备份的更新：换成备份的，本机的稿留一个历史版本。
        Thread.sleep(5)
        a.saveText(w.id, w.chapters[0].id, "电脑上改过的新稿。")
        assertEquals(1, b.restoreBackup(String(a.backup()!!)))
        assertEquals("电脑上改过的新稿。", b.text(w.id, w.chapters[0].id))
        val kept = b.history(w.id, w.chapters[0].id).map { b.snapshotText(w.id, w.chapters[0].id, it.at) }
        assertTrue("本机改过的稿。" in kept)
    }

    @Test
    fun emptyStoreHasNoBackupAndBadIdsAreRejected() {
        val s = store()
        assertNull(s.backup())
        assertNull(s.get("../x"))
        val evil = """{"works":[{"id":"../../evil","title":"x","volumes":[]}]}"""
        assertTrue(runCatching { s.restoreBackup(evil) }.isFailure)
    }

    @Test
    fun corruptWorkMetadataIsReportedInsteadOfSilentlyOmittedFromBackup() {
        val folder = tmp.newFolder()
        val s = WorkStore(folder)
        val work = s.create("不能遗漏")
        val other = s.create("好好的")
        s.saveText(other.id, other.chapters.single().id, "林月推开门。")
        File(folder, "works/${work.id}/work.json").writeText("""{"id":"${work.id}","title":"不能遗漏","volumes":[""")
        // 坏的那部不再拖垮整份备份：别的作品照常备份，坏的报告出来（认得出书名）。
        val report = s.backupReport()!!
        assertEquals(listOf("不能遗漏"), report.skipped.map { it.work })
        assertEquals("work.json", report.skipped.single().file)
        val works = JSONObject(String(report.bytes)).getJSONArray("works")
        assertEquals(listOf(other.id), (0 until works.length()).map { works.getJSONObject(it).getString("id") })
        assertTrue(File(folder, "works/${work.id}/work.json.damaged").isFile)
    }

    @Test
    fun corruptStatsSkipsCountingButNeverBlocksSavingOrBackup() {
        val folder = tmp.newFolder()
        val s = WorkStore(folder)
        val work = s.create("保留统计")
        val chapter = work.chapters.single().id
        val source = File(folder, "stats.json")
        source.writeText("{damaged stats")
        // 正文照常保存，只是不计数；坏的统计文件不被覆盖，另存了一份。
        assertNotNull(s.saveText(work.id, chapter, "新稿"))
        assertEquals("新稿", s.text(work.id, chapter))
        s.addWords(LocalDate.of(2026, 10, 25), 20)
        assertEquals("{damaged stats", source.readText())
        assertEquals("{damaged stats", File(folder, "stats.json.damaged").readText())
        // 备份照常做，统计不放进去并报告出来。
        val report = s.backupReport()!!
        assertEquals(listOf("stats.json"), report.skipped.map { it.file })
        assertFalse(JSONObject(String(report.bytes)).has("days"))
        // 设目标就是修好的路：从空的统计重新记，坏的内容还在副本里。
        s.setGoal(2000)
        assertEquals(2000, s.stats().goal)
        assertEquals("{damaged stats", File(folder, "stats.json.damaged").readText())
        s.saveText(work.id, chapter, "新稿再加几个字")
        assertEquals(5, s.stats().on(LocalDate.now()))
    }

    @Test
    fun negativeDailyNetChangeRemainsValidWritingStats() {
        val source = WorkStore(tmp.newFolder())
        val day = LocalDate.of(2026, 10, 25)
        source.addWords(day, -5)
        source.setGoal(100)
        val destination = WorkStore(tmp.newFolder())
        val archive = String(source.backup()!!)
        destination.validateBackup(archive)
        destination.restoreBackup(archive)
        // Existing merge semantics keep the larger per-day count; the source file
        // itself must still be readable and writable with a negative net change.
        assertEquals(-5, source.stats().on(day))
        source.setGoal(200)
        assertEquals(200, source.stats().goal)
    }

    @Test
    fun missingTextOrMalformedLaterWorkRejectsWholeBackupBeforeWriting() {
        val source = WorkStore(tmp.newFolder())
        val first = source.create("第一部")
        source.saveText(first.id, first.chapters.single().id, "正文甲")
        val second = source.create("第二部")
        source.saveText(second.id, second.chapters.single().id, "正文乙")
        val archive = JSONObject(String(source.backup()!!))
        val workEntries = archive.getJSONArray("works")
        val secondJson = (0 until workEntries.length()).map { workEntries.getJSONObject(it) }
            .first { it.getString("id") == second.id }
        secondJson.getJSONObject("texts").remove(second.chapters.single().id)

        val destination = WorkStore(tmp.newFolder())
        assertTrue(runCatching { destination.validateBackup(archive.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(archive.toString()) }.isFailure)
        assertNull(destination.get(first.id))
        assertNull(destination.get(second.id))
        assertTrue(destination.list().isEmpty())
    }

    @Test
    fun legacyBackupWithoutOptionalWritingComponentsStillRestoresText() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("旧备份")
        val chapter = work.chapters.single().id
        source.saveText(work.id, chapter, "旧格式里的正文")
        val archive = JSONObject(String(source.backup()!!))
        archive.getJSONArray("works").getJSONObject(0).apply {
            remove("cards")
            remove("pack")
            remove("changes")
            remove("assist")
        }
        val destination = WorkStore(tmp.newFolder())
        destination.validateBackup(archive.toString())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals("旧格式里的正文", destination.text(work.id, chapter))
    }

    @Test
    fun malformedCardAndPackAreRejectedWithoutReplacingLocalText() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("验证附件")
        val chapter = work.chapters.single().id
        source.saveText(work.id, chapter, "备份正文")
        source.saveCard(work.id, chapter, ChapterCard(facts = "事实"))
        source.addPackDoc(work.id, PackKind.REFERENCE, "设定", "设定正文")
        val archive = String(source.backup()!!)
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(archive))
        destination.saveText(work.id, chapter, "本机正文")

        val badCard = JSONObject(archive)
        badCard.getJSONArray("works").getJSONObject(0).getJSONObject("cards").put(chapter, "bad")
        assertTrue(runCatching { destination.validateBackup(badCard.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(badCard.toString()) }.isFailure)

        val badPack = JSONObject(archive)
        badPack.getJSONArray("works").getJSONObject(0).getJSONArray("pack").getJSONObject(0).put("text", 17)
        assertTrue(runCatching { destination.validateBackup(badPack.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(badPack.toString()) }.isFailure)

        val badAuto = JSONObject(archive)
        badAuto.getJSONArray("works").getJSONObject(0).getJSONObject("cards")
            .getJSONObject(chapter).put("auto", "invalid")
        assertTrue(runCatching { destination.validateBackup(badAuto.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(badAuto.toString()) }.isFailure)

        val badPackTime = JSONObject(archive)
        badPackTime.getJSONArray("works").getJSONObject(0).getJSONArray("pack")
            .getJSONObject(0).put("updatedAt", "yesterday")
        assertTrue(runCatching { destination.validateBackup(badPackTime.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(badPackTime.toString()) }.isFailure)

        val badPackFlag = JSONObject(archive)
        badPackFlag.getJSONArray("works").getJSONObject(0).getJSONArray("pack")
            .getJSONObject(0).put("always", "false")
        assertTrue(runCatching { destination.validateBackup(badPackFlag.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(badPackFlag.toString()) }.isFailure)

        val overflow = JSONObject(archive).put("goal", Int.MAX_VALUE.toLong() + 1)
        assertTrue(runCatching { destination.validateBackup(overflow.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(overflow.toString()) }.isFailure)
        assertEquals("本机正文", destination.text(work.id, chapter))
    }

    @Test
    fun olderBackupKeepsLegacyPackAndCardEditsWhoseWorkTimestampDidNotChange() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("设定")
        val chapter = work.chapters.single().id
        source.saveCard(work.id, chapter, ChapterCard(facts = "旧细纲"))
        val doc = source.addPackDoc(work.id, PackKind.REFERENCE, "资料", "旧设定")
        val original = JSONObject(String(source.backup()!!))
        val destinationFolder = tmp.newFolder()
        val destination = WorkStore(destinationFolder)
        assertEquals(1, destination.restoreBackup(original.toString()))
        destination.saveCard(work.id, chapter, ChapterCard(facts = "新细纲"))
        destination.savePackText(work.id, doc.id, "新设定")

        // Legacy versions saved these components without touching work.json. Even an
        // apparently newer backup must not replace newer component revisions.
        val workFile = File(destinationFolder, "works/${work.id}/work.json")
        val metadata = JSONObject(workFile.readText())
        workFile.writeText(metadata.put("updatedAt", original.getJSONArray("works").getJSONObject(0).getLong("updatedAt")).toString())
        original.getJSONArray("works").getJSONObject(0).put("updatedAt", System.currentTimeMillis() + 100_000)
        assertEquals(0, destination.restoreBackup(original.toString()))
        assertEquals("新细纲", destination.card(work.id, chapter).facts)
        assertEquals("新设定", destination.packText(work.id, doc.id))
    }

    @Test
    fun newerCardAndPackRevisionsRestoreTogether() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("设定同步")
        val chapter = work.chapters.single().id
        source.saveCard(work.id, chapter, ChapterCard(facts = "旧事实"))
        val doc = source.addPackDoc(work.id, PackKind.REFERENCE, "设定", "旧内容")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        source.saveCard(work.id, chapter, ChapterCard(facts = "新事实"))
        source.savePackText(work.id, doc.id, "新内容")
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        assertEquals("新事实", destination.card(work.id, chapter).facts)
        assertEquals("新内容", destination.packText(work.id, doc.id))
    }

    @Test
    fun olderCardAndPackObjectsMayOmitNewOptionalFields() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("旧附件")
        val chapter = work.chapters.single().id
        source.saveCard(work.id, chapter, ChapterCard(facts = "旧事实"))
        val doc = source.addPackDoc(work.id, PackKind.REFERENCE, "资料", "旧资料")
        val archive = JSONObject(String(source.backup()!!))
        val entry = archive.getJSONArray("works").getJSONObject(0)
        entry.getJSONObject("cards").getJSONObject(chapter).apply { remove("auto"); remove("updatedAt") }
        entry.getJSONArray("pack").getJSONObject(0).apply {
            remove("priority"); remove("always"); remove("updatedAt"); remove("words")
        }
        val destination = WorkStore(tmp.newFolder())
        destination.validateBackup(archive.toString())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals("旧事实", destination.card(work.id, chapter).facts)
        assertEquals("旧资料", destination.packText(work.id, doc.id))
    }

    @Test
    fun failedSwapOnSecondWorkRollsBackBothWorks() {
        val source = WorkStore(tmp.newFolder())
        val first = source.create("甲")
        val second = source.create("乙")
        val c1 = first.chapters.single().id
        val c2 = second.chapters.single().id
        source.saveText(first.id, c1, "第一版甲")
        source.saveText(second.id, c2, "第一版乙")
        val destinationFolder = tmp.newFolder()
        val destination = WorkStore(destinationFolder)
        assertEquals(2, destination.restoreBackup(String(source.backup()!!)))
        source.saveText(first.id, c1, "第二版甲")
        source.saveText(second.id, c2, "第二版乙")
        var swaps = 0
        val failing = WorkStore(destinationFolder) { if (++swaps == 2) throw IOException("inject second swap failure") }
        assertTrue(runCatching { failing.restoreBackup(String(source.backup()!!)) }.isFailure)
        val reopened = WorkStore(destinationFolder)
        assertEquals("第一版甲", reopened.text(first.id, c1))
        assertEquals("第一版乙", reopened.text(second.id, c2))
        assertTrue(File(destinationFolder, "works/${first.id}/work.json").isFile)
        assertTrue(File(destinationFolder, "works/${second.id}/work.json").isFile)
    }

    @Test
    fun startupRecoversInterruptedRestoreJournal() {
        val folder = tmp.newFolder()
        val first = WorkStore(folder)
        val work = first.create("尚未提交")
        val chapter = work.chapters.single().id
        first.saveText(work.id, chapter, "原来的稿")
        val stage = File(folder, ".restore-writing-interrupted")
        val previous = File(stage, "previous").apply { mkdirs() }
        File(stage, "prepared").mkdirs()
        File(stage, "journal.json").writeText(JSONObject().put("ids", org.json.JSONArray().put(work.id)).put("stats", false).toString())
        assertTrue(File(folder, "works/${work.id}").renameTo(File(previous, work.id)))

        val reopened = WorkStore(folder)
        assertEquals("原来的稿", reopened.text(work.id, chapter))
        assertFalse(stage.exists())
    }

    @Test
    fun repeatedRecoveryDoesNotDeleteWorkAlreadyRolledBack() {
        val folder = tmp.newFolder()
        val first = WorkStore(folder)
        val work = first.create("原稿")
        val chapter = work.chapters.single().id
        first.saveText(work.id, chapter, "应保留")
        val stage = File(folder, ".restore-writing-repeat")
        File(stage, "previous").mkdirs()
        File(stage, "prepared").mkdirs()
        // An earlier recovery already moved previous/id back to works/id, but the
        // process died before removing the journal. Recovery must be idempotent.
        File(stage, "journal.json").writeText(JSONObject()
            .put("ids", org.json.JSONArray().put(work.id))
            .put("existing", org.json.JSONArray().put(work.id))
            .put("stats", false).toString())
        val reopened = WorkStore(folder)
        assertEquals("应保留", reopened.text(work.id, chapter))
        assertFalse(stage.exists())
    }

    @Test
    fun startupDropsUncommittedNewWorkAndKeepsUntouchedExistingWork() {
        val folder = tmp.newFolder()
        val store = WorkStore(folder)
        val existing = store.create("本机原有")
        val existingChapter = existing.chapters.single().id
        store.saveText(existing.id, existingChapter, "原有正文")
        val added = store.create("恢复到一半的新作品")
        val stage = File(folder, ".restore-writing-new-work-interrupted")
        File(stage, "previous").mkdirs()
        File(stage, "prepared/${existing.id}").mkdirs()
        File(stage, "journal.json").writeText(JSONObject()
            .put("ids", org.json.JSONArray().put(added.id).put(existing.id))
            .put("existing", org.json.JSONArray().put(existing.id))
            .put("stats", false).toString())

        val reopened = WorkStore(folder)
        assertNull(reopened.get(added.id))
        assertEquals("原有正文", reopened.text(existing.id, existingChapter))
        assertFalse(stage.exists())
    }
}
