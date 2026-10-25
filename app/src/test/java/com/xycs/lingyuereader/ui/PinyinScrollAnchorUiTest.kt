package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.BookParser
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

/** 滚动模式开着注音：等字表读好再恢复位置，读到的那一行不因为行高变大而往前漂。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinyinScrollAnchorUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val marker = "灯下读信"
    private var targetBlock = -1
    private var targetChar = -1

    private val seed = object : ExternalResource() {
        override fun before() {
            val root = File(context.filesDir, "books")
            root.deleteRecursively()
            val dir = File(root, "pinyin-book").apply { mkdirs() }
            val source = File(dir, "s0.txt")
            val paragraphs = (1..30).map { n ->
                if (n == 20) {
                    "　　第二十段。" + "顾长风推开窗，院子里的雪还没有化。".repeat(14) + "${marker}，他把信纸折好收进怀里。" + "远处传来更鼓声。".repeat(6)
                } else {
                    "　　第${n}段。" + "屋檐下的冰凌一根一根垂下来。".repeat(10)
                }
            }
            source.writeText("第一章 雪夜\n${paragraphs.joinToString("\n")}")
            val content = BookParser.parse(listOf(BookParser.SourceBytes("雪夜.txt", source)))
            val chapter = content.chapters[0]
            targetBlock = chapter.blocks.indexOfFirst { it.displayText?.contains(marker) == true }
            targetChar = chapter.blocks[targetBlock].displayText!!.indexOf(marker)
            assertTrue(targetBlock > 0 && targetChar > 100)
            val anchor = TextAnchors.capture(chapter, targetBlock, targetChar)
            val progress = JSONObject().put("chapter", 0).put("title", chapter.title)
                .put("item", targetBlock + 1).put("offset", 0).put("char", targetChar)
                .put("anchorText", anchor.text).put("anchorOffset", anchor.offset)
            val entry = JSONObject().put("id", "pinyin-book").put("title", "雪夜书简")
                .put("author", "").put("format", "TXT").put("addedAt", 1L).put("openedAt", 0L)
                .put("chapterCount", content.chapters.size).put("charCount", content.charCount)
                .put("sources", JSONArray().put(JSONObject().put("name", "雪夜.txt").put("uri", "").put("file", "s0.txt")))
                .put("progress", progress)
            File(root, "index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "SCROLL").putBoolean("pinyin", true).putBoolean("breakReminder", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun savedProgress(): JSONObject = JSONArray(File(context.filesDir, "books/index.json").readText())
        .getJSONObject(0).getJSONObject("progress")

    @Test
    fun reopeningWithPinyinKeepsTheSameLine() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("雪夜书简").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("雪夜书简")[0].performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty() }
        // 等注音排好、位置报过几次。
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val saved = savedProgress()
        assertEquals(targetBlock + 1, saved.getInt("item"))
        // 顶上那一行的行首：在目标字之前，但不超过一行（这一宽度一行不到 25 个字）。
        val char = saved.getInt("char")
        assertTrue("存下的字位 $char，目标 $targetChar", char in (targetChar - 25)..targetChar)
    }
}
