package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import android.content.Context

/**
 * 在真实 Activity 里走一遍书架 → 阅读页 → 弹窗。miuix 的弹窗依赖 Activity 提供的返回手势分发器，
 * 依赖版本不对时一打开就闪退，单看截图发现不了。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OverlayUiTest {

    /** 在 Activity 启动前放好一本书。 */
    private val seedLibrary = object : ExternalResource() {
        override fun before() {
            val files = ApplicationProvider.getApplicationContext<Context>().filesDir
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n　　正文一。\n第二章 路上\n　　正文二。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 8)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedLibrary).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openReaderMenu() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文")
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
    }

    @Test
    fun settingsSheetOpens() {
        openReaderMenu()
        compose.onNodeWithText("设置").performClick()
        waitForText("阅读设置")
        compose.onNodeWithText("恢复默认排版").assertExists()
    }

    @Test
    fun catalogSheetOpensAndJumps() {
        openReaderMenu()
        compose.onNodeWithText("目录").performClick()
        waitForText("目录 2")
        compose.onAllNodesWithText("第二章 路上")[0].performClick()
        waitForText("正文二。")
    }

    @Test
    fun searchScreenOpens() {
        openReaderMenu()
        compose.onNodeWithContentDescription("书内搜索").performClick()
        waitForText("人名、地名、原话")
    }

    @Test
    fun shelfMenuAndDialogsOpen() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performTouchInput { longClick() }
        waitForText("重命名")
        compose.onNodeWithText("重命名").performClick()
        waitForText("确定")
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("测试书")[0].performTouchInput { longClick() }
        waitForText("移除")
        compose.onNodeWithText("移除").performClick()
        waitForText("移除《测试书》")
    }
}
