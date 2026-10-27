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

    @Test fun fabricatedFactSourceStopsAutomaticRevision() = runBlocking {
        val source = ReviewEvidence.task(task)
        val evidence = ReviewEvidence(listOf(source))
        var revised = false
        val answer = """{"summary":[],"missing":[],"issues":[{"kind":"FACT","quote":"门外响起敲门声。","problem":"人物已经出门","basis":"本章细纲说人物必须出门","source_ref":"${source.ref}","source_quote":"人物必须出门","fix":"让人物出门","required":true}]}"""
        val result = DraftWorkflow.run(task, RuleSet(), create = { clean }, review = { answer },
            revise = { _, _ -> revised = true; clean }, evidence = evidence)
        assertFalse(revised)
        assertEquals(DraftStage.NEEDS_REVIEW, result.stage)
        assertTrue(result.assessment.missing.single().contains("来源无法"))
        assertFalse(result.assessment.findings.single().required)
        assertEquals(false, result.assessment.findings.single().sourceLocated)
        assertTrue(result.assessment.findings.single().render().startsWith("待核 · FACT"))
        assertTrue(result.assessment.findings.single().render().contains("不能作为已核实的必改意见"))
    }

    @Test fun exactSourceQuotationCanSupportARequiredFactReview() = runBlocking {
        val source = ReviewEvidence.task(task)
        val evidence = ReviewEvidence(listOf(source))
        val initial = "她看见了来客。"
        val answer = """{"summary":[],"missing":[],"issues":[{"kind":"FACT","quote":"$initial","problem":"漏了开门动作","basis":"本章事实要求先开门","source_ref":"${source.ref}","source_quote":"客人抵达，主人决定开门","fix":"补开门动作","required":true}]}"""
        var revised = false
        val result = DraftWorkflow.run(task, RuleSet(), create = { initial },
            review = { if (it == initial) answer else pass },
            revise = { _, _ -> revised = true; clean }, evidence = evidence)
        assertTrue(revised)
        assertEquals(DraftStage.READY, result.stage)
        assertTrue(evidence.locates(source.ref, "客人抵达，主人决定开门"))
        assertFalse(evidence.locates(source.ref, "主人"))
        assertFalse(evidence.locates("T_wrong", "客人抵达，主人决定开门"))
    }

    @Test fun unreferencedRequiredCraftCannotTriggerAutomaticRevision() = runBlocking {
        val initial = "她看见了来客。"
        val answer = """{"summary":[],"missing":[],"issues":[{"kind":"CRAFT","quote":"$initial","problem":"对白语气不合规范","basis":"写作规范要求换一种说法","fix":"重写对白","required":true}]}"""
        var revised = false
        val result = DraftWorkflow.run(task, RuleSet(), create = { initial }, review = { answer },
            revise = { _, _ -> revised = true; clean }, evidence = ReviewEvidence(listOf(ReviewEvidence.task(task))))
        assertFalse(revised)
        assertEquals(DraftStage.NEEDS_REVIEW, result.stage)
        assertTrue(result.assessment.missing.single().contains("写法意见的来源无法"))
        assertTrue(result.assessment.findings.single().render().startsWith("待核 · CRAFT"))
        assertTrue(result.assessment.findings.single().render().contains("不能作为已核实的必改意见"))
    }

    @Test fun requiredCraftCanCiteWritingRuleButFactCannotUseStyleAsEvidence() = runBlocking {
        val style = ReviewEvidence.pack(PackDoc("style", PackKind.STYLE, "写作规范"), "对白要避免空泛总结，保留人物自己的语气。")
        val rules = ReviewEvidence.pack(PackDoc("rules", PackKind.RULES, "检查规则"), "禁用空泛总结这句话。")
        val evidence = ReviewEvidence(listOf(ReviewEvidence.task(task), style, rules))
        val initial = "她看见了来客。"
        fun answer(kind: String, ref: String, quote: String) = """{"summary":[],"missing":[],"issues":[{"kind":"$kind","quote":"$initial","problem":"写法不合要求","basis":"明确写法规则","source_ref":"$ref","source_quote":"$quote","fix":"局部改写","required":true}]}"""
        val craft = DraftWorkflow.run(task, RuleSet(), create = { initial },
            review = { if (it == initial) answer("CRAFT", style.ref, "对白要避免空泛总结") else pass },
            revise = { _, _ -> clean }, evidence = evidence)
        assertEquals(DraftStage.READY, craft.stage)
        assertEquals(1, craft.assessment.round)
        assertTrue(evidence.locates(rules.ref, "禁用空泛总结这句话", "CRAFT"))

        val wrongType = DraftChecks.parse(answer("FACT", style.ref, "对白要避免空泛总结"), initial,
            task.fingerprint, evidence)
        assertFalse(wrongType.passed)
        assertTrue(wrongType.missing.single().contains("事实意见的来源无法"))
        assertFalse(wrongType.findings.single().required)
    }

    @Test fun craftCannotTreatFactCardFinalProseOrCharacterProfileAsWritingRule() {
        val brief = task.copy(instruction = "对白必须保留人物自己的语气", previous = "前章陈青已经收起了令牌。")
        val taskSource = ReviewEvidence.task(brief)
        val character = ReviewEvidence.pack(PackDoc("character", PackKind.CHARACTER, "人物档案"), "陈青一直害怕夜里的雨声。")
        val past = ReviewEvidence.chapter(WorkChapter("past", "第一章", 0, 0, ChapterStatus.FINAL),
            "前章陈青已经收起了令牌。")
        val evidence = ReviewEvidence(listOf(taskSource, character, past))
        val initial = "她看见了来客。"
        fun craft(ref: String, sourceQuote: String) = """{"summary":[],"missing":[],"issues":[{"kind":"CRAFT","quote":"$initial","problem":"应改写这一句","basis":"声称这是写法硬要求","source_ref":"$ref","source_quote":"$sourceQuote","fix":"改写","required":true}]}"""
        for ((ref, sourceQuote) in listOf(
            taskSource.ref to "客人抵达，主人决定开门",
            taskSource.ref to "前章陈青已经收起了令牌",
            past.ref to "前章陈青已经收起了令牌",
            character.ref to "陈青一直害怕夜里的雨声",
        )) {
            val report = DraftChecks.parse(craft(ref, sourceQuote), initial, brief.fingerprint, evidence)
            assertFalse(report.passed)
            assertTrue(report.missing.single().contains("写法意见的来源无法"))
            assertFalse(report.findings.single().required)
        }
        val explicit = DraftChecks.parse(craft(taskSource.ref, "对白必须保留人物自己的语气"), initial,
            brief.fingerprint, evidence)
        assertTrue(explicit.findings.single().required)
        assertTrue(explicit.missing.isEmpty())
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
        for (answer in listOf("写得很好", "{}", """{"summary":[],"missing":[]}""")) {
            try { DraftChecks.parse(answer, clean, task.fingerprint); fail("错误报告应拒绝：$answer") }
            catch (_: IllegalArgumentException) { }
        }
        // 编造的原句不作废整份，但这份审读不能通过。
        val fabricated = DraftChecks.parse(issue("正文里不存在的句子。"), clean, task.fingerprint)
        assertFalse(fabricated.passed)
        assertTrue(fabricated.findings.none { it.required })
    }

    @Test fun oneMismatchedQuoteDropsOnlyThatSuggestionOrMarksTheRequiredItemUnverified() = runBlocking {
        val text = "林月推开渡口的木门，说：“沈默，你来晚了。”\n老周在船头收起缆绳。"
        fun item(quote: String, required: Boolean, problem: String) =
            """{"kind":"CRAFT","quote":"$quote","problem":"$problem","basis":"写作规范：对白保持自然语气","fix":"由作者决定","required":$required}"""
        // 全角半角、空白不同的原句仍算对得上；改写过的建议原句对不上只丢掉这一条。
        val suggestions = """{"summary":["对白可以更松"],"missing":[],"issues":[""" +
            item("林月推开渡口的木门,说:“沈默,你来晚了。”", false, "可以多一点停顿") + "," +
            item("杜娘在岸边喊了一声。", false, "这句原文并不存在") + "]}"
        val kept = DraftChecks.parse(suggestions, text, task.fingerprint)
        assertTrue(kept.passed)
        assertEquals(listOf("可以多一点停顿"), kept.findings.map { it.problem })
        assertTrue(kept.findings.single().quoteLocated)

        // 必改项原句对不上：保留并标“依据未核实”，列进缺项，审读不通过，也不交给修订。
        val required = """{"summary":[],"missing":[],"issues":[""" +
            item("顾长风拔刀砍断了缆绳。", true, "缆绳不该被砍断") + "," +
            item("老周在船头收起缆绳。", false, "动作可以更具体") + "]}"
        val report = DraftChecks.parse(required, text, task.fingerprint)
        assertFalse(report.passed)
        assertEquals(2, report.findings.size)
        val unverified = report.findings.first()
        assertFalse(unverified.quoteLocated)
        assertFalse("对不上原句的必改项不能当作已核实的依据", unverified.required)
        assertTrue(unverified.render().startsWith("依据未核实"))
        assertTrue(report.missing.single().contains("缆绳不该被砍断"))
        assertTrue(report.findings.none { it.required })
        var revised = false
        val result = DraftWorkflow.run(task, RuleSet(), initial = text, create = { text }, review = { required },
            revise = { _, _ -> revised = true; text })
        assertFalse(revised)
        assertEquals(DraftStage.NEEDS_REVIEW, result.stage)
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
