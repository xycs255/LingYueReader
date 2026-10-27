package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.referenceAnalyses
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

/** A synthetic source and local fake model keep the graphic tests independent of real novels. */
class WritingMethodMapTest {
    @get:Rule val tmp = TemporaryFolder()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var service: WritingMethodMap
    private lateinit var workId: String
    private lateinit var chapterId: String
    private lateinit var analysis: ReferenceAnalysis
    private val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    @Volatile private var release: CountDownLatch? = null
    @Volatile private var answer = validAnswer()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                requests += JSONObject(exchange.requestBody.readBytes().decodeToString())
                release?.await(10, TimeUnit.SECONDS)
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("role", "assistant").put("content", answer))
                    .put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 90).put("completion_tokens", 40))
                    .toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                runCatching { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            }
            this.executor = this@WritingMethodMapTest.executor
            start()
        }
        store = WorkStore(tmp.newFolder("method-map"))
        val work = store.create("自创故事")
        workId = work.id
        chapterId = work.chapters.single().id
        store.saveText(workId, chapterId, "本书原稿：主角仍在渡口。", count = false)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(facts = "本章人要过河。"))
        val book = store.importReference(workId, "合成范本.txt", """
            第一章 门外
            旅人停在门前，雨水从帽檐落下。
            “你带来了什么？”门卫问。
            “先看这封信。”旅人答。
            门卫放下闩，门开了一道缝。
            第二章 翌日
            另一章禁入标记：日出以后两人离开。
        """.trimIndent())
        val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.first().id, ReferencePart.OPENING, maxChars = 200)!!
        analysis = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId, "old-target", "旧 Markdown 分析不供图使用"))
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.CUSTOM,
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "test-model").withKey("test-key"))
        service = WritingMethodMap(store, ai, scope) { }
    }

    @After fun tearDown() {
        release?.countDown()
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun finished() = withTimeout(15_000) { while (service.state.value?.loading != false) delay(10) }
    private suspend fun requestArrived() = withTimeout(15_000) { while (requests.isEmpty()) delay(10) }

    @Test fun onlyExplicitGenerationReturnsVerifiedSourcePositionsAndLeavesManuscriptUntouched() = runBlocking {
        assertTrue(requests.isEmpty())
        val before = store.text(workId, chapterId)
        val history = store.referenceAnalyses(workId, chapterId)
        service.generate(workId, chapterId, analysis.id)
        finished()
        val s = service.state.value!!
        assertNull(s.error)
        assertEquals(3, s.nodes.size)
        assertEquals("先发言者", s.dialogue?.first?.speaker)
        assertEquals("后发言者", s.dialogue?.second?.speaker)
        assertNotNull(s.dialogue)
        assertTrue(s.chapterLength >= s.sourceEnd)
        val source = store.selectedChapterExcerpt(workId, analysis.referenceId, analysis.chapterId,
            analysis.part, analysis.end - analysis.start, analysis.start, analysis.end)!!
        s.nodes.forEach { node ->
            assertEquals(node.quote, source.text.substring(node.start - source.start, node.end - source.start))
        }
        assertTrue(s.nodes.zipWithNext().all { (a, b) -> a.end <= b.start })
        val sent = requests.single().getJSONArray("messages").toString()
        assertTrue("旅人停在门前" in sent)
        assertFalse("另一章禁入标记" in sent)
        assertFalse("旧 Markdown 分析不供图使用" in sent)
        assertFalse(requests.single().has("tools"))
        assertEquals(before, store.text(workId, chapterId))
        assertEquals(history, store.referenceAnalyses(workId, chapterId))
        assertTrue(service.current(workId, chapterId, analysis.id))
        assertNotNull(s.request)
        assertEquals(90L, service.state.value!!.usage?.input)
    }

    @Test fun compliantQuotesAreNotRejectedByTheAnalysisQuotationTotal() = runBlocking {
        val narrative = listOf(
            "林月站在渡口的石阶上，看着河水一点点漫过最下面那一级台阶，远处亮着船灯。",
            "老周把船撑到岸边，竹篙在水里点了三下，船身稳稳地停在石阶旁边不再晃动。",
            "沈默背着包袱从堤上跑下来，鞋上全是泥，喘着气把一封信塞进林月的手心里。",
            "林月没有拆信，只是把它压在掌心，抬头看了一眼对岸那排已经熄灭的窗户。",
            "老周不再多问，解开缆绳，船慢慢离开石阶，林月回头时沈默已经走回堤上去了。",
        )
        val first = "今晚风大，船只能过一趟，你们谁先走谁留下，现在就定下来吧。"
        val second = "让她先走，信里写的事情只有她能办，我留在渡口等下一趟船过来。"
        val text = listOf(narrative[0], narrative[1], narrative[2], "“$first”老周说。", "“$second”沈默答。", narrative[3], narrative[4])
        val book = store.importReference(workId, "渡口范本.txt", "第一章 渡口\n" + text.joinToString("\n"))
        val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.single().id, ReferencePart.OPENING, maxChars = 2_000)!!
        val long = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId, "old-target", "渡口一章的分析"))
        // 四个节点和三处对白引文都是提示词允许的 24 字，合计 168 字，超过分析的短引合计上限也照样可用。
        answer = JSONObject().put("nodes", JSONArray().apply {
            listOf(0, 1, 2, 3).forEach { i -> put(JSONObject().put("kind", "动作").put("label", "节点$i").put("quote", narrative[i].take(24))) }
        }).put("dialogue", JSONObject().put("topic", "谁先过河")
            .put("first", JSONObject().put("speaker", "老周").put("quote", first.take(24)).put("intent", "逼两人做决定"))
            .put("second", JSONObject().put("speaker", "沈默").put("quote", second.take(24)).put("intent", "把机会让给林月"))
            .put("outcome", "林月独自上船").put("outcomeQuote", narrative[4].take(24))).toString()
        service.generate(workId, chapterId, long.id)
        finished()
        val s = service.state.value!!
        assertNull(s.error)
        assertEquals(4, s.nodes.size)
        assertNotNull(s.dialogue)
    }

    @Test fun invalidQuotesAndAbsentRealSpeechAreOmittedInsteadOfDrawn() = runBlocking {
        answer = JSONObject().put("nodes", JSONArray()
            .put(JSONObject().put("kind", "动作").put("label", "真节点").put("quote", "旅人停在门前"))
            .put(JSONObject().put("kind", "转折").put("label", "伪造节点").put("quote", "根本不存在的证据")))
            .put("dialogue", JSONObject().put("topic", "信件")
                .put("first", JSONObject().put("speaker", "甲").put("quote", "旅人停在门前").put("intent", "试探"))
                .put("second", JSONObject().put("speaker", "乙").put("quote", "先看这封信。").put("intent", "争取进入"))
                .put("outcome", "门开").put("outcomeQuote", "门开了一道缝"))
            .toString()
        service.generate(workId, chapterId, analysis.id)
        finished()
        val s = service.state.value!!
        assertNull(s.error)
        assertEquals(listOf("真节点"), s.nodes.map { it.label })
        assertNull(s.dialogue)
    }

    @Test fun fragmentsFromOneUtteranceDoNotPretendToBeTwoSpeakers() = runBlocking {
        answer = JSONObject().put("nodes", JSONArray())
            .put("dialogue", JSONObject().put("topic", "通行")
                .put("first", JSONObject().put("quote", "你带来了").put("intent", "发问"))
                .put("second", JSONObject().put("quote", "什么？").put("intent", "追问"))
                .put("outcome", "门开").put("outcomeQuote", "门开了一道缝"))
            .toString()
        service.generate(workId, chapterId, analysis.id)
        finished()
        assertNull(service.state.value!!.error)
        assertTrue(service.state.value!!.nodes.isEmpty())
        assertNull(service.state.value!!.dialogue)
    }

    @Test fun missingOrChangedSourceNeverSendsAnotherChapterAndChangedTaskInvalidatesGraphic() = runBlocking {
        service.generate(workId, chapterId, "missing")
        finished()
        assertTrue(service.state.value!!.error!!.contains("不存在"))
        assertTrue(requests.isEmpty())

        service.generate(workId, chapterId, analysis.id)
        finished()
        assertNull(service.state.value!!.error)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(facts = "本章改成留在岸边。"))
        assertFalse(service.current(workId, chapterId, analysis.id))
        assertTrue(service.state.value!!.stale)
        assertTrue(service.state.value!!.nodes.isEmpty())

        val root = store.referenceRoot(workId)
        java.io.File(root, "${analysis.referenceId}/${analysis.chapterId}.txt").writeText("被替换的范本原文")
        service.generate(workId, chapterId, analysis.id)
        finished()
        assertNotNull(service.state.value!!.error)
        assertEquals(1, requests.size)
    }

    @Test fun sourceOrTaskChangedWhileModelRespondsDiscardsItsGraphic() = runBlocking {
        release = CountDownLatch(1)
        service.generate(workId, chapterId, analysis.id)
        requestArrived()
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(facts = "本章结果又改了。"))
        release!!.countDown()
        finished()
        assertTrue(service.state.value!!.error!!.contains("变化"))
        assertTrue(service.state.value!!.nodes.isEmpty())
        assertNull(service.state.value!!.dialogue)
    }

    @Test fun stoppingAnInflightRequestCannotTurnIntoACompletedGraphic() = runBlocking {
        release = CountDownLatch(1)
        service.generate(workId, chapterId, analysis.id)
        requestArrived()
        service.stop()
        release!!.countDown()
        assertFalse(service.current(workId, chapterId, analysis.id))
        val s = service.state.value!!
        assertFalse(s.loading)
        assertNotNull(s.error)
        assertTrue(s.nodes.isEmpty())
        assertNull(s.dialogue)
    }

    private companion object {
        fun validAnswer(): String = JSONObject().put("nodes", JSONArray()
            .put(JSONObject().put("kind", "动作").put("label", "先让人受阻").put("quote", "旅人停在门前"))
            .put(JSONObject().put("kind", "对白").put("label", "问来意").put("quote", "你带来了什么？"))
            .put(JSONObject().put("kind", "转折").put("label", "门略开").put("quote", "门开了一道缝")))
            .put("dialogue", JSONObject().put("topic", "信件")
                .put("first", JSONObject().put("speaker", "门卫").put("quote", "你带来了什么？").put("intent", "辨认来意"))
                .put("second", JSONObject().put("speaker", "旅人").put("quote", "先看这封信。").put("intent", "争取进门"))
                .put("outcome", "门留了一条缝").put("outcomeQuote", "门开了一道缝"))
            .toString()
    }
}
