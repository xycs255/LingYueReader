package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.AiCost
import com.xycs.lingyuereader.ai.AiPricing
import com.xycs.lingyuereader.ai.WriteDirection
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
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
import java.net.InetSocketAddress

/** 作品 AI 设置里的“写正文时先思考”，和编辑器里 AI 一个字没写时的出错显示（不是“写了 0 字”，不能采纳，花费照常显示）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingThinkingUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val text = "沈默推开茶馆的门。"

    /** 这句话的请求只思考、不写正文：回空的正文、finish_reason=length。 */
    @Volatile
    private var thinksOnly: (String) -> Boolean = { true }

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    val empty = thinksOnly(user)
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", if (empty) "" else "杜娘没说话，把门又关上一半。"))
                        .put("finish_reason", if (empty) "length" else "stop")))
                        .put("usage", JSONObject().put("prompt_tokens", 20_000).put("completion_tokens", if (empty) 8_192 else 100))
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
            store.saveText(w.id, w.chapters[0].id, text, count = false)
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

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun noText(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isEmpty()

    private fun openEditor(vm: AppViewModel) {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
    }

    @Test
    fun theWorkSettingsSaveThinkingWhileWriting() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        // 和“写正文的温度”在一组，带说明；默认关。
        compose.onNodeWithText("写正文的温度").performScrollTo()
        compose.onNodeWithText("写正文时先思考").performScrollTo().assertIsOff()
        compose.onNodeWithText(THINKING_NOTE).performScrollTo()
        assertTrue(THINKING_NOTE.contains("目前只对官方 DeepSeek V4 起作用"))
        compose.onNodeWithText("写正文时先思考").performClick()
        waitFor { WorkStore(writingDir).assist(workId).thinking }
        compose.onNodeWithText("写正文时先思考").assertIsOn()
        compose.onNodeWithText("写正文时先思考").performClick()
        waitFor { !WorkStore(writingDir).assist(workId).thinking }
        compose.onNodeWithText("写正文时先思考").assertIsOff()
    }

    @Test
    fun anInlineWriteWithNoProseIsAnErrorThatShowsTheCost() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor(vm)
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("从光标处往下写")
        compose.onNodeWithText("开始写").performClick()
        waitForText("模型把这次的输出上限都用在思考上，没写出正文")
        // 不是“写了 0 字、可以采纳后再接着写”。
        assertTrue(noText("AI 写了"))
        assertTrue(noText("可以采纳后再接着写"))
        // 局域网模型关不了思考：不叫作者去关。
        assertTrue(noText("关掉“写正文时先思考”"))
        compose.onNodeWithText("采纳").assertIsNotEnabled()
        compose.onNodeWithText("重写").assertIsEnabled()
        // 花了多少照常显示。
        val inline = vm.writingAi.inline.value!!
        assertEquals(8_192L, inline.usage!!.output)
        waitForText(AiPricing.brief(inline.usage!!, inline.cost ?: AiCost())!!)
        // 撤回：正文不变。
        compose.onNodeWithText("撤回").performClick()
        waitFor { vm.writingAi.inline.value == null }
        assertEquals(text, WorkStore(writingDir).text(workId, chapterId))
    }

    @Test
    fun onlyTheEmptyDirectionShowsTheError() {
        thinksOnly = { user -> "【这一版的方向：${WriteDirection.SURPRISE.label}】" in user }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor(vm)
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("从光标处往下写")
        compose.onNodeWithText("写下一场").performScrollTo().performClick()
        compose.onNodeWithText("出 3 个方向的版本（花费约 3 倍）").performScrollTo().performClick()
        compose.onNodeWithText("开始写").performClick()
        waitForText("意外插入·出错")
        waitForText("冲突升级·写好")
        waitForText("按下不发·写好")
        compose.onNodeWithText("意外插入·出错").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 2 }
        waitForText("模型把这次的输出上限都用在思考上，没写出正文")
        compose.onNodeWithText("采纳").assertIsNotEnabled()
        compose.onNodeWithText("冲突升级·写好").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 0 }
        waitForText("AI 写了")
        compose.onNodeWithText("采纳").assertIsEnabled().performClick()
        waitFor { WorkStore(writingDir).text(workId, chapterId) == text + "\n杜娘没说话，把门又关上一半。" }
    }
}
