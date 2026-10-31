package com.xycs.lingyuereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 项目自己的选项行，选中标记也使用项目图标。[color] 给删除类的红字（菜单画在弹层宿主里，读不到设置页配色，在调用处读好传进来）；
 * 不给时选中用强调色、其余用正文色。
 */
@Composable
internal fun LingYueMenuItem(label: String, selected: Boolean, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val tint = color.takeOrElse { if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface }
    Row(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.Medium))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
        if (selected) Icon(LingYueIcons.Check, contentDescription = "已选中", tint = tint, modifier = Modifier.size(18.dp))
    }
}
