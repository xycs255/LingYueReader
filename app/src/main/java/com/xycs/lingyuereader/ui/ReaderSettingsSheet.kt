package com.xycs.lingyuereader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.ChineseConvert
import com.xycs.lingyuereader.data.FontStore
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.ReadingOrientation
import com.xycs.lingyuereader.data.TapAction
import com.xycs.lingyuereader.data.TapZones
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/*
 * 阅读设置面板，照 Apple Books 的“主题与设置”来做（样稿见 docs/design.md）：
 * - 菜单里的“外观”打开首页：字号、亮度、书页配色、字体、排版预设，最常调的几样一屏放下；
 * - 菜单里的“设置”打开“阅读设置”：其余按用途分成几页（字体、排版、翻页、点按与按键、护眼、屏幕、繁简），
 *   从列表点进去，左上角返回。每页都是分组列表，一行一个短名字，解释写在组下面的小字里。
 */

/** 面板里的各页。 */
private enum class SettingsPage(val title: String) {
    MAIN("外观"),
    MORE("阅读设置"),
    FONT("字体"),
    LAYOUT("排版"),
    PAGING("翻页方式"),
    TAP("点按与按键"),
    EYE("护眼"),
    DISPLAY("屏幕"),
    TEXT("繁简与注音"),
    COLORS("自定义配色"),
}

@Composable
internal fun SettingsSheet(
    settings: ReaderSettings,
    palette: ReaderPalette,
    update: ((ReaderSettings) -> ReaderSettings) -> Unit,
    fonts: List<String> = emptyList(),
    onImportFonts: (List<android.net.Uri>) -> Unit = {},
    onDeleteFont: (String) -> Unit = {},
    ambient: AmbientLight? = null,
    /** 强调色。面板画在最外层，拿不到应用主题，由阅读页取好传进来。 */
    tint: Color = palette.accent,
    /** 从“阅读设置”那页开始（菜单里的“设置”），否则从“外观”开始。 */
    startWithMore: Boolean = false,
    /** 首页右上角“完成”。 */
    onDone: () -> Unit = {},
) {
    ProvideReaderColors(palette, tint) {
        // 导航栈：点进去压一页，返回弹一页，回到起始页后再返回就关面板。
        var stack by rememberSaveable { mutableStateOf(listOf(if (startWithMore) SettingsPage.MORE else SettingsPage.MAIN)) }
        val page = stack.last()
        fun open(p: SettingsPage) { stack = stack + p }
        fun back() { stack = stack.dropLast(1) }
        BackHandler(enabled = stack.size > 1) { back() }
        // 面板跟着内容高矮，最高到屏幕的 85%，内容多的页在里面滚动；换页时高度渐变过去，不跳。
        val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            // 标题跟着页一起换：新标题从翻页的方向滑进来、旧的淡出，不和内容脱节。
            AnimatedContent(
                targetState = stack,
                transitionSpec = {
                    val forward = targetState.size > initialState.size
                    (slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it / 4 else -it / 4 } + fadeIn(Motion.standard(Motion.SHORT)))
                        .togetherWith(fadeOut(Motion.exit()))
                },
                label = "settingsTitle",
            ) { s ->
                SheetNavBar(
                    s.last().title,
                    back = s.getOrNull(s.size - 2)?.title,
                    action = ("完成" to onDone).takeIf { s.size == 1 },
                ) { back() }
            }
            AnimatedContent(
                targetState = stack,
                contentKey = { it.last() },
                transitionSpec = {
                    // 进下一页从右推入，返回从左回来（导航栈变深是进，变浅是退）。
                    val forward = targetState.size > initialState.size
                    (slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it else -it / 3 } + fadeIn(Motion.standard(Motion.SHORT)))
                        .togetherWith(slideOutHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) -it / 3 else it } + fadeOut(Motion.exit()))
                },
                label = "settingsPage",
                modifier = Modifier.weight(1f, fill = false),
            ) { st ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 12.dp)) {
                    when (st.last()) {
                        SettingsPage.MAIN -> MainPage(settings, update, fonts, ::open)
                        SettingsPage.MORE -> MorePage(settings, fonts, ::open)
                        SettingsPage.FONT -> FontPage(settings, fonts, update, onImportFonts, onDeleteFont)
                        SettingsPage.LAYOUT -> LayoutPage(settings, update)
                        SettingsPage.PAGING -> PagingPage(settings, update)
                        SettingsPage.TAP -> TapPage(settings, update)
                        SettingsPage.EYE -> EyePage(settings, ambient, update)
                        SettingsPage.DISPLAY -> DisplayPage(settings, update)
                        SettingsPage.TEXT -> TextPage(settings, update)
                        SettingsPage.COLORS -> ColorsPage(settings, update)
                    }
                }
            }
        }
    }
}

