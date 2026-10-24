package com.xycs.lingyuereader.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
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

/** 目录、书签、批注：顶上分段控件切换，下面是圆角卡片里的列表（照 Apple Books 的目录）。 */
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
) {
    ProvideGroupedColors(dark = palette.background.luminance() < 0.5f, tint = tint, elevated = true) {
        val c = LocalGroupedColors.current
        var tab by remember { mutableIntStateOf(0) }
        Column {
            SheetNavBar("目录", back = null) {}
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedControl(
                    listOf(0 to "目录 ${chapters.size}", 1 to "书签 ${marks.bookmarks.size}", 2 to "批注 ${marks.notes.size}"),
                    tab,
                ) { tab = it }
            }
            Box(Modifier.fillMaxWidth().height(460.dp).padding(top = 6.dp)) {
                when (tab) {
                    0 -> {
                        val state = rememberLazyListState((current - 3).coerceAtLeast(0))
                        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(chapters) { index, chapter ->
                                GroupedListCell(index, chapters.size) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable { onPickChapter(index) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            chapter.title,
                                            color = if (index == current) c.tint else c.label,
                                            fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (index == current) Text("正在读", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                    1 -> if (marks.bookmarks.isEmpty()) {
                        EmptyHint("还没有书签。在阅读时点顶栏的书签按钮，或长按段落选“加书签”。", palette)
                    } else {
                        val list = marks.bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.itemIndex }))
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(list, key = { _, b -> b.id }) { i, b ->
                                GroupedListCell(i, list.size) {
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
                        EmptyHint("还没有批注。长按正文段落即可划线、写批注。", palette)
                    } else {
                        val list = marks.notes.sortedWith(compareBy({ it.lost }, { it.chapterIndex }, { it.blockIndex }, { it.start }))
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            item {
                                GroupedSection { row { ActionRow("导出全部批注（Markdown）", onClick = onExportNotes) } }
                            }
                            itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                                GroupedListCell(i, list.size) {
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

/** 长列表里的一格：同一组的格子连成一张圆角卡片（首尾圆角、中间细线），像分组列表但可以懒加载。 */
@Composable
private fun GroupedListCell(index: Int, count: Int, content: @Composable () -> Unit) {
    val c = LocalGroupedColors.current
    val r = 12.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp,
        topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp,
        bottomEnd = if (index == count - 1) r else 0.dp,
    )
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.cell)) {
        if (index > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        content()
    }
}

@Composable
private fun EmptyHint(text: String, @Suppress("UNUSED_PARAMETER") palette: ReaderPalette) {
    Text(text, color = LocalGroupedColors.current.secondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
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
            Text(title, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp)) {
                if (color != null) Box(Modifier.padding(top = 5.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(color))
                Text(body, color = c.label, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (note != null) Text(note, color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
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
        textStyle = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = 17.sp),
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
    ProvideGroupedColors(dark = palette.background.luminance() < 0.5f, tint = tint, elevated = true) {
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
                        textStyle = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = 16.sp, lineHeight = 26.sp),
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
            Text(quote, color = c.label, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(LingYueIcons.Delete, contentDescription = "删除", tint = c.destructive)
        }
    }
}

/** 跨段落划线：选颜色、写批注（记在最后一段上），每段各存一条划线。 */
@Composable
internal fun MultiNoteSheet(quote: String, palette: ReaderPalette, tint: Color = palette.accent, onSave: (MarkColor, String) -> Unit) {
    ProvideGroupedColors(dark = palette.background.luminance() < 0.5f, tint = tint, elevated = true) {
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
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
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
