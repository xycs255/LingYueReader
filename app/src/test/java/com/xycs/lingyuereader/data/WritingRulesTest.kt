package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingRulesTest {
    private fun rules(vararg lines: String) = CheckRules.parse(lines.joinToString("\n"), "检查规则")

    private fun messages(text: String, vararg lines: String) = CheckRules.check(text, rules(*lines)).hits.map { it.message }

    @Test
    fun linesAreReadIntoRulesAndTheRestIsReported() {
        val set = CheckRules.parse(
            """
            # 检查规则
            # 说明行不参与检查
            - 字数：3000-6000
            汉字：三千五至六千
            1. 每章至多两次：像是
            至多 1：点了点头、顿了顿／一顿、大概（除外：大概在今天）
            合计至多一次：没说话、没吭声
            至少 1：实际上
            禁用称谓：您、公公
            句式：不是……而是……
            对白禁用：社死
            叙述短段：12 字以内至多 5 处
            长段：300 字以上
            对白占比：20%-40%
            禁用：
            旁白要有人格
            字数：很多
            > 引用不算
            """.trimIndent(),
            "规范",
        )
        assertEquals(
            listOf(
                "每章 3000–6000 字",
                "每章 3500–6000 个汉字",
                "每章各至多 2 次：像是",
                "每章各至多 1 次：点了点头、顿了顿／一顿、大概（大概在今天不算）",
                "每章合计至多 1 次：没说话、没吭声",
                "每章各至少 1 次：实际上",
                "不用：您、公公",
                "不用：不是……而是……",
                "对白里不用：社死",
                "叙述里 12 字以内的段，每章至多 5 处",
                "300 字以上的段，每章至多 0 处",
                "对白占全章 20%–40%",
            ),
            set.rules.map(CheckRules::describe),
        )
        // 留空的“禁用：”是模板里的空位，不算错；看不懂的两行列出来，带行号。
        assertEquals(listOf("旁白要有人格" to 16, "字数：很多" to 17), set.unknown.map { it.text to it.line })
        assertEquals("规范", set.rules.first().source.doc)
        // 模板本身每一行都读得懂。
        assertTrue(CheckRules.parse(CheckRules.TEMPLATE).unknown.isEmpty())
    }

    @Test
    fun chineseNumbers() {
        assertEquals(listOf(12, 2, 3500, 6000, 3005, 10000, 25), listOf("十二", "两", "三千五", "六千", "三千零五", "一万", "２５").map { CheckRules.cn(it) })
        assertNull(CheckRules.cn("很多"))
    }

    @Test
    fun wildcardsStayInsideASentence() {
        val look = CheckRules.pattern("看了某人一眼")
        assertTrue(look.containsMatchIn("他看了老周一眼。"))
        assertFalse(look.containsMatchIn("他看了，一眼就认出来"))
        val notBut = CheckRules.pattern("不是……而是……")
        assertEquals("不是他不想，而是", notBut.find("不是他不想，而是不能。")?.value)
        assertFalse(notBut.containsMatchIn("不是他。而是我"))
    }

    @Test
    fun wordsAreCountedPerChapterWithFormsExceptionsAndScopes() {
        val text = "他点了点头。老周顿了顿，又一顿。\n“您先请。”她说，“社死了。”\n大概在今天的孟津。大概三更。\n社死的是他。"
        assertEquals(
            listOf(
                "「顿了顿／一顿」2 次，至多 1",
                "对白里「社死」1 处，不用",
                "「您」1 处，不用",
            ),
            messages(text, "至多 1：点了点头、顿了顿／一顿、大概（除外：大概在今天）", "对白禁用：社死", "禁用：您、公公"),
        )
        // 合起来数：两个词各一次，合计两次。
        assertEquals(
            listOf("「没说话」、「没吭声」 合计 2 次，至多 1"),
            messages("他没说话。她也没吭声。", "合计至多 1：没说话、没吭声"),
        )
        assertEquals(listOf("「实际上」0 次，至少 1"), messages("他饿了。", "至少 1：实际上"))
        // 叙述里才查：引号里的不算。
        assertEquals(emptyList<String>(), messages("“你看了我一眼。”", "叙述至多 0：看了某人一眼"))
    }

    @Test
    fun hitsPointAtTheTextAndKeepTheirKeyWhenCountsChange() {
        val set = rules("至多 1：顿了顿")
        val a = CheckRules.check("顿了顿。顿了顿。", set).hits.single()
        assertEquals(listOf(0..2, 4..6), a.ranges)
        val b = CheckRules.check("顿了顿。顿了顿。顿了顿。", set).hits.single()
        assertEquals(a.key, b.key)
        assertTrue(a.message != b.message)
    }

    @Test
    fun lengthParagraphsAndDialogueRatio() {
        val text = "好。\n“走吧。”他站起来，拍了拍衣服上的土，往门外走去，脚步比来时轻快了许多。\n停。"
        assertEquals(
            listOf("本章 ${WordCount.of(text)} 字，少于 3000", "叙述里 3 字以内的段 2 处，至多 1"),
            messages(text, "字数：3000-6000", "叙述短段：3 字以内至多 1 处"),
        )
        // 只数汉字：标点不算。
        assertEquals(listOf("本章 5 个汉字，超过 3"), messages("他来了。我走。", "汉字：至多 3"))
        assertEquals(listOf("对白占 100%，超过 40%"), messages("“走吧。”", "对白占比：20%-40%"))
        val s = CheckRules.stats("他来了。\n“走吧。”\n天黑了，风也大了。")
        assertEquals(3, s.paragraphs)
        assertEquals(5, s.medianParagraph)
        assertEquals(5 * 100 / s.words, s.dialoguePercent)
    }

    @Test
    fun rulesDocumentsAreAKindOfTheirOwn() {
        assertEquals(PackKind.RULES, PackImport.classify("检查规则.md").kind)
        assertFalse(PackKind.RULES.always)
        assertEquals(CheckRules.TEMPLATE, PackImport.template(PackKind.RULES))
        // 写作规范还是写作规范。
        assertEquals(PackKind.STYLE, PackImport.classify("正文写作约定.md").kind)
    }

    @Test
    fun theAiPromptAsksForTheRuleFormatAndTheAnswerIsCleaned() {
        val prompt = WritePrompts.rules("【写作规范】\n每章至多一次“点了点头”。")
        assertTrue("每章至多一次“点了点头”" in prompt)
        assertTrue("至多 1：词甲、词乙" in prompt)
        assertTrue("只输出规则文本" in prompt)
        assertEquals("至多 1：点了点头\n", WritePrompts.cleanRules("```text\n至多 1：点了点头\n```"))
    }
}
