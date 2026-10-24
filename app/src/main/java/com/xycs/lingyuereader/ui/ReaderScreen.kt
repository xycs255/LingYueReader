package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.xycs.lingyuereader.ui.glass.GlassSheet
import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.glassHighlight
import com.xycs.lingyuereader.ui.glass.readerGlass
import com.xycs.lingyuereader.ui.glass.recordGlass
import com.xycs.lingyuereader.ui.glass.rememberGlassBackdrop
import com.xycs.lingyuereader.tts.ReadAloud
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.magnifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import com.xycs.lingyuereader.data.TapZones
import com.xycs.lingyuereader.data.ReadingOrientation
import com.xycs.lingyuereader.data.TapAction
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventType
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.xycs.lingyuereader.data.MarkColor
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import com.xycs.lingyuereader.ui.glass.GlassMenu
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import androidx.compose.ui.unit.em
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
import com.xycs.lingyuereader.data.layoutKey
import com.xycs.lingyuereader.data.displayText
import com.xycs.lingyuereader.data.TextAnchors
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text

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
        // 自己选了深色的灰蓝，不再换成墨夜。
        settings.theme == ReaderTheme.SLATE -> ReaderTheme.SLATE
        lightLevel == LightLevel.DARK -> ReaderTheme.NIGHT
        lightLevel == null && settings.autoNight && systemDark -> ReaderTheme.NIGHT
        else -> settings.theme
    }
    val dim = if (lightLevel == LightLevel.DIM && theme != ReaderTheme.NIGHT) 0.9f else 1f
    val palette = if (eInk) Effects.eInkPalette(settings.theme == ReaderTheme.NIGHT) else animatePalette(settings.paletteOf(theme).adjusted(dim, warmth))

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
    OrientationLock(settings.orientation)
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
                Reader(vm, screen, loaded.entry, loaded.content, loaded.sourceContent, settings, palette)
            }
        }
    }
}

/** 当前阅读位置。正文的字符坐标不含排版缩进；像素偏移只用于读取旧版进度。 */
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
private fun startOf(screen: Screen.Reader, entry: BookEntry, sourceContent: BookContent): Position {
    screen.chapterIndex?.let { index ->
        val c = index.coerceIn(sourceContent.chapters.indices)
        return Position(c, screen.blockIndex?.let { it + HEADER_ITEMS } ?: 0, screen.offset, screen.char)
    }
    val p = entry.progress ?: return Position(0, 0, 0, 0)
    val byIndex = sourceContent.chapters.getOrNull(p.chapterIndex)?.takeIf { it.title == p.chapterTitle }
    val index = if (byIndex != null) p.chapterIndex else sourceContent.chapters.indexOfFirst { it.title == p.chapterTitle }
    if (index < 0) return Position(p.chapterIndex.coerceIn(sourceContent.chapters.indices), 0, 0, 0)
    val chapter = sourceContent.chapters[index]
    val found = TextAnchors.resolve(chapter, p.itemIndex - HEADER_ITEMS, p.charOffset, p.anchor)
    val block = (found?.block ?: p.itemIndex - HEADER_ITEMS).coerceIn(-1, chapter.blocks.lastIndex)
    val char = found?.char ?: p.charOffset.coerceIn(0, chapter.blocks.getOrNull(block)?.displayText?.length ?: 0)
    // 新进度用文字恢复；只有旧进度才依赖当时的像素偏移。
    return Position(index, block + HEADER_ITEMS, if (p.anchor.text.isEmpty()) p.itemOffset else 0, char)
}

/** 横排分页坐标包含视觉缩进，进度与书签始终使用不含缩进的原文坐标。 */
internal fun pageSourceChar(chapter: com.xycs.lingyuereader.data.Chapter, block: Int, layoutChar: Int, indent: Boolean): Int =
    (layoutChar - (chapter.blocks.getOrNull(block)?.let { indentOf(it, indent) } ?: 0)).coerceAtLeast(0)

internal fun pageLayoutChar(chapter: com.xycs.lingyuereader.data.Chapter, block: Int, sourceChar: Int, indent: Boolean): Int =
    sourceChar + (chapter.blocks.getOrNull(block)?.let { indentOf(it, indent) } ?: 0)

