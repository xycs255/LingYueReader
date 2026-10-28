package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color
import com.xycs.lingyuereader.ai.SuggestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建议的颜色按配色记住：每打一个字，正文标记和问题面板每一行都要取一次；以前每次现算，
 * AI 腔、口吻两类要试几十个色相，自选青色强调色时一次七百多微秒。
 */
class SuggestColorCacheTest {
    @Test
    fun theSameThemeIsComputedOnce() {
        val teal = groupedColors(false, Color(0xFF00796B))
        val purple = groupedColors(true, Color(0xFF6A1B9A))
        // 先各取一次（第一次要算），再量取一千次。
        SuggestKind.entries.forEach { suggestColor(it.name, teal); suggestColor(it.name, purple) }
        val t = System.nanoTime()
        repeat(500) { SuggestKind.entries.forEach { k -> suggestColor(k.name, teal); suggestColor(k.name, purple) } }
        val ms = (System.nanoTime() - t) / 1_000_000
        println("取 5000 次建议颜色：$ms 毫秒（改前约 500 毫秒：青色强调色下一次 AI 腔约 740 微秒）")
        assertTrue("取 5000 次 $ms 毫秒", ms < 100)
    }

    @Test
    fun cachedColorsAreTheSameAndFollowTheTheme() {
        val light = groupedColors(false, Color(0xFF00796B))
        val dark = groupedColors(true, Color(0xFF00796B))
        val first = SuggestKind.entries.map { suggestColor(it.name, light) }
        assertEquals(first, SuggestKind.entries.map { suggestColor(it.name, light.copy()) })
        // 换了主题就是另一套。
        assertTrue(SuggestKind.entries.map { suggestColor(it.name, dark) } != first)
        // 写作建议的几类各不相同。
        assertEquals(SuggestKind.entries.size, first.toSet().size)
    }
}
