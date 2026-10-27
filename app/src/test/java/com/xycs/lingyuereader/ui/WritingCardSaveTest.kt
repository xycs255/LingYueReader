package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.plannedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritingCardSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val outline = """
        # 第一卷细纲
        ## 细纲
        - **第一章 雪夜来客**：林月提灯入城；边界：老周还活着；钩子：城门响起钟声。
    """.trimIndent()

    @Test
    fun firstEditPersistsUntouchedOutlineFieldsAndKeepsConcurrentSummary() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.renameChapter(work.id, chapter, "第一章 雪夜来客")
        store.addPackDoc(work.id, PackKind.VOLUME, "第一卷细纲", outline)

        val storedAtOpen = store.card(work.id, chapter)
        val displayed = store.plannedCard(work.id, chapter)
        assertEquals(ChapterCard(), storedAtOpen)
        assertNotNull(displayed.source)
        assertTrue(displayed.card.hook.isNotBlank())
        assertTrue(displayed.card.boundary.isNotBlank())

        store.updateCard(work.id, chapter) { it.copy(summary = "AI 刚补的摘要") }
        val edited = displayed.card.copy(facts = "林月带着玉佩入城")
        val saved = store.updateCard(work.id, chapter) { latest -> mergeCard(displayed.card, edited, latest, storedAtOpen) }
        assertEquals(edited.facts, saved.facts)
        assertEquals(displayed.card.boundary, saved.boundary)
        assertEquals(displayed.card.hook, saved.hook)
        assertEquals(displayed.card.auto, saved.auto)
        assertEquals("AI 刚补的摘要", saved.summary)

        val reopened = store.plannedCard(work.id, chapter)
        assertEquals(saved, reopened.card)
        assertNull(reopened.source) // 作者改了事实，后续细纲更新不应覆盖它。
    }

    @Test
    fun summaryOnlyFirstEditStillFollowsUpdatedOutline() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.renameChapter(work.id, chapter, "第一章 雪夜来客")
        val doc = store.addPackDoc(work.id, PackKind.VOLUME, "第一卷细纲", outline)
        val storedAtOpen = store.card(work.id, chapter)
        val displayed = store.plannedCard(work.id, chapter).card

        val saved = store.updateCard(work.id, chapter) { latest ->
            mergeCard(displayed, displayed.copy(summary = "这一章写完了"), latest, storedAtOpen)
        }
        assertEquals(displayed.hook, saved.hook)
        assertEquals(displayed.auto, saved.auto)
        store.savePackText(work.id, doc.id, outline.replace("城门响起钟声。", "渡口起火。"))
        val reopened = store.plannedCard(work.id, chapter)
        assertNotNull(reopened.source)
        assertNotEquals(saved.hook, reopened.card.hook)
        assertEquals("渡口起火。", reopened.card.hook)
        assertEquals("这一章写完了", reopened.card.summary)
    }

    @Test
    fun editsTypedWhileSaveIsPendingRebaseOntoTheSavedCard() {
        val opening = ChapterCard(facts = "林月入城", hook = "城门响钟")
        val sent = opening.copy(facts = "林月带着玉佩入城")
        val typedWhilePending = sent.copy(hook = "渡口起火")
        val diskAfterAiSummary = opening.copy(summary = "AI 补写的摘要")

        val saved = mergeCard(opening, sent, diskAfterAiSummary)
        val stillEditing = mergeCard(sent, typedWhilePending, saved)
        assertEquals("林月带着玉佩入城", stillEditing.facts)
        assertEquals("渡口起火", stillEditing.hook)
        assertEquals("AI 补写的摘要", stillEditing.summary)
        assertEquals("渡口起火", mergeCard(saved, stillEditing, saved).hook)
    }

    @Test
    fun leavingDuringSaveReappliesRevertedFieldsEvenIfFirstWriteFinishesOrFails() {
        val storedAtOpen = ChapterCard(summary = "旧摘要")
        val displayed = storedAtOpen.copy(facts = "细纲事实", hook = "细纲钩子", auto = 17)
        val sent = displayed.copy(facts = "临时改动")
        val atLeave = displayed.copy(pov = "作者指定视角") // facts was reverted after the first save started.

        fun finishAfterLeaving(firstResult: ChapterCard): ChapterCard {
            val flight = CardSaveFlight().apply { submitted = sent }
            assertTrue(flight.leave(atLeave))
            val (base, finalEdit) = flight.finish()!!
            assertNull(flight.finish())
            return mergeCard(base, finalEdit, firstResult, storedAtOpen)
        }

        val firstSaved = mergeCard(displayed, sent, storedAtOpen, storedAtOpen)
            .copy(summary = "AI 刚写的摘要")
        val afterSuccess = finishAfterLeaving(firstSaved)
        assertEquals("细纲事实", afterSuccess.facts)
        assertEquals("细纲钩子", afterSuccess.hook)
        assertEquals("作者指定视角", afterSuccess.pov)
        assertEquals("AI 刚写的摘要", afterSuccess.summary)

        val afterFailure = finishAfterLeaving(storedAtOpen)
        assertEquals("细纲事实", afterFailure.facts)
        assertEquals("细纲钩子", afterFailure.hook)
        assertEquals("作者指定视角", afterFailure.pov)
        assertEquals(displayed.auto, afterFailure.auto)
    }
}
