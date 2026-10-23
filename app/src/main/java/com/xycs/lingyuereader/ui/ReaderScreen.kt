package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.GlassSheet
import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.glassHighlight
import com.xycs.lingyuereader.ui.glass.readerGlass
import com.xycs.lingyuereader.ui.glass.recordGlass
import com.xycs.lingyuereader.ui.glass.rememberGlassBackdrop
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xycs.lingyuereader.ReturnPoint
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import top.yukonga.miuix.kmp.icon.extended.Play
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
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
import com.xycs.lingyuereader.data.ReadingStats
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

@Composable
fun ReaderScreen(vm: AppViewModel, screen: Screen.Reader) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    // 护眼：有光线传感器时按环境光定背景（很暗换夜间、昏暗压暗背景），否则按系统深色；日落后偏暖。
    // 墨水屏 / 省电模式：不读光线传感器，不变暖，书页纯黑白，配色不渐变。
    val eInk = settings.eInk
    val ambient = rememberAmbientLight(settings.ambientLight && !eInk)
    val lightLevel = ambient.level
    val warmth = rememberEveningWarmth(settings.eveningWarmth && !eInk)
    val systemDark = isSystemInDarkTheme()
    val theme = when {
        settings.theme == ReaderTheme.NIGHT -> ReaderTheme.NIGHT
        lightLevel == LightLevel.DARK -> ReaderTheme.NIGHT
        lightLevel == null && settings.autoNight && systemDark -> ReaderTheme.NIGHT
        else -> settings.theme
    }
    val dim = if (lightLevel == LightLevel.DIM && theme != ReaderTheme.NIGHT) 0.9f else 1f
    val palette = if (eInk) Effects.eInkPalette(settings.theme == ReaderTheme.NIGHT) else animatePalette(theme.palette().adjusted(dim, warmth))

    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // 关灯看屏最累（Fan 等 2024：10 lx 比 0 lx 疲劳低），持续很暗时提醒一次开灯。
    var lampHinted by remember { mutableStateOf(false) }
    LaunchedEffect(lightLevel) {
        if (lightLevel == LightLevel.DARK && !lampHinted) {
            delay(60_000)
            lampHinted = true
            snackbar.showSnackbar("周围很暗，开一盏灯读起来会更舒服")
        }
    }

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
            CompositionLocalProvider(LocalAmbientLight provides ambient) {
                Reader(vm, screen, loaded.entry, loaded.content, settings, palette)
            }
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
        return Position(c, screen.blockIndex?.let { it + HEADER_ITEMS } ?: 0, screen.offset, screen.char)
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
    /** 跨段落划线。 */
    data object MultiNote : ReaderSheet
    data class Card(val content: QuoteCardContent) : ReaderSheet
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
    val chinese = remember(content) { looksChinese(content.chapters.firstOrNull()?.blocks.orEmpty()) }
    val customFont = remember(settings.font) { vm.fontFamily(settings.font) }
    // 注音要读字表，后台准备好再用。
    val annotator by produceState<com.xycs.lingyuereader.dict.PinyinAnnotator?>(null, settings.pinyin) {
        value = if (settings.pinyin) vm.pinyinAnnotator() else null
    }
    val styles = remember(settings, palette, chinese, customFont, annotator) { ReaderStyles(settings, palette, chinese, customFont, annotator) }
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
        val indent = chapter.blocks.getOrNull(block)?.let { indentOf(it, settings.indent) } ?: 0
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

    // 阅读统计：各章在全书中的起始字数，用来把位置换算成“读到第几个字”。
    val chapterStarts = remember(content) { content.chapters.runningFold(0L) { acc, c -> acc + c.charCount } }
    fun charIndexOf(p: Position): Long {
        val c = content.chapters.getOrNull(p.chapter) ?: return -1
        return chapterStarts[p.chapter] + (p.fraction.coerceIn(0f, 1f) * c.charCount).toLong()
    }
    val readTracker = remember(entry.id) { object { var last = -1L; var at = 0L } }

    // 停下 300 毫秒再存进度，免得滚动时频繁写文件。
    LaunchedEffect(pos) {
        delay(300)
        vm.saveProgress(pos.chapter, pos.item, pos.offset, pos.char)
        // 顺着读下去的字数计入统计；跳章、拖进度条这类一下子跳很远的不算（每秒最多算 30 字，外加一页的余量）。
        val now = android.os.SystemClock.elapsedRealtime()
        val at = charIndexOf(pos)
        if (readTracker.last >= 0 && at > readTracker.last) {
            val moved = at - readTracker.last
            if (moved <= (now - readTracker.at) / 1000 * 30 + 600) vm.addReadChars(moved)
        }
        readTracker.last = at
        readTracker.at = now
    }
    val readingStats by vm.readingStats.collectAsStateWithLifecycle()
    val speed = remember(readingStats) { ReadingStats.charsPerMinute(readingStats) }
    val chapterLeft = remember(pos.fraction, chapter, speed) {
        val minutes = (1f - pos.fraction.coerceIn(0f, 1f)) * chapter.charCount / speed
        when {
            chapter.charCount == 0 -> null // 漫画、PDF 没有文字，估不出时间
            minutes < 1f -> "本章不到 1 分钟"
            else -> "本章约剩 ${kotlin.math.ceil(minutes).toInt()} 分钟"
        }
    }

    val goTo: (Int, Int?) -> Unit = { index, block ->
        if (index in content.chapters.indices) {
            vm.replaceTop(Screen.Reader(entry.id, index, block))
            menuVisible = false
        }
    }
    // 从目录、书签、进度条跳走：先记下现在读到哪里，跳过去后可以一键回来。
    val jump: (Int, Int?) -> Unit = { index, block ->
        if (index in content.chapters.indices) {
            vm.markJump(ReturnPoint(entry.id, pos.chapter, pos.block, pos.offset, pos.char, content.chapters[pos.chapter].title))
            goTo(index, block)
        }
    }
    val returnPoint by vm.returnPoint.collectAsStateWithLifecycle()
    val currentSettings by rememberUpdatedState(settings)
    val textSelection by vm.selection.collectAsStateWithLifecycle()
    // 自动滚动：开着时点正文是暂停/继续。
    var autoScroll by remember { mutableStateOf(false) }
    var autoPaused by remember { mutableStateOf(false) }
    val onTap: (Float) -> Unit = { x ->
        when {
            // 有划选时，点一下只是取消划选。
            vm.selection.value != null -> vm.clearSelection()
            autoScroll && !menuVisible -> autoPaused = !autoPaused
            menuVisible -> menuVisible = false
            !currentSettings.tapToTurn -> menuVisible = true
            x < 1f / 3 -> turner.turn?.invoke(false)
            x > 2f / 3 -> turner.turn?.invoke(true)
            else -> menuVisible = true
        }
    }
    // 长按正文选出按住的词，松手就查；不松手拖动是自由划选（可跨段落，拖到上下边缘自动滚动），
    // 松手后可拖两端把手微调。
    // 点开的注释（键）。
    var footnote by remember { mutableStateOf<String?>(null) }
    // 长按插图（漫画、PDF 的页）打开的大图。
    var zoomed by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val edgePx = with(density) { 56.dp.toPx() }
    // 拖把手时手指在把手上，对应的字在手指上方约这么高。
    val liftPx = with(density) { 30.dp.toPx() }
    val tracker = remember(vm, chapterIndex) {
        SelectionTracker(
            onPress = { block, char ->
                // 开始选字就收起菜单，免得挡住操作条。
                menuVisible = false
                vm.pressText(chapterIndex, block, char)
            },
            onDrag = vm::dragText,
            onRelease = vm::releaseText,
            onHandle = vm::moveSelectionHandle,
        )
    }
    // 拖到上下边缘：滚动模式下一直滚，滚的同时按手指位置更新选区。
    LaunchedEffect(tracker, tracker.edge) {
        val dir = tracker.edge
        while (dir != 0 && tracker.mode != null) {
            val scroll = tracker.scroller ?: break
            scroll(dir * edgePx * 0.2f)
            tracker.retrack(edgePx, liftPx)
            delay(16)
        }
    }
    // 自动滚动：滚动模式按“每分钟几行”匀速滚，翻页模式按同样的速度定时翻页；
    // 菜单、面板、划选打开时暂停；章末接着下一章，书末停下。
    val readingStatsForSpeed by vm.readingStats.collectAsStateWithLifecycle()
    val linePx = with(density) { (settings.fontSize * settings.lineHeight).sp.toPx() }
    val autoLines = settings.autoScrollLines.takeIf { it > 0f }
        ?: autoScrollLinesFor(ReadingStats.charsPerMinute(readingStatsForSpeed), charsPerLine = 16f)
    val autoActive = autoScroll && !autoPaused && !menuVisible && sheet == null && textSelection == null && footnote == null
    LaunchedEffect(autoActive, autoLines, settings.pageMode, chapterIndex) {
        if (!autoActive) return@LaunchedEffect
        if (settings.pageMode == PageMode.SCROLL) {
            val pxPerMs = linePx * autoLines / 60_000f
            var last = 0L
            while (true) {
                val now = withFrameMillis { it }
                val dt = if (last == 0L) 0L else (now - last).coerceAtMost(100)
                last = now
                if (dt == 0L) continue
                val want = pxPerMs * dt
                // 换章后新的列表还没登记滚动函数，等下一帧。
                val got = tracker.scroller?.invoke(want) ?: continue
                if (got < want * 0.5f) {
                    // 滚到章末：有下一章就接着读，没有就停。
                    if (chapterIndex < content.chapters.lastIndex) goTo(chapterIndex + 1, null) else autoScroll = false
                    break
                }
            }
        } else {
            val height = tracker.root?.size?.height?.toFloat() ?: 0f
            // 页面上下各留了一点边距，按八成五的高度估算一页几行。
            val linesPerPage = (height * 0.85f / linePx).coerceAtLeast(3f)
            while (true) {
                delay(autoPageMillis(linesPerPage, autoLines))
                if (pos.chapter >= content.chapters.lastIndex && pos.fraction >= 0.999f) {
                    autoScroll = false
                    break
                }
                turner.turn?.invoke(true)
            }
        }
    }
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val selected = textSelection?.takeIf { it.chapterIndex == chapterIndex }
        ?.let { Highlight(it.start.block, it.start.char, it.end.block, it.end.char, handles = !it.dragging) }
        ?: lookup?.takeIf { it.chapterIndex == chapterIndex }?.let { l -> l.range?.let { Highlight(l.block, it.first, l.block, it.last, handles = false) } }

    // 内容区的上下边距不随状态栏显隐变化，否则一开菜单翻页模式就要重新分页。
    val bars = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val navBars = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
    val topInset = (if (settings.fullscreen) 0.dp else bars.calculateTopPadding()) + 16.dp
    val bottomInset = navBars.calculateBottomPadding() + 28.dp

    val images = remember(entry.id) { BookImages { name -> vm.bookFile(entry.id, name) } }
    DisposableEffect(images) { onDispose { images.close() } }
    val screenHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    val glassBackdrop = rememberGlassBackdrop(palette.background)
    CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop) {
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .onGloballyPositioned { tracker.root = it }
            // 选字开始后，手指的移动和松手都在这里统一处理（抢在列表滚动、翻页之前）。
            .pointerInput(tracker) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (tracker.mode == null) continue
                        val change = event.changes.firstOrNull() ?: continue
                        change.consume()
                        if (change.pressed) tracker.track(change.position, edgePx, liftPx) else tracker.finish()
                    }
                }
            },
    ) {
        // 书页（正文和状态行）记录成玻璃的取样源，菜单栏、控制条、操作条和底部面板都透出它。
        Box(Modifier.fillMaxSize().recordGlass(glassBackdrop)) {
        CompositionLocalProvider(
            LocalBookImages provides images,
            LocalImageMaxHeight provides screenHeight * 0.8f,
            LocalImageZoom provides { zoomed = it },
        ) {
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
                        selector = tracker,
                        onFootnote = { footnote = it },
                        selected = selected,
                        onPosition = { item, offset, fraction ->
                            pos = Position(chapterIndex, item, offset, 0, fraction, "${(fraction * 100).toInt()}%")
                        },
                        onNext = { goTo(chapterIndex + 1, null) },
                        registerTurner = { turner.turn = it },
                        registerScroller = { tracker.scroller = it },
                        speaking = speaking,
                    )
                }
            } else if (settings.pageMode == PageMode.VERTICAL) {
                key(screen, content, settings) {
                    val start = remember { pos }
                    val typeface = remember(settings.font, settings.serif) { vm.cardTypeface(settings) }
                    VerticalReader(
                        content = content,
                        chapterIndex = chapterIndex,
                        start = PageStart(start.block, start.char, screen.fromEnd && start == initial),
                        styles = styles,
                        typeface = typeface,
                        marks = marks,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onTap = onTap,
                        onLongPress = { block, char -> vm.lookupAt(chapterIndex, block, char) },
                        onPosition = { block, char, fraction, label ->
                            pos = Position(chapterIndex, block + HEADER_ITEMS, 0, char, fraction, label)
                        },
                        onChapterChange = { index, fromEnd -> vm.replaceTop(Screen.Reader(entry.id, index, null, fromEnd)) },
                        registerTurner = { turner.turn = it },
                        speaking = speaking,
                        selected = lookup?.takeIf { it.chapterIndex == chapterIndex }?.let { l -> l.range?.let { l.block to it } },
                    )
                }
            } else {
                // 翻页模式的分页取决于排版设置，设置一变就从当前位置重新分页。
                // 只在排版设置变化时重新分页；配色随环境渐变时不必。
                key(screen, content, settings) {
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
                        selector = tracker,
                        onFootnote = { footnote = it },
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

        StatusLine(
            chapter.title,
            listOfNotNull("${chapterIndex + 1}/${content.chapters.size}", pos.label, chapterLeft).joinToString(" · "),
            palette,
            Modifier.align(Alignment.BottomCenter),
        )
        }


        val back = returnPoint?.takeIf { it.bookId == entry.id }
        AnimatedVisibility(
            visible = back != null && !menuVisible && listening == null && !autoScroll,
            enter = Effects.enter(fadeIn()),
            exit = Effects.exit(fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 4.dp),
        ) {
            back?.let { ReturnChip(it.chapterTitle, palette, glassBackdrop, onReturn = vm::jumpBack, onDismiss = vm::clearReturnPoint) }
        }
        AnimatedVisibility(
            visible = listening != null && !menuVisible,
            enter = Effects.enter(fadeIn()),
            exit = Effects.exit(fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            listening?.let { l -> ReadAloudBar(l, palette, onSettings = { sheet = ReaderSheet.ReadAloud }) }
        }
        AnimatedVisibility(
            visible = autoScroll && listening == null && !menuVisible,
            enter = Effects.enter(fadeIn()),
            exit = Effects.exit(fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AutoScrollBar(
                lines = autoLines,
                paused = autoPaused,
                paged = settings.pageMode != PageMode.SCROLL,
                palette = palette,
                onToggle = { autoPaused = !autoPaused },
                onSpeed = { v -> vm.updateSettings { it.copy(autoScrollLines = v) } },
                onStop = { autoScroll = false },
            )
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = Effects.enter(slideInVertically { -it } + fadeIn()),
            exit = Effects.exit(slideOutVertically { -it } + fadeOut()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val bookmarked = marks.bookmarks.any { it.chapterIndex == chapterIndex && it.itemIndex == pos.item.coerceAtLeast(HEADER_ITEMS) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .readerGlass(RoundedCornerShape(28.dp), palette, glassBackdrop)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
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
                IconButton(onClick = {
                    autoScroll = true
                    autoPaused = false
                    menuVisible = false
                }) {
                    Icon(MiuixIcons.Play, contentDescription = "自动滚动", tint = if (autoScroll) palette.accent else palette.text)
                }
                IconButton(onClick = { vm.open(Screen.Search(entry.id)) }) {
                    Icon(MiuixIcons.Search, contentDescription = "书内搜索", tint = palette.text)
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = Effects.enter(slideInVertically { it } + fadeIn()),
            exit = Effects.exit(slideOutVertically { it } + fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .readerGlass(RoundedCornerShape(28.dp), palette, glassBackdrop)
                    .padding(top = 8.dp, bottom = 6.dp),
            ) {
                BookProgress(content, pos.chapter, pos.fraction, palette) { ch, block -> jump(ch, block) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton("上一章", MiuixIcons.ChevronBackward, palette.text, enabled = chapterIndex > 0) {
                        goTo(chapterIndex - 1, null)
                    }
                    BarButton("目录", MiuixIcons.ListView, palette.text) { sheet = ReaderSheet.Catalog }
                    BarButton("设置", MiuixIcons.Tune, palette.text) { sheet = ReaderSheet.Settings }
                BarButton(
                    if (listening != null) "听书中" else "听书",
                    MiuixIcons.VolumeUp,
                    if (listening != null) palette.accent else palette.text,
                    // 漫画、PDF 没有文字可读。
                    enabled = !entry.format.imageOnly,
                ) {
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
        // 操作条贴着选区：优先放在首行上方，放不下就放在末行（和把手）下方。放在最后，盖在其他浮层之上。
        val bounds = selected?.takeIf { textSelection?.dragging == false }?.let(tracker::bounds)
        if (bounds != null) {
            SelectionBar(
                palette,
                onLookup = vm::lookupSelection.takeIf { textSelection?.singleBlock == true },
                onAnnotate = {
                    textSelection?.let { sel ->
                        if (sel.chapterIndex != chapterIndex) return@let
                        if (sel.singleBlock) {
                            vm.clearSelection()
                            sheet = ReaderSheet.Paragraph(sel.start.block, sel.start.char..sel.end.char)
                        } else {
                            sheet = ReaderSheet.MultiNote
                        }
                    }
                },
                onCopy = {
                    vm.selectionText()?.let { copyText(context, it) }
                    vm.clearSelection()
                },
                onCard = {
                    vm.selectionText()?.let { text ->
                        sheet = ReaderSheet.Card(QuoteCardContent(text, entry.title, entry.author, chapter.title))
                    }
                    vm.clearSelection()
                },
                onCancel = vm::clearSelection,
                modifier = Modifier.layout { measurable, constraints ->
                    val bar = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val gap = with(density) { 10.dp.toPx() }
                    val handle = with(density) { 22.dp.toPx() }
                    val minTop = with(density) { topInset.toPx() }
                    val maxBottom = constraints.maxHeight - with(density) { bottomInset.toPx() }
                    val above = bounds.top - gap - bar.height
                    val below = bounds.bottom + handle + gap
                    val y = when {
                        above >= minTop -> above
                        below + bar.height <= maxBottom -> below
                        // 选区占满了整屏，就放在屏幕中间。
                        else -> (constraints.maxHeight - bar.height) / 2f
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        bar.place((constraints.maxWidth - bar.width) / 2, y.roundToInt())
                    }
                },
            )
        }
        BreakReminder(enabled = settings.breakReminder, paused = listening != null, palette = palette)
        zoomed?.let { src -> ZoomedImage(src, images) { zoomed = null } }
    }
    }

    GlassSheet(
        show = sheet == ReaderSheet.Catalog,
        title = "目录",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        CatalogSheet(
            chapters = content.chapters,
            current = chapterIndex,
            marks = marks,
            palette = palette,
            onPickChapter = {
                sheet = null
                jump(it, null)
            },
            onPickPosition = { ch, block ->
                sheet = null
                jump(ch, block)
            },
            onDeleteBookmark = vm::deleteBookmark,
            onDeleteNote = vm::deleteNote,
            onExportNotes = { vm.notesMarkdown()?.let { shareText(context, "《${entry.title}》批注", it) } },
        )
    }
    // 面板画在最外层的 Scaffold 里，拿不到这里提供的 CompositionLocal，光线读数要直接传进去。
    val ambient = LocalAmbientLight.current
    GlassSheet(
        show = sheet == ReaderSheet.Settings,
        title = "阅读设置",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        val fonts by vm.fonts.collectAsStateWithLifecycle()
        SettingsSheet(settings, palette, vm::updateSettings, fonts, vm::importFonts, vm::deleteFont, ambient)
    }
    GlassSheet(
        show = sheet == ReaderSheet.ReadAloud && aloud != null,
        title = "听书",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        aloud?.let { a -> ReadAloudSheet(a, palette, onRate = vm::setReadAloudRate, onVoice = vm::setReadAloudVoice) }
    }
    val paragraph = sheet as? ReaderSheet.Paragraph
    val paragraphText = paragraph?.let { chapter.blocks.getOrNull(it.block)?.displayText }
    GlassSheet(
        show = paragraph != null && paragraphText != null,
        title = "段落",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
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
    GlassSheet(
        show = sheet == ReaderSheet.MultiNote,
        title = "划线",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        MultiNoteSheet(vm.selectionText().orEmpty(), palette) { color, note ->
            vm.annotateSelection(color, note)
            sheet = null
        }
    }
    // 书摘卡片：收起动画期间还显示原来的内容。
    var lastCard by remember { mutableStateOf<QuoteCardContent?>(null) }
    (sheet as? ReaderSheet.Card)?.let { lastCard = it.content }
    GlassSheet(
        show = sheet is ReaderSheet.Card,
        title = "书摘卡片",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        lastCard?.let { c ->
            val typeface = remember(settings.font, settings.serif) { vm.cardTypeface(settings) }
            QuoteCardSheet(c, typeface, palette)
        }
    }
    // 注释面板：收起动画期间还显示原来的内容。
    var lastFootnote by remember { mutableStateOf<String?>(null) }
    footnote?.let { lastFootnote = it }
    GlassSheet(
        show = footnote != null,
        title = "注释",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { footnote = null },
    ) {
        Text(
            lastFootnote?.let { content.notes[it] } ?: "这本书里找不到这条注释。",
            color = palette.text,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            fontFamily = styles.fontFamily,
            modifier = Modifier.padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
        )
    }
    LookupOverlay(vm, palette) { l ->
        vm.closeLookup()
        if (l.chapterIndex == chapterIndex) sheet = ReaderSheet.Paragraph(l.block, l.range)
    }
}

/** 划选后的操作条。 */
@Composable
private fun SelectionBar(
    palette: ReaderPalette,
    onLookup: (() -> Unit)?,
    onAnnotate: () -> Unit,
    onCopy: () -> Unit,
    onCard: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        SelectionBarRow(palette, onLookup, onAnnotate, onCopy, onCard, onCancel)
    }
}

@Composable
private fun SelectionBarRow(
    palette: ReaderPalette,
    onLookup: (() -> Unit)?,
    onAnnotate: () -> Unit,
    onCopy: () -> Unit,
    onCard: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .readerGlass(RoundedCornerShape(50), palette, LocalGlassBackdrop.current)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 跨段落的选区不查词。
        listOfNotNull(onLookup?.let { "查词" to it }, "划线·批注" to onAnnotate, "复制" to onCopy, "卡片" to onCard, "取消" to onCancel).forEach { (label, action) ->
            Text(
                label,
                color = if (label == "取消") palette.secondary else palette.text,
                fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = action).padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun LookupOverlay(vm: AppViewModel, palette: ReaderPalette, onAnnotate: (Lookup) -> Unit) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    // 面板收起的动画期间还要显示内容，记住最后一次查的词。
    var last by remember { mutableStateOf<Lookup?>(null) }
    lookup?.let { last = it }
    GlassSheet(
        show = lookup != null,
        title = "查词",
        background = palette.background,
        highlight = glassHighlight(palette.isDark, big = true),
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

/** 跳转后浮在页底的“回到刚才”。 */
@Composable
private fun ReturnChip(title: String, palette: ReaderPalette, backdrop: Backdrop?, onReturn: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .readerGlass(RoundedCornerShape(50), palette, backdrop)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "↩ 回到刚才 · $title",
            color = palette.accent,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onReturn)
                .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        )
        Text(
            "✕",
            color = palette.secondary,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = "不用回去" }
                .padding(horizontal = 10.dp, vertical = 9.dp),
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
