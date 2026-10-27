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
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 阅读页的强调色。界面其余部分默认用纸色，仍可使用 Monet 或自选强调色。 */
private val Cinnabar = Color(0xFFB5432F)
internal val PaperAccent = Color(0xFFA94B36)
/** 夜间强调色用琥珀色：深底上红、绿文字最易累眼（Fan 等，2024）。 */
private val Amber = Color(0xFFE0905F)

@Composable
fun LingYueTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    // 默认外观用样稿的暖纸色和朱砂色；读者选了壁纸取色或自定义强调色时交给 miuix 生成配色。
    // 两种都走同一个 controller、同一处 MiuixTheme，只换配色：界面不在两个分支间拆掉重建，换主题的扩散动画和页面状态都留着。
    val controller = remember(appearance.mode, appearance.dynamicColors, appearance.accent, appearance.paletteStyle) {
        if (!appearance.dynamicColors) return@remember ThemeController(
            colorSchemeMode = when (appearance.mode) {
                ThemeMode.SYSTEM -> ColorSchemeMode.System
                ThemeMode.LIGHT -> ColorSchemeMode.Light
                ThemeMode.DARK -> ColorSchemeMode.Dark
            },
            lightColors = paperAppColors(false),
            darkColors = paperAppColors(true),
        )
        val mode = when (appearance.mode) {
            ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
            ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
            ThemeMode.DARK -> ColorSchemeMode.MonetDark
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

/** 全应用默认中性色：纸张承载内容，玻璃只用于导航与浮层。 */
internal fun paperAppColors(dark: Boolean): Colors = if (dark) darkColorScheme().copy(
    primary = Amber,
    onPrimary = Color(0xFF241810),
    primaryContainer = Color(0xFF493326),
    onPrimaryContainer = Color(0xFFF4D9BE),
    secondary = Color(0xFFB9AB99),
    onSecondary = Color(0xFF221E1A),
    secondaryContainer = Color(0xFF332E28),
    onSecondaryContainer = Color(0xFFE6DDCF),
    background = Color(0xFF191714),
    onBackground = Color(0xFFE6DDCF),
    surface = Color(0xFF1E1B18),
    onSurface = Color(0xFFE6DDCF),
    surfaceVariant = Color(0xFF332E28),
    surfaceContainer = Color(0xFF292521),
    onSurfaceContainer = Color(0xFFE6DDCF),
    surfaceContainerHigh = Color(0xFF302B26),
    onSurfaceContainerHigh = Color(0xFFE6DDCF),
    surfaceContainerHighest = Color(0xFF38322C),
    onSurfaceContainerHighest = Color(0xFFE6DDCF),
    onSurfaceVariantSummary = Color(0xFFB9AB99),
    onSurfaceVariantActions = Color(0xFF9D9081),
    dividerLine = Color(0xFF4A4036),
) else lightColorScheme().copy(
    primary = PaperAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DED2),
    onPrimaryContainer = Color(0xFF6E2D20),
    secondary = Color(0xFF75695D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE7DE),
    onSecondaryContainer = Color(0xFF292621),
    background = Color(0xFFF6F2EA),
    onBackground = Color(0xFF292621),
    surface = Color(0xFFF6F2EA),
    onSurface = Color(0xFF292621),
    surfaceVariant = Color(0xFFEDE7DE),
    surfaceContainer = Color(0xFFFFFCF7),
    onSurfaceContainer = Color(0xFF292621),
    surfaceContainerHigh = Color(0xFFF2ECE3),
    onSurfaceContainerHigh = Color(0xFF292621),
    surfaceContainerHighest = Color(0xFFEAE2D8),
    onSurfaceContainerHighest = Color(0xFF292621),
    // 删除色偏胭脂红，不用 miuix 的正红 #E94634：正红和朱砂强调色几乎同色，删除键、错字标记和主操作分不开。
    error = Color(0xFFC92B5B),
    onSurfaceVariantSummary = Color(0xFF71675C),
    onSurfaceVariantActions = Color(0xFF84786B),
    dividerLine = Color(0xFFE4DBD0),
)

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

/**
 * 语义色（删除、提醒）和 [others]（强调色、别的语义色）分得开：都按 [backgrounds] 调到读得清以后，
 * CIEDE2000 色差不到 [minDelta] 就在 CIELAB 里转色相（明度、彩度不动，对比度基本不变），两个方向轮流试，取转得最少的那个。
 * 转到 60° 还不够时取色差最大的。返回转过色相、还没按底色调过的颜色，用的地方照旧 [readableOn]。
 */
fun Color.distinctFrom(others: List<Color>, backgrounds: List<Color>, minDelta: Float = SEMANTIC_MIN_DELTA): Color {
    val fixed = others.map { it.readableOn(backgrounds) }
    fun nearest(c: Color) = fixed.minOfOrNull { colorDifference(c.readableOn(backgrounds), it) } ?: Float.MAX_VALUE
    var best = this
    var bestDelta = nearest(this)
    if (bestDelta >= minDelta) return this
    for (step in 1..20) {
        for (turn in listOf(step * 3f, -step * 3f)) {
            val candidate = turnHue(turn)
            val delta = nearest(candidate)
            if (delta >= minDelta) return candidate
            if (delta > bestDelta) {
                best = candidate
                bestDelta = delta
            }
        }
    }
    return best
}

/** 语义色之间、语义色和强调色之间至少要有的 CIEDE2000 色差：15 左右并排一眼能分出是两种颜色（1～2 是刚能察觉）。 */
const val SEMANTIC_MIN_DELTA = 15f

/** 两个颜色的 CIEDE2000 色差（Sharma 2005 的公式）。 */
fun colorDifference(a: Color, b: Color): Float {
    val (l1, a1, b1) = a.toLab()
    val (l2, a2, b2) = b.toLab()
    val c1 = hypot(a1, b1)
    val c2 = hypot(a2, b2)
    val cMean7 = ((c1 + c2) / 2).pow(7)
    val g = 0.5 * (1 - sqrt(cMean7 / (cMean7 + 25.0.pow(7))))
    val a1p = (1 + g) * a1
    val a2p = (1 + g) * a2
    val c1p = hypot(a1p, b1)
    val c2p = hypot(a2p, b2)
    fun hueOf(x: Double, y: Double) = if (x == 0.0 && y == 0.0) 0.0 else (Math.toDegrees(atan2(y, x)) + 360) % 360
    val h1p = hueOf(a1p, b1)
    val h2p = hueOf(a2p, b2)
    val dL = l2 - l1
    val dC = c2p - c1p
    val dh = when {
        c1p * c2p == 0.0 -> 0.0
        h2p - h1p > 180 -> h2p - h1p - 360
        h2p - h1p < -180 -> h2p - h1p + 360
        else -> h2p - h1p
    }
    val dH = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dh / 2))
    val lMean = (l1 + l2) / 2
    val cMeanP = (c1p + c2p) / 2
    val hMean = when {
        c1p * c2p == 0.0 -> h1p + h2p
        abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
        h1p + h2p < 360 -> (h1p + h2p + 360) / 2
        else -> (h1p + h2p - 360) / 2
    }
    val t = 1 - 0.17 * cos(Math.toRadians(hMean - 30)) + 0.24 * cos(Math.toRadians(2 * hMean)) +
        0.32 * cos(Math.toRadians(3 * hMean + 6)) - 0.20 * cos(Math.toRadians(4 * hMean - 63))
    val dTheta = 30 * exp(-((hMean - 275) / 25).pow(2))
    val cMeanP7 = cMeanP.pow(7)
    val rC = 2 * sqrt(cMeanP7 / (cMeanP7 + 25.0.pow(7)))
    val sL = 1 + 0.015 * (lMean - 50).pow(2) / sqrt(20 + (lMean - 50).pow(2))
    val sC = 1 + 0.045 * cMeanP
    val sH = 1 + 0.015 * cMeanP * t
    val rT = -sin(Math.toRadians(2 * dTheta)) * rC
    return sqrt((dL / sL).pow(2) + (dC / sC).pow(2) + (dH / sH).pow(2) + rT * (dC / sC) * (dH / sH)).toFloat()
}

