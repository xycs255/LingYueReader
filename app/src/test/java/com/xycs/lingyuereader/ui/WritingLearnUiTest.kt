package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.FinishStage
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.AiOriginal
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.EditLearning
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.FixReason
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
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
import java.net.InetSocketAddress
import java.util.Collections

/**
 * 从改稿里学的界面：收尾页有“从这章的改稿里学”，确认页能看到候选和例子，勾了的进黑名单、学到的规则，没勾的以后不再提；
 * 作品 AI 设置里“从改稿学到的”能加、能删；草稿页“这段不行”能选原因，原段 / 新段对比后采纳。人名虚构、文本自造。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingLearnUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private var priorId = ""
    private lateinit var server: HttpServer
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val ban = "眼中闪过一丝慌乱"
    private val draft = "顾长风推门进来，带进一身雨气。\n林月抬头看他，没说话。\n杜娘把茶碗放下，转身去关窗。"
    private val rewritten = "林月攥紧了袖口，半天才挤出一句：“你还知道回来。”"
    private val aiRule = "动作写在对白前面，别补解释"

    private fun cut(n: Int): Pair<String, String> {
        val ai = List(n) { i -> "林月${ban}，把第${i + 1}盏灯笼挂上船头。" } + "老周在岸上喊了一声，杜娘没应。"
        val fin = List(n) { i -> "林月把第${i + 1}盏灯笼挂上船头。" } + "老周在岸上喊了一声，杜娘没应。"
        return ai.joinToString("\n") to fin.joinToString("\n")
    }

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += user
                    val answer = when {
                        "请归纳作者反复的改法" in user -> """{"rules":[{"kind":"rule","text":"$aiRule","p":1}]}"""
                        "要重写的这一段" in user -> rewritten
                        "写到哪里停" in user -> draft
                        else -> "这一章写得很好。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    runCatching { ex.responseBody.use { it.write(bytes) } }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("渡口")
            val (_, second) = store.addChapter(w.id, w.volumes[0].id)!!
            priorId = w.chapters[0].id
            chapterId = second.id
            store.renameChapter(w.id, chapterId, "第二章 灯笼")
            // 前一章：AI 原稿和作者删过两处的定稿，已经收尾学过（记了累积数）。
            val (a1, f1) = cut(2)
            store.saveText(w.id, priorId, f1, count = false)
            store.updateLearning(w.id) {
                val edits = EditLearning.compare(listOf(AiOriginal(1, a1)), f1)!!
                EditLearning.tally(EditLearning.record(it, priorId, a1, 1), priorId, EditLearning.observe(edits, f1), 1)
            }
            // 这一章：AI 写的放进正文，作者又删了一处。
            val (a2, f2) = cut(1)
            store.recordAiText(w.id, chapterId, a2)
            store.saveText(w.id, chapterId, f2, count = false)
            store.saveCard(w.id, chapterId, ChapterCard(facts = "顾长风回到渡口，林月和他闹别扭"))
            // 收尾只学，不整理、不写摘要。整章草稿页不先补细纲。
            store.updateAssist(w.id) { it.copy(finish = FinishOptions(sync = false, summary = false, review = false, learn = true), draftPrep = false) }
            workId = w.id
        }

        override fun after() {
            server.stop(0)
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
    fun finishingShowsCandidatesWithExamplesAndOnlyPickedOnesStick() {
        compose.runOnUiThread { vm.open(Screen.ChapterFinish(workId, chapterId)) }
        waitFor { seen("从这章的改稿里学") }
        compose.onNode(hasText("从这章的改稿里学") and isToggleable()).assertIsOn()
        press("开始")
        waitFor { seen("从改稿里学到的") }
        // 本机比对的黑名单词（默认勾上）和它的例子；AI 归纳的规则默认不勾。
        val banTitle = "黑名单词：「$ban」"
        waitFor { seen(banTitle) }
        compose.onNode(hasText(banTitle) and isToggleable()).assertIsOn()
        assertTrue(seen("原来「林月${ban}，把第1盏灯笼挂上船头。」→ 你改成「林月把第1盏灯笼挂上船头。」"))
        assertTrue(seen("改稿比对：2 章里删了 3 处"))
        val ruleTitle = "写法规则：$aiRule"
        waitFor { seen(ruleTitle) }
        compose.onNode(hasText(ruleTitle) and isToggleable()).assertIsOff()
        assertTrue(asked.any { "请归纳作者反复的改法" in it })
        // AI 归纳的没勾不等于不要：点了“不要”才以后不再提。
        press("不要")
        waitFor { seen("已不要，以后不再提") }
        compose.onNode(hasText(ruleTitle) and isToggleable()).assertIsOff()

        press("完成收尾")
        waitFor { vm.writingAi.finish.value?.stage == FinishStage.DONE }
        waitFor { ban in store.assist(workId).banned }
        assertFalse(aiRule in store.assist(workId).learned)
        // 没勾的记进“不再提”：再算候选也不出现，勾了的也不再出现（已经在黑名单里）。
        val again = EditLearning.candidates(store.learning(workId), store.assist(workId))
        assertTrue(again.none { it.text == aiRule || it.text == ban })
        assertTrue("ai:$aiRule" in store.learning(workId).dismissed)
    }

    @Test
    fun noLearningRowWithoutAiOriginals() {
        compose.runOnUiThread { vm.open(Screen.ChapterFinish(workId, priorId)) }
        waitFor { seen("收尾要做的") }
        // 前一章记过原稿：有这一项。删掉以后再开就没有。
        waitFor { seen("从这章的改稿里学") }
        compose.runOnUiThread { vm.back() }
        waitFor { !has("收尾要做的") }
        store.updateLearning(workId) { it.copy(originals = emptyMap()) }
        compose.runOnUiThread { vm.writingAi.stopFinish(); vm.open(Screen.ChapterFinish(workId, priorId)) }
        waitFor { seen("收尾要做的") }
        waitFor { vm.writingAi.finish.value?.let { it.chapterId == priorId && !it.learnable } == true }
        waitFor { !has("从这章的改稿里学") }
    }

    @Test
    fun learnedRulesCanBeAddedAndDeletedInSettings() {
        store.updateAssist(workId) { it.learn("长句拆短。") }
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        press("从改稿学到的")
        waitFor { has("长句拆短。") }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("对白说完就收住。")
        press("加进去")
        waitFor { "对白说完就收住。" in store.assist(workId).learned }
        waitFor { has("对白说完就收住。") && has("2 条") }
        compose.onNodeWithContentDescription("删去「长句拆短。」").performClick()
        waitFor { store.assist(workId).learned == listOf("对白说完就收住。") }
        waitFor { compose.onAllNodesWithContentDescription("删去「长句拆短。」").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun rejectingAParagraphPicksReasonsAndAcceptsTheComparedRewrite() {
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.let { !it.loading && it.text == draft } == true }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitFor { seen("林月抬头看他，没说话。") }
        waitFor { compose.onAllNodesWithText("这段不行").fetchSemanticsNodes().size == 3 }
        // 读屏分得清是哪一段：第几段、段首几个字。
        assertEquals(1, compose.onAllNodesWithContentDescription("这段不行：第 2 段「林月抬头看他，没…」").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("这段不行：第 1 段", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("这段不行")[1].performScrollTo().performClick()
        waitFor { has("哪里不行") }
        compose.onNode(hasText("没情绪") and isToggleable()).performClick()
        compose.onNode(hasText("其他") and isToggleable()).performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("她该生气")
        compose.onNodeWithText("重写这段").performClick()
        waitFor { seen("新段") }
        assertTrue(seen(rewritten))
        val request = asked.single { "要重写的这一段" in it }
        assertTrue("顾长风推门进来" in request && "杜娘把茶碗放下" in request)
        assertTrue(FixReason.FLAT.fix in request && "作者的意见：她该生气" in request)
        // 采纳了才计数。
        assertEquals(null, store.learning(workId).reasons[FixReason.FLAT.name])
        press("采纳这段")
        waitFor { store.draft(workId, chapterId).split('\n') == listOf("顾长风推门进来，带进一身雨气。", rewritten, "杜娘把茶碗放下，转身去关窗。") }
        waitFor { store.learning(workId).reasons[FixReason.FLAT.name] == 1 }
        waitFor { !has("新段") }
        // 换下来的那版还能换回去。
        assertTrue(seen("换回修订前那一版"))
    }
}
