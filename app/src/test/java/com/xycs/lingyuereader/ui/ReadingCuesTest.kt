package com.xycs.lingyuereader.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

class ScrollDistanceTest {
    @Test
    fun aTapScrollsTheReadableAreaLessOneLine() {
        // 2000 高的视口，上面 100 被状态栏遮住，下面 150 被状态行遮住，一行 50。
        // 能看清 1750，少翻一行半。
        assertEquals(1675f, pageScrollDistance(2000f, 100f, 150f, 50f), 0.01f)
        // 屏幕极小时至少翻一行。
        assertEquals(50f, pageScrollDistance(120f, 100f, 150f, 50f), 0.01f)
    }
}

/** 跳回去的那一段会亮一下再褪掉。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandingGlowUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n　　正文一。\n第二章 路上\n　　正文二。\n")
            val entry = JSONObject().put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 8)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("breakReminder", false).putBoolean("ambientLight", false).putBoolean("eveningWarmth", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 某段左边一点（发光区里、字外面）的颜色。 */
    private fun beside(text: String): androidx.compose.ui.graphics.Color {
        val bounds = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot
        val px = with(compose.density) { 3.dp.toPx() }
        val map = compose.onRoot().captureToImage().toPixelMap()
        return map[(bounds.left - px).toInt(), bounds.center.y.toInt()]
    }

    @Test
    fun aSearchHitLightsUpItsParagraphThenFades() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文一。")
        compose.waitForIdle()
        val page = beside("正文一。")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("书内搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("正文二")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForText("共 1 处")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("第二章 路上").performClick()
        compose.mainClock.advanceTimeBy(900)
        val glowing = beside("正文二。")
        assertNotEquals("刚跳过来时这一段在发光", page, glowing)
        assertTrue("发光偏红（朱砂强调色）：$glowing", glowing.red > glowing.blue)
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("几秒后褪掉", page, beside("正文二。"))
    }
}

/** 翻页模式两侧的书页厚度：刚开始读，右边厚、左边薄。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageEdgesUiTest : PageTurnUiBase("SLIDE") {
    override fun prefs(editor: SharedPreferences.Editor) {
        editor.putBoolean("ambientLight", false).putBoolean("eveningWarmth", false)
    }

    @Test
    fun unreadPagesStackOnTheRight() {
        open()
        waitForText("· 1/")
        compose.waitForIdle()
        val map = compose.onRoot().captureToImage().toPixelMap()
        val y = map.height / 2
        val page = map[map.width / 2, 8].luminance()
        // 两侧 16dp 里有几列是书页线（比纸色暗）。
        val band = with(compose.density) { 16.dp.toPx() }.toInt()
        fun dark(xs: IntProgression) = xs.count { x -> map[x, y].luminance() < page - 0.02f }
        val left = dark(0 until band)
        val right = dark(map.width - band until map.width)
        assertTrue("刚开始读：右边（没读的）比左边厚，左 $left 列、右 $right 列", right > left)
    }
}

