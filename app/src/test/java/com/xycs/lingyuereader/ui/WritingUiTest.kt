package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onLast
import androidx.lifecycle.ViewModelProvider
import com.xycs.lingyuereader.AppViewModel
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
            if (name.methodName.startsWith("preview") || name.methodName.startsWith("chapters") || name.methodName.startsWith("editorTools")) {
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

    private fun chapterRow(title: String) = compose.onNode(
        hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
    )

    private fun scrollToChapter(title: String) {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("non-empty scroll viewport") {
            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }).performScrollToNode(
            hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        )
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
        waitForText("作品进度")
        scrollToChapter("第1章")
        chapterRow("第1章").performClick()

        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("正文").performTextInput("他推开门，风雪灌进来。")
        // 停笔后自动保存。
        waitForText("11 字 · 已保存")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("标点").performClick()
        compose.onNodeWithContentDescription("插入「」").performClick()
        waitForText("13 字")

        val store = WorkStore(writingDir)
        compose.waitUntil(10_000) { store.list().singleOrNull()?.words == 13 }
        val work = store.list().single()
        assertEquals("长夜", work.title)
        assertEquals("他推开门，风雪灌进来。「」", store.text(work.id, work.chapters[0].id))

        // 返回作品页，字数跟着变；离开时存了一个历史版本。
        compose.onNodeWithContentDescription("返回").performClick()
        // 作品页顶上多了进度环和色块带，规划树里的这一章可能在屏幕下面：先滚到它。
        waitForText("作品进度")
        scrollToChapter("第1章")
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
        waitForText("作品进度")
        scrollToChapter("第二章 天亮")
        chapterRow("第二章 天亮").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("预览本章").performClick()
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
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("预览本章").performClick()
        compose.waitUntil(20_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("鸡叫了三遍。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, JSONArray(indexFile.readText()).length())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h700dp")
    fun editorToolsStayReachableOnASmallPhone() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val work = WorkStore(writingDir).get(seededWork!!)!!
        compose.runOnUiThread { vm.open(com.xycs.lingyuereader.Screen.Editor(work.id, work.chapters[0].id)) }
        // 正文使用 TextFieldState；等待编辑框就绪，不依赖文本是否合并进读屏树。
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("查").performClick()
        waitForText("三道审稿")
        compose.onNodeWithText("整理").performClick()
        waitForText("历史版本")
        compose.onNodeWithText("更多").performClick()
        waitForText("预览本章")
        // 不常用的在右上“⋯”里。
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("从范本找写法")
        compose.onNodeWithText("从范本找写法").performClick()
        waitForText("写作范本")
    }

    @Test
    fun chaptersAddedDeletedAndMovedShowUpInTheRightPlace() {
        val store = WorkStore(writingDir)
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val w = store.get(seededWork!!)!!
        openWritingTab()
        waitForText("长夜")
        compose.onNodeWithText("长夜").performClick()
        waitForText("作品进度")
        scrollToChapter("第二章 天亮")
        // 章节那一行（能长按的那个；“继续写”那行的右边也写着章名）。
        fun row(title: String) = chapterRow(title)
        fun top(title: String) = row(title).fetchSemanticsNode().boundsInRoot.top
        fun shown(title: String) = compose.onAllNodes(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNodes().isNotEmpty()

        // 加一章：接在这一卷最后（章节一格一格懒加载，新的那格淡入）。
        compose.runOnUiThread { vm.writing.addChapter(w.id, w.volumes[0].id) }
        compose.waitUntil(10_000) { store.get(w.id)!!.chapters.size == 3 }
        // 作品页上面的入口多，新的一格可能在屏幕下面：滚过去再看。
        compose.waitUntil(10_000) {
            runCatching { scrollToChapter("第3章") }.isSuccess
        }
        compose.waitUntil(10_000) { shown("第3章") }
        assertTrue(top("第一章 雪夜") < top("第二章 天亮") && top("第二章 天亮") < top("第3章"))

        // 长按一章弹出菜单，删除放在最后，还要再确认。
        row("第二章 天亮").performTouchInput { longClick() }
        waitForText("细纲卡")
        compose.onNodeWithText("删除").performClick()
        waitForText("会从作品里删除")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("删除").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(10_000) { store.get(w.id)!!.chapters.size == 2 }
        // 删掉的那格淡出后不留空位，其余两章还在，先后不乱。
        compose.waitUntil(10_000) { !shown("第二章 天亮") }
        assertTrue(top("第一章 雪夜") < top("第3章"))

        // 上移：第 3 章挪到第一章前面，列表跟着换位。
        row("第3章").performTouchInput { longClick() }
        waitForText("上移")
        compose.onNodeWithText("上移").performClick()
        compose.waitUntil(10_000) { store.get(w.id)!!.chapters.first().title == "第3章" }
        compose.waitUntil(10_000) { top("第3章") < top("第一章 雪夜") }
        assertEquals(listOf("第3章", "第一章 雪夜"), store.get(w.id)!!.chapters.map { it.title })
    }
}
