package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.dict.PinyinAnnotator
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithContent
import com.xycs.lingyuereader.data.displayText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.changedToUp
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
import androidx.compose.ui.text.style.BaselineShift
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

/** 正文里注释角标的注解标签，值是注释的键。 */
internal const val FOOTNOTE_TAG = "footnote"

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
internal class ReaderStyles(
    val settings: ReaderSettings,
    val palette: ReaderPalette,
    val chinese: Boolean = true,
    /** 导入的字体；为空时用内置宋体/黑体。 */
    customFont: FontFamily? = null,
    /** 注音时给汉字配拼音；为空不注音。 */
    val pinyin: PinyinAnnotator? = null,
) {
    /** 注音时每行上方多留出的高度（放拼音），字压到行底。分页按同样的行高算，两边一致。 */
    private val bodyLineHeight = if (pinyin != null) (settings.lineHeight + RUBY_SPACE).em else settings.lineHeight.em
    private val bodyLineStyle = if (pinyin != null) LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None) else null

    private val locale: LocaleList? = if (chinese) LocaleList("zh-CN") else null

    val fontFamily: FontFamily = customFont ?: if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    private val size = settings.fontSize

    /** 正文字重：打开“粗体文本”时半粗（系统字体取粗体，导入的单一字重字体由系统加粗）。 */
    private val bodyWeight: FontWeight? = if (settings.boldText) FontWeight.SemiBold else null

    val gap: Dp = (size * settings.paragraphSpacing).dp
    val horizontalPadding: Dp = settings.margin.dp

    /*
     * 中文排版：
     * - 严格断行（避头尾）：句号、逗号、后引号等不出现在行首，前引号不留在行尾；
     * - chws（相邻标点挤压）：“。”、“，「”这类连续全角标点之间不留整格空白，字体不支持时忽略；
     * - 两端对齐，每行右边缘齐平，可在设置里关掉。
     */
    val para = TextStyle(
        color = palette.text,
        fontFamily = fontFamily,
        fontWeight = bodyWeight,
        fontSize = size.sp,
        lineHeight = bodyLineHeight,
        lineHeightStyle = bodyLineStyle,
        letterSpacing = settings.letterSpacing.em,
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
        fontWeight = bodyWeight,
        fontSize = (size * 0.92f).sp,
        lineHeight = bodyLineHeight,
        lineHeightStyle = bodyLineStyle,
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
    footnoteColor: Color = Color.Unspecified,
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
        // 注释角标：小号上标、强调色；点它弹出注释。
        parsed.footnotes.forEach { (r, key) ->
            val start = r.first + shift
            val end = r.last + 1 + shift
            addStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(0.45f), color = footnoteColor), start, end)
            addStringAnnotation(FOOTNOTE_TAG, key, start, end)
        }
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
 * 文字块自己处理手势：轻点把横向位置交给 [onTap]（翻页/菜单）；长按交给 [selector] 选字，
 * 之后的拖动由阅读区统一跟踪（可以跨段落）。[highlight] 是正文里高亮的选区，带把手时在选区两端显示把手。
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
    highlight: Highlight? = null,
    onTap: ((Float) -> Unit)? = null,
    blockIndex: Int = 0,
    selector: TextSelector? = null,
    onFootnote: ((String) -> Unit)? = null,
) {
    val palette = styles.palette
    val gap = styles.gap
    val selected = highlight?.rangeIn(blockIndex, block.displayText?.length ?: 0)
    val text = remember(block, notes, speaking, selected, styles.settings.indent, palette.accent) {
        blockText(block, notes, speaking, palette.accent.copy(alpha = 0.22f), selected, palette.accent.copy(alpha = 0.38f), styles.settings.indent, palette.accent)
    }
    val shown = if (text != null && range != null) text.subSequence(range.first, range.last + 1) else text
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(selector)
    val footnote by rememberUpdatedState(onFootnote)
    val landing = LocalLanding.current?.takeIf { it.block == blockIndex }
    val base = range?.first ?: 0
    val indent = indentOf(block, styles.settings.indent)
    // 排版里的位置 ↔ 块文字（不含缩进）里的位置。
    val toChar = { layoutOffset: Int -> (layoutOffset + base - indent).coerceAtLeast(0) }
    val toLayout = { char: Int -> char + indent - base }

    @Composable
    fun SelectableText(style: TextStyle, boxModifier: Modifier, justify: Boolean = false) {
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // 登记到选字总调度，手指在别处时也能换算到这里的字。
        val key = remember { Any() }
        fun register() {
            val l = layout ?: return
            val c = coords?.takeIf { it.isAttached } ?: return
            select?.register(key, blockIndex, c, l, toChar, toLayout)
        }
        DisposableEffect(key) { onDispose { select?.unregister(key) } }
        BoxWithConstraints(boxModifier) {
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val density = LocalDensity.current
            val width = constraints.maxWidth
            // 中文两端对齐：把行尾空出的一点宽度摊到字间（翻页时被截断的段落，最后一行也要排满）。
            val display = remember(shown, style, width, justify, isEnd) {
                if (justify && width in 1 until Constraints.Infinity) justifyCjk(shown!!, style, width, measurer, density, justifyLastLine = !isEnd) else shown!!
            }
            // 注音：汉字上方画拼音（标题不注）。
            val ruby = styles.pinyin?.takeIf { style.lineHeightStyle != null }
            val readings = remember(display.text, ruby) { ruby?.annotate(display.text) }
            val rubyColor = palette.secondary
            Text(
                display,
                style = style,
                onTextLayout = {
                    layout = it
                    register()
                },
                modifier = Modifier.drawWithContent {
                    // 刚跳过来的这一段：铺一层淡淡的强调色，慢慢褪掉。
                    val glow = landing?.alpha?.invoke() ?: 0f
                    if (glow > 0f) {
                        val pad = 6.dp.toPx()
                        drawRoundRect(
                            palette.accent.copy(alpha = 0.16f * glow),
                            topLeft = androidx.compose.ui.geometry.Offset(-pad, -pad / 2),
                            size = androidx.compose.ui.geometry.Size(size.width + pad * 2, size.height + pad),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad * 1.5f),
                        )
                    }
                    drawContent()
                    val l = layout
                    if (readings != null && l != null) drawRuby(l, readings, style.fontSize.toPx(), rubyColor)
                }.onGloballyPositioned {
                    coords = it
                    register()
                }.pointerInput(block, base) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var up: PointerInputChange? = null
                        // 到长按时间前手指移出了触摸容差、第二根手指按下或被列表滚动拿走，就不算长按
                        // （横着挪不会让列表滚动、竖着挪不会让翻页模式翻页，这些都要自己判断）。
                        val slop = viewConfiguration.touchSlop
                        val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var gone = false
                            while (!gone) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id }
                                if (c != null && c.changedToUp()) up = c
                                gone = c == null || c.changedToUp() || c.isConsumed || e.changes.size > 1 ||
                                    (c.position - down.position).getDistance() > slop
                            }
                            false
                        } ?: true
                        if (!longPressed) {
                            up?.let {
                                it.consume()
                                val note = layout?.let { l -> footnoteAt(l, it.position) }
                                val open = footnote
                                if (note != null && open != null) open(note) else tap?.invoke(it.position.x / size.width)
                            }
                            return@awaitEachGesture
                        }
                        val l = layout
                        val s = select
                        // 按在行尾之后的空白处不算选字。
                        if (l == null || s == null || down.position.x > l.getLineRight(l.getLineForVerticalPosition(down.position.y))) {
                            return@awaitEachGesture
                        }
                        // 之后的拖动和松手由阅读区跟踪。取手指下面的那个字，而不是离手指最近的字缝。
                        s.press(blockIndex, toChar(charUnder(l, down.position)))
                    }
                },
            )
        }
    }

    // 插图、表格等非文字块只响应轻点（翻页/菜单）。
    val tapOnly = Modifier.pointerInput(block) { detectTapGestures(onTap = { p -> tap?.invoke(p.x / size.width) }) }
    when (block) {
        is Block.Para -> SelectableText(
            styles.para,
            modifier.padding(bottom = if (isEnd) gap else 0.dp),
            justify = styles.settings.justify && styles.chinese,
        )
        is Block.Heading -> SelectableText(styles.heading(block.level), modifier.padding(top = gap * 1.5f, bottom = gap))
        is Block.Quote -> Row(modifier.padding(bottom = gap).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.accent.copy(alpha = 0.5f)))
            SelectableText(styles.quote, Modifier.padding(start = styles.quoteIndent - 3.dp), justify = styles.settings.justify && styles.chinese)
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
        is Block.Image -> {
            val zoom = LocalImageZoom.current
            val gestures = Modifier.pointerInput(block) {
                detectTapGestures(onTap = { p -> tap?.invoke(p.x / size.width) }, onLongPress = { zoom?.invoke(block.src) })
            }
            BookImage(block.src, palette.bar, modifier.then(gestures).padding(bottom = gap))
        }
    }
}

