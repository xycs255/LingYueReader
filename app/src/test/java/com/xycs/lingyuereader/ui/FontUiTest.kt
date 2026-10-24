package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fontFile get() = File(context.filesDir, "fonts/TestBlocks.ttf")
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n" + (1..30).joinToString("\n") { "　　测试字体灵阅 ABC abc 第${it}段。" } + "\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 600)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            fontFile.parentFile!!.mkdirs()
            File("src/test/resources/testfonts/TestBlocks.ttf").copyTo(fontFile, overwrite = true)
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("font", "TestBlocks.ttf").putString("pageMode", "SLIDE").putBoolean("breakReminder", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun importedFontIsUsedForPagingAndCanBeDeleted() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        // 翻页模式下用导入的字体分页、显示。
        waitForText("第 1/")
        waitForText("测试字体灵阅")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("字体").performScrollTo().performClick()
        waitForText("TestBlocks")
        // 长按导入的字体，下面出现红色的“删除”。
        compose.onNodeWithText("TestBlocks").performScrollTo().performTouchInput { longClick() }
        waitForText("删除")
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.waitUntil(5_000) { !fontFile.exists() }
        assertFalse(fontFile.exists())
        // 删掉正在用的字体后退回内置字体。
        compose.waitUntil(5_000) { context.getSharedPreferences("reader", Context.MODE_PRIVATE).getString("font", null) == "" }
        assertEquals("", context.getSharedPreferences("reader", Context.MODE_PRIVATE).getString("font", null))
    }
}
