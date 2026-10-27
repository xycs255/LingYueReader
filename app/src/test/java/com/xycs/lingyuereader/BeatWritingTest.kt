package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.DraftBeats
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
 * 按节拍写：“AI 写本章”逐拍请求、每拍连同进度存一次，停下或出错后从下一拍接着写；
 * 编辑器里只写某一拍，采纳后记成已写；AI 拆节拍。
 */
class BeatWritingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    /** 写到这一拍时回一次错（只一次）。 */
    @Volatile private var failBeat = 0
    /** 写到这一拍时卡住，等测试放行。 */
    @Volatile private var holdBeat = 0
    private val hold = CountDownLatch(1)

    private val beatText = mapOf(1 to "沈默到了渡口，船不见了。", 2 to "杜娘开了门，说老周昨夜走了。", 3 to "老周的信在桌上压着。")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                prompts += user
                val beat = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.toInt()
                if (beat != null && beat == failBeat) {
                    failBeat = 0
                    val bytes = """{"error":{"message":"模拟断线"}}""".toByteArray()
                    ex.sendResponseHeaders(400, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                    return@createContext
                }
                if (beat != null && beat == holdBeat) hold.await(30, TimeUnit.SECONDS)
                val answer = when {
                    "【当前阶段：审读】" in user -> """{"summary":["节拍齐全"],"missing":[],"issues":[]}"""
                    "拆成 3 到 8 拍" in user -> "好的，拆成三拍：\n1. 沈默赶到渡口，船不见了（800字）\n2. 杜娘开门\n3. 老周的信（约 500 字）"
                    beat != null -> beatText.getValue(beat)
                    else -> "整章一口气写完。"
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
        store.addPackDoc(workId, PackKind.RULES, "检查规则", "字数：3000-3000\n")
        store.saveCard(workId, chapterId, ChapterCard(
            facts = "沈默到渡口，老周已经走了", hook = "老周留下一封信",
            beats = "1. 沈默赶到渡口，发现船不见了\n2. 杜娘开门，说老周昨夜走了\n3. 老周的信（500字）",
        ))
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"))
        writing = WritingAi(store, ai, scope) { }
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
    private fun beatPrompt(n: Int) = prompts.filter { "只写第 $n 拍" in it }
    private val all = beatText.values.joinToString("\n")

    private fun loadDraft() {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
    }

    @Test
    fun eachBeatIsItsOwnRequestAndTheCandidateIsTheBeatsJoined() {
        loadDraft()
        writing.writeDraft(workId, chapterId)
        waitFor("写完三拍") { draft()?.loading == false }
        assertNull(draft()?.error)
        assertEquals(all, draft()?.text)
        assertEquals(DraftBeats(3, 3), draft()?.beats)
        // 盘上的候选稿和进度是同一份。
        val disk = store.draftContent(workId, chapterId)
        assertEquals(all, disk.text)
        assertEquals(DraftBeats(3, 3), disk.beats)

        // 第一拍：只写开头这一拍，下一拍不要写到；没写篇幅按全章平均分。
        val first = beatPrompt(1).single()
        assertTrue(first.contains("请只写本章开头的这一拍"))
        assertTrue(first.contains("「沈默赶到渡口，发现船不见了」。这件事必须写到、写完"))
        assertTrue(first.contains("下一拍「杜娘开门，说老周昨夜走了」不要写到"))
        assertTrue(first.contains("这一拍篇幅约 1000 字"))
        // 第二拍：接着已写的写，上一拍不要重写。
        val second = beatPrompt(2).single()
        assertTrue(second.contains("已经写了下面这些"))
        assertTrue(second.contains(beatText.getValue(1)))
        assertTrue(second.contains("上一拍「沈默赶到渡口，发现船不见了」已经写过了，上面已经写的正文就是，不要重写"))
        assertFalse(second.contains("请写出这一章「"))
        // 最后一拍：用它自己写的篇幅，写完收束。
        val third = beatPrompt(3).single()
        assertTrue(third.contains("这一拍篇幅约 500 字"))
        assertTrue(third.contains("这是最后一拍"))
        assertEquals(3, prompts.size)
    }

    @Test
    fun aFailedBeatKeepsTheSavedOnesAndContinuingStartsFromIt() {
        loadDraft()
        failBeat = 2
        writing.writeDraft(workId, chapterId)
        waitFor("第二拍出错") { draft()?.loading == false }
        // 第一拍写好存了；第二拍没写成，不留半截。
        assertEquals(beatText.getValue(1), draft()?.text)
        assertEquals(DraftBeats(1, 3), draft()?.beats)
        assertTrue(draft()?.notice.orEmpty().contains("接着写从第 2 拍开始"))
        assertEquals(beatText.getValue(1), store.draft(workId, chapterId))
        assertEquals(DraftBeats(1, 3), store.draftContent(workId, chapterId).beats)

        // 接着写：从第 2 拍开始，第 1 拍不再请求。
        writing.writeDraft(workId, chapterId, more = true)
        waitFor("接着写完") { draft()?.loading == false && draft()?.beats == DraftBeats(3, 3) }
        assertEquals(all, draft()?.text)
        assertEquals(1, beatPrompt(1).size)
        assertEquals(2, beatPrompt(2).size)
        assertEquals(all, store.draft(workId, chapterId))
    }

    @Test
    fun stoppingMidBeatDropsTheHalfWrittenBeatAndResumesThere() {
        loadDraft()
        holdBeat = 2
        writing.writeDraft(workId, chapterId)
        waitFor("写到第二拍") { draft()?.beatWriting == 2 && beatPrompt(2).isNotEmpty() }
        assertEquals(DraftBeats(1, 3), store.draftContent(workId, chapterId).beats)
        writing.stopDraft(workId, chapterId)
        assertFalse(draft()?.loading == true)
        assertEquals(beatText.getValue(1), draft()?.text)
        assertTrue(draft()?.notice.orEmpty().contains("接着写从第 2 拍开始"))
        hold.countDown()
        Thread.sleep(300)
        // 放行后迟到的回答不进候选稿。
        assertEquals(beatText.getValue(1), store.draft(workId, chapterId))
        assertEquals(DraftBeats(1, 3), store.draftContent(workId, chapterId).beats)

        // 重开以后（只看盘上的）接着写，也从第 2 拍开始。
        val reopened = WritingAi(store, AiAssistant(scope, null, bookFile = { _, _ -> null }).apply {
            saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"))
        }, scope) { }
        reopened.loadDraft(workId, chapterId)
        waitFor("重开读草稿") { reopened.drafts.value[key] != null }
        assertEquals(DraftBeats(1, 3), reopened.drafts.value[key]?.beats)
        holdBeat = 0
        val before = beatPrompt(1).size
        reopened.writeDraft(workId, chapterId, more = true)
        waitFor("接着写完") { reopened.drafts.value[key]?.let { !it.loading && it.beats == DraftBeats(3, 3) } == true }
        assertEquals(all, reopened.drafts.value[key]?.text)
        assertEquals(before, beatPrompt(1).size)
    }

    @Test
    fun withoutBeatsTheChapterIsStillOneRequest() {
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(beats = "", beatsDone = emptyList()))
        loadDraft()
        writing.writeDraft(workId, chapterId)
        waitFor("写完") { draft()?.loading == false }
        assertEquals("整章一口气写完。", draft()?.text)
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("请写出这一章「"))
        assertFalse(prompts.single().contains("拍"))
        assertNull(store.draftContent(workId, chapterId).beats)
    }

    @Test
    fun theReviewWorkflowRunsAfterAllBeats() {
        loadDraft()
        writing.writeDraft(workId, chapterId, workflow = true)
        waitFor("写完并审读") { draft()?.loading == false }
        assertNull(draft()?.error)
        // 拼好的三拍是创作初稿；篇幅不够，本机检查不过，照原来修订一轮（修订稿替换全文，初稿留底）。
        assertEquals(all, draft()?.initialText)
        assertEquals("整章一口气写完。", draft()?.text)
        assertTrue(draft()?.assessment != null)
        assertEquals(DraftBeats(3, 3), store.draftContent(workId, chapterId).beats)
        // 第一拍走项目包的创作提示，审读看的是拼好的全文。
        assertTrue(beatPrompt(1).single().contains("【当前阶段：创作】"))
        assertTrue(beatPrompt(1).single().contains("只写本章开头的这一拍"))
        val review = prompts.first { "【当前阶段：审读】" in it }
        assertTrue(review.contains(all))
    }

    @Test
    fun theEditorWritesOneBeatAndMarksItWrittenOnlyWhenAccepted() {
        val text = beatText.getValue(1)
        store.saveText(workId, chapterId, text, count = false)
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.BEAT, beat = 2)
        waitFor("写第二拍") { writing.inline.value?.loading == false }
        val ask = beatPrompt(2).single()
        assertTrue(ask.contains("请从光标处接着往下写正文"))
        assertTrue(ask.contains("上一拍「沈默赶到渡口，发现船不见了」已经写过了，光标前的正文就是，不要重写"))
        assertTrue(ask.contains("下一拍「老周的信」不要写到：写到第 2 拍收住就停"))
        // 重写：还是这一拍。
        writing.retryInline()
        waitFor("重写第二拍") { writing.inline.value?.loading == false && beatPrompt(2).size == 2 }
        assertEquals(2, writing.inline.value?.beat)
        // 撤回不记。
        writing.discardInline()
        assertTrue(store.card(workId, chapterId).beatsDone.isEmpty())
        // 采纳才记成已写。
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.BEAT, beat = 2)
        waitFor("再写第二拍") { writing.inline.value?.loading == false }
        assertTrue(writing.acceptInline(text) != null)
        waitFor("记成已写") { store.card(workId, chapterId).beatsDone == listOf(2) }
        assertEquals(text + "\n" + beatText.getValue(2), store.text(workId, chapterId))
    }

    @Test
    fun splittingBeatsReturnsCleanLinesWithoutSaving() = runBlocking {
        val card = store.card(workId, chapterId).copy(beats = "")
        val beats = writing.splitBeats(workId, chapterId, card)
        assertEquals("沈默赶到渡口，船不见了（800字）\n杜娘开门\n老周的信（500字）", beats)
        val ask = prompts.single { "拆成 3 到 8 拍" in it }
        assertTrue(ask.contains("本章事实与结果：沈默到渡口，老周已经走了"))
        assertTrue(ask.contains("全章篇幅3000 到 3000 字"))
        // 不直接存：卡片上还是原来的节拍。
        assertTrue(store.card(workId, chapterId).beats.startsWith("1. 沈默赶到渡口"))
    }
}