private sealed interface ReaderSheet {
    data object Catalog : ReaderSheet
    /** 阅读设置；[more] 为真时从“更多设置”那页开始（菜单里的“设置”），否则从“外观”开始。 */
    data class Settings(val more: Boolean = false) : ReaderSheet
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
    sourceContent: BookContent,
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
    // 翻页模式分好的页：换章时阅读区重建，这份留着，前后章不必重分。
    val pageCache = remember(content) { PageCache() }
    // 正在翻的那一页翻到哪：书页厚度跟着它走。
    val pageTurn = remember(content) { PageTurn() }
    val initial = remember(screen, content, sourceContent) { startOf(screen, entry, sourceContent) }
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
        vm.startReadAloud(chapterIndex, block, pos.char)
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
    // 全书读到哪：纸书靠手里两边的厚度知道，屏幕上只能看（Mangen 等 2019）。
    val bookFraction = run {
        val total = chapterStarts.last().coerceAtLeast(1)
        ((chapterStarts[chapterIndex] + pos.fraction.coerceIn(0f, 1f) * chapter.charCount) / total).coerceIn(0f, 1f)
    }
    val readingStats by vm.readingStats.collectAsStateWithLifecycle()
    val speed = remember(readingStats) { ReadingStats.charsPerMinute(readingStats) }
    val chapterLeft = remember(pos.fraction, chapter, speed) {
        val minutes = (1f - pos.fraction.coerceIn(0f, 1f)) * chapter.charCount / speed
        when {
            chapter.charCount == 0 -> null // 漫画、PDF 没有文字，估不出时间
            minutes < 1f -> "本章不到 1 分钟"
            else -> "本章余 ${kotlin.math.ceil(minutes).toInt()} 分钟"
        }
    }

