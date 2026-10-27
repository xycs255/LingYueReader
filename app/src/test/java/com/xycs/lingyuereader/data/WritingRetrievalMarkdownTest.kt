package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingRetrievalMarkdownTest {
    @Test fun volumeH1IsKeptAsParentAndFencedExamplesAreNotIndexed() {
        val md = """
            # 第一卷《渡口》
            ## 第一章 夜渡
            林月在渡口等船。

            ```markdown
            # 第九卷《归乡》
            ## 第九章 假例
            第九章：这里仅是格式示例，不是项目事实。
            ```

            ## 第二章 对岸
            沈默在对岸落脚。
        """.trimIndent()
        val units = PackUnits.split(md)
        assertTrue(units.any { it.path == listOf("第一卷《渡口》", "第一章 夜渡") && "林月在渡口等船" in it.text })
        assertTrue(units.any { it.path == listOf("第一卷《渡口》", "第二章 对岸") && "沈默在对岸落脚" in it.text })
        assertFalse(units.any { "第九卷" in it.path.joinToString() || "格式示例" in it.text })
    }

    @Test fun multipleH1PeopleAreSeparateWhileSingleDocumentTitleRemainsImplicit() {
        val people = PackUnits.split("# 张飞\n守长坂桥。\n# 赵云\n护送阿斗。")
        assertEquals(listOf(listOf("张飞"), listOf("赵云")), people.map { it.path })
        assertEquals(listOf("守长坂桥。", "护送阿斗。"), people.map { it.text })

        val outline = PackUnits.split("# 全书总纲\n## 第一卷\n渡口失守。", documentTitle = "全书总纲")
        assertEquals(listOf("第一卷"), outline.single().path)

        val singlePerson = PackUnits.split("# 赵云\n## 动机\n护送百姓出城。", documentTitle = "人物档案")
        assertEquals(listOf("赵云", "动机"), singlePerson.single().path)
    }

    @Test fun tildeFencesDoNotBecomeCanonOrSplitARealSection() {
        val md = "## 已定事实\n村门关闭。\n~~~md\n# 第三卷\n伪事实：村门大开。\n~~~\n村门仍关着。"
        val units = PackUnits.split(md)
        assertEquals(2, units.size)
        assertTrue(units.all { it.path == listOf("已定事实") })
        assertFalse(units.any { "伪事实" in it.text })
    }
}
