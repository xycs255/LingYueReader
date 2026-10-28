package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.DraftBeats
import com.xycs.lingyuereader.data.LargePack
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.writingTask
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本章任务同一时间只编一份：参数相同的一起等（单飞），没人等了就停；逐拍写、审读、修订用同一份；
 * 编的时候内存不够变成一句提示，不闪退。真机上的闪退就是几次编译叠在一起、OutOfMemoryError 又漏到没兜底的地方。
 */
class WritingTaskFlightTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped += e })
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    private val beatText = (1..4).associateWith { "第${it}拍：林月在渡口等老周。" }

    @Before
    fun setUp() {
        CrashLog.use(tmp.root.resolve("crash-log.json"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                prompts += user
                val beat = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.toInt()
                val answer = when {
                    "【当前阶段：审读】" in user -> """{"summary":["节拍齐全"],"missing":[],"issues":[]}"""
                    beat != null -> beatText.getValue(beat)
                    else -> "沈默把灯笼挂上船头，林月跟着上了船。"
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
        // 篇幅定死在 3000 字：本机检查不过，审读后照原来修订一轮。
        store.addPackDoc(workId, PackKind.RULES, "检查规则", "字数：3000-3000\n")
        store.addPackDoc(workId, PackKind.CANON, "世界设定", LargePack.res("pack/世界设定.md"))
        store.saveCard(workId, chapterId, ChapterCard(
            facts = "林月到渡口等老周，沈默来了", hook = "船头的灯灭了",
            beats = "1. 林月到渡口\n2. 杜娘送饭\n3. 沈默来了\n4. 两人上船",
        ))
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        CrashLog.use(null)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private val key get() = workId to chapterId
    private fun draft() = writing.drafts.value[key]

    /** 换成数次数、看同时有几份在编的编译。 */
    private class Counting(private val store: WorkStore, private val hold: CountDownLatch? = null) {
        val calls = AtomicInteger()
        val running = AtomicInteger()
        val most = AtomicInteger()
        val stopped = AtomicInteger()
        fun compile(w: String, c: String, i: String, t: String, checkpoint: () -> Unit): ChapterWritingTask? {
            calls.incrementAndGet()
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                if (hold != null) while (!hold.await(5, TimeUnit.MILLISECONDS)) {
                    try { checkpoint() } catch (e: Throwable) { stopped.incrementAndGet(); throw e }
                }
                return store.writingTask(w, c, i, t, checkpoint)
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test fun sameArgumentsShareOneCompile() = runBlocking {
        val release = CountDownLatch(1)
        val counting = Counting(store, release)
        writing.compiler = counting::compile
        val waiting = (1..5).map { async(Dispatchers.Default) { writing.writingTask(workId, chapterId, "对白更生活一些") } }
        waitFor("开始编") { counting.calls.get() == 1 }
        Thread.sleep(100)
        release.countDown()
        val tasks = waiting.awaitAll()
        assertEquals("五处同时要同一份，只编一次", 1, counting.calls.get())
        tasks.forEach { assertSame(tasks[0], it) }
        // 编完就不再挂着：下一次照常重编（设定包没变，用缓存，很快）。
        writing.writingTask(workId, chapterId, "对白更生活一些")
        assertEquals(2, counting.calls.get())
    }

    @Test fun anAbandonedCompileStopsAndOnlyOneRunsAtATime() = runBlocking {
        val release = CountDownLatch(1)
        val counting = Counting(store, release)
        writing.compiler = counting::compile
        // 作者在草稿页上连着改要求：旧的那次没人等了，就停在下一个检查点。
        val old = launch(Dispatchers.Default) { writing.writingTask(workId, chapterId, "旧要求") }
        waitFor("旧的开始编") { counting.calls.get() == 1 }
        val queued = (1..3).map { n -> async(Dispatchers.Default) { writing.writingTask(workId, chapterId, "要求 $n") } }
        Thread.sleep(100)
        assertEquals("排队的不同时开编", 1, counting.calls.get())
        old.cancelAndJoin()
        waitFor("旧的停下") { counting.stopped.get() == 1 }
        release.countDown()
        val done = queued.awaitAll()
        assertEquals(listOf("要求 1", "要求 2", "要求 3"), done.map { it!!.instruction })
        assertEquals("同一时间只编一份", 1, counting.most.get())
        assertTrue(escaped.isEmpty())
    }

    @Test fun beatsReviewAndRevisionUseOneCompiledTask() {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        val compiles = store.packCache.compiles.get()
        val reads = store.packCache.reads.get()
        writing.writeDraft(workId, chapterId, workflow = true)
        waitFor("逐拍写完、审读、修订") { draft()?.loading == false }
        assertNull(draft()?.error)
        assertEquals(DraftBeats(4, 4), store.draftContent(workId, chapterId).beats)
        assertTrue("审读过", prompts.any { "【当前阶段：审读】" in it })
        assertTrue("修订过", draft()?.initialText != null)
        assertEquals(4, prompts.count { Regex("只写第 \\d 拍").containsMatchIn(it) })
        // 开头编一次；写完再编一次核对资料有没有在写的途中变（变了旧审读作废）。四拍、审读、修订都用开头那份。
        assertEquals(2, store.packCache.compiles.get() - compiles)
        assertTrue("设定包没变，最多读一次", store.packCache.reads.get() - reads <= 1)
        assertTrue(escaped.isEmpty())
    }

    @Test fun runningOutOfMemoryWhileCompilingIsAMessageNotACrash() {
        writing.compiler = { _, _, _, _, _ -> throw OutOfMemoryError("Failed to allocate a 48 byte allocation") }
        try {
            runBlocking { writing.writingTask(workId, chapterId) }
            fail("应该报错")
        } catch (e: TaskCompileError) {
            assertEquals(WritingAi.TASK_TOO_LARGE, e.message)
            // 协程调试模式会把异常复制一层（原来的放进 cause），所以顺着 cause 找。
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is OutOfMemoryError })
        }
        // 写本章（项目包流程和普通的）：停下、报这一句，不漏到协程外面。
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        for (workflow in listOf(true, false)) {
            writing.writeDraft(workId, chapterId, workflow = workflow)
            Thread.sleep(50)
            waitFor("停下") { draft()?.loading == false }
            val shown = "${draft()?.error} ${draft()?.notice}"
            assertTrue(shown, WritingAi.TASK_TOO_LARGE in shown)
        }
        // 采纳前的核对、审读用的任务：也是普通异常，接得住。
        val blocked = runCatching { runBlocking { writing.draftBlocked(workId, chapterId) } }
        assertTrue(blocked.exceptionOrNull().let { it == null || it is TaskCompileError })
        assertTrue("没有漏出去的：$escaped", escaped.isEmpty())
        assertTrue("记进了闪退记录", CrashLog.entries().any { !it.fatal && "OutOfMemoryError" in it.trace })
    }

    @Test fun otherErrorsWhileCompilingAreCaughtToo() {
        writing.compiler = { _, _, _, _, _ -> throw StackOverflowError() }
        val e = runCatching { runBlocking { writing.writingTask(workId, chapterId) } }.exceptionOrNull()
        assertTrue("$e", e is TaskCompileError && "StackOverflowError" in e.message.orEmpty())
        val cancelled = AtomicBoolean(false)
        // 取消照常是取消，不算出错。
        writing.compiler = { _, _, _, _, check -> while (true) { try { check() } catch (c: Throwable) { cancelled.set(true); throw c }; Thread.sleep(5) }; null }
        runBlocking {
            val job = launch(Dispatchers.Default) { writing.writingTask(workId, chapterId) }
            Thread.sleep(50)
            job.cancelAndJoin()
        }
        waitFor("停下") { cancelled.get() }
        assertTrue(escaped.isEmpty())
    }
}
