package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** The user's creative decisions, AI's prose, and human acceptance are visibly separate steps. */
@Composable
internal fun DraftWorkflowStrip(active: Int) {
    val c = LocalGroupedColors.current
    val steps = listOf(
        Triple("你的构思", LingYueIcons.Book, 0),
        Triple("AI 拟写法", LingYueIcons.Sparkle, 1),
        Triple("AI 正文草稿", LingYueIcons.Notes, 2),
        Triple("你来审定", LingYueIcons.Check, 3),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        steps.forEachIndexed { index, (label, icon, phase) ->
            DraftStep(label, icon, active >= phase, active == phase, Modifier.weight(1f))
            if (index < steps.lastIndex) {
                Box(Modifier.padding(top = 15.dp).width(7.dp).height(2.dp).background(if (active > phase) c.tint else c.separator))
            }
        }
    }
}

@Composable
private fun DraftStep(label: String, icon: ImageVector, done: Boolean, current: Boolean, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(32.dp).background(if (done) c.tint.copy(alpha = if (current) 0.20f else 0.12f) else c.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (done) c.tint else c.secondary, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            color = if (current) c.label else c.secondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** Choosing a reference is optional; no chapter is silently selected just because it is newest. */
@Composable
internal fun DraftReferenceChoiceDialog(
    analyses: List<ReferenceAnalysis>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalGroupedColors.current
    GlassDialog(
        show = true,
        title = "AI 写作范本",
        summary = "范本只提供叙事和对白技法，人物、事件与句子不进入本书正文。",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            item(key = "none") { CheckRow("不使用范本", selected = selectedId == null, subtitle = "按本章细纲和项目资料写") { onSelect(null) } }
            items(analyses.sortedByDescending { it.createdAt }, key = { it.id }) { analysis ->
                CheckRow(
                    title = "《${analysis.referenceTitle}》 · ${analysis.chapterTitle}",
                    selected = analysis.id == selectedId,
                    subtitle = "${analysis.part.label} · 第 ${analysis.start + 1}—${analysis.end} 字符 · ${whenLabel(analysis.createdAt)}分析",
                ) { onSelect(analysis.id) }
            }
        }
        if (analyses.isEmpty()) Text("本章还没有范本分析。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        ActionRow("新增或分析范本", onClick = onCreate)
        DialogOkButton("关闭", primary = false, onClick = onDismiss)
    }
}
