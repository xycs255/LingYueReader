package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
class BookRulesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("【楔子】\n那年冬天。本章未完\n【一 下山】\n他下了山。本章未完\n【二 进城】\n他进了城。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 20)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun pickATocPresetAddACleanupRuleAndSave() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performTouchInput { longClick() }
        waitForText("详情")
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("净化与分章规则"))
        compose.onNodeWithText("净化与分章规则").performClick()
        waitForText("按这个规则只分出 1 章")
        compose.onNodeWithText("【章名】").performClick()
        waitForText("将分为 3 章")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("查找"))
        compose.onNodeWithText("查找").performTextInput("本章未完")
        waitForText("将匹配 2 处")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("添加"))
        compose.onNodeWithText("添加").performClick()
        waitForText("匹配 2 处")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存并重新分章"))
        compose.onNodeWithText("保存并重新分章").performClick()
        compose.waitUntil(15_000) {
            JSONArray(File(context.filesDir, "books/index.json").readText()).getJSONObject(0).getInt("chapterCount") == 3
        }
        val rules = JSONObject(File(context.filesDir, "books/b1/rules.json").readText())
        assertEquals("本章未完", rules.getJSONArray("replace").getJSONObject(0).getString("pattern"))
    }
}
