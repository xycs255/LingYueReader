package com.xycs.lingyuereader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.MethodDialogueLane
import com.xycs.lingyuereader.MethodNarrativeNode
import com.xycs.lingyuereader.WritingMethodMapState
import top.yukonga.miuix.kmp.basic.Text

/** Only the source-verified marks are drawn. Empty evidence stays empty. */
@Composable
internal fun VerifiedMethodMapPanel(state: WritingMethodMapState, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state.loading -> Text("AI 正在为所选原文绘制写法图…", color = c.secondary, fontSize = 13.sp)
            state.error != null -> ErrorText(state.error)
            state.stale -> Text("范本或本章资料变了，请重新生成写法图。", color = c.secondary, fontSize = 13.sp)
            else -> {
                Text("叙事路径 · AI 解读所选片段", color = c.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (state.nodes.isEmpty()) {
                    Text("这段没有足够的可核对证据，暂不绘制叙事节点。", color = c.secondary, fontSize = 13.sp)
                } else {
                    state.nodes.forEachIndexed { index, node ->
                        NarrativeMark(node, index < state.nodes.lastIndex)
                    }
                }
                Text("对白推进 · AI 解读所选片段", color = c.label, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                val dialogue = state.dialogue
                if (dialogue == null) {
                    Text("所选原文没有两句可核对发言及局面变化，暂不绘制对白图。", color = c.secondary, fontSize = 13.sp)
                } else {
                    Text("表层话题：${dialogue.topic}", color = c.secondary, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogueMark(dialogue.first, Modifier.weight(1f))
                        DialogueMark(dialogue.second, Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(c.tint, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("话后局面：${dialogue.outcome}", color = c.label, fontSize = 13.sp)
                    }
                    Text("原文短引“${dialogue.outcomeQuote}” · 第 ${dialogue.outcomeStart + 1}—${dialogue.outcomeEnd} 字符",
                        color = c.secondary, fontSize = 11.sp)
                }
                state.note?.let { Text(it, color = c.secondary, fontSize = 11.sp) }
                UsageNote(state.usage, state.cost)
            }
        }
    }
}

@Composable
private fun NarrativeMark(node: MethodNarrativeNode, connected: Boolean) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(11.dp).background(c.tint, CircleShape))
            if (connected) Canvas(Modifier.width(2.dp).height(48.dp)) {
                drawLine(c.tint.copy(alpha = 0.4f), Offset(size.width / 2f, 0f),
                    Offset(size.width / 2f, size.height), strokeWidth = size.width)
            }
        }
        Column(Modifier.weight(1f).background(c.fill, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${node.kind} · ${node.label}", color = c.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("“${node.quote}” · 第 ${node.start + 1}—${node.end} 字符", color = c.secondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DialogueMark(lane: MethodDialogueLane, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(modifier.background(c.fill, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(lane.speaker, color = c.tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("“${lane.quote}”", color = c.label, fontSize = 13.sp)
        Text("第 ${lane.start + 1}—${lane.end} 字符", color = c.secondary, fontSize = 11.sp)
        Text("目的（AI 推断）：${lane.inferredIntent}", color = c.secondary, fontSize = 12.sp)
    }
}
