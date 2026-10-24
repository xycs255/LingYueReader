package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 连接层的几道防线：被截断的回答、重定向、关掉面板时马上断开。 */
class AiClientSafetyTest {
    private lateinit var server: HttpServer
    private val headersOnRedirectTarget = mutableListOf<String?>()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, type: String, body: String) {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/long/") { ex ->
            ex.requestBody.readBytes()
            reply(ex, 200, "text/event-stream", sse("""{"choices":[{"delta":{"content":"半句"},"finish_reason":"length"}]}""", "[DONE]"))
        }
        server.createContext("/moved/") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/target/v1/chat/completions")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        server.createContext("/target/") { ex ->
            headersOnRedirectTarget += ex.requestHeaders.getFirst("x-api-key")
            reply(ex, 200, "text/event-stream", sse("""{"choices":[{"delta":{"content":"不该到这"}}]}""", "[DONE]"))
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun config(path: String, protocol: AiProtocol = AiProtocol.OPENAI) =
        AiConfig(enabled = true, preset = AiPresets.CUSTOM, protocol = protocol, baseUrl = "http://127.0.0.1:${server.address.port}/$path/v1", model = "m").withKey("sk-secret")

    @Test
    fun truncatedAnswersAreReportedNotSavedAsComplete() = runBlocking<Unit> {
        val error = runCatching { AiClient().stream(config("long"), "", listOf(AiMessage("user", "译")), whole = true) }.exceptionOrNull()
        assertTrue(error is AiException && "截断" in error.message!!)
        val chat = AiClient().stream(config("long"), "", listOf(AiMessage("user", "问")))
        assertTrue(chat, chat.startsWith("半句") && "截断" in chat)
    }

    @Test
    fun redirectsAreNotFollowedWithTheKey() = runBlocking<Unit> {
        val error = runCatching { AiClient().stream(config("moved", AiProtocol.ANTHROPIC), "", listOf(AiMessage("user", "问"))) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error is AiException && "重定向" in error.message!!)
        assertEquals(emptyList<String?>(), headersOnRedirectTarget)
    }

    /**
     * 卡在读回答上的连接：先给一段，然后一直等，直到 disconnect() 才出错返回（Android 的实现就是这样，
     * 桌面 JDK 的 disconnect 会先把回包读完，测不出来，所以用假的连接）。
     */
    private class StalledConnection(url: java.net.URL) : java.net.HttpURLConnection(url) {
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = disconnected.countDown()
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        override fun getInputStream(): java.io.InputStream = object : java.io.InputStream() {
            private var head = "data: {\"choices\":[{\"delta\":{\"content\":\"想\"}}]}\n\n".toByteArray().inputStream()
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = head.read(b, off, len)
                if (n > 0) return n
                // 模型还在想：一直不回，直到连接被断开。
                if (!disconnected.await(30, TimeUnit.SECONDS)) return -1
                throw java.io.IOException("Socket closed")
            }
        }
    }

    @Test
    fun cancellingDisconnectsAStalledRequestRightAway() = runBlocking<Unit> {
        val conn = arrayOfNulls<StalledConnection>(1)
        val client = AiClient { url -> StalledConnection(url).also { conn[0] = it } }
        val pieces = java.util.Collections.synchronizedList(mutableListOf<String>())
        val call = async(Dispatchers.IO) { client.stream(config("slow"), "", listOf(AiMessage("user", "问")), onText = { pieces += it }) }
        withTimeout(10_000) { while (pieces.isEmpty()) kotlinx.coroutines.delay(20) }
        val at = System.nanoTime()
        call.cancel()
        // 读超时是 90 秒：一取消就断开连接的话，卡住的读马上返回。
        withTimeout(5_000) { runCatching { call.await() } }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - at) < 5)
        assertEquals(0L, conn[0]!!.disconnected.count)
        if (!call.isCancelled) fail("应当是被取消的")
    }
}
