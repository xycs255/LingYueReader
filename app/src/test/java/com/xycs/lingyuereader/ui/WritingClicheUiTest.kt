package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.WritePrompts
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
import java.time.LocalDate
import java.util.Collections

/**
 * 去 AI 腔的界面：编辑器里标出 AI 腔、状态行的“N 处问题”按处数算，点到一处能加进黑名单；
 * “去 AI 腔”把标出的段发给（假的）AI，结果一条条改或忽略（写作建议没开也能用）；
 * AI 接着写完还没采纳时提醒“这段有 N 处 AI 腔”；作品 AI 设置里的开关和我的黑名单页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingClicheUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 发给假服务商的每个请求里用户那条消息。 */
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "林月嘴角勾起，看着窗外的雨。\n沈默倒吸一口凉气，没有说话。\n顾长风推门进来。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    prompts += user
                    val answer = when {
                        "请去掉这些 AI 腔" in user -> JSONObject().put("issues", JSONArray()
                            .put(JSONObject().put("p", 1).put("wrong", "林月嘴角勾起，").put("right", "林月笑了一下，").put("reason", "换成动作"))
                            .put(JSONObject().put("p", 2).put("wrong", "倒吸一口凉气，").put("right", "").put("reason", "删掉"))).toString()
                        "【光标在这里】" in user -> "杜娘嘴角勾起，没再说话。"
                        else -> "好。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                        .put("usage", JSONObject().put("prompt_tokens", 800).put("completion_tokens", 60))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                start()
            }
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            File(files, "ai-usage.json").delete()
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.saveText(w.id, w.chapters[0].id, text)
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
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

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun openEditor() {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun clichesAreMarkedCountedAndCanBeBanned() {
        openEditor()
        // 状态行：AI 腔按处数算进“N 处问题”（这章没有检查规则，写作建议没开）。
        waitForText("2 处问题")
        // 光标点到“嘴角勾起”：提示是 AI 腔，可以“以后都不要”。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(3))
        waitForText("以后都不要")
        assertTrue(compose.onAllNodesWithText("「嘴角勾起」", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("以后都不要").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.assist(workId).banned == listOf("嘴角勾起") }
        // 进了黑名单：同一处也算黑名单命中，处数跟着变。
        waitForText("3 处问题")
        // 不花钱：一个请求都没发。
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun declicheSendsMarkedParagraphsAndFixesOneByOne() {
        openEditor()
        waitForText("2 处问题")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        // 有命中才出现入口；写作建议没开也能用。
        compose.onNodeWithText("去 AI 腔").performScrollTo().performClick()
        waitForText("林月笑了一下，")
        val ask = prompts.single { "请去掉这些 AI 腔" in it }
        assertTrue("1. 林月嘴角勾起，看着窗外的雨。" in ask && "「嘴角勾起」" in ask)
        assertTrue("2. 沈默倒吸一口凉气，没有说话。" in ask && "「倒吸一口凉气」" in ask)
        // 没标出的段不发。
        assertFalse("顾长风推门进来" in ask)
        // 写法要求照样带上（在最后的任务卡前面）。
        assertTrue(ask.contains(WritePrompts.PROSE_HEADER))
        // 用量单独记一类“去 AI 腔”。
        waitFor { vm.aiUsage.value.days[LocalDate.now()].orEmpty().any { it.work == workId && it.feature == AiFeature.CLICHE.name } }
        // 每条带“AI 腔”标签（读屏念“AI 腔类建议”）。
        assertEquals(2, compose.onAllNodesWithContentDescription("AI 腔类建议").fetchSemanticsNodes().size)

        // 第一条“改”：正文换掉，记进采纳过的修改（那一句前后）。
        compose.onAllNodesWithText("改").onFirst().performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId).startsWith("林月笑了一下，看着窗外的雨。") }
        waitFor { store.assist(workId).accepted.lastOrNull()?.let { it.action == "去 AI 腔" && it.before == "林月嘴角勾起，看着窗外的雨。" && it.after == "林月笑了一下，看着窗外的雨。" } == true }
        // 第二条“忽略”：正文不动，列表空了，面板收起。
        compose.onNodeWithText("忽略").performClick()
        waitFor { compose.onAllNodesWithText("忽略").fetchSemanticsNodes().isEmpty() && vm.writingAi.realtime.value.issues.isEmpty() }
        Thread.sleep(1_000)
        ShadowLooper.idleMainLooper()
        assertTrue("沈默倒吸一口凉气，没有说话。" in store.text(workId, chapterId))
        assertEquals(1, prompts.count { "请去掉这些 AI 腔" in it })
    }

    @Test
    fun aFixAcceptedFromTheSuggestionsReplacesTheAiOriginal() {
        // 这章是 AI 写的（记了原稿）；去 AI 腔的改法是 AI 提的，采纳以后原稿跟着换，收尾时不算作者改的。
        WorkStore(writingDir).recordAiText(workId, chapterId, text)
        openEditor()
        waitForText("2 处问题")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("去 AI 腔").performScrollTo().performClick()
        waitForText("林月笑了一下，")
        compose.onAllNodesWithText("改").onFirst().performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId).startsWith("林月笑了一下，看着窗外的雨。") }
        waitFor { store.learning(workId).originals[chapterId]?.singleOrNull()?.text?.startsWith("林月笑了一下，看着窗外的雨。\n") == true }
        assertFalse("嘴角勾起" in store.learning(workId).originals.getValue(chapterId).single().text)
    }

    @Test
    fun inlineWriteWarnsAboutClichesButStillAccepts() {
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        compose.onNodeWithText("写 500 字左右").performClick()
        compose.onNodeWithText("开始写").performClick()
        waitForText("这段有 1 处 AI 腔")
        // 不拦采纳。
        compose.onNodeWithText("采纳").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId).endsWith("杜娘嘴角勾起，没再说话。") }
    }

    @Test
    fun settingsSwitchAndBlacklistPage() {
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        compose.onNodeWithText("查 AI 腔").performScrollTo()
        waitForText("本机按内置清单标出套话，不花钱")
        compose.onNodeWithText("查 AI 腔").performClick()
        waitFor { !store.assist(workId).aiCliche }
        compose.onNodeWithText("我的黑名单").performScrollTo().performClick()
        waitForText("不想再看到的说法")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("说来话长")
        compose.onNodeWithText("加入黑名单").performClick()
        waitFor { store.assist(workId).banned == listOf("说来话长") }
        waitForText("1 条")
        compose.onNodeWithContentDescription("从黑名单删去「说来话长」").performClick()
        waitFor { store.assist(workId).banned.isEmpty() }
        waitForText("还没有。")
    }
}
