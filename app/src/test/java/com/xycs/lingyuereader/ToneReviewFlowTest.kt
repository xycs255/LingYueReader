package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.FixReason
import com.xycs.lingyuereader.data.ToneIssue
import com.xycs.lingyuereader.data.ToneKind
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
 * “AI 查 AI 腔”（假的官方 DeepSeek V4，文字自造、人名虚构）：点了才发；一组固定问题；不带温度、关思考、只要 JSON，
 * 记在“审稿”类；原句对不上正文的丢掉；每条能接到“这段不行”。
 */
class ToneReviewFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    private val asked = CopyOnWriteArrayList<JSONObject>()

    private val text = "林月心头一暖，她很感动。\n沈默说：“你也知道，渡灯镇三百年前是座水寨。”\n这一夜，她终于明白了什么叫守护。"

    @Volatile
    private var answer = """{"issues":[
        {"kind":"情绪贴标签","quote":"林月心头一暖，她很感动。","problem":"情绪直接说出来了","fix":"写她手上的动作"},
        {"kind":"EXPOSE","quote":"渡灯镇三百年前是座水寨","problem":"两人都知道的事说给读者听","fix":""},
        {"kind":"段尾升华","quote":"她终于明白了什么叫守护","problem":"段尾加了一句感慨","fix":"删掉，停在动作上"},
        {"kind":"比喻扎堆","quote":"正文里没有这一句","problem":"编出来的"}
    ]}"""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                asked += body
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
        chapterId = w.chapters.single().id
        store.saveText(workId, chapterId, text, count = false)
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

    private fun user(body: JSONObject): String {
        val msgs = body.getJSONArray("messages")
        return (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
    }

    @Test
    fun nothingIsSentUntilAsked() {
        // 打开作品、读草稿都不发这一类请求：只有点了“AI 查 AI 腔”才发。
        writing.loadAssist(workId)
        writing.loadDraft(workId, chapterId)
        Thread.sleep(300)
        assertTrue(asked.isEmpty())
        assertNull(writing.lastRequest.value)
    }

    @Test
    fun theFixedQuestionsGoOutAsAQuietJsonRequest() {
        val found = runBlocking { writing.toneReview(workId, chapterId, text) }
        val body = asked.single()
        assertFalse(body.has("temperature"))
        assertEquals("disabled", body.optJSONObject("thinking")?.getString("type"))
        val u = user(body)
        // 一组固定问题，每类一问；只要 JSON；不碰错字、事实、情节。
        ToneKind.entries.forEach { assertTrue(it.label, it.label in u) }
        assertTrue("只输出 JSON" in u && "\"issues\"" in u && "不提错字、事实和情节安排" in u)
        assertTrue("林月心头一暖" in u)
        // 记在“审稿”类（请求名“AI 腔审读”）。
        assertEquals("AI 腔审读", writing.lastRequest.value?.label)
        // 编出来的原句丢掉；类别认名字或中文标签。
        assertEquals(listOf(ToneKind.LABEL, ToneKind.EXPOSE, ToneKind.UPLIFT), found.map { it.kind })
        assertEquals("林月心头一暖，她很感动。", found[0].quote)
    }

    @Test
    fun aBadAnswerIsAnErrorNotAClean() {
        answer = "看起来没什么问题。"
        val failed = runCatching { runBlocking { writing.toneReview(workId, chapterId, text) } }
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("不是要求的格式"))
        // 空列表是“没查出来”，不是出错。
        answer = """{"issues":[]}"""
        assertTrue(runBlocking { writing.toneReview(workId, chapterId, text) }.isEmpty())
        // 没正文不发。
        assertTrue(runCatching { runBlocking { writing.toneReview(workId, chapterId, " ") } }.isFailure)
        assertEquals(2, asked.size)
    }

    @Test
    fun eachFindingBecomesAThisParagraphIsBadInstruction() {
        val issue = ToneIssue("她终于明白了什么叫守护", ToneKind.UPLIFT, "段尾加了一句感慨", "删掉，停在动作上")
        val say = issue.instruction()
        assertTrue(say.startsWith("这段不行（${FixReason.WORDY.label}）"))
        assertTrue(FixReason.WORDY.fix in say)
        assertTrue("段尾加了一句感慨" in say && "删掉，停在动作上" in say && "只改这一段" in say)
        // 解析：超过上限的截掉，重复的合成一条。
        val many = (1..12).joinToString(",") { """{"kind":"放哪都行","quote":"林月心头一暖","problem":"第${it}条"}""" }
        assertEquals(1, WritePrompts.parseTone("""{"issues":[$many]}""", text)!!.size)
        assertNull(WritePrompts.parseTone("好的", text))
    }
}
