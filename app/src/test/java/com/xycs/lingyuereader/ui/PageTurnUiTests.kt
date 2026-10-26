package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 仿真、无动画翻页，横屏双页。 */
abstract class PageTurnUiBase(private val mode: String) {
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            val chapter = (1..40).joinToString("\n") { "　　第${it}段，城门口排着长队，挑担的、推车的、牵驴的，一直排到护城河边。" }
            File(dir, "s0.txt").writeText("第一章 开端\n$chapter\n第二章 进城\n　　第二章正文。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 1500)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", mode).putBoolean("breakReminder", false).also(::prefs).commit()
        }
    }
    protected val compose = createAndroidComposeRule<MainActivity>()

    /** 子类在打开前再放些设置。 */
    protected open fun prefs(editor: android.content.SharedPreferences.Editor) {}

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    protected fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    protected fun open() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
    }

    protected fun capture(name: String) {
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (workingDir.name == "app") workingDir else File(workingDir, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TwoPageUiTest : PageTurnUiBase("SLIDE") {
    @Test
    fun wideLandscapeShowsTwoPagesAndTurnsThemTogether() {
        open()
        // 状态行“1-2/N 页”：一次两页。
        waitForText("第 1-2/")
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText("第 3-4/")
    }

    @Test
    fun slideKeepsBothSpreadsAlignedHalfwayThrough() {
        open()
        waitForText("第 1-2/")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(150)
        capture("page-slide-landscape-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 3-4/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeCoverUiTest : PageTurnUiBase("COVER") {
    @Test fun coverMovesOneSpreadAndKeepsTheNextSpreadStationary() {
        open()
        waitForText("第 1-2/")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(150)
        val mid = compose.onRoot().captureToImage().asAndroidBitmap()
        val seam = compose.onNodeWithTag("reader-page-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left.toInt()
        fun brightness(x: Int) = mid.getPixel(x, 8).let {
            (android.graphics.Color.red(it) + android.graphics.Color.green(it) + android.graphics.Color.blue(it)) / 3
        }
        assertTrue("横屏覆盖翻到中途仍应有接缝阴影", brightness(seam + 45) - brightness(seam + 5) > 4)
        capture("page-cover-landscape-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 3-4/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeCurlUiTest : PageTurnUiBase("CURL") {
    @Test fun rightPageFoldsAroundSpineAndTurnsOneSpread() {
        open()
        waitForText("第 1-2/")
        compose.onRoot().captureToImage() // 用户看见首帧后才会点按；也让背面预绘完成。
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(220)
        val mid = compose.onRoot().captureToImage().asAndroidBitmap()
        var inkPixels = 0
        var samples = 0
        for (y in mid.height * 7 / 100 until mid.height * 85 / 100 step 3) {
            for (x in mid.width * 40 / 100 until mid.width * 62 / 100 step 3) {
                val pixel = mid.getPixel(x, y)
                if (maxOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel)) < 110) inkPixels++
                samples++
            }
        }
        assertTrue("翻起的背面应印有下一面左页的正文：$inkPixels/$samples", inkPixels > samples * 0.02f)
        capture("page-curl-landscape-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 3-4/")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(220)
        capture("page-curl-landscape-back-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 1-2/")
    }

    @Test fun returningThroughMultipleSpreadsKeepsPrintedBackside() {
        open()
        waitForText("第 1-2/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 3-4/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 5-6/")
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        waitForText("第 3-4/")
        compose.onRoot().captureToImage()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(220)
        val mid = compose.onRoot().captureToImage().asAndroidBitmap()
        var ink = 0
        var samples = 0
        for (y in mid.height * 7 / 100 until mid.height * 85 / 100 step 3) {
            for (x in mid.width * 40 / 100 until mid.width * 62 / 100 step 3) {
                val color = mid.getPixel(x, y)
                if (maxOf(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)) < 110) ink++
                samples++
            }
        }
        assertTrue("连续返回时纸背仍应印有下一面正文：$ink/$samples", ink > samples * 0.02f)
        capture("page-curl-landscape-back-twice-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 1-2/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CurlUiTest : PageTurnUiBase("CURL") {
    @Test
    fun curlTurnsOnePageAtATime() {
        open()
        waitForText("第 1/")
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText("第 2/")
        // 点右边：从右下角翻。
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 3/")
        // 按住右下角往左上拖：掀角跟着手指走，照样翻过去。
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.95f, height * 0.9f), Offset(width * 0.1f, height * 0.75f), 300) }
        waitForText("第 4/")
        // 从中间往右拖回上一页。
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.1f, height * 0.5f), Offset(width * 0.95f, height * 0.5f), 300) }
        waitForText("第 3/")
    }

    @Test
    fun portraitFoldKeepsItsShadowHalfwayThrough() {
        open()
        waitForText("第 1/")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(220)
        capture("page-curl-portrait-mid")
        compose.mainClock.autoAdvance = true
        waitForText("第 2/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoAnimationUiTest : PageTurnUiBase("NONE") {
    @Test
    fun tapAndSwipeTurnPagesWithoutMovingThem() {
        open()
        waitForText("第 1/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 2/")
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText("第 3/")
        compose.onRoot().performTouchInput { swipeRight() }
        waitForText("第 2/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverTurnUiTest : PageTurnUiBase("COVER") {
    override fun prefs(editor: android.content.SharedPreferences.Editor) {
        editor.putBoolean("pageEdges", false).putBoolean("ambientLight", false).putBoolean("eveningWarmth", false)
    }

    @Test
    fun oldPageSlidesAwayWhileNextPageStaysPutAndBackTurnKeepsCurrentPageStill() {
        open()
        waitForText("第 1/")
        fun image() = compose.onRoot().captureToImage().asAndroidBitmap()
        fun changedFraction(a: Bitmap, b: Bitmap, startX: Int, endX: Int): Float {
            var changed = 0
            var sampled = 0
            for (y in 80 until a.height - 100 step 3) for (x in startX until endX step 3) {
                sampled++
                if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
            }
            return changed.toFloat() / sampled
        }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(150)
        val forwardMid = image()
        fun brightness(pixel: Int) = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
        val forwardSeam = compose.onNodeWithTag("reader-page-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left.toInt()
        val shadowNear = brightness(forwardMid.getPixel(forwardSeam + 5, 8))
        val paperFar = brightness(forwardMid.getPixel(forwardSeam + 45, 8))
        assertTrue("翻到中途的接缝阴影应连续可见：$shadowNear / $paperFar", paperFar - shadowNear > 4)
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (workingDir.name == "app") workingDir else File(workingDir, "app")
        val output = File(appDir, "build/outputs/ui-check/page-cover-mid.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { check(forwardMid.compress(Bitmap.CompressFormat.PNG, 100, it)) }

        compose.mainClock.autoAdvance = true
        waitForText("第 2/")
        val settled = image()
        val forwardDifference = changedFraction(forwardMid, settled, settled.width / 3, settled.width - 20)
        assertTrue("覆盖翻页时下页应静止，正文区域差异 $forwardDifference", forwardDifference < 0.01f)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(150)
        val backwardMid = image()
        val backwardDifference = changedFraction(backwardMid, settled, settled.width * 95 / 100, settled.width - 5)
        assertTrue("返回时当前页应静止，右侧正文差异 $backwardDifference", backwardDifference < 0.01f)
        compose.mainClock.autoAdvance = true
        waitForText("第 1/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalTurnUiTest : PageTurnUiBase("VERTICAL") {
    @After fun resetMotion() { Effects.systemNoMotion = false }

    @Test
    fun reducedMotionKeepsVerticalPageNavigationInReadingOrder() {
        Effects.systemNoMotion = true
        open()
        waitForText("第 1/")
        // 竖排由右向左读；减少动态效果后，左右手势仍然要翻到正确的页。
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        waitForText("第 2/")
        compose.onRoot().performTouchInput { swipeRight() }
        waitForText("第 3/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 2/")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReducedMotionCurlUiTest : PageTurnUiBase("CURL") {
    @After fun resetMotion() { Effects.systemNoMotion = false }

    @Test
    fun tapSkipsTheCurlAnimationAndSwipeStillChangesOnePage() {
        Effects.systemNoMotion = true
        open()
        waitForText("第 1/")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("减少动态效果时点按应立即到下一页", compose.onAllNodesWithText("第 2/", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.mainClock.autoAdvance = true
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText("第 3/")
    }
}
