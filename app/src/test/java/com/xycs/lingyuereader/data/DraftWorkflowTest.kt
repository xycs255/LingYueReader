package com.xycs.lingyuereader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftWorkflowTest {
    private val task = ChapterWritingTask(
        title = "第二章 来客", chapter = 2, volume = 1, instruction = "保留人物的犹豫", targetLength = "依细纲",
        previous = "", previousLabel = "依细纲结果承接", card = ChapterCard(facts = "客人抵达，主人决定开门"),
        sourceNames = listOf("本卷细纲"), warnings = emptyList(), blocking = emptyList(), project = true, fingerprint = "materials-1",
    )
    private val clean = "门外响起敲门声。\n“等一下。”她说，伸手拨开门闩。"
    private val pass = """{"summary":[],"missing":[],"issues":[]}"""

    private fun issue(quote: String, required: Boolean = true) = """{"summary":["人物的选择需要核对"],"missing":[],"issues":[{"kind":"FACT","quote":"$quote","problem":"门尚未开启","basis":"本章细纲：先开门，再见客人","fix":"补回开门动作","required":$required}]}"""

    @Test fun successfulCandidateNeedsAnActualReviewAndStaysSeparateFromConfirmation() = runBlocking {
        val stages = mutableListOf<DraftStage>()
        var requests = 0
        val result = DraftWorkflow.run(task, RuleSet(), create = { requests++; clean }, review = { requests++; pass }, revise = { _, _ -> error("不该修订") }, onStage = { s, _, _ -> stages += s })
        assertEquals(2, requests)
        assertEquals(listOf(DraftStage.CREATING, DraftStage.CHECKING, DraftStage.REVIEWING, DraftStage.READY), stages)
        assertTrue(result.assessment.passed)
        assertEquals(DraftStage.READY, result.stage)
        assertTrue(result.assessment.matches(clean, task.fingerprint))
        assertFalse(result.assessment.matches(clean + "她停了停。", task.fingerprint))
        assertFalse(result.assessment.matches(clean, "materials-2"))
    }

    @Test fun aRevisionGetsANewReviewAndOnlyRequiredOpinionsReachTheReviser() = runBlocking {
        val initial = "她看见了来客。"
        var reviews = 0
        var revisions = 0
        val result = DraftWorkflow.run(task, RuleSet(), create = { initial }, review = { text ->
            reviews++
            if (text == initial) issue(initial) else pass
        }, revise = { text, report ->
            revisions++
            assertEquals(initial, text)
            assertTrue("先开门" in report)
            clean
        })
        assertEquals(2, reviews)
        assertEquals(1, revisions)
        assertEquals(clean, result.text)
        assertTrue(result.assessment.matches(clean, task.fingerprint))
        assertEquals(1, result.assessment.round)
    }

    @Test fun missingMaterialStopsRevisionInsteadOfInventingAFact() = runBlocking {
        var revised = false
        val result = DraftWorkflow.run(task, RuleSet(), create = { clean }, review = { """{"summary":[],"missing":["尚未给出主人是否有权开门"],"issues":[]}""" }, revise = { _, _ -> revised = true; clean })
        assertEquals(DraftStage.NEEDS_REVIEW, result.stage)
        assertFalse(result.assessment.passed)
        assertFalse(revised)
    }

    @Test fun aestheticSuggestionsDoNotBecomeMandatoryPlotChanges() = runBlocking {
        val suggestion = """{"summary":["此处可以更从容"],"missing":[],"issues":[{"kind":"CRAFT","quote":"门外响起敲门声。","problem":"可以考虑增加一点生活细节","basis":"审美建议，不是细纲事实冲突","fix":"由作者决定","required":false}]}"""
        val result = DraftWorkflow.run(task, RuleSet(), create = { clean }, review = { suggestion }, revise = { _, _ -> error("建议不能擅自重写") })
        assertTrue(result.assessment.passed)
        assertEquals(1, result.assessment.findings.size)
    }

    @Test fun invalidOrFabricatedReviewNeverPasses() {
        for (answer in listOf("写得很好", "{}", """{"summary":[],"missing":[]}""", issue("正文里不存在的句子。"))) {
            try { DraftChecks.parse(answer, clean, task.fingerprint); fail("错误报告应拒绝：$answer") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun localHardRulesStillFailWhenTheModelSaysEverythingIsFine() = runBlocking {
        val rules = CheckRules.parse("禁用：敲门声")
        var calls = 0
        val result = DraftWorkflow.run(task, rules, create = { clean }, review = { pass }, revise = { text, _ -> calls++; text })
        assertFalse(result.assessment.passed)
        assertEquals(DraftStage.NEEDS_REVIEW, result.stage)
        assertEquals(1, calls)
        assertTrue(result.assessment.localIssues.isNotEmpty())
    }

    @Test fun perChapterTargetOverridesBookLengthAndElasticTargetsOnlyWarn() {
        val rules = CheckRules.parse("字数：100-200")
        val (issues, _) = DraftChecks.local(clean, rules, "10—80字")
        assertTrue(issues.toString(), issues.isEmpty())
        val (elasticIssues, notes) = DraftChecks.local(clean, rules, "约100—200字")
        assertTrue(elasticIssues.isEmpty())
        assertTrue(notes.isNotEmpty())
    }

    @Test fun aSingleExplicitChapterGoalDoesNotRetainTheConflictingBookMinimum() {
        val rules = CheckRules.parse("字数：至少100")
        val (issues, notes) = DraftChecks.local(clean, rules, "45字")
        assertTrue(issues.toString(), issues.isEmpty())
        assertTrue(notes.isNotEmpty())
        assertTrue(DraftChecks.local(clean, rules, "严格45字").first.isNotEmpty())
    }

    @Test fun restoredCandidateIsReviewedWithoutCallingCreateAgain() = runBlocking {
        val result = DraftWorkflow.run(task, RuleSet(), initial = clean, create = { error("不应重写候选稿") }, review = { pass }, revise = { _, _ -> error("不应修订") })
        assertEquals(clean, result.text)
        assertEquals(DraftStage.READY, result.stage)
    }

    @Test fun cancellationDoesNotTurnAnInterruptedRunIntoAPass() = runBlocking {
        val stages = mutableListOf<DraftStage>()
        try {
            DraftWorkflow.run(task, RuleSet(), create = { clean }, review = { throw CancellationException("停止") }, revise = { _, _ -> clean }, onStage = { s, _, _ -> stages += s })
            fail("取消必须传播")
        } catch (_: CancellationException) { }
        assertFalse(DraftStage.READY in stages)
    }

    @Test fun blockedTaskMakesNoRequests() = runBlocking {
        var calls = 0
        try {
            DraftWorkflow.run(task.copy(blocking = listOf("缺少本章事实")), RuleSet(), create = { calls++; clean }, review = { calls++; pass }, revise = { _, _ -> calls++; clean })
            fail("资料缺项不能继续")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun manuscriptAndReportMarkersStayOutOfACompleteChapter() {
        assertTrue(DraftChecks.local("【写作说明】\n下面开始。", RuleSet(), "依细纲").first.isNotEmpty())
        assertTrue(DraftChecks.local("门打开了。[待补角色]", RuleSet(), "依细纲").first.isNotEmpty())
        assertTrue(DraftChecks.local(clean, RuleSet(), "依细纲").first.isEmpty())
    }
}
