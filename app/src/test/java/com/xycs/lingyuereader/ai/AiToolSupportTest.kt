package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** A local fake provider verifies that source-dependent writing never silently drops tools. */
class AiToolSupportTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JSONObject>()

    /** 带工具的请求怎么回：（状态码, Content-Type, 回包）。默认是局域网服务那种“不支持工具”的 400。 */
    @Volatile
    private var withToolsReply = Triple(400, "application/json", """{"error":{"message":"model does not support tool calls"}}""")

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(requests) { requests += request }
            val withTools = request.has("tools")
            val (status, type, reply) = if (withTools) withToolsReply
            else Triple(200, "application/json", """{"choices":[{"message":{"content":"普通回答"},"finish_reason":"stop"}]}""")
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun config() = AiConfig(enabled = true, preset = AiPresets.CUSTOM,
        baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "fake-v4").withKey("fake-key")

    private val tools = listOf(AiTool("read_doc", "阅读设定资料", JSONObject().put("type", "object")))

    @Test
    fun projectTaskFailsClosedWhenModelRejectsTools() = runBlocking<Unit> {
        try {
            AiClient().converse(config(), "已定资料", "核对本章", tools, run = { "资料" }, requireTools = true)
            fail("Unsupported tool calls must not become an unverified answer")
        } catch (e: AiException) {
            assertTrue(e.message.orEmpty().contains("不支持设定包查阅工具"))
        }
        val sent = synchronized(requests) { requests.toList() }
        assertEquals(1, sent.size)
        assertTrue(sent.single().has("tools"))
    }

    @Test
    fun ordinaryAssistantRetainsNoToolFallback() = runBlocking<Unit> {
        var dropped = false
        val answer = AiClient().converse(config(), "普通助手", "问候", tools, run = { "资料" }, onToolsDropped = { dropped = true })
        assertEquals("普通回答", answer)
        assertTrue("去掉工具重发时要告诉调用方", dropped)
        val sent = synchronized(requests) { requests.toList() }
        assertEquals(2, sent.size)
        assertTrue(sent[0].has("tools"))
        assertFalse(sent[1].has("tools"))
    }

    /** 参数不兼容之类的 400：原样报出，不去掉工具重发，也不说成“不支持工具”。 */
    @Test
    fun otherClientErrorsSurfaceUnchangedWithoutRetry() = runBlocking<Unit> {
        withToolsReply = Triple(400, "application/json",
            """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""")
        for (require in listOf(false, true)) {
            synchronized(requests) { requests.clear() }
            var dropped = false
            try {
                AiClient().converse(config(), "渡口", "林月问路", tools, run = { "资料" }, requireTools = require, onToolsDropped = { dropped = true })
                fail("参数不兼容不能变成一次不带工具的回答")
            } catch (e: AiException) {
                assertFalse(e is AiToolsUnsupportedException)
                assertTrue(e.message, e.message.orEmpty().contains("max_tokens"))
                assertFalse(e.message, e.message.orEmpty().contains("不支持设定包查阅工具"))
            }
            assertFalse(dropped)
            assertEquals(1, synchronized(requests) { requests.size })
        }
    }

    /** 回答流中途报错（哪怕字面上说不支持工具）：前面的部分已经计费，不重发。 */
    @Test
    fun midStreamErrorIsNotRetried() = runBlocking<Unit> {
        withToolsReply = Triple(200, "text/event-stream",
            "data: {\"choices\":[{\"delta\":{\"content\":\"沈默在渡口\"}}]}\n\n" +
                "data: {\"error\":{\"message\":\"model does not support tools\"}}\n\n")
        var dropped = false
        try {
            AiClient().converse(config(), "渡口", "沈默等船", tools, run = { "资料" }, onToolsDropped = { dropped = true })
            fail("流中途的报错要原样报出")
        } catch (e: AiException) {
            assertFalse(e is AiToolsUnsupportedException)
            assertTrue(e.message, e.message.orEmpty().contains("服务商报错"))
        }
        assertFalse(dropped)
        assertEquals(1, synchronized(requests) { requests.size })
    }

    @Test
    fun onlyExplicitToolRejectionsCountAsUnsupported() {
        listOf(
            "registry.local/library/duko:7b does not support tools",
            "model does not support tool calls",
            "This model doesn't support function calling",
            "tools are not supported for this model",
            "tool_choice none not supported",
            "Function calling is not enabled for this model",
            "Unsupported parameter: 'tools'",
            "\"auto\" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set",
            "该模型不支持工具调用",
        ).forEach { assertTrue(it, AiToolWire.unsupported(it)) }
        listOf(
            "Unsupported parameter: 'max_tokens' is not supported with this model.",
            "temperature does not support 0.2 with this model",
            "Invalid tool schema: properties must be an object",
            "The model `fake-v4` does not exist",
            "This model does not support images",
            "",
        ).forEach { assertFalse(it, AiToolWire.unsupported(it)) }
        assertFalse(AiToolWire.unsupported(null))
    }

    /** 只是不支持并行调用工具：工具本身能用，不能当成“不支持工具”去掉工具重发。 */
    @Test
    fun parallelToolCallsUnsupportedIsNotToolsUnsupported() {
        assertFalse(AiToolWire.unsupported("parallel tool calls are not supported for this model"))
        assertFalse(AiToolWire.unsupported("Parallel tool calls is not supported"))
        assertTrue(AiToolWire.unsupported("tool calls are not supported for this model"))
    }
}
