package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.LedgerChapter
import com.xycs.lingyuereader.data.LedgerConflict
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.StoryLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        // 撤销拿刚写进去的那一条（带着写入时间）。
        val saved = withLater.single { it.id == later.id }
        val undo = ledgerGraphUndoOp(withLater, null, saved)
        assertTrue(undo is LedgerOp.Remove)
        val (restored, _) = StoryLedger.apply(withLater, listOf(undo!!), 101L)
        assertEquals(listOf(early), restored)

        // A sync or another author action that edits the new entry must block this stale undo.
        assertNull(ledgerGraphUndoOp(withLater.map { if (it.id == later.id) it.copy(text = "在长安") else it }, null, saved))
    }

    @Test
    fun staleUndoIsRejectedByTheStoreEvenWhenTheScreenListLooksUnchanged() {
        val (edited, _) = StoryLedger.apply(listOf(early), listOf(LedgerOp.Update(early.copy(text = "在石桥"), expected = early)), 100L)
        val saved = edited.single()
        // 界面上的列表还是刚改完的样子，撤销判断“没有其他改动”。
        val undo = ledgerGraphUndoOp(edited, early, saved)!!
        // 在这之间整理把它改成了“在京城”：存储层比对失败，整批不写。
        val (synced, _) = StoryLedger.apply(edited, listOf(LedgerOp.Update(saved.copy(text = "在京城"))), 200L)
        assertThrows(LedgerConflict::class.java) { StoryLedger.apply(synced, listOf(undo), 300L) }
        assertEquals(listOf(early.copy(updatedAt = 301L)), StoryLedger.apply(edited, listOf(undo), 301L).first)
    }

    @Test
    fun payoffIsSavedOnlyAfterARealChange() {
        val planned = LedgerEntry(id = "p", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 8, payoff = 11)
        val open = planned.copy(payoff = null)
        // 轻点没拖：不写。
        assertNull(ledgerPayoffToSave(planned, null, 8, 14))
        // 拖了又回到原来的章：不写。
        assertNull(ledgerPayoffToSave(planned, 11.2f, 8, 14))
        // 真拖到别的章（读屏调节同样用调节后的值）：写。
        assertEquals(13, ledgerPayoffToSave(planned, 12.6f, 8, 14))
        assertEquals(14, ledgerPayoffToSave(planned, 40f, 8, 14))
        // 没定计划收章的伏笔：滑块松手不写，要作者点保存。
        assertNull(ledgerPayoffToSave(open, null, 8, 14))
        assertNull(ledgerPayoffToSave(open, 12f, 8, 14))
    }

    @Test
    fun authorOnlyTruthIsNotKnownByAnyoneAndQuickRegistrationIsNotSecret() {
        val truth = LedgerEntry(id = "t", kind = LedgerKind.KNOW, text = "沈默是朝廷的卧底", subject = "林月", secret = true)
        val known = LedgerEntry(id = "k", kind = LedgerKind.KNOW, text = "沈默是朝廷的卧底", subject = "杜娘", from = 3)
        val fact = ledgerGraphFacts(listOf(truth, known)).single()
        assertEquals(listOf(truth), fact.truth)
        val ch12 = LedgerChapter("c12", "第12章", 12, 1)
        // 林月那一格：那条“真相”意思是她还不知道，不算已知，也不算已有记录。
        assertEquals(LedgerGraphCell(null, null), ledgerGraphCell(fact, "林月", ch12))
        assertEquals(LedgerGraphCell(null, null), ledgerGraphCell(fact, "林月", null))
        assertEquals(LedgerGraphCell(known, known), ledgerGraphCell(fact, "杜娘", ch12))
        // 在林月那一格登记得知：新的一条不是只有作者知道的。
        val registered = ledgerQuickKnowledgeEntry(LedgerQuickKnowledge("林月", truth), 30, " 偷听 ", "")
        assertFalse(registered.secret)
        assertEquals(Triple("林月", 30, "偷听"), Triple(registered.subject, registered.from, registered.via))
        assertEquals("沈默是朝廷的卧底", registered.text)
        val after = ledgerGraphFacts(listOf(truth, known, registered)).single()
        assertEquals(registered, ledgerGraphCell(after, "林月", LedgerChapter("c30", "第30章", 30, 1)).known)
        assertNull(ledgerGraphCell(after, "林月", ch12).known)
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
