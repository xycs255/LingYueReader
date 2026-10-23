package com.xycs.lingyuereader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
    val longChapter = (1..40).joinToString("\n") { "　　第${it}段，田槐是天亮前回来的，裤腿湿到膝盖，鞋底糊着一层黄泥，进了打谷场先找水喝。" }
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
        // 状态栏显示“1/2 · 1/N 页”
        waitForText("· 1/")
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitForText("· 2/")
        compose.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        waitForText("· 3/")
        compose.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        waitForText("· 2/")
    }

    @Test
    fun catalogJumpLandsOnChapterInPageMode() {
        openBook()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("目录").performClick()
        waitForText("目录 2")
        compose.onAllNodesWithText("第二章 路上")[0].performClick()
        waitForText("正文二。")
        waitForText("2/2 · 1/1 页")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
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
        compose.onAllNodesWithText("第1段", substring = true)[0].performTouchInput { longClick() }
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
        compose.onNodeWithContentDescription("搜索书架").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("某人")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("测试书").fetchSemanticsNodes().isEmpty() }
        waitForText("乙书")
    }

    @Test
    fun listViewAndDetailPage() {
        waitForText("测试书")
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("列表显示")
        compose.onNodeWithText("列表显示").performClick()
        // 列表里有作者一栏
        waitForText("某人 · TXT")
        compose.onAllNodesWithContentDescription("操作")[0].performClick()
        waitForText("详情")
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        waitForText("累计阅读")
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
        // 状态行显示时间（HH:mm）
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("clock") { n ->
                n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
                    ?.any { t -> Regex("""^\d{2}:\d{2}$""").matches(t.text) } == true
            }).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("听书").performClick()
        waitForText("正在听书")
        compose.onNodeWithContentDescription("停止听书").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在听书").fetchSemanticsNodes().isEmpty() }
    }
}
