package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.MarkColor
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
class CrossSelectionUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n" + (1..30).joinToString("\n") { "　　第${it}段，城门口排着长队。" } + "\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 400)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "SCROLL").putBoolean("breakReminder", false).commit()
        }
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

    private fun clipboard(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip!!.getItemAt(0).text.toString()
    }

    /** 在“第 from 段”开头长按，拖到 [to]（屏幕坐标）松手。 */
    private fun dragFrom(from: Int, holdMillis: Long = 0, to: (Float, Float) -> Offset) {
        val node = compose.onAllNodesWithText("第${from}段", substring = true)[0]
        val start = node.fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(Offset(start.left + 40.dp.toPx(), start.center.y))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput {
            moveTo(to(width.toFloat(), height.toFloat()))
        }
        if (holdMillis > 0) compose.mainClock.advanceTimeBy(holdMillis)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun dragAcrossParagraphsCopiesThemAll() {
        openBook()
        val third = compose.onAllNodesWithText("第3段", substring = true)[0].fetchSemanticsNode().boundsInRoot
        dragFrom(1) { _, _ -> Offset(third.right - 4f, third.center.y) }
        waitForText("批注")
        val copy = compose.onNodeWithText("复制").fetchSemanticsNode().boundsInRoot
        assertTrue("划选操作虽缩小，点击区仍需至少 48dp：${copy.height}", copy.height >= context.resources.displayMetrics.density * 48f - 1f)
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val screenshot = File(appDir, "build/outputs/ui-check/selection-compact.png")
        screenshot.parentFile!!.mkdirs()
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 跨段落时不查词。
        compose.onNodeWithText("查词").assertDoesNotExist()
        compose.onNodeWithText("复制").performClick()
        val copied = clipboard()
        assertTrue(copied, copied.contains("第2段，城门口排着长队。") && copied.lines().size == 3 && copied.endsWith("排着长队。"))
    }

    /** 连着加很多条批注，一条都不能丢：写盘排队，后加的快照总是最后落盘。 */
    @Test
    fun rapidNotesAreAllSaved() {
        openBook()
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { repeat(30) { vm.addNote(0, it % 10, 0, 2, "", MarkColor.YELLOW) } }
        val marks = File(context.filesDir, "books/b1/marks.json")
        compose.waitUntil(10_000) { marks.isFile && JSONObject(marks.readText()).getJSONArray("notes").length() == 30 }
        // 再等一会儿，确认没有更旧的快照晚到把它盖掉。
        Thread.sleep(300)
        assertEquals(marks.readText(), 30, JSONObject(marks.readText()).getJSONArray("notes").length())
    }

    @Test
    fun highlightAcrossParagraphsSavesOneNotePerParagraph() {
        openBook()
        val second = compose.onAllNodesWithText("第2段", substring = true)[0].fetchSemanticsNode().boundsInRoot
        dragFrom(1) { _, _ -> Offset(second.right - 4f, second.center.y) }
        waitForText("批注")
        compose.onNodeWithText("批注").performClick()
        waitForText("写点批注")
        compose.onNodeWithText("完成").performClick()
        // 批注在后台线程写盘，整套测试一起跑、机器忙时会慢一些。
        val marks = File(context.filesDir, "books/b1/marks.json")
        fun saved() = if (marks.isFile) marks.readText() else "（没有 marks.json）"
        runCatching {
            compose.waitUntil(10_000) { marks.isFile && JSONObject(marks.readText()).getJSONArray("notes").length() == 2 }
        }.onFailure { throw AssertionError("两段各一条批注没等到，存下的：${saved()}", it) }
        val notes = JSONObject(marks.readText()).getJSONArray("notes")
        assertEquals(saved(), setOf(0, 1), (0 until notes.length()).map { notes.getJSONObject(it).getInt("block") }.toSet())
    }

    @Test
    fun draggingToTheBottomEdgeScrollsAndKeepsSelecting() {
        openBook()
        compose.mainClock.autoAdvance = false
        dragFrom(1, holdMillis = 3_000) { w, h -> Offset(w / 2, h - 4f) }
        compose.mainClock.autoAdvance = true
        waitForText("批注")
        compose.onNodeWithText("复制").performClick()
        val copied = clipboard()
        // 一开始屏幕上只到十几段，自动滚动后选到了更后面。
        val last = Regex("""第(\d+)段""").findAll(copied).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        assertTrue("last=$last", last >= 20)
        assertTrue(copied.startsWith("第1段") || copied.startsWith("1段") || copied.contains("第1段"))
    }

    /** 段末那一行下方的把手伸出了段落，原来只有上沿按得到，按中下部会把选区取消。 */
    @Test
    fun endHandleUnderTheLastLineCanBeGrabbedLowDown() {
        openBook()
        val third = compose.onAllNodesWithText("第3段", substring = true)[0].fetchSemanticsNode().boundsInRoot
        dragFrom(1) { _, _ -> Offset(third.right - 4f, third.center.y) }
        waitForText("批注")
        val handle = compose.onNodeWithContentDescription("选区终点").fetchSemanticsNode().boundsInRoot
        assertTrue("把手点击区需至少 48dp：${handle.height}", handle.height >= context.resources.displayMetrics.density * 48f - 1f)
        compose.onRoot().performTouchInput {
            // 按在把手的下半部，往上拖到第 2 段。
            down(Offset(handle.center.x, handle.top + 16.dp.toPx()))
            moveBy(Offset(0f, -(third.height + 4.dp.toPx())))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("复制").performClick()
        val copied = clipboard()
        assertTrue(copied, copied.startsWith("第1段") || copied.contains("1段"))
        assertTrue(copied, !copied.contains("第3段"))
    }

    /** 到长按时间前手指已经挪开（横着挪，列表不接手），不算长按。 */
    @Test
    fun movingAwayBeforeTheLongPressTimeoutDoesNotSelect() {
        openBook()
        val node = compose.onAllNodesWithText("第3段", substring = true)[0]
        val start = node.fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(Offset(start.left + 40.dp.toPx(), start.center.y))
            moveBy(Offset(48.dp.toPx(), 0f))
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("批注").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("查词").fetchSemanticsNodes().isEmpty())
    }
}
