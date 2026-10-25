package com.xycs.lingyuereader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PageTransition
import com.xycs.lingyuereader.data.PaletteStyle
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 主题设置，照苹果“显示与亮度”的样子：顶上三部小手机选外观（跟随系统 / 浅色 / 深色），
 * 下面是分组列表——强调色、玻璃效果、墨水屏、动画与缩放，解释写在每组下面。
 */
@Composable
fun ThemeScreen(vm: AppViewModel) {
    val a by vm.appearance.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // 改配色的设置从点的地方扩散开；其余直接改。
    val reveal = LocalThemeReveal.current
    val update = vm::updateAppearance
    val recolor: ((Appearance) -> Appearance) -> Unit = { t -> reveal?.change { update(t) } ?: update(t) }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "主题设置",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(header = "外观") {
                        row { AppearancePicker(a) { m -> if (m != a.mode) recolor { it.copy(mode = m) } } }
                    }
                    GroupedSection(header = "强调色") {
                        row { AccentDots(a.accent) { color -> if (color != a.accent) recolor { it.copy(accent = color) } } }
                        row { ToggleRow("Monet 取色", a.monet, subtitle = "从壁纸取色（Android 12 起）；选了强调色时按它生成整套配色") { v -> recolor { it.copy(monet = v) } } }
                        if (a.dynamicColors) {
                            row {
                                PickerRow("配色风格", PaletteStyle.entries.map { it to it.label }, a.paletteStyle) { p ->
                                    recolor { it.copy(paletteStyle = p) }
                                }
                            }
                        }
                    }
                    // 每个开关的作用写在它自己下面，不堆在组末一大段。
                    GroupedSection(header = "效果") {
                        row { ToggleRow("模糊", a.blur, enabled = !settings.eInk, subtitle = "顶栏、底栏和面板透出后面的内容") { v -> update { it.copy(blur = v) } } }
                        row { ToggleRow("液态玻璃", a.liquidGlass, enabled = !settings.eInk, subtitle = "玻璃边缘的折射和高光") { v -> update { it.copy(liquidGlass = v) } } }
                        row { ToggleRow("悬浮底栏", a.floatingBar, subtitle = "底栏浮在内容上，按住可以左右拖动") { v -> update { it.copy(floatingBar = v) } } }
                    }
                    GroupedSection {
                        row {
                            ToggleRow("墨水屏 / 省电模式", settings.eInk, subtitle = "关掉玻璃、动画和环境光变色，书页纯黑白") { v -> vm.updateSettings { it.copy(eInk = v) } }
                        }
                    }
                    val steps = Appearance.SCALES
                    val index = steps.indexOfFirst { kotlin.math.abs(it - a.scale) < 0.001f }.takeIf { it >= 0 } ?: steps.indexOf(1f)
                    GroupedSection(
                        header = "动画与大小",
                        footer = "书页的配色、字体、排版在阅读页的“外观”里调。",
                    ) {
                        row {
                            PickerRow("页面切换动画", PageTransition.entries.map { it to it.label }, a.transition, enabled = !settings.eInk) { t ->
                                update { it.copy(transition = t) }
                            }
                        }
                        row {
                            LabeledSliderRow(
                                title = "界面缩放",
                                value = index.toFloat(),
                                valueText = "${(steps[index] * 100).toInt()}%",
                                valueRange = 0f..steps.lastIndex.toFloat(),
                                steps = steps.size - 2,
                            ) { v -> update { it.copy(scale = steps[kotlin.math.round(v).toInt().coerceIn(steps.indices)]) } }
                        }
                    }
                }
            }
        }
    }
}

/** 三部小手机：跟随系统（一半浅一半深）、浅色、深色，下面是名字和圆形选中标记。 */
@Composable
private fun AppearancePicker(a: Appearance, onPick: (ThemeMode) -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ThemeMode.entries.forEach { m ->
            val on = m == a.mode
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = on, role = Role.RadioButton) { onPick(m) }
                    .semantics { contentDescription = "${m.label}外观" }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MiniPhone(m, a)
                Text(m.label, color = c.label, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(if (on) Modifier.background(c.tint) else Modifier.border(1.5.dp, c.tertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(LingYueIcons.Check, contentDescription = null, tint = c.onTint, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** 一部小手机：当前强调色的书架和底栏；跟随系统时左半浅、右半深。 */
@Composable
private fun MiniPhone(mode: ThemeMode, a: Appearance) {
    val c = LocalGroupedColors.current
    @Composable
    fun Screen(dark: Boolean, modifier: Modifier) {
        val bg = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
        val ink = if (dark) Color.White else Color.Black
        Box(modifier.background(bg)) {
            Column(Modifier.padding(start = 7.dp, end = 7.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(22.dp).height(5.dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.9f, 0.55f, 0.3f).forEach { alpha ->
                        Box(Modifier.size(width = 14.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(c.tint.copy(alpha = alpha)))
                    }
                }
                listOf(1f, 0.7f, 0.85f).forEach { w ->
                    Box(Modifier.fillMaxWidth(w).height(3.dp).clip(CircleShape).background(ink.copy(alpha = 0.18f)))
                }
            }
            val bar = if (dark) Color(0xFF2C2C2E) else Color.White
            if (a.floatingBar) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(width = 40.dp, height = 10.dp).clip(CircleShape).background(bar))
            } else {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(11.dp).background(bar))
            }
        }
    }
    Box(
        Modifier
            .size(width = 66.dp, height = 128.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.separator, RoundedCornerShape(12.dp)),
    ) {
        when (mode) {
            ThemeMode.LIGHT -> Screen(false, Modifier.fillMaxSize())
            ThemeMode.DARK -> Screen(true, Modifier.fillMaxSize())
            ThemeMode.SYSTEM -> Row(Modifier.fillMaxSize()) {
                Screen(false, Modifier.weight(1f).fillMaxHeight())
                Screen(true, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** 强调色：默认（miuix 蓝），加几种中国传统色，排成一行，放不下就左右滑。选中的那个打勾、外圈弹出来。圆点看着小，能点的范围 48dp。 */
@Composable
private fun AccentDots(accent: Int, onPick: (Int) -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        (listOf(0) + Appearance.ACCENTS).forEach { color ->
            val on = color == accent
            val dot = if (color == 0) Color(0xFF3482FF) else Color(color)
            val ring by animateDpAsState(if (on) 2.dp else 0.dp, Motion.pop(), label = "ring")
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onPick(color) }
                    .semantics {
                        contentDescription = if (color == 0) "默认强调色" else "强调色 #%06X".format(color and 0xFFFFFF)
                        selected = on
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(ring.coerceAtLeast(0.dp), dot.copy(alpha = if (on) 1f else 0f), CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(dot),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = on,
                        enter = Effects.enter(androidx.compose.animation.scaleIn(Motion.pop(), initialScale = 0.5f) + androidx.compose.animation.fadeIn(Motion.standard(Motion.SHORT))),
                        exit = Effects.exit(androidx.compose.animation.fadeOut(Motion.exit())),
                    ) {
                        // 勾画在这颗圆点自己的颜色上（不一定是当前强调色，开着 Monet 时就不是）：和 onTint 同一个挑法，
                        // 白勾够 3:1（图形的对比度要求）就用白，太浅的圆点上换深色勾。
                        val check = if (contrastRatio(Color.White, dot) >= 3f) Color.White else contentColorOn(dot)
                        Icon(LingYueIcons.Check, contentDescription = null, tint = check, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
