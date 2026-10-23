package com.xycs.lingyuereader.ui

import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import com.xycs.lingyuereader.tts.ReadAloud
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Lookup
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.displayText
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
fun ReaderScreen(vm: AppViewModel, screen: Screen.Reader) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    val theme = if (settings.autoNight && isSystemInDarkTheme()) ReaderTheme.NIGHT else settings.theme
    val palette = theme.palette()

    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(screen.bookId) { vm.ensureLoaded(screen.bookId) }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    KeepScreenOn(settings.keepScreenOn)
    Brightness(settings.brightness)

    // 阅读页铺满全屏自己处理边距；外面套一层 Scaffold 是为了给底部弹窗和提示条提供宿主。
    Scaffold(
        containerColor = palette.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 96.dp)) },
    ) {
        val loaded = book?.takeIf { it.entry.id == screen.bookId }
        if (loaded == null) {
            SystemBars(darkBackground = palette.isDark, hidden = false)
            Box(Modifier.fillMaxSize()) {
                InfiniteProgressIndicator(Modifier.align(Alignment.Center), color = palette.secondary)
            }
        } else {
            Reader(vm, screen, loaded.entry, loaded.content, settings, palette)
        }
    }
}

/** 当前阅读位置。滚动模式记列表项与像素偏移，翻页模式记块与块内字。 */
private data class Position(
    val chapter: Int,
    val item: Int,
    val offset: Int,
    val char: Int,
    val fraction: Float = 0f,
    val label: String = "",
) {
    val block: Int get() = item - HEADER_ITEMS
}

/** 进度里的章序与章名对不上时（文稿刷新后增删了章），按章名找回。 */
private fun startOf(screen: Screen.Reader, entry: BookEntry, content: BookContent): Position {
    screen.chapterIndex?.let { index ->
        val c = index.coerceIn(content.chapters.indices)
        return Position(c, screen.blockIndex?.let { it + HEADER_ITEMS } ?: 0, 0, 0)
    }
    val p = entry.progress ?: return Position(0, 0, 0, 0)
    val byIndex = content.chapters.getOrNull(p.chapterIndex)?.takeIf { it.title == p.chapterTitle }
    val index = if (byIndex != null) p.chapterIndex else content.chapters.indexOfFirst { it.title == p.chapterTitle }
    return if (index >= 0) Position(index, p.itemIndex, p.itemOffset, p.charOffset) else Position(p.chapterIndex.coerceIn(content.chapters.indices), 0, 0, 0)
}

private sealed interface ReaderSheet {
    data object Catalog : ReaderSheet
    data object Settings : ReaderSheet
    data class Paragraph(val block: Int, val selection: IntRange? = null) : ReaderSheet
    data object ReadAloud : ReaderSheet
}

