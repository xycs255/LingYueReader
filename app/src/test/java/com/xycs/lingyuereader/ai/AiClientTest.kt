package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** 连本机的假服务：看请求发得对不对、流式回答能不能一段段收到、出错时说什么。 */
class AiClientTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Pair<Map<String, String>, JSONObject>>()
    /** 下一次请求的回应：状态码、Content-Type、回包。 */
    private var reply: Triple<Int, String, String> = Triple(200, "text/event-stream", "")

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            requests += headers to JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val (code, type, body) = reply
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"
    private fun openAi() = AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "$base/v1", model = "m").withKey("sk-1")
    private fun anthropic() = openAi().copy(protocol = AiProtocol.ANTHROPIC, model = "claude-opus-5-5")

    @Test
    fun streamsOpenAiAnswersPieceByPiece() = runBlocking<Unit> {
        reply = Triple(
            200, "text/event-stream",
            """
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"林舟"}}]}

            : keep-alive

            data: {"choices":[{"delta":{"content":"来自江南。"}}]}

            data: [DONE]

            """.trimIndent(),
        )
        val pieces = mutableListOf<String>()
        val answer = AiClient().stream(openAi(), "系统", listOf(AiMessage("user", "问")), onText = pieces::add)
        assertEquals("林舟来自江南。", answer)
        assertEquals(listOf("林舟", "来自江南。"), pieces)
        val (headers, body) = requests.single()
        assertEquals("Bearer sk-1", headers["authorization"])
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun streamsAnthropicAnswers() = runBlocking<Unit> {
        reply = Triple(
            200, "text/event-stream; charset=utf-8",
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"m","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"好"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent(),
        )
        assertEquals("好", AiClient().stream(anthropic(), "系统", listOf(AiMessage("user", "问"))))
        val (headers, body) = requests.single()
        assertEquals("sk-1", headers["x-api-key"])
        assertNull(headers["authorization"])
        assertEquals("系统", body.getString("system"))
        // 不是官方地址，不加拒答自动换模型的参数。
        assertFalse(body.has("fallbacks"))
    }

    @Test
    fun readsNonStreamingReplies() = runBlocking<Unit> {
        reply = Triple(200, "application/json", """{"choices":[{"message":{"content":"整段"}}]}""")
        assertEquals("整段", AiClient().stream(openAi(), "", listOf(AiMessage("user", "问"))))
    }

    @Test
    fun explainsBadKeys() = runBlocking<Unit> {
        reply = Triple(401, "application/json", """{"error":{"message":"Incorrect API key provided"}}""")
        try {
            AiClient().stream(openAi(), "", listOf(AiMessage("user", "问")))
            fail()
        } catch (e: AiException) {
            assertTrue(e.message!!.startsWith("密钥不对"))
            assertFalse(e.message!!.contains("sk-1"))
        }
    }

    @Test
    fun saysWhenTheModelRefuses() = runBlocking<Unit> {
        reply = Triple(200, "text/event-stream", "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}\n\n")
        assertEquals("模型拒绝回答这个问题。", AiClient().stream(anthropic(), "", listOf(AiMessage("user", "问"))))
    }

    @Test
    fun askBookSendsOnlyWhatWasReadAndKeepsSources() = runBlocking<Unit> {
        reply = Triple(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"他从江南来[1]。\"}}]}\n\ndata: [DONE]\n\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("ai").toFile()
        val assistant = AiAssistant(scope, null, bookFile = { id, name -> File(dir, "$id-$name") })
        assistant.saveConfig(openAi())
        val content = BookContent(
            "书", null,
            listOf(
                Chapter("第一章", listOf(Block.Para("林舟从江南来，找他的舅舅。"))),
                Chapter("第二章", listOf(Block.Para("雨夜。"), Block.Para("原来林舟是老周的儿子。"))),
            ),
        )
        assistant.startAskBook(AiBook("b", "书", null, content), ReadPos(1, 0))
        assistant.ask("林舟从哪里来？")
        val turn = withTimeout(5_000) {
            while (assistant.session.value?.turns?.lastOrNull()?.loading != false) delay(10)
            assistant.session.value!!.turns.last()
        }
        assertNull(turn.error)
        assertEquals("他从江南来[1]。", turn.answer)
        assertTrue(turn.sources.first().text.contains("江南"))
        assertEquals("林舟从哪里来？", turn.replay)
        // 先让模型改写检索词，再带着找到的原文回答；两次都不带没读到的内容。
        assertEquals(2, requests.size)
        assertTrue(requests[0].second.toString().contains("检索词"))
        val sent = requests[1].second.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(sent.contains("江南"))
        requests.forEach { assertFalse(it.second.toString().contains("儿子")) }
        // 答完的对话存进对话记录。
        withTimeout(5_000) { while (!File(dir, "b-ai-chats.json").exists()) delay(10) }
        val saved = AiHistory { File(dir, "b-ai-chats.json") }.list().single()
        assertEquals("林舟从哪里来？", saved.title)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun recapIsCachedForTheChapter() = runBlocking<Unit> {
        reply = Triple(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"提要\"}}]}\n\ndata: [DONE]\n\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("ai").toFile()
        val assistant = AiAssistant(scope, null, bookFile = { id, name -> File(dir, "$id-$name") })
        assistant.saveConfig(openAi())
        val book = AiBook("b", "书", null, BookContent("书", null, listOf(Chapter("第一章", listOf(Block.Para("开头。"), Block.Para("接着。"))))))
        assistant.startRecap(book, ReadPos(0, 1))
        withTimeout(5_000) {
            while (assistant.session.value?.turns?.lastOrNull()?.loading != false) delay(10)
            while (!File(dir, "b-ai-recap.json").exists()) delay(10)
        }
        assistant.close()
        assistant.startRecap(book, ReadPos(0, 1))
        val s = assistant.session.value!!
        assertEquals("提要", s.turns.single().answer)
        assertTrue(s.cachedAt > 0)
        assertEquals(1, requests.size)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun recapSaveFailureIsShownAndCorruptExistingCacheDoesNotTriggerAPaidRetry() = runBlocking<Unit> {
        reply = Triple(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"提要\"}}]}\n\ndata: [DONE]\n\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("recap-save").toFile()
        try {
            val book = AiBook("b", "书", null, BookContent("书", null, listOf(Chapter("第一章", listOf(Block.Para("开头。"))))))
            val cache = File(dir, "b-ai-recap.json")
            val blocked = File(dir, "b-ai-recap.json.tmp").apply { mkdir() }
            val assistant = AiAssistant(scope, null, bookFile = { id, name -> File(dir, "$id-$name") })
            assistant.saveConfig(openAi())
            assistant.startRecap(book, ReadPos(0, 0, 3))
            withTimeout(5_000) { while (assistant.session.value?.turns?.lastOrNull()?.error == null) delay(10) }
            assertTrue(assistant.session.value!!.turns.last().error.orEmpty().contains("本机保存失败"))
            assertFalse(cache.exists())
            blocked.delete()
            requests.clear()
            cache.writeText("{}")
            assistant.close()
            assistant.startRecap(book, ReadPos(0, 0, 3))
            assertTrue(assistant.session.value!!.turns.last().error.orEmpty().contains("缓存读取失败"))
            assertTrue("旧缓存损坏时不能自动重复计费", requests.isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
