package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiUsageEvent
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.WritingReference
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.referenceAnalyses
import com.xycs.lingyuereader.data.referenceText
import com.xycs.lingyuereader.data.selectedChapterExcerpt
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

/** 本机假AI验证真实请求、范围与结果边界；所有范本文字均为合成测试材料。 */
class WritingReferenceAiTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var store: WorkStore
    private lateinit var assistant: AiAssistant
    private lateinit var service: WritingReferenceAi
    private lateinit var workId: String
    private lateinit var chapterId: String
    private lateinit var reference: WritingReference
    private val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val usage: MutableList<AiUsageEvent> = Collections.synchronizedList(mutableListOf())
    @Volatile private var blockFirst: CountDownLatch? = null
    @Volatile private var streamFirst = false
    @Volatile private var finishReason = "stop"
    @Volatile private var answer = """
        ## 入场与开头手法
        从角色受到阻碍的动作入场，先建立眼前问题。
        ## 所选片段的叙事结构
        所见行动先受阻，再以新消息改变局面，未提供部分不作判断。
        ## 对白深度
        这段对白表面询问消息，实际试探对方能否信任；判断只限所给证据。
        ## 当前章具体怎么写
        围绕作者已经确定的过河结果，先让选择产生可见代价，再以行动承接。
        ## 可借鉴与不宜照搬
        可以借鉴信息延迟，不照搬范本人物、原句和事件。
    """.trimIndent()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().decodeToString())
                requests += request
                val number = requests.size
                if (number == 1 && streamFirst) {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    runCatching {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { output ->
                            output.write("data: {\"choices\":[{\"delta\":{\"content\":\"尚未完成的半篇分析。\"}}]}\n\n".toByteArray())
                            output.flush()
                            blockFirst?.await(10, TimeUnit.SECONDS)
                            output.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                            output.flush()
                        }
                    }
                    return@createContext
                }
                if (number == 1) blockFirst?.await(10, TimeUnit.SECONDS)
                val message = JSONObject().put("role", "assistant").put("content", answer)
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finishReason)))
                    .put("usage", JSONObject().put("prompt_tokens", 200).put("completion_tokens", 100))
                    .toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                runCatching { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            }
            this.executor = this@WritingReferenceAiTest.executor
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val work = store.create("合成目标")
        workId = work.id
        chapterId = work.chapters.single().id
        store.saveText(workId, chapterId, "目标稿原文：主角在岸边等船。", count = false)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(facts = "本章既定结果：主角付出代价后过河。", pov = "主角"))
        store.addPackDoc(workId, PackKind.CANON, "目标设定", "河水涨起，渡口只剩一艘小船。")
        reference = store.importReference(workId, "合成范本.txt", """
            第一章 门外
            范本开头标记：送信人停在门前，手里捏着一张湿纸。
            “谁让你来的？”门里的人问。
            他把纸折起，只问门内有没有点灯。
            第二章 翌日
            别章不发送标记：第二天，旧铺子已经关门。
        """.trimIndent())
        assistant = AiAssistant(scope, null, bookFile = { _, _ -> null })
        assistant.saveConfig(AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "test-model").withKey("test-key"))
        assistant.client.onUsage = { usage += it }
        service = WritingReferenceAi(store, assistant, scope) { }
    }

    @After fun tearDown() {
        blockFirst?.countDown()
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun waitFor(done: () -> Boolean) = withTimeout(15_000) {
        while (!done()) delay(10)
    }

    private suspend fun finished() {
        waitFor { service.state.value?.loading == false }
    }

    private fun requestText(request: JSONObject) = request.getJSONArray("messages").let { messages ->
        (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).getString("content") }
    }

    @Test fun analysisSendsOnlyTheSelectedChapterAndSavesAdviceWithoutChangingStoryData() = runBlocking {
        val original = store.text(workId, chapterId)
        val pack = store.pack(workId)
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        finished()
        val state = service.state.value!!
        assertNull(state.error)
        assertNotNull(state.analysis)
        val request = requests.single()
        val prompt = requestText(request)
        assertTrue("范本开头标记" in prompt)
        assertFalse("别章不发送标记" in prompt)
        assertTrue("本章既定结果" in prompt)
        assertTrue("目标稿原文" in prompt)
        assertTrue("潜台词" in prompt && "没有对白就明确说未出现/证据不足" in prompt)
        assertFalse(request.has("tools"))
        assertFalse(state.analysis!!.applied)
        assertEquals(original, store.text(workId, chapterId))
        assertEquals(pack, store.pack(workId))
        assertTrue(store.ledger(workId).isEmpty())
        val saved = store.referenceAnalyses(workId, chapterId).single()
        assertEquals(answer, saved.body)
        val excerpt = store.selectedChapterExcerpt(workId, reference.id, reference.chapters.first().id)!!
        assertEquals(excerpt.fingerprint, saved.sourceFingerprint)
        assertEquals(AiFeature.REFERENCE, usage.single().meter!!.feature)
        assertEquals(workId, usage.single().meter!!.workId)
        waitFor { service.state.value?.usage != null }
        assertEquals(200L, service.state.value!!.usage!!.input)
        assertEquals(1, store.aiLog(workId).count { it.kind == "范本分析" })
        assertEquals("范本分析", service.lastRequest.value!!.label)
    }

    @Test fun chapterScopeIncludesTheWholeChapterWhileOpeningStaysExplicitlyPartial() = runBlocking {
        val body = "开头真实标记。\n" + "这是合成的场景推进材料，送信人等待门内回应。\n".repeat(180) + "章末真实标记：门开了，收信人却不是原先约好的人。"
        val book = store.importReference(workId, "长范本.txt", "第一章 门外\n$body\n第二章 清晨\n不应发送的后文。")
        val id = book.chapters.first().id
        service.analyze(workId, chapterId, book.id, id, ReferencePart.CHAPTER)
        finished()
        assertNull(service.state.value!!.error)
        assertTrue("章末真实标记" in requestText(requests.single()))
        assertTrue("## 整章叙事结构" in requestText(requests.single()))
        assertEquals(ReferencePart.CHAPTER, store.referenceAnalyses(workId, chapterId).single().part)
        service.analyze(workId, chapterId, book.id, id, ReferencePart.OPENING, maxChars = 240)
        finished()
        val opening = requestText(requests.last())
        assertTrue("开头真实标记" in opening)
        assertFalse("章末真实标记" in opening)
        assertTrue("## 所选片段的叙事结构" in opening)
        assertFalse("## 整章叙事结构" in opening)
    }

    @Test fun endingAndExplicitRangesNeverReadTheOtherParts() = runBlocking {
        val source = store.referenceText(workId, reference.id, reference.chapters.first().id)!!
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.ENDING, maxChars = 10)
        finished()
        var prompt = requestText(requests.last())
        assertTrue(source.takeLast(10) in prompt)
        assertFalse("范本开头标记" in prompt)
        assertTrue("未提供章首时明确无法判断真实开头" in prompt)
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.RANGE, start = 0, end = 9)
        finished()
        prompt = requestText(requests.last())
        assertTrue(source.take(9) in prompt)
        assertFalse("谁让你来的" in prompt)
        assertEquals(9, service.state.value!!.analysis!!.end)
    }

    @Test fun missingIdsAndOversizedWholeChaptersNeverMakeANetworkRequest() = runBlocking {
        service.analyze(workId, chapterId, reference.id, "missing", ReferencePart.OPENING)
        finished()
        assertNotNull(service.state.value!!.error)
        val book = store.importReference(workId, "过长范本.txt", "第一章 长夜\n" + "合成正文用于验证完整章的长度上限。".repeat(900))
        service.analyze(workId, chapterId, book.id, book.chapters.single().id, ReferencePart.CHAPTER)
        finished()
        assertTrue(service.state.value!!.error!!.contains("超过"))
        assertTrue(requests.isEmpty())
        assertTrue(store.referenceAnalyses(workId).isEmpty())
    }

    @Test fun invalidRangeAndDisabledAiDoNotFallBackToAnyChapter() = runBlocking {
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.RANGE, start = 1000, end = 1001)
        finished()
        assertNotNull(service.state.value!!.error)
        assistant.saveConfig(assistant.currentConfig.copy(enabled = false))
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        assertFalse(service.state.value!!.loading)
        assertNotNull(service.state.value!!.error)
        assertTrue(requests.isEmpty())
    }

    @Test fun aTruncatedOrOverquotedAnswerIsNeverSavedAsAnAnalysis() = runBlocking {
        finishReason = "length"
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        finished()
        assertNotNull(service.state.value!!.error)
        assertTrue(store.referenceAnalyses(workId).isEmpty())
        finishReason = "stop"
        answer = store.referenceText(workId, reference.id, reference.chapters.first().id)!!
        service.retry()
        finished()
        assertTrue(service.state.value!!.error!!.contains("引用范本过长"))
        assertTrue(store.referenceAnalyses(workId).isEmpty())
        assertEquals("目标稿原文：主角在岸边等船。", store.text(workId, chapterId))
    }

    @Test fun stoppingAnInFlightAnalysisDoesNotSaveThePartialResponse() = runBlocking {
        blockFirst = CountDownLatch(1)
        streamFirst = true
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        waitFor { service.state.value?.text?.isNotBlank() == true }
        service.stop()
        blockFirst!!.countDown()
        delay(150)
        assertFalse(service.state.value!!.loading)
        assertTrue(service.state.value!!.text.isEmpty())
        assertNull(service.state.value!!.analysis)
        assertTrue(store.referenceAnalyses(workId).isEmpty())
    }

    @Test fun anOldResponseCannotReplaceANewerSelection() = runBlocking {
        blockFirst = CountDownLatch(1)
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        waitFor { requests.size == 1 }
        service.analyze(workId, chapterId, reference.id, reference.chapters.last().id, ReferencePart.CHAPTER)
        finished()
        assertNull(service.state.value!!.error)
        val savedId = service.state.value!!.analysis!!.id
        blockFirst!!.countDown()
        delay(150)
        assertEquals(reference.chapters.last().id, service.state.value!!.referenceChapterId)
        assertEquals(savedId, service.state.value!!.analysis!!.id)
        assertEquals(listOf(savedId), store.referenceAnalyses(workId).map { it.id })
    }

    @Test fun editsDuringAnalysisInvalidateTheOldAdviceWithoutChangingTheNewText() = runBlocking {
        blockFirst = CountDownLatch(1)
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        waitFor { requests.size == 1 }
        store.saveText(workId, chapterId, "作者已经改写的新正文。", count = false)
        blockFirst!!.countDown()
        finished()
        assertTrue(service.state.value!!.error!!.contains("变化"))
        assertTrue(store.referenceAnalyses(workId).isEmpty())
        assertEquals("作者已经改写的新正文。", store.text(workId, chapterId))
    }

    @Test fun loadingHistoryAndReturningToAnActiveTaskNeverSendExtraRequests() = runBlocking {
        service.load(workId, chapterId)
        delay(100)
        assertTrue(requests.isEmpty())
        blockFirst = CountDownLatch(1)
        service.analyze(workId, chapterId, reference.id, reference.chapters.first().id, ReferencePart.OPENING)
        waitFor { requests.size == 1 }
        service.load(workId, chapterId)
        assertTrue(service.state.value!!.loading)
        blockFirst!!.countDown()
        finished()
        val result = service.state.value!!.analysis!!
        val reader = WritingReferenceAi(store, assistant, scope) { }
        reader.load(workId, chapterId)
        waitFor { reader.state.value?.analysis != null }
        assertEquals(result.id, reader.state.value!!.analysis!!.id)
        assertEquals(answer, reader.state.value!!.text)
        assertNull(reader.state.value!!.request)
        assertEquals(1, requests.size)
    }

    @Test fun multipleShortQuotesCannotEvadeTheTotalQuotationBudget() {
        val parts = (1..20).map { "第${it}句合成例文用于验证短引总量的约束。" }
        val source = parts.joinToString("\n")
        val output = parts.joinToString("\n") { "证据：“$it”。这句话只用于举例。" }
        val error = runCatching { ReferenceAnalysisPrompts.checkShortQuotes(output, source) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("短引总量"))
    }
}
