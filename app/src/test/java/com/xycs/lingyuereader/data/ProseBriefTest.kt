package com.xycs.lingyuereader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 写正文时带的本章资料：不带后面章节的细纲、后面卷的细纲、全书结局和“只有作者知道的”；别的路径照旧。 */
class ProseBriefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val volume = """
        # 第一卷 雾渡 细纲
        本卷四十章，卷末老周被带走。
        ### 第3章 空船
        事实：林月到渡口，船上没人；沈默赶来。
        ### 第4章 盐引
        事实：白先生在黑水湾换走账本。
        ### 第10章 夜航
        事实：顾长风在夜里截住沈默。
    """.trimIndent()
    private val second = "# 第二卷 漕影 细纲\n### 第50章 盐账\n事实：顾北山套出林月的腰牌，有人被沉河。\n"
    private val outline = "# 总纲\n## 主线\n林月追查父亲失踪。\n## 结局\n林守仁为保女儿自愿入狱，沈默留在青石镇。\n"
    private val canon = """
        # 世界设定
        ## 铁锁关沉船案
        十年前一艘官盐船在铁锁关沉没，三十七人遇难，其中有杜娘的丈夫。此案是全书最后的谜底。第12章顾长风在渡口叛帮。
        ## 漕帮
        漕帮总舵在白沙渡，见玉如见少当家；杜娘的茶馆在渡口边上。
    """.trimIndent()
    private val people = """
        ## 林月
        - 想要：找到父亲。
        - 变化：从只想找爹，到为整个镇子提灯。
        ## 老周
        - 说话：先咳一声。第九章战死。
        ## 沈默
        - 说话：半句话。
        ## 杜娘
        - 说话：先损人再帮人。
    """.trimIndent()
    private val seen = """
        | 章 | 人物 | 知道什么 |
        | --- | --- | --- |
        | 第2章 | 林月 | 老周约她卯时见 |
        | 第20章 | 林月 | 沈默是朝廷暗探 |
        | 第21章 | 林守仁 | 老周还活着 |
    """.trimIndent()

    private val docs = listOf(
        PackDoc("o", PackKind.OUTLINE, "总纲") to outline,
        PackDoc("v1", PackKind.VOLUME, "第一卷细纲") to volume,
        PackDoc("v2", PackKind.VOLUME, "第二卷细纲") to second,
        PackDoc("w", PackKind.CANON, "世界设定") to canon,
        PackDoc("c", PackKind.CHARACTER, "人物档案") to people,
        PackDoc("a", PackKind.AWARENESS, "察觉表") to seen,
    )
    private val card = "林月到渡口，杜娘说老周走了，沈默赶来。铁锁关的旧事。"

    @Test
    fun proseMaterialStopsAtThisChapter() {
        val b = ChapterBriefs.build(docs, "", card, chapter = 3, volume = 1, ahead = false)
        val text = b.render(prose = true)!!
        // 后面章节的细纲、后面卷的细纲、全书结局都不在。
        listOf("白先生在黑水湾换走账本", "顾长风在夜里截住沈默", "顾北山套出林月的腰牌", "自愿入狱", "卷末老周被带走").forEach { assertFalse(it, it in text) }
        // 设定里写到后面的句子、写谜底的句子、人物后来怎样的条目去掉，其余留着。
        assertTrue("三十七人遇难" in text)
        assertFalse("全书最后的谜底" in text)
        assertFalse("第12章顾长风在渡口叛帮" in text)
        assertTrue("想要：找到父亲" in text)
        assertFalse("为整个镇子提灯" in text)
        assertFalse("战死" in text)
        // 只有作者知道的（结局、后文走向）不带。
        assertTrue(b.hidden.isEmpty())
        assertFalse("只有作者知道" in text)
        // 人物还不知道的：只说出场人物的，不带章号。
        assertTrue("林月还不知道：沈默是朝廷暗探" in text)
        assertFalse("老周还活着" in text)
        assertFalse("第20章" in text)
        // 开头一句只说这是什么资料，不讲“以前面的为准”。
        assertTrue(text.startsWith("写第 3 章用得上的资料"))
        assertFalse("以前面的为准" in text)
    }

    @Test
    fun otherPathsStillSeeEverything() {
        val b = ChapterBriefs.build(docs, "", card, chapter = 3, volume = 1)
        val text = b.render()!!
        assertTrue("以前面的为准" in text)
        assertTrue("第20章" in text)
        assertTrue(b.hidden.any { "第九章战死" in it })
    }

    @Test
    fun theStoreBuildsProseMaterialFromTheCreateStageOnly() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        s.addChapter(w.id, w.volumes[0].id)
        val (_, three) = s.addChapter(w.id, w.volumes[0].id)!!
        s.renameChapter(w.id, three.id, "第3章 空船")
        docs.forEach { (d, t) -> s.addPackDoc(w.id, d.kind, d.title, t) }
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "章节审读提示词", "审读时核对出处。")
        s.saveCard(w.id, three.id, ChapterCard(facts = card, beats = "林月到渡口\n杜娘开门"))
        val prose = s.brief(w.id, three.id, "", limit = 6_000, prose = true)!!.render(prose = true)!!
        assertFalse("审读时核对出处" in prose)
        assertFalse("白先生在黑水湾换走账本" in prose)
        assertTrue("杜娘" in prose)
        // 不写正文的路径照旧拿到全部。
        assertTrue("第20章" in s.brief(w.id, three.id)!!.render()!!)
    }
}
