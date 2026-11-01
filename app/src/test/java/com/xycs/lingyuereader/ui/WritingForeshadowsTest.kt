package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.Foreshadow
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingForeshadowsTest {
    @Test
    fun ledgerClosureWinsOverAnOpenSourceTableWithoutDuplicatingTheRow() {
        val source = Foreshadow("合成信物：铜环", planted = 1, payoff = 4, doc = "source", line = 3)
        val entry = LedgerEntry(kind = LedgerKind.PROMISE, text = "合成信物：铜环", from = 1, payoff = 4, done = true, closed = 3)
        val merged = mergeWritingForeshadows(listOf(source), listOf(entry)).single()
        assertTrue(merged.display.done)
        assertEquals(3, merged.display.closed)
        assertEquals(listOf(source), merged.legacy)
        assertEquals(entry.id, merged.ledger?.id)
    }

    @Test
    fun legacyAndLedgerOnlyPromisesAreBothVisible() {
        val source = Foreshadow("合成样本：回信", planted = 1, payoff = 5)
        val entry = LedgerEntry(kind = LedgerKind.PROMISE, text = "合成样本：桥下的箱子", from = 2, payoff = 6)
        val merged = mergeWritingForeshadows(listOf(source), listOf(entry))
        assertEquals(setOf(source.what, entry.text), merged.map { it.display.what }.toSet())
        assertTrue(merged.none { it.display.done })
    }

    @Test
    fun closingAnUnimportedSourceCreatesTheCanonicalLedgerState() {
        val source = Foreshadow("合成样本：暗号（只有作者知道）", planted = 2, payoff = 7)
        val item = mergeWritingForeshadows(listOf(source), emptyList()).single()
        val added = (item.close(7) as LedgerOp.Add).entry
        assertEquals("合成样本：暗号", added.text)
        assertTrue(added.secret)
        assertTrue(added.done)
        assertEquals(7, added.closed)
        assertFalse(added.openAt(7))
        assertTrue(added.openAt(6))
    }

    /** 作品页卡片上的伏笔数：伏笔表里没导入的也算；计划回收的章不晚于写到的章算该回收（原来伏笔页的口径）。 */
    @Test
    fun theOverviewCountsTableOnlyForeshadowsAndWhichAreDue() {
        val table = listOf(Foreshadow("玉佩之谜", planted = 3, payoff = 8), Foreshadow("铜灯的来历", planted = 2, payoff = 30))
        val ledger = listOf(
            LedgerEntry(kind = LedgerKind.PROMISE, text = "林月答应杜娘回渡口", from = 2, payoff = 9),
            LedgerEntry(kind = LedgerKind.PROMISE, text = "沈默的旧伤", from = 1, payoff = 4, done = true, closed = 4),
        )
        val counts = promiseCounts(mergeWritingForeshadows(table, ledger), latest = 9)
        assertEquals(PromiseCounts(total = 4, open = 3, due = 2), counts)
        assertEquals("2 条该回收", ledgerValue(2, counts))
        // 账本是空的、只有伏笔表：不说“还没有记录”。
        val onlyTable = promiseCounts(mergeWritingForeshadows(table.take(1), emptyList()), latest = 9)
        assertEquals("1 条该回收", ledgerValue(0, onlyTable))
        assertEquals("1 条伏笔没收", ledgerValue(0, promiseCounts(mergeWritingForeshadows(table.take(1), emptyList()), latest = 5)))
    }

    /** 账本空、伏笔表还没读完时按加载中，不先出整页空状态。 */
    @Test
    fun anEmptyLedgerWaitsForTheForeshadowTableBeforeShowingTheEmptyState() {
        assertEquals(LedgerBody.LOADING, ledgerBody(null, false, emptyList()))
        assertEquals(LedgerBody.LOADING, ledgerBody(emptyList(), false, null))
        assertEquals(LedgerBody.EMPTY, ledgerBody(emptyList(), false, emptyList()))
        assertEquals(LedgerBody.TABS, ledgerBody(emptyList(), false, listOf(Foreshadow("玉佩之谜", planted = 3, payoff = 8))))
        assertEquals(LedgerBody.TABS, ledgerBody(listOf(LedgerEntry(kind = LedgerKind.EVENT, text = "老周的船沉了", from = 3)), false, null))
        assertEquals(LedgerBody.LOADING, ledgerBody(listOf(LedgerEntry(kind = LedgerKind.EVENT, text = "老周的船沉了", from = 3)), true, null))
    }

    /** 筛选行下的说明：只在“全部记录”说条数，按“只看谁”过滤后的数。 */
    @Test
    fun theFilterFooterCountsWhatIsListed() {
        val entries = listOf(
            LedgerEntry(kind = LedgerKind.STATE, text = "在渡口守灯", subject = "林月", from = 2),
            LedgerEntry(kind = LedgerKind.STATE, text = "回了城南茶馆", subject = "沈默", from = 3),
        )
        assertEquals("共 2 条，按类别列出。点一条可以改。", ledgerHeadFooter(null, LedgerVisualTab.RECORDS, entries, null))
        assertEquals("林月的 1 条（共 2 条），按类别列出。点一条可以改。", ledgerHeadFooter(null, LedgerVisualTab.RECORDS, entries, "林月"))
        assertEquals(null, ledgerHeadFooter(null, LedgerVisualTab.PEOPLE, entries, null))
        assertEquals(null, ledgerHeadFooter(null, LedgerVisualTab.PROMISES, entries, "林月"))
    }
}
