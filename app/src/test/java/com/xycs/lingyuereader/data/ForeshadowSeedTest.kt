package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.ProseScope
import com.xycs.lingyuereader.ai.ProseTask
import com.xycs.lingyuereader.ai.WritePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 作者的决定：写正文时后面的剧情要能拿来铺垫。账本快照里后面才知道、后面才收的照样列着，但不说第几章，
 * 只说这一章能做到哪一步；任务卡多一节“这一章可以埋的伏笔”，只点到为止；工具说明里写明后文只用来铺垫。
 */
class ForeshadowSeedTest {
    @get:Rule val tmp = TemporaryFolder()

    private val people = "## 林月\n- 想要：找到父亲。\n## 沈默\n- 说话：半句话。\n## 杜娘\n- 说话：先损人再帮人。\n## 顾北山\n- 漕帮的人。\n"
    private val docs = listOf(PackDoc("c", PackKind.CHARACTER, "人物档案") to people)
    private val card = "林月到渡口，杜娘说老周走了，沈默赶来。"

    private val ledger = listOf(
        LedgerEntry(kind = LedgerKind.KNOW, subject = "林月", text = "沈默是朝廷暗探", from = 20),
        LedgerEntry(kind = LedgerKind.PROMISE, subject = "林月", text = "玉佩的来历", from = 1, payoff = 40),
        LedgerEntry(kind = LedgerKind.STATE, subject = "林月", aspect = "位置", text = "白沙渡", from = 2),
        // 和出场人物无关，但发生在她所在的地方。
        LedgerEntry(kind = LedgerKind.PROMISE, text = "白沙渡码头下沉着一口铁箱", from = 2, payoff = 30),
        // 和这一章无关：不在任务卡里。
        LedgerEntry(kind = LedgerKind.PROMISE, subject = "顾北山", text = "顾北山袖口的刺青", from = 1, payoff = 50),
        // 这一章就该收的：是要收的，不是要埋的。
        LedgerEntry(kind = LedgerKind.PROMISE, subject = "杜娘", text = "杜娘的旧账本", from = 1, payoff = 3),
    )

    private fun prose() = ChapterBriefs.build(docs, "", card, chapter = 3, volume = 1, ledger = ledger, ahead = false)

    @Test
    fun theLedgerSaysLaterWithoutChapterNumbersWhenWritingProse() {
        val text = prose().render(prose = true)!!
        assertTrue(text, "沈默是朝廷暗探——这件事林月现在还不知道，后面才会知道。这一章别让林月知道或说破，可以留个引子。" in text)
        assertTrue(text, "玉佩的来历（第 1 章埋）——这条伏笔后面才收，这一章可以轻轻提一笔，别收。" in text)
        // 不说后面第几章知道、计划第几章收。
        listOf("第 20 章", "计划第 40 章收", "计划第 30 章收", "要到").forEach { assertFalse(it, it in text) }
        // 这一章要收的照旧写明。
        assertTrue("杜娘的旧账本" in text)
        // 不写正文的路径（审稿、账本页）照旧带章号。
        val other = ChapterBriefs.build(docs, "", card, chapter = 3, volume = 1, ledger = ledger).render()!!
        assertTrue("林月要到第 20 章才知道：沈默是朝廷暗探" in other)
        assertTrue("计划第 40 章收" in other)
    }

    @Test
    fun seedsAreTheOpenOnesAboutThisChaptersPeopleAndPlaces() {
        val seeds = prose().seeds()
        assertEquals(listOf("玉佩的来历", "白沙渡码头下沉着一口铁箱"), seeds)
        // 伏笔表里的也算，最多 5 条。
        val table = "| 伏笔 | 埋 | 收 |\n| --- | --- | --- |\n" + (1..6).joinToString("") { "| 沈默的第${it}封信 | 第1章 | 第${30 + it}章 |\n" }
        val many = ChapterBriefs.build(docs + (PackDoc("f", PackKind.FORESHADOW, "伏笔表") to table), "", card, chapter = 3, volume = 1, ledger = ledger, ahead = false)
        assertEquals(ChapterBrief.SEEDS, many.seeds().size)
        // 没有相关的：一条也没有。
        assertTrue(ChapterBriefs.build(docs, "", "老周在城里。", chapter = 3, volume = 1, ahead = false).seeds().isEmpty())
    }

    @Test
    fun theTaskCardListsSeedsOnlyWhenThereAreAny() {
        val task = ProseTask(3, "第3章 空船", ChapterCard(facts = card), ProseScope.CHAPTER, seeds = listOf("玉佩的来历", "白沙渡码头下沉着一口铁箱"))
        val c = WritePrompts.taskCard(task)
        assertTrue(c, "这一章可以埋的伏笔" in c)
        assertTrue(c, "- 玉佩的来历（只点到为止）\n- 白沙渡码头下沉着一口铁箱（只点到为止）" in c)
        assertTrue("别让人物提前知道" in c)
        // 没有可埋的：这一节不写。
        assertFalse("可以埋的伏笔" in WritePrompts.taskCard(task.copy(seeds = emptyList())))
        // 修订不加新东西。
        assertFalse("可以埋的伏笔" in WritePrompts.taskCard(task.copy(scope = ProseScope.REVISE)))
    }

    @Test
    fun theToolGuideSaysLaterPlotIsOnlyForForeshadowing() {
        val g = WritePrompts.proseToolGuide(listOf("总纲", "第一卷细纲"))
        assertTrue(g, "后面的剧情只用来埋伏笔、做铺垫" in g)
        assertTrue("不能让人物提前知道" in g && "不能说破" in g && "不能写出后面才发生的事" in g)
    }

    @Test
    fun planningStillSeesNothingLater() {
        // 拟细纲、拆节拍上一轮刚收紧过：不能用后文。铺垫的规矩只给写正文，这里不动。
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        s.addChapter(w.id, w.volumes[0].id)
        val (_, three) = s.addChapter(w.id, w.volumes[0].id)!!
        s.renameChapter(w.id, three.id, "第3章 空船")
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "# 第一卷 细纲\n### 第3章 空船\n事实：林月到渡口。\n### 第10章 夜航\n事实：顾长风在夜里截住沈默。\n")
        s.addPackDoc(w.id, PackKind.OUTLINE, "总纲", "# 总纲\n## 结局\n沈默留在青石镇。\n")
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", people)
        val hints = s.chapterHints(w.id, three.id, "", upTo = true, noAhead = true)
        listOf("顾长风在夜里截住沈默", "沈默留在青石镇", "可以埋的伏笔", "只用来埋伏笔").forEach { assertFalse(it, it in hints) }
    }
}
