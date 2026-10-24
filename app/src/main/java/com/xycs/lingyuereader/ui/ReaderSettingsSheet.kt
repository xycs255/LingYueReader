package com.xycs.lingyuereader.ui

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
 * 阅读设置面板，照 Apple Books 的“主题与设置”来做：
 * 打开就是最常调的三样——字号、亮度、书页配色；其余按用途分成几页（字体、排版、翻页、点按与按键、护眼、屏幕、繁简），
 * 从列表点进去，左上角返回。每页都是分组列表，一行一个短名字，解释写在组下面的小字里。
 */

/** 面板里的各页；[parent] 是返回时回到哪一页。 */
private enum class SettingsPage(val title: String) {
    MAIN("阅读设置"),
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
) {
    ProvideGroupedColors(dark = palette.background.luminance() < 0.5f, tint = tint, elevated = true) {
        var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
        BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
        // 面板固定高度，换页时不忽高忽低；内容多的页在里面滚动。
        val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.78f).toDp() }
        Column(Modifier.fillMaxWidth().height(height)) {
            SheetNavBar(page.title, back = if (page == SettingsPage.MAIN) null else SettingsPage.MAIN.title) { page = SettingsPage.MAIN }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // 进下一页从右推入，返回从左回来，像苹果的导航栈。
                    val forward = targetState != SettingsPage.MAIN
                    (slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it else -it / 3 } + fadeIn(Motion.standard(Motion.SHORT)))
                        .togetherWith(slideOutHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) -it / 3 else it } + fadeOut(Motion.exit()))
                },
                label = "settingsPage",
                modifier = Modifier.weight(1f),
            ) { p ->
                Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
                    when (p) {
                        SettingsPage.MAIN -> MainPage(settings, update, fonts) { page = it }
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

/** 面板顶上的标题栏：二级页左边有“‹ 返回”；[action] 是右边的主操作（强调色半粗字，像苹果的“完成”）。 */
@Composable
internal fun SheetNavBar(title: String, back: String?, action: Pair<String, () -> Unit>? = null, onBack: () -> Unit) {
    val c = LocalGroupedColors.current
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
        if (back != null) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(end = 8.dp)
                    .semantics { contentDescription = "返回$back" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LingYueIcons.ChevronBackward, contentDescription = null, tint = c.tint, modifier = Modifier.size(24.dp))
                Text(back, color = c.tint, fontSize = 17.sp)
            }
        }
        Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        if (action != null) {
            Text(
                action.first,
                color = c.tint,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = action.second)
                    .padding(horizontal = 10.dp, vertical = 11.dp),
            )
        }
    }
}

// ---------- 首页：字号、亮度、配色，和各项入口 ----------

@Composable
private fun MainPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, fonts: List<String>, open: (SettingsPage) -> Unit) {
    val c = LocalGroupedColors.current
    GroupedSection {
        row { FontSizeRow(settings.fontSize) { size -> update { it.copy(fontSize = size) } } }
        row {
            SliderRow(
                value = if (settings.brightness < 0) 0.5f else settings.brightness,
                onValueChange = { v -> update { it.copy(brightness = v.coerceAtLeast(0.02f)) } },
                valueRange = 0f..1f,
                label = "亮度",
                valueText = if (settings.brightness < 0) "跟随系统" else "${(settings.brightness * 100).toInt()}%",
                start = { Icon(LingYueIcons.Brightness, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp)) },
                end = { Icon(LingYueIcons.Brightness, contentDescription = null, tint = c.secondary, modifier = Modifier.size(24.dp)) },
            )
        }
        row {
            ToggleRow("亮度跟随系统", settings.brightness < 0) { v -> update { it.copy(brightness = if (v) -1f else 0.5f) } }
        }
    }
    ThemeTiles(settings) { theme -> update { it.copy(theme = theme) } }
    if (settings.theme == ReaderTheme.CUSTOM) {
        GroupedSection { row { NavRow("自定义配色", onClick = { open(SettingsPage.COLORS) }) } }
    }
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
}

/** 字号：左边名字，右边“小 A | 19 | 大 A”的步进器，每点一下一号。 */
@Composable
private fun FontSizeRow(size: Float, onChange: (Float) -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("字号", color = c.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(Modifier.height(36.dp).clip(RoundedCornerShape(9.dp)).background(c.fill), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Step(label: String, textSize: Int, enabled: Boolean, description: String, delta: Float) {
                Box(
                    Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { onChange((size + delta).coerceIn(14f, 30f)) }
                        .semantics { contentDescription = description; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (enabled) c.label else c.tertiary, fontSize = textSize.sp, fontFamily = FontFamily.Serif)
                }
            }
            Step("A", 14, size > 14f, "字号减小", -1f)
            Box(Modifier.width(0.5.dp).height(18.dp).background(c.secondary.copy(alpha = 0.4f)))
            Text("${size.toInt()}", color = c.label, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
            Box(Modifier.width(0.5.dp).height(18.dp).background(c.secondary.copy(alpha = 0.4f)))
            Step("A", 21, size < 30f, "字号增大", 1f)
        }
    }
}

/** 书页配色：一排圆角方块，块里用该配色写一个“文”，选中的描强调色边。 */
@Composable
private fun ThemeTiles(settings: ReaderSettings, onPick: (ReaderTheme) -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ReaderTheme.entries.forEach { theme ->
            val p = settings.paletteOf(theme)
            val on = settings.theme == theme
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPick(theme) }
                    .semantics { contentDescription = "${theme.label}背景"; selected = on }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.background)
                        .border(if (on) 2.5.dp else 0.5.dp, if (on) c.tint else c.separator, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("文", color = p.text, fontFamily = FontFamily.Serif, fontSize = 20.sp)
                }
                Text(theme.label, color = if (on) c.label else c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
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
            LabeledSliderRow("行距", settings.lineHeight, "%.1f".format(settings.lineHeight), 1.3f..2.6f) { v -> update { it.copy(lineHeight = v) } }
        }
        row {
            LabeledSliderRow("段距", settings.paragraphSpacing, "%.1f".format(settings.paragraphSpacing), 0f..1.5f) { v -> update { it.copy(paragraphSpacing = v) } }
        }
        row {
            LabeledSliderRow("字距", settings.letterSpacing, "%.2f".format(settings.letterSpacing), 0f..0.2f) { v -> update { it.copy(letterSpacing = v) } }
        }
        row {
            LabeledSliderRow("边距", settings.margin, "${settings.margin.toInt()}", 8f..48f, steps = 9) { v -> update { it.copy(margin = v) } }
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
private fun PagingPage(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    GroupedSection(footer = "滚动和翻页读起来没有明显差别，选顺手的就好。仿真翻页按下的高度决定掀起哪个角。") {
        PageMode.entries.forEach { mode ->
            row { CheckRow(mode.label, settings.pageMode == mode) { update { it.copy(pageMode = mode) } } }
        }
    }
    GroupedSection(footer = "书页厚度：翻页时屏幕两侧画出读过和剩下的页，像拿着纸书一样知道读到哪。") {
        row { ToggleRow("书页厚度", settings.pageEdges) { v -> update { it.copy(pageEdges = v) } } }
        row { ToggleRow("横屏时双页", settings.twoPage) { v -> update { it.copy(twoPage = v) } } }
    }
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
