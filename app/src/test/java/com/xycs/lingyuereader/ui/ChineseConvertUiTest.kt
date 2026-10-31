package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.BookParser
import com.xycs.lingyuereader.data.Bookmark
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.TextAnchors
import com.xycs.lingyuereader.data.displayText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ChineseConvertUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            val source = File(dir, "s0.txt").apply {
                writeText("第一章 开端\n　　她的头发很长，后来皇后也来了。\n第二章 头发\n　　她找到掉落的头发，又走回家。\n")
            }
            val content = BookParser.parse(listOf(BookParser.SourceBytes("测试书.txt", source)))
            val chapter = content.chapters[1]
            val block = chapter.blocks.indexOfFirst { it.displayText?.contains("她找到掉落的头发") == true }
            val offset = chapter.blocks[block].displayText!!.indexOf("头发")
            val bookmark = Bookmark("converted-bookmark", 1, chapter.title, block + 1, offset,
                "她找到掉落的头发", 1, TextAnchors.capture(chapter, block, offset))
            File(dir, "marks.json").writeText(Marks(bookmarks = listOf(bookmark)).toJson().toString())
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", content.chapters.size).put("charCount", content.charCount)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            val traditionalDir = File(files, "books/b2").apply { mkdirs() }
            val traditionalSource = File(traditionalDir, "s0.txt").apply {
                writeText("第一章 開端\n　　她的頭髮很長，後來皇后也來了。\n第二章 頭髮\n　　她找到掉落的頭髮，又走回家。\n")
            }
            val traditionalContent = BookParser.parse(listOf(BookParser.SourceBytes("繁体书.txt", traditionalSource)))
            val traditionalChapter = traditionalContent.chapters[1]
            val traditionalBlock = traditionalChapter.blocks.indexOfFirst { it.displayText?.contains("她找到掉落的頭髮") == true }
            val traditionalOffset = traditionalChapter.blocks[traditionalBlock].displayText!!.indexOf("頭髮")
            val traditionalBookmark = Bookmark("traditional-bookmark", 1, traditionalChapter.title,
                traditionalBlock + 1, traditionalOffset, "她找到掉落的頭髮", 1,
                TextAnchors.capture(traditionalChapter, traditionalBlock, traditionalOffset))
            File(traditionalDir, "marks.json").writeText(Marks(bookmarks = listOf(traditionalBookmark)).toJson().toString())
            val traditionalEntry = JSONObject()
                .put("id", "b2").put("title", "繁体书").put("author", "").put("format", "TXT")
                .put("addedAt", 2L).put("openedAt", 0L).put("chapterCount", traditionalContent.chapters.size)
                .put("charCount", traditionalContent.charCount)
                .put("sources", JSONArray().put(JSONObject().put("name", "繁体书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).put(traditionalEntry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putBoolean("breakReminder", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showReaderMenu() {
        // 转换模式会重新载入正文，底栏在旧面板退出和新正文显示之间可能被重建。
        repeat(4) {
            compose.waitForIdle()
            if (compose.onAllNodesWithText("目录").fetchSemanticsNodes().isNotEmpty()) return
            compose.onRoot().performTouchInput { click(center) }
        }
        compose.onNodeWithText("目录").fetchSemanticsNode()
    }

    private fun chooseConversion(option: String, expectedBody: String) {
        showReaderMenu()
        compose.onNodeWithText("更多").performClick()
        waitForText("更多设置")
        // 繁简在“繁简与注音”二级页里。
        compose.onNodeWithText("繁简与注音").performScrollTo().performClick()
        compose.onNodeWithText(option).performScrollTo().performClick()
        waitForText(expectedBody)
        // 从“更多”进来时栈底是“外观”：第一次返回回到更多设置，第二次回外观首页，第三次关面板。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("更多设置").fetchSemanticsNodes().isEmpty() }
    }

    private fun jumpViaBookmark(excerpt: String, expectedBody: String, expectedChapter: String) {
        showReaderMenu()
        compose.onNodeWithText("目录").performClick()
        waitForText("书签 1")
        compose.onNodeWithText("书签 1").performClick()
        compose.onNode(hasText(excerpt, substring = true) and hasClickAction()).performClick()
        waitForText(expectedBody)
        waitForText(expectedChapter)
    }

    @Test
    fun switchingToTraditionalConvertsTheOpenBook() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("她的头发很长")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("繁简与注音").performScrollTo().performClick()
        waitForText("简转繁")
        compose.onNodeWithText("简转繁").performScrollTo().performClick()
        waitForText("她的頭髮很長，後來皇后也來了。")
        waitForText("第一章 開端")
    }

    @Test
    fun bookmarkInOriginalTextStaysClickableAfterConversionAndSwitchBack() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("她的头发很长")
        chooseConversion("简转繁", "她的頭髮很長")
        jumpViaBookmark("她找到掉落的头发", "她找到掉落的頭髮", "第二章 頭髮")
        chooseConversion("不转换", "她找到掉落的头发")
        jumpViaBookmark("她找到掉落的头发", "她找到掉落的头发", "第二章 头发")
    }

    @Test
    fun traditionalBookmarkStaysClickableInSimplifiedView() {
        waitForText("繁体书")
        compose.onAllNodesWithText("繁体书")[0].performClick()
        waitForText("她的頭髮很長")
        chooseConversion("繁转简", "她的头发很长")
        jumpViaBookmark("她找到掉落的頭髮", "她找到掉落的头发", "第二章 头发")
    }
}
