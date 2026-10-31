package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.Digests
import com.xycs.lingyuereader.data.StoryDigest
import com.xycs.lingyuereader.data.VolumeDigest
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
 * 作品页上的分层前情和线索体检（真页面、真存盘，文字自造、人名虚构）：故事至今在“更多功能”里，能看、能改，改了标“你改过的”；
 * 卷菜单里的卷摘要能看、能改；体检表在“更多功能”里能打开，有冷了的线时进度卡上也有一枚胶囊；冷了的线点“带进下一章”写进下一章的作者要求。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDigestUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var volumeId = ""
    private val ids = mutableListOf<String>()

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
            val w = store.create("渡灯")
            workId = w.id
            volumeId = w.volumes.single().id
            ids.clear()
            ids += w.chapters.single().id
            repeat(12) { ids += store.addChapter(workId, volumeId)!!.second.id }
            // 前 12 章有正文；“暗线”只在第 1 章推进过，到第 12 章已经冷了。第 13 章是下一章。
            ids.take(12).forEachIndexed { i, id ->
                store.saveText(workId, id, "林月在渡灯镇的第${i + 1}天。", count = false)
                store.saveCard(workId, id, ChapterCard(threads = if (i == 0) "明线；暗线" else "明线", summary = "第${i + 1}章的事。"))
            }
            store.updateDigests(workId) { Digests(StoryDigest("林月到了渡灯镇。\n沈默替她挡了一刀。", ids[11], 12), mapOf(volumeId to VolumeDigest("第一卷里林月查出了铜灯的记号。"))) }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** 体检表一行的名字和标签合成一句读屏说明（“线暗线，冷了，……”），按说明找。 */
    private fun waitForPulse(name: String) = waitFor {
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("线$name，", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** 规划树懒加载：先滚到那一行（找有大小的那个列表，标题栏下面还有别的空列表）。 */
    private fun scrollTo(t: String) {
        val list = hasScrollToIndexAction() and SemanticsMatcher("non-empty scroll viewport") { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        waitFor { compose.onAllNodes(list).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(list).performScrollToNode(hasText(t, substring = true))
    }

    private fun openWork(): WorkStore {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Work(workId)) }
        waitForText("规划树")
        return WorkStore(writingDir)
    }

    /** 故事至今、体检收在“更多功能”里：滚到它、点开。 */
    private fun openMore() {
        scrollTo("更多功能")
        compose.onNodeWithText("更多功能").performClick()
    }

    @Test
    fun theStorySoFarCanBeReadAndEditedNextToTheOutline() {
        val store = openWork()
        openMore()
        scrollTo("故事至今")
        waitForText("2 句 · 写到第 12 章")
        compose.onNodeWithText("故事至今").performClick()
        waitForText("沈默替她挡了一刀")
        // 改：存成“你改过的”，收尾不再盖掉。
        val field = compose.onAllNodes(hasSetTextAction()).onLast()
        field.performTextClearance()
        field.performTextInput("林月到了渡灯镇，找到铜灯。")
        compose.onNodeWithText("存下").performClick()
        waitFor { store.digests(workId).story?.text == "林月到了渡灯镇，找到铜灯。" }
        assertTrue(store.digests(workId).story!!.edited)
        assertEquals(ids[11], store.digests(workId).story!!.throughId)
        waitForText("你改过的")
        // 改回自动：内容留着，下次收尾接着更新。对话框收起的动画走完前点不进去，点到开了为止。
        waitFor {
            if (compose.onAllNodesWithText("改回自动").fetchSemanticsNodes().isEmpty()) runCatching { compose.onNodeWithText("故事至今").performClick() }
            compose.onAllNodesWithText("改回自动").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("改回自动").performClick()
        waitFor { store.digests(workId).story?.edited == false }
        assertEquals("林月到了渡灯镇，找到铜灯。", store.digests(workId).story!!.text)
    }

    @Test
    fun aVolumeSummaryCanBeReadAndEditedFromTheVolumeMenu() {
        val store = openWork()
        scrollTo("有卷摘要")
        compose.onNodeWithContentDescription("正文卷操作", substring = true).performClick()
        waitForText("卷摘要")
        compose.onAllNodesWithText("卷摘要").onLast().performClick()
        waitForText("第一卷里林月查出了铜灯的记号")
        val field = compose.onAllNodes(hasSetTextAction()).onLast()
        field.performTextClearance()
        field.performTextInput("第一卷：林月查出记号，沈默受伤。")
        compose.onNodeWithText("存下").performClick()
        waitFor { store.digests(workId).volumes[volumeId]?.text == "第一卷：林月查出记号，沈默受伤。" }
        assertTrue(store.digests(workId).volumes.getValue(volumeId).edited)
        waitForText("你改过的")
    }

    @Test
    fun thePulseTableOpensAndCarriesAColdThreadIntoTheNextChapter() {
        val store = openWork()
        // 进度卡上一枚提醒胶囊，点了就是体检表。
        waitForText("1 条线索冷了 ›")
        compose.onNodeWithText("1 条线索冷了 ›").performClick()
        waitForPulse("暗线")
        compose.onNodeWithText("知道了").performClick()
        waitFor { compose.onAllNodesWithText("最近推进：第 1 章", substring = true).fetchSemanticsNodes().isEmpty() }
        // “更多功能”里的那一行也写着结果。
        openMore()
        scrollTo("线索与节奏体检")
        waitForText("1 条冷了")
        compose.onNodeWithText("线索与节奏体检").performClick()
        waitForPulse("暗线")
        waitForText("最近推进：第 1 章")
        compose.onNodeWithText("带进下一章").performClick()
        waitFor { store.draftIntent(workId, ids[12])?.instruction?.contains("推进「暗线」这条线") == true }
        assertTrue(store.draftIntent(workId, ids[12])!!.instruction.contains("已经 11 章没动"))
        // 再点一次不重复加。
        compose.onNodeWithText("带进下一章").performClick()
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
        assertEquals(1, store.draftIntent(workId, ids[12])!!.instruction.split("推进「暗线」").size - 1)
        assertFalse(store.draftIntent(workId, ids[12])!!.instruction.contains("明线"))
    }

    /** 收尾页“前情”一组的说明照着实际的位置写：故事至今在作品页的“更多功能”里，不在规划树下面。 */
    @Test
    fun theFinishPagePointsToWhereTheStorySoFarActuallyLives() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        // 直接摆出“整理前情”做完、等确认的那一步（不跑 AI）。
        val field = vm.writingAi.javaClass.getDeclaredField("_finish").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val finish = field.get(vm.writingAi) as kotlinx.coroutines.flow.MutableStateFlow<com.xycs.lingyuereader.FinishRun?>
        finish.value = com.xycs.lingyuereader.FinishRun(
            workId, ids[11],
            stage = com.xycs.lingyuereader.FinishStage.CONFIRM,
            review = com.xycs.lingyuereader.StepState.SKIPPED,
            sync = com.xycs.lingyuereader.StepState.SKIPPED,
            summary = com.xycs.lingyuereader.StepState.SKIPPED,
            digest = com.xycs.lingyuereader.StepState.DONE,
            digestNote = "故事至今已按第 12 章更新",
        )
        compose.runOnUiThread { vm.open(Screen.ChapterFinish(workId, ids[11])) }
        waitForText("收尾")
        scrollTo("故事至今已按第 12 章更新")
        waitForText("故事至今在作品页的“更多功能”里")
        assertEquals(0, compose.onAllNodesWithText("规划树下面", substring = true).fetchSemanticsNodes().size)
    }
}
