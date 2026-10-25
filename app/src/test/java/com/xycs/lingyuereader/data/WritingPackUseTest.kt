package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 设定包在长篇里真正用起来：按块挑资料、细纲跨文档合并、上下章分开、导入正文不留杂章。 */
class WritingPackUseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 像长篇总纲那样：开头是说明，分卷速查里每卷一个粗体小标题，下面是列表项；后面的卷写到同一批人物。 */
    private val outline = """
        # 全书总纲

        本文件用于速查，不要求写一章时通读。落笔先用当卷细纲。各卷章内的人名、人数以细纲为准。这一段只是说明，写哪一章都用不上。

        ## 一、写作原则

        - 先写人，再写事。

        ## 四、分卷速查

        **第一卷《渡口》**（三十章）
        - 本卷任务：林月守住渡口，把沈默送过河。
        - 第1章《夜渡》：林月在渡口等船，船没来。
        - 第12、14、20章接续：老周的账本三次出现。
        - 第30章杜娘关门，渡口失守。

        **第二卷《对岸》**（三十一至六十章）
        - 本卷任务：沈默在对岸立足。

        **第七卷《归乡》**
        - 林月与沈默回到渡口，老周已经不在了。
    """.trimIndent()

    @Test
    fun outlinesKeepHalvesApartAndMergeAcrossDocuments() {
        val volume = """
            ## 细纲
            - **第二十九章 雪桥（上）**：林月守桥；钩子：桥塌了一半。
            - **第三十章 雪桥（下）**：沈默过桥；钩子：杜娘关门。
        """.trimIndent()
        val index = "| 章节与日序 | 当章事实与结果 | 后文承接边界 |\n|---|---|---|\n| 第4—12章（原第5—13章） | 老周记账 | 账本不能丢 |\n| 第1章《夜渡》，冬夜 | 船没来，林月折回 | 沈默还没露面 |\n"
        val entries = ChapterOutlines.parse(listOf("卷纲" to volume)).map { it.copy(rank = 0) } +
            ChapterOutlines.parse(listOf("接续索引" to index)).map { it.copy(rank = 1) } +
            ChapterOutlines.parse(listOf("总纲" to outline)).map { it.copy(rank = 2) }

        // 上下章是两章：章名带着“（上）”“（下）”对。
        assertEquals("桥塌了一半。", ChapterOutlines.card(ChapterOutlines.match(entries, 29, 1, "第二十九章 雪桥（上）")).hook)
        assertEquals("杜娘关门。", ChapterOutlines.card(ChapterOutlines.match(entries, 30, 1, "第三十章 雪桥（下）")).hook)
        assertEquals("雪桥（上）", ChapterOutlines.nameOf("第十八章（上） 雪桥"))

        // 不连着的“第12、14、20章”分成三章；表格只认格子开头的章号（第 4 到 12 章，不是到 13 章）。
        assertEquals(listOf(12, 14, 20), entries.filter { "账本三次" in it.text }.map { it.from })
        assertEquals(4..12, entries.first { it.text.contains("老周记账") }.let { it.from..it.to })
        // “第30章杜娘关门”这样不空格的列表项也认，整句都是内容。
        assertTrue(entries.any { it.from == 30 && it.title.isEmpty() && "杜娘关门" in it.text })

        // 同一章在接续索引和总纲里都写了：按章名合在一起，卷纲细纲、接续索引在前，总纲标“另见”。
        val c1 = ChapterOutlines.card(ChapterOutlines.match(entries, 1, 1, "第一章 夜渡"))
        assertTrue(c1.facts.startsWith("船没来，林月折回"))
        assertTrue("【另见《总纲》】林月在渡口等船，船没来。" in c1.facts)
        assertEquals("沈默还没露面", c1.boundary)
    }

    @Test
    fun packUnitsCarryTheVolumeLeadAndRangesAreNotExpanded() {
        val units = PackUnits.split(outline)
        assertTrue(units.any { it.path == listOf("四、分卷速查", "第一卷《渡口》") && "第1章《夜渡》" in it.text })
        assertTrue(units.any { it.path.lastOrNull() == "第七卷《归乡》" && "老周已经不在了" in it.text })
        assertEquals(listOf(12..12, 14..14, 20..20, 1..40), PackRanking.refs("第12、14、20章接续；第一至四十章"))
        // 大范围几乎不算，单独写到这一章最算数。
        assertEquals(0.0, PackRanking.chapterScore(PackRanking.refs("第一至四十章"), 19).first, 0.0)
        assertTrue(PackRanking.chapterScore(PackRanking.refs("第19章"), 19).first > PackRanking.chapterScore(PackRanking.refs("第18至20章"), 19).first)
        assertEquals(setOf(7), PackRanking.volumes("第七卷《归乡》"))
        assertEquals("十二", PackRanking.chinese(12))
    }

    @Test
    fun theBriefTakesTheRightPiecesOfALongOutline() {
        val long = outline + "\n\n" + (1..40).joinToString("\n\n") { "## 附录 $it\n" + "与本章无关的说明。".repeat(40) }
        val docs = listOf(
            PackDoc("o", PackKind.OUTLINE, "全书总纲", 2, true) to long,
            PackDoc("c", PackKind.CHARACTER, "人物", 3, true) to "| 人物 | 想要 |\n|---|---|\n| 林月 | 守住渡口 |\n| 沈默 | 过河 |\n| 老周 | 记账 |\n",
            PackDoc("r", PackKind.CANON, "硬性设定", 3, false) to "## 前三卷口径\n第一至四十章都在冬天。\n\n## 渡船\n渡口只有一条船，夜里不开。",
        )
        val b = ChapterBriefs.build(docs, "林月看着老周。", "林月在渡口等船，船没来。", chapter = 1, volume = 1, query = "林月在渡口等船，船没来，夜里")
        val titles = b.items.map { it.title }
        // 长的常用文档不再截开头的说明。
        assertFalse(b.items.any { "这一段只是说明" in it.text })
        // 第一卷写到第 1 章的那块带上；第七卷（隔了一卷以上，是后文）不带，哪怕写到同一批人物。
        assertTrue(b.items.any { it.title == "全书总纲 › 四、分卷速查 › 第一卷《渡口》" && it.reason == "提到第 1 章" })
        assertFalse(b.items.any { "第七卷" in it.title || "老周已经不在了" in it.text })
        // “第一至四十章”这样的大范围不算写到这一章；和细纲用词重合的“渡船”那节靠用词挑上。
        assertFalse(titles.any { it.endsWith("前三卷口径") })
        assertTrue(b.items.any { it.title == "硬性设定 › 渡船" && it.reason == "和本章细纲相关" })
    }

    @Test
    fun earlierChaptersLendTheirOutlinesAsBackstory() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val v = w.volumes[0].id
        s.renameChapter(w.id, w.chapters[0].id, "第一章 夜渡")
        val (_, two) = s.addChapter(w.id, v, "第二章 对岸")!!
        s.addPackDoc(w.id, PackKind.VOLUME, "卷纲", "- **第一章 夜渡**：船没来，林月折回。\n- **第二章 对岸**：沈默过河。\n")
        val b = s.brief(w.id, two.id)!!
        // 第一章没写摘要：用它的细纲当前情；自己这一章的细纲已经在细纲卡里，不再重复带。
        assertEquals("（按细纲）船没来，林月折回。", b.items.first { it.group == "前情" }.text)
        assertFalse(b.items.any { it.group != "前情" && "沈默过河" in it.text })
    }

    @Test
    fun importingManuscriptsLeavesNoStrayChapters() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val note = "# 已定正文（第六至七章）\n\n> 承上一份，本文件随写作追加。\n\n"
        s.importPack(w.id, listOf(
            PackImport.classify("已定正文_第6-7章.md", note + "## 第六章 雪夜\n他推开门。\n\n## 第七章 来客\n来人了。"),
            PackImport.classify("已定正文_第1-5章.md", "## 第一章 夜渡\n船没来。"),
        ))
        // 文件开头的说明不成章，空白的“第1章”删掉，“第1-5章”排在“第6-7章”前面。
        assertEquals(listOf("第一章 夜渡", "第六章 雪夜", "第七章 来客"), s.get(w.id)!!.chapters.map { it.title })
        // 同名的章再导一次：换成新稿（定稿的先解锁、登记），不重复加。
        s.importPack(w.id, listOf(PackImport.classify("已定正文_第6-7章.md", "## 第六章 雪夜\n他推开门，雪很大。")))
        val work = s.get(w.id)!!
        assertEquals(3, work.chapters.size)
        val six = work.chapters.first { it.title == "第六章 雪夜" }
        assertEquals("他推开门，雪很大。", s.text(w.id, six.id))
        assertTrue(six.locked)
        assertTrue(s.changes(w.id).any { it.kind == ChangeKind.UNLOCK && "重新导入" in it.reason })
    }

    @Test
    fun aiPlansAreReadBackIntoTheCard() {
        val card = WritePrompts.parsePlan(
            "本章事实与结果：林月守桥\n桥塌了一半\n后文承接边界：老周还活着\n视角人物：林月\n倒计时：无\n**章末钩子：**杜娘关门。\n推进的线：明线",
        )
        assertEquals("林月守桥\n桥塌了一半", card.facts)
        assertEquals("老周还活着", card.boundary)
        assertEquals("", card.countdown)
        assertEquals("杜娘关门。", card.hook)
        val prompt = WritePrompts.plan("第三章 来客", "上一章写到船没来。", ChapterCard(facts = "沈默过河"), "第四章 对岸", "")
        assertTrue("下一章「第四章 对岸」已经定下的细纲" in prompt && "章末钩子：" in prompt)
    }
}
