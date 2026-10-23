package com.xycs.lingyuereader.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.intl.LocaleList
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

/** 严格断行规则（避头尾）。逐行尽量排满（Simple），不做整段平衡：整段平衡会让中文行尾空出好几个字。 */
internal val CJK_LINE_BREAK = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Default,
)

/** OpenType chws：相邻全角标点按上下文挤压成半宽。 */
internal const val CJK_FEATURES = "\"chws\" 1"

/**
 * 阅读页的排版参数。滚动模式直接用它渲染，翻页模式先用同一套样式量出每页放得下多少行，
 * 所以两边必须取自这里，不能各写一份。
 */
/**
 * [chinese] 为真时把正文标成中文：弯引号“”‘’、省略号……、破折号——这些中西文共用的标点，
 * Android 按文字的语言挑字体，标成中文才会用中文字体排成全角；否则跟着系统语言走，英文系统下会变窄。
 */
internal class ReaderStyles(val settings: ReaderSettings, val palette: ReaderPalette, val chinese: Boolean = true) {
    private val locale: LocaleList? = if (chinese) LocaleList("zh-CN") else null

    val fontFamily: FontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    private val size = settings.fontSize

    val gap: Dp = (size * settings.paragraphSpacing).dp
    val horizontalPadding: Dp = 24.dp

