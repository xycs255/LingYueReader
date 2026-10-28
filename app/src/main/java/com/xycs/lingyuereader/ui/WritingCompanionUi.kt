package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ai.IdeaKind
import top.yukonga.miuix.kmp.basic.Text

/**
 * 书灵：只管两件事——问主意和选范本。写正文不在这里，从 AI 条“AI 写…”或智能推荐进；
 * 选好的范本在“AI 写”面板里可以开关。问主意只给建议，不改稿子，结果在 AI 面板里看、可以插到光标处。
 * 主意按钮放不下时换行，不横着溢出。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WritingCompanionPanel(
    /** 选的范本（“《书名》·章名（哪部分）”）；没选为空。 */
    source: String?,
    model: String,
    ready: Boolean,
    /** 选过的范本已经不在了。 */
    missing: Boolean = false,
    onIdea: (IdeaKind) -> Unit,
    onSelectReference: () -> Unit,
    onClearReference: () -> Unit,
    onConfigureAi: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    InlinePanel(
        title = "书灵",
        icon = LingYueIcons.Book,
        status = model,
        onClose = onClose,
        closeLabel = "关闭书灵",
        actions = {
            if (!ready) PillButton("设置 AI", onConfigureAi, style = PillStyle.Filled)
            PillButton(if (source == null) "选择范本" else "换范本", onSelectReference)
            if (source != null || missing) PillButton("不用范本", onClearReference)
        },
    ) {
        CompanionLabel("问主意")
        if (ready) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IdeaKind.entries.forEach { k -> PillButton(k.label, { onIdea(k) }, style = PillStyle.Tinted) }
            }
            Text("根据写到这里的内容和设定包出主意，不会改你的稿子。", color = c.secondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        } else {
            Text("先设置写作 AI，才能问主意。", color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        CompanionLabel("范本")
        Text(
            when {
                missing -> "选过的范本不在了，请重新选择。"
                source == null -> "没选范本。选了以后，“AI 写…”面板里可以照它的写法写。"
                else -> "$source\n“AI 写…”时只借它的叙事与对白手法；人物、事件与结果依本书构思。"
            },
            color = if (missing) c.warning else c.secondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** 书灵面板里一节的小标题。 */
@Composable
private fun CompanionLabel(text: String) {
    Text(text, color = LocalGroupedColors.current.label, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
}
