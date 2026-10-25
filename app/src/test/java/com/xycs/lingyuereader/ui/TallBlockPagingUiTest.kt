package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 翻页模式：比一页还高的引用（一整封信）拆到几页上，末尾那几句翻得到、不被裁掉。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TallBlockPagingUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val ending = "信末一句唯一"

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            val letter = (1..40).joinToString("") { "长风兄，第${it}封信写到这里，院里的梅花又开了一枝。" } + ending + "。"
            File(dir, "s0.md").writeText("# 第一章 来信\n\n杜娘把信递过来。\n\n> $letter\n\n读完以后，屋里很久没人说话。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "梅花来信").put("author", "").put("format", "MARKDOWN")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 1200)
                .put("sources", JSONArray().put(JSONObject().put("name", "梅花来信.md").put("uri", "").put("file", "s0.md")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "NONE").putBoolean("breakReminder", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test
    fun aQuoteTallerThanAPageContinuesOnTheNextPages() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("梅花来信").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("梅花来信")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("杜娘把信递过来", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // 停在屏幕上的这一页里含 [text] 的文字（前后页也排着，但在屏幕外）；用没被裁过的位置和大小。
        fun onScreen(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
            .filter { it.positionInRoot.x >= root.left && it.positionInRoot.x < root.right }
        var pages = 0
        while (onScreen(ending).isEmpty() && pages < 20) {
            compose.onRoot().performTouchInput { click(center.copy(x = width * 0.9f)) }
            compose.waitForIdle()
            pages++
        }
        val shown = onScreen(ending)
        assertTrue("翻遍了也没看到信的末尾", shown.isNotEmpty())
        // 信的末尾那一段整段都在页里：没有排到页底下面（文字超出可用高度被裁掉）。
        shown.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertTrue("引用排出了页底", layouts.isNotEmpty() && layouts.none { it.didOverflowHeight })
            assertTrue(node.positionInRoot.y + node.size.height <= root.bottom)
        }
        // 引用后面的段落接在同一页或下一页上。
        if (onScreen("屋里很久没人说话").isEmpty()) compose.onRoot().performTouchInput { click(center.copy(x = width * 0.9f)) }
        compose.waitUntil(5_000) { onScreen("屋里很久没人说话").isNotEmpty() }
    }
}
