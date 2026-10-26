package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** The human supplies the scene intent; AI writes a candidate and the human decides whether to accept it. */
@Composable
internal fun WritingCompanionPanel(
    source: String?,
    model: String,
    ready: Boolean,
    onWriteHere: () -> Unit,
    onWriteChapter: () -> Unit,
    onSelectReference: () -> Unit,
    onClearReference: () -> Unit,
    onConfigureAi: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    InlinePanel(
        title = "书灵写作",
        icon = LingYueIcons.Book,
        status = if (source == null) model else "$model · $source",
        onClose = onClose,
        actions = {
            if (ready) PillButton("交给 AI 写这一段", onWriteHere, style = PillStyle.Filled)
            else PillButton("设置 AI", onConfigureAi, style = PillStyle.Filled)
            PillButton("写本章草稿", onWriteChapter)
            PillButton(if (source == null) "选择范本" else "换范本", onSelectReference)
            if (source != null) PillButton("不用范本", onClearReference)
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("你的构思", color = c.secondary, fontSize = 12.sp)
            Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary)
            Text("AI 写候选稿", color = c.tint, fontSize = 12.sp)
            Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary)
            Text("你来审定", color = c.secondary, fontSize = 12.sp)
        }
        Text(
            if (source == null) "先说这一段要发生什么。范本可选；AI 写出的文字会先给你审定。"
            else "范本只借叙事与对白手法；人物、事件与结果依本书构思。AI 写出的文字先给你审定。",
            color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}
