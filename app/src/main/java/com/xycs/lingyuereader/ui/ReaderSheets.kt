package com.xycs.lingyuereader.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
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
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete

// ---------- 目录 / 书签 / 批注 ----------

@Composable
internal fun CatalogSheet(
    chapters: List<Chapter>,
    current: Int,
    marks: Marks,
    palette: ReaderPalette,
    onPickChapter: (Int) -> Unit,
    onPickPosition: (chapter: Int, block: Int) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onExportNotes: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(
            tabs = listOf("目录 ${chapters.size}", "书签 ${marks.bookmarks.size}", "批注 ${marks.notes.size}"),
            selectedTabIndex = tab,
            onTabSelected = { tab = it },
            colors = TabRowDefaults.tabRowColors(
                backgroundColor = palette.text.copy(alpha = 0.06f),
                contentColor = palette.secondary,
                selectedBackgroundColor = palette.accent.copy(alpha = 0.15f),
                selectedContentColor = palette.accent,
            ),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(440.dp)) {
            when (tab) {
                0 -> {
                    val state = rememberLazyListState((current - 3).coerceAtLeast(0))
                    LazyColumn(state = state) {
                        itemsIndexed(chapters) { index, chapter ->
                            Text(
                                chapter.title,
                                color = if (index == current) palette.accent else palette.text,
                                fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Serif,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPickChapter(index) }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
                1 -> if (marks.bookmarks.isEmpty()) {
                    EmptyHint("还没有书签。在阅读时点顶栏的书签按钮，或长按段落选“加书签”。", palette)
                } else {
                    LazyColumn {
                        items(marks.bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.itemIndex })), key = { it.id }) { b ->
                            MarkRow(
                                title = chapters.getOrNull(b.chapterIndex)?.title ?: b.chapterTitle,
                                body = b.excerpt,
                                note = null,
                                color = null,
                                palette = palette,
                                onClick = { onPickPosition(b.chapterIndex, b.itemIndex - HEADER_ITEMS) },
                                onDelete = { onDeleteBookmark(b.id) },
                            )
                        }
                    }
                }
                else -> Column {
                    if (marks.notes.isEmpty()) {
                        EmptyHint("还没有批注。长按正文段落即可划线、写批注。", palette)
                    } else {
                        TextButton(
                            text = "导出全部批注（Markdown）",
                            onClick = onExportNotes,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = ButtonDefaults.textButtonColors(color = palette.text.copy(alpha = 0.06f), textColor = palette.accent),
                        )
                        LazyColumn {
                            items(marks.notes.sortedWith(compareBy({ it.lost }, { it.chapterIndex }, { it.blockIndex }, { it.start })), key = { it.id }) { n ->
                                MarkRow(
                                    title = if (n.lost) "原文已改动 · ${n.chapterTitle}" else chapters.getOrNull(n.chapterIndex)?.title ?: n.chapterTitle,
                                    body = n.quote,
                                    note = n.text.ifBlank { null },
                                    color = Color(n.color.argb),
                                    palette = palette,
                                    onClick = if (n.lost) null else ({ onPickPosition(n.chapterIndex, n.blockIndex) }),
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

@Composable
private fun EmptyHint(text: String, palette: ReaderPalette) {
    Text(text, color = palette.secondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
}

@Composable
private fun MarkRow(
    title: String,
    body: String,
    note: String?,
    color: Color?,
    palette: ReaderPalette,
    onClick: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp)) {
                if (color != null) Box(Modifier.padding(top = 4.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(color))
                Text(body, color = palette.text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (note != null) Text(note, color = palette.secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(MiuixIcons.Delete, contentDescription = "删除", tint = palette.secondary)
        }
    }
}

// ---------- 长按段落 ----------

@Composable
internal fun ParagraphSheet(
    text: String,
    notes: List<Note>,
    palette: ReaderPalette,
    onCopy: (String) -> Unit,
    onBookmark: () -> Unit,
    onSave: (start: Int, end: Int, note: String, color: MarkColor) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    var selection by remember(text) { mutableStateOf(TextFieldValue(text)) }
    var note by remember(text) { mutableStateOf("") }
    var color by remember { mutableStateOf(MarkColor.YELLOW) }
    val fieldColors = TextFieldDefaults.textFieldColors(
        backgroundColor = palette.text.copy(alpha = 0.06f),
        labelColor = palette.secondary,
        borderColor = palette.accent,
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        notes.forEach { n ->
            MarkRow("已有批注", n.quote, n.text.ifBlank { null }, Color(n.color.argb), palette, null) { onDeleteNote(n.id) }
        }
        Text("拖动选中要划线的文字；不选则划整段。", color = palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        // 只读输入框：能选中文字，选区就是要划线的范围。
        TextField(
            value = selection,
            onValueChange = { selection = it.copy(text = text) },
            readOnly = true,
            colors = fieldColors,
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.text, fontSize = 16.sp, lineHeight = 26.sp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("颜色", color = palette.text, fontSize = 14.sp, modifier = Modifier.width(40.dp))
            MarkColor.entries.forEach { c ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(c.argb).copy(alpha = 0.9f))
                        .then(if (c == color) Modifier.border(2.dp, palette.accent, CircleShape) else Modifier)
                        .clickable { color = c },
                )
            }
        }
        TextField(
            value = note,
            onValueChange = { note = it },
            label = "写点批注（可选）",
            colors = fieldColors,
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.text, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val buttonColors = ButtonDefaults.textButtonColors(color = palette.text.copy(alpha = 0.06f), textColor = palette.text)
            TextButton(
                text = "复制",
                onClick = {
                    val r = selection.selection
                    onCopy(if (r.collapsed) text else text.substring(r.min, r.max))
                },
                colors = buttonColors,
                modifier = Modifier.weight(1f),
            )
            TextButton(text = "加书签", onClick = onBookmark, colors = buttonColors, modifier = Modifier.weight(1f))
            TextButton(
                text = "划线",
                onClick = {
                    val r: TextRange = selection.selection
                    onSave(r.min, r.max, note, color)
                },
                colors = ButtonDefaults.textButtonColors(color = palette.accent, textColor = palette.background),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------- 阅读设置 ----------

@Composable
internal fun SettingsSheet(
    settings: ReaderSettings,
    palette: ReaderPalette,
    update: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val textColor = palette.text
    val accent = palette.accent
    val sliderColors = SliderDefaults.sliderColors(foregroundColor = accent, backgroundColor = textColor.copy(alpha = 0.12f))
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ChoiceRow("翻页", PageMode.entries.map { it to it.label }, settings.pageMode, palette) { m -> update { it.copy(pageMode = m) } }
        LabeledSlider("字号", "${settings.fontSize.toInt()}", textColor) {
            Slider(
                value = settings.fontSize,
                onValueChange = { v -> update { it.copy(fontSize = v) } },
                valueRange = 14f..30f,
                steps = 15,
                colors = sliderColors,
            )
        }
        LabeledSlider("行距", "%.1f".format(settings.lineHeight), textColor) {
            Slider(
                value = settings.lineHeight,
                onValueChange = { v -> update { it.copy(lineHeight = v) } },
                valueRange = 1.3f..2.6f,
                colors = sliderColors,
            )
        }
        LabeledSlider("段距", "%.1f".format(settings.paragraphSpacing), textColor) {
            Slider(
                value = settings.paragraphSpacing,
                onValueChange = { v -> update { it.copy(paragraphSpacing = v) } },
                valueRange = 0f..1.5f,
                colors = sliderColors,
            )
        }
        LabeledSlider("亮度", if (settings.brightness < 0) "系统" else "${(settings.brightness * 100).toInt()}%", textColor) {
            Slider(
                value = if (settings.brightness < 0) 0.5f else settings.brightness,
                onValueChange = { v -> update { it.copy(brightness = v.coerceAtLeast(0.02f)) } },
                valueRange = 0f..1f,
                colors = sliderColors,
            )
        }
        SwitchRow("亮度跟随系统", settings.brightness < 0, textColor, accent) { v ->
            update { it.copy(brightness = if (v) -1f else 0.5f) }
        }
        Text("背景", color = textColor, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val p = theme.palette()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (settings.theme == theme) accent else p.secondary.copy(alpha = 0.4f))
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(p.background)
                            .clickable { update { it.copy(theme = theme) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("文", color = p.text, fontFamily = FontFamily.Serif, fontSize = 16.sp)
                    }
                    Text(theme.label, color = textColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        SwitchRow("系统深色时自动用夜间背景", settings.autoNight, textColor, accent) { v -> update { it.copy(autoNight = v) } }
        ChoiceRow("字体", listOf(true to "宋体", false to "黑体"), settings.serif, palette) { v -> update { it.copy(serif = v) } }
        SwitchRow("全屏（隐藏状态栏）", settings.fullscreen, textColor, accent) { v -> update { it.copy(fullscreen = v) } }
        SwitchRow("点击左右两侧翻页", settings.tapToTurn, textColor, accent) { v -> update { it.copy(tapToTurn = v) } }
        SwitchRow("音量键翻页", settings.volumeKeys, textColor, accent) { v -> update { it.copy(volumeKeys = v) } }
        SwitchRow("阅读时屏幕常亮", settings.keepScreenOn, textColor, accent) { v -> update { it.copy(keepScreenOn = v) } }
        TextButton(
            text = "恢复默认排版",
            onClick = { update { it.copy(fontSize = ReaderSettings().fontSize, lineHeight = ReaderSettings().lineHeight, paragraphSpacing = ReaderSettings().paragraphSpacing) } },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = ButtonDefaults.textButtonColors(color = textColor.copy(alpha = 0.06f), textColor = accent),
        )
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, palette: ReaderPalette, onSelect: (T) -> Unit) {
    Row(
        Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = palette.text, fontSize = 14.sp, modifier = Modifier.width(40.dp))
        options.forEach { (value, text) ->
            val on = value == selected
            Text(
                text,
                color = if (on) palette.accent else palette.text,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) palette.accent.copy(alpha = 0.15f) else palette.text.copy(alpha = 0.06f))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, textColor: Color, slider: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f)) { slider() }
        Text(value, color = textColor, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, textColor: Color, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = textColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.switchColors(checkedTrackColor = accent, uncheckedTrackColor = textColor.copy(alpha = 0.15f)),
        )
    }
}
