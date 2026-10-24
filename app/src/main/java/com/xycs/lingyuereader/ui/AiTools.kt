package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ai.AiHistoryItem
import com.xycs.lingyuereader.ai.BookCharacter
import com.xycs.lingyuereader.ai.TranslateTarget
import com.xycs.lingyuereader.ui.glass.GlassSheet
import com.xycs.lingyuereader.ui.glass.glassHighlight
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

/** 阅读页顶栏 AI 菜单里的各项。 */
internal enum class AiMenuItem(val label: String) {
    RECAP("前情提要"),
    ASK_BOOK("问这本书"),
    CHARACTERS("人物表"),
    NOTES("读书笔记"),
    TRANSLATE("翻译本章"),
    HISTORY("对话记录"),
}

/** AI 菜单打开的面板。 */
internal enum class AiTool { HISTORY, CHARACTERS, NOTES, TRANSLATE }

@Composable
internal fun AiMenuItems(onPick: (AiMenuItem) -> Unit) {
    AiMenuItem.entries.forEach { item -> LingYueMenuItem(item.label, selected = false) { onPick(item) } }
}

/** AI 工具的面板：随书页配色，收起动画期间还显示原来的内容。 */
@Composable
internal fun AiToolSheets(tool: AiTool?, vm: AppViewModel, palette: ReaderPalette, tint: Color, chapter: Int, block: Int, onClose: () -> Unit) {
    var last by remember { mutableStateOf<AiTool?>(null) }
    tool?.let { last = it }
    GlassSheet(
        show = tool != null,
        title = null,
        background = readerGroupedColors(palette, tint).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = onClose,
    ) {
        ProvideReaderColors(palette, tint) {
            val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                when (last) {
                    AiTool.HISTORY -> HistorySheet(vm, onOpen = { id ->
                        onClose()
                        vm.resumeAi(id)
                    })
                    AiTool.CHARACTERS -> CharacterSheet(vm, chapter, block)
                    AiTool.NOTES -> NotesSheet(vm)
                    AiTool.TRANSLATE -> TranslateSheet(vm, chapter, onClose)
                    null -> {}
                }
            }
        }
    }
}

