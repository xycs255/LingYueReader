package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.readerGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

/** 自动滚动的速度范围（每分钟几行）。 */
internal const val AUTO_MIN_LINES = 4f
internal const val AUTO_MAX_LINES = 80f

/** 按个人阅读速度（字/分钟）和每行大约几个字，估算每分钟几行。 */
internal fun autoScrollLinesFor(charsPerMinute: Int, charsPerLine: Float): Float =
    (charsPerMinute / charsPerLine.coerceAtLeast(1f)).coerceIn(AUTO_MIN_LINES, AUTO_MAX_LINES)

/** 翻页模式下，一页 [linesPerPage] 行、每分钟 [lines] 行时，每页停留的毫秒数。 */
internal fun autoPageMillis(linesPerPage: Float, lines: Float): Long =
    (linesPerPage / lines.coerceAtLeast(AUTO_MIN_LINES) * 60_000).toLong().coerceAtLeast(1_000)

/** 自动滚动时的控制条：暂停/继续、减速、加速、停止。 */
@Composable
internal fun AutoScrollBar(
    lines: Float,
    paused: Boolean,
    paged: Boolean,
    palette: ReaderPalette,
    onToggle: () -> Unit,
    onSpeed: (Float) -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 36.dp)
            .fillMaxWidth()
            .readerGlass(RoundedCornerShape(Radius.XLarge), palette, LocalGlassBackdrop.current)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(if (paused) LingYueIcons.Play else LingYueIcons.Pause, contentDescription = if (paused) "继续自动滚动" else "暂停自动滚动", tint = palette.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(if (paused) "已暂停" else if (paged) "自动翻页" else "自动滚动", color = palette.text, fontSize = TypeScale.Caption)
            Text("每分钟 ${lines.toInt()} 行 · 点正文暂停", color = palette.secondary, fontSize = TypeScale.Micro)
        }
        IconButton(onClick = { onSpeed((lines - 2f).coerceAtLeast(AUTO_MIN_LINES)) }) {
            Icon(LingYueIcons.Remove, contentDescription = "慢一点", tint = palette.text)
        }
        IconButton(onClick = { onSpeed((lines + 2f).coerceAtMost(AUTO_MAX_LINES)) }) {
            Icon(LingYueIcons.Add, contentDescription = "快一点", tint = palette.text)
        }
        IconButton(onClick = onStop) {
            Icon(LingYueIcons.Close, contentDescription = "停止自动滚动", tint = palette.text)
        }
    }
}
