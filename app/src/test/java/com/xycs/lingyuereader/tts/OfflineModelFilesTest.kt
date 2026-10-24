package com.xycs.lingyuereader.tts

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/** 离线语音模型的下载、断点续传、校验、导入和解压（用本机的假服务器和小模型包）。 */
class OfflineModelFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var archive = ByteArray(0)
    private val ranges = mutableListOf<String?>()

    /** 做一个模型包：[top] 目录下放 [files]（名字 → 内容）。 */
    private fun tarBz2(top: String, files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { t ->
            t.putArchiveEntry(TarArchiveEntry("$top/"))
            t.closeArchiveEntry()
            for ((name, body) in files) {
                val bytes = body.toByteArray()
                t.putArchiveEntry(TarArchiveEntry(if (name.startsWith("/") || name.startsWith("..")) name else "$top/$name").apply { size = bytes.size.toLong() })
                t.write(bytes)
                t.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val melo = OfflineModels.MELO
    private val files = mapOf(
        "model.onnx" to "MODEL",
        "tokens.txt" to "a 1",
        "lexicon.txt" to "中 zh",
        "phone.fst" to "fst",
        "number.fst" to "skip me",
        "dict/jieba.dict.utf8" to "skip me too",
        "../../evil.txt" to "outside",
    )

    private fun model(bytes: ByteArray) = melo.copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))

    @Before
    fun start() {
        archive = tarBz2(melo.id, files)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            // 下到一半就卡住的服务器：给一点数据后一直不动。
            createContext("/stall/") { ex ->
                ex.sendResponseHeaders(200, archive.size.toLong())
                ex.responseBody.write(archive, 0, 100)
                ex.responseBody.flush()
                stalled.await(30, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { ex.close() }
            }
            createContext("/") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                ranges += range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                val body = archive.copyOfRange(from, archive.size)
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-${archive.size - 1}/${archive.size}")
                ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    private val stalled = java.util.concurrent.CountDownLatch(1)

    @After
    fun stop() {
        stalled.countDown()
        server.stop(0)
    }

    private fun files(root: File) = OfflineModelFiles(root, urlOf = { "http://127.0.0.1:${server.address.port}/${it.id}.tar.bz2" })

    @Test
    fun downloadVerifiesAndInstallsOnlyTheModelFiles() = runBlocking {
        val root = tmp.newFolder("models")
        val f = files(root)
        val m = model(archive)
        val phases = mutableSetOf<OfflinePhase>()
        f.download(m) { p, _, _ -> phases += p }
        assertTrue(f.installed(m))
        assertEquals(setOf(m.id), f.installedIds())
        val dir = f.dir(m)
        assertEquals("MODEL", File(dir, "model.onnx").readText())
        assertTrue(File(dir, "phone.fst").isFile)
        // 用不到的跳过；跑出目录的路径挡掉。
        assertFalse(File(dir, "number.fst").exists())
        assertFalse(File(dir, "dict").exists())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(root.parentFile, "evil.txt").exists())
        assertTrue(OfflinePhase.VERIFY in phases)
        // 下载的包装好后删掉，不占两份地方。
        assertEquals(listOf(m.id), root.list()!!.toList())
        assertTrue(f.sizeOnDisk(m) > 0)
        f.delete(m)
        assertFalse(f.installed(m))
        assertFalse(f.dir(m).exists())
    }

    @Test
    fun resumesAPartialDownload() = runBlocking {
        val root = tmp.newFolder("models")
        val m = model(archive)
        File(root, "${m.id}.tar.bz2.part").writeBytes(archive.copyOfRange(0, 100))
        files(root).download(m) { _, _, _ -> }
        assertEquals(listOf<String?>("bytes=100-"), ranges)
        assertTrue(files(root).installed(m))
    }

    @Test
    fun badChecksumIsRejectedAndThePartialFileDropped() = runBlocking {
        val root = tmp.newFolder("models")
        val m = model(archive).copy(sha256 = "0".repeat(64))
        try {
            files(root).download(m) { _, _, _ -> }
            fail()
        } catch (e: OfflineVoiceException) {
            assertTrue(e.message!!, "校验" in e.message!!)
        }
        assertFalse(files(root).installed(m))
        assertFalse(File(root, "${m.id}.tar.bz2.part").exists())
    }

    @Test
    fun importRecognisesTheModelByItsFolder() = runBlocking {
        val root = tmp.newFolder("models")
        val file = tmp.newFile("pkg.tar.bz2").apply { writeBytes(archive) }
        val m = files(root).import({ file.inputStream() }, file.length()) { _, _, _ -> }
        assertEquals(melo.id, m.id)
        assertTrue(files(root).installed(melo))
        assertEquals("中 zh", File(files(root).dir(melo), "lexicon.txt").readText())
    }

    @Test
    fun importRejectsUnknownOrIncompletePackages() = runBlocking {
        val root = tmp.newFolder("models")
        suspend fun importBytes(b: ByteArray): String = try {
            files(root).import({ b.inputStream() }, b.size.toLong()) { _, _, _ -> }
            "ok"
        } catch (e: OfflineVoiceException) {
            e.message!!
        }
        assertTrue("不认识" in importBytes("not an archive".toByteArray()))
        assertTrue("不认识" in importBytes(tarBz2("some-other-model", mapOf("model.onnx" to "x"))))
        val missing = importBytes(tarBz2(melo.id, mapOf("model.onnx" to "x")))
        assertTrue(missing, "不完整" in missing && "tokens.txt" in missing)
        assertFalse(files(root).installed(melo))
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun cancellingAStalledDownloadStopsRightAwayAndKeepsThePart() = runBlocking {
        val root = tmp.newFolder("models")
        val m = model(archive)
        val f = OfflineModelFiles(root, urlOf = { "http://127.0.0.1:${server.address.port}/stall/x" })
        val job = launch(Dispatchers.IO) { f.download(m) { _, _, _ -> } }
        val part = File(root, "${m.id}.tar.bz2.part")
        withTimeout(10_000) { while (part.length() < 100) delay(20) }
        val at = System.nanoTime()
        job.cancelAndJoin()
        // 读超时 60 秒：取消时断开连接，马上就停。下了的部分留着，下次接着下。
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - at) < 5)
        assertEquals(100L, part.length())
    }

    @Test
    fun cancellingDuringExtractionLeavesNoHalfInstalledFiles() = runBlocking<Unit> {
        val root = tmp.newFolder("models")
        // 一个解压要好一会儿的大文件（随机数据压不小），解到一半取消。
        val random = java.util.Random(7)
        val big = String(CharArray(3_000_000) { ('a'.code + random.nextInt(26)).toChar() })
        val pkg = tarBz2(melo.id, files.filterKeys { !it.startsWith("..") } + ("model.onnx" to big))
        val error = runCatching {
            files(root).import({ pkg.inputStream() }, pkg.size.toLong()) { phase, _, _ ->
                if (phase == OfflinePhase.EXTRACT) throw kotlinx.coroutines.CancellationException("读者取消")
            }
        }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertTrue(root.list()!!.none { it.endsWith(".installing") })
        assertFalse(files(root).installed(melo))
    }
}
