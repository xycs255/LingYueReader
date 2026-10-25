package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteCardRenderTest {
    @Test
    fun cardGrowsWithTheQuoteAndUsesTheStyleColors() {
        val short = renderQuoteCard(QuoteCardContent("年龄不是问题。", "庆余年", "猫腻", "第八章"), CardStyle.NIGHT)
        val long = renderQuoteCard(QuoteCardContent("年龄不是问题。".repeat(40), "庆余年", null, null), CardStyle.PAPER)
        assertEquals(1080, short.width)
        assertTrue(long.height > short.height)
        assertEquals(CardStyle.NIGHT.background, short.getPixel(4, 4))
        assertEquals(CardStyle.PAPER.background, long.getPixel(4, 4))
        // 超长书摘截断，卡片不会无限长。
        val huge = renderQuoteCard(QuoteCardContent("长".repeat(5000), "书", null, null), CardStyle.PAPER)
        assertTrue(huge.height < 6000)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteCardUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n　　银行门口排着长队，挑担的、推车的。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "某人").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 20)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putBoolean("breakReminder", false).commit()
            File(context.cacheDir, "cards").deleteRecursively()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun selectTextMakeACardAndShareIt() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("银行门口")
        val body = compose.onAllNodesWithText("银行门口", substring = true)[0].fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { down(Offset(body.left + 40.dp.toPx(), body.center.y)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput {
            moveTo(Offset(body.right - 4f, body.center.y))
            up()
        }
        waitForText("卡片")
        compose.onNodeWithText("卡片").performClick()
        waitForText("书摘卡片")
        compose.onNodeWithContentDescription("书摘卡片预览").assertExists()
        compose.onNodeWithText("墨夜").performClick()
        compose.onNodeWithText("分享").performScrollTo().performClick()
        compose.waitUntil(5_000) { File(context.cacheDir, "cards/书摘.png").isFile }
    }

    @Test
    fun sharingACardThatCannotBeWrittenSaysSoInsteadOfCrashing() {
        // 放卡片的目录被一个同名文件占着：图片写不出来。
        File(context.cacheDir, "cards").writeText("占位")
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("银行门口")
        val body = compose.onAllNodesWithText("银行门口", substring = true)[0].fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { down(Offset(body.left + 40.dp.toPx(), body.center.y)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput {
            moveTo(Offset(body.right - 4f, body.center.y))
            up()
        }
        waitForText("卡片")
        compose.onNodeWithText("卡片").performClick()
        waitForText("书摘卡片")
        compose.onNodeWithText("分享").performScrollTo().performClick()
        waitForText("分享失败")
        assertTrue(File(context.cacheDir, "cards").isFile)
    }
}
