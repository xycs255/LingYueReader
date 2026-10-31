package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Text

/** 编辑器顶栏章名下面那一行的几段：章节状态、字数、保存状态、问题数、“写作建议已开”。 */
internal enum class EditorStatusPart { STATE, WORDS, SAVING, ISSUES, TAIL }

/** 这一行实际露出了哪几段（给测试核对：读屏念的是完整的一句，不跟着省）。 */
internal val EditorStatusShown = SemanticsPropertyKey<List<EditorStatusPart>>("EditorStatusShown")

/** 放不下时先省的在前：“写作建议已开”、字数、保存状态、章节状态。问题数不在里面：它是问题面板的入口，始终显示。 */
private val DROP_ORDER = listOf(EditorStatusPart.TAIL, EditorStatusPart.WORDS, EditorStatusPart.SAVING, EditorStatusPart.STATE)

/**
 * 状态行这次露哪几段：整行放得下就全露；放不下按 [DROP_ORDER] 一段一段省，直到放得下（[fits] 量的是省过以后的那一行）。
 * 360dp 宽的屏上“草稿 · 740 字 · 已保存 · 21 处问题”放不下时，先省字数、再省“已保存”，“21 处问题”一直在。
 */
internal fun editorStatusShown(parts: List<EditorStatusPart>, fits: (List<EditorStatusPart>) -> Boolean): List<EditorStatusPart> {
    var shown = parts
    for (drop in DROP_ORDER) {
        if (fits(shown)) return shown
        shown = shown - drop
    }
    return shown
}

/**
 * 编辑器顶栏的状态行：各段用“ · ”连起来，12sp 次要色，问题数用提醒色（看得出能点）。
 * 保存状态“编辑中 / 已保存”只淡入淡出，不挪动别的字（几种状态都是三个字宽）。读屏照旧念一整行（[semantics] 里给）。
 */
@Composable
internal fun EditorStatusLine(
    texts: Map<EditorStatusPart, String>,
    modifier: Modifier = Modifier,
    semantics: SemanticsPropertyReceiver.() -> Unit = {},
) {
    val c = LocalGroupedColors.current
    val parts = EditorStatusPart.entries.filter { !texts[it].isNullOrEmpty() }
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontSize = TypeScale.Footnote) }
    BoxWithConstraints(modifier) {
        val max = with(LocalDensity.current) { maxWidth.roundToPx() }
        val shown = remember(texts, max) {
            editorStatusShown(parts) { list ->
                measurer.measure(list.joinToString(" · ") { texts.getValue(it) }, style, maxLines = 1, softWrap = false).size.width <= max
            }
        }
        Row(
            Modifier.clearAndSetSemantics {
                semantics()
                this[EditorStatusShown] = shown
            },
        ) {
            shown.forEachIndexed { i, part ->
                val text = texts.getValue(part)
                val dot = if (i > 0) " · " else ""
                when (part) {
                    EditorStatusPart.SAVING -> {
                        if (dot.isNotEmpty()) Text(dot, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1)
                        AnimatedContent(
                            text,
                            transitionSpec = {
                                ContentTransform(Effects.enter(fadeIn(Motion.standard(Motion.SHORT))), Effects.exit(fadeOut(Motion.exit())), sizeTransform = null)
                            },
                            label = "saveState",
                        ) { Text(it, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1) }
                    }
                    EditorStatusPart.ISSUES -> Text(dot + text, color = c.warning, fontSize = TypeScale.Footnote, maxLines = 1)
                    EditorStatusPart.TAIL -> Text(
                        dot + text, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    else -> Text(dot + text, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1)
                }
            }
        }
    }
}
