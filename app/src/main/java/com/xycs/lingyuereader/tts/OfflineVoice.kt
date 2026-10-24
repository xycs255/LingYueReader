package com.xycs.lingyuereader.tts

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** 离线语音模型的种类：配置方式不同。 */
enum class OfflineKind { KOKORO, MELO }

/**
 * 一款离线语音模型：sherpa-onnx 官方发布的模型包（.tar.bz2）。只收许可证允许随意使用的：
 * 训练数据仅限非商业使用（如标贝）或来源不明的都不收。
 */
data class OfflineModel(
    /** 模型包里的目录名，也是下载文件名（不含扩展名）。 */
    val id: String,
    val label: String,
    val summary: String,
    /** 模型包大小（字节），给读者看下载要多少流量。 */
    val bytes: Long,
    /** 装好后大约占多少空间（MB）。 */
    val installedMb: Int,
    /** 模型包的 SHA-256：下载完核对，防止下坏或被篡改。 */
    val sha256: String,
    val kind: OfflineKind,
    /** 可选的声音：name 是说话人编号。 */
    val voices: List<VoiceOption>,
    val license: String,
    /** 解压时跳过的文件和目录（用不到，省空间）。 */
    val skip: List<String>,
    /** 装好后必须有的文件。 */
    val required: List<String>,
) {
    val url: String get() = OfflineModels.BASE + id + ".tar.bz2"

    val sizeLabel: String get() = "${bytes / 1_000_000} MB"

    /** 声音编号（说话人 id）对应的说明。 */
    fun voiceLabel(name: String?): String = voices.firstOrNull { it.name == name }?.label ?: voices.first().label
}

object OfflineModels {
    const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    /** Kokoro v1.1 中文版，speaker 编号按模型里 speaker_names 的顺序。 */
    private val KOKORO_SPEAKERS = (
        "af_maple,af_sol,bf_vale,zf_001,zf_002,zf_003,zf_004,zf_005,zf_006,zf_007,zf_008,zf_017,zf_018,zf_019,zf_021," +
            "zf_022,zf_023,zf_024,zf_026,zf_027,zf_028,zf_032,zf_036,zf_038,zf_039,zf_040,zf_042,zf_043,zf_044,zf_046,zf_047," +
            "zf_048,zf_049,zf_051,zf_059,zf_060,zf_067,zf_070,zf_071,zf_072,zf_073,zf_074,zf_075,zf_076,zf_077,zf_078,zf_079," +
            "zf_083,zf_084,zf_085,zf_086,zf_087,zf_088,zf_090,zf_092,zf_093,zf_094,zf_099,zm_009,zm_010,zm_011,zm_012,zm_013," +
            "zm_014,zm_015,zm_016,zm_020,zm_025,zm_029,zm_030,zm_031,zm_033,zm_034,zm_035,zm_037,zm_041,zm_045,zm_050,zm_052," +
            "zm_053,zm_054,zm_055,zm_056,zm_057,zm_058,zm_061,zm_062,zm_063,zm_064,zm_065,zm_066,zm_068,zm_069,zm_080,zm_081," +
            "zm_082,zm_089,zm_091,zm_095,zm_096,zm_097,zm_098,zm_100"
        ).split(',')

    /** 中文女声、男声各取前 8 个，加两个英文声音。 */
    private fun kokoroVoices(): List<VoiceOption> {
        fun pick(prefix: String, label: String) = KOKORO_SPEAKERS.withIndex().filter { it.value.startsWith(prefix) }.take(8)
            .mapIndexed { n, (sid, _) -> VoiceOption(sid.toString(), "$label ${n + 1}") }
        return pick("zf_", "女声") + pick("zm_", "男声") +
            listOf(VoiceOption(KOKORO_SPEAKERS.indexOf("af_maple").toString(), "英文女声 Maple"), VoiceOption(KOKORO_SPEAKERS.indexOf("bf_vale").toString(), "英式女声 Vale"))
    }

    val KOKORO = OfflineModel(
        id = "kokoro-int8-multi-lang-v1_1",
        label = "Kokoro 中英",
        summary = "最自然，女声、男声各 8 个，中英混读；合成较慢，适合较新的手机",
        bytes = 147_031_220,
        installedMb = 195,
        sha256 = "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6",
        kind = OfflineKind.KOKORO,
        voices = kokoroVoices(),
        license = "Kokoro-82M v1.1（Apache-2.0）",
        skip = listOf("dict/", "lexicon-gb-en.txt", "README.md"),
        required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt", "espeak-ng-data/"),
    )

    val MELO = OfflineModel(
        id = "vits-melo-tts-zh_en",
        label = "MeloTTS 中英",
        summary = "一个女声，清晰平稳，合成快",
        bytes = 167_006_755,
        installedMb = 178,
        sha256 = "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514",
        kind = OfflineKind.MELO,
        voices = listOf(VoiceOption("0", "女声")),
        license = "MeloTTS（MIT）",
        skip = listOf("dict/", "model.int8.onnx", "new_heteronym.fst", "number.fst", "README.md"),
        required = listOf("model.onnx", "tokens.txt", "lexicon.txt"),
    )

    val ALL = listOf(KOKORO, MELO)

    fun of(id: String?): OfflineModel? = ALL.firstOrNull { it.id == id }
}

/** 离线语音的设置：听书时用不用、用哪个模型、每个模型选的声音。 */
data class OfflineVoiceConfig(
    val enabled: Boolean = false,
    val model: String = OfflineModels.KOKORO.id,
    val voices: Map<String, String> = emptyMap(),
) {
    val current: OfflineModel get() = OfflineModels.of(model) ?: OfflineModels.KOKORO

    fun voice(m: OfflineModel = current): String = voices[m.id]?.takeIf { v -> m.voices.any { it.name == v } } ?: m.voices.first().name

    fun withVoice(name: String, m: OfflineModel = current): OfflineVoiceConfig = copy(voices = voices + (m.id to name))
}

class OfflineVoiceStore(context: Context) {
    private val sp = context.getSharedPreferences("offline_voice", Context.MODE_PRIVATE)

    fun load(): OfflineVoiceConfig = OfflineVoiceConfig(
        enabled = sp.getBoolean("enabled", false),
        model = sp.getString("model", null) ?: OfflineModels.KOKORO.id,
        voices = runCatching { JSONObject(sp.getString("voices", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap()),
    )

    fun save(c: OfflineVoiceConfig) {
        sp.edit()
            .putBoolean("enabled", c.enabled)
            .putString("model", c.model)
            .putString("voices", JSONObject(c.voices).toString())
            .apply()
    }
}

/** 把合成出的浮点采样包成 16 位单声道 WAV，交给 MediaPlayer 放。 */
object Wav {
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun int(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
        fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray())
        int(36 + data)
        out.write("WAVEfmt ".toByteArray())
        int(16)
        short(1)
        short(1)
        int(sampleRate)
        int(sampleRate * 2)
        short(2)
        short(16)
        out.write("data".toByteArray())
        int(data)
        for (s in samples) short((s.coerceIn(-1f, 1f) * 32767f).toInt())
        return out.toByteArray()
    }

    /** 一小段静音：遇到只有标点的句子时用，免得播放器打不开空音频。 */
    fun silence(sampleRate: Int, millis: Int = 200): ByteArray = encode(FloatArray(sampleRate * millis / 1000), sampleRate)
}
