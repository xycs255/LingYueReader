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
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
