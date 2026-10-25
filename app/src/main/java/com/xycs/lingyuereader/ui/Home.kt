package com.xycs.lingyuereader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.HomeTab
import com.xycs.lingyuereader.ui.glass.GlassPopupHost
import com.xycs.lingyuereader.ui.glass.LiquidGlassNavigationBar
import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.recordGlass
import com.xycs.lingyuereader.ui.glass.rememberGlassBackdrop
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.xycs.lingyuereader.ui.glass.glass
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页各标签页底部要给悬浮导航栏让出的高度；不在首页时为 0。 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

private val HomeTab.icon
    get() = when (this) {
        HomeTab.Shelf -> LingYueIcons.Notes
        HomeTab.Write -> LingYueIcons.Write
        HomeTab.Vocabulary -> LingYueIcons.Translate
        HomeTab.Stats -> LingYueIcons.Months
        HomeTab.Me -> LingYueIcons.ContactsCircle
    }

/** 导航栏离屏幕底边的距离，与 [LiquidGlassNavigationBar] 里的算法一致。 */
@Composable
private fun tabBarInset(floating: Boolean): Dp {
    val nav = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    // 贴底的导航栏：栏高加系统导航条。
    if (!floating) return 64.dp + nav
    return 64.dp + if (nav != 0.dp) 8.dp + nav else 36.dp
}

/** 首页：书架、写作、生词本、统计、我的五页，底部是可以按住左右拖动切换的液态玻璃导航栏。 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val tab by vm.homeTab.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    // 不在书架页时，返回键先回书架。
    BackHandler(enabled = tab != HomeTab.Shelf) { vm.selectTab(HomeTab.Shelf) }

    val pager = rememberPagerState(initialPage = tab.ordinal) { HomeTab.entries.size }
    LaunchedEffect(tab) {
        if (pager.currentPage != tab.ordinal) {
            // 跟着导航栏指示器（手指拖着走）松手后归位：用不回弹的 settle 弹簧，和指示器一起停稳。
            if (Effects.noMotion) pager.scrollToPage(tab.ordinal) else pager.animateScrollToPage(tab.ordinal, animationSpec = Motion.settle())
        }
    }

    // 五页一起记录成玻璃的取样源：导航栏，以及各页弹出的对话框、面板、菜单都从这里取样。
    val backdrop = rememberGlassBackdrop(MiuixTheme.colorScheme.surface)

    // 外层 Scaffold：各页里弹出的对话框、菜单都挂到最外层 Scaffold 上，画在导航栏之上，不会被它挡住按钮。
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
    Scaffold(
        bottomBar = {
            if (appearance.floatingBar) {
                LiquidGlassNavigationBar(
                    items = HomeTab.entries.map { NavigationItem(it.label, it.icon) },
                    selectedIndex = tab.ordinal,
                    onItemClick = { vm.selectTab(HomeTab.entries[it]) },
                    backdrop = backdrop,
                    isBlurActive = backdrop != null,
                )
            } else {
                // 贴底的导航栏：毛玻璃底，从四页的记录里取样。
                NavigationBar(
                    Modifier.glass(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.6f),
                        blur = 24.dp,
                    ),
                    Color.Transparent,
                ) {
                    HomeTab.entries.forEach { t ->
                        NavigationBarItem(t == tab, { vm.selectTab(t) }, t.icon, t.label)
                    }
                }
            }
        },
        // 各页自己处理状态栏等边距；导航栏让出的高度由 LocalTabBarInset 告诉各页。
        contentWindowInsets = WindowInsets(0),
        // 各页弹出的菜单、对话框画在这里，拿不到各页里提供的配色：宿主这里按主题补一套设置页配色。
        popupHost = { ProvideGroupedColors { GlassPopupHost() } },
    ) { _ ->
        CompositionLocalProvider(LocalTabBarInset provides tabBarInset(appearance.floatingBar)) {
            HorizontalPager(
                state = pager,
                // 左右滑动留给书架分组标签和统计图；换页只靠导航栏。
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().recordGlass(backdrop),
            ) { page ->
                when (HomeTab.entries[page]) {
                    HomeTab.Shelf -> ShelfScreen(vm)
                    HomeTab.Write -> WritingHomeScreen(vm)
                    HomeTab.Vocabulary -> VocabularyScreen(vm)
                    HomeTab.Stats -> StatsScreen(vm)
                    HomeTab.Me -> MeScreen(vm)
                }
            }
        }
    }
}
}
