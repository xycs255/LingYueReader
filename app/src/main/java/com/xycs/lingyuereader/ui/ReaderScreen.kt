package com.xycs.lingyuereader.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 列表里第 0 项是章名，正文块从第 1 项开始。 */
private const val HEADER_ITEMS = 1

@Composable
fun ReaderScreen(vm: AppViewModel, screen: Screen.Reader) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val book by vm.openBook.collectAsStateWithLifecycle()
    val palette = settings.theme.palette()

    LaunchedEffect(screen.bookId) { vm.ensureLoaded(screen.bookId) }
    SystemBarsAppearance(darkBackground = palette.isDark)
    KeepScreenOn(settings.keepScreenOn)

    val loaded = book?.takeIf { it.entry.id == screen.bookId }
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = palette.accent)
        }
        return
    }
    Reader(vm, screen, loaded.entry, loaded.content, settings)
}

/** 进度里的章序与章名对不上时（文稿刷新后增删了章），按章名找回。 */
private fun resolveStart(screen: Screen.Reader, entry: BookEntry, content: BookContent): Triple<Int, Int, Int> {
    screen.chapterIndex?.let { index ->
        val c = index.coerceIn(content.chapters.indices)
        return Triple(c, screen.blockIndex?.let { it + HEADER_ITEMS } ?: 0, 0)
    }
    val p = entry.progress ?: return Triple(0, 0, 0)
    val byIndex = content.chapters.getOrNull(p.chapterIndex)?.takeIf { it.title == p.chapterTitle }
    val index = if (byIndex != null) p.chapterIndex else content.chapters.indexOfFirst { it.title == p.chapterTitle }
    return if (index >= 0) Triple(index, p.itemIndex, p.itemOffset) else Triple(p.chapterIndex.coerceIn(content.chapters.indices), 0, 0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reader(vm: AppViewModel, screen: Screen.Reader, entry: BookEntry, content: BookContent, settings: ReaderSettings) {
    val palette = settings.theme.palette()
    val start = remember(screen, content) { resolveStart(screen, entry, content) }
    val chapterIndex = start.first
    val chapter = content.chapters[chapterIndex]
    var menuVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }

    val goTo: (Int) -> Unit = { index ->
        if (index in content.chapters.indices) {
            vm.replaceTop(Screen.Reader(entry.id, index))
            menuVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        // 换章时重建列表状态，从进度或指定段落开始。
        key(screen, content) {
            val listState = rememberLazyListState(start.second, start.third)

            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collectLatest { (item, offset) ->
                        delay(300)
                        vm.saveProgress(chapterIndex, item, offset)
                    }
            }

            ChapterText(
                chapter = chapter,
                nextTitle = content.chapters.getOrNull(chapterIndex + 1)?.title,
                settings = settings,
                listState = listState,
                onToggleMenu = { menuVisible = !menuVisible },
                onNext = { goTo(chapterIndex + 1) },
            )

            ChapterStatus(listState, chapter, chapterIndex, content.chapters.size, settings, Modifier.align(Alignment.BottomCenter))
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bar)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架", tint = palette.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(chapter.title, color = palette.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.open(Screen.Search(entry.id)) }) {
                    Icon(Icons.Outlined.Search, contentDescription = "书内搜索", tint = palette.text)
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bar)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BarButton("上一章", Icons.Outlined.ChevronLeft, palette.text, enabled = chapterIndex > 0) {
                    goTo(chapterIndex - 1)
                }
                BarButton("目录", Icons.AutoMirrored.Outlined.List, palette.text) { sheet = ReaderSheet.CATALOG }
                BarButton("设置", Icons.Outlined.TextFields, palette.text) { sheet = ReaderSheet.SETTINGS }
                BarButton("下一章", Icons.Outlined.ChevronRight, palette.text, enabled = chapterIndex < content.chapters.lastIndex) {
                    goTo(chapterIndex + 1)
                }
            }
        }
    }

    when (sheet) {
        ReaderSheet.CATALOG -> ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = palette.background,
        ) {
            CatalogSheet(content.chapters, chapterIndex, palette.text, palette.accent, palette.secondary) {
                sheet = null
                goTo(it)
            }
        }
        ReaderSheet.SETTINGS -> ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = palette.background,
        ) {
            SettingsSheet(settings, palette.text, palette.accent, vm::updateSettings)
        }
        null -> Unit
    }
}

private enum class ReaderSheet { CATALOG, SETTINGS }

