package com.xycs.lingyuereader.data

import org.junit.Assert.*
import org.junit.Test

class ProjectPackImportTest {
    @Test fun stagePromptsAreInstructionsEvenWhenTheirNamesContainManuscript() {
        for (name in listOf("写作规范/合订本与细纲配套正文提示词.txt", "正文创作_prompt.md", "章节审读提示词.txt", "章节修订提示词.md")) {
            val item = PackImport.classify(name, "给助手的分阶段工作要求")
            assertEquals(name, PackKind.INSTRUCTION, item.kind)
            assertFalse(name, item.asChapters)
        }
    }

    @Test fun referenceManuscriptsNeverBecomeAcceptedChaptersByFilename() {
        for (name in listOf("参考/某书研究/三章正文排版参照.txt", "参考\\正文第一至三章.md", "第一章_对白验证样章.txt", "未定样章_正文.txt", "维护记录/已定正文_第1-3章.txt")) {
            val item = PackImport.classify(name, "第一章 开门\n这是研究或候选稿。")
            assertEquals(name, PackKind.REFERENCE, item.kind)
            assertFalse(name, item.asChapters)
            assertFalse(name, item.always)
        }
    }

    @Test fun realAcceptedManuscriptImportRemainsAvailable() {
        val item = PackImport.classify("已定正文_第1-3章.txt", "第一章 过门\n正文")
        assertTrue(item.asChapters)
    }

    @Test fun chapterRangesInsidePlanningFileNamesDoNotTurnOutlinesIntoManuscripts() {
        val cases = mapOf(
            "分章细纲/第一卷细纲_第1-8章.md" to PackKind.VOLUME,
            "第二卷卷纲_第9—20章.txt" to PackKind.VOLUME,
            "章组细纲_第21至30章.md" to PackKind.VOLUME,
            "章节分镜_第31-40章.md" to PackKind.VOLUME,
            "全本大纲_第1-40章.txt" to PackKind.OUTLINE,
        )
        cases.forEach { (name, kind) ->
            val item = PackImport.classify(name, "### 第一章\n事实：这是规划，不是正文。")
            assertFalse(name, item.asChapters)
            assertEquals(name, kind, item.kind)
        }
    }
}
