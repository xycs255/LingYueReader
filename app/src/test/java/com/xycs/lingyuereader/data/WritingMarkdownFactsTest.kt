package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingMarkdownFactsTest {
    @Test fun markdownCoverHeadingsAreNotPeopleOrAwarenessHolders() {
        val people = "# 人物档案\n人物索引。\n# 核心人物\n名单。\n# 张飞\n守住关口。"
        assertEquals(listOf("张飞"), ChapterBriefs.characters(listOf("人物档案" to people)).map { it.name })
        assertTrue(ChapterBriefs.characters(listOf("三国演义" to "# 三国演义\n这是一部作品。" )).isEmpty())

        val awareness = "# 察觉表\n## 赵云\n| 章 | 事实 |\n| --- | --- |\n| 第2章 | 城门将关 |"
        val item = ChapterBriefs.knowledge(listOf("察觉表" to awareness), listOf("赵云")).single()
        assertEquals(setOf("赵云"), item.holders)
        assertFalse("察觉表" in item.text)
    }

    @Test fun longMarkdownParagraphIsNotTruncatedWhenSplitForFacts() {
        val paragraph = "渡口暗号".repeat(700)
        val blocks = PackSections.split("# 第一卷\n$paragraph", chunk = 1_200)
        assertEquals(paragraph, blocks.joinToString("") { it.text })
        assertTrue(blocks.all { it.path == listOf("第一卷") && it.text.length <= 1_200 })
    }

    @Test fun fencedExamplesDoNotBecomePeopleKnowledgeOrForeshadows() {
        val people = """# 人物档案
            |```md
            |**假人**：这是格式示例。
            |```
            |# 赵云
            |赵云要护送百姓出城。
        """.trimMargin()
        assertEquals(listOf("赵云"), ChapterBriefs.characters(listOf("人物档案" to people)).map { it.name })

        val awareness = """# 察觉表
            |```md
            |- 第1章 张飞知道假消息。
            |```
            |- 第2章 赵云知道城门将关。
        """.trimMargin()
        val known = ChapterBriefs.knowledge(listOf("察觉表" to awareness), listOf("张飞", "赵云"))
        assertEquals(1, known.size)
        assertTrue("赵云" in known.single().text)
        assertFalse("假消息" in known.single().text)

        val promises = """# 伏笔表
            |~~~md
            |- 示例玉佩：第1章埋，第2章回收。
            |~~~
            |- 真玉佩：第3章埋，第8章回收。
        """.trimMargin()
        assertEquals(listOf("真玉佩"), ChapterBriefs.foreshadows(listOf("伏笔表" to promises)).map { it.what })
    }

    @Test fun statusUpdateWorksForMarkdownTableWithoutOuterPipes() {
        val markdown = "伏笔 | 埋下 | 回收\n--- | --- | ---\n真玉佩 | 第3章 | 第8章"
        val item = ChapterBriefs.foreshadows(listOf("伏笔表" to markdown)).single()
        val marked = ChapterBriefs.markDone(markdown, item, 9)
        val table = MdTable.parse(marked).single()
        assertEquals(listOf("伏笔", "埋下", "回收", "状态"), table.header)
        assertEquals("已回收（第9章）", table.rows.single()[3])
        assertTrue(ChapterBriefs.foreshadows(listOf("伏笔表" to marked)).single().done)
    }
}