// ---------- 首页：字号、亮度、配色，和各项入口 ----------

@Composable
private fun MainPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, fonts: List<String>, open: (SettingsPage) -> Unit) {
    // 一张卡片一件事：亮度；书页背景；文字（字号、字体、排版）；翻页和其余设置。每行左边写着是什么，右边是控件。
    GroupedSection {
        row { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { BrightnessRow(settings.brightness) { b -> update { it.copy(brightness = b) } } } }
    }
    GroupedSection(header = "背景") {
        row { Box(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) { ThemeCircles(settings) { theme -> update { it.copy(theme = theme) } } } }
        if (settings.theme == ReaderTheme.CUSTOM) row { NavRow("自定义配色", onClick = { open(SettingsPage.COLORS) }) }
    }
    GroupedSection(header = "文字") {
        row { FontSizeRow(settings.fontSize) { size -> update { it.copy(fontSize = size) } } }
        row { NavRow("字体", fontLabel(settings, fonts)) { open(SettingsPage.FONT) } }
        row { LabeledRow("排版") { LayoutSegment(settings, update, Modifier.width(192.dp)) } }
    }
    GroupedSection {
        row { NavRow("翻页方式", settings.pageMode.label) { open(SettingsPage.PAGING) } }
        row { NavRow("更多设置") { open(SettingsPage.MORE) } }
    }
}

/** 菜单里的“设置”：其余各项按用途分组，点进去。 */
@Composable
private fun MorePage(settings: ReaderSettings, fonts: List<String>, open: (SettingsPage) -> Unit) {
    GroupedSection {
        row { NavRow("字体", fontLabel(settings, fonts)) { open(SettingsPage.FONT) } }
        row { NavRow("排版", LayoutPreset.entries.firstOrNull { it.matches(settings) }?.label ?: "手动调整") { open(SettingsPage.LAYOUT) } }
        row { NavRow("翻页方式", settings.pageMode.label) { open(SettingsPage.PAGING) } }
        row {
            NavRow("繁简与注音", listOfNotNull(settings.chineseConvert.label, "注音".takeIf { settings.pinyin }).joinToString(" · ")) {
                open(SettingsPage.TEXT)
            }
        }
    }
    GroupedSection {
        row { NavRow("点按与按键", if (settings.tapToTurn) "点按翻页" else "关闭点按") { open(SettingsPage.TAP) } }
        row { NavRow("护眼", eyeSummary(settings)) { open(SettingsPage.EYE) } }
        row { NavRow("屏幕", if (settings.eInk) "墨水屏" else settings.orientation.label) { open(SettingsPage.DISPLAY) } }
    }
    if (settings.theme == ReaderTheme.CUSTOM) {
        GroupedSection { row { NavRow("自定义配色", onClick = { open(SettingsPage.COLORS) }) } }
    }
}

