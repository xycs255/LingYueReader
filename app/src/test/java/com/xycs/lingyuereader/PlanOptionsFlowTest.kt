package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
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
import java.util.Collections

/**
 * 拟细纲前先给 3 个走向（假 AI，文字自造、人名虚构）：一次小请求，不带温度、能关思考的关掉思考，用量记在“拟细纲”类；
 * 防剧透照旧，带上超期的旧伏笔；作者挑一个再按它拟细纲。一键写本章打开这一项时停在挑走向上，挑了才往下，停止照常能停。
 */
class PlanOptionsFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private data class Asked(val user: String, val body: JSONObject) {
        val kind: String get() = when {
            "3 个可选的走向" in user -> "走向"
            "拟细纲卡" in user -> "拟细纲"
            "拆成 3 到 8 拍" in user -> "拆节拍"
            else -> Regex("只写第 (\\d+) 拍").find(user)?.let { "第${it.groupValues[1]}拍" } ?: "别的"
        }
        val thinking: String? get() = body.optJSONObject("thinking")?.getString("type")
    }

    private lateinit var server: HttpServer
    private val asked: MutableList<Asked> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private var workId = ""
    private var first = ""
    private var second = ""

    private val options = """{"options":[
        {"what":"林月在渡口等沈默，等来的是杜娘","change":"林月知道沈默失约","end":"杜娘递来一封信","hook":"谁替沈默送的信"},
        {"what":"林月独自过河去找老周","change":"林月和老周翻脸","end":"老周的灯灭了","hook":"老周到底站哪边"},
        {"what":"沈默带伤回来，两人连夜搬走铜灯","change":"两人从提防变成合作","end":"门外有人敲门","hook":"敲门的是谁"}
    ]}"""
    private val plan = "本章事实与结果：林月过河找老周，两人翻脸\n后文承接边界：老周还活着\n视角人物：林月\n倒计时：无\n章末钩子：老周的灯灭了\n推进的线：明线"
    private val split = "1. 林月过河\n2. 老周翻脸\n3. 老周的灯灭了"
    private val beats = mapOf(1 to "林月撑船过了河。", 2 to "老周把门关上了。", 3 to "老周的灯灭了。")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                val a = Asked(user, body)
                asked += a
                val answer = when (a.kind) {
                    "走向" -> options
                    "拟细纲" -> plan
                    "拆节拍" -> split
                    "别的" -> "好的。"
                    else -> beats.getValue(a.kind.removePrefix("第").removeSuffix("拍").toInt())
                }
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                    .toString().toByteArray()
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
        first = w.chapters.single().id
        second = store.addChapter(workId, w.volumes.single().id)!!.second.id
        store.renameChapter(workId, first, "第1章 渡口")
        store.renameChapter(workId, second, "第2章 过河")
        store.saveText(workId, first, "林月提灯到了渡口。沈默没来。", count = false)
        store.saveCard(workId, first, ChapterCard(facts = "林月在渡口没等到沈默", summary = "林月在渡口没等到沈默。"))
        store.applyLedger(workId, listOf(
            // 计划第 1 章收、还开着：写第 2 章时算超期，可以推进或回收。
            LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "铜灯底座的记号", from = 1, payoff = 1)),
            // 第 3 章才埋的：写第 2 章时不能提（防剧透）。
            LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "灯谜的谜底", from = 3, payoff = 1)),
        ), "手动添加")
        store.updateAssist(workId) { it.copy(planOptions = true) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    /** 官方 DeepSeek（能关思考）或本机模型。 */
    private fun newWriting(local: Boolean = false): WritingAi {
        val base = "http://127.0.0.1:${server.address.port}"
        val ai = if (local) AiAssistant(scope, null, bookFile = { _, _ -> null }).also {
            it.saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "$base/v1", model = "qwen3:8b"))
        } else {
            val client = AiClient { _ -> URL("$base/chat/completions").openConnection() as HttpURLConnection }
            AiAssistant(scope, null, client, bookFile = { _, _ -> null }).also { it.saveConfig(AiConfig(enabled = true).withKey("sk-test")) }
        }
        return WritingAi(store, ai, scope) {}
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    /** 发出去的几类请求（上一章交接这类别的请求不算）。 */
    private fun kinds() = synchronized(asked) { asked.map { it.kind }.filter { it != "别的" } }

    @Test
    fun threeDirectionsInOneSmallRequestThenThePlanFollowsThePick() {
        val writing = newWriting()
        val list = runBlocking { writing.planOptions(workId, second) }
        assertEquals(3, list.size)
        assertEquals("老周到底站哪边", list[1].hook)
        val ask = asked.single()
        // 不带温度，官方 V4 关思考；只要 JSON；150 字以内、各有看点。
        assertFalse(ask.body.has("temperature"))
        assertEquals("disabled", ask.thinking)
        assertTrue("只输出 JSON" in ask.user && "不超过 150 字" in ask.user && "看点" in ask.user)
        // 接上一章结尾；超期的旧伏笔作为候选带上，不强制；后面才埋的不提。
        assertTrue("沈默没来" in ask.user)
        assertTrue("铜灯底座的记号" in ask.user && "不强制" in ask.user)
        assertFalse("灯谜" in ask.user || "灯谜" in (ask.body.getJSONArray("messages").getJSONObject(0).optString("content")))
        // 记在“拟细纲”类（请求名“给走向”）。
        assertEquals("给走向", writing.lastRequest.value?.label)

        // 挑第二个：拟细纲照这个走向展开。
        val card = runBlocking { writing.planCard(workId, second, list[1].text()) }
        assertEquals("林月过河找老周，两人翻脸", card.facts)
        val planAsk = asked.last()
        assertEquals("拟细纲", planAsk.kind)
        assertTrue("作者选定的走向：林月独自过河去找老周；本章改变：林月和老周翻脸；章末停在：老周的灯灭了" in planAsk.user)
        assertFalse(planAsk.body.has("temperature"))
    }

    @Test
    fun aBadAnswerSaysSo() {
        val writing = newWriting()
        assertTrue(runCatching { WritePrompts.parsePlanOptions("好的，我想了三个走向。") }.isFailure)
        assertEquals(1, WritePrompts.parsePlanOptions("""```json
{"options":[{"what":"林月过河","hook":"看点"},{"what":""},{"what":"林月过河"}]}
```""").size)
        assertTrue(WritePrompts.parsePlanOptions("""{"options":[{"what":"${"长".repeat(400)}"}]}""").single().what.length <= WritePrompts.OPTION_CHARS)
        assertNull(writing.drafts.value[workId to second])
    }

    @Test
    fun oneClickWritingStopsForTheChoiceAndGoesOnAfterThePick() {
        val writing = newWriting(local = true)
        writing.loadDraft(workId, second)
        waitFor("读草稿") { writing.drafts.value[workId to second] != null }
        writing.writeDraft(workId, second, prepare = true)
        waitFor("停在挑走向") { writing.drafts.value[workId to second]?.prep == DraftPrep.CHOOSE }
        val waiting = writing.drafts.value[workId to second]!!
        assertTrue(waiting.loading)
        assertEquals(3, waiting.directions?.size)
        // 停在这里：只发了出走向的那一次，没拟细纲、没写。
        Thread.sleep(200)
        assertEquals(listOf("走向"), kinds())
        assertTrue(store.card(workId, second).facts.isBlank())

        writing.pickDirection(workId, second, 1)
        waitFor("写完") { writing.drafts.value[workId to second]?.loading == false }
        val done = writing.drafts.value[workId to second]!!
        assertNull(done.error)
        assertNull(done.directions)
        assertEquals(listOf("走向", "拟细纲", "拆节拍", "第1拍", "第2拍", "第3拍"), kinds())
        assertTrue("作者选定的走向：林月独自过河去找老周" in asked.single { it.kind == "拟细纲" }.user)
        assertEquals("林月过河找老周，两人翻脸", store.card(workId, second).facts)
        assertEquals(beats.values.joinToString("\n"), done.text)
    }

    @Test
    fun stoppingWhileChoosingSavesNothing() {
        val writing = newWriting(local = true)
        writing.loadDraft(workId, second)
        waitFor("读草稿") { writing.drafts.value[workId to second] != null }
        writing.writeDraft(workId, second, prepare = true)
        waitFor("停在挑走向") { writing.drafts.value[workId to second]?.prep == DraftPrep.CHOOSE }
        writing.stopDraft(workId, second)
        waitFor("停下") { writing.drafts.value[workId to second]?.loading == false }
        val stopped = writing.drafts.value[workId to second]!!
        assertNull(stopped.directions)
        assertNull(stopped.prep)
        // 挑了也不再往下走。
        writing.pickDirection(workId, second, 0)
        Thread.sleep(200)
        assertEquals(listOf("走向"), kinds())
        assertTrue(store.card(workId, second).facts.isBlank())
    }

    @Test
    fun withTheSwitchOffOneClickPlansStraightAway() {
        store.updateAssist(workId) { it.copy(planOptions = false) }
        val writing = newWriting(local = true)
        writing.loadDraft(workId, second)
        waitFor("读草稿") { writing.drafts.value[workId to second] != null }
        writing.writeDraft(workId, second, prepare = true)
        waitFor("写完") { writing.drafts.value[workId to second]?.loading == false }
        assertEquals(listOf("拟细纲", "拆节拍", "第1拍", "第2拍", "第3拍"), kinds())
        // 拟细纲照样带上超期的旧伏笔，不带后面的。
        val planAsk = asked.first { it.kind == "拟细纲" }
        assertTrue("铜灯底座的记号" in planAsk.user && "可以推进或回收的旧伏笔" in planAsk.user)
        assertFalse("灯谜" in planAsk.user)
    }
}
