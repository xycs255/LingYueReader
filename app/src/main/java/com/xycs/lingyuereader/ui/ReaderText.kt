package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.InlineText
import com.xycs.lingyuereader.data.Note
import com.xycs.lingyuereader.data.ReaderSettings
import top.yukonga.miuix.kmp.basic.Text

/** 段首缩进两个全角空格。批注位置不含这两个字，渲染时要错开。 */
internal const val INDENT = "　　"

/**
 * 阅读页的排版参数。滚动模式直接用它渲染，翻页模式先用同一套样式量出每页放得下多少行，
 * 所以两边必须取自这里，不能各写一份。
 */
internal class ReaderStyles(val settings: ReaderSettings, val palette: ReaderPalette) {
    val fontFamily: FontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    private val size = settings.fontSize

    val gap: Dp = (size * settings.paragraphSpacing).dp
    val horizontalPadding: Dp = 24.dp

    val para = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontSize = size.sp,
        lineHeight = settings.lineHeight.em,
        letterSpacing = 0.03.em,
        textAlign = TextAlign.Justify,
    )
    val title = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (size * 1.35f).sp,
        lineHeight = (size * 1.35f * 1.4f).sp,
    )
    val titleTop: Dp = 24.dp
    val titleBottom: Dp = 32.dp

    fun heading(level: Int) = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (size * if (level <= 2) 1.2f else 1.08f).sp,
        lineHeight = 1.5.em,
    )
    val quote = TextStyle(
        color = palette.secondary,
        fontFamily = fontFamily,
        fontSize = (size * 0.92f).sp,
        lineHeight = settings.lineHeight.em,
    )
    val quoteIndent: Dp = 15.dp
    val list = para.copy(textAlign = TextAlign.Start)
    val listMarkerWidth: Dp = (size * 1.6f).dp
    val table = TextStyle(color = palette.text, fontSize = (size * 0.8f).sp)
    val sceneBreak = TextStyle(color = palette.secondary, fontSize = size.sp, textAlign = TextAlign.Center)
}

/** 块在阅读页上显示的完整文字（含段首缩进），以及加粗和批注的样式。 */
internal fun blockText(
    block: Block,
    notes: List<Note>,
    speaking: IntRange? = null,
    speakingColor: Color = Color.Unspecified,
    selected: IntRange? = null,
    selectedColor: Color = Color.Unspecified,
): AnnotatedString? {
    val raw = when (block) {
        is Block.Para -> block.text
        is Block.Heading -> block.text
        is Block.Quote -> block.text
        is Block.ListItem -> block.text
        else -> return null
    }
    val parsed = InlineText.parse(raw)
    val shift = if (block is Block.Para && block.prose) INDENT.length else 0
    return buildAnnotatedString {
        if (shift > 0) append(INDENT)
        append(parsed.text)
        parsed.bold.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first + shift, it.last + 1 + shift) }
        notes.forEach { n ->
            val start = (n.start + shift).coerceIn(0, length)
            val end = (n.end + shift).coerceIn(start, length)
            addStyle(
                SpanStyle(
                    background = Color(n.color.argb),
                    textDecoration = if (n.text.isNotBlank()) TextDecoration.Underline else null,
                ),
                start,
                end,
            )
        }
        // 听书时正在读的那一句。
        speaking?.let { r ->
            val start = (r.first + shift).coerceIn(0, length)
            val end = (r.last + 1 + shift).coerceIn(start, length)
            addStyle(SpanStyle(background = speakingColor), start, end)
        }
        // 长按选中、正在查的词。
        selected?.let { r ->
            val start = (r.first + shift).coerceIn(0, length)
            val end = (r.last + 1 + shift).coerceIn(start, length)
            addStyle(SpanStyle(background = selectedColor), start, end)
        }
    }
}

internal fun indentOf(block: Block): Int = if (block is Block.Para && block.prose) INDENT.length else 0

/**
 * 渲染一个块。[range] 不为空时只渲染其中一段文字（翻页模式下一段跨页的情形），
 * [isEnd] 表示这一段是否到块尾，决定要不要留段距。
 *
 * 文字块自己处理点按：轻点把横向位置交给 [onTap]（翻页/菜单），长按把手指下那个字在
 * 显示文字（不含段首缩进）里的位置交给 [onLongPressChar]（选词查词）。
 */