/** 字号：左边写“字号”，右边一个步进器——小“A”、当前字号、大“A”。 */
@Composable
private fun FontSizeRow(size: Float, onChange: (Float) -> Unit) {
    val c = LocalGroupedColors.current
    LabeledRow("字号") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Step(textSize: Int, enabled: Boolean, description: String, delta: Float) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(source, 0.88f)
                        .clickable(enabled = enabled, interactionSource = source, indication = null) { onChange((size + delta).coerceIn(14f, 30f)) }
                        .semantics { contentDescription = description; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
                        Text("A", color = if (enabled) c.label else c.tertiary, fontSize = textSize.sp, fontFamily = FontFamily.Serif)
                    }
                }
            }
            Step(13, size > 14f, "字号减小", -1f)
            Text(
                "${size.toInt()}",
                color = c.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(44.dp),
            )
            Step(19, size < 30f, "字号增大", 1f)
        }
    }
}

/** 亮度：两头小太阳、大太阳，右边一颗“自动”（跟随系统）。 */
@Composable
private fun BrightnessRow(brightness: Float, onChange: (Float) -> Unit) {
    val c = LocalGroupedColors.current
    val auto = brightness < 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(LingYueIcons.Brightness, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
        // 亮度条用灰色，不用强调色：它不是“选中”，只是一个量。
        CompositionLocalProvider(LocalGroupedColors provides c.copy(tint = c.secondary)) {
            LingYueSlider(
                value = if (auto) 0.5f else brightness,
                onValueChange = { v -> onChange(v.coerceAtLeast(0.02f)) },
                valueRange = 0f..1f,
                label = "亮度",
                valueText = if (auto) "跟随系统" else "${(brightness * 100).toInt()}%",
                modifier = Modifier.weight(1f),
            )
        }
        Icon(LingYueIcons.Brightness, contentDescription = null, tint = c.secondary, modifier = Modifier.size(22.dp))
        Box(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .toggleable(value = auto, role = Role.Switch) { on -> onChange(if (on) -1f else 0.5f) }
                .semantics { contentDescription = "亮度跟随系统" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "自动",
                color = if (auto) c.tint else c.secondary,
                fontSize = 13.sp,
                fontWeight = if (auto) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (auto) c.tint.copy(alpha = 0.12f) else c.fill)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 书页配色：一排圆，圆里用该配色写一个“文”；选中的外面套一圈强调色，圈从小弹出来；按下时圆缩一点。 */
@Composable
private fun ThemeCircles(settings: ReaderSettings, onPick: (ReaderTheme) -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ReaderTheme.entries.forEach { theme ->
            val p = settings.paletteOf(theme)
            val on = settings.theme == theme
            val source = remember { MutableInteractionSource() }
            val ring by animateFloatAsState(if (on) 1f else 0f, Motion.pop(), label = "ring")
            val labelColor by animateColorAsState(if (on) c.label else c.secondary, Motion.standard(Motion.SHORT), label = "themeLabel")
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = on, role = Role.RadioButton, interactionSource = source, indication = null) { onPick(theme) }
                    .semantics { contentDescription = "${theme.label}背景" }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(52.dp).pressScale(source, 0.9f), contentAlignment = Alignment.Center) {
                    // 选中圈：从 0.8 倍弹到原大小、渐显。
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = ring.coerceIn(0f, 1f)
                                val s = 0.8f + 0.2f * ring
                                scaleX = s
                                scaleY = s
                            }
                            .border(2.dp, c.tint, CircleShape),
                    )
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(p.background)
                            .border(0.5.dp, c.separator, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (theme == ReaderTheme.CUSTOM) "＋" else "文", color = p.text, fontFamily = FontFamily.Serif, fontSize = 17.sp)
                    }
                }
                Text(theme.label, color = labelColor, fontSize = 12.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 排版预设：紧凑 / 标准 / 宽松，手动调过就都不选。 */
@Composable
private fun LayoutSegment(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, modifier: Modifier = Modifier) {
    SegmentedControl(LayoutPreset.entries.map { it to it.label }, LayoutPreset.entries.firstOrNull { it.matches(settings) }, modifier, height = 36.dp) { p -> update(p::apply) }
}

private fun fontLabel(settings: ReaderSettings, fonts: List<String>): String =
    settings.font.takeIf { it in fonts }?.let(FontStore::label) ?: if (settings.serif) "宋体" else "黑体"

private fun eyeSummary(s: ReaderSettings): String = listOfNotNull(
    "环境光".takeIf { s.ambientLight && !s.eInk },
    "变暖".takeIf { s.eveningWarmth && !s.eInk },
    "远眺".takeIf { s.breakReminder },
).joinToString(" · ").ifEmpty { "关闭" }

// ---------- 字体 ----------

@Composable
private fun FontPage(
    settings: ReaderSettings,
    fonts: List<String>,
    update: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = LocalGroupedColors.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val current = settings.font.takeIf { it in fonts }
    GroupedSection(header = "内置") {
        row { CheckRow("宋体", current == null && settings.serif) { update { it.copy(serif = true, font = "") } } }
        row { CheckRow("黑体", current == null && !settings.serif) { update { it.copy(serif = false, font = "") } } }
    }
    GroupedSection(
        header = if (fonts.isNotEmpty()) "导入的字体" else null,
        footer = "支持 TTF、OTF、TTC，比如霞鹜文楷、思源宋体。" + if (fonts.isNotEmpty()) "长按导入的字体可以删除。" else "",
    ) {
        fonts.forEach { f ->
            // 长按出“删除”，相当于苹果列表里的左滑删除。
            row { CheckRow(FontStore.label(f), current == f, onLongClick = { deleting = f }) { update { it.copy(font = f) } } }
            if (deleting == f) row { ActionRow("删除", destructive = true) { onDelete(f); deleting = null } }
        }
        row { ActionRow("导入字体…") { picker.launch(arrayOf("*/*")) } }
    }
    if (deleting != null) {
        Text(
            "取消",
            color = c.tint,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 32.dp).clickable { deleting = null }.padding(vertical = 8.dp),
        )
    }
    GroupedSection(footer = "笔画细的字体、调暗屏幕或环境光弱时，正文加粗一些更好认。") {
        row { ToggleRow("粗体文本", settings.boldText) { v -> update { it.copy(boldText = v) } } }
    }
}