    /*
     * 中文排版：
     * - 严格断行（避头尾）：句号、逗号、后引号等不出现在行首，前引号不留在行尾；
     * - chws（相邻标点挤压）：“。”、“，「”这类连续全角标点之间不留整格空白，字体不支持时忽略；
     * - 两端对齐，每行右边缘齐平，可在设置里关掉。
     */
    val para = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontSize = size.sp,
        lineHeight = settings.lineHeight.em,
        letterSpacing = 0.03.em,
        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
        lineBreak = CJK_LINE_BREAK,
        fontFeatureSettings = CJK_FEATURES,
        localeList = locale,
    )
    val title = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (size * 1.35f).sp,
        lineHeight = (size * 1.35f * 1.4f).sp,
        localeList = locale,
    )
    val titleTop: Dp = 24.dp
    val titleBottom: Dp = 32.dp

    fun heading(level: Int) = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (size * if (level <= 2) 1.2f else 1.08f).sp,
        lineHeight = 1.5.em,
        localeList = locale,
    )
    val quote = TextStyle(
        color = palette.secondary,
        fontFamily = fontFamily,
        fontSize = (size * 0.92f).sp,
        lineHeight = settings.lineHeight.em,
        lineBreak = CJK_LINE_BREAK,
        fontFeatureSettings = CJK_FEATURES,
        localeList = locale,
    )
    val quoteIndent: Dp = 15.dp
    val list = para.copy(textAlign = TextAlign.Start)
    val listMarkerWidth: Dp = (size * 1.6f).dp
    val table = TextStyle(color = palette.text, fontSize = (size * 0.8f).sp, localeList = locale)
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
    indent: Boolean = true,
): AnnotatedString? {
    val raw = when (block) {
        is Block.Para -> block.text
        is Block.Heading -> block.text
        is Block.Quote -> block.text
        is Block.ListItem -> block.text
        else -> return null
    }
    val parsed = InlineText.parse(raw)
    val shift = indentOf(block, indent)
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

internal fun indentOf(block: Block, enabled: Boolean = true): Int = if (enabled && block is Block.Para && block.prose) INDENT.length else 0

/**
 * 渲染一个块。[range] 不为空时只渲染其中一段文字（翻页模式下一段跨页的情形），
 * [isEnd] 表示这一段是否到块尾，决定要不要留段距。
 *
 * 文字块自己处理手势：轻点把横向位置交给 [onTap]（翻页/菜单）；长按交给 [selector] 选字——
 * 不松手接着拖就是自由划选。[handles] 为真时在选区两端显示可拖动的把手。
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
    handles: Boolean = false,
    onTap: ((Float) -> Unit)? = null,
    blockIndex: Int = 0,
    selector: TextSelector? = null,
) {
    val palette = styles.palette
    val gap = styles.gap
    val text = remember(block, notes, speaking, selected) {
        blockText(block, notes, speaking, palette.accent.copy(alpha = 0.22f), selected, palette.accent.copy(alpha = 0.38f), styles.settings.indent)
    }
    val shown = if (text != null && range != null) text.subSequence(range.first, range.last + 1) else text
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(selector)
    val base = range?.first ?: 0
    val indent = indentOf(block, styles.settings.indent)
    // 排版里的位置 ↔ 块文字（不含缩进）里的位置。
    val toChar = { layoutOffset: Int -> (layoutOffset + base - indent).coerceAtLeast(0) }
    val toLayout = { char: Int -> char + indent - base }

    @Composable
    fun SelectableText(style: TextStyle, boxModifier: Modifier) {
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // 把带把手的选区在屏幕上的位置报上去，操作条好贴着它摆。滚动时位置变了也会再报。
        fun reportBounds() {
            val l = layout ?: return
            val c = coords?.takeIf { it.isAttached } ?: return
            if (!handles || selected == null) return
            val len = l.layoutInput.text.length
            if (len == 0) return
            val start = toLayout(selected.first).coerceIn(0, len - 1)
            val end = toLayout(selected.last).coerceIn(0, len - 1)
            if (toLayout(selected.last) < 0 || toLayout(selected.first) >= len) return
            val top = l.getLineTop(l.getLineForOffset(start))
            val bottom = l.getLineBottom(l.getLineForOffset(end))
            val origin = c.positionInRoot()
            select?.bounds(blockIndex, Rect(origin.x, origin.y + top, origin.x + c.size.width, origin.y + bottom))
        }
        LaunchedEffect(selected, handles, layout) { reportBounds() }
        Box(boxModifier) {
            Text(
                shown!!,
                style = style,
                onTextLayout = { layout = it },
                modifier = Modifier.onGloballyPositioned {
                    coords = it
                    reportBounds()
                }.pointerInput(block, base) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var up: PointerInputChange? = null
                        val longPressed = try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation() }
                            false
                        } catch (_: PointerEventTimeoutCancellationException) {
                            true
                        }
                        if (!longPressed) {
                            up?.let {
                                it.consume()
                                tap?.invoke(it.position.x / size.width)
                            }
                            return@awaitEachGesture
                        }
                        val l = layout
                        val s = select
                        // 按在行尾之后的空白处不算选字。
                        if (l == null || s == null || down.position.x > l.getLineRight(l.getLineForVerticalPosition(down.position.y))) {
                            return@awaitEachGesture
                        }
                        s.press(blockIndex, toChar(l.getOffsetForPosition(down.position)))
                        drag(down.id) { change ->
                            change.consume()
                            s.dragTo(blockIndex, toChar(l.getOffsetForPosition(change.position)))
                        }
                        // 松手这一下也吃掉，免得外层再当成轻点（取消划选、开菜单）。
                        currentEvent.changes.forEach { it.consume() }
                        s.release()
                    }
                },
            )
            val l = layout
            if (handles && selected != null && l != null && select != null) {
                val start = toLayout(selected.first)
                val end = toLayout(selected.last + 1)
                val len = l.layoutInput.text.length
                if (start in 0..len) SelectionHandle(l, start, isStart = true, palette.accent) { cursor ->
                    select?.moveHandle(blockIndex, true, toChar(cursor))
                }
                if (end in 0..len && end > 0) SelectionHandle(l, end, isStart = false, palette.accent) { cursor ->
                    select?.moveHandle(blockIndex, false, toChar(cursor))
                }
            }
        }
    }

    // 插图、表格等非文字块只响应轻点（翻页/菜单）。
    val tapOnly = Modifier.pointerInput(block) { detectTapGestures(onTap = { p -> tap?.invoke(p.x / size.width) }) }
    when (block) {
        is Block.Para -> SelectableText(styles.para, modifier.padding(bottom = if (isEnd) gap else 0.dp))
        is Block.Heading -> SelectableText(styles.heading(block.level), modifier.padding(top = gap * 1.5f, bottom = gap))
        is Block.Quote -> Row(modifier.padding(bottom = gap).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.accent.copy(alpha = 0.5f)))
            SelectableText(styles.quote, Modifier.padding(start = styles.quoteIndent - 3.dp))
        }
        is Block.ListItem -> Row(modifier.padding(start = (block.indent * 16).dp, bottom = gap / 2)) {
            Text(block.marker, style = styles.list.copy(color = palette.accent), modifier = Modifier.width(styles.listMarkerWidth))
            SelectableText(styles.list, Modifier)
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

/** 正文里高亮的选区；[handles] 为真时两端显示把手。 */
internal data class Highlight(val block: Int, val range: IntRange, val handles: Boolean)

/** 正文选字的回调。位置都是块文字（不含段首缩进）里的下标。 */
internal interface TextSelector {
    /** 长按某个字。 */
    fun press(block: Int, char: Int)

    /** 长按后不松手，拖到某个字。 */
    fun dragTo(block: Int, char: Int)

    /** 松手。 */
    fun release()

    /** 拖动选区一端的把手；[cursor] 是字与字之间的光标位置。 */
    fun moveHandle(block: Int, start: Boolean, cursor: Int)

    /** 带把手的选区在根布局里的范围（从首行顶到末行底）。 */
    fun bounds(block: Int, rect: Rect)
}

/** 选区一端的把手：挂在字的下方，拖动时按手指所在的位置换算成光标。 */
@Composable
private fun SelectionHandle(layout: TextLayoutResult, offset: Int, isStart: Boolean, color: Color, onMove: (Int) -> Unit) {
    val density = LocalDensity.current
    val size = 22.dp
    val sizePx = with(density) { size.toPx() }
    val rect = layout.getCursorRect(offset)
    // 起点把手挂在光标左下，终点把手挂在右下，像系统的选择把手。
    val anchor = Offset(if (isStart) rect.left - sizePx else rect.left, rect.bottom)
    val move by rememberUpdatedState(onMove)
    val currentLayout by rememberUpdatedState(layout)
    val currentOffset by rememberUpdatedState(offset)
    Box(
        Modifier
            .offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }
            .size(size)
            .pointerInput(isStart) {
                // 从光标所在行的中线出发，跟着手指的位移找字。
                var target = Offset.Zero
                detectDragGestures(
                    onDragStart = { target = currentLayout.getCursorRect(currentOffset).center },
                    onDrag = { change, amount ->
                        change.consume()
                        target += amount
                        move(currentLayout.getOffsetForPosition(target))
                    },
                )
            }
            .semantics { contentDescription = if (isStart) "选区起点" else "选区终点" },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(
                    if (isStart) RoundedCornerShape(topStart = 0.dp, topEnd = 50.dp, bottomStart = 50.dp, bottomEnd = 50.dp)
                    else RoundedCornerShape(topStart = 50.dp, topEnd = 0.dp, bottomStart = 50.dp, bottomEnd = 50.dp),
                )
                .background(color),
        )
    }
}

/** 粗看一本书是不是中文：取开头几段，汉字占非空白字符的三成以上。 */
internal fun looksChinese(blocks: List<Block>): Boolean {
    var han = 0
    var total = 0
    for (b in blocks.take(30)) {
        for (c in b.plainText) {
            if (c.isWhitespace()) continue
            total++
            if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN) han++
        }
        if (total > 2_000) break
    }
    return total == 0 || han * 10 >= total * 3
}
