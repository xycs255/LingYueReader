package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 选项界面的共用组件。默认外观用暖纸色和紧凑卡片，深色用暖墨色；miuix 提供原生交互。
 * - 一组选项放在一张 16dp 圆角的卡片里，组间留白，触控范围独立于可见图形；
 * - 组名是卡片上方的次要色小字（miuix SmallTitle），解释是卡片下方的灰色小字；
 * - 一行：左边名字（下面可有灰色说明），右边当前值和小箭头；开关、滑块、下拉选择、分段都用 miuix 自己的控件；
 * - 强调色跟随主题（Monet / 自选强调色），阅读页的面板跟随书页。
 */

/** 设置页的配色，取自 miuix 的配色方案；[tint] 是强调色（选中、链接、开关、滑块）。 */
@Immutable
internal data class GroupedColors(
    val background: Color,
    val cell: Color,
    val pressed: Color,
    val separator: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    /** 分段控件的底槽、胶囊按钮的底。 */
    val fill: Color,
    /** 分段控件选中的那一格。 */
    val segment: Color,
    val tint: Color,
    /** 强调色底上的字和图标（主按钮、选中的胶囊）。 */
    val onTint: Color,
    /** 删除、出错。 */
    val destructive: Color,
    /** 提醒、该处理了（卡片底上的小字也读得清，≥ 4.5:1）。 */
    val warning: Color,
    /** 完成、通过（同上）。 */
    val success: Color,
    val switchOn: Color,
    val dark: Boolean,
) {
    /** 分段控件选中格上的字：强调色，在 [segment] 上不够 4.5:1 时往黑或白里调到够（很浅的自选强调色、Monet 下也一样）。 */
    val segmentLabel: Color = tint.readableOn(listOf(segment))

    /** 分段控件没选中的字：次要色，在底槽 [fill] 上同样调到够 4.5:1。 */
    val segmentIdle: Color = secondary.readableOn(listOf(fill))
}

/** 提醒、成功的基准色：浅色底用深一些的橙和绿，深色底用亮一些的；实际取值再按卡片底调到够对比度。 */
private val WarningLight = Color(0xFFA85500)
private val WarningDark = Color(0xFFFF9F0A)
private val SuccessLight = Color(0xFF15803D)
private val SuccessDark = Color(0xFF30D158)

/**
 * 删除色、提醒色：和强调色太像（朱砂和正红、琥珀和橙）时转开一点色相，提醒色还要和删除色分开，
 * 免得删除键像主操作、几类写作建议分不出来（见 [distinctFrom]）。提醒色再调到在 [cards] 上读得清。
 */
private fun semanticColors(red: Color, orange: Color, tint: Color, cards: List<Color>): Pair<Color, Color> {
    val destructive = red.distinctFrom(listOf(tint), cards)
    return destructive to orange.distinctFrom(listOf(tint, destructive), cards).readableOn(cards)
}

internal fun Colors.grouped(tint: Color, dark: Boolean): GroupedColors {
    val cards = listOf(surfaceContainer, surface)
    val (destructive, warning) = semanticColors(error, if (dark) WarningDark else WarningLight, tint, cards)
    return GroupedColors(
        background = surface,
        cell = surfaceContainer,
        pressed = secondaryContainer,
        separator = dividerLine,
        label = onSurface,
        secondary = onSurfaceVariantSummary,
        tertiary = onSurfaceVariantActions,
        fill = secondaryContainer,
        // 选中格比底槽亮一档：浅色是卡片白，深色往字色提一点。不用深色的 secondary——纸色方案里它是开关关着时的浅米色轨道，
        // 拿来垫强调色字只有 1.1:1；Monet 深色的 secondary 也是浅色。
        segment = if (dark) lerp(secondaryContainer, onSurface, 0.08f) else surfaceContainer,
        tint = tint,
        // miuix 自己配的 onPrimary 保留品牌对比；很浅的自选强调色则在黑白里挑可读字色。
        onTint = if (tint == primary && contrastRatio(onPrimary, tint) >= 3f) onPrimary else contentColorOn(tint),
        destructive = destructive,
        warning = warning,
        success = (if (dark) SuccessDark else SuccessLight).readableOn(cards),
        switchOn = tint,
        dark = dark,
    )
}

/** 不在 Compose 里时（比如给面板定底色）用默认纸色方案。[elevated] 保留给浮在页面上的面板，配色相同。 */
@Suppress("UNUSED_PARAMETER")
internal fun groupedColors(dark: Boolean, tint: Color, elevated: Boolean = false): GroupedColors =
    paperAppColors(dark).grouped(tint, dark)

/**
 * 阅读页面板的配色：从书页配色调出来，不用 miuix 的冷灰和纯黑——纸白页上的面板是暖白，墨夜页上是深褐。
 * 面板底比书页稍亮一点（浮在页面上），卡片再亮一点；文字用书页的字色和次要色。
 */
