package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.ui.glass.READER_GLASS_SOLID_ALPHA
import com.xycs.lingyuereader.ui.glass.glassSurfaceAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读玻璃没有模糊时的不透明度、编辑器标记的底色和线形、统计页各本书的条。纯函数，不用起界面。 */
class ReadingGlassAndMarksTest {
    @Test
    fun readerGlassIsNearlyOpaqueWithoutBlur() {
        // 开着模糊照旧透一些（模糊把背后的字糊开了），关了模糊至少 92%，章名和进度不压在透出来的正文上。
        assertEquals(0.62f, glassSurfaceAlpha(0.62f, blurOn = true, solid = READER_GLASS_SOLID_ALPHA), 0f)
        assertTrue(glassSurfaceAlpha(0.62f, blurOn = false, solid = READER_GLASS_SOLID_ALPHA) >= 0.92f)
        // 本来就更不透明的不往回降。
        assertEquals(0.97f, glassSurfaceAlpha(0.97f, blurOn = false, solid = READER_GLASS_SOLID_ALPHA), 0f)
    }

    @Test
    fun editorMarksAreFaintFillsTellApartByLineShape() {
        val warning = Color(0xFFA85500)
        // 底色很淡：浅色 7%、深色 10% 左右（颜色按 8 位存，差一点点）。以前规则 20%、建议 14%。
        assertTrue(markFill(warning, dark = false).alpha <= 0.08f)
        assertTrue(markFill(warning, dark = true).alpha <= 0.105f)
        // 同一条规则第 4 处起只画线不铺底。
        assertTrue(markFill(warning, dark = false, nth = MARK_FILL_REPEATS).alpha > 0f)
        assertEquals(0f, markFill(warning, dark = false, nth = MARK_FILL_REPEATS + 1).alpha, 0f)
        // 墨水屏灰阶下靠线形分：五类写作建议加本机规则，六种线形互不相同。
        val lines = SuggestKind.entries.map { suggestLine(it.name) } + ruleMarkLine
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun statsBarsOnlyHighlightTheTopThree() {
        val c = groupedColors(dark = false, tint = Color(0xFFA94B36))
        assertEquals(c.tint, statsBarColor(0, c))
        assertEquals(c.tint, statsBarColor(STATS_ACCENT_BOOKS - 1, c))
        val rest = statsBarColor(STATS_ACCENT_BOOKS, c)
        assertTrue(rest != c.tint)
        // 淡的那几条仍和底槽分得开，看得出长短。
        assertTrue(contrastRatio(rest, c.fill) > 1.2f)
    }
}
