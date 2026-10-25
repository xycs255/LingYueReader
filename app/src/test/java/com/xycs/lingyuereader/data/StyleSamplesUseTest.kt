package com.xycs.lingyuereader.data

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.WritingAi
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections

/** 文风范例带不带：接着写、写本章、改稿带；挑错、读者点评、写作建议不带；只从当前章之前挑；本机模型的量。 */
class StyleSamplesUseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    /** 每次请求：（请求里的要求, 系统提示）。 */
    private val sent: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                val all = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                sent += all.last { it.getString("role") == "user" }.getString("content") to all.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                val user = sent.last().first
                val answer = if ("只找下面这几类问题" in user) """{"issues":[]}""" else "雨还在下。沈默推门进去。"
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop"))).toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡口")
        workId = w.id
        val volume = w.volumes.first().id
        ids += w.chapters.first().id
        repeat(3) { ids += store.addChapter(workId, volume)!!.second.id }
        // 第一章、第四章定稿（第四章在当前章之后，写第三章时不能拿）；第三章是要写的。
        store.renameChapter(workId, ids[0], "第一章 渡口")
        store.saveText(workId, ids[0], StyleSamplesTest.chapterText())
        store.setStatus(workId, ids[0], ChapterStatus.FINAL)
        store.renameChapter(workId, ids[3], "第四章 夜雨")
        store.saveText(workId, ids[3], StyleSamplesTest.chapterText("夜雨"))
        store.setStatus(workId, ids[3], ChapterStatus.FINAL)
        store.saveText(workId, ids[2], "沈默在渡口等船。\n")
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(
            AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"),
        )
        writing = WritingAi(store, ai, scope) { }
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

    /** 最近一次要求里带 [marker] 的请求的系统提示。 */
    private fun systemFor(marker: String): String = sent.last { marker in it.first }.second

    @Test
    fun writingEntriesCarrySamplesOthersDoNot() {
        val text = store.text(workId, ids[2])
        // AI 接着写。
        writing.writeInline(workId, ids[2], text, text.length, InlineMode.WORDS)
        waitFor("接着写") { writing.inline.value?.loading == false }
        val inline = systemFor("请从光标处接着往下写正文")
        assertTrue(inline.contains(StyleSamples.HEADER))
        assertTrue(inline.contains("第一章 渡口"))
        // 防剧透：当前章之后的第四章不拿。
        val block = inline.substring(inline.indexOf(StyleSamples.HEADER)).substringBefore("\n\n")
        assertTrue(block.contains("第一章 渡口"))
        assertFalse(block.contains("夜雨"))
        assertFalse(block.contains("第四章"))

        // 写本章草稿。
        writing.writeDraft(workId, ids[2])
        waitFor("写本章") { writing.drafts.value[workId to ids[2]]?.loading == false }
        assertTrue(systemFor("请写出这一章").contains(StyleSamples.HEADER))

        // 润色带；挑错、读者点评不带。
        fun run(task: WriteAiTask, marker: String): String {
            writing.run(workId, ids[2], text, task)
            waitFor(marker) { writing.state.value?.loading == false && sent.any { marker in it.first } }
            return systemFor(marker)
        }
        assertTrue(run(WriteAiTask.Edit(WriteAction.POLISH, "沈默在渡口等船。", 0, 8), "润色这段文字").contains(StyleSamples.HEADER))
        assertFalse(run(WriteAiTask.Edit(WriteAction.PROOFREAD, "沈默在渡口等船。", 0, 8), "只改错").contains(StyleSamples.HEADER))
        assertFalse(run(WriteAiTask.Review, "网文老读者").contains(StyleSamples.HEADER))

        // 同样的章再写一次：系统提示一字不差（前缀稳定）。
        val before = inline
        writing.writeInline(workId, ids[2], text, text.length, InlineMode.WORDS)
        waitFor("再接着写") { writing.inline.value?.loading == false && sent.count { "请从光标处接着往下写正文" in it.first } == 2 }
        assertEquals(before, systemFor("请从光标处接着往下写正文"))
        // 最近一次发给 AI 的内容里看得到范例。
        assertTrue(writing.lastRequest.value!!.system.contains(StyleSamples.HEADER))
    }

    @Test
    fun turnedOffOrFirstChapterCarriesNothing() = runBlocking {
        // 写第一章：前面没有章。
        assertTrue(writing.stylePick(workId, ids[0]).samples.isEmpty())
        // 设置页预览：按写下一章挑，两章定稿都算。
        val preview = writing.stylePick(workId, null, local = false)
        assertTrue(preview.samples.isNotEmpty())
        assertTrue(setOf(ids[0], ids[3]).containsAll(preview.samples.map { it.chapterId }))
        // 本机模型只带三分之一。
        val local = writing.stylePick(workId, ids[2], local = true)
        assertTrue(local.chars <= 500)
        // 关掉以后不带。
        store.updateAssist(workId) { it.copy(sampleLimit = 0) }
        writing.forget()
        assertTrue(writing.stylePick(workId, ids[2]).samples.isEmpty())
    }

    @Test
    fun cacheIsReusedUntilFinalChaptersChange() = runBlocking {
        val first = writing.stylePick(workId, ids[2], local = false)
        assertTrue(first.samples.isNotEmpty())
        assertTrue(store.sampleCache(workId)!!.isNotEmpty())
        // 缓存里的结果直接用（这里把缓存改了，读出来就是改过的，说明没重挑）。
        val sig = StyleSamples.decodeCache(store.sampleCache(workId)).single().first
        store.saveSampleCache(workId, StyleSamples.encodeCache(listOf(sig to SamplePick(first.samples.take(1), sources = 1))))
        assertEquals(1, writing.stylePick(workId, ids[2], local = false).samples.size)
        // 定稿章改了：重挑。
        store.setStatus(workId, ids[0], ChapterStatus.REVISION, reason = "改错字")
        store.saveText(workId, ids[0], StyleSamplesTest.chapterText("改"))
        store.setStatus(workId, ids[0], ChapterStatus.FINAL)
        assertTrue(writing.stylePick(workId, ids[2], local = false).samples.size > 1)
        // 没有定稿章、关了退回：不带。
        store.setStatus(workId, ids[0], ChapterStatus.REVISION, reason = "再改")
        store.updateAssist(workId) { it.copy(sampleFallback = false) }
        writing.forget()
        assertTrue(writing.stylePick(workId, ids[2], local = false).samples.isEmpty())
        // 开着退回：用字数最多的章。
        store.updateAssist(workId) { it.copy(sampleFallback = true) }
        writing.forget()
        assertTrue(writing.stylePick(workId, ids[2], local = false).fallback)
    }
}
