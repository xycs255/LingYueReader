package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ExportFormat
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Snapshot
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate

/** 每天码字目标的选项。 */
private val GOALS = listOf(0, 1000, 2000, 3000, 4000, 6000, 8000, 10000)

private fun words(n: Int): String = if (n >= 10_000) "%.1f 万字".format(n / 10_000f) else "$n 字"

/** “刚刚”“5 分钟前”“昨天 21:30”“10月3日”。 */
internal fun whenLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    val zone = java.time.ZoneId.systemDefault()
    val then = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        then.toLocalDate() == today -> "今天 %02d:%02d".format(then.hour, then.minute)
        then.toLocalDate() == today.minusDays(1) -> "昨天 %02d:%02d".format(then.hour, then.minute)
        then.year == today.year -> "${then.monthValue}月${then.dayOfMonth}日"
        else -> "${then.year}年${then.monthValue}月${then.dayOfMonth}日"
    }
}

/** 页面上方显示 [AppViewModel.messages] 的提示条。 */
@Composable
private fun rememberMessages(vm: AppViewModel): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.messages.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    return snackbar
}

/**
 * 首页的“写作”：今天写了多少、每天的目标、连续达标天数，下面是作品列表。
 */
@Composable
fun WritingHomeScreen(vm: AppViewModel) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val stats by vm.writing.stats.collectAsStateWithLifecycle()
    val loaded by vm.writing.loaded.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val snackbar = rememberMessages(vm)
    val today = LocalDate.now()

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        val scrollBehavior = MiuixScrollBehavior()
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "写作",
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { creating = true }, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(LingYueIcons.Add, contentDescription = "新建作品")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
                overscrollEffect = null,
            ) {
                item {
                    val written = stats.on(today).coerceAtLeast(0)
                    val streak = stats.streak(today)
                    GroupedSection(
                        footer = when {
                            stats.goal > 0 && streak > 0 -> "连续 $streak 天达标。字数按每天净增算，删掉的字会扣掉。"
                            streak > 0 -> "连续 $streak 天在写。字数按每天净增算，删掉的字会扣掉。"
                            else -> "字数按每天净增算，删掉的字会扣掉；不算空白，标点算字。"
                        },
                    ) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("今天", fontSize = 13.sp, color = c.secondary)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("$written", color = c.label, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (stats.goal > 0) " / ${stats.goal} 字" else " 字",
                                        color = c.secondary,
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                                    )
                                }
                                if (stats.goal > 0) {
                                    val fraction = (written.toFloat() / stats.goal).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.fill)) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(3.dp)).background(c.tint))
                                    }
                                    Text(
                                        if (written >= stats.goal) "今天的目标完成了" else "还差 ${stats.goal - written} 字",
                                        color = c.secondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        row {
                            PickerRow("每天写", GOALS.map { it to if (it == 0) "不设目标" else "$it 字" }, stats.goal) { vm.writing.setGoal(it) }
                        }
                    }
                    if (loaded && works.isEmpty()) {
                        EmptyState(
                            LingYueIcons.Write,
                            "还没有作品",
                            "新建一部作品，按卷和章来写。写好的章节可以当书预览、听一遍，也能导出成 TXT 或 EPUB。",
                            action = "新建作品" to { creating = true },
                        )
                    } else if (works.isNotEmpty()) {
                        GroupedSection(header = "作品") {
                            works.forEach { w ->
                                row {
                                    NavRow(
                                        w.title,
                                        subtitle = "${w.chapters.size} 章 · ${words(w.words)} · ${whenLabel(w.updatedAt)}编辑",
                                    ) { vm.open(Screen.Work(w.id)) }
                                }
                            }
                        }
                    }
                }
            }
            if (creating) {
                WorkInfoDialog(
                    title = "新建作品",
                    initial = Work(id = "", title = ""),
                    confirm = "新建",
                    onDismiss = { creating = false },
                ) { title, author, _ ->
                    creating = false
                    vm.writing.create(title, author) { vm.open(Screen.Work(it.id)) }
                }
            }
        }
    }
}

