package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ui.glass.GlassMenu
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/*
 * 选项界面的组件，按苹果“设置”的分组列表来做（规范见 docs/design.md）：
 * - 圆角卡片分组，组与组之间留白，卡片里一行一项、细线分隔；
 * - 每行只写一个短名字，当前值放在右边灰字，解释放到组下面的小字里；
 * - 开关、滑块、分段控件、勾选列表都是同一套样子，阅读页面板和“我的”“主题设置”共用。
 */

/** 分组列表的配色，浅色、深色各一套，取自苹果的系统配色；[tint] 是强调色（选中、链接、滑块）。 */
@Immutable
internal data class GroupedColors(
    val background: Color,
    val cell: Color,
    val pressed: Color,
    val separator: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    /** 分段控件的底槽。 */
    val fill: Color,
    /** 分段控件选中的那一格。 */
    val segment: Color,
    val tint: Color,
    val destructive: Color,
    val switchOn: Color,
    val dark: Boolean,
)

/** [elevated] 为真时是浮在页面上的面板（阅读设置、听书），深色下底色比页面亮一级。 */
internal fun groupedColors(dark: Boolean, tint: Color, elevated: Boolean = false): GroupedColors = if (dark) {
    GroupedColors(
        background = if (elevated) Color(0xFF1C1C1E) else Color(0xFF000000),
        cell = if (elevated) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
        pressed = Color(0xFF3A3A3C),
        separator = Color(0xFF3D3D41),
        label = Color(0xFFFFFFFF),
        secondary = Color(0xFF8D8D93),
        tertiary = Color(0xFF5A5A5F),
        fill = Color(0xFF3A3A3D),
        segment = Color(0xFF68686D),
        tint = tint,
        destructive = Color(0xFFFF453A),
        switchOn = Color(0xFF30D158),
        dark = true,
    )
} else {
    GroupedColors(
        background = Color(0xFFF2F2F7),
        cell = Color(0xFFFFFFFF),
        pressed = Color(0xFFE5E5EA),
        separator = Color(0xFFD8D8DC),
        label = Color(0xFF000000),
        secondary = Color(0xFF8A8A8E),
        tertiary = Color(0xFFC4C4C7),
        fill = Color(0xFFE9E9EB),
        segment = Color(0xFFFFFFFF),
        tint = tint,
        destructive = Color(0xFFFF3B30),
        switchOn = Color(0xFF34C759),
        dark = false,
    )
}

internal val LocalGroupedColors = staticCompositionLocalOf { groupedColors(dark = false, tint = Color(0xFF007AFF)) }

/** 按应用当前深浅色提供分组配色；阅读页的面板按书页深浅另传 [dark]。 */
@Composable
internal fun ProvideGroupedColors(
    dark: Boolean = MiuixTheme.colorScheme.background.luminance() < 0.5f,
    tint: Color = MiuixTheme.colorScheme.primary,
    elevated: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(dark, tint, elevated) { groupedColors(dark, tint, elevated) }
    CompositionLocalProvider(LocalGroupedColors provides colors, content = content)
}

/** 一组的内容：用 [row] 一行行加，行与行之间自动画分隔线。 */
internal class GroupedSectionScope {
    internal val rows = mutableListOf<Pair<Dp, @Composable () -> Unit>>()

    /** [separatorInset]：这一行上方分隔线从左边多远开始（行首有图标时对齐到文字）。 */
    fun row(separatorInset: Dp = ROW_PADDING, content: @Composable () -> Unit) {
        rows += separatorInset to content
    }
}

private val ROW_PADDING = 16.dp
private val ICON_INSET = 58.dp

