package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** 统计页“各本书”：书多时先列读得最久的 10 本，点“查看全部”再展开。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
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

    @Test
    fun longBookListShowsTheTopTenUntilExpanded() {
        waitForText("林月第")
        compose.onNodeWithText("统计").performClick()
        waitForText("查看全部 12 本")
        // 书架页可能还留在组合里，按出现的次数比：第 11、12 本读得最少，展开前不列。
        fun count(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().size
        val last = count("林月第12卷")
        compose.onNodeWithText("查看全部 12 本").performScrollTo().performClick()
        compose.waitUntil(10_000) { count("林月第12卷") == last + 1 }
        waitForText("收起")
    }
}
