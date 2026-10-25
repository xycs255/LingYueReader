package com.xycs.lingyuereader.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterLengthSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun editorialReferencesCannotBecomeChapterFactsOrChapterLength() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("合成长篇")
        val id = w.chapters.single().id
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "# 第一卷\n第一至二章约三千至四千字。\n### 第一章 开门\n事实：来客到门口，主人开门。\n对白范例建议一百至二百字。")
        s.addPackDoc(w.id, PackKind.REFERENCE, "独立审读报告", "### 第一章 开门\n局部修法：增加约150—250汉字。\n事实：报告中的建议不自动成为新情节。")
        val task = s.writingTask(w.id, id)!!
        assertEquals("约 3000—4000字", task.targetLength)
        assertFalse(task.card.facts.contains("报告中的建议"))
        assertFalse(task.sourceNames.contains("独立审读报告"))
    }

    @Test fun onlyAWholeChapterLengthFieldCanOverrideScopedOrGeneralDefaults() {
        assertNull(ProjectLengths.chapter("对白150—250汉字。局部修法：补充200字。"))
        assertEquals("7000—8000字", ProjectLengths.chapter("场面留给人物的选择。正文七千至八千字，常规手续简述。"))
        assertEquals("不少于 5000 字", ProjectLengths.chapter("本章字数：至少五千字。"))
        assertEquals("3000—4000字", ProjectLengths.chapter("片段150字，整章三千至四千字。"))
        assertEquals("6000字", ProjectLengths.chapter("目标字数：6000"))
        assertNull(ProjectLengths.chapter("本章先做三件事，补写对白150—250字。"))
        assertNull(ProjectLengths.chapter("本章收束：一、回到院中。正文中不重复说明，另一卷约十五万字。"))
        assertNull(ProjectLengths.chapter("本章引用字数：一、先列事件。"))
    }

    @Test fun generalLengthCannotScanAcrossSceneAdviceOrVolumeBudgets() {
        assertNull(ProjectLengths.general(listOf("每章先选一件让人物行动的主冲突，卷规模约十五万字。")))
        assertEquals("不少于 5000 字", ProjectLengths.general(listOf("每章先选主冲突，卷规模约十五万字。其余章节五千字起步，按本章要求调整。")))
        assertEquals("不少于 5000 字", ProjectLengths.general(listOf("三十章，正文每章五千字起步，按场景完整展开。")))
    }

    @Test fun unscopedBookMinimumIsAFallbackAndNeverAnOpeningOverride() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("合成长篇")
        val id = w.chapters.single().id
        s.addPackDoc(w.id, PackKind.OUTLINE, "全本总纲", "规模为十五卷。其余章节五千字起步，依本章明确要求调整。")
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "# 第一卷\n第一至二章约三千至四千字。\n### 第一章 开门\n事实：来客到门口。\n### 第三章 出门\n事实：主人离开院子。")
        assertEquals("约 3000—4000字", s.writingTask(w.id, id)!!.targetLength)
        s.renameChapter(w.id, id, "第三章 出门")
        val middle = s.writingTask(w.id, id)!!
        assertEquals("不少于 5000 字", middle.targetLength)
        assertTrue(middle.previousLabel.startsWith("以细纲结果承接"))
        assertFalse(middle.previousLabel.contains("开篇"))
    }
}