internal fun readerGroupedColors(palette: ReaderPalette, tint: Color): GroupedColors {
    val dark = palette.background.luminance() < 0.5f
    val bg = palette.background
    fun up(c: Color, f: Float) = lerp(c, Color.White, f)
    return if (dark) {
        val sheet = up(bg, 0.05f)
        val cell = up(bg, 0.10f)
        val (destructive, warning) = semanticColors(Color(0xFFF15B4F), WarningDark, tint, listOf(cell, sheet))
        GroupedColors(
            background = sheet,
            cell = cell,
            pressed = up(cell, 0.07f),
            separator = palette.text.copy(alpha = 0.10f),
            label = palette.text,
            // 深底上次要字再提亮一点，小字也够 4.5:1。
            secondary = lerp(palette.secondary, palette.text, 0.3f),
            tertiary = palette.text.copy(alpha = 0.38f),
            fill = up(bg, 0.08f),
            segment = up(bg, 0.20f),
            tint = tint,
            // 书页的强调色有深有浅（朱砂配白字，琥珀、灰蓝配深字），按对比度挑。
            onTint = contentColorOn(tint),
            destructive = destructive,
            warning = warning,
            success = SuccessDark.readableOn(listOf(cell, sheet)),
            switchOn = tint,
            dark = true,
        )
    } else {
        val sheet = up(bg, 0.45f)
        val cell = up(bg, 0.88f)
        // 纸白、宣黄、竹青的面板底各不相同，提醒色调到在卡片和面板底上都够 4.5:1。
        val (destructive, warning) = semanticColors(Color(0xFFD9412F), WarningLight, tint, listOf(cell, sheet))
        GroupedColors(
            background = sheet,
            cell = cell,
            pressed = lerp(cell, palette.text, 0.06f),
            separator = palette.text.copy(alpha = 0.10f),
            label = palette.text,
            secondary = palette.secondary,
            tertiary = palette.text.copy(alpha = 0.36f),
            fill = lerp(sheet, palette.text, 0.06f),
            segment = cell,
            tint = tint,
            onTint = contentColorOn(tint),
            destructive = destructive,
            warning = warning,
            success = SuccessLight.readableOn(listOf(cell, sheet)),
            switchOn = tint,
            dark = false,
        )
    }
}

/** 阅读页里的面板用 [readerGroupedColors]。 */
@Composable
internal fun ProvideReaderColors(palette: ReaderPalette, tint: Color, content: @Composable () -> Unit) {
    val colors = remember(palette, tint) { readerGroupedColors(palette, tint) }
    ProvideGroupedColorsValue(colors, content)
}

/**
 * 设置页配色。没有默认值：在 [ProvideGroupedColors] / [ProvideReaderColors] 外面读会直接报错——
 * 以前默认给一套浅色，深色模式下读到它也不报错，黑字画在深灰卡片上、强调色固定成蓝色。
 */
internal val LocalGroupedColors = staticCompositionLocalOf<GroupedColors> {
    error("LocalGroupedColors 没有提供：在 ProvideGroupedColors / ProvideReaderColors 里面读（弹层的内容画在最外层宿主里，要在调用处读好再传进去）")
}

/** 外面有没有提供 [LocalGroupedColors]：弹层要把调用处的配色带进宿主时用。 */
private val LocalGroupedColorsProvided = staticCompositionLocalOf { false }

/** 调用处提供的配色；没有提供时为 null（不报错）。 */
@Composable
internal fun currentGroupedColorsOrNull(): GroupedColors? = if (LocalGroupedColorsProvided.current) LocalGroupedColors.current else null

/** 直接提供一套配色（同时记下“已经提供过”）。 */
@Composable
internal fun ProvideGroupedColorsValue(colors: GroupedColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGroupedColors provides colors, LocalGroupedColorsProvided provides true, content = content)
}

/**
 * 调用处的配色；外面没有提供时按当前 miuix 主题现算一套。
 * 对话框、面板、菜单的内容画在最外层的弹层宿主里，拿不到调用处的 CompositionLocal，
 * 要在调用处取好（[com.xycs.lingyuereader.ui.glass.GlassDialog] 这样做），再在弹层里用 [ProvideGroupedColorsValue] 提供。
 */
@Composable
internal fun currentGroupedColorsOrTheme(): GroupedColors {
    currentGroupedColorsOrNull()?.let { return it }
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme, dark) { scheme.grouped(scheme.primary, dark) }
}

/**
 * 提供设置页配色：深浅和当前 miuix 主题一致时直接用主题的配色（含 Monet），
 * 阅读页的面板按书页深浅另传 [dark]，与主题不一致时用纸色方案。
 */
@Composable
internal fun ProvideGroupedColors(
    dark: Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f,
    tint: Color = MiuixTheme.colorScheme.primary,
    elevated: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val themeDark = scheme.surface.luminance() < 0.5f
    val colors = remember(scheme, dark, tint, elevated) {
        if (themeDark == dark) scheme.grouped(tint, dark) else groupedColors(dark, tint, elevated)
    }
    ProvideGroupedColorsValue(colors, content)
}

/** 一组的内容：用 [row] 一行行加。 */
internal class GroupedSectionScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** [separatorInset] 是苹果式分隔线的缩进，miuix 卡片里不画分隔线，保留参数只为兼容。 */
    @Suppress("UNUSED_PARAMETER")
    fun row(separatorInset: Dp = 0.dp, content: @Composable () -> Unit) {
        rows += content
    }
}

/** 一组：组名（可无）、圆角卡片里的各行、卡片下的说明小字（可无）。 */
@Composable
internal fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: GroupedSectionScope.() -> Unit,
) {
    val c = LocalGroupedColors.current
    val scope = GroupedSectionScope().apply(content)
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        // 组名用灰色小字，不用强调色：组名是标签，不该比里面的内容还抢眼。
        if (header != null) SmallTitle(header, textColor = c.secondary)
        if (scope.rows.isNotEmpty()) {
            Card(
                // 开关带出附属行、加载完多出几行时，卡片平滑长高，不一下跳开。
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().animateHeightChanges(),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(color = c.cell),
            ) {
                scope.rows.forEach { it() }
            }
        }
        if (footer != null) {
            Text(
                footer,
                color = c.secondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp),
            )
        }
    }
}

