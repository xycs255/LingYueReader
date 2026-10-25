package com.xycs.lingyuereader.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/** 对话里的一句：[role] 是 user 或 assistant。 */
data class AiMessage(val role: String, val content: String)

/** 出错时给读者看的话（已经是中文、不含密钥）。 */
class AiException(message: String) : Exception(message)

/**
 * 两种接口的请求和回包格式。只管拼字符串、解析字符串，不联网，方便测试。
 * - OpenAI 兼容：POST {地址}/chat/completions，Authorization: Bearer 密钥，流式回包每行 `data: {choices:[{delta:{content}}]}`，以 `data: [DONE]` 结束；
 * - Anthropic：POST {地址}/v1/messages，x-api-key 密钥，流式回包里 `content_block_delta` 的 `text_delta` 是新文字。
 */
object AiWire {
    /** Anthropic 接口版本（anthropic-version 头）。 */
    const val ANTHROPIC_VERSION = "2026-10-21"

    /** 回答的长度上限：读书助手的回答都短；Claude 的思考也算在里面，留足。 */
    const val MAX_TOKENS = 8000

    fun endpoint(c: AiConfig): String {
        val base = c.baseUrl.trim().trimEnd('/')
        return when (c.protocol) {
            AiProtocol.OPENAI -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            AiProtocol.ANTHROPIC -> when {
                base.endsWith("/messages") -> base
                base.endsWith("/v1") -> "$base/messages"
                else -> "$base/v1/messages"
            }
            // 本机模型不走网络（[AiClient.stream] 直接交给 [DeviceLlm]）。
            AiProtocol.DEVICE -> ""
        }
    }

    fun headers(c: AiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        put("Accept", "text/event-stream")
        when (c.protocol) {
            AiProtocol.OPENAI -> if (c.apiKey.isNotBlank()) put("Authorization", "Bearer ${c.apiKey.trim()}")
            AiProtocol.ANTHROPIC -> {
                put("x-api-key", c.apiKey.trim())
                put("anthropic-version", ANTHROPIC_VERSION)
                if (refusalFallback(c)) put("anthropic-beta", "server-side-fallback-2026-07-01")
            }
            AiProtocol.DEVICE -> {}
        }
    }

    /** 官方接口上的新一代 Claude：安全分类器拒答时让服务端自动换模型重答（第三方转发的接口不认这个参数，不加）。 */
    private fun refusalFallback(c: AiConfig): Boolean =
        isOfficialAnthropic(c) && c.model.trim() in setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

    private fun isOfficialAnthropic(c: AiConfig): Boolean =
        runCatching { java.net.URI(c.baseUrl.trim()).host }.getOrNull() == "api.anthropic.com"

    /** Claude 5 一代支持调思考力度：读书助手的问题不难，用 low，回得快、花得少。 */
    private fun supportsEffort(model: String): Boolean = Regex("^claude-(opus|sonnet|haiku|fable|mythos)-5").containsMatchIn(model.trim())

    fun body(c: AiConfig, system: String, messages: List<AiMessage>, stream: Boolean = true): String {
        val list = JSONArray()
        val o = JSONObject().put("model", c.model.trim()).put("stream", stream)
        when (c.protocol) {
            AiProtocol.OPENAI -> {
                if (system.isNotEmpty()) list.put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { list.put(JSONObject().put("role", it.role).put("content", it.content)) }
            }
            AiProtocol.ANTHROPIC -> {
                o.put("max_tokens", MAX_TOKENS)
                if (system.isNotEmpty()) o.put("system", system)
                messages.forEach { list.put(JSONObject().put("role", it.role).put("content", it.content)) }
                if (supportsEffort(c.model)) o.put("output_config", JSONObject().put("effort", "low"))
                if (refusalFallback(c)) o.put("fallbacks", "default")
            }
            AiProtocol.DEVICE -> {}
        }
        return o.put("messages", list).toString()
    }

    /** 流里的一条事件带来的东西。 */
    data class Delta(val text: String = "", val done: Boolean = false, val refused: Boolean = false, val truncated: Boolean = false)

