package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.TextRange
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.data.ChapterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The compact entry changes its recommendation, but never takes a candidate as an action. */
class WritingEditorToolsUiTest {
    private fun recommendation(
        text: CharSequence = "主角推开门。",
        selection: TextRange = TextRange(text.length),
        status: ChapterStatus = ChapterStatus.DRAFT,
        ready: Boolean = true,
        inline: InlineWrite? = null,
        aiState: WriteAiState? = null,
    ) = editorRecommendation(text, selection, status, ready, inline, aiState)

    private fun recommend(
        text: CharSequence = "主角推开门。",
        selection: TextRange = TextRange(text.length),
        status: ChapterStatus = ChapterStatus.DRAFT,
        ready: Boolean = true,
    ) = recommendation(text, selection, status, ready).kind

    @Test fun aPendingCandidateTakesPriorityOverEveryOtherContext() {
        val inline = InlineWrite("work", "chapter", 0, "", InlineMode.SCENE, loading = false, text = "候选正文")
        val result = recommendation(status = ChapterStatus.FINAL, ready = false, inline = inline)
        assertEquals(EditorRecommendationKind.PENDING_AI, result.kind)
        assertEquals("核对续写结果", result.label)
        assertFalse(result.enabled)
    }

    @Test fun aiStatusesNameTheirActualTaskAndRemainPassive() {
        val tasks = listOf(
            WriteAiTask.Edit(WriteAction.POLISH, "原文", 0, 2) to "核对润色候选",
            WriteAiTask.Idea(IdeaKind.NEXT) to "查看灵感结果",
            WriteAiTask.Review to "查看读者点评",
            WriteAiTask.Consistency to "查看矛盾检查",
        )
        tasks.forEach { (task, label) ->
            val result = recommendation(aiState = WriteAiState("work", "chapter", task, text = "结果", loading = false))
            assertEquals(label, result.label)
            assertEquals(EditorRecommendationKind.PENDING_AI, result.kind)
            assertFalse(result.enabled)
        }
        val loading = recommendation(aiState = WriteAiState("work", "chapter", WriteAiTask.Review))
        assertEquals("正在生成点评", loading.label)
        assertFalse(loading.enabled)
        val failed = recommendation(aiState = WriteAiState("work", "chapter", WriteAiTask.Consistency, loading = false, error = "网络失败"))
        assertEquals("前后矛盾检查失败", failed.label)
        assertFalse(failed.enabled)
    }

    @Test fun aFinalChapterRequiresUnlockBeforeAnyAiAction() {
        assertEquals(EditorRecommendationKind.UNLOCK, recommend(status = ChapterStatus.FINAL))
        assertEquals(EditorRecommendationKind.UNLOCK, recommend(status = ChapterStatus.FINAL, ready = false))
    }

    @Test fun missingAiConfigurationLeadsToSettings() {
        assertEquals(EditorRecommendationKind.CONFIGURE_AI, recommend(ready = false))
    }

    @Test fun selectedTextGetsRevisionAndEmptyChapterGetsDraft() {
        assertEquals(EditorRecommendationKind.REVISE_SELECTION, recommend(selection = TextRange(0, 2)))
        assertEquals(EditorRecommendationKind.DRAFT_CHAPTER, recommend(text = "", selection = TextRange(0)))
    }

    @Test fun revisionChapterGetsReviewAndOtherTextGetsContinuation() {
        assertEquals(EditorRecommendationKind.REVIEW_CHAPTER, recommend(status = ChapterStatus.REVISION))
        assertEquals(EditorRecommendationKind.CONTINUE, recommend())
    }

    @Test fun selectionIsClampedAfterTextEdits() {
        assertEquals(EditorRecommendationKind.REVISE_SELECTION, recommend(text = "主角", selection = TextRange(0, 200)))
        assertEquals(EditorRecommendationKind.CONTINUE, recommend(text = "主角", selection = TextRange(200)))
    }

    @Test fun recommendationReadsLongTextWithoutCopyingTheWholeChapter() {
        val text = object : CharSequence {
            private val value = "主角推开门。"
            override val length: Int get() = value.length
            override fun get(index: Int): Char = value[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)
            override fun toString(): String = error("The whole chapter must not be copied")
        }
        assertEquals(EditorRecommendationKind.REVISE_SELECTION,
            recommendation(text, selection = TextRange(0, 2)).kind)
    }
}
