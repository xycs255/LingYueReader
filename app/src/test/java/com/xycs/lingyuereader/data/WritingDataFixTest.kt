package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** 写作数据的修正：导入正文和同名、伏笔表补列、备份恢复遇到坏文件、按字段改细纲卡。 */
class WritingDataFixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manuscript(name: String, text: String) = PackImportItem(name, text, PackKind.REFERENCE, asChapters = true)

    @Test
    fun aFirstChapterNamedLikeTheBlankOneIsNotDeleted() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.importPack(w.id, listOf(manuscript("正文.md", "第1章\n林月推开门。\n第2章\n沈默过河。")))
        val work = s.get(w.id)!!
        assertEquals(listOf("第1章", "第2章"), work.chapters.map { it.title })
        assertEquals("林月推开门。", s.text(w.id, work.chapters[0].id))
        assertEquals("沈默过河。", s.text(w.id, work.chapters[1].id))
        // 自带的空白章已经删掉了。
        assertFalse(work.chapters.any { it.id == w.chapters.single().id })
    }

    @Test
    fun sameNamedItemsInOneImportDoNotOverwriteEachOther() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "旧档案")
        val items = listOf(
            PackImport.classify("第一卷/细纲.md", "第一卷：林月守渡口。"),
            PackImport.classify("第二卷/细纲.md", "第二卷：沈默在对岸。"),
            PackImport.classify("人物档案.md", "林月、沈默"),
            PackImport.classify("备份/人物档案.md", "老周、杜娘"),
            manuscript("第一卷/正文.md", "## 第1章\n林月等船。"),
            manuscript("第二卷/正文.md", "## 第1章\n沈默上岸。"),
        )
        // 导入前的预览就能看出来：后一份改名，作品里已有的同名那份仍然被替换。
        assertEquals(
            listOf("细纲", "细纲（2）", "人物档案", "人物档案（2）", "正文", "正文"),
            PackImport.docTitles(items, setOf("人物档案")),
        )
        val r = s.importPack(w.id, items)
        assertEquals(3, r.added)
        assertEquals(1, r.replaced)
        assertEquals(listOf("第1章（2）", "细纲（2）", "人物档案（2）"), r.renamed)
        val docs = s.pack(w.id).associate { it.title to s.packText(w.id, it.id) }
        assertEquals("第一卷：林月守渡口。", docs["细纲"])
        assertEquals("第二卷：沈默在对岸。", docs["细纲（2）"])
        assertEquals("林月、沈默", docs["人物档案"])
        assertEquals("老周、杜娘", docs["人物档案（2）"])
        val work = s.get(w.id)!!
        assertEquals(listOf("第1章", "第1章（2）"), work.chapters.map { it.title })
        assertEquals(listOf("林月等船。", "沈默上岸。"), work.chapters.map { s.text(w.id, it.id) })

        // 再导一次同一份正文：仍然替换已有的同名章，不重复加。
        s.importPack(w.id, listOf(manuscript("第一卷/正文.md", "## 第1章\n林月又等了一夜。")))
        assertEquals(2, s.get(w.id)!!.chapters.size)
        assertEquals("林月又等了一夜。", s.text(w.id, s.get(w.id)!!.chapters[0].id))
    }

    @Test
    fun exportedZipKeepsSameNamedDocumentsApart() {
        val docs = listOf(
            PackDoc("a", PackKind.CHARACTER, "人物档案") to "林月",
            PackDoc("b", PackKind.CHARACTER, "人物档案") to "顾长风",
        )
        val bytes = ByteArrayOutputStream().also { PackImport.writeZip(docs, it) }.toByteArray()
        val items = PackImport.readZip { bytes.inputStream() }
        assertEquals(listOf("人物档案", "人物档案（2）"), items.map { it.title })
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        assertEquals(2, s.importPack(w.id, items).added)
        assertEquals(setOf("林月", "顾长风"), s.pack(w.id).map { s.packText(w.id, it.id) }.toSet())
    }

    @Test
    fun manuscriptsSortByFileNameEvenWhenMixedWithDocuments() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.importPack(w.id, listOf(
            manuscript("稿/正文_第11-15章.md", "## 第十一章\n十一"),
            PackImport.classify("人物.md", "林月"),
            manuscript("稿/正文_第6-10章.md", "## 第六章\n六"),
            PackImport.classify("总纲.md", "主线"),
            manuscript("另一处/正文_第1-5章.md", "## 第一章\n一"),
        ))
        assertEquals(listOf("第一章", "第六章", "第十一章"), s.get(w.id)!!.chapters.map { it.title })
    }

    @Test
    fun onlyFileNamesThatSayFinalLockTheChapters() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.importPack(w.id, listOf(
            manuscript("未定稿_第1-1章.md", "## 第一章\n一"),
            manuscript("定稿/正文_第2-2章.md", "## 第二章\n二"),
            manuscript("已定正文_第3-3章.md", "## 第三章\n三"),
            manuscript("正文_第4-4章（定稿）.md", "## 第四章\n四"),
        ))
        val locked = s.get(w.id)!!.chapters.associate { it.title to it.locked }
        assertEquals(mapOf("第一章" to false, "第二章" to false, "第三章" to true, "第四章" to true), locked)
    }

    @Test
    fun markingAForeshadowDoneAddsTheMissingStatusColumn() {
        val table = "| 伏笔 | 埋下 | 回收 |\n| --- | --- | --- |\n| 半块玉佩 | 第3章 | 第20章 |\n| 老周的账本 | 第5章 | 第30章 |"
        val list = ChapterBriefs.foreshadows(listOf("伏笔表" to table))
        assertFalse(list[0].done)
        val marked = ChapterBriefs.markDone(table, list[0], 20)
        val lines = marked.lines()
        assertEquals("| 伏笔 | 埋下 | 回收 | 状态 |", lines[0])
        assertEquals(1, MdTable.parse(marked).size)
        val again = ChapterBriefs.foreshadows(listOf("伏笔表" to marked))
        assertEquals(listOf(true, false), again.map { it.done })
        // 有了状态列以后，再标另一条就改那一格。
        val second = ChapterBriefs.markDone(marked, again[1], null)
        assertEquals(listOf(true, true), ChapterBriefs.foreshadows(listOf("伏笔表" to second)).map { it.done })
        assertEquals(4, MdTable.parse(second).single().header.size)
    }

    @Test
    fun damagedWritingFilesAreReportedAndLeftOutOfTheBackup() {
        val folder = tmp.newFolder()
        val s = WorkStore(folder)
        val w = s.create("长夜")
        val chapter = w.chapters.single().id
        s.saveText(w.id, chapter, "林月推开门。")
        val (_, two) = s.addChapter(w.id, w.volumes.single().id, "第2章")!!
        s.saveText(w.id, two.id, "沈默过河。")
        s.saveCard(w.id, chapter, ChapterCard(facts = "船没来"))
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物", "林月")
        s.updateAssist(w.id) { it.copy(style = "短句") }
        s.addChange(w.id, ChangeEntry("c1", 1, ChangeKind.CANON, reason = "改设定", canon = true))
        val good = String(s.backup()!!)
        val destinationFolder = tmp.newFolder()
        val destination = WorkStore(destinationFolder)
        assertEquals(1, destination.restoreBackup(good))

        val dir = File(folder, "works/${w.id}")
        File(dir, "$chapter.card.json").writeText("{bad")
        File(dir, "assist.json").writeText("[")
        File(dir, "changes.json").writeText("{")
        File(dir, "pack/index.json").writeText("not json")
        File(dir, "${two.id}.txt").delete()
        Thread.sleep(5)
        s.renameChapter(w.id, chapter, "第1章 夜渡")
        val report = s.backupReport()!!
        assertEquals(
            setOf("$chapter.card.json", "assist.json", "changes.json", "pack/index.json", "${two.id}.txt"),
            report.skipped.map { it.file }.toSet(),
        )
        assertTrue(report.skipped.all { it.work == "长夜" })
        assertTrue(report.skipped.first { it.file == "${two.id}.txt" }.label.startsWith("《长夜》${two.id}.txt："))
        val entry = JSONObject(String(report.bytes)).getJSONArray("works").getJSONObject(0)
        assertFalse(entry.has("pack") || entry.has("assist") || entry.has("changes"))
        assertFalse(entry.getJSONObject("cards").has(chapter))

        // 用这份备份恢复：读不出的几项保留那台手机上原来的。
        destination.validateBackup(String(report.bytes))
        assertEquals(1, destination.restoreBackup(String(report.bytes)))
        assertEquals("第1章 夜渡", destination.get(w.id)!!.chapters[0].title)
        assertEquals("船没来", destination.card(w.id, chapter).facts)
        assertEquals(listOf("人物"), destination.pack(w.id).map { it.title })
        assertEquals("短句", destination.assist(w.id).style)
        assertEquals(listOf("c1"), destination.changes(w.id).map { it.id })
        // 正文文件不见了的那章是空的，旧稿进了历史版本。
        assertEquals("", destination.text(w.id, two.id))
        assertTrue(destination.history(w.id, two.id).any { destination.snapshotText(w.id, two.id, it.at) == "沈默过河。" })
    }

    @Test
    fun restoreReportsWorksKeptBecauseTheLocalCopyIsNewerOrDamaged() {
        val source = WorkStore(tmp.newFolder())
        val older = source.create("渡口")
        val damaged = source.create("对岸")
        val fresh = source.create("归乡")
        val archive = String(source.backup()!!)
        val destinationFolder = tmp.newFolder()
        val destination = WorkStore(destinationFolder)
        assertEquals(3, destination.restoreBackup(archive))
        // 同一版再恢复一次：没有可报告的。
        assertEquals(WritingRestore(0, emptyList()), destination.restoreBackupReport(archive))

        Thread.sleep(5)
        destination.saveText(older.id, older.chapters.single().id, "本机后来写的。")
        File(destinationFolder, "works/${damaged.id}/work.json").writeText("{")
        Thread.sleep(5)
        source.saveText(fresh.id, fresh.chapters.single().id, "电脑上写的。")
        source.saveText(damaged.id, damaged.chapters.single().id, "电脑上写的另一部。")
        val report = destination.restoreBackupReport(String(source.backup()!!))
        assertEquals(1, report.restored)
        assertEquals(setOf("渡口", "对岸"), report.skipped.map { it.work }.toSet())
        assertEquals("work.json", report.skipped.single { it.work == "对岸" }.file)
        assertEquals("", report.skipped.single { it.work == "渡口" }.file)
        assertEquals("本机后来写的。", destination.text(older.id, older.chapters.single().id))
        assertEquals("电脑上写的。", destination.text(fresh.id, fresh.chapters.single().id))
        assertEquals("{", File(destinationFolder, "works/${damaged.id}/work.json").readText())
    }

    @Test
    fun aDamagedLocalStatsFileIsReplacedByTheBackupCopy() {
        val source = WorkStore(tmp.newFolder())
        source.addWords(LocalDate.of(2026, 10, 1), 800)
        source.setGoal(1500)
        val folder = tmp.newFolder()
        val destination = WorkStore(folder)
        File(folder, "stats.json").writeText("{damaged")
        destination.restoreBackup(String(source.backup()!!))
        assertEquals(800, destination.stats().on(LocalDate.of(2026, 10, 1)))
        assertEquals(1500, destination.stats().goal)
        assertEquals("{damaged", File(folder, "stats.json.damaged").readText())
    }

    @Test
    fun aSecondDifferentStatsDamageGetsItsOwnCopy() {
        val folder = tmp.newFolder()
        val s = WorkStore(folder)
        File(folder, "stats.json").writeText("{first")
        s.setGoal(100)
        File(folder, "stats.json").writeText("{second")
        s.addWords(LocalDate.of(2026, 10, 2), 5)
        s.addWords(LocalDate.of(2026, 10, 3), 5)
        val copies = folder.listFiles()!!.filter { it.name.startsWith("stats.json.damaged") }.map { it.readText() }
        assertEquals(listOf("{first", "{second"), copies.sorted())
    }

    @Test
    fun updateCardChangesOnlyTheFieldsItTouches() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c = w.chapters.single().id
        s.saveCard(w.id, c, ChapterCard(facts = "林月守桥", hook = "桥塌了"))
        val before = s.card(w.id, c)
        // 页面拿着旧卡片时，AI 写摘要只改摘要这一项。
        val next = s.updateCard(w.id, c) { it.copy(summary = "林月守住了桥。") }
        assertEquals("林月守桥", next.facts)
        assertEquals("桥塌了", next.hook)
        assertEquals("林月守住了桥。", s.card(w.id, c).summary)
        assertTrue(next.updatedAt > before.updatedAt)
        // 没改动不写盘。
        assertEquals(next, s.updateCard(w.id, c) { it })
        // 作者已经写了摘要：按现在的卡判断，跳过。
        s.updateCard(w.id, c) { if (it.summary.isBlank()) it.copy(summary = "AI 摘要") else it }
        assertEquals("林月守住了桥。", s.card(w.id, c).summary)
        assertTrue(runCatching { s.updateCard(w.id, "missing") { it } }.isFailure)
    }
}
