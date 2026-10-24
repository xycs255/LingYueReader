package com.xycs.lingyuereader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

private val context: Context get() = ApplicationProvider.getApplicationContext()

/** 一本两章的书，第一章足够长，翻页模式下会分成好几页。 */
private fun seedBook(pageMode: String) {
    val files = context.filesDir
    File(files, "books").deleteRecursively()
    val dir = File(files, "books/b1").apply { mkdirs() }
    val longChapter = (1..40).joinToString("\n") { "　　第${it}段，老周是天亮前回来的，裤腿湿到膝盖，鞋底糊着一层黄泥，进了打谷场先找水喝。" }
    File(dir, "s0.txt").writeText("第一章 开端\n$longChapter\n第二章 路上\n　　正文二。\n")
    val entry = JSONObject()
        .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
        .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 2).put("charCount", 100)
        .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
    File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
    context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putString("pageMode", pageMode).commit()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagedModeUiTest {
    private val seed = object : ExternalResource() {
        override fun before() = seedBook("SLIDE")
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("1/")
    }

    @Test
    fun tapRightAndVolumeKeysTurnPages() {
        openBook()
        // 状态栏显示“第 1/N 页”
        waitForText("第 1/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("第 2/")
        compose.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        waitForText("第 3/")
        compose.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        waitForText("第 2/")
    }

    @Test
    fun catalogJumpLandsOnChapterInPageMode() {
        openBook()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("目录").performClick()
        waitForText("目录 2")
        compose.onAllNodesWithText("第二章 路上")[0].performClick()
        waitForText("正文二。")
        // 页脚左边是章名，右边“第 1/1 页”。
        waitForText("第 1/1 页")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarksUiTest {
    private val seed = object : ExternalResource() {
        override fun before() = seedBook("SCROLL")
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("第1段")
    }

    @Test
    fun longPressParagraphSavesHighlight() {
        openBook()
        // 长按先选词查词，再从查词面板进划线。
        compose.onAllNodesWithText("第1段", substring = true)[0].performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("划线·批注").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("划线·批注").performClick()
        waitForText("拖动选中要划线的文字")
        compose.onNodeWithText("划线").performClick()
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("目录").performClick()
        waitForText("批注 1")
        compose.onNodeWithText("批注 1").performClick()
        waitForText("导出全部批注")
        // 存到了书的目录里
        val saved = File(context.filesDir, "books/b1/marks.json").readText()
        assertEquals(1, JSONObject(saved).getJSONArray("notes").length())
    }

    @Test
    fun bookmarkButtonAddsBookmark() {
        openBook()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("加书签").performClick()
        waitForText("已加书签")
        compose.onNodeWithContentDescription("删除书签").assertExists()
        compose.onNodeWithText("目录").performClick()
        waitForText("书签 1")
    }

    @Test
    fun progressBarShowsWholeBookPercent() {
        openBook()
        compose.onRoot().performTouchInput { click(center) }
        waitForText("全书 ")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OpenWithUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun viewIntentImportsAndOpensOnceOnly() {
        File(context.filesDir, "books").deleteRecursively()
        val file = File(context.cacheDir, "外部的书.txt").apply { writeText("第一章 起\n　　外部正文。\n") }
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(file), context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("外部正文", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        // 再打开一次同一个文件，书架上仍然只有一本。
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("外部正文", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        val index = JSONArray(File(context.filesDir, "books/index.json").readText())
        assertEquals(1, index.length())
        assertEquals("外部的书", index.getJSONObject(0).getString("title"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ShelfUiTest {
    private val seed = object : ExternalResource() {
        override fun before() {
            seedBook("SCROLL")
            // 再加一本，测搜索和排序。
            val files = context.filesDir
            val dir = File(files, "books/b2").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 起\n　　乙书正文。\n")
            val index = JSONArray(File(files, "books/index.json").readText())
            index.getJSONObject(0)
                .put("openedAt", 3L)
                .put("progress", JSONObject().put("chapter", 0).put("title", "第一章 开端").put("item", 1).put("offset", 0))
            index.put(
                JSONObject().put("id", "b2").put("title", "乙书").put("author", "某人").put("format", "TXT")
                    .put("addedAt", 2L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 5).put("readMillis", 3_720_000L)
                    .put("sources", JSONArray().put(JSONObject().put("name", "乙书.txt").put("uri", "").put("file", "s0.txt"))),
            )
            File(files, "books/index.json").writeText(index.toString())
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun searchFiltersByTitleOrAuthor() {
        waitForText("测试书")
        waitForText("接着读")
        compose.onNodeWithContentDescription("搜索书架").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("某人")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("测试书").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("接着读").fetchSemanticsNodes().isEmpty() }
        waitForText("乙书")
    }

    @Test
    fun continueReadingShowsChapterAndOpensBook() {
        waitForText("接着读")
        waitForText("读到第 1/2 章")
        waitForText("第一章 开端")
        compose.onNodeWithText("继续阅读").performClick()
        waitForText("第1段")
    }

    @Test
    fun searchingTheTextOfEveryBookOpensTheHit() {
        waitForText("测试书")
        compose.onNodeWithContentDescription("搜索书架").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("乙书正文")
        waitForText("书名和作者里没有")
        compose.onNodeWithText("在所有书的正文里搜索", substring = true).performClick()
        waitForText("在 1 本书里找到 1 处")
        waitForText("《乙书》")
        compose.onAllNodesWithText("乙书正文。…")[0].performClick()
        // 打开那本书的那一处；返回回到搜索结果。
        waitForText("第一章 起")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("在 1 本书里找到 1 处")
    }

    @Test
    fun bottomTabsTapDragAndBackToShelf() {
        waitForText("测试书")
        // 点标签换页。
        compose.onNodeWithText("我的").performClick()
        waitForText("备份书架")
        // 返回键先回书架，而不是退出。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("备份书架").fetchSemanticsNodes().isEmpty() }
        waitForText("测试书")
        // 按住导航栏从“书架”拖到“统计”，松手就换到统计页。
        // 书架页的大标题也叫“书架”，取最下面那个（底栏的标签）。
        val from = compose.onAllNodesWithText("书架").fetchSemanticsNodes().maxBy { it.boundsInRoot.center.y }.boundsInRoot.center
        val to = compose.onNodeWithText("统计").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(from)
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
            up()
        }
        waitForText("本周")
    }

    @Test
    fun gridMenuOpensBookDetails() {
        waitForText("测试书")
        // 接着读卡片占据首行时，先把网格书项滚进视口再点操作。
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasContentDescription("操作"))
        compose.onAllNodesWithContentDescription("操作")[0].performScrollTo().performClick()
        waitForText("详情")
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        waitForText("累计阅读")
    }

    @Test
    fun listViewAndDetailPage() {
        waitForText("测试书")
        compose.onNodeWithContentDescription("排序与显示").performClick()
        waitForText("列表显示")
        compose.onNodeWithText("列表显示").performClick()
        // 接着读卡片占据首行，先滚到有作者的那本书。
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText("某人 · TXT"))
        waitForText("某人 · TXT")
        compose.onAllNodesWithContentDescription("操作")[0].performScrollTo().performClick()
        waitForText("详情")
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        waitForText("累计阅读")
    }
}

class ShelfProgressLabelTest {
    private fun entry(chapters: Int, at: Int?) = com.xycs.lingyuereader.data.BookEntry(
        id = "book",
        title = "测试书",
        author = null,
        format = com.xycs.lingyuereader.data.BookFormat.TXT,
        sources = emptyList(),
        addedAt = 0,
        openedAt = 0,
        chapterCount = chapters,
        charCount = 100,
        progress = at?.let { com.xycs.lingyuereader.data.ReadingProgress(it, "章节", 0, 0) },
    )

    @Test
    fun progressLabelUsesChapterPositionAndClampsBadIndexes() {
        assertEquals("未读 · 10 章", progressLabel(entry(10, null)))
        assertEquals("读到第 1/10 章", progressLabel(entry(10, 0)))
        assertEquals("读到第 1/10 章", progressLabel(entry(10, -3)))
        assertEquals("读到第 10/10 章", progressLabel(entry(10, 99)))
        assertEquals("读到第 10/10 章", progressLabel(entry(10, Int.MAX_VALUE)))
        assertEquals("继续阅读", progressLabel(entry(0, 0)))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ListenUiTest {
    private val seed = object : ExternalResource() {
        override fun before() = seedBook("SCROLL")
        override fun after() = com.xycs.lingyuereader.tts.ReadAloud.resetForTest()
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun listenFromMenuShowsControlBarAndStops() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("第1段")
        // 不是全屏时系统状态栏里有时间和电量，页脚不再重复。
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("听书").performClick()
        waitForText("正在听书")
        compose.onNodeWithContentDescription("停止听书").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在听书").fetchSemanticsNodes().isEmpty() }
    }
}
