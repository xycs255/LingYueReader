package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiUsageEvent
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.referenceAnalyses
import com.xycs.lingyuereader.data.references
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
import com.xycs.lingyuereader.data.writingTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local fake AI with synthetic novels. No external network or real reference text. */
class WritingCompanionAdviceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var assistant: AiAssistant
    private lateinit var service: WritingCompanionAdvice
    private lateinit var workId: String
    private lateinit var chapterId: String
    private lateinit var analysis: ReferenceAnalysis
    private val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val usage: MutableList<AiUsageEvent> = Collections.synchronizedList(mutableListOf())
    @Volatile private var release: CountDownLatch? = null
    @Volatile private var stream = false
    @Volatile private var answer = "范本观察：只见送信人先被门挡住，再借问灯试探对方。\n当前下一步：让岸边人物面对过河的代价，写出他怎样选择。\n对白提醒：若开口交涉，表层谈船，内里试探船主能否信任。"
    @Volatile private var liveText = "未保存标记：主角走到涨水的渡口。"

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                requests += JSONObject(exchange.requestBody.readBytes().decodeToString())
                if (stream) {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    runCatching {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { output ->
                            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"未完成的半句。\"}}]}\n\n".toByteArray())
                            output.flush()
                            release?.await(10, TimeUnit.SECONDS)
                            output.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                            output.flush()
                        }
                    }
                    return@createContext
                }
                release?.await(10, TimeUnit.SECONDS)
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("role", "assistant").put("content", answer))
                    .put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 200).put("completion_tokens", 100))
                    .toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                runCatching { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            }
            this.executor = this@WritingCompanionAdviceTest.executor
            start()
        }
        store = WorkStore(tmp.newFolder("companion"))
        val work = store.create("目标小说")
        workId = work.id
        chapterId = work.chapters.single().id
        store.saveText(workId, chapterId, "硬盘旧稿标记：主角仍在家里。", count = false)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(
            facts = "本章既定结果标记：主角付出代价后过河。", pov = "主角"))
        val book = store.importReference(workId, "合成范本.txt", """
            第一章 门外
            已选原文标记：送信人停在门前，手里捏着湿纸。
            “谁让你来的？”门里的人问。
            他把纸折起，只问门内有没有点灯。
            第二章 翌日
            严禁发送别章标记：第二天旧铺子关门。
        """.trimIndent())
        val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.first().id, ReferencePart.OPENING, maxChars = 70)!!
        val oldFingerprint = WritingReferenceAi.targetFingerprint(store.writingTask(workId, chapterId)!!, store.text(workId, chapterId))
        analysis = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId, oldFingerprint,
            "历史建议错误标记：旧目标稿要在家里写茶水，不适用于作者新稿。"))
        assistant = AiAssistant(scope, null, bookFile = { _, _ -> null })
        assistant.saveConfig(AiConfig(enabled = true, preset = AiPresets.CUSTOM,
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "test-model").withKey("test-key"))
        assistant.client.onUsage = { usage += it }
        service = WritingCompanionAdvice(store, assistant, scope) { }
    }

    @After fun tearDown() {
        release?.countDown()
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(10) }
    private suspend fun finished() = waitFor { service.state.value?.loading == false }
    private fun requestText() = requests.single().getJSONArray("messages").let { messages ->
        (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).getString("content") }
    }
    private fun ask(offset: Int = liveText.length) =
        service.ask(workId, chapterId, analysis.id, liveText, offset, currentManuscript = { liveText })

    @Test fun oldAnalysisOnlySelectsFreshSourceWhilePromptUsesUnsavedTextAndCurrentTask() = runBlocking {
        val stored = store.text(workId, chapterId)
        val pack = store.pack(workId)
        val history = store.referenceAnalyses(workId)
        ask()
        finished()
        val state = service.state.value!!
        assertNull(state.error)
        assertEquals(answer, state.text)
        assertFalse(state.stale)
        assertEquals(ReferencePart.OPENING, state.part)
        assertEquals(analysis.referenceTitle, state.sourceTitle)
        val prompt = requestText()
        assertTrue("已选原文标记" in prompt)
        assertFalse("严禁发送别章标记" in prompt)
        assertTrue("本章既定结果标记" in prompt)
        assertTrue("未保存标记" in prompt)
        assertFalse("硬盘旧稿标记" in prompt)
        assertFalse("历史建议错误标记" in prompt)
        assertTrue("只提供所选文字" in prompt)
        assertFalse(requests.single().has("tools"))
        assertEquals(stored, store.text(workId, chapterId))
        assertEquals(pack, store.pack(workId))
        assertEquals(history, store.referenceAnalyses(workId))
        assertTrue(store.ledger(workId).isEmpty())
        assertEquals(AiFeature.COMPANION, usage.single().meter!!.feature)
        waitFor { service.state.value?.usage != null }
        assertEquals(200L, service.state.value!!.usage!!.input)
        assertNotNull(state.request)
    }

    @Test fun unavailableAnalysisAndChangedSourceNeverFallBackToAnotherChapter() = runBlocking {
        service.ask(workId, chapterId, "missing", liveText, liveText.length, { liveText })
        finished()
        assertTrue(service.state.value!!.error!!.contains("不存在"))
        assertTrue(requests.isEmpty())

        // Replace the source file directly to simulate a stale import index: the store detects corruption.
        val ref = store.references(workId).single()
        val root = store.referenceRoot(workId)
        java.io.File(root, "${ref.id}/${ref.chapters.first().id}.txt").writeText("被替换的范本原文")
        ask()
        finished()
        assertNotNull(service.state.value!!.error)
        assertTrue(requests.isEmpty())
    }

    @Test fun editsDuringNetworkWaitDiscardTheOldSuggestion() = runBlocking {
        release = CountDownLatch(1)
        ask()
        waitFor { requests.size == 1 }
        liveText = "修改后的未保存正文。"
        release!!.countDown()
        finished()
        assertTrue(service.state.value!!.error!!.contains("变化"))
        assertTrue(service.state.value!!.text.isEmpty())
        assertEquals("硬盘旧稿标记：主角仍在家里。", store.text(workId, chapterId))
    }

    @Test fun movingCaretStopsAStreamingSuggestionAndCannotDisplayItsPartialText() = runBlocking {
        stream = true
        release = CountDownLatch(1)
        ask()
        waitFor { service.state.value?.text?.isNotEmpty() == true }
        service.markStale(workId, chapterId, liveText, 0)
        release!!.countDown()
        delay(150)
        assertTrue(service.state.value!!.stale)
        assertFalse(service.state.value!!.loading)
        assertEquals("", service.state.value!!.text)
    }

    @Test fun overlongAnswerIsRejectedAndRetryUsesTheLatestUnsavedSnapshot() = runBlocking {
        answer = "太长建议。".repeat(130)
        ask()
        finished()
        assertTrue(service.state.value!!.error!!.contains("过长"))
        assertEquals("", service.state.value!!.text)
        liveText = "第二轮新的正文标记。"
        answer = "范本观察：门内先盘问。\n当前下一步：明确渡河代价。\n对白提醒：借问船试探可信度。"
        service.retry(liveText, 3) { liveText }
        finished()
        assertNull(service.state.value!!.error)
        assertEquals(3, service.state.value!!.cursorOffset)
        assertTrue(service.state.value!!.text.contains("渡河代价"))
        assertEquals(2, requests.size)
    }
}
