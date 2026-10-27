package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 细纲卡的节拍：解析、篇幅、勾“已写”、存盘和备份，以及逐拍写的候选稿进度和正文一起落盘。 */
class BeatsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class SimulatedProcessDeath : Error()

    @Test
    fun parseDropsNumberingAndTakesTheLengthAtTheEnd() {
        val beats = Beats.parse(
            """
            1. 沈默赶到渡口，发现船不见了（800字）
            ② 杜娘说老周昨夜就走了（约 600 字）

            - 沈默在雪里等到天亮
            第四拍：顾长风带人来了（１２００字左右）
            **5、林月推门进来**
            """.trimIndent(),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), beats.map { it.index })
        assertEquals(
            listOf("沈默赶到渡口，发现船不见了", "杜娘说老周昨夜就走了", "沈默在雪里等到天亮", "顾长风带人来了", "林月推门进来"),
            beats.map { it.text },
        )
        assertEquals(listOf(800, 600, null, 1200, null), beats.map { it.length })
        // 只有序号、空着的行不算一拍。
        assertEquals(1, Beats.parse("1.\n\n- \n沈默上船").size)
        assertTrue(Beats.parse("  \n").isEmpty())
    }

    @Test
    fun lengthWithoutABeatLengthIsTheChapterTargetSharedOut() {
        assertEquals(1000, Beats.share("3000 到 5000 字", 4))
        assertEquals(750, Beats.share("不少于 3000 字", 4))
        assertEquals(1350, Beats.share("4000—4200个汉字", 3))
        assertNull(Beats.share("依本章细纲；没有明确数字时，以完整自然的章节为准", 4))
        val list = Beats.parse("沈默赶到渡口\n杜娘开门（500字）\n老周的信")
        val second = Beats.task(list, 2, "3000 到 3000 字")
        assertEquals("沈默赶到渡口", second.previous?.text)
        assertEquals("老周的信", second.next?.text)
        assertEquals(500, second.length)
        assertEquals(1000, Beats.task(list, 3, "3000 到 3000 字").length)
        assertNull(Beats.task(list, 3, "3000 到 3000 字").next)
        assertNull(Beats.task(list, 1, "").previous)
    }

    @Test
    fun changingTheNumberOfBeatsClearsWhatWasMarkedWritten() {
        val card = ChapterCard(beats = "沈默赶到渡口\n杜娘开门\n老周的信").withBeatDone(1, true).withBeatDone(3, true)
        assertEquals(listOf(1, 3), card.beatsDone)
        assertEquals(2, Beats.firstUndone(card))
        // 改措辞、篇幅，条数没变：勾留着。
        assertEquals(listOf(1, 3), card.withBeats("沈默赶到渡口（800字）\n杜娘开门\n老周留下的信").beatsDone)
        // 多了一拍：序号可能错位，清空。
        assertTrue(card.withBeats("沈默赶到渡口\n船不见了\n杜娘开门\n老周的信").beatsDone.isEmpty())
        assertEquals(listOf(3), card.withBeatDone(1, false).beatsDone)
        // 超出拍数的不记。
        assertEquals(listOf(1, 3), card.withBeatDone(9, true).beatsDone)
        assertNull(Beats.firstUndone(card.withBeatDone(2, true)))
    }

    @Test
    fun aCardWithOnlyBeatsIsNotEmptyButStillTakesItsOutlineFromThePack() {
        val beatsOnly = ChapterCard(beats = "沈默赶到渡口")
        assertFalse(beatsOnly.isEmpty)
        assertTrue(beatsOnly.outlineEmpty)
        assertTrue(ChapterCard(summary = "摘要").isEmpty)

        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡口")
        val c = w.chapters[0].id
        s.renameChapter(w.id, c, "第一章 渡口")
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "# 第一卷\n### 第一章 渡口\n事实：沈默到渡口，船不见了。")
        s.saveCard(w.id, c, beatsOnly)
        // 只写了节拍的卡：前六项照样从细纲提取，节拍留着；指纹只看前六项。
        val planned = s.plannedCard(w.id, c)
        assertTrue(planned.card.facts.isNotBlank())
        assertEquals("沈默赶到渡口", planned.card.beats)
        assertEquals(planned.card.outlineHash(), planned.card.auto)
        assertEquals(planned.card.auto, planned.card.copy(beats = "换了节拍", beatsDone = listOf(1)).outlineHash())
        // 节拍不让卡变成“作者手写的细纲”：项目包只看前六项。
        s.saveCard(w.id, c, planned.card.copy(beats = "沈默赶到渡口\n杜娘开门"))
        val again = s.plannedCard(w.id, c)
        assertEquals(planned.card.facts, again.card.facts)
        assertEquals("沈默赶到渡口\n杜娘开门", again.card.beats)
        assertTrue(again.source != null)
    }

    @Test
    fun beatsAreSavedReadFromOldCardsAndCarriedThroughBackup() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡口")
        val c = w.chapters[0].id
        val card = ChapterCard(facts = "沈默到渡口", beats = "沈默赶到渡口\n杜娘开门（500字）", beatsDone = listOf(2))
        s.saveCard(w.id, c, card)
        assertEquals(card.beats, s.card(w.id, c).beats)
        assertEquals(listOf(2), s.card(w.id, c).beatsDone)

        // 旧版本写的卡片没有这两项：读成空的。
        val root = tmp.newFolder()
        val old = WorkStore(root)
        val ow = old.create("旧书")
        val oc = ow.chapters[0].id
        old.saveCard(ow.id, oc, ChapterCard(facts = "旧事实"))
        val file = File(root, "works/${ow.id}/$oc.card.json")
        val json = JSONObject(file.readText()).apply { remove("beats"); remove("beatsDone") }
        file.writeText(json.toString())
        val reopened = WorkStore(root).card(ow.id, oc)
        assertEquals("旧事实", reopened.facts)
        assertEquals("", reopened.beats)
        assertTrue(reopened.beatsDone.isEmpty())

        // 换手机恢复：节拍和已写的勾跟着走；只写了节拍的卡也进备份。
        val (_, two) = s.addChapter(w.id, w.volumes[0].id)!!
        s.saveCard(w.id, two.id, ChapterCard(beats = "林月推门进来"))
        val other = WorkStore(tmp.newFolder())
        assertEquals(1, other.restoreBackup(String(s.backup()!!)))
        assertEquals(card.beats, other.card(w.id, c).beats)
        assertEquals(listOf(2), other.card(w.id, c).beatsDone)
        assertEquals("林月推门进来", other.card(w.id, two.id).beats)

        // 备份里的“已写”不是整数：整份备份不认。
        val broken = JSONObject(String(s.backup()!!))
        val works = broken.getJSONArray("works")
        works.getJSONObject(0).getJSONObject("cards").getJSONObject(c).put("beatsDone", org.json.JSONArray().put("第一拍"))
        assertTrue(runCatching { WorkStore(tmp.newFolder()).restoreBackupReport(broken.toString()) }.let { r ->
            r.isFailure || r.getOrNull()?.restored == 0
        })
    }

    @Test
    fun resumeStartsFromTheBeatAfterTheLastSavedOne() {
        // 从头写、没有候选稿：从第 1 拍。
        assertEquals(1, Beats.resume(3, more = false, candidate = "旧稿", progress = DraftBeats(2, 3)))
        assertEquals(1, Beats.resume(3, more = true, candidate = "", progress = null))
        // 写到第 2 拍停了：接着写第 3 拍。
        assertEquals(3, Beats.resume(3, more = true, candidate = "两拍的正文", progress = DraftBeats(2, 3)))
        // 不是逐拍写的稿子、或各拍都写完了：照原来整章接着写。
        assertNull(Beats.resume(3, more = true, candidate = "整章写的", progress = null))
        assertNull(Beats.resume(3, more = true, candidate = "三拍都写了", progress = DraftBeats(3, 3)))
        // 没有节拍：照原来。
        assertNull(Beats.resume(0, more = false, candidate = "", progress = null))
        // 节拍条数改了：不猜，请作者从头写。
        assertTrue(runCatching { Beats.resume(4, more = true, candidate = "两拍的正文", progress = DraftBeats(2, 3)) }
            .exceptionOrNull()?.message.orEmpty().contains("从头写"))
    }

    @Test
    fun beatProgressIsStoredWithTheCandidateAndSurvivesBackup() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡口")
        val c = w.chapters[0].id
        s.saveDraft(w.id, c, "第一拍。", beats = DraftBeats(1, 3))
        assertEquals(DraftBeats(1, 3), s.draftContent(w.id, c).beats)
        // 不是逐拍写的稿子：没有进度，来源记录也不多这两项。
        s.saveDraft(w.id, c, "整章写的。")
        assertNull(s.draftContent(w.id, c).beats)
        s.saveDraft(w.id, c, "第一拍。\n第二拍。", beats = DraftBeats(2, 3))
        val other = WorkStore(tmp.newFolder())
        assertEquals(1, other.restoreBackup(String(s.backup()!!)))
        assertEquals("第一拍。\n第二拍。", other.draft(w.id, c))
        assertEquals(DraftBeats(2, 3), other.draftContent(w.id, c).beats)
        assertFalse(other.draftContent(w.id, c).blocked)
    }

    @Test
    fun aBrokenBeatProgressBlocksTheCandidateInsteadOfGuessing() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        val c = w.chapters[0].id
        s.saveDraft(w.id, c, "第一拍。", beats = DraftBeats(1, 3))
        val source = File(root, "works/${w.id}/$c.draft.reference.json")
        source.writeText(JSONObject(source.readText()).put("beatsWritten", 5).toString())
        val content = WorkStore(root).draftContent(w.id, c)
        assertTrue(content.blocked)
        assertNull(content.beats)
    }

    @Test
    fun processDeathWhileSavingABeatRollsBackTextAndProgressTogether() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        val c = w.chapters[0].id
        s.saveDraft(w.id, c, "第一拍。", beats = DraftBeats(1, 3))
        // 写第二拍：正文写下去了，来源记录（带进度）还没写，进程没了。
        val dying = WorkStore(root, savePhaseHook = { if (it == "draft-body") throw SimulatedProcessDeath() })
        assertTrue(runCatching { dying.saveDraft(w.id, c, "第一拍。\n第二拍。", beats = DraftBeats(2, 3)) }.exceptionOrNull() is SimulatedProcessDeath)
        // 下次启动：正文和进度一起回到第一拍写完时，接着写从第二拍开始。
        val reopened = WorkStore(root)
        assertEquals("第一拍。", reopened.draft(w.id, c))
        val content = reopened.draftContent(w.id, c)
        assertEquals(DraftBeats(1, 3), content.beats)
        assertFalse(content.blocked)
        assertEquals(2, Beats.resume(3, more = true, candidate = content.text, progress = content.beats))
    }
}
