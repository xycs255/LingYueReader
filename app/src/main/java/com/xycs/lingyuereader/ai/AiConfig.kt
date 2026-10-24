package com.xycs.lingyuereader.ai

import android.content.Context
import androidx.core.content.edit

/** 接口的格式：大多数服务商兼容 OpenAI 的 chat/completions；Claude 用 Anthropic 自己的 Messages 接口。 */
enum class AiProtocol(val label: String) {
    OPENAI("OpenAI 兼容"),
    ANTHROPIC("Anthropic"),
    /** 在这台手机上跑的模型（[DeviceModels]），不联网。 */
    DEVICE("本机"),
}

/** 常用服务商：选了自动填好地址和模型名，读者只需填自己的密钥。模型名可以改。 */
data class AiPreset(
    val id: String,
    val label: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val model: String,
    /** 去哪里申请密钥。 */
    val keySite: String,
)

object AiPresets {
    const val CUSTOM = "custom"
    const val LOCAL = "local"
    const val DEVICE = "device"

    val ALL = listOf(
        AiPreset("deepseek", "DeepSeek", AiProtocol.OPENAI, "https://api.deepseek.com", "deepseek-chat", "platform.deepseek.com"),
        AiPreset("qwen", "通义千问", AiProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "阿里云百炼控制台"),
        AiPreset("kimi", "Kimi", AiProtocol.OPENAI, "https://api.moonshot.cn/v1", "kimi-latest", "platform.moonshot.cn"),
        AiPreset("glm", "智谱 GLM", AiProtocol.OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "open.bigmodel.cn"),
        AiPreset("claude", "Claude", AiProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", "console.anthropic.com"),
        AiPreset(LOCAL, "局域网模型", AiProtocol.OPENAI, "http://192.168.1.2:11434/v1", "qwen3:8b", ""),
        AiPreset(DEVICE, "本机模型（离线）", AiProtocol.DEVICE, "", "", ""),
        AiPreset(CUSTOM, "自定义", AiProtocol.OPENAI, "", "", ""),
    )

    fun of(id: String): AiPreset = ALL.firstOrNull { it.id == id } ?: ALL.last()
}

/** 划选后“问 AI”的快捷问题：[label] 显示在按钮上，[instruction] 是发给模型的要求。读者可以改、可以加。 */
data class QuickAsk(val label: String, val instruction: String) {
    companion object {
        val DEFAULTS = listOf(
            QuickAsk("解释", "解释选中文字的意思：难懂的字词、典故、言外之意，以及它在上下文里起什么作用。"),
            QuickAsk("翻译", "翻译选中的文字：文言或古白话译成现代汉语，外文译成中文，现代汉语译成英文。只给译文，必要时加一句注释。"),
            QuickAsk("背景", "讲讲选中文字涉及的人物、典故、历史或文化背景，帮读者读懂这里。"),
        )
        const val MAX = 8
    }
}

/** 回答的长短，写进系统提示。 */
enum class AnswerLength(val label: String, val instruction: String) {
    SHORT("简短", "回答尽量简短：三五句话以内，只说最要紧的。"),
    MEDIUM("适中", "回答长短适中，一般不超过两三百字。"),
    LONG("详细", "可以详细展开，分点讲清楚，但不说废话。"),
}

/**
 * 局域网里的地址（家里电脑上的 Ollama、LM Studio）：只认字面上的私有地址和 .local 这类本地域名，不去解析域名。
 * 这类地址可以用 http://，也可以不填密钥。
 */
fun isLanHost(host: String?): Boolean {
    val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
    if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(':')
    val p = h.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    if (p.size != 4 || h.count { it == '.' } != 3) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
}

data class AiConfig(
    /** 默认关着：关着时灵阅不联网，阅读界面里也不出现 AI 的入口。 */
    val enabled: Boolean = false,
    val preset: String = AiPresets.ALL.first().id,
    val protocol: AiProtocol = AiPresets.ALL.first().protocol,
    val baseUrl: String = AiPresets.ALL.first().baseUrl,
    val model: String = AiPresets.ALL.first().model,
    /** 每家服务商一个密钥：来回换服务商不用重填。 */
    val keys: Map<String, String> = emptyMap(),
    val asks: List<QuickAsk> = QuickAsk.DEFAULTS,
    val length: AnswerLength = AnswerLength.MEDIUM,
    /** 问这本书时用的向量模型（服务商的 embeddings 接口）；为空时只按关键词检索。 */
    val embeddingModel: String = "",
    /** AI 朗读（听书、讲书用的语音合成）。 */
    val speech: SpeechConfig = SpeechConfig(),
) {
    /** 听书用 AI 语音：AI 助手开着、AI 朗读开着且填全了。 */
    val speechReady: Boolean get() = enabled && speech.enabled && speech.complete

    val apiKey: String get() = keys[preset].orEmpty()

    fun withKey(key: String): AiConfig = copy(keys = keys + (preset to key))

    /** 接口在局域网里：可以用 http://，密钥可以不填。 */
    val lan: Boolean get() = isLanHost(runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull())

    /** 地址、模型、密钥都填了（局域网里的模型可以不要密钥）。 */
    val complete: Boolean get() = if (onDevice) model.isNotBlank() else baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || lan)

