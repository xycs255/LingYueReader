package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ReadingLookTest {
    @Test
    fun customColorsMakeAPaletteThatKnowsWhetherItIsDark() {
        val dark = customPalette(Color.Black, Color(0xFFD6D2C8))
        assertTrue(dark.isDark)
        val light = customPalette(Color(0xFFDDE7EE), Color(0xFF1F2D3D))
        assertFalse(light.isDark)
        // 次要文字在字色和底色之间。
        assertTrue(contrastRatio(light.background, light.secondary) < contrastRatio(light.background, light.text))
        val s = ReaderSettings(theme = ReaderTheme.CUSTOM, customBackground = 0xFF000000.toInt(), customText = 0xFFFFFFFF.toInt())
        assertEquals(Color.Black, s.paletteOf().background)
        assertEquals(ReaderTheme.PAPER.palette(), s.paletteOf(ReaderTheme.PAPER))
    }

    @Test
    fun marginAndLetterSpacingReachTheStyles() {
        val styles = ReaderStyles(ReaderSettings(margin = 40f, letterSpacing = 0.1f), ReaderTheme.PAPER.palette())
        assertEquals(40.dp, styles.horizontalPadding)
        assertEquals(0.1.em, styles.para.letterSpacing)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomBackgroundUiTest : PageTurnUiBase("SCROLL") {
    @Test
    fun pickingColorsRepaintsThePage() {
        open()
        waitForText("第1段")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        // 选了“自定义”，下面出现“自定义配色”，点进去选颜色。
        compose.onNodeWithText("自定义配色").performScrollTo().performClick()
        compose.onNodeWithContentDescription("底色 #000000").performScrollTo().performClick()
        compose.onNodeWithContentDescription("字色 #FFFFFF").performScrollTo().performClick()
        waitForText("适合长时间阅读")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // 配色渐变约 3 秒。
        compose.mainClock.advanceTimeBy(4_000)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val px = bmp.getPixel(4, bmp.height / 2)
        assertTrue("背景是 #%08X".format(px), android.graphics.Color.red(px) < 16 && android.graphics.Color.green(px) < 16 && android.graphics.Color.blue(px) < 16)
    }
}
