package com.xycs.lingyuereader.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Utterance
import com.xycs.lingyuereader.data.displayText
import com.xycs.lingyuereader.dict.WordFinder
import kotlinx.coroutines.launch

/**
 * 竖排：字从上往下排成一列，列从右往左排，页也从右往左翻（下一页在左边）。
 *
 * 每个字占一格，格子等大，排版就是数格子：一列放 [rows] 个字，一页放 [columns] 列。
 * 句读、括号、引号换成竖排字形（︒︐「」……），英文字母和数字横躺着；
 * 句号逗号这类不放在列首，排不下时挂在上一列末尾（悬挂）。插图单独占一页。
 */
internal class VerticalLayout(private val rows: Int, private val columns: Int, private val indent: Boolean) {

    enum class Kind { TEXT, ROTATED, TITLE, HEADING }

    /** 一格：显示的字（已换成竖排字形）、它在块文字里的位置（标题、缩进的空格为 -1）。 */
    data class Cell(val text: String, val block: Int, val char: Int, val kind: Kind) {
        /** 这格占到的最后一个字（英文一格放两个）。 */
        val last: Int get() = if (char < 0) char else char + (text.length - 1).coerceAtLeast(0)
    }

    data class VPage(val columns: List<List<Cell>>, val image: String? = null, val imageBlock: Int = -1) {
        private val cells get() = columns.flatten().filter { it.char >= 0 }
        // 整页都是表格时没有带字位的格子：取第一个属于正文块的格子，不然进度会记成第 0 段。
        val firstBlock: Int get() = if (image != null) imageBlock else cells.firstOrNull()?.block
            ?: columns.flatten().firstOrNull { it.block >= 0 }?.block ?: 0
        val firstChar: Int get() = if (image != null) 0 else cells.firstOrNull()?.char ?: 0

        /** 这一页是否含块 [block] 的第 [char] 个字。 */
        fun contains(block: Int, char: Int): Boolean =
            if (image != null) imageBlock == block else cells.any { it.block == block && char in it.char..it.last }

        fun lastPosition(): Pair<Int, Int>? = if (image != null) imageBlock to 0 else cells.lastOrNull()?.let { it.block to it.last }
    }

    fun layout(chapter: Chapter): List<VPage> {
        val pages = mutableListOf<VPage>()
        val page = mutableListOf<List<Cell>>()
        var column = mutableListOf<Cell>()

        fun flushPage() {
            if (page.isNotEmpty()) pages += VPage(page.toList())
            page.clear()
        }
        fun newColumn() {
            page += column.toList()
            column = mutableListOf()
            if (page.size >= columns) flushPage()
        }
        fun startBlock() {
            if (column.isNotEmpty()) newColumn()
        }
        fun put(cell: Cell) {
            if (column.size >= rows) {
                // 句号、逗号、后引号等不放在列首：挂在这一列末尾。
                if (column.size == rows && cell.text.firstOrNull()?.let { it in NO_COLUMN_START } == true) {
                    column += cell
                    newColumn()
                    return
                }
                newColumn()
            }
            column += cell
        }
        /** [positioned] 为假时格子记在块 [block] 下、但没有字位（表格的行不是块文字里的字）。 */
        fun text(s: String, block: Int, kind: Kind, positioned: Boolean = true) {
            fun pos(i: Int) = if (block < 0 || !positioned) -1 else i
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = Character.charCount(cp)
                val c = s[i]
                if (n == 1 && isLatin(c)) {
                    // 一串英文、数字：连成一段，紧挨着中文的空格去掉。
                    var end = i
                    while (end < s.length && (isLatin(s[end]) || (s[end] == ' ' && end + 1 < s.length && isLatin(s[end + 1])))) end++
                    val run = s.substring(i, end)
                    if (run.length <= 2 && run.all { it in '0'..'9' }) {
                        // 一两位数字横着并排放在一格里（纵中横）。
                        put(Cell(run, block, pos(i), kind))
                    } else {
                        // 其余横躺着画，两个半角字占一格。
                        run.chunked(2).forEachIndexed { k, part -> put(Cell(part, block, pos(i + k * 2), Kind.ROTATED)) }
                    }
                    i = end
                    while (i < s.length && s[i] == ' ') i++
                    continue
                }
                val ch = String(Character.toChars(cp))
                if (ch != "\n" && !(c == ' ' && i + 1 < s.length && isLatin(s[i + 1]))) {
                    val upright = n > 1 || WordFinder.isHan(c) || c.code >= 0x2E80 || c in VERTICAL_FORMS || c.isWhitespace()
                    val shown = if (n == 1) VERTICAL_FORMS[c]?.toString() ?: ch else ch
                    // 章名、表格这些不对应块里的字，位置记 -1。
                    put(Cell(shown, block, pos(i), if (upright) kind else Kind.ROTATED))
                }
                i += n
            }
        }

