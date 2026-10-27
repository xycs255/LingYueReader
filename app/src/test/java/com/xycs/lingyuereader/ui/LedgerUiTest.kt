package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class LedgerUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var ch12 = ""
    private var ch15 = ""

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
            ch12 = w.chapters[0].id
            store.renameChapter(workId, ch12, "第12章 渡口")
            ch15 = store.addChapter(workId, w.volumes[0].id, "第15章 偷听")!!.second.id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val store get() = WorkStore(writingDir)
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForStage(stage: String, condition: () -> Boolean) {
        try {
            waitFor(condition = condition)
        } catch (failure: Throwable) {
            throw AssertionError("账本界面等待阶段：$stage", failure)
        }
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private fun waitForText(text: String) = waitFor { count(text, substring = true) > 0 }

    /** 换章、换模式是淡出淡入的：过渡途中新旧两份都在，等只剩一份再点、再数。 */
    private fun waitForOne(text: String) = waitFor { count(text) == 1 }

    private fun open(screen: Screen) = compose.runOnUiThread { vm.open(screen) }

    private fun showAllRecords() {
        waitForText("全部记录")
        compose.onNodeWithText("全部记录").performClick()
    }

    /** 林月在渡口（第 12 章起）、沈默第 15 章才知道杜娘是卧底、顾长风的约（计划第 11 章收）、老周的船沉了（第 3 章）。 */
    private fun seedLedger() {
        store.applyLedger(
            workId,
            listOf(
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "在渡口，左臂伤未愈", subject = "林月", aspect = "位置", from = 12)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "沈默", from = 15, via = "偷听")),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 8, payoff = 11)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "老周的船沉了", from = 3, origin = LedgerOrigin.IMPORT)),
            ),
            "手动添加",
        )
    }

    @Test
    fun emptyLedgerExplainsAndOffersImportAndManualAdd() {
        // 作品页有入口。
        open(Screen.Work(workId))
        waitForText("故事账本")
        compose.onNodeWithText("故事账本").performClick()
        waitForText("账本还是空的")
        waitForText("手动添加")

        // 导入：设定包里什么都没有，提示一句。
        compose.onNodeWithText("从设定包导入").performClick()
        waitForText("没有找到账本里还没有的条目")

        // 手动加一条。
        compose.onNodeWithText("手动添加").performClick()
        waitForText("添加一条")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("杜娘")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("在城南茶馆")
        compose.onNodeWithText("添加").performClick()
        waitFor { store.ledger(workId).size == 1 }
        val e = store.ledger(workId).single()
        assertEquals(Triple(LedgerKind.STATE, "杜娘", "在城南茶馆"), Triple(e.kind, e.subject, e.text))
        assertEquals(LedgerOrigin.MANUAL, e.origin)
        showAllRecords()
        waitForOne("在城南茶馆")
        waitForText("人物状态 · 杜娘")
    }

    @Test
    fun allEntriesAreGroupedByKind() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        showAllRecords()
        waitForOne("在渡口，左臂伤未愈")
        assertEquals(1, count("人物状态 · 林月"))
        assertEquals(1, count("发生的事"))
        assertEquals(2, count("知情")) // 记录组和顶部分段标签各一个
        // 组的顺序照类别：人物状态在发生的事前面，发生的事在知情前面。
        fun top(text: String) = compose.onAllNodesWithText(text).onLast().fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("人物状态 · 林月") < top("发生的事"))
        assertTrue(top("发生的事") < top("知情"))
        // 副文字：谁、哪方面、第几章起；来源的标签。
        waitForText("林月 · 位置 · 第 12 章起")
        waitForText("沈默 · 第 15 章起 · 消息来自偷听")
        assertEquals(1, count("导入"))
        assertTrue(count("手动") >= 2)
        // 伏笔在最后一组，可能在屏幕外面：先滚过去。
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("林月 · 位置", substring = true))).performScrollToNode(hasText("第 8 章埋，计划第 11 章收", substring = true))
        waitForText("第 8 章埋，计划第 11 章收")
        assertEquals(2, count("伏笔")) // 记录组和顶部分段标签各一个
    }

    @Test
    fun aChapterShowsOnlyWhatIsKnownByThen() {
        seedLedger()
        // 从编辑器的菜单进来，直接翻到这一章（第 12 章）。
        open(Screen.Editor(workId, ch12))
        waitFor { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("更多").onFirst().performClick()
        waitForText("故事账本")
        compose.onNodeWithText("故事账本").performClick()
        waitForText("第 12 章")
        waitForOne("还不知道的事")
        // 沈默第 15 章才知道：第 12 章不算知情，列在“还不知道”里。
        assertEquals(1, count("知情")) // 只有顶部分段标签，尚无知情记录组
        assertEquals(1, count("杜娘是卧底"))
        // 顾长风的约计划第 11 章收，到第 12 章还开着：过期没收。
        assertEquals(1, count("过期没收的伏笔"))
        assertEquals(1, count("顾长风说三日后再来"))
        assertEquals(1, count("在渡口，左臂伤未愈"))

        // 翻到第 15 章：知道了，不再是“还不知道”。
        compose.onNodeWithText("看到第几章").performClick()
        waitForText("第15章 偷听")
        compose.onNodeWithText("第15章 偷听").performClick()
        waitFor { count("还不知道的事") == 0 && count("知情") == 2 }
        waitForOne("杜娘是卧底")

        // 回到“全部”。
        compose.onNodeWithText("看到第几章").performClick()
        waitForText("列出所有条目")
        compose.onNodeWithText("列出所有条目").performClick()
        waitFor { count("知情") == 2 && count("伏笔") == 2 && count("过期没收的伏笔") == 0 }
    }

    @Test
    fun editingAnEntrySavesIt() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        showAllRecords()
        waitForOne("在渡口，左臂伤未愈")
        compose.onNodeWithText("在渡口，左臂伤未愈").performClick()
        waitForText("改一条")
        compose.onNode(hasSetTextAction() and hasText("在渡口，左臂伤未愈")).performTextReplacement("在石桥，左臂伤未愈")
        compose.onNodeWithText("保存").performClick()
        waitFor { store.ledger(workId).any { it.text == "在石桥，左臂伤未愈" } }
        val e = store.ledger(workId).first { it.kind == LedgerKind.STATE }
        assertEquals(Triple("林月", "位置", 12), Triple(e.subject, e.aspect, e.from))
        assertEquals("手动修改", store.ledgerVersions(workId).first().label)
        waitForOne("在石桥，左臂伤未愈")
    }

    @Test
    fun deletingAsksFirst() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        showAllRecords()
        waitForOne("老周的船沉了")
        compose.onNodeWithText("老周的船沉了").performClick()
        waitForText("改一条")
        compose.onNodeWithText("删除这一条").performScrollTo().performClick()
        waitForText("删掉这一条")
        // 确认之前没删。
        assertEquals(4, store.ledger(workId).size)
        compose.onNodeWithText("删除").performClick()
        waitFor { store.ledger(workId).none { it.text == "老周的船沉了" } }
        waitFor { count("老周的船沉了") == 0 }
        assertEquals(3, store.ledger(workId).size)
    }

    @Test
    fun anOldVersionCanBeRestored() {
        seedLedger()
        val gone = store.ledger(workId).first { it.text == "老周的船沉了" }
        store.applyLedger(workId, listOf(LedgerOp.Remove(gone.id)), "手动删除")
        open(Screen.WorkLedger(workId))
        showAllRecords()
        waitForOne("在渡口，左臂伤未愈")
        compose.onAllNodesWithContentDescription("更多").onFirst().performClick()
        waitForText("历史版本")
        compose.onNodeWithText("历史版本").performClick()
        waitForText("4 条")
        waitForText("手动删除前的样子")
        compose.onNodeWithText("4 条").performClick()
        // 那一版的条目：船还在。
        waitForText("换回这个版本")
        waitForOne("老周的船沉了")
        compose.onNodeWithText("换回这个版本").performScrollTo().performClick()
        waitFor { store.ledger(workId).size == 4 }
        waitForText("已换回")
        waitFor { count("老周的船沉了") == 1 && count("换回这个版本") == 0 }
    }

    @Test
    fun graphicalPersonStateKeepsEarlierChapterAndCanBeUndone() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        waitForStage("人物状态卡加载") { count("位置 · 在渡口", substring = true) > 0 }
        compose.onNodeWithText("位置 · 在渡口", substring = true).performClick()
        waitForStage("快捷状态弹窗显示") { count("本章状态", substring = true) > 0 }
        compose.onNode(hasSetTextAction() and hasText("在渡口，左臂伤未愈"))
            .performTextReplacement("在石桥，伤已痊愈")
        compose.onNode(hasSetTextAction() and hasText("12")).performTextReplacement("15")
        waitForStage("第15章可保存") {
            runCatching { compose.onNodeWithText("保存状态").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("保存状态").performClick()
        waitForStage("新状态写入磁盘") {
            store.ledger(workId).count { it.kind == LedgerKind.STATE && it.subject == "林月" } == 2
        }
        val states = store.ledger(workId).filter { it.kind == LedgerKind.STATE && it.subject == "林月" }
        assertTrue(states.any { it.from == 12 && it.text == "在渡口，左臂伤未愈" })
        assertTrue(states.any { it.from == 15 && it.text == "在石桥，伤已痊愈" && it.origin == LedgerOrigin.MANUAL })
        waitForStage("ViewModel 刷新与撤销入口显示") {
            vm.writing.ledgers.value[workId]?.count { it.kind == LedgerKind.STATE && it.subject == "林月" } == 2 &&
                count("撤销上一步 · 手动登记人物状态") == 1
        }
        compose.onNodeWithText("撤销上一步 · 手动登记人物状态").performClick()
        waitForStage("撤销写入磁盘") {
            store.ledger(workId).count { it.kind == LedgerKind.STATE && it.subject == "林月" } == 1
        }
        assertEquals("在渡口，左臂伤未愈", store.ledger(workId).first { it.kind == LedgerKind.STATE }.text)
    }

    @Test
    fun knowledgeMatrixRequiresSourceBeforeSaving() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        waitForText("知情")
        compose.onNodeWithText("知情").performClick()
        waitForText("谁知道什么")
        compose.onNodeWithText("尚未知").performClick()
        waitForText("消息来源")
        // The fact is known by 沈默; registering it for 林月 needs both chapter and source.
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("12")
        assertEquals(4, store.ledger(workId).size)
        compose.onNodeWithText("登记知情").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("杜娘亲口告知")
        compose.onNodeWithText("登记知情").performClick()
        waitFor { store.ledger(workId).any { it.kind == LedgerKind.KNOW && it.subject == "林月" && it.via == "杜娘亲口告知" && it.from == 12 } }
    }

    @Test
    fun futureKnowledgeCellOpensExistingRecordInsteadOfDuplicateRegistration() {
        seedLedger()
        open(Screen.WorkLedger(workId, ch12))
        waitForText("第 12 章")
        compose.onNodeWithText("知情").performClick()
        waitForText("第15章后知")
        compose.onNodeWithText("第15章后知").performClick()
        waitForText("改一条")
        assertEquals(0, count("登记知情"))
        assertEquals(4, store.ledger(workId).size)
    }

    @Test
    fun personFilterAlsoAppliesAfterSwitchingToPromiseGraph() {
        seedLedger()
        store.applyLedger(
            workId,
            listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "林月要交信", subject = "林月", from = 12))),
            "手动添加",
        )
        open(Screen.WorkLedger(workId))
        waitForText("人物")
        compose.onAllNodesWithText("人物").onFirst().performClick()
        waitForText("林月")
        compose.onAllNodesWithText("林月").onLast().performClick()
        compose.onNodeWithText("伏笔").performClick()
        waitForText("林月要交信")
        waitFor { count("顾长风说三日后再来") == 0 }
    }
}
