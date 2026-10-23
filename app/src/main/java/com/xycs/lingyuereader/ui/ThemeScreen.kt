package com.xycs.lingyuereader.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PageTransition
import com.xycs.lingyuereader.data.PaletteStyle
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 主题设置：深浅色、Monet 取色与强调色、玻璃效果、导航栏样式、页面动画、界面缩放。顶上是随设置变化的预览。 */
@Composable
fun ThemeScreen(vm: AppViewModel) {
    val a by vm.appearance.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // 改配色的设置从点的地方扩散开；其余直接改。
    val reveal = LocalThemeReveal.current
    val update = vm::updateAppearance
    val recolor: ((Appearance) -> Appearance) -> Unit = { t -> reveal?.change { update(t) } ?: update(t) }

    GlassScaffold(
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
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    ThemePreview(a)
                }
                ModeButtons(a.mode) { m -> if (m != a.mode) recolor { it.copy(mode = m) } }

                GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(
                        title = "Monet 取色",
                        summary = "从壁纸取色（Android 12 起）；选了强调色时按强调色生成整套配色",
                        checked = a.monet,
                        onCheckedChange = { v -> recolor { it.copy(monet = v) } },
                    )
                    AccentRow(a.accent) { c -> if (c != a.accent) recolor { it.copy(accent = c) } }
                    if (a.dynamicColors) {
                        LingYueDropdownPreference(
                            items = PaletteStyle.entries.map { it.label },
                            selectedIndex = a.paletteStyle.ordinal,
                            title = "配色风格",
                            onSelectedIndexChange = { i -> recolor { it.copy(paletteStyle = PaletteStyle.entries[i]) } },
                        )
                    }
                }

                SmallTitle("效果")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(
                        title = "模糊",
                        summary = "顶栏、导航栏、弹出面板透出后面的内容并模糊",
                        checked = a.blur,
                        enabled = !settings.eInk,
                        onCheckedChange = { v -> update { it.copy(blur = v) } },
                    )
                    SwitchPreference(
                        title = "液态玻璃",
                        summary = "玻璃边缘折射和高光描边；模糊和液态玻璃都关时用不透明底色",
                        checked = a.liquidGlass,
                        enabled = !settings.eInk,
                        onCheckedChange = { v -> update { it.copy(liquidGlass = v) } },
                    )
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "底部导航栏悬浮在内容上，可按住左右拖动；关掉则贴在屏幕底部",
                        checked = a.floatingBar,
                        onCheckedChange = { v -> update { it.copy(floatingBar = v) } },
                    )
                    SwitchPreference(
                        title = "墨水屏 / 省电模式",
                        summary = "关掉玻璃、动画和环境光变色，书页纯黑白，翻页不带动画；墨水屏阅读器和旧手机用",
                        checked = settings.eInk,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(eInk = v) } },
                    )
                }

                SmallTitle("动画与缩放")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    LingYueDropdownPreference(
                        items = PageTransition.entries.map { it.label },
                        selectedIndex = a.transition.ordinal,
                        title = "页面切换动画",
                        enabled = !settings.eInk,
                        onSelectedIndexChange = { i -> update { it.copy(transition = PageTransition.entries[i]) } },
                    )
                    ScaleRow(a.scale) { s -> update { it.copy(scale = s) } }
                }
                Text(
                    "书页的配色、字体、排版在阅读页的“设置”里调。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 一部小手机，画出当前配色下的书架和导航栏。 */
@Composable
private fun ThemePreview(a: Appearance) {
    val c = MiuixTheme.colorScheme
    Box(
        Modifier
            .width(150.dp)
            .height(300.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .border(1.5.dp, c.onSurface.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
            .semantics { contentDescription = "主题预览" },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp)) {
            Text("灵阅", color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.9f, 0.6f, 0.35f).forEach { alpha ->
                    Box(Modifier.weight(1f).aspectRatio(0.7f).clip(RoundedCornerShape(5.dp)).background(c.primary.copy(alpha = alpha)))
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(1f, 0.7f, 0.85f, 0.5f).forEach { w ->
                    Box(Modifier.fillMaxWidth(w).height(6.dp).clip(CircleShape).background(c.onSurface.copy(alpha = 0.18f)))
                }
            }
        }
        val dots: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { i ->
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 0) c.primary else c.onSurface.copy(alpha = 0.35f)))
                }
            }
        }
        if (a.floatingBar) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(CircleShape)
                    .background(c.surfaceContainer.copy(alpha = if (a.blur || a.liquidGlass) 0.75f else 1f))
                    .border(if (a.liquidGlass) 1.dp else 0.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { dots() }
        } else {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.surfaceContainer).padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { dots() }
        }
    }
}

/** 跟随系统 / 浅色 / 深色。 */
@Composable
private fun ModeButtons(mode: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val c = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { m ->
            val on = m == mode
            val shape = RoundedCornerShape(14.dp)
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .pressScale(press)
                    .clip(shape)
                    .then(if (on) Modifier.background(c.surfaceContainer) else Modifier.border(1.dp, c.onSurface.copy(alpha = 0.25f), shape))
                    .clickable(press, LocalIndication.current) { onPick(m) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, color = c.onSurface, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** 强调色：默认，加几种中国传统色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentRow(accent: Int, onPick: (Int) -> Unit) {
    val c = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("强调色", color = c.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val options = listOf(0) + Appearance.ACCENTS
            options.forEach { color ->
                val on = color == accent
                val press = remember { MutableInteractionSource() }
                // 选中的那个外圈弹出来。
                val ring by animateDpAsState(if (on) 3.dp else 0.dp, Motion.pop(), label = "ring")
                Box(
                    Modifier
                        .size(34.dp)
                        .pressScale(press, 0.88f)
                        .clip(CircleShape)
                        .background(if (on || ring > 0.dp) c.onSurface else Color.Transparent)
                        .padding(ring.coerceAtLeast(0.dp))
                        .clip(CircleShape)
                        .background(if (color == 0) c.surfaceContainer else Color(color))
                        .clickable(press, LocalIndication.current) { onPick(color) }
                        .semantics { contentDescription = if (color == 0) "默认强调色" else "强调色 #%06X".format(color and 0xFFFFFF) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == 0) Text("默认", color = c.onSurface, fontSize = 10.sp)
                }
            }
        }
    }
}

/** 界面缩放：几档比例。 */
@Composable
private fun ScaleRow(scale: Float, onPick: (Float) -> Unit) {
    val c = MiuixTheme.colorScheme
    val steps = Appearance.SCALES
    val index = steps.indexOfFirst { kotlin.math.abs(it - scale) < 0.001f }.takeIf { it >= 0 } ?: steps.indexOf(1f)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("界面缩放", color = c.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("调整整个界面的显示比例", color = c.onSurfaceVariantSummary, fontSize = 14.sp)
            }
            Text("${(steps[index] * 100).toInt()}%", color = c.onSurfaceVariantActions, fontSize = 15.sp)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { v -> onPick(steps[kotlin.math.round(v).toInt().coerceIn(steps.indices)]) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
            modifier = Modifier.padding(top = 10.dp).semantics { contentDescription = "界面缩放" },
        )
    }
}