/** 一组：组名（可无）、圆角卡片里的各行、组下的说明小字（可无）。 */
@Composable
internal fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: GroupedSectionScope.() -> Unit,
) {
    val c = LocalGroupedColors.current
    val scope = GroupedSectionScope().apply(content)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 26.dp)) {
        if (header != null) {
            Text(header, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp))
        }
        if (scope.rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cell)) {
                scope.rows.forEachIndexed { i, (inset, row) ->
                    if (i > 0) Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(c.separator))
                    row()
                }
            }
        }
        if (footer != null) {
            Text(footer, color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

/** 图标行在分组里用这个缩进加，分隔线对齐到文字。 */
internal fun GroupedSectionScope.iconRow(content: @Composable () -> Unit) = row(ICON_INSET, content)

/** 一行的外壳：至少 48dp 高，按下时整行变灰（苹果的高亮），不用水波纹。 */
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
            .heightIn(min = 48.dp)
            .background(if (pressed) c.pressed else Color.Transparent)
            .then(clickable)
            .padding(horizontal = ROW_PADDING, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 设置风格的小圆角方块图标：彩色底、白色图形。 */
@Composable
internal fun RowIcon(icon: ImageVector, background: Color) {
    Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(13.dp))
}

@Composable
private fun RowScope.RowTitle(title: String, color: Color, enabled: Boolean, subtitle: String? = null) {
    val c = LocalGroupedColors.current
    Column(Modifier.weight(1f)) {
        Text(title, color = if (enabled) color else c.tertiary, fontSize = 17.sp)
        if (subtitle != null) Text(subtitle, color = c.secondary, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 点进去的一行：名字、右边灰色的当前值、小箭头。 */
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
        if (value != null) {
            Text(
                value,
                color = c.secondary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 180.dp),
            )
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

/** 带开关的一行；整行都能点。 */
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
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = ROW_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon, iconBackground)
        RowTitle(title, c.label, enabled, subtitle)
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked, enabled)
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
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
            textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
        )
    }
}

/** 单选列表里的一项：选中的右边打勾。 */
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
            .heightIn(min = 48.dp)
            .background(if (pressed) c.pressed else Color.Transparent)
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = ROW_PADDING, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle(title, c.label, enabled, subtitle)
        if (selected) Icon(LingYueIcons.Check, contentDescription = null, tint = c.tint, modifier = Modifier.padding(start = 12.dp).size(20.dp))
    }
}

/**
 * 选一项的一行（苹果的菜单式选择器）：右边灰字是当前值和上下小箭头，点开弹出菜单，选中的打勾。
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
            Text(
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                color = if (enabled) c.secondary else c.tertiary,
                fontSize = 17.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
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
internal fun ValueRow(title: String, value: String) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Text(title, color = c.label, fontSize = 17.sp)
        Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
        // 值靠右；太长（比如几个来源文件）就在右边折行。
        Text(
            value,
            color = c.secondary,
            fontSize = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(max = 230.dp),
        )
    }
}

/** 滑块一行：左右两端放小图标或字（比如小“A”和大“A”），中间是滑块；[label] 给读屏用。 */
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
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = ROW_PADDING, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        start?.invoke()
        IosSlider(
            value,
            onValueChange,
            valueRange,
            label,
            Modifier.weight(1f),
            steps = steps,
            enabled = enabled,
            valueText = valueText,
            onValueChangeFinished = onValueChangeFinished,
        )
        end?.invoke()
    }
}

/** 名字在左、滑块在右、值在最右（行距、段距这类没有直观两端的数值）。 */
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
    SliderRow(
        onValueChangeFinished = onValueChangeFinished,
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        label = title,
        steps = steps,
        valueText = valueText,
        start = { Text(title, color = c.label, fontSize = 17.sp, modifier = Modifier.width(48.dp)) },
        end = { Text(valueText, color = c.secondary, fontSize = 15.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End) },
    )
}

