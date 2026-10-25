package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VocabularyUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "vocabulary.json")
    private val seed = object : ExternalResource() {
        override fun before() {
            File(context.filesDir, "books").deleteRecursively()
            file.writeText(
                """[{"word":"银行","reading":"yín háng","gloss":"bank","context":"银行门口排着长队。","bookTitle":"测试书","addedAt":2},
                   {"word":"排队","reading":"pái duì","gloss":"to line up","context":"银行门口排着长队。","bookTitle":"测试书","addedAt":1}]""",
            )
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun reviewSchedulesWordsOnTheForgettingCurve() {
        waitForText("书架还是空的")
        compose.onNodeWithText("生词本").performClick()
        waitForText("学习中 2")
        waitForText("yín háng")
        compose.onNodeWithText("复习 2").performClick()
        waitForText("1 / 2")
        compose.onNodeWithText("看释义").performClick()
        compose.onNodeWithText("记得").performClick()
        waitForText("2 / 2")
        // 没记住的这一轮最后再出现一次。
        compose.onNodeWithText("还不熟").performClick()
        waitForText("3 / 3")
        compose.onNodeWithText("还不熟").performClick()
        waitForText("记得 1 个，还不熟 1 个")
        compose.onNodeWithText("返回生词本").performClick()
        // 记得的排到明天，没记住的今天还要复习。
        waitForText("复习 1")
        waitForText("下次复习 明天")
        val words = JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf(0, 1), words.map { it.getInt("interval") }.sorted())
        assertTrue(words.none { it.getBoolean("mastered") })
    }

    @Test
    // 手机大小的屏幕：两张生词卡都在屏幕内。
    @Config(sdk = [36], qualifiers = "w411dp-h891dp")
    fun deletingAWordCanBeUndoneAndPutsItBackUnchanged() {
        // 删第二个词：撤销后要回到第二个，不是挤到最前面。
        val before = JSONArray(file.readText()).getJSONObject(1)
        waitForText("书架还是空的")
        compose.onNodeWithText("生词本").performClick()
        waitForText("学习中 2")
        compose.onAllNodesWithContentDescription("删除")[1].performClick()
        waitForText("已删除“排队”")
        compose.waitUntil(10_000) { JSONArray(file.readText()).length() == 1 }
        waitForText("学习中 1")
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(10_000) { JSONArray(file.readText()).length() == 2 }
        waitForText("学习中 2")
        // 原样放回原来的位置：查过几次、出处、时间都不变。
        val restored = JSONArray(file.readText()).getJSONObject(1)
        assertEquals("排队", restored.getString("word"))
        listOf("reading", "gloss", "context", "bookTitle").forEach { assertEquals(before.getString(it), restored.getString(it)) }
        assertEquals(before.getLong("addedAt"), restored.getLong("addedAt"))
        assertEquals(1, restored.getInt("lookups"))
    }

    @Test
    fun exportingALargeVocabularySharesAFileInsteadOfText() {
        // 几千个词、每个带一段长原句：整份放进 EXTRA_TEXT 会超过跨进程的大小上限。
        file.writeText(
            JSONArray().apply {
                (1..3000).forEach { i ->
                    put(
                        org.json.JSONObject().put("word", "词$i").put("reading", "").put("gloss", "释义$i")
                            .put("context", "林月和沈默在老周的茶馆里坐了一下午，顾长风推门进来。".repeat(6)).put("bookTitle", "测试书").put("addedAt", i.toLong()),
                    )
                }
            }.toString(),
        )
        waitForText("书架还是空的")
        compose.onNodeWithText("生词本").performClick()
        waitForText("学习中 3000")
        compose.onNodeWithContentDescription("导出生词本").performClick()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var chooser: Intent? = null
        compose.waitUntil(10_000) {
            chooser = chooser ?: shadowOf(app).nextStartedActivity
            chooser != null
        }
        val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val exported = File(File(context.cacheDir, "exports"), "生词本.md")
        assertTrue(exported.length() > 1_000_000)
        assertTrue(exported.readText().contains("## 词3000"))
        assertEquals(context.packageName + ".files", uri.authority)
    }
}
