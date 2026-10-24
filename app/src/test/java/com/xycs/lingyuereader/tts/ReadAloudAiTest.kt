package com.xycs.lingyuereader.tts

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/** 听书用 AI 语音：声音换成服务商的，按句合成；服务商出错时停在这句并说清原因。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadAloudAiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val book = BookContent(null, null, listOf(Chapter("第一章", listOf(Block.Para("甲一。甲二。")))))

    @Before
    fun setUp() = ReadAloud.resetForTest()

    @After
    fun tearDown() = ReadAloud.resetForTest()

    @Test
    fun aiVoiceErrorsStopOnTheSentenceWithTheReason() {
        val asked = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val ai = AiVoice(
            "硅基流动 CosyVoice",
            listOf(VoiceOption("v-a", "沉稳女声"), VoiceOption("v-b", "沉稳男声")),
            "v-b",
            fingerprint = { it },
            fetch = { voice, text ->
                asked += voice to text
                throw AiException("语音合成：账户余额不足")
            },
        )
        ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f, ai = ai)
        val s0 = ReadAloud.state.value!!
        assertEquals("硅基流动 CosyVoice", s0.aiLabel)
        assertEquals(listOf("v-a", "v-b"), s0.voices.map { it.name })
        assertEquals("v-b", s0.voice)
        repeat(100) {
            if (ReadAloud.state.value?.error != null) return@repeat
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        val s = ReadAloud.state.value!!
        assertEquals("语音合成：账户余额不足", s.error)
        assertFalse(s.playing)
        assertEquals(0, s.sentence)
        assertEquals("v-b" to "甲一。", asked.first())
    }
}
