package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.BudgetMiss
import com.xycs.lingyuereader.data.PackBudget
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 设定文档 AI 结果的本机检查：格式（空的、截断的不写）、精简后专名数字的核对、精简默认字数、写入前留历史版本。 */
class PackDocAiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val doc = """
        # 势力
        ## 青云宗
        - 宗主：顾长风，四十二岁。
        - 弟子：三百人，分内外两门。
        **镇河印**是宗门信物。
        | 人物 | 身份 |
        | --- | --- |
        | 杜娘 | 茶馆掌柜 |
        1. 第一条规矩：子时闭门。
        老周说“白沙渡”夜里不能过，罚银 30 两。
    """.trimIndent()

    @Test
    fun keyTermsFindNamesAndNumbers() {
        val terms = PackDocAi.keyTerms(doc, known = listOf("沈默", "顾长风"))
        listOf("青云宗", "宗主", "顾长风", "四十二岁", "三百人", "镇河印", "杜娘", "茶馆掌柜", "白沙渡", "30两").forEach {
            assertTrue("应认出 $it：$terms", it in terms || (it == "30两" && terms.any { t -> t.startsWith("30") }))
        }
        // 表头、正文里没有的人名、列表序号不算。
        assertFalse("人物" in terms)
        assertFalse("沈默" in terms)
        assertFalse(terms.any { it == "1" })
    }

    @Test
    fun missingListsWhatTheCondensedTextDropped() {
        val condensed = "## 青云宗\n- 宗主：顾长风。\n- 三百人。\n**镇河印**是信物。\n| 杜娘 | 茶馆掌柜 |\n白沙渡夜里不能过，罚银 30 两。"
        val lost = PackDocAi.missing(doc, condensed)
        assertTrue("四十二岁" in lost)
        assertFalse("顾长风" in lost)
        assertFalse("镇河印" in lost)
        assertTrue(PackDocAi.missing(doc, doc).isEmpty())
    }

    @Test
    fun emptyOrTruncatedResultsAreNotWritable() {
        assertNotNull(PackDocAi.problem(""))
        assertNotNull(PackDocAi.problem("  \n "))
        assertNotNull(PackDocAi.problem("## 青云宗\n- 宗主：顾长风，"))
        assertNotNull(PackDocAi.problem("## 青云宗\n- 宗主："))
        assertNotNull(PackDocAi.problem("| 人物 | 身份 |\n| --- | --- |\n| 杜娘 | 茶馆"))
        assertNotNull(PackDocAi.problem("```\n## 青云宗"))
        assertNull(PackDocAi.problem(doc))
        assertNull(PackDocAi.problem("| 人物 | 身份 |\n| --- | --- |\n| 杜娘 | 茶馆掌柜 |"))
    }

    @Test
    fun cleanDropsFencesAndOpeners() {
        assertEquals("## 青云宗\n\n- 宗主：顾长风", PackDocAi.clean("好的，以下是起草的文档：\n```markdown\n## 青云宗\n\n- 宗主：顾长风\n```"))
        assertEquals("## 青云宗", PackDocAi.clean("好的，以下是起草的文档：\n\n## 青云宗\n"))
    }

    @Test
    fun blankMeansEmptyOrOnlyTheTemplate() {
        assertTrue(PackDocAi.isBlank("", PackKind.CHARACTER))
        assertTrue(PackDocAi.isBlank(PackImport.template(PackKind.CHARACTER), PackKind.CHARACTER))
        assertFalse(PackDocAi.isBlank("## 林月\n想要：找到父亲", PackKind.CHARACTER))
    }

    private fun budget(misses: List<BudgetMiss>, knowledgeChars: Int = 50_000, knowledgeLimit: Int = 60_000) =
        PackBudget("第一章", emptyList(), 0, 3_000, 6_000, knowledgeChars, knowledgeLimit, misses)

    @Test
    fun condenseTargetFollowsTheBudget() {
        // 放不下：取知识库里还剩的地方。
        assertEquals(10_000, PackDocAi.condenseTarget("人物档案", 30_000, budget(listOf(BudgetMiss("人物档案", PackKind.CHARACTER, 30_000, "", whole = true)))))
        // 截短：取带上的那半截。
        assertEquals(2_400, PackDocAi.condenseTarget("世界设定", 9_000, budget(listOf(BudgetMiss("世界设定 › 地理", PackKind.CANON, 2_400, "", whole = false)))))
        // 放得下、算不出：原长一半。
        assertEquals(1_500, PackDocAi.condenseTarget("总纲", 3_000, budget(emptyList())))
        assertEquals(1_500, PackDocAi.condenseTarget("总纲", 3_000, null))
        // 知识库已经满了：也是一半；再短也不低于下限。
        assertEquals(4_000, PackDocAi.condenseTarget("人物档案", 8_000, budget(listOf(BudgetMiss("人物档案", PackKind.CHARACTER, 8_000, "", whole = true)), knowledgeChars = 60_000)))
        assertEquals(PackDocAi.TARGET_MIN, PackDocAi.condenseTarget("人物档案", 9_000, budget(listOf(BudgetMiss("人物档案", PackKind.CHARACTER, 9_000, "", whole = true)), knowledgeChars = 59_990)))
        // 短文档压一半，默认值不会比原文还长。
        assertEquals(60, PackDocAi.condenseTarget("短", 120, null))
    }

    @Test
    fun writingAResultKeepsTheVersionBeforeInHistory() {
        val store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        val d = store.addPackDoc(w.id, PackKind.CANON, "势力", doc)
        store.saveDocFromAi(w.id, d.id, doc, "## 青云宗\n- 宗主：顾长风。")
        assertEquals("## 青云宗\n- 宗主：顾长风。", store.packText(w.id, d.id))
        val history = store.history(w.id, store.docKey(d.id))
        assertEquals(1, history.size)
        assertEquals(doc, store.snapshotText(w.id, store.docKey(d.id), history.first().at))
        // 撤回也先留一版：两版都在。
        store.saveDocFromAi(w.id, d.id, "## 青云宗\n- 宗主：顾长风。", doc)
        assertEquals(doc, store.packText(w.id, d.id))
        assertEquals(2, store.history(w.id, store.docKey(d.id)).size)
    }
}