/** 图标行（miuix 卡片不画分隔线，和 [GroupedSectionScope.row] 一样）。 */
internal fun GroupedSectionScope.iconRow(content: @Composable () -> Unit) = row(content = content)

/** 一行的外壳：至少 52dp 高，左右 16dp；紧凑显示并保留足够的触控范围。 */
@Composable
internal fun GroupedRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val clickable = when {
        onClick != null && onLongClick != null ->
            Modifier.combinedClickable(interactionSource = source, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
        onClick != null -> Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(pressHighlight(pressed))
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 设置入口图标：淡色底托住细线图形，不用高饱和实心方块抢过文字。 */
@Composable
internal fun RowIcon(icon: ImageVector, background: Color) {
    val c = LocalGroupedColors.current
    val ink = lerp(c.label, background, 0.5f).readableOn(listOf(c.cell, c.background))
    val fill = lerp(c.cell, ink, if (c.dark) 0.20f else 0.12f)
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
    }
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun RowScope.RowTitle(title: String, color: Color, enabled: Boolean, subtitle: String? = null, subtitleMaxLines: Int = Int.MAX_VALUE) {
    val c = LocalGroupedColors.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = if (enabled) color else c.tertiary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = c.secondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** 右边灰色的当前值（miuix 的 onSurfaceVariantActions）。 */
@Composable
private fun RowValue(value: String, enabled: Boolean = true) {
    val c = LocalGroupedColors.current
    Text(
        value,
        color = if (enabled) c.secondary else c.tertiary,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = 12.dp).widthIn(max = 180.dp),
    )
}

/**
 * 点进去的一行：名字（可有说明）、右边当前值、小箭头。
 * [trailing] 放在当前值和箭头之间（[Tag] 这类）；[subtitleMaxLines] 限制说明的行数（列表里的长摘要）；可以长按。
 */
@Composable
internal fun NavRow(
    title: String,
    value: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = Color.Unspecified,
    enabled: Boolean = true,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    onClick: () -> Unit,
) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick, enabled = enabled, onLongClick = onLongClick) {
        if (icon != null) RowIcon(icon, iconBackground)
        RowTitle(title, c.label, enabled, subtitle, subtitleMaxLines)
        if (value != null) RowValue(value, enabled)
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/**
 * 在本页展开、收起一组次要选项的一行（排版设置的“更多排版规则”）：名字、右边灰色的说明（几项），
 * 小箭头收着朝右、展开朝下，转过去用标准缓动（减少动效时直接换）。读屏念“已展开 / 已收起”。
 * 点进二级页的用 [NavRow]；这里只在本页多露几行。
 */
@Composable
internal fun ExpandRow(title: String, expanded: Boolean, value: String? = null, onToggle: () -> Unit) {
    val c = LocalGroupedColors.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, if (Effects.noMotion) snap() else Motion.standard(Motion.SHORT), label = "expandArrow")
    GroupedRow(
        Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        onClick = onToggle,
    ) {
        RowTitle(title, c.label, enabled = true)
        if (value != null) RowValue(value)
        Icon(
            LingYueIcons.ChevronForward,
            contentDescription = null,
            tint = c.tertiary,
            modifier = Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = turn },
        )
    }
}

/** 带开关的一行（miuix 开关）；整行都能点。 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconBackground: Color = Color.Unspecified,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalGroupedColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon, iconBackground)
        RowTitle(title, c.label, enabled, subtitle)
        Spacer(Modifier.width(12.dp))
        LingYueSwitch(checked, enabled)
    }
}

/** 一个动作：强调色文字（删除类用红色）。 */
@Composable
internal fun ActionRow(title: String, destructive: Boolean = false, enabled: Boolean = true, centered: Boolean = false, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick, enabled = enabled) {
        Text(
            title,
            color = when {
                !enabled -> c.tertiary
                destructive -> c.destructive
                else -> c.tint
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = if (centered) TextAlign.Center else null,
        )
    }
}

