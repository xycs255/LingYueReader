package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.StoryDigest
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * “这章写完了”收尾时顺带整理前情（假的官方 DeepSeek V4 服务，文字自造、人名虚构）：写好摘要后另发一次小请求，
 * 不带温度、关思考、只要 JSON，记在“整理”类；整理好就存下故事至今和卷摘要；回的不是约定的 JSON 照常收尾，下次再补；
 * 作者改过的不盖掉。
 */
class DigestFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private val ids = mutableListOf<String>()

    private data class Asked(val user: String, val body: JSONObject) {
        val thinking: String? get() = body.optJSONObject("thinking")?.getString("type")
    }
    private val asked = CopyOnWriteArrayList<Asked>()

    @Volatile
    private var digestAnswer = """{"story":["林月到渡灯镇找铜灯。","沈默替她挡了一刀。","老周交出半本账册。"],"volume":"第一卷里林月查出了铜灯的记号。"}"""

    private fun isDigest(user: String) = "请整理这部小说的前情" in user
    private fun isSummary(user: String) = user.trimEnd().endsWith("只输出摘要本身。")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += Asked(user, body)
                val answer = when {
                    isDigest(user) -> digestAnswer
                    isSummary(user) -> "林月在旧仓找到第十个记号，沈默受了伤。"
                    else -> "好的。"
                }
                val reply = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                val bytes = reply.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        val volume = w.volumes.single().id
        ids += w.chapters.single().id
        repeat(9) { ids += store.addChapter(workId, volume)!!.second.id }
        ids.forEachIndexed { i, id ->
            store.renameChapter(workId, id, "第${i + 1}章 灯")
            store.saveText(workId, id, "林月在渡灯镇的第${i + 1}天，沈默还在。", count = false)
            // 最后一章是要收尾的：还没有摘要。
            if (i < ids.lastIndex) store.saveCard(workId, id, ChapterCard(summary = "第${i + 1}章：林月查到第${i + 1}个记号。"))
        }
        store.updateAssist(workId) { it.copy(finish = FinishOptions(sync = false, summary = true, review = false, learn = false, handoff = false)) }
        val client = AiClient { _ -> URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true).withKey("sk-test"))
        writing = WritingAi(store, ai, scope) {}
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private val last get() = ids.last()

    private fun finishToConfirm(): FinishRun {
        writing.openFinish(workId, last)
        waitFor("勾选项读完") { writing.finish.value?.options?.let { it.summary && !it.sync } == true }
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        return writing.finish.value!!
    }

    @Test
    fun finishingTidiesTheStoryAndTheVolumeQuietly() {
        val run = finishToConfirm()
        val digest = asked.single { isDigest(it.user) }
        // 不带温度，官方 V4 关思考；只要 JSON；只给章摘要，本章用刚写的摘要。
        assertFalse(digest.body.has("temperature"))
        assertEquals("disabled", digest.thinking)
        assertTrue(digest.user.contains("只输出 JSON"))
        assertTrue(digest.user.contains("林月在旧仓找到第十个记号"))
        assertTrue(digest.user.contains("第3章：林月查到第3个记号"))
        assertFalse(digest.user.contains("林月在渡灯镇的第3天"))
        // 一卷有了十章摘要：卷摘要一起要。
        assertTrue(digest.user.contains("\"volume\""))
        // 记在“整理”类（请求名“整理前情”）。
        assertEquals("整理前情", writing.lastRequest.value?.label)
        assertEquals(StepState.DONE, run.digest)
        val saved = store.digests(workId)
        assertEquals("林月到渡灯镇找铜灯。\n沈默替她挡了一刀。\n老周交出半本账册。", saved.story!!.text)
        assertEquals(last, saved.story!!.throughId)
        assertEquals(10, saved.story!!.throughNo)
        assertFalse(saved.story!!.edited)
        val volume = store.get(workId)!!.volumes.single().id
        assertEquals("第一卷里林月查出了铜灯的记号。", saved.volumes.getValue(volume).text)
        assertEquals(10, saved.volumes.getValue(volume).chapters.size)
    }

    @Test
    fun aBadAnswerDoesNotBlockFinishing() {
        digestAnswer = "好的，前情如下：林月到了渡灯镇。"
        val run = finishToConfirm()
        assertEquals(StepState.FAILED, run.digest)
        assertTrue(run.digestError!!.contains("前情没整理成"))
        assertTrue(run.digestError!!.contains("下次收尾再补"))
        assertTrue(store.digests(workId).isEmpty)
        // 照常完成收尾：摘要写进细纲卡、定稿。
        var done: String? = null
        writing.applyFinish({ w, c -> store.setStatus(w, c, ChapterStatus.FINAL) }) { done = it }
        waitFor("收尾完成") { done != null }
        assertEquals(ChapterStatus.FINAL, store.get(workId)!!.chapter(last)!!.status)
        assertTrue(store.card(workId, last).summary.isNotBlank())
    }

    @Test
    fun whatTheAuthorEditedIsNotOverwritten() {
        val volume = store.get(workId)!!.volumes.single().id
        store.updateDigests(workId) { it.copy(story = StoryDigest("我自己写的梗概。", ids[2], 3, edited = true)) }
        store.updateDigests(workId) { it.copy(volumes = mapOf(volume to com.xycs.lingyuereader.data.VolumeDigest("我写的卷摘要。", edited = true))) }
        val run = finishToConfirm()
        // 两样都是作者改过的：不发整理前情的请求，说一句留着了。
        assertTrue(asked.none { isDigest(it.user) })
        assertEquals(StepState.DONE, run.digest)
        assertTrue(run.digestNote!!.contains("你改过"))
        assertEquals("我自己写的梗概。", store.digests(workId).story!!.text)
        assertEquals("我写的卷摘要。", store.digests(workId).volumes.getValue(volume).text)
        // 只有卷摘要是作者改过的：故事至今照样整理，卷摘要不要，也不盖。
        store.updateDigests(workId) { it.copy(story = null) }
        writing.stopFinish()
        asked.clear()
        val again = finishToConfirm()
        val digest = asked.single { isDigest(it.user) }
        assertFalse(digest.user.contains("\"volume\""))
        assertEquals(StepState.DONE, again.digest)
        assertEquals("我写的卷摘要。", store.digests(workId).volumes.getValue(volume).text)
        assertTrue(store.digests(workId).story!!.text.startsWith("林月到渡灯镇"))
    }

    @Test
    fun withoutASummaryNothingIsTidied() {
        store.updateAssist(workId) { it.copy(finish = FinishOptions(sync = false, summary = false, review = false, learn = false, handoff = false)) }
        writing.openFinish(workId, last)
        waitFor("勾选项读完") { writing.finish.value?.options?.summary == false }
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        assertEquals(StepState.SKIPPED, writing.finish.value!!.digest)
        assertTrue(asked.isEmpty())
        assertNull(store.digests(workId).story)
    }
}
