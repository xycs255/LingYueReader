package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ai.AiCost
import com.xycs.lingyuereader.ai.AiUsage
import com.xycs.lingyuereader.SentRequest
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text

/** A small suggestion above the keyboard; the actual text caret and manuscript stay untouched. */
@Composable
internal fun WritingCompanionPanel(
    source: String?,
    model: String,
    text: String,
    loading: Boolean,
    error: String?,
    stale: Boolean,
    ready: Boolean,
    usage: AiUsage?,
    cost: AiCost?,
    request: SentRequest?,
    onAsk: () -> Unit,
    onStop: () -> Unit,
    onSelectReference: () -> Unit,
    onConfigureAi: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    var details by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }
    InlinePanel(
        title = "书灵陪写",
        icon = LingYueIcons.Book,
        status = if (source == null) model else "$model · $source",
        onClose = onClose,
        actions = {
            when {
                loading -> PillButton("停止", onStop)
                source == null -> PillButton("选择范本", onSelectReference, style = PillStyle.Filled)
                !ready -> PillButton("设置 AI", onConfigureAi, style = PillStyle.Filled)
                else -> PillButton(if (text.isBlank()) "看看这段怎么写" else "更新建议", onAsk, style = PillStyle.Filled)
            }
            if (source != null) PillButton("换范本", onSelectReference)
            if (text.isNotBlank()) PillButton("展开", { details = true })
            if (request != null) PillButton("已发送内容", { showSent = true })
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val lead = when {
                error != null -> error
                stale && text.isNotBlank() -> "正文或光标已变化；这条是先前位置的建议，点“更新建议”按当前位置重新看。"
                loading && text.isBlank() -> "正在对照范本与光标附近的正文……"
                source == null -> "先选一章范本并分析，书灵才能依据真实原文给你建议。"
                !ready -> "设置 AI 服务商后，可以在这里按当前正文得到写法建议。"
                text.isBlank() -> "光标放在要写的位置后点击。所选范本片段、本章资料和光标附近正文会交给当前 AI；联网模型可能产生费用，建议不会改正文。"
                else -> text
            }
            Text(lead, color = if (error == null) c.label else c.warning, fontSize = 14.sp,
                lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (text.isNotBlank()) UsageNote(usage, cost, Modifier.padding(top = 4.dp))
        }
    }
    if (details && text.isNotBlank()) {
        GlassDialog(show = true, title = "书灵陪写 · $source", summary = model,
            onDismissRequest = { details = false }) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                    MarkdownText(text)
                }
            }
            UsageNote(usage, cost)
            DialogOkButton("继续写") { details = false }
        }
    }
    if (showSent) request?.let { SentDialog(it) { showSent = false } }
}