        text(chapter.title, -1, Kind.TITLE)
        // 章名后空一列。
        newColumn()
        newColumn()
        chapter.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Image -> {
                    startBlock()
                    flushPage()
                    pages += VPage(emptyList(), block.src, index)
                }
                is Block.Heading -> {
                    startBlock()
                    text(block.displayText.orEmpty(), index, Kind.HEADING)
                    newColumn()
                }
                Block.Rule, Block.SceneBreak -> {
                    startBlock()
                    newColumn()
                }
                is Block.Table -> {
                    startBlock()
                    block.rows.forEach { row ->
                        // 表格竖排没法对齐，每行一列，格子之间空一格。
                        text(row.joinToString("　"), index, Kind.TEXT, positioned = false)
                        newColumn()
                    }
                }
                else -> {
                    val s = block.displayText ?: return@forEachIndexed
                    startBlock()
                    if (indent && block is Block.Para && block.prose) repeat(2) { put(Cell("", index, -1, Kind.TEXT)) }
                    text(s, index, Kind.TEXT)
                }
            }
        }
        if (column.isNotEmpty()) newColumn()
        flushPage()
        return pages.ifEmpty { listOf(VPage(listOf(emptyList()))) }
    }

    companion object {
        /** 横排标点 → 竖排字形（Unicode 竖排兼容形式）。 */
        val VERTICAL_FORMS: Map<Char, Char> = mapOf(
            '，' to '︐', '、' to '︑', '。' to '︒', '：' to '︓', '；' to '︔', '！' to '︕', '？' to '︖',
            '（' to '︵', '）' to '︶', '｛' to '︷', '｝' to '︸', '〔' to '︹', '〕' to '︺', '【' to '︻', '】' to '︼',
            '《' to '︽', '》' to '︾', '〈' to '︿', '〉' to '﹀', '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
            '“' to '﹁', '”' to '﹂', '‘' to '﹃', '’' to '﹄', '…' to '︙', '—' to '︱', '–' to '︲', '_' to '︳',
        )

        /** 半角英文字母和数字。 */
        fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

        /** 不放在列首的标点（竖排字形）。 */
        val NO_COLUMN_START: Set<Char> = setOf('︐', '︑', '︒', '︓', '︔', '︕', '︖', '︶', '︸', '︺', '︼', '︾', '﹀', '﹂', '﹄')
    }
}