/** 单选列表里的一项：选中的名字变强调色，右边打勾。 */
@Composable
internal fun CheckRow(
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(pressHighlight(pressed))
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleColor by animateColorAsState(if (selected) c.tint else c.label, Motion.standard(Motion.SHORT), label = "checkTitle")
        RowTitle(title, titleColor, enabled, subtitle)
        // 勾从小弹出来，取消时淡掉。
        AnimatedVisibility(
            visible = selected,
            enter = Effects.enter(scaleIn(Motion.pop(), initialScale = 0.6f) + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(fadeOut(Motion.exit())),
        ) {
            Icon(LingYueIcons.Check, contentDescription = null, tint = c.tint, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}

/**
 * 勾选框一行：左边 miuix 勾选框，右边名字（最多 [titleMaxLines] 行）和一行灰色说明，整行都能点。
 * 勾上的名字淡一点（第三级灰），像清单里做完的事。可以各自勾、不互斥的清单用它（细纲卡的节拍“已写”）；单选用 [CheckRow]。
 * 当选项开关用（对话框里“出 3 个方向的版本”）时 [dimChecked] 传假：勾上不淡。
 */
@Composable
internal fun CheckboxRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    dimChecked: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(pressHighlight(pressed))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, interactionSource = source, indication = null, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 勾选框只是样子：点按和读屏都交给整行。
        Box(Modifier.clearAndSetSemantics {}) { Checkbox(state = ToggleableState(checked), onClick = { onChange(!checked) }, enabled = enabled) }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if ((checked && dimChecked) || !enabled) c.tertiary else c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.secondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/**
 * 对话框里挑一项、项带“做过了”标记（“写这一拍”挑节拍）：名字最多 [titleMaxLines] 行；
 * 做过的（[done]）左边一个成功色小勾、名字淡一点，读屏念 [doneLabel]；选中的名字变强调色、右边打勾，同 [CheckRow]。
 */
@Composable
internal fun MarkedOptionRow(
    title: String,
    selected: Boolean,
    done: Boolean,
    doneLabel: String,
    titleMaxLines: Int = 2,
    onClick: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(pressHighlight(pressed))
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { if (done) stateDescription = doneLabel }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(LingYueIcons.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(16.dp))
        }
        val titleColor by animateColorAsState(
            when {
                selected -> c.tint
                done -> c.tertiary
                else -> c.label
            },
            Motion.standard(Motion.SHORT), label = "markedTitle",
        )
        Text(title, color = titleColor, fontSize = 15.sp, lineHeight = 21.sp, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
        AnimatedVisibility(
            visible = selected,
            enter = Effects.enter(scaleIn(Motion.pop(), initialScale = 0.6f) + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(fadeOut(Motion.exit())),
        ) {
            Icon(LingYueIcons.Check, contentDescription = null, tint = c.tint, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}

/**
 * 选一项的一行（miuix 的下拉选择）：右边是当前值和上下小箭头，点开弹出菜单，选中的打勾。
 * 选项不多（两到六个）时用；选项多或需要说明时用 [CheckRow] 列表单独成页。
 */
@Composable
internal fun <T> PickerRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val c = LocalGroupedColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        GroupedRow(onClick = { expanded = true }, enabled = enabled) {
            RowTitle(title, c.label, enabled)
            RowValue(options.firstOrNull { it.first == selected }?.second.orEmpty(), enabled)
            Icon(LingYueIcons.ChevronDown, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        OverlayListPopup(
            show = expanded,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { expanded = false },
        ) {
            GlassMenu {
                ListPopupColumn {
                    options.forEach { (value, label) ->
                        LingYueMenuItem(label, selected = value == selected) {
                            expanded = false
                            onSelect(value)
                        }
                    }
                }
            }
        }
    }
}

/** 只显示的一行：名字和值。 */
@Composable
internal fun ValueRow(title: String, value: String, icon: ImageVector? = null, iconBackground: Color = Color.Unspecified) {
    val c = LocalGroupedColors.current
    GroupedRow {
        if (icon != null) RowIcon(icon, iconBackground)
        Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
        // 值靠右；太长（比如几个来源文件）就在右边折行。
        Text(value, color = c.secondary, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 230.dp))
    }
}

/** 滑块一行：左右两端放小图标或字（比如小“A”和大“A”），中间是 miuix 滑块；[label] 给读屏用。 */
@Composable
internal fun SliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    steps: Int = 0,
    enabled: Boolean = true,
    valueText: String? = null,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        start?.invoke()
        LingYueSlider(value, onValueChange, valueRange, label, Modifier.weight(1f), steps, enabled, valueText, onValueChangeFinished)
        end?.invoke()
    }
}

/** 上面一行名字和值，下面整宽的滑块（行距、段距这类数值，miuix 设置页的滑块写法）。 */
@Composable
internal fun LabeledSliderRow(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueText, color = c.secondary, fontSize = 14.sp)
        }
        LingYueSlider(value, onValueChange, valueRange, title, Modifier.fillMaxWidth(), steps, valueText = valueText, onValueChangeFinished = onValueChangeFinished)
    }
}

/** miuix 开关，强调色取 [LocalGroupedColors]。只画样子，点击由所在的行处理。 */
@Composable
internal fun LingYueSwitch(checked: Boolean, enabled: Boolean = true) {
    val c = LocalGroupedColors.current
    if (Effects.noMotion) {
        // miuix 的开关滑块总要滑过去；关了动画时画一个不动的，大小、配色照 miuix。
        val track = when {
            checked -> c.switchOn
            c.dark -> Color(0xFF474747)
            else -> Color(0xFFE6E6E6)
        }
        Box(
            Modifier
                .size(width = 49.dp, height = 28.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                .clip(CircleShape)
                .background(track)
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
        }
        return
    }
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.switchColors(checkedTrackColor = c.switchOn),
    )
}

/** miuix 滑块（HyperOS 的粗圆角条），强调色取 [LocalGroupedColors]；读屏能读值、能调。 */
@Composable
internal fun LingYueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    valueText: String? = null,
    /** 松手之后调用一次：拖动途中只改显示，松手才真正生效的设置用。 */
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = LocalGroupedColors.current
    val update by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        modifier = modifier.semantics {
            contentDescription = label
            if (valueText != null) stateDescription = valueText
            progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
            if (enabled) setProgress { target -> update(target.coerceIn(valueRange.start, valueRange.endInclusive)); finished?.invoke(); true }
        },
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.sliderColors(foregroundColor = c.tint),
    )
}