    val goTo: (Int, Int?, Boolean) -> Unit = { index, block, highlight ->
        if (index in content.chapters.indices) {
            vm.replaceTop(Screen.Reader(entry.id, index, block, highlight = highlight))
            menuVisible = false
        }
    }
    // 从目录、书签、进度条跳走：先记下现在读到哪里，跳过去后可以一键回来。
    val jump: (Int, Int?, Boolean) -> Unit = { index, block, highlight ->
        if (index in content.chapters.indices) {
            vm.markJump(ReturnPoint(entry.id, pos.chapter, pos.block, pos.offset, pos.char, content.chapters[pos.chapter].title))
            goTo(index, block, highlight)
        }
    }
    val jumpAt: (Int, Int, Int) -> Unit = { index, block, char ->
        if (index in content.chapters.indices) {
            vm.markJump(ReturnPoint(entry.id, pos.chapter, pos.block, pos.offset, pos.char, content.chapters[pos.chapter].title))
            vm.replaceTop(Screen.Reader(entry.id, index, block, char = char, highlight = true))
            menuVisible = false
        }
    }
    // 跳过来的那一段：亮一下再慢慢褪掉（墨水屏上不渐变，亮两秒后消失）。
    val landing = remember(screen) { Animatable(if (screen.highlight && screen.blockIndex != null) 1f else 0f) }
    LaunchedEffect(screen) {
        if (landing.value <= 0f) return@LaunchedEffect
        if (Effects.noMotion) {
            delay(2000)
            landing.snapTo(0f)
        } else {
            landing.animateTo(0f, tween(1600, delayMillis = 700, easing = Motion.Standard))
        }
    }
    val returnPoint by vm.returnPoint.collectAsStateWithLifecycle()
    val currentSettings by rememberUpdatedState(settings)
    val textSelection by vm.selection.collectAsStateWithLifecycle()
    // 自动滚动：开着时点正文是暂停/继续。
    var autoScroll by remember { mutableStateOf(false) }
    var autoPaused by remember { mutableStateOf(false) }
    // 点击区域要知道点在多高：按下时在最外层记下（各处传进来的只有横向位置）。
    val touch = remember { object { var y = 0.5f } }
    val onTap: (Float) -> Unit = { x ->
        when {
            // 有划选时，点一下只是取消划选。
            vm.selection.value != null -> vm.clearSelection()
            autoScroll && !menuVisible -> autoPaused = !autoPaused
            menuVisible -> menuVisible = false
            !currentSettings.tapToTurn -> menuVisible = true
            else -> {
                val zones = currentSettings.tapZones
                val i = TapZones.indexOf(x, touch.y)
                // 哪一格都不是菜单时，中间那格仍打开菜单，免得菜单再也叫不出来。
                val action = if (i == 4 && TapAction.MENU !in zones) TapAction.MENU else zones[i]
                when (action) {
                    TapAction.PREV -> turner.turn?.invoke(false)
                    TapAction.NEXT -> turner.turn?.invoke(true)
                    TapAction.MENU -> menuVisible = true
                    TapAction.NONE -> {}
                }
            }
        }
    }
    // 双指捏合调字号：捏的时候只显示字号，松手再重新排版。
    var pinchSize by remember { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current
    // 长按正文选出按住的词，松手就查；不松手拖动是自由划选（可跨段落，拖到上下边缘自动滚动），
    // 松手后可拖两端把手微调。
    // 点开的注释（键）。
    var footnote by remember { mutableStateOf<String?>(null) }
    // 长按插图（漫画、PDF 的页）打开的大图。
    var zoomed by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val edgePx = with(density) { 56.dp.toPx() }
    val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
    // 拖把手时手指在把手上，对应的字在手指上方约这么高。
    val liftPx = with(density) { 30.dp.toPx() }
    val selectHaptic = LocalHapticFeedback.current
    val pressRing = remember { PressRing() }
    val ringScope = rememberCoroutineScope()
    val tracker = remember(vm, chapterIndex) {
        SelectionTracker(
            onPress = { block, char ->
                // 长按选中一个词：轻震一下，蓄力环迸开，告诉手指已经抓住了。
                selectHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                pressRing.burst(ringScope)
                // 开始选字就收起菜单，免得挡住操作条。
                menuVisible = false
                vm.pressText(chapterIndex, block, char)
            },
            onDrag = vm::dragText,
            onRelease = vm::releaseText,
            onHandle = vm::moveSelectionHandle,
        )
    }
    // 划选、拖把手时选区每变一格轻震一下（系统和 Compose 都这样），手指能感觉到走了几个字。
    val selectionEnds = textSelection?.let { it.start to it.end }
    var lastEnds by remember { mutableStateOf(selectionEnds) }
    LaunchedEffect(selectionEnds) {
        if (selectionEnds != null && lastEnds != null && selectionEnds != lastEnds && tracker.mode != null && tracker.moved) {
            selectHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastEnds = selectionEnds
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
                    if (chapterIndex < content.chapters.lastIndex) goTo(chapterIndex + 1, null, false) else autoScroll = false
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
            .onGloballyPositioned {
                tracker.root = it
                tracker.dp = density.density
                tracker.touchSlop = viewConfig.touchSlop
                tracker.lensHalfWidth = with(density) { 75.dp.toPx() }
            }
            // 选字开始后，手指的移动和松手都在这里统一处理（抢在列表滚动、翻页之前）。
            .pointerInput(tracker) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { touch.y = it.position.y / size.height.coerceAtLeast(1) }
                        }
                        // 手指在哪：放大镜跟着它。
                        event.changes.firstOrNull()?.let { tracker.finger = if (it.pressed) it.position else Offset.Unspecified }
                        if (tracker.mode == null) continue
                        val change = event.changes.firstOrNull() ?: continue
                        change.consume()
                        if (change.pressed) tracker.track(change.position, edgePx, liftPx, change.uptimeMillis) else tracker.finish(change.uptimeMillis)
                    }
                }
            }
            // 长按蓄力环：按住超过 0.15 秒，手指周围出现一圈慢慢画满，满了就选中；移动或松手就消失。
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val timeout = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (Effects.noMotion || menuVisible || sheet != null || !tracker.overText(down.position)) return@awaitEachGesture
                    pressRing.start(down.position, ringScope, timeout)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: break
                        if (e.changes.size > 1 || !c.pressed || (c.position - down.position).getDistance() > slop) break
                    }
                    pressRing.cancel(ringScope)
                }
            }
            .then(
                // Robolectric 里的系统放大镜显示后一收起就崩（没有真正的 Surface），测试环境不挂。
                if (android.os.Build.FINGERPRINT == "robolectric") {
                    Modifier
                } else {
                    Modifier.magnifier(
                        sourceCenter = { tracker.lens },
                        // 放大镜在正在选的那一行上方；太靠上时放到下方。
                        magnifierCenter = {
                            val src = tracker.lens
                            if (!src.isSpecified) Offset.Unspecified
                            else {
                                val lift = 76.dp.toPx()
                                if (src.y - lift < 48.dp.toPx()) src + Offset(0f, lift) else src - Offset(0f, lift)
                            }
                        },
                        zoom = 1.5f,
                        size = DpSize(150.dp, 56.dp),
                        cornerRadius = 28.dp,
                    )
                },
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var startDistance = 0f
                    var startSize = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size < 2) continue
                        val d = (pressed[0].position - pressed[1].position).getDistance()
                        if (startDistance <= 0f) {
                            startDistance = d.coerceAtLeast(1f)
                            startSize = currentSettings.fontSize
                            vm.clearSelection()
                        }
                        val next = (startSize * d / startDistance).coerceIn(14f, 30f)
                        // 每跨过一个整数字号轻轻震一下。
                        if (kotlin.math.round(next) != kotlin.math.round(pinchSize ?: startSize)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pinchSize = next
                        // 两指在捏：不让下面的列表滚动、翻页、选字。
                        event.changes.forEach { it.consume() }
                    }
                    pinchSize?.let { size -> vm.updateSettings { it.copy(fontSize = kotlin.math.round(size)) } }
                    pinchSize = null
                }
            },
    ) {
        // 书页（正文和状态行）记录成玻璃的取样源，菜单栏、控制条、操作条和底部面板都透出它。
        Box(Modifier.fillMaxSize().recordGlass(glassBackdrop)) {
        val landingBlock = screen.blockIndex ?: -1
        CompositionLocalProvider(
            LocalLanding provides remember(landingBlock, landing) { Landing(landingBlock) { landing.value } },
            LocalBookImages provides images,
            LocalImageMaxHeight provides screenHeight * 0.8f,
            LocalImageZoom provides { zoomed = it },
        ) {
            if (settings.pageMode == PageMode.SCROLL) {
                key(screen, content, settings.layoutKey) {
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
                        startItem = start.item,
                        startChar = start.char,
                        onFootnote = { footnote = it },
                        selected = selected,
                        onPosition = { item, _, char, fraction ->
                            pos = Position(chapterIndex, item, 0, char, fraction, "${(fraction * 100).toInt()}%")
                        },
                        onNext = { goTo(chapterIndex + 1, null, false) },
                        registerTurner = { turner.turn = it },
                        registerScroller = { tracker.scroller = it },
                        speaking = speaking,
                    )
                }
            } else {
                // 书页夹在两摞纸中间（书页厚度），翻页时两摞跟着换。
                val turnedReader: @Composable () -> Unit = {
                    if (settings.pageMode == PageMode.VERTICAL) {
                        key(screen, content, settings.layoutKey) {
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
                                turn = pageTurn,
                                selected = lookup?.takeIf { it.chapterIndex == chapterIndex }?.let { l -> l.range?.let { l.block to it } },
                            )
                        }
                    } else {
                        // 翻页模式的分页取决于排版设置，设置一变就从当前位置重新分页。
                        // 只在排版设置变化时重新分页；配色随环境渐变时不必。
                        key(screen, content, settings.layoutKey) {
                            val start = remember { pos }
                            PagedReader(
                                content = content,
                                chapterIndex = chapterIndex,
                                start = PageStart(start.block, pageLayoutChar(chapter, start.block, start.char, settings.indent), screen.fromEnd && start == initial),
                                styles = styles,
                                mode = settings.pageMode,
                                marks = marks,
                                // 每页顶上留出页眉。
                                topInset = topInset + PAGE_HEADER,
                                bottomInset = bottomInset,
                                onTap = onTap,
                                selector = tracker,
                                onFootnote = { footnote = it },
                                selected = selected,
                                onPosition = { block, char, fraction, label ->
                                    pos = Position(chapterIndex, block + HEADER_ITEMS, 0, pageSourceChar(chapter, block, char, settings.indent), fraction, label)
                                },
                                onChapterChange = { index, fromEnd -> vm.replaceTop(Screen.Reader(entry.id, index, null, fromEnd)) },
                                registerTurner = { turner.turn = it },
                                speaking = speaking,
                                pageCache = pageCache,
                                turn = pageTurn,
                            )
                        }
                    }
                }
                if (settings.pageEdges) {
                    BookEdges(
                        fraction = bookFraction,
                        pageShare = pageTurn.chapterShare * chapter.charCount / chapterStarts.last().coerceAtLeast(1),
                        turn = pageTurn,
                        mode = settings.pageMode,
                        palette = palette,
                        rightToLeft = settings.pageMode == PageMode.VERTICAL,
                        thickness = remember(content) { bookThickness(chapterStarts.last()) },
                        content = turnedReader,
                    )
                } else {
                    turnedReader()
                }
            }
        }

        StatusLine(
            chapter.title,
            listOfNotNull("${chapterIndex + 1}/${content.chapters.size}", pos.label, chapterLeft).joinToString(" · "),
            palette,
            Modifier.align(Alignment.BottomCenter),
            bookFraction,
            // 系统状态栏藏起来时，页脚补上时间和电量。
            clock = settings.fullscreen,
        )
        }


        // 捏合时的字号气泡：弹出来，里面的“永”字跟着变大变小。
        var bubbleSize by remember { mutableFloatStateOf(settings.fontSize) }
        pinchSize?.let { bubbleSize = it }
        AnimatedVisibility(
            visible = pinchSize != null,
            enter = Effects.enter(scaleIn(Motion.pop(), initialScale = 0.7f) + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(scaleOut(Motion.exit(), targetScale = 0.85f) + fadeOut(Motion.exit())),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                Modifier
                    .readerGlass(RoundedCornerShape(50), palette, glassBackdrop)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("永", color = palette.text, fontSize = kotlin.math.round(bubbleSize).sp, fontFamily = styles.fontFamily)
                Text("字号 ${kotlin.math.round(bubbleSize).toInt()}", color = palette.secondary, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        // 选区两端的把手：盖在正文上面，段末那一行下方的把手也按得到。
        selected?.takeIf { it.handles }?.let { SelectionHandles(tracker, it, palette.accent) }
        PressRingOverlay(pressRing, palette.accent)
        val back = returnPoint?.takeIf { it.bookId == entry.id }
        AnimatedVisibility(
            visible = back != null && !menuVisible && listening == null && !autoScroll,
            // 从页底升上来，轻轻弹一下。
            enter = Effects.enter(slideInVertically(Motion.pop()) { it / 2 } + fadeIn(Motion.standard(Motion.SHORT)) + scaleIn(Motion.pop(), initialScale = 0.9f)),
            exit = Effects.exit(slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit())),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 4.dp),
        ) {
            back?.let { ReturnChip(it.chapterTitle, palette, glassBackdrop, onReturn = vm::jumpBack, onDismiss = vm::clearReturnPoint) }
        }
        AnimatedVisibility(
            visible = listening != null && !menuVisible,
            enter = Effects.enter(slideInVertically(Motion.emphasized(Motion.MEDIUM)) { it / 2 } + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit())),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            listening?.let { l -> ReadAloudBar(l, palette, onSettings = { sheet = ReaderSheet.ReadAloud }) }
        }
        AnimatedVisibility(
            visible = autoScroll && listening == null && !menuVisible,
            enter = Effects.enter(slideInVertically(Motion.emphasized(Motion.MEDIUM)) { it / 2 } + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit())),
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
            enter = Effects.enter(slideInVertically(Motion.emphasized(Motion.MEDIUM)) { -it } + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(slideOutVertically(Motion.exit(Motion.MEDIUM)) { -it } + fadeOut(Motion.exit())),
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
                    Icon(LingYueIcons.Back, contentDescription = "返回书架", tint = palette.text)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(entry.title, color = palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(entry.author?.takeIf { it.isNotBlank() }, "已读 ${(bookFraction * 100).toInt()}%").joinToString(" · "),
                        color = palette.secondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { vm.toggleBookmark(chapterIndex, pos.block.coerceAtLeast(0), pos.char) }) {
                    Icon(
                        if (bookmarked) LingYueIcons.FavoritesFill else LingYueIcons.Favorites,
                        contentDescription = if (bookmarked) "删除书签" else "加书签",
                        tint = if (bookmarked) palette.accent else palette.text,
                    )
                }
                IconButton(onClick = { vm.open(Screen.Search(entry.id)) }) {
                    Icon(LingYueIcons.Search, contentDescription = "书内搜索", tint = palette.text)
                }
                // 不常用的收进“更多”：自动滚动 / 自动翻页、书籍详情。
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) {
                        Icon(LingYueIcons.More, contentDescription = "更多", tint = palette.text)
                    }
                    OverlayListPopup(show = more, alignment = PopupPositionProvider.Align.End, onDismissRequest = { more = false }) {
                        GlassMenu {
                            ListPopupColumn {
                                LingYueMenuItem(if (settings.pageMode == PageMode.SCROLL) "自动滚动" else "自动翻页", selected = autoScroll) {
                                    more = false
                                    autoScroll = true
                                    autoPaused = false
                                    menuVisible = false
                                }
                                LingYueMenuItem("书籍详情", selected = false) {
                                    more = false
                                    vm.open(Screen.Detail(entry.id))
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = Effects.enter(slideInVertically(Motion.emphasized(Motion.MEDIUM)) { it } + fadeIn(Motion.standard(Motion.SHORT))),
            exit = Effects.exit(slideOutVertically(Motion.exit(Motion.MEDIUM)) { it } + fadeOut(Motion.exit())),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .readerGlass(RoundedCornerShape(30.dp), palette, glassBackdrop)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                BookProgress(
                    content,
                    pos.chapter,
                    pos.fraction,
                    pageLabel = pos.label.takeIf { settings.pageMode != PageMode.SCROLL },
                    bookFraction = bookFraction,
                    palette = palette,
                    onPrev = { goTo(chapterIndex - 1, null, false) },
                    onNext = { goTo(chapterIndex + 1, null, false) },
                ) { ch, block -> jump(ch, block, false) }
                Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth().height(0.5.dp).background(palette.text.copy(alpha = 0.1f)))
                Row(Modifier.fillMaxWidth()) {
                    BarButton("目录", LingYueIcons.ListView, palette, Modifier.weight(1f)) { sheet = ReaderSheet.Catalog }
                    BarButton(
                        if (listening != null) "听书中" else "听书",
                        LingYueIcons.VolumeUp,
                        palette,
                        Modifier.weight(1f),
                        active = listening != null,
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
                    BarButton("外观", null, palette, Modifier.weight(1f), active = sheet == ReaderSheet.Settings(false)) { sheet = ReaderSheet.Settings(false) }
                    BarButton("设置", LingYueIcons.Tune, palette, Modifier.weight(1f)) { sheet = ReaderSheet.Settings(true) }
                }
            }
        }
        // 操作条贴着选区：优先放在首行上方，放不下就放在末行（和把手）下方。放在最后，盖在其他浮层之上。
        val bounds = selected?.takeIf { textSelection?.dragging == false }?.let(tracker::bounds)
        if (bounds != null) {
            SelectionBar(
                palette,
                onLookup = vm::lookupSelection.takeIf { textSelection?.singleBlock == true },
                // 划线：直接用黄色划上，不弹面板；要写批注、换颜色点“批注”。
                onHighlight = { vm.annotateSelection(MarkColor.YELLOW, "") },
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

    // 面板的强调色：没在“主题设置”里挑过强调色、也没开 Monet 时，跟着书页走（纸白朱砂、墨夜琥珀）；挑过就用挑的。
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val appTint = if (appearance.accent != 0 || appearance.monet) MiuixTheme.colorScheme.primary else palette.accent
    GlassSheet(
        show = sheet == ReaderSheet.Catalog,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
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
                jump(it, null, false)
            },
            onPickPosition = { ch, block, char ->
                sheet = null
                jumpAt(ch, block, char)
            },
            onDeleteBookmark = vm::deleteBookmark,
            onDeleteNote = vm::deleteNote,
            onExportNotes = { vm.notesMarkdown()?.let { shareText(context, "《${entry.title}》批注", it) } },
            tint = appTint,
            bookTitle = entry.title,
            author = entry.author,
            bookFraction = bookFraction,
            chapterFraction = pos.fraction,
            charsPerMinute = speed.toFloat(),
            onClose = { sheet = null },
        )
    }
    // 面板画在最外层的 Scaffold 里，拿不到这里提供的 CompositionLocal，光线读数要直接传进去。
    val ambient = LocalAmbientLight.current
    // 阅读设置是苹果式的分组面板：底色用分组灰（深色书页用深灰），标题和返回在面板里面。
    // 收起动画期间还按原来那一页显示。
    var lastSettings by remember { mutableStateOf(ReaderSheet.Settings()) }
    (sheet as? ReaderSheet.Settings)?.let { lastSettings = it }
    GlassSheet(
        show = sheet is ReaderSheet.Settings,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        val fonts by vm.fonts.collectAsStateWithLifecycle()
        key(lastSettings.more) {
            SettingsSheet(
                settings, palette, vm::updateSettings, fonts, vm::importFonts, vm::deleteFont, ambient,
                tint = appTint,
                startWithMore = lastSettings.more,
                onDone = { sheet = null },
            )
        }
    }
    GlassSheet(
        show = sheet == ReaderSheet.ReadAloud && aloud != null,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        aloud?.let { a -> ReadAloudSheet(a, palette, onRate = vm::setReadAloudRate, onVoice = vm::setReadAloudVoice, tint = appTint) }
    }
    val paragraph = sheet as? ReaderSheet.Paragraph
    val paragraphText = paragraph?.let { chapter.blocks.getOrNull(it.block)?.displayText }
    GlassSheet(
        show = paragraph != null && paragraphText != null,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
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
                tint = appTint,
            )
        }
    }
    GlassSheet(
        show = sheet == ReaderSheet.MultiNote,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        MultiNoteSheet(vm.selectionText().orEmpty(), palette, appTint) { color, note ->
            vm.annotateSelection(color, note)
            sheet = null
        }
    }
    // 书摘卡片：收起动画期间还显示原来的内容。
    var lastCard by remember { mutableStateOf<QuoteCardContent?>(null) }
    (sheet as? ReaderSheet.Card)?.let { lastCard = it.content }
    GlassSheet(
        show = sheet is ReaderSheet.Card,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { sheet = null },
    ) {
        lastCard?.let { c ->
            val typeface = remember(settings.font, settings.serif) { vm.cardTypeface(settings) }
            QuoteCardSheet(c, typeface, palette, appTint)
        }
    }
    // 注释面板：收起动画期间还显示原来的内容。
    var lastFootnote by remember { mutableStateOf<String?>(null) }
    footnote?.let { lastFootnote = it }
    GlassSheet(
        show = footnote != null,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, appTint, elevated = true).background,
        highlight = glassHighlight(palette.isDark, big = true),
        onDismissRequest = { footnote = null },
    ) {
        FootnoteSheet(lastFootnote?.let { content.notes[it] } ?: "这本书里找不到这条注释。", palette, styles.fontFamily, appTint) { footnote = null }
    }
    LookupOverlay(vm, palette, appTint) { l ->
        vm.closeLookup()
        if (l.chapterIndex == chapterIndex) sheet = ReaderSheet.Paragraph(l.block, l.range)
    }
}

