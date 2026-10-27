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

    @Test fun separatorWithDifferentColumnCountStillMakesATable() {
        // 手写表格的分隔行比表头少一格：仍然是表格，列以表头为准。
        val fewer = "| 伏笔 | 埋下 | 回收 |\n|---|---|\n| 玉佩 | 第3章 | 第8章 |"
        val table = MdTable.parse(fewer).single()
        assertEquals(listOf("伏笔", "埋下", "回收"), table.header)
        val f = ChapterBriefs.foreshadows(listOf("伏笔表" to fewer)).single()
        assertEquals("玉佩", f.what)
        assertEquals(3, f.planted)
        assertEquals(8, f.payoff)

        // 分隔行多一格、某行少一格或多一格，都照样认。
        val ragged = "| 伏笔 | 埋下 | 回收 |\n| :-- | --- | --: | --- |\n| 渡口铜铃 | 第2章 |\n| 旧船票 | 第4章 | 第9章 | 备注 |"
        val rows = MdTable.parse(ragged).single()
        assertEquals(2, rows.rows.size)
        assertEquals("", rows.cell(rows.rows[0], 2))
        assertEquals(listOf("渡口铜铃", "旧船票"), ChapterBriefs.foreshadows(listOf("伏笔表" to ragged)).map { it.what })

        // 只有横线、没有竖线的一行（分隔线、标题下划线）不是分隔行。
        assertTrue(MdTable.parse("林月 | 沈默\n---\n渡口").isEmpty())
    }

    @Test fun proseLineWithAPipeAfterATableIsNotARow() {
        val md = "| 伏笔 | 埋下 | 回收 |\n|---|---|---|\n| 玉佩 | 第3章 | 第8章 |\n注：A|B 两条线并行，第9章合流。"
        val table = MdTable.parse(md).single()
        assertEquals(listOf(listOf("玉佩", "第3章", "第8章")), table.rows)
        assertEquals(listOf(2), table.lines)
        assertEquals(listOf("玉佩"), ChapterBriefs.foreshadows(listOf("伏笔表" to md)).map { it.what })
    }

    @Test fun longLinesWithBackticksAndBackslashesParseInLinearTime() {
        // 每行 10 万字符：反引号一个接一个、反斜杠一个接一个，都放在表格行里，切格和去转义都要走一遍。
        val ticks = "| " + "`a".repeat(50_000) + " | 尾 |"
        val slashes = "| " + "\\a".repeat(50_000) + " | 尾 |"
        val md = "| 甲 | 乙 |\n|---|---|\n$ticks\n$slashes"
        MdTable.parse("| 甲 | 乙 |\n|---|---|\n| `a`\\| | 尾 |") // 预热
        val started = System.nanoTime()
        val table = MdTable.parse(md).single()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("尾", "尾"), table.rows.map { it[1] })
        // 改成线性后本机只要几十毫秒；以前 10 万字符一行要几秒。上限留足余量，免得 CI 偶尔慢时误报。
        assertTrue("10 万字符的行解析用了 $ms 毫秒", ms < 500)
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
