package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate

/**
 * 写作编辑器的自动排版：“更多 → 自动排版”先出预览（按类计数，可以勾掉一类），排好作为一步编辑写进正文
 * （存一个历史版本、不算码字数、撤销一下整步回去）；定稿的章不能排；输入时自动排版；全书排版跳过定稿的章。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingFormatUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val store get() = WorkStore(writingDir)
    private var workId = ""
    private var chapterId = ""
    private var lockedId = ""
    private var emptyId = ""
    private val text = "林月说:\"老周,你来了...\"\n沈默没说话。"

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val s = WorkStore(writingDir)
            val w = s.create("渡口")
            s.renameChapter(w.id, w.chapters[0].id, "第一章 渡口")
            s.saveText(w.id, w.chapters[0].id, text, count = false)
            val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章 杜娘")!!
            s.saveText(w.id, two.id, "杜娘说:\"船不来了.\"", count = false)
            s.setStatus(w.id, two.id, ChapterStatus.FINAL)
            val (_, three) = s.addChapter(w.id, w.volumes[0].id, "第三章 夜")!!
            workId = w.id
            chapterId = w.chapters[0].id
            lockedId = two.id
            emptyId = three.id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun shown(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(t: String) = waitFor { shown(t) }

    /** 编辑框里现在的正文。 */
    private fun body(): String =
        compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun open(chapter: String) {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapter)) }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("正文")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun menu(item: String) {
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText(item)
        compose.onNodeWithText(item).performScrollTo().performClick()
    }

    private fun today() = store.stats().on(LocalDate.now())

    @Test
    fun previewCountsUncheckApplyAndUndoInOneStep() {
        open(chapterId)
        waitFor { body() == text }
        // 先接着写一句（照常算码字数），再排版。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("正文").performTextInput("杜娘也来了。")
        val typed = "${text}杜娘也来了。"
        waitFor { store.text(workId, chapterId) == typed }
        val counted = today()
        assertEquals(6, counted)
        val versions = store.history(workId, chapterId).size

        menu("自动排版")
        waitForText("共 5 处：标点 2、引号 2、省略号 1")
        // 勾掉引号这一类。
        compose.onNodeWithText("引号 2").performClick()
        waitForText("共 3 处：标点 2、省略号 1")
        compose.onNodeWithText("排版 3 处").performClick()

        val formatted = "林月说：\"老周，你来了……\"\n沈默没说话。杜娘也来了。"
        waitFor { body() == formatted }
        waitFor { store.text(workId, chapterId) == formatted }
        // 写进正文前存了一个历史版本；排版改的不算码字数。
        assertEquals(versions + 1, store.history(workId, chapterId).size)
        assertEquals(typed, store.snapshotText(workId, chapterId, store.history(workId, chapterId).first().at))
        assertEquals(counted, today())

        // 撤销一下整步回到排版前，也不算码字数。
        compose.onNodeWithContentDescription("撤销").performClick()
        waitFor { body() == typed }
        waitFor { store.text(workId, chapterId) == typed }
        assertEquals(counted, today())
        // 重做又回到排好的。
        compose.onNodeWithContentDescription("重做").performClick()
        waitFor { store.text(workId, chapterId) == formatted }
        assertEquals(counted, today())
    }

    @Test
    fun finalChapterCannotBeFormatted() {
        open(lockedId)
        waitFor { body() == "杜娘说:\"船不来了.\"" }
        menu("自动排版")
        waitForText("要排版先解锁")
        assertFalse(shown("排版设置"))
        assertEquals("杜娘说:\"船不来了.\"", store.text(workId, lockedId))
    }

    @Test
    fun typingFormatsAsYouGoAndUndoTakesTheConversionBackFirst() {
        open(emptyId)
        val field = compose.onNodeWithContentDescription("正文")
        field.performTextInput("他说...")
        waitFor { body() == "他说……" }
        // 第一次撤销撤的是自动换的那一下，第二次才撤打的字。
        compose.onNodeWithContentDescription("撤销").performClick()
        waitFor { body() == "他说..." }
        compose.onNodeWithContentDescription("撤销").performClick()
        waitFor { body() == "" }

        field.performTextInput("林月")
        field.performTextInput(",")
        waitFor { body() == "林月，" }
        field.performTextInput("\"")
        waitFor { body() == "林月，“" }
        field.performTextInput("\"")
        waitFor { body() == "林月，“”" }
        // 快捷标点照旧。
        compose.onNodeWithContentDescription("插入……").performClick()
        waitFor { body() == "林月，“”……" }

        // 排版设置里关掉“输入时自动排版”：照打。
        compose.runOnUiThread { vm.open(Screen.WorkFormatSettings(workId)) }
        waitForText("输入时自动排版")
        compose.onNodeWithText("输入时自动排版").performClick()
        waitFor { !store.assist(workId).format.onInput }
        compose.runOnUiThread { vm.back() }
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("正文").performTextInput("好...")
        waitFor { body() == "林月，“”……好..." }
        // 等一会儿也不换。
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
        assertEquals("林月，“”……好...", body())
    }

    @Test
    fun wholeBookSkipsFinalChapters() {
        val counted = today()
        compose.runOnUiThread { vm.open(Screen.WorkFormat(workId)) }
        waitForText("定稿的 1 章不排：第二章 杜娘")
        waitForText("排版 1 章")
        compose.onNodeWithText("排版 1 章").performScrollTo().performClick()
        waitFor { store.text(workId, chapterId) == "林月说：“老周，你来了……”\n沈默没说话。" }
        waitForText("排好了")
        waitForText("改了 1 章、5 处")
        assertEquals("杜娘说:\"船不来了.\"", store.text(workId, lockedId))
        assertEquals(counted, today())
    }
}