/** 子组件登记的“翻一页”动作，供点按两侧和音量键共用。 */
private class Turner {
    var turn: ((Boolean) -> Unit)? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reader(
    vm: AppViewModel,
    screen: Screen.Reader,
    entry: BookEntry,
    content: BookContent,
    settings: ReaderSettings,
    palette: ReaderPalette,
) {
    val marks by vm.marks.collectAsStateWithLifecycle()
    val styles = remember(settings, palette) { ReaderStyles(settings, palette) }
    val initial = remember(screen, content) { startOf(screen, entry, content) }
    var pos by remember(screen, content) { mutableStateOf(initial) }
    val chapterIndex = initial.chapter
    val chapter = content.chapters[chapterIndex]
    var menuVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    val turner = remember { Turner() }
    val context = LocalContext.current

    val aloud by ReadAloud.state.collectAsStateWithLifecycle()
    val listening = aloud?.takeIf { it.bookId == entry.id }
    val speaking = listening?.takeIf { it.chapterIndex == chapterIndex }?.current
    // 听书读进下一章时，阅读页跟着换章。
    LaunchedEffect(listening?.chapterIndex) {
        val l = listening ?: return@LaunchedEffect
        if (l.chapterIndex != chapterIndex) vm.replaceTop(Screen.Reader(entry.id, l.chapterIndex, l.current?.block))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val startListening = {
        // 通知栏的控制按钮需要通知权限；不给也能听，只是看不到通知。
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val block = pos.block.coerceAtLeast(0)
        val indent = chapter.blocks.getOrNull(block)?.let(::indentOf) ?: 0
        vm.startReadAloud(chapterIndex, block, (pos.char - indent).coerceAtLeast(0))
        menuVisible = false
    }

    SystemBars(darkBackground = palette.isDark, hidden = settings.fullscreen && !menuVisible)
    // 只计阅读页在前台的时间：切到后台或离开阅读页时把这段时长记下。
    LifecycleResumeEffect(entry.id) {
        val start = System.currentTimeMillis()
        onPauseOrDispose { vm.addReadingTime(entry.id, System.currentTimeMillis() - start) }
    }
    VolumeKeys(settings.volumeKeys && sheet == null) { forward -> turner.turn?.invoke(forward) }

    // 停下 300 毫秒再存进度，免得滚动时频繁写文件。
    LaunchedEffect(pos) {
        delay(300)
        vm.saveProgress(pos.chapter, pos.item, pos.offset, pos.char)
    }

    val goTo: (Int, Int?) -> Unit = { index, block ->
        if (index in content.chapters.indices) {
            vm.replaceTop(Screen.Reader(entry.id, index, block))
            menuVisible = false
        }
    }
    val currentSettings by rememberUpdatedState(settings)
    val onTap: (Float) -> Unit = { x ->
        when {
            menuVisible -> menuVisible = false
            !currentSettings.tapToTurn -> menuVisible = true
            x < 1f / 3 -> turner.turn?.invoke(false)
            x > 2f / 3 -> turner.turn?.invoke(true)
            else -> menuVisible = true
        }
    }
    // 长按正文：选出按住的那个词并查词；划线、批注从查词面板进入。
    val onLongPress: (Int, Int) -> Unit = { block, char -> vm.selectWordAt(chapterIndex, block, char) }
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val selected = lookup?.takeIf { it.chapterIndex == chapterIndex }?.let { l -> l.range?.let { l.block to it } }

    // 内容区的上下边距不随状态栏显隐变化，否则一开菜单翻页模式就要重新分页。
    val bars = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val navBars = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
    val topInset = (if (settings.fullscreen) 0.dp else bars.calculateTopPadding()) + 16.dp
    val bottomInset = navBars.calculateBottomPadding() + 28.dp

    val images = remember(entry.id) { BookImages { name -> vm.bookFile(entry.id, name) } }
    val screenHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        CompositionLocalProvider(LocalBookImages provides images, LocalImageMaxHeight provides screenHeight * 0.8f) {
            if (settings.pageMode == PageMode.SCROLL) {
                key(screen, content) {
                    val start = remember { pos }
                    val listState = rememberLazyListState(start.item, start.offset)
                    ScrollReader(
                        chapter = chapter,
                        chapterIndex = chapterIndex,
                        nextTitle = content.chapters.getOrNull(chapterIndex + 1)?.title,
                        styles = styles,
                        marks = marks,
                        listState = listState,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        selected = selected,
                        onPosition = { item, offset, fraction ->
                            pos = Position(chapterIndex, item, offset, 0, fraction, "${(fraction * 100).toInt()}%")
                        },
                        onNext = { goTo(chapterIndex + 1, null) },
                        registerTurner = { turner.turn = it },
                        speaking = speaking,
                    )
                }
            } else {
                // 翻页模式的分页取决于排版设置，设置一变就从当前位置重新分页。
                key(screen, content, styles) {
                    val start = remember { pos }
                    PagedReader(
                        content = content,
                        chapterIndex = chapterIndex,
                        start = PageStart(start.block, start.char, screen.fromEnd && start == initial),
                        styles = styles,
                        mode = settings.pageMode,
                        marks = marks,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        selected = selected,
                        onPosition = { block, char, fraction, label ->
                            pos = Position(chapterIndex, block + HEADER_ITEMS, 0, char, fraction, label)
                        },
                        onChapterChange = { index, fromEnd -> vm.replaceTop(Screen.Reader(entry.id, index, null, fromEnd)) },
                        registerTurner = { turner.turn = it },
                        speaking = speaking,
                    )
                }
            }
        }

        StatusLine(chapter.title, "${chapterIndex + 1}/${content.chapters.size} · ${pos.label}", palette, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = listening != null && !menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            listening?.let { l -> ReadAloudBar(l, palette, onSettings = { sheet = ReaderSheet.ReadAloud }) }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val bookmarked = marks.bookmarks.any { it.chapterIndex == chapterIndex && it.itemIndex == pos.item.coerceAtLeast(HEADER_ITEMS) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bar)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(MiuixIcons.Back, contentDescription = "返回书架", tint = palette.text)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(entry.title, color = palette.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(chapter.title, color = palette.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.toggleBookmark(chapterIndex, pos.block.coerceAtLeast(0), pos.char) }) {
                    Icon(
                        if (bookmarked) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                        contentDescription = if (bookmarked) "删除书签" else "加书签",
                        tint = if (bookmarked) palette.accent else palette.text,
                    )
                }
                IconButton(onClick = { vm.open(Screen.Search(entry.id)) }) {
                    Icon(MiuixIcons.Search, contentDescription = "书内搜索", tint = palette.text)
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bar)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 8.dp, bottom = 6.dp),
            ) {
                BookProgress(content, pos.chapter, pos.fraction, palette) { ch, block -> goTo(ch, block) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton("上一章", MiuixIcons.ChevronBackward, palette.text, enabled = chapterIndex > 0) {
                        goTo(chapterIndex - 1, null)
                    }
                    BarButton("目录", MiuixIcons.ListView, palette.text) { sheet = ReaderSheet.Catalog }
                    BarButton("设置", MiuixIcons.Tune, palette.text) { sheet = ReaderSheet.Settings }
                BarButton(if (listening != null) "听书中" else "听书", MiuixIcons.VolumeUp, if (listening != null) palette.accent else palette.text) {
                    if (listening != null) {
                        menuVisible = false
                        sheet = ReaderSheet.ReadAloud
                    } else {
                        startListening()
                    }
                }
                    BarButton("下一章", MiuixIcons.ChevronForward, palette.text, enabled = chapterIndex < content.chapters.lastIndex) {
                        goTo(chapterIndex + 1, null)
                    }
                }
            }
        }
    }

    OverlayBottomSheet(
        show = sheet == ReaderSheet.Catalog,
        title = "目录",
        backgroundColor = palette.background,
        onDismissRequest = { sheet = null },
    ) {
        CatalogSheet(
            chapters = content.chapters,
            current = chapterIndex,
            marks = marks,
            palette = palette,
            onPickChapter = {
                sheet = null
                goTo(it, null)
            },
            onPickPosition = { ch, block ->
                sheet = null
                goTo(ch, block)
            },
            onDeleteBookmark = vm::deleteBookmark,
            onDeleteNote = vm::deleteNote,
            onExportNotes = { vm.notesMarkdown()?.let { shareText(context, "《${entry.title}》批注", it) } },
        )
    }
    OverlayBottomSheet(
        show = sheet == ReaderSheet.Settings,
        title = "阅读设置",
        backgroundColor = palette.background,
        onDismissRequest = { sheet = null },
    ) {
        SettingsSheet(settings, palette, vm::updateSettings)
    }
    OverlayBottomSheet(
        show = sheet == ReaderSheet.ReadAloud && aloud != null,
        title = "听书",
        backgroundColor = palette.background,
        onDismissRequest = { sheet = null },
    ) {
        aloud?.let { a -> ReadAloudSheet(a, palette, onRate = vm::setReadAloudRate) }
    }
    val paragraph = sheet as? ReaderSheet.Paragraph
    val paragraphText = paragraph?.let { chapter.blocks.getOrNull(it.block)?.displayText }
    OverlayBottomSheet(
        show = paragraph != null && paragraphText != null,
        title = "段落",
        backgroundColor = palette.background,
        onDismissRequest = { sheet = null },
    ) {
        if (paragraph != null && paragraphText != null) {
            ParagraphSheet(
                text = paragraphText,
                notes = marks.notesIn(chapterIndex, paragraph.block),
                palette = palette,
                onCopy = {
                    copyText(context, it)
                    sheet = null
                },
                onBookmark = {
                    vm.toggleBookmark(chapterIndex, paragraph.block, 0)
                    sheet = null
                },
                onSave = { start, end, note, color ->
                    vm.addNote(chapterIndex, paragraph.block, start, end, note, color)
                    sheet = null
                },
                onDeleteNote = vm::deleteNote,
                initialSelection = paragraph.selection,
            )
        }
    }
    LookupOverlay(vm, palette) { l ->
        vm.closeLookup()
        if (l.chapterIndex == chapterIndex) sheet = ReaderSheet.Paragraph(l.block, l.range)
    }
}

