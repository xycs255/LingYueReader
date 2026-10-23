package com.xycs.lingyuereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xycs.lingyuereader.data.ReaderTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 阅读页的强调色。界面其余部分用 miuix 默认配色，随系统深浅色切换。 */
private val Cinnabar = Color(0xFFA23B2A)
/** 夜间强调色用琥珀色：深底上红、绿文字最易累眼（Fan 等，2024）。 */
private val Amber = Color(0xFFD49A57)

@Composable
fun LingYueTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val bar: Color,
    val isDark: Boolean,
)

fun ReaderTheme.palette(): ReaderPalette = when (this) {
    // 浅灰暖白而不是近乎纯白：亮处更柔和，对比度仍在 13:1 左右。
    ReaderTheme.PAPER -> ReaderPalette(Color(0xFFF4F2EC), Color(0xFF2A2724), Color(0xFF857D73), Cinnabar, Color(0xFFEAE6DD), false)
    ReaderTheme.PARCHMENT -> ReaderPalette(Color(0xFFF2E6CC), Color(0xFF3E3222), Color(0xFF8C7A5E), Color(0xFF9A3A22), Color(0xFFE8D9B9), false)
    ReaderTheme.GREEN -> ReaderPalette(Color(0xFFDCEBD2), Color(0xFF26331F), Color(0xFF6C7D62), Color(0xFF3E6B2E), Color(0xFFCCDFC0), false)
    // 深底配偏暖的浅黄字（不用纯白），对比度约 9:1。
    ReaderTheme.NIGHT -> ReaderPalette(Color(0xFF151413), Color(0xFFC4B48E), Color(0xFF7D7565), Amber, Color(0xFF22201E), true)
}

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
    return if (isDark) {
        copy(text = warm(text, 1f), secondary = warm(secondary, 1f))
    } else {
        copy(background = dimmed(warm(background, 1f)), bar = dimmed(warm(bar, 1f)))
    }
}

/** WCAG 定义的相对亮度对比度，用来检查配色。 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
