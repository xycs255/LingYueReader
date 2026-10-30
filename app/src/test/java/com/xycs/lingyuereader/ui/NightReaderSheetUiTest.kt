package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PageTransition
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 应用本身浅色、书页夜间时，阅读面板仍需保持深底与可读文字。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NightReaderSheetUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n　　正文一段。\n第二章 路上\n　　正文二段。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 10)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("theme", ReaderTheme.NIGHT.name)
                .putBoolean("ambientLight", false)
                .putBoolean("eveningWarmth", false)
                .putBoolean("autoNight", false)
                .commit()
            // 使用不透明面板让像素断言不受设备模拟器的模糊实现影响。
            Prefs(context).saveAppearance(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false, transition = PageTransition.NONE))
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openReaderMenu() {
        assertEquals(ThemeMode.LIGHT, Prefs(context).appearance().mode)
        assertEquals(ReaderTheme.NIGHT, Prefs(context).settings().theme)
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文一段")
        compose.onRoot().performTouchInput { click(center) }
        waitForText("更多")
    }

    private fun captureAndCheck(name: String, title: Rect, content: Rect) {
        compose.mainClock.advanceTimeBy(1_000)
        val image = compose.onRoot().captureToImage()
        saveScreenshot(name, image)
        val pixels = image.toPixelMap()
        // 在标题那一行、面板最左边的空白处取底色（再往右是目录的小书封、外观的返回钮）。
        val background = pixels[(pixels.width * 0.02f).toInt(), title.center.y.toInt()].luminance()
        assertTrue("$name 面板底色亮度 $background", background < 0.2f)

        fun checkText(label: String, bounds: Rect) {
            val left = bounds.left.toInt().coerceIn(0, pixels.width - 1)
            val right = bounds.right.toInt().coerceIn(left + 1, pixels.width)
            val top = bounds.top.toInt().coerceIn(0, pixels.height - 1)
            val bottom = bounds.bottom.toInt().coerceIn(top + 1, pixels.height)
            val lightness = ArrayList<Float>((right - left) * (bottom - top))
            for (y in top until bottom) for (x in left until right) lightness += pixels[x, y].luminance()
            // 标题节点横跨整张面板，背景像素远多于字形；检查确有一组高对比字形像素。
            val readablePixels = lightness.count { (it + 0.05f) / (background + 0.05f) >= 4.5f }
            assertTrue("$name $label 缺少可读字形像素（$readablePixels）", readablePixels >= 8)
        }
        checkText("标题", title)
        checkText("内容", content)
    }

    private fun saveScreenshot(name: String, image: ImageBitmap) {
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (workingDir.name == "app") workingDir else File(workingDir, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { stream ->
            check(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun tapOutsideAndWaitForClose(text: String) {
        compose.onNodeWithContentDescription("关闭目录侧栏").performTouchInput { click(Offset(width * 0.96f, height * 0.5f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun catalogSheetIsDarkAndReadableAndClosesOnOutsideTap() {
        openReaderMenu()
        compose.onNodeWithText("目录").performClick()
        waitForText("目录 2")
        val title = compose.onNodeWithText("目录 2").fetchSemanticsNode().boundsInRoot
        val chapter = compose.onNodeWithText("第二章 路上").fetchSemanticsNode().boundsInRoot
        captureAndCheck("night-catalog", title, chapter)
        tapOutsideAndWaitForClose("目录 2")
    }

    @Test
    fun typographySheetIsDarkAndReadableAndClosesOnBack() {
        openReaderMenu()
        compose.onNodeWithText("外观").performClick()
        waitForText("宋体")
        val label = compose.onNodeWithText("宋体").fetchSemanticsNode().boundsInRoot
        // 面板标题“外观”在“宋体”上方（菜单里也有一个“外观”按钮，在屏幕最底下）。
        val title = compose.onAllNodesWithText("外观").fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom <= label.top }.maxBy { it.boundsInRoot.top }.boundsInRoot
        captureAndCheck("night-typography", title, label)
        // 排版面板几乎铺满全屏，没有可点的遮罩区域；系统返回键应关闭它。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("宋体").fetchSemanticsNodes().isEmpty() }
    }
}
