package com.xycs.lingyuereader.ui

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
internal fun blockText(block: Block, notes: List<Note>): AnnotatedString? {
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
    }
}

internal fun indentOf(block: Block): Int = if (block is Block.Para && block.prose) INDENT.length else 0

/**
 * 渲染一个块。[range] 不为空时只渲染其中一段文字（翻页模式下一段跨页的情形），
 * [isEnd] 表示这一段是否到块尾，决定要不要留段距。
 */
@Composable
internal fun BlockView(
    block: Block,
    styles: ReaderStyles,
    notes: List<Note>,
    modifier: Modifier = Modifier,
    range: IntRange? = null,
    isEnd: Boolean = true,
) {
    val palette = styles.palette
    val gap = styles.gap
    val text = remember(block, notes) { blockText(block, notes) }
    val shown = if (text != null && range != null) text.subSequence(range.first, range.last + 1) else text
    when (block) {
        is Block.Para -> Text(
            shown!!,
            style = styles.para,
            modifier = modifier.padding(bottom = if (isEnd) gap else 0.dp),
        )
        is Block.Heading -> Text(
            shown!!,
            style = styles.heading(block.level),
            modifier = modifier.padding(top = gap * 1.5f, bottom = gap),
        )
        is Block.Quote -> Row(modifier.padding(bottom = gap).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.accent.copy(alpha = 0.5f)))
            Text(shown!!, style = styles.quote, modifier = Modifier.padding(start = styles.quoteIndent - 3.dp))
        }
        is Block.ListItem -> Row(modifier.padding(start = (block.indent * 16).dp, bottom = gap / 2)) {
            Text(block.marker, style = styles.list.copy(color = palette.accent), modifier = Modifier.width(styles.listMarkerWidth))
            Text(shown!!, style = styles.list)
        }
        is Block.Table -> Column(
            modifier
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
            modifier.padding(vertical = gap).fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.3f)),
        )
        Block.SceneBreak -> Text(
            "＊　＊　＊",
            style = styles.sceneBreak,
            modifier = modifier.fillMaxWidth().padding(vertical = (styles.settings.fontSize * 0.8f).dp),
        )
    }
}
