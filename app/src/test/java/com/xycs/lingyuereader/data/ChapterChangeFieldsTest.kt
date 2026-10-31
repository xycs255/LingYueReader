package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.ProseScope
import com.xycs.lingyuereader.ai.ProseTask
import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 细纲卡新加的两栏“本章改变了什么”“伏笔动作”：读写、旧数据、备份、对上账本伏笔、拟细纲产出、写正文的任务卡、事实审稿核对。
 * 人名、文字都是造的（《渡灯》）。
 */
class ChapterChangeFieldsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val moves = listOf(
        ForeshadowMove(MoveAction.PLANT, "铜灯底座的记号"),
        ForeshadowMove(MoveAction.ADVANCE, "老周藏起的半本账册"),
        ForeshadowMove(MoveAction.DEFER, "顾长风的来历"),
    )
    private val card = ChapterCard(facts = "林月在旧仓找到铜灯", change = "林月和沈默从互相提防变成暂时合作", moves = moves)

    @Test
    fun movesAreParsedFromTextAndKeepTheirLedgerIds() {
        val parsed = ForeshadowMoves.parse("埋：铜灯底座的记号\n- 推进：老周藏起的半本账册。\n暂缓：顾长风的来历\n\n无")
        assertEquals(moves, parsed)
        assertEquals("埋：铜灯底座的记号\n推进：老周藏起的半本账册\n暂缓：顾长风的来历", ForeshadowMoves.format(parsed))
        // AI 常写在一行里，用“；”隔开；没写动作的算推进；“收”算回收。
        assertEquals(listOf(MoveAction.PLANT, MoveAction.PAYOFF, MoveAction.ADVANCE),
            ForeshadowMoves.parse("埋下：灯油的味道；收：杜娘的旧伤\n沈默的玉佩").map { it.action })
        // 说明没变的沿用原来的账本 id；改了说明的要重新对。
        val linked = listOf(ForeshadowMove(MoveAction.ADVANCE, "老周藏起的半本账册", "p1"))
        assertEquals("p1", ForeshadowMoves.parse("推进：老周藏起的半本账册", linked).single().ledgerId)
        assertEquals("", ForeshadowMoves.parse("回收：老周藏起的半本账册", linked).single().ledgerId)
        // 对上账本里的伏笔：像的挂上 id，不像的留作自由文字。
        val ledger = listOf(
            LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "老周把半本账册藏了起来", from = 3),
            LedgerEntry(id = "p2", kind = LedgerKind.PROMISE, text = "顾长风的真实来历", from = 5),
            LedgerEntry(id = "s1", kind = LedgerKind.STATE, text = "铜灯底座的记号", subject = "林月"),
        )
        val out = ForeshadowMoves.link(moves, ledger)
        assertEquals(listOf("", "p1", "p2"), out.map { it.ledgerId })
    }

    @Test
    fun theCardStoresTheTwoFieldsAndOldCardsStillRead() {
        val dir = tmp.newFolder()
        val s = WorkStore(dir)
        val w = s.create("渡灯")
        val c = w.chapters.single().id
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "老周把半本账册藏了起来", from = 1))), "测试")
        s.saveCard(w.id, c, card)
        val saved = s.card(w.id, c)
        assertEquals(card.change, saved.change)
        assertEquals(moves.map { it.text }, saved.moves.map { it.text })
        // 存的时候对上了账本里的伏笔。
        assertEquals("p1", saved.moves[1].ledgerId)
        // 按字段改：只改伏笔动作，别的不动。
        s.updateCard(w.id, c) { it.copy(moves = it.moves.take(1)) }
        assertEquals(card.change, s.card(w.id, c).change)
        assertEquals(1, s.card(w.id, c).moves.size)
        // 旧卡片没有这两项：读成空的。
        val file = File(dir, "works/${w.id}/$c.card.json")
        val old = JSONObject(file.readText()).apply { remove("change"); remove("moves") }
        file.writeText(old.toString())
        assertEquals("", s.card(w.id, c).change)
        assertTrue(s.card(w.id, c).moves.isEmpty())
        assertEquals("林月在旧仓找到铜灯", s.card(w.id, c).facts)
    }

    @Test
    fun theTwoFieldsTravelWithTheBackupAndBrokenOnesAreRejected() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        val c = w.chapters.single().id
        s.saveCard(w.id, c, card)
        val backup = String(s.backup()!!)
        val other = WorkStore(tmp.newFolder())
        other.restoreBackup(backup)
        assertEquals(card.change, other.card(w.id, c).change)
        assertEquals(moves, other.card(w.id, c).moves)
        // 旧备份（卡片里没有这两项）照样恢复。
        val old = JSONObject(backup).apply {
            getJSONArray("works").getJSONObject(0).getJSONObject("cards").getJSONObject(c).apply { remove("change"); remove("moves") }
        }
        val third = WorkStore(tmp.newFolder())
        third.restoreBackup(old.toString())
        assertEquals("林月在旧仓找到铜灯", third.card(w.id, c).facts)
        assertTrue(third.card(w.id, c).moves.isEmpty())
        // 坏的：伏笔动作不是数组、本章改变了什么不是字符串，整份拒绝，不悄悄丢。
        listOf("moves" to JSONObject().put("x", 1), "change" to JSONArray().put("x")).forEach { (key, bad) ->
            val broken = JSONObject(backup).apply {
                getJSONArray("works").getJSONObject(0).getJSONObject("cards").getJSONObject(c).put(key, bad)
            }
            try {
                WorkStore(tmp.newFolder()).validateBackup(broken.toString())
                fail("应当拒绝：$key")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun planningAsksForAndReadsBackTheTwoFields() {
        val prompt = WritePrompts.plan("第七章 旧仓", "", null, null, "", request = "推进「灯会旧案」这条线（已经 12 章没动）")
        assertTrue(prompt.contains("本章改变了什么："))
        assertTrue(prompt.contains("伏笔动作："))
        assertTrue(prompt.contains("“埋”“推进”“回收”或“暂缓”"))
        assertTrue(prompt.contains("作者这次的要求：推进「灯会旧案」这条线"))
        val parsed = WritePrompts.parsePlan(
            "本章事实与结果：林月在旧仓找到铜灯\n后文承接边界：无\n视角人物：林月\n倒计时：无\n章末钩子：仓顶有脚步声\n推进的线：明线：灯会旧案\n" +
                "本章改变了什么：林月和沈默从互相提防变成暂时合作\n伏笔动作：\n埋：铜灯底座的记号\n推进：老周藏起的半本账册\n暂缓：顾长风的来历",
        )
        assertEquals("林月和沈默从互相提防变成暂时合作", parsed.change)
        assertEquals(moves, parsed.moves)
        assertEquals("明线：灯会旧案", parsed.threads)
        // 写成一行、写“无”的也认。
        assertEquals(2, WritePrompts.parsePlan("本章事实与结果：甲\n伏笔动作：埋：灯油；回收：玉佩").moves.size)
        assertTrue(WritePrompts.parsePlan("本章事实与结果：甲\n本章改变了什么：无\n伏笔动作：无").let { it.change.isEmpty() && it.moves.isEmpty() })
        // AI 拟的补进细纲卡：只补空着的。
        val merged = ChapterCard(change = "作者写的变化").withAiOutline(ChapterCard(change = "作者写的变化"), parsed)
        assertEquals("作者写的变化", merged.change)
        assertEquals(moves, merged.moves)
    }

    @Test
    fun theTaskCardCarriesTheChangeAndTheMoves() {
        val text = WritePrompts.taskCard(ProseTask(7, "第七章 旧仓", card, ProseScope.CHAPTER))
        assertTrue(text.contains(WritePrompts.CHANGE_HEAD + "林月和沈默从互相提防变成暂时合作"))
        assertTrue(text.contains(WritePrompts.MOVES_HEAD))
        assertTrue(text.contains("- 埋：铜灯底座的记号"))
        assertTrue(text.contains("- 暂缓：顾长风的来历"))
        // 原有的几行照旧，最后一句照旧。
        assertTrue(text.contains("本章必须成立的事：林月在旧仓找到铜灯"))
        assertTrue(text.endsWith("这些事要写出来，但写成场面和对话，不要列清单式交代。"))
        // 修订：变化照带，伏笔动作不再列（和可以埋的伏笔一样）。
        val revise = WritePrompts.taskCard(ProseTask(7, "第七章 旧仓", card, ProseScope.REVISE))
        assertTrue(revise.contains(WritePrompts.CHANGE_HEAD))
        assertFalse(revise.contains(WritePrompts.MOVES_HEAD))
        // 两项都空：任务卡和以前一字不差。
        val plain = ChapterCard(facts = "林月在旧仓找到铜灯")
        val before = WritePrompts.taskCard(ProseTask(7, "第七章 旧仓", plain, ProseScope.CHAPTER))
        assertFalse(before.contains(WritePrompts.CHANGE_HEAD))
        assertFalse(before.contains(WritePrompts.MOVES_HEAD))
        // 细纲卡带给 AI 的文字（改稿、灵感用）也有这两项。
        assertTrue(WritePrompts.card(card, null, null)!!.contains("本章改变了什么：林月和沈默"))
        assertTrue(WritePrompts.card(card, null, null)!!.contains("伏笔动作：埋：铜灯底座的记号；推进"))
    }

    @Test
    fun theFactReviewChecksTheyLandedOnlyWhenWritten() {
        val review = WritePrompts.factReview("林月推开仓门。", "第七章 旧仓", "", card)
        assertTrue(review.contains("细纲卡要这一章落地的："))
        assertTrue(review.contains("- 本章改变了什么：林月和沈默从互相提防变成暂时合作"))
        assertTrue(review.contains("- 伏笔动作：推进：老周藏起的半本账册"))
        assertTrue(review.contains(WritePrompts.LANDING_RULE))
        // 证据规则照旧：只输出带原句的 JSON。
        assertTrue(review.contains("\"quote\""))
        val plain = WritePrompts.factReview("林月推开仓门。", "第七章 旧仓", "", ChapterCard(facts = "甲"))
        assertFalse(plain.contains("细纲卡要这一章落地的"))
        assertFalse(plain.contains(WritePrompts.LANDING_RULE))
        assertEquals(plain, WritePrompts.factReview("林月推开仓门。", "第七章 旧仓", ""))
    }

    @Test
    fun theProjectTaskCarriesThemAndTheirFingerprintOnlyWhenWritten() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        val c = w.chapters.single().id
        s.saveCard(w.id, c, ChapterCard(facts = "林月在旧仓找到铜灯"))
        val plain = s.writingTask(w.id, c)!!
        assertFalse(plain.render().contains("伏笔动作"))
        s.saveCard(w.id, c, card)
        val task = s.writingTask(w.id, c)!!
        assertTrue(task.render().contains("本章要改变的（人物关系、处境、知道的事）：\n林月和沈默从互相提防变成暂时合作"))
        assertTrue(task.render().contains("伏笔动作：\n埋：铜灯底座的记号"))
        assertNotEquals(plain.fingerprint, task.fingerprint)
        // 写回空的：指纹和没有这两项时一样（旧审读不因升级失效）。
        s.saveCard(w.id, c, ChapterCard(facts = "林月在旧仓找到铜灯"))
        assertEquals(plain.fingerprint, s.writingTask(w.id, c)!!.fingerprint)
        // 审读核对它们落没落地。
        assertTrue(WritePrompts.projectReview(task, "林月推开仓门。", "").contains("核对它们有没有用场面落到正文里"))
        assertFalse(WritePrompts.projectReview(plain, "林月推开仓门。", "").contains("核对它们有没有用场面落到正文里"))
    }
}
