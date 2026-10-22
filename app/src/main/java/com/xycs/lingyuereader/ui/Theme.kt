package com.xycs.lingyuereader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.xycs.lingyuereader.data.ReaderTheme

/** 朱砂红作强调色。 */
private val Cinnabar = Color(0xFFA23B2A)
private val CinnabarLight = Color(0xFFE08A74)

private val LightColors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DDD5),
    onPrimaryContainer = Color(0xFF3F0A02),
    secondary = Color(0xFF6E5B4B),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
    surfaceContainer = Color(0xFFF3EEE6),
    surfaceContainerHigh = Color(0xFFEDE7DE),
)

private val DarkColors = darkColorScheme(
    primary = CinnabarLight,
    onPrimary = Color(0xFF3F0A02),
    primaryContainer = Color(0xFF6B2416),
    onPrimaryContainer = Color(0xFFFFDAD1),
    secondary = Color(0xFFD5C2B1),
    background = Color(0xFF16130F),
    surface = Color(0xFF16130F),
)

@Composable
fun LingYueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
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
