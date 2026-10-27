package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 设置页配色的每一种组合：暖纸浅色、深色，各强调色、各配色风格下的 Monet，再加上和主题深浅不一致时的纸色方案、阅读面板。
 * 分段控件选中格、没选中的字都够 4.5:1（13sp 中等字重算正文）；删除、提醒、强调色两两分得开，写作建议四类也分得开。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    /** 自选强调色以外再加几种：miuix 默认蓝、很浅的黄、深色 Monet 那种浅粉、琥珀、朱砂、灰。 */
    private val seeds = Appearance.ACCENTS.map { Color(it) } +
        listOf(Color(0xFF3482FF), Color(0xFFFFD54F), Color(0xFFFFB1C8), Color(0xFFE0905F), Color(0xFFB5432F), Color(0xFF9CC3F0))

    private data class Case(val name: String, val colors: GroupedColors)

    /** 所有组合。Monet 配色要在组合里由 miuix 的 ThemeController 生成（生成函数不公开），所以先 setContent 跑一遍收集起来。 */
    private fun cases(): List<Case> = buildList {
        val monet = LinkedHashMap<String, GroupedColors>()
        compose.setContent {
            for (dark in listOf(false, true)) {
                for (seed in seeds) {
                    for (spec in ThemeColorSpec.entries) {
                        for (style in ThemePaletteStyle.entries) {
                            val scheme = ThemeController(
                                colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                                keyColor = seed,
                                colorSpec = spec,
                                paletteStyle = style,
                            ).currentColors()
                            monet["Monet 深色=$dark $seed $spec $style"] = scheme.grouped(scheme.primary, dark)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(2 * seeds.size * ThemeColorSpec.entries.size * ThemePaletteStyle.entries.size, monet.size)
        monet.forEach { (name, colors) -> add(Case(name, colors)) }
        for (dark in listOf(false, true)) {
            val paper = paperAppColors(dark)
            add(Case("纸色 深色=$dark 默认强调色", paper.grouped(paper.primary, dark)))
            // ProvideGroupedColors 在页面深浅和主题不一致时用纸色方案配调用处的强调色。
            seeds.forEach { add(Case("纸色 深色=$dark 强调色 $it", groupedColors(dark, it))) }
        }
        val pages = ReaderTheme.entries.map { it.palette() } + ReaderTheme.entries.map { it.palette().adjusted(dim = 0.88f, warmth = 1f) }
        for (page in pages) {
            (seeds + page.accent).forEach { add(Case("阅读面板 ${page.background} 强调色 $it", readerGroupedColors(page, it))) }
        }
    }

    private fun assertContrast(what: String, text: Color, background: Color, minimum: Float = 4.5f) {
        val ratio = contrastRatio(text, background)
        assertTrue("$what：$text 在 $background 上只有 ${"%.2f".format(ratio)}:1", ratio >= minimum)
    }

    @Test
    fun segmentedControlLabelsAreReadableEverywhere() {
        for ((name, c) in cases()) {
            assertContrast("$name 分段选中格的字", c.segmentLabel, c.segment)
            assertContrast("$name 分段没选中的字", c.segmentIdle, c.fill)
        }
    }

    @Test
    fun darkPaperSegmentIsADarkBlockNotTheBeigeTrack() {
        val scheme = paperAppColors(true)
        val c = scheme.grouped(scheme.primary, true)
        // 以前选中格用浅米色的 secondary 垫琥珀色字，只有 1.1:1。
        assertTrue(contrastRatio(scheme.primary, scheme.secondary) < 1.5f)
        assertTrue(c.segment != scheme.secondary)
        assertTrue("选中格要比底槽深色时亮一档", c.segment.luminanceOf() > c.fill.luminanceOf() && contrastRatio(c.segment, c.fill) >= 1.15f)
        assertContrast("深色纸色选中格的字", c.segmentLabel, c.segment)
        // 浅色的选中格还是卡片白，强调色字本来就够，不改。
        val light = paperAppColors(false).let { it.grouped(it.primary, false) }
        assertEquals(light.cell, light.segment)
        assertEquals(light.tint, light.segmentLabel)
    }

    @Test
    fun destructiveWarningAndTintAreDistinct() {
        for ((name, c) in cases()) {
            val cards = listOf(c.cell, c.background)
            val tint = c.tint.readableOn(cards)
            val destructive = c.destructive.readableOn(cards)
            val warning = c.warning.readableOn(cards)
            assertDistinct("$name 强调色和删除色", tint, destructive)
            assertDistinct("$name 强调色和提醒色", tint, warning)
            assertDistinct("$name 删除色和提醒色", destructive, warning)
        }
    }

    @Test
    fun suggestionKindsAreDistinctAndReadable() {
        for ((name, c) in cases()) {
            val colors = SuggestKind.entries.associateWith { suggestColor(it.name, c) }
            colors.forEach { (kind, color) ->
                assertContrast("$name ${kind.label} 在卡片上", color, c.cell)
                assertContrast("$name ${kind.label} 在页面底上", color, c.background)
            }
            val kinds = SuggestKind.entries
            for (i in kinds.indices) for (j in i + 1 until kinds.size) {
                assertDistinct("$name ${kinds[i].label}和${kinds[j].label}", colors.getValue(kinds[i]), colors.getValue(kinds[j]))
            }
        }
        // 颜色之外每类还有自己的形状。
        assertEquals(SuggestKind.entries.size, SuggestKind.entries.map { suggestShape(it.name) }.toSet().size)
    }

    @Test
    fun defaultPaperSemanticColorsOnlyMoveWhereTheyClashed() {
        val light = paperAppColors(false).let { it.grouped(it.primary, false) }
        // 浅色的删除色在配色里就换成了胭脂红，不需要再转。
        assertEquals(Color(0xFFC92B5B), light.destructive)
        val dark = paperAppColors(true).let { it.grouped(it.primary, true) }
        // 深色的删除色本来就和琥珀分得开，不动。
        assertEquals(paperAppColors(true).error, dark.destructive)
        // 深色的提醒橙和琥珀撞色，只往黄那边转一点。
        assertTrue(dark.warning != Color(0xFFFF9F0A))
        assertTrue(colorDifference(dark.warning, Color(0xFFFF9F0A)) < 6f)
    }

    @Test
    fun colorDifferenceBehaves() {
        assertEquals(0f, colorDifference(Color(0xFFA94B36), Color(0xFFA94B36)), 0.001f)
        assertEquals(100f, colorDifference(Color.Black, Color.White), 0.5f)
        // 朱砂和 miuix 的正红几乎同色，这就是要修的。
        assertTrue(colorDifference(Color(0xFFA94B36).readableOn(listOf(Color(0xFFFFFCF7))), Color(0xFFE94634).readableOn(listOf(Color(0xFFFFFCF7)))) < 8f)
    }

    @Test
    fun switchingBetweenPaperAndDynamicColorsKeepsTheTree() {
        // 以前暖纸和 Monet 是两个 MiuixTheme 分支，打开 Monet、第一次选强调色时整棵界面重建，remember 的状态和换主题的扩散动画都没了。
        var appearance by mutableStateOf(Appearance(mode = ThemeMode.LIGHT))
        var created = 0
        var primary = Color.Unspecified
        compose.setContent {
            LingYueTheme(appearance) {
                remember { created++ }
                primary = MiuixTheme.colorScheme.primary
            }
        }
        compose.waitForIdle()
        assertEquals(PaperAccent, primary)
        compose.runOnIdle { appearance = appearance.copy(accent = Appearance.ACCENTS[3]) }
        compose.waitForIdle()
        assertTrue("换成了 Monet 配色", primary != PaperAccent)
        compose.runOnIdle { appearance = appearance.copy(accent = 0, mode = ThemeMode.DARK) }
        compose.waitForIdle()
        assertEquals(paperAppColors(true).primary, primary)
        assertEquals("界面没有重建", 1, created)
    }

    private fun assertDistinct(what: String, a: Color, b: Color) {
        val delta = colorDifference(a, b)
        assertTrue("$what 太像：$a 和 $b 色差只有 ${"%.1f".format(delta)}", delta >= SEMANTIC_MIN_DELTA - 0.01f)
    }

    private fun Color.luminanceOf(): Float = contrastRatio(this, Color.Black)
}
