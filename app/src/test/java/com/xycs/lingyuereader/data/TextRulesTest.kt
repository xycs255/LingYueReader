package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

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
    fun slowRegexIsAbandonedInsteadOfHangingForever() {
        // 带反向引用的嵌套重复：在一行几十个字母上要回溯几小时。
        val slow = """^((\w)+\2?)+$"""
        val text = novel + "\nshenmolaozhouduniangguchangfenglinyuexyz!\n"
        val saved = TextRules.timeoutMillis
        TextRules.timeoutMillis = 300
        try {
            fun assertTooSlow(block: () -> Unit) {
                val started = System.nanoTime()
                val error = runCatching(block).exceptionOrNull()
                assertTrue("应当报正则太慢，实际：$error", error is RegexTooSlowException)
                assertTrue("应当在期限后很快放弃", System.nanoTime() - started < 5_000_000_000L)
            }
            assertTooSlow { TextRules.preview(ReplaceRule(slow, regex = true), text) }
            assertTooSlow { TextRules(listOf(ReplaceRule(slow, regex = true))).clean(text) }
            assertTooSlow { TextRules(toc = slow).titleMatcher(text) }
            assertTooSlow { TextRules(toc = slow).titleMatcher()("shenmolaozhouduniangguchangfenglinyuexyz!") }
            // 停用的慢规则不跑；正常的正则不受影响。
            assertEquals(text, TextRules(listOf(ReplaceRule(slow, regex = true, enabled = false))).clean(text))
            assertEquals(3, TextRules.preview(ReplaceRule("""【[^】]+】""", regex = true), text).first)
            // 放弃的那次匹配也停下来了，没有线程一直空转。
            val deadline = System.currentTimeMillis() + 5_000
            fun spinning() = Thread.getAllStackTraces().keys.any { it.name == "regex-guard" && it.state == Thread.State.RUNNABLE }
            while (spinning() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(!spinning())
        } finally {
            TextRules.timeoutMillis = saved
        }
    }

    @Test
    fun customTocWithWholeTextMatchesTheSameLinesAsLineByLine() {
        val bracket = TextRules.TOC_PRESETS.first { it.label == "【章名】" }.pattern
        val rules = TextRules(toc = bracket)
        val byText = TxtParser.parse(novel, rules.titleMatcher(novel))
        assertEquals(TxtParser.parse(novel, rules.titleMatcher()).map { it.title }, byText.map { it.title })
        // 全文里没有的行（去空白的方式不同）也照样判断。
        assertTrue(rules.titleMatcher(novel)("【三 出城】"))
    }

    @Test
    fun previewCountsMatchesWithContext() {
        val (count, samples) = TextRules.preview(ReplaceRule("""【[^】]+】""", regex = true), novel)
        assertEquals(3, count)
        assertEquals("【【楔子】】", samples.first())
        val json = TextRules(listOf(ReplaceRule("a", "b", true, false)), "x").toJson()
        assertEquals(TextRules(listOf(ReplaceRule("a", "b", true, false)), "x"), TextRules.fromJson(json))
    }

    @Test
    fun onlyOneRunawayRegexThreadAtATime() {
        val saved = TextRules.timeoutMillis
        TextRules.timeoutMillis = 100
        val release = AtomicBoolean(false)
        try {
            // 第一个：超时后还停不下来（Android 上本地代码里的正则就是这样；这里用不理会中断的空转代替）。
            val first = runCatching {
                TextRules.guarded("""(林月\s?)+$""", "第一段") { while (!release.get()) LockSupport.parkNanos(1_000_000); 1 }
            }.exceptionOrNull()
            assertTrue("应当报太慢：$first", first is RegexTooSlowException && !first.busy)
            // 第二个：直接报“上一个正则还在跑”，不起线程（块没跑）。
            val ran = AtomicBoolean(false)
            val second = runCatching { TextRules.guarded("""第.+章""", "第二段") { ran.set(true); 2 } }.exceptionOrNull()
            assertTrue("应当报上一个还在跑：$second", second is RegexTooSlowException && second.busy)
            assertEquals("""(林月\s?)+$""", (second as RegexTooSlowException).pattern)
            assertTrue(second.message!!.contains("上一个正则还在跑"))
            assertFalse(ran.get())
            // 分章、净化也一样报，不起线程。
            assertTrue(runCatching { TextRules.preview(ReplaceRule("""【[^】]+】""", regex = true), novel) }.exceptionOrNull().let { it is RegexTooSlowException && it.busy })
            // 第一个跑完以后恢复。
            release.set(true)
            assertEquals(2, TextRules.guarded("""第.+章""", "第二段") { 2 })
            assertEquals(3, TextRules.preview(ReplaceRule("""【[^】]+】""", regex = true), novel).first)
        } finally {
            release.set(true)
            TextRules.timeoutMillis = saved
        }
    }

    @Test
    fun riskyPatternsAreFlaggedBeforeRunning() {
        listOf(
            """(a+)+""", """(a*)*""", """(a+)*""", """(a|a)*""", """(\w+\s?)+$""", """^((\w)+\2?)+$""",
            """(?:\d+)+""", """((ab)+)+""", """(\s*)+""", """(林月|林月)+""", """(?<n>\d+){2,}""", """(?i)(x+)+y""",
        ).forEach { assertNotNull("应当认出：$it", TextRules.risky(it)) }
        // 说明里带着那一段写法。
        assertTrue(TextRules.risky("""^第(\w+\s?)+章""")!!.contains("""(\w+\s?)+"""))
        // 正常的写法不误判：分章预设、净化模板，和常见的几种。
        (TextRules.TOC_PRESETS.map { it.pattern } + com.xycs.lingyuereader.ui.REPLACE_TEMPLATES.map { it.second.pattern }).forEach {
            assertNull("不该误判：$it", TextRules.risky(it))
        }
        listOf(
            """(\d+\.)+""", """(.|\n)*""", """(?:\r?\n)+""", """(a|b)+""", """(?>a+)+""", """(a++)+""", """[(a+)+]""", """\(a+\)+""",
            """第[0-9]+章""", """(第.{1,20}章)+""", """(?i)chapter\s+\d+""", """^\s*(Chapter|Part)\s+\w+""", """(\S+)\s+""", """(本章未完.*)""",
            """(a+){2}""", """(ab?)+""", """(?=a+)+b""", """([ \t]{2,})""", """(\p{L}+[,，])+""", """\x{4E00}+""",
        ).forEach { assertNull("不该误判：$it", TextRules.risky(it)) }
        // 写错的正则不报（另有“正则有误”）。
        assertNull(TextRules.risky("(["))
    }

    @Test
    fun bigBookIsTimedInSmallBatchesNotAsAWhole() {
        // 正常但不算快的写法（每行要回溯一阵，长度的平方级）：整本加起来跑得久，每 2000 行一批很快。
        val toc = """^.*第.*章"""
        val re = TextRules.compile(toc)!!
        val lines = (1..300_000).map { if (it % 1000 == 0) "第${it / 1000}章 渡口" else "林月和顾长风走到第$it 个渡口，杜娘在船头等了很久，老周还没来，雪下得正紧" }
        val text = lines.joinToString("\n")
        lines.forEach { re.containsMatchIn(it) } // 先热一下
        val started = System.nanoTime()
        lines.forEach { re.containsMatchIn(it) }
        val whole = (System.nanoTime() - started) / 1_000_000
        val saved = TextRules.timeoutMillis
        // 期限设成整本耗时的四分之一：整本一次限时会判太慢，按批限时（每批约整本的一百五十分之一）不会。
        TextRules.timeoutMillis = maxOf(whole / 4, 200)
        try {
            val matcher = TextRules(toc = toc).titleMatcher(text)
            assertTrue(matcher("第300章 渡口"))
            assertFalse(matcher(lines[6]))
            assertEquals(300, lines.count(matcher))
        } finally {
            TextRules.timeoutMillis = saved
        }
        // 整段文字一起算的（净化、统计）：每 100 万字多给一份期限。
        assertEquals(TextRules.timeoutMillis * 3, TextRules.limitFor(2_500_000))
        assertEquals(TextRules.timeoutMillis, TextRules.limitFor(999_999))
    }
}
