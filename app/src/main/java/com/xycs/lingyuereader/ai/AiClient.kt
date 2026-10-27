package com.xycs.lingyuereader.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
open class AiException(message: String) : Exception(message)

/**
 * 服务商在开始回答之前（HTTP 4xx、没有正文流）明确说不支持工具调用：tools、function calling、tool_choice 不被支持。
 * 只有这一种报错会让带工具的对话去掉工具重发；参数不兼容等别的错误、回答流中途的报错都照原样抛出。
 */
class AiToolsUnsupportedException(message: String) : AiException(message)

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

    /**
     * 流式请求要服务商在最后一块里带上用量（`stream_options: {include_usage: true}`）。不是每家都认这个参数（不认的可能回 400），
     * 只给核对过支持的几家加：OpenAI、DeepSeek、通义千问（百炼的兼容接口）。别家照样解析流里自带的 usage（智谱、Kimi 在最后一块里就有），
     * 都没有就按字数估。
     */
    fun streamUsage(c: AiConfig): Boolean {
        if (c.protocol != AiProtocol.OPENAI) return false
        val host = runCatching { java.net.URI(c.baseUrl.trim()).host }.getOrNull().orEmpty()
        return host in setOf("api.openai.com", "api.deepseek.com", "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")
    }

    /** 官方 DeepSeek 的 SSE 必须以非空 finish_reason 和 data: [DONE] 结束。 */
    fun officialDeepSeek(c: AiConfig): Boolean = c.protocol == AiProtocol.OPENAI &&
        runCatching { java.net.URI(c.baseUrl.trim()).host?.equals("api.deepseek.com", ignoreCase = true) == true }
            .getOrDefault(false)

    fun body(c: AiConfig, system: String, messages: List<AiMessage>, stream: Boolean = true,
             maxTokens: Int? = null, disableOfficialDeepSeekThinking: Boolean = false): String {
        val list = JSONArray()
        val o = JSONObject().put("model", c.model.trim()).put("stream", stream)
        when (c.protocol) {
            AiProtocol.OPENAI -> {
                if (stream && streamUsage(c)) o.put("stream_options", JSONObject().put("include_usage", true))
                if (maxTokens != null) o.put("max_tokens", maxTokens)
                if (disableOfficialDeepSeekThinking && officialDeepSeek(c) &&
                    c.model.trim().lowercase() in setOf(AiPresets.DEEPSEEK_V4_PRO, AiPresets.DEEPSEEK_FLASH)) {
                    o.put("thinking", JSONObject().put("type", "disabled"))
                }
                if (system.isNotEmpty()) list.put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { list.put(JSONObject().put("role", it.role).put("content", it.content)) }
            }
            AiProtocol.ANTHROPIC -> {
                o.put("max_tokens", maxTokens ?: MAX_TOKENS)
                if (system.isNotEmpty()) o.put("system", system)
                messages.forEach { list.put(JSONObject().put("role", it.role).put("content", it.content)) }
                if (supportsEffort(c.model)) o.put("output_config", JSONObject().put("effort", "low"))
                if (refusalFallback(c)) o.put("fallbacks", "default")
            }
            AiProtocol.DEVICE -> {}
        }
        return o.put("messages", list).toString()
    }

    /** 流里的一条事件带来的东西；[usage] 是这条里带的用量（多半在最后一块）。 */
    data class Delta(val text: String = "", val done: Boolean = false, val terminal: Boolean = false,
                     val refused: Boolean = false, val truncated: Boolean = false, val usage: AiUsage? = null)

    /** 解析 SSE 里一行 `data:` 后面的内容。服务端在流里报错时抛 [AiException]。 */
    fun delta(protocol: AiProtocol, data: String, strictOfficialDeepSeek: Boolean = false): Delta {
        val d = data.trim()
        if (d.isEmpty()) return Delta()
        if (d == "[DONE]") return Delta(done = true)
        val o = runCatching { JSONObject(d) }.getOrNull()
            ?: if (strictOfficialDeepSeek) throw AiException("DeepSeek 返回了无法解析的回答片段，本次内容不完整，请重试") else return Delta()
        o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").ifEmpty { it.toString() }.take(160)}") }
        return when (protocol) {
            AiProtocol.OPENAI -> {
                // 用量：include_usage 时在最后一块（choices 是空的）；Kimi 写在 choices[0].usage 里。
                val choice = o.optJSONArray("choices")?.optJSONObject(0)
                val usage = AiUsage.openAi(o.optJSONObject("usage")) ?: AiUsage.openAi(choice?.optJSONObject("usage"))
                if (choice == null) return Delta(usage = usage)
                val text = choice.optJSONObject("delta")?.optStringOrEmpty("content") ?: ""
                val finish = choice.optStringOrEmpty("finish_reason")
                requireUninterruptedOpenAi(finish)
                Delta(text, terminal = finish.isNotEmpty(), refused = finish == "content_filter", truncated = finish == "length", usage = usage)
            }
            AiProtocol.ANTHROPIC -> when (o.optString("type")) {
                "message_start" -> Delta(usage = AiUsage.anthropic(o.optJSONObject("message")?.optJSONObject("usage")))
                "content_block_delta" -> {
                    val delta = o.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") Delta(delta.optString("text")) else Delta()
                }
                "message_delta" -> o.optJSONObject("delta")?.optString("stop_reason").let { reason ->
                    Delta(refused = reason == "refusal", truncated = reason == "max_tokens", usage = AiUsage.anthropic(o.optJSONObject("usage")))
                }
                "message_stop" -> Delta(done = true)
                else -> Delta()
            }
            AiProtocol.DEVICE -> Delta()
        }
    }

    /** A partial answer after a provider interruption is not a complete writing candidate. */
    fun requireUninterruptedOpenAi(finishReason: String) {
        when (finishReason) {
            "aborted" -> throw AiException("服务商中断了回答，本次内容不完整，请重试")
            "insufficient_system_resource" -> throw AiException("服务商资源不足，中断了回答，本次内容不完整，请稍后重试")
        }
    }

    /** 官方 DeepSeek 非流式回复也必须明确声明本次生成如何结束。 */
    fun requireOfficialDeepSeekWhole(config: AiConfig, body: String) {
        if (!officialDeepSeek(config)) return
        val reply = runCatching { JSONObject(body) }.getOrNull() ?: throw AiException("看不懂服务商的回复")
        reply.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").take(160)}") }
        val finish = reply.optJSONArray("choices")?.optJSONObject(0)?.optStringOrEmpty("finish_reason").orEmpty()
        if (finish.isEmpty()) throw AiException("DeepSeek 回答缺少结束原因，本次内容可能不完整，请重试")
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

    /** 一整个 JSON 的回答里的用量。 */
    fun wholeUsage(protocol: AiProtocol, body: String): AiUsage? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (protocol) {
            AiProtocol.OPENAI -> AiUsage.openAi(o.optJSONObject("usage"))
            AiProtocol.ANTHROPIC -> AiUsage.anthropic(o.optJSONObject("usage"))
            AiProtocol.DEVICE -> null
        }
    }

    /** 有的转发服务不支持流式，直接回一整个 JSON：取出里面的回答。 */
    fun whole(protocol: AiProtocol, body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw AiException("看不懂服务商的回复")
        o.optJSONObject("error")?.let { throw AiException("服务商报错：${it.optString("message").take(160)}") }
        return when (protocol) {
            AiProtocol.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
                requireUninterruptedOpenAi(choice.optStringOrEmpty("finish_reason"))
                choice.optJSONObject("message")?.optStringOrEmpty("content").orEmpty()
            }.orEmpty()
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
            422 -> "请求参数无效，请检查模型与本次功能是否兼容"
            429 -> "请求太频繁，或同时进行的请求太多"
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

    /** 每发完一次请求（联网的），用量交给它记账（只有数字，没有内容）；在发请求的线程上调。 */
    @Volatile
    var onUsage: ((AiUsageEvent) -> Unit)? = null

    /**
     * 记一次用量：加进协程上下文里的 [AiMeter]（调用方据此显示这一次花了多少），再交给 [onUsage]。
     * 服务商没返回用量时按发出、收到的字数估。
     */
    private suspend fun report(config: AiConfig, usage: AiUsage?, sent: () -> String, received: String) {
        val u = usage ?: AiUsage.estimate(sent(), received)
        val cost = AiPricing.cost(config, u)
        val meter = coroutineContext[AiMeter]
        meter?.add(u, cost)
        runCatching { onUsage?.invoke(AiUsageEvent(config, u, cost, meter)) }
    }

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
        /** 整稿请求（[whole]）遇到拒答或内容过滤：不能把提示语当正文交出去。 */
        const val REFUSED = "模型拒绝回答，或内容被服务商过滤，本次没有完整回答；调整内容后再试"
        /** 整稿请求的流没有正常结束（代理超时、对端断开）。 */
        const val INCOMPLETE = "连接中断，回答不完整；请重试"

        /**
         * 流是否正常结束：OpenAI 兼容见到 `[DONE]` 或非空 finish_reason 任一即可（有的转发服务只给其一），
         * Anthropic 要见到 message_stop。只用来拦整稿请求，普通问答不发 `[DONE]` 的局域网服务照常可用。
         */
        internal fun streamEnded(protocol: AiProtocol, sawDone: Boolean, sawFinish: Boolean): Boolean = when (protocol) {
            AiProtocol.OPENAI -> sawDone || sawFinish
            AiProtocol.ANTHROPIC -> sawDone
            AiProtocol.DEVICE -> true
        }

        /** 一次向量请求最多几段（通义千问的向量接口一次最多 10 段）。 */
        const val EMBED_BATCH = 10
        /** 一段语音返回的压缩音频上限，避免异常服务端耗尽内存。 */
        const val MAX_SPEECH_BYTES = 20 * 1024 * 1024
    }

    /**
     * 发出请求，回答一段段到时调 [onText]（在 IO 线程）；返回整段回答。拒答时末尾加一句说明。
     * 回答到了长度上限被截断、拒答或被过滤、流没有正常结束时：[whole] 为真（整稿、要完整 JSON 的整理、翻译）就报错，
     * 否则截断和拒答在末尾加一句说明，断流照常返回。
     */
    suspend fun stream(config: AiConfig, system: String, messages: List<AiMessage>, whole: Boolean = false, onText: (String) -> Unit = {}): String =
        streamWithOptions(config, system, messages, whole, onText)

    /** Connection checks are real, billable requests: bound their output and disable official V4 thinking. */
    internal suspend fun testConnection(config: AiConfig): String = streamWithOptions(
        config, "", listOf(AiMessage("user", "请只回复“好”。")), maxTokens = 32,
        disableOfficialDeepSeekThinking = true,
    )

    private suspend fun streamWithOptions(
        config: AiConfig,
        system: String,
        messages: List<AiMessage>,
        whole: Boolean = false,
        onText: (String) -> Unit = {},
        maxTokens: Int? = null,
        disableOfficialDeepSeekThinking: Boolean = false,
    ): String =
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
            val out = StringBuilder()
            var usage: AiUsage? = null
            var reported = false
            var sawDone = false
            var sawFinish = false
            val officialDeepSeek = AiWire.officialDeepSeek(config)
            val sent = { system + messages.joinToString("") { it.content } }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                AiWire.headers(config).forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(AiWire.body(config, system, messages,
                    maxTokens = maxTokens, disableOfficialDeepSeekThinking = disableOfficialDeepSeekThinking).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                redirected(conn, code)?.let { throw AiException(it) }
                if (code !in 200..299) {
                    val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                    throw AiException(AiWire.error(code, err))
                }
                val type = conn.contentType.orEmpty()
                val wholeJson = type.contains("json") && !type.contains("event-stream")
                var refused = false
                var truncated = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (wholeJson) {
                        val body = reader.readText()
                        AiWire.requireOfficialDeepSeekWhole(config, body)
                        usage = AiWire.wholeUsage(config.protocol, body)
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
                        val d = AiWire.delta(config.protocol, line.removePrefix("data:"), officialDeepSeek)
                        if (d.terminal) sawFinish = true
                        usage = if (config.protocol == AiProtocol.ANTHROPIC) AiUsage.merge(usage, d.usage) else d.usage ?: usage
                        if (d.text.isNotEmpty()) {
                            out.append(d.text)
                            onText(d.text)
                        }
                        if (d.refused) refused = true
                        if (d.truncated) truncated = true
                        if (d.done) { sawDone = true; break }
                    }
                }
                if (officialDeepSeek && !wholeJson) {
                    if (!sawDone || !sawFinish) throw AiException("DeepSeek 回答流未完整结束，本次内容可能不完整，请重试")
                }
                if (whole && !wholeJson && !streamEnded(config.protocol, sawDone, sawFinish)) throw AiException(INCOMPLETE)
                // 收完了就记用量（之后截断报错也照样花了钱）。
                reported = true
                report(config, usage, sent, out.toString())
                if (truncated) {
                    if (whole) throw AiException("回答太长，到了模型的长度上限被截断了；内容少一些再试")
                    val note = "\n\n（回答太长，后面被截断了。）"
                    out.append(note)
                    onText(note)
                }
                if (refused) {
                    if (whole) throw AiException(REFUSED)
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
                // 中途停下、断了：已经收到的部分照样花了钱，按收到的估一份（服务商已经给了用量就用它的）。
                if (!reported && (out.isNotEmpty() || usage != null)) withContext(NonCancellable) { report(config, usage, sent, out.toString()) }
            }
        }

    /**
     * 带工具的对话：AI 可以先用工具查（交给 [run] 执行，拿结果）再答，最多查 [maxRounds] 轮，到了上限要求它直接作答。
     * 回答一段段到时调 [onText]；AI 决定去查时调 [onLookup]（这一轮之前说的话不是回答，调用方清掉重来）。
     * 返回最后一轮的回答。服务商或模型在第一轮就以 4xx 明确拒绝工具时（[AiToolsUnsupportedException]），去掉工具重发一次并调 [onToolsDropped]；
     * [requireTools] 为真时不重发、直接报错。别的错误（参数不兼容、流中途报错等）原样抛出，不重发，免得重复计费。
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
        cacheExtra: Int = 0,
        /** A project task must not pretend it consulted the pack after the model rejects tools. */
        requireTools: Boolean = false,
        /** 模型不支持工具、这次去掉工具重发了：调用方据此提示“没能查设定包”。 */
        onToolsDropped: () -> Unit = {},
        onLookup: (AiToolCall) -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (requireTools && tools.isEmpty()) throw AiException("本次任务需要设定包查阅工具，但没有可用工具")
        if (config.onDevice || config.protocol == AiProtocol.DEVICE) {
            if (requireTools) throw AiException("本机模型不支持设定包查阅工具，无法完成本次任务")
            return stream(config, system, listOf(AiMessage("user", prompt)), whole, onText)
        }
        var useTools = tools
        retry@ while (true) {
            val turns = JSONArray().put(AiToolWire.user(config, prompt))
            var round = 0
            try {
                while (true) {
                    val allow = useTools.isNotEmpty() && round < maxRounds
                    val body = AiToolWire.body(config, system, turns, useTools, allow, cachePrefix, maxTokens, cacheExtra)
                    val turn = post(config, body, whole, onText)
                    if (turn.truncated && turn.calls.isNotEmpty()) {
                        throw AiException("模型的工具调用被截断，本次没有执行，请缩短材料后重试")
                    }
                    if (turn.calls.isEmpty() || !allow) {
                        val out = StringBuilder(turn.text)
                        if (turn.truncated) {
                            if (whole) throw AiException("回答太长，到了模型的长度上限被截断了；内容少一些再试")
                            "\n\n（回答太长，后面被截断了。）".let { out.append(it); onText(it) }
                        }
                        if (turn.refused && whole) throw AiException(REFUSED)
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
            } catch (e: AiToolsUnsupportedException) {
                if (useTools.isEmpty() || round != 0) throw e
                if (requireTools) throw AiException("当前模型不支持设定包查阅工具，本次任务没有继续生成；请改用支持工具调用的模型")
                useTools = emptyList()
                onToolsDropped()
                continue@retry
            }
        }
    }

    /** 发一轮（带工具的对话用）：流式读回答，拼成 [AiToolWire.Turn]。[whole] 为真时流必须正常结束。 */
    private suspend fun post(config: AiConfig, body: String, whole: Boolean, onText: (String) -> Unit): AiToolWire.Turn = withContext(Dispatchers.IO) {
        val collector = AiToolWire.Collector(config.protocol, AiWire.officialDeepSeek(config), onText)
        var reported = false
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
                // 还没开始回答就被拒：只有明确说不支持工具的 4xx 才算，交给 converse 去掉工具重发。
                if (code in 400..499 && AiToolWire.unsupported(err)) throw AiToolsUnsupportedException(AiWire.error(code, err))
                throw AiException(AiWire.error(code, err))
            }
            val type = conn.contentType.orEmpty()
            val wholeJson = type.contains("json") && !type.contains("event-stream")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (wholeJson) {
                    val response = reader.readText()
                    AiWire.requireOfficialDeepSeekWhole(config, response)
                    collector.feedWhole(response)
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
            if (AiWire.officialDeepSeek(config) && !wholeJson &&
                (!collector.finished || !collector.terminal)) {
                throw AiException("DeepSeek 回答流未完整结束，本次内容可能不完整，请重试")
            }
            if (whole && !wholeJson && !streamEnded(config.protocol, collector.finished, collector.terminal)) throw AiException(INCOMPLETE)
            collector.finish().also { turn ->
                reported = true
                report(config, turn.usage, { body }, turn.text + turn.calls.joinToString("") { it.name + it.arguments })
            }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw AiException(networkError(e, url))
        } finally {
            watcher.cancel()
            conn.disconnect()
            if (!reported && (collector.received.isNotEmpty() || collector.usage != null)) {
                withContext(NonCancellable) { report(config, collector.usage, { body }, collector.received) }
            }
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
            if (conn.contentLengthLong > MAX_SPEECH_BYTES) throw AiException("语音合成：音频超过 20 MB，请缩短文字后重试")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > MAX_SPEECH_BYTES - out.size()) throw AiException("语音合成：音频超过 20 MB，请缩短文字后重试")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
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