@Composable
private fun LookupOverlay(vm: AppViewModel, palette: ReaderPalette, onAnnotate: (Lookup) -> Unit) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    // 面板收起的动画期间还要显示内容，记住最后一次查的词。
    var last by remember { mutableStateOf<Lookup?>(null) }
    lookup?.let { last = it }
    OverlayBottomSheet(
        show = lookup != null,
        title = "查词",
        backgroundColor = palette.background,
        onDismissRequest = vm::closeLookup,
    ) {
        last?.let { l ->
            LookupSheet(
                lookup = l,
                palette = palette,
                vm = vm,
                onAnnotate = { onAnnotate(l) },
                onManage = {
                    vm.closeLookup()
                    vm.open(Screen.Dictionaries)
                },
            )
        }
    }
}

/** 全书进度条：按“第几章 + 章内比例”定位，拖动时显示将要跳到的章名，松手跳转。 */
@Composable
private fun BookProgress(content: BookContent, chapter: Int, fraction: Float, palette: ReaderPalette, onJump: (Int, Int?) -> Unit) {
    val count = content.chapters.size
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: (chapter + fraction.coerceIn(0f, 0.999f))
    val target = value.toInt().coerceIn(0, count - 1)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                content.chapters[target].title,
                color = if (dragging != null) palette.accent else palette.secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("全书 ${(value / count * 100).toInt().coerceIn(0, 100)}%", color = palette.secondary, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v ->
                    val ch = v.toInt().coerceIn(0, count - 1)
                    val blocks = content.chapters[ch].blocks.size
                    val within = v - ch
                    onJump(ch, if (within < 0.01f || blocks == 0) null else (within * blocks).toInt().coerceIn(0, blocks - 1))
                }
                dragging = null
            },
            valueRange = 0f..count.toFloat(),
            colors = SliderDefaults.sliderColors(foregroundColor = palette.accent, backgroundColor = palette.text.copy(alpha = 0.12f)),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun StatusLine(title: String, label: String, palette: ReaderPalette, modifier: Modifier) {
    val time = rememberClock()
    val battery = rememberBattery()
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.92f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = palette.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(label, color = palette.secondary, fontSize = 11.sp)
        Text(time, color = palette.secondary, fontSize = 11.sp)
        battery?.let { Text("电量 $it%", color = palette.secondary, fontSize = 11.sp) }
    }
}

