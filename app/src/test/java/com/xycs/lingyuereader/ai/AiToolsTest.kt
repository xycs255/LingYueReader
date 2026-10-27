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

/** 带工具的对话：AI 先查再答，两种接口的格式都对，服务商不支持工具时去掉重发。 */
class AiToolsTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JSONObject>()
    /** 依次回的每一轮：状态码、回包。 */
    private val replies = ArrayDeque<Pair<Int, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val (code, body) = replies.removeFirst()
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", if (code == 200) "text/event-stream" else "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"
    private fun openAi() = AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "$base/v1", model = "deepseek-chat").withKey("sk-1")
    private fun anthropic() = openAi().copy(protocol = AiProtocol.ANTHROPIC, model = "claude-opus-5-5")
    private val tool = AiTool("read_chapter", "读一章", JSONObject().put("type", "object"))

    @Test
    fun interruptedToolStreamCannotReturnPartialAnswerOrRetryWithoutTools() = runBlocking<Unit> {
        replies += 200 to """
            data: {"choices":[{"delta":{"content":"未完成的正文"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"aborted"}]}

            data: [DONE]

        """.trimIndent()
        try {
            AiClient().converse(openAi().copy(model = AiPresets.DEEPSEEK_V4_PRO), "设定包", "写一章", listOf(tool),
                run = { fail("中断时不应调用工具"); "" }, whole = true, requireTools = true)
            fail("中断的部分正文不能作为完整回答")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("中断"))
        }
        assertEquals(1, requests.size)
        assertTrue(requests.single().has("tools"))
    }

    @Test
    fun openAiCompatibleModelsLookThingsUpThenAnswer() = runBlocking<Unit> {
        // 第一轮：先说一句，再分几段给出要用的工具和参数。
        replies += 200 to """
            data: {"choices":[{"delta":{"content":"我先看看上一章。"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_chapter","arguments":""}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"chapter\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"34\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        replies += 200 to """
            data: {"choices":[{"delta":{"content":"接着写："}}]}

            data: {"choices":[{"delta":{"content":"雪停了。"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val calls = mutableListOf<AiToolCall>()
        var shown = ""
        val answer = AiClient().converse(
            openAi(), "设定包……", "写第三十五章", listOf(tool),
            run = { c -> calls += c; "第三十四章：院里杀了两只羊。" },
            onLookup = { shown = "" },
            onText = { shown += it },
        )
        assertEquals("接着写：雪停了。", answer)
        // 查之前说的话清掉了，界面上只剩回答。
        assertEquals("接着写：雪停了。", shown)
        assertEquals(listOf("read_chapter"), calls.map { it.name })
        assertEquals("34", calls.single().arguments.getString("chapter"))

        val first = requests[0]
        assertEquals("read_chapter", first.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        // 第二轮把 AI 的那条（带工具调用）和工具结果交回去。
        val msgs = requests[1].getJSONArray("messages")
        val assistant = msgs.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("{\"chapter\":\"34\"}", assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        val result = msgs.getJSONObject(3)
        assertEquals("tool", result.getString("role"))
        assertEquals("call_1", result.getString("tool_call_id"))
        assertEquals("第三十四章：院里杀了两只羊。", result.getString("content"))
        // 每轮的系统提示一样：服务商能按相同开头缓存。
        assertEquals(first.getJSONArray("messages").getJSONObject(0).toString(), msgs.getJSONObject(0).toString())
    }

    @Test
    fun deepSeekV4ProKeepsThinkingWithToolCallButOnlyShowsAnswer() = runBlocking<Unit> {
        replies += 200 to """
            data: {"choices":[{"delta":{"reasoning_content":"先核对"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"上一章。","tool_calls":[{"index":0,"id":"call_v4","type":"function","function":{"name":"read_chapter","arguments":"{\"chapter\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"34\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        replies += 200 to """
            data: {"choices":[{"delta":{"reasoning_content":"已经核对。"}}]}

            data: {"choices":[{"delta":{"content":"雪停了。"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val config = openAi().copy(model = "deepseek-v4-pro")
        var shown = ""
        val answer = AiClient().converse(config, "设定包", "继续写", listOf(tool),
            run = { call ->
                assertEquals("read_chapter", call.name)
                assertEquals("34", call.arguments.getString("chapter"))
                "上一章正文"
            },
            onLookup = { shown = "" }, onText = { shown += it },
        )
        assertEquals("雪停了。", answer)
        assertEquals(answer, shown)
        assertEquals("deepseek-v4-pro", requests[0].getString("model"))
        val next = requests[1].getJSONArray("messages")
        val assistant = next.getJSONObject(2)
        assertEquals("先核对上一章。", assistant.getString("reasoning_content"))
        assertTrue(assistant.isNull("content"))
        assertEquals("call_v4", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("上一章正文", next.getJSONObject(3).getString("content"))
    }

    @Test
    fun claudeGetsCacheMarksAndToolResults() = runBlocking<Unit> {
        replies += 200 to """
            data: {"type":"message_start","message":{}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"要看上一章"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read_chapter","input":{}}}

            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"chapter\":"}}

            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"34\"}"}}

            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

            data: {"type":"message_stop"}

        """.trimIndent()
        replies += 200 to """
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"雪停了。"}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            data: {"type":"message_stop"}

        """.trimIndent()
        val answer = AiClient().converse(anthropic(), "设定包整份。本章提示。", "写", listOf(tool), run = { "羊" }, cachePrefix = 6)
        assertEquals("雪停了。", answer)
        val system = requests[0].getJSONArray("system")
        assertEquals("设定包整份。", system.getJSONObject(0).getString("text"))
        assertEquals("ephemeral", system.getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertEquals("本章提示。", system.getJSONObject(1).getString("text"))
        assertEquals("read_chapter", requests[0].getJSONArray("tools").getJSONObject(0).getString("name"))
        // 思考块（带签名）和工具调用原样交回，结果放在 tool_result 里。
        val msgs = requests[1].getJSONArray("messages")
        val blocks = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", blocks.getJSONObject(0).getString("type"))
        assertEquals("sig", blocks.getJSONObject(0).getString("signature"))
        assertEquals("34", blocks.getJSONObject(1).getJSONObject("input").getString("chapter"))
        val result = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("tu_1", result.getString("tool_use_id"))
        assertEquals("羊", result.getString("content"))
    }

    @Test
    fun modelsWithoutToolsGetAPlainRequest() = runBlocking<Unit> {
        replies += 400 to """{"error":{"message":"This model does not support tools"}}"""
        replies += 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"
        assertEquals("好", AiClient().converse(openAi(), "系统", "问", listOf(tool), run = { "x" }))
        assertTrue(requests[0].has("tools"))
        assertFalse(requests[1].has("tools"))
    }

    @Test
    fun afterEnoughLookupsTheModelMustAnswer() = runBlocking<Unit> {
        val call = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"read_chapter\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n"
        replies += 200 to call
        replies += 200 to call
        replies += 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"
        var runs = 0
        assertEquals("答", AiClient().converse(openAi(), "系统", "问", listOf(tool), run = { runs++; "x" }, maxRounds = 2))
        assertEquals(2, runs)
        assertEquals("none", requests[2].getString("tool_choice"))
        assertFalse(requests[1].has("tool_choice"))
    }
}
