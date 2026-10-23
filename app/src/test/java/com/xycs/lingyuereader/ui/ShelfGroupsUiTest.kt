package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ShelfGroupsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val index = JSONArray()
            listOf("b1" to "甲书", "b2" to "乙书").forEachIndexed { i, (id, title) ->
                val dir = File(files, "books/$id").apply { mkdirs() }
                File(dir, "s0.txt").writeText("第一章 起\n　　$title 的正文。\n")
                index.put(
                    JSONObject().put("id", id).put("title", title).put("author", "").put("format", "TXT")
                        .put("addedAt", i.toLong()).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 5)
                        .put("sources", JSONArray().put(JSONObject().put("name", "$title.txt").put("uri", "").put("file", "s0.txt"))),
                )
            }
            File(files, "books/index.json").writeText(index.toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun gone(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun putABookInAGroupFilterRenameAndDelete() {
        waitForText("甲书")
        // 还没有分组时不显示分组栏。
        compose.onNodeWithText("全部").assertDoesNotExist()
        compose.onAllNodesWithText("甲书")[0].performTouchInput { longClick() }
        compose.onNodeWithText("分组…").performClick()
        waitForText("的分组")
        compose.onNodeWithText("新建分组").performTextInput("武侠")
        compose.onNodeWithText("添加").performClick()
        compose.onNodeWithText("确定").performClick()

        waitForText("全部")
        compose.onNodeWithText("武侠").performClick()
        gone("乙书")
        compose.onNodeWithText("未分组").performClick()
        waitForText("乙书")
        gone("甲书")

        // 长按分组改名。
        compose.onNodeWithText("武侠").performTouchInput { longClick() }
        waitForText("改名，或删除这个分组")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("江湖")
        compose.onNodeWithText("改名").performClick()
        waitForText("江湖")
        val saved = JSONArray(File(context.filesDir, "books/index.json").readText())
        val groups = (0 until saved.length()).associate { saved.getJSONObject(it).getString("title") to saved.getJSONObject(it).optJSONArray("groups")?.toString() }
        assertEquals("[\"江湖\"]", groups["甲书"])

        // 删除分组：书还在，分组栏消失。
        compose.onNodeWithText("江湖").performTouchInput { longClick() }
        compose.onNodeWithText("删除分组").performClick()
        gone("江湖")
        waitForText("甲书")
        waitForText("乙书")
    }
}
