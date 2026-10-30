package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PromiseState
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
 * 第二批写作功能在界面上的入口：账本伏笔的状态和“最近推进”、手动改状态；高级设置里的“记数值与持有”（默认关）；
 * “拟细纲前先给 3 个走向”（默认关，开了细纲卡页多一个按钮）；改定稿后核对挂进变更登记的“待同步”、账本条目标“待复核”、摘要过期的提示。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingSecondBatchUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
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
            chapterId = w.chapters.single().id
            store.renameChapter(workId, chapterId, "第1章 渡口")
            store.saveText(workId, chapterId, "林月推开旧仓的门。", count = false)
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

    private fun count(text: String, substring: Boolean = true) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private fun waitForText(text: String) = waitFor { count(text) > 0 }

    private fun open(screen: Screen) = compose.runOnUiThread { vm.open(screen) }

    @Test
    fun aForeshadowShowsItsStateAndLastAdvanceAndCanBePaused() {
        store.applyLedger(workId, listOf(
            LedgerOp.Add(LedgerEntry(id = "book", kind = LedgerKind.PROMISE, text = "老周藏起的半本账册", from = 1, lastAdvanced = 6)),
            LedgerOp.Add(LedgerEntry(id = "lamp", kind = LedgerKind.PROMISE, text = "铜灯底座的记号", from = 2)),
        ), "手动添加")
        open(Screen.WorkLedger(workId))
        waitForText("伏笔")
        compose.onAllNodesWithText("伏笔", substring = false).onFirst().performClick()
        waitForText("老周藏起的半本账册")
        waitForText("最近推进：第 6 章")
        waitForText("最近推进：还没有")
        assertTrue(count("推进中", substring = false) >= 1)
        assertTrue(count("埋下", substring = false) >= 1)

        compose.onAllNodesWithText("改状态").onFirst().performClick()
        waitForText("伏笔状态")
        compose.onAllNodesWithText("暂缓", substring = false).onLast().performClick()
        waitFor { store.ledger(workId).single { it.id == "book" }.state == PromiseState.PAUSED }
        // 内容和推进章不变。
        val book = store.ledger(workId).single { it.id == "book" }
        assertEquals("老周藏起的半本账册", book.text)
        assertEquals(6, book.lastAdvanced)
        waitForText("暂缓")
    }

    @Test
    fun holdingsAreOffUntilSwitchedOnInAdvancedSettings() {
        assertFalse(store.assist(workId).holdings)
        open(Screen.WorkAiAdvanced(workId))
        waitForText("记数值与持有")
        compose.onNodeWithText("记数值与持有").performScrollTo().performClick()
        waitFor { WorkStore(writingDir).assist(workId).holdings }
        // 打开以后，账本里加一条能选“数值”。
        open(Screen.WorkLedger(workId))
        waitForText("手动添加")
        compose.onAllNodesWithText("手动添加").onFirst().performClick()
        waitForText("添加一条")
        compose.onNodeWithText("数值", substring = false).performClick()
        waitForText("期初（这一章开始时）")
        waitForText("这是一件物品")
    }

    @Test
    fun threeDirectionsIsOffByDefaultAndAddsAButtonOnTheCardPage() {
        open(Screen.ChapterCard(workId, chapterId))
        waitForText("AI 拟这一章的细纲")
        assertEquals(0, count("先给 3 个走向", substring = false))

        open(Screen.WorkAi(workId))
        waitForText("拟细纲前先给 3 个走向")
        compose.onNodeWithText("拟细纲前先给 3 个走向").performScrollTo().performClick()
        waitFor { WorkStore(writingDir).assist(workId).planOptions }

        open(Screen.ChapterCard(workId, chapterId))
        waitForText("AI 拟这一章的细纲")
        waitFor { count("先给 3 个走向", substring = false) == 1 }
    }

    @Test
    fun aRefinalizedChapterShowsWhatToRecheck() {
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月在旧仓找到铜灯", summary = "林月在旧仓找到铜灯。"))
        store.updateCard(workId, chapterId) { it.copy(summaryStale = true) }
        store.applyLedger(workId, listOf(
            LedgerOp.Add(LedgerEntry(id = "hide", kind = LedgerKind.STATE, text = "躲在旧仓", subject = "林月", aspect = "位置", from = 1,
                quote = "林月躲进旧仓。", chapterId = chapterId, recheck = true)),
        ), "改定稿后核对")
        store.addChange(workId, ChangeEntry(WorkStore.newId(), System.currentTimeMillis(), ChangeKind.CASCADE, chapterId, "第1章 渡口",
            "第 1 章「第1章 渡口」定稿后又改过；第 4 章的账本条目「林月·位置：到了渡口」接的是这一章的「躲在旧仓」，可能要跟着改", canon = true))

        // 细纲卡页：摘要过期了，说一声。
        open(Screen.ChapterCard(workId, chapterId))
        waitForText("下面的摘要可能过期了")

        // 变更登记：挂在“待同步”里，点开去看账本。
        open(Screen.WorkChanges(workId))
        waitForText("待同步设定")
        waitForText("改定稿后核对 · 第1章 渡口")
        compose.onAllNodesWithText("改定稿后核对 · 第1章 渡口").onFirst().performClick()
        waitForText("接的是这一章的「躲在旧仓」，可能要跟着改")
        waitForText("去看账本")
        compose.onNodeWithText("去看账本").performClick()
        waitForText("全部记录")
        compose.onNodeWithText("全部记录").performClick()
        waitForText("躲在旧仓")
        waitForText("待复核")

        // 作者核过、存一下：不再待复核。
        compose.onAllNodesWithText("躲在旧仓").onFirst().performClick()
        waitForText("原句在正文里找不到了")
        compose.onNodeWithText("保存").performClick()
        waitFor { !store.ledger(workId).single().recheck }
    }
}
