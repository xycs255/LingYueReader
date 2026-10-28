package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** 写正文的温度：只在 OpenAI 兼容协议的请求体里写；服务商不认时去掉重发一次，别的 400 照原样报出。 */
class AiTemperatureTest {
    private val deepseek = AiConfig(enabled = true).withKey("sk-test")
    private val claude = AiConfig(enabled = true).withPreset(AiPresets.of("claude")).withKey("sk-ant-test")
    private val device = AiConfig(enabled = true).withPreset(AiPresets.of(AiPresets.DEVICE))
    private val tools = listOf(AiTool("read_doc", "阅读设定资料", JSONObject().put("type", "object")))

    private lateinit var server: HttpServer
    private val requests = mutableListOf<JSONObject>()

    /** 带了 temperature 的请求怎么回：（状态码, 回包）。默认照常回答。 */
    @Volatile
    private var withTemperature = 200 to ""

    private fun answer(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", text)).put("finish_reason", "stop"))).toString()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(requests) { requests += request }
            val (status, reply) = if (request.has("temperature") && withTemperature.first != 200) withTemperature
            else 200 to answer(if (request.has("temperature")) "带温度的回答" else "默认温度的回答")
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun local() = AiConfig(enabled = true, preset = AiPresets.CUSTOM,
        baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "fake-reasoner").withKey("fake-key")

    private fun sent() = synchronized(requests) { requests.toList() }

    @Test
    fun onlyOpenAiCompatibleBodiesCarryTheTemperature() {
        val o = JSONObject(AiWire.body(deepseek, "系统", listOf(AiMessage("user", "写")), temperature = 1.3f))
        // 按十进制写：1.3 不写成 1.2999999523。
        assertEquals("1.3", o.get("temperature").toString())
        assertEquals(1.5, JSONObject(AiWire.body(deepseek, "", listOf(AiMessage("user", "写")), temperature = 1.5f)).getDouble("temperature"), 0.0)
        // 没设（服务商默认）：不写这个参数。
        assertFalse(JSONObject(AiWire.body(deepseek, "系统", listOf(AiMessage("user", "写")))).has("temperature"))
        // Anthropic、本机模型都不写。
        assertFalse(JSONObject(AiWire.body(claude, "系统", listOf(AiMessage("user", "写")), temperature = 1.5f)).has("temperature"))
        assertFalse(JSONObject(AiWire.body(device, "系统", listOf(AiMessage("user", "写")), temperature = 1.5f)).has("temperature"))

        // 带工具的请求体也一样。
        val turns = JSONArray().put(JSONObject().put("role", "user").put("content", "写"))
        assertEquals(0.7, JSONObject(AiToolWire.body(deepseek, "系统", turns, tools, temperature = 0.7f)).getDouble("temperature"), 0.0)
        assertFalse(JSONObject(AiToolWire.body(deepseek, "系统", turns, tools)).has("temperature"))
        assertFalse(JSONObject(AiToolWire.body(claude, "系统", turns, tools, temperature = 1.5f)).has("temperature"))
        assertFalse(JSONObject(AiToolWire.body(device, "系统", turns, tools, temperature = 1.5f)).has("temperature"))
    }

    @Test
    fun recognizesOnlyClientErrorsAboutTheTemperature() {
        assertTrue(AiWire.temperatureRejected(400, """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}"""))
        assertTrue(AiWire.temperatureRejected(400, """{"error":{"message":"Unsupported value: 'temperature' does not support 1.5 with this model. Only the default (1) value is supported."}}"""))
        assertTrue(AiWire.temperatureRejected(422, """{"detail":"Temperature must be between 0 and 1"}"""))
        assertTrue(AiWire.temperatureRejected(400, """{"error":{"message":"该模型不支持设置温度"}}"""))
        // 别的参数问题、别的状态码都不算。
        assertFalse(AiWire.temperatureRejected(400, """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model."}}"""))
        assertFalse(AiWire.temperatureRejected(400, """{"error":{"message":"model does not support tool calls"}}"""))
        assertFalse(AiWire.temperatureRejected(401, """{"error":{"message":"invalid key; temperature ignored"}}"""))
        assertFalse(AiWire.temperatureRejected(500, """{"error":{"message":"temperature overloaded"}}"""))
        // “temperatures”“attemperature” 这类不是这个参数。
        assertFalse(AiWire.temperatureRejected(400, """{"error":{"message":"attemperature"}}"""))
    }

    @Test
    fun streamDropsARejectedTemperatureAndRetriesOnce() = runBlocking<Unit> {
        withTemperature = 400 to """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}"""
        val pieces = StringBuilder()
        val answer = AiClient().stream(local(), "系统", listOf(AiMessage("user", "写")), temperature = 1.5f) { pieces.append(it) }
        assertEquals("默认温度的回答", answer)
        assertEquals("默认温度的回答", pieces.toString())
        val sent = sent()
        assertEquals(2, sent.size)
        assertEquals(1.5, sent[0].getDouble("temperature"), 0.0)
        assertFalse(sent[1].has("temperature"))
    }

    @Test
    fun converseDropsARejectedTemperatureButKeepsTheTools() = runBlocking<Unit> {
        withTemperature = 400 to """{"error":{"message":"temperature does not support 1.5 with this model"}}"""
        var dropped = false
        val answer = AiClient().converse(local(), "资料", "写", tools, run = { "资料" }, temperature = 1.5f,
            onToolsDropped = { dropped = true })
        assertEquals("默认温度的回答", answer)
        assertFalse("去掉的是温度，不是工具", dropped)
        val sent = sent()
        assertEquals(2, sent.size)
        assertTrue(sent[0].has("temperature"))
        assertFalse(sent[1].has("temperature"))
        assertTrue(sent.all { it.has("tools") })
    }

    @Test
    fun otherClientErrorsAreNotRetried() = runBlocking<Unit> {
        withTemperature = 400 to """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model."}}"""
        for (tooled in listOf(false, true)) {
            synchronized(requests) { requests.clear() }
            try {
                if (tooled) AiClient().converse(local(), "资料", "写", tools, run = { "资料" }, temperature = 1.5f)
                else AiClient().stream(local(), "系统", listOf(AiMessage("user", "写")), temperature = 1.5f)
                fail("别的 400 要照原样报出")
            } catch (e: AiException) {
                assertTrue(e.message.orEmpty().contains("max_tokens"))
            }
            assertEquals("不重发，免得重复计费", 1, sent().size)
        }
    }

    @Test
    fun withoutATemperatureNothingIsDroppedOrRetried() = runBlocking<Unit> {
        withTemperature = 400 to """{"error":{"message":"temperature is not supported"}}"""
        // 没设温度：请求里没有它，照常回答，只发一次。
        assertEquals("默认温度的回答", AiClient().stream(local(), "系统", listOf(AiMessage("user", "写"))))
        assertEquals(1, sent().size)
        assertFalse(sent().single().has("temperature"))
    }
}
