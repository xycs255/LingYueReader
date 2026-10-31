package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 设定文档的 AI 起草、按要求改、精简发出去的请求：不带温度（作品设了写正文的温度也不带），官方 V4 上关思考；
 * 起草带作品信息、按预算挑的设定、同类文档、规划树和章节摘要，不带正文全文；空的、截断的结果报错、不写入。
 * 设定包用《渡灯》fixture（全是虚构内容）。
 */
class PackDocRequestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var docId = ""
    private var emptyDocId = ""
    private val bodies = CopyOnWriteArrayList<JSONObject>()

    /** 假服务按最后一句用户的话回答。 */
    @Volatile
    private var reply: (String) -> String = { "## 灯芯\n- 来历：林月母亲留下的旧灯笼。\n- 用法：夜里照见水下的东西。" }

    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    private val chapterBody get() = res("ch1.txt").lines().first().trim()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                bodies += body
                val messages = body.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                val answer = reply(user)
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 2_000).put("completion_tokens", 100))
                    .toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        val port = server.address.port
        val client = AiClient { url -> URL("http://127.0.0.1:$port${url.path}").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))

        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        listOf(
            "工作说明" to PackKind.INSTRUCTION, "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
            "第一卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON, "人物档案" to PackKind.CHARACTER,
            "伏笔表" to PackKind.FORESHADOW, "写作规范" to PackKind.STYLE,
        ).forEach { (name, kind) -> store.addPackDoc(workId, kind, name, res("pack/$name.md")) }
        // 一份按需的同类文档：知识库不整份放，起草同类时要单独带上。
        store.addPackDoc(workId, PackKind.CANON, "漕帮规矩", "## 漕帮\n- 帮主：钱二爷\n- 规矩：夜里不过白沙渡。\n", always = false)
        val first = w.chapters[0].id
        store.renameChapter(workId, first, "第1章 雾里渡口")
        store.saveText(workId, first, res("ch1.txt"), count = false)
        store.saveCard(workId, first, ChapterCard(summary = "林月去渡口打听父亲的下落，遇见沈默。"))
        val (_, two) = store.addChapter(workId, w.volumes[0].id, "第2章 空船")!!
        store.saveCard(workId, two.id, ChapterCard(facts = "老周的船拴在桩上，船上没人。"))
        docId = store.addPackDoc(workId, PackKind.CANON, "金手指", "## 灯芯\n- 来历：旧灯笼。\n").id
        emptyDocId = store.addPackDoc(workId, PackKind.CANON, "势力", "").id
        store.updateAssist(workId) { it.copy(temperature = 1.1f) }
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun JSONObject.system() = getJSONArray("messages").getJSONObject(0).getString("content")
    private fun JSONObject.user() = getJSONArray("messages").let { m -> (0 until m.length()).map { m.getJSONObject(it) }.last { it.getString("role") == "user" } }.getString("content")

    @Test
    fun draftCarriesWorkSettingsPlanAndSummariesButNoChapterText() = runBlocking {
        val text = writing.draftDoc(workId, emptyDocId, "三方势力的划分")
        assertTrue(text.startsWith("## 灯芯"))
        val body = bodies.single()
        // 不带温度；官方 V4 上关掉思考（要一整份文档，思考会吃掉输出上限）。
        assertFalse(body.has("temperature"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        val system = body.system()
        val user = body.user()
        assertTrue(system.contains("《渡灯》"))
        assertTrue("常用设定按预算整份带上", system.contains("林守仁"))
        // 同类（硬性设定）里按需的那份单独带上；规划树、章节摘要、细纲卡也在。
        assertTrue(user.contains("【漕帮规矩】"))
        assertTrue(user.contains("第1章 雾里渡口"))
        assertTrue(user.contains("林月去渡口打听父亲的下落"))
        assertTrue(user.contains("老周的船拴在桩上"))
        assertTrue(user.contains("三方势力的划分"))
        // 按文档类型给格式提示。
        assertTrue(user.contains(PackKind.CANON.hint))
        // 正文全文一句都不带。
        assertFalse(system.contains(chapterBody) || user.contains(chapterBody))
        // 起草的结果只给看，还没写进文档。
        assertEquals("", store.packText(workId, emptyDocId))
        assertEquals("起草设定", writing.lastRequest.value!!.label)
    }

    @Test
    fun reviseOnlyTheSelectionAndKeepTheRest() = runBlocking {
        val text = store.packText(workId, docId)
        reply = { "- 来历：林月母亲留下的旧灯笼，灯罩上两个墨点。" }
        val at = text.indexOf("- 来历")
        val range = at until text.indexOf('\n', at)
        val out = writing.reviseDoc(workId, docId, text, "补上灯笼的标记", range)
        assertEquals("## 灯芯\n- 来历：林月母亲留下的旧灯笼，灯罩上两个墨点。\n", out)
        val body = bodies.single()
        assertFalse(body.has("temperature"))
        assertTrue(body.user().contains("要改的这一段：\n- 来历：旧灯笼。"))
        assertTrue(body.user().contains("补上灯笼的标记"))
        assertFalse(body.user().contains(chapterBody))
    }

    @Test
    fun condenseListsWhatMustStayAndReportsWhatGotLost() = runBlocking {
        val text = "## 漕帮\n- 帮主：钱二爷，五十三岁。\n- 规矩：夜里不过白沙渡，违者罚银 30 两。\n- 帮里的人都很怕他，说起他来声音都会压低。\n"
        reply = { "## 漕帮\n- 帮主：钱二爷。\n- 规矩：夜里不过白沙渡。" }
        val out = writing.condenseDoc(workId, docId, text, 40)
        val user = bodies.single().user()
        assertTrue(user.contains("精简到约 40 字"))
        assertTrue(user.contains("钱二爷") && user.contains("五十三岁") && user.contains("30"))
        assertFalse(bodies.single().has("temperature"))
        // 新稿里丢了年龄和罚银：列出来提醒作者。
        assertTrue(out.missing.contains("五十三岁"))
        assertTrue(out.missing.any { it.startsWith("30") })
        assertFalse(out.missing.contains("钱二爷"))
    }

    @Test
    fun truncatedOrEmptyResultsAreRefused() = runBlocking {
        val before = store.packText(workId, docId)
        reply = { "## 灯芯\n- 来历：林月母亲留下的旧灯笼，" }
        try {
            writing.reviseDoc(workId, docId, before, "写长一点")
            fail("截断的结果不该交出来")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("没写完"))
        }
        reply = { "```markdown\n## 灯芯\n- 来历：旧灯笼。" }
        try {
            writing.draftDoc(workId, emptyDocId, "")
            fail("代码块没收尾的结果不该交出来")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("没写完"))
        }
        reply = { "   " }
        try {
            writing.condenseDoc(workId, docId, before, 10)
            fail("空的结果不该交出来")
        } catch (e: AiException) {
            assertTrue(e.message!!.isNotBlank())
        }
        // 文档一字没动。
        assertEquals(before, store.packText(workId, docId))
        assertEquals("", store.packText(workId, emptyDocId))
    }
}
