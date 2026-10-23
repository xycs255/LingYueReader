package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
// 手机大小的屏幕：默认的 320×470dp 太矮，统计页下方的书单会落在悬浮导航栏下面、不在屏幕内。
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class StatsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            // 第一章 2000 字左右。
            File(dir, "s0.txt").writeText("第一章 开端\n" + (1..50).joinToString("\n") { "　　他走进院子，看见老槐树下坐着一个人，正低头剥豆子。" } + "\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 1300).put("readMillis", 45 * 60_000L)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val today = LocalDate.now()
            // 两天共 40 分钟、读 8000 字：每分钟 200 字。
            val days = JSONObject()
                .put(today.toString(), JSONObject().put("ms", 25 * 60_000L).put("chars", 5_000).put("books", JSONObject().put("b1", 25 * 60_000L)))
                .put(today.minusDays(1).toString(), JSONObject().put("ms", 15 * 60_000L).put("chars", 3_000))
            File(files, "stats.json").writeText(JSONObject().put("days", days).toString())
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun statsScreenShowsTotalsStreakAndSpeed() {
        waitForText("测试书")
        compose.onNodeWithText("统计").performClick()
        waitForText("25 分")
        waitForText("连续阅读 2 天")
        waitForText("每分钟 200 字")
        waitForText("45 分钟")
        compose.onNodeWithContentDescription("近 20 周有 2 天在读书").assertExists()
    }

    @Test
    fun statusLineEstimatesTimeLeftInTheChapter() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        // 约 1300 字、每分钟 200 字：开头处约剩 7 分钟。
        waitForText("本章约剩 7 分钟")
    }
}
