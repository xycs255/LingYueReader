package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
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
 * Issue #17 收尾：设定包的分类格（点了筛选、空的点了新建）和“AI 每次带多少”（点开明细、放不下标红），
 * 细纲卡的彩色小卡片（就地展开改、存盘，AI 拟的点确认后标签消失），以及读屏念的文字。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingPackGridUiTest {
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
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear().commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            workId = w.id
            chapterId = w.chapters[0].id
            store.renameChapter(w.id, chapterId, "第1章 渡口")
            store.saveText(w.id, chapterId, "林月推开门，风雪灌进来。老周蹲在船头抽烟。")
            store.saveCard(w.id, chapterId, ChapterCard(facts = "林月到渡口，得知封航", pov = "林月", hook = "对岸亮起一盏灯",
                beats = "林月到渡口\n老周说封航了", beatsDone = listOf(1), aiOutline = true, aiBeats = true))
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "## 林月\n想要真相。\n## 老周\n守着渡口。")
            store.addPackDoc(w.id, PackKind.CHARACTER, "配角", "## 杜娘\n茶馆老板娘。", always = false)
            store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "对白要短。")
            store.addPackDoc(w.id, PackKind.REFERENCE, "渡口地图说明", "渡口在城南。")
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun shown(text: String, substring: Boolean = true) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) = compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun openPack() {
        compose.runOnUiThread { vm.open(Screen.WorkPack(workId)) }
        waitFor { shown("文档分类") && described("写正文每次带") }
    }

    @Test
    fun aKindCardFiltersTheListAndAnEmptyOneCreatesADoc() {
        openPack()
        // 读屏：每张卡念名字、几份、多少字、常用还是按需；空的念“点了新建”。
        compose.onNodeWithContentDescription("人物档案，2 份，", substring = true).assertExists()
        assertTrue(described("人物档案，2 份，") && described("，常用 1"))
        // 只排有文档的三类和一格“新建”；空的十类收在“其余 10 类”里，展开才有。
        assertFalse(described("硬性设定，还没有文档"))
        compose.onNodeWithContentDescription("新建文档，先选一类").assertExists()
        compose.onNodeWithText("其余 10 类").performScrollTo().performClick()
        compose.onNodeWithContentDescription("硬性设定，还没有文档，点了新建").assertExists()
        // 一开始全部列着。
        assertTrue(shown("渡口地图说明", substring = false))

        // 点人物档案：只剩这一类，组名写着几份，组下有这一类的说明和“新建一份人物档案”。
        compose.onNodeWithContentDescription("人物档案，2 份，", substring = true).performClick()
        waitFor { shown("人物档案 · 2 份") }
        assertTrue(shown("配角", substring = false))
        assertFalse(shown("渡口地图说明", substring = false))
        assertFalse(shown("写作规范", substring = false))
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        compose.onNode(hasContentDescription("人物档案，2 份，", substring = true) and selected).assertExists()
        // 再点一次回到全部。
        compose.onNodeWithContentDescription("人物档案，2 份，", substring = true).performClick()
        waitFor { shown("渡口地图说明", substring = false) }

        // 点空的“硬性设定”：新建对话框，类型已经选好硬性设定。
        compose.onNodeWithContentDescription("硬性设定，还没有文档，点了新建").performScrollTo().performClick()
        waitFor { shown("新建文档") }
        compose.onNodeWithText("新建").performClick()
        waitFor { WorkStore(writingDir).pack(workId).any { it.kind == PackKind.CANON } }
        assertEquals("硬性设定", WorkStore(writingDir).pack(workId).single { it.kind == PackKind.CANON }.title)
    }

    @Test
    fun theBudgetBarOpensItsDetailsAndShowsWhatDoesNotFit() {
        // 一份大的常用设定、知识库上限 3 万字：放不下。
        val store = WorkStore(writingDir)
        store.addPackDoc(workId, PackKind.CANON, "北岸地理", "林月记得北岸有一间茶棚。".repeat(4_000))
        store.updateAssist(workId) { it.copy(knowledgeLimit = 30_000) }
        openPack()
        waitFor { described("份放不下或被截掉") }
        // 读屏把整根条念成一句：总数、按哪章算、各类、两个上限。
        compose.onNodeWithContentDescription("写正文每次带", substring = true).assertExists()
        assertTrue(described("按「第1章 渡口」算"))
        assertTrue(described("知识库") && described("上限 3.0 万字"))
        // 列表里那份挂着删除色的“放不下”。
        compose.onNodeWithText("北岸地理").performScrollTo()
        assertTrue(shown("放不下", substring = false))

        compose.onNodeWithContentDescription("写正文每次带", substring = true).performScrollTo().performClick()
        waitFor { shown("放不下、被截掉的") }
        assertTrue(shown("整份没带 · 北岸地理"))
        assertTrue(shown("知识库上限 3.0 万字 放不下"))
        assertTrue(shown("写正文带上的"))
        // “去调上限”进高级设置。
        compose.onNodeWithText("去调上限").performClick()
        waitFor { shown("知识库上限") }
    }

    @Test
    fun aCardFieldEditsInPlaceAndConfirmingRemovesTheAiTags() {
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitFor { shown("林月到渡口，得知封航") }
        // 填了的几张挂“AI 拟的·待确认”，节拍卡挂“AI 拆的·待确认”；空的写“未填”。
        waitFor { compose.onAllNodesWithText(AI_PENDING, substring = true).fetchSemanticsNodes().size == 3 }
        assertTrue(shown(AI_PENDING_BEATS))
        assertTrue(shown("未填", substring = false))
        // 节拍卡一直列着各拍，已写的勾着。
        assertTrue(shown("2 拍 · 已写 1"))
        assertTrue(shown("1. 林月到渡口"))

        // 读屏：项名、内容、标签连起来念，再念收着还是展开。
        val pov = compose.onNode(hasText("视角人物", substring = false) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        pov.assertExists()
        // 点开“视角人物”就地改：存进细纲卡，改过就算作者确认，AI 拟的标签没了。
        pov.performClick()
        compose.onNode(hasText("视角人物", substring = false) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("林月", substring = false)).performTextReplacement("沈默")
        waitFor { WorkStore(writingDir).card(workId, chapterId).pov == "沈默" }
        assertFalse(WorkStore(writingDir).card(workId, chapterId).aiOutline)
        waitFor { !shown(AI_PENDING) }

        // 节拍：点“确认这些节拍”，标签消失，节拍原文不动。
        compose.onNodeWithText("确认这些节拍").performScrollTo().performClick()
        waitFor { !WorkStore(writingDir).card(workId, chapterId).aiBeats }
        waitFor { !shown(AI_PENDING_BEATS) }
        assertEquals("林月到渡口\n老周说封航了", WorkStore(writingDir).card(workId, chapterId).beats)
        // 上一章交接、本章摘要照旧在（本章摘要的文本框一直露着）。
        assertTrue(shown("本章摘要", substring = true))
    }

    @Test
    fun confirmingTheOutlineRemovesEveryPendingTag() {
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitFor { compose.onAllNodesWithText(AI_PENDING, substring = true).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithText("确认这份细纲").performClick()
        waitFor { !WorkStore(writingDir).card(workId, chapterId).aiOutline }
        waitFor { !shown(AI_PENDING) }
        // 内容没变，节拍那张还等着确认。
        assertEquals("林月到渡口，得知封航", WorkStore(writingDir).card(workId, chapterId).facts)
        assertTrue(shown(AI_PENDING_BEATS))
    }
}
