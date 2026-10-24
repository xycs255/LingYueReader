package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class AiEnhanceTest {
    @Test
    fun onlyLiteralPrivateAddressesCountAsLan() {
        listOf("192.168.1.2", "10.0.0.8", "172.20.1.1", "127.0.0.1", "localhost", "pc.local", "[::1]", "nas.lan").forEach { assertTrue(it, isLanHost(it)) }
        listOf("172.32.0.1", "8.8.8.8", "api.deepseek.com", "192.168.1", "fd.example.com", null).forEach { assertFalse(it.toString(), isLanHost(it)) }
        val local = AiConfig(enabled = true).withPreset(AiPresets.of(AiPresets.LOCAL))
        assertTrue(local.lan)
        assertTrue("局域网模型不填密钥也能用", local.ready)
        assertFalse(AiConfig(enabled = true).ready)
    }

    @Test
    fun answerLengthGoesIntoTheSystemPrompt() {
        assertTrue(AiPrompts.system("书", null, AnswerLength.SHORT).contains(AnswerLength.SHORT.instruction))
        assertTrue(AiPrompts.keywords("他后来去了哪？", listOf("林舟是谁？")).contains("林舟是谁？"))
    }

    @Test
    fun historyRoundTripsAndSkipsUnfinishedTurns() {
        val dir = Files.createTempDirectory("h").toFile()
        val history = AiHistory { File(dir, AiHistory.FILE) }
        val session = AiSession(
            AiTask.Selection("选文", "上下文", "第一章", 0, listOf(NoteRange(1, 2, 5))),
            listOf(AiTurn("解释", "回答", loading = false), AiTurn("追问", loading = true)),
        )
        history.save(session)
        val loaded = history.load(session.id)!!
        assertEquals(1, loaded.turns.size)
        assertEquals("回答", loaded.turns.single().answer)
        assertEquals(listOf(NoteRange(1, 2, 5)), (loaded.task as AiTask.Selection).pieces)
        val book = AiSession(AiTask.AskBook(ReadPos(2, 3)), listOf(AiTurn("谁？", "他[1]", sources = listOf(AiSource(1, 0, "第一章", 4, "原文")), loading = false, replay = "谁？")))
        history.save(book)
        assertEquals(listOf("谁？", "选文"), history.list().map { it.title })
        assertEquals("原文", history.load(book.id)!!.turns.single().sources.single().text)
        history.remove(session.id)
        assertEquals(1, history.list().size)
        history.save(AiSession(AiTask.Recap(ReadPos(0, 0), "")))
        assertEquals(1, history.list().size)
        dir.deleteRecursively()
    }

    private val book = BookContent(
        "书", null,
        listOf(
            Chapter("第一章", listOf(Block.Para("甲".repeat(9_000)), Block.Para("乙".repeat(9_000)), Block.Para("丙丙"))),
            Chapter("第二章", listOf(Block.Para("丁丁"), Block.Para("戊戊"))),
        ),
    )

    @Test
    fun characterChunksStopAtTheReaderAndResume() {
        val all = AiCharacters.chunks(book, null, ReadPos(1, 0))
        assertEquals(2, all.size)
        assertEquals(ReadPos(0, 0), all[0].end)
        assertEquals(ReadPos(1, 0), all[1].end)
        assertTrue(all[1].text.contains("【第二章】"))
        assertFalse(all.any { it.text.contains("戊") })
        val rest = AiCharacters.chunks(book, ReadPos(1, 0), ReadPos(1, 1))
        assertEquals("戊戊", rest.single().text.substringAfter('\n'))
        assertTrue(AiCharacters.chunks(book, ReadPos(1, 1), ReadPos(1, 1)).isEmpty())
    }

    @Test
    fun parsesCharacterTablesLeniently() {
        val answer = "好的：\n```json\n{\"characters\":[{\"name\":\"林舟\",\"aliases\":[\"小舟\",\"林舟\"],\"role\":\"外乡人\",\"summary\":\"来找舅舅。\",\"relations\":[{\"name\":\"老周\",\"relation\":\"舅舅\"}]},{\"name\":\"\"}]}\n```"
        val list = AiCharacters.parse(answer)
        assertEquals(1, list.size)
        val c = list.single()
        assertEquals(listOf("小舟"), c.aliases)
        assertEquals(listOf("老周" to "舅舅"), c.relations)
        val table = CharacterTable(list, 0, 1, 1L)
        assertEquals("林舟", table.find("小舟")?.name)
        assertNull(table.find("舟"))
        val dir = Files.createTempDirectory("c").toFile()
        AiCharacters.write(File(dir, "c.json"), table)
        assertEquals(table, AiCharacters.read(File(dir, "c.json")))
        dir.deleteRecursively()
    }

    @Test
    fun vectorsCacheAndFuse() {
        val dir = Files.createTempDirectory("v").toFile()
        val cache = VectorCache(File(dir, VectorCache.FILE))
        cache.load("m1")
        cache.put(mapOf(1L to floatArrayOf(1f, 0f)))
        assertArrayEquals(floatArrayOf(1f, 0f), VectorCache(File(dir, VectorCache.FILE)).load("m1")[1L], 0f)
        assertTrue("换了模型就作废", VectorCache(File(dir, VectorCache.FILE)).load("m2").isEmpty())
        assertEquals(1f, VectorCache.cosine(floatArrayOf(1f, 1f), floatArrayOf(2f, 2f)), 1e-5f)
        // 两种排序里都靠前的排第一。
        assertEquals(listOf(2, 1, 3), VectorCache.fuse(listOf(1, 2), listOf(2, 3)).let { listOf(it[0]) + it.drop(1).sorted() })
        assertTrue(VectorCache.key("甲") != VectorCache.key("乙"))
        dir.deleteRecursively()
    }

    @Test
    fun embedsThroughTheOpenAiCompatibleEndpoint() = runBlocking<Unit> {
        var seen: JSONObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/embeddings") { ex ->
                seen = JSONObject(ex.requestBody.readBytes().decodeToString())
                val n = seen!!.getJSONArray("input").length()
                val data = JSONArray().apply { (n - 1 downTo 0).forEach { put(JSONObject().put("index", it).put("embedding", JSONArray(listOf(it.toDouble(), 1.0)))) } }
                val bytes = JSONObject().put("data", data).toString().toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val config = AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "m")
        val out = AiClient().embed(config, "nomic-embed-text", listOf("a", "b"))
        assertEquals("nomic-embed-text", seen!!.getString("model"))
        assertEquals(listOf(0f, 1f), out.map { it[0] })
        server.stop(0)
    }

    @Test
    fun plainHttpOnlyForLanModels() = runBlocking<Unit> {
        val remote = AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "http://api.example.com/v1", model = "m").withKey("k")
        val error = runCatching { AiClient().stream(remote, "", listOf(AiMessage("user", "x"))) }.exceptionOrNull()
        assertTrue(error is AiException && error.message!!.contains("https"))
    }
}
