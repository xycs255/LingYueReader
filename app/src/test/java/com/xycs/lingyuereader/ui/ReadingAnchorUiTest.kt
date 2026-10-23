package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
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

/** 真实阅读页的重排、重启和书签定位回归；正文中每段都有唯一文字。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingAnchorUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val marker = "雨停见虹"
    private val firstMarker = "开篇唯一句"
    private var targetChapterIndex = -1
    private var targetChapterTitle = ""
    private var firstChapterTitle = ""
    private var chapterCount = 0
    private var targetBlock = -1
    private var targetChar = -1

    private val seed = object : ExternalResource() {
        override fun before() {
            val root = File(context.filesDir, "books")
            root.deleteRecursively()
            val dir = File(root, "anchor-book").apply { mkdirs() }
            val source = File(dir, "s0.txt")
            val paragraphs = (1..90).map { number ->
                when (number) {
                    61 -> "　　第六十一段。" + "风吹过院墙，旧灯仍然亮着。".repeat(12) +
                        "$marker，田槐终于找到了回家的路。" + "夜色渐渐退开。".repeat(12)
                    else -> "　　第${number}段。" + "院墙边的桂花带着淡淡香气。".repeat(20)
                }
            }
            source.writeText("第一章 序章\n　　$firstMarker。庭前的树影落在地上。\n第二章 漫长的路\n${paragraphs.joinToString("\n")}")
            val content = BookParser.parse(listOf(BookParser.SourceBytes("长篇.txt", source)))
            chapterCount = content.chapters.size
            targetChapterIndex = content.chapters.indexOfFirst { chapter -> chapter.blocks.any { it.displayText?.contains(marker) == true } }
            val chapter = content.chapters[targetChapterIndex]
            targetChapterTitle = chapter.title
            firstChapterTitle = content.chapters.first { c -> c.blocks.any { it.displayText?.contains(firstMarker) == true } }.title
            targetBlock = chapter.blocks.indexOfFirst { it.displayText?.contains(marker) == true }
            targetChar = chapter.blocks[targetBlock].displayText!!.indexOf(marker)
            assertTrue(targetChapterIndex >= 0 && targetBlock >= 0 && targetChar > 0)
            val anchor = TextAnchors.capture(chapter, targetBlock, targetChar)
            val progress = JSONObject().put("chapter", targetChapterIndex).put("title", chapter.title)
                .put("item", targetBlock + 1).put("offset", 0).put("char", targetChar)
                .put("anchorText", anchor.text).put("anchorOffset", anchor.offset)
            val entry = JSONObject().put("id", "anchor-book").put("title", "长篇测试书")
                .put("author", "").put("format", "TXT").put("addedAt", 1L).put("openedAt", 0L)
                .put("chapterCount", chapterCount).put("charCount", content.charCount)
                .put("sources", JSONArray().put(JSONObject().put("name", "长篇.txt").put("uri", "").put("file", "s0.txt")))
                .put("progress", progress)
            File(root, "index.json").writeText(JSONArray().put(entry).toString())
            val bookmark = Bookmark("target", targetChapterIndex, chapter.title, targetBlock + 1, targetChar, marker, 1L, anchor)
            File(dir, "marks.json").writeText(Marks(bookmarks = listOf(bookmark)).toJson().toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "SLIDE").commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        waitForText("长篇测试书")
        compose.onAllNodesWithText("长篇测试书")[0].performClick()
        waitForText(marker)
    }

    private fun chooseRelaxedLayout() {
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("设置").performClick()
        waitForText("阅读设置")
        compose.onNodeWithText("宽松").performClick()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("阅读设置").fetchSemanticsNodes().isEmpty() }
    }

    private fun openCatalog() {
        if (compose.onAllNodesWithText("目录").fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performTouchInput { click(center) }
        }
        compose.onNodeWithText("目录").performClick()
        waitForText("目录 2")
    }

    private fun savedProgress(): JSONObject = JSONArray(File(context.filesDir, "books/index.json").readText())
        .getJSONObject(0).getJSONObject("progress")

    @Test
    fun reflowAndReopenStayOnTheSameLongTxtParagraph() {
        openBook()
        assertEquals(targetBlock + 1, savedProgress().getInt("item"))
        chooseRelaxedLayout() // 同时改变字号和行距，触发重新分页。
        waitForText(marker)
        compose.waitUntil(10_000) { savedProgress().getInt("item") == targetBlock + 1 }
        compose.onNodeWithContentDescription("返回书架").performClick()
        waitForText("长篇测试书")
        compose.onAllNodesWithText("长篇测试书")[0].performClick()
        waitForText(marker)
        assertEquals(targetBlock + 1, savedProgress().getInt("item"))
        assertTrue(savedProgress().getString("anchorText").isNotEmpty())
    }

    @Test
    fun bookmarkAfterFontChangeReturnsToOriginalSentenceAndBack() {
        openBook()
        chooseRelaxedLayout()
        // 从目录回到章首，再用带字符锚点的书签跳回中段。
        openCatalog()
        compose.onNode(hasText(firstChapterTitle) and hasClickAction()).performClick()
        waitForText(firstMarker)
        // 连续跳转默认保留最早的位置；主动结束第一次回跳，再验证书签自己的回跳闭环。
        waitForText("回到刚才")
        compose.onNodeWithContentDescription("不用回去").performClick()
        openCatalog()
        compose.onNodeWithText("书签 1").performClick()
        compose.onNode(hasText(marker, substring = true) and hasClickAction()).performClick()
        waitForText(marker)
        waitForText("回到刚才")
        compose.onAllNodesWithText("回到刚才", substring = true)[0].performClick()
        waitForText(firstMarker)
    }
}
