package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
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

/** 一本只有一段“银行”的书：长按哪里都落在这个词上。 */
private fun seedDictBook() {
    val files = context.filesDir
    File(files, "books").deleteRecursively()
    File(files, "dicts").deleteRecursively()
    val dir = File(files, "books/b1").apply { mkdirs() }
    File(dir, "s0.txt").writeText("第一章 开端\n银行\n")
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
        waitForText("银行")
        compose.onAllNodesWithText("银行", substring = true)[0].performTouchInput {
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
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("词典…")
        compose.onNodeWithText("词典…").performClick()
        waitForText("3004 个词条")
        compose.onNodeWithText("删除").performClick()
        waitForText("删除词典")
        compose.onAllNodesWithText("删除")[1].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("3004 个词条").fetchSemanticsNodes().isEmpty() }
        assertTrue(File(context.filesDir, "dicts").listFiles()!!.none { it.isDirectory && it.name != "builtin" })
    }
}
