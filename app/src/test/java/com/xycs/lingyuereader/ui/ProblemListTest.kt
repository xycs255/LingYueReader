package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问题面板的那张单子：四个来源怎么合、怎么排、每类几条（状态行的“N 处问题”就是合计）。 */
class ProblemListTest {
    private val text = "林月嘴角勾起，推开客栈的门。\n老周点了点头，又点了点头。\n本章写到这里"

    private fun report() = CheckRules.check(
        text,
        CheckRules.parse("至多 1：点了点头\n字数：至少 100\n", "检查规则") + AiCliche.rules(WorkAssist(banned = listOf("推开客栈"))),
    )

    private val review = ChapterReview(
        1, 2,
        listOf(
            GateResult(ReviewGate.FACT, listOf(
                ReviewIssue("推开客栈的门", "林月这时还没到客栈"),
                ReviewIssue("早就改掉的一句", "原句已经不在了"),
                ReviewIssue("嘴角勾起", "已经改过", state = ReviewIssue.FIXED),
            )),
            GateResult(ReviewGate.CRAFT, listOf(ReviewIssue("", "整章对白太少", state = ReviewIssue.IGNORED), ReviewIssue("", "旁白太多"))),
            GateResult(ReviewGate.RULES, listOf(ReviewIssue("点了点头", "「点了点头」2 次，至多 1"))),
        ),
    )

    private val typo = WriteIssue("本章写到这里", "写到", "写到了", "漏字")

    @Test
    fun fourSourcesAddUpAndRuleGateIsNotCountedTwice() {
        val list = collectProblems(text, report(), listOf(typo), review)
        val counts = problemCounts(list)
        // 规则：点了点头、字数，各一条（一条规则有几处也是一条）。
        assertEquals(2, counts[ProblemFilter.RULE])
        // AI 腔按处数：嘴角勾起（内置清单）、推开客栈（黑名单）。
        assertEquals(2, counts[ProblemFilter.CLICHE])
        assertEquals(1, counts[ProblemFilter.SUGGEST])
        // 审稿：只算事实关、写法关里没处理的；规则关和“规则”重复，不算。
        assertEquals(3, counts[ProblemFilter.REVIEW])
        assertEquals(8, counts[ProblemFilter.ALL])
        assertEquals(list.size, counts[ProblemFilter.ALL])
        assertTrue(list.none { it is Problem.Review && it.gate == ReviewGate.RULES })
        assertTrue(list.filterIsInstance<Problem.Review>().all { it.issue.open })
    }

    @Test
    fun sortedByPlaceWithWholeChapterFirstAndMissingLast() {
        val list = collectProblems(text, report(), listOf(typo), review)
        // 整章的（字数不够）排最前；同一处的先规则、AI 腔，再建议、审稿；找不到原句、没有原句的排最后。
        assertTrue((list.first() as Problem.Rule).hit.message.contains("少于 100"))
        val placed = list.drop(1).takeWhile { it.order != Int.MAX_VALUE }
        assertEquals(placed.map { it.order }.sorted(), placed.map { it.order })
        assertEquals(listOf(ProblemFilter.CLICHE, ProblemFilter.CLICHE, ProblemFilter.REVIEW, ProblemFilter.RULE, ProblemFilter.SUGGEST), placed.map { it.filter })
        val tail = list.drop(1 + placed.size)
        assertEquals(listOf("原句已经不在了", "旁白太多"), tail.map { (it as Problem.Review).issue.problem })
        // 认条目不靠位置：同一条规则的几处 AI 腔各有各的键。
        assertEquals(list.size, list.map { it.key }.toSet().size)
    }

    @Test
    fun nothingWhenNothingWasFound() {
        assertTrue(collectProblems("", null, emptyList(), null).isEmpty())
        assertEquals(0, problemCounts(emptyList())[ProblemFilter.ALL])
        // 去 AI 腔的改法算“建议”（和写作建议一样改或忽略）。
        val cliche = WriteIssue("林月嘴角勾起，推开客栈的门。", "嘴角勾起，", "笑了一下，", "换成动作", SuggestKind.CLICHE.name)
        assertEquals(ProblemFilter.SUGGEST, collectProblems(text, null, listOf(cliche), null).single().filter)
    }

    @Test
    fun aReviewFixAsksForThisSentenceOnly() {
        assertEquals("审稿意见：林月这时还没到客栈。改法：改成渡口。只改这一句和必要的上下文。",
            reviewFixInstruction(ReviewIssue("推开客栈的门", "林月这时还没到客栈", "改成渡口")))
        assertEquals("审稿意见：旁白太多。只改这一句和必要的上下文。", reviewFixInstruction(ReviewIssue("", "旁白太多")))
    }
}