/** 分段控件（miuix 的带轮廓标签栏样子）：浅灰底槽，选中的那格是白块（深色是亮一档的深块）、强调色字；两到五个选项。 */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    onSelect: (T) -> Unit,
) {
    val c = LocalGroupedColors.current
    val index = options.indexOfFirst { it.first == selected }
    val position by animateFloatAsState(index.coerceAtLeast(0).toFloat(), Motion.settle(), label = "segment")
    BoxWithConstraints(modifier.fillMaxWidth().height(maxOf(48.dp, height))) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(height).clip(RoundedCornerShape(height * 0.35f)).background(c.fill))
        val cell = (maxWidth - 6.dp) / options.size
        if (index >= 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((3.dp + cell * position).roundToPx(), 0) }
                    .width(cell)
                    .height(height - 6.dp)
                    .clip(RoundedCornerShape(height * 0.35f - 3.dp))
                    .background(c.segment),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (value, label) ->
                val source = remember { MutableInteractionSource() }
                val color by animateColorAsState(if (i == index) c.segmentLabel else c.segmentIdle, Motion.standard(Motion.SHORT), label = "segmentText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(source, 0.95f)
                        .selectable(selected = i == index, role = Role.Tab, interactionSource = source, indication = null) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = color,
                        fontSize = 13.sp,
                        fontWeight = if (i == index) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 卡片里单独一行的分段控件。 */
@Composable
internal fun <T> SegmentedRow(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SegmentedControl(options, selected, onSelect = onSelect)
    }
}

/** 设置页的整页背景：miuix 的浅灰底（深色纯黑）。 */
@Composable
internal fun GroupedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalGroupedColors.current
    Column(modifier.fillMaxWidth().background(c.background), content = content)
}

/** 行按下时的灰底：按下 / 松开都在 [Motion.SHORT] 里渐变，不是一闪。 */
@Composable
internal fun pressHighlight(pressed: Boolean): Color {
    val c = LocalGroupedColors.current
    return animateColorAsState(if (pressed) c.pressed else c.pressed.copy(alpha = 0f), Motion.standard(Motion.SHORT), label = "press").value
}

/** 读屏时把一组子元素合成一个节点。 */
internal fun Modifier.groupedSemantics(text: String): Modifier = clearAndSetSemantics { contentDescription = text }

/**
 * 空状态：页面中间偏上一个圆底图标、一句标题、一两句说明，可带一个按钮。
 * 用在书架、生词本、搜索这些“还什么都没有”的地方，不只丢一行灰字在左上角。
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariantSummary, modifier = Modifier.size(23.dp))
        }
        Text(title, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (body != null) {
            Text(
                body,
                color = scheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 300.dp),
            )
        }
        if (action != null) {
            top.yukonga.miuix.kmp.basic.Button(
                onClick = action.second,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.padding(top = 16.dp).widthIn(min = 148.dp),
            ) {
                Text(action.first, color = scheme.onPrimary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 内容变高变矮时平滑过去（标准缓动 [Motion.MEDIUM]）：开关带出的附属行、加载完多出来的几行、流式输出。
 * 第一次显示时不动——画完第一帧才接上（接上时按当时的大小起算），免得一打开页面整块先长高一遍；减少动效时不加。
 */
@Composable
internal fun Modifier.animateHeightChanges(): Modifier {
    if (Effects.noMotion) return this
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        ready = true
    }
    return if (ready) animateContentSize(Motion.standard(Motion.MEDIUM)) else this
}

/** 一行：左边名字，右边控件。 */
@Composable
internal fun LabeledRow(title: String, control: @Composable () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        control()
    }
}

/** 只显示的一行：名字，下面一行灰色说明（[subtitleMaxLines] 限行），左边可带行首图标；不能点。 */
@Composable
internal fun InfoRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = Color.Unspecified,
    subtitleMaxLines: Int = Int.MAX_VALUE,
) {
    val c = LocalGroupedColors.current
    GroupedRow {
        if (icon != null) RowIcon(icon, iconBackground)
        RowTitle(title, c.label, true, subtitle, subtitleMaxLines)
    }
}

/** 章号这类步进器的默认上限：五位数，和以前只收五位数字的章号输入框一样。 */
internal const val STEPPER_MAX = 99_999

/**
 * 步进器（－ 数字 ＋）：章号这类小整数。两边是 32dp 的圆底灰钮（能点的范围 48dp），中间的数字点一下就能直接输入（数字键盘）。
 * - [value] 为空是“不填”（只有 [optional] 时才有）：中间是第三级灰色的 [emptyText]，－ 不能点，按 ＋ 从 [start] 开始；
 * - 在最小值上再按 －，回到“不填”；把输入框删空也一样；
 * - 输入超出 [range] 的数收到边上；到了上限 ＋ 变灰；
 * - 不能空的（[optional] 为假）删空后离开输入框，恢复原来的值。
 * [label] 是读屏念的名字（“从第几章起”），两边的钮念成“从第几章起减一”“从第几章起加一”。
 */
