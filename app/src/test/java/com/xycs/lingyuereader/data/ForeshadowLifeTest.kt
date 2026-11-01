package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 伏笔生命周期：最近一次实质推进只认细纲卡的“推进”和整理时 AI 引用编号、附了原句的“推进”，只提一句不算；
 * 状态（埋下、推进中、暂缓、已收）；拟细纲挑超期、停滞的旧伏笔（防剧透）；线索体检按最近推进判断冷不冷。人名虚构。
 */
class ForeshadowLifeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lamp = LedgerEntry(id = "lamp", kind = LedgerKind.PROMISE, text = "铜灯底座的记号", from = 3, payoff = 20)
    private val book = LedgerEntry(id = "book", kind = LedgerKind.PROMISE, text = "老周藏起的半本账册", from = 5)

    private val chapter = "林月又想起铜灯底座的记号。\n杜娘把半本账册摊开，第一页写着顾长风的名字。\n沈默说：“明早去渡口。”"

    @Test
    fun onlyAQuotedAdvanceWithAnIdMovesTheForeshadow() {
        val ledger = ChapterSyncs.ledgerView(listOf(lamp, book.copy(lastAdvanced = 9)), 30)
        // 列出的伏笔带编号，推进过的写最近推进。
        val bookCode = ledger.codes.entries.single { it.value.id == "book" }.key
        val lampCode = ledger.codes.entries.single { it.value.id == "lamp" }.key
        assertTrue("$bookCode：老周藏起的半本账册（第 5 章埋）（最近推进：第 9 章）" in ledger.text)
        val p = WritePrompts.sync("第三十章 渡口", 30, chapter, ledger = ledger.text)
        assertTrue("threads_advanced" in p && "只是又提了一句" in p && "不算推进" in p)
        assertTrue("已有的不要在这里另开一条" in p)

        val answer = """{"threads_advanced":[
            {"id":"$bookCode","how":"账册上出现了顾长风的名字","quote":"杜娘把半本账册摊开，第一页写着顾长风的名字。"},
            {"id":"P9","how":"编出来的编号","quote":"沈默说：“明早去渡口。”"},
            {"id":"$lampCode","how":"只是想起来","quote":"正文里没有这一句"}
        ],"time":{"when":""}}"""
        val parsed = WritePrompts.parseSync(answer, chapter, "c30", 30, ledger.codes)
        // 认不出的编号丢掉，原句找不到的丢掉：只剩引用了账册编号、附了原句的那一条。
        val item = parsed.items.single()
        assertEquals(SyncGroup.PROMISE, item.group)
        assertEquals("book", item.advances?.id)
        assertEquals("账册上出现了顾长风的名字", item.text)
        assertTrue(item.writable && item.defaultOn)

        // 写进账本：记下第 30 章推进，原来的内容不动。
        val ops = PackSync.ledgerOps(item, listOf(lamp, book.copy(lastAdvanced = 9)))
        assertEquals(listOf(LedgerOp.Advance("book", 30, item.quote)), ops)
        val (after, _) = StoryLedger.apply(listOf(lamp, book.copy(lastAdvanced = 9)), ops, 100L)
        val moved = after.single { it.id == "book" }
        assertEquals(30, moved.lastAdvanced)
        assertEquals("老周藏起的半本账册", moved.text)
        assertEquals(PromiseState.ADVANCING, Foreshadows.state(moved))
        // 铜灯那条只在正文里被想起一次：不动。
        assertNull(after.single { it.id == "lamp" }.lastAdvanced)
        assertEquals(PromiseState.PLANTED, Foreshadows.state(after.single { it.id == "lamp" }))
    }

    @Test
    fun aRepeatOfAnOldForeshadowWithoutAnIdIsMarkedMaybeDuplicate() {
        // AI 没引用编号，把旧伏笔当成新埋的报上来：不新建了事，标“可能重复”、默认不勾。
        val text = "杜娘又说起老周藏起的那半本账册。"
        val answer = """{"threads_opened":[{"what":"老周藏起的那半本账册","payoff":null,"quote":"杜娘又说起老周藏起的那半本账册。"}]}"""
        val items = WritePrompts.parseSync(answer, text, "c31", 31).items
        val planned = PackSync.plan(items, emptyList(), listOf(lamp, book)).single()
        assertEquals("book", planned.maybeSame?.id)
        assertFalse(planned.defaultOn)
        assertNull(planned.advances)
    }

    @Test
    fun advancingOnlyMovesForwardAndLeavesClosedOnesAlone() {
        val paused = book.copy(lastAdvanced = 12, state = PromiseState.PAUSED)
        val closed = lamp.copy(done = true, closed = 15)
        val (after, _) = StoryLedger.apply(listOf(paused, closed), listOf(
            LedgerOp.Advance("book", 10), LedgerOp.Advance("lamp", 18),
        ), 1L)
        // 往前倒的不记；收了的不动。
        assertEquals(12, after.single { it.id == "book" }.lastAdvanced)
        assertEquals(PromiseState.PAUSED, after.single { it.id == "book" }.state)
        assertNull(after.single { it.id == "lamp" }.lastAdvanced)
        // 往后挪的记下，暂缓换成推进中。
        val (later, _) = StoryLedger.apply(listOf(paused), listOf(LedgerOp.Advance("book", 16)), 2L)
        assertEquals(16, later.single().lastAdvanced)
        assertNull(later.single().state)
        assertEquals(PromiseState.ADVANCING, Foreshadows.state(later.single()))
    }

    @Test
    fun statesCanBeSetByHand() {
        assertEquals(PromiseState.PLANTED, Foreshadows.state(lamp))
        val paused = Foreshadows.withState(lamp, PromiseState.PAUSED)
        assertEquals(PromiseState.PAUSED, Foreshadows.state(paused))
        val closed = Foreshadows.withState(paused, PromiseState.CLOSED)
        assertTrue(closed.done)
        assertEquals(PromiseState.CLOSED, Foreshadows.state(closed))
        // 从已收改回推进中：重新打开，没有推进章时记成当前这一章。
        val reopened = Foreshadows.withState(closed, PromiseState.ADVANCING, 22)
        assertFalse(reopened.done)
        assertNull(reopened.closed)
        assertEquals(22, reopened.lastAdvanced)
        assertEquals(PromiseState.ADVANCING, Foreshadows.state(reopened))
        // 改回埋下：清掉推进章。
        assertNull(Foreshadows.withState(reopened, PromiseState.PLANTED).lastAdvanced)
        // 细纲卡里的推进也算进“推进中”。
        assertEquals(PromiseState.ADVANCING, Foreshadows.state(lamp, advanced = 8))
    }

    @Test
    fun theLedgerKeepsTheNewFieldsAndReadsOldOnes() {
        val e = book.copy(lastAdvanced = 9, state = PromiseState.PAUSED, recheck = true)
        val back = LedgerEntry.fromJson(JSONObject(e.toJson().toString()))!!
        assertEquals(e, back)
        // 旧账本没有这几项：读成空的。
        val old = JSONObject().put("id", "x").put("kind", "PROMISE").put("text", "顾长风说三日后再来").put("from", 8)
        val read = LedgerEntry.fromJson(old)!!
        assertNull(read.lastAdvanced)
        assertNull(read.state)
        assertFalse(read.recheck)
        assertEquals(PromiseState.PLANTED, Foreshadows.state(read))
    }

    @Test
    fun planningPicksOverdueThenStalledButNothingFromLater() {
        val overdue = lamp // 计划第 20 章收
        val stalled = book.copy(lastAdvanced = 6) // 第 6 章推进过，到第 40 章已经 34 章没动
        val fresh = LedgerEntry(id = "fresh", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 35)
        val paused = LedgerEntry(id = "paused", kind = LedgerKind.PROMISE, text = "杜娘的旧伤", from = 1, state = PromiseState.PAUSED)
        val secret = LedgerEntry(id = "secret", kind = LedgerKind.PROMISE, text = "沈默是朝廷的卧底", from = 1, secret = true)
        val ahead = LedgerEntry(id = "ahead", kind = LedgerKind.PROMISE, text = "第五十章才埋的灯谜", from = 50, payoff = 30)
        val closed = LedgerEntry(id = "closed", kind = LedgerKind.PROMISE, text = "渡口的船票", from = 2, payoff = 9, done = true, closed = 9)
        val all = listOf(fresh, paused, secret, ahead, closed, stalled, overdue)
        val picked = Foreshadows.stale(all, 40)
        assertEquals(listOf("lamp", "book"), picked.map { it.entry.id })
        assertTrue(picked[0].overdue)
        assertEquals(34, picked[1].idle)
        assertEquals("铜灯底座的记号（第 3 章埋；计划第 20 章收，已经过了）", Foreshadows.planLine(picked[0]))
        assertEquals("老周藏起的半本账册（第 5 章埋；最近推进在第 6 章；已经 34 章没推进）", Foreshadows.planLine(picked[1]))
        // 写第 10 章：那时铜灯还没超期，账册才 4 章没动；记在后面章的推进不算（防剧透）。
        assertTrue(Foreshadows.stale(listOf(overdue, book.copy(lastAdvanced = 38)), 10).isEmpty())
        // 后面章记的推进不当成这一章之前的推进：写第 40 章时第 45 章的推进看不见，照样算停滞。
        assertEquals(listOf("book"), Foreshadows.stale(listOf(book.copy(lastAdvanced = 45)), 40).map { it.entry.id })
        // 最多 3 条。
        val many = (1..6).map { LedgerEntry(id = "m$it", kind = LedgerKind.PROMISE, text = "旧伏笔$it", from = it) }
        assertEquals(Foreshadows.PLAN_MAX, Foreshadows.stale(many, 60).size)
        // 细纲卡里的推进：只认这一章之前的。
        val adv = Foreshadows.cardAdvances(listOf(
            30 to listOf(ForeshadowMove(MoveAction.ADVANCE, "账册", "book")),
            45 to listOf(ForeshadowMove(MoveAction.ADVANCE, "账册", "book")),
            32 to listOf(ForeshadowMove(MoveAction.DEFER, "账册", "book"), ForeshadowMove(MoveAction.PLANT, "新的", "lamp")),
        ), before = 40)
        assertEquals(mapOf("book" to 30), adv)
        assertTrue(Foreshadows.stale(listOf(book), 40, adv).isEmpty())
    }

    @Test
    fun thePlanPromptOffersStaleForeshadowsWithoutForcingThem() {
        val p = WritePrompts.plan("第四十章 夜渡", "", null, null, "", stale = listOf("铜灯底座的记号（第 3 章埋；计划第 20 章收，已经过了）"))
        assertTrue("可以推进或回收的旧伏笔" in p && "不强制" in p && "铜灯底座的记号" in p)
        // 没有就不提。
        assertFalse("可以推进或回收的旧伏笔" in WritePrompts.plan("第四十章 夜渡", "", null, null, ""))
    }

    @Test
    fun thePulseJudgesForeshadowsByTheLastRealAdvance() {
        val chapters = (1..40).map { n -> PulseChapter("c$n", n, "第${n}章", "明线", "", emptyList()) }
        // 第 5 章埋、从没推进：冷了。
        val cold = StoryPulse.check(chapters, listOf(book)).single { it.kind == PulseKind.FORESHADOW }
        assertEquals(PulseState.COLD, cold.state)
        // 第 25 章有过实质推进：还不冷，最近一次是第 25 章。
        val warm = StoryPulse.check(chapters, listOf(book.copy(lastAdvanced = 25))).single { it.kind == PulseKind.FORESHADOW }
        assertEquals(PulseState.OK, warm.state)
        assertEquals(25, warm.lastNo)
        // 账本里标了暂缓：不催。
        val paused = StoryPulse.check(chapters, listOf(book.copy(state = PromiseState.PAUSED))).single { it.kind == PulseKind.FORESHADOW }
        assertEquals(PulseState.DEFERRED, paused.state)
        assertTrue(paused.note.contains("账本里标了暂缓"))
    }

    @Test
    fun finalizingRecordsTheCardsAdvances() {
        val store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        val chapterId = w.chapters.single().id
        store.applyLedger(w.id, listOf(LedgerOp.Add(book)), "手动添加")
        // 细纲卡写了推进、正文还没写：不算。
        store.saveCard(w.id, chapterId, ChapterCard(moves = listOf(ForeshadowMove(MoveAction.ADVANCE, "账册露出顾长风的名字", "book"))))
        assertEquals(0, store.advanceFromCard(w.id, chapterId))
        store.saveText(w.id, chapterId, "杜娘把半本账册摊开。", count = false)
        assertEquals(1, store.advanceFromCard(w.id, chapterId))
        assertEquals(1, store.ledger(w.id).single().lastAdvanced)
        // 已经记过：再定稿一次不重复写账本。
        assertEquals(0, store.advanceFromCard(w.id, chapterId))
        assertNotNull(store.ledger(w.id).single().lastAdvanced)
    }
}
