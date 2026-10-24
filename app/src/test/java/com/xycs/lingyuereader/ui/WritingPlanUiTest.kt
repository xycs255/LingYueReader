package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.ChapterStatus
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
class WritingPlanUiTest {
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
            store.saveText(w.id, w.chapters[0].id, "他推开门。")
            workId = w.id
            chapterId = w.chapters[0].id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun finalizeLocksTheTextAndUnlockingNeedsAReason() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("草稿 · 5 字")

        // 定稿：正文锁定。
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("章节状态").performClick()
        waitForText("的状态")
        compose.onNodeWithContentDescription("状态定稿").performClick()
        waitForText("定稿后正文锁定")
        compose.onNodeWithText("好").performClick()
        waitFor { store.get(workId)!!.chapter(chapterId)!!.status == ChapterStatus.FINAL }
        waitForText("已定稿，正文锁定")
        waitForText("定稿 · 5 字 · 已锁定")
        // 打字进不去。
        runCatching { compose.onNodeWithContentDescription("正文").performTextInput("偷偷加一句。") }
        Thread.sleep(1_200)
        ShadowLooper.idleMainLooper()
        assertEquals("他推开门。", store.text(workId, chapterId))
        // AI 改稿也不行。
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("润色").performClick()
        waitForText("先解锁并登记原因")

        // 解锁：原因必填，选“涉及设定”。
        compose.onNodeWithText("解锁修改").performClick()
        waitForText("为什么要改")
        compose.onNodeWithContentDescription("状态修订").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("老周改为第九章战死")
        compose.onNodeWithContentDescription("涉及设定，要同步设定包").performClick()
        compose.onNodeWithText("解锁").performClick()
        waitFor { store.get(workId)!!.chapter(chapterId)!!.status == ChapterStatus.REVISION }
        val unlock = store.changes(workId).first()
        assertEquals(ChangeKind.UNLOCK to "老周改为第九章战死", unlock.kind to unlock.reason)
        assertTrue(unlock.pending)
        waitForText("修订 · 5 字")

        // 变更登记：待同步的那条标为已同步。
        compose.runOnUiThread { vm.open(Screen.WorkChanges(workId)) }
        waitForText("待同步设定")
        compose.onAllNodesWithText("解锁修改 · 第1章")[0].performClick()
        waitForText("标为已同步")
        compose.onNodeWithText("标为已同步").performClick()
        waitFor { !store.changes(workId).first().pending }
    }

    @Test
    fun theChapterCardIsSavedAsYouType() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitForText("本章事实与结果")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("护送队十二人到石桥")
        waitFor { store.card(workId, chapterId).facts == "护送队十二人到石桥" }
        // 从这里也能改状态。
        compose.onNodeWithContentDescription("改状态").performClick()
        waitForText("的状态")
        compose.onNodeWithContentDescription("状态修订").performClick()
        compose.onNodeWithText("好").performClick()
        waitFor { store.get(workId)!!.chapter(chapterId)!!.status == ChapterStatus.REVISION }
    }
}
