package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.TextRange
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.data.Beat
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
        val failed = recommendation(aiState = WriteAiState("work", "chapter", WriteAiTask.Review, loading = false, error = "网络失败"))
        assertEquals("读者点评失败", failed.label)
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

    /** 细纲卡还有没写的拍：推荐“写第 N 拍”（说明是这一拍写什么）；空章、选了字、修订章照旧，前面那几样优先。 */
    @Test fun anUnwrittenBeatIsRecommendedOverPlainContinuation() {
        val beat = Beat(2, "杜娘开门")
        val result = editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, true, nextBeat = beat)
        assertEquals(EditorRecommendationKind.WRITE_BEAT, result.kind)
        assertEquals("写第 2 拍", result.label)
        assertEquals("杜娘开门", result.reason)
        assertEquals(EditorRecommendationKind.DRAFT_CHAPTER, editorRecommendation("", TextRange(0), ChapterStatus.DRAFT, true, nextBeat = beat).kind)
        assertEquals("AI 写整章草稿", editorRecommendation("", TextRange(0), ChapterStatus.DRAFT, true, nextBeat = beat).label)
        assertEquals(EditorRecommendationKind.REVISE_SELECTION, editorRecommendation("主角推开门。", TextRange(0, 2), ChapterStatus.DRAFT, true, nextBeat = beat).kind)
        assertEquals(EditorRecommendationKind.REVIEW_CHAPTER, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.REVISION, true, nextBeat = beat).kind)
        assertEquals(EditorRecommendationKind.CONTINUE, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, true).kind)
    }

    /**
     * 这章写完了：节拍都写过了，或字数到了目标篇幅的下限，才推荐；排在“写第 N 拍”之后，
     * 空章、选了字、修订章、定稿章照旧是原来那几样。
     */
    @Test fun aFinishedChapterIsRecommendedAfterTheBeats() {
        assertEquals("细纲卡的 3 拍都写完了", finishHint(beatCount = 3, beatsLeft = false, words = 10, minWords = null))
        assertEquals(null, finishHint(beatCount = 3, beatsLeft = true, words = 10, minWords = null))
        assertEquals(null, finishHint(beatCount = 0, beatsLeft = false, words = 10, minWords = null))
        assertEquals("已写 3000 字，到了目标篇幅", finishHint(beatCount = 0, beatsLeft = false, words = 3000, minWords = 3000))
        assertEquals(null, finishHint(beatCount = 0, beatsLeft = false, words = 2999, minWords = 3000))
        // 还有没写的拍时字数到了也算（看着写完了），但推荐先写拍。
        assertEquals("已写 3200 字，到了目标篇幅", finishHint(beatCount = 3, beatsLeft = true, words = 3200, minWords = 3000))

        val done = "细纲卡的 3 拍都写完了"
        val result = editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, true, finished = done)
        assertEquals(EditorRecommendationKind.FINISH_CHAPTER, result.kind)
        assertEquals("这章写完了", result.label)
        assertEquals(done, result.reason)
        assertEquals(EditorRecommendationKind.WRITE_BEAT,
            editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, true, nextBeat = Beat(2, "杜娘开门"), finished = done).kind)
        assertEquals(EditorRecommendationKind.DRAFT_CHAPTER, editorRecommendation("", TextRange(0), ChapterStatus.DRAFT, true, finished = done).kind)
        assertEquals(EditorRecommendationKind.REVISE_SELECTION, editorRecommendation("主角推开门。", TextRange(0, 2), ChapterStatus.DRAFT, true, finished = done).kind)
        assertEquals(EditorRecommendationKind.REVIEW_CHAPTER, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.REVISION, true, finished = done).kind)
        assertEquals(EditorRecommendationKind.UNLOCK, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.FINAL, true, finished = done).kind)
        assertEquals(EditorRecommendationKind.CONFIGURE_AI, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, false, finished = done).kind)
    }

    /** 空章、细纲卡缺事实或节拍：照样推荐写整章草稿，说一句 AI 会先补上；有字的章照旧接着写，不硬推拆节拍。 */
    @Test fun aChapterWithoutPlanOrBeatsIsStillRecommendedTheOneClickDraft() {
        val missing = editorRecommendation("", TextRange(0), ChapterStatus.DRAFT, true, cardMissing = true)
        assertEquals(EditorRecommendationKind.DRAFT_CHAPTER, missing.kind)
        assertEquals("AI 写整章草稿", missing.label)
        assertEquals("缺细纲、节拍也行：AI 先拟、先拆，再逐拍写", missing.reason)
        assertEquals("先看细纲，再生成候选稿", editorRecommendation("", TextRange(0), ChapterStatus.DRAFT, true).reason)
        assertEquals(EditorRecommendationKind.CONTINUE, editorRecommendation("主角推开门。", TextRange(6), ChapterStatus.DRAFT, true, cardMissing = true).kind)
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
