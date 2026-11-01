package com.xycs.lingyuereader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.ReferencePart
import top.yukonga.miuix.kmp.basic.Text

/** The selected source range, measured in the same normalized UTF-16 offsets as the excerpt. */
@Composable
internal fun ReferenceRangeRuler(
    start: Int,
    end: Int,
    chapterLength: Int,
    part: ReferencePart,
    modifier: Modifier = Modifier,
) {
    val c = LocalGroupedColors.current
    val valid = chapterLength > 0 && start in 0 until end && end <= chapterLength
    val description = if (valid) "范本${part.label}，选中第 ${start + 1} 至 $end 字符，全章 $chapterLength 字符"
        else "范本${part.label}，第 ${start + 1} 至 $end 字符，章节长度待读取"
    Column(modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("所选${part.label}", color = c.label, fontSize = TypeScale.Caption)
            Text("第 ${start + 1}—$end 字符" + if (valid) " · 全章 $chapterLength 字符" else "",
                color = c.secondary, fontSize = TypeScale.Footnote)
        }
        if (valid) {
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val thickness = 8.dp.toPx()
                val top = (size.height - thickness) / 2f
                val radius = CornerRadius(thickness / 2f)
                drawRoundRect(c.fill, topLeft = Offset(0f, top), size = Size(size.width, thickness), cornerRadius = radius)
                val left = size.width * start.toFloat() / chapterLength
                val right = size.width * end.toFloat() / chapterLength
                drawRoundRect(c.tint, topLeft = Offset(left, top), size = Size((right - left).coerceAtLeast(2.dp.toPx()), thickness), cornerRadius = radius)
                drawCircle(c.tint, radius = 5.dp.toPx(), center = Offset(left.coerceIn(0f, size.width), size.height / 2f))
                drawCircle(c.tint, radius = 5.dp.toPx(), center = Offset(right.coerceIn(0f, size.width), size.height / 2f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("章首", color = c.tertiary, fontSize = TypeScale.Micro)
                Text("章末", color = c.tertiary, fontSize = TypeScale.Micro)
            }
        }
    }
}
