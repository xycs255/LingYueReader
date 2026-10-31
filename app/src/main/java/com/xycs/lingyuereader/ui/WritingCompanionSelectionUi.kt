package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text

/** Select a saved source range for the AI writer; importing and analysis happen on the reference screen. */
@Composable
internal fun WritingCompanionSelectionDialog(
    analyses: List<ReferenceAnalysis>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalGroupedColors.current
    GlassDialog(
        show = true,
        title = "选择写作范本",
        summary = "选本章已分析的片段。AI 只借叙事与对白手法，按你的构思写新正文。",
        onDismissRequest = onDismiss,
    ) {
        if (analyses.isEmpty()) {
            Text(
                "本章还没有范本分析。可先导入并分析，也可以不用范本写作。",
                color = c.secondary,
                fontSize = TypeScale.Caption,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(analyses.sortedByDescending { it.createdAt }, key = { it.id }) { analysis ->
                    CheckRow(
                        title = "《${analysis.referenceTitle}》 · ${analysis.chapterTitle}",
                        selected = analysis.id == selectedId,
                        subtitle = "${analysis.part.label} · 第 ${analysis.start + 1}—${analysis.end} 字符 · ${whenLabel(analysis.createdAt)}分析",
                    ) { onSelect(analysis.id) }
                }
            }
        }
        ActionRow("新增/分析范本", onClick = onCreate)
        DialogOkButton("关闭", primary = false, onClick = onDismiss)
    }
}