    /** 解析 SSE 里一行 `data:` 后面的内容。服务端在流里报错时抛 [AiException]。 */
    fun delta(protocol: AiProtocol, data: String): Delta {
        val d = data.trim()
        if (d.isEmpty()) return Delta()
        if (d == "[DONE]") return Delta(done = true)
        val o = runCatching { JSONObject(d) }.getOrNull() ?: return Delta()
        o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").ifEmpty { it.toString() }.take(160)}") }
        return when (protocol) {
            AiProtocol.OPENAI -> {
                val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return Delta()
                val text = choice.optJSONObject("delta")?.optStringOrEmpty("content") ?: ""
                val finish = choice.optStringOrEmpty("finish_reason")
                Delta(text, refused = finish == "content_filter", truncated = finish == "length")
            }
            AiProtocol.ANTHROPIC -> when (o.optString("type")) {
                "content_block_delta" -> {
                    val delta = o.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") Delta(delta.optString("text")) else Delta()
                }
                "message_delta" -> o.optJSONObject("delta")?.optString("stop_reason").let { reason ->
                    Delta(refused = reason == "refusal", truncated = reason == "max_tokens")
                }
                "message_stop" -> Delta(done = true)
                else -> Delta()
            }
            AiProtocol.DEVICE -> Delta()
        }
    }

    /** 一整个 JSON 的回答是不是因为长度上限被截断了。 */
    fun wholeTruncated(protocol: AiProtocol, body: String): Boolean {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return when (protocol) {
            AiProtocol.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.optStringOrEmpty("finish_reason") == "length"
            AiProtocol.ANTHROPIC -> o.optString("stop_reason") == "max_tokens"
            AiProtocol.DEVICE -> false
        }
    }

    /** 有的转发服务不支持流式，直接回一整个 JSON：取出里面的回答。 */
    fun whole(protocol: AiProtocol, body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw AiException("看不懂服务商的回复")
        o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").take(160)}") }
        return when (protocol) {
            AiProtocol.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optStringOrEmpty("content").orEmpty()
            AiProtocol.ANTHROPIC -> {
                val content = o.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text") }.joinToString("")
            }
            AiProtocol.DEVICE -> ""
        }
    }

    /** 按 HTTP 状态码和回包里的错误说明，告诉读者哪里不对。 */
    fun error(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.take(160)
        val head = when (code) {
            400 -> "请求被拒绝，可能是模型名不对"
            401, 403 -> "密钥不对，或没有权限用这个模型"
            402 -> "账户余额不足"
            404 -> "接口地址或模型名不对"
            408, 504 -> "等太久没有回应"
            413 -> "发送的内容太长"
            429 -> "请求太频繁，或额度用完了"
            in 500..599 -> "服务商暂时出了问题（$code），稍后再试"
            else -> "请求失败（$code）"
        }
        return if (detail != null) "$head：$detail" else head
    }

    private fun JSONObject.optStringOrEmpty(name: String): String = if (isNull(name)) "" else optString(name)
}

/**
 * 连服务商。只在读者点了 AI 功能时调用；密钥只放在请求头里，不写日志。
 * [open] 可以换掉，测试时连本机的假服务。
 */