/** 对话记录：这本书里问过 AI 的对话，新的在前；点了接着问，右边删除。 */
@Composable
private fun HistorySheet(vm: AppViewModel, onOpen: (String) -> Unit) {
    val c = LocalGroupedColors.current
    var items by remember { mutableStateOf<List<AiHistoryItem>?>(null) }
    LaunchedEffect(Unit) { items = vm.aiHistory() }
    SheetNavBar("对话记录", back = null) {}
    val list = items
    when {
        list == null -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { InfiniteProgressIndicator(color = c.tint) }
        list.isEmpty() -> Text(
            "这本书里还没有和 AI 的对话。划选文字后点“问 AI”，或在 AI 菜单里“问这本书”，对话会自动存在这里。",
            color = c.secondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp),
        )
        else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            GroupedSection(footer = "最多保留最近 50 段对话，删书时一起删除。") {
                list.forEach { item ->
                    row {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { onOpen(item.id) }.padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(item.title.ifEmpty { "（无标题）" }, color = c.label, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${if (item.kind == "book") "问这本书" else "问 AI"} · ${item.turns} 问 · " +
                                        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(item.updatedAt)),
                                    color = c.secondary,
                                    fontSize = 13.sp,
                                )
                            }
                            IconButton(onClick = {
                                vm.removeAiHistory(item.id)
                                items = list.filterNot { it.id == item.id }
                            }) {
                                Icon(LingYueIcons.Delete, contentDescription = "删除", tint = c.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 人物表：没整理过时说明要发多少字、点“开始整理”；整理中显示进度、可停止；整理过的列出人物，
 * 读到新内容时顶上“更新”。只看读过的部分。
 */
@Composable
private fun CharacterSheet(vm: AppViewModel, chapter: Int, block: Int) {
    val c = LocalGroupedColors.current
    val state by vm.characters.collectAsStateWithLifecycle()
    val config by vm.aiConfig.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    LaunchedEffect(chapter, block) { vm.loadCharacters(chapter, block) }
    val table = state.table
    SheetNavBar(
        "人物表",
        back = null,
        action = if (table != null && state.pendingChars > 0 && !state.running) "更新" to { vm.buildCharacters(chapter, block) } else null,
    ) {}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val wan = { n: Int -> if (n >= 10_000) "%.1f 万".format(n / 10_000f) else "$n" }
        when {
            state.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfiniteProgressIndicator(size = 18.dp, color = c.tint)
                Text(state.progress ?: "正在整理……", color = c.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("停止", color = c.tint, fontSize = 15.sp, modifier = Modifier.clickable(role = Role.Button, onClick = vm::cancelCharacters).padding(8.dp))
            }
            table == null -> {
                Text(
                    if (state.pendingChars == 0) "还没读到正文。" else
                        "从书开头整理到你读到的地方：约 ${wan(state.pendingChars)}字，分 ${state.pendingChunks} 次发给 ${config.providerLabel}。" +
                            "只看读过的部分，不会剧透；整理好存在本机，以后读到新内容再补。",
                    color = c.secondary,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                if (state.pendingChars > 0) {
                    Text(
                        "开始整理",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.tint)
                            .clickable(role = Role.Button) { vm.buildCharacters(chapter, block) }
                            .padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            state.pendingChars > 0 -> Text("读到的地方之后还有约 ${wan(state.pendingChars)}字没整理，点右上角“更新”。", color = c.secondary, fontSize = 13.sp)
        }
        state.error?.let { Text(it, color = c.destructive, fontSize = 14.sp) }
        table?.characters?.forEach { ch -> CharacterCard(ch) }
        if (table != null) {
            Text(
                "整理到「${book?.content?.chapters?.getOrNull(table.chapter)?.title.orEmpty()}」 · " +
                    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(table.updatedAt)) +
                    " · 长按正文里的人名也能看到",
                color = c.tertiary,
                fontSize = 12.sp,
            )
        }
    }
}

/** 一个人物：名字、别称、身份、经历、关系。查词面板里也用。 */
@Composable
internal fun CharacterCard(ch: BookCharacter, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.cell).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ch.name, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (ch.aliases.isNotEmpty()) {
                Text("又称 " + ch.aliases.joinToString("、"), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
            }
        }
        if (ch.role.isNotEmpty()) Text(ch.role, color = c.tint, fontSize = 13.sp)
        if (ch.summary.isNotEmpty()) Text(ch.summary, color = c.label, fontSize = 14.sp, lineHeight = 21.sp)
        if (ch.relations.isNotEmpty()) {
            Text(ch.relations.joinToString("　") { (n, r) -> "$n：$r" }, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

/**
 * 读书笔记：把这本书的划线和批注交给 AI 整理成一篇 Markdown 笔记；上次整理的存在本机，
 * 可以复制、分享、存成 .md 文件，划线多了再“重新整理”。
 */
@Composable
private fun NotesSheet(vm: AppViewModel) {
    val c = LocalGroupedColors.current
    val context = LocalContext.current
    val state by vm.readingNotes.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadReadingNotes() }
    val title = book?.entry?.title.orEmpty()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(state.text.toByteArray()) } }
    }
    SheetNavBar(
        "读书笔记",
        back = null,
        action = if (state.text.isNotEmpty() && !state.loading && state.hasMarks) "重新整理" to vm::buildReadingNotes else null,
    ) {}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.text.isEmpty() && state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfiniteProgressIndicator(size = 18.dp, color = c.tint)
                Text("正在整理……", color = c.secondary, fontSize = 14.sp)
            }
            state.text.isEmpty() -> {
                Text(
                    if (state.hasMarks) "把这本书的划线和批注整理成一篇读书笔记：每章要点、摘句、你的想法，最后是总结。只发划线和批注，不发正文。"
                    else "这本书还没有划线和批注。读的时候划几句、写点想法，再来整理。",
                    color = c.secondary,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                if (state.hasMarks) PrimaryButton("开始整理", vm::buildReadingNotes)
            }
            else -> {
                SelectionContainer { MarkdownText(state.text) }
                if (state.loading) InfiniteProgressIndicator(size = 16.dp, color = c.tint)
                if (!state.loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            "复制" to { copyToClipboard(context, state.text) },
                            "分享" to { shareMarkdown(context, "《$title》读书笔记", state.text) },
                            "存为文件" to { saver.launch("《$title》读书笔记.md") },
                        ).forEach { (label, action) ->
                            Text(
                                label,
                                color = c.tint,
                                fontSize = 15.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.tint.copy(alpha = 0.1f)).clickable(role = Role.Button, onClick = action).padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                    }
                    if (state.at > 0) {
                        Text("整理于 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(state.at)), color = c.tertiary, fontSize = 12.sp)
                    }
                }
            }
        }
        state.error?.let { Text(it, color = c.destructive, fontSize = 14.sp) }
    }
}

/** 面板里的大按钮：强调色铺满一行。 */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Text(
        label,
        color = Color.White,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.tint)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

/** 简单的 Markdown 显示：标题加粗放大，引用灰色带竖线，列表加圆点，**粗体**。 */
@Composable
internal fun MarkdownText(text: String) {
    val c = LocalGroupedColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        inlineBold(line.drop(level).trim(), c.label),
                        color = c.label,
                        fontSize = if (level <= 1) 20.sp else if (level == 2) 17.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = if (level <= 2) 8.dp else 4.dp),
                    )
                }
                line.trimStart().startsWith(">") -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.tint.copy(alpha = 0.5f)))
                    Text(inlineBold(line.trimStart().removePrefix(">").trim(), c.label), color = c.secondary, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(start = 10.dp))
                }
                Regex("""^\s*([-*•]|\d+[.、])\s+.*""").matches(line) -> Row {
                    val marker = Regex("""^\s*([-*•]|\d+[.、])\s+""").find(line)!!
                    val bullet = marker.groupValues[1].let { if (it.first().isDigit()) it else "•" }
                    Text(bullet, color = c.tint, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                    Text(inlineBold(line.substring(marker.range.last + 1), c.label), color = c.label, fontSize = 15.sp, lineHeight = 23.sp)
                }
                else -> Text(inlineBold(line, c.label), color = c.label, fontSize = 15.sp, lineHeight = 23.sp)
            }
        }
    }
}