/**
 * 正文里高亮的选区，可以跨段落：从第 [startBlock] 块第 [startChar] 字到第 [endBlock] 块第 [endChar] 字（含）。
 * [handles] 为真时两端显示把手。
 */
/**
 * [p] 落在哪个字上。排版的 getOffsetForPosition 给的是离手指最近的字缝：按在一个字的右半边，
 * 它会给出后一个字的位置，选中的就成了下一个字。这里换成“手指下面那个字”。
 */
internal fun charUnder(l: TextLayoutResult, p: Offset): Int {
    val len = l.layoutInput.text.length
    if (len == 0) return 0
    val line = l.getLineForVerticalPosition(p.y)
    val lineStart = l.getLineStart(line)
    val lineEnd = l.getLineEnd(line, visibleEnd = true).coerceAtLeast(lineStart + 1).coerceAtMost(len)
    var o = l.getOffsetForPosition(p)
    if (o > lineStart && (o >= lineEnd || l.getBoundingBox(o).left > p.x)) o--
    return o.coerceIn(lineStart, lineEnd - 1)
}

/** 刚跳到的一段（第几块），[alpha] 从 1 褪到 0。 */
internal class Landing(val block: Int, val alpha: () -> Float)

internal val LocalLanding = androidx.compose.runtime.staticCompositionLocalOf<Landing?> { null }

