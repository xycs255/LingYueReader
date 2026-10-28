package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * “这章写完了”不经界面的部分：整理和摘要合成一次请求；章太长分了段时摘要另发一次；只要摘要时只发摘要请求；
 * 只勾了定稿时不发请求，直接到确认，“完成收尾”就是定稿。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ChapterFinishFlowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val writingDir get() = File(app.filesDir, "writing")
    private val store get() = WorkStore(writingDir)
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val models = mutableListOf<AppViewModel>()
    private lateinit var workId: String
    private lateinit var chapterId: String
    private val text = "渡口起了雾。\n杜娘把茶碗推给林月，说老周昨夜里走了。\n林月这才知道沈默是朝廷的卧底。"
    private val combined = "林月在渡口茶馆从杜娘口中得知老周昨夜死了，又知道了沈默是朝廷的卧底。"
    private val alone = "林月到渡口，听说老周走了，识破了沈默的身份。"

    /** 收到的请求：最后那句用户的话。 */
    private val asked = CopyOnWriteArrayList<String>()

    private fun isSync(user: String) = "作者写完了这一章，请整理它" in user
    private fun isSummary(user: String) = user.trimEnd().endsWith("只输出摘要本身。")

    @Before
    fun setUp() {
        File(app.filesDir, "books").deleteRecursively()
        File(app.filesDir, "books").mkdirs()
        File(app.filesDir, "books/index.json").writeText("[]")
        writingDir.deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).edit().clear().commit()
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += user
                val answer = when {
                    isSync(user) -> JSONObject()
                        .put("new", JSONArray()).put("conflicts", JSONArray())
                        .put("events", JSONArray().put(JSONObject().put("what", "老周昨夜死了").put("quote", "说老周昨夜里走了")))
                        // 要了摘要才回摘要（照提示词里写的格式）。
                        .apply { if ("\"summary\"" in user) put("summary", combined) }
                        .toString()
                    isSummary(user) -> alone
                    else -> "好的。"
                }
                val bytes = JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop"))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            this.executor = this@ChapterFinishFlowTest.executor
            start()
        }
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
            .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "synthetic-model").commit()
        val work = store.create("长夜")
        workId = work.id
        chapterId = work.chapters.single().id
        store.renameChapter(workId, chapterId, "第十二章 渡口")
        store.saveText(workId, chapterId, text, count = false)
        store.addPackDoc(workId, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |")
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, condition())
    }

    private fun model(): AppViewModel = AppViewModel(app).also { vm ->
        models += vm
        waitUntil("写作数据尚未载入") { vm.writing.loaded.value }
    }

    /** 打开收尾、按 [options] 勾好、开始，等到确认页。 */
    private fun runToConfirm(vm: AppViewModel, options: FinishOptions): FinishRun {
        val ai = vm.writingAi
        ai.openFinish(workId, chapterId)
        waitUntil("收尾没打开") { ai.finish.value?.stage == FinishStage.SETUP }
        ai.setFinishOptions(options)
        ai.startFinish()
        waitUntil("没走到确认") { ai.finish.value?.stage == FinishStage.CONFIRM }
        return ai.finish.value!!
    }

    @Test
    fun syncAndSummaryGoOutAsOneRequest() {
        val vm = model()
        val run = runToConfirm(vm, FinishOptions(sync = true, summary = true))
        assertEquals("只发一次请求", 1, asked.size)
        assertTrue(isSync(asked.single()))
        assertTrue("9. summary（本章摘要）" in asked.single())
        // 记在整理名下（请求名是“整理本章”）。
        assertEquals("整理本章", vm.writingAi.lastRequest.value?.label)
        assertEquals(StepState.DONE, run.sync)
        assertEquals(StepState.DONE, run.summary)
        assertEquals(combined, run.draft)
        assertEquals(listOf("老周昨夜死了"), run.session!!.items.map { it.title })
        // 勾选项按作品记住了。
        waitUntil("勾选项没存") { store.assist(workId).finish == FinishOptions(sync = true, summary = true) }
    }

    @Test
    fun aLongChapterGetsItsSummaryFromASeparateRequest() {
        val long = text + "\n" + List(1_100) { "雾气贴着河面慢慢地散开，船桨一下一下地响。$it" }.joinToString("\n")
        store.saveText(workId, chapterId, long, count = false)
        val parts = WritePrompts.syncParts(long).size
        assertTrue(parts > 1)
        val vm = model()
        val run = runToConfirm(vm, FinishOptions(sync = true, summary = true))
        val syncs = asked.filter(::isSync)
        val summaries = asked.filter(::isSummary)
        assertEquals(parts, syncs.size)
        // 分段整理的每一段都不要摘要（最后一段概括不了全章），摘要另发一次，发的是整章。
        assertTrue(syncs.none { "\"summary\"" in it })
        assertEquals(1, summaries.size)
        assertTrue("雾气贴着河面慢慢地散开，船桨一下一下地响。1099" in summaries.single())
        assertEquals(parts + 1, asked.size)
        assertEquals(alone, run.draft)
    }

    @Test
    fun onlyTheSummarySendsOnlyTheSummaryRequest() {
        val vm = model()
        val run = runToConfirm(vm, FinishOptions(sync = false, summary = true))
        assertEquals(1, asked.size)
        assertTrue(isSummary(asked.single()))
        assertEquals("章节摘要", vm.writingAi.lastRequest.value?.label)
        assertEquals(StepState.SKIPPED, run.sync)
        assertEquals(alone, run.draft)
        assertEquals(null, run.session)
    }

    @Test
    fun onlyFinalizingGoesStraightToConfirmAndFinalizes() {
        val vm = model()
        val run = runToConfirm(vm, FinishOptions(sync = false, summary = false, review = false))
        assertTrue(asked.isEmpty())
        assertEquals(listOf(StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED), listOf(run.review, run.sync, run.summary))
        var said = ""
        vm.writingAi.applyFinish(finalize = { w, c -> vm.writing.setStatusNow(w, c, ChapterStatus.FINAL) }) { said = it }
        waitUntil("没定稿") { vm.writingAi.finish.value?.stage == FinishStage.DONE }
        assertEquals("这一章定稿了", said)
        assertTrue(store.get(workId)!!.chapter(chapterId)!!.locked)
        assertTrue(store.ledger(workId).isEmpty())
        assertEquals("", store.card(workId, chapterId).summary)
    }

    @Test
    fun anExistingSummaryIsKeptUnlessTheAuthorPicksTheNewOne() {
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月到渡口", summary = "作者写的摘要"))
        val vm = model()
        val run = runToConfirm(vm, FinishOptions(sync = true, summary = true))
        assertEquals("作者写的摘要", run.oldSummary)
        assertTrue("默认留旧的", run.keepOld)
        vm.writingAi.applyFinish(finalize = { w, c -> vm.writing.setStatusNow(w, c, ChapterStatus.FINAL) })
        waitUntil("没收完") { vm.writingAi.finish.value?.stage == FinishStage.DONE }
        // 留旧的：摘要不动，别的项也不动；整理照写、定稿照做。
        assertEquals("作者写的摘要", store.card(workId, chapterId).summary)
        assertEquals("林月到渡口", store.card(workId, chapterId).facts)
        assertEquals(listOf(LedgerKind.EVENT), store.ledger(workId).map { it.kind })
        assertTrue(store.get(workId)!!.chapter(chapterId)!!.locked)
        assertFalse(store.assist(workId).synced[chapterId] == null)
    }
}