    /** 用本机模型：不联网，上下文短。 */
    val onDevice: Boolean get() = protocol == AiProtocol.DEVICE

    /** 打开了且填全了：阅读界面里才出现 AI 的入口。 */
    val ready: Boolean get() = enabled && complete

    /** 设置页、回答下面显示的服务商名。 */
    val providerLabel: String
        get() = if (onDevice) "本机" else AiPresets.of(preset).takeIf { it.id != AiPresets.CUSTOM }?.label
            ?: runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.removePrefix("api.")
            ?: "自定义"

    /** 换服务商：地址、模型换成它的（选“自定义”时留着原来的当底子改），密钥换成这家的。 */
    fun withPreset(p: AiPreset): AiConfig = if (p.id == preset) this else copy(
        preset = p.id,
        protocol = p.protocol,
        baseUrl = if (p.id == AiPresets.CUSTOM) baseUrl else p.baseUrl,
        embeddingModel = "",
        model = if (p.id == AiPresets.CUSTOM) model else p.model,
    )

    /** 打日志、出错时不会把密钥带出去。 */
    override fun toString(): String = "AiConfig(enabled=$enabled, preset=$preset, protocol=$protocol, baseUrl=$baseUrl, model=$model, keys=${keys.keys})"
}

/**
 * AI 设置单独存一个文件（ai.xml）：备份规则把它排除在系统备份之外，灵阅自己的备份也不含它，
 * 密钥只留在这台手机上。
 */
class AiStore(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        enabled = sp.getBoolean("enabled", false),
        preset = sp.getString("preset", null) ?: AiConfig().preset,
        protocol = runCatching { AiProtocol.valueOf(sp.getString("protocol", null)!!) }.getOrDefault(AiConfig().protocol),
        baseUrl = sp.getString("baseUrl", null) ?: AiConfig().baseUrl,
        model = sp.getString("model", null) ?: AiConfig().model,
        keys = AiPresets.ALL.mapNotNull { p -> sp.getString("key.${p.id}", null)?.takeIf { it.isNotEmpty() }?.let { p.id to it } }.toMap(),
        asks = sp.getString("asks", null)?.let(::decodeAsks) ?: QuickAsk.DEFAULTS,
        length = runCatching { AnswerLength.valueOf(sp.getString("length", null)!!) }.getOrDefault(AnswerLength.MEDIUM),
        embeddingModel = sp.getString("embeddingModel", null).orEmpty(),
        speech = SpeechConfig().let { d ->
            SpeechConfig(
                enabled = sp.getBoolean("speech.enabled", false),
                preset = sp.getString("speech.preset", null) ?: d.preset,
                baseUrl = sp.getString("speech.baseUrl", null) ?: d.baseUrl,
                model = sp.getString("speech.model", null) ?: d.model,
                voice = sp.getString("speech.voice", null) ?: d.voice,
                instructions = sp.getString("speech.instructions", null).orEmpty(),
                keys = SpeechPresets.ALL.mapNotNull { p -> sp.getString("speech.key.${p.id}", null)?.takeIf { it.isNotEmpty() }?.let { p.id to it } }.toMap(),
            )
        },
    )

    private fun decodeAsks(raw: String): List<QuickAsk>? = runCatching {
        val a = org.json.JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> QuickAsk(o.getString("label"), o.getString("instruction")) } }
    }.getOrNull()

    fun save(c: AiConfig) {
        sp.edit {
            putBoolean("enabled", c.enabled)
            putString("preset", c.preset)
            putString("protocol", c.protocol.name)
            putString("baseUrl", c.baseUrl.trim())
            putString("model", c.model.trim())
            putString("asks", org.json.JSONArray().apply { c.asks.forEach { put(org.json.JSONObject().put("label", it.label).put("instruction", it.instruction)) } }.toString())
            putString("length", c.length.name)
            putString("embeddingModel", c.embeddingModel.trim())
            putBoolean("speech.enabled", c.speech.enabled)
            putString("speech.preset", c.speech.preset)
            putString("speech.baseUrl", c.speech.baseUrl.trim())
            putString("speech.model", c.speech.model.trim())
            putString("speech.voice", c.speech.voice.trim())
            putString("speech.instructions", c.speech.instructions.trim())
            SpeechPresets.ALL.forEach { p -> c.speech.keys[p.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("speech.key.${p.id}", it) } ?: remove("speech.key.${p.id}") }
            AiPresets.ALL.forEach { p -> c.keys[p.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("key.${p.id}", it) } ?: remove("key.${p.id}") }
        }
    }

    companion object {
        /** 备份规则（res/xml/backup_rules.xml、data_extraction_rules.xml）按这个文件名排除。 */
        const val FILE = "ai"
    }
}
