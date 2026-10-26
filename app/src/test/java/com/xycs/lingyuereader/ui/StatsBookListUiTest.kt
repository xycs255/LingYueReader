package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

/** 统计页“各本书”：书多时先列读得最久的 10 本，点“查看全部”再展开。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsBookListUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val index = JSONArray()
            (1..12).forEach { i ->
                val dir = File(files, "books/b$i").apply { mkdirs() }
                File(dir, "s0.txt").writeText("第一章 开端\n林月推开院门。\n")
                index.put(
                    JSONObject()
                        .put("id", "b$i").put("title", "林月第${i}卷").put("author", "").put("format", "TXT")
                        .put("addedAt", i.toLong()).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 8)
                        .put("readMillis", (100 - i) * 60_000L)
                        .put("sources", JSONArray().put(JSONObject().put("name", "林月第${i}卷.txt").put("uri", "").put("file", "s0.txt"))),
                )
            }
            File(files, "books/index.json").writeText(index.toString())
            File(files, "stats.json").delete()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (cwd.name == "app") cwd else File(cwd, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun longBookListShowsTheTopTenUntilExpanded() {
        waitForText("林月第")
        compose.onNodeWithText("统计").performClick()
        waitForText("查看全部 12 本")
        // 只检查统计列表里的行，避免页面过渡期间书架同名节点影响数量。
        compose.onNodeWithTag("stats-book-b12").assertDoesNotExist()
        compose.onNodeWithText("查看全部 12 本").performScrollTo()
        // 自动滚动只保证节点在视口内；悬浮底栏盖住视口底部，像用户一样再上滑一段。
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("查看全部 12 本").performClick()
        waitForText("收起")
        compose.onNodeWithTag("stats-book-b12").assertExists()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("收起").performScrollTo()
        compose.onRoot().performTouchInput { swipeUp() }
        capture("stats-expanded")
    }
}