class AiClient(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    /** 本机模型（选了“本机模型（离线）”时用）；手机不支持时为空。 */
    var device: DeviceLlm? = null

    /**
     * 不自动跟随重定向：系统跟随到别的主机时只去掉 Authorization，x-api-key 之类的密钥头照样带过去，
     * 局域网的 http 地址还可能被引到公网上明文发出。遇到重定向就报错，让读者改填新地址。
     */
    private fun open(url: URL): HttpURLConnection = connect(url).apply { instanceFollowRedirects = false }

    /** 重定向时的说明（不是重定向返回空）。 */
    private fun redirected(conn: HttpURLConnection, code: Int): String? {
        if (code !in 300..399) return null
        val to = runCatching { URL(conn.url, conn.getHeaderField("Location")).host }.getOrNull()
        return "接口地址被重定向到${to?.let { "「$it」" } ?: "别处"}，为了密钥安全不跟过去；请把接口地址改成重定向后的地址"
    }

    /**
     * 读网络时线程卡在 read() 里，协程取消管不到它：另起一个等取消的子协程，读者一关面板就断开连接，
     * 卡住的读马上出错返回，不再等到超时、继续计费。用完要 cancel 返回的 Job。
     */
    private fun CoroutineScope.disconnectOnCancel(conn: HttpURLConnection): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 一次向量请求最多几段（通义千问的向量接口一次最多 10 段）。 */
        const val EMBED_BATCH = 10
    }

    /**
     * 发出请求，回答一段段到时调 [onText]（在 IO 线程）；返回整段回答。拒答时末尾加一句说明。
     * 回答到了长度上限被截断时：[whole] 为真（要完整 JSON 的整理、翻译）就报错，否则末尾加一句说明。
     */
    suspend fun stream(config: AiConfig, system: String, messages: List<AiMessage>, whole: Boolean = false, onText: (String) -> Unit = {}): String =
        if (config.onDevice) {
            val llm = device ?: throw AiException("这台手机用不了本机模型（要 64 位的手机）")
            llm.generate(config.model, system, messages, onText).also { if (it.isBlank()) throw AiException("本机模型没有回答") }
        } else withContext(Dispatchers.IO) {
            // 明文 http 只给局域网里的模型（家里电脑上的 Ollama 等）；公网上的服务商一律 https，密钥不明文传输。
            val url = runCatching { URL(AiWire.endpoint(config)) }.getOrNull()?.takeIf { it.protocol == "https" || (it.protocol == "http" && isLanHost(it.host)) }
                ?: throw AiException("接口地址不对：要以 https:// 开头（局域网地址可以用 http://）")
            val conn = try {
                open(url)
            } catch (e: IOException) {
                throw AiException(networkError(e, url))
            }
            val watcher = disconnectOnCancel(conn)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                AiWire.headers(config).forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(AiWire.body(config, system, messages).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                redirected(conn, code)?.let { throw AiException(it) }
                if (code !in 200..299) {
                    val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                    throw AiException(AiWire.error(code, err))
                }
                val type = conn.contentType.orEmpty()
                val out = StringBuilder()
                var refused = false
                var truncated = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (type.contains("json") && !type.contains("event-stream")) {
                        val body = reader.readText()
                        val text = AiWire.whole(config.protocol, body)
                        truncated = AiWire.wholeTruncated(config.protocol, body)
                        out.append(text)
                        onText(text)
                        return@use
                    }
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val d = AiWire.delta(config.protocol, line.removePrefix("data:"))
                        if (d.text.isNotEmpty()) {
                            out.append(d.text)
                            onText(d.text)
                        }
                        if (d.refused) refused = true
                        if (d.truncated) truncated = true
                        if (d.done) break
                    }
                }
                if (truncated) {
                    if (whole) throw AiException("回答太长，到了模型的长度上限被截断了；内容少一些再试")
                    val note = "\n\n（回答太长，后面被截断了。）"
                    out.append(note)
                    onText(note)
                }
                if (refused) {
                    val note = if (out.isEmpty()) "模型拒绝回答这个问题。" else "\n\n（模型中途停止了回答。）"
                    out.append(note)
                    onText(note)
                }
                if (out.isBlank()) throw AiException("服务商没有返回内容")
                out.toString()
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw AiException(networkError(e, url))
            } finally {
                watcher.cancel()
                conn.disconnect()
            }
        }

    /**
     * 带工具的对话：AI 可以先用工具查（交给 [run] 执行，拿结果）再答，最多查 [maxRounds] 轮，到了上限要求它直接作答。
     * 回答一段段到时调 [onText]；AI 决定去查时调 [onLookup]（这一轮之前说的话不是回答，调用方清掉重来）。
     * 返回最后一轮的回答。服务商或模型不支持工具调用时，去掉工具重发一次。
     * [system] 的前 [cachePrefix] 个字每次都一样（设定包），Anthropic 上标成可缓存。
     */
    suspend fun converse(
        config: AiConfig,
        system: String,
        prompt: String,
        tools: List<AiTool>,
        run: suspend (AiToolCall) -> String,
        cachePrefix: Int = 0,
        maxRounds: Int = 6,
        whole: Boolean = false,
        maxTokens: Int? = null,
        onLookup: (AiToolCall) -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (config.onDevice || config.protocol == AiProtocol.DEVICE) return stream(config, system, listOf(AiMessage("user", prompt)), whole, onText)
        var useTools = tools
        retry@ while (true) {
            val turns = JSONArray().put(AiToolWire.user(config, prompt))
            var round = 0
            try {
                while (true) {
                    val allow = useTools.isNotEmpty() && round < maxRounds
                    val body = AiToolWire.body(config, system, turns, useTools, allow, cachePrefix, maxTokens)
                    val turn = post(config, body, onText)
                    if (turn.calls.isEmpty() || !allow) {
                        val out = StringBuilder(turn.text)
                        if (turn.truncated) {
                            if (whole) throw AiException("回答太长，到了模型的长度上限被截断了；内容少一些再试")
                            "\n\n（回答太长，后面被截断了。）".let { out.append(it); onText(it) }
                        }
                        if (turn.refused) ("\n\n（模型中途停止了回答。）".takeIf { out.isNotEmpty() } ?: "模型拒绝回答这个问题。").let { out.append(it); onText(it) }
                        if (out.isBlank()) throw AiException("服务商没有返回内容")
                        return out.toString()
                    }
                    round++
                    turns.put(turn.message)
                    val results = turn.calls.map { call ->
                        onLookup(call)
                        call to run(call)
                    }
                    AiToolWire.results(config, results).forEach { turns.put(it) }
                }
            } catch (e: AiException) {
                if (useTools.isNotEmpty() && round == 0 && AiToolWire.unsupported(e.message)) {
                    useTools = emptyList()
                    continue@retry
                }
                throw e
            }
        }
    }

    /** 发一轮（带工具的对话用）：流式读回答，拼成 [AiToolWire.Turn]。 */
    private suspend fun post(config: AiConfig, body: String, onText: (String) -> Unit): AiToolWire.Turn = withContext(Dispatchers.IO) {
        val url = runCatching { URL(AiWire.endpoint(config)) }.getOrNull()?.takeIf { it.protocol == "https" || (it.protocol == "http" && isLanHost(it.host)) }
            ?: throw AiException("接口地址不对：要以 https:// 开头（局域网地址可以用 http://）")
        val conn = try {
            open(url)
        } catch (e: IOException) {
            throw AiException(networkError(e, url))
        }
        val watcher = disconnectOnCancel(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            // 带着整个知识库的请求，服务商开始回答前要读一会儿。
            conn.readTimeout = 180_000
            conn.doOutput = true
            AiWire.headers(config).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            redirected(conn, code)?.let { throw AiException(it) }
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                throw AiException(AiWire.error(code, err))
            }
            val collector = AiToolWire.Collector(config.protocol, onText)
            val type = conn.contentType.orEmpty()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (type.contains("json") && !type.contains("event-stream")) {
                    collector.feedWhole(reader.readText())
                    return@use
                }
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    collector.feed(line.removePrefix("data:"))
                    if (collector.finished) break
                }
            }
            collector.finish()
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw AiException(networkError(e, url))
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }

    /**
     * 文本向量（OpenAI 兼容的 /embeddings 接口）：问这本书时找相关原文用。一次最多发 [EMBED_BATCH] 段。
     * Anthropic 接口没有向量模型。
     */
    suspend fun embed(config: AiConfig, model: String, inputs: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (config.protocol != AiProtocol.OPENAI) throw AiException(if (config.onDevice) "本机模型没有向量模型" else "Anthropic 接口没有向量模型")
        require(inputs.size <= EMBED_BATCH)
        val endpoint = AiWire.endpoint(config).removeSuffix("/chat/completions") + "/embeddings"
        val url = runCatching { URL(endpoint) }.getOrNull()?.takeIf { it.protocol == "https" || (it.protocol == "http" && isLanHost(it.host)) }
            ?: throw AiException("接口地址不对")
        val conn = try { open(url) } catch (e: IOException) { throw AiException(networkError(e, url)) }
        val watcher = disconnectOnCancel(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            val body = JSONObject().put("model", model.trim()).put("input", JSONArray(inputs)).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            redirected(conn, code)?.let { throw AiException("向量模型：$it") }
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                throw AiException("向量模型：" + AiWire.error(code, err))
            }
            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val data = o.optJSONArray("data") ?: throw AiException("向量模型没有返回结果")
            val out = arrayOfNulls<FloatArray>(inputs.size)
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val v = item.getJSONArray("embedding")
                out[item.optInt("index", i).coerceIn(0, inputs.lastIndex)] = FloatArray(v.length()) { k -> v.getDouble(k).toFloat() }
            }
            out.map { it ?: throw AiException("向量模型少返回了结果") }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw AiException(networkError(e, url))
        } catch (e: org.json.JSONException) {
            throw AiException("看不懂向量模型的回复")
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }

    /**
     * 语音合成（OpenAI 兼容的 /audio/speech）：返回 mp3。AI 朗读、AI 讲书用。
     * 明文 http 只给局域网里的服务（Kokoro 等）。
     */
    suspend fun speech(config: SpeechConfig, text: String, voice: String = config.voice): ByteArray = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
        val url = runCatching { URL(endpoint) }.getOrNull()?.takeIf { it.protocol == "https" || (it.protocol == "http" && isLanHost(it.host)) }
            ?: throw AiException("语音接口地址不对：要以 https:// 开头（局域网地址可以用 http://）")
        val conn = try { open(url) } catch (e: IOException) { throw AiException(networkError(e, url)) }
        val watcher = disconnectOnCancel(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            val body = JSONObject().put("model", config.model.trim()).put("input", text).put("voice", voice).put("response_format", "mp3")
            if (config.instructions.isNotBlank() && SpeechPresets.of(config.preset).instructions) body.put("instructions", config.instructions.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            redirected(conn, code)?.let { throw AiException("语音合成：$it") }
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                throw AiException("语音合成：" + AiWire.error(code, err))
            }
            if (conn.contentType.orEmpty().contains("json")) {
                val o = runCatching { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) }.getOrNull()
                throw AiException("语音合成：" + (o?.optJSONObject("error")?.optString("message") ?: o?.optString("message") ?: "服务商没有返回音频").take(160))
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 64) throw AiException("语音合成：服务商没有返回音频")
            bytes
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw AiException(networkError(e, url))
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }

    private fun networkError(e: IOException, url: URL): String = when {
        e is UnknownHostException -> "连不上 ${url.host}：检查网络或接口地址"
        e is SocketTimeoutException -> "等太久没有回应，稍后再试"
        e.message?.contains("Cleartext", ignoreCase = true) == true -> "只支持 https:// 开头的地址"
        else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
    }
}
