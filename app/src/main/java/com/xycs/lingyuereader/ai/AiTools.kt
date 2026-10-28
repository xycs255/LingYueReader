package com.xycs.lingyuereader.ai

import org.json.JSONArray
import org.json.JSONObject

/** 给 AI 用的一个工具：名字、说明、参数（JSON Schema）。 */
data class AiTool(val name: String, val description: String, val parameters: JSONObject)

/** AI 要用一次工具：[arguments] 是它给的参数（解析不了时为空对象）。 */
data class AiToolCall(val id: String, val name: String, val arguments: JSONObject)

/**
 * 带工具的对话在两种接口上的格式：请求里怎么写工具、流里怎么拼出 AI 要用的工具、下一轮怎么把工具结果交回去。
 * 只拼字符串、解析字符串，不联网，方便测试。
 * - OpenAI 兼容（DeepSeek、通义千问、Kimi 等）：`tools: [{type:function, function:{name, description, parameters}}]`，
 *   流里 `delta.tool_calls[{index, id, function:{name, arguments（分段）}}]`，`finish_reason: tool_calls`；
 *   结果用 `{role: tool, tool_call_id, content}` 交回。
 * - Anthropic：`tools: [{name, description, input_schema}]`，流里 `content_block_start` 的 `tool_use` 块加 `input_json_delta`，
 *   `stop_reason: tool_use`；思考块（带签名）要原样交回，结果用 `tool_result` 交回。
 */
