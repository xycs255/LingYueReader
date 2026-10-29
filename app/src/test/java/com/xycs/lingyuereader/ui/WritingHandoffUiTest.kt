package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterHandoff
import com.xycs.lingyuereader.data.HandoffOrigin
import com.xycs.lingyuereader.data.HandoffPerson
import com.xycs.lingyuereader.data.Handoffs
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

/**
 * 交接卡的界面：草稿页“按这些写的”卡片里“接上一章”能展开、能改（存成“你改过的”）；细纲卡页显示“上一章交接：…”一行；
 * 收尾页有“整理交接卡”的勾选项，默认勾上。不发 AI 请求：AI 设置指向一个不存在的本机地址。人名虚构、文本自造。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingHandoffUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var priorId = ""
    private var chapterId = ""

    private val ending = "沈默把船推离岸边。\n顾长风站在渡口茶馆门口，没有回头。\n林月追出来，喊：“你到底要去哪？”"

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:9/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("渡口")
            priorId = w.chapters[0].id
            store.renameChapter(w.id, priorId, "第一章 渡口")
            store.saveText(w.id, priorId, ending, count = false)
            chapterId = store.addChapter(w.id, w.volumes[0].id, "第二章 夜渡")!!.second.id
            store.saveCard(w.id, chapterId, ChapterCard(facts = "沈默把船撑到北岸"))
            val prior = store.get(w.id)!!.chapter(priorId)!!
            store.saveHandoff(w.id, priorId, ChapterHandoff(
                scene = "渡口茶馆门口，天快黑了", people = listOf(HandoffPerson("林月", "追到门口", "又急又气")),
                mood = "憋着火", open = listOf("顾长风要去哪"), origin = HandoffOrigin.AI,
                hash = Handoffs.hash(ending), textAt = prior.updatedAt,
            ))
            workId = w.id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val store get() = WorkStore(writingDir)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun seen(t: String): Boolean {
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(t, substring = true)) }
        ShadowLooper.idleMainLooper()
        return has(t)
    }

    private fun press(t: String) {
        waitFor { seen(t) }
        compose.onNodeWithText(t).performClick()
    }

    @Test
    fun draftPageShowsTheHandoffExpandsAndSavesTheAuthorsEdit() {
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitFor { seen("接上一章") }
        assertEquals(false, has("场景：渡口茶馆门口"))
        press("接上一章")
        waitFor { seen("场景：渡口茶馆门口，天快黑了") }
        assertTrue(seen("在场人物：林月：追到门口；又急又气"))
        assertTrue(seen("没了结的：顾长风要去哪"))
        press("改")
        waitFor { has("上一章《第一章 渡口》结尾时的样子") }
        compose.onNode(hasSetTextAction() and hasText("渡口茶馆门口，天快黑了")).performTextReplacement("北岸芦苇荡，起了雾")
        press("存下")
        waitFor { store.handoff(workId, priorId)?.let { it.origin == HandoffOrigin.AUTHOR && it.scene == "北岸芦苇荡，起了雾" } == true }
        // 其余的项照原样留着；页面上换成改过的。
        assertEquals(listOf(HandoffPerson("林月", "追到门口", "又急又气")), store.handoff(workId, priorId)!!.people)
        waitFor { seen("场景：北岸芦苇荡，起了雾") }
        waitFor { seen("你改过的") }
    }

    @Test
    fun cardPageShowsThePreviousHandoffLine() {
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitFor { seen("上一章交接：场景：渡口茶馆门口，天快黑了") }
        press("上一章交接")
        waitFor { has("上一章《第一章 渡口》结尾时的样子") }
    }

    @Test
    fun finishPageOffersTidyingTheHandoff() {
        compose.runOnUiThread { vm.open(Screen.ChapterFinish(workId, priorId)) }
        waitFor { seen("整理交接卡") }
        compose.onNode(hasText("整理交接卡") and isToggleable()).assertIsOn()
    }
}
