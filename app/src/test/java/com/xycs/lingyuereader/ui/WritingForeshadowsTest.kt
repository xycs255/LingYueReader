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
}
