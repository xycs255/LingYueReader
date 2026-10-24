package com.xycs.lingyuereader.ai

import android.content.Context
import androidx.core.content.edit

/** 接口的格式：大多数服务商兼容 OpenAI 的 chat/completions；Claude 用 Anthropic 自己的 Messages 接口。 */
enum class AiProtocol(val label: String) {
    OPENAI("OpenAI 兼容"),
    ANTHROPIC("Anthropic"),
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

    val ALL = listOf(
        AiPreset("deepseek", "DeepSeek", AiProtocol.OPENAI, "https://api.deepseek.com", "deepseek-chat", "platform.deepseek.com"),
        AiPreset("qwen", "通义千问", AiProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "阿里云百炼控制台"),
        AiPreset("kimi", "Kimi", AiProtocol.OPENAI, "https://api.moonshot.cn/v1", "kimi-latest", "platform.moonshot.cn"),
        AiPreset("glm", "智谱 GLM", AiProtocol.OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "open.bigmodel.cn"),
        AiPreset("claude", "Claude", AiProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", "console.anthropic.com"),
        AiPreset(CUSTOM, "自定义", AiProtocol.OPENAI, "", "", ""),
    )

    fun of(id: String): AiPreset = ALL.firstOrNull { it.id == id } ?: ALL.last()
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
) {
    val apiKey: String get() = keys[preset].orEmpty()

    fun withKey(key: String): AiConfig = copy(keys = keys + (preset to key))

    /** 地址、模型、密钥都填了。 */
    val complete: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 打开了且填全了：阅读界面里才出现 AI 的入口。 */
    val ready: Boolean get() = enabled && complete

    /** 设置页、回答下面显示的服务商名。 */
    val providerLabel: String
        get() = AiPresets.of(preset).takeIf { it.id != AiPresets.CUSTOM }?.label
            ?: runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.removePrefix("api.")
            ?: "自定义"

    /** 换服务商：地址、模型换成它的（选“自定义”时留着原来的当底子改），密钥换成这家的。 */
    fun withPreset(p: AiPreset): AiConfig = if (p.id == preset) this else copy(
        preset = p.id,
        protocol = p.protocol,
        baseUrl = if (p.id == AiPresets.CUSTOM) baseUrl else p.baseUrl,
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
    )

    fun save(c: AiConfig) {
        sp.edit {
            putBoolean("enabled", c.enabled)
            putString("preset", c.preset)
            putString("protocol", c.protocol.name)
            putString("baseUrl", c.baseUrl.trim())
            putString("model", c.model.trim())
            AiPresets.ALL.forEach { p -> c.keys[p.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("key.${p.id}", it) } ?: remove("key.${p.id}") }
        }
    }

    companion object {
        /** 备份规则（res/xml/backup_rules.xml、data_extraction_rules.xml）按这个文件名排除。 */
        const val FILE = "ai"
    }
}
