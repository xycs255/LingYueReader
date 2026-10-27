package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingMarkdownTablesTest {
    @Test fun tableWithoutOuterPipesKeepsEscapedAndCodePipesInOneCell() {
        val md = """
            人物 | 知道什么 | 最早章
            --- | --- | ---
            林月 | 玉佩\|旧案；`信|件`只是暗号 | 第三章
            沈默 | 尚不知道 | 第四章
        """.trimIndent()
        val table = MdTable.parse(md).single()
        assertEquals(listOf("人物", "知道什么", "最早章"), table.header)
        assertEquals(listOf("林月", "玉佩|旧案；`信|件`只是暗号", "第三章"), table.rows[0])
        assertEquals(listOf(2, 3), table.lines)

        val changed = MdTable.setCell(md, table.lines[0], 1, "玉佩|账本")
        assertTrue("新竖线应被 Markdown 转义", "玉佩\\|账本" in changed)
        assertEquals(listOf("林月", "玉佩|账本", "第三章"), MdTable.parse(changed).single().rows[0])
        assertEquals(listOf("沈默", "尚不知道", "第四章"), MdTable.parse(changed).single().rows[1])
    }

    @Test fun fencedExampleTablesNeverBecomeForeshadowFacts() {
        val md = """
            # 伏笔表
            下面只是表格写法示例：
            ```markdown
            | 伏笔 | 埋下 | 回收 |
            | --- | --- | --- |
            | 示例玉佩 | 第1章 | 第2章 |
            ```
            ~~~~
            伏笔 | 埋下 | 回收
            --- | --- | ---
            示例账本 | 第1章 | 第2章
            ~~~~

            | 伏笔 | 埋下 | 回收 |
            | --- | --- | --- |
            | 真玉佩 | 第3章 | 第8章 |
        """.trimIndent()
        val tables = MdTable.parse(md)
        assertEquals(1, tables.size)
        assertEquals("真玉佩", tables.single().rows.single()[0])
        val facts = ChapterBriefs.foreshadows(listOf("伏笔表" to md))
        assertEquals(listOf("真玉佩"), facts.map { it.what })
    }

    @Test fun h1OnlyCharacterCardsAreSectionsAndFencedHeadingsAreIgnored() {
        val md = """
            # 林月
            想要一个家，害怕被抛下。

            ```md
            # 示例人物
            此处只是格式示例。
            ```

            # 沈默
            想查清旧案，顾虑连累旁人。
        """.trimIndent()
        val sections = PackSections.split(md)
        assertEquals(listOf("林月", "沈默"), sections.map { it.title })
        assertFalse(sections.any { "示例人物" in it.text })
        assertEquals(listOf("林月", "沈默"), ChapterBriefs.characters(listOf("人物档案" to md)).map { it.name })

        val titled = PackSections.split("# 人物档案\n\n## 核心人物\n林月守着渡口。")
        assertEquals(listOf("核心人物"), titled.map { it.title })
        assertEquals(listOf("人物档案", "核心人物"), titled.single().path)
    }
}
