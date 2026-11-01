package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 阅读侧入口精简以后的盘点：阅读底栏“外观 / 更多”（原来的“设置”）、阅读“⋯”里的净化与分章、
 * 书的菜单只留“详情 / 分组… / 移除”（刷新、重命名在详情页，详情页补了分组）、我的页分组和“AI 服务”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReaderEntriesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n　　正文一。\n第二章 路上\n　　正文二。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 8)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun has(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(t: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForGone(t: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty() }

    private fun openReaderMenu() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文")
        compose.onRoot().performTouchInput { click(center) }
        waitForText("目录")
    }

    /**
     * 底栏四格“目录 / 听书 / 外观 / 更多”：字体、翻页方式只在“外观”；“更多”直接进“更多设置”，
     * 但栈底仍是“外观”：左上角“‹ 外观”、返回键都先回外观（字体、翻页方式够得着），再返回才关面板。
     */
    @Test
    fun appearanceAndMoreSplitTheReadingSettingsWithoutRepeats() {
        openReaderMenu()
        assertFalse("底栏不再有“设置”格", has("设置"))
        compose.onNodeWithText("外观").performClick()
        waitForText("翻页方式")
        assertTrue(has("字体"))
        compose.onNodeWithText("更多设置").performScrollTo().performClick()
        waitForText("点按与按键")
        compose.waitForIdle()
        listOf("排版", "繁简与注音", "护眼", "屏幕").forEach { assertTrue(it, has(it)) }
        listOf("字体", "翻页方式").forEach { assertFalse("$it 只在外观首页", has(it)) }
        // 返回键先回外观首页，再关面板。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("翻页方式")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForGone("翻页方式")

        compose.onRoot().performTouchInput { click(center) }
        waitForText("目录")
        compose.onNodeWithText("更多").performClick()
        waitForText("点按与按键")
        compose.waitForIdle()
        assertFalse(has("字体"))
        // 返回键：先回外观首页，再关面板。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("翻页方式")
        assertTrue(has("字体"))
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForGone("翻页方式")
    }

    /** 从“更多”打开的设置面板，左上角有“‹ 外观”：点了回外观首页，字体、翻页方式就在那里。 */
    @Test
    fun settingsOpenedFromMoreCanGoBackToAppearance() {
        openReaderMenu()
        compose.onNodeWithText("更多").performClick()
        waitForText("点按与按键")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回外观").performClick()
        waitForText("翻页方式")
        compose.waitForIdle()
        assertTrue(has("字体"))
        assertFalse("回到外观首页，“更多设置”那页收起", has("点按与按键"))
        // 外观首页是栈底：没有返回钮，右上角是“完成”。
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("返回外观")).fetchSemanticsNodes().isEmpty())
        assertTrue(has("完成"))
    }

    /** 净化与分章：阅读“⋯”里两下就到（原来只能从详情页进）。 */
    @Test
    fun cleanupAndChaptersAreTwoTapsFromTheReader() {
        openReaderMenu()
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("净化与分章")
        compose.onNodeWithText("净化与分章").performClick()
        compose.waitUntil(10_000) { vm.stack.value.last() == Screen.BookRules("b1") }
    }

    /** 书的菜单只留“详情 / 分组… / 移除”；重命名、刷新在详情页（动作行），详情页补了“分组…”。 */
    @Test
    fun bookMenuIsShortAndTheDetailPageHasTheRest() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performTouchInput { longClick() }
        waitForText("移除")
        compose.waitForIdle()
        listOf("详情", "分组…", "移除").forEach { assertTrue(it, has(it)) }
        listOf("重命名", "从原文件刷新").forEach { assertFalse(it, has(it)) }
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("重命名"))
        assertTrue(has("净化与分章"))
        compose.onNodeWithText("分组…").performClick()
        waitForText("《测试书》的分组")
        compose.onNodeWithText("取消").performClick()
        waitForGone("《测试书》的分组")
        compose.onNodeWithText("重命名").performClick()
        waitForText("确定")
    }

    /** 我的页：阅读 / AI 与语音 / 备份 / 关于；全局 AI 设置叫“AI 服务”，进去的页标题也是。 */
    @Test
    fun mePageGroupsAndTheAiServiceName() {
        waitForText("测试书")
        compose.onNodeWithText("我的").performClick()
        // 先等“我的”页滑到位（不然滚动时会把整排页面也拖动）。
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            runCatching { compose.onNodeWithText("主题设置").assertIsDisplayed() }.isSuccess
        }
        waitForText("AI 与语音")
        listOf("阅读", "备份", "关于").forEach { waitForText(it) }
        assertFalse(has("AI 助手"))
        compose.onNodeWithText("AI 服务").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { vm.stack.value.last() == Screen.Ai }
        waitForText("启用 AI 服务")
    }
}
