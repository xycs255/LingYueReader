package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritingBriefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val characters = """
        # 人物档案

        ## 核心人物卡

        | 人物 | 想要 | 害怕 |
        |---|---|---|
        | 林月（原创，药铺女儿） | 一个家 | 被人丢下 |
        | 沈默 | 查清旧案 | 连累别人 |

        ## 配角

        - **老周（原创，门房）：** 话少，左手缺两根指头。
          - 第九章战死。
        - **苏九**，小名阿九，爱笑。
        - **锋芒与越线：** 这是写法说明，不是人物。

        ## 杜娘
        开茶馆的寡妇，消息灵通。
    """.trimIndent()

    private val awareness = """
        # 察觉表

        ### 林月（玉佩线）

        | 卷 | 看到的线索 | 判断 |
        | --- | --- | --- |
        | 一 | 沈默腰间有半块玉佩 | 只觉得眼熟 |
        | 一（第五章） | 杜娘说出玉佩来历 | 知道玉佩是旧案物证 |
        | 二 | 沈默亮出另一半 | 确认他是旧案遗孤 |

        ## 其他
        1. **第一卷，老周：** 第二章起知道林月夜里出门。
    """.trimIndent()

    private val foreshadow = """
        | 伏笔 | 埋下 | 回收 | 状态 |
        | --- | --- | --- | --- |
        | 半块玉佩 | 第1章 | 第3章 | |
        | 老周的断指 | 第2章 | 第9章 | |
        | 门闩刀痕 | 第1章 | 第2章 | 已回收 |

        - 杜娘的账本：第2章埋，第12章回收
    """.trimIndent()

    @Test
    fun chapterReferencesInManyForms() {
        assertEquals(listOf(12), ChapterRefs.find("见第十二章"))
        assertEquals((12..15).toList(), ChapterRefs.find("第12—15章"))
        assertEquals(listOf(6, 17), ChapterRefs.find("白水碗（第六、十七章）"))
        assertEquals(listOf(3), ChapterRefs.find("第一卷第三章"))
        assertEquals(listOf(18), ChapterRefs.find("第十八章（上）"))
        assertEquals(listOf(29, 30), ChapterRefs.find("第二十九至三十章"))
        assertEquals(12, ChapterRefs.number("第十二章 雪夜"))
        assertNull(ChapterRefs.number("楔子"))
    }

    @Test
    fun tablesAndSections() {
        val tables = MdTable.parse(characters)
        assertEquals(listOf("人物", "想要", "害怕"), tables.single().header)
        assertEquals("沈默", tables.single().rows[1][0])
        val line = tables.single().lines[0]
        assertTrue("| 林月（原创，药铺女儿） | 一个家 | 改了 |" in MdTable.setCell(characters, line, 2, "改了"))
        val sections = PackSections.split(characters)
        assertEquals(listOf("核心人物卡", "配角", "杜娘"), sections.map { it.title })
        // 太长的节按空行切成几块，标题跟着走。
        val long = "## 长节\n" + (1..5).joinToString("\n\n") { "第${it}段" + "字".repeat(300) }
        assertTrue(PackSections.split(long, chunk = 700).let { s -> s.size >= 3 && s.all { it.title == "长节" } })
    }

    @Test
    fun charactersFromTablesBulletsAndHeadings() {
        val found = ChapterBriefs.characters(listOf("人物档案" to characters))
        assertEquals(listOf("林月", "沈默", "杜娘", "老周", "苏九"), found.map { it.name })
        assertEquals(listOf("阿九"), found.first { it.name == "苏九" }.aliases)
        assertTrue(found.first { it.name == "林月" }.notes.single().contains("想要：一个家"))
        // 子项跟着人物走。
        assertTrue("第九章战死" in found.first { it.name == "老周" }.notes.single())
        assertEquals(2, ChapterBriefs.mentions(found.first { it.name == "苏九" }, "苏九来了，阿九笑了。"))
    }

    @Test
    fun awarenessRowsKnowWhenAndWho() {
        val items = ChapterBriefs.knowledge(listOf("察觉表" to awareness), listOf("林月", "沈默", "老周", "杜娘"))
        assertEquals(listOf(1 to null, 1 to 5, 2 to null, 1 to 2), items.map { it.volume to it.chapter })
        assertTrue(items.first().who.containsAll(setOf("林月", "沈默")))
        assertTrue(items.first().text.startsWith("林月，第1卷："))
        assertEquals(setOf("老周", "林月"), items.last().who)
    }

    @Test
    fun foreshadowsAreReadAndMarkedDone() {
        val list = ChapterBriefs.foreshadows(listOf("伏笔表" to foreshadow))
        assertEquals(
            listOf(Triple("半块玉佩", 3, false), Triple("老周的断指", 9, false), Triple("门闩刀痕", 2, true), Triple("杜娘的账本", 12, false)),
            list.map { Triple(it.what, it.payoff, it.done) },
        )
        val table = ChapterBriefs.markDone(foreshadow, list[0], 3)
        assertTrue("| 半块玉佩 | 第1章 | 第3章 | 已回收（第3章） |" in table)
        assertTrue(ChapterBriefs.foreshadows(listOf("x" to table))[0].done)
        val bullet = ChapterBriefs.markDone(foreshadow, list[3], null)
        assertTrue("- 杜娘的账本：第2章埋，第12章回收（已回收）" in bullet)
    }

    @Test
    fun theBriefPicksWhatThisChapterNeeds() {
        val docs = listOf(
            PackDoc("o", PackKind.OUTLINE, "总纲", 1, true) to "主线：查清旧案。",
            PackDoc("c", PackKind.CHARACTER, "人物档案", 3, true) to characters,
            PackDoc("a", PackKind.AWARENESS, "察觉表", 4, false) to awareness,
            PackDoc("f", PackKind.FORESHADOW, "伏笔表", 4, false) to foreshadow,
            PackDoc("v", PackKind.VOLUME, "第一卷细纲", 2, false) to "## 第3章 来客\n沈默第一次见到林月。\n\n## 第8章 夜雨\n老周守夜。",
        )
        val b = ChapterBriefs.build(docs, "林月推开门。", "视角：林月", chapter = 3, volume = 1)
        assertEquals(listOf("林月"), b.present)
        val titles = b.items.map { it.group to it.title }
        assertEquals("总纲" to "总纲", titles.first())
        assertTrue("人物" to "林月" in titles)
        // 没出场的人物不带；整份人物文档也不带。
        assertFalse(titles.any { it.second == "沈默" || it.second == "人物档案" })
        // 第五章以后才知道的，列为“还不该知道”；第 3 章细纲带上，第 8 章的不带。
        val known = b.items.first { it.group == "察觉" }.text
        assertTrue("只觉得眼熟" in known)
        assertFalse("知道玉佩是旧案物证" in known)
        assertTrue(b.notYet.any { "知道玉佩是旧案物证" in it })
        assertTrue(b.items.any { it.title == "第一卷细纲 › 第3章 来客" && it.reason == "提到第 3 章" })
        assertFalse(b.items.any { "夜雨" in it.title })
        assertEquals(listOf("半块玉佩"), b.due.map { it.what })
        assertEquals(listOf("半块玉佩", "老周的断指", "杜娘的账本"), b.open.map { it.what })
        val text = b.render()!!
        assertTrue("【计划在这一章回收的伏笔】\n- 半块玉佩（第1章埋下，计划第3章回收）" in text)
        assertTrue("【到这一章为止还没发生、人物还不知道的事" in text)
        // 正文里还认不出人物时，常用的人物文档整份带上。
        assertTrue(ChapterBriefs.build(docs, "", "", 1, 1).items.any { it.title == "人物档案" })
    }

    @Test
    fun theStoreNumbersChaptersAndKeepsReviews() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c1 = w.chapters[0].id
        val (_, two) = s.addChapter(w.id, w.volumes[0].id)!!
        s.renameChapter(w.id, two.id, "第十二章 雪夜")
        val work = s.get(w.id)!!
        assertEquals(1, work.chapterNumber(c1))
        assertEquals(12, work.chapterNumber(two.id))
        assertEquals(1, work.volumeNumber(two.id))
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", characters)
        s.saveText(w.id, two.id, "杜娘端来茶。")
        assertEquals(listOf("杜娘"), s.brief(w.id, two.id)!!.present)

        val review = ChapterReview(42, 7, listOf(GateResult(ReviewGate.FACT, listOf(ReviewIssue("杜娘端来茶", "杜娘这时在城外", "改成伙计")), at = 5), GateResult(ReviewGate.CRAFT, error = "跳过")))
        s.saveReview(w.id, two.id, review)
        assertEquals(review, s.review(w.id, two.id))
        s.deleteChapter(w.id, two.id)
        assertNull(s.review(w.id, two.id))
    }

    @Test
    fun reviewPromptsAndAnswers() {
        val fact = WritePrompts.factReview("正文", "第三章", "上一章的结尾")
        assertTrue("上一章结尾：\n上一章的结尾" in fact)
        assertTrue("人物知道了这时还不该知道的事" in fact)
        val craft = WritePrompts.craftReview("正文", "第三章", "【写作规范】\n少用成语")
        assertTrue("只按上面的写作规范" in craft && "少用成语" in craft)
        assertEquals(
            listOf(ReviewIssue("林月早就知道", "林月第五章才知道", "改成觉得眼熟")),
            WritePrompts.parseReview("好的：\n```json\n{\"issues\":[{\"quote\":\"“林月早就知道”\",\"problem\":\"林月第五章才知道\",\"fix\":\"改成觉得眼熟\"},{\"quote\":\"x\",\"problem\":\"\"}]}\n```"),
        )
        assertNull(WritePrompts.parseReview("没有问题"))
        assertEquals(PackKind.FORESHADOW, PackImport.classify("伏笔表.md").kind)
    }

    @Test
    fun laterFactsAndSecretsAreKeptApartFromTheCards() {
        val people = """
            | 人物 | 想要 | 结局 | 怎么变 |
            |---|---|---|---|
            | 林月 | 一个家 | 第九章嫁给沈默 | 从怕事到敢说 |
            - **老周（门房）：** 话少。第二章起守夜。第九章战死。
        """.trimIndent()
        val seen = """
            ### 林月
            | 卷 | 线索 | 沈默这一面 |
            | --- | --- | --- |
            | 一 | 看见玉佩 | 他早认出了林月 |
        """.trimIndent()
        val docs = listOf(
            PackDoc("c", PackKind.CHARACTER, "人物", 3, false) to people,
            PackDoc("a", PackKind.AWARENESS, "察觉表", 4, false) to seen,
        )
        val b = ChapterBriefs.build(docs, "林月看着老周。", "", chapter = 3, volume = 1)
        val lin = b.items.first { it.title == "林月" }.text
        assertTrue("想要：一个家" in lin)
        assertFalse("结局" in lin || "嫁给" in lin || "怎么变" in lin)
        val zhou = b.items.first { it.title == "老周" }.text
        assertTrue("第二章起守夜" in zhou)
        assertFalse("战死" in zhou)
        val known = b.items.first { it.group == "察觉" }.text
        assertTrue("看见玉佩" in known)
        assertFalse("早认出" in known)
        assertTrue(b.hidden.any { "结局：第九章嫁给沈默" in it })
        assertTrue(b.hidden.any { it.startsWith("老周——") && "第九章战死" in it })
        assertTrue(b.hidden.any { "沈默这一面：他早认出了林月" in it })
        assertTrue("【只有作者知道的" in b.render()!!)
        assertEquals("他来了。" to "第九章他走了。", ChapterBriefs.splitByChapter("他来了。第九章他走了。", 3))
    }

    @Test
    fun earlierChaptersComeInAsSummaries() {
        val docs = listOf(PackDoc("c", PackKind.CHARACTER, "人物", 3, false) to characters)
        val summaries = (1..9).map { ChapterSummary(it, "第${it}章", "第${it}章发生的事" + if (it == 2) "，杜娘收留了林月" else "") }
        val b = ChapterBriefs.build(docs, "杜娘开了门。", "", chapter = 10, volume = 1, summaries = summaries)
        val past = b.items.filter { it.group == "前情" }
        // 最近 5 章都带；更早的只带写到出场人物的那章。
        assertEquals(listOf(9, 8, 7, 6, 5, 2), past.map { it.title.substringAfter("第 ").substringBefore(" 章").toInt() })
        assertEquals("写到杜娘", past.last().reason)
        // 后面的章不算前情。
        assertTrue(ChapterBriefs.build(docs, "", "", chapter = 1, volume = 1, summaries = summaries).items.none { it.group == "前情" })
    }

    @Test
    fun summariesAndDraftsAreStored() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c1 = w.chapters[0].id
        val (_, two) = s.addChapter(w.id, w.volumes[0].id)!!
        s.saveCard(w.id, c1, ChapterCard(facts = "进城", summary = "沈默进了城，在杜娘的茶馆落脚。"))
        assertEquals("沈默进了城，在杜娘的茶馆落脚。", s.card(w.id, c1).summary)
        assertTrue(s.card(w.id, c1).copy(facts = "").isEmpty)
        // 摘要随备份走。
        val b = WorkStore(tmp.newFolder())
        b.restoreBackup(String(s.backup()!!))
        assertEquals("沈默进了城，在杜娘的茶馆落脚。", b.card(w.id, c1).summary)
        // 本章资料带上前一章的摘要。
        s.addPackDoc(w.id, PackKind.OUTLINE, "总纲", "主线。", priority = 1)
        assertTrue(s.brief(w.id, two.id)!!.items.any { it.group == "前情" && "茶馆" in it.text })

        s.saveDraft(w.id, two.id, "草稿第一段。")
        assertEquals("草稿第一段。", s.draft(w.id, two.id))
        s.saveDraft(w.id, two.id, "")
        assertEquals("", s.draft(w.id, two.id))
        s.saveDraft(w.id, two.id, "又一版。")
        s.deleteChapter(w.id, two.id)
        assertEquals("", s.draft(w.id, two.id))
    }

    @Test
    fun answersThatAreNotTheRightShapeAreNotIgnored() {
        assertNull(WritePrompts.parseReview("这一章写得不错，没什么问题。"))
        assertNull(WritePrompts.parseReview("{\"result\":\"ok\"}"))
        assertEquals(emptyList<ReviewIssue>(), WritePrompts.parseReview("{\"issues\":[]}"))
        assertEquals(
            "灯下，林月看着玉佩。\n“你回来了。”",
            WritePrompts.cleanDraft("```\n第三章 玉佩\n\n　　灯下，林月看着玉佩。\n\n“你回来了。”\n（本章完）\n```"),
        )
        val draft = WritePrompts.draft("第三章", "上一章的结尾", "【检查规则】\n字数：3000-6000", "3000 到 6000 字", "多写对白")
        assertTrue("上一章结尾：\n上一章的结尾" in draft && "篇幅3000 到 6000 字" in draft && "作者这次的要求：多写对白" in draft)
        assertTrue("请从最后一句接着往下写" in WritePrompts.draftMore("第三章", "已经写了的。", "", "3000 到 6000 字"))
        assertTrue("150 到 300 字" in WritePrompts.summary("正文", "第三章"))
    }
}
