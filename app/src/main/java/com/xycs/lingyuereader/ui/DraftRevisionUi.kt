package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.ai.DraftParagraphDiff
import com.xycs.lingyuereader.ai.TextDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

internal enum class DraftVersionView { CURRENT, COUNTERPART, DIFFERENCE }

private sealed interface DraftDiffUiState {
    data object Loading : DraftDiffUiState
    data object Unaligned : DraftDiffUiState
    data class Aligned(val pieces: List<DraftParagraphDiff.Piece>) : DraftDiffUiState
}

/**
 * The comparison runs off the UI thread; an unalignable rewrite shows two complete texts.
 * [onReject] 不为空时，当前稿每段下面有“这段不行”（参数是这段在草稿里是第几行），[rejectEnabled] 为假时按不了（在写、在审读）。
 */
@Composable
internal fun DraftRevisionBody(view: DraftVersionView, current: String, counterpart: String?, counterpartLabel: String,
                               onReject: ((Int) -> Unit)? = null, rejectEnabled: Boolean = true) {
    val c = LocalGroupedColors.current
    val comparison = counterpart?.takeIf { it.isNotBlank() }
    val diff = produceState<DraftDiffUiState>(DraftDiffUiState.Loading, view, current, comparison) {
        if (view == DraftVersionView.DIFFERENCE && comparison != null) {
            val pieces = withContext(Dispatchers.Default) { DraftParagraphDiff.diff(comparison, current) }
            value = if (pieces == null) DraftDiffUiState.Unaligned else DraftDiffUiState.Aligned(pieces)
        }
    }.value

    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (view) {
                DraftVersionView.CURRENT -> if (onReject == null) DraftVersionText(current) else DraftRejectableText(current, rejectEnabled, onReject)
                DraftVersionView.COUNTERPART -> DraftVersionText(comparison ?: current)
                DraftVersionView.DIFFERENCE -> if (comparison == null) DraftVersionText(current)
                else if (diff == DraftDiffUiState.Loading) {
                    Text("正在对齐段落……", color = c.secondary, fontSize = 13.sp)
                } else if (diff == DraftDiffUiState.Unaligned) {
                    Text("段落无法可靠对齐，以下展示两份完整稿件。", color = c.secondary, fontSize = 13.sp)
                    Text(counterpartLabel, color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    DraftVersionText(comparison)
                    Text("当前稿", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                    DraftVersionText(current)
                } else (diff as DraftDiffUiState.Aligned).pieces.forEach { piece ->
                    val changed = piece.kind != TextDiff.Kind.SAME
                    if (changed) Text(if (piece.kind == TextDiff.Kind.DELETED) "对照稿中" else "当前稿中",
                        color = if (piece.kind == TextDiff.Kind.DELETED) c.destructive else c.success,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                    Text(piece.paragraphs.joinToString("\n"),
                        color = c.label, fontSize = 17.sp, lineHeight = 30.sp,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (!changed) c.cell else if (piece.kind == TextDiff.Kind.DELETED)
                                c.destructive.copy(alpha = 0.10f) else c.success.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(if (changed) 8.dp else 0.dp))
                }
            }
        }
    }
}

@Composable
private fun DraftVersionText(text: String) {
    val c = LocalGroupedColors.current
    Text(text, color = c.label, fontSize = 17.sp, lineHeight = 30.sp,
        style = androidx.compose.ui.text.TextStyle(textIndent = TextIndent(firstLine = 2.em)),
        modifier = Modifier.fillMaxWidth())
}

/**
 * 当前稿逐段排，每段右下一个“这段不行”：点了选原因，AI 只重写这一段。
 * 按钮不进选区（长按全选复制稿子时不混进一串“这段不行”）；读屏念出是第几段、段首几个字，几十个按钮分得清。
 */
@Composable
private fun DraftRejectableText(text: String, enabled: Boolean, onReject: (Int) -> Unit) {
    var n = 0
    text.split('\n').forEachIndexed { i, line ->
        if (line.isBlank()) return@forEachIndexed
        n++
        val trimmed = line.trim()
        DraftVersionText(trimmed)
        val label = "这段不行：第 $n 段「${trimmed.take(DRAFT_REJECT_HEAD)}${if (trimmed.length > DRAFT_REJECT_HEAD) "…" else ""}」"
        DisableSelection {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                PillButton("这段不行", onClick = { onReject(i) }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }
}

/** 读屏念“这段不行”时带上段首几个字。 */
private const val DRAFT_REJECT_HEAD = 8
