package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PageTransition
import com.xycs.lingyuereader.data.PaletteStyle
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.ui.glass.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openThemePage() {
        waitForText("我的")
        compose.onNodeWithText("我的").performClick()
        waitForText("主题设置")
        compose.onNodeWithText("主题设置").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("深色外观")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun appearanceSurvivesSaving() {
        val prefs = Prefs(context)
        val a = Appearance(ThemeMode.DARK, monet = true, accent = Appearance.ACCENTS[2], paletteStyle = PaletteStyle.VIBRANT,
            blur = false, liquidGlass = false, floatingBar = false, transition = PageTransition.SLIDE, scale = 1.1f)
        prefs.saveAppearance(a)
        assertEquals(a, prefs.appearance())
        // 设置随备份走（同一个设置文件）。
        assertTrue(prefs.exportJson().has("ui.accent"))
    }

    @Test
    fun darkModeAccentAndGlassSwitchesApply() {
        openThemePage()
        val bars = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        assertTrue("浅色页面应使用深色状态栏图标", bars.isAppearanceLightStatusBars)
        compose.onNodeWithText("深色").performClick()
        compose.waitForIdle()
        assertFalse("手动深色应使用浅色状态栏图标", bars.isAppearanceLightStatusBars)
        compose.onNodeWithContentDescription("强调色 #D49A57").performScrollTo().performClick()
        waitForText("配色风格")
        compose.onNodeWithText("悬浮底栏").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // 外观选中了“深色”。
        assertTrue(compose.onNodeWithContentDescription("深色外观").performScrollTo().fetchSemanticsNode().config[SemanticsProperties.Selected])
        // 下面的卡片也是深色底：取字左边一点的卡片底色。
        val title = compose.onNodeWithText("墨水屏 / 省电模式").performScrollTo().fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(500)
        val root = compose.onRoot().captureToImage().toPixelMap()
        val card = root[(title.left - 6).toInt(), title.center.y.toInt()]
        assertTrue("卡片底色 $card", card.luminance() < 0.2f)

        compose.onNodeWithText("模糊").performScrollTo().performClick()
        compose.waitForIdle()
        val a = Prefs(context).appearance()
        assertEquals(ThemeMode.DARK, a.mode)
        assertEquals(Appearance.ACCENTS[1], a.accent)
        assertFalse(a.blur)
        assertFalse(a.floatingBar)
        assertFalse(GlassStyle.blur)
    }

    @Test
    fun pageTransitionMenuSavesSelection() {
        openThemePage()
        compose.onNodeWithText("页面切换动画").performScrollTo().performClick()
        waitForText("淡入淡出")
        compose.onNodeWithText("淡入淡出").performClick()
        compose.waitForIdle()
        assertEquals(PageTransition.FADE, Prefs(context).appearance().transition)
    }

    @Test
    fun accentChoicesHaveAccessibleTargetsAndSelectedState() {
        openThemePage()
        val default = compose.onNodeWithContentDescription("默认强调色").performScrollTo().fetchSemanticsNode()
        val minSizePx = context.resources.displayMetrics.density * 48f - 1f
        assertTrue(default.boundsInRoot.width >= minSizePx)
        assertTrue(default.boundsInRoot.height >= minSizePx)
        assertTrue(default.config[SemanticsProperties.Selected])

        compose.onNodeWithContentDescription("强调色 #D49A57").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        val amber = compose.onNodeWithContentDescription("强调色 #D49A57").performScrollTo().fetchSemanticsNode()
        assertTrue(amber.boundsInRoot.width >= minSizePx)
        assertTrue(amber.boundsInRoot.height >= minSizePx)
        assertTrue(amber.config[SemanticsProperties.Selected])
        assertFalse(compose.onNodeWithContentDescription("默认强调色").performScrollTo().fetchSemanticsNode().config[SemanticsProperties.Selected])
    }
}
