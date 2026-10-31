package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.MoveAction
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

/** 细纲卡页新加的两张小卡片“本章改变了什么”“伏笔动作”：和别的几张一样就地展开改，边打边存；伏笔动作一行一条，换行不被吞掉。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingChangeCardsUiTest {
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
            val w = store.create("渡灯")
            workId = w.id
            chapterId = w.chapters.single().id
            store.saveText(workId, chapterId, "林月推开旧仓的门。", count = false)
            store.saveCard(workId, chapterId, ChapterCard(facts = "林月在旧仓找到铜灯"))
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

    private fun scrollTo(t: String) = compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(t, substring = true))

    @Test
    fun theTwoNewCardsEditInPlace() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitForText("本章事实与结果")
        scrollTo("本章改变了什么")
        compose.onAllNodesWithText("本章改变了什么").onFirst().performClick()
        waitForText("本章改变了什么 · 一两句")
        compose.onAllNodes(hasSetTextAction() and hasText("本章改变了什么 · 一两句", substring = true)).onFirst().performTextInput("林月和沈默从互相提防变成暂时合作")
        waitFor { store.card(workId, chapterId).change == "林月和沈默从互相提防变成暂时合作" }

        scrollTo("伏笔动作")
        compose.onAllNodesWithText("伏笔动作").onFirst().performClick()
        waitForText("伏笔动作 · 一行一条")
        val field = compose.onAllNodes(hasSetTextAction() and hasText("伏笔动作 · 一行一条", substring = true)).onLast()
        field.performTextInput("埋：铜灯底座的记号\n")
        field.performTextInput("推进：老周藏起的半本账册")
        waitFor { store.card(workId, chapterId).moves.size == 2 }
        val moves = store.card(workId, chapterId).moves
        assertEquals(listOf(MoveAction.PLANT, MoveAction.ADVANCE), moves.map { it.action })
        assertEquals("老周藏起的半本账册", moves[1].text)
        // 别的项没被动。
        assertEquals("林月在旧仓找到铜灯", store.card(workId, chapterId).facts)
    }
}