@Composable
internal fun BlockView(
    block: Block,
    styles: ReaderStyles,
    notes: List<Note>,
    modifier: Modifier = Modifier,
    range: IntRange? = null,
    isEnd: Boolean = true,
    speaking: IntRange? = null,
    selected: IntRange? = null,
    onTap: ((Float) -> Unit)? = null,
    onLongPressChar: ((Int) -> Unit)? = null,
) {
    val palette = styles.palette
    val gap = styles.gap
    val text = remember(block, notes, speaking, selected) {
        blockText(block, notes, speaking, palette.accent.copy(alpha = 0.22f), selected, palette.accent.copy(alpha = 0.38f))
    }
    val shown = if (text != null && range != null) text.subSequence(range.first, range.last + 1) else text
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPressChar)
    val base = range?.first ?: 0
    val indent = indentOf(block)
    val gestures = Modifier.pointerInput(block, base) {
        detectTapGestures(
            onTap = { p -> tap?.invoke(p.x / size.width) },
            onLongPress = { p ->
                val l = layout ?: return@detectTapGestures
                // 按在行尾之后的空白处不算选词。
                if (p.x > l.getLineRight(l.getLineForVerticalPosition(p.y))) return@detectTapGestures
                longPress?.invoke((l.getOffsetForPosition(p) + base - indent).coerceAtLeast(0))
            },
        )
    }
    val onLayout: (TextLayoutResult) -> Unit = { layout = it }
    // 插图、表格等非文字块只响应轻点（翻页/菜单）。
    val tapOnly = Modifier.pointerInput(block) { detectTapGestures(onTap = { p -> tap?.invoke(p.x / size.width) }) }
    when (block) {
        is Block.Para -> Text(
            shown!!,
            style = styles.para,
            onTextLayout = onLayout,
            modifier = modifier.padding(bottom = if (isEnd) gap else 0.dp).then(gestures),
        )
        is Block.Heading -> Text(
            shown!!,
            style = styles.heading(block.level),
            onTextLayout = onLayout,
            modifier = modifier.padding(top = gap * 1.5f, bottom = gap).then(gestures),
        )
        is Block.Quote -> Row(modifier.padding(bottom = gap).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.accent.copy(alpha = 0.5f)))
            Text(shown!!, style = styles.quote, onTextLayout = onLayout, modifier = Modifier.padding(start = styles.quoteIndent - 3.dp).then(gestures))
        }
        is Block.ListItem -> Row(modifier.padding(start = (block.indent * 16).dp, bottom = gap / 2)) {
            Text(block.marker, style = styles.list.copy(color = palette.accent), modifier = Modifier.width(styles.listMarkerWidth))
            Text(shown!!, style = styles.list, onTextLayout = onLayout, modifier = gestures)
        }
        is Block.Table -> Column(
            modifier
                .then(tapOnly)
                .padding(bottom = gap)
                .horizontalScroll(rememberScrollState())
                .border(1.dp, palette.secondary.copy(alpha = 0.4f)),
        ) {
            block.rows.forEachIndexed { r, row ->
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .background(if (r == 0) palette.bar else palette.background),
                ) {
                    row.forEachIndexed { c, cell ->
                        if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(palette.secondary.copy(alpha = 0.3f)))
                        Text(
                            InlineText.parse(cell).text,
                            style = styles.table.copy(fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
                            modifier = Modifier.widthIn(min = 64.dp, max = 260.dp).padding(8.dp),
                        )
                    }
                }
                if (r < block.rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.3f)))
            }
        }
        Block.Rule -> Box(
            modifier.then(tapOnly).padding(vertical = gap).fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.3f)),
        )
        Block.SceneBreak -> Text(
            "＊　＊　＊",
            style = styles.sceneBreak,
            modifier = modifier.then(tapOnly).fillMaxWidth().padding(vertical = (styles.settings.fontSize * 0.8f).dp),
        )
        is Block.Image -> BookImage(block.src, palette.bar, modifier.then(tapOnly).padding(bottom = gap))
    }
}
