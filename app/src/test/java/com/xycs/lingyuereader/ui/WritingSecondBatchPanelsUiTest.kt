package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.HoldIssue
import com.xycs.lingyuereader.data.Holdings
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.ToneIssue
import com.xycs.lingyuereader.data.ToneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 问题面板里的两组新东西：“建议”下的“AI 查 AI 腔”（点了才查，结果能接到“这段不行”）、“事实”里数值与持有的对账（两处原句都给）；
 * 以及挑走向的列表。深色下照样画。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingSecondBatchPanelsUiTest {
    @get:Rule val compose = createComposeRule()

    private val text = "林月心头一暖，她很感动。\n她把青铜灯交给沈默。\n这一夜，她终于明白了什么叫守护。"

    private fun show(filter: ProblemFilter, problems: List<Problem>, actions: ProblemActions, onTone: (() -> Unit)?, tone: ToneState = ToneState(), dark: Boolean = false) {
        compose.setContent {
            LingYueTheme(Appearance(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { ProvideGroupedColors {
                ProblemsPanel(
                    problems, filter, {}, text, null, hasRules = false, realtime = RealtimeState(), realtimeOn = false,
                    review = null, actions = actions, onClose = {}, onCliche = null, onOpenRules = {}, onOpenReview = {},
                    onTone = onTone, tone = tone,
                )
            } }
        }
        compose.waitForIdle()
    }

    private fun actions(rewrite: (ToneIssue) -> Unit = {}, drop: (ToneIssue) -> Unit = {}, ledger: () -> Unit = {}) =
        ProblemActions({ _, _ -> }, {}, {}, {}, {}, {}, { _, _, _ -> }, null, rewriteTone = rewrite, dropTone = drop, openLedger = ledger)

    @Test
    fun theToneCheckOnlyRunsWhenTappedAndFindingsGoToRewrite() {
        var asked = 0
        show(ProblemFilter.SUGGEST, emptyList(), actions(), onTone = { asked++ })
        // 还没有任何建议，“建议”这一类和按钮也在；没点就不查。
        compose.onNodeWithText("AI 查 AI 腔").assertExists()
        compose.onNodeWithText("点了才查", substring = true).assertExists()
        assertEquals(0, asked)
        compose.onNodeWithText("AI 查 AI 腔").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun findingsCanBeRewrittenOrIgnored() {
        val label = ToneIssue("林月心头一暖，她很感动。", ToneKind.LABEL, "情绪直接说出来了", "写她手上的动作")
        val uplift = ToneIssue("她终于明白了什么叫守护", ToneKind.UPLIFT, "段尾加了一句感慨")
        val problems = collectProblems(text, null, emptyList(), null, tones = listOf(label, uplift))
        val rewritten = mutableListOf<ToneIssue>()
        val dropped = mutableListOf<ToneIssue>()
        show(ProblemFilter.SUGGEST, problems, actions(rewrite = { rewritten += it }, drop = { dropped += it }), onTone = {},
            tone = ToneState(note = "AI 查出 2 处，列在“建议”里；只是建议，看着改。"))
        compose.onNodeWithText("情绪贴标签").assertExists()
        compose.onNodeWithText("段尾升华").assertExists()
        compose.onNodeWithText("情绪直接说出来了").assertExists()
        compose.onNodeWithText("改：写她手上的动作", substring = true).assertExists()
        compose.onNodeWithText("AI 查出 2 处", substring = true).assertExists()
        compose.onAllNodesWithText("这段不行")[0].performClick()
        assertEquals(listOf(label), rewritten)
        compose.onAllNodesWithText("忽略")[1].performClick()
        assertEquals(listOf(uplift), dropped)
    }

    @Test
    fun aRunningOrFailedCheckSaysSoInTheDark() {
        show(ProblemFilter.SUGGEST, emptyList(), actions(), onTone = {}, tone = ToneState(error = "请求失败"), dark = true)
        compose.onNodeWithText("AI 腔没查成：请求失败").assertExists()
    }

    @Test
    fun aHoldingMismatchListsBothQuotesUnderFacts() {
        val lin = LedgerEntry(id = "lin", kind = LedgerKind.HOLD, text = "持有", subject = "林月", aspect = "青铜灯", from = 3, item = true,
            quote = "林月把青铜灯揣进怀里。", chapterId = "c3")
        val shen = LedgerEntry(id = "shen", kind = LedgerKind.HOLD, text = "持有", subject = "沈默", aspect = "青铜灯", from = 4, item = true,
            quote = "她把青铜灯交给沈默。", chapterId = "c4")
        val issue: HoldIssue = Holdings.check(listOf(lin, shen)).single()
        var ledger = 0
        show(ProblemFilter.FACT, collectProblems(text, null, emptyList(), null, holds = listOf(issue)), actions(ledger = { ledger++ }), onTone = null, dark = true)
        assertTrue(compose.onAllNodesWithText("事实 1").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("同时在林月和沈默手里", substring = true).assertExists()
        compose.onNodeWithText("「林月把青铜灯揣进怀里。」", substring = true).assertExists()
        compose.onNodeWithText("「她把青铜灯交给沈默。」", substring = true).assertExists()
        compose.onNodeWithText("跳到原文").assertExists()
        compose.onNodeWithText("去账本").performClick()
        assertEquals(1, ledger)
    }

    @Test
    fun directionsArePickedFromAList() {
        val options = listOf(
            WritePrompts.PlanOption("林月在渡口等沈默", "林月知道沈默失约", "杜娘递来一封信", "谁替沈默送的信"),
            WritePrompts.PlanOption("林月独自过河去找老周", hook = "老周到底站哪边"),
        )
        val picked = mutableListOf<WritePrompts.PlanOption>()
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) { ProvideGroupedColors { DirectionOptions(options) { picked += it } } }
        }
        compose.onNodeWithText("1. 林月在渡口等沈默").assertExists()
        compose.onNodeWithText("看点：谁替沈默送的信", substring = true).assertExists()
        compose.onNodeWithText("章末停在：杜娘递来一封信", substring = true).assertExists()
        compose.onNodeWithText("2. 林月独自过河去找老周").performClick()
        assertEquals(listOf(options[1]), picked)
    }
}
