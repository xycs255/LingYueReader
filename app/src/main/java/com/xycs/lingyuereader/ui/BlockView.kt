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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Block

@Composable
internal fun BlockView(block: Block, settings: com.xycs.lingyuereader.data.ReaderSettings, palette: ReaderPalette, fontFamily: FontFamily) {
    val size = settings.fontSize
    val gap = (size * settings.paragraphSpacing).dp
    when (block) {
        is Block.Para -> Text(
            inline(if (block.prose) "　　${block.text}" else block.text),
            color = palette.text,
            fontFamily = fontFamily,
            fontSize = size.sp,
            lineHeight = settings.lineHeight.em,
            letterSpacing = 0.03.em,
            textAlign = TextAlign.Justify,
            modifier = Modifier.padding(bottom = gap),
        )
        is Block.Heading -> Text(
            inline(block.text),
            color = palette.text,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size * if (block.level <= 2) 1.2f else 1.08f).sp,
            lineHeight = 1.5.em,
            modifier = Modifier.padding(top = gap * 1.5f, bottom = gap),
        )
        is Block.Quote -> Row(Modifier.padding(bottom = gap).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.accent.copy(alpha = 0.5f)))
            Text(
                inline(block.text),
                color = palette.secondary,
                fontFamily = fontFamily,
                fontSize = (size * 0.92f).sp,
                lineHeight = settings.lineHeight.em,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        is Block.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp, bottom = gap / 2)) {
            Text(block.marker, color = palette.accent, fontSize = size.sp, lineHeight = settings.lineHeight.em, modifier = Modifier.width((size * 1.6f).dp))
            Text(inline(block.text), color = palette.text, fontFamily = fontFamily, fontSize = size.sp, lineHeight = settings.lineHeight.em)
        }
        is Block.Table -> Column(
            Modifier
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
                            inline(cell),
                            color = palette.text,
                            fontSize = (size * 0.8f).sp,
                            fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.widthIn(min = 64.dp, max = 260.dp).padding(8.dp),
                        )
                    }
                }
                if (r < block.rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.3f)))
            }
        }
        Block.Rule -> Box(
            Modifier.padding(vertical = gap).fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.3f)),
        )
        Block.SceneBreak -> Text(
            "＊　＊　＊",
            color = palette.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = (size * 0.8f).dp),
        )
    }
}

/** 只处理 `**加粗**`，其余原样显示。 */
internal fun inline(text: String): AnnotatedString {
    if ("**" !in text) return AnnotatedString(text)
    return buildAnnotatedString {
        val parts = text.split("**")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1 && i < parts.lastIndex) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
            } else {
                if (i % 2 == 1) append("**")
                append(part)
            }
        }
    }
}
