package com.xycs.lingyuereader.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Sentences
import com.xycs.lingyuereader.data.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** 定时关闭：到点停，或读完本章停。 */
sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer
    data object EndOfChapter : SleepTimer
}

/** 可选的语音。[label] 是给人看的说明，[name] 是引擎里的名字。 */
data class VoiceOption(val name: String, val label: String)

/**
 * 用 AI 语音朗读：服务商名、可选的声音、选用的声音，以及合成一句的函数。
 * [fingerprint] 区分服务商、模型、声音，缓存音频用。
 */
class AiVoice(
    val label: String,
    val voices: List<VoiceOption>,
    val voice: String,
    val fingerprint: (voice: String) -> String,
    val fetch: suspend (voice: String, text: String) -> ByteArray,
)

data class ReadAloudState(
    val bookId: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    /** 正在读的一句；刚开始、还没读出第一句时为空。 */
    val current: Utterance?,
    val playing: Boolean,
    val rate: Float,
    val timer: SleepTimer,
    /** 语音引擎不可用（未安装或不支持中文）时的说明。 */
    val error: String? = null,
    /** 本章第几句（从 0 起）、共几句，用来显示进度。 */
    val sentence: Int = 0,
    val sentences: Int = 0,
    /** 引擎里可选的中文语音；为空时只能用默认。 */
    val voices: List<VoiceOption> = emptyList(),
    /** 选用的语音名；为空用引擎默认。 */
    val voice: String? = null,
    /** 用 AI 语音读时是服务商名（如“硅基流动 CosyVoice”）；用系统语音时为空。 */
    val aiLabel: String? = null,
)

/**
 * 听书。用系统的文字转语音按句朗读，一章读完接着读下一章。整个应用只有一个实例，
 * 由前台服务保活，锁屏或离开阅读页也能继续。
 */
object ReadAloud {

    private val _state = MutableStateFlow<ReadAloudState?>(null)
    val state: StateFlow<ReadAloudState?> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var appContext: Context? = null

    private var content: BookContent? = null
    private var queue: List<Utterance> = emptyList()
    private var index = 0
    private var pendingStart: (() -> Unit)? = null
    /** AI 语音：设了就不用系统的文字转语音。 */
    private var ai: AiVoice? = null
    private var speaker: AiSpeaker? = null
    /** 每次重新排队或中断朗读都换代；旧引擎即使继续回调，也不能改变新位置。 */
    private var utteranceGeneration = 0L

    /** 从某本书的某个位置开始读；已在读的话先停下。 */
    fun start(
        context: Context,
        bookId: String,
        bookTitle: String,
        content: BookContent,
        chapterIndex: Int,
        block: Int,
        char: Int,
        rate: Float,
        voice: String? = null,
        ai: AiVoice? = null,
    ) {
        invalidateUtterances()
        haltEngine()
        appContext = context.applicationContext
        this.content = content
        setAi(ai)
        val previous = _state.value
        val previousTimer = previous?.takeIf { it.bookId == bookId }?.timer ?: SleepTimer.Off
        _state.value = ReadAloudState(
            bookId, bookTitle, chapterIndex, content.chapters[chapterIndex].title, null, true, rate, previousTimer,
            voices = if (ai != null) ai.voices else previous?.voices?.takeIf { previous.aiLabel == null }.orEmpty(),
            voice = ai?.voice ?: voice,
            aiLabel = ai?.label,
        )
        loadChapter(chapterIndex, block, char)
        ensureEngine {
            val current = _state.value ?: return@ensureEngine
            tts?.setSpeechRate(current.rate)
            applyVoice(current.voice)
            speakFrom(index)
        }
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
    }

    fun pause() {
        invalidateUtterances()
        _state.update { it?.copy(playing = false) }
        haltEngine()
    }

    /** 停下正在读的（系统语音或 AI 语音）。 */
    private fun haltEngine() {
        tts?.stop()
        speaker?.stop()
    }

    /** 换用 AI 语音或系统语音；AI 语音的播放器按声音建一个。 */
    private fun setAi(next: AiVoice?) {
        speaker?.release()
        speaker = null
        ai = next
        val context = appContext ?: return
        if (next != null) {
            speaker = AiSpeaker(
                java.io.File(context.cacheDir, "tts-ai"),
                fetch = { text, _ -> next.fetch(next.voice, text) },
                fingerprint = next.fingerprint(next.voice),
                onStart = ::onUtteranceStart,
                onDone = ::onUtteranceDone,
                onError = { id, message -> onUtteranceError(id, message) },
            ).also { it.speed = _state.value?.rate ?: 1f }
        }
    }

