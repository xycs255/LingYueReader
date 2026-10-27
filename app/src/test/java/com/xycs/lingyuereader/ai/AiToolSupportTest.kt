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

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(requests) { requests += request }
            val withTools = request.has("tools")
            val status = if (withTools) 400 else 200
            val reply = if (withTools) """{"error":{"message":"model does not support tool calls"}}"""
            else """{"choices":[{"message":{"content":"普通回答"},"finish_reason":"stop"}]}"""
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
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
        val answer = AiClient().converse(config(), "普通助手", "问候", tools, run = { "资料" })
        assertEquals("普通回答", answer)
        val sent = synchronized(requests) { requests.toList() }
        assertEquals(2, sent.size)
        assertTrue(sent[0].has("tools"))
        assertFalse(sent[1].has("tools"))
    }
}
