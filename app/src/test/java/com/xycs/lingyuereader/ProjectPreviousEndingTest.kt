package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.reviewEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 复现“AI 连写几章、还没定稿时，每章都像重新起头”：项目包流程写第 2 章，第 1 章还是草稿。
 * 以前请求里一个字都不带第 1 章；现在带它现在正文的结尾，并标明未定稿、只接场面和语气。审读时它不能当 FACT 依据。
 * 假的服务只在本机，文本自造、人名虚构。
 */
class ProjectPreviousEndingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var first = ""
    private var second = ""
    private val asked = CopyOnWriteArrayList<Pair<String, String>>()
    private val pass = """{"summary":[],"missing":[],"issues":[]}"""

    /** 第 1 章（草稿）的结尾：最后一句是要在第 2 章的请求里看到的。 */
    private val ending = "沈默把船推离岸边，雾一下子漫上来。\n顾长风站在渡口茶馆门口，没有回头。\n林月追出来，喊：“你到底要去哪？”"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val list = (0 until messages.length()).map { messages.getJSONObject(it) }
                val system = list.filter { it.optString("role") == "system" }.joinToString("\n") { it.optString("content") }
                val user = list.last { it.optString("role") == "user" }.getString("content")
                asked += system to user
                val answer = when {
                    "【当前阶段：审读】" in user -> pass
                    "交给下一章接着写" in user -> """{"scene":"渡口","people":[],"mood":"","open":[],"hook":""}"""
                    else -> "沈默在北岸系好船，回头只看见一片雾。"
                }
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop"))).toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡口")
        workId = w.id
        first = w.chapters.single().id
        store.renameChapter(workId, first, "第一章 渡口")
        store.saveText(workId, first, "早上的事。\n$ending", count = false)
        second = store.addChapter(workId, w.volumes.single().id, "第二章 夜渡")!!.second.id
        store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲",
            "# 第一卷\n### 第一章 渡口\n事实：顾长风要走，林月追问。\n### 第二章 夜渡\n事实：沈默把船撑到北岸，林月没追上。\n视角：沈默\n")
        store.updateAssist(workId) { it.copy(draftPrep = false) }
        val client = AiClient { _ -> URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true).withKey("sk-test"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private fun writeSecond() {
        asked.clear()
        writing.writeDraft(workId, second, workflow = true)
        waitFor("写完并审读") { writing.drafts.value[workId to second]?.let { !it.loading && it.text.isNotBlank() } == true }
    }

    /** 写正文的请求：以任务卡收尾、不是审读、不是整理交接。 */
    private fun createRequest() = asked.first { (_, user) -> "【这次要写的】" in user && "【当前阶段：审读】" !in user }

    @Test
    fun projectDraftOfChapterTwoCarriesTheDraftEndingOfChapterOneMarkedUnfinalized() {
        assertTrue(store.get(workId)!!.chapter(first)!!.status != ChapterStatus.FINAL)
        writeSecond()
        val (_, user) = createRequest()
        assertTrue("请求里要有第 1 章的结尾", user.contains("林月追出来，喊：“你到底要去哪？”"))
        assertTrue("要标明未定稿", user.contains("上一章（未定稿"))
        // 审读能看到它，但标着不能当 FACT 依据；任务来源里没有它。
        val review = asked.first { (_, u) -> "【当前阶段：审读】" in u }
        assertTrue(review.first.contains("你到底要去哪"))
        assertTrue(review.second.contains("不能作 FACT 依据"))
        val task = runBlocking { writing.writingTask(workId, second) }!!
        val evidence = store.reviewEvidence(workId, second, task)
        assertFalse(evidence.locates(evidence.taskRef!!, "林月追出来，喊"))
        assertTrue(evidence.sources.none { source -> evidence.locates(source.ref, "林月追出来，喊") })
    }

    @Test
    fun anEmptyPreviousChapterStillCarriesNothing() {
        store.saveText(workId, first, "", count = false)
        writeSecond()
        val (_, user) = createRequest()
        assertFalse(user.contains("未定稿"))
        assertFalse(user.contains("上一章结尾"))
    }
}
