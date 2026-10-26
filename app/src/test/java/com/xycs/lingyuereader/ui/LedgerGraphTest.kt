package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.LedgerChapter
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.StoryLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerGraphTest {
    private val early = LedgerEntry(id = "early", kind = LedgerKind.STATE, text = "在渡口", subject = "林月", aspect = "位置", from = 12)
    private val later = LedgerEntry(id = "later", kind = LedgerKind.STATE, text = "在石桥", subject = "林月", aspect = "位置", from = 15)

    @Test
    fun personCardUsesChapterSnapshotWithoutErasingEarlierState() {
        val entries = listOf(early, later)
        assertEquals("在渡口", ledgerGraphStates(entries, LedgerChapter("c12", "第12章", 12, 1)).single().text)
        assertEquals("在石桥", ledgerGraphStates(entries, LedgerChapter("c15", "第15章", 15, 1)).single().text)
        assertEquals("在石桥", ledgerGraphStates(entries, null).single().text)
        assertEquals(2, entries.size)
    }

    @Test
    fun graphUndoOnlyRemovesItsOwnNewEntryAndPreservesOtherChanges() {
        val (withLater, added) = StoryLedger.apply(listOf(early), listOf(LedgerOp.Add(later)), 100L)
        assertEquals(1, added)
        val undo = ledgerGraphUndoOp(withLater, null, later)
        assertTrue(undo is LedgerOp.Remove)
        val (restored, _) = StoryLedger.apply(withLater, listOf(undo!!), 101L)
        assertEquals(listOf(early), restored)

        // A sync or another author action that edits the new entry must block this stale undo.
        assertNull(ledgerGraphUndoOp(withLater.map { if (it.id == later.id) it.copy(text = "在长安") else it }, null, later))
    }

    @Test
    fun promiseGraphHonorsPersonAndChapterFilters() {
        val generic = LedgerEntry(id = "generic", kind = LedgerKind.PROMISE, text = "三日后再来", from = 8, payoff = 11)
        val lin = LedgerEntry(id = "lin", kind = LedgerKind.PROMISE, text = "林月要交信", subject = "林月", from = 10)
        val shen = LedgerEntry(id = "shen", kind = LedgerKind.PROMISE, text = "沈默要返乡", subject = "沈默", from = 15)
        val all = listOf(generic, lin, shen)
        assertEquals(listOf(lin), ledgerGraphPromises(all, LedgerChapter("c12", "第12章", 12, 1), "林月"))
        assertEquals(emptyList<LedgerEntry>(), ledgerGraphPromises(all, LedgerChapter("c12", "第12章", 12, 1), "沈默"))
        assertTrue(ledgerGraphPromises(all, null, null).containsAll(all))
    }
}
