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
    @Test
    fun nextPageEntersOverTheCurrentPageAndBackTurnRevealsThePreviousPage() {
        open()
        waitForText("第 1/")
        fun left(page: Int) = compose.onNodeWithTag("reader-page-$page", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        val firstLeft = left(0)
        val viewportWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(150)
        val firstMoving = left(0)
        val nextMoving = left(1)
        assertTrue("旧页应留在原处：$firstLeft -> $firstMoving", kotlin.math.abs(firstMoving - firstLeft) < 3f)
        assertTrue("新页应从右侧盖上：$nextMoving", nextMoving > firstLeft + 20f && nextMoving < viewportWidth - 20f)
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (workingDir.name == "app") workingDir else File(workingDir, "app")
        val output = File(appDir, "build/outputs/ui-check/page-cover-mid.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }

        compose.mainClock.autoAdvance = true
        waitForText("第 2/")
        val currentLeft = left(1)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.1f)) }
        compose.mainClock.advanceTimeBy(150)
        assertTrue("返回时上层纸页应向右退出", left(1) > currentLeft + 20f)
        assertTrue("上一页应原地露出", kotlin.math.abs(left(0) - firstLeft) < 3f)
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