/** 新建作品、改作品信息：书名、笔名、简介（新建时不填简介）。 */
@Composable
private fun WorkInfoDialog(
    title: String,
    initial: Work,
    confirm: String,
    withSynopsis: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, synopsis: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.title) }
    var author by remember { mutableStateOf(initial.author) }
    var synopsis by remember { mutableStateOf(initial.synopsis) }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = name, onValueChange = { name = it }, label = "书名", singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(value = author, onValueChange = { author = it }, label = "笔名（可不填）", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (withSynopsis) {
            TextField(
                value = synopsis,
                onValueChange = { synopsis = it },
                label = "简介",
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 96.dp, max = 200.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = confirm,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, author, synopsis) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 返回按钮。 */
@Composable
private fun BackButton(vm: AppViewModel) {
    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
        Icon(LingYueIcons.Back, contentDescription = "返回")
    }
}

/**
 * 一部作品：字数、继续写，按卷列出各章（点开写，长按改名、挪位置、删除），右上角菜单里预览、听稿、导出、加卷、改信息、删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkScreen(vm: AppViewModel, workId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val loaded by vm.writing.loaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val snackbar = rememberMessages(vm)
    var menu by remember { mutableStateOf(false) }
    var editingInfo by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingVolume by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf<WorkChapter?>(null) }
    var volumeMenu by remember { mutableStateOf<Volume?>(null) }

    // 作品被删了（或从备份恢复时没了）：退回去。
    LaunchedEffect(loaded, work == null) { if (loaded && work == null) vm.back() }
    if (work == null) return

    val launchers = ExportFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> uri?.let { vm.exportWork(workId, f, it) } }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = work.title,
                    navigationIcon = { BackButton(vm) },
                    actions = {
                        Box(Modifier.padding(end = 12.dp)) {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(LingYueIcons.More, contentDescription = "更多") }
                            OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                                GlassMenu {
                                    ListPopupColumn {
                                        val items = listOf<Pair<String, () -> Unit>>(
                                            "当书预览" to { vm.previewWork(workId, null) },
                                            "新建一卷" to { addingVolume = true },
                                            "作品信息" to { editingInfo = true },
                                        ) + ExportFormat.entries.map { f -> "导出 ${f.label}" to { launchers.getValue(f).launch("${work.title}.${f.ext}") } } +
                                            ("删除作品" to { deleting = true })
                                        items.forEach { (label, action) ->
                                            LingYueMenuItem(label, selected = false) {
                                                menu = false
                                                action()
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
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (busy) top.yukonga.miuix.kmp.basic.LinearProgressIndicator(Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                    val last = work.chapters.maxByOrNull { it.updatedAt }
                    GroupedSection(footer = work.synopsis.ifBlank { null }) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(words(work.words), color = c.label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append("${work.chapters.size} 章")
                                        if (work.volumes.size > 1) append(" · ${work.volumes.size} 卷")
                                        if (work.author.isNotBlank()) append(" · ${work.author}")
                                    },
                                    color = c.secondary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (last != null) {
                            row {
                                NavRow("继续写", value = last.title, icon = LingYueIcons.Write, iconBackground = Color(0xFFFF9500)) {
                                    vm.open(Screen.Editor(workId, last.id))
                                }
                            }
                        }
                        if (last != null) {
                            row {
                                NavRow("当书预览", value = "排版、听稿", icon = LingYueIcons.Book, iconBackground = Color(0xFF34C759), enabled = !busy) {
                                    vm.previewWork(workId, null)
                                }
                            }
                        }
                    }
                    work.volumes.forEach { v ->
                        GroupedSection {
                            row { VolumeRow(v) { volumeMenu = v } }
                            v.chapters.forEach { ch ->
                                row {
                                    ChapterRow(
                                        ch,
                                        onClick = { vm.open(Screen.Editor(workId, ch.id)) },
                                        onLongClick = { chapterMenu = ch },
                                    )
                                }
                            }
                            row {
                                ActionRow("新的一章") { vm.writing.addChapter(workId, v.id) { vm.open(Screen.Editor(workId, it.id)) } }
                            }
                        }
                    }
                    Text(
                        "长按章节可以改名、调整顺序或删除。",
                        color = c.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
        }
        if (editingInfo) {
            WorkInfoDialog("作品信息", work, "保存", withSynopsis = true, onDismiss = { editingInfo = false }) { t, a, s ->
                editingInfo = false
                vm.writing.updateInfo(workId, t, a, s)
            }
        }
        if (addingVolume) {
            NameDialog("新建一卷", "第${work.volumes.size + 1}卷", "新建", onDismiss = { addingVolume = false }) {
                addingVolume = false
                vm.writing.addVolume(workId, it)
            }
        }
        if (deleting) {
            GlassDialog(
                show = true,
                title = "删除《${work.title}》",
                summary = "作品的全部章节和历史版本都会删除，不能恢复。要留底的话先导出一份。",
                onDismissRequest = { deleting = false },
            ) {
                DialogButtons(confirm = "删除", onDismiss = { deleting = false }) {
                    deleting = false
                    vm.writing.delete(workId)
                }
            }
        }
        chapterMenu?.let { ch ->
            ChapterDialog(
                ch,
                onDismiss = { chapterMenu = null },
                onRename = { vm.writing.renameChapter(workId, ch.id, it) },
                onMove = { vm.writing.moveChapter(workId, ch.id, it) },
                onDelete = { vm.writing.deleteChapter(workId, ch.id) },
            )
        }
        volumeMenu?.let { v ->
            VolumeDialog(
                v,
                canDelete = work.volumes.size > 1,
                onDismiss = { volumeMenu = null },
                onRename = { vm.writing.renameVolume(workId, v.id, it) },
                onDelete = { vm.writing.deleteVolume(workId, v.id) },
            )
        }
    }
}

@Composable
private fun VolumeRow(v: Volume, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        Text(v.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${v.chapters.size} 章 · ${words(v.chapters.sumOf { it.words })}", color = c.secondary, fontSize = 13.sp)
        Icon(LingYueIcons.More, contentDescription = "卷设置", tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(ch: WorkChapter, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(pressHighlight(pressed))
            .combinedClickable(interactionSource = source, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ch.title, color = c.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(if (ch.words == 0) "空" else words(ch.words), color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/** 一个名字的输入框（新建卷、改名）。 */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = confirm,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 长按一章：改名、上移下移、删除（删除要再点一次确认）。 */
@Composable
private fun ChapterDialog(ch: WorkChapter, onDismiss: () -> Unit, onRename: (String) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(ch.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    GlassDialog(
        show = true,
        title = if (confirmDelete) "删除“${ch.title}”" else ch.title,
        summary = if (confirmDelete) "这一章（${words(ch.words)}）会从作品里删除。删除前的正文会留一份在历史版本文件里。" else null,
        onDismissRequest = onDismiss,
    ) {
        if (confirmDelete) {
            DialogButtons(confirm = "删除", onDismiss = { confirmDelete = false }) {
                onDelete()
                onDismiss()
            }
            return@GlassDialog
        }
        TextField(value = name, onValueChange = { name = it }, label = "章名", singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "上移", onClick = { onMove(-1) }, modifier = Modifier.weight(1f))
            TextButton(text = "下移", onClick = { onMove(1) }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "删除", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f))
            TextButton(
                text = "完成",
                onClick = {
                    if (name.isNotBlank() && name.trim() != ch.title) onRename(name)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 点卷名：改名、删除这一卷（连同卷里的章，要再确认）。 */
@Composable
private fun VolumeDialog(v: Volume, canDelete: Boolean, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(v.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    GlassDialog(
        show = true,
        title = if (confirmDelete) "删除“${v.title}”" else "卷名",
        summary = if (confirmDelete) "这一卷和卷里的 ${v.chapters.size} 章都会删除。" else null,
        onDismissRequest = onDismiss,
    ) {
        if (confirmDelete) {
            DialogButtons(confirm = "删除", onDismiss = { confirmDelete = false }) {
                onDelete()
                onDismiss()
            }
            return@GlassDialog
        }
        TextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canDelete) TextButton(text = "删除本卷", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f))
            else TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = "完成",
                onClick = {
                    if (name.isNotBlank() && name.trim() != v.title) onRename(name)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 键盘上方一排常用的中文标点：成对的引号插进去后光标停在中间。 */
private val QUICK_INSERTS = listOf("「」", "“”", "『』", "……", "——", "，", "。", "？", "！", "：", "、")

/**
 * 写一章：上方章名和字数，中间正文（一行一段），键盘上方一排常用标点。
 * 停笔不到一秒自动保存，离开这一章、切到后台时也存；每写十分钟和每次离开存一个历史版本。
 */
@OptIn(FlowPreview::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val chapter = work?.chapter(chapterId)
    val snackbar = rememberMessages(vm)
    var state by remember(workId, chapterId) { mutableStateOf<TextFieldState?>(null) }
    var savedText by remember(workId, chapterId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    LaunchedEffect(workId, chapterId) {
        val text = vm.writing.open(workId, chapterId)
        savedText = text
        state = TextFieldState(text).apply { edit { placeCursorAtEnd() } }
    }
    val current = state
    // 自动保存：停笔 800 毫秒存一次。
    if (current != null) {
        LaunchedEffect(current) {
            snapshotFlow { current.text.toString() }.drop(1).debounce(800).collect { text ->
                vm.writing.save(workId, chapterId, text)
                savedText = text
            }
        }
    }
    val latest by rememberUpdatedState(current)
    // 切到后台（可能被系统杀掉）时马上存。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        latest?.text?.toString()?.let { vm.writing.save(workId, chapterId, it) }
    }
    // 离开这一章：存，并存一个历史版本。
    DisposableEffect(workId, chapterId) {
        onDispose { latest?.text?.toString()?.let { vm.writing.save(workId, chapterId, it, closing = true) } }
    }

    val chapters = work?.chapters.orEmpty()
    val index = chapters.indexOfFirst { it.id == chapterId }
    val scheme = MiuixTheme.colorScheme

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = scheme.surface,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(vm)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).combinedClickableCompat { renaming = true },
                    ) {
                        Text(chapter?.title.orEmpty(), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val text = current?.text
                        val count = remember(text) { text?.let { WordCount.of(it.toString()) } ?: 0 }
                        Text(
                            "$count 字 · " + if (text == null) "载入中" else if (text.toString() == savedText) "已保存" else "编辑中",
                            color = c.secondary,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = { current?.undoState?.undo() }, enabled = current?.undoState?.canUndo == true) {
                        Icon(LingYueIcons.Undo, contentDescription = "撤销")
                    }
                    IconButton(onClick = { current?.undoState?.redo() }, enabled = current?.undoState?.canRedo == true) {
                        Icon(LingYueIcons.Redo, contentDescription = "重做")
                    }
                    Box(Modifier.padding(end = 8.dp)) {
                        IconButton(onClick = { menu = true }) { Icon(LingYueIcons.More, contentDescription = "更多") }
                        OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                            GlassMenu {
                                ListPopupColumn {
                                    // 预览、听稿要用刚打的字：不等自动保存，先存一次（排在预览前面落盘）。
                                    val saveNow = { current?.text?.toString()?.let { vm.writing.save(workId, chapterId, it) } }
                                    val items = buildList<Pair<String, () -> Unit>> {
                                        add("预览本章" to { saveNow(); vm.previewWork(workId, chapterId) })
                                        add("听本章" to { saveNow(); vm.previewWork(workId, chapterId, listen = true) })
                                        // 先把现在的正文存成一版，历史列表里就有“刚才”这一版。
                                        add("历史版本" to {
                                            current?.text?.toString()?.let { vm.writing.save(workId, chapterId, it, closing = true) }
                                            vm.open(Screen.WorkHistory(workId, chapterId))
                                        })
                                        add("改章名" to { renaming = true })
                                        chapters.getOrNull(index - 1)?.let { p -> add("上一章" to { vm.replaceTop(Screen.Editor(workId, p.id)) }) }
                                        chapters.getOrNull(index + 1)?.let { n -> add("下一章" to { vm.replaceTop(Screen.Editor(workId, n.id)) }) }
                                        if (index == chapters.lastIndex && index >= 0) {
                                            val volume = work?.volumeOf(chapterId)
                                            if (volume != null) add("写下一章" to { vm.writing.addChapter(workId, volume.id) { vm.replaceTop(Screen.Editor(workId, it.id)) } })
                                        }
                                    }
                                    items.forEach { (label, action) ->
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
                val focus = remember { FocusRequester() }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (current != null) {
                        LaunchedEffect(current) { if (current.text.isEmpty()) runCatching { focus.requestFocus() } }
                        BasicTextField(
                            state = current,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focus)
                                .semantics { contentDescription = "正文" }
                                .padding(start = 22.dp, end = 22.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp),
                            // 每段首行空两格（只是显示，存的正文不带缩进），段和折行一眼分得清。
                            textStyle = TextStyle(color = scheme.onSurface, fontSize = 18.sp, lineHeight = 32.sp, textIndent = TextIndent(firstLine = 2.em)),
                            cursorBrush = SolidColor(scheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            decorator = { inner ->
                                Box {
                                    if (current.text.isEmpty()) {
                                        Text("从这里开始写……\n换行就是另起一段。", color = c.tertiary, fontSize = 18.sp, lineHeight = 32.sp)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                // 键盘上方的标点条，上面一道细线和正文隔开。
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Row(
                    Modifier.fillMaxWidth().background(c.background).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_INSERTS.forEach { s ->
                        Box(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickableCompat { current?.let { insert(it, s) } }
                                .semantics { contentDescription = "插入$s" }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(s, color = c.label, fontSize = 17.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (renaming && chapter != null) {
            NameDialog("章名", chapter.title, "保存", onDismiss = { renaming = false }) {
                renaming = false
                vm.writing.renameChapter(workId, chapterId, it)
            }
        }
    }
}

/** 插入文字：成对的符号（「」“”『』）插入后光标停在中间；选中了文字时把它包起来。 */
private fun insert(state: TextFieldState, s: String) {
    state.edit {
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val pair = s.length == 2 && s[0] != s[1]
        if (pair) {
            val inner = toString().substring(start, end)
            replace(start, end, "${s[0]}$inner${s[1]}")
            selection = androidx.compose.ui.text.TextRange(start + 1 + inner.length)
        } else {
            replace(start, end, s)
            selection = androidx.compose.ui.text.TextRange(start + s.length)
        }
    }
}

/** 点击：不带涟漪，按下时变灰。 */
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this.background(pressHighlight(pressed)).clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * 一章的历史版本：新的在前，写了时间和字数。点开看全文，可以换回那一版（现在的正文会先存一版）。
 */
@Composable
fun WorkHistoryScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var list by remember { mutableStateOf<List<Snapshot>?>(null) }
    var open by remember { mutableStateOf<Pair<Snapshot, String>?>(null) }
    var loading by remember { mutableStateOf<Snapshot?>(null) }
    LaunchedEffect(workId, chapterId) { list = vm.writing.history(workId, chapterId) }
    LaunchedEffect(loading) {
        val s = loading ?: return@LaunchedEffect
        open = vm.writing.snapshotText(workId, chapterId, s.at)?.let { s to it }
        loading = null
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "历史版本",
                    navigationIcon = { BackButton(vm) },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    val items = list
                    GroupedSection(
                        header = chapter?.title,
                        footer = "每次打开、离开这一章，以及写作中每隔十分钟，都会存一版。每章最多留 ${com.xycs.lingyuereader.data.WorkStore.KEEP_SNAPSHOTS} 版。",
                    ) {
                        if (items != null && items.isEmpty()) {
                            row { Text("还没有历史版本。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                        }
                        items.orEmpty().forEach { s ->
                            row { NavRow(whenLabel(s.at), value = words(s.words)) { loading = s } }
                        }
                    }
                }
            }
        }
        open?.let { (s, text) ->
            GlassDialog(show = true, title = whenLabel(s.at), summary = words(s.words), onDismissRequest = { open = null }) {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text.ifBlank { "（空）" }, color = c.label, fontSize = 15.sp, lineHeight = 24.sp)
                }
                DialogButtons(confirm = "换回这一版", onDismiss = { open = null }) {
                    open = null
                    vm.writing.restore(workId, chapterId, s.at) { vm.back() }
                }
            }
        }
    }
}