@Composable
internal fun NumberStepper(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    range: IntRange = 1..STEPPER_MAX,
    optional: Boolean = true,
    start: Int = range.first,
    emptyText: String = "不填",
) {
    val c = LocalGroupedColors.current
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(value)
    // 输入框里的字跟着外面的值走；删空了、还没填完时先留着自己的。
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val atMin = value != null && value <= range.first
    val canMinus = value != null && (!atMin || optional)
    val canPlus = value == null || value < range.last
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        StepperButton(LingYueIcons.Remove, if (atMin && optional) "${label}改为$emptyText" else "${label}减一", canMinus) {
            onValueChange(if (value == null || value <= range.first) null else value - 1)
        }
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(9)
                if (digits.isEmpty()) {
                    text = ""
                    if (optional && value != null) onValueChange(null)
                } else {
                    val n = digits.toLong().coerceIn(range.first.toLong(), range.last.toLong()).toInt()
                    text = n.toString()
                    if (n != value) onValueChange(n)
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = c.label, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(c.tint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .width(60.dp)
                .onFocusChanged { if (!it.isFocused) text = latest?.toString().orEmpty() }
                .semantics {
                    contentDescription = label
                    stateDescription = value?.toString() ?: emptyText
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) Text(emptyText, color = c.tertiary, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
            },
        )
        StepperButton(LingYueIcons.Add, "${label}加一", canPlus) {
            onValueChange(if (value == null) start.coerceIn(range) else value + 1)
        }
    }
}

/** 步进器两边的钮：看着 32dp 的圆底，能点 48dp；按下缩一点，不能点时图形变成第三级灰。 */
@Composable
private fun StepperButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (enabled) c.label else c.tertiary, Motion.standard(Motion.SHORT), label = "stepper")
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.pressScale(source, 0.9f).size(32.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 放不下时左右滑的一排：哪一边还有没露出来的，哪一边的边缘就渐隐（[fade] 宽），提示能滑；全放得下时两边都不渐隐。
 * 胶囊少、能换行的用 FlowRow；要一格格对齐、人多时一行排不下的（知情格）才用它。
 */
@Composable
internal fun FadingScrollRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    fade: Dp = 28.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.horizontalFadingEdges(state, fade).horizontalScroll(state),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** 横向滚动内容的边缘渐隐：在绘制阶段用 DstIn 把边上一段渐渐抹透明，不改布局；滚到头的那一边不抹。 */
internal fun Modifier.horizontalFadingEdges(state: ScrollState, fade: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = fade.toPx().coerceAtMost(size.width / 2)
            if (state.canScrollBackward) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                    size = Size(w, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollForward) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                    topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

// ---------- 按钮、标签 ----------

/**
 * 整宽的主按钮（“开始阅读”“保存”）：至少 50dp 高、16dp 圆角，强调色底配 [GroupedColors.onTint] 的字；
 * 删除类（[destructive]）用红底。左右和下面的卡片对齐由调用处给边距。
 */
@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val (fill, text) = buttonColors(c, filled = true, enabled = enabled, destructive = destructive)
    val background by animateColorAsState(fill, Motion.standard(Motion.SHORT), label = "primaryFill")
    val content by animateColorAsState(text, Motion.standard(Motion.SHORT), label = "primaryText")
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(source, 0.97f)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** 胶囊按钮的样子。 */
internal enum class PillStyle {
    /** 强调色底、[GroupedColors.onTint] 字：一排里最主要的那个（“采纳”“接在后面”）。 */
    Filled,
    /** 淡强调色底、强调色字：次要但要让人看见的（“复习 N”）。 */
    Tinted,
    /** 灰底、正文色字：其余的（“复制”“再来一次”“不用了”）。 */
    Plain,
}

/**
 * 胶囊按钮：看着 36dp 高、两头全圆，能点的范围至少 48dp（触控大小），一排并列时用 8dp 间距。
 * 按下缩到 0.95 倍；[destructive] 时强调色换成红色。
 */
@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Plain,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val accent = if (destructive) c.destructive else c.tint
    val (fill, text) = when {
        style == PillStyle.Filled || !enabled -> buttonColors(c, filled = style == PillStyle.Filled, enabled = enabled, destructive = destructive)
        style == PillStyle.Tinted -> accent.copy(alpha = 0.12f) to accent
        else -> c.fill to (if (destructive) c.destructive else c.label)
    }
    val background by animateColorAsState(fill, Motion.standard(Motion.SHORT), label = "pillFill")
    val content by animateColorAsState(text, Motion.standard(Motion.SHORT), label = "pillText")
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .pressScale(source, 0.95f)
                .heightIn(min = 36.dp)
                .clip(CircleShape)
                .background(background)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * 实心按钮（主按钮、实心胶囊、对话框的确认键）的底色和字色：强调色配 onTint；删除类红底白字——
 * miuix 的删除色配白字不到 4.5:1（浅色 #E94634 只有 3.9:1），把红压深到够为止，不换成黑字；不可用时灰底灰字。
 */
internal fun buttonColors(c: GroupedColors, filled: Boolean = true, enabled: Boolean = true, destructive: Boolean = false): Pair<Color, Color> = when {
    !enabled -> c.fill to c.tertiary
    !filled -> c.fill to (if (destructive) c.destructive else c.label)
    destructive -> c.destructive.readableOn(listOf(Color.White)) to Color.White
    else -> c.tint to c.onTint
}

/**
 * 小徽标：状态、类别这类一两个词（“定稿”“错字”“规则”）。[color] 是字色，底是它的淡色；
 * 用语义色（[GroupedColors.warning]、[GroupedColors.success]、强调色、次要色），小字才读得清。
 */
@Composable
internal fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

// ---------- 对话框 ----------

/**
 * 对话框底下的“取消 + 确认”一行，两键等宽。会删东西的（[destructive]）确认键用红色；
 * [confirmEnabled] 为假时确认键变灰（比如还没填名字）。
 */
@Composable
internal fun DialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    dismissText: String = "取消",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    val c = LocalGroupedColors.current
    val (fill, text) = buttonColors(c, destructive = destructive)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 取消键用设置页的灰底：阅读页的面板里也跟着书页配色。
        TextButton(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(color = c.fill, textColor = c.label),
        )
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(color = fill, textColor = text),
        )
    }
}