/** 当前时间“HH:mm”，在每分钟开始时更新。 */
@Composable
private fun rememberClock(): String {
    val format = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val time by produceState(format.format(java.util.Date())) {
        while (true) {
            val now = System.currentTimeMillis()
            value = format.format(java.util.Date(now))
            delay(60_000 - now % 60_000)
        }
    }
    return time
}

/** 电量百分比；系统广播是粘性的，注册时立即拿到当前值，之后随变化更新。 */
@Composable
private fun rememberBattery(): Int? {
    val context = LocalContext.current
    var level by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val l = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) level = l * 100 / scale
            }
        }
        val sticky = androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level
}

@Composable
private fun BarButton(label: String, icon: ImageVector, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) tint else tint.copy(alpha = 0.3f)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("灵阅", text))
}

private fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "导出批注").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 状态栏图标深浅随阅读背景；全屏时隐藏状态栏，从顶部下滑可临时呼出。 */
@Composable
private fun SystemBars(darkBackground: Boolean, hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkBackground, hidden) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = !darkBackground
        controller?.isAppearanceLightNavigationBars = !darkBackground
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller?.hide(WindowInsetsCompat.Type.statusBars()) else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            if (previous != null) {
                controller.isAppearanceLightStatusBars = previous
                controller.isAppearanceLightNavigationBars = previous
            }
        }
    }
}

/** 阅读页单独的屏幕亮度；离开阅读页恢复系统亮度。 */
@Composable
private fun Brightness(value: Float) {
    val activity = LocalActivity.current
    DisposableEffect(value) {
        val window = activity?.window
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (value < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0.01f, 1f)
        }
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

@Composable
private fun VolumeKeys(enabled: Boolean, onTurn: (Boolean) -> Unit) {
    val activity = LocalActivity.current as? MainActivity ?: return
    val current by rememberUpdatedState(onTurn)
    DisposableEffect(activity, enabled) {
        activity.volumeKeyHandler = if (enabled) { forward -> current(forward) } else null
        onDispose { activity.volumeKeyHandler = null }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
