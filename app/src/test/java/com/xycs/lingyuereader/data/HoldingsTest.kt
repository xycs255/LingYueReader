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

/**
 * 数值与持有：按作品开关（默认关）；整理时开着才让 AI 提、才认；本机对账查出期初期末对不上、一件东西同时在两个人手里；
 * 写正文的账本快照带上出场人物的当前值。人名、物件都是虚构的。
 */
class HoldingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hold(id: String, who: String, item: String, from: Int, before: String, after: String, delta: String = "", thing: Boolean = false, quote: String = "") =
        LedgerEntry(id = id, kind = LedgerKind.HOLD, text = after, subject = who, aspect = item, from = from, before = before, delta = delta, item = thing,
            quote = quote, chapterId = "c$from")

    @Test
    fun theSwitchIsOffByDefaultAndOldDataReadsAsOff() {
        assertFalse(WorkAssist().holdings)
        assertFalse(WorkAssist().planOptions)
        val dir = tmp.newFolder("writing")
        val store = WorkStore(dir)
        val w = store.create("渡灯")
        assertFalse(store.assist(w.id).holdings)
        store.updateAssist(w.id) { it.copy(holdings = true, planOptions = true) }
        val again = WorkStore(dir).assist(w.id)
        assertTrue(again.holdings)
        assertTrue(again.planOptions)
    }

    @Test
    fun aMismatchedOpeningIsReported() {
        val a = hold("a", "林月", "灵石", 3, "120", "90", "花去 30", quote = "林月数出三十块灵石。")
        val b = hold("b", "林月", "灵石", 4, "100", "60", "又花 40", quote = "她又拿出四十块。")
        val issue = Holdings.check(listOf(a, b)).single()
        assertEquals("a", issue.first.id)
        assertEquals("b", issue.second.id)
        assertTrue(issue.title.contains("期初写的「100」") && issue.title.contains("期末「90」"))
        assertEquals(setOf("c3", "c4"), issue.chapterIds)
        // 对得上的不报；写法不同（空格、标点）算一样。
        assertTrue(Holdings.check(listOf(a, b.copy(before = "90"))).isEmpty())
        val realm = hold("r1", "沈默", "境界", 2, "炼气九层", "筑基 初期")
        assertTrue(Holdings.check(listOf(realm, hold("r2", "沈默", "境界", 5, "筑基初期", "筑基中期"))).isEmpty())
        // 只有作者知道的不查。
        assertTrue(Holdings.check(listOf(a, b.copy(secret = true))).isEmpty())
    }

    @Test
    fun anItemInTwoHandsIsReportedButAHandOverIsNot() {
        val lin = hold("lin", "林月", "青铜灯", 3, "没有", "持有", thing = true, quote = "林月把青铜灯揣进怀里。")
        val shen = hold("shen", "沈默", "青铜灯", 6, "没有", "持有", thing = true, quote = "沈默提着青铜灯上了船。")
        val issue = Holdings.check(listOf(lin, shen)).single()
        assertTrue(issue.title.contains("同时在林月和沈默手里"))
        assertEquals("林月把青铜灯揣进怀里。", issue.first.quote)
        assertEquals("沈默提着青铜灯上了船。", issue.second.quote)
        // 第 6 章林月交出去了：不报。
        val gave = hold("gave", "林月", "青铜灯", 6, "持有", "交出", thing = true)
        assertTrue(Holdings.check(listOf(lin, shen, gave)).isEmpty())
        // 当前值里只有沈默拿着。
        assertEquals(listOf("shen"), Holdings.current(listOf(lin, shen, gave)).map { it.id })
        assertEquals("沈默·青铜灯：在他手里（第 6 章）", Holdings.line(shen))
    }

    @Test
    fun syncOnlyProposesHoldingsWhenTheSwitchIsOn() {
        val text = "林月数出三十块灵石。\n她把青铜灯交给沈默。"
        val answer = """{"holdings":[
            {"who":"林月","item":"灵石","kind":"数值","before":"100","change":"花去 30","after":"70","quote":"林月数出三十块灵石。"},
            {"who":"沈默","item":"青铜灯","kind":"物品","before":"没有","change":"林月给的","after":"持有","quote":"她把青铜灯交给沈默。"}
        ],"time":{"when":""}}"""
        // 开关关着：模型多回了也不认，提示词里也不要这一项。
        assertTrue(WritePrompts.parseSync(answer, text, "c4", 4).items.none { it.group == SyncGroup.HOLD })
        assertFalse("holdings" in WritePrompts.sync("第四章", 4, text))
        val p = WritePrompts.sync("第四章", 4, text, holdings = true)
        assertTrue("holdings（数值与持有）" in p && "before" in p && "after" in p)

        val items = WritePrompts.parseSync(answer, text, "c4", 4, holdings = true).items
        assertEquals(2, items.size)
        val money = items.first { it.entry?.aspect == "灵石" }
        assertEquals("林月·灵石：100 → 花去 30 → 70", money.title)
        assertEquals(Triple("100", "花去 30", "70"), Triple(money.entry!!.before, money.entry!!.delta, money.entry!!.text))
        assertTrue(items.first { it.entry?.aspect == "青铜灯" }.entry!!.item)

        // 账本里上一章记的期末是 90：期初 100 对不上，标出来、默认不勾，作者确认了才写。
        val last = hold("a", "林月", "灵石", 3, "120", "90", quote = "林月数出三十块灵石。")
        val planned = PackSync.plan(items, emptyList(), listOf(last))
        val flagged = planned.first { it.entry?.aspect == "灵石" }
        assertEquals("a", flagged.holdMismatch?.id)
        assertFalse(flagged.defaultOn)
        val fine = planned.first { it.entry?.aspect == "青铜灯" }
        assertNull(fine.holdMismatch)
        assertTrue(fine.defaultOn)
        val op = PackSync.ledgerOp(fine, listOf(last)) as LedgerOp.Add
        assertEquals(LedgerKind.HOLD, op.entry.kind)
        assertTrue(op.entry.item)
    }

    @Test
    fun theWritingSnapshotCarriesWhatThePresentPeopleHold() {
        val entries = listOf(
            hold("a", "林月", "灵石", 3, "120", "90"),
            hold("b", "林月", "灵石", 8, "90", "60"),
            hold("c", "老周", "渡船", 2, "没有", "持有", thing = true),
            // 后面章的不给（防剧透）。
            hold("d", "林月", "境界", 12, "炼气三层", "炼气四层"),
        )
        val snap = StoryLedger.snapshot(entries, 10, null, listOf("林月"))
        assertEquals(listOf("b"), snap.holds.map { it.id })
        val text = snap.render(2_000)!!
        assertTrue(text.contains("人物现在的数值与持有物"))
        assertTrue(text.contains("林月·灵石：90 → 60（第 8 章）"))
        assertFalse(text.contains("炼气四层"))
        // 整理时：开着这一类才给出场人物的当前值。
        assertFalse(ChapterSyncs.ledgerView(entries, 10, present = listOf("林月")).text.contains("数值与持有"))
        assertTrue(ChapterSyncs.ledgerView(entries, 10, present = listOf("林月"), holdings = true).text.contains("林月·灵石：90 → 60"))
    }

    @Test
    fun theLedgerKeepsHoldFieldsAndOldEntriesStillRead() {
        val e = hold("a", "林月", "青铜灯", 3, "没有", "持有", "从旧仓里找到", thing = true, quote = "林月把青铜灯揣进怀里。")
        assertEquals(e, LedgerEntry.fromJson(JSONObject(e.toJson().toString())))
        val old = JSONObject().put("id", "x").put("kind", "STATE").put("text", "在渡口").put("subject", "林月")
        val read = LedgerEntry.fromJson(old)!!
        assertEquals("", read.before)
        assertFalse(read.item)
    }
}
