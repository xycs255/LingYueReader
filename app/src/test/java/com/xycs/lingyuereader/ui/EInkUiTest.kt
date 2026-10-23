package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EInkUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 起\n　　正文。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 3)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        }

        override fun after() {
            Effects.reduced = false
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun eInkModeTurnsTheBookPagePureWhiteAndTurnsEffectsOff() {
        waitForText("测试书")
        compose.onNodeWithText("我的").performClick()
        // 墨水屏开关在“主题设置”里。
        waitForText("主题设置")
        compose.onNodeWithText("主题设置").performClick()
        waitForText("墨水屏 / 省电模式")
        compose.onNodeWithText("墨水屏 / 省电模式").performScrollTo().performClick()
        compose.waitUntil(5_000) { Effects.reduced }
        assertTrue(context.getSharedPreferences("reader", Context.MODE_PRIVATE).getBoolean("eInk", false))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("墨水屏模式")

        compose.onNodeWithText("书架").performClick()
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文。")
        compose.waitForIdle()
        // 书页是纯白的（平时是暖白 #F4F2EC，还会随环境光、日落变化）。
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val corner = pixels[pixels.width - 4, pixels.height / 2]
        assertEquals(androidx.compose.ui.graphics.Color.White, corner)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class AutoBackupUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            File(context.filesDir, "books").deleteRecursively()
            context.getSharedPreferences("auto-backup", Context.MODE_PRIVATE).edit().clear()
                .putString("folder", "content://com.example.missing/tree/primary%3ASync")
                .putString("label", "同步盘")
                .putLong("lastAt", System.currentTimeMillis())
                .commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test
    fun meShowsTheBackupFolderAndReportsAFailedBackup() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("存到「同步盘」", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // 文件夹已经不在了（比如授权被收回）：备份失败要说出来，也记在开关下面。
        compose.onNodeWithText("现在备份一次").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("备份失败", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("上次失败", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
