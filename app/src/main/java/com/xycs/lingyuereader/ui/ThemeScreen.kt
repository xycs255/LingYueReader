package com.xycs.lingyuereader.ui

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
                    GroupedSection(
                        header = "强调色",
                        footer = "Monet 从壁纸取色（Android 12 起）；选了强调色时按它生成整套配色。",
                    ) {
                        row { AccentDots(a.accent) { color -> if (color != a.accent) recolor { it.copy(accent = color) } } }
                        row { ToggleRow("Monet 取色", a.monet) { v -> recolor { it.copy(monet = v) } } }
                        if (a.dynamicColors) {
                            row {
                                PickerRow("配色风格", PaletteStyle.entries.map { it to it.label }, a.paletteStyle) { p ->
                                    recolor { it.copy(paletteStyle = p) }
                                }
                            }
                        }
                    }
                    GroupedSection(
                        header = "效果",
                        footer = "模糊：顶栏、导航栏和弹出面板透出后面的内容。液态玻璃：边缘折射和高光。两样都关时用不透明底色。悬浮底栏可以按住左右拖动。",
                    ) {
                        row { ToggleRow("模糊", a.blur, enabled = !settings.eInk) { v -> update { it.copy(blur = v) } } }
                        row { ToggleRow("液态玻璃", a.liquidGlass, enabled = !settings.eInk) { v -> update { it.copy(liquidGlass = v) } } }
                        row { ToggleRow("悬浮底栏", a.floatingBar) { v -> update { it.copy(floatingBar = v) } } }
                    }
                    GroupedSection(footer = "关掉玻璃、动画和环境光变色，书页纯黑白，翻页不带动画。墨水屏阅读器和旧手机用。") {
                        row { ToggleRow("墨水屏 / 省电模式", settings.eInk) { v -> vm.updateSettings { it.copy(eInk = v) } } }
                    }
                    val steps = Appearance.SCALES
                    val index = steps.indexOfFirst { kotlin.math.abs(it - a.scale) < 0.001f }.takeIf { it >= 0 } ?: steps.indexOf(1f)
                    GroupedSection(
                        header = "动画与缩放",
                        footer = "界面缩放调整整个界面的显示比例。书页的配色、字体、排版在阅读页的“设置”里调。",
                    ) {
                        row {
                            PickerRow("页面切换动画", PageTransition.entries.map { it to it.label }, a.transition, enabled = !settings.eInk) { t ->
                                update { it.copy(transition = t) }
                            }
                        }
                        row {
                            SliderRow(
                                value = index.toFloat(),
                                onValueChange = { v -> update { it.copy(scale = steps[kotlin.math.round(v).toInt().coerceIn(steps.indices)]) } },
                                valueRange = 0f..steps.lastIndex.toFloat(),
                                steps = steps.size - 2,
                                label = "界面缩放",
                                valueText = "${(steps[index] * 100).toInt()}%",
                                start = { Text("A", color = c.secondary, fontSize = 13.sp) },
                                end = { Text("A", color = c.secondary, fontSize = 21.sp) },
                            )
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
                    if (on) Icon(LingYueIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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

/** 强调色：默认，加几种中国传统色。圆点看着小，能点的范围 48dp。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentDots(accent: Int, onPick: (Int) -> Unit) {
    val c = LocalGroupedColors.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        (listOf(0) + Appearance.ACCENTS).forEach { color ->
            val on = color == accent
            // 选中的那个外圈弹出来。
            val ring by animateDpAsState(if (on) 2.5.dp else 0.dp, Motion.pop(), label = "ring")
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
                        .size(34.dp)
                        .border(ring.coerceAtLeast(0.dp), c.label.copy(alpha = if (on) 0.85f else 0f), CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (color == 0) Color(0xFF3482FF) else Color(color)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == 0) Text("默认", color = Color.White, fontSize = 8.sp)
                }
            }
        }
    }
}
