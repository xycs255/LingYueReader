package com.xycs.lingyuereader.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 离线语音：模型清单、设置、WAV 打包、sherpa-onnx 配置。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OfflineVoiceTest {
    @Test
    fun catalogIsConsistent() {
        assertEquals(OfflineModels.ALL.size, OfflineModels.ALL.map { it.id }.toSet().size)
        for (m in OfflineModels.ALL) {
            assertEquals("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${m.id}.tar.bz2", m.url)
            assertEquals(64, m.sha256.length)
            assertTrue(m.voices.isNotEmpty())
            assertEquals(m.voices.size, m.voices.map { it.name }.toSet().size)
            assertTrue(m.voices.all { it.name.toIntOrNull() != null })
            // 必需的文件不能被跳过。
            assertTrue(m.required.none { r -> m.skip.any { s -> r == s || r.startsWith(s) } })
        }
        // Kokoro 的 103 个说话人：女声编号落在 zf_ 段，男声落在 zm_ 段。
        val k = OfflineModels.KOKORO.voices
        assertEquals(8, k.count { it.label.startsWith("女声") })
        assertEquals(8, k.count { it.label.startsWith("男声") })
        assertTrue(k.filter { it.label.startsWith("女声") }.all { it.name.toInt() in 3..57 })
        assertTrue(k.filter { it.label.startsWith("男声") }.all { it.name.toInt() in 58..102 })
        assertEquals("女声 1", OfflineModels.KOKORO.voiceLabel("3"))
    }

    @Test
    fun configFallsBackToTheFirstVoiceAndRoundTrips() {
        val c = OfflineVoiceConfig(model = OfflineModels.MELO.id, voices = mapOf(OfflineModels.MELO.id to "99"))
        assertEquals("0", c.voice())
        assertEquals(OfflineModels.KOKORO, OfflineVoiceConfig(model = "gone").current)
        val k = c.copy(model = OfflineModels.KOKORO.id).withVoice("60")
        assertEquals("60", k.voice())

        val context: Context = ApplicationProvider.getApplicationContext()
        val store = OfflineVoiceStore(context)
        assertFalse(store.load().enabled)
        store.save(k.copy(enabled = true))
        assertEquals(k.copy(enabled = true), OfflineVoiceStore(context).load())
    }

    @Test
    fun wavHeaderDescribesMono16BitPcm() {
        val wav = Wav.encode(floatArrayOf(0f, 1f, -1f, 2f), 24000)
        assertEquals(44 + 8, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 8, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(24000, b.getInt(24))
        assertEquals(48000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertEquals(listOf(0, 32767, -32767, 32767), (0 until 4).map { b.getShort(44 + it * 2).toInt() })
        assertEquals(44 + 24000 / 5 * 2, Wav.silence(24000).size)
    }

    @Test
    fun sherpaConfigPointsIntoTheModelFolder() {
        val dir = File("/m")
        val k = OfflineEngine.config(OfflineModels.KOKORO, dir, 4)
        assertEquals("/m/model.int8.onnx", k.model.kokoro.model)
        assertEquals("/m/espeak-ng-data", k.model.kokoro.dataDir)
        assertEquals("/m/lexicon-us-en.txt,/m/lexicon-zh.txt", k.model.kokoro.lexicon)
        assertEquals(4, k.model.numThreads)
        assertEquals("", k.model.vits.model)
        val m = OfflineEngine.config(OfflineModels.MELO, dir, 2)
        assertEquals("/m/model.onnx", m.model.vits.model)
        assertEquals("/m/phone.fst,/m/date.fst", m.ruleFsts)
        assertEquals("", m.model.kokoro.model)
    }

    @Test
    fun offlineVoicesKeepTheirOwnCache() {
        val v = AiVoice("Kokoro 中英", listOf(VoiceOption("3", "女声 1")), "3", { it }, { _, _ -> ByteArray(0) }, offline = true)
        val w = v.withVoice("4")
        assertTrue(w.offline)
        assertEquals("4", w.voice)
        assertEquals("tts-offline", w.cacheName)
        assertEquals("tts-ai", AiVoice("x", emptyList(), "a", { it }, { _, _ -> ByteArray(0) }).cacheName)
    }
}
