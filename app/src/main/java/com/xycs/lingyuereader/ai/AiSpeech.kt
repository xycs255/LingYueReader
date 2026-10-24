package com.xycs.lingyuereader.ai

/** 语音合成的服务商：地址、模型、可选的声音。都走 OpenAI 兼容的 /audio/speech 接口。 */
data class SpeechPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    /** 声音：接口里的名字 → 给人看的说明。为空时读者自己填。 */
    val voices: List<Pair<String, String>>,
    val keySite: String,
    /** 支持“朗读风格”的文字说明（OpenAI 的 instructions）。 */
    val instructions: Boolean = false,
)

object SpeechPresets {
    const val CUSTOM = "custom"
    const val LOCAL = "local"

    private const val COSY = "FunAudioLLM/CosyVoice2-0.5B"

    val ALL = listOf(
        SpeechPreset(
            "siliconflow", "硅基流动 CosyVoice", "https://api.siliconflow.cn/v1", COSY,
            listOf(
                "$COSY:anna" to "沉稳女声", "$COSY:claire" to "温柔女声", "$COSY:diana" to "欢快女声", "$COSY:bella" to "激情女声",
                "$COSY:alex" to "沉稳男声", "$COSY:benjamin" to "低沉男声", "$COSY:charles" to "磁性男声", "$COSY:david" to "欢快男声",
            ),
            "cloud.siliconflow.cn",
        ),
        SpeechPreset(
            "openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-tts",
            listOf("coral" to "Coral", "nova" to "Nova", "shimmer" to "Shimmer", "sage" to "Sage", "alloy" to "Alloy", "ash" to "Ash", "echo" to "Echo", "onyx" to "Onyx", "fable" to "Fable"),
            "platform.openai.com",
            instructions = true,
        ),
        SpeechPreset(
            LOCAL, "局域网 Kokoro", "http://192.168.1.2:8880/v1", "kokoro",
            listOf(
                "zf_xiaoxiao" to "女声 · 晓晓", "zf_xiaobei" to "女声 · 小贝", "zf_xiaoni" to "女声 · 小妮", "zf_xiaoyi" to "女声 · 小艺",
                "zm_yunxi" to "男声 · 云希", "zm_yunjian" to "男声 · 云健", "zm_yunxia" to "男声 · 云夏", "zm_yunyang" to "男声 · 云扬",
            ),
            "",
        ),
        SpeechPreset(CUSTOM, "自定义", "", "", emptyList(), ""),
    )

    fun of(id: String): SpeechPreset = ALL.firstOrNull { it.id == id } ?: ALL.last()
}

/** AI 朗读的设置：开没开、哪家、模型、声音、朗读风格；每家一个密钥。 */
data class SpeechConfig(
    val enabled: Boolean = false,
    val preset: String = SpeechPresets.ALL.first().id,
    val baseUrl: String = SpeechPresets.ALL.first().baseUrl,
    val model: String = SpeechPresets.ALL.first().model,
    val voice: String = SpeechPresets.ALL.first().voices.first().first,
    val instructions: String = "",
    val keys: Map<String, String> = emptyMap(),
) {
    val apiKey: String get() = keys[preset].orEmpty()

    fun withKey(key: String): SpeechConfig = copy(keys = keys + (preset to key))

    val lan: Boolean get() = isLanHost(runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull())

    val complete: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && voice.isNotBlank() && (apiKey.isNotBlank() || lan)

    val label: String get() = SpeechPresets.of(preset).label

    /** 可选的声音：预设的那几个；自定义时只有填的那一个。 */
    val voices: List<Pair<String, String>>
        get() = SpeechPresets.of(preset).voices.ifEmpty { listOfNotNull(voice.takeIf { it.isNotBlank() }?.let { it to it }) }

    fun withPreset(p: SpeechPreset): SpeechConfig = if (p.id == preset) this else copy(
        preset = p.id,
        baseUrl = if (p.id == SpeechPresets.CUSTOM) baseUrl else p.baseUrl,
        model = if (p.id == SpeechPresets.CUSTOM) model else p.model,
        voice = p.voices.firstOrNull()?.first ?: voice,
    )

    /** 缓存音频时区分服务商、模型、声音和风格：换了任何一样都重新合成。 */
    fun fingerprint(voice: String = this.voice): String = listOf(baseUrl.trim(), model.trim(), voice, instructions.trim()).joinToString("|")

    override fun toString(): String = "SpeechConfig(enabled=$enabled, preset=$preset, baseUrl=$baseUrl, model=$model, voice=$voice, keys=${keys.keys})"
}
