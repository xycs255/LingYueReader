package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.DraftBeats
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ProseBudget
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 本机收住：假 AI 流式写出远超上限的文字时，在句末收住、断开请求，结果是完整句子并带说明；没超的不受影响；
 * 逐拍写时某一拍被收住，后面的拍照常写；全章累计超过全章上限的 1.3 倍时后面的拍不再写。
 */
class ProseCutoffFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 哪些请求写个没完（按请求正文判断）。 */
    @Volatile private var endless: (String) -> Boolean = { false }
    /** 写个没完的请求里，客户端中途断开的（服务端往下写时出错）。 */
    private val aborted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val sentence = "林月走过渡口，看见船不见了。"
    private fun short(user: String) = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.let { "第${it}拍：杜娘开了门。老周走了。" } ?: "沈默到了。"

    private fun start(rules: String, card: ChapterCard) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                prompts += user
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                fun delta(text: String?, finish: String? = null) = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
                    .put("delta", JSONObject().apply { if (text != null) put("content", text) }).put("finish_reason", finish ?: JSONObject.NULL)))
                try {
                    ex.responseBody.use { out ->
                        fun send(o: Any) {
                            out.write("data: $o\n\n".toByteArray())
                            out.flush()
                        }
                        if (endless(user)) {
                            // 两万多字，一小块一小块地写：客户端收住、断开以后，往下写会出错。
                            sentence.repeat(1_500).chunked(9).forEach {
                                send(delta(it))
                                Thread.sleep(1)
                            }
                        } else short(user).chunked(4).forEach { send(delta(it)) }
                        send(delta(null, "stop"))
                        send("[DONE]")
                    }
                } catch (e: IOException) {
                    if (endless(user)) aborted += user
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡口")
        workId = w.id
        chapterId = w.chapters[0].id
        store.addPackDoc(workId, PackKind.RULES, "检查规则", rules)
        store.saveCard(workId, chapterId, card)
        val ai = AiAssistant(scope, null, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "qwen3:8b"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private val key get() = workId to chapterId
    private fun draft() = writing.drafts.value[key]

    private fun writeDraft() {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { draft() != null }
        writing.writeDraft(workId, chapterId)
        waitFor("开始写") { draft()?.loading == true }
        waitFor("写完") { draft()?.loading == false }
    }

    @Test
    fun aLongBeatIsCutAtASentenceEndAndTheNextBeatsStillGetWritten() {
        start("字数：3500-6000\n", ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信",
            beats = "1. 林月到渡口（400字）\n2. 杜娘开门，说老周走了（400字）\n3. 老周的信（400字）"))
        endless = { "只写第 2 拍" in it }
        writeDraft()
        val d = draft()!!
        assertNull(d.error)
        assertEquals(DraftBeats(3, 3), d.beats)
        val parts = d.text.split("\n")
        assertEquals(3, parts.size)
        // 没超的第 1、3 拍原样；第 3 拍照常接着写了。
        assertEquals("第1拍：杜娘开了门。老周走了。", parts[0])
        assertEquals("第3拍：杜娘开了门。老周走了。", parts[2])
        // 第 2 拍：过了上限（500 字）的 1.3 倍以后，在下一个句末收住，是完整的句子。
        val cap = ProseBudget.of(400).cutoff
        val second = WordCount.of(parts[1])
        assertTrue("第 2 拍 $second 字", second in (cap + 1)..(cap + sentence.length))
        assertTrue(parts[1].endsWith("。"))
        assertEquals("", parts[1].replace(sentence, ""))
        assertEquals("第 2 拍写长了，在 $second 字处收住了", d.notice)
        // 请求被取消：服务端往下写时连接已经断了。
        waitFor("服务端发现断开") { aborted.any { "只写第 2 拍" in it } }
        assertEquals(3, prompts.count { Regex("只写第 \\d 拍").containsMatchIn(it) })
        // 全章在上限附近，没有写成两万字。
        assertTrue(WordCount.of(d.text) < 6_000)
    }

    @Test
    fun theWholeChapterRunningPastItsCeilingStopsTheLaterBeats() {
        // 作者自己写的每拍篇幅加起来远超全章（不改作者的），但全章累计到上限的 1.3 倍就在句末收住，后面的拍不再写。
        start("字数：1000-2000\n", ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信",
            beats = "1. 林月到渡口（2000字）\n2. 杜娘开门（2000字）\n3. 沈默赶来（2000字）\n4. 老周的信（2000字）"))
        endless = { "只写第" in it }
        writeDraft()
        val d = draft()!!
        assertNull(d.error)
        assertEquals(DraftBeats(1, 4), d.beats)
        val words = WordCount.of(d.text)
        assertTrue("全章 $words 字", words in 2_601..(2_600 + sentence.length))
        assertTrue(d.text.endsWith("。"))
        assertTrue(d.notice!!, "第 2—4 拍没有再写" in d.notice!!)
        assertEquals(1, prompts.count { "只写第" in it })
    }

    @Test
    fun aWholeChapterWithoutBeatsIsCutToo() {
        start("字数：1000-2000\n", ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信"))
        endless = { "【这次要写的】" in it }
        writeDraft()
        val d = draft()!!
        assertNull(d.error)
        val words = WordCount.of(d.text)
        assertTrue("全章 $words 字", words in 2_601..(2_600 + sentence.length))
        assertEquals("这一章写长了，在 $words 字处收住了", d.notice)
        waitFor("服务端发现断开") { aborted.isNotEmpty() }
    }

    @Test
    fun aChapterWithinItsLengthIsLeftAlone() {
        start("字数：1000-2000\n", ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信"))
        writeDraft()
        assertEquals("沈默到了。", draft()?.text)
        assertNull(draft()?.notice)
        assertTrue(aborted.isEmpty())
    }

    @Test
    fun theEditorCutsOneBeatToo() {
        start("字数：3500-6000\n", ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信",
            beats = "1. 林月到渡口（400字）\n2. 杜娘开门，说老周走了（400字）\n3. 老周的信（400字）"))
        endless = { "只写第 2 拍" in it }
        val text = "林月到了渡口。"
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.BEAT, beat = 2)
        waitFor("写完这一拍") { writing.inline.value?.loading == false }
        val w = writing.inline.value!!
        assertNull(w.error)
        val written = w.text.removePrefix("\n")
        val words = WordCount.of(written)
        val cap = ProseBudget.of(400).cutoff
        assertTrue("这一拍 $words 字", words in (cap + 1)..(cap + sentence.length))
        assertTrue(written.endsWith("。"))
        assertEquals("这一拍写长了，在 $words 字处收住了", w.note)
        waitFor("服务端发现断开") { aborted.isNotEmpty() }
    }
}
