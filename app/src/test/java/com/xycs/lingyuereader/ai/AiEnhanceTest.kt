package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

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
        val book = AiSession(AiTask.AskBook(ReadPos(2, 3, 17)), listOf(AiTurn("谁？", "他[1]", sources = listOf(AiSource(1, 0, "第一章", 4, "原文")), loading = false, replay = "谁？")))
        history.save(book)
        assertEquals(listOf("谁？", "选文"), history.list().map { it.title })
        assertEquals("原文", history.load(book.id)!!.turns.single().sources.single().text)
        assertEquals(17, ((history.load(book.id)!!.task) as AiTask.AskBook).pos.char)
        // 旧版记录没有字符坐标，继续追问时不能默认读完当前长段。
        val old = AiHistory.encode(book).apply { remove("char") }
        assertEquals(0, ((AiHistory.decode(old)!!.task) as AiTask.AskBook).pos.char)
        history.remove(session.id)
        assertEquals(1, history.list().size)
        history.save(AiSession(AiTask.Recap(ReadPos(0, 0), "")))
        assertEquals(1, history.list().size)
        // 同一毫秒里连存几段，也按存的先后排（新的在前）。
        history.clear()
        val titles = (1..8).map { "第$it 段" }
        titles.forEach { t -> history.save(AiSession(AiTask.Selection(t, "", ""), listOf(AiTurn("解释", "答", loading = false)))) }
        assertEquals(titles.reversed(), history.list().map { it.title })
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
    fun characterChunksNeverSendUnreadSuffixAndResumeInsideLongBlock() {
        val body = "甲".repeat(35_000) + "未读结尾"
        val content = BookContent("书", null, listOf(Chapter("第一章", listOf(Block.Para(body)))))
        val chunks = AiCharacters.chunks(content, null, ReadPos(0, 0, 31_000))
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.text.length <= AiCharacters.CHUNK })
        assertEquals(ReadPos(0, 0, 31_000), chunks.last().end)
        assertFalse(chunks.any { it.text.contains("未读结尾") })
        val first = chunks.first().end
        val rest = AiCharacters.chunks(content, first, ReadPos(0, 0, 31_000))
        assertEquals("甲".repeat(31_000 - first.char), rest.joinToString("") { it.text.substringAfter('\n') })
        val dir = Files.createTempDirectory("characters").toFile()
        val table = CharacterTable(emptyList(), 0, 0, 1L, first.char)
        AiCharacters.write(File(dir, AiCharacters.FILE), table)
        assertEquals(first.char, AiCharacters.read(File(dir, AiCharacters.FILE))!!.char)
        dir.deleteRecursively()
    }

    @Test
    fun characterChunksUseVisibleTextCoordinatesForBoldParagraphs() {
        val content = BookContent("书", null, listOf(Chapter("第一章", listOf(Block.Para("**甲**乙未读谜底")))))
        val chunks = AiCharacters.chunks(content, null, ReadPos(0, 0, 2))
        assertEquals("甲乙", chunks.single().text.substringAfter('\n'))
        assertEquals(ReadPos(0, 0, 2), chunks.single().end)
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
        File(dir, "c.json.tmp").mkdir()
        val failed = runCatching { AiCharacters.write(File(dir, "c.json"), table.copy(char = 2)) }.exceptionOrNull()
        assertTrue("保存失败须可见，不能把未持久化的人物表当成功", failed is java.io.IOException)
        assertEquals(table, AiCharacters.read(File(dir, "c.json")))
        File(dir, "c.json").writeText("{}")
        assertTrue("损坏的旧人物表不能静默视为空表", runCatching { AiCharacters.read(File(dir, "c.json")) }.isFailure)
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
    fun vectorsAppendWithoutRewritingAndRecoverAnInterruptedTail() {
        val dir = Files.createTempDirectory("vectors").toFile()
        val file = File(dir, VectorCache.FILE)
        val cache = VectorCache(file)
        cache.load("m")
        cache.put(mapOf(1L to floatArrayOf(1f)))
        val firstLength = file.length()
        cache.put(mapOf(2L to floatArrayOf(2f)))
        assertEquals(firstLength + 16, file.length())
        file.appendBytes(byteArrayOf(1, 2, 3, 4))
        val resumed = VectorCache(file)
        assertEquals(setOf(1L, 2L), resumed.load("m").keys)
        resumed.put(mapOf(3L to floatArrayOf(3f)))
        assertEquals(firstLength + 32, file.length())
        assertEquals(setOf(1L, 2L, 3L), VectorCache(file).load("m").keys)
        dir.deleteRecursively()
    }

    @Test
    fun vectorsKeepOldCacheWhenWriteFailsAndReadLegacyFormat() {
        val dir = Files.createTempDirectory("vectors").toFile()
        val file = File(dir, VectorCache.FILE)
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4C595645)
            out.writeUTF("m")
            out.writeInt(1)
            out.writeLong(1L)
            out.writeInt(1)
            out.writeFloat(1f)
        }
        val cache = VectorCache(file)
        assertEquals(setOf(1L), cache.load("m").keys)
        val blocked = File(dir, VectorCache.FILE + ".tmp")
        blocked.mkdir()
        assertTrue(runCatching { cache.put(mapOf(2L to floatArrayOf(2f))) }.exceptionOrNull() is VectorCacheException)
        assertEquals(setOf(1L), cache.load("m").keys)
        assertEquals(setOf(1L), VectorCache(file).load("m").keys)
        blocked.delete()
        cache.put(mapOf(2L to floatArrayOf(2f)))
        assertEquals(setOf(1L, 2L), VectorCache(file).load("m").keys)
        dir.deleteRecursively()
    }

    @Test
    fun malformedVectorCacheHeadersAndOversizedFilesFailBeforeAllocatingVectors() {
        val dir = Files.createTempDirectory("vector-bounds").toFile()
        val file = File(dir, VectorCache.FILE)
        try {
            DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(0x4C595645)
                out.writeUTF("m")
                out.writeInt(8_001)
            }
            val legacy = file.readBytes()
            assertTrue(runCatching { VectorCache(file).load("m") }.exceptionOrNull() is VectorCacheException)
            assertArrayEquals("拒绝加载不得更改旧缓存", legacy, file.readBytes())

            DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(0x4C595646)
                out.writeUTF("m")
                out.writeLong(1)
                out.writeInt(Int.MAX_VALUE)
            }
            assertTrue("维度应在分配前校验", runCatching { VectorCache(file).load("m") }.exceptionOrNull() is VectorCacheException)

            DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(0x4C595646)
                out.writeUTF("m")
                repeat(8_001) {
                    out.writeLong(it.toLong())
                    out.writeInt(1)
                    out.writeFloat(1f)
                }
            }
            val oversizedEntries = file.length()
            assertTrue(runCatching { VectorCache(file).load("m") }.exceptionOrNull() is VectorCacheException)
            assertEquals(oversizedEntries, file.length())

            RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            assertTrue("大文件应在解析前拒绝", runCatching { VectorCache(file).load("m") }.exceptionOrNull() is VectorCacheException)

            file.delete()
            val inMemory = VectorCache(file)
            inMemory.load("keep")
            inMemory.put(mapOf(99L to floatArrayOf(1f)))
            DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(0x4C595645)
                out.writeUTF("bad")
                out.writeInt(8_001)
            }
            assertTrue(runCatching { inMemory.load("bad") }.exceptionOrNull() is VectorCacheException)
            assertEquals("加载失败不能清掉已载入的旧向量", setOf(99L), inMemory.load("keep").keys)
        } finally {
            dir.deleteRecursively()
        }
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
    fun askBookCapsFakeEmbeddingRequestsAndStopsWhenCacheCannotBeSaved() = runBlocking<Unit> {
        val sent = CopyOnWriteArrayList<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                ex.requestBody.use { it.readBytes() }
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"线索\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/v1/embeddings") { ex ->
                val count = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("input").length()
                sent.add(count)
                val data = JSONArray().apply {
                    repeat(count) { put(JSONObject().put("index", it).put("embedding", JSONArray(listOf(1.0, 0.0)))) }
                }
                val body = JSONObject().put("data", data).toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("bounded-vectors").toFile()
        try {
            val config = AiConfig(enabled = true, preset = AiPresets.LOCAL,
                baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "chat", embeddingModel = "fake-embedding")
            val content = BookContent("书", null, listOf(Chapter("第一章", (0 until 80).map {
                Block.Para("线索 第 $it 段 " + "正文".repeat(230))
            })))
            val assistant = AiAssistant(scope, null, bookFile = { _, name -> File(dir, name) })
            assistant.saveConfig(config)
            assistant.startAskBook(AiBook("a", "书", null, content), ReadPos(0, 79))
            assistant.ask("线索是什么？")
            withTimeout(10_000) { while (assistant.session.value?.turns?.lastOrNull()?.loading != false) delay(10) }
            assertNull(assistant.session.value!!.turns.last().error)
            assertEquals("最多只发送 40 个段落和 1 个问题", 41, sent.sum())
            assertTrue("最多四批正文及一次问题向量", sent.size <= 5)
            assertTrue(sent.all { it in 1..AiClient.EMBED_BATCH })

            sent.clear()
            val blocked = File(dir, "ai-vectors.bin.tmp").apply { mkdir() }
            val failing = AiAssistant(scope, null, bookFile = { _, name -> File(dir, name) })
            failing.saveConfig(config.copy(embeddingModel = "another-fake-embedding"))
            failing.startAskBook(AiBook("b", "书", null, content), ReadPos(0, 79))
            failing.ask("线索是什么？")
            withTimeout(10_000) { while (failing.session.value?.turns?.lastOrNull()?.loading != false) delay(10) }
            assertTrue(failing.session.value!!.turns.last().error.orEmpty().contains("向量缓存"))
            assertEquals("缓存无法保存后不得再发下一批或问题向量", listOf(10), sent.toList())
            blocked.delete()
            Unit
        } finally {
            scope.cancel()
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun plainHttpOnlyForLanModels() = runBlocking<Unit> {
        val remote = AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "http://api.example.com/v1", model = "m").withKey("k")
        val error = runCatching { AiClient().stream(remote, "", listOf(AiMessage("user", "x"))) }.exceptionOrNull()
        assertTrue(error is AiException && error.message!!.contains("https"))
    }
}
