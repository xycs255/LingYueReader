package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.Monotony
import com.xycs.lingyuereader.data.MonotonyKind
import com.xycs.lingyuereader.data.RepeatPhrase
import com.xycs.lingyuereader.data.RepeatReport
import com.xycs.lingyuereader.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 问题面板里的“本书高频”：单开一组，列出说法、次数和分布在哪几章，能跳到位置、加进黑名单、不再提；深色下也照样画。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class RepeatProblemsUiTest {
    @get:Rule val compose = createComposeRule()

    private val text = "林月嘴角勾起一抹弧度。\n他推开门。\n他坐下。\n他喝茶。\n他起身。"
    private val phrase = RepeatPhrase("嘴角勾起一抹弧度", 9, listOf("第3章" to 2, "第5章" to 3, "第7章" to 4), listOf(2..9))
    private val mono = Monotony(MonotonyKind.SAME_OPENING, "连着 4 段都用“他”开头；换个开头，或者把其中两段并起来", listOf(12..12, 18..18), "他")

    private fun show(dark: Boolean, actions: ProblemActions) {
        val problems = collectProblems(text, null, emptyList(), null, RepeatReport(listOf(phrase), listOf(mono)))
        compose.setContent {
            LingYueTheme(Appearance(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { ProvideGroupedColors {
                ProblemsPanel(
                    problems, ProblemFilter.REPEAT, {}, text, null, hasRules = false, realtime = RealtimeState(), realtimeOn = false,
                    review = null, actions = actions, onClose = {}, onCliche = null, onOpenRules = {}, onOpenReview = {},
                )
            } }
        }
        compose.waitForIdle()
    }

    @Test
    fun aRepeatedPhraseCanBeBannedOrDismissedAndMonotonyJumped() {
        val banned = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val jumped = mutableListOf<IntRange>()
        show(dark = false, ProblemActions({ _, _ -> }, { banned += it }, {}, {}, {}, {}, { _, _, _ -> }, null,
            jumpRange = { jumped += it }, dismissRepeat = { dismissed += it }))
        // 分段控件里有“本书高频 2”。
        assertTrue(compose.onAllNodesWithText("本书高频 2").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("「嘴角勾起一抹弧度」近 10 章 9 次").assertExists()
        compose.onNodeWithText("分布在：第3章 2 次、第5章 3 次、第7章 4 次").assertExists()
        compose.onNodeWithText("连着 4 段都用“他”开头", substring = true).assertExists()
        compose.onNodeWithText("加进黑名单").performClick()
        assertEquals(listOf("嘴角勾起一抹弧度"), banned)
        compose.onAllNodesWithText("不再提")[0].performClick()
        compose.onAllNodesWithText("不再提")[1].performClick()
        assertTrue(dismissed.contains(phrase.key))
        assertTrue(dismissed.contains(mono.key))
        compose.onAllNodesWithText("跳到位置")[0].performClick()
        assertTrue(jumped.isNotEmpty())
    }

    @Test
    fun itDrawsInTheDarkToo() {
        show(dark = true, ProblemActions({ _, _ -> }, {}, {}, {}, {}, {}, { _, _, _ -> }, null))
        compose.onNodeWithText("「嘴角勾起一抹弧度」近 10 章 9 次").assertExists()
        assertEquals(2, compose.onAllNodesWithText("不再提").fetchSemanticsNodes().size)
    }
}
