package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.data.LargePack
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 审读、修订请求的大小：用一百二十万字上下的虚构设定包（[LargePack]）。真机上作者的设定包很大，审读一次发了 34 万字，
 * 超过 DeepSeek 的上下文。审读照写正文的瘦身办法：本章任务（含本章资料、账本快照）只放一份，其余资料用工具查。
 */
class ReviewRequestSizeTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Sent(val phase: String, val system: String, val user: String) {
        val size: Int get() = system.length + user.length
    }

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val reviews = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private lateinit var workId: String
    private lateinit var chapterId: String
    private val manuscript by lazy { LargePack.res("ch3_beats.txt").replace("=====", "").trim() }

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val list = body.getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val system = list.filter { it.optString("role") == "system" }.joinToString("\n") { it.optString("content") }
                val user = list.last { it.optString("role") == "user" }.getString("content")
                val phase = when {
                    "【当前阶段：审读】" in user -> "REVIEW"
                    "这一章现在的稿子：" in user -> "REVISE"
                    else -> "OTHER"
                }
                sent += Sent(phase, system, user)
                val answer = when (phase) {
                    // 第一轮审读给一条有出处的必改意见（引本章任务的细纲卡原句），好让流程走到修订；复审通过。
                    "REVIEW" -> if (reviews.incrementAndGet() == 1) {
                        val ref = Regex("本章任务来源编号是 (T_[0-9a-f]+)").find(user)?.groupValues?.get(1).orEmpty()
                        JSONObject().put("summary", JSONArray()).put("missing", JSONArray()).put("issues", JSONArray().put(JSONObject()
                            .put("kind", "FACT").put("quote", manuscript.lines().first { it.isNotBlank() }.take(12))
                            .put("problem", "约见的时辰没写清").put("basis", "细纲卡写明卯时在渡口船上见")
                            .put("source_ref", ref).put("source_quote", "杜娘转述老周约林月卯时渡口船上见")
                            .put("fix", "补上卯时").put("required", true))).toString()
                    } else """{"summary":[],"missing":[],"issues":[]}"""
                    "REVISE" -> manuscript + "\n卯时的雾还没散。"
                    else -> "测试收到意外请求"
                }
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                    .toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            this.executor = this@ReviewRequestSizeTest.executor
            start()
        }
        val port = server.address.port
        val client = AiClient { url -> URL("http://127.0.0.1:$port${url.path}").openConnection() as HttpURLConnection }
        val ai = AiAssistant(CoroutineScope(SupervisorJob() + Dispatchers.Default), null, client, bookFile = { _, _ -> null })
        // DeepSeek：应用按 128K token 规划上下文。
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))
        store = WorkStore(tmp.newFolder("writing"))
        LargePack.build(store).let { (w, c) -> workId = w; chapterId = c }
        writing = WritingAi(store, ai, scope) { }
    }

    @After fun tearDown() {
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 120_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    @Test fun reviewAndRevisionStayLeanWithAMillionCharacterPack() {
        val total = LargePack.totalChars(store, workId)
        assertTrue("设定包 $total 字", total in 1_000_000..1_500_000)
        store.saveDraft(workId, chapterId, manuscript)
        store.setDraftWorkflow(workId, chapterId, true)
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        writing.reviewDraft(workId, chapterId, targetLength = "3000—4000字")
        Thread.sleep(100)
        waitFor("审读完") { writing.drafts.value[workId to chapterId]?.loading == false }
        val d = writing.drafts.value[workId to chapterId]!!
        assertEquals(null, d.error)
        val review = sent.filter { it.phase == "REVIEW" }
        val revise = sent.filter { it.phase == "REVISE" }
        assertEquals("审读两轮（修订后复审）", 2, review.size)
        assertEquals(1, revise.size)
        println("设定包 $total 字：审读请求 ${review.map { it.size }} 字（系统提示 ${review.map { it.system.length }}），" +
            "修订请求 ${revise.map { it.size }} 字（系统提示 ${revise.map { it.system.length }}）")
        // 改前审读一次约 26 万字（系统提示里是整份知识库和上百份文档的小节目录）；改后两万字以内。
        review.forEach { assertTrue("审读请求 ${it.size} 字", it.size <= 20_000) }
        revise.forEach { assertTrue("修订请求 ${it.size} 字", it.size <= 20_000) }
        // 判断标准和出处要求都还在：本章任务、来源编号、本章正文；其余资料用工具查，清单里带来源编号。
        val first = review.first()
        assertTrue(first.system.contains("【本次章节任务】"))
        assertTrue(first.user.contains("source_ref"))
        assertTrue(first.user.contains(manuscript.take(40)))
        assertTrue(first.system.contains("read_doc"))
        assertTrue("能查的资料清单", first.system.contains("《世界设定》"))
        assertTrue("不再整份放知识库", !first.system.contains("=== 知识库（整份） ==="))
        // 伏笔表几百条：只列 30 条，其余说一句有多少条（写正文那份资料同样）。
        assertTrue(Regex("另有 \\d+ 条埋下还没收的伏笔没列出").containsMatchIn(first.system))
        assertTrue(Regex("另有 \\d+ 条埋下还没收的伏笔没列出").containsMatchIn(revise.single().system))
    }

    @Test fun aRequestOverTheModelContextIsStoppedBeforeSending() {
        // 稿子本身就超过 DeepSeek 的 128K：发之前拦下，不白发（服务商会退回来，可能照样计费）。
        val huge = "林月在渡口等老周的船，雾一直没散。".repeat(15_000)
        store.saveDraft(workId, chapterId, huge)
        store.setDraftWorkflow(workId, chapterId, true)
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        writing.reviewDraft(workId, chapterId, targetLength = "3000—4000字")
        Thread.sleep(100)
        waitFor("拦下") { writing.drafts.value[workId to chapterId]?.loading == false }
        val d = writing.drafts.value[workId to chapterId]!!
        // 已有上一版（就是这一稿），流程照旧把它留着，原因写在提示里。
        val why = listOfNotNull(d.error, d.notice).joinToString("；")
        assertTrue(why, why.contains("资料太多，超过模型上限") && why.contains("没有发送"))
        assertEquals(huge, d.text)
        assertTrue("一个请求都没发", sent.isEmpty())
        // 看发给 AI 的内容：仍能看到这次要发什么、有多大。
        assertEquals("本稿审读", writing.lastRequest.value?.label)
        assertTrue(writing.lastRequest.value!!.size > huge.length)
    }
}
