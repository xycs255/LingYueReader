package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteDirection
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 官方 DeepSeek V4 上的“写正文时先思考”：写正文的请求默认关思考、编辑器里写压到 8192；开着时不写参数、用整章的上限。
 * 审稿、拟细纲这些照服务商默认。AI 一个字没写（上限全用在思考上）时如实报错，不当成“写了 0 字”给人采纳。
 */
class WritingThinkingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他。"

    /** 收到的请求：最后一句用户的话、请求体。 */
    private data class Asked(val user: String, val body: JSONObject) {
        val thinking: String? get() = body.optJSONObject("thinking")?.getString("type")
        val maxTokens: Int? get() = if (body.has("max_tokens")) body.getInt("max_tokens") else null
    }
    private val asked = CopyOnWriteArrayList<Asked>()

    /** 这句话的请求只思考、不写正文：回空的正文和这个 finish_reason（空是照常回答）。 */
    @Volatile
    private var emptyWhen: (String) -> String? = { null }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += Asked(user, body)
                val empty = emptyWhen(user)
                val answer = when {
                    empty != null -> ""
                    "细纲卡" in user && "JSON" in user -> """{"facts":"沈默到渡口"}"""
                    else -> "林月在船头等着。"
                }
                val reply = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", empty ?: "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 20_000).put("completion_tokens", if (empty == "length") 8_192 else 100))
                val bytes = reply.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡口")
        workId = w.id
        chapterId = w.chapters[0].id
        store.saveText(workId, chapterId, text, count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "沈默到渡口，老周已经走了"))
        // 官方 DeepSeek V4 Pro：请求发给 api.deepseek.com，实际连到本机的假服务。
        val client = AiClient { requested ->
            assertEquals("api.deepseek.com", requested.host)
            URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection
        }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true).withKey("sk-test"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    /** 跑一件事，等它发出的请求回来、做完，返回这件事发的请求。 */
    private fun requestsOf(what: String, start: () -> Unit, done: () -> Boolean): List<Asked> {
        asked.clear()
        start()
        waitFor(what) { asked.isNotEmpty() && done() }
        return asked.toList()
    }

    private fun inline(variants: Boolean = false): List<Asked> = requestsOf("在编辑器里写", {
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE, variants = variants)
    }) { writing.inline.value?.let { !it.anyLoading } == true }

    private fun draft(): List<Asked> = requestsOf("写本章", { writing.writeDraft(workId, chapterId) }) {
        writing.drafts.value[workId to chapterId]?.loading == false
    }

    private fun task(task: WriteAiTask): Asked = requestsOf(task.title, { writing.run(workId, chapterId, text, task) }) {
        writing.state.value?.loading == false
    }.single()

    @Test
    fun proseRequestsTurnThinkingOffByDefaultAndNothingElseDoes() {
        assertFalse(store.assist(workId).thinking)
        // 编辑器里写：关思考，上限压到 8192。
        val written = inline().single()
        assertEquals("disabled", written.thinking)
        assertEquals(8_192, written.maxTokens)
        assertEquals("林月在船头等着。", writing.inline.value!!.text.trim())
        writing.discardInline()
        // 3 个方向：每一路都一样。
        val three = inline(variants = true)
        assertEquals(3, three.size)
        assertTrue(three.all { it.thinking == "disabled" && it.maxTokens == 8_192 })
        writing.discardInline()
        // 写整章草稿：关思考，用整章的上限。
        val chapter = draft().single()
        assertEquals("disabled", chapter.thinking)
        assertEquals(32_768, chapter.maxTokens)
        // 润色这类改稿：关思考；挑错、灵感、读者点评照服务商默认。
        listOf(WriteAction.POLISH, WriteAction.EXPAND, WriteAction.CUSTOM).forEach { action ->
            assertEquals(action.label, "disabled", task(WriteAiTask.Edit(action, "杜娘抬头看他。", 10, 17, instruction = "多写神态")).thinking)
        }
        assertNull(task(WriteAiTask.Edit(WriteAction.PROOFREAD, "杜娘抬头看他。", 10, 17)).thinking)
        assertNull(task(WriteAiTask.Idea(IdeaKind.NEXT)).thinking)
        assertNull(task(WriteAiTask.Review).thinking)
        // 拟细纲：不是写正文，什么都不写。
        var planned = false
        assertNull(requestsOf("拟细纲", { runBlocking { runCatching { writing.planCard(workId, chapterId) }; planned = true } }) { planned }.single().thinking)
        // 摘要只是概括一段：关思考（见 summariesAndSyncNeverThink）。
        var summarized = false
        assertEquals("disabled", requestsOf("章节摘要", { runBlocking { writing.summarize(workId, chapterId) }; summarized = true }) { summarized }.single().thinking)
    }

    /**
     * 整理入账、写摘要：要完整的 JSON 或一段短答，用不着先想。V4 默认开着思考，思考的 token 也算进上限，
     * 收尾那次整理加摘要的上限只有 5120，以前会被思考吃光、报“回答太长”。作品开着“写正文时先思考”也一样关；不带温度。
     */
    @Test
    fun summariesAndSyncNeverThink() {
        store.updateAssist(workId) { it.copy(thinking = true, temperature = 1.1f) }
        val sync = requestsOf("整理本章", { writing.syncChapter(workId, chapterId) }) {
            writing.sync.value?.let { it.running == null && it.results.isNotEmpty() } == true
        }
        assertTrue(sync.isNotEmpty())
        assertTrue(sync.all { it.thinking == "disabled" && !it.body.has("temperature") })
        var summarized = false
        val summary = requestsOf("章节摘要", { runBlocking { writing.summarize(workId, chapterId) }; summarized = true }) { summarized }.single()
        assertEquals("disabled", summary.thinking)
        assertFalse(summary.body.has("temperature"))
        // 审稿照服务商默认（要判断前后矛盾，思考有用，也不设上限）。
        assertNull(task(WriteAiTask.Review).thinking)
    }

    @Test
    fun thinkingOnSendsNothingAndUsesTheFullBudget() {
        store.updateAssist(workId) { it.copy(thinking = true) }
        val written = inline().single()
        assertFalse(written.body.has("thinking"))
        assertEquals(32_768, written.maxTokens)
        writing.discardInline()
        assertTrue(inline(variants = true).all { !it.body.has("thinking") && it.maxTokens == 32_768 })
        writing.discardInline()
        val chapter = draft().single()
        assertFalse(chapter.body.has("thinking"))
        assertEquals(32_768, chapter.maxTokens)
    }

    @Test
    fun inlineThatOnlyThoughtIsAnErrorAndCannotBeAccepted() {
        emptyWhen = { "length" }
        store.updateAssist(workId) { it.copy(thinking = true) }
        inline()
        val thought = writing.inline.value!!
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文。可以在作品 AI 设置里关掉“写正文时先思考”再试", thought.error)
        assertEquals("", thought.text)
        assertNull(thought.note)
        // 花费照常记着。
        assertEquals(8_192L, thought.usage!!.output)
        assertNotNull(thought.cost)
        assertNull(writing.acceptInline(text))
        assertEquals(text, store.text(workId, chapterId))

        // 关着思考也截断了：只说原因，不叫作者去关。（改的是写作 AI 记着的设置，和设置页一样。）
        writing.updateAssist(workId) { it.copy(thinking = false) }
        inline()
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文", writing.inline.value!!.error)

        // 正常结束、一个字没有。
        emptyWhen = { "stop" }
        inline()
        assertEquals("AI 没有写出正文，再试一次", writing.inline.value!!.error)
        assertNull(writing.acceptInline(text))
    }

    @Test
    fun onlyTheEmptyDirectionFails() {
        emptyWhen = { user -> "length".takeIf { "【这一版的方向：${WriteDirection.SURPRISE.label}】" in user } }
        assertEquals(3, inline(variants = true).size)
        val d = writing.inline.value!!
        val failed = d.variants.single { it.direction == WriteDirection.SURPRISE }
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文", failed.error)
        assertEquals("", failed.text)
        d.variants.filter { it.direction != WriteDirection.SURPRISE }.forEach { v ->
            assertNull(v.error)
            assertEquals("林月在船头等着。", v.text.trim())
        }
        // 选中出错的那一版不能采纳；另一版照样能。
        writing.selectInline(d.variants.indexOf(failed))
        assertNull(writing.acceptInline(text))
        writing.selectInline(0)
        assertNotNull(writing.acceptInline(text))
    }

    @Test
    fun aDraftThatOnlyThoughtSaysSo() {
        emptyWhen = { "length" }
        store.updateAssist(workId) { it.copy(thinking = true) }
        draft()
        val d = writing.drafts.value[workId to chapterId]!!
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文。可以在作品 AI 设置里关掉“写正文时先思考”再试", d.error)
        assertEquals("", d.text)
        assertEquals(8_192L, d.usage!!.output)

        writing.updateAssist(workId) { it.copy(thinking = false) }
        emptyWhen = { "stop" }
        draft()
        assertEquals("AI 没有写出正文，再试一次", writing.drafts.value[workId to chapterId]!!.error)
    }

    @Test
    fun aBeatThatOnlyThoughtSaysSo() {
        store.saveCard(workId, chapterId, ChapterCard(facts = "沈默到渡口", beats = "1. 沈默赶到渡口\n2. 杜娘开门"))
        emptyWhen = { "length" }
        draft()
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文", writing.drafts.value[workId to chapterId]!!.error)
    }
}