internal data class Highlight(val startBlock: Int, val startChar: Int, val endBlock: Int, val endChar: Int, val handles: Boolean) {
    /** 这一块里被选中的范围（块文字长 [length]）；不在选区里返回空。 */
    fun rangeIn(block: Int, length: Int): IntRange? {
        if (block < startBlock || block > endBlock || length <= 0) return null
        val s = if (block == startBlock) startChar else 0
        val e = if (block == endBlock) endChar else length - 1
        return s.coerceIn(0, length - 1)..e.coerceIn(0, length - 1)
    }
}

/** 正文选字的回调。位置都是块文字（不含段首缩进）里的下标。 */
internal interface TextSelector {
    /** 长按某个字，开始选字；之后的移动由阅读区跟踪。 */
    fun press(block: Int, char: Int)

    /** 文字块登记自己的位置和排版；[toChar]、[toLayout] 在排版位置与块文字位置之间换算。 */
    fun register(key: Any, block: Int, coords: LayoutCoordinates, layout: TextLayoutResult, toChar: (Int) -> Int, toLayout: (Int) -> Int)

    fun unregister(key: Any)
}

/**
 * 选区两端的把手，画在阅读区最上层，位置按登记的排版现算，滚动、翻页时跟着走。
 * 不画在段落里面：挂在段末那一行下方的把手会伸出段落，伸出去的部分按不到（系统和 Compose 的把手也都在单独一层）。
 */
@Composable
internal fun SelectionHandles(tracker: SelectionTracker, highlight: Highlight, color: Color) {
    tracker.caret(highlight.startBlock, highlight.startChar, atLineEnd = false)?.let { SelectionHandle(it, isStart = true, color, tracker) }
    tracker.caret(highlight.endBlock, highlight.endChar + 1, atLineEnd = true)?.let { SelectionHandle(it, isStart = false, color, tracker) }
}

/**
 * 一端的把手：起点挂在光标左下，终点挂在右下，像系统的选择把手。看上去 22dp，能按到的范围 44dp 见方，
 * 从行底往下，横向以把手为中心，手指不必按得很准，也不会盖住这一行的字。
 */
