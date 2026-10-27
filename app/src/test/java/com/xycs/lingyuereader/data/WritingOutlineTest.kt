package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritingOutlineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 列表写法：每章一条“**第X章 章名**：……；钩子：……”，后面有修订补记。 */
    private val listOutline = """
        # 第一卷细纲

        ## 大纲

        - **一句话**：林月在渡口等一个人。
        - **节奏**：每章开头倒计时；每章结尾留钩子。
        第十九章的钩子要在第二十章接上。

        ## 细纲

        ### 第二段 雪夜（十月）

        - **第十九章 雪夜来客**：林月视角开场，提灯到渡口；沈默在渡口等船；钩子：林月问渡口谁管事——老周。
        - **第二十章 守门**：倒计时三天；杜娘关门；边界：老周此时还活着；钩子：灯灭了。
        - **第二十一章 借灯**：沈默借灯。
          - 暗线：玉佩的下落。

        ## 修订补记

        **章号变动。**原第二十一章拆为两章。

        **第二十一章《借灯》落定。**沈默夜里数灯油，少了一壶。

        **第一章修订落定。**新增门房老周。
    """.trimIndent()

    @Test
    fun listOutlinesAreSplitIntoCardFields() {
        val entries = ChapterOutlines.parse(listOf("第一卷细纲" to listOutline))
        // 大纲里的“一句话”“节奏”、正文里提到“第十九章的钩子”、“章号变动”都不算一章。
        assertEquals(listOf(19, 20, 21, 21, 1), entries.map { it.from })
        assertTrue(entries.all { it.volume == 1 })

        val c19 = ChapterOutlines.card(ChapterOutlines.match(entries, 19, 1, "第十九章 雪夜来客"))
        assertEquals("林月视角开场，提灯到渡口；沈默在渡口等船", c19.facts)
        assertEquals("林月问渡口谁管事——老周。", c19.hook)
        assertEquals("林月", c19.pov)

        val c20 = ChapterOutlines.card(ChapterOutlines.match(entries, 20, 1, "第二十章"))
        assertEquals("倒计时三天", c20.countdown)
        assertEquals("老周此时还活着", c20.boundary)
        assertEquals("杜娘关门", c20.facts)

        // 章名对得上的优先；后面的补记接在后面；缩进的下级列表也算这一章。
        val c21 = ChapterOutlines.card(ChapterOutlines.match(entries, 22, 1, "第二十二章 借灯"))
        assertEquals("沈默借灯。\n【补记】沈默夜里数灯油，少了一壶。", c21.facts)
        assertEquals("玉佩的下落。", c21.threads)

        // “第一章修订落定”没有章名：按章号接上。
        val c1 = ChapterOutlines.card(ChapterOutlines.match(entries, 1, 1, "第一章 渡口"))
        assertEquals("新增门房老周。", c1.facts)
        assertTrue(ChapterOutlines.match(entries, 50, 1, "第五十章 别处").isEmpty())
    }

    @Test
    fun headingsTablesGroupsAndVolumes() {
        val volumeOne = "## 第一卷\n\n### 第3章 旧事\n\n林月回忆旧事。\n"
        val volumeTwo = """
            ## 第二卷

            ### 第3章 夜渡

            视角：沈默
            发生：沈默夜渡，丢了包袱。

            #### 钩子

            对岸有灯。

            ### 第4章 对岸

            | 章 | 章名 | 事实 | 钩子 | 推进的线 |
            |---|---|---|---|---|
            | 第5章 | 雪夜 | 老周病了 | 门外有人 | 暗线：玉佩 |
            | 6 | 回城 | 杜娘回城 | | |

            ## 章组

            - **第七至九章 章组：围城**：三章写围城；钩子：城门开了。
        """.trimIndent()
        val entries = ChapterOutlines.parse(listOf("卷纲" to volumeOne, "卷纲二" to volumeTwo))

        // 按卷编章号：第二卷的第 3 章用第二卷的。
        val c3 = ChapterOutlines.card(ChapterOutlines.match(entries, 3, 2, "第三章"))
        assertEquals("沈默", c3.pov)
        assertEquals("沈默夜渡，丢了包袱。", c3.facts)
        assertEquals("对岸有灯。", c3.hook)
        assertEquals("林月回忆旧事。", ChapterOutlines.card(ChapterOutlines.match(entries, 3, 1, "第三章")).facts)

        // 表格：“章”那一列认章号，其他列按表头归类。
        val c5 = ChapterOutlines.card(ChapterOutlines.match(entries, 5, 2, "第五章 雪夜"))
        assertEquals("老周病了", c5.facts)
        assertEquals("门外有人", c5.hook)
        assertEquals("暗线：玉佩", c5.threads)
        assertEquals("杜娘回城", ChapterOutlines.card(ChapterOutlines.match(entries, 6, 2, "第六章")).facts)

        // 章组：第八章没单独写，用第七至九章那一条。
        val c8 = ChapterOutlines.card(ChapterOutlines.match(entries, 8, 2, "第八章"))
        assertEquals("【第 7—9 章章组】围城：三章写围城", c8.facts)
        assertEquals("城门开了。", c8.hook)
    }

    @Test
    fun fencedMarkdownExamplesAreNotChapterOutlinesOrFacts() {
        val markdown = """
            # 第一卷细纲

            ### 第一章 真章
            事实：开端。

            ```markdown
            ## 第二卷
            ### 第二章 示例章节
            事实：只用于展示格式。
            | 章 | 事实 |
            |---|---|
            | 第八章 | 示例表格 |
            ```

            事实：代码块之后仍属第一章。

            ~~~text
            第九章 示例段落：不应进入细纲。
            ~~~

            ### 第三章 真结尾
            事实：真实的下一章。
        """.trimIndent()
        val txt = "第四章 原有写法：中文 TXT 细纲仍可识别。"
        val entries = ChapterOutlines.parse(listOf("细纲.md" to markdown, "细纲.txt" to txt))

        assertEquals(listOf(1, 3, 4), entries.map { it.from })
        assertEquals(listOf(1, 1, null), entries.map { it.volume })
        assertEquals("事实：开端。\n事实：代码块之后仍属第一章。", entries[0].text)
        assertEquals("真实的下一章。", ChapterOutlines.card(ChapterOutlines.match(entries, 3, 1, "第三章 真结尾")).facts)
        assertTrue(entries.none { "示例" in it.text || "示例" in it.title })
    }

    @Test
    fun cardsFollowTheOutlineUntilTheAuthorEditsThem() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c = w.chapters[0].id
        s.renameChapter(w.id, c, "第十九章 雪夜来客")
        // 还没有卷纲细纲：照旧。
        assertNull(s.plannedCard(w.id, c).source)

        val doc = s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", listOutline)
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物", "| 人物 | 想要 |\n|---|---|\n| 林月 | 回家 |\n| 老周 | 守门 |\n")
        val p = s.plannedCard(w.id, c)
        assertEquals("《第一卷细纲》 第 19 章「雪夜来客」", p.source)
        assertEquals("林月问渡口谁管事——老周。", p.card.hook)
        // 本章资料也按提取出来的细纲认人。
        assertEquals(setOf("林月", "老周"), s.brief(w.id, c)!!.present.toSet())

        // 存一次（比如填了摘要），没改前六项：细纲改了照样跟着变。
        s.saveCard(w.id, c, p.card.copy(summary = "写完了"))
        s.savePackText(w.id, doc.id, listOutline.replace("钩子：林月问渡口谁管事——老周。", "钩子：渡口起火。"))
        val again = s.plannedCard(w.id, c)
        assertEquals("渡口起火。", again.card.hook)
        assertEquals("写完了", again.card.summary)
        assertNotNull(again.source)

        // 作者改过：按作者的，细纲里的留着可以替换。
        s.saveCard(w.id, c, again.card.copy(hook = "我自己写的钩子"))
        val edited = s.plannedCard(w.id, c)
        assertEquals("我自己写的钩子", edited.card.hook)
        assertNull(edited.source)
        assertEquals("渡口起火。", edited.outline!!.hook)
        val replaced = edited.card.withOutline(edited.outline!!)
        s.saveCard(w.id, c, replaced)
        assertNotNull(s.plannedCard(w.id, c).source)

        // 填了细纲章号：只按章号找。
        s.saveCard(w.id, c, ChapterCard(plan = "二十"))
        assertEquals("灯灭了。", s.plannedCard(w.id, c).card.hook)

        // 找不到：说明按什么找的。
        s.saveCard(w.id, c, ChapterCard())
        s.renameChapter(w.id, c, "第五十章 别处")
        val missing = s.plannedCard(w.id, c)
        assertEquals("按第 50 章、章名「别处」找的", missing.missing)
        assertTrue(missing.card.isEmpty)
        assertFalse(missing.card.auto != 0)
    }
}
