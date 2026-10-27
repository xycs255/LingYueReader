package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.data.PackDoc
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackImportItem
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

private fun size(words: Int): String = when {
    words == 0 -> "空"
    words >= 10_000 -> "%.1f 万字".format(words / 10_000f)
    else -> "$words 字"
}

private val KIND_OPTIONS = PackKind.entries.map { it to it.label }

/**
 * 设定包：按类型分组的文档。数字小的优先级高，AI 遇到资料冲突时以它为准；
 * “常用”的每次请 AI 都带上，“按需”的只在用到时带（以后按章节自动挑）。
 */
@Composable
fun WorkPackScreen(vm: AppViewModel, workId: String, initialPreview: List<PackImportItem>? = null) {
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writing.loadPack(workId) }
    val docs = packs[workId]
    val snackbar = rememberWritingMessages(vm)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // 导入前的预览。测试里直接给（系统的文件选择器在测试里点不了）。
    var preview by remember { mutableStateOf(initialPreview) }
    var editingItem by remember { mutableStateOf<Int?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var proposals by remember { mutableStateOf<List<BibleProposal>?>(null) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun load(read: suspend () -> List<PackImportItem>) {
        scope.launch {
            try {
                val items = read()
                if (items.isEmpty()) notice = "导入设定包" to "没有找到可以导入的文件（支持 .md、.txt，以及设定包 .zip）。"
                else preview = items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "导入设定包" to "读取失败：${e.message ?: "文件打不开"}"
            }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) load { vm.readImportFiles(uris) } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { load { vm.readImportFolder(it) } } }
    val work = vm.writing.works.collectAsStateWithLifecycle().value.firstOrNull { it.id == workId }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportPack(workId, it) } }

    SubPageScaffold(
        if (preview != null) "导入设定包" else "设定包",
        onBack = { if (preview != null) preview = null else vm.back() },
        snackbar = snackbar,
        actions = {
            if (preview == null) {
                IconButton(onClick = { creating = true }) { Icon(LingYueIcons.Add, contentDescription = "新建文档") }
                Box(Modifier.padding(end = 12.dp)) {
                    IconButton(onClick = { menu = true }, enabled = !busy) { Icon(LingYueIcons.More, contentDescription = "更多") }
                    OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                        GlassMenu {
                            ListPopupColumn {
                                listOf<Pair<String, () -> Unit>>(
                                    "导入文件" to { pickFiles.launch(arrayOf("*/*")) },
                                    "导入文件夹" to { pickFolder.launch(null) },
                                    "导出设定包" to { export.launch("${work?.title ?: "作品"}-设定包.zip") },
                                ).forEach { (label, action) ->
                                    LingYueMenuItem(label, selected = false) {
                                        menu = false
                                        action()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // 文档列表 ↔ 导入预览：按层级左右滑（进预览从右边推入，返回从左边回来），和标题一起换；减少动效时直接换。
        AnimatedContent(
            preview,
            contentKey = { it != null },
            transitionSpec = {
                val forward = targetState != null
                Effects.enter(slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it else -it / 3 } + fadeIn(Motion.standard(Motion.SHORT)))
                    .togetherWith(Effects.exit(slideOutHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) -it / 3 else it } + fadeOut(Motion.exit())))
            },
            label = "packPreview",
        ) { shown ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (shown != null) {
                        ImportPreview(shown, existing = docs.orEmpty().map { it.title }.toSet(), onEdit = { editingItem = it }) {
                            vm.writing.importPack(workId, shown) { r ->
                                preview = null
                                vm.showMessage(
                                    buildString {
                                        append("已导入")
                                        if (r.added > 0) append("：新加 ${r.added} 份")
                                        if (r.replaced > 0) append("，替换 ${r.replaced} 份（旧版在历史版本里）")
                                        if (r.chapters > 0) append("，正文 ${r.chapters} 章")
                                        if (r.renamed.isNotEmpty()) append("；重名的加了编号：${r.renamed.take(3).joinToString("、")}${if (r.renamed.size > 3) " 等 ${r.renamed.size} 个" else ""}")
                                    },
                                )
                            }
                        }
                        return@item
                    }
                    GroupedSection(
                        footer = "数字小的优先：资料之间有冲突时，AI 以优先级高的为准，你在“写作 AI → 文风”里写的要求最优先。" +
                            "“常用”的文档每次请 AI 都会带上（太长会截断），“按需”的先不带。",
                    ) {
                        row {
                            // 整理的时候右边转圈；整理完弹出建议。
                            NavRow(
                                "用 AI 整理人物",
                                value = if (extracting) "整理中……" else null,
                                icon = LingYueIcons.Sparkle,
                                iconBackground = Color(0xFFAF52DE),
                                enabled = !extracting,
                                trailing = if (extracting) ({ Spinner() }) else null,
                            ) {
                                extracting = true
                                scope.launch {
                                    try {
                                        proposals = vm.writingAi.extractCharacters(workId)
                                        if (proposals.isNullOrEmpty()) notice = "整理人物" to "没有整理出人物。"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        notice = "整理人物" to (e.message ?: "整理失败")
                                    } finally {
                                        extracting = false
                                    }
                                }
                            }
                        }
                        row {
                            NavRow("导入设定包", value = "文件、文件夹、zip", icon = LingYueIcons.Download, iconBackground = Color(0xFF30B0C7)) { menu = true }
                        }
                    }
                    if (docs == null) LoadingRow("正在读设定包……")
                    if (docs != null && docs.isEmpty()) {
                        EmptyState(
                            LingYueIcons.Notes,
                            "设定包还是空的",
                            "把总纲、细纲、人物档案、写作规范放进来，分好类、定好优先级。也可以把电脑上整理好的一整个文件夹的 .md 导进来，按文件名自动归类。",
                            action = "新建文档" to { creating = true },
                        )
                    }
                    PackKind.entries.forEach { kind ->
                        val list = docs.orEmpty().filter { it.kind == kind }
                        if (list.isEmpty()) return@forEach
                        GroupedSection(header = kind.label) {
                            list.forEach { d ->
                                row {
                                    NavRow(
                                        d.title,
                                        subtitle = "优先级 ${d.priority} · ${if (d.always) "常用" else "按需"} · ${size(d.words)} · ${whenLabel(d.updatedAt)}更新",
                                    ) { vm.open(Screen.PackDoc(workId, d.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (creating) {
            NewDocDialog(onDismiss = { creating = false }) { kind, title ->
                creating = false
                vm.writing.addDoc(workId, kind, title) { vm.open(Screen.PackDoc(workId, it.id)) }
            }
        }
        val items = preview
        val editIndex = editingItem
        if (items != null && editIndex != null) {
            ImportItemDialog(items[editIndex], onDismiss = { editingItem = null }) { changed ->
                preview = items.toMutableList().also { it[editIndex] = changed }
                editingItem = null
            }
        }
        proposals?.takeIf { it.isNotEmpty() }?.let { list ->
            ProposalsDialog(list, onDismiss = { proposals = null }) { picked ->
                proposals = null
                vm.writingAi.addProposals(workId, picked) { vm.writing.loadPack(workId) }
            }
        }
        notice?.let { (title, msg) ->
            GlassDialog(show = true, title = title, summary = msg, onDismissRequest = { notice = null }) {
                DialogOkButton("好") { notice = null }
            }
        }
    }
}

/** 导入前看一眼：每个文件认成了什么、要不要导；点一行改类型、优先级或改成“正文拆章节”。 */
@Composable
private fun ImportPreview(items: List<PackImportItem>, existing: Set<String>, onEdit: (Int) -> Unit, onImport: () -> Unit) {
    val c = LocalGroupedColors.current
    val count = items.count { it.include && it.text.isNotBlank() }
    // 导入后的标题：和前面导入的文档重名的加编号（导入时也按这个算）。
    val titles = remember(items, existing) { PackImport.docTitles(items, existing) }
    fun excerpt(text: String): String = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .take(2).joinToString(" · ").take(140).ifBlank { "原文为空，请核对后再导入" }
    GroupedSection(
        header = "${items.size} 个文件",
        footer = "按文件名自动归类，点一行可以改。同名的文档会被替换，旧的内容存进历史版本；这次导入的文件之间重名的加编号，都留着。" +
            "“正文”会按章名拆开，接在最后一卷后面，重名的章同样加编号。",
    ) {
        items.forEachIndexed { i, item ->
            val blank = item.text.isBlank()
            row {
                GroupedRow(onClick = { onEdit(i) }) {
                    Checkbox(state = ToggleableState(item.include && !blank), enabled = !blank, onClick = { onEdit(i) })
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            item.title,
                            color = if (item.include) c.label else c.tertiary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                if (item.asChapters) append("正文（拆成章节）") else append("${item.kind.label} · 优先级 ${item.priority} · ${if (item.always) "常用" else "按需"}")
                                append(" · ${size(WordCount.of(item.text))}")
                                if (!item.asChapters && item.include && titles[i] != item.title) append(" · 和前面的重名，导入为「${titles[i]}」")
                                else if (!item.asChapters && item.title in existing) append(" · 替换同名")
                            },
                            color = c.secondary,
                            fontSize = 13.sp,
                        )
                        Text(
                            "来源：${item.fileName.replace('\\', '/')}",
                            color = c.secondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "原文：${excerpt(item.text)}",
                            color = if (blank || '\uFFFD' in item.text) c.warning else c.secondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (blank) Text("空白文件已跳过，不会覆盖现有资料", color = c.warning, fontSize = 12.sp)
                        if ('\uFFFD' in item.text) Text(
                            "包含无法识别的字符，请核对原文件编码",
                            color = c.warning,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }
            }
        }
    }
    GroupedSection {
        row { ActionRow("导入 $count 个文件", enabled = count > 0, centered = true, onClick = onImport) }
    }
}

/**
 * 一份设定文档带给 AI 的方式：类型（十几种、各有说明，用勾选列表）、优先级（1–9 的滑块）、常用还是按需（分段控件）。
 * 新建、改属性、导入前改一项共用。
 */
@Composable
private fun PackDocFields(
    kind: PackKind,
    priority: Int,
    always: Boolean,
    onKind: (PackKind) -> Unit,
    onPriority: (Int) -> Unit,
    onAlways: (Boolean) -> Unit,
) {
    val c = LocalGroupedColors.current
    Text("类型", fontSize = 13.sp, color = c.secondary)
    DialogOptionList(KIND_OPTIONS, onPick = onKind, selected = kind, description = { it.hint })
    // 优先级 1–9（数字小的优先）；按类型带进来的 0（工作说明）也留着，不在打开时悄悄改掉。
    val low = minOf(priority, 1)
    LabeledSliderRow(
        "优先级",
        value = priority.toFloat(),
        valueText = "$priority",
        valueRange = low.toFloat()..9f,
        steps = 9 - low - 1,
    ) { onPriority(it.roundToInt()) }
    Text("数字小的优先，资料冲突时以它为准。", fontSize = 13.sp, lineHeight = 18.sp, color = c.secondary)
    SegmentedControl(listOf(true to "常用", false to "按需"), always, modifier = Modifier.padding(top = 6.dp), onSelect = onAlways)
    Text(if (always) "常用：每次都带给 AI（太长会截断）。" else "按需：用到时才带。", fontSize = 13.sp, lineHeight = 18.sp, color = c.secondary)
}

/** 改一个待导入文件：导不导、当设定（选类型、优先级、常用）还是当正文。 */
@Composable
private fun ImportItemDialog(item: PackImportItem, onDismiss: () -> Unit, onSave: (PackImportItem) -> Unit) {
    val blank = item.text.isBlank()
    var title by remember { mutableStateOf(item.title) }
    var include by remember { mutableStateOf(item.include) }
    var chapters by remember { mutableStateOf(item.asChapters) }
    var kind by remember { mutableStateOf(item.kind) }
    var priority by remember { mutableStateOf(item.priority) }
    var always by remember { mutableStateOf(item.always) }
    GlassDialog(show = true, title = item.title, onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = title, onValueChange = { title = it }, label = "导入后的标题", singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("来源：${item.fileName.replace('\\', '/')}", color = c.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.text.lineSequence().take(6).joinToString("\n").take(500).ifBlank { "原文为空，请核对后再导入" },
                    color = if (item.text.isBlank() || '\uFFFD' in item.text) c.warning else c.label,
                    fontSize = 13.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if ('\uFFFD' in item.text) Text("包含无法识别的字符，请核对原文件编码", color = c.warning, fontSize = 12.sp)
            }
            if (blank) Text("空白文件不能导入，请在原文件中补充内容后重试。", color = c.warning, fontSize = 13.sp)
            else SegmentedControl(listOf(true to "导入", false to "不导入"), include) { include = it }
            SegmentedControl(listOf(false to "当设定", true to "当正文"), chapters) { chapters = it }
            if (chapters) {
                Text("当正文：按章名拆成章节，接在最后一卷后面。", fontSize = 13.sp, lineHeight = 18.sp, color = c.secondary)
            } else {
                PackDocFields(
                    kind,
                    priority,
                    always,
                    onKind = {
                        if (priority == kind.priority) priority = it.priority
                        always = it.always
                        kind = it
                    },
                    onPriority = { priority = it },
                    onAlways = { always = it },
                )
            }
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                onSave(item.copy(title = title.trim(), include = include && !blank, asChapters = chapters, kind = kind, priority = priority, always = always))
            },
            confirmText = "好",
            confirmEnabled = title.isNotBlank(),
        )
    }
}

/** 新建文档：起个标题、选类型（人物档案、写作规范等会带一份模板）。 */
@Composable
private fun NewDocDialog(onDismiss: () -> Unit, onCreate: (PackKind, String) -> Unit) {
    var kind by remember { mutableStateOf(PackKind.CHARACTER) }
    var title by remember { mutableStateOf("") }
    GlassDialog(show = true, title = "新建文档", onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        TextField(value = title, onValueChange = { title = it }, label = "标题（不填就叫“${kind.label}”）", singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("类型", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        // 十几种类型各有说明：勾选列表，在框里滚。
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            DialogOptionList(KIND_OPTIONS, onPick = { kind = it }, selected = kind, description = { it.hint })
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onCreate(kind, title) }, confirmText = "新建")
    }
}

/** 文档属性：标题、类型、优先级、常用与否。 */
@Composable
private fun DocPropsDialog(doc: PackDoc, onDismiss: () -> Unit, onSave: (PackDoc) -> Unit) {
    var title by remember { mutableStateOf(doc.title) }
    var kind by remember { mutableStateOf(doc.kind) }
    var priority by remember { mutableStateOf(doc.priority) }
    var always by remember { mutableStateOf(doc.always) }
    GlassDialog(show = true, title = "文档属性", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = title, onValueChange = { title = it }, label = "标题", singleLine = true, modifier = Modifier.fillMaxWidth())
            PackDocFields(kind, priority, always, onKind = { kind = it }, onPriority = { priority = it }, onAlways = { always = it })
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onSave(doc.copy(title = title, kind = kind, priority = priority, always = always)) }, confirmText = "保存")
    }
}

/**
 * 改一份设定文档：纯文本（Markdown 也行），停笔自动保存，打开、离开和每写十分钟存一个历史版本。
 * 设定的字数不算进码字统计。
 */
@OptIn(FlowPreview::class)
@Composable
fun PackDocScreen(vm: AppViewModel, workId: String, docId: String) {
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { if (packs[workId] == null) vm.writing.loadPack(workId) }
    val doc = packs[workId]?.firstOrNull { it.id == docId }
    val snackbar = rememberWritingMessages(vm)
    var state by remember(docId) { mutableStateOf<TextFieldState?>(null) }
    var savedText by remember(docId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var props by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(workId, docId) {
        val text = vm.writing.openDoc(workId, docId)
        savedText = text
        state = TextFieldState(text)
    }
    val current = state
    if (current != null) {
        LaunchedEffect(current) {
            snapshotFlow { current.text.toString() }.drop(1).debounce(800).collect { text ->
                vm.writing.saveDoc(workId, docId, text, onSaved = { savedText = text })
            }
        }
    }
    val latest by rememberUpdatedState(current)
    val gone by rememberUpdatedState(deleted)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!gone) latest?.text?.toString()?.let { vm.writing.saveDoc(workId, docId, it) }
    }
    DisposableEffect(workId, docId) {
        onDispose { if (!gone) latest?.text?.toString()?.let { vm.writing.saveDoc(workId, docId, it, closing = true) } }
    }
    val scheme = MiuixTheme.colorScheme

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = scheme.surface,
            topBar = {
                Row(Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { props = true }) {
                        Text(doc?.title.orEmpty(), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val text = current?.text
                        val count = remember(text) { text?.let { WordCount.of(it.toString()) } ?: 0 }
                        Text(
                            buildString {
                                if (doc != null) append("${doc.kind.label} · 优先级 ${doc.priority} · ${if (doc.always) "常用" else "按需"} · ")
                                append("$count 字 · ")
                                append(if (text == null) "载入中" else if (text.toString() == savedText) "已保存" else "编辑中")
                            },
                            color = c.secondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.padding(end = 8.dp)) {
                        IconButton(onClick = { menu = true }) { Icon(LingYueIcons.More, contentDescription = "更多") }
                        OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                            GlassMenu {
                                ListPopupColumn {
                                    listOf<Pair<String, () -> Unit>>(
                                        "属性（类型、优先级）" to { props = true },
                                        "历史版本" to {
                                            current?.text?.toString()?.let { vm.writing.saveDoc(workId, docId, it, closing = true) }
                                            vm.open(Screen.WorkHistory(workId, "", docId))
                                        },
                                        "删除" to { deleting = true },
                                    ).forEach { (label, action) ->
                                        LingYueMenuItem(label, selected = false) {
                                            menu = false
                                            action()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                if (current != null) {
                    BasicTextField(
                        state = current,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "文档内容" }
                            .padding(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp),
                        textStyle = TextStyle(color = scheme.onSurface, fontSize = 16.sp, lineHeight = 26.sp),
                        cursorBrush = SolidColor(scheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        decorator = { inner ->
                            Box {
                                if (current.text.isEmpty()) Text(doc?.kind?.hint.orEmpty(), color = c.tertiary, fontSize = 16.sp, lineHeight = 26.sp)
                                inner()
                            }
                        },
                    )
                }
            }
            if (props && doc != null) {
                DocPropsDialog(doc, onDismiss = { props = false }) { changed ->
                    props = false
                    vm.writing.updateDoc(workId, docId) { changed }
                }
            }
            if (deleting && doc != null) {
                GlassDialog(show = true, title = "删除“${doc.title}”", summary = "这份文档会从设定包里删除，删除前的内容会留一份在历史版本文件里。", onDismissRequest = { deleting = false }) {
                    DialogButtons(
                        onDismiss = { deleting = false },
                        onConfirm = {
                            deleting = false
                            deleted = true
                            vm.writing.deleteDoc(workId, docId)
                            vm.back()
                        },
                        confirmText = "删除",
                        destructive = true,
                    )
                }
            }
        }
    }
}