// ---------- 排版 ----------

/** 一键排版：字号、行距、段距、字距一起改。宽松给年长读者和读字吃力时用（字距加大能减轻拥挤效应）。 */
internal enum class LayoutPreset(val label: String, val fontSize: Float, val lineHeight: Float, val paragraphSpacing: Float, val letterSpacing: Float) {
    COMPACT("紧凑", 18f, 1.6f, 0.4f, 0.02f),
    STANDARD("标准", 19f, 1.8f, 0.6f, 0.03f),
    RELAXED("宽松", 22f, 2.0f, 0.8f, 0.1f),
    ;

    fun matches(s: ReaderSettings) =
        s.fontSize == fontSize && s.lineHeight == lineHeight && s.paragraphSpacing == paragraphSpacing && s.letterSpacing == letterSpacing

    fun apply(s: ReaderSettings) = s.copy(fontSize = fontSize, lineHeight = lineHeight, paragraphSpacing = paragraphSpacing, letterSpacing = letterSpacing)
}

@Composable
private fun LayoutPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection(header = "预设", footer = "宽松：字更大、字距和行距放开，读字吃力时字不挤在一起。") {
        row {
            SegmentedRow(LayoutPreset.entries.map { it to it.label }, LayoutPreset.entries.firstOrNull { it.matches(settings) }) { p -> update(p::apply) }
        }
    }
    GroupedSection(header = "细调") {
        row {
            LayoutSliderRow("行距", settings.lineHeight, { "%.1f".format(it) }, 1.3f..2.6f) { v -> update { it.copy(lineHeight = v) } }
        }
        row {
            LayoutSliderRow("段距", settings.paragraphSpacing, { "%.1f".format(it) }, 0f..1.5f) { v -> update { it.copy(paragraphSpacing = v) } }
        }
        row {
            LayoutSliderRow("字距", settings.letterSpacing, { "%.2f".format(it) }, 0f..0.2f) { v -> update { it.copy(letterSpacing = v) } }
        }
        row {
            LayoutSliderRow("边距", settings.margin, { "${it.toInt()}" }, 8f..48f, steps = 9) { v -> update { it.copy(margin = v) } }
        }
    }
    GroupedSection {
        row { ToggleRow("段首空两格", settings.indent) { v -> update { it.copy(indent = v) } } }
        row { ToggleRow("两端对齐", settings.justify) { v -> update { it.copy(justify = v) } } }
    }
    GroupedSection {
        row {
            ActionRow("恢复默认排版") {
                val d = ReaderSettings()
                update { it.copy(fontSize = d.fontSize, lineHeight = d.lineHeight, paragraphSpacing = d.paragraphSpacing, letterSpacing = d.letterSpacing, margin = d.margin) }
            }
        }
    }
}

