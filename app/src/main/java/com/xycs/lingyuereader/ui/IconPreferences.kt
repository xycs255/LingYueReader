package com.xycs.lingyuereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.ui.glass.GlassMenu
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 保留偏好设置行的布局和反馈，只替换它的默认尾部图标。 */
@Composable
internal fun LingYueArrowPreference(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    trailingText: String? = null,
    endIcon: ImageVector = LingYueIcons.ChevronForward,
    onClick: () -> Unit,
) {
    val trailingTint = MiuixTheme.colorScheme.onSurfaceVariantSummary.let { color ->
        if (enabled) color else color.copy(alpha = color.alpha * 0.3f)
    }
    ArrowPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        endActions = {
            if (trailingText != null) {
                Text(
                    trailingText,
                    color = trailingTint,
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                endIcon,
                contentDescription = null,
                tint = trailingTint,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/** 项目自己的选项行，选中标记也使用项目图标。 */
@Composable
internal fun LingYueMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
        if (selected) Icon(LingYueIcons.Check, contentDescription = "已选中", tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** 主题页的选项弹出层：下拉箭头和选中勾号均来自灵阅图标系统。 */
@Composable
internal fun LingYueDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LingYueArrowPreference(
            title = title,
            enabled = enabled,
            trailingText = items.getOrNull(selectedIndex),
            endIcon = LingYueIcons.ChevronDown,
            onClick = { expanded = true },
        )
        OverlayListPopup(
            show = expanded,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { expanded = false },
        ) {
            GlassMenu {
                ListPopupColumn {
                    items.forEachIndexed { index, item ->
                        LingYueMenuItem(item, selected = index == selectedIndex) {
                            expanded = false
                            onSelectedIndexChange(index)
                        }
                    }
                }
            }
        }
    }
}
