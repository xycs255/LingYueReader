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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * “写正文时先思考”：只给官方 DeepSeek 上能开关思考的 V4 写正文请求写 `thinking: disabled`（开关关着时）；
 * 别的请求、别的服务商、别的模型什么都不写。温度被拒重发时思考的设置照旧。
 */
class AiProseThinkingTest {
    private val deepseek = AiConfig(enabled = true).withKey("sk-test")
    private val claude = AiConfig(enabled = true).withPreset(AiPresets.of("claude")).withKey("sk-ant-test")
    private val tools = listOf(AiTool("read_doc", "阅读设定资料", JSONObject().put("type", "object")))
    private val turns get() = JSONArray().put(JSONObject().put("role", "user").put("content", "写"))

    private lateinit var server: HttpServer
    private val requests = mutableListOf<JSONObject>()

    /** 带了 temperature 的请求怎么回：（状态码, 回包）；不带的照常回答。 */
    @Volatile
    private var withTemperature = 200 to ""

    /** 不带温度的请求回什么：（正文, finish_reason）。 */
    @Volatile
    private var reply = "林月推开了门。" to "stop"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(requests) { requests += request }
            val (status, body) = if (request.has("temperature") && withTemperature.first != 200) withTemperature
            else 200 to JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", reply.first)).put("finish_reason", reply.second)))
                .put("usage", JSONObject().put("prompt_tokens", 1000).put("completion_tokens", 8192)).toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    /** 请求发给 api.deepseek.com，实际连到本机的假服务。 */
    private fun officialClient() = AiClient { requested ->
        assertEquals("api.deepseek.com", requested.host)
        URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection
    }

    private fun sent() = synchronized(requests) { requests.toList() }

    private fun JSONObject.thinking(): String? = optJSONObject("thinking")?.getString("type")

    private fun plain(c: AiConfig, prose: ProseSampling?) = JSONObject(AiWire.body(c, "系统", listOf(AiMessage("user", "写")), prose = prose))
    private fun tooled(c: AiConfig, prose: ProseSampling?) = JSONObject(AiToolWire.body(c, "系统", turns, tools, prose = prose))

    @Test
    fun onlyOfficialV4ModelsCanSwitchThinking() {
        assertTrue(AiWire.switchableThinking(deepseek))
        assertTrue(AiWire.switchableThinking(deepseek.copy(model = AiPresets.DEEPSEEK_FLASH)))
        // 旧名 deepseek-v4-flash 官方还暂时转到 Flash，一样能关；大小写、两头空白不算数。
        assertTrue(AiWire.switchableThinking(deepseek.copy(model = " DeepSeek-V4-Flash ")))
        // 已停用的旧型号、别的服务商、自建地址上同名的模型都不算。
        assertFalse(AiWire.switchableThinking(deepseek.copy(model = "deepseek-chat")))
        assertFalse(AiWire.switchableThinking(deepseek.copy(model = "deepseek-reasoner")))
        assertFalse(AiWire.switchableThinking(claude))
        assertFalse(AiWire.switchableThinking(AiConfig(enabled = true, preset = AiPresets.CUSTOM,
            baseUrl = "https://other.example/v1", model = AiPresets.DEEPSEEK_V4_PRO)))
    }