/** 划选后的操作条：一颗深色胶囊（墨色底、纸色字），点书页别处就收起。 */
@Composable
private fun SelectionBar(
    palette: ReaderPalette,
    onLookup: (() -> Unit)?,
    onHighlight: () -> Unit,
    onAnnotate: () -> Unit,
    onCopy: () -> Unit,
    onCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 出现时从 0.9 倍弹到原大小。
    val appear = remember { Animatable(if (Effects.noMotion) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.pop()) }
    Box(
        modifier.graphicsLayer {
            alpha = appear.value.coerceIn(0f, 1f)
            val s = 0.9f + 0.1f * appear.value
            scaleX = s
            scaleY = s
        },
    ) {
        SelectionBarRow(palette, onLookup, onHighlight, onAnnotate, onCopy, onCard)
    }
}

@Composable
private fun SelectionBarRow(
    palette: ReaderPalette,
    onLookup: (() -> Unit)?,
    onHighlight: () -> Unit,
    onAnnotate: () -> Unit,
    onCopy: () -> Unit,
    onCard: () -> Unit,
) {
    Row(
        Modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(palette.text)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 跨段落的选区不查词。
        val actions = listOfNotNull(onLookup?.let { "查词" to it }, "划线" to onHighlight, "批注" to onAnnotate, "复制" to onCopy, "卡片" to onCard)
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) Box(Modifier.width(0.5.dp).height(18.dp).background(palette.background.copy(alpha = 0.25f)))
            Text(
                label,
                color = palette.background,
                fontSize = 14.sp,
                fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = action)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LookupOverlay(vm: AppViewModel, palette: ReaderPalette, tint: Color, onAnnotate: (Lookup) -> Unit) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    // 面板收起的动画期间还要显示内容，记住最后一次查的词。
    var last by remember { mutableStateOf<Lookup?>(null) }
    lookup?.let { last = it }
    GlassSheet(
        show = lookup != null,
        title = null,
        background = groupedColors(palette.background.luminance() < 0.5f, tint, elevated = true).background,
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
                tint = tint,
            )
        }
    }
}

