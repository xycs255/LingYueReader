package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
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

private fun seedLongBook(context: Context, pageMode: String) {
    val files = context.filesDir
    File(files, "books").deleteRecursively()
    val dir = File(files, "books/b1").apply { mkdirs() }
    val chapter1 = (1..40).joinToString("\n") { "　　第${it}段，城门口排着长队，挑担的、推车的、牵驴的，一直排到护城河边。" }
    File(dir, "s0.txt").writeText("第一章 开端\n$chapter1\n第二章 进城\n　　第二章正文。\n")
    val entry = JSONObject()
        .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
        .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 1500)
        .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
    File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
    context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
        .putString("pageMode", pageMode).putBoolean("breakReminder", false).putFloat("autoScrollLines", 60f).commit()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoScrollUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() = seedLongBook(context, "SCROLL")
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun firstVisibleParagraph(): Int = compose.onAllNodesWithText("段，城门口", substring = true).fetchSemanticsNodes()
        .filter { it.boundsInRoot.bottom > 0 }
        .minOf { n -> Regex("""第(\d+)段""").find(n.config.toString())!!.groupValues[1].toInt() }

    @Test
    fun scrollsAtTheChosenSpeedPausesOnTapAndAdjustsSpeed() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("第1段")
        compose.onRoot().performTouchInput { click(center) }
        // 自动滚动一直在动，界面不会“空闲”：之后手动拨时钟，直接检查。
        compose.onNodeWithContentDescription("自动滚动").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("自动滚动").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("每分钟 60 行", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(20_000)
        val moved = firstVisibleParagraph()
        assertTrue("moved=$moved", moved > 3)
        // 点正文暂停，再等也不动。
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500)
        val paused = firstVisibleParagraph()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(paused, firstVisibleParagraph())
        compose.mainClock.autoAdvance = true
        waitForText("已暂停")
        compose.onNodeWithContentDescription("快一点").performClick()
        waitForText("每分钟 62 行")
        assertEquals(62f, context.getSharedPreferences("reader", Context.MODE_PRIVATE).getFloat("autoScrollLines", 0f))
        compose.onNodeWithContentDescription("停止自动滚动").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("每分钟", substring = true).fetchSemanticsNodes().isEmpty() }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoPageUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() = seedLongBook(context, "SLIDE")
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun turnsPagesOnATimer() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("· 1/")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("自动滚动").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("自动滚动").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("自动翻页").assertExists()
        // 每分钟 60 行，一页十几行：二十多秒内至少翻一页。
        compose.mainClock.advanceTimeBy(25_000)
        // 状态行“1/2 · N/12 页”：页码前进了。
        val status = compose.onAllNodesWithText(" 页 · ", substring = true).fetchSemanticsNodes()
            .joinToString { it.config.toString() }
        val page = Regex("""· (\d+)/\d+ 页""").find(status)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(status, page >= 2)
        compose.onNodeWithContentDescription("停止自动滚动").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
    }
}
