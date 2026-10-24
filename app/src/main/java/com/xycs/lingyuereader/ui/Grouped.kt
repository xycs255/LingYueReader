package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
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
    val destructive: Color,
    val switchOn: Color,
    val dark: Boolean,
)

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
    destructive = error,
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
            destructive = Color(0xFFF15B4F),
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
            destructive = Color(0xFFD9412F),
            switchOn = tint,
            dark = false,
        )
    }
}

/** 阅读页里的面板用 [readerGroupedColors]。 */
@Composable
internal fun ProvideReaderColors(palette: ReaderPalette, tint: Color, content: @Composable () -> Unit) {
    val colors = remember(palette, tint) { readerGroupedColors(palette, tint) }
    CompositionLocalProvider(LocalGroupedColors provides colors, content = content)
}

internal val LocalGroupedColors = staticCompositionLocalOf { groupedColors(dark = false, tint = Color(0xFF3482FF)) }

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
    CompositionLocalProvider(LocalGroupedColors provides colors, content = content)
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
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
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

/** 一行的外壳：至少 56dp 高，左右 16dp，按下时整行变灰（miuix 的按压反馈）。 */
@Composable
internal fun GroupedRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val clickable = if (onClick != null) {
        Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
    } else Modifier
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
private fun RowScope.RowTitle(title: String, color: Color, enabled: Boolean, subtitle: String? = null) {
    val c = LocalGroupedColors.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = if (enabled) color else c.tertiary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
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

/** 点进去的一行：名字（可有说明）、右边当前值、小箭头。 */
@Composable
internal fun NavRow(
    title: String,
    value: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = Color.Unspecified,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick, enabled = enabled) {
        if (icon != null) RowIcon(icon, iconBackground)
        RowTitle(title, c.label, enabled, subtitle)
        if (value != null) RowValue(value, enabled)
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
