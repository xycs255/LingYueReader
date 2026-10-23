package com.xycs.lingyuereader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookParser
import com.xycs.lingyuereader.data.Library
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import com.xycs.lingyuereader.data.ShelfSort

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    BackHandler(enabled = stack.size > 1) { vm.back() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it::class },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Shelf -> ShelfScreen(vm)
            is Screen.Reader -> ReaderScreen(vm, screen)
            is Screen.Search -> SearchScreen(vm, screen.bookId)
            is Screen.Detail -> DetailScreen(vm, screen.bookId)
            Screen.Dictionaries -> DictionariesScreen(vm)
            Screen.Vocabulary -> VocabularyScreen(vm)
            Screen.Stats -> StatsScreen(vm)
            is Screen.BookRules -> BookRulesScreen(vm, screen.bookId)
        }
    }
}

@Composable
private fun ShelfScreen(vm: AppViewModel) {
    val all by vm.shelf.collectAsStateWithLifecycle()
    val shelf by vm.visibleShelf.collectAsStateWithLifecycle()
    val shelfPrefs by vm.shelfPrefs.collectAsStateWithLifecycle()
    val query by vm.shelfQuery.collectAsStateWithLifecycle()
    val loaded by vm.shelfLoaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = MiuixScrollBehavior()
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.pick(it) }
    // 各家文件管理器给 .md/.epub 报的类型不一，干脆不按类型过滤，选完再按扩展名判断。
    val pickFiles = { picker.launch(arrayOf("*/*")) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::backup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "灵阅",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.setShelfQuery("")
                    }) {
                        Icon(MiuixIcons.Search, contentDescription = "搜索书架")
                    }
                    IconButton(onClick = pickFiles, enabled = !busy) {
                        Icon(MiuixIcons.Add, contentDescription = "导入")
                    }
                    Box(Modifier.padding(end = 12.dp)) {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(MiuixIcons.More, contentDescription = "更多")
                        }
                        val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT) }
                        val options = ShelfSort.entries.map { "排序：${it.label}" } +
                            listOf(if (shelfPrefs.list) "网格显示" else "列表显示", "备份书架…", "从备份恢复…", "词典…", "生词本…", "阅读统计…")
                        OverlayListPopup(
                            show = moreMenu,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { moreMenu = false },
                        ) {
                            ListPopupColumn {
                                options.forEachIndexed { index, label ->
                                    DropdownImpl(
                                        text = label,
                                        optionSize = options.size,
                                        isSelected = index == shelfPrefs.sort.ordinal,
                                        index = index,
                                        onSelectedIndexChange = { i ->
                                            moreMenu = false
                                            when {
                                                i < ShelfSort.entries.size -> vm.updateShelfPrefs { it.copy(sort = ShelfSort.entries[i]) }
                                                i == ShelfSort.entries.size -> vm.updateShelfPrefs { it.copy(list = !it.list) }
                                                i == ShelfSort.entries.size + 1 -> backupLauncher.launch("灵阅备份-${stamp.format(java.util.Date())}.zip")
                                                i == ShelfSort.entries.size + 2 -> restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                                i == ShelfSort.entries.size + 3 -> vm.open(Screen.Dictionaries)
                                                i == ShelfSort.entries.size + 4 -> vm.open(Screen.Vocabulary)
                                                else -> vm.open(Screen.Stats)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                bottomContent = {
                    Column {
                        if (searching) {
                            TextField(
                                value = query,
                                onValueChange = vm::setShelfQuery,
                                label = "搜索书名或作者",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val listModifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
        val contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        )
        when {
            !loaded -> Unit
            all.isEmpty() -> EmptyShelf(Modifier.padding(padding), pickFiles)
            shelf.isEmpty() -> Text(
                "没有找到“$query”",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(top = 48.dp),
            )
            shelfPrefs.list -> LazyColumn(
                modifier = listModifier,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                overscrollEffect = null,
            ) {
                items(shelf, key = { it.id }) { entry -> ShelfRow(entry, vm) }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                modifier = listModifier,
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                overscrollEffect = null,
            ) {
                items(shelf, key = { it.id }) { entry -> ShelfItem(entry, vm) }
            }
        }
    }

    pending?.let { files -> ImportDialog(files, vm) }
    confirmRestore?.let { uri ->
        OverlayDialog(
            show = true,
            title = "从备份恢复",
            summary = "备份里的书会加回书架；同一本书会用备份里的版本（含进度、书签批注）覆盖。阅读设置也会恢复。",
            onDismissRequest = { confirmRestore = null },
        ) {
            DialogButtons(confirm = "恢复", onDismiss = { confirmRestore = null }) {
                vm.restore(uri)
                confirmRestore = null
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    MiuixIcons.Notes,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text("书架还是空的", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持 TXT（自动识别 GBK/UTF-8 与章节）、EPUB 和 Markdown。" +
                        "可以从手机存储或 Google 云盘等处选择文件；一次选多个文件时，可以合成一本书，适合分卷或分章的文稿。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onImport, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("导入书籍", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

internal fun progressLabel(entry: BookEntry): String =
    if (entry.progress == null) "未读 · ${entry.chapterCount} 章" else "读到 ${(entry.readFraction * 100).toInt()}%"

/** 网格里的一本书：点开阅读，长按弹出操作菜单。 */
@Composable
private fun ShelfItem(entry: BookEntry, vm: AppViewModel) {
    val dialogs = remember { BookDialogState() }
    var menu by remember { mutableStateOf(false) }
    Column {
        Box {
            BookCover(
                entry.title,
                entry.format.label,
                cover = entry.cover?.let { vm.bookFile(entry.id, it) },
                onClick = { vm.open(Screen.Reader(entry.id)) },
                onLongPress = { menu = true },
            )
            BookMenu(entry, vm, menu, dialogs) { menu = false }
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(progressLabel(entry), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    BookDialogs(entry, vm, dialogs)
}

/** 列表里的一本书：小封面、书名、作者、进度。 */
@Composable
private fun ShelfRow(entry: BookEntry, vm: AppViewModel) {
    val dialogs = remember { BookDialogState() }
    var menu by remember { mutableStateOf(false) }
    Card(
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { vm.open(Screen.Reader(entry.id)) },
        onLongPress = { menu = true },
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(52.dp)) {
                BookCover(entry.title, "", cover = entry.cover?.let { vm.bookFile(entry.id, it) }, compact = true)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(entry.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(entry.author, entry.format.label).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    progressLabel(entry),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(MiuixIcons.More, contentDescription = "操作", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                BookMenu(entry, vm, menu, dialogs) { menu = false }
            }
        }
    }
    BookDialogs(entry, vm, dialogs)
}

/** 书的操作菜单与对话框，书架和详情页共用。 */
internal class BookDialogState {
    var renaming by mutableStateOf(false)
    var removing by mutableStateOf(false)
}

@Composable
private fun BookMenu(entry: BookEntry, vm: AppViewModel, show: Boolean, dialogs: BookDialogState, onDismiss: () -> Unit) {
    val actions = buildList {
        add("详情" to { vm.open(Screen.Detail(entry.id)) })
        if (entry.sources.any { it.uri != null }) add("从原文件刷新" to { vm.refresh(entry) })
        add("重命名" to { dialogs.renaming = true })
        add("移除" to { dialogs.removing = true })
    }
    OverlayListPopup(show = show, onDismissRequest = onDismiss) {
        ListPopupColumn {
            actions.forEachIndexed { index, (label, action) ->
                DropdownImpl(
                    text = label,
                    optionSize = actions.size,
                    isSelected = false,
                    index = index,
                    onSelectedIndexChange = {
                        onDismiss()
                        action()
                    },
                )
            }
        }
    }
}

@Composable
internal fun BookDialogs(entry: BookEntry, vm: AppViewModel, dialogs: BookDialogState, onRemoved: () -> Unit = {}) {
    if (dialogs.renaming) {
        var title by remember { mutableStateOf(entry.title) }
        OverlayDialog(show = true, title = "重命名", onDismissRequest = { dialogs.renaming = false }) {
            TextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DialogButtons(confirm = "确定", onDismiss = { dialogs.renaming = false }) {
                vm.rename(entry, title)
                dialogs.renaming = false
            }
        }
    }
    if (dialogs.removing) {
        OverlayDialog(
            show = true,
            title = "移除《${entry.title}》",
            summary = "会删除本机副本、阅读进度和书签批注，原文件不受影响。",
            onDismissRequest = { dialogs.removing = false },
        ) {
            DialogButtons(confirm = "移除", onDismiss = { dialogs.removing = false }) {
                vm.remove(entry)
                dialogs.removing = false
                onRemoved()
            }
        }
    }
}

@Composable
internal fun DialogButtons(confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

private val coverColors = listOf(
    Color(0xFF8C3B2E), Color(0xFF2F4F6B), Color(0xFF3F5E45), Color(0xFF6B4E2E),
    Color(0xFF4B3F6B), Color(0xFF2E5E5E), Color(0xFF6B2E4B), Color(0xFF4A4A42),
)

/** 没有封面图时按书名生成一张：色块、书脊和书名。 */
@Composable
fun BookCover(
    title: String,
    label: String,
    modifier: Modifier = Modifier,
    cover: java.io.File? = null,
    /** 列表里的小封面：不写书名，只留色块。 */
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val color = coverColors[Math.floorMod(title.hashCode(), coverColors.size)]
    Card(
        modifier = modifier.fillMaxWidth().aspectRatio(0.72f),
        cornerRadius = 8.dp,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
        onLongPress = onLongPress,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(color)) {
            if (cover?.isFile == true) {
                CoverImage(cover, Modifier.fillMaxSize())
                return@Box
            }
            Box(Modifier.width(if (compact) 4.dp else 8.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.18f)))
            if (compact) {
                Text(
                    title.take(1),
                    color = Color(0xFFF7EFE2),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@Box
            }
            Text(
                title,
                color = Color(0xFFF7EFE2),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(start = 18.dp, end = 10.dp),
            )
            Text(
                label,
                color = Color(0xFFF7EFE2).copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

@Composable
private fun ImportDialog(files: List<Library.Picked>, vm: AppViewModel) {
    var merge by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(suggestTitle(files.map { it.name })) }
    val sorted = remember(files) { files.map { it.name }.sortedWith(BookParser.NaturalOrder) }

    OverlayDialog(
        show = true,
        title = "导入 ${files.size} 个文件",
        summary = sorted.take(5).joinToString("\n") + if (sorted.size > 5) "\n……" else "",
        onDismissRequest = vm::cancelImport,
    ) {
        Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
            RadioButtonPreference(
                title = "合成一本书",
                summary = "分卷、分章的文稿；按章号排序，同一章以后面的文件为准",
                selected = merge,
                onClick = { merge = true },
            )
            RadioButtonPreference(
                title = "分别作为 ${files.size} 本书",
                selected = !merge,
                onClick = { merge = false },
            )
        }
        if (merge) {
            TextField(
                value = title,
                onValueChange = { title = it },
                label = "书名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        DialogButtons(confirm = "导入", onDismiss = vm::cancelImport) {
            vm.importBooks(files, merge, title)
        }
    }
}

/** 取文件名的公共前缀作书名，如“已定正文_第1-5章”“已定正文_第6-10章”→“已定正文”。 */
internal fun suggestTitle(names: List<String>): String {
    val titles = names.map(BookParser::titleOf)
    val prefix = titles.reduce { a, b -> a.commonPrefixWith(b) }
        .trimEnd { it.isDigit() || it in " _-—·.、（(第" }
    return prefix.ifEmpty { titles.first() }
}
