package com.xycs.lingyuereader.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
        }
    }

    fun headers(c: AiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        put("Accept", "text/event-stream")
        when (c.protocol) {
            AiProtocol.OPENAI -> put("Authorization", "Bearer ${c.apiKey.trim()}")
            AiProtocol.ANTHROPIC -> {
                put("x-api-key", c.apiKey.trim())
                put("anthropic-version", ANTHROPIC_VERSION)
                if (refusalFallback(c)) put("anthropic-beta", "server-side-fallback-2026-07-01")
            }
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
        }
        return o.put("messages", list).toString()
    }

    /** 流里的一条事件带来的东西。 */
    data class Delta(val text: String = "", val done: Boolean = false, val refused: Boolean = false)

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
                Delta(text, refused = finish == "content_filter")
            }
            AiProtocol.ANTHROPIC -> when (o.optString("type")) {
                "content_block_delta" -> {
                    val delta = o.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") Delta(delta.optString("text")) else Delta()
                }
                "message_delta" -> Delta(refused = o.optJSONObject("delta")?.optString("stop_reason") == "refusal")
                "message_stop" -> Delta(done = true)
                else -> Delta()
            }
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
class AiClient(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    /** 发出请求，回答一段段到时调 [onText]（在 IO 线程）；返回整段回答。拒答时末尾加一句说明。 */
    suspend fun stream(config: AiConfig, system: String, messages: List<AiMessage>, onText: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val url = runCatching { URL(AiWire.endpoint(config)) }.getOrNull()?.takeIf { it.protocol == "https" || it.protocol == "http" }
                ?: throw AiException("接口地址不对：要以 https:// 开头")
            val conn = try {
                open(url)
            } catch (e: IOException) {
                throw AiException(networkError(e, url))
            }
            // 读者关掉面板时协程取消，断开连接，阻塞的读马上返回。
            val handle = coroutineContext.job.invokeOnCompletion { if (it != null) conn.disconnect() }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                AiWire.headers(config).forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(AiWire.body(config, system, messages).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
                    throw AiException(AiWire.error(code, err))
                }
                val type = conn.contentType.orEmpty()
                val out = StringBuilder()
                var refused = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (type.contains("json") && !type.contains("event-stream")) {
                        val text = AiWire.whole(config.protocol, reader.readText())
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
                        if (d.done) break
                    }
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
                handle.dispose()
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
