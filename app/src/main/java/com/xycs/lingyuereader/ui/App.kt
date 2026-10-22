package com.xycs.lingyuereader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfScreen(vm: AppViewModel) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val loaded by vm.shelfLoaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.pick(it) }
    // 各家文件管理器给 .md/.epub 报的类型不一，干脆不按类型过滤，选完再按扩展名判断。
    val pickFiles = { picker.launch(arrayOf("*/*")) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("灵阅", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = pickFiles, enabled = !busy) {
                            Icon(Icons.Outlined.Add, contentDescription = "导入")
                        }
                    },
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !loaded -> Unit
                shelf.isEmpty() -> EmptyShelf(pickFiles)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(shelf, key = { it.id }) { entry -> ShelfItem(entry, vm) }
                }
            }
        }
    }

    pending?.let { files -> ImportDialog(files, vm) }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(12.dp))
        Text(
            "支持 TXT（自动识别 GBK/UTF-8 与章节）、EPUB 和 Markdown。" +
                "可以从手机存储或 Google 云盘等处选择文件；一次选多个文件时，可以合成一本书，适合分卷或分章的文稿。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("导入书籍")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(entry: BookEntry, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.combinedClickable(
            onClick = { vm.open(Screen.Reader(entry.id)) },
            onLongClick = { menu = true },
        ),
    ) {
        Box {
            BookCover(entry.title, entry.format.label)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (entry.sources.any { it.uri != null }) {
                    DropdownMenuItem(text = { Text("从原文件刷新") }, onClick = {
                        menu = false
                        vm.refresh(entry)
                    })
                }
                DropdownMenuItem(text = { Text("重命名") }, onClick = {
                    menu = false
                    renaming = true
                })
                DropdownMenuItem(text = { Text("移除") }, onClick = {
                    menu = false
                    confirmDelete = true
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                entry.progress == null -> "未读 · ${entry.chapterCount} 章"
                else -> "读到 ${(entry.readFraction * 100).toInt()}%"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (renaming) {
        var title by remember { mutableStateOf(entry.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(entry, title)
                    renaming = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移除《${entry.title}》") },
            text = { Text("会删除本机副本和阅读进度，原文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(entry)
                    confirmDelete = false
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private val coverColors = listOf(
    Color(0xFF8C3B2E), Color(0xFF2F4F6B), Color(0xFF3F5E45), Color(0xFF6B4E2E),
    Color(0xFF4B3F6B), Color(0xFF2E5E5E), Color(0xFF6B2E4B), Color(0xFF4A4A42),
)

/** 没有封面图时按书名生成一张：色块、书脊和竖排感的书名。 */
@Composable
fun BookCover(title: String, label: String, modifier: Modifier = Modifier) {
    val color = coverColors[Math.floorMod(title.hashCode(), coverColors.size)]
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(color),
    ) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.18f)))
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

@Composable
private fun ImportDialog(files: List<Library.Picked>, vm: AppViewModel) {
    var merge by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(suggestTitle(files.map { it.name })) }
    val sorted = remember(files) { files.map { it.name }.sortedWith(BookParser.NaturalOrder) }

    AlertDialog(
        onDismissRequest = vm::cancelImport,
        title = { Text("导入 ${files.size} 个文件") },
        text = {
            Column {
                Text(
                    sorted.take(5).joinToString("\n") + if (sorted.size > 5) "\n……" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow("合成一本书", "分卷、分章的文稿；按章号排序，同一章以后面的文件为准", merge) { merge = true }
                ChoiceRow("分别作为 ${files.size} 本书", null, !merge) { merge = false }
                if (merge) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("书名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.importBooks(files, merge, title) }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = vm::cancelImport) { Text("取消") } },
    )
}

@Composable
private fun ChoiceRow(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
