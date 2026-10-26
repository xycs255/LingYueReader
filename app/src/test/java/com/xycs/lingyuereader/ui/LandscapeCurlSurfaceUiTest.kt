package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 双页仿真的背面应来自下一面的左页，而不是当前右页的镜像。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeCurlSurfaceUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(next: ImageBitmap?): Bitmap {
        val height = with(compose.density) { 400.dp.roundToPx() }
        val touch = CurlTouch().apply { pressY = height / 2f; fingerY = height / 2f }
        compose.setContent {
            Box(Modifier.size(800.dp, 400.dp).testTag("spread")) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Yellow, size = Size(size.width / 2, size.height))
                    drawRect(Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                }
                Canvas(Modifier.fillMaxSize().pageCurl({ 0.7f }, touch, Color.White, spine = 0.5f, backside = { next })) {
                    drawRect(Color.Red, size = Size(size.width / 2, size.height))
                    drawRect(Color.Green, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                }
            }
        }
        return compose.onNodeWithTag("spread").captureToImage().asAndroidBitmap()
    }

    @Test fun suppliedNextSpreadIsPrintedOnTheTurningPageBack() {
        val width = with(compose.density) { 800.dp.roundToPx() }
        val height = with(compose.density) { 400.dp.roundToPx() }
        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            AndroidCanvas(this).apply {
                drawColor(android.graphics.Color.BLUE)
                drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = android.graphics.Color.YELLOW })
            }
        }.asImageBitmap()
        val image = render(next)
        val y = image.height / 2
        fun rgb(x: Float) = image.getPixel((image.width * x).toInt(), y)
        fun red(c: Int) = android.graphics.Color.red(c)
        fun green(c: Int) = android.graphics.Color.green(c)
        fun blue(c: Int) = android.graphics.Color.blue(c)
        assertTrue("左页仍显示本面", red(rgb(0.05f)) > 200 && green(rgb(0.05f)) < 80)
        assertTrue("翻起的背面应显示下一面的左页", red(rgb(0.4f)) > 170 && green(rgb(0.4f)) > 170 && blue(rgb(0.4f)) < 120)
        assertTrue("右侧露出下一面的右页", blue(rgb(0.95f)) > 180 && red(rgb(0.95f)) < 100)
    }

    @Test fun missingBacksideIsPlainPaperInsteadOfMirroredCurrentText() {
        val image = render(null)
        val color = image.getPixel((image.width * 0.4f).toInt(), image.height / 2)
        val channels = listOf(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        assertTrue("缺少背面图时应是素纸：$channels", channels.max() - channels.min() < 15)
    }
}
