package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun size(words: Int): String = when {
    words == 0 -> "空"
    words >= 10_000 -> "%.1f 万字".format(words / 10_000f)
    else -> "$words 字"
}

/** 一排小圆角标签，选一个。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, describe: String, onSelect: (T) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val c = LocalGroupedColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) scheme.primary else c.fill)
                    .clickable { onSelect(value) }
                    .semantics { contentDescription = "$describe$label" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(label, color = if (on) scheme.onPrimary else c.label, fontSize = 14.sp) }
        }
    }
}

private val KIND_OPTIONS = PackKind.entries.map { it to it.label }
private val PRIORITY_OPTIONS = (1..9).map { it to "$it" }

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

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = if (preview != null) "导入设定包" else "设定包",
                    navigationIcon = {
                        IconButton(onClick = { if (preview != null) preview = null else vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
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
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val items = preview
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (items != null) {
                        ImportPreview(items, existing = docs.orEmpty().map { it.title }.toSet(), onEdit = { editingItem = it }) {
                            preview = null
                            vm.writing.importPack(workId, items) { r ->
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
                            NavRow("用 AI 整理人物", value = if (extracting) "整理中……" else null, icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE), enabled = !extracting) {
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
            if (creating) {
                NewDocDialog(onDismiss = { creating = false }) { kind, title ->
                    creating = false
                    vm.writing.addDoc(workId, kind, title) { vm.open(Screen.PackDoc(workId, it.id)) }
                }
            }
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
                    TextButton(text = "好", onClick = { notice = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

/** 导入前看一眼：每个文件认成了什么、要不要导；点一行改类型、优先级或改成“正文拆章节”。 */
@Composable
private fun ImportPreview(items: List<PackImportItem>, existing: Set<String>, onEdit: (Int) -> Unit, onImport: () -> Unit) {
    val c = LocalGroupedColors.current
    val count = items.count { it.include }
    // 导入后的标题：和前面导入的文档重名的加编号（导入时也按这个算）。
    val titles = remember(items, existing) { PackImport.docTitles(items, existing) }
    GroupedSection(
        header = "${items.size} 个文件",
        footer = "按文件名自动归类，点一行可以改。同名的文档会被替换，旧的内容存进历史版本；这次导入的文件之间重名的加编号，都留着。" +
            "“正文”会按章名拆开，接在最后一卷后面，重名的章同样加编号。",
    ) {
        items.forEachIndexed { i, item ->
            row {
                GroupedRow(onClick = { onEdit(i) }) {
                    Checkbox(state = ToggleableState(item.include), onClick = { onEdit(i) })
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.title, color = if (item.include) c.label else c.tertiary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    }
                    Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    GroupedSection {
        row { ActionRow("导入 $count 个文件", enabled = count > 0, centered = true, onClick = onImport) }
    }
}

/** 改一个待导入文件：导不导、当设定（选类型、优先级、常用）还是当正文。 */
@Composable
private fun ImportItemDialog(item: PackImportItem, onDismiss: () -> Unit, onSave: (PackImportItem) -> Unit) {
    var include by remember { mutableStateOf(item.include) }
    var chapters by remember { mutableStateOf(item.asChapters) }
    var kind by remember { mutableStateOf(item.kind) }
    var priority by remember { mutableStateOf(item.priority) }
    var always by remember { mutableStateOf(item.always) }
    GlassDialog(show = true, title = item.title, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chips(listOf(true to "导入", false to "不导入"), include, "导入与否") { include = it }
            Chips(listOf(false to "当设定", true to "当正文（拆成章节）"), chapters, "用途") { chapters = it }
            if (!chapters) {
                Text("类型", fontSize = 13.sp, color = LocalGroupedColors.current.secondary)
                Chips(KIND_OPTIONS, kind, "类型") {
                    if (priority == kind.priority) priority = it.priority
                    always = it.always
                    kind = it
                }
                Text("优先级（数字小的优先）", fontSize = 13.sp, color = LocalGroupedColors.current.secondary)
                Chips(PRIORITY_OPTIONS, priority, "优先级") { priority = it }
                Chips(listOf(true to "常用", false to "按需"), always, "") { always = it }
            }
        }
        DialogButtons(confirm = "好", onDismiss = onDismiss) {
            onSave(item.copy(include = include, asChapters = chapters, kind = kind, priority = priority, always = always))
        }
    }
}

/** 新建文档：选类型、起个标题（人物档案、写作规范等会带一份模板）。 */
@Composable
private fun NewDocDialog(onDismiss: () -> Unit, onCreate: (PackKind, String) -> Unit) {
    var kind by remember { mutableStateOf(PackKind.CHARACTER) }
    var title by remember { mutableStateOf("") }
    GlassDialog(show = true, title = "新建文档", onDismissRequest = onDismiss) {
        Chips(KIND_OPTIONS, kind, "类型") { kind = it }
        Text(kind.hint, color = LocalGroupedColors.current.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        TextField(value = title, onValueChange = { title = it }, label = "标题（不填就叫“${kind.label}”）", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        DialogButtons(confirm = "新建", onDismiss = onDismiss) { onCreate(kind, title) }
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
            Text("类型", fontSize = 13.sp, color = LocalGroupedColors.current.secondary)
            Chips(KIND_OPTIONS, kind, "类型") { kind = it }
            Text("优先级（数字小的优先，冲突时以它为准）", fontSize = 13.sp, color = LocalGroupedColors.current.secondary)
            Chips(PRIORITY_OPTIONS, priority, "优先级") { priority = it }
            Chips(listOf(true to "常用：每次都带给 AI", false to "按需：用到时才带"), always, "") { always = it }
        }
        DialogButtons(confirm = "保存", onDismiss = onDismiss) { onSave(doc.copy(title = title, kind = kind, priority = priority, always = always)) }
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
                    DialogButtons(confirm = "删除", onDismiss = { deleting = false }) {
                        deleting = false
                        deleted = true
                        vm.writing.deleteDoc(workId, docId)
                        vm.back()
                    }
                }
            }
        }
    }
}
