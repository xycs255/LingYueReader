package com.xycs.lingyuereader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 离线合成出错时给读者看的说明。 */
class OfflineVoiceException(message: String) : Exception(message)

/**
 * 离线语音引擎：用 sherpa-onnx 在手机上合成，不联网。整个应用只载入一个模型（几百 MB 内存），
 * 换模型时先放掉旧的；同一时间只合成一句（引擎本身用多线程）。
 */
object OfflineEngine {
    private val lock = Mutex()
    private var loaded: Pair<String, OfflineTts>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idle: Job? = null

    /** 合成一句，返回 WAV。[sid] 是说话人编号。 */
    suspend fun synthesize(dir: File, model: OfflineModel, sid: Int, text: String): ByteArray = lock.withLock {
        idle?.cancel()
        // 一阵子没再用（听书停了、讲书放完）就放掉模型，把内存还给系统；再用时重新载入（几秒）。
        idle = scope.launch {
            delay(IDLE_MS)
            release()
        }
        withContext(Dispatchers.Default) {
            val tts = load(dir, model)
            val audio = try {
                tts.generate(text, sid.coerceIn(0, (tts.numSpeakers() - 1).coerceAtLeast(0)), 1f)
            } catch (e: Throwable) {
                throw OfflineVoiceException("离线语音合成失败：${e.message ?: e.javaClass.simpleName}")
            }
            if (audio.samples.isEmpty()) Wav.silence(audio.sampleRate) else Wav.encode(audio.samples, audio.sampleRate)
        }
    }

    /** 放掉载入的模型（删除模型、换模型、应用退出时）。 */
    suspend fun release() = lock.withLock {
        loaded?.second?.release()
        loaded = null
    }

    private fun load(dir: File, model: OfflineModel): OfflineTts {
        loaded?.let { (id, tts) -> if (id == model.id) return tts }
        loaded?.second?.release()
        loaded = null
        if (model.required.any { !File(dir, it.trimEnd('/')).exists() }) throw OfflineVoiceException("「${model.label}」没装好，请重新下载")
        val tts = try {
            OfflineTts(config = config(model, dir, threads()))
        } catch (e: UnsatisfiedLinkError) {
            throw OfflineVoiceException("这台手机不支持离线语音（缺少 ${android.os.Build.SUPPORTED_ABIS.firstOrNull()} 的引擎）")
        } catch (e: NoClassDefFoundError) {
            throw OfflineVoiceException("这台手机不支持离线语音")
        } catch (e: Throwable) {
            throw OfflineVoiceException("载入「${model.label}」失败：${e.message ?: e.javaClass.simpleName}")
        }
        loaded = model.id to tts
        return tts
    }

    private const val IDLE_MS = 3 * 60_000L

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** sherpa-onnx 的配置，照官方 Android 示例里这两种模型的写法。 */
    internal fun config(model: OfflineModel, dir: File, threads: Int): OfflineTtsConfig {
        fun f(name: String) = File(dir, name).path
        return when (model.kind) {
            OfflineKind.KOKORO -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = f("model.int8.onnx"),
                        voices = f("voices.bin"),
                        tokens = f("tokens.txt"),
                        dataDir = f("espeak-ng-data"),
                        lexicon = "${f("lexicon-us-en.txt")},${f("lexicon-zh.txt")}",
                    ),
                    numThreads = threads,
                ),
                ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst").joinToString(",") { f(it) },
            )
            OfflineKind.MELO -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = f("model.onnx"), lexicon = f("lexicon.txt"), tokens = f("tokens.txt")),
                    numThreads = threads,
                ),
                ruleFsts = listOf("phone.fst", "date.fst").joinToString(",") { f(it) },
            )
        }
    }
}