// ---------- 翻页方式 ----------

@Composable
internal fun PagingPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection(header = "试翻") {
        row { PageModePreview(settings.pageMode) }
        row {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMode.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { mode ->
                            PageModeChoice(mode, settings.pageMode == mode, Modifier.weight(1f)) {
                                update { it.copy(pageMode = mode) }
                            }
                        }
                    }
                }
            }
        }
    }
    GroupedSection {
        row { ToggleRow("书页厚度", settings.pageEdges) { v -> update { it.copy(pageEdges = v) } } }
        row { ToggleRow("横屏时双页", settings.twoPage) { v -> update { it.copy(twoPage = v) } } }
    }
}

/** 只演示一次因果关系，实际阅读的翻页方式仍由阅读页负责。关动画时直接给终态。 */
@Composable
internal fun PageModePreview(mode: PageMode, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    val noMotion = Effects.noMotion
    var replay by remember(mode) { mutableIntStateOf(0) }
    val turn = remember(mode, noMotion) { Animatable(if (noMotion || mode == PageMode.NONE) 1f else 0f) }
    LaunchedEffect(mode, replay, noMotion) {
        if (noMotion) turn.snapTo(if (replay % 2 == 0) 1f else 0f)
        else if (mode == PageMode.NONE) turn.snapTo(1f)
        else {
            turn.snapTo(0f)
            turn.animateTo(1f, Motion.standard(if (mode == PageMode.CURL) Motion.CURL_TURN else Motion.TURN))
        }
    }
    val vertical = mode == PageMode.VERTICAL
    val page = if (mode == PageMode.NONE && replay % 2 == 1) 0 else 1
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(164.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(c.fill)
            .clickable(role = Role.Button) { replay++ }
            .semantics {
                contentDescription = "${mode.label}翻页预览，${if (noMotion) "点按切换静态纸页" else "点按重播"}。${mode.previewMeaning()}"
                stateDescription = if (noMotion) "减少动态效果，显示第${if (replay % 2 == 0) 2 else 1}页" else if (mode == PageMode.NONE) "立即切换到第${page + 1}页" else "翻页演示"
            },
        contentAlignment = Alignment.Center,
    ) {
        val paper = if (c.cell.luminance() < 0.4f) Color(0xFF39342E) else Color(0xFFF6F0E5)
        val ink = if (c.cell.luminance() < 0.4f) Color(0xFFE9DFD0) else Color(0xFF524A40)
        BoxWithConstraints(Modifier.width(220.dp).height(130.dp).clip(RoundedCornerShape(10.dp)).clipToBounds()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val amount = turn.value
            when (mode) {
                PageMode.SCROLL -> {
                    PreviewPaper(1, false, paper, ink, Modifier.graphicsLayer { translationY = heightPx * (1f - amount) })
                    PreviewPaper(0, false, paper, ink, Modifier.graphicsLayer { translationY = -heightPx * amount })
                }
                PageMode.SLIDE -> {
                    PreviewPaper(1, false, paper, ink, Modifier.graphicsLayer { translationX = widthPx * (1f - amount) })
                    PreviewPaper(0, false, paper, ink, Modifier.graphicsLayer { translationX = -widthPx * amount })
                }
                PageMode.COVER -> {
                    PreviewPaper(0, false, paper, ink)
                    PreviewPaper(1, false, paper, ink, Modifier.graphicsLayer { translationX = widthPx * (1f - amount) })
                }
                PageMode.CURL -> {
                    PreviewPaper(1, false, paper, ink)
                    // 预览直接画阅读页的真实折角，避免旋转矩形与实际仿真表现不一致。
                    if (amount < 1f) PreviewPaper(0, false, paper, ink, Modifier.pageCurl({ amount }, remember { CurlTouch() }, paper))
                }
                PageMode.NONE -> PreviewPaper(page, false, paper, ink)
                PageMode.VERTICAL -> {
                    PreviewPaper(1, vertical, paper, ink, Modifier.graphicsLayer { translationX = -widthPx * (1f - amount) })
                    PreviewPaper(0, vertical, paper, ink, Modifier.graphicsLayer { translationX = widthPx * amount })
                }
            }
        }
        Icon(
            LingYueIcons.Redo,
            contentDescription = null,
            tint = c.secondary,
            modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(17.dp),
        )
    }
}

