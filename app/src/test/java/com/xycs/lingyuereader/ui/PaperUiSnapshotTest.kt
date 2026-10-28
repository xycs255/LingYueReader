package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
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

/** 实际 Compose 页面统一使用纸色；顺手导出主要入口的画面供设计复核。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperUiSnapshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 渡口\n　　河上的灯一盏盏亮起来。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "渡口夜话").put("author", "沈青禾").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 13)
                .put("sources", JSONArray().put(JSONObject().put("name", "渡口夜话.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            File(files, "writing").deleteRecursively()
            val store = WorkStore(File(files, "writing"))
            val work = store.create("长夜", "沈青禾")
            store.renameChapter(work.id, work.chapters[0].id, "第一章 雪夜")
            store.saveText(work.id, work.chapters[0].id, "他推开门，风雪灌进来。")
            val (_, next) = store.addChapter(work.id, work.volumes[0].id, "第二章 天亮")!!
            store.saveText(work.id, next.id, "天终于亮了。")
            store.applyLedger(
                work.id,
                listOf(
                    LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "在渡口", subject = "沈青禾", aspect = "位置", from = 1)),
                    LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "渡口封航", subject = "沈青禾", from = 2, via = "听船夫说")),
                    LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "雪夜里的约定", from = 1, payoff = 2)),
                ),
                "截图示例",
            )
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            Prefs(context).saveAppearance(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false))
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 写作工具没有标题栏了：等分段控件那一行右端的关闭钮。 */
    private fun waitForToolsPanel() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("关闭写作工具").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForEditorTools() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("打开四组工具").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun fiveHomeDestinationsUseTheSamePaperSystem() {
        val palette = paperAppColors(false)
        assertEquals(Color(0xFFF6F2EA), palette.surface)
        assertEquals(Color(0xFFA94B36), palette.primary)
        waitFor("渡口夜话")
        capture("paper-shelf")
        compose.onNodeWithText("写作").performClick()
        waitFor("长夜")
        capture("paper-writing")
        compose.onNodeWithText("长夜").performClick()
        waitFor("第二章 天亮")
        capture("paper-work")
        // 规划树章行右侧的细纲卡图标：一点直接进细纲卡页。
        compose.onNodeWithContentDescription("打开第一章 雪夜细纲卡").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("打开第一章 雪夜细纲卡").fetchSemanticsNodes().isEmpty() }
        waitFor("本章")
        capture("paper-work-tree-card")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("第二章 天亮")
        compose.onNodeWithText("故事账本").performClick()
        waitFor("人物")
        capture("paper-ledger")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("第二章 天亮")
        // “继续写”也可能指向第一章；列表里的章节行始终在它后面。
        val chapterRows = compose.onAllNodesWithText("第一章 雪夜")
        chapterRows[chapterRows.fetchSemanticsNodes().lastIndex].performClick()
        waitFor("他推开门")
        capture("paper-editor")
        compose.onNodeWithContentDescription("打开四组工具").performClick()
        waitForToolsPanel()
        capture("paper-editor-tools")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("第二章 天亮")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("长夜")
        compose.onNodeWithText("生词本").performClick()
        waitFor("学习中")
        capture("paper-vocabulary")
        compose.onNodeWithText("统计").performClick()
        waitFor("今天")
        capture("paper-stats")
        compose.onNodeWithText("我的").performClick()
        waitFor("主题设置")
        capture("paper-me")
        compose.onNodeWithText("主题设置").performClick()
        waitFor("外观")
        capture("paper-theme")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("主题设置")
        compose.onNodeWithText("书架").performClick()
        waitFor("渡口夜话")
        compose.onAllNodesWithText("渡口夜话")[0].performClick()
        waitFor("河上的灯")
        capture("paper-reader")
    }

    @Test
    fun manuallySelectedDarkThemeUsesWarmInkAcrossPages() {
        val palette = paperAppColors(true)
        assertEquals(Color(0xFF1E1B18), palette.surface)
        waitFor("渡口夜话")
        compose.onNodeWithText("我的").performClick()
        waitFor("主题设置")
        compose.onNodeWithText("主题设置").performClick()
        waitFor("深色")
        compose.onNodeWithText("深色").performClick()
        compose.waitForIdle()
        capture("ink-theme")
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("主题设置")
        capture("ink-me")
        compose.onNodeWithText("写作").performClick()
        waitFor("长夜")
        capture("ink-writing")
        compose.onNodeWithText("长夜").performClick()
        waitFor("第二章 天亮")
        capture("ink-work")
        // 同一毫秒保存的两章可能并列为最近编辑，不能假设“继续写”会选中第二章。
        val chapterRow = hasText("第二章 天亮") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("作品进度")))
            .performScrollToNode(chapterRow)
        compose.onNode(chapterRow).performScrollTo().performClick()
        waitFor("天终于亮了")
        capture("ink-editor")
        compose.onNodeWithContentDescription("打开四组工具").performClick()
        waitForToolsPanel()
        capture("ink-editor-tools")
    }

    /** A short viewport approximates the space left to write when a phone keyboard is open. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h640dp")
    fun compactWritingToolsKeepTheChapterVisible() {
        waitFor("渡口夜话")
        compose.onNodeWithText("写作").performClick()
        waitFor("长夜")
        compose.onNodeWithText("长夜").performClick()
        waitFor("继续写")
        compose.onNodeWithText("继续写").performClick()
        waitForEditorTools()
        compose.onNodeWithContentDescription("打开四组工具").performClick()
        waitForToolsPanel()
        compose.waitForIdle()
        // 360 × 640 上展开写作工具后，正文上方仍留着好几行可见的地方，不被面板挤没。
        val bodyTop = compose.onNodeWithContentDescription("正文").getUnclippedBoundsInRoot().top
        val panelTop = compose.onNodeWithContentDescription("关闭写作工具").getUnclippedBoundsInRoot().top
        assertTrue("写作工具面板不能把正文挤掉：正文顶 $bodyTop、面板顶 $panelTop", panelTop - bodyTop >= 200.dp)
        capture("paper-editor-compact-tools")
    }
}
