package com.xycs.lingyuereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 讲书播放的状态：放到第几句、在不在放、出错。 */
data class TalkPlayback(val line: Int = 0, val playing: Boolean = false, val error: String? = null)

/**
 * 讲书的播放器：一句句读讲书稿。设了离线语音或 AI 朗读就用它们（两人对谈时各用一个声音），
 * 否则用系统的文字转语音（搭档的音调高一点，听得出是两个人）。
 */
class TalkPlayer(
    private val context: Context,
    /** AI 语音：主讲、搭档的声音和合成函数；为空用系统语音。 */
    private val ai: Ai?,
) {
    class Ai(val voices: List<String>, val fingerprint: String, val fetch: suspend (text: String, voice: String?) -> ByteArray, val cacheName: String = "tts-ai")

    private val _state = MutableStateFlow(TalkPlayback())
    val state: StateFlow<TalkPlayback> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var lines: List<Pair<Int, String>> = emptyList()
    private var generation = 0
    private var speaker: AiSpeaker? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 换一篇稿子（不自动播放）。 */
    fun load(lines: List<Pair<Int, String>>) {
        pause()
        this.lines = lines
        _state.value = TalkPlayback()
    }

    fun toggle() = if (_state.value.playing) pause() else play(_state.value.line)

    /** 从第 [from] 句读起。 */
    fun play(from: Int = _state.value.line) {
        if (lines.isEmpty()) return
        generation++
        val start = from.coerceIn(0, lines.lastIndex)
        _state.value = TalkPlayback(start, playing = true)
        if (ai != null) playAi(start) else playSystem(start)
    }

    fun pause() {
        generation++
        speaker?.stop()
        tts?.stop()
        _state.value = _state.value.copy(playing = false)
    }

    fun release() {
        pause()
        speaker?.release()
        speaker = null
        tts?.shutdown()
        tts = null
    }

    private fun id(i: Int) = "$generation:$i"

    private fun parse(id: String): Int? = id.split(':').takeIf { it.size == 2 && it[0].toIntOrNull() == generation }?.get(1)?.toIntOrNull()

    private fun started(id: String) = main.post { parse(id)?.let { _state.value = _state.value.copy(line = it) } }

    private fun done(id: String) = main.post {
        val i = parse(id) ?: return@post
        if (i >= lines.lastIndex) _state.value = TalkPlayback(0, playing = false)
    }

    private fun failed(id: String, message: String) = main.post {
        val i = parse(id) ?: return@post
        generation++
        speaker?.stop()
        _state.value = TalkPlayback(i, playing = false, error = message)
    }

    private fun playAi(from: Int) {
        val a = ai ?: return
        val sp = speaker ?: AiSpeaker(java.io.File(context.cacheDir, a.cacheName), a.fetch, a.fingerprint, ::started, ::done, ::failed).also { speaker = it }
        sp.playItems((from..lines.lastIndex).map { i -> AiSpeaker.Item(id(i), lines[i].second, a.voices.getOrNull(lines[i].first) ?: a.voices.firstOrNull()) })
    }

    private fun playSystem(from: Int) {
        val engine = tts
        if (engine == null || !ttsReady) {
            if (engine == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    main.post {
                        if (status != TextToSpeech.SUCCESS) {
                            _state.value = _state.value.copy(playing = false, error = "没有可用的语音引擎")
                            return@post
                        }
                        val e = tts ?: return@post
                        e.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        e.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                started(utteranceId)
                            }

                            override fun onDone(utteranceId: String) {
                                done(utteranceId)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String) {
                                failed(utteranceId, "语音引擎朗读失败")
                            }
                        })
                        ttsReady = true
                        if (_state.value.playing) playSystem(_state.value.line)
                    }
                }
            }
            return
        }
        engine.stop()
        for (i in from..lines.lastIndex) {
            // 搭档的音调高一点，听得出是两个人。
            engine.setPitch(if (lines[i].first == 1) 1.25f else 1f)
            engine.speak(lines[i].second, TextToSpeech.QUEUE_ADD, Bundle(), id(i))
        }
    }
}