/**
 * 对话框里“选一项”：一行一项，名字（下面可有灰色说明），点了就选。样子照 [CheckRow]，给了 [selected] 时当前那项打勾。
 * “新建什么”这类挑一种做法的对话框用它，不用一格格按钮；选项只有两三个、不用说明的用 [SegmentedControl]。
 */
@Composable
internal fun <T> DialogOptionList(
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    selected: T? = null,
    description: (T) -> String? = { null },
) {
    Column(modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                CheckRow(label, selected = value == selected, subtitle = description(value)) { onPick(value) }
            }
        }
    }
}

/**
 * 对话框里一节的小标题（“AI 写”面板的“写这一拍”“写多少”）：次要色 13sp，和下面选项的文字左对齐；只是标签，不能点。
 * 读屏当标题念，可以按标题跳。
 */
@Composable
internal fun DialogSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalGroupedColors.current.secondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp).semantics { heading() },
    )
}

// ---------- 加载、空、出错 ----------

/**
 * 转圈（miuix 的）。减少动效时（墨水屏 / 省电、系统关了动画）换成不动的三个点：一直转的圈在墨水屏上只会留残影，也费电。
 * 默认 16dp、强调色；阅读页的书页上传书页的次要色。
 */
@Composable
internal fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = LocalGroupedColors.current.tint,
) {
    val busy = Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
    if (Effects.noMotion) {
        Canvas(modifier.then(busy).size(size)) {
            val radius = this.size.minDimension / 10f
            val gap = this.size.width / 3.2f
            for (i in -1..1) drawCircle(color, radius, center + Offset(i * gap, 0f))
        }
    } else {
        InfiniteProgressIndicator(modifier.then(busy), color = color, size = size)
    }
}

