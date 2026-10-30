package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.MarkColor
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Note
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

// ---------- 目录 / 书签 / 批注 ----------

internal fun chapterIndexAt(row: Int, count: Int, descending: Boolean): Int = if (descending) count - 1 - row else row
internal fun chapterRowFor(index: Int, count: Int, descending: Boolean): Int = chapterIndexAt(index, count, descending)

/** 目录、书签、批注：顶上分段控件切换，侧栏里用紧凑列表。 */
@Composable
internal fun CatalogSheet(
    chapters: List<Chapter>,
    current: Int,
    marks: Marks,
    palette: ReaderPalette,
    onPickChapter: (Int) -> Unit,
    onPickPosition: (chapter: Int, block: Int, char: Int) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onExportNotes: () -> Unit,
    tint: Color = palette.accent,
    /** 书名、作者，顶上那张小书封用。 */
    bookTitle: String = "",
    author: String? = null,
    /** 全书读到哪、本章读到哪（0..1）。 */
    bookFraction: Float = 0f,
    chapterFraction: Float = 0f,
    /** 每分钟读多少字，估每章、全书还要读多久。 */
    charsPerMinute: Float = 400f,
    onClose: () -> Unit = {},
    /** AI 速读：各章摘要（章下标 → 摘要），显示在章名下面。 */
    summaries: Map<Int, String> = emptyMap(),
    /** 开了 AI 助手时的速读入口；为空不显示。 */
    summary: SummaryControls? = null,
    /** 侧栏模式使用整屏高度；排序只影响章节列表，不改变书的原始章节编号。 */
    sidebar: Boolean = false,
    sortDescending: Boolean = false,
    onSortToggle: () -> Unit = {},
) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        var tab by remember { mutableIntStateOf(0) }
        Column(if (sidebar) Modifier.fillMaxHeight() else Modifier) {
            // 顶上：小书封、书名、作者 · 已读 · 还剩多久，右边关闭。
            Row(Modifier.padding(start = if (sidebar) 16.dp else 20.dp, end = 8.dp, top = if (sidebar) 12.dp else 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebar) MiniCover(bookTitle)
                Column(Modifier.weight(1f).padding(horizontal = if (sidebar) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(bookTitle.ifBlank { "目录" }, color = c.label, fontSize = TypeScale.Headline, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val left = chapters.sumOf { it.charCount.toLong() } * (1f - bookFraction.coerceIn(0f, 1f)) / charsPerMinute
                    Text(
                        listOfNotNull(author?.takeIf { it.isNotBlank() }, "已读 ${(bookFraction * 100).toInt()}%", readingTime(left)?.let { "还剩约 $it" })
                            .joinToString(" · "),
                        color = c.secondary,
                        fontSize = TypeScale.Footnote,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PanelCloseButton("关闭目录", onClose)
            }
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SegmentedControl(
                    listOf(0 to "目录 ${chapters.size}", 1 to "书签 ${marks.bookmarks.size}", 2 to "批注 ${marks.notes.size}"),
                    tab,
                ) { tab = it }
            }
            if (tab == 0) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("章节", color = c.secondary, fontSize = TypeScale.Footnote, modifier = Modifier.weight(1f))
                    Row(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(Radius.Medium))
                            .clickable(role = Role.Button, onClick = onSortToggle)
                            .semantics { contentDescription = "目录排序：${if (sortDescending) "倒序" else "正序"}" }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(LingYueIcons.Sort, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
                        Text(if (sortDescending) "倒序" else "正序", color = c.secondary, fontSize = TypeScale.Caption)
                    }
                }
            }
            // 速读入口只在目录页：跟着标签页收起、展开，不一下把下面的列表顶开。
            AnimatedVisibility(
                visible = tab == 0 && summary != null,
                enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM)) + fadeIn(Motion.standard(Motion.SHORT))),
                exit = Effects.exit(shrinkVertically(Motion.standard(Motion.MEDIUM)) + fadeOut(Motion.exit())),
            ) {
                // 收起动画期间 summary 可能已经为空（关了 AI 助手），这时什么都不画。
                summary?.let { SummaryBar(it) }
            }
            // 三个标签内容跟着上面的分段滑块按方向横移：往右边的标签切，新内容从右边进来，反之从左边；减少动效时直接换。
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it / 4 else -it / 4 } + fadeIn(Motion.standard(Motion.SHORT)))
                        .togetherWith(slideOutHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) -it / 4 else it / 4 } + fadeOut(Motion.exit()))
                },
                label = "catalogTab",
                modifier = (if (sidebar) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(460.dp)).padding(top = 6.dp),
            ) { page ->
                when (page) {
                    0 -> {
                        val state = androidx.compose.runtime.key(sortDescending) {
                            rememberLazyListState((chapterRowFor(current, chapters.size, sortDescending) - 3).coerceAtLeast(0))
                        }
                        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(chapters.size, key = { row -> chapterIndexAt(row, chapters.size, sortDescending) }) { row ->
                                val index = chapterIndexAt(row, chapters.size, sortDescending)
                                val chapter = chapters[index]
                                val now = index == current
                                GroupedListCell(row == 0, row == chapters.size - 1, gap = 0.dp) {
                                    // 一行：序号、章名（宋体）、右边本章要读多久；正在读的那章铺一层淡强调色，写读到百分之几。
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(if (now) c.tint.copy(alpha = 0.07f) else Color.Transparent)
                                            .heightIn(min = 56.dp)
                                            .clickable(role = Role.Button) { onPickChapter(index) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "%02d".format(index + 1),
                                            color = if (now) c.tint else c.secondary,
                                            fontSize = TypeScale.Caption,
                                            modifier = Modifier.width(34.dp).clearAndSetSemantics { },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                chapter.title,
                                                color = c.label,
                                                fontFamily = FontFamily.Serif,
                                                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = TypeScale.Headline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            summaries[index]?.let {
                                                Text(it, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                            }
                                        }
                                        if (now) {
                                            Box(Modifier.padding(start = 8.dp, end = 6.dp).size(6.dp).clip(CircleShape).background(c.tint))
                                            Text("正在读 · ${(chapterFraction * 100).toInt()}%", color = c.tint, fontSize = TypeScale.Footnote)
                                        } else {
                                            readingTime(chapter.charCount / charsPerMinute)?.let {
                                                Text(it, color = c.secondary, fontSize = TypeScale.Footnote, modifier = Modifier.padding(start = 8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> if (marks.bookmarks.isEmpty()) {
                        EmptyHint("还没有书签。在阅读时点顶栏的书签按钮，或长按段落选“加书签”。")
                    } else {
                        val list = marks.bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.itemIndex }))
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(list, key = { _, b -> b.id }) { i, b ->
                                // 删掉一条：它淡出，下面的平滑补上来。
                                GroupedListCell(i == 0, i == list.size - 1, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()), gap = 0.dp) {
                                    MarkRow(
                                        title = if (b.lost) "原文已改动 · ${b.chapterTitle}" else chapters.getOrNull(b.chapterIndex)?.title ?: b.chapterTitle,
                                        body = b.excerpt,
                                        note = null,
                                        color = null,
                                        palette = palette,
                                        onClick = if (b.lost) null else ({ onPickPosition(b.chapterIndex, b.itemIndex - HEADER_ITEMS, b.charOffset) }),
                                        onDelete = { onDeleteBookmark(b.id) },
                                    )
                                }
                            }
                        }
                    }
                    else -> if (marks.notes.isEmpty()) {
                        EmptyHint("还没有批注。长按正文段落即可划线、写批注。")
                    } else {
                        val list = marks.notes.sortedWith(compareBy({ it.lost }, { it.chapterIndex }, { it.blockIndex }, { it.start }))
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            item {
                                GroupedSection { row { ActionRow("导出全部批注（Markdown）", onClick = onExportNotes) } }
                            }
                            itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                                GroupedListCell(i == 0, i == list.size - 1, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()), gap = 0.dp) {
                                    MarkRow(
                                        title = if (n.lost) "原文已改动 · ${n.chapterTitle}" else chapters.getOrNull(n.chapterIndex)?.title ?: n.chapterTitle,
                                        body = n.quote,
                                        note = n.text.ifBlank { null },
                                        color = Color(n.color.argb),
                                        palette = palette,
                                        onClick = if (n.lost) null else ({ onPickPosition(n.chapterIndex, n.blockIndex, n.start) }),
                                        onDelete = { onDeleteNote(n.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 书签、批注还没有时：和列表一样的一张卡片，里面一行灰字（[EmptyRow]），不在面板中间飘一段字。 */
@Composable
private fun EmptyHint(text: String) {
    // 标签页那块是定高的，外面套一层 Box 让卡片按自己的高矮排，不被撑满。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.Large)).background(LocalGroupedColors.current.cell)) {
            EmptyRow(text)
        }
    }
}

@Composable
private fun MarkRow(
    title: String,
    body: String,
    note: String?,
    color: Color?,
    @Suppress("UNUSED_PARAMETER") palette: ReaderPalette,
    onClick: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp)) {
                if (color != null) Box(Modifier.padding(top = 5.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(color))
                Text(body, color = c.label, fontSize = TypeScale.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (note != null) Text(note, color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(LingYueIcons.Delete, contentDescription = "删除", tint = c.destructive)
        }
    }
}

// ---------- 长按段落 ----------

/** 划线颜色：一排圆点，选中的外圈描强调色；圆点看着小，能点的范围 48dp。 */
@Composable
private fun MarkColors(selected: MarkColor, onPick: (MarkColor) -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        MarkColor.entries.forEach { m ->
            val on = m == selected
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable { onPick(m) }
                    .semantics { contentDescription = "${m.label}批注颜色"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(if (on) 2.5.dp else 0.dp, if (on) c.tint else Color.Transparent, CircleShape)
                        .padding(if (on) 4.dp else 2.dp)
                        .clip(CircleShape)
                        .background(Color(m.argb).copy(alpha = 1f)),
                )
            }
        }
    }
}

/** 批注输入框：卡片里的一行，没有边框和底色，占位字是灰色。 */
@Composable
private fun NoteField(note: String, onChange: (String) -> Unit) {
    val c = LocalGroupedColors.current
    TextField(
        value = note,
        onValueChange = onChange,
        label = "写点批注（可选）",
        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent),
        textStyle = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = TypeScale.Headline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}

@Composable
internal fun ParagraphSheet(
    text: String,
    notes: List<Note>,
    palette: ReaderPalette,
    onCopy: (String) -> Unit,
    onBookmark: () -> Unit,
    onSave: (start: Int, end: Int, note: String, color: MarkColor) -> Unit,
    onDeleteNote: (String) -> Unit,
    initialSelection: IntRange? = null,
    tint: Color = palette.accent,
) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        var selection by remember(text, initialSelection) {
            mutableStateOf(TextFieldValue(text, initialSelection?.let { TextRange(it.first, it.last + 1) } ?: TextRange(text.length)))
        }
        var note by remember(text) { mutableStateOf("") }
        var color by remember { mutableStateOf(MarkColor.YELLOW) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetNavBar(
                "段落",
                back = null,
                action = "划线" to {
                    val r: TextRange = selection.selection
                    onSave(r.min, r.max, note, color)
                },
            ) {}
            Spacer(Modifier.height(8.dp))
            if (notes.isNotEmpty()) {
                GroupedSection(header = "已有批注") {
                    notes.forEach { n ->
                        row { NoteRow(n.quote, n.text.ifBlank { null }, Color(n.color.argb)) { onDeleteNote(n.id) } }
                    }
                }
            }
            GroupedSection(footer = "拖动选中要划线的文字；不选则划整段。") {
                row {
                    // 只读输入框：能选中文字，选区就是要划线的范围。
                    TextField(
                        value = selection,
                        onValueChange = { selection = it.copy(text = text) },
                        readOnly = true,
                        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent),
                        textStyle = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    )
                }
            }
            GroupedSection(header = "颜色与批注") {
                row { MarkColors(color) { color = it } }
                row { NoteField(note) { note = it } }
            }
            GroupedSection {
                row {
                    ActionRow("复制") {
                        val r = selection.selection
                        onCopy(if (r.collapsed) text else text.substring(r.min, r.max))
                    }
                }
                row { ActionRow("加书签", onClick = onBookmark) }
            }
        }
    }
}

/** 分组里的一条批注：色点、引文、批注，右边删除。 */
@Composable
private fun NoteRow(quote: String, note: String?, color: Color, onDelete: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(quote, color = c.label, fontSize = TypeScale.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(LingYueIcons.Delete, contentDescription = "删除", tint = c.destructive)
        }
    }
}

/** 跨段落划线：选颜色、写批注（记在最后一段上），每段各存一条划线。 */
@Composable
internal fun MultiNoteSheet(quote: String, palette: ReaderPalette, tint: Color = palette.accent, onSave: (MarkColor, String) -> Unit) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        var note by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(MarkColor.YELLOW) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetNavBar("划线", back = null, action = "完成" to { onSave(color, note) }) {}
            Spacer(Modifier.height(8.dp))
            GroupedSection(footer = "跨了几段就划几条，批注记在最后一段上。") {
                row {
                    Text(
                        quote,
                        color = c.label,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            GroupedSection(header = "颜色与批注") {
                row { MarkColors(color) { color = it } }
                row { NoteField(note) { note = it } }
            }
        }
    }
}

/** 注释面板：标题栏“注释”、右边“完成”，正文用书里的字体放在一张卡片里。 */
@Composable
internal fun FootnoteSheet(text: String, palette: ReaderPalette, fontFamily: FontFamily, tint: Color, onDone: () -> Unit) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetNavBar("注释", back = null, action = "完成" to onDone) {}
            Spacer(Modifier.height(8.dp))
            GroupedSection {
                row {
                    Text(
                        text,
                        color = c.label,
                        fontSize = TypeScale.Headline,
                        lineHeight = TypeScale.HeadlineLine,
                        fontFamily = fontFamily,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/** 读多久：“3 分钟”“1 小时 20 分”；没有文字（漫画、PDF）或不到半分钟时为空。 */
internal fun readingTime(minutes: Float): String? {
    if (minutes.isNaN() || minutes < 0.5f) return null
    val m = kotlin.math.ceil(minutes).toInt()
    return when {
        m < 60 -> "$m 分钟"
        m % 60 == 0 -> "${m / 60} 小时"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
}

/** 目录顶上的小书封：纸色书面、书脊一道阴影，书名竖着写。 */
@Composable
private fun MiniCover(title: String) {
    val c = LocalGroupedColors.current
    Box(
        Modifier
            .size(44.dp, 62.dp)
            // 书脊小封面：照实物书的书脊、书口画的圆角和字号，不用界面的档。
            .shadow(3.dp, RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp))
            .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp))
            .background(if (c.dark) Color(0xFF3A342C) else Color(0xFFE9DFCB))
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(6).toList().joinToString("\n"),
            color = if (c.dark) Color(0xFFE6DCC4) else Color(0xFF2B2620),
            fontFamily = FontFamily.Serif,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** 目录里的 AI 速读入口：选范围（显示约多少字），生成中显示进度、可停止。 */
internal class SummaryControls(
    val running: Boolean,
    val progress: String?,
    val error: String?,
    /** 某个范围还差几章、约多少字没有摘要。 */
    val estimate: (com.xycs.lingyuereader.ai.SummaryRange) -> Pair<Int, Int>,
    val onStart: (com.xycs.lingyuereader.ai.SummaryRange) -> Unit,
    val onCancel: () -> Unit,
)

@Composable
private fun SummaryBar(s: SummaryControls) {
    val c = LocalGroupedColors.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spinner()
                Text("AI 速读生成中 ${s.progress.orEmpty()} 章", color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.weight(1f))
                Text("停止", color = c.tint, fontSize = TypeScale.Caption, modifier = Modifier.clip(RoundedCornerShape(Radius.Small)).clickable(onClick = s.onCancel).padding(8.dp))
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 速读", color = c.secondary, fontSize = TypeScale.Caption)
                com.xycs.lingyuereader.ai.SummaryRange.entries.forEach { r ->
                    val (count, chars) = s.estimate(r)
                    val label = if (count == 0) "${r.label} · 已有" else "${r.label} · 约 ${if (chars >= 10_000) "%.1f 万".format(chars / 10_000f) else chars.toString()} 字"
                    Text(
                        label,
                        color = if (count == 0) c.tertiary else c.tint,
                        fontSize = TypeScale.Caption,
                        modifier = Modifier
                            .clip(Radius.Full)
                            .background(c.tint.copy(alpha = if (count == 0) 0.04f else 0.1f))
                            .clickable(enabled = count > 0) { s.onStart(r) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        s.error?.let { ErrorText(it) }
    }
}