object AiToolWire {
    /**
     * 一轮的请求体。[system] 的前 [cachePrefix] 个字是每次都一样的部分（设定包的工作说明、知识库）：
     * Anthropic 上标成可缓存；OpenAI 兼容的服务商（DeepSeek 等）自己按相同开头缓存，不用标。
     * [allowTools] 为假时要求这一轮直接作答（查的次数到了上限）。
     */
    fun body(
        c: AiConfig,
        system: String,
        turns: JSONArray,
        tools: List<AiTool>,
        allowTools: Boolean = true,
        cachePrefix: Int = 0,
        maxTokens: Int? = null,
        /** 第二处缓存的位置（文风范例这类紧跟在设定包后面、也很少变的部分的末尾）；0 是没有。 */
        cacheExtra: Int = 0,
        /** 写正文的采样（温度、思考开关）：见 [AiWire.prose]，空是不是写正文的请求，什么都不写。 */
        prose: ProseSampling? = null,
    ): String {
        val o = JSONObject().put("model", c.model.trim()).put("stream", true)
        when (c.protocol) {
            AiProtocol.OPENAI -> {
                if (AiWire.streamUsage(c)) o.put("stream_options", JSONObject().put("include_usage", true))
                val list = JSONArray()
                if (system.isNotEmpty()) list.put(JSONObject().put("role", "system").put("content", system))
                for (i in 0 until turns.length()) list.put(turns.get(i))
                o.put("messages", list)
                if (tools.isNotEmpty()) {
                    o.put("tools", JSONArray().apply {
                        tools.forEach { t ->
                            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)))
                        }
                    })
                    if (!allowTools) o.put("tool_choice", "none")
                }
                if (maxTokens != null) o.put("max_tokens", maxTokens)
                AiWire.prose(o, c, prose)
            }
            AiProtocol.ANTHROPIC -> {
                o.put("max_tokens", maxTokens ?: AiWire.MAX_TOKENS)
                if (system.isNotEmpty()) {
                    // 每处缓存位置切一块、标上 cache_control（最多两处）；最后剩下的不标。
                    val cuts = listOf(cachePrefix, cacheExtra).map { it.coerceIn(0, system.length) }.filter { it > 0 }.distinct().sorted()
                    o.put("system", JSONArray().apply {
                        var from = 0
                        cuts.forEach { cut ->
                            put(JSONObject().put("type", "text").put("text", system.substring(from, cut)).put("cache_control", JSONObject().put("type", "ephemeral")))
                            from = cut
                        }
                        if (from < system.length) put(JSONObject().put("type", "text").put("text", system.substring(from)))
                    })
                }
                o.put("messages", turns)
                if (tools.isNotEmpty()) {
                    o.put("tools", JSONArray().apply { tools.forEach { t -> put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.parameters)) } })
                    if (!allowTools) o.put("tool_choice", JSONObject().put("type", "none"))
                }
            }
            AiProtocol.DEVICE -> {}
        }
        return o.toString()
    }

    /** 作者的话。 */
    fun user(c: AiConfig, text: String): JSONObject = JSONObject().put("role", "user").put("content", text)

    /**
     * 一轮回答拼出来的东西：说的话、要用的工具，以及原样交回下一轮用的那条 assistant 消息。
     * [truncated] 是到了长度上限，[refused] 是拒答，[usage] 是服务商给的这一轮用量（没给为空）。
     */
    class Turn(val text: String, val calls: List<AiToolCall>, val message: JSONObject, val truncated: Boolean, val refused: Boolean, val usage: AiUsage? = null)

    /** 一轮流式回答的拼装器：一行行喂 `data:` 后面的内容，最后 [finish]。 */
    class Collector(private val protocol: AiProtocol, private val strictOfficialDeepSeek: Boolean = false,
                    private val onText: (String) -> Unit) {
        private val text = StringBuilder()
        private val reasoning = StringBuilder()
        private var truncated = false
        private var refused = false
        private var done = false

        /** OpenAI 兼容流的末块必须有非空 finish_reason；只在官方 DeepSeek 校验。 */
        var terminal: Boolean = false
            private set

        /** 服务商在流里给的用量（OpenAI 兼容在最后一块，Anthropic 在 message_start 和 message_delta 里）。 */
        var usage: AiUsage? = null
            private set

        /** 已经收到的字（说的话、思考、工具参数）：中途断了、服务商没给用量时按它估。 */
        val received: String get() = text.toString() + reasoning + openAiCalls.values.joinToString("") { it.third } + blocks.values.joinToString("") { it.text.toString() + it.json }

        // OpenAI：按 index 拼的工具调用（id、名字、参数）。
        private val openAiCalls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()

        // Anthropic：按 index 拼的内容块。
        private class Block(val type: String, val start: JSONObject) {
            val text = StringBuilder()
            val signature = StringBuilder()
            val json = StringBuilder()
        }
        private val blocks = sortedMapOf<Int, Block>()

        val finished: Boolean get() = done

        fun feed(data: String) {
            val d = data.trim()
            if (d.isEmpty()) return
            if (d == "[DONE]") {
                done = true
                return
            }
            val o = runCatching { JSONObject(d) }.getOrNull()
                ?: if (strictOfficialDeepSeek) throw AiException("DeepSeek 返回了无法解析的回答片段，本次内容不完整，请重试") else return
            o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").ifEmpty { it.toString() }.take(160)}") }
            when (protocol) {
                AiProtocol.OPENAI -> {
                    val choice = o.optJSONArray("choices")?.optJSONObject(0)
                    (AiUsage.openAi(o.optJSONObject("usage")) ?: AiUsage.openAi(choice?.optJSONObject("usage")))?.let { usage = it }
                    if (choice == null) return
                    val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
                    if (delta != null) {
                        delta.optString("content").takeIf { !delta.isNull("content") && it.isNotEmpty() }?.let { text.append(it); onText(it) }
                        delta.optString("reasoning_content").takeIf { !delta.isNull("reasoning_content") && it.isNotEmpty() }?.let { reasoning.append(it) }
                        delta.optJSONArray("tool_calls")?.let { calls ->
                            for (i in 0 until calls.length()) {
                                val call = calls.optJSONObject(i) ?: continue
                                val slot = openAiCalls.getOrPut(call.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                call.optString("id").takeIf { !call.isNull("id") && it.isNotEmpty() }?.let { slot.first.setLength(0); slot.first.append(it) }
                                call.optJSONObject("function")?.let { f ->
                                    f.optString("name").takeIf { !f.isNull("name") && it.isNotEmpty() }?.let { slot.second.append(it) }
                                    f.optString("arguments").takeIf { !f.isNull("arguments") }?.let { slot.third.append(it) }
                                }
                            }
                        }
                    }
                    val finishReason = choice.optString("finish_reason").takeIf { !choice.isNull("finish_reason") }
                    if (!finishReason.isNullOrEmpty()) terminal = true
                    AiWire.requireUninterruptedOpenAi(finishReason.orEmpty())
                    when (finishReason) {
                        "length" -> truncated = true
                        "content_filter" -> refused = true
                    }
                }
                AiProtocol.ANTHROPIC -> when (o.optString("type")) {
                    "message_start" -> usage = AiUsage.merge(usage, AiUsage.anthropic(o.optJSONObject("message")?.optJSONObject("usage")))
                    "content_block_start" -> {
                        val block = o.optJSONObject("content_block") ?: return
                        blocks[o.optInt("index")] = Block(block.optString("type"), block)
                    }
                    "content_block_delta" -> {
                        val b = blocks[o.optInt("index")] ?: return
                        val delta = o.optJSONObject("delta") ?: return
                        when (delta.optString("type")) {
                            "text_delta" -> delta.optString("text").let { b.text.append(it); text.append(it); onText(it) }
                            "thinking_delta" -> b.text.append(delta.optString("thinking"))
                            "signature_delta" -> b.signature.append(delta.optString("signature"))
                            "input_json_delta" -> b.json.append(delta.optString("partial_json"))
                        }
                    }
                    "message_delta" -> {
                        usage = AiUsage.merge(usage, AiUsage.anthropic(o.optJSONObject("usage")))
                        when (o.optJSONObject("delta")?.optString("stop_reason")) {
                            "max_tokens" -> truncated = true
                            "refusal" -> refused = true
                        }
                    }
                    "message_stop" -> done = true
                }
                AiProtocol.DEVICE -> {}
            }
        }

        /** 不是流、回了一整个 JSON（有的转发服务）：拆成一条条喂进来。 */
        fun feedWhole(body: String) {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: throw AiException("看不懂服务商的回复")
            o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").take(160)}") }
            usage = AiWire.wholeUsage(protocol, body)
            when (protocol) {
                AiProtocol.OPENAI -> {
                    val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return
                    AiWire.requireUninterruptedOpenAi(choice.optString("finish_reason"))
                    val msg = choice.optJSONObject("message") ?: return
                    val calls = msg.optJSONArray("tool_calls")
                    if (calls != null) for (i in 0 until calls.length()) calls.optJSONObject(i)?.put("index", i)
                    feed(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", msg).put("finish_reason", choice.opt("finish_reason") ?: JSONObject.NULL))).toString())
                }
                AiProtocol.ANTHROPIC -> {
                    val content = o.optJSONArray("content") ?: JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        val b = Block(block.optString("type"), block)
                        when (b.type) {
                            "text" -> block.optString("text").let { b.text.append(it); text.append(it); onText(it) }
                            "thinking" -> { b.text.append(block.optString("thinking")); b.signature.append(block.optString("signature")) }
                            "tool_use" -> b.json.append(block.optJSONObject("input")?.toString() ?: "{}")
                        }
                        blocks[i] = b
                    }
                    when (o.optString("stop_reason")) {
                        "max_tokens" -> truncated = true
                        "refusal" -> refused = true
                    }
                }
                AiProtocol.DEVICE -> {}
            }
            done = true
        }

        private fun args(raw: String): JSONObject = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

        fun finish(): Turn = when (protocol) {
            AiProtocol.ANTHROPIC -> {
                val content = JSONArray()
                val calls = mutableListOf<AiToolCall>()
                blocks.values.forEach { b ->
                    when (b.type) {
                        "text" -> if (b.text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", b.text.toString()))
                        "thinking" -> content.put(JSONObject().put("type", "thinking").put("thinking", b.text.toString()).put("signature", b.signature.toString()))
                        "redacted_thinking" -> content.put(JSONObject().put("type", "redacted_thinking").put("data", b.start.optString("data")))
                        "tool_use" -> {
                            val input = args(b.json.toString())
                            val id = b.start.optString("id")
                            calls += AiToolCall(id, b.start.optString("name"), input)
                            content.put(JSONObject().put("type", "tool_use").put("id", id).put("name", b.start.optString("name")).put("input", input))
                        }
                    }
                }
                Turn(text.toString(), calls, JSONObject().put("role", "assistant").put("content", content), truncated, refused, usage)
            }
            else -> {
                val calls = openAiCalls.entries.mapNotNull { (i, s) ->
                    val name = s.second.toString().ifEmpty { return@mapNotNull null }
                    AiToolCall(s.first.toString().ifEmpty { "call_$i" }, name, args(s.third.toString())) to s.third.toString()
                }
                val msg = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
                // DeepSeek 的思考模式要求同一轮里把思考内容交回去；别家不认识这个字段，也不报错。
                if (reasoning.isNotEmpty()) msg.put("reasoning_content", reasoning.toString())
                if (calls.isNotEmpty()) {
                    msg.put("tool_calls", JSONArray().apply {
                        calls.forEach { (c, raw) -> put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", raw.ifBlank { "{}" }))) }
                    })
                }
                Turn(text.toString(), calls.map { it.first }, msg, truncated, refused, usage)
            }
        }
    }

    /** 把这一轮各个工具的结果交回去（下一轮的消息）。 */
    fun results(c: AiConfig, results: List<Pair<AiToolCall, String>>): List<JSONObject> = when (c.protocol) {
        AiProtocol.ANTHROPIC -> listOf(
            JSONObject().put("role", "user").put("content", JSONArray().apply {
                results.forEach { (call, out) -> put(JSONObject().put("type", "tool_result").put("tool_use_id", call.id).put("content", out)) }
            }),
        )
        else -> results.map { (call, out) -> JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", out) }
    }

    /**
     * 服务商（或模型）明确说不支持工具调用的报错（4xx 回包原文）：tools、function calling、tool_choice 不被支持。
     * 只认这几种说法；“max_tokens is not supported” 这类参数不兼容、别的含 tool 字样的报错都不算，照原样报出。
     */
    fun unsupported(message: String?): Boolean = message != null && TOOLS_UNSUPPORTED.any { it.containsMatchIn(message) }

    /** 工具相关的字眼：tools、tool use / tool calls / tool calling、tool_choice、function calling、functions。 */
    private const val TOOL_WORD = """(?:tools?|tool[ _-]?(?:use|calls?|calling|choice)|function[ _-]?call(?:s|ing)?|functions)"""
    private val TOOLS_UNSUPPORTED = listOf(
        // “model does not support tools”“doesn't support function calling”
        Regex("""(?i)\b(?:does|do)\s*(?:not|n't)\s+support\s+$TOOL_WORD\b"""),
        // “tools are not supported”“tool_choice none not supported”“function calling is unsupported”
        Regex("""(?i)\b$TOOL_WORD\b[^.;\n]{0,40}?\b(?:(?:is|are)\s+)?(?:not\s+supported|unsupported|not\s+enabled)\b"""),
        // “unsupported parameter: 'tools'”
        Regex("""(?i)\b(?:unsupported|unrecognized|unknown)\s+(?:parameter|field|argument)s?\s*:?\s*['"`]?(?:tools|tool_choice|functions)\b"""),
        // 有的兼容服务：“"auto" tool choice requires --enable-auto-tool-choice”
        Regex("""(?i)tool[ _-]?choice\s+requires\b"""),
        Regex("""不支持\s*(?:工具|函数调用|function|tool)"""),
    )
}
