package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.tts.ReadAloud
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
import java.io.File

/** 听书暂停着，从书内搜索的结果跳到别的章：阅读页停在跳过去的那章，不被弹回听书所在的章。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ListenSearchJumpUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            val first = (1..20).joinToString("\n") { "　　第${it}段，老周把船拴在柳树上，回头看了一眼对岸。" }
            File(dir, "s0.txt").writeText("第一章 柳岸\n$first\n第二章 渡口\n　　杜娘提着灯笼走下台阶。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "柳岸渡口").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 600)
                .put("sources", JSONArray().put(JSONObject().put("name", "柳岸渡口.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "SCROLL").putBoolean("breakReminder", false).commit()
        }

        override fun after() = ReadAloud.resetForTest()
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun savedChapter(): Int? = JSONArray(File(context.filesDir, "books/index.json").readText())
        .getJSONObject(0).optJSONObject("progress")?.getInt("chapter")

    @Test
    fun pausedListeningDoesNotPullASearchJumpBack() {
        waitForText("柳岸渡口")
        compose.onAllNodesWithText("柳岸渡口")[0].performClick()
        waitForText("第1段")
        // 在第一章开始听书，然后暂停。
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("听书").performClick()
        waitForText("正在听书")
        compose.runOnUiThread { ReadAloud.pause() }
        compose.waitUntil(5_000) { ReadAloud.state.value?.playing == false }
        assertEquals(0, ReadAloud.state.value?.chapterIndex)

        // 书内搜索第二章里的字，点结果跳过去。
        if (compose.onAllNodesWithContentDescription("书内搜索").fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performTouchInput { click(center) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("书内搜索").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("书内搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("提着灯笼")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForText("共 1 处")
        compose.onNodeWithText("第二章 渡口").performClick()
        waitForText("杜娘提着灯笼")

        // 过一会儿仍在第二章，存下的进度也是第二章。
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("杜娘提着灯笼", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("第1段", substring = true).fetchSemanticsNodes().isEmpty())
        compose.waitUntil(5_000) { savedChapter() == 1 }
        assertEquals(0, ReadAloud.state.value?.chapterIndex)

        // 暂停听书时底栏仍能回到搜索前的位置，且不会恢复播放或占出第二条浮层。
        val returnLabel = "回到刚才 · 第一章 柳岸"
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(returnLabel).fetchSemanticsNodes().isNotEmpty()
        }
        val returnTarget = compose.onNodeWithContentDescription(returnLabel).fetchSemanticsNode()
        assertTrue(returnTarget.boundsInRoot.height >= context.resources.displayMetrics.density * 48f - 1f)
        compose.onNodeWithContentDescription(returnLabel).performClick()
        waitForText("第1段")
        compose.waitUntil(5_000) { savedChapter() == 0 }
        assertEquals(false, ReadAloud.state.value?.playing)
        assertTrue(compose.onAllNodesWithContentDescription(returnLabel).fetchSemanticsNodes().isEmpty())
    }
}
