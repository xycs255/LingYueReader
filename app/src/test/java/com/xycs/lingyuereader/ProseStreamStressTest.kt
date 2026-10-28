package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.plannedCard
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 写正文的几条路径在接近真实的规模下跑一遍（虚构作品，几十章、每章三四千字，设定包几万字）：
 * 假 AI 先说半句、再调 read_doc / search / read_chapter 查大段资料，然后几个字一块地流式写很多块。
 * 协程放在一个单线程的“主线程”上（和 viewModelScope 一样没有异常处理器的地方，异常会让 app 闪退），
 * 记下漏出来的异常、主线程忙了多久、状态更新了几次。
 */
class ProseStreamStressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val mainBusy = AtomicLong()
    private val mainLongest = AtomicLong()
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
    private val main = java.util.concurrent.Executor { r ->
        mainThread.execute {
            val t0 = System.nanoTime()
            try { r.run() } finally {
                val d = System.nanoTime() - t0
                mainBusy.addAndGet(d)
                mainLongest.accumulateAndGet(d) { a, b -> maxOf(a, b) }
            }
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, e -> uncaught += e })
    private val requests = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""

    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    private val beats by lazy { res("ch3_beats.txt").split("=====").map { it.trim() } }

    /** 写一拍约一千字，写整章约四千字。 */
    private fun answer(n: Int) = generateSequence { beats.joinToString("\n") }.take(n).joinToString("\n")

    /** 每块几个字。 */
    private val chunk = 3

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                requests.incrementAndGet()
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                val user = list.last { it.getString("role") == "user" }.getString("content")
                val afterTool = list.last().getString("role") == "tool"
                val canCall = body.has("tools") && body.optString("tool_choice") != "none"
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                runCatching {
                    ex.responseBody.use { out ->
                        fun send(o: Any) {
                            out.write("data: $o\n\n".toByteArray())
                            out.flush()
                        }
                        fun delta(d: JSONObject, finish: String? = null) = JSONObject().put("choices", JSONArray().put(
                            JSONObject().put("index", 0).put("delta", d).put("finish_reason", finish ?: JSONObject.NULL)))
                        fun stream(text: String) = text.chunked(chunk).forEach { send(delta(JSONObject().put("content", it))) }
                        when {
                            "【当前阶段：审读】" in user -> stream("""{"summary":["节拍齐全"],"missing":[],"issues":[]}""")
                            canCall && !afterTool && "【这次要写的】" in user -> {
                                // 先说半句再去查：调用方要把这半句清掉。
                                stream("我先查一下人物和前文。")
                                send(delta(JSONObject().put("tool_calls", JSONArray()
                                    .put(call(0, "read_doc", JSONObject().put("title", "人物档案")))
                                    .put(call(1, "search", JSONObject().put("query", "林月 渡口 老周")))
                                    .put(call(2, "read_chapter", JSONObject().put("chapter", "3"))))))
                                send(delta(JSONObject(), "tool_calls"))
                                send("[DONE]")
                                return@use
                            }
                            "这一章现在的稿子：" in user -> stream(answer(4))
                            Regex("只写第 \\d+ 拍").containsMatchIn(user) -> stream(answer(1))
                            else -> stream(answer(4))
                        }
                        send(delta(JSONObject(), "stop"))
                        send("[DONE]")
                    }
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        val port = server.address.port
        val client = AiClient { url -> URL("http://127.0.0.1:$port${url.path}").openConnection() as HttpURLConnection }
        val ai = AiAssistant(CoroutineScope(SupervisorJob() + Dispatchers.Default), null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))

        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        val volume = w.volumes[0].id
        // 设定包：照作者的项目包，人物档案、世界设定再加长到几万字。
        listOf(
            "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION, "章节审读提示词" to PackKind.INSTRUCTION,
            "章节修订提示词" to PackKind.INSTRUCTION, "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
            "第一卷细纲" to PackKind.VOLUME, "第二卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON,
            "人物档案" to PackKind.CHARACTER, "察觉表" to PackKind.AWARENESS, "伏笔表" to PackKind.FORESHADOW,
            "写作规范" to PackKind.STYLE, "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
        ).forEach { (name, kind) ->
            val text = res("pack/$name.md")
            store.addPackDoc(workId, kind, name, if (name == "世界设定") text + "\n\n" + text.replace("# ", "## 补记 ") else text)
        }
        // 四十章定稿正文，每章三四千字（用两章样章的段落轮换拼）。
        val paragraphs = (res("ch1.txt") + "\n" + res("ch2.txt")).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = w.chapters[0].id
        val ids = listOf(first) + (2..40).map { store.addChapter(workId, volume)!!.second.id }
        ids.forEachIndexed { i, id ->
            store.renameChapter(workId, id, "第${i + 1}章 渡口${i + 1}")
            val text = buildString {
                var k = i * 7
                while (length < 3_500) append(paragraphs[k++ % paragraphs.size]).append('\n')
            }.trim()
            store.saveText(workId, id, text, count = false)
            store.saveCard(workId, id, ChapterCard(facts = "林月和沈默在渡口找老周。", pov = "林月", hook = "河上有船过来。",
                summary = "第${i + 1}章：林月在渡口打听老周的下落，沈默帮她找线索。"))
            store.setStatus(workId, id, ChapterStatus.FINAL)
        }
        chapterId = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, chapterId, "第41章 空船")
        store.saveCard(workId, chapterId, ChapterCard(
            facts = "卯时林月到渡口，老周的船拴在桩上但船上没人；杜娘说昨夜顾长风带人来过；沈默赶来，两人在船舱夹板里找到另半张药方。",
            boundary = "本章不到白沙渡。", pov = "林月", countdown = "老周被带走后第一个白天。", hook = "河上传来漕帮的号子，有船朝渡口来。",
            beats = "1. 卯时林月到渡口，老周的船拴在桩上，船上没人（800字）\n2. 杜娘开门，说昨夜顾长风带人来过（900字）\n" +
                "3. 沈默赶来，和林月在船舱夹板里找到另半张药方（1000字）\n4. 林月决定去白沙渡，沈默说要跟着去（700字）",
        ))
        store.updateAssist(workId) { it.copy(temperature = 1.1f, thinking = false, aiCliche = true) }
        writing = WritingAi(store, ai, scope) { }
    }

    private fun call(i: Int, name: String, args: JSONObject) = JSONObject().put("index", i).put("id", "call_$i").put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        mainThread.shutdownNow()
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 120_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what；漏出的异常：$uncaught" }
            Thread.sleep(20)
        }
    }

    /** 跑一条路径，返回（主线程忙的毫秒、主线程最长一次的毫秒、状态更新次数）。 */
    private fun measure(label: String, updates: () -> Int, block: () -> Unit): Triple<Long, Long, Int> {
        mainBusy.set(0)
        mainLongest.set(0)
        val before = updates()
        val a0 = allocated()
        val t0 = System.currentTimeMillis()
        block()
        val r = Triple(mainBusy.get() / 1_000_000, mainLongest.get() / 1_000_000, updates() - before)
        println("[$label] 用时 ${System.currentTimeMillis() - t0} ms，主线程忙 ${r.first} ms，最长一次 ${r.second} ms，状态更新 ${r.third} 次，" +
            "共分配 ${(allocated() - a0) / 1_000_000} MB")
        return r
    }

    /** 所有活着的线程一共分配了多少字节（看 GC 压力）。 */
    private fun allocated(): Long {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return mx.getThreadAllocatedBytes(mx.allThreadIds).filter { it > 0 }.sum()
    }

    @Test
    fun everyProsePathSurvivesLongStreamsAndBigToolResults() {
        val draftUpdates = AtomicInteger()
        val inlineUpdates = AtomicInteger()
        writing.drafts.onEach { draftUpdates.incrementAndGet() }.launchIn(CoroutineScope(Dispatchers.Unconfined))
        writing.inline.onEach { inlineUpdates.incrementAndGet() }.launchIn(CoroutineScope(Dispatchers.Unconfined))
        // 编辑器里：光标前已经写了两拍、两万字的长章节。
        val body = (beats[0] + "\n" + beats[1] + "\n").repeat(8).trim()
        store.saveText(workId, chapterId, body, count = false)
        for (mode in InlineMode.entries) {
            measure("编辑器 $mode", { inlineUpdates.get() }) {
                writing.writeInline(workId, chapterId, body, body.length, mode, instruction = if (mode == InlineMode.CUSTOM) "多写对白" else "",
                    beat = if (mode == InlineMode.BEAT) 3 else null)
                Thread.sleep(200)
                waitFor("编辑器 $mode 写完") { writing.inline.value?.loading == false }
                val w = writing.inline.value!!
                assertEquals("编辑器 $mode：${w.error}", null, w.error)
                assertTrue(w.text.length > 500)
            }
        }
        measure("编辑器 三个方向", { inlineUpdates.get() }) {
            writing.writeInline(workId, chapterId, body, body.length, InlineMode.SCENE, variants = true)
            Thread.sleep(200)
            waitFor("三个方向写完") { writing.inline.value?.variants?.all { !it.loading } == true }
            writing.inline.value!!.variants.forEach { assertEquals(null, it.error) }
        }
        writing.discardInline()
        store.saveText(workId, chapterId, "", count = false)
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        // 逐拍写四拍、每拍上千块：界面最多每 150 毫秒刷新一次，不再一块一刷（改前每条路径一千三四百次）。
        val beatsRun = measure("逐拍写整章草稿", { draftUpdates.get() }) {
            writing.writeDraft(workId, chapterId)
            Thread.sleep(200)
            waitFor("逐拍写完") { writing.drafts.value[workId to chapterId]?.loading == false }
            val d = writing.drafts.value[workId to chapterId]!!
            assertEquals(null, d.error)
            assertTrue(d.text.length > 3_000)
        }
        val projectRun = measure("项目包流程", { draftUpdates.get() }) {
            writing.writeDraft(workId, chapterId, workflow = true)
            Thread.sleep(200)
            waitFor("项目包流程写完") { writing.drafts.value[workId to chapterId]?.loading == false }
            assertEquals(null, writing.drafts.value[workId to chapterId]!!.error)
        }
        // 没有节拍：整章一次写。
        store.saveCard(workId, chapterId, store.plannedCard(workId, chapterId).card.copy(beats = ""))
        val wholeRun = measure("整章草稿（无节拍）", { draftUpdates.get() }) {
            writing.writeDraft(workId, chapterId)
            Thread.sleep(200)
            waitFor("整章写完") { writing.drafts.value[workId to chapterId]?.loading == false }
            assertEquals(null, writing.drafts.value[workId to chapterId]!!.error)
        }
        assertEquals("漏出来的异常：$uncaught", emptyList<Throwable>(), uncaught.toList())
        listOf(beatsRun, projectRun, wholeRun).forEach { (_, _, updates) -> assertTrue("状态更新 $updates 次，流式没有节流", updates < 200) }
    }
}
