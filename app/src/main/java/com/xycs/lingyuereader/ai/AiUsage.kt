package com.xycs.lingyuereader.ai

import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次请求（或工具多轮、几次请求加在一起）用了多少 token。
 * [input] 是输入的总数，含缓存命中的 [cached] 和写进缓存的 [cacheWrite]（Anthropic 才有）；
 * [estimated] 为真表示服务商没返回用量，是按字数粗估的（[estimatedRequests] 次）。
 */
data class AiUsage(
    val input: Long = 0,
    val cached: Long = 0,
    val output: Long = 0,
    val cacheWrite: Long = 0,
    val requests: Int = 0,
    val estimatedRequests: Int = 0,
) {
    val estimated: Boolean get() = estimatedRequests > 0
    val isEmpty: Boolean get() = requests == 0

    operator fun plus(o: AiUsage) = AiUsage(input + o.input, cached + o.cached, output + o.output, cacheWrite + o.cacheWrite, requests + o.requests, estimatedRequests + o.estimatedRequests)

    /** 缓存命中占输入的比例（0–1）。 */
    val hitRate: Double get() = if (input > 0) cached.toDouble() / input else 0.0

    companion object {
        /**
         * OpenAI 兼容的 usage：`prompt_tokens`、`completion_tokens`；缓存命中按各家写法取——
         * DeepSeek 的 `prompt_cache_hit_tokens`（另有 `prompt_cache_miss_tokens`），OpenAI、通义千问的 `prompt_tokens_details.cached_tokens`，
         * Kimi 的 `cached_tokens`。没有 usage 或全是 0 时返回空。
         */
        fun openAi(o: JSONObject?): AiUsage? {
            if (o == null) return null
            val hit = o.long("prompt_cache_hit_tokens") ?: o.optJSONObject("prompt_tokens_details")?.long("cached_tokens") ?: o.long("cached_tokens") ?: 0
            val miss = o.long("prompt_cache_miss_tokens")
            val input = o.long("prompt_tokens") ?: miss?.let { it + hit } ?: o.long("input_tokens") ?: return null
            val output = o.long("completion_tokens") ?: o.long("output_tokens") ?: 0
            if (input == 0L && output == 0L) return null
            return AiUsage(input, hit.coerceAtMost(input), output, requests = 1)
        }

        /**
         * Anthropic 的 usage：`input_tokens` 不含缓存，另有 `cache_read_input_tokens`（命中）、`cache_creation_input_tokens`（写入），
         * `output_tokens`。流式时 message_start 带输入、message_delta 带累计的输出：拿到几份就逐项取大的（[merge]）。
         */
        fun anthropic(o: JSONObject?): AiUsage? {
            if (o == null) return null
            val read = o.long("cache_read_input_tokens") ?: 0
            val write = o.long("cache_creation_input_tokens") ?: 0
            val fresh = o.long("input_tokens") ?: 0
            val output = o.long("output_tokens") ?: 0
            if (fresh + read + write + output == 0L) return null
            return AiUsage(fresh + read + write, read, output, write, requests = 1)
        }

        /** 同一次请求里先后拿到的几份用量：逐项取大的（Anthropic 流里的输出是累计数）。 */
        fun merge(a: AiUsage?, b: AiUsage?): AiUsage? = when {
            a == null -> b
            b == null -> a
            else -> AiUsage(maxOf(a.input, b.input), maxOf(a.cached, b.cached), maxOf(a.output, b.output), maxOf(a.cacheWrite, b.cacheWrite), 1)
        }

        /**
         * 粗估 token：汉字（含全角标点）约 0.6 个，其余字符约 0.3 个（DeepSeek 文档给的换算，各家分词不同，只是个数量级）。
         */
        fun estimateTokens(text: String): Long {
            var cjk = 0L
            var other = 0L
            for (ch in text) {
                if (ch.code >= 0x2E80) cjk++ else if (!ch.isWhitespace()) other++
            }
            return Math.round(cjk * 0.6 + other * 0.3)
        }

        /** 服务商没返回用量：按发出去和收回来的字数估一份。 */
        fun estimate(sent: String, received: String) = AiUsage(estimateTokens(sent), 0, estimateTokens(received), requests = 1, estimatedRequests = 1)

        private fun JSONObject.long(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
    }
}

/** 估出来的钱：人民币、美元分开记（Claude 按美元计价），[unpriced] 是没有单价、没估钱的请求数。 */
data class AiCost(val cny: Double = 0.0, val usd: Double = 0.0, val unpriced: Int = 0) {
    operator fun plus(o: AiCost) = AiCost(cny + o.cny, usd + o.usd, unpriced + o.unpriced)

    /** 都折成人民币（美元按 [rate] 换）。 */
    fun inCny(rate: Double): Double = cny + usd * rate

    val priced: Boolean get() = cny > 0 || usd > 0 || unpriced == 0
}

/** 用量记在哪个功能上（按功能累计用）。 */
enum class AiFeature(val label: String) {
    SUGGEST("写作建议"),
    INLINE("AI 接着写"),
    DRAFT("写本章草稿"),
    EDIT("改稿"),
    IDEA("灵感与点评"),
    REVIEW("审稿"),
    SYNC("整理进设定包"),
    PLAN("细纲与摘要"),
    PACK("整理人物与规则"),
    REFERENCE("范本分析"),
    COMPANION("范本陪写"),
    ASK("问 AI"),
    TRANSLATE("翻译"),
    READING("阅读的其他 AI"),
    TEST("测试连接"),
    OTHER("其他"),
    ;

    companion object {
        fun of(name: String): AiFeature = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * 计量器：放进协程上下文（`withContext(AiMeter(...))`），[AiClient] 每发完一次请求就把用量和估的钱加进来——
 * 工具多轮的几次请求也都加在这一个上。顺带说明这次请求算在哪个功能、哪部作品上（[workId] 空是阅读）。
 */
class AiMeter(val feature: AiFeature, val workId: String = "") : AbstractCoroutineContextElement(AiMeter) {
    companion object Key : CoroutineContext.Key<AiMeter>

    @Volatile
    var usage: AiUsage = AiUsage()
        private set

    @Volatile
    var cost: AiCost = AiCost()
        private set

    @Synchronized
    fun add(u: AiUsage, c: AiCost) {
        usage += u
        cost += c
    }
}

/** 一次请求的用量（交给 [AiClient.onUsage] 记账）：[meter] 为空时算“其他”。 */
class AiUsageEvent(val config: AiConfig, val usage: AiUsage, val cost: AiCost, val meter: AiMeter?)

/** 单价：每百万 token 多少钱（输入、缓存命中、输出），[usd] 为真是美元。[cacheWrite] 为 0 时按输入价算。 */
data class AiPrice(val input: Double, val cached: Double, val output: Double, val usd: Boolean = false, val cacheWrite: Double = 0.0) {
    fun cost(u: AiUsage): AiCost {
        val fresh = (u.input - u.cached - u.cacheWrite).coerceAtLeast(0)
        val money = (fresh * input + u.cached * cached + u.cacheWrite * (if (cacheWrite > 0) cacheWrite else input) + u.output * output) / 1_000_000.0
        return if (usd) AiCost(usd = money) else AiCost(cny = money)
    }

    fun toJson(): JSONObject = JSONObject().put("input", input).put("cached", cached).put("output", output).put("usd", usd).put("cacheWrite", cacheWrite)

    companion object {
        fun fromJson(o: JSONObject?): AiPrice? = o?.let { AiPrice(it.optDouble("input", 0.0), it.optDouble("cached", 0.0), it.optDouble("output", 0.0), it.optBoolean("usd"), it.optDouble("cacheWrite", 0.0)) }
    }
}

/** 内置的一条参考价：哪家、哪个模型、什么价、出自哪里、哪天整理的。 */
data class ReferencePrice(val price: AiPrice, val source: String, val date: String, val note: String = "")

/**
 * 估钱：作者在 AI 设置里填了单价就按填的；没填时用内置的参考价（只收了核对过来源的几家，见 [reference]），
 * 都没有就不估钱，只记 token。
 */
object AiPricing {
    /**
     * 内置参考价。**参考价，以服务商为准**；服务商随时调价，查不到出处的不收，留给作者填。
     * - DeepSeek：api-docs.deepseek.com 价格页（2026-10-25 查），人民币/百万 token；分高峰、闲时两档（闲时减半），这里按高峰价估。
     *   页上只列了 deepseek-flash（旧名 deepseek-v4-flash 也按它计）和 deepseek-v4-pro；没写 deepseek-chat，所以 deepseek-chat 不估。
     * - Claude：Anthropic 官方价格（2026-10-06 的价格表），美元/百万 token；缓存命中按价格表写的（Opus 5.5、Sonnet 5.5 为 $0.20，Fable 5.1 为 $0.25，
     *   其余按输入价的一成），写入缓存（5 分钟）按输入价的 1.25 倍。
     * - 通义千问、Kimi、智谱：没有核对过当前价格（Kimi 还按上下文分档），不内置，作者自己填。
     */
    fun reference(c: AiConfig): ReferencePrice? {
        val model = c.model.trim().lowercase()
        val host = runCatching { java.net.URI(c.baseUrl.trim()).host }.getOrNull().orEmpty()
        return when {
            c.onDevice -> null
            host == "api.deepseek.com" || c.preset == "deepseek" -> deepseek(model)
            c.protocol == AiProtocol.ANTHROPIC && (host == "api.anthropic.com" || c.preset == "claude") -> claude(model)
            else -> null
        }
    }

    private const val DEEPSEEK_SOURCE = "DeepSeek 官网价格页"
    private const val DEEPSEEK_DATE = "2026-10-25"
    private const val DEEPSEEK_NOTE = "按高峰时段价估，闲时减半"

    private fun deepseek(model: String): ReferencePrice? = when {
        model == "deepseek-flash" || model.startsWith("deepseek-v4-flash") ->
            ReferencePrice(AiPrice(input = 2.0, cached = 0.04, output = 8.0), DEEPSEEK_SOURCE, DEEPSEEK_DATE, DEEPSEEK_NOTE)
        model == "deepseek-v4-pro" ->
            ReferencePrice(AiPrice(input = 9.0, cached = 0.30, output = 27.0), DEEPSEEK_SOURCE, DEEPSEEK_DATE, DEEPSEEK_NOTE)
        else -> null
    }

    private const val CLAUDE_SOURCE = "Anthropic 官方价格"
    private const val CLAUDE_DATE = "2026-10-06"

    private fun claude(model: String): ReferencePrice? {
        // 输入、命中、输出（美元/百万 token）。
        val (input, cached, output) = when (model) {
            "claude-fable-5-1" -> Triple(10.0, 0.25, 50.0)
            "claude-fable-5" -> Triple(10.0, 1.0, 50.0)
            "claude-opus-5-5" -> Triple(4.0, 0.20, 20.0)
            "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6" -> Triple(5.0, 0.50, 25.0)
            "claude-sonnet-5-5" -> Triple(2.0, 0.20, 10.0)
            "claude-sonnet-5" -> Triple(2.0, 0.20, 10.0)
            "claude-sonnet-4-6" -> Triple(3.0, 0.30, 15.0)
            "claude-haiku-5-5" -> Triple(0.10, 0.01, 0.50)
            "claude-haiku-4-5" -> Triple(1.0, 0.10, 5.0)
            else -> return null
        }
        return ReferencePrice(AiPrice(input, cached, output, usd = true, cacheWrite = input * 1.25), CLAUDE_SOURCE, CLAUDE_DATE)
    }

    /**
     * 作者填的单价按“服务商 + 接口地址的主机 + 模型名”记（[AiConfig.prices] 的键），换了模型就对不上、不会按别的模型的价算。
     * 以前只按服务商记的（键只有服务商的 id）是“这家服务商的通用价”，不知道是哪个模型的，排在专门填的后面。
     */
    fun priceKey(c: AiConfig): String {
        val host = runCatching { java.net.URI(c.baseUrl.trim()).host }.getOrNull().orEmpty().lowercase()
        return "${c.preset}|$host|${c.model.trim().lowercase()}"
    }

    /** 现在的单价从哪来：给这个模型专门填的、这家服务商的通用价（旧数据，未指定模型）、内置参考价。 */
    enum class PriceKind { MODEL, PROVIDER, REFERENCE }

    class PriceChoice(val price: AiPrice, val kind: PriceKind)

    /** 现在用的单价：给当前模型填的优先，其次这家服务商的通用价（旧数据），再其次参考价；都没有为空（没估钱）。 */
    fun choose(c: AiConfig): PriceChoice? =
        c.prices[priceKey(c)]?.let { PriceChoice(it, PriceKind.MODEL) }
            ?: c.prices[c.preset]?.let { PriceChoice(it, PriceKind.PROVIDER) }
            ?: reference(c)?.let { PriceChoice(it.price, PriceKind.REFERENCE) }

    fun price(c: AiConfig): AiPrice? = choose(c)?.price

    /**
     * 旧数据迁移：以前单价只按服务商记。当前服务商的那份，存的时候和现在存着的模型在一起，按“当时的模型”改记到它名下；
     * 别家服务商的不知道是哪个模型，留作那家的通用价（界面上写明“未指定模型”）。
     */
    fun migrate(c: AiConfig): AiConfig {
        val old = c.prices[c.preset] ?: return c
        if (c.onDevice || c.model.isBlank()) return c
        val key = priceKey(c)
        return c.copy(prices = c.prices - c.preset + (key to (c.prices[key] ?: old)))
    }

    /** 这次用量约多少钱；没有单价时记一次“没估钱”。 */
    fun cost(c: AiConfig, u: AiUsage): AiCost = price(c)?.cost(u) ?: AiCost(unpriced = u.requests)

    /** 显示钱：小于一分钱时多留几位（“¥0.003”），大了两位小数；人民币、美元都有就都写。 */
    fun money(cost: AiCost): String? {
        if (cost.cny <= 0 && cost.usd <= 0) return if (cost.unpriced > 0) null else "¥0"
        return listOfNotNull(
            cost.cny.takeIf { it > 0 }?.let { "¥" + amount(it) },
            cost.usd.takeIf { it > 0 }?.let { "$" + amount(it) },
        ).joinToString(" + ")
    }

    fun amount(v: Double): String = when {
        v >= 100 -> String.format(java.util.Locale.ROOT, "%.0f", v)
        v >= 1 -> String.format(java.util.Locale.ROOT, "%.2f", v)
        v >= 0.01 -> String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
        v > 0 -> String.format(java.util.Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').let { if (it == "0") "0.0001" else it }
        else -> "0"
    }

    /** 单价、汇率这类作者填的数：原样写，去掉末尾的 0（“2”“0.2”“7.1”）。 */
    fun plain(v: Double): String = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    /** token 数写成“1.2 万”“860”。 */
    fun tokens(n: Long): String = when {
        n >= 100_000_000 -> String.format(java.util.Locale.ROOT, "%.1f 亿", n / 100_000_000.0)
        n >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1f 万", n / 10_000.0)
        else -> n.toString()
    }

    /** 面板状态行的一行小字：“约 ¥0.003 · 缓存命中 92%”；没有单价时写 token 数。 */
    fun brief(usage: AiUsage, cost: AiCost): String? {
        if (usage.isEmpty) return null
        val money = money(cost)?.let { "约 $it" } ?: "${tokens(usage.input + usage.output)} token"
        val hit = if (usage.cached > 0) " · 缓存命中 ${Math.round(usage.hitRate * 100)}%" else ""
        return money + hit + if (usage.estimated) "（估算）" else ""
    }

    /** “最近一次发给 AI 的内容”、AI 记录里的一句：输入多少（其中缓存命中多少）、输出多少、约多少钱。 */
    fun detail(usage: AiUsage, cost: AiCost): String {
        val parts = mutableListOf("输入 ${tokens(usage.input)} token" + if (usage.cached > 0) "（缓存命中 ${tokens(usage.cached)}）" else "")
        parts += "输出 ${tokens(usage.output)}"
        if (usage.requests > 1) parts += "${usage.requests} 次请求"
        parts += money(cost)?.let { "约 $it" } ?: "没有单价，没估钱"
        return parts.joinToString(" · ") + if (usage.estimated) "（服务商没返回用量，按字数估算）" else ""
    }
}
