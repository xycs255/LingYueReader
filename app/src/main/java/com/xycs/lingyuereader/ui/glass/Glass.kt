package com.xycs.lingyuereader.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import com.xycs.lingyuereader.ui.ReaderPalette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/*
 * 灵阅的透明液态玻璃：顶栏、底部导航、对话框、底部面板、菜单、卡片都用它。
 *
 * 玻璃要“透过”后面的内容：每个页面把正文区域记录成一层（LayerBackdrop），玻璃再取样这一层，
 * 依次做提饱和、模糊、边缘折射，叠一层很淡的底色和高光描边。
 * - 对话框、面板、菜单由最外层 Scaffold 统一画在最上面，取样 [LocalGlassBackdrop]（页面根部的记录）；
 * - 顶栏和内容同在一个 Scaffold 里，取样 [LocalBarBackdrop]（这个 Scaffold 内容区的记录）；
 * - 卡片本身就在被记录的内容里，不能取样自己，只取样纯色底，玻璃感来自半透明底色和高光描边。
 * RuntimeShader 要 Android 13 起才有；更早的系统一律退回纯色。
 */

/** 页面根部的背景记录：对话框、底部面板、菜单从这里取样。 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 当前 Scaffold 内容区的背景记录：顶栏从这里取样。 */
val LocalBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 液态玻璃（折射、模糊、高光）要用 RuntimeShader，Android 13 起才有。 */
fun liquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()

/** 把一块区域记录成玻璃的取样源；不支持时为 null。[background] 是这块区域的底色。 */
@Composable
fun rememberGlassBackdrop(background: Color): LayerBackdrop? {
    if (!liquidGlassSupported()) return null
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

fun Modifier.recordGlass(backdrop: LayerBackdrop?): Modifier = if (backdrop != null) layerBackdrop(backdrop) else this

@Composable
private fun isDarkSurface(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f

/**
 * 一块玻璃。
 * @param tint 叠在玻璃上的底色，透明度决定“多透”；退回纯色时至少用 [fallbackAlpha] 的不透明度，保证文字看得清。
 * @param blur 模糊半径。
 * @param refraction 边缘折射的宽度；0 不折射（贴屏幕边的顶栏）。
 * @param highlight 高光描边，null 不画。
 */
fun Modifier.glass(
    backdrop: Backdrop?,
    shape: Shape,
    tint: Color,
    blur: Dp = 16.dp,
    refraction: Dp = 0.dp,
    highlight: Highlight? = null,
    fallbackAlpha: Float = 0.96f,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    if (backdrop == null || !liquidGlassSupported()) {
        return background(tint.copy(alpha = maxOf(tint.alpha, fallbackAlpha)), shape)
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            if (blur > 0.dp) blur(blur.toPx(), blur.toPx())
            if (refraction > 0.dp && shape is CornerBasedShape) {
                lens(refractionHeight = refraction.toPx(), refractionAmount = refraction.toPx())
            }
        },
        highlight = highlight?.let { h -> { h } },
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(tint) },
    )
}

/** 只画一种颜色的取样源：给卡片这类在内容里面、不能取样自己的玻璃用。 */
@Stable
private class SolidBackdrop(val color: Color) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        drawRect(color)
    }
}

/** 顶栏：贴着屏幕顶边，只模糊不折射，取样本页内容。 */
@Composable
fun Modifier.glassBar(): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    return glass(LocalBarBackdrop.current, RectangleShape, surface.copy(alpha = 0.55f), blur = 24.dp)
}

/** 玻璃边缘的高光描边：深浅底色各一套，大块浮层用宽一些的。 */
fun glassHighlight(dark: Boolean, big: Boolean): Highlight = when {
    dark -> if (big) Highlight.GlassStrokeBigDark else Highlight.GlassStrokeSmallDark
    else -> if (big) Highlight.GlassStrokeBigLight else Highlight.GlassStrokeSmallLight
}

/** 对话框、底部面板、菜单这类浮层的高光，跟随界面深浅色。 */
@Composable
fun panelHighlight(big: Boolean = true): Highlight = glassHighlight(isDarkSurface(), big)

/** 阅读页里浮着的控件（菜单栏、控制条、选字操作条）：取样书页，用书页配色。 */
fun Modifier.readerGlass(shape: CornerBasedShape, palette: ReaderPalette, backdrop: Backdrop?): Modifier = this
    .dropShadow(shape, Shadow(radius = 12.dp, color = Color.Black, alpha = if (palette.isDark) 0.3f else 0.12f))
    .glass(
        backdrop = backdrop,
        shape = shape,
        // 后面是密密的正文，底色和模糊都要足一些，按钮文字才看得清。
        tint = palette.bar.copy(alpha = 0.62f),
        blur = 16.dp,
        refraction = 14.dp,
        highlight = glassHighlight(palette.isDark, big = false),
    )
    .clip(shape)