    @Test
    fun proseRequestsOnOfficialV4TurnThinkingOffUnlessTheWorkWantsIt() {
        for (body in listOf(::plain, ::tooled)) {
            // 写正文、开关关着：不思考，温度照写。
            val off = body(deepseek, ProseSampling(temperature = 1.3f, thinking = false))
            assertEquals("disabled", off.thinking())
            assertEquals("1.3", off.get("temperature").toString())
            assertEquals("disabled", body(deepseek, ProseSampling()).thinking())
            assertEquals("disabled", body(deepseek.copy(model = AiPresets.DEEPSEEK_FLASH), ProseSampling()).thinking())
            assertEquals("disabled", body(deepseek.copy(model = "deepseek-v4-flash"), ProseSampling()).thinking())
            // 开着：什么都不写，照服务商默认先想再写。
            assertFalse(body(deepseek, ProseSampling(thinking = true)).has("thinking"))
            assertEquals(1.5, body(deepseek, ProseSampling(1.5f, thinking = true)).getDouble("temperature"), 0.0)
            // 不是写正文的请求（审稿、整理等）：不写。
            assertFalse(body(deepseek, null).has("thinking"))
            assertFalse(body(deepseek, null).has("temperature"))
            // 非官方地址、非 V4 模型、别的协议：不写。
            assertFalse(body(AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "https://other.example/v1",
                model = AiPresets.DEEPSEEK_V4_PRO).withKey("k"), ProseSampling()).has("thinking"))
            assertFalse(body(deepseek.copy(model = "deepseek-chat"), ProseSampling()).has("thinking"))
            assertFalse(body(AiConfig(enabled = true).withPreset(AiPresets.of("qwen")).withKey("k"), ProseSampling()).has("thinking"))
            assertFalse(body(claude, ProseSampling()).has("thinking"))
        }
        // 连接测试照旧关思考（它不是写正文的请求）。
        assertEquals("disabled", JSONObject(AiWire.body(deepseek, "", listOf(AiMessage("user", "好")), disableOfficialDeepSeekThinking = true)).thinking())
    }

    @Test
    fun aRejectedTemperatureIsDroppedButThinkingStaysOff() = runBlocking<Unit> {
        withTemperature = 400 to """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}"""
        val answer = officialClient().stream(deepseek, "系统", listOf(AiMessage("user", "写")), prose = ProseSampling(1.5f))
        assertEquals("林月推开了门。", answer)
        val sent = sent()
        assertEquals(2, sent.size)
        assertEquals(1.5, sent[0].getDouble("temperature"), 0.0)
        assertFalse(sent[1].has("temperature"))
        assertEquals(listOf("disabled", "disabled"), sent.map { it.thinking() })

        synchronized(requests) { requests.clear() }
        assertEquals("林月推开了门。", officialClient().converse(deepseek, "资料", "写", tools, run = { "资料" }, prose = ProseSampling(1.5f)))
        val tooled = sent()
        assertEquals(2, tooled.size)
        assertFalse(tooled[1].has("temperature"))
        assertEquals(listOf("disabled", "disabled"), tooled.map { it.thinking() })
        assertTrue(tooled.all { it.has("tools") })
    }

    @Test
    fun aWholeAnswerCutOffBeforeAnyTextSaysSo() = runBlocking<Unit> {
        // 上限全用在思考上：正文一个字没有、finish_reason=length。
        reply = "" to "length"
        for (tooledRequest in listOf(false, true)) {
            try {
                if (tooledRequest) officialClient().converse(deepseek, "资料", "写", tools, run = { "资料" }, whole = true)
                else officialClient().stream(deepseek, "系统", listOf(AiMessage("user", "写")), whole = true)
                fail("截断要报错")
            } catch (e: AiTruncatedException) {
                assertTrue(e.empty)
            }
        }
        // 写了一些才截断：不是空的，原来的说法不变。
        reply = "林月推开" to "length"
        try {
            officialClient().stream(deepseek, "系统", listOf(AiMessage("user", "写")), whole = true)
            fail("截断要报错")
        } catch (e: AiTruncatedException) {
            assertFalse(e.empty)
            assertEquals(AiClient.TRUNCATED, e.message)
        }
        // 正常结束却一个字没有。
        reply = "" to "stop"
        try {
            officialClient().stream(deepseek, "系统", listOf(AiMessage("user", "写")))
            fail("空回答要报错")
        } catch (e: AiEmptyAnswerException) {
            assertEquals("服务商没有返回内容", e.message)
        }
    }
}