/** 卡片里（或面板里）的一行“正在加载”：16dp 转圈、次要色的字。 */
@Composable
internal fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner()
        Text(text, color = c.secondary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** 整页的“正在加载”：放得下时在中间（在列表里时离顶 72dp），转圈下面一行次要色的字。 */
@Composable
internal fun LoadingState(text: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Spinner(size = 28.dp)
        Text(text, color = c.secondary, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}

/** 卡片里的“还没有……”：一行次要色的字，和别的行一样高。整页什么都没有时用 [EmptyState]。 */
@Composable
internal fun EmptyRow(text: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = c.secondary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/**
 * 出错的说明：删除色、14sp。写成“做什么没成：原因”（如“导入失败：文件读不出来”），不另加“出错了”前缀；
 * 出现时读屏会念出来。
 */
@Composable
internal fun ErrorText(text: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Text(
        text,
        color = c.destructive,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** 流程里一步的记号：等着、在做、好了、出错、不做。 */
internal enum class StepMark { WAITING, RUNNING, DONE, FAILED, SKIPPED }

/**
 * 流程里的一步（“这章写完了”的进度）：左边 20dp 的记号——等着是第三级灰空心圈，在做是 [Spinner]，好了是成功色勾，
 * 出错是删除色叉，不做是第三级灰短横；中间名字（不做的变灰），右边一个状态字（[label]），出错时下面一行 [detail] 写原因。
 * 读屏把名字、状态、原因连成一句念；状态变了（在做 → 好了）礼貌地念出来。
 */
@Composable
internal fun StepRow(title: String, mark: StepMark, label: String, detail: String? = null) {
    val c = LocalGroupedColors.current
    val tone = when (mark) {
        StepMark.DONE -> c.success
        StepMark.FAILED -> c.destructive
        StepMark.RUNNING -> c.tint
        StepMark.WAITING, StepMark.SKIPPED -> c.tertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clearAndSetSemantics {
                contentDescription = "$title：$label" + (detail?.let { "，$it" } ?: "")
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                StepMark.RUNNING -> Spinner(size = 18.dp)
                StepMark.DONE -> Icon(LingYueIcons.Check, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
                StepMark.FAILED -> Icon(LingYueIcons.Close, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
                StepMark.WAITING -> Canvas(Modifier.size(12.dp)) {
                    drawCircle(tone, radius = size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                StepMark.SKIPPED -> Box(Modifier.size(width = 10.dp, height = 2.dp).clip(CircleShape).background(tone))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (mark == StepMark.SKIPPED) c.tertiary else c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = if (mark == StepMark.FAILED) c.destructive else c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Text(label, color = tone, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---------- 面板、页面骨架 ----------

/** 面板右上角的关闭钮：32dp 的圆底灰叉，能点的范围 48dp（目录面板、编辑器浮层面板）。 */
@Composable
internal fun PanelCloseButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            Icon(LingYueIcons.Close, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 编辑器里贴在键盘上方的浮层面板（AI 结果、可能的问题、AI 写的那一段、书灵、写作建议、规则）：顶上 20dp 圆角、卡片底色；
 * 标题一行左边可带强调色小图标，[status] 是标题右边的灰色小字（“生成中……”），最右是圆底关闭钮（[onClose] 为空时不显示）；
 * 内容在中间，[actions] 一排胶囊按钮（[PillButton]）在最下面，放不下左右滑。
 * 高矮变化（流式输出、换一条建议）平滑过去；出现、收起由调用处包 AnimatedVisibility（[Motion.edgeEnter] / [Motion.edgeExit]）。
 * 给了 [header] 时不显示标题字，标题行换成这一排控件（写作工具的分段控件），关闭钮仍在最右；[title] 只留给读屏当面板名。
 */
@Composable
internal fun InlinePanel(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    status: String? = null,
    closeLabel: String = "关闭",
    header: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .semantics { paneTitle = title }
            .animateHeightChanges()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (header != null) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = header)
            } else {
                if (icon != null) Icon(icon, contentDescription = null, tint = c.tint, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    status.orEmpty(),
                    color = c.secondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            if (onClose != null) PanelCloseButton(closeLabel, onClose, Modifier.offset(x = 6.dp))
        }
        content()
        if (actions != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** 工具格里的一项：图标、短名字（两到五个字）和点了做什么。 */
internal data class ToolTile(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * 工具格：一格一个“图标 + 短名字”，等宽铺满整行。一行放得下全部（每格不窄于 [minCell]，随字号放大）就排成一行，
 * 放不下就每行 [columns] 格换行，最后一行不满时空格补齐，格子不会被切掉、右边也不留一大块空。
 * 每格看着 52dp 高、12dp 圆角灰底，能点的范围不小于 48dp；读屏念名字。
 */
@Composable
internal fun ToolTileGrid(
    tiles: List<ToolTile>,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    minCell: Dp = 64.dp,
    gap: Dp = 6.dp,
) {
    val c = LocalGroupedColors.current
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fitsOneRow = tiles.size > columns &&
            (maxWidth - gap * (tiles.size - 1)) / tiles.size >= minCell * scale
        val perRow = if (fitsOneRow) tiles.size else columns.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { tile ->
                        val source = remember { MutableInteractionSource() }
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .pressScale(source, 0.95f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.fill)
                                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = tile.onClick)
                                .semantics { contentDescription = tile.label }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(tile.icon, contentDescription = null, tint = c.tint, modifier = Modifier.size(20.dp))
                            Text(
                                tile.label,
                                color = c.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 带字的图标按钮：图标下面一行 10sp 小字写着是什么（“AI”“书灵”“工具”），用在编辑器底栏这种只放得下图标的地方。
 * 能点的范围 48dp 宽、至少 52dp 高；[selected] 时图标和字是强调色，否则次要色。
 * [description] 给读屏（不给就念 [label]）；[onLongClick] 长按时给一句说明，读屏也能从操作菜单里找到。
 */
@Composable
internal fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    selected: Boolean = false,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val color by animateColorAsState(if (selected) c.tint else c.secondary, Motion.standard(Motion.SHORT), label = "labeledIcon")
    Column(
        modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(pressHighlight(pressed))
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = description
                if (selected) this.selected = true
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 面板顶上的标题栏（HyperOS 底部面板的写法）：左边圆形返回钮，中间标题，右边强调色的主操作（“完成”“划线”）。 */
@Composable
internal fun SheetNavBar(title: String, back: String?, action: Pair<String, () -> Unit>? = null, onBack: () -> Unit) {
    val c = LocalGroupedColors.current
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp)) {
        if (back != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回$back"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
                    Icon(LingYueIcons.ChevronBackward, contentDescription = null, tint = c.label, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(title, color = c.label, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
        if (action != null) {
            Text(
                action.first,
                color = c.tint,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClick = action.second)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
    }
}

/**
 * 二级页的骨架：玻璃小标题栏（左边返回，右边 [actions]，文字按钮用 [BarTextAction]）、设置页的浅灰底、提示条。
 * 自己提供 [ProvideGroupedColors]；[content] 拿到的边距已经让出标题栏和系统栏，列表照
 * `contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp)` 来排。
 */
@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = actions,
                )
            },
            snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
            content = content,
        )
    }
}

/** 标题栏右边的文字按钮（“保存”“完成”）：强调色半粗，不能点时变灰。 */
@Composable
internal fun BarTextAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = LocalGroupedColors.current
    Text(
        text,
        color = if (enabled) c.tint else c.tertiary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

/**
 * 长列表里的一格：同一组的格子连成一张圆角卡片（首格上面圆角、末格下面圆角），和 [GroupedSection] 一个样子，
 * 但能懒加载、每格能单独做增删动画（LazyColumn 的 items + animateItem）。[last] 那格下面留出 [gap]：
 * 卡片之间 12dp，下面紧跟说明小字时 8dp（[GroupedListFooter]）。
 */
@Composable
internal fun GroupedListCell(first: Boolean, last: Boolean, modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) {
    val c = LocalGroupedColors.current
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
    Box(modifier.fillMaxWidth().padding(bottom = if (last) gap else 0.dp)) {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(c.cell)) {
            content()
        }
    }
}

/** 长列表的组名：和 [GroupedSection] 的组名一样，卡片上方的灰色小字。 */
@Composable
internal fun GroupedListHeader(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text, modifier = modifier, textColor = LocalGroupedColors.current.secondary)
}

/** 长列表卡片下的说明小字（和 [GroupedSection] 的 footer 一样）：末格用 `gap = 8.dp`，下面再留 12dp。 */
@Composable
internal fun GroupedListFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalGroupedColors.current.secondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
    )
}
