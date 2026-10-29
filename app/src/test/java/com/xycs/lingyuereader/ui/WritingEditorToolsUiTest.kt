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
import org.junit.Assert.assertTrue
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

    // ---------- ✦ 按位置推荐 ----------

    private fun spark(
        text: String,
        selection: TextRange,
        status: ChapterStatus = ChapterStatus.DRAFT,
        nextBeat: Beat? = null,
        finished: String? = null,
        cliche: Boolean = false,
    ) = sparkActions(text, selection, status, true, nextBeat = nextBeat, finished = finished, selectionCliche = cliche)

    private val two = "林月推开门。\n沈默坐在柜台后面。"

    @Test fun theCaretSpotIsReadFromTheTextAroundIt() {
        assertEquals(CaretSpot.EMPTY_CHAPTER, caretSpot("  \n", TextRange(1)))
        assertEquals(CaretSpot.SELECTION, caretSpot(two, TextRange(0, 2)))
        // 只选了空白不算选中。
        assertEquals(CaretSpot.BLANK_LINE, caretSpot("林月。\n \n沈默。", TextRange(4, 5)))
        assertEquals(CaretSpot.CHAPTER_END, caretSpot(two, TextRange(two.length)))
        assertEquals(CaretSpot.CHAPTER_END, caretSpot("$two\n\n", TextRange(two.length + 1)))
        assertEquals(CaretSpot.PARAGRAPH_END, caretSpot(two, TextRange(6)))
        assertEquals(CaretSpot.BLANK_LINE, caretSpot("林月推开门。\n\n沈默。", TextRange(7)))
        assertEquals(CaretSpot.MID_PARAGRAPH, caretSpot(two, TextRange(2)))
    }

    @Test fun aSelectionGetsPolishThenExpandOrCondenseAndDeclicheOnlyWhenItHits() {
        val short = spark(two, TextRange(0, 5))
        assertEquals(listOf("润色", "扩写"), short.map { it.label })
        assertEquals(EditorRecommendationKind.REVISE_SELECTION, short[0].kind)
        assertEquals("已选 5 字", short[0].reason)
        val long = "林".repeat(LONG_SELECTION + 10)
        assertEquals(listOf("润色", "缩写"), spark(long, TextRange(0, LONG_SELECTION)).map { it.label })
        assertEquals(listOf("润色", "扩写", "去 AI 腔"), spark(two, TextRange(0, 5), cliche = true).map { it.label })
        assertEquals(EditorRecommendationKind.DECLICHE_SELECTION, spark(two, TextRange(0, 5), cliche = true)[2].kind)
    }

    @Test fun theChapterEndGetsTheNextBeatOrFinishingThenContinuation() {
        val beat = Beat(2, "杜娘开门")
        assertEquals(listOf("写第 2 拍", "AI 接着写"), spark(two, TextRange(two.length), nextBeat = beat).map { it.label })
        assertEquals(listOf("AI 接着写"), spark(two, TextRange(two.length)).map { it.label })
        val done = spark(two, TextRange(two.length), finished = "已写 2800 字，接近目标 3000 字")
        assertEquals(listOf(EditorRecommendationKind.FINISH_CHAPTER, EditorRecommendationKind.CONTINUE), done.map { it.kind })
        assertEquals("这章写完了", done[0].label)
        assertEquals("已写 2800 字，接近目标 3000 字", done[0].reason)
        // 不在章末：看着写完了也不推荐收尾。
        assertFalse(spark(two, TextRange(6), finished = "细纲卡的 3 拍都写完了").any { it.kind == EditorRecommendationKind.FINISH_CHAPTER })
        assertEquals(3, spark(two, TextRange(two.length), status = ChapterStatus.REVISION, nextBeat = beat, finished = "x").size)
        assertEquals(EditorRecommendationKind.REVIEW_CHAPTER, spark(two, TextRange(two.length), status = ChapterStatus.REVISION)[0].kind)
    }

    @Test fun blankLinesParagraphEndsAndTheMiddleOfAParagraph() {
        val beat = Beat(3, "林月识破沈默")
        assertEquals(listOf("写第 3 拍", "AI 接着写"), spark(two, TextRange(6), nextBeat = beat).map { it.label })
        assertEquals(listOf("AI 接着写"), spark("林月推开门。\n\n沈默。", TextRange(7)).map { it.label })
        assertEquals(listOf("润色这段", "扩写这段"), spark(two, TextRange(2), nextBeat = beat).map { it.label })
    }

    @Test fun anEmptyChapterIsWrittenAndBusyOrLockedChaptersOnlyShowTheirState() {
        val empty = spark("", TextRange(0))
        assertEquals(listOf(EditorRecommendationKind.DRAFT_CHAPTER), empty.map { it.kind })
        assertEquals("写本章", empty[0].label)
        assertEquals(listOf(EditorRecommendationKind.UNLOCK), spark(two, TextRange(0, 2), status = ChapterStatus.FINAL).map { it.kind })
        assertEquals(listOf(EditorRecommendationKind.CONFIGURE_AI), sparkActions(two, TextRange(0, 2), ChapterStatus.DRAFT, false).map { it.kind })
        val inline = InlineWrite("work", "chapter", 0, "", InlineMode.SCENE, loading = true)
        val busy = sparkActions(two, TextRange(0, 2), ChapterStatus.DRAFT, true, inline = inline)
        assertEquals(1, busy.size)
        assertFalse(busy[0].enabled)
    }

    @Test fun nearlyThereCountsFromNinetyPercent() {
        assertEquals("已写 2700 字，接近目标 3000 字", nearFinishHint(2700, 3000))
        assertEquals(null, nearFinishHint(2699, 3000))
        assertEquals(null, nearFinishHint(3000, 3000))
        assertEquals(null, nearFinishHint(2900, null))
    }

    // ---------- 标签归属 ----------

    /** 改前的盘点：右上菜单、AI 条、工具四组、底栏的每一项（见 docs 里的盘点）。 */
    private val inventory = listOf(
        // 右上菜单
        "细纲卡", "章节状态", "解锁修改", "这章写完了", "AI 写整章草稿", "三道审稿", "整理本章进设定包", "本章资料", "故事账本",
        "从范本找写法", "问题", "自动排版", "历史版本", "预览本章", "听本章", "改章名", "上一章", "下一章", "写下一章",
        // AI 条、标点条、书灵
        "AI 写…", "润色", "更生动", "扩写", "缩写", "挑错", "按要求改…", "读者点评", "去 AI 腔", "快捷标点", "书灵",
        // 工具四组里另有的
        "设定包", "整理入账", "范本写法",
    )

    /** 盘点里的每一项都在新位置上找得到：底栏四个标签（最后一章、没定稿时最全）、右上“⋯”、顶栏箭头。 */
    @Test fun everyOldEntryHasANewHome() {
        val homes = EditorToolGroup.entries.flatMap { editorTabEntries(it, locked = false, lastChapter = true) } + EDITOR_OVERFLOW + EDITOR_TOP_BAR
        inventory.forEach { old ->
            assertTrue("“$old”没有去处", homes.any { old in it.legacy })
        }
        // 每一样都至少有一个位置，四个标签里不重复。
        EditorEntry.entries.forEach { assertTrue("${it.label} 没放进任何地方", it in homes) }
        val inTabs = EditorToolGroup.entries.flatMap { editorTabEntries(it, locked = false, lastChapter = true) }
        assertEquals(inTabs.size, inTabs.distinct().size)
        // 一排三到五个：360dp 的屏上排得下一行。
        EditorToolGroup.entries.forEach { g -> assertTrue(g.label, editorTabEntries(g, locked = false, lastChapter = true).size in 3..5) }
    }

    @Test fun lockedAndLastChapterChangeOnlyWhatTheyShould() {
        assertFalse(EditorEntry.FINISH in editorTabEntries(EditorToolGroup.ORGANIZE, locked = true, lastChapter = false))
        assertEquals("解锁修改", entryLabel(EditorEntry.STATUS, locked = true))
        assertEquals("章节状态", entryLabel(EditorEntry.STATUS, locked = false))
        assertFalse(EditorEntry.NEW_CHAPTER in editorTabEntries(EditorToolGroup.MORE, locked = false, lastChapter = false))
        assertTrue(EditorEntry.NEW_CHAPTER in editorTabEntries(EditorToolGroup.MORE, locked = false, lastChapter = true))
        // 读屏：只有一个字的“写”念“AI 写作”，底栏“更多”和右上“⋯”（更多）分开。
        assertEquals("AI 写作", EditorToolGroup.WRITE.description)
        assertEquals("更多工具", EditorToolGroup.MORE.description)
    }
}
