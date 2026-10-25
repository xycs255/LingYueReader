package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.WORKS_GROUP
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val name = TestName()
    private var seededWork: String? = null

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            if (name.methodName.startsWith("preview")) {
                val store = WorkStore(writingDir)
                val w = store.create("长夜", "某某")
                store.renameChapter(w.id, w.chapters[0].id, "第一章 雪夜")
                store.saveText(w.id, w.chapters[0].id, "他推开门。\n风雪灌进来。")
                val (_, c2) = store.addChapter(w.id, w.volumes[0].id, "第二章 天亮")!!
                store.saveText(w.id, c2.id, "天终于亮了。")
                seededWork = w.id
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    private fun waitForText(text: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openWritingTab() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("写作").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("写作").performClick()
    }

    @Test
    fun createAWorkWriteAChapterAndSeeItSaved() {
        openWritingTab()
        waitForText("还没有作品")
        compose.onNodeWithText("新建作品").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("书名")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("长夜")
        compose.onNodeWithText("新建").performClick()
        // 进了作品页：一卷“正文”，一个空的第 1 章。
        waitForText("新的一章")
        compose.onAllNodesWithText("第1章")[0].performClick()

        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("正文").performTextInput("他推开门，风雪灌进来。")
        // 停笔后自动保存。
        waitForText("11 字 · 已保存")
        compose.onNodeWithContentDescription("插入「」").performClick()
        waitForText("13 字")

        val store = WorkStore(writingDir)
        compose.waitUntil(10_000) { store.list().singleOrNull()?.words == 13 }
        val work = store.list().single()
        assertEquals("长夜", work.title)
        assertEquals("他推开门，风雪灌进来。「」", store.text(work.id, work.chapters[0].id))

        // 返回作品页，字数跟着变；离开时存了一个历史版本。
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("13 字")
        compose.waitUntil(10_000) { store.history(work.id, work.chapters[0].id).isNotEmpty() }
        // 今天的码字数。
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("作品")
        waitForText("1 章 · 13 字")
    }

    @Test
    fun previewPutsTheWorkOnTheShelfAndOpensTheChapter() {
        val store = WorkStore(writingDir)
        val w = store.get(seededWork!!)!!
        openWritingTab()
        waitForText("长夜")
        compose.onNodeWithText("长夜").performClick()
        waitForText("第二章 天亮")
        compose.onAllNodesWithText("第二章 天亮")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("预览本章").performScrollTo().performClick()
        // 阅读页打开到第二章（编辑器里也有这句，等编辑框没了再找）。
        val indexFile = File(context.filesDir, "books/index.json")
        // 编辑框的光标一直在闪，界面不会“空闲”，测试框架就不替主线程处理消息：这里手动处理，预览的协程才能往下走。
        compose.waitUntil(20_000) { ShadowLooper.idleMainLooper(); JSONArray(indexFile.readText()).length() == 1 }
        compose.waitUntil(20_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("天终于亮了。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        val index = JSONArray(indexFile.readText())
        assertEquals(1, index.length())
        val entry = index.getJSONObject(0)
        assertEquals("长夜", entry.getString("title"))
        assertEquals(WORKS_GROUP, entry.getJSONArray("groups").getString(0))
        assertEquals(entry.getString("id"), store.get(w.id)!!.previewBookId)
        assertTrue(store.previewFile(w.id).isFile)

        // 回去接着写，再预览：刷新书架上的同一本书，不另加一本。
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("正文").performTextInput("鸡叫了三遍。")
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("预览本章").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("鸡叫了三遍。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, JSONArray(indexFile.readText()).length())
    }
}