@Composable
internal fun ChapterText(
    chapter: Chapter,
    nextTitle: String?,
    settings: ReaderSettings,
    listState: LazyListState,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
) {
    val palette = settings.theme.palette()
    val fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { (settings.fontSize * settings.lineHeight).sp.toPx() }
    val bars = WindowInsets.systemBars.asPaddingValues()

    // 点左侧三分之一上翻、右侧三分之一下翻、中间呼出菜单。
    fun turnPage(forward: Boolean) {
        val viewport = listState.layoutInfo.viewportSize.height
        val distance = (viewport - lineHeightPx * 2).coerceAtLeast(lineHeightPx)
        scope.launch { listState.animateScrollBy(if (forward) distance else -distance) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(settings.tapToTurn) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        !settings.tapToTurn -> onToggleMenu()
                        offset.x < third -> turnPage(forward = false)
                        offset.x > third * 2 -> turnPage(forward = true)
                        else -> onToggleMenu()
                    }
                }
            },
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = bars.calculateTopPadding() + 16.dp,
            bottom = bars.calculateBottomPadding() + 40.dp,
        ),
    ) {
        item {
            Text(
                chapter.title,
                color = palette.text,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (settings.fontSize * 1.35f).sp,
                lineHeight = (settings.fontSize * 1.35f * 1.4f).sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 32.dp),
            )
        }
        itemsIndexed(chapter.blocks) { _, block ->
            BlockView(block, settings, palette, fontFamily)
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("— 本章完 —", color = palette.secondary, fontFamily = fontFamily)
                Spacer(Modifier.height(24.dp))
                if (nextTitle != null) {
                    OutlinedButton(onClick = onNext) {
                        Text("下一章　$nextTitle", color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("全书完", color = palette.secondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun ChapterStatus(
    listState: LazyListState,
    chapter: Chapter,
    chapterIndex: Int,
    chapterCount: Int,
    settings: ReaderSettings,
    modifier: Modifier,
) {
    val palette = settings.theme.palette()
    val total = chapter.blocks.size + 2
    val percent by remember(chapter) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            when {
                last == null -> 0
                last.index >= total - 1 -> 100
                else -> (last.index * 100 / total).coerceIn(0, 99)
            }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.92f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Text(
            chapter.title,
            color = palette.secondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${chapterIndex + 1}/$chapterCount · $percent%", color = palette.secondary, fontSize = 11.sp)
    }
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
        Icon(icon, contentDescription = null, tint = color)
        Text(label, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun CatalogSheet(chapters: List<Chapter>, current: Int, textColor: Color, accent: Color, secondary: Color, onPick: (Int) -> Unit) {
    val state = rememberLazyListState((current - 3).coerceAtLeast(0))
    Text(
        "共 ${chapters.size} 章",
        color = secondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(480.dp)) {
        itemsIndexed(chapters) { index, chapter ->
            Text(
                chapter.title,
                color = if (index == current) accent else textColor,
                fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(index) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
internal fun SettingsSheet(
    settings: ReaderSettings,
    textColor: Color,
    accent: Color,
    update: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = accent,
        activeTrackColor = accent,
        inactiveTrackColor = textColor.copy(alpha = 0.15f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
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
        Spacer(Modifier.height(8.dp))
        Text("背景", color = textColor, fontSize = 14.sp)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val p = theme.palette()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(if (settings.theme == theme) accent else p.secondary.copy(alpha = 0.4f))
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(p.background)
                            .clickable { update { it.copy(theme = theme) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("文", color = p.text, fontFamily = FontFamily.Serif)
                    }
                    Text(theme.label, color = textColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("字体", color = textColor, fontSize = 14.sp)
            listOf(true to "宋体", false to "黑体").forEach { (serif, label) ->
                FilterChip(
                    selected = settings.serif == serif,
                    onClick = { update { it.copy(serif = serif) } },
                    label = { Text(label, fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = textColor,
                        selectedContainerColor = accent.copy(alpha = 0.18f),
                        selectedLabelColor = accent,
                    ),
                )
            }
        }
        SwitchRow("点击左右两侧翻页", settings.tapToTurn, textColor, accent) { v -> update { it.copy(tapToTurn = v) } }
        SwitchRow("阅读时屏幕常亮", settings.keepScreenOn, textColor, accent) { v -> update { it.copy(keepScreenOn = v) } }
        TextButton(onClick = { update { ReaderSettings(theme = it.theme) } }) {
            Text("恢复默认排版", color = accent)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, textColor: Color, slider: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f)) { slider() }
        Text(value, color = textColor, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, textColor: Color, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent))
    }
}

@Composable
fun SystemBarsAppearance(darkBackground: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkBackground) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = !darkBackground
        controller?.isAppearanceLightNavigationBars = !darkBackground
        onDispose {
            if (previous != null) {
                controller.isAppearanceLightStatusBars = previous
                controller.isAppearanceLightNavigationBars = previous
            }
        }
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
