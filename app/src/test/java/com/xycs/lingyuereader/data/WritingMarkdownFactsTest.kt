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

    @Test fun categoryLikeH1HeadingsAreNotPeople() {
        // 封面标题和文档名不一样（改过标题，或者带书名“长夜”）：像资料分类名的仍是封面。
        val people = "# 长夜人物\n本文件记录主要人物。\n\n## 林月\n药铺女儿。\n## 沈默\n捕快。"
        assertEquals(listOf("林月", "沈默"), ChapterBriefs.characters(listOf("人物档案" to people)).map { it.name })
        val villains = "# 反派\n以下是反派。\n## 赵三\n山匪。"
        assertEquals(listOf("赵三"), ChapterBriefs.characters(listOf("人物档案" to villains)).map { it.name })
        // 不在开头的分类标题、下面紧跟二级标题的一级标题，也不是人物。
        val grouped = "# 林月\n药铺女儿。\n# 反派\n这几个人都不好惹。\n# 渡口众人\n## 沈默\n捕快。"
        assertEquals(listOf("林月", "沈默"), ChapterBriefs.characters(listOf("人物档案" to grouped)).map { it.name })

        // 三个解析器对封面的判断一致：封面不进分节目录和检索路径，人物卡片的一级标题留着。
        assertEquals(listOf("林月", "沈默"), PackOutline.sections(people, "人物档案").map { it.path.joinToString(" › ") })
        assertEquals(listOf(emptyList(), listOf("林月"), listOf("沈默")), PackUnits.split(people, documentTitle = "人物档案").map { it.path })
        assertEquals(listOf("一、原则", "二、分卷"), PackOutline.sections("# 《长夜》总纲\n## 一、原则\nA\n## 二、分卷\nB", "全书总纲").map { it.path.joinToString(" › ") })
        assertEquals(listOf("第二卷 对岸", "第二卷 对岸 › 一、主线"), PackOutline.sections("# 第二卷 对岸\n## 一、主线\n第3章渡船沉没。", "第二卷细纲").map { it.path.joinToString(" › ") })
        assertEquals(listOf(listOf("第二卷 对岸", "一、主线")), PackUnits.split("# 第二卷 对岸\n## 一、主线\n第3章渡船沉没。", documentTitle = "第二卷细纲").map { it.path })
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
