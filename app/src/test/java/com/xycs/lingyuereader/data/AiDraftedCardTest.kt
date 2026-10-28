package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一键写本章存进细纲卡的“AI 拟的 / AI 拆的”标记：补进细纲卡只补空着的项，存盘、备份都带着，旧卡读成没标；
 * 作者改过就清掉；AI 拟的不算作者划定的章组范围；作品的“缺细纲、节拍时 AI 先补上”开关。
 */
class AiDraftedCardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun anAiOutlineOnlyFillsWhatIsMissingAndBecomesAFixedCard() {
        val planned = ChapterCard(hook = "城门响起钟声", pov = "林月", auto = 123, summary = "旧摘要", beats = "林月进城")
        val ai = ChapterCard(facts = "林月提灯入城，见到沈默", hook = "AI 想的钩子", boundary = "老周还活着")
        val card = ChapterCard(summary = "旧摘要", beats = "林月进城").withAiOutline(planned, ai)
        assertEquals("林月提灯入城，见到沈默", card.facts)
        // 已经有的（作者写的、从细纲提取的）不动。
        assertEquals("城门响起钟声", card.hook)
        assertEquals("林月", card.pov)
        assertEquals("老周还活着", card.boundary)
        // 存死的一份：不再跟细纲文档变；摘要、节拍不动。
        assertEquals(0, card.auto)
        assertTrue(card.aiOutline)
        assertFalse(card.aiBeats)
        assertEquals("旧摘要", card.summary)
        assertEquals("林月进城", card.beats)
    }

    @Test
    fun authorEditsClearTheMarks() {
        val card = ChapterCard(facts = "林月入城", beats = "林月进城\n沈默开门", aiOutline = true, aiBeats = true)
        // 改节拍：不再是 AI 拆的；细纲的标记不动。
        val beatsEdited = card.withBeats("林月进城\n沈默开门（500字）")
        assertFalse(beatsEdited.aiBeats)
        assertTrue(beatsEdited.aiOutline)
        assertFalse(card.withBeats("只剩一拍").aiBeats)
        // 换成细纲文档里的：不再是 AI 拟的。
        val replaced = card.withOutline(ChapterCard(facts = "细纲里的事实"))
        assertFalse(replaced.aiOutline)
        assertTrue(replaced.aiBeats)
        // 勾“已写”不算确认。
        assertTrue(card.withBeatDone(1, true).aiBeats)
    }

    @Test
    fun marksAreSavedReadFromOldCardsAndCarriedThroughBackup() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        val c = w.chapters[0].id
        s.saveCard(w.id, c, ChapterCard(facts = "沈默到渡口", beats = "沈默赶到渡口\n杜娘开门", aiOutline = true, aiBeats = true))
        assertTrue(s.card(w.id, c).aiOutline)
        assertTrue(s.card(w.id, c).aiBeats)

        // 换手机恢复：标记跟着走。
        val other = WorkStore(tmp.newFolder())
        assertEquals(1, other.restoreBackup(String(s.backup()!!)))
        assertTrue(other.card(w.id, c).aiOutline)
        assertTrue(other.card(w.id, c).aiBeats)

        // 旧版本写的卡片、旧备份没有这两项：读成没标。
        val file = File(root, "works/${w.id}/$c.card.json")
        file.writeText(JSONObject(file.readText()).apply { remove("aiOutline"); remove("aiBeats") }.toString())
        val reopened = WorkStore(root).card(w.id, c)
        assertEquals("沈默到渡口", reopened.facts)
        assertFalse(reopened.aiOutline)
        assertFalse(reopened.aiBeats)
        val oldBackup = JSONObject(String(s.backup()!!))
        oldBackup.getJSONArray("works").getJSONObject(0).getJSONObject("cards").getJSONObject(c).apply { remove("aiOutline"); remove("aiBeats") }
        val third = WorkStore(tmp.newFolder())
        assertEquals(1, third.restoreBackup(oldBackup.toString()))
        assertFalse(third.card(w.id, c).aiOutline)

        // 备份里的标记不是真假值：整份备份不认。
        val broken = JSONObject(String(s.backup()!!))
        broken.getJSONArray("works").getJSONObject(0).getJSONObject("cards").getJSONObject(c).put("aiOutline", "是")
        assertTrue(runCatching { WorkStore(tmp.newFolder()).restoreBackupReport(broken.toString()) }.let { r ->
            r.isFailure || r.getOrNull()?.restored == 0
        })
    }

    @Test
    fun anUnconfirmedAiOutlineDoesNotScopeAChapterGroup() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("桥北")
        val c = w.chapters[0].id
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "### 第一至三章 河桥\n事实：进城、相认、离城，三章展开。")
        assertTrue(s.writingTask(w.id, c)!!.blocking.any { "章组" in it })
        // AI 拟的、作者还没确认：章组那条照旧拦着（一键写本章不替作者划定本章范围）。
        s.saveCard(w.id, c, ChapterCard(facts = "本章只过桥，留宿北岸。", aiOutline = true))
        val task = s.writingTask(w.id, c)!!
        assertTrue(task.blocking.any { "章组" in it })
        assertTrue(task.prepBlocking.any { "章组" in it })
        // 作者确认了：和手写的一样。
        s.saveCard(w.id, c, s.card(w.id, c).copy(aiOutline = false))
        assertTrue(s.writingTask(w.id, c)!!.blocking.isEmpty())
    }

    @Test
    fun theMissingFactsBlockIsTheOnlyOneOneClickWritingSkips() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("桥北")
        val c = w.chapters[0].id
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "完整正文创作提示词", "遵守本章事实边界。")
        val task = s.writingTask(w.id, c)!!
        assertEquals(listOf(ChapterWritingTask.NO_FACTS), task.blocking)
        assertTrue(task.prepBlocking.isEmpty())
    }

    @Test
    fun theDraftPrepSwitchDefaultsOnAndIsRemembered() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        assertTrue(s.assist(w.id).draftPrep)
        s.updateAssist(w.id) { it.copy(draftPrep = false) }
        assertFalse(WorkStore(root).assist(w.id).draftPrep)
        // 跟着备份走。
        val other = WorkStore(tmp.newFolder())
        assertEquals(1, other.restoreBackup(String(s.backup()!!)))
        assertFalse(other.assist(w.id).draftPrep)
        // 旧设置没有这一项：默认开。
        val file = File(root, "works/${w.id}/assist.json")
        assertTrue(JSONObject(file.readText()).has("draftPrep"))
        file.writeText(JSONObject(file.readText()).apply { remove("draftPrep") }.toString())
        assertTrue(WorkStore(root).assist(w.id).draftPrep)
    }
}