/** 暖纸缩图使用少量真正的文字和墨线，图形本身不进入无障碍树。 */
@Composable
private fun PreviewPaper(page: Int, vertical: Boolean, paper: Color, ink: Color, modifier: Modifier = Modifier) {
    Box(
        Modifier.fillMaxSize().then(modifier).background(paper)
            .border(1.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .clearAndSetSemantics { },
    ) {
        if (vertical) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                listOf("春\n风\n起", "山\n河\n新", if (page == 0) "开\n卷\n时" else "续\n读\n时").forEach { column ->
                    Text(column, color = ink, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(if (page == 0) "第一章" else "第二页", color = ink.copy(alpha = 0.6f), fontSize = 10.sp)
                Spacer(Modifier.height(9.dp))
                Text(if (page == 0) "翻开故事" else "接着读下去", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                listOf(0.95f, 0.83f, 0.89f).forEach { width ->
                    Box(Modifier.fillMaxWidth(width).height(3.dp).clip(CircleShape).background(ink.copy(alpha = 0.22f)))
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
        Text("0${page + 1}", color = ink.copy(alpha = 0.55f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

@Composable
private fun PageModeChoice(mode: PageMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val fill by animateColorAsState(if (selected) c.tint.copy(alpha = 0.15f) else c.fill, Motion.standard(Motion.SHORT), label = "pageModeFill")
    val border by animateColorAsState(if (selected) c.tint.copy(alpha = 0.6f) else c.separator, Motion.standard(Motion.SHORT), label = "pageModeBorder")
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${mode.label}：${mode.previewMeaning()}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 13.dp).size(7.dp).clip(CircleShape).background(if (selected) c.tint else c.secondary.copy(alpha = 0.45f)))
        Text(mode.label, color = if (selected) c.tint else c.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

private fun PageMode.previewMeaning(): String = when (this) {
    PageMode.SCROLL -> "正文向上移动，接着阅读"
    PageMode.SLIDE -> "旧页向左移出，新页从右进入"
    PageMode.COVER -> "新页从右盖住旧页"
    PageMode.CURL -> "纸角翻起，露出下一页"
    PageMode.NONE -> "点按后立即换页"
    PageMode.VERTICAL -> "竖排文字向右翻到下一页"
}

// ---------- 点按与按键 ----------

@Composable
private fun TapPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection {
        row { ToggleRow("点按翻页", settings.tapToTurn) { v -> update { it.copy(tapToTurn = v) } } }
    }
    if (settings.tapToTurn) {
        GroupedSection(header = "点按区域", footer = "点一格换一种动作。竖排时左右对调；双指捏合正文可以直接调字号。") {
            row {
                SegmentedRow(listOf(TapZones.DEFAULT to "左右翻页", TapZones.ONE_HAND to "单手"), settings.tapZones.takeIf { it == TapZones.DEFAULT || it == TapZones.ONE_HAND }) { z ->
                    update { it.copy(tapZones = z) }
                }
            }
            row { TapZoneGrid(settings.tapZones) { z -> update { it.copy(tapZones = z) } } }
        }
    }
    GroupedSection {
        row { ToggleRow("音量键翻页", settings.volumeKeys) { v -> update { it.copy(volumeKeys = v) } } }
    }
}

/** 九格点按区域，示意整块屏幕：点一格换下一种动作。 */
@Composable
private fun TapZoneGrid(zones: List<TapAction>, onChange: (List<TapAction>) -> Unit) {
    val c = LocalGroupedColors.current
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, c.separator, RoundedCornerShape(16.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (0 until 3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until 3).forEach { col ->
                        val i = row * 3 + col
                        val action = zones[i]
                        Box(
                            Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when (action) {
                                        TapAction.MENU -> c.tint.copy(alpha = 0.16f)
                                        TapAction.NONE -> Color.Transparent
                                        else -> c.fill
                                    },
                                )
                                .clickable {
                                    val next = TapAction.entries[(action.ordinal + 1) % TapAction.entries.size]
                                    onChange(zones.toMutableList().also { it[i] = next })
                                }
                                .semantics { contentDescription = "点击区域第${row + 1}行第${col + 1}格：${action.label}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(action.label, color = if (action == TapAction.MENU) c.tint else if (action == TapAction.NONE) c.secondary else c.label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 护眼 ----------

@Composable
private fun EyePage(settings: ReaderSettings, ambient: AmbientLight?, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection(
        footer = buildString {
            append("昏暗时压暗背景，很暗时换夜间，减少屏幕与周围的亮度落差。")
            when {
                !settings.ambientLight -> {}
                settings.eInk -> append("墨水屏 / 省电模式下不读光线传感器。")
                ambient == null -> {}
                !ambient.available -> append("这台手机没有光线传感器，按系统深色设置。")
                ambient.lux >= 0 -> append("当前环境光约 ${ambient.lux} lx（${ambient.level?.label ?: "测量中"}）。")
                else -> append("正在读取环境光…")
            }
        },
    ) {
        row { ToggleRow("跟随环境光", settings.ambientLight) { v -> update { it.copy(ambientLight = v) } } }
        row { ToggleRow("系统深色时用夜间背景", settings.autoNight) { v -> update { it.copy(autoNight = v) } } }
    }
    GroupedSection(footer = "日落后书页慢慢偏暖，只为看着柔和，不宣称护眼或助眠。") {
        row { ToggleRow("日落后逐渐变暖", settings.eveningWarmth) { v -> update { it.copy(eveningWarmth = v) } } }
    }
    GroupedSection(footer = "每读 20 分钟提醒看远处 20 秒。") {
        row { ToggleRow("远眺提醒", settings.breakReminder) { v -> update { it.copy(breakReminder = v) } } }
    }
}

// ---------- 屏幕 ----------

@Composable
private fun DisplayPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection {
        row { ToggleRow("全屏", settings.fullscreen, subtitle = null) { v -> update { it.copy(fullscreen = v) } } }
        row { ToggleRow("阅读时屏幕常亮", settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } } }
    }
    GroupedSection(header = "方向") {
        row { SegmentedRow(ReadingOrientation.entries.map { it to it.label }, settings.orientation) { v -> update { it.copy(orientation = v) } } }
    }
    GroupedSection(footer = "关掉玻璃、动画和环境光变色，书页纯黑白，翻页不带动画。墨水屏阅读器配合左右翻页最好。") {
        row { ToggleRow("墨水屏 / 省电模式", settings.eInk) { v -> update { it.copy(eInk = v) } } }
    }
}

// ---------- 繁简与注音 ----------

@Composable
private fun TextPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection(header = "繁简", footer = "只改显示，不改原文；书签、批注照常对得上。") {
        ChineseConvert.entries.forEach { mode ->
            row { CheckRow(mode.label, settings.chineseConvert == mode) { update { it.copy(chineseConvert = mode) } } }
        }
    }
    GroupedSection(footer = "在汉字上方标拼音，多音字按词定读音。") {
        row { ToggleRow("注音", settings.pinyin) { v -> update { it.copy(pinyin = v) } } }
    }
}

// ---------- 自定义配色 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    val c = LocalGroupedColors.current

    @Composable
    fun Swatches(label: String, colors: List<Long>, selected: Int, onPick: (Int) -> Unit) {
        FlowRow(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            colors.forEach { value ->
                val argb = value.toInt()
                val on = argb == selected
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onPick(argb) }
                        .semantics { contentDescription = "$label #%06X".format(argb and 0xFFFFFF); this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .border(if (on) 2.5.dp else 0.5.dp, if (on) c.tint else c.separator, CircleShape)
                            .padding(if (on) 4.dp else 0.dp)
                            .clip(CircleShape)
                            .background(Color(argb)),
                    )
                }
            }
        }
    }
    val ratio = contrastRatio(Color(settings.customBackground), Color(settings.customText))
    // 预览：用选好的底色和字色排一行字。
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 26.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(settings.customBackground))
            .border(0.5.dp, c.separator, RoundedCornerShape(12.dp))
            .padding(vertical = 18.dp, horizontal = 16.dp),
    ) {
        Text("　　城门口排着长队，人人手里拿着一张纸。", color = Color(settings.customText), fontFamily = FontFamily.Serif, fontSize = 17.sp)
    }
    GroupedSection(header = "底色") {
        row { Swatches("底色", CUSTOM_BACKGROUNDS, settings.customBackground) { v -> update { it.copy(customBackground = v) } } }
    }
    GroupedSection(
        header = "字色",
        footer = "对比度 %.1f : 1".format(ratio) + when {
            ratio < 4.5f -> "，太低，看久了费眼，换一个更深（或更浅）的字色吧。"
            ratio < 7f -> "，可以读，再拉开一点更不累眼。"
            else -> "，适合长时间阅读。"
        },
    ) {
        row { Swatches("字色", CUSTOM_TEXTS, settings.customText) { v -> update { it.copy(customText = v) } } }
    }
}

/** 自定义背景可选的底色：几种纸色、浅蓝、浅粉，和几种深底。 */
private val CUSTOM_BACKGROUNDS = listOf(
    0xFFF4F2EC, 0xFFFFFFFF, 0xFFEFE6D8, 0xFFF2E6CC, 0xFFDCEBD2, 0xFFDDE7EE, 0xFFF1E4E4,
    0xFF2B2B2B, 0xFF1E2A35, 0xFF151413, 0xFF000000,
)
private val CUSTOM_TEXTS = listOf(
    0xFF2A2724, 0xFF000000, 0xFF3E3222, 0xFF26331F, 0xFF1F2D3D, 0xFF5A4636,
    0xFFC4B48E, 0xFFD6D2C8, 0xFFB8C7D6, 0xFFFFFFFF,
)

/**
 * 改排版的滑块：拖动时只改显示的数，松手才保存。每动一下都保存的话，整章要跟着重新分页，
 * 又卡，阅读位置还会一点点往前漂。
 */
@Composable
private fun LayoutSliderRow(
    title: String,
    value: Float,
    format: (Float) -> String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember(value) { mutableStateOf(value) }
    LabeledSliderRow(title, dragging, format(dragging), valueRange, steps, onValueChangeFinished = { if (dragging != value) onCommit(dragging) }) { dragging = it }
}
