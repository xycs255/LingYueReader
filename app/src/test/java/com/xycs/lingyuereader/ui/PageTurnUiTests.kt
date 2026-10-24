package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Rule
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
