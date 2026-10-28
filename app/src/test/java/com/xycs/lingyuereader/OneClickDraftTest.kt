package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftBeats
import com.xycs.lingyuereader.data.DraftStage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一键写本章（草稿页点“写本章”，[WritingAi.writeDraft] 的 `prepare`）：细纲卡没有事实与结果先拟、没有节拍先拆，
 * 存进细纲卡标成 AI 拟的，再逐拍写。作者写了的不动；停下时已经存下的留着；项目包里只有章组照旧拦；拟细纲不看后面的章。
 */
class OneClickDraftTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 收到的请求：系统提示、最后一句用户的话、请求体。 */
    private data class Asked(val system: String, val user: String, val body: JSONObject) {
        val kind: String get() = when {
            "拟细纲卡" in user -> "拟细纲"
            "拆成 3 到 8 拍" in user -> "拆节拍"
            "【当前阶段：审读】" in user -> "审读"
            else -> Regex("只写第 (\\d+) 拍").find(user)?.let { "第${it.groupValues[1]}拍" } ?: "整章"
        }
    }

    private lateinit var server: HttpServer
    private val asked: MutableList<Asked> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    /** 收到这一类请求时卡住，等测试放行。 */
    @Volatile private var holdKind = ""
    private val hold = CountDownLatch(1)

    private val beatText = mapOf(1 to "林月提灯到了渡口。", 2 to "沈默从船上跳下来。", 3 to "老周的灯灭了。")
    private val plan = "本章事实与结果：林月在渡口等到沈默，老周没来\n后文承接边界：老周还活着\n视角人物：林月\n倒计时：无\n章末钩子：老周的灯灭了\n推进的线：明线"
    private val split = "1. 林月提灯到渡口（600字）\n2. 沈默下船\n3. 老周的灯灭了"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                val system = list.firstOrNull { it.getString("role") == "system" }?.optString("content").orEmpty()
                val user = list.last { it.getString("role") == "user" }.getString("content")
                val a = Asked(system, user, body)
                asked += a
                if (a.kind == holdKind) hold.await(30, TimeUnit.SECONDS)
                val answer = when (a.kind) {
                    "拟细纲" -> plan
                    "拆节拍" -> split
                    "审读" -> """{"summary":["节拍齐全"],"missing":[],"issues":[]}"""
                    "整章" -> "整章一口气写完。"
                    else -> beatText.getValue(a.kind.removePrefix("第").removeSuffix("拍").toInt())
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
        val w = store.create("渡口")
        workId = w.id
        chapterId = w.chapters[0].id
        writing = newWriting()
    }

    private fun newWriting(): WritingAi {
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"))
        return WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        hold.countDown()
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

    private val key get() = workId to chapterId
    private fun draft() = writing.drafts.value[key]
    private fun kinds() = synchronized(asked) { asked.map { it.kind } }
    private val all = beatText.values.joinToString("\n")

    private fun write(workflow: Boolean = false) {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        writing.writeDraft(workId, chapterId, workflow = workflow, prepare = true)
        waitFor("写完") { draft()?.loading == false }
    }

    @Test
    fun anEmptyCardIsPlannedThenSplitThenWrittenBeatByBeat() {
        write()
        assertNull(draft()?.error)
        assertEquals(listOf("拟细纲", "拆节拍", "第1拍", "第2拍", "第3拍"), kinds())
        assertEquals(all, draft()?.text)
        assertEquals(DraftBeats(3, 3), store.draftContent(workId, chapterId).beats)
        // 拟好的、拆好的都存进了细纲卡，标成 AI 拟的、待确认。
        val card = store.card(workId, chapterId)
        assertEquals("林月在渡口等到沈默，老周没来", card.facts)
        assertEquals("老周的灯灭了", card.hook)
        assertEquals("林月提灯到渡口（600字）\n沈默下船\n老周的灯灭了", card.beats)
        assertTrue(card.aiOutline)
        assertTrue(card.aiBeats)
        assertEquals(0, card.auto)
        // 拆节拍按拟好的细纲拆；逐拍写按拆好的节拍写。
        assertTrue(asked.single { it.kind == "拆节拍" }.user.contains("本章事实与结果：林月在渡口等到沈默，老周没来"))
        assertTrue(asked.single { it.kind == "第1拍" }.user.contains("林月提灯到渡口"))
        // 拟细纲、拆节拍求稳：不带写正文的温度；写完草稿页拿到补过以后的任务。
        listOf("拟细纲", "拆节拍").forEach { k -> assertFalse(asked.single { it.kind == k }.body.has("temperature")) }
        assertEquals("林月在渡口等到沈默，老周没来", draft()?.prepared?.card?.facts)
        // 费用照分类记：拟细纲、拆节拍记在“拟细纲”一类的记录里。
        val log = store.aiLog(workId).map { it.kind }
        assertTrue("拟细纲" in log && "拆节拍" in log)
    }

    @Test
    fun aHandWrittenCardWithoutBeatsIsOnlySplit() {
        store.saveCard(workId, chapterId, ChapterCard(facts = "沈默到渡口，老周已经走了", hook = "老周留下一封信"))
        write()
        assertNull(draft()?.error)
        assertEquals(listOf("拆节拍", "第1拍", "第2拍", "第3拍"), kinds())
        val card = store.card(workId, chapterId)
        // 作者写的细纲不动，也不标 AI 拟的；节拍是 AI 拆的。
        assertEquals("沈默到渡口，老周已经走了", card.facts)
        assertEquals("老周留下一封信", card.hook)
        assertFalse(card.aiOutline)
        assertTrue(card.aiBeats)
    }

    @Test
    fun aCardWithFactsAndBeatsWritesExactlyAsBefore() {
        val card = ChapterCard(facts = "沈默到渡口", beats = "林月到渡口\n沈默下船\n灯灭了")
        store.saveCard(workId, chapterId, card)
        write()
        assertNull(draft()?.error)
        assertEquals(listOf("第1拍", "第2拍", "第3拍"), kinds())
        assertEquals(all, draft()?.text)
        assertNull(draft()?.prepared)
        val after = store.card(workId, chapterId)
        assertEquals(card.facts, after.facts)
        assertEquals(card.beats, after.beats)
        assertFalse(after.aiOutline || after.aiBeats)
    }

    @Test
    fun withTheSwitchOffNothingIsPrepared() {
        store.updateAssist(workId) { it.copy(draftPrep = false) }
        write()
        assertEquals(listOf("整章"), kinds())
        assertTrue(store.card(workId, chapterId).isEmpty)
    }

    @Test
    fun stoppingWhilePlanningSavesNothing() {
        holdKind = "拟细纲"
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        writing.writeDraft(workId, chapterId, prepare = true)
        waitFor("在拟细纲") { draft()?.prep == DraftPrep.PLAN && kinds() == listOf("拟细纲") }
        writing.stopDraft(workId, chapterId)
        assertFalse(draft()?.loading == true)
        hold.countDown()
        Thread.sleep(500)
        // 迟到的细纲不进细纲卡，也没往下拆、写。
        assertTrue(store.card(workId, chapterId).isEmpty)
        assertEquals(listOf("拟细纲"), kinds())
        assertEquals("", store.draft(workId, chapterId))
    }

    @Test
    fun stoppingAfterTheSplitKeepsBothAndTheNextRunStartsAtBeatOne() {
        holdKind = "第1拍"
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        writing.writeDraft(workId, chapterId, prepare = true)
        waitFor("写到第一拍") { draft()?.beatWriting == 1 && "第1拍" in kinds() }
        writing.stopDraft(workId, chapterId)
        hold.countDown()
        Thread.sleep(300)
        val card = store.card(workId, chapterId)
        assertTrue(card.aiOutline && card.aiBeats)
        assertEquals(3, com.xycs.lingyuereader.data.beats(card).size)

        // 下次直接从写第 1 拍开始：不再拟、不再拆。
        holdKind = ""
        asked.clear()
        writing.writeDraft(workId, chapterId, prepare = true)
        waitFor("写完") { draft()?.loading == false && draft()?.beats == DraftBeats(3, 3) }
        assertEquals(listOf("第1拍", "第2拍", "第3拍"), kinds())
        assertEquals(all, draft()?.text)
    }

    @Test
    fun aFailedSplitKeepsThePlannedOutline() {
        // 拆节拍回的不是节拍：报清楚是哪一步没成，拟好的细纲留着。
        server.removeContext("/v1/chat/completions")
        server.createContext("/v1/chat/completions") { ex ->
            val user = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages").let { m -> m.getJSONObject(m.length() - 1).getString("content") }
            val answer = if ("拟细纲卡" in user) plan else "好的："
            val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                .toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { ex.responseBody.use { it.write(bytes) } }
        }
        write()
        assertTrue(draft()?.error.orEmpty().startsWith("拆节拍没成"))
        val card = store.card(workId, chapterId)
        assertTrue(card.aiOutline)
        assertEquals("林月在渡口等到沈默，老周没来", card.facts)
        assertEquals("", card.beats)
    }

    @Test
    fun theProjectWorkflowNoLongerStopsAtMissingFacts() {
        store.addPackDoc(workId, PackKind.INSTRUCTION, "完整正文创作提示词", "只输出本章完整正文。")
        assertEquals(listOf(ChapterWritingTask.NO_FACTS), runBlocking { writing.writingTask(workId, chapterId) }!!.blocking)
        write(workflow = true)
        assertFalse(draft()?.error.orEmpty().contains("事实与结果"))
        assertEquals(listOf("拟细纲", "拆节拍", "第1拍", "第2拍", "第3拍"), kinds().take(5))
        assertTrue("审读" in kinds())
        assertTrue(draft()?.stage != DraftStage.ERROR)
        assertTrue(store.card(workId, chapterId).aiOutline)
        // 审读看的是补过细纲以后的任务：不再缺事实与结果。
        assertTrue(asked.first { it.kind == "审读" }.system.contains("林月在渡口等到沈默"))
        assertTrue(draft()?.task?.blocking.orEmpty().isEmpty())
    }

    @Test
    fun aChapterGroupStillStopsTheProjectWorkflowWithAClearHint() {
        store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", "### 第一至三章 河桥\n事实：进城、相认、离城，三章展开。")
        write(workflow = true)
        val error = draft()?.error.orEmpty()
        assertTrue(error, "章组" in error)
        assertTrue(error, ChapterWritingTask.GROUP_HINT in error)
        // 没花拟细纲、拆节拍的钱，细纲卡也没动。
        assertTrue(kinds().isEmpty())
        assertTrue(store.card(workId, chapterId).isEmpty)
    }

    @Test
    fun planningNeverSeesLaterChapters() {
        store.renameChapter(workId, chapterId, "第一章 渡口")
        val (_, two) = store.addChapter(workId, store.get(workId)!!.volumes[0].id)!!
        store.renameChapter(workId, two.id, "第二章 夜船")
        val (_, three) = store.addChapter(workId, store.get(workId)!!.volumes[0].id)!!
        store.renameChapter(workId, three.id, "第三章 灯灭")
        store.saveText(workId, chapterId, "林月在渡口等了一夜。", count = false)
        store.saveCard(workId, chapterId, ChapterCard(summary = "林月在渡口等了一夜"))
        store.saveText(workId, three.id, "后文正文标记：顾长风推门进来。", count = false)
        store.saveCard(workId, three.id, ChapterCard(facts = "后文细纲标记：杜娘离开渡口", hook = "后文钩子标记"))
        store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", """
            # 第一卷
            ### 第二章 夜船
            事实：本章细纲标记，沈默划船过河。
            ### 第三章 灯灭
            事实：后文卷纲标记，老周死在船上。
        """.trimIndent())
        store.addPackDoc(workId, PackKind.CHARACTER, "人物档案", "林月：渡口长大。\n沈默：话少。")

        runBlocking { writing.planCard(workId, two.id) }
        val request = asked.single { it.kind == "拟细纲" }
        val sent = request.system + "\n" + request.user
        // 本章的卷纲细纲、前一章的东西都在。
        assertTrue(sent, "本章细纲标记" in sent)
        assertTrue(sent, "林月在渡口等了一夜" in sent)
        // 后面章节的正文、细纲卡、卷纲细纲都没有。
        listOf("后文正文标记", "后文细纲标记", "后文钩子标记", "后文卷纲标记", "第三章 灯灭").forEach { marker ->
            assertFalse("拟细纲请求里有后面章节的内容：$marker", marker in sent)
        }
        // 不给查阅工具：查阅工具能读到后面章节的细纲。
        assertFalse(request.body.has("tools"))
    }
}