@Composable
private fun SelectionHandle(caret: Rect, isStart: Boolean, color: Color, tracker: SelectionTracker) {
    val visual = 22.dp
    val touch = 44.dp
    val current by rememberUpdatedState(caret)
    Box(
        Modifier
            .offset {
                val v = visual.toPx()
                val center = if (isStart) caret.left - v / 2 else caret.left + v / 2
                IntOffset((center - touch.toPx() / 2).roundToInt(), caret.bottom.roundToInt())
            }
            .size(touch)
            .pointerInput(isStart, tracker) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    tracker.beginHandle(isStart, Offset(current.left, current.center.y))
                }
            }
            .semantics { contentDescription = if (isStart) "选区起点" else "选区终点" },
    ) {
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .size(visual)
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

/** 点在注释角标上（角标很小，前后各放宽一个字）时返回注释的键。 */
internal fun footnoteAt(layout: TextLayoutResult, position: androidx.compose.ui.geometry.Offset): String? {
    val text = layout.layoutInput.text
    if (text.isEmpty()) return null
    val offset = layout.getOffsetForPosition(position)
    val from = (offset - 1).coerceAtLeast(0)
    val to = (offset + 1).coerceAtMost(text.length)
    return text.getStringAnnotations(FOOTNOTE_TAG, from, to).firstOrNull()?.item
}

/**
 * 中文两端对齐。系统的 Justify 只拉宽空格，中文行里没有空格，避头尾挪走一个字后行尾就空着。
 * 这里先排一遍，把每行右边空出的宽度平均摊到这一行的字间（加大这一段的字距），再排一遍确认换行位置
 * 完全没变——变了就不动，保证分页、选区、批注的位置都与未拉伸时一致。空得太多（超过三个字）的行也不拉。
 */
internal fun justifyCjk(
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    measurer: TextMeasurer,
    density: Density,
    justifyLastLine: Boolean,
): AnnotatedString {
    if (text.length < 2) return text
    val constraints = Constraints(maxWidth = widthPx)
    val first = measurer.measure(text, style, constraints = constraints)
    val lastLine = if (justifyLastLine) first.lineCount - 1 else first.lineCount - 2
    if (lastLine < 0) return text
    val fontPx = with(density) { style.fontSize.toPx() }
    if (fontPx <= 0f) return text
    val baseEm = if (style.letterSpacing.isEm) style.letterSpacing.value else 0f
    val builder = AnnotatedString.Builder(text)
    var changed = false
    for (line in 0..lastLine) {
        val start = first.getLineStart(line)
        val end = first.getLineEnd(line, visibleEnd = true)
        if (end - start < 2) continue
        val gap = widthPx - first.getLineRight(line)
        if (gap < 0.5f || gap > fontPx * 3f) continue
        // 稍留一点余量，免得舍入误差把最后一个字挤到下一行。
        val extraEm = gap * 0.99f / (end - start - 1) / fontPx
        builder.addStyle(SpanStyle(letterSpacing = (baseEm + extraEm).em), start, end - 1)
        changed = true
    }
    if (!changed) return text
    val justified = builder.toAnnotatedString()
    val second = measurer.measure(justified, style, constraints = constraints)
    val sameBreaks = second.lineCount == first.lineCount &&
        (0 until first.lineCount).all { second.getLineEnd(it) == first.getLineEnd(it) }
    return if (sameBreaks) justified else text
}

/** 注音时每行上方多留的高度（字号的倍数）。 */
internal const val RUBY_SPACE = 0.6f

/** 在每个有拼音的字上方居中画拼音；拼音比字宽时横向压窄，免得和旁边的挤在一起。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRuby(layout: TextLayoutResult, readings: Array<String?>, fontPx: Float, color: Color) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontPx * 0.4f
        textAlign = android.graphics.Paint.Align.CENTER
        this.color = color.toArgb()
    }
    val canvas = drawContext.canvas.nativeCanvas
    val count = minOf(readings.size, layout.layoutInput.text.length)
    for (i in 0 until count) {
        val r = readings[i] ?: continue
        val box = layout.getBoundingBox(i)
        if (box.width <= 0f) continue
        val baseline = layout.getLineBaseline(layout.getLineForOffset(i))
        paint.textScaleX = 1f
        val w = paint.measureText(r)
        if (w > box.width * 1.08f) paint.textScaleX = (box.width * 1.08f / w).coerceAtLeast(0.55f)
        canvas.drawText(r, box.center.x, baseline - fontPx * 1.02f, paint)
    }
}
