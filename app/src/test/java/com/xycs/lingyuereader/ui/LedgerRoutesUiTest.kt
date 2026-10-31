package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 《渡灯》的故事账本从哪进、落在哪、回来还在不在（真页面、真存盘，文字自造、人名虚构）：
 * 作品页卡片按伏笔表 + 账本提示“N 条该回收”并直接落在“伏笔”标签；去别的页再回来，标签和筛选都在；
 * 变更登记的“去看账本”落在能看见“待复核”的地方；筛选行下面的说明跟着标签和“只看谁”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class LedgerRoutesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private val ids = mutableListOf<String>()
    private lateinit var store: WorkStore

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear().commit()
            store = WorkStore(writingDir)
            val w = store.create("渡灯")
            workId = w.id
            ids.clear()
            ids += w.chapters.single().id
            repeat(8) { ids += store.addChapter(workId, w.volumes.single().id)!!.second.id }
            // 九章都有字：写到了第 9 章。
            ids.forEachIndexed { i, id -> store.saveText(workId, id, "林月在渡灯镇的第${i + 1}天。", count = false) }
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

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    private fun waitForText(text: String) = waitFor { count(text, substring = true) > 0 }
    private fun described(d: String) = compose.onAllNodesWithContentDescription(d).fetchSemanticsNodes().isNotEmpty()
    private fun open(screen: Screen) = compose.runOnUiThread { vm.open(screen) }
    private fun tab(title: String) = compose.onNode(hasText(title) and isSelectable())

    private fun seedLedger() {
        store.applyLedger(
            workId,
            listOf(
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "在渡口守灯", subject = "林月", aspect = "位置", from = 2)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "回了城南茶馆", subject = "沈默", aspect = "位置", from = 3)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "林月答应杜娘回渡口", subject = "林月", from = 2, payoff = 20)),
            ),
            "手动添加",
        )
    }

    /** M1：伏笔只在设定包伏笔表里（没导入账本），计划第 8 章收、已写到第 9 章：卡片写“1 条该回收”，点了直接是“伏笔”标签。 */
    @Test
    fun theLedgerCardWarnsAboutDueForeshadowsFromTheTableAndOpensThePromisesTab() {
        store.addPackDoc(workId, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋下 | 回收 | 状态 |\n| --- | --- | --- | --- |\n| 玉佩之谜 | 第3章 | 第8章 | |\n")
        open(Screen.Work(workId))
        waitFor { compose.onAllNodes(hasText("故事账本") and hasText("1 条该回收")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, count("还没有记录"))
        compose.onNodeWithText("故事账本").performClick()
        waitForText("该回收了")
        waitForText("玉佩之谜")
        tab("伏笔").assertIsSelected()
    }

    /** 没有该回收的伏笔时照旧落在“人物”。 */
    @Test
    fun withoutDueForeshadowsTheCardOpensThePeopleTab() {
        seedLedger()
        open(Screen.Work(workId))
        waitFor { compose.onAllNodes(hasText("故事账本") and hasText("1 条伏笔没收")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("故事账本").performClick()
        waitFor { compose.onAllNodes(hasText("人物") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        tab("人物").assertIsSelected()
    }

    /** M2：在伏笔标签选了第 3 章、只看林月，去别的页再回来，三样都还在；从作品页重新进来才回到默认。 */
    @Test
    fun theTabChapterAndPersonSurviveAVisitToAnotherPage() {
        seedLedger()
        open(Screen.Work(workId))
        waitForText("故事账本")
        compose.onNodeWithText("故事账本").performClick()
        waitFor { described("只看谁：全部人物") }
        tab("伏笔").performClick()
        compose.onNodeWithContentDescription("看到第几章", substring = true).performClick()
        waitForText("第3章")
        compose.onAllNodesWithText("第3章").onLast().performClick()
        waitFor { described("看到第几章：第 3 章") }
        compose.onNodeWithContentDescription("只看谁", substring = true).performClick()
        waitForText("沈默")
        compose.onAllNodesWithText("林月").onLast().performClick()
        waitFor { described("只看谁：林月") }

        // 去设定包（编辑伏笔表也是压一页），再返回。
        open(Screen.WorkPack(workId))
        waitFor { vm.stack.value.last() is Screen.WorkPack && count("故事账本") == 0 }
        compose.runOnUiThread { vm.back() }
        waitFor { described("只看谁：林月") }
        assertEquals(true, described("看到第几章：第 3 章"))
        tab("伏笔").assertIsSelected()

        // 回作品页再点卡片：重新进来按入口的默认，不带上一回的筛选。
        compose.runOnUiThread { vm.back() }
        waitFor { vm.stack.value.last() is Screen.Work }
        waitFor { count("故事账本") > 0 && count("规划树", substring = true) > 0 }
        compose.onNodeWithText("故事账本").performClick()
        waitFor { described("只看谁：全部人物") }
        assertEquals(true, described("看到第几章：全部章节"))
        tab("人物").assertIsSelected()
    }

    /** M3：改定稿后核对登记的“去看账本”落在“全部记录”，待复核的条目单列在最上面，不用自己切标签。 */
    @Test
    fun cascadeRecheckOpensWhereTheRecheckTagIsVisible() {
        seedLedger()
        store.applyLedger(
            workId,
            listOf(LedgerOp.Add(LedgerEntry(id = "hide", kind = LedgerKind.STATE, text = "躲在旧仓", subject = "顾长风", aspect = "位置", from = 1,
                quote = "顾长风躲进旧仓。", chapterId = ids[0], recheck = true))),
            "改定稿后核对",
        )
        store.addChange(workId, ChangeEntry(WorkStore.newId(), System.currentTimeMillis(), ChangeKind.CASCADE, ids[0], "第1章",
            "第 1 章定稿后又改过：这一章记进账本的 1 条，原句在正文里找不到了，标了待复核", canon = true))
        open(Screen.WorkChanges(workId))
        waitForText("待同步设定")
        compose.onAllNodesWithText("改定稿后核对", substring = true).onFirst().performClick()
        waitForText("去看账本")
        compose.onNodeWithText("去看账本").performClick()
        waitForText("躲在旧仓")
        waitFor { count("待复核") > 0 }
        tab("全部记录").assertIsSelected()
    }

    /** L4：筛选行下的说明跟着标签和“只看谁”：人物等标签不说“按类别列出”；全部记录里只看林月时说林月的条数。 */
    @Test
    fun theFooterUnderTheFiltersMatchesWhatIsShown() {
        seedLedger()
        open(Screen.WorkLedger(workId))
        waitFor { described("只看谁：全部人物") }
        tab("人物").assertIsSelected()
        assertEquals(0, count("按类别列出", substring = true))
        tab("全部记录").performClick()
        waitForText("共 3 条，按类别列出")
        compose.onNodeWithContentDescription("只看谁", substring = true).performClick()
        waitForText("沈默")
        compose.onAllNodesWithText("林月").onLast().performClick()
        waitForText("林月的 2 条（共 3 条）")
        assertEquals(0, count("共 3 条，按类别列出", substring = true))
    }
}
