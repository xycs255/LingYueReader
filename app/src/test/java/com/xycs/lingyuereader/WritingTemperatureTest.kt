package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 作品设的“写正文的温度”只出现在写正文、改正文的请求里；挑错、灵感、拟细纲、摘要都不带。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class WritingTemperatureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val writingDir get() = File(app.filesDir, "writing")
    private val store get() = WorkStore(writingDir)
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val models = mutableListOf<AppViewModel>()
    private lateinit var workId: String
    private lateinit var chapterId: String
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他。"

    /** 收到的请求：用户那句话，和请求体里的温度（没有为空）。 */
    private data class Asked(val user: String, val temperature: Double?)
    private val asked = CopyOnWriteArrayList<Asked>()

    @Before
    fun setUp() {
        File(app.filesDir, "books").deleteRecursively()
        File(app.filesDir, "books").mkdirs()
        File(app.filesDir, "books/index.json").writeText("[]")
        writingDir.deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).edit().clear().commit()
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += Asked(user, if (body.has("temperature")) body.getDouble("temperature") else null)
                val answer = when {
                    "细纲卡" in user && "JSON" in user -> """{"facts":"沈默到渡口"}"""
                    else -> "林月在船头等着。"
                }
                val bytes = JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop"))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            this.executor = this@WritingTemperatureTest.executor
            start()
        }
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
            .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "synthetic-model").commit()
        val work = store.create("渡口")
        workId = work.id
        chapterId = work.chapters.single().id
        store.saveText(workId, chapterId, text, count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "沈默到渡口，老周已经走了"))
        store.updateAssist(workId) { it.copy(temperature = 1.1f) }
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, condition())
    }

    private fun model(): AppViewModel = AppViewModel(app).also { vm ->
        models += vm
        waitUntil("写作数据尚未载入") { vm.writing.loaded.value }
    }

    /** 跑一件事，等它发出的请求（只发一次）回来，返回请求里的温度。 */
    private fun temperatureOf(what: String, start: () -> Unit, done: () -> Boolean): Double? {
        asked.clear()
        start()
        waitUntil("$what 没有做完") { asked.isNotEmpty() && done() }
        assertEquals("$what 只发一次请求", 1, asked.size)
        return asked.single().temperature
    }

    @Test
    fun onlyWritingAndRewritingRequestsCarryTheTemperature() {
        val vm = model()
        val ai = vm.writingAi
        fun runTask(task: WriteAiTask) = temperatureOf(task.title, { ai.run(workId, chapterId, text, task) }) { ai.state.value?.loading == false }

        // 编辑器里接着写、写本章、润色扩写续写这类改正文：带温度。
        assertEquals(1.1, temperatureOf("接着写", { ai.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE) }) {
            ai.inline.value?.loading == false
        })
        ai.discardInline()
        assertEquals(1.1, temperatureOf("写本章", { ai.writeDraft(workId, chapterId) }) { ai.drafts.value[workId to chapterId]?.loading == false })
        listOf(WriteAction.POLISH, WriteAction.VIVID, WriteAction.EXPAND, WriteAction.CONDENSE, WriteAction.CUSTOM).forEach { action ->
            assertEquals(action.label, 1.1, runTask(WriteAiTask.Edit(action, "杜娘抬头看他。", 10, 17, instruction = "多写神态")))
        }
        assertEquals(1.1, runTask(WriteAiTask.Edit(WriteAction.CONTINUE, "", text.length, text.length)))

        // 挑错（只改错，求稳）、灵感、读者点评、拟细纲、摘要：不带。
        assertEquals(null, runTask(WriteAiTask.Edit(WriteAction.PROOFREAD, "杜娘抬头看他。", 10, 17)))
        assertEquals(null, runTask(WriteAiTask.Idea(IdeaKind.NEXT)))
        assertEquals(null, runTask(WriteAiTask.Review))
        var planned = false
        assertEquals(null, temperatureOf("拟细纲", { runBlocking { runCatching { ai.planCard(workId, chapterId) }; planned = true } }) { planned })
        var summarized = false
        assertEquals(null, temperatureOf("章节摘要", { runBlocking { ai.summarize(workId, chapterId) }; summarized = true }) { summarized })
    }

    @Test
    fun theProviderDefaultSendsNoTemperatureAnywhere() {
        store.updateAssist(workId) { it.copy(temperature = null) }
        val vm = model()
        val ai = vm.writingAi
        assertEquals(null, temperatureOf("接着写", { ai.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE) }) {
            ai.inline.value?.loading == false
        })
        assertEquals(null, temperatureOf("润色", { ai.run(workId, chapterId, text, WriteAiTask.Edit(WriteAction.POLISH, "杜娘抬头看他。", 10, 17)) }) {
            ai.state.value?.loading == false
        })
    }
}
