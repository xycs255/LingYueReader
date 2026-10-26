package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import org.junit.Assert.*
import org.junit.Test

class ProjectWritePromptsTest {
    private fun task() = ChapterWritingTask(
        title = "第四章 河桥", chapter = 4, volume = 1,
        instruction = "保留旧友之间的日常话，不要改成互相总结。", targetLength = "6000—8000汉字",
        previous = "城门在他身后合上。", previousLabel = "用户定稿第三章", card = ChapterCard(
            facts = "陈青获准查看木料，不能调兵；最后决定北返。", boundary = "本章不发生渡河战斗。", pov = "陈青",
        ), sourceNames = listOf("第一卷细纲", "人物档案"), warnings = emptyList(), blocking = emptyList(), project = true,
        fingerprint = "current-source-fingerprint", context = "陈青从未获知旧友的真实来历。",
    )

    @Test fun composeHonoursTheChapterTaskWithoutTurningTheFactSheetIntoASceneQuota() {
        val p = WritePrompts.projectDraft(task(), "对白可以长说，也可改口。")
        assertTrue(p.contains("6000—8000汉字"))
        assertTrue(p.contains("本章不发生渡河战斗"))
        assertTrue(p.contains("不能调兵"))
        assertTrue(p.contains("本次明确要求优先"))
        assertTrue(p.contains("不逐项表演"))
        assertTrue(p.contains("允许长说"))
        assertTrue(p.contains("流程检查通过也不等于作者已定"))
        assertFalse(p.contains("3000 到 5000"))
        assertFalse(p.contains("事实都要写到"))
    }

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
        assertFalse(p.contains("当前阶段：创作"))
    }

    @Test fun repairUsesOnlyVerifiedOpinionsAndInvalidatesOldEvidence() {
        val p = WritePrompts.projectRevision(task(), "陈青举起了令牌。", "这枚令牌只准查木料，不能调兵。", "保留生活细节。")
        assertTrue(p.contains("不自动成为新设定"))
        assertTrue(p.contains("误读原稿"))
        assertTrue(p.contains("两遍校稿"))
        assertTrue(p.contains("旧审核与待核状态增量均作废"))
        assertTrue(p.contains("只交付修订后的完整正文"))
        assertTrue(p.contains("不把所有对白改短"))
        assertFalse(p.contains("当前阶段：审读"))
    }

    @Test fun olderComposeAndContinuePromptsAlsoRespectNaturalChapterEndings() {
        val draft = WritePrompts.draft("河桥", "", "", "6000字", "")
        assertFalse(draft.contains("事实都要写到"))
        assertTrue(draft.contains("事实底稿供核对"))
        val more = WritePrompts.draftMore("河桥", "他站住。", "", "6000字")
        assertTrue(more.contains("不强加悬念"))
        val inline = WritePrompts.continueAt("河桥", "他站住。", "", InlineMode.TO_END, "6000字", "", "")
        assertTrue(inline.contains("既定章末自然收束"))
        assertFalse(inline.contains("停在章末钩子上"))
    }
}