/** 浮层玻璃的底色：透明为主，文字仍要看得清。 */
@Composable
fun panelTint(base: Color = MiuixTheme.colorScheme.surface): Color = base.copy(alpha = if (isDarkSurface()) 0.62f else 0.58f)

/**
 * 页面骨架：内容区记录成玻璃的取样源，顶栏（配合 [glassBar]）和本页弹出的浮层从它取样。
 * 作为页面根部时（外面没有别的玻璃页面），本页的对话框、面板、菜单也取样它，菜单的不透明底色换成透明，由玻璃来画。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberGlassBackdrop(containerColor)
    val parent = LocalGlassBackdrop.current
    val isRoot = parent == null
    CompositionLocalProvider(
        LocalGlassBackdrop provides (parent ?: backdrop),
        LocalBarBackdrop provides backdrop,
    ) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            containerColor = containerColor,
            contentWindowInsets = contentWindowInsets,
            popupHost = { if (isRoot) GlassPopupHost() else MiuixPopupHost() },
        ) { padding ->
            Box(Modifier.fillMaxSize().recordGlass(backdrop)) {
                content(padding)
            }
        }
    }
}

/** 浮层宿主：菜单的底色（surfaceContainer）在这里换成透明，玻璃由 [GlassMenu] 画。 */
@Composable
fun GlassPopupHost() {
    if (!liquidGlassSupported()) {
        MiuixPopupHost()
        return
    }
    val colors = MiuixTheme.colorScheme
    val transparent = remember(colors) { colors.copy(surfaceContainer = Color.Transparent) }
    MiuixTheme(colors = transparent) {
        MiuixPopupHost()
    }
}

/** 下拉菜单的玻璃，包住 ListPopupColumn。 */
@Composable
fun GlassMenu(content: @Composable () -> Unit) {
    Box(
        Modifier.glass(
            backdrop = LocalGlassBackdrop.current,
            shape = RoundedCornerShape(16.dp),
            tint = panelTint(MiuixTheme.colorScheme.surface),
            blur = 16.dp,
            refraction = 10.dp,
            highlight = panelHighlight(big = false),
        ),
    ) {
        content()
    }
}

/** 对话框：手机上贴着屏幕底部弹出。 */
@Composable
fun GlassDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val glass = Modifier.glass(
        backdrop = LocalGlassBackdrop.current,
        shape = RoundedCornerShape(32.dp),
        tint = panelTint(),
        blur = 24.dp,
        refraction = 14.dp,
        highlight = panelHighlight(),
    )
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        modifier = glass,
        backgroundColor = if (LocalGlassBackdrop.current != null && liquidGlassSupported()) Color.Transparent else MiuixTheme.colorScheme.background,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/** 底部面板。[background] 是面板的底色（阅读页传书页配色）。 */
@Composable
fun GlassSheet(
    show: Boolean,
    title: String? = null,
    background: Color = MiuixTheme.colorScheme.background,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    highlight: Highlight = panelHighlight(),
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val radius = 28.dp
    val active = backdrop != null && liquidGlassSupported()
    OverlayBottomSheet(
        show = show,
        title = title,
        modifier = Modifier.glass(
            backdrop = backdrop,
            shape = RoundedCornerShape(topStart = radius, topEnd = radius),
            tint = panelTint(background),
            blur = 28.dp,
            refraction = 18.dp,
            highlight = highlight,
        ),
        backgroundColor = if (active) Color.Transparent else background,
        cornerRadius = radius,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/** 卡片玻璃：半透明底色加高光描边。 */
@Composable
private fun Modifier.glassCard(cornerRadius: Dp): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    val container = MiuixTheme.colorScheme.surfaceContainer
    val backdrop = remember(surface) { SolidBackdrop(surface) }
    return glass(
        backdrop = backdrop,
        shape = RoundedCornerShape(cornerRadius),
        tint = container.copy(alpha = 0.7f),
        blur = 0.dp,
        highlight = panelHighlight(big = false).copy(alpha = 0.8f),
    )
}

@Composable
private fun glassCardColors() = if (liquidGlassSupported()) {
    CardDefaults.defaultColors(color = Color.Transparent)
} else {
    CardDefaults.defaultColors()
}

/** 卡片，参数同 miuix 的 Card。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.glassCard(cornerRadius),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = glassCardColors(),
        content = content,
    )
}

/** 可点的卡片，参数同 miuix 的 Card。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.glassCard(cornerRadius),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = glassCardColors(),
        pressFeedbackType = pressFeedbackType,
        onClick = onClick,
        onLongPress = onLongPress,
        content = content,
    )
}