    fun resume() {
        val s = _state.value ?: return
        invalidateUtterances()
        _state.value = s.copy(playing = true, error = null)
        ensureEngine {
            val current = _state.value ?: return@ensureEngine
            tts?.setSpeechRate(current.rate)
            applyVoice(current.voice)
            speakFrom(index)
        }
    }

    fun toggle() {
        if (_state.value?.playing == true) pause() else resume()
    }

    fun stop() {
        invalidateUtterances()
        main.removeCallbacks(sleepRunnable)
        haltEngine()
        speaker?.release()
        speaker = null
        ai = null
        _state.value = null
        content = null
        queue = emptyList()
        appContext?.let { it.stopService(Intent(it, ReadAloudService::class.java)) }
    }

    /** 调语速：从当前这句重新读起才会生效。 */
    fun setRate(rate: Float) {
        val s = _state.value ?: return
        _state.value = s.copy(rate = rate)
        tts?.setSpeechRate(rate)
        speaker?.let {
            // AI 语音直接改播放速度，不用重读这一句。
            it.speed = rate
            return
        }
        if (s.playing) {
            invalidateUtterances()
            tts?.stop()
            speakFrom(index)
        }
    }

    /** 换语音：从当前这句重新读起。 */
    fun setVoice(name: String?) {
        val s = _state.value ?: return
        _state.value = s.copy(voice = name)
        ai?.let { a ->
            invalidateUtterances()
            setAi(AiVoice(a.label, a.voices, name ?: a.voice, a.fingerprint, a.fetch))
            if (s.playing) speakFrom(index)
            return
        }
        applyVoice(name)
        if (s.playing) {
            invalidateUtterances()
            tts?.stop()
            speakFrom(index)
        }
    }

    private fun applyVoice(name: String?) {
        val engine = tts ?: return
        val voice = name?.let { n -> runCatching { engine.voices?.firstOrNull { it.name == n } }.getOrNull() }
        if (voice != null) {
            engine.voice = voice
        } else {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        }
    }

    /** 上一句、下一句（下一句读到章末就接下一章）。 */
    fun previousSentence() = jump(index - 1)

    fun nextSentence() = jump(index + 1)

    private fun jump(to: Int) {
        val s = _state.value ?: return
        invalidateUtterances()
        if (to >= queue.size) {
            haltEngine()
            nextChapter()
            return
        }
        index = to.coerceAtLeast(0)
        _state.value = s.copy(current = queue.getOrNull(index), sentence = index)
        if (s.playing) speakFrom(index)
    }

    fun setTimer(minutes: Int?) {
        main.removeCallbacks(sleepRunnable)
        val timer = when (minutes) {
            null -> SleepTimer.Off
            0 -> SleepTimer.EndOfChapter
            else -> {
                main.postDelayed(sleepRunnable, minutes * 60_000L)
                SleepTimer.At(System.currentTimeMillis() + minutes * 60_000L, minutes)
            }
        }
        _state.update { it?.copy(timer = timer) }
    }

    private val sleepRunnable = Runnable { stop() }

    private fun loadChapter(chapterIndex: Int, block: Int, char: Int) {
        val chapter = content?.chapters?.getOrNull(chapterIndex) ?: return
        queue = Sentences.split(chapter.blocks, block, char)
        index = 0
        _state.update {
            it?.copy(chapterIndex = chapterIndex, chapterTitle = chapter.title, current = queue.firstOrNull(), sentence = 0, sentences = queue.size)
        }
    }

    /** 把本章从第 [from] 句起全部排进语音队列。 */
    private fun speakFrom(from: Int) {
        invalidateUtterances()
        speaker?.let { sp ->
            val s = _state.value ?: return
            if (!s.playing) return
            if (from >= queue.size) {
                nextChapter()
                return
            }
            sp.play((from until queue.size).map { activeUtteranceId(it) to queue[it].text })
            return
        }
        if (!ready) return
        val engine = tts ?: return
        val s = _state.value ?: return
        if (!s.playing) return
        if (from >= queue.size) {
            nextChapter()
            return
        }
        engine.stop()
        for (i in from until queue.size) {
            engine.speak(queue[i].text, TextToSpeech.QUEUE_ADD, null, activeUtteranceId(i))
        }
    }

    private fun nextChapter() {
        val s = _state.value ?: return
        val book = content ?: return
        if (s.timer == SleepTimer.EndOfChapter || s.chapterIndex >= book.chapters.lastIndex) {
            stop()
            return
        }
        loadChapter(s.chapterIndex + 1, 0, 0)
        speakFrom(0)
    }