/** 全书进度条：按“第几章 + 章内比例”定位，拖动时显示将要跳到的章名，松手跳转。 */
@Composable
private fun BookProgress(
    content: BookContent,
    chapter: Int,
    fraction: Float,
    pageLabel: String?,
    /** 全书按字数读到哪；拖动时按拖到的章和章内位置现算。 */
    bookFraction: Float,
    palette: ReaderPalette,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: (Int, Int?) -> Unit,
) {
    val count = content.chapters.size
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: (chapter + fraction.coerceIn(0f, 0.999f))
    val target = value.toInt().coerceIn(0, count - 1)
    val starts = remember(content) { content.chapters.runningFold(0L) { acc, c -> acc + c.charCount } }
    val percent = dragging?.let { v ->
        val total = starts.last().coerceAtLeast(1)
        ((starts[target] + (v - target).coerceIn(0f, 1f) * content.chapters[target].charCount) / total * 100).toInt()
    } ?: (bookFraction * 100).toInt()
    Column {
        // 上一章 · 章名（拖动时是要跳到的那一章）· 下一章
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChapterStep("上一章", LingYueIcons.ChevronBackward, before = true, enabled = chapter > 0, palette, onPrev)
            Text(
                content.chapters[target].title,
                color = if (dragging != null) palette.accent else palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ChapterStep("下一章", LingYueIcons.ChevronForward, before = false, enabled = chapter < count - 1, palette, onNext)
        }
        ProvideGroupedColors(dark = palette.isDark, tint = palette.accent) {
            LingYueSlider(
                value = value,
                onValueChange = { dragging = it },
                valueRange = 0f..count.toFloat(),
                label = "全书进度",
                valueText = "全书 ${percent.coerceIn(0, 100)}%",
                onValueChangeFinished = {
                    dragging?.let { v ->
                        val ch = v.toInt().coerceIn(0, count - 1)
                        val blocks = content.chapters[ch].blocks.size
                        val within = v - ch
                        onJump(ch, if (within < 0.01f || blocks == 0) null else (within * blocks).toInt().coerceIn(0, blocks - 1))
                    }
                    dragging = null
                },
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
            Text("全书 ${percent.coerceIn(0, 100)}%", color = palette.secondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (pageLabel != null) Text("本章 $pageLabel", color = palette.secondary, fontSize = 11.sp)
        }
    }
}

/** 菜单里的“‹ 上一章”“下一章 ›”：强调色小字，到头了变灰。 */
@Composable
private fun ChapterStep(label: String, icon: ImageVector, before: Boolean, enabled: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    val color = if (enabled) palette.accent else palette.secondary.copy(alpha = 0.5f)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (before) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, color = color, fontSize = 13.sp)
        if (!before) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }
}

