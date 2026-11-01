package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 深色下的开关、勾选框：关着、没勾的不能比开着、勾上的还亮（以前关着的轨道是浅米色，看一眼像开着）；
 * 深色的成功色、提醒色比原来的 iOS 色淡，仍然读得清。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun luminance(c: Color) = contrastRatio(c, Color.Black)

    /** 深色的几种配色：纸色、阅读面板的墨夜和灰蓝、几种强调色的 Monet。 */
    private fun darkCases(): List<Pair<String, GroupedColors>> {
        val out = mutableListOf<Pair<String, GroupedColors>>()
        val paper = paperAppColors(true)
        out += "纸色深色" to paper.grouped(paper.primary, true)
        Appearance.ACCENTS.forEach { out += "纸色深色 强调色 $it" to groupedColors(true, Color(it)) }
        listOf(ReaderTheme.NIGHT, ReaderTheme.SLATE).forEach { t -> out += "阅读面板 $t" to readerGroupedColors(t.palette(), t.palette().accent) }
        val monet = mutableListOf<Pair<String, GroupedColors>>()
        compose.setContent {
            for (seed in listOf(Color(0xFFB5432F), Color(0xFF3482FF), Color(0xFFFFD54F), Color(0xFFFFB1C8))) {
                val scheme = ThemeController(
                    colorSchemeMode = ColorSchemeMode.MonetDark,
                    keyColor = seed,
                    colorSpec = ThemeColorSpec.entries.first(),
                    paletteStyle = ThemePaletteStyle.TonalSpot,
                ).currentColors()
                monet += "Monet 深色 $seed" to scheme.grouped(scheme.primary, true)
            }
        }
        compose.waitForIdle()
        return out + monet
    }

    @Test
    fun offSwitchIsDimmerThanOnInEveryDarkScheme() {
        for ((name, c) in darkCases()) {
            val offTrack = switchOffTrack(c)
            val offThumb = switchOffThumb(c)
            assertTrue("$name 关着的轨道 $offTrack 比开着的 ${c.switchOn} 还亮", luminance(offTrack) < luminance(c.switchOn))
            // 整个开关看着的亮度（轨道占大头）也是关着的更暗。
            val off = luminance(offTrack) * 0.7f + luminance(offThumb) * 0.3f
            val on = luminance(c.switchOn) * 0.7f + luminance(c.onTint) * 0.3f
            assertTrue("$name 关着的开关整体比开着的亮：$off ≥ $on", off < on)
            // 关着的轨道仍和卡片分得出来（不是和卡片一个颜色）。
            assertTrue("$name 关着的轨道和卡片分不开", contrastRatio(offTrack, c.cell) >= 1.1f)
        }
    }

    private fun meanLuminance(image: ImageBitmap): Float {
        val px = image.toPixelMap()
        var sum = 0f
        for (y in 0 until px.height) for (x in 0 until px.width) sum += luminance(px[x, y])
        return sum / (px.width * px.height)
    }

    /** 实际画出来：深色纸色主题下，关着的开关、没勾的勾选框平均亮度都比开着、勾上的低。 */
    @Test
    fun renderedOffControlsAreDarkerThanOnOnes() {
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.DARK)) {
                ProvideGroupedColors {
                    val c = LocalGroupedColors.current
                    Row(Modifier.background(c.cell).padding(8.dp)) {
                        Box(Modifier.testTag("switchOff")) { LingYueSwitch(false) }
                        Box(Modifier.testTag("switchOn")) { LingYueSwitch(true) }
                        Box(Modifier.testTag("boxOff")) { LingYueCheckbox(false, onClick = null) }
                        Box(Modifier.testTag("boxOn")) { LingYueCheckbox(true, onClick = null) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val switchOff = meanLuminance(compose.onNodeWithTag("switchOff").captureToImage())
        val switchOn = meanLuminance(compose.onNodeWithTag("switchOn").captureToImage())
        val boxOff = meanLuminance(compose.onNodeWithTag("boxOff").captureToImage())
        val boxOn = meanLuminance(compose.onNodeWithTag("boxOn").captureToImage())
        assertTrue("关着的开关 $switchOff 不比开着的 $switchOn 暗", switchOff < switchOn)
        assertTrue("没勾的勾选框 $boxOff 不比勾上的 $boxOn 暗", boxOff < boxOn)
    }

    /** 粗略的 HSV 饱和度。 */
    private fun saturation(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        return if (max == 0f) 0f else (max - min) / max
    }

    @Test
    fun darkSuccessAndWarningAreCalmerButStillReadable() {
        assertTrue("成功色饱和度 ${saturation(SuccessDark)}", saturation(SuccessDark) < saturation(Color(0xFF30D158)) - 0.15f)
        assertTrue("提醒色饱和度 ${saturation(WarningDark)}", saturation(WarningDark) < saturation(Color(0xFFFF9F0A)) - 0.15f)
        val paper = paperAppColors(true)
        for (bg in listOf(paper.surfaceContainer, paper.surface)) {
            assertTrue("成功色在 $bg 上 ${contrastRatio(SuccessDark, bg)}", contrastRatio(SuccessDark, bg) >= 4.5f)
            assertTrue("提醒色在 $bg 上 ${contrastRatio(WarningDark, bg)}", contrastRatio(WarningDark, bg) >= 4.5f)
        }
        // 实际用的（按卡片底调过、和强调色转开的）也不再是 iOS 的那两个。
        val c = paper.grouped(paper.primary, true)
        val dSuccess = colorDifference(c.success, Color(0xFF30D158))
        val dWarning = colorDifference(c.warning, Color(0xFFFF9F0A))
        assertTrue("成功色 ${c.success} 和 iOS 绿只差 $dSuccess", dSuccess > 5f)
        assertTrue("提醒色 ${c.warning} 和 iOS 橙只差 $dWarning", dWarning > 5f)
    }
}
