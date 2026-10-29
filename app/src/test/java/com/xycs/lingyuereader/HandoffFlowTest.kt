package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterHandoff
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.HandoffOrigin
import com.xycs.lingyuereader.data.Handoffs
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 交接卡的整条路（假的官方 DeepSeek V4 服务，文本自造、人名虚构）：写下一章前按需请 AI 整理（不带温度、关思考、只要 JSON、
 * 只给最后约 3000 字和钩子），写本章开头的请求带“上一章交接”和任务卡那一句，接着写、写到中间的不带；JSON 不对如实报错、照常写；
 * 正文改过算过期、重新整理；作者改过的不被盖掉，点“重新整理”才重来；收尾时整理一次。
 */
class HandoffFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var first = ""
    private var second = ""
    private val messages = CopyOnWriteArrayList<String>()

    private data class Asked(val user: String, val body: JSONObject) {
        val thinking: String? get() = body.optJSONObject("thinking")?.getString("type")
    }
    private val asked = CopyOnWriteArrayList<Asked>()

    private val ending = "沈默把船推离岸边，雾一下子漫上来。\n顾长风站在渡口茶馆门口，没有回头。\n林月追出来，喊：“你到底要去哪？”"
    private val early = "很早以前，老周在河上撑船。"

    @Volatile
    private var handoffAnswer = """{"scene":"渡口茶馆门口，天快黑了","people":[{"name":"林月","doing":"追到门口","state":"又急又气"},
        {"name":"顾长风","doing":"背对着她","state":"不肯说"}],"mood":"憋着火","open":["顾长风要去哪"],"hook":"林月追问去向"}"""

    @Volatile
    private var prose = "沈默在北岸系好船，回头只看见一片雾。"

    private fun isHandoff(user: String) = "交给下一章接着写" in user

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += Asked(user, body)
                val answer = if (isHandoff(user)) handoffAnswer else prose
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
        val w = store.create("渡口")
        workId = w.id
        first = w.chapters.single().id
        store.renameChapter(workId, first, "第一章 渡口")
        // 前面垫够长，才看得出只给了最后一部分。
        store.saveText(workId, first, (early + "\n").repeat(400) + ending, count = false)
        store.saveCard(workId, first, ChapterCard(facts = "顾长风要走", hook = "林月追问去向"))
        second = store.addChapter(workId, w.volumes.single().id, "第二章 夜渡")!!.second.id
        store.saveCard(workId, second, ChapterCard(facts = "沈默把船撑到北岸"))
        store.updateAssist(workId) { it.copy(draftPrep = false) }
        val client = AiClient { _ -> URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true).withKey("sk-test"))
        writing = WritingAi(store, ai, scope) { messages += it }
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

    private fun writeDraft(more: Boolean = false) {
        asked.clear()
        writing.writeDraft(workId, second, more = more)
        waitFor("草稿") { writing.drafts.value[workId to second]?.let { !it.loading && it.text.isNotBlank() } == true }
    }

    private fun handoffRequests() = asked.filter { isHandoff(it.user) }
    private fun proseRequest() = asked.last { WritePrompts.CARD_HEADER in it.user }.user

    @Test
    fun beforeWritingTheNextChapterAiTidiesTheHandoffQuietlyAndTheOpeningRequestUsesIt() {
        writeDraft()
        val tidy = handoffRequests().single()
        // 不带温度，官方 V4 关思考；只给最后一部分和细纲卡的钩子，只要 JSON。
        assertFalse(tidy.body.has("temperature"))
        assertEquals("disabled", tidy.thinking)
        assertTrue(tidy.user.contains("你到底要去哪？"))
        assertTrue(tidy.user.contains("林月追问去向"))
        assertTrue(tidy.user.contains("只输出 JSON"))
        assertTrue(tidy.user.split(early).size - 1 < 400)
        // 存下了，和正文指纹一起。
        val saved = store.handoff(workId, first)!!
        assertEquals(HandoffOrigin.AI, saved.origin)
        assertEquals(Handoffs.hash(store.text(workId, first)), saved.hash)
        // 写本章开头的请求：上一章结尾后面接“上一章交接”，任务卡里有那一句。
        val p = proseRequest()
        assertTrue(p.indexOf("上一章结尾：") in 0 until p.indexOf(WritePrompts.HANDOFF_HEADER))
        assertTrue(p.contains("场景：渡口茶馆门口，天快黑了"))
        assertTrue(p.contains("在场人物：林月：追到门口；又急又气；顾长风：背对着她；不肯说"))
        assertTrue(p.contains("没了结的：顾长风要去哪"))
        assertTrue(p.contains(WritePrompts.CONTINUITY))
        // 交接卡还新：再写一次不再整理。
        writeDraft()
        assertTrue(handoffRequests().isEmpty())
        assertTrue(proseRequest().contains(WritePrompts.HANDOFF_HEADER))
        // 接着写：不带交接卡，也没有那一句。
        writeDraft(more = true)
        val more = proseRequest()
        assertFalse(more.contains(WritePrompts.HANDOFF_HEADER))
        assertFalse(more.contains(WritePrompts.CONTINUITY))
    }

    @Test
    fun aBadAnswerIsReportedAndTheChapterIsWrittenWithoutAHandoff() {
        handoffAnswer = "好的，这一章结尾在渡口。"
        writeDraft()
        assertEquals(1, handoffRequests().size)
        assertTrue(messages.any { "上一章的交接没整理成" in it && "不带交接卡" in it })
        assertFalse(proseRequest().contains(WritePrompts.HANDOFF_HEADER))
        // 照常写：上一章结尾照样带。
        assertTrue(proseRequest().contains("你到底要去哪？"))
        assertNull(store.handoff(workId, first))
        assertEquals(prose, writing.drafts.value[workId to second]!!.text)
    }

    @Test
    fun editingThePreviousChapterMakesTheHandoffStaleAndItIsTidiedAgain() {
        writeDraft()
        assertEquals(1, handoffRequests().size)
        runBlocking { assertFalse(writing.handoffView(workId, second)!!.stale) }
        store.saveText(workId, first, store.text(workId, first) + "\n杜娘关了门。", count = false)
        runBlocking {
            val view = writing.handoffView(workId, second)!!
            assertTrue(view.stale)
            // 过期了先给本机抽的。
            assertEquals(HandoffOrigin.LOCAL, view.card.origin)
        }
        writeDraft()
        assertEquals(1, handoffRequests().size)
        assertTrue(handoffRequests().single().user.contains("杜娘关了门。"))
        runBlocking { assertFalse(writing.handoffView(workId, second)!!.stale) }
    }

    @Test
    fun theAuthorsEditWinsUntilTheyAskToTidyAgain() {
        runBlocking {
            writing.saveHandoff(workId, first, ChapterHandoff(scene = "北岸芦苇荡", mood = "冷"))
        }
        assertEquals(HandoffOrigin.AUTHOR, store.handoff(workId, first)!!.origin)
        // 正文又改了：作者改过的照用，不自动重新整理。
        store.saveText(workId, first, store.text(workId, first) + "\n杜娘关了门。", count = false)
        writeDraft()
        assertTrue(handoffRequests().isEmpty())
        assertTrue(proseRequest().contains("场景：北岸芦苇荡"))
        runBlocking { assertTrue(writing.handoffView(workId, second)!!.outdated) }
        // 收尾整理时也不盖掉。
        writing.updateAssist(workId) { it.copy(finish = FinishOptions(sync = false, summary = false, review = false, learn = false, handoff = true)) }
        writing.openFinish(workId, first)
        waitFor("勾选项读完") { writing.finish.value?.options?.let { !it.sync && it.handoff } == true }
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        assertEquals("北岸芦苇荡", store.handoff(workId, first)!!.scene)
        // 点“重新整理”才重来。
        asked.clear()
        val fresh = runBlocking { writing.refreshHandoff(workId, second) }
        assertNotNull(fresh)
        assertEquals(1, handoffRequests().size)
        assertEquals(HandoffOrigin.AI, store.handoff(workId, first)!!.origin)
        assertEquals("渡口茶馆门口，天快黑了", store.handoff(workId, first)!!.scene)
    }

    @Test
    fun finishingAChapterTidiesItsHandoff() {
        store.updateAssist(workId) { it.copy(finish = FinishOptions(sync = false, summary = false, review = false, learn = false, handoff = true)) }
        writing.openFinish(workId, first)
        waitFor("勾选项读完") { writing.finish.value?.options?.let { !it.sync && it.handoff } == true }
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        assertEquals(StepState.DONE, writing.finish.value!!.handoff)
        assertEquals(1, handoffRequests().size)
        assertEquals(HandoffOrigin.AI, store.handoff(workId, first)!!.origin)
        // 下一章写之前不再整理。
        writeDraft()
        assertTrue(handoffRequests().isEmpty())
    }

    @Test
    fun aFailedTidyDoesNotBlockFinishing() {
        handoffAnswer = "不是 JSON"
        store.updateAssist(workId) { it.copy(finish = FinishOptions(sync = false, summary = false, review = false, learn = false, handoff = true)) }
        writing.openFinish(workId, first)
        waitFor("勾选项读完") { writing.finish.value?.options?.let { !it.sync && it.handoff } == true }
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        assertEquals(StepState.FAILED, writing.finish.value!!.handoff)
        assertTrue(writing.finish.value!!.handoffError!!.contains("交接卡没整理成"))
    }

    @Test
    fun theEditorCarriesTheHandoffOnlyWhenWritingFromTheChapterStart() {
        runBlocking { writing.saveHandoff(workId, first, ChapterHandoff(scene = "北岸芦苇荡")) }
        asked.clear()
        writing.writeInline(workId, second, "", 0, InlineMode.SCENE)
        waitFor("章首写") { writing.inline.value?.let { !it.anyLoading } == true }
        val opening = proseRequest()
        assertTrue(opening.contains(WritePrompts.HANDOFF_HEADER) && opening.contains("场景：北岸芦苇荡"))
        assertTrue(opening.contains(WritePrompts.CONTINUITY))
        // 编辑器里不为交接卡另发请求。
        assertTrue(handoffRequests().isEmpty())
        asked.clear()
        val text = "沈默撑开船，雾很大。"
        writing.writeInline(workId, second, text, text.length, InlineMode.SCENE)
        waitFor("写到中间") { writing.inline.value?.let { !it.anyLoading && it.at == text.length } == true }
        val middle = proseRequest()
        assertFalse(middle.contains(WritePrompts.HANDOFF_HEADER))
        assertFalse(middle.contains(WritePrompts.CONTINUITY))
    }

    @Test
    fun movingChaptersKeepsCardsByIdAndThePreviousChapterFollowsTheCatalog() {
        runBlocking { writing.saveHandoff(workId, first, ChapterHandoff(scene = "北岸芦苇荡")) }
        val third = store.addChapter(workId, store.get(workId)!!.volumes.single().id, "第三章 雾散")!!.second.id
        store.saveText(workId, second, "沈默在北岸系好船。", count = false)
        runBlocking { assertEquals(first, writing.handoffView(workId, second)!!.chapterId) }
        runBlocking { assertEquals(second, writing.handoffView(workId, third)!!.chapterId) }
        // 第三章挪到第二章前面：第三章的上一章成了第一章，交接卡还是那一张。
        store.moveChapter(workId, third, -1)
        runBlocking {
            val view = writing.handoffView(workId, third)!!
            assertEquals(first, view.chapterId)
            assertEquals("北岸芦苇荡", view.card.scene)
        }
        assertEquals("北岸芦苇荡", store.handoff(workId, first)!!.scene)
    }
}
