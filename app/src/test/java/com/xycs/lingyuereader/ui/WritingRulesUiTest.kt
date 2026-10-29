package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingRulesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val name = TestName()
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val seed = object : ExternalResource() {
        override fun before() {
            // 假服务商：整理规则时回一份带 ``` 包裹、有一行看不懂的规则。
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    prompts += msgs.getJSONObject(msgs.length() - 1).getString("content")
                    val answer = "```\n# 出自写作规范\n至多 1：点了点头\n禁用：您\n旁白要有人格\n```"
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
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
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.saveText(w.id, w.chapters[0].id, "他点了点头。老周点了点头。\n她看了他一眼。")
            val (_, second) = store.addChapter(w.id, w.volumes[0].id)!!
            store.saveText(w.id, second.id, "雪停了。")
            store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "每章至多一次点了点头；不用“您”。")
            if (name.methodName.startsWith("editor")) store.addPackDoc(w.id, PackKind.RULES, "检查规则", "至多 1：点了点头\n字数：至少 100\n")
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    /** 按钮可能先显示、等数据读好才可点（慢机器上更明显）：等它可点再点，点早了会被忽略。 */
    private fun waitForEnabled(text: String) = waitFor { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun selection(): TextRange = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test
    fun editorChecksTheChapterAndJumpsToEachPlace() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId, check = true)) }
        waitForText("「点了点头」2 次，至多 1")
        waitForText("本章 20 字，少于 100")
        // 从检查全书点进来：问题面板停在“规则”，状态行合成一个数。
        waitForText("2 处问题")
        waitForText("规则 2")
        // 字数这条没有位置，只有说明；点“跳到位置”依次选中它在正文里出现的地方。
        assertEquals(1, compose.onAllNodesWithText("跳到位置").fetchSemanticsNodes().size)
        compose.onNodeWithText("跳到位置").performClick()
        waitFor { selection() == TextRange(1, 5) }
        compose.onNodeWithText("跳到位置").performClick()
        waitFor { selection() == TextRange(8, 12) }

        // 定稿前提醒还有几条不合规则（不拦着）。
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("章节状态").performClick()
        compose.onNode(hasText("定稿") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForText("这一章还有 2 条不合")
        compose.onNodeWithText("取消").performClick()

        // 改掉一处，这一条就没了。
        compose.onNodeWithContentDescription("正文").performTextReplacement("他点了点头。老周没接话。\n她看了他一眼。")
        waitFor { compose.onAllNodesWithText("「点了点头」", substring = true).fetchSemanticsNodes().isEmpty() }
        waitForText("1 处问题")
    }

    @Test
    fun aiDraftsRulesThenTheWholeWorkIsChecked() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.WorkRules(workId)) }
        waitForText("还没有规则")
        waitForEnabled("用 AI 从写作规范整理")
        compose.onNodeWithText("用 AI 从写作规范整理").performClick()
        waitForText("读懂 2 条，1 行没看懂")
        assertTrue("每章至多一次点了点头" in prompts.single())
        compose.onNodeWithText("存为检查规则").performClick()
        waitFor { store.pack(workId).any { it.kind == PackKind.RULES } }
        val doc = store.pack(workId).single { it.kind == PackKind.RULES }
        assertEquals("# 出自写作规范\n至多 1：点了点头\n禁用：您\n旁白要有人格\n", store.packText(workId, doc.id))
        // 规则逐条用白话列出来，看不懂的一行标出来。
        waitForText("每章各至多 1 次：点了点头")
        waitForText("不用：您")
        waitForText("旁白要有人格")

        compose.onNodeWithText("检查全书").performScrollTo().performClick()
        waitForText("共 2 章，1 章有不合规则的地方")
        compose.onNodeWithText("第1章").performScrollTo().performClick()
        waitForText("「点了点头」2 次，至多 1")
    }
}
