package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.RuleReport
import com.xycs.lingyuereader.data.ThemeMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 问题面板的键必须唯一：LazyColumn 撞键直接闪退，存了盘的重复审稿会让面板每次打开都闪退。
 * 四个来头都要挡住：模型重复输出的审稿、旧盘上已经存下的重复审稿、同一行规则里写重复的词、两份同名的规则文档。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProblemKeysTest {
    @get:Rule val compose = createComposeRule()

    private val text = "林月推开客栈的门，大概是累了。\n老周大概也累了。"

    /** 模型把同一条问题说了两遍。 */
    private val twiceAnswer = """{"issues":[
        {"quote":"推开客栈的门","problem":"林月这时还没到客栈","fix":"改成渡口"},
        {"quote":"推开客栈的门","problem":"林月这时还没到客栈","fix":"改成渡口"},
        {"quote":"老周大概也累了","problem":"老周这时不在场"}]}"""

    private fun review(issues: List<ReviewIssue>) = ChapterReview(1, 2, listOf(GateResult(ReviewGate.FACT, issues)))

    private fun keysUnique(list: List<Problem>) = assertEquals(list.map { it.key }.toSet().size, list.size)

    @Test
    fun parseReviewDropsRepeats() {
        val issues = WritePrompts.parseReview(twiceAnswer)!!
        assertEquals(listOf("林月这时还没到客栈", "老周这时不在场"), issues.map { it.problem })
    }

    @Test
    fun repeatsAlreadyOnDiskAreDroppedWhenRead() {
        val dup = ReviewIssue("推开客栈的门", "林月这时还没到客栈")
        val saved = review(listOf(dup, dup, dup.copy(state = ReviewIssue.FIXED))).toJson()
        val back = ChapterReview.fromJson(JSONObject(saved.toString()))
        assertEquals(1, back.gate(ReviewGate.FACT)!!.issues.size)
        // 就算有别的路子把重复的塞进来，合单子时也按键去重。
        keysUnique(collectProblems(text, null, emptyList(), review(listOf(dup, dup))))
    }

    @Test
    fun repeatedTermsOnOneLineAndSameNamedDocsGetUniqueKeys() {
        val sameLine = CheckRules.parse("禁用：大概、大概\n", "检查规则")
        assertEquals(1, (sameLine.rules.single() as com.xycs.lingyuereader.data.CheckRule.Count).terms.size)
        // 两份都叫“检查规则”的文档，同一行写着不同的词。
        val twoDocs = CheckRules.parseDocs(listOf("检查规则" to "禁用：大概\n", "检查规则" to "禁用：累了\n"))
        val report = CheckRules.check(text, twoDocs)
        assertEquals(2, report.hits.size)
        val list = collectProblems(text, report, emptyList(), null)
        assertEquals(2, list.size)
        keysUnique(list)
        // 黑名单里同一个说法存了两遍也一样。
        val banned = CheckRules.check(text, AiCliche.banned(listOf("推开客栈", "推开客栈")))
        keysUnique(collectProblems(text, banned, emptyList(), null))
        // 写作建议重复了也只列一条。
        val typo = WriteIssue("老周大概也累了。", "大概", "大约", "用词")
        keysUnique(collectProblems(text, null, listOf(typo, typo), null))
    }

    /** 实际渲染面板：基线上这里抛 “Key ... was already used”。 */
    @Test
    fun panelWithRepeatsRendersInsteadOfCrashing() {
        val dup = ReviewIssue("推开客栈的门", "林月这时还没到客栈")
        val rules = CheckRules.parse("禁用：大概、大概\n", "检查规则")
        val report: RuleReport = CheckRules.check(text, rules)
        val problems = collectProblems(text, report, emptyList(), review(listOf(dup, dup)))
        val noop = ProblemActions({ _, _ -> }, {}, {}, {}, {}, {}, { _, _, _ -> }, null)
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) { ProvideGroupedColors {
                ProblemsPanel(
                    problems, ProblemFilter.ALL, {}, text, report, hasRules = true, realtime = RealtimeState(), realtimeOn = false,
                    review = review(listOf(dup, dup)), actions = noop, onClose = {}, onCliche = null, onOpenRules = {}, onOpenReview = {},
                )
            } }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("林月这时还没到客栈").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("「大概」", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