/** 苹果式开关：51×31，白色圆钮，打开时绿色。只画样子，点击由所在的行处理。 */
@Composable
internal fun IosSwitch(checked: Boolean, enabled: Boolean = true) {
    val c = LocalGroupedColors.current
    val progress by animateFloatAsState(if (checked) 1f else 0f, Motion.settle(), label = "switch")
    val track by animateColorAsState(if (checked) c.switchOn else c.fill, Motion.standard(Motion.SHORT), label = "track")
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.5f)),
    ) {
        Box(
            Modifier
                .offset { IntOffset((2.dp.toPx() + progress * 20.dp.toPx()).roundToInt(), 2.dp.roundToPx()) }
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 苹果式滑块：细轨道、强调色填充、白色圆钮。可拖、可点；[steps] 大于 0 时吸附到刻度。 */
@Composable
internal fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    valueText: String? = null,
    /** 松手（或点一下）之后调用一次：拖动途中只改显示，松手才真正生效的设置用。 */
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = LocalGroupedColors.current
    val update by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun snap(v: Float): Float {
        val clamped = v.coerceIn(valueRange.start, valueRange.endInclusive)
        if (steps <= 0) return clamped
        val step = span / (steps + 1)
        return valueRange.start + ((clamped - valueRange.start) / step).roundToInt() * step
    }
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumb = 28.dp
    BoxWithConstraints(
        modifier
            .height(thumb)
            .semantics {
                contentDescription = label
                if (valueText != null) stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                if (enabled) setProgress { target -> update(snap(target)); finished?.invoke(); true }
            }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(valueRange, steps) {
                        val thumbPx = thumb.toPx()
                        fun at(x: Float) = valueRange.start + ((x - thumbPx / 2) / (size.width - thumbPx)).coerceIn(0f, 1f) * span
                        detectTapGestures { update(snap(at(it.x))); finished?.invoke() }
                    }
                    .pointerInput(valueRange, steps) {
                        val thumbPx = thumb.toPx()
                        fun at(x: Float) = valueRange.start + ((x - thumbPx / 2) / (size.width - thumbPx)).coerceIn(0f, 1f) * span
                        detectHorizontalDragGestures(onDragEnd = { finished?.invoke() }) { drag, _ ->
                            drag.consume()
                            update(snap(at(drag.position.x)))
                        }
                    },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val travel = with(density) { (constraints.maxWidth.toFloat() - thumb.toPx()).coerceAtLeast(0f) }
        val x = travel * fraction
        Box(Modifier.padding(horizontal = thumb / 2).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.fill))
        Box(
            Modifier
                .padding(start = thumb / 2)
                .width(with(density) { x.toDp() })
                .height(4.dp)
                .clip(CircleShape)
                .background(if (enabled) c.tint else c.tertiary),
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .size(thumb)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 苹果式分段控件：灰色底槽，选中的那格是浮起的白块；两到五个选项。 */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val c = LocalGroupedColors.current
    val index = options.indexOfFirst { it.first == selected }
    val position by animateFloatAsState(index.coerceAtLeast(0).toFloat(), Motion.settle(), label = "segment")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(c.fill).padding(2.dp),
    ) {
        val cell = maxWidth / options.size
        if (index >= 0) {
            Box(
                Modifier
                    .offset { IntOffset((cell * position).roundToPx(), 0) }
                    .width(cell)
                    .fillMaxHeight()
                    .shadow(if (c.dark) 0.dp else 2.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.segment),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, (value, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = i == index, role = Role.Tab, interactionSource = null, indication = null) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = c.label,
                        fontSize = 13.sp,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 分组卡片里单独一行的分段控件。 */
@Composable
internal fun <T> SegmentedRow(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        SegmentedControl(options, selected, onSelect = onSelect)
    }
}

/** 分组列表的整页背景：苹果的分组灰底。 */
@Composable
internal fun GroupedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalGroupedColors.current
    Column(modifier.fillMaxWidth().background(c.background), content = content)
}

/** 读屏时把一组子元素合成一个节点。 */
internal fun Modifier.groupedSemantics(text: String): Modifier = clearAndSetSemantics { contentDescription = text }
