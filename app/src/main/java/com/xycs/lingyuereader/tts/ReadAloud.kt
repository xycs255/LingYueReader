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

    /** 从某本书的某个位置开始读；已在读的话先停下。 */
    fun start(context: Context, bookId: String, bookTitle: String, content: BookContent, chapterIndex: Int, block: Int, char: Int, rate: Float) {
        appContext = context.applicationContext
        this.content = content
        val previousTimer = _state.value?.takeIf { it.bookId == bookId }?.timer ?: SleepTimer.Off
        _state.value = ReadAloudState(bookId, bookTitle, chapterIndex, content.chapters[chapterIndex].title, null, true, rate, previousTimer)
        loadChapter(chapterIndex, block, char)
        ensureEngine {
            tts?.setSpeechRate(rate)
            speakFrom(index)
        }
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
    }

    fun pause() {
        _state.update { it?.copy(playing = false) }
        tts?.stop()
    }

    fun resume() {
        val s = _state.value ?: return
        _state.value = s.copy(playing = true)
        ensureEngine { speakFrom(index) }
    }

    fun toggle() {
        if (_state.value?.playing == true) pause() else resume()
    }

    fun stop() {
        main.removeCallbacks(sleepRunnable)
        tts?.stop()
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
        if (s.playing) {
            tts?.stop()
            speakFrom(index)
        }
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
        _state.update { it?.copy(chapterIndex = chapterIndex, chapterTitle = chapter.title, current = queue.firstOrNull()) }
    }

    /** 把本章从第 [from] 句起全部排进语音队列。 */
    private fun speakFrom(from: Int) {
        val engine = tts ?: return
        val s = _state.value ?: return
        if (!s.playing) return
        if (from >= queue.size) {
            nextChapter()
            return
        }
        engine.stop()
        for (i in from until queue.size) {
            engine.speak(queue[i].text, TextToSpeech.QUEUE_ADD, null, "${s.chapterIndex}:$i")
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
        val (chapter, i) = parseId(id) ?: return@post
        val s = _state.value ?: return@post
        if (chapter != s.chapterIndex) return@post
        index = i
        _state.value = s.copy(current = queue.getOrNull(i))
    }

    internal fun onUtteranceDone(id: String) = main.post {
        val (chapter, i) = parseId(id) ?: return@post
        val s = _state.value ?: return@post
        if (chapter != s.chapterIndex || !s.playing) return@post
        if (i == queue.lastIndex) nextChapter() else index = i + 1
    }

    /** 测试之间清掉进程级的状态。 */
    internal fun resetForTest() {
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

    private fun parseId(id: String): Pair<Int, Int>? {
        val parts = id.split(':')
        val c = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val i = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return c to i
    }

    private fun ensureEngine(then: () -> Unit) {
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
                        onUtteranceDone(utteranceId)
                    }
                })
                ready = true
                pendingStart?.invoke()
                pendingStart = null
            }
        }
    }
}
