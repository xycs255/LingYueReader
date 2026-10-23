package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    private val novel = """
        【楔子】
        那年冬天下了很大的雪。本章未完，请点击下一页继续阅读
        【一 下山】
        他背着刀下了山。更多好书请访问 www.example.com/abc 谢谢
        【二 进城】
        城门口排着长队。
    """.trimIndent()

    @Test
    fun cleanAppliesLiteralAndRegexRulesAndSkipsBrokenOnes() {
        val rules = TextRules(
            listOf(
                ReplaceRule("本章未完，请点击下一页继续阅读"),
                ReplaceRule("""更多好书请访问\s*\S+\s*谢谢""", regex = true),
                ReplaceRule("([", regex = true), // 写错的正则：跳过
                ReplaceRule("雪", "霜", enabled = false),
            ),
        )
        val cleaned = rules.clean(novel)
        assertTrue(cleaned, "本章未完" !in cleaned && "example" !in cleaned && "很大的雪。" in cleaned)
        assertNotNull(TextRules.error("(["))
        assertNull(TextRules.error("""^第.+章"""))
    }

    @Test
    fun customTocRuleSplitsChaptersTheAutomaticRuleMisses() {
        // 自动识别认不出【一 下山】这种章名。
        assertEquals(1, TxtParser.parse(novel).size)
        val bracket = TextRules.TOC_PRESETS.first { it.label == "【章名】" }.pattern
        val chapters = TxtParser.parse(novel, TextRules(toc = bracket).titleMatcher())
        assertEquals(listOf("【楔子】", "【一 下山】", "【二 进城】"), chapters.map { it.title })
        // 只看短行：正文里的长句即便匹配也不当章名。
        val long = "【" + "很长的一句话".repeat(20) + "】"
        assertTrue(!TextRules(toc = bracket).titleMatcher()(long))
    }

    @Test
    fun presetsMatchTheirFormats() {
        fun matches(label: String, line: String) = TextRules(toc = TextRules.TOC_PRESETS.first { it.label.startsWith(label) }.pattern).titleMatcher()(line)
        assertTrue(matches("第X章", "第十二章 风起"))
        assertTrue(matches("第X卷", "第三卷 江湖"))
        assertTrue(matches("数字序号", "012. 再会"))
        assertTrue(matches("数字序号", "3、归来"))
        assertTrue(matches("Chapter", "Chapter Seven"))
        assertTrue(matches("单独一行", "雪夜"))
        assertTrue(!matches("单独一行", "他笑了。"))
    }

    @Test
    fun previewCountsMatchesWithContext() {
        val (count, samples) = TextRules.preview(ReplaceRule("""【[^】]+】""", regex = true), novel)
        assertEquals(3, count)
        assertEquals("【【楔子】】", samples.first())
        val json = TextRules(listOf(ReplaceRule("a", "b", true, false)), "x").toJson()
        assertEquals(TextRules(listOf(ReplaceRule("a", "b", true, false)), "x"), TextRules.fromJson(json))
    }
}
