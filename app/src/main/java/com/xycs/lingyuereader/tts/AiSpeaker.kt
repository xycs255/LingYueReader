package com.xycs.lingyuereader.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * AI 语音的播放器：一句句向服务商要音频（mp3），边放边预取后面 [AHEAD] 句，音频按“服务商+声音+文字”缓存在本机
 * （重听、往回跳不再花钱）。每句开始、读完、出错都回调，和系统语音的回调一样用。
 */
class AiSpeaker(
    private val cacheDir: File,
    /** 合成一句：返回音频字节；[voice] 为空用默认声音。出错时抛出带中文说明的异常。 */
    private val fetch: suspend (text: String, voice: String?) -> ByteArray,
    /** 缓存键里区分服务商、模型、声音、风格。 */
    private val fingerprint: String,
    private val onStart: (id: String) -> Unit,
    private val onDone: (id: String) -> Unit,
    private val onError: (id: String, message: String) -> Unit,
    /**
     * 听书时不让 CPU 睡下（不计引用次数的 PARTIAL_WAKE_LOCK）：锁屏后句子之间还要合成下一句，离线语音在手机上合成，慢时要好几秒，
     * 只靠播放器的唤醒锁，句与句之间就会停住。开始读时拿，每句续一次时限，停下、出错、释放时放；忘了放也会到时自己失效。
     */
    private val wakeLock: PowerManager.WakeLock? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null

    /** 语速（0.5–2）。 */
    var speed: Float = 1f
        set(value) {
            field = value
            player?.let { p -> runCatching { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(value) } }
        }

    /** 一句：身份、文字、用哪个声音（为空用默认，讲书两人对谈时各用各的）。 */
    data class Item(val id: String, val text: String, val voice: String? = null)

    /** 从头依次读 [items]（句子的身份、文字）；先停掉正在读的。 */
    fun play(items: List<Pair<String, String>>) = playItems(items.map { Item(it.first, it.second) })

    fun playItems(items: List<Item>) {
        stop()
        if (items.isEmpty()) return
        keepAwake()
        job = scope.launch {
            // 预取的句子出错只记在它自己的结果里：后面的句子先失败时不能连带取消整个朗读，轮到它时再报。
            val pending = HashMap<Int, Deferred<Result<File>>>()
            fun prefetch(i: Int) {
                if (i in items.indices && i !in pending) {
                    pending[i] = async(Dispatchers.IO) {
                        try {
                            Result.success(audio(items[i].text, items[i].voice))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }
            }
            for (i in items.indices) {
                keepAwake()
                for (k in i..i + AHEAD) prefetch(k)
                val id = items[i].id
                val file = pending.remove(i)!!.await().getOrElse { e ->
                    onError(id, e.message ?: "语音合成失败")
                    return@launch
                }
                onStart(id)
                val ok = playFile(file)
                if (!ok) {
                    onError(id, "播放音频失败")
                    return@launch
                }
                onDone(id)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
    }

    /** 拿住（或续上）唤醒锁，每次最多 [AWAKE_MS]。 */
    private fun keepAwake() {
        wakeLock?.let { lock -> runCatching { lock.acquire(AWAKE_MS) } }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /** 取一句的音频：先看缓存，没有就合成后存下。 */
    private suspend fun audio(text: String, voice: String?): File {
        val file = File(cacheDir, key(text, voice) + ".mp3")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val bytes = fetch(text, voice)
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            // 同一句在预取范围里出现两次时会同时合成：各写各的临时文件，改名时后到的为准，不会写坏。
            val tmp = File(cacheDir, file.name + "." + java.util.UUID.randomUUID() + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            trim()
        }
        return file
    }

    /** 放完一句：返回是否正常放完。协程取消时停下并释放。 */
    private suspend fun playFile(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            p.setOnCompletionListener {
                if (player === p) player = null
                p.release()
                if (cont.isActive) cont.resume(true)
            }
            p.setOnErrorListener { _, _, _ ->
                if (player === p) player = null
                p.release()
                if (cont.isActive) cont.resume(false)
                true
            }
            p.prepare()
            p.start()
            if (speed != 1f) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        } catch (e: Exception) {
            file.delete()
            if (player === p) player = null
            p.release()
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation {
            runCatching { p.stop() }
            p.release()
            if (player === p) player = null
        }
    }

    private fun key(text: String, voice: String?): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(fingerprint.toByteArray())
        md.update(0)
        voice?.let { md.update(it.toByteArray()) }
        md.update(0)
        md.update(text.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 缓存超过 [MAX_CACHE] 时删掉最久没用的。 */
    private fun trim() {
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".mp3") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val AHEAD = 3
        /** 唤醒锁每句续的时限：合成加播放一句用不了这么久，出了岔子也不会一直耗电。 */
        const val AWAKE_MS = 10 * 60 * 1000L
        const val MAX_CACHE = 80L * 1024 * 1024
    }
}
