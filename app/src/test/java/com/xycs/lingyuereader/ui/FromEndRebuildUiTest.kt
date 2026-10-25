package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
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

/**
 * 往回翻进上一章（停在章末）以后，在那一章里再往回翻几页，然后阅读页重建（旋转屏幕、进搜索再返回）：
 * 应当回到刚才那一页，不能又跳到章末、把末页存成进度。
 */
abstract class FromEndRebuildUiBase(private val mode: String, private val prevTapX: Float) {
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            val first = (1..40).joinToString("\n") { "　　第${it}段，林月沿着河堤往回走，风从芦苇里钻出来，吹得灯笼一晃一晃。" }
            File(dir, "s0.txt").writeText("第一章 河堤\n$first\n第二章 渡口\n　　沈默在渡口等了一夜。\n")
            // 上次读到第二章开头。
            val progress = JSONObject().put("chapter", 1).put("title", "第二章 渡口").put("item", 1).put("offset", 0).put("char", 0)
            val entry = JSONObject()
                .put("id", "b1").put("title", "河堤夜话").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 1700)
                .put("sources", JSONArray().put(JSONObject().put("name", "河堤夜话.txt").put("uri", "").put("file", "s0.txt")))
                .put("progress", progress)
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", mode).putBoolean("breakReminder", false)
                .putBoolean("ambientLight", false).putBoolean("eveningWarmth", false).commit()
        }
    }
    protected val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val label = Regex("第 (\\d+)/(\\d+) 页")

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 状态行上的“第 i/n 页”。 */
    private fun page(): Pair<Int, Int>? = compose.onAllNodesWithText("页", substring = true).fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }
        .firstNotNullOfOrNull { text -> label.find(text.text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } }

    private fun waitForPage(test: (Pair<Int, Int>) -> Boolean): Pair<Int, Int> {
        compose.waitUntil(10_000) { page()?.let(test) == true }
        return page()!!
    }

    private fun savedProgress(): JSONObject = JSONArray(File(context.filesDir, "books/index.json").readText())
        .getJSONObject(0).getJSONObject("progress")

    private fun turnBack() {
        compose.onRoot().performTouchInput { click(center.copy(x = width * prevTapX)) }
    }

    /** 从第二章开头往回翻进第一章（停在章末），再往回翻两页；返回这时的页码和存下的进度。 */
    private fun backIntoFirstChapterAndTwoPagesMore(): Pair<Pair<Int, Int>, JSONObject> {
        waitForText("河堤夜话")
        compose.onAllNodesWithText("河堤夜话")[0].performClick()
        waitForText("第 1/1 页")
        turnBack()
        val (last, total) = waitForPage { it.second > 3 && it.first == it.second }
        assertEquals(total, last)
        turnBack()
        waitForPage { it.first == total - 1 }
        turnBack()
        val at = waitForPage { it.first == total - 2 }
        // 停下 300 毫秒才存进度：等存到第一章这一页。
        var saved = JSONObject()
        compose.waitUntil(10_000) {
            saved = savedProgress()
            saved.getInt("chapter") == 0 && saved.getInt("item") > 1
        }
        compose.waitForIdle()
        return at to savedProgress()
    }

    private fun assertStaysAt(at: Pair<Int, Int>, saved: JSONObject) {
        val now = waitForPage { true }
        assertEquals("重建后还在刚才那一页，没有跳到章末", at, now)
        // 等过存进度的 300 毫秒，进度也没被改成末页。
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(at, page())
        val after = savedProgress()
        assertEquals(0, after.getInt("chapter"))
        assertEquals(saved.getInt("item"), after.getInt("item"))
        assertEquals(saved.getInt("char"), after.getInt("char"))
    }

    @Test
    fun recreateKeepsThePageAfterTurningBackAcrossChapters() {
        val (at, saved) = backIntoFirstChapterAndTwoPagesMore()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertStaysAt(at, saved)
    }

    @Test
    fun searchAndBackKeepsThePageAfterTurningBackAcrossChapters() {
        val (at, saved) = backIntoFirstChapterAndTwoPagesMore()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("书内搜索")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("书内搜索").performClick()
        compose.waitUntil(10_000) { page() == null }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertTrue(page() != null || compose.onAllNodesWithText("河堤", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertStaysAt(at, saved)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagedFromEndRebuildUiTest : FromEndRebuildUiBase("NONE", prevTapX = 0.1f)

/** 竖排：页从右往左翻，点右边是上一页。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalFromEndRebuildUiTest : FromEndRebuildUiBase("VERTICAL", prevTapX = 0.9f)
