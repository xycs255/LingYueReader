package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritingPlanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun chaptersStartAsOutlinesAndBecomeDraftsWhenWritten() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c = w.chapters[0]
        assertEquals(ChapterStatus.OUTLINE, c.status)
        s.saveText(w.id, c.id, "他推开门。")
        assertEquals(ChapterStatus.DRAFT, s.get(w.id)!!.chapter(c.id)!!.status)
        // 修订中的章写字不会退回草稿。
        s.setStatus(w.id, c.id, ChapterStatus.REVISION)
        s.saveText(w.id, c.id, "他推开门，风雪灌进来。")
        assertEquals(ChapterStatus.REVISION, s.get(w.id)!!.chapter(c.id)!!.status)
    }

    @Test
    fun oldDataWithoutStatusIsDerivedFromWords() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("长夜")
        s.saveText(w.id, w.chapters[0].id, "有字")
        val (_, empty) = s.addChapter(w.id, w.volumes[0].id)!!
        // 去掉状态字段，模拟旧版本存的 work.json。
        val file = File(root, "works/${w.id}/work.json")
        val json = JSONObject(file.readText())
        val volumes = json.getJSONArray("volumes")
        for (i in 0 until volumes.length()) {
            val chapters = volumes.getJSONObject(i).getJSONArray("chapters")
            for (j in 0 until chapters.length()) chapters.getJSONObject(j).remove("status")
        }
        file.writeText(json.toString())
        val loaded = s.get(w.id)!!
        assertEquals(ChapterStatus.DRAFT, loaded.chapters[0].status)
        assertEquals(ChapterStatus.OUTLINE, loaded.chapter(empty.id)!!.status)
    }

    @Test
    fun finalChaptersAreLockedAndUnlockingIsLogged() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c = w.chapters[0].id
        s.saveText(w.id, c, "定稿的正文。")
        s.setStatus(w.id, c, ChapterStatus.FINAL)
        assertTrue(s.get(w.id)!!.chapter(c)!!.locked)
        // 定稿时存了一版。
        assertEquals("定稿的正文。", s.snapshotText(w.id, c, s.history(w.id, c).first().at))
        // 锁定后不能改正文；内容没变的保存（离开编辑器时）照常放过。
        assertTrue(runCatching { s.saveText(w.id, c, "偷偷改一句。") }.isFailure)
        assertEquals("定稿的正文。", s.text(w.id, c))
        s.saveText(w.id, c, "定稿的正文。")
        // 换回历史版本也不行。
        s.snapshot(w.id, c, "更早的稿")
        val early = s.history(w.id, c).first { s.snapshotText(w.id, c, it.at) == "更早的稿" }.at
        assertTrue(runCatching { s.restore(w.id, c, early) }.isFailure)
        assertEquals("定稿的正文。", s.text(w.id, c))

        // 解锁必须写原因。
        assertTrue(runCatching { s.setStatus(w.id, c, ChapterStatus.REVISION) }.isFailure)
        s.setStatus(w.id, c, ChapterStatus.REVISION, "老周改为第九章战死", canon = true)
        assertFalse(s.get(w.id)!!.chapter(c)!!.locked)
        s.saveText(w.id, c, "改过的正文。")

        val log = s.changes(w.id)
        assertEquals(listOf(ChangeKind.UNLOCK, ChangeKind.FINALIZE), log.map { it.kind })
        val unlock = log.first()
        assertEquals("老周改为第九章战死" to "第1章", unlock.reason to unlock.chapterTitle)
        assertTrue(unlock.pending)
        s.markSynced(w.id, unlock.id)
        assertFalse(s.changes(w.id).first().pending)
        // 状态没变不记。
        s.setStatus(w.id, c, ChapterStatus.REVISION)
        assertEquals(2, s.changes(w.id).size)
    }

    @Test
    fun cardsAreSavedDeletedWithTheChapterAndCarriedByBackups() {
        val a = WorkStore(tmp.newFolder())
        val w = a.create("长夜")
        val c = w.chapters[0].id
        assertTrue(a.card(w.id, c).isEmpty)
        val card = ChapterCard(facts = "护送队十二人到石桥", boundary = "老周还活着", pov = "沈默", countdown = "离冬至三天", hook = "门外有人", threads = "明线")
        a.saveCard(w.id, c, card)
        val savedCard = a.card(w.id, c)
        assertTrue(savedCard.updatedAt > 0)
        assertEquals(card.copy(updatedAt = savedCard.updatedAt), savedCard)
        a.setStatus(w.id, c, ChapterStatus.FINAL, "")
        a.addChange(w.id, ChangeEntry("x1", 5, ChangeKind.CANON, reason = "人数改为十三", canon = true))

        val json = String(a.backup()!!)
        val b = WorkStore(tmp.newFolder())
        b.restoreBackup(json)
        assertEquals(savedCard, b.card(w.id, c))
        assertEquals(ChapterStatus.FINAL, b.get(w.id)!!.chapter(c)!!.status)
        assertEquals(setOf(ChangeKind.FINALIZE, ChangeKind.CANON), b.changes(w.id).map { it.kind }.toSet())
        assertTrue(JSONObject(json).getJSONArray("works").getJSONObject(0).getJSONObject("cards").has(c))

        // 删章连同细纲卡。
        val (_, c2) = a.addChapter(w.id, w.volumes[0].id)!!
        a.saveCard(w.id, c2.id, card)
        a.deleteChapter(w.id, c2.id)
        assertTrue(a.card(w.id, c2.id).isEmpty)
        assertTrue(runCatching { a.saveCard(w.id, c2.id, card) }.isFailure)
    }

    @Test
    fun importedFinalTextIsLocked() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.importPack(w.id, listOf(
            PackImport.classify("已定正文_第1-1章.md", "## 第一章 雪夜\n他推开门。"),
            PackImport.classify("草稿_第2-2章.md", "## 第二章 来客\n来人了。"),
        ))
        val chapters = s.get(w.id)!!.chapters
        // 新建作品自带的空白“第1章”导入正文后删掉。
        assertEquals(listOf(ChapterStatus.FINAL, ChapterStatus.DRAFT), chapters.map { it.status })
        assertEquals(ChangeKind.FINALIZE, s.changes(w.id).single().kind)
    }

    @Test
    fun cardPromptHasFactsAndThePreviousHook() {
        val text = WritePrompts.card(
            ChapterCard(facts = "护送队到石桥", hook = "门外有人"),
            "第一章 雪夜",
            ChapterCard(hook = "灯灭了", boundary = "老周还活着"),
        )!!
        assertTrue("本章细纲卡（事实边界" in text)
        assertTrue("- 本章事实与结果：护送队到石桥" in text)
        assertTrue("- 章末钩子：门外有人" in text)
        assertTrue("上一章「第一章 雪夜」留下的：\n- 钩子：灯灭了\n- 承接边界：老周还活着" in text)
        assertFalse("视角人物" in text)
        assertNull(WritePrompts.card(ChapterCard(), null, null))
    }
}
