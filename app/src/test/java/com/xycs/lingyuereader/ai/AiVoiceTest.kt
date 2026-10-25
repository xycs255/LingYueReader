package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class AiVoiceTest {
    @Test
    fun speechPresetsFillVoicesAndKeepKeysApart() {
        val sf = SpeechConfig()
        assertEquals("siliconflow", sf.preset)
        assertTrue(sf.voice.startsWith("FunAudioLLM/CosyVoice2-0.5B:"))
        assertFalse("没填密钥不算填全", sf.complete)
        val withKey = sf.withKey("sk-sf")
        assertTrue(withKey.complete)
        val openai = withKey.withPreset(SpeechPresets.of("openai"))
        assertEquals("coral", openai.voice)
        assertEquals("", openai.apiKey)
        val local = openai.withPreset(SpeechPresets.of(SpeechPresets.LOCAL))
        assertTrue("局域网的 Kokoro 不要密钥", local.complete)
        assertEquals("sk-sf", local.withPreset(SpeechPresets.of("siliconflow")).apiKey)
        assertNotEquals(sf.fingerprint("a"), sf.fingerprint("b"))
        assertFalse(withKey.toString().contains("sk-sf"))
        val custom = SpeechConfig(preset = SpeechPresets.CUSTOM, voice = "v1")
        assertEquals(listOf("v1" to "v1"), custom.voices)
    }

    @Test
    fun speechAsksForMp3AndReturnsTheAudio() = runBlocking<Unit> {
        var seen: JSONObject? = null
        var auth: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/audio/speech") { ex ->
                seen = JSONObject(ex.requestBody.readBytes().decodeToString())
                auth = ex.requestHeaders.getFirst("Authorization")
                val bytes = ByteArray(200) { it.toByte() }
                ex.responseHeaders.add("Content-Type", "audio/mpeg")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/bad/audio/speech") { ex ->
                val bytes = """{"error":{"message":"Insufficient balance"}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(402, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val config = SpeechConfig(preset = SpeechPresets.LOCAL, baseUrl = "$base/v1", model = "kokoro", voice = "zf_xiaoxiao", instructions = "温柔")
        val audio = AiClient().speech(config, "你好。", "zm_yunxi")
        assertArrayEquals(ByteArray(200) { it.toByte() }, audio)
        assertEquals("kokoro", seen!!.getString("model"))
        assertEquals("你好。", seen!!.getString("input"))
        assertEquals("zm_yunxi", seen!!.getString("voice"))
        assertEquals("mp3", seen!!.getString("response_format"))
        assertFalse("只有支持的服务商才发朗读风格", seen!!.has("instructions"))
        assertNull("局域网不填密钥就不带", auth)
        val error = runCatching { AiClient().speech(config.copy(baseUrl = "$base/bad"), "你好") }.exceptionOrNull()
        assertTrue(error is AiException && error.message!!.contains("余额不足") && error.message!!.contains("Insufficient balance"))
        server.stop(0)
        val remote = runCatching { AiClient().speech(SpeechConfig(baseUrl = "http://tts.example.com/v1").withKey("k"), "x") }.exceptionOrNull()
        assertTrue("公网地址不许明文", remote is AiException && remote.message!!.contains("https"))
    }

    @Test
    fun speechRejectsOversizedChunkedAudioWithoutKeepingItAllInMemory() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/audio/speech") { ex ->
                ex.requestBody.use { it.readBytes() }
                ex.responseHeaders.add("Content-Type", "audio/mpeg")
                ex.sendResponseHeaders(200, 0) // 不提供 Content-Length，必须边读边检查
                runCatching {
                    ex.responseBody.use { out ->
                        val chunk = ByteArray(16 * 1024)
                        repeat(AiClient.MAX_SPEECH_BYTES / chunk.size + 1) { out.write(chunk) }
                    }
                }
            }
            start()
        }
        try {
            val config = SpeechConfig(preset = SpeechPresets.LOCAL,
                baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "fake", voice = "voice")
            val error = runCatching { AiClient().speech(config, "测试") }.exceptionOrNull()
            assertTrue(error is AiException && error.message.orEmpty().contains("20 MB"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun talkScriptsParseAndSplitLongLines() {
        val long = "第一句话说得很长很长很长很长很长很长很长很长。" + "第二句话也说得很长很长很长很长很长很长很长很长。".repeat(3)
        val answer = """好的：{"title":"桃花源里的人","lines":[{"s":0,"t":"大家好。"},{"s":1,"t":"$long"},{"s":5,"t":" "}]}"""
        val duo = AiTalk.parse(answer, TalkStyle.DUO)!!
        assertEquals("桃花源里的人", duo.title)
        assertEquals(0, duo.lines.first().speaker)
        assertTrue(duo.lines.drop(1).all { it.speaker == 1 && it.text.length <= 80 })
        assertTrue(duo.lines.size > 2)
        assertTrue("一人讲解都算主讲", AiTalk.parse(answer, TalkStyle.SOLO)!!.lines.all { it.speaker == 0 })
        assertNull(AiTalk.parse("不是稿子", TalkStyle.SOLO))
        assertTrue(AiTalk.prompt(TalkStyle.DUO, TalkScope.READ, "第三章", "原文").contains("搭档"))
        val dir = Files.createTempDirectory("t").toFile()
        val file = File(dir, AiTalk.FILE)
        AiTalk.write(file, (1..35).associate { "k$it" to Talk("t$it", listOf(TalkLine(0, "x")), at = it.toLong()) })
        val back = AiTalk.read(file)
        assertEquals("只留最近 30 篇", 30, back.size)
        assertTrue("k35" in back && "k1" !in back)
        dir.deleteRecursively()
    }
}
