package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import org.junit.Assert.*
import org.junit.Test

class ProjectWritePromptsTest {
    private val card = ChapterCard(
        facts = "陈青获准查看木料，不能调兵；最后决定北返。", boundary = "本章不发生渡河战斗。", pov = "陈青", hook = "城外传来马蹄声。",
    )

    private fun task() = ChapterWritingTask(
        title = "第四章 河桥", chapter = 4, volume = 1,
        instruction = "保留旧友之间的日常话，不要改成互相总结。", targetLength = "6000—8000汉字",
        previous = "城门在他身后合上。", previousLabel = "用户定稿第三章", card = card,
        sourceNames = listOf("第一卷细纲", "人物档案"), warnings = emptyList(), blocking = emptyList(), project = true,
        fingerprint = "current-source-fingerprint", context = "陈青从未获知旧友的真实来历。",
    )

    private fun prose(scope: ProseScope = ProseScope.CHAPTER) =
        ProseTask(4, "第四章 河桥", card, scope, length = "6000—8000汉字", instruction = "保留旧友之间的日常话，不要改成互相总结。")

    /** 项目包的创作：事实、边界、篇幅、作者的要求都在任务卡里；细纲写成场面，不是打卡清单；没有流程话术。 */
    @Test fun composeHonoursTheChapterTaskWithoutTurningTheFactSheetIntoASceneQuota() {
        val p = WritePrompts.draft("城门在他身后合上。", prose())
        assertTrue(p.contains("篇幅：6000—8000汉字"))
        assertTrue(p.contains("这一章不写到：本章不发生渡河战斗"))
        assertTrue(p.contains("本章必须成立的事：陈青获准查看木料，不能调兵"))
        assertTrue(p.contains("作者这次的要求：保留旧友之间的日常话"))
        assertTrue(p.contains("写成场面和对话，不要列清单式交代"))
        assertTrue(p.endsWith(WritePrompts.taskCard(prose().copy(opening = true, previous = true))))
        // 内部术语、核验流程的话一句都不给写正文的模型。
        listOf("本次明确要求优先", "不逐项表演", "流程检查通过", "资料分工", "事实底稿", "细纲卡", "承接边界", "当前阶段", "核清", "来源 P_")
            .forEach { assertFalse(it, p.contains(it)) }
        assertFalse(p.contains("3000 到 5000"))
    }

    /** 审读：判断标准都在；本章任务只在系统提示里给一次，请求里不再重复整份任务包。 */
    @Test fun reviewHasEvidenceAndMissingDataInsteadOfPretendingToHaveRunAGate() {
        val p = WritePrompts.projectReview(task(), "陈青举起了令牌。", "禁用空洞总结。")
        assertTrue(p.contains("陈青举起了令牌。"))
        assertTrue(p.contains("资料不足列为missing"))
        assertTrue(Regex("不(?:能)?把推测当错误").containsMatchIn(p))
        assertTrue(p.contains("具体资料依据"))
        assertTrue(p.contains("审美建议required=false"))
        assertTrue(p.contains("只输出JSON"))
        assertTrue(p.contains("\"issues\""))
        assertTrue(p.contains("不声称已运行脚本"))
        assertTrue(p.contains("核时地、在场与知情、资源权限"))
        assertFalse(p.contains("当前阶段：创作"))
        assertFalse(p.contains("【本次章节任务】"))
        assertFalse(p.contains("陈青从未获知旧友的真实来历"))
    }

    /** 修订：意见是线索不是新设定、误读原稿的不照办、不把对白都改短；和写正文一样以任务卡收尾。 */
    @Test fun repairUsesOnlyVerifiedOpinionsAndEndsWithTheTaskCard() {
        val voice = WritePrompts.proseTail(com.xycs.lingyuereader.data.WorkAssist(banned = listOf("嘴角勾起")), null)
        val p = WritePrompts.projectRevision("陈青举起了令牌。", "这枚令牌只准查木料，不能调兵。", prose(), voice)
        assertTrue(p.contains("不自动成为新设定"))
        assertTrue(p.contains("误读了原稿"))
        assertTrue(p.contains("不要把所有对白改短"))
        assertTrue(p.contains("改完整章，交出修订后的完整正文"))
        assertTrue(p.indexOf(WritePrompts.PROSE_HEADER) in 0 until p.indexOf(WritePrompts.CARD_HEADER))
        assertTrue(p.endsWith(WritePrompts.taskCard(prose(ProseScope.REVISE))))
        assertFalse(p.contains("当前阶段：审读"))
        assertFalse(p.contains("旧审核与待核状态增量"))
    }

    /** 写完整章、写到本章结尾：停在细纲的章末钩子上，不再说“不强加悬念”。 */
    @Test fun chapterEndingsStopOnTheHook() {
        val draft = WritePrompts.draft("", prose())
        assertTrue(draft.contains("写完整章，停在章末钩子上"))
        assertTrue(draft.contains("章末停在：城外传来马蹄声。"))
        val more = WritePrompts.draftMore("他站住。", prose(ProseScope.TO_END).copy(length = "约 5000 字"))
        assertTrue(more.contains("把这一章写完，停在章末钩子上"))
        val inline = WritePrompts.continueAt("他站住。", "", prose(ProseScope.TO_END))
        listOf(draft, more, inline).forEach {
            assertFalse(it.contains("不强加悬念"))
            assertFalse(it.contains("自然收束"))
        }
    }
}