private fun inlineBold(s: String, color: Color) = buildAnnotatedString {
    var at = 0
    Regex("""\*\*(.+?)\*\*""").findAll(s).forEach { m ->
        append(s.substring(at, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) { append(m.groupValues[1]) }
        at = m.range.last + 1
    }
    append(s.substring(at))
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("灵阅", text))
}

private fun shareMarkdown(context: android.content.Context, subject: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, subject).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * 翻译本章：选译成什么，整章分批交给 AI，译完接在每段原文下面（小一号、淡一些），存在本机。
 * 可以随时隐藏译文，或删掉本章的译文。
 */
@Composable
private fun TranslateSheet(vm: AppViewModel, chapter: Int, onClose: () -> Unit) {
    val c = LocalGroupedColors.current
    val state by vm.translate.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadTranslations() }
    val ch = book?.content?.chapters?.getOrNull(chapter)
    val translated = ch?.let { state.translations.of(chapter, it.title) } != null
    var target by remember(chapter, state.translations) {
        mutableStateOf(if (translated) state.translations.target else vm.defaultTranslateTarget(chapter))
    }
    SheetNavBar("翻译本章", back = null) {}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            "把「${ch?.title.orEmpty()}」（约 ${ch?.charCount ?: 0} 字）分批交给 AI 翻译，译文接在每段原文下面，存在本机，不影响书签批注和进度。",
            color = c.secondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupedSection(header = "译成") {
            row { SegmentedRow(TranslateTarget.entries.map { it to it.label }, target) { target = it } }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfiniteProgressIndicator(size = 18.dp, color = c.tint)
                    Text("正在翻译第${state.running!! + 1}章：${state.progress.orEmpty()} 批", color = c.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("停止", color = c.tint, fontSize = 15.sp, modifier = Modifier.clickable(role = Role.Button, onClick = vm::cancelTranslate).padding(8.dp))
                }
            } else {
                PrimaryButton(if (translated) "重新翻译本章" else "翻译本章") {
                    vm.translateChapter(chapter, target)
                }
            }
            state.error?.let { Text(it, color = c.destructive, fontSize = 14.sp) }
        }
        if (state.translations.chapters.isNotEmpty()) {
            GroupedSection(
                modifier = Modifier.padding(top = 16.dp),
                footer = "已翻译 ${state.translations.chapters.size} 章。隐藏后译文仍保留，随时可以再显示。",
            ) {
                row {
                    ToggleRow("显示译文", state.translations.show) {
                        vm.setShowTranslation(it)
                        onClose()
                    }
                }
                if (translated) row {
                    ActionRow("删除本章译文", destructive = true) {
                        vm.deleteTranslation(chapter)
                        onClose()
                    }
                }
            }
        }
    }
}