/** 长按蓄力环的状态：在哪、画了多少、是否正在迸开。 */
private class PressRing {
    var at by mutableStateOf(Offset.Unspecified)
    val progress = Animatable(0f)
    val burst = Animatable(0f)
    private var job: kotlinx.coroutines.Job? = null

    fun start(position: Offset, scope: kotlinx.coroutines.CoroutineScope, longPressMillis: Long) {
        job?.cancel()
        job = scope.launch {
            burst.snapTo(0f)
            progress.snapTo(0f)
            // 快速点按不出现，按住了才开始画。
            delay(150)
            at = position
            progress.animateTo(1f, tween((longPressMillis - 150).toInt().coerceAtLeast(100), easing = LinearEasing))
        }
    }

    fun cancel(scope: kotlinx.coroutines.CoroutineScope) {
        if (burst.isRunning) return
        job?.cancel()
        scope.launch {
            at = Offset.Unspecified
            progress.snapTo(0f)
        }
    }

    /** 长按生效：环迸开淡出。 */
    fun burst(scope: kotlinx.coroutines.CoroutineScope) {
        if (!at.isSpecified) return
        job?.cancel()
        job = scope.launch {
            progress.snapTo(1f)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(280, easing = Motion.Standard))
            at = Offset.Unspecified
            burst.snapTo(0f)
            progress.snapTo(0f)
        }
    }
}

