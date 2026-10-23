package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 故乡\n　　我冒了严寒，回到相隔二千余里，别了二十余年的故乡去。\n")
            val entry = JSONObject().put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 30)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "VERTICAL").putBoolean("ambientLight", false).putBoolean("eveningWarmth", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test
    fun longPressInAColumnLooksUpTheWord() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("测试书").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("测试书")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("竖排正文")).fetchSemanticsNodes().isNotEmpty() }
        // 右起第三列是正文第一列：□□我冒了严寒……；按在“严寒”上。
        compose.onNodeWithContentDescription("竖排正文").performTouchInput {
            longClick(Offset(width - 24.dp.toPx() - 2.5f * 19f * 1.8f * density, 150.dp.toPx()))
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("严寒", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