    /** 语音引擎回调（在引擎的线程上）：切回主线程处理。也供测试直接调用。 */
    internal fun onUtteranceStart(id: String) = main.post {
        val (generation, chapter, i) = parseId(id) ?: return@post
        val s = _state.value ?: return@post
        if (generation != utteranceGeneration || chapter != s.chapterIndex || !s.playing || i !in queue.indices || i < index) return@post
        index = i
        _state.value = s.copy(current = queue.getOrNull(i), sentence = i, sentences = queue.size)
    }

    internal fun onUtteranceDone(id: String) = main.post {
        val (generation, chapter, i) = parseId(id) ?: return@post
        val s = _state.value ?: return@post
        if (generation != utteranceGeneration || chapter != s.chapterIndex || !s.playing || i != index || i !in queue.indices) return@post
        if (i == queue.lastIndex) nextChapter() else index = i + 1
    }

    /** 朗读失败时停在出错的句子，避免把失败回调当成“读完”而跳到下一章。 */
    internal fun onUtteranceError(id: String, message: String? = null) = main.post {
        val (generation, chapter, i) = parseId(id) ?: return@post
        val s = _state.value ?: return@post
        if (generation != utteranceGeneration || chapter != s.chapterIndex || !s.playing || i != index || i !in queue.indices) return@post
        invalidateUtterances()
        haltEngine()
        _state.value = s.copy(
            current = queue[i], sentence = i, playing = false,
            error = message ?: "语音引擎朗读失败，请切换语音或检查系统语音设置后继续",
        )
    }

    /** 测试之间清掉进程级的状态。 */
    internal fun resetForTest() {
        invalidateUtterances()
        speaker?.release()
        speaker = null
        ai = null
        main.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        ready = false
        pendingStart = null
        content = null
        queue = emptyList()
        index = 0
        _state.value = null
    }

    /** 引擎里的中文语音，按“本机、女声/男声”排序并起个看得懂的名字。 */
    private fun chineseVoices(engine: TextToSpeech): List<VoiceOption> {
        val all = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == "zh" || it.locale.language == "cmn" || it.locale.language == "yue" }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))
        return all.mapIndexed { i, v ->
            val region = when {
                v.locale.language == "yue" || v.locale.country == "HK" -> "粤语"
                v.locale.country == "TW" -> "台湾"
                else -> "普通话"
            }
            val gender = when {
                v.name.contains("female", true) -> "女声"
                v.name.contains("male", true) -> "男声"
                else -> null
            }
            val parts = listOfNotNull("语音 ${i + 1}", region, gender, if (v.isNetworkConnectionRequired) "在线" else "本机")
            VoiceOption(v.name, parts.joinToString(" · "))
        }
    }

    private data class UtteranceId(val generation: Long, val chapter: Int, val sentence: Int)

    private fun invalidateUtterances() {
        utteranceGeneration++
    }

    /** 同一章节重复朗读也有不同的身份，供回调判断是否属于当前队列。 */
    internal fun activeUtteranceId(sentence: Int): String =
        "$utteranceGeneration:${_state.value?.chapterIndex}:$sentence"

    private fun parseId(id: String): UtteranceId? {
        val parts = id.split(':')
        if (parts.size != 3) return null
        val generation = parts[0].toLongOrNull() ?: return null
        val chapter = parts[1].toIntOrNull() ?: return null
        val sentence = parts[2].toIntOrNull() ?: return null
        return UtteranceId(generation, chapter, sentence)
    }

    private fun ensureEngine(then: () -> Unit) {
        // AI 语音不用系统引擎，马上可以读。
        if (speaker != null) {
            then()
            return
        }
        if (ready && tts != null) {
            then()
            return
        }
        pendingStart = then
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (status != TextToSpeech.SUCCESS) {
                    _state.update { it?.copy(playing = false, error = "没有可用的语音引擎，请在系统设置里安装或启用“文字转语音”") }
                    tts = null
                    return@post
                }
                val engine = tts ?: return@post
                val lang = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _state.update { it?.copy(error = "语音引擎缺少中文语音包，朗读可能不正常") }
                }
                _state.update { it?.copy(voices = chineseVoices(engine)) }
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        onUtteranceStart(utteranceId)
                    }

                    override fun onDone(utteranceId: String) {
                        onUtteranceDone(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        onUtteranceError(utteranceId)
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        onUtteranceError(utteranceId)
                    }
                })
                ready = true
                pendingStart?.invoke()
                pendingStart = null
            }
        }
    }
}
