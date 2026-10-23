package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.dict.Dictionaries
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

private val context: Context get() = ApplicationProvider.getApplicationContext()

/** 一本只有一短段的书，开头是“银行”。 */
private fun seedDictBook() {
    val files = context.filesDir
    File(files, "books").deleteRecursively()
    File(files, "dicts").deleteRecursively()
    val dir = File(files, "books/b1").apply { mkdirs() }
    File(dir, "s0.txt").writeText("第一章 开端\n银行门口排着长队\n")
    val entry = JSONObject()
        .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
        .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 2)
        .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
    File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
    context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putString("pageMode", "SCROLL").commit()
}

/** 把测试用的 MDict 词典复制成文件，用 file:// 导入。 */
private fun importFixture(vararg names: String): Dictionaries.ImportResult {
    val picked = names.map { name ->
        val f = File(context.cacheDir, name)
        File("src/test/resources/mdict/$name").copyTo(f, overwrite = true)
        Library.Picked(Uri.fromFile(f), name)
    }
    return Dictionaries(context).import(picked) { File(it.uri.path!!).inputStream() }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
// 选词要用到真实的文字排版。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictionaryUiTest {
    private val seed = object : ExternalResource() {
        override fun before() = seedDictBook()
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun longPressWord() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("银行门口")
        compose.onAllNodesWithText("银行门口", substring = true)[0].performTouchInput {
            // 按在“银”上（这一段太短，不加段首缩进）。
            longClick(Offset(8.dp.toPx(), centerY))
        }
        waitForText("查词")
    }

    @Test
    fun longPressLooksUpTheWordInTheBuiltinDictionary() {
        longPressWord()
        waitForText("yín háng")
        waitForText("bank")
        // 能调整选区：结尾少一个字就成了“银”。
        compose.onNodeWithContentDescription("选区结尾少一个字").performClick()
        waitForText("部首")
        // 从面板进词典管理。
        compose.onNodeWithText("管理词典 ›").performScrollTo().performClick()
        waitForText("基础字典与词典")
    }

    @Test
    fun dragAfterLongPressSelectsFreelyAndHandlesAdjust() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("银行门口")
        val body = compose.onAllNodesWithText("银行门口", substring = true)[0]
        dragSelect(body)
        // 松手后出现操作条和两端把手，不直接弹查词面板。
        waitForText("划线·批注")
        compose.onNodeWithContentDescription("选区起点").assertExists()
        // 操作条贴着选区（上方或下方），而不是沉在屏幕底部。
        val text = body.fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithText("查词").fetchSemanticsNode().boundsInRoot
        val px = context.resources.displayMetrics.density
        val gap = if (bar.bottom <= text.top) text.top - bar.bottom else bar.top - text.bottom
        assertTrue("$text / $bar", gap in 0f..60 * px)
        compose.onNodeWithText("复制").performClick()
        assertEquals("银行门口排着长队", clipboard())

        // 再选一次，把终点把手往左拖，选区变短。
        dragSelect(body)
        waitForText("划线·批注")
        compose.onNodeWithContentDescription("选区终点").performTouchInput {
            down(center)
            moveBy(Offset(-40.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("复制").performClick()
        val shorter = clipboard()
        assertTrue(shorter, shorter.startsWith("银行") && shorter.length in 2..7)
    }

    @Test
    fun selectingTextHidesTheMenuSoTheToolbarIsVisible() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("银行门口")
        compose.onRoot().performTouchInput { click(Offset(centerX, bottom * 0.6f)) }
        waitForText("目录")
        dragSelect(compose.onAllNodesWithText("银行门口", substring = true)[0])
        waitForText("划线·批注")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("目录").fetchSemanticsNodes().isEmpty() }
    }

    /** 按住开头的字，等过长按时间，再拖到这一段末尾松手。 */
    private fun dragSelect(body: SemanticsNodeInteraction) {
        body.performTouchInput { down(Offset(8.dp.toPx(), centerY)) }
        compose.mainClock.advanceTimeBy(1_000)
        body.performTouchInput {
            moveTo(Offset(right - 2f, centerY))
            up()
        }
    }

    private fun clipboard(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test
    fun lookedUpWordsGoIntoTheVocabularyBook() {
        java.io.File(context.filesDir, "vocabulary.json").delete()
        longPressWord()
        waitForText("bank")
        compose.onNodeWithText("☆ 收入生词本").performClick()
        waitForText("★ 已在生词本")
        val saved = java.io.File(context.filesDir, "vocabulary.json").readText()
        assertTrue(saved, saved.contains("银行") && saved.contains("银行门口排着长队") && saved.contains("测试书"))
    }

    @Test
    fun importedDictionaryResultsAppearInTheLookup() {
        val result = importFixture("v2_zlib_utf8.mdx", "v2_zlib_utf8.mdd")
        assertEquals(listOf("测试词典"), result.added)
        longPressWord()
        waitForText("测试词典")
    }

    @Test
    fun dictionariesScreenListsAndRemovesImported() {
        waitForText("测试书")
        importFixture("v2_zlib_utf8.mdx")
        compose.onNodeWithContentDescription("我的").performClick()
        waitForText("导入 1 本")
        compose.onNodeWithText("词典").performClick()
        waitForText("3004 个词条")
        compose.onNodeWithText("删除").performClick()
        waitForText("删除词典")
        compose.onAllNodesWithText("删除")[1].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("3004 个词条").fetchSemanticsNodes().isEmpty() }
        assertTrue(File(context.filesDir, "dicts").listFiles()!!.none { it.isDirectory && it.name != "builtin" })
    }
}