@Composable
private fun PressRingOverlay(ring: PressRing, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val c = ring.at
        if (!c.isSpecified) return@Canvas
        val r = 30.dp.toPx()
        val b = ring.burst.value
        if (b > 0f) {
            drawCircle(color.copy(alpha = 0.28f * (1f - b)), radius = r * (1f + 0.6f * b), center = c)
            return@Canvas
        }
        val p = ring.progress.value
        if (p <= 0f) return@Canvas
        val stroke = 3.dp.toPx()
        drawCircle(color.copy(alpha = 0.15f), radius = r, center = c, style = Stroke(stroke))
        drawArc(
            color.copy(alpha = 0.75f),
            startAngle = -90f,
            sweepAngle = 360f * p,
            useCenter = false,
            topLeft = c - Offset(r, r),
            size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
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
        Row(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onReturn)
                .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(LingYueIcons.Back, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
            Text("回到刚才 · $title", color = palette.accent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            LingYueIcons.Close,
            contentDescription = "不用回去",
            tint = palette.secondary,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDismiss)
                .padding(15.dp),
        )
    }
}

@Composable
private fun StatusLine(title: String, label: String, palette: ReaderPalette, modifier: Modifier, bookFraction: Float = 0f, clock: Boolean = false) {
    // 读到下一处时，全书进度线顺滑地走过去，而不是一格格跳。
    val shown by animateFloatAsState(bookFraction, Motion.standard(Motion.MEDIUM), label = "book")
    // 页脚：一根全书进度细线，下面一行小字——左边章名，右边页码和本章还剩多久。
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.92f))
            .semantics { contentDescription = "全书已读 ${(bookFraction * 100).toInt()}%" }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(1.dp).drawBehind {
                drawRect(palette.text.copy(alpha = 0.12f))
                drawRect(palette.accent, size = Size(size.width * shown, size.height))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = palette.secondary, fontSize = 11.sp, letterSpacing = 0.04.em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(label, color = palette.secondary, fontSize = 11.sp, letterSpacing = 0.04.em)
            if (clock) {
                Text(rememberClock(), color = palette.secondary, fontSize = 11.sp)
                rememberBattery()?.let { Text("$it%", color = palette.secondary, fontSize = 11.sp) }
            }
        }
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
private fun BarButton(
    label: String,
    icon: ImageVector?,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> palette.text.copy(alpha = 0.3f)
        active -> palette.accent
        else -> palette.text
    }
    // 一格：上面图标（“外观”是衬线的 Aa），下面小字；开着的那项底下铺一层淡强调色。
    Column(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) palette.accent.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        } else {
            Text("Aa", color = color, fontSize = 19.sp, lineHeight = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        }
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
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !darkBackground
        controller?.isAppearanceLightNavigationBars = !darkBackground
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller?.hide(WindowInsetsCompat.Type.statusBars()) else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
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

/** 阅读页锁定方向，离开阅读页恢复跟随系统。 */
@Composable
private fun OrientationLock(orientation: ReadingOrientation) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(orientation) {
        activity.requestedOrientation = when (orientation) {
            ReadingOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReadingOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReadingOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
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
