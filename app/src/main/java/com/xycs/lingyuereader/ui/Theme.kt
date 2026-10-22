package com.xycs.lingyuereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.xycs.lingyuereader.data.ReaderTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 阅读页的强调色。界面其余部分用 miuix 默认配色，随系统深浅色切换。 */
private val Cinnabar = Color(0xFFA23B2A)
private val CinnabarLight = Color(0xFFE08A74)

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
    ReaderTheme.PAPER -> ReaderPalette(Color(0xFFFBF9F4), Color(0xFF2A2724), Color(0xFF8A8278), Cinnabar, Color(0xFFF1EDE4), false)
    ReaderTheme.PARCHMENT -> ReaderPalette(Color(0xFFF2E6CC), Color(0xFF3E3222), Color(0xFF8C7A5E), Color(0xFF9A3A22), Color(0xFFE8D9B9), false)
    ReaderTheme.GREEN -> ReaderPalette(Color(0xFFDCEBD2), Color(0xFF26331F), Color(0xFF6C7D62), Color(0xFF3E6B2E), Color(0xFFCCDFC0), false)
    ReaderTheme.NIGHT -> ReaderPalette(Color(0xFF151413), Color(0xFF9E9A93), Color(0xFF66625C), CinnabarLight, Color(0xFF22201E), true)
}