/** 在 CIELAB 里保持明度、彩度，把色相转 [degrees] 度；转出 sRGB 的分量截到 0..1。 */
private fun Color.turnHue(degrees: Float): Color {
    val (l, a, b) = toLab()
    val chroma = hypot(a, b)
    val hue = atan2(b, a) + Math.toRadians(degrees.toDouble())
    return labToColor(l, chroma * cos(hue), chroma * sin(hue), alpha)
}

// D65 白点下 sRGB 和 CIELAB 互转。
private const val WHITE_X = 0.95047
private const val WHITE_Z = 1.08883

private fun Color.toLab(): Triple<Double, Double, Double> {
    fun linear(c: Float) = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    val r = linear(red)
    val g = linear(green)
    val b = linear(blue)
    fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    val fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / WHITE_X)
    val fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
    val fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / WHITE_Z)
    return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

private fun labToColor(l: Double, a: Double, b: Double, alpha: Float): Color {
    val fy = (l + 16) / 116
    val fx = fy + a / 500
    val fz = fy - b / 200
    fun inverse(t: Double) = if (t.pow(3) > 216.0 / 24389) t.pow(3) else (116 * t - 16) / (24389.0 / 27)
    val x = inverse(fx) * WHITE_X
    val y = inverse(fy)
    val z = inverse(fz) * WHITE_Z
    fun encode(c: Double): Float {
        val v = c.coerceIn(0.0, 1.0)
        return (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    }
    return Color(
        encode(3.2406 * x - 1.5372 * y - 0.4986 * z),
        encode(-0.9689 * x + 1.8758 * y + 0.0415 * z),
        encode(0.0557 * x - 0.2040 * y + 1.0570 * z),
        alpha,
    )
}
