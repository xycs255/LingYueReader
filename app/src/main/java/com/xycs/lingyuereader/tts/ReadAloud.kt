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
import com.xycs.lingyuereader.data.ReadingProgress
import com.xycs.lingyuereader.data.Sentences
import com.xycs.lingyuereader.data.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** 定时关闭：到点停，或读完本章停。 */
sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer
    data object EndOfChapter : SleepTimer
}

/** 听书用哪种声音：系统的文字转语音、手机上的离线语音、服务商的 AI 语音。 */
enum class ReadAloudEngine { SYSTEM, OFFLINE, ONLINE }

/** 可选的语音。[label] 是给人看的说明，[name] 是引擎里的名字。 */
data class VoiceOption(val name: String, val label: String)

/**
 * 用 AI 语音朗读：服务商名（或离线模型名）、可选的声音、选用的声音，以及合成一句的函数。
 * [fingerprint] 区分服务商、模型、声音，缓存音频用。[offline] 为真时是手机上的离线语音，不联网、不计费。
 */
class AiVoice(
    val label: String,
    val voices: List<VoiceOption>,
    val voice: String,
    val fingerprint: (voice: String) -> String,
    val fetch: suspend (voice: String, text: String) -> ByteArray,
    val offline: Boolean = false,
) {
    fun withVoice(name: String): AiVoice = AiVoice(label, voices, name, fingerprint, fetch, offline)

    /** 缓存目录：离线语音是 WAV、个头大，和花钱买来的服务商音频分开放，免得把它们挤掉。 */
    val cacheName: String get() = if (offline) "tts-offline" else "tts-ai"
}

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
    /** 用 AI 语音读时是服务商名（如“硅基流动 CosyVoice”）或离线模型名；用系统语音时为空。 */
    val aiLabel: String? = null,
    /** 用的是手机上的离线语音。 */
    val offline: Boolean = false,
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
    private var engineGeneration = 0L
    private var appContext: Context? = null

    private var content: BookContent? = null
    private var queue: List<Utterance> = emptyList()
    private var index = 0
    private var pendingStart: (() -> Unit)? = null
    /** AI 语音：设了就不用系统的文字转语音。 */
    private var ai: AiVoice? = null
    private var speaker: AiSpeaker? = null
    /**
     * 把听到哪存成阅读进度：由打开听书的界面给出（按原文取锚点、写书架）。在这里存而不是在界面里存，
     * 是因为界面关了（从最近任务划掉、去看别的书）听书还在继续。每 5 秒最多存一次，暂停、停止时马上存。
     */
    private var persist: ((chapterIndex: Int, block: Int, char: Int) -> ReadingProgress?)? = null
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var lastSavedAt = 0L
    private val _saved = MutableStateFlow<Pair<String, ReadingProgress>?>(null)
    /** 刚存下的听书进度（书、进度）：界面据此更新书架上的进度，不再另写一次。 */
    val saved: StateFlow<Pair<String, ReadingProgress>?> = _saved.asStateFlow()

    /** 音频焦点：别的应用开始放音乐、来电时暂停；只是临时被占（导航播报、来电）的，结束后接着读。 */
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var pausedByFocus = false

    private fun requestFocus() {
        val am = appContext?.getSystemService(android.media.AudioManager::class.java) ?: return
        val request = focusRequest ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // 朗读是人声，被“压低音量”时也停下，不然听不清。
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
            .build()
            .also { focusRequest = it }
        am.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        appContext?.getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(request)
        pausedByFocus = false
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> if (_state.value?.playing == true) {
                pause()
                pausedByFocus = false
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (_state.value?.playing == true) {
                pause()
                pausedByFocus = true
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                resume()
            }
        }
    }

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
        saveProgress: ((chapterIndex: Int, block: Int, char: Int) -> ReadingProgress?)? = null,
        startPaused: Boolean = false,
    ) {
        invalidateUtterances()
        pendingStart = null
        haltEngine()
        // 换书前把上一本听到的地方存下。
        persistNow(force = true)
        persist = saveProgress
        appContext = context.applicationContext
        this.content = content
        setAi(ai, rate)
        val previous = _state.value
        val previousTimer = previous?.takeIf { it.bookId == bookId }?.timer ?: SleepTimer.Off
        // 定时关闭只对同一本书接着算；换了书，旧的定时也要真的取消，不能只是界面上不显示。
        if (previousTimer !is SleepTimer.At) main.removeCallbacks(sleepRunnable)
        val engine = tts
        _state.value = ReadAloudState(
            bookId, bookTitle, chapterIndex, content.chapters[chapterIndex].title, null, !startPaused, rate, previousTimer,
            voices = when {
                ai != null -> ai.voices
                previous != null && previous.aiLabel == null && previous.voices.isNotEmpty() -> previous.voices
                // 从 AI 或离线语音切回来、或停过一次：系统引擎早已就绪，不会再回调初始化，这里重新列一遍声音。
                ready && engine != null -> chineseVoices(engine)
                else -> emptyList()
            },
            voice = ai?.voice ?: voice,
            aiLabel = ai?.label,
            offline = ai?.offline == true,
        )
        loadChapter(chapterIndex, block, char)
        if (!startPaused) {
            requestFocus()
            ensureEngine {
                val current = _state.value ?: return@ensureEngine
                tts?.setSpeechRate(current.rate)
                applyVoice(current.voice)
                speakFrom(index)
            }
        }
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
    }

    fun pause() {
        invalidateUtterances()
        pendingStart = null
        _state.update { it?.copy(playing = false) }
        haltEngine()
        persistNow(force = true)
    }

    /** 存下听到的位置（见 [persist]）。 */
    private fun persistNow(force: Boolean) {
        val s = _state.value ?: return
        val u = s.current ?: return
        val save = persist ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt < 5_000) return
        lastSavedAt = now
        saver.launch {
            val progress = runCatching { save(s.chapterIndex, u.block, u.start) }.getOrNull() ?: return@launch
            _saved.value = s.bookId to progress
        }
    }

    /** 停下正在读的（系统语音或 AI 语音）。 */
    private fun haltEngine() {
        tts?.stop()
        speaker?.stop()
    }

    /** 换用 AI 语音或系统语音；AI 语音的播放器按声音建一个。 */
    private fun setAi(next: AiVoice?, rate: Float = _state.value?.rate ?: 1f) {
        speaker?.release()
        speaker = null
        ai = next
        val context = appContext ?: return
        if (next != null) {
            speaker = AiSpeaker(
                java.io.File(context.cacheDir, next.cacheName),
                fetch = { text, _ -> next.fetch(next.voice, text) },
                fingerprint = next.fingerprint(next.voice),
                onStart = ::onUtteranceStart,
                onDone = ::onUtteranceDone,
                onError = { id, message -> onUtteranceError(id, message) },
            ).also { it.speed = rate }
        }
    }

    fun resume() {
        val s = _state.value ?: return
        pausedByFocus = false
        requestFocus()
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
        persistNow(force = true)
        persist = null
        abandonFocus()
        invalidateUtterances()
        pendingStart = null
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
            setAi(a.withVoice(name ?: a.voice))
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
        persistNow(force = false)
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
        persist = null
        _saved.value = null
        invalidateUtterances()
        speaker?.release()
        speaker = null
        ai = null
        main.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        ready = false
        engineGeneration++
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
            pendingStart = null
            then()
            return
        }
        if (ready && tts != null) {
            then()
            return
        }
        pendingStart = then
        if (tts != null) return
        val generation = ++engineGeneration
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (generation != engineGeneration) return@post
                if (status != TextToSpeech.SUCCESS) {
                    pendingStart = null
                    if (speaker == null) _state.update { it?.copy(playing = false, error = "没有可用的语音引擎，请在系统设置里安装或启用“文字转语音”") }
                    tts = null
                    ready = false
                    engineGeneration++
                    return@post
                }
                val engine = tts ?: return@post
                val lang = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (speaker == null && (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED)) {
                    _state.update { it?.copy(error = "语音引擎缺少中文语音包，朗读可能不正常") }
                }
                if (speaker == null) _state.update { it?.copy(voices = chineseVoices(engine)) }
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
                val start = pendingStart
                pendingStart = null
                if (speaker == null && _state.value?.playing == true) start?.invoke()
            }
        }
    }
}
