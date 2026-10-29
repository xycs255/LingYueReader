package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.ProseScope
import com.xycs.lingyuereader.ai.ProseTask
import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 交接卡的本机部分：本机从结尾抽（拿不准的留空）、读 AI 回的 JSON、过期判断、存盘（原子写入、删章清掉、备份恢复带上、旧数据能读），
 * 项目包里未定稿的上一章结尾：带进本章任务、标明未定稿，审读时不能当 FACT 依据。请求里“上一章交接”只在章首写时带。
 * 人名虚构、文本自造。
 */
class ChapterHandoffTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ending = "沈默把船推离岸边。\n顾长风站在渡口茶馆门口，没有回头。\n林月追出来，喊：“你到底要去哪？”"

    @Test fun localPicksNamesPlacesAndTheUnansweredQuestionAndLeavesTheRestBlank() {
        val card = Handoffs.local("老周早上来过。\n" + ending, names = listOf("林月", "顾长风", "沈默", "老周", "杜娘"),
            places = listOf("渡口茶馆", "北岸"), hook = "林月追问去向")
        // 最后几段里出现的人，按最后出现的先后；早先段落里的老周、没出现的杜娘都不算。
        assertEquals(listOf("林月", "顾长风", "沈默"), card.people.map { it.name })
        assertTrue(card.people.all { it.doing.isEmpty() && it.state.isEmpty() })
        assertEquals("渡口茶馆", card.scene)
        assertEquals(listOf("没答上的问话：“你到底要去哪？”"), card.open)
        assertEquals("", card.mood)
        assertEquals("林月追问去向", card.hook)
        assertEquals(HandoffOrigin.LOCAL, card.origin)
        // 结尾里一个认得的都没有：不瞎填。
        val empty = Handoffs.local("雨一直下。", listOf("林月"), listOf("北岸"))
        assertTrue(empty.isEmpty)
    }

    @Test fun parsesTheAiJsonCapsOpenItemsAndRejectsAnythingElse() {
        val card = Handoffs.parse("""好的：{"scene":"渡口，天快黑了","people":[{"name":"林月","doing":"追到门口","state":"又急又气"},{"name":""}],
            "mood":"憋着火","open":["顾长风去哪","船票是谁买的","杜娘为什么没出来","多出来的一条"],"hook":"林月追问去向"}""")
        assertEquals("渡口，天快黑了", card.scene)
        assertEquals(listOf(HandoffPerson("林月", "追到门口", "又急又气")), card.people)
        assertEquals(Handoffs.MAX_OPEN, card.open.size)
        assertEquals(HandoffOrigin.AI, card.origin)
        assertThrows(Handoffs.ParseError::class.java) { Handoffs.parse("场景是渡口。") }
        assertThrows(Handoffs.ParseError::class.java) { Handoffs.parse("""{"rules":[]}""") }
    }

    @Test fun staleOnlyWhenTheTextReallyChanged() {
        val card = ChapterHandoff(scene = "渡口", origin = HandoffOrigin.AI, hash = Handoffs.hash(ending), textAt = 100)
        var reads = 0
        // 修改时间没变：不读正文。
        assertTrue(Handoffs.fresh(card, 100) { reads++; "改过的" })
        assertEquals(0, reads)
        // 修改时间变了（比如只改了状态）：按指纹比，正文没变就没过期。
        assertTrue(Handoffs.fresh(card, 200) { reads++; "$ending\n" })
        assertFalse(Handoffs.fresh(card, 200) { ending + "\n杜娘关了门。" })
        // 细纲卡写了钩子：以细纲卡为准。
        assertEquals("林月追问去向", Handoffs.withCardHook(card.copy(hook = "AI 写的钩子"), "林月追问去向").hook)
        assertEquals("AI 写的钩子", Handoffs.withCardHook(card.copy(hook = "AI 写的钩子"), "").hook)
    }

    @Test fun editorLinesRoundTrip() {
        assertEquals(HandoffPerson("林月", "守在门口", "左臂带伤；憋着火"), Handoffs.person("林月：守在门口；左臂带伤；憋着火"))
        assertEquals(HandoffPerson("老周"), Handoffs.person("- 老周"))
        assertNull(Handoffs.person("  "))
        assertEquals("林月：守在门口；左臂带伤", HandoffPerson("林月", "守在门口", "左臂带伤").line())
    }

    @Test fun storeWritesAtomicallyClearsWithTheChapterAndCarriesThroughBackups() {
        val dirs = listOf(tmp.newFolder("a"), tmp.newFolder("b"))
        val a = WorkStore(dirs[0])
        val w = a.create("渡口")
        val c1 = w.chapters.single().id
        val c2 = a.addChapter(w.id, w.volumes.single().id)!!.second.id
        a.saveHandoff(w.id, c1, ChapterHandoff(scene = "渡口", origin = HandoffOrigin.AI, hash = "h1"))
        a.saveHandoff(w.id, c2, ChapterHandoff(mood = "憋着火", origin = HandoffOrigin.AUTHOR))
        val file = File(dirs[0], "works/${w.id}/handoffs.json")
        assertTrue(file.isFile)
        assertFalse(File(file.parentFile, "handoffs.json.tmp").exists())
        assertEquals("渡口", WorkStore(dirs[0]).handoff(w.id, c1)?.scene)

        // 备份带上；恢复到另一台。
        val backup = String(a.backup()!!)
        assertTrue(JSONObject(backup).getJSONArray("works").getJSONObject(0).has("handoffs"))
        val b = WorkStore(dirs[1])
        assertEquals(1, b.restoreBackup(backup))
        assertEquals("渡口", b.handoff(w.id, c1)?.scene)
        assertEquals(HandoffOrigin.AUTHOR, b.handoff(w.id, c2)?.origin)

        // 旧备份没有这一项：恢复照常，本机的留着。
        val old = JSONObject(backup).apply {
            getJSONArray("works").getJSONObject(0).apply { remove("handoffs"); put("updatedAt", getLong("updatedAt") + 60_000) }
        }
        b.saveHandoff(w.id, c1, ChapterHandoff(scene = "北岸", origin = HandoffOrigin.AUTHOR))
        assertEquals(1, b.restoreBackup(old.toString()))
        assertEquals("北岸", b.handoff(w.id, c1)?.scene)

        // 删章：这章的交接卡清掉，别的章不动。
        a.deleteChapter(w.id, c1)
        assertNull(a.handoff(w.id, c1))
        assertNotNull(a.handoff(w.id, c2))
    }

    @Test fun oldDataWithoutNewFieldsStillReads() {
        // 早先的交接卡只有几项；早先的收尾勾选项没有 handoff。
        val card = Handoffs.fromJson(JSONObject("""{"scene":"渡口"}"""))
        assertEquals("渡口", card.scene)
        assertEquals(HandoffOrigin.LOCAL, card.origin)
        assertTrue(card.people.isEmpty() && card.open.isEmpty())
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        val assist = File(root, "works/${w.id}/assist.json")
        assist.writeText("""{"finish":{"sync":false,"summary":true,"review":false,"learn":true}}""")
        val finish = WorkStore(root).assist(w.id).finish
        assertFalse(finish.sync)
        assertTrue(finish.handoff)
        s.updateAssist(w.id) { it.copy(finish = it.finish.copy(handoff = false)) }
        assertFalse(WorkStore(root).assist(w.id).finish.handoff)
    }

    @Test fun unfinalizedPreviousChapterEndingRidesAlongButIsNeverFactEvidence() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡口")
        val c1 = w.chapters.single().id
        s.renameChapter(w.id, c1, "第一章 渡口")
        s.saveText(w.id, c1, "早先的事。\n" + ending)
        val c2 = s.addChapter(w.id, w.volumes.single().id, "第二章 夜渡")!!.second.id
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "# 第一卷\n### 第一章 渡口\n事实：顾长风要走。\n### 第二章 夜渡\n事实：沈默把船撑到北岸，林月没追上。\n视角：沈默\n")
        val task = s.writingTask(w.id, c2)!!
        assertTrue(task.project)
        // 定稿前文照旧是空的；未定稿的结尾另放，标明只接场面和语气。
        assertEquals("", task.previous)
        assertTrue(task.previousDraft.endsWith("你到底要去哪？”"))
        assertTrue(task.previousLabel.startsWith("以细纲结果承接"))
        assertTrue(task.render().contains(ChapterWritingTask.PREVIOUS_DRAFT + "：\n"))
        // 审读：任务来源不含它；单独那份来源能看见，但 FACT、CRAFT 都定位不到。
        val evidence = s.reviewEvidence(w.id, c2, task)
        val taskSource = evidence.sources.first { it.ref == evidence.taskRef }
        assertFalse(taskSource.text.contains("你到底要去哪"))
        assertFalse(evidence.locates(evidence.taskRef!!, "林月追出来，喊"))
        val draft = evidence.sources.single { it.ref.startsWith("D_") }
        assertFalse(evidence.locates(draft.ref, "林月追出来，喊"))
        assertFalse(evidence.locates(draft.ref, "林月追出来，喊", "CRAFT"))
        assertTrue(WritePrompts.projectReview(task, "正文", "", evidence).contains("不能作 FACT 依据"))
        // 改了上一章草稿：任务指纹跟着变（审读要重做）。
        val before = task.fingerprint
        s.saveText(w.id, c1, "早先的事。\n林月没有追出来。")
        assertTrue(before != s.writingTask(w.id, c2)!!.fingerprint)
        // 上一章是空章：照旧不带。
        s.saveText(w.id, c1, "")
        assertEquals("", s.writingTask(w.id, c2)!!.previousDraft)
        // 定稿了就是已定前文，不再算未定稿。
        s.saveText(w.id, c1, ending)
        s.setStatus(w.id, c1, ChapterStatus.FINAL)
        val finalTask = s.writingTask(w.id, c2)!!
        assertTrue(finalTask.previous.contains("你到底要去哪"))
        assertEquals("", finalTask.previousDraft)
    }

    private fun prose(scope: ProseScope = ProseScope.CHAPTER) =
        ProseTask(2, "第二章 夜渡", ChapterCard(facts = "沈默把船撑到北岸。", hook = "北岸有人等他"), scope, previousHook = "林月追问去向")

    @Test fun onlyRequestsFromTheChapterStartCarryTheHandoffAndTheContinuityLine() {
        val card = ChapterHandoff(scene = "渡口茶馆，天快黑了", people = listOf(HandoffPerson("林月", "追到门口", "又急又气")),
            mood = "憋着火", open = listOf("顾长风去哪"), hook = "林月追问去向")
        val section = WritePrompts.handoffSection(card, previousHook = "林月追问去向")
        assertTrue(section.startsWith(WritePrompts.HANDOFF_HEADER))
        // 和任务卡里“上一章停在”一样的钩子不重复写。
        assertFalse(section.contains("章末钩子"))
        val first = WritePrompts.draft("林月追出来。", prose(), handoff = section)
        assertTrue(first.indexOf("上一章结尾：") < first.indexOf(WritePrompts.HANDOFF_HEADER))
        assertTrue(first.indexOf(WritePrompts.HANDOFF_HEADER) < first.indexOf(WritePrompts.CARD_HEADER))
        assertTrue(first.contains("在场人物：林月：追到门口；又急又气"))
        assertTrue(first.contains(WritePrompts.CONTINUITY))
        // 原有任务卡结尾不变。
        assertTrue(first.endsWith("这些事要写出来，但写成场面和对话，不要列清单式交代。"))
        // 未定稿的上一章：标题写明。
        assertTrue(WritePrompts.draft("林月追出来。", prose(), handoff = section, tentative = true).startsWith(ChapterWritingTask.PREVIOUS_DRAFT + "：\n"))
        // 接着写、写到中间：不带。
        val more = WritePrompts.draftMore("沈默撑开船。", prose(ProseScope.TO_END))
        assertFalse(more.contains(WritePrompts.HANDOFF_HEADER) || more.contains(WritePrompts.CONTINUITY))
        val middle = WritePrompts.continueAt("沈默撑开船。", "", prose(ProseScope.SCENE), handoff = section)
        assertFalse(middle.contains(WritePrompts.HANDOFF_HEADER) || middle.contains(WritePrompts.CONTINUITY))
        val start = WritePrompts.continueAt("（上一章结尾）\n林月追出来。\n（本章）\n", "", prose(ProseScope.SCENE).copy(opening = true, previous = true), handoff = section)
        assertTrue(start.contains(WritePrompts.HANDOFF_HEADER) && start.contains(WritePrompts.CONTINUITY))
        // 修订整章不是从开头写：不带那一句。
        assertFalse(WritePrompts.taskCard(prose(ProseScope.REVISE).copy(opening = true, previous = true)).contains(WritePrompts.CONTINUITY))
    }

    @Test fun handoffRequestOnlySendsTheLastPartAndAsksForJson() {
        val long = "很早以前的事。".repeat(800) + "\n" + ending
        val p = WritePrompts.handoff("第一章 渡口", long, "林月追问去向", listOf("林月"))
        assertFalse(p.contains("很早以前的事。".repeat(500)))
        assertTrue(p.contains("你到底要去哪？"))
        assertTrue(p.contains("林月追问去向"))
        assertTrue(p.contains("只输出 JSON"))
    }
}
