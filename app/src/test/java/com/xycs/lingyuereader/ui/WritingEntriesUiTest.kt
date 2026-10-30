package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 写作侧入口精简以后的盘点：删掉的每一处入口，功能都还有一个自然的去处，点得到、进对地方。
 * 对照 docs/design.md 的“入口与叫法”：作品页“⋯”、更多功能、进度卡、规划树的总纲和伏笔记号、故事账本的“伏笔”标签、
 * 写作 AI 首屏和高级设置、编辑器的“⋯”和“查”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingEntriesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            // AI 填的是假地址：这里只看入口，不发请求。
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:9/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜", "沈默")
            store.saveText(w.id, w.chapters[0].id, "林月推开门，风雪灌进来。", count = false)
            val (_, two) = store.addChapter(w.id, w.volumes[0].id, "第二章 渡口")!!
            store.saveText(w.id, two.id, "老周蹲在船头抽烟。", count = false)
            // 设定包里有一份伏笔表（还没导入账本的一条）；账本里另有一条第 1 章埋下、还没收的。
            store.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋下 | 回收 | 状态 |\n| --- | --- | --- | --- |\n| 半块玉佩 | 第1章 | 第2章 | |\n")
            store.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "老周藏起的半张船票", subject = "老周", from = 1, payoff = 6))), "合成样本")
            workId = w.id
            chapterId = w.chapters[0].id
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
    private fun has(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun top() = vm.stack.value.last()
    /** 最上面那一页的列表（底下还叠着写作首页的列表）。 */
    private fun list(): androidx.compose.ui.test.SemanticsNodeInteraction {
        val id = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
            .last { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 100f }.id
        return compose.onNode(androidx.compose.ui.test.SemanticsMatcher("最上面那一页的列表") { it.id == id })
    }
    private fun back() = compose.runOnUiThread { vm.back() }

    private fun openWork() {
        compose.runOnUiThread { vm.open(Screen.Work(workId)) }
        waitForText("定稿 0 / 共 2 章")
        // 等页面进场、量完再点：换页动画还在量的时候注入点击，Robolectric 偶尔报“在测量中又调了测量”。
        compose.waitForIdle()
    }

    /** 作品页“⋯”：分作品 / 导出 / 删除三节；当书预览、整理没整理的章节、两项排版不在这里，各有别处。 */
    @Test
    fun workPageMenuIsTrimmedAndEveryRemovedItemStillHasAHome() {
        openWork()
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("删除作品")
        listOf("新建一卷", "作品信息", "补写章节摘要", "排版…", "导出…", "删除作品").forEach { assertTrue(it, has(it)) }
        listOf("当书预览", "整理没整理的章节", "全书排版", "排版设置").forEach { assertFalse("$it 不该在作品页“⋯”里", has(it)) }
        // 导出…：点了再选格式（菜单不用滚就看得到最后的“删除作品”）。
        compose.onNodeWithText("导出…").performClick()
        waitForText("导出《长夜》")
        listOf("TXT", "EPUB", "Markdown").forEach { assertTrue(it, has(it)) }
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("排版…")
        // 排版…：进全书排版页，右上“设置”是排版设置（页标题和入口同名）。
        compose.onNodeWithText("排版…").performClick()
        waitFor { top() == Screen.WorkFormat(workId) }
        compose.onNodeWithText("设置").performClick()
        waitFor { top() == Screen.WorkFormatSettings(workId) }
        waitForText("排版设置")
        back()
        back()
        waitFor { top() == Screen.Work(workId) }

        // 当书预览：作品页“更多功能”在本页展开。
        list().performScrollToNode(hasText("更多功能"))
        compose.onNodeWithText("更多功能").performClick()
        waitForText("当书预览")
        assertFalse("伏笔并进了故事账本", has("伏笔"))

        // 整理没整理的章节：进度卡上的“N 章待整理”能点，直接问要不要整理。
        list().performScrollToNode(hasText("2 章待整理 ›"))
        compose.onNodeWithText("2 章待整理 ›").performClick()
        waitForText("有 2 章还没整理")
        compose.onNodeWithText("取消").performClick()
    }

    /** 规划树的“总纲”打开总纲那份文档（没有就新建一份）；伏笔记号去账本的“伏笔”标签。 */
    @Test
    fun outlineRowOpensTheOutlineDocAndForeshadowMarkOpensThePromisesTab() {
        openWork()
        list().performScrollToNode(hasText("总纲 · 长夜"))
        compose.onNodeWithText("总纲 · 长夜").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.pack(workId).any { it.kind == PackKind.OUTLINE } }
        val outline = store.pack(workId).single { it.kind == PackKind.OUTLINE }
        waitFor { top() == Screen.PackDoc(workId, outline.id) }
        back()
        waitFor { top() == Screen.Work(workId) }
        // 再点一次：打开的还是那一份，不再新建。
        list().performScrollToNode(hasText("总纲 · 长夜"))
        compose.onNodeWithText("总纲 · 长夜").performClick()
        waitFor { top() == Screen.PackDoc(workId, outline.id) }
        assertEquals(1, store.pack(workId).count { it.kind == PackKind.OUTLINE })
        back()

        waitFor { top() == Screen.Work(workId) }
        list().performScrollToNode(androidx.compose.ui.test.hasContentDescription("去故事账本看", substring = true))
        compose.onAllNodesWithContentDescription("去故事账本看", substring = true)[0].performClick()
        waitFor { (top() as? Screen.WorkLedger)?.promises == true }
        waitForText("老周藏起的半张船票")
    }

    /** 原来的“伏笔”页：作品页“故事账本”卡片 → “伏笔”标签，该回收的、还没回收的、伏笔表里还没导入的、来源文档都在。 */
    @Test
    fun foreshadowsLiveInTheLedgerPromisesTab() {
        openWork()
        compose.onNodeWithText("故事账本").performClick()
        waitFor { top() is Screen.WorkLedger }
        waitForText("人物")
        compose.onNodeWithText("伏笔").performClick()
        // 写到了第 2 章：伏笔表里计划第 2 章收的那条该回收了；账本里那条还没到。
        waitForText("该回收了")
        waitForText("半块玉佩")
        waitForText("还没回收 · 拖动计划收章")
        waitForText("老周藏起的半张船票")
        list().performScrollToNode(hasText("编辑伏笔表"))
        compose.onNodeWithText("编辑伏笔表").performClick()
        waitFor { top() is Screen.PackDoc }
    }

    /** 写作 AI：AI 记录在首屏最底下；设定包、写作范本不在高级设置里，分别从作品页、编辑器“⋯”进。 */
    @Test
    fun aiLogIsOnTheFirstScreenAndContentEntriesLeftTheSettings() {
        openWork()
        compose.onNodeWithText("写作 AI").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { top() == Screen.WorkAi(workId) }
        compose.waitForIdle()
        compose.onNodeWithText("AI 记录").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitFor { top() == Screen.WorkAiLog(workId) }
        back()
        waitFor { top() == Screen.WorkAi(workId) }
        compose.waitForIdle()
        compose.onNodeWithText("高级设置").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("知识库上限")
        compose.waitForIdle()
        compose.onNodeWithText("AI 用量").performScrollTo()
        assertFalse(has("设定包"))
        assertFalse(has("写作范本"))
        back()
        back()
        waitFor { top() == Screen.Work(workId) }
        compose.waitForIdle()
        compose.onNodeWithText("设定包").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { top() == Screen.WorkPack(workId) }
    }

    /** 编辑器“⋯”：没有三道审稿（在“查”里），有写作范本…、设定包、写作 AI 设置（这部书的）。 */
    @Test
    fun editorOverflowAndTheCheckTab() {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("写作 AI 设置")
        listOf("整理进设定包", "写作范本…", "设定包", "写作 AI 设置").forEach { assertTrue(it, has(it)) }
        assertFalse(has("三道审稿"))
        compose.onNodeWithText("写作范本…").performClick()
        waitFor { top() == Screen.WritingReference(workId, chapterId) }
        back()
        waitFor { top() is Screen.Editor }
        compose.onNodeWithText("查").performClick()
        compose.onNodeWithText("三道审稿").performClick()
        waitFor { top() == Screen.ChapterReview(workId, chapterId) }
    }
}
