package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页的语义色（强调色底上的字、提醒、成功）在各种底上都要读得清：普通小字 4.5:1（WCAG AA）。 */
class SemanticColorsTest {
    /** 几种强调色：书页自己的、miuix 默认蓝、深色 Monet 那种浅粉、很浅的黄、深绿、琥珀。 */
    private val tints = listOf(
        Color(0xFF3482FF), Color(0xFFFFB1C8), Color(0xFFFFD54F), Color(0xFF1B5E20), Color(0xFFE0905F), Color(0xFF9CC3F0),
    )

    private val pages = ReaderTheme.entries.map { it.palette() } +
        ReaderTheme.entries.map { it.palette().adjusted(dim = 0.88f, warmth = 1f) } +
        customPalette(Color(0xFFDDE7EE), Color(0xFF1F2D3D))

    private fun assertReadable(what: String, text: Color, vararg backgrounds: Any) {
        backgrounds.map { it as Color }.forEach { bg ->
            val ratio = contrastRatio(text, bg)
            assertTrue("$what：$text 在 $bg 上只有 ${"%.2f".format(ratio)}:1", ratio >= 4.5f)
        }
    }

    @Test
    fun onTintIsReadableOnEveryReaderAccent() {
        for (page in pages) {
            for (tint in tints + page.accent) {
                val c = readerGroupedColors(page, tint)
                assertReadable("阅读面板强调色底上的字", c.onTint, c.tint)
            }
        }
        // 朱砂配白字，琥珀配深字。
        assertEquals(Color.White, readerGroupedColors(ReaderTheme.PAPER.palette(), ReaderTheme.PAPER.palette().accent).onTint)
        assertEquals(Color.Black, readerGroupedColors(ReaderTheme.NIGHT.palette(), ReaderTheme.NIGHT.palette().accent).onTint)
    }

    @Test
    fun onTintIsReadableOnChosenAccentsInTheApp() {
        for (dark in listOf(false, true)) {
            for (tint in tints) {
                val c = groupedColors(dark, tint)
                if (tint == paperAppColors(dark).primary) continue
                assertReadable("设置页强调色底上的字", c.onTint, c.tint)
            }
        }
    }

    @Test
    fun theDefaultPaperAccentUsesReadableText() {
        for (dark in listOf(false, true)) {
            val scheme = paperAppColors(dark)
            val c = groupedColors(dark, scheme.primary)
            assertEquals(scheme.onPrimary, c.onTint)
            assertTrue(contrastRatio(c.onTint, c.tint) >= 4.5f)
            assertReadable("默认纸色正文", scheme.onSurface, scheme.surface, scheme.surfaceContainer)
            assertReadable("默认纸色说明", scheme.onSurfaceVariantSummary, scheme.surface, scheme.surfaceContainer)
        }
    }

    @Test
    fun warningAndSuccessAreReadableOnCards() {
        for (dark in listOf(false, true)) {
            val c = groupedColors(dark, Color(0xFF3482FF))
            assertReadable("设置页提醒色", c.warning, c.cell, c.background)
            assertReadable("设置页成功色", c.success, c.cell, c.background)
        }
        for (page in pages) {
            val c = readerGroupedColors(page, page.accent)
            assertReadable("阅读面板提醒色", c.warning, c.cell, c.background)
            assertReadable("阅读面板成功色", c.success, c.cell, c.background)
        }
    }

    @Test
    fun readableOnOnlyDarkensAsMuchAsNeeded() {
        val orange = Color(0xFFFF9500)
        val fixed = orange.readableOn(listOf(Color.White))
        assertTrue(contrastRatio(fixed, Color.White) >= 4.5f)
        // 调到刚好够，不是直接变成黑色。
        assertTrue(contrastRatio(fixed, Color.White) < 5.5f)
        assertEquals(Color.Black, contentColorOn(Color(0xFFFFD54F)))
        assertEquals(Color.White, contentColorOn(Color(0xFF1B5E20)))
    }
}
