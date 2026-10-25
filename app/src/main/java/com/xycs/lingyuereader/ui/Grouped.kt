package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.roundToInt

/*
 * 选项界面的组件，照 HyperOS（miuix）的设置页来做（规范见 docs/design.md）：
 * - 页面浅灰底（深色纯黑），一组选项放在一张 16dp 圆角的白卡片（深色 #242424）里，卡片之间留 12dp，不画分隔线；
 * - 组名是卡片上方的蓝灰小字（miuix SmallTitle），解释是卡片下方的灰色小字；
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
)

/** 提醒、成功的基准色：浅色底用深一些的橙和绿，深色底用亮一些的；实际取值再按卡片底调到够对比度。 */
private val WarningLight = Color(0xFFA85500)
private val WarningDark = Color(0xFFFF9F0A)
private val SuccessLight = Color(0xFF15803D)
private val SuccessDark = Color(0xFF30D158)

private fun Colors.grouped(tint: Color, dark: Boolean) = GroupedColors(
    background = surface,
    cell = surfaceContainer,
    pressed = secondaryContainer,
    separator = dividerLine,
    label = onSurface,
    secondary = onSurfaceVariantSummary,
    tertiary = onSurfaceVariantActions,
    fill = secondaryContainer,
    segment = if (dark) secondary else surfaceContainer,
    tint = tint,
    // miuix 自己配的 onPrimary（默认的蓝配白只有 3.6:1，照 HyperOS 留着白字）；
    // 换了别的强调色，或 onPrimary 实在看不清（不到 3:1，比如自选了很浅的强调色）时在黑白里挑。
    onTint = if (tint == primary && contrastRatio(onPrimary, tint) >= 3f) onPrimary else contentColorOn(tint),
    destructive = error,
    warning = (if (dark) WarningDark else WarningLight).readableOn(listOf(surfaceContainer, surface)),
    success = (if (dark) SuccessDark else SuccessLight).readableOn(listOf(surfaceContainer, surface)),
    switchOn = tint,
    dark = dark,
)

/** 不在 Compose 里时（比如给面板定底色）用 miuix 的默认浅色、深色方案。[elevated] 保留给浮在页面上的面板，配色相同。 */
@Suppress("UNUSED_PARAMETER")
internal fun groupedColors(dark: Boolean, tint: Color, elevated: Boolean = false): GroupedColors =
    (if (dark) darkColorScheme() else lightColorScheme()).grouped(tint, dark)

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
            destructive = Color(0xFFF15B4F),
            warning = WarningDark.readableOn(listOf(cell, sheet)),
            success = SuccessDark.readableOn(listOf(cell, sheet)),
            switchOn = tint,
            dark = true,
        )
    } else {
        val sheet = up(bg, 0.45f)
        val cell = up(bg, 0.88f)
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
            destructive = Color(0xFFD9412F),
            // 纸白、宣黄、竹青的面板底各不相同，调到在卡片和面板底上都够 4.5:1。
            warning = WarningLight.readableOn(listOf(cell, sheet)),
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
 * 阅读页的面板按书页深浅另传 [dark]，与主题不一致时用 miuix 默认方案。
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
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        // 组名用灰色小字，不用强调色：组名是标签，不该比里面的内容还抢眼。
        if (header != null) SmallTitle(header, textColor = c.secondary)
        if (scope.rows.isNotEmpty()) {
            Card(
                // 开关带出附属行、加载完多出几行时，卡片平滑长高，不一下跳开。
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().animateHeightChanges(),
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

/** 一行的外壳：至少 56dp 高，左右 16dp，按下时整行变灰（miuix 的按压反馈）；可以长按（[onLongClick]）。 */
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
            .heightIn(min = 56.dp)
            .background(pressHighlight(pressed))
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** HyperOS 设置首页式的图标：彩色圆角方块、白色图形。 */
@Composable
internal fun RowIcon(icon: ImageVector, background: Color) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
    Spacer(Modifier.width(14.dp))
}

@Composable
private fun RowScope.RowTitle(title: String, color: Color, enabled: Boolean, subtitle: String? = null, subtitleMaxLines: Int = Int.MAX_VALUE) {
    val c = LocalGroupedColors.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = if (enabled) color else c.tertiary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** 右边灰色的当前值（miuix 的 onSurfaceVariantActions）。 */
@Composable
private fun RowValue(value: String, enabled: Boolean = true) {
    val c = LocalGroupedColors.current
    Text(
        value,
        color = if (enabled) c.secondary else c.tertiary,
        fontSize = 14.sp,
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
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

/** 分段控件（miuix 的带轮廓标签栏样子）：浅灰底槽，选中的那格是白块、强调色字；两到五个选项。 */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    onSelect: (T) -> Unit,
) {
    val c = LocalGroupedColors.current
    val index = options.indexOfFirst { it.first == selected }
    val position by animateFloatAsState(index.coerceAtLeast(0).toFloat(), Motion.settle(), label = "segment")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height * 0.35f)).background(c.fill).padding(3.dp),
    ) {
        val cell = maxWidth / options.size
        if (index >= 0) {
            Box(
                Modifier
                    .offset { IntOffset((cell * position).roundToPx(), 0) }
                    .width(cell)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height * 0.35f - 3.dp))
                    .background(c.segment),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, (value, label) ->
                val source = remember { MutableInteractionSource() }
                val color by animateColorAsState(if (i == index) c.tint else c.secondary, Motion.standard(Motion.SHORT), label = "segmentText")
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
                        fontSize = 14.sp,
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
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
        modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariantSummary, modifier = Modifier.size(34.dp))
        }
        Text(title, color = scheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        if (body != null) {
            Text(
                body,
                color = scheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
            )
        }
        if (action != null) {
            top.yukonga.miuix.kmp.basic.Button(
                onClick = action.second,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.padding(top = 20.dp).widthIn(min = 160.dp),
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
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        control()
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
            .heightIn(min = 50.dp)
            .pressScale(source, 0.97f)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content,
            fontSize = 17.sp,
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
                .padding(horizontal = 16.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
 * “AI 接着写”“新建什么”这类挑一种做法的对话框用它，不用一格格按钮；选项只有两三个、不用说明的用 [SegmentedControl]。
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

// ---------- 面板、页面骨架 ----------

/** 面板右上角的关闭钮：32dp 的圆底灰叉，能点的范围 44dp（目录面板、编辑器浮层面板）。 */
@Composable
internal fun PanelCloseButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Box(
        modifier
            .size(44.dp)
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
 * 编辑器里贴在键盘上方的浮层面板（AI 结果、可能的问题、AI 接着写、写作建议、规则）：顶上 20dp 圆角、卡片底色；
 * 标题一行左边可带强调色小图标，[status] 是标题右边的灰色小字（“生成中……”），最右是圆底关闭钮（[onClose] 为空时不显示）；
 * 内容在中间，[actions] 一排胶囊按钮（[PillButton]）在最下面，放不下左右滑。
 * 高矮变化（流式输出、换一条建议）平滑过去；出现、收起由调用处包 AnimatedVisibility（[Motion.edgeEnter] / [Motion.edgeExit]）。
 */
@Composable
internal fun InlinePanel(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    status: String? = null,
    closeLabel: String = "关闭",
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .animateHeightChanges()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
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
