package com.xycs.lingyuereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PaletteStyle
import com.xycs.lingyuereader.data.ThemeMode
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 阅读页的强调色。界面其余部分用 miuix 默认配色，随系统深浅色切换。 */
private val Cinnabar = Color(0xFFB5432F)
/** 夜间强调色用琥珀色：深底上红、绿文字最易累眼（Fan 等，2024）。 */
private val Amber = Color(0xFFE0905F)

@Composable
fun LingYueTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val controller = remember(appearance.mode, appearance.dynamicColors, appearance.accent, appearance.paletteStyle) {
        val mode = when (appearance.mode) {
            ThemeMode.SYSTEM -> if (appearance.dynamicColors) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
            ThemeMode.LIGHT -> if (appearance.dynamicColors) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
            ThemeMode.DARK -> if (appearance.dynamicColors) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        }
        ThemeController(
            colorSchemeMode = mode,
            // 没选强调色时 Monet 用壁纸取色。
            keyColor = appearance.accent.takeIf { it != 0 }?.let { Color(it) },
            paletteStyle = when (appearance.paletteStyle) {
                PaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
                PaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
                PaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
                PaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
                PaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
            },
        )
    }
    // 界面缩放：按比例放大缩小 dp 和 sp，整个界面一起变。
    val density = LocalDensity.current
    val scaled = remember(density, appearance.scale) { Density(density.density * appearance.scale, density.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MiuixTheme(controller = controller, content = content)
    }
}

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val bar: Color,
    val isDark: Boolean,
)

fun ReaderTheme.palette(): ReaderPalette = (when (this) {
    // 浅灰暖白而不是近乎纯白：亮处更柔和，对比度仍在 13:1 左右。
    ReaderTheme.PAPER -> ReaderPalette(Color(0xFFF5F0E6), Color(0xFF2B2620), Color(0xFF6F665A), Cinnabar, Color(0xFFEDE6D8), false)
    ReaderTheme.PARCHMENT -> ReaderPalette(Color(0xFFF2E6CC), Color(0xFF3E3222), Color(0xFF8C7A5E), Color(0xFF9A3A22), Color(0xFFE8D9B9), false)
    ReaderTheme.GREEN -> ReaderPalette(Color(0xFFDCEBD2), Color(0xFF26331F), Color(0xFF6C7D62), Color(0xFF3E6B2E), Color(0xFFCCDFC0), false)
    // 深底配偏暖的浅黄字（不用纯白），对比度约 9:1。
    ReaderTheme.NIGHT -> ReaderPalette(Color(0xFF191714), Color(0xFFD6CDBB), Color(0xFF8E8676), Amber, Color(0xFF24211E), true)
    // 灰蓝：不那么黑的深色，夜里开着灯读。
    ReaderTheme.SLATE -> ReaderPalette(Color(0xFF3A3F47), Color(0xFFDCE1E8), Color(0xFFA9B1BC), Color(0xFF9CC3F0), Color(0xFF454B54), true)
    ReaderTheme.CUSTOM -> customPalette(Color(ReaderSettings().customBackground), Color(ReaderSettings().customText))
}).withReadableSecondary()

/** 次要文字仍比正文柔和，但在书页及不透明浮层上都达到普通小字的对比度。 */
private fun ReaderPalette.withReadableSecondary(): ReaderPalette {
    fun minimumContrast(color: Color) = minOf(contrastRatio(color, background), contrastRatio(color, bar))
    // 留一点余量，避免黄昏暖色调整后正好跌破 4.5:1。
    val target = 4.7f
    if (minimumContrast(secondary) >= target) return this
    val anchor = if (minimumContrast(text) >= target) text else listOf(Color.Black, Color.White).maxBy(::minimumContrast)
    if (minimumContrast(anchor) < target) return copy(secondary = anchor)
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (minimumContrast(lerp(secondary, anchor, middle)) >= target) high = middle else low = middle
    }
    return copy(secondary = lerp(secondary, anchor, high))
}

/** 自定义背景：次要文字取字色和底色之间，深底用琥珀色强调。 */
fun customPalette(background: Color, text: Color): ReaderPalette {
    val dark = background.luminance() < 0.3f
    return ReaderPalette(
        background = background,
        text = text,
        secondary = lerp(text, background, 0.45f),
        accent = if (dark) Amber else Cinnabar,
        bar = lerp(background, text, 0.07f),
        isDark = dark,
    ).withReadableSecondary()
}

/** [theme] 的配色；自定义背景用设置里的颜色。 */
fun ReaderSettings.paletteOf(theme: ReaderTheme = this.theme): ReaderPalette =
    if (theme == ReaderTheme.CUSTOM) customPalette(Color(customBackground), Color(customText)) else theme.palette()

/**
 * 按环境调整配色：[dim] 是背景亮度系数（1 为不变，昏暗环境下压到约 0.88），
 * [warmth] 是 0..1 的偏暖程度（日落后）。文字颜色不压暗，对比度保持在 7:1 以上。
 */
fun ReaderPalette.adjusted(dim: Float, warmth: Float): ReaderPalette {
    if (dim >= 1f && warmth <= 0f) return this
    fun warm(c: Color, strength: Float): Color {
        val w = warmth * strength
        return Color(c.red, c.green * (1 - 0.04f * w), c.blue * (1 - 0.16f * w), c.alpha)
    }
    fun dimmed(c: Color) = Color(c.red * dim, c.green * dim, c.blue * dim, c.alpha)
    return (if (isDark) {
        copy(text = warm(text, 1f), secondary = warm(secondary, 1f))
    } else {
        copy(background = dimmed(warm(background, 1f)), bar = dimmed(warm(bar, 1f)))
    }).withReadableSecondary()
}

/** WCAG 定义的相对亮度对比度，用来检查配色。 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 强调色这类有色底上的字：优先用 [preferred]（通常是白），不够 4.5:1 就在黑白里挑对比度高的那个（总能到 4.5:1 以上）。 */
fun contentColorOn(background: Color, preferred: Color = Color.White): Color =
    if (contrastRatio(preferred, background) >= 4.5f) preferred
    else listOf(Color.Black, Color.White).maxBy { contrastRatio(it, background) }

/**
 * 有色的字（警告、成功这类）在 [backgrounds] 上都要读得清：不够 [target] 就往黑（浅底）或白（深底）里调，
 * 只调到刚好够，颜色尽量留着。和书页次要字一样留一点余量，免得环境光、日落变暖一调就跌破 4.5:1。
 */
fun Color.readableOn(backgrounds: List<Color>, target: Float = 4.7f): Color {
    fun minimumContrast(color: Color) = backgrounds.minOf { contrastRatio(color, it) }
    if (minimumContrast(this) >= target) return this
    val anchor = listOf(Color.Black, Color.White).maxBy(::minimumContrast)
    if (minimumContrast(anchor) < target) return anchor
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (minimumContrast(lerp(this, anchor, middle)) >= target) high = middle else low = middle
    }
    return lerp(this, anchor, high)
}