/** 竖排阅读：和左右翻页一样按页翻，只是页从右往左，点左边是下一页。 */
@Composable
internal fun VerticalReader(
    content: BookContent,
    chapterIndex: Int,
    start: PageStart,
    styles: ReaderStyles,
    typeface: Typeface,
    marks: Marks,
    topInset: Dp,
    bottomInset: Dp,
    onTap: (xFraction: Float) -> Unit,
    onLongPress: (block: Int, char: Int) -> Unit,
    onPosition: (block: Int, char: Int, fraction: Float, pageLabel: String) -> Unit,
    onChapterChange: (index: Int, fromEnd: Boolean) -> Unit,
    registerTurner: ((Boolean) -> Unit) -> Unit,
    speaking: Utterance? = null,
    /** 翻到哪了，书页厚度跟着走。 */
    turn: PageTurn? = null,
    /** 正在查的词：块和范围。 */
    selected: Pair<Int, IntRange>? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fontPx = with(density) { styles.settings.fontSize * density.fontScale * density.density }
        val cellH = fontPx * (1.05f + styles.settings.letterSpacing)
        val colW = fontPx * styles.settings.lineHeight
        val paddingPx = with(density) { styles.horizontalPadding.toPx() }
        val topPx = with(density) { topInset.toPx() }
        // 书页厚度：书页夹在两摞纸中间，按两摞之间的宽度排版。
        val inset = LocalBookInset.current
        val widthPx = with(density) { (maxWidth - (inset?.total ?: 0.dp)).toPx() } - paddingPx * 2
        val heightPx = with(density) { (maxHeight - topInset - bottomInset).toPx() }
        val rows = (heightPx / cellH).toInt().coerceAtLeast(4) - 1 // 留一格给悬挂的标点
        val columns = (widthPx / colW).toInt().coerceAtLeast(2)
        val layout = remember(rows, columns, styles.settings.indent) { VerticalLayout(rows, columns, styles.settings.indent) }

        fun pagesOf(index: Int) = content.chapters.getOrNull(index)?.let(layout::layout)
        val chapter = content.chapters[chapterIndex]
        val pages = remember(chapter, layout) { pagesOf(chapterIndex)!! }
        val prev = remember(chapterIndex, content, layout) { pagesOf(chapterIndex - 1) }
        val next = remember(chapterIndex, content, layout) { pagesOf(chapterIndex + 1) }
        val lead = if (prev != null) 1 else 0
        val count = lead + pages.size + if (next != null) 1 else 0

        // 最近停在哪一页的开头：重新排版时按它找回所在的页（见 PagedReader）。
        val settledAt = remember { arrayOfNulls<Pair<Int, Int>>(1) }
        val startPage = remember(pages, start) {
            val at = settledAt[0]
            when {
                at != null -> pages.indexOfLast { p -> p.firstBlock < at.first || (p.firstBlock == at.first && p.firstChar <= at.second) }.coerceAtLeast(0)
                start.fromEnd -> pages.lastIndex
                start.block == null -> 0
                else -> pages.indexOfLast { p ->
                    p.firstBlock < start.block || (p.firstBlock == start.block && p.firstChar <= start.char)
                }.coerceAtLeast(0)
            }
        }
        val pageCount by rememberUpdatedState(count)
        val pager = remember(pages) { PagerState(lead + startPage) { pageCount } }
        val scope = rememberCoroutineScope()
        DisposableEffect(pager) {
            registerTurner { forward ->
                scope.launch {
                    val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, count - 1)
                    if (Effects.noMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = tween(Motion.TURN, easing = Motion.Standard))
                }
            }
            onDispose { }
        }
        LaunchedEffect(speaking, pages) {
            val u = speaking ?: return@LaunchedEffect
            val target = pages.indexOfFirst { it.contains(u.block, u.start) }
            if (target >= 0 && pager.currentPage != lead + target && !pager.isScrollInProgress) pager.scrollToPage(lead + target)
        }
        if (turn != null) {
            LaunchedEffect(pager, pages) {
                turn.chapterShare = 1f / pages.size
                snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction - pager.settledPage }
                    .collect { turn.progress = it.coerceIn(-1f, 1f) }
            }
        }
        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.settledPage }.collect { p ->
                when {
                    prev != null && p == 0 -> onChapterChange(chapterIndex - 1, true)
                    next != null && p == count - 1 -> onChapterChange(chapterIndex + 1, false)
                    else -> {
                        val i = (p - lead).coerceIn(pages.indices)
                        settledAt[0] = pages[i].firstBlock to pages[i].firstChar
                        onPosition(pages[i].firstBlock, pages[i].firstChar, (i + 1f) / pages.size, "${i + 1}/${pages.size} 页")
                    }
                }
            }
        }

        val bold = styles.settings.boldText
        val paint = remember(typeface, fontPx, styles.palette.text, bold) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 粗体文本：取半粗（Android 9 起按字重挑），更早的系统直接用粗体。
                this.typeface = when {
                    !bold -> typeface
                    android.os.Build.VERSION.SDK_INT >= 28 -> android.graphics.Typeface.create(typeface, 600, false)
                    else -> android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                }
                textSize = fontPx
                textAlign = Paint.Align.CENTER
                color = styles.palette.text.toArgb()
            }
        }
        val tap by rememberUpdatedState(onTap)
        val press by rememberUpdatedState(onLongPress)

        // 下一页在左边：倒过来排。
        HorizontalPager(state = pager, reverseLayout = true, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize().clipToBook(inset)) { p ->
            val (pageChapter, page) = when {
                prev != null && p == 0 -> chapterIndex - 1 to prev.last()
                next != null && p == count - 1 -> chapterIndex + 1 to next.first()
                else -> chapterIndex to pages[p - lead]
            }
            val notes = remember(marks, pageChapter) { marks.notes.filter { it.chapterIndex == pageChapter } }
            fun cellAt(x: Float, y: Float, width: Float): VerticalLayout.Cell? {
                val k = ((width - paddingPx - x) / colW).toInt()
                val r = ((y - topPx) / cellH).toInt()
                return page.columns.getOrNull(k)?.getOrNull(r)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .bookPage(inset)
                    .background(styles.palette.background)
                    .semantics { contentDescription = "竖排正文" }
                    .pointerInput(page) {
                        detectTapGestures(
                            // 竖排时左边是下一页：把横向位置反过来交给翻页逻辑。
                            onTap = { tap(1f - it.x / size.width) },
                            onLongPress = { pos ->
                                val cell = cellAt(pos.x, pos.y, size.width.toFloat())
                                if (cell != null && cell.char >= 0 && pageChapter == chapterIndex) press(cell.block, cell.char)
                            },
                        )
                    },
            ) {
                val image = page.image
                if (image != null) {
                    Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset), contentAlignment = Alignment.Center) {
                        BookImage(image, styles.palette.bar)
                    }
                    return@Box
                }
                Canvas(Modifier.fillMaxSize()) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val metrics = paint.fontMetrics
                    val baselineShift = -(metrics.ascent + metrics.descent) / 2
                    val right = size.width - paddingPx
                    page.columns.forEachIndexed { k, column ->
                        val cx = right - (k + 0.5f) * colW
                        column.forEachIndexed { r, cell ->
                            val cy = topPx + (r + 0.5f) * cellH
                            // 划线、听书时正在读的句子、正在查的词：在字后面铺一块底色。
                            val mark = when {
                                cell.char < 0 -> null
                                selected?.first == cell.block && cell.char in selected.second -> styles.palette.accent.copy(alpha = 0.38f)
                                speaking?.block == cell.block && cell.char >= speaking.start && cell.char < speaking.end && pageChapter == chapterIndex ->
                                    styles.palette.accent.copy(alpha = 0.22f)
                                else -> notes.firstOrNull { it.blockIndex == cell.block && cell.char >= it.start && cell.char < it.end }?.let { Color(it.color.argb) }
                            }
                            if (mark != null) {
                                drawRect(mark, topLeft = androidx.compose.ui.geometry.Offset(cx - colW * 0.36f, cy - cellH / 2), size = androidx.compose.ui.geometry.Size(colW * 0.72f, cellH))
                            }
                            if (cell.text.isEmpty()) return@forEachIndexed
                            paint.isFakeBoldText = cell.kind == VerticalLayout.Kind.TITLE || cell.kind == VerticalLayout.Kind.HEADING
                            val rotate = cell.kind == VerticalLayout.Kind.ROTATED ||
                                (cell.text.length == 1 && cell.text[0] in VerticalLayout.VERTICAL_FORMS.values && !paint.hasGlyph(cell.text))
                            val shown = if (rotate && cell.text.length == 1) {
                                // 字体没有竖排字形：用原来的标点横躺着画。
                                VerticalLayout.VERTICAL_FORMS.entries.firstOrNull { it.value == cell.text[0] }?.key?.toString() ?: cell.text
                            } else {
                                cell.text
                            }
                            if (rotate) {
                                canvas.save()
                                canvas.rotate(90f, cx, cy)
                                canvas.drawText(shown, cx, cy + baselineShift, paint)
                                canvas.restore()
                            } else if (shown.length == 2 && shown.all(VerticalLayout::isLatin)) {
                                // 纵中横：两位数字挤进一格宽。
                                val w = paint.measureText(shown)
                                val scale = (fontPx * 0.92f / w).coerceAtMost(1f)
                                paint.textScaleX = scale
                                canvas.drawText(shown, cx, cy + baselineShift, paint)
                                paint.textScaleX = 1f
                            } else {
                                canvas.drawText(shown, cx, cy + baselineShift, paint)
                            }
                        }
                    }
                }
            }
        }
    }
}
