package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.SampleChapter
import com.xycs.lingyuereader.data.SampleOptions
import com.xycs.lingyuereader.data.StyleSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * 写正文卡不卡，量几样（虚构的样章拼成 5000 字、20000 字的长章节）：
 * - 本机检查（AI 腔清单加检查规则）对全文跑一遍要多久：编辑器停笔就跑；新的“叙述短段”“盯着……看了一会儿”不能回溯爆炸；
 * - 流式出字时清理正文：以前每来一块都把全文重新清理一遍（平方级），现在边收边清理；
 * - 挑文风范例（30 章定稿）一次要分配多少内存。
 * 数字打印出来（回报里改前改后对比用）；断言只卡住数量级，机器忙的时候也不误报。
 */
class ProseStreamPerfTest {
    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    private val source by lazy { res("ch1.txt") + "\n" + res("ch2.txt") + "\n" + res("ch3_beats.txt").replace("=====", "") }

    private fun chapter(n: Int) = buildString { while (length < n) append(source).append('\n') }.take(n)

    @Test
    fun checkingALongChapterStaysFast() {
        val rules = AiCliche.builtin + CheckRules.parse(res("pack/检查规则.md"), "检查规则")
        for (n in listOf(5_000, 20_000)) {
            val text = chapter(n)
            repeat(5) { CheckRules.check(text, rules) }
            val t = System.nanoTime()
            repeat(20) { CheckRules.check(text, rules) }
            val us = (System.nanoTime() - t) / 20 / 1_000
            println("本机检查 $n 字：每次 $us 微秒")
            assertTrue("本机检查 $n 字要 $us 微秒", us < 400_000)
        }
    }

    @Test
    fun cleaningWhileStreamingIsLinear() {
        for (n in listOf(5_000, 20_000)) {
            val pieces = chapter(n).chunked(3)
            // 改前：每来一块都对收到的全文 cleanDraft 一遍。
            val t0 = System.nanoTime()
            var all = ""
            var before = ""
            pieces.forEach { all += it; before = WritePrompts.cleanDraft(all) }
            val oldMs = (System.nanoTime() - t0) / 1_000_000
            // 改后：边收边清理，每块只问一句“有字了没有”，全文按节流拼（这里每 50 块拼一次，比 150 毫秒一次还勤）。
            val t1 = System.nanoTime()
            val stream = WritePrompts.DraftStream()
            pieces.forEachIndexed { i, p ->
                stream.append(p)
                if (stream.hasText && i % 50 == 0) stream.text()
            }
            val after = stream.text()
            val newMs = (System.nanoTime() - t1) / 1_000_000
            println("流式清理 $n 字（${pieces.size} 块）：改前 $oldMs 毫秒，改后 $newMs 毫秒")
            assertEquals(before, after)
            if (n == 20_000) assertTrue("改前 $oldMs 毫秒、改后 $newMs 毫秒", newMs * 4 < oldMs.coerceAtLeast(40))
        }
    }

    @Test
    fun pickingStyleSamplesDoesNotFloodMemory() {
        val paragraphs = source.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val chapters = (0 until 30).map { i ->
            SampleChapter("c$i", "第${i + 1}章", buildString { var k = i * 3; while (length < 4_000) append(paragraphs[k++ % paragraphs.size]).append('\n') })
        }
        val avoid = "卯时林月到渡口，老周的船拴在桩上但船上没人；杜娘说昨夜顾长风带人来过；沈默赶来，两人在船舱夹板里找到另半张药方。"
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(2) { StyleSamples.pick(chapters, SampleOptions(limit = 2_500, avoid = avoid)) }
        val a = mx.getThreadAllocatedBytes(id)
        val t = System.nanoTime()
        val picked = StyleSamples.pick(chapters, SampleOptions(limit = 2_500, avoid = avoid))
        val mb = (mx.getThreadAllocatedBytes(id) - a) / 1_000_000
        println("挑文风范例（30 章、避开本章细纲）：${(System.nanoTime() - t) / 1_000_000} 毫秒，分配 $mb MB")
        assertTrue(picked.isNotEmpty())
        assertTrue("挑一次分配 $mb MB", mb < 120)
    }
}
