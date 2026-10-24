package com.xycs.lingyuereader.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiWireTest {
    private val deepseek = AiConfig(enabled = true).withKey("sk-test")
    private val claude = AiConfig(enabled = true).withPreset(AiPresets.of("claude")).withKey("sk-ant-test")

    @Test
    fun endpointsFollowEachProtocol() {
        assertEquals("https://api.deepseek.com/chat/completions", AiWire.endpoint(deepseek))
        assertEquals("https://x.com/v1/chat/completions", AiWire.endpoint(deepseek.copy(baseUrl = "https://x.com/v1/")))
        assertEquals("https://x.com/v1/chat/completions", AiWire.endpoint(deepseek.copy(baseUrl = "https://x.com/v1/chat/completions")))
        assertEquals("https://api.anthropic.com/v1/messages", AiWire.endpoint(claude))
        assertEquals("https://proxy.cn/v1/messages", AiWire.endpoint(claude.copy(baseUrl = "https://proxy.cn/v1")))
    }

    @Test
    fun keysGoOnlyInTheRightHeader() {
        assertEquals("Bearer sk-test", AiWire.headers(deepseek)["Authorization"])
        val h = AiWire.headers(claude)
        assertEquals("sk-ant-test", h["x-api-key"])
        assertNull(h["Authorization"])
        assertEquals(AiWire.ANTHROPIC_VERSION, h["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", h["anthropic-beta"])
        // 第三方转发的 Anthropic 接口不加测试版参数。
        assertNull(AiWire.headers(claude.copy(baseUrl = "https://proxy.cn"))["anthropic-beta"])
    }

    @Test
    fun openAiBodyPutsTheSystemPromptFirst() {
        val o = JSONObject(AiWire.body(deepseek, "系统", listOf(AiMessage("user", "问"))))
        assertEquals("deepseek-chat", o.getString("model"))
        assertTrue(o.getBoolean("stream"))
        val m = o.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("问", m.getJSONObject(1).getString("content"))
    }

    @Test
    fun anthropicBodyUsesTopLevelSystemAndLowEffortOnClaude5() {
        val o = JSONObject(AiWire.body(claude, "系统", listOf(AiMessage("user", "问"))))
        assertEquals("系统", o.getString("system"))
        assertEquals(1, o.getJSONArray("messages").length())
        assertTrue(o.getInt("max_tokens") > 0)
        assertEquals("low", o.getJSONObject("output_config").getString("effort"))
        assertEquals("default", o.getString("fallbacks"))
        // 没有 temperature：新接口版本不收。
        assertFalse(o.has("temperature"))
        val old = JSONObject(AiWire.body(claude.copy(model = "claude-haiku-4-5"), "", listOf(AiMessage("user", "问"))))
        assertFalse(old.has("output_config"))
        assertFalse(old.has("fallbacks"))
        assertFalse(old.has("system"))
    }

    @Test
    fun parsesOpenAiStreamLines() {
        assertEquals("你好", AiWire.delta(AiProtocol.OPENAI, """{"choices":[{"delta":{"content":"你好"}}]}""").text)
        assertEquals("", AiWire.delta(AiProtocol.OPENAI, """{"choices":[{"delta":{"content":null,"reasoning_content":"想"}}]}""").text)
        assertTrue(AiWire.delta(AiProtocol.OPENAI, " [DONE]").done)
        assertTrue(AiWire.delta(AiProtocol.OPENAI, """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""").refused)
    }

    @Test
    fun parsesAnthropicStreamEvents() {
        val text = AiWire.delta(AiProtocol.ANTHROPIC, """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"释义"}}""")
        assertEquals("释义", text.text)
        assertEquals("", AiWire.delta(AiProtocol.ANTHROPIC, """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}""").text)
        assertTrue(AiWire.delta(AiProtocol.ANTHROPIC, """{"type":"message_delta","delta":{"stop_reason":"refusal"}}""").refused)
        assertTrue(AiWire.delta(AiProtocol.ANTHROPIC, """{"type":"message_stop"}""").done)
        try {
            AiWire.delta(AiProtocol.ANTHROPIC, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
            fail()
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("Overloaded"))
        }
    }

    @Test
    fun readsWholeJsonReplies() {
        assertEquals("好", AiWire.whole(AiProtocol.OPENAI, """{"choices":[{"message":{"role":"assistant","content":"好"}}]}"""))
        assertEquals("好", AiWire.whole(AiProtocol.ANTHROPIC, """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"好"}]}"""))
    }

    @Test
    fun explainsHttpErrorsInChinese() {
        assertTrue(AiWire.error(401, """{"error":{"message":"Invalid API key"}}""").startsWith("密钥不对"))
        assertTrue(AiWire.error(401, """{"error":{"message":"Invalid API key"}}""").contains("Invalid API key"))
        assertEquals("接口地址或模型名不对", AiWire.error(404, "<html>"))
        assertTrue(AiWire.error(503, "").contains("503"))
    }
}
