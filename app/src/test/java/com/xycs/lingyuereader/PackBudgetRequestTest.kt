package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackBudget
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
 * 设定包页“AI 每次带多少”的数字要和写正文真正发出去的对得上：用 [ProseRequestTest] 那套虚构作品《渡灯》的设定包，
 * 再加一份几十万字的大设定，在编辑器里接着写一次，拿假服务器收到的系统提示逐段核对。
 */
class PackBudgetRequestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val systems: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private lateinit var ai: AiAssistant
    private var workId = ""
    private var chapterId = ""

    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                systems += list.filter { it.getString("role") == "system" }.joinToString("\n") { it.getString("content") }
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", "林月把缆绳解开，船慢慢离了岸。")).put("finish_reason", "stop")))
                    .toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        val port = server.address.port
        val client = AiClient { url -> URL("http://127.0.0.1:$port${url.path}").openConnection() as HttpURLConnection }
        ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        val volume = w.volumes[0].id
        store.renameChapter(workId, w.chapters[0].id, "第1章 雾渡")
        val ch2 = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, ch2, "第2章 茶钱")
        chapterId = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, chapterId, "第3章 空船")
        listOf(
            "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION, "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
            "第一卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON, "人物档案" to PackKind.CHARACTER, "察觉表" to PackKind.AWARENESS,
            "伏笔表" to PackKind.FORESHADOW, "写作规范" to PackKind.STYLE, "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
        ).forEach { (name, kind) -> store.addPackDoc(workId, kind, name, res("pack/$name.md")) }
        store.saveText(workId, w.chapters[0].id, res("ch1.txt").trim(), count = false)
        store.saveText(workId, ch2, res("ch2.txt").trim(), count = false)
        store.saveCard(workId, chapterId, ChapterCard(
            facts = "卯时林月到渡口，老周的船拴在桩上但船上没人；沈默赶来，两人在船舱夹板里找到另半张药方。",
            boundary = "本章不到白沙渡。", pov = "林月", hook = "河上传来漕帮的号子。",
        ))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    /** 几十万字的虚构设定：每节写到林月、渡口，有的节很长（本章资料里会被截短）。 */
    private fun bigCanon(sections: Int): String = buildString {
        repeat(sections) { i ->
            append("## 渡口北岸第").append(i + 1).append("处\n")
            val line = "林月记得渡口北岸第${i + 1}处有一间茶棚，杜娘每天卯时开门，老周的船从这里出发去对岸。"
            repeat(if (i % 7 == 0) 60 else 8) { append(line) }
            append("\n\n")
        }
    }

    /** 在编辑器里从第 3 章正文末尾接着写一次，返回这次写正文的系统提示。 */
    private fun writeOnce(): String {
        val text = res("ch1.txt").trim().take(300)
        store.saveText(workId, chapterId, text, count = false)
        systems.clear()
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.TO_END)
        val until = System.currentTimeMillis() + 60_000
        while (writing.inline.value?.loading != false) {
            check(System.currentTimeMillis() < until) { "等不到写完" }
            Thread.sleep(30)
        }
        return systems.single { "【作者的写作规范】" in it }
    }

    /** 从发出去的系统提示里取出作者说明、写作规范、本章资料三段（[WritePrompts.proseSystem] 拼的格式）。 */
    private fun sections(system: String): Triple<String, String, String> {
        val authorHead = "\n\n【作者写给 AI 的说明】\n"
        val normsHead = "\n\n【作者的写作规范】（照这个写；和后面的写法要求说得不一样时，以这里为准）\n"
        val materialHead = "\n\n写第 3 章用得上的资料"
        val a = system.indexOf(authorHead)
        val n = system.indexOf(normsHead)
        val m = system.indexOf(materialHead)
        val end = system.indexOf("\n\n细节拿不准", m)
        assertTrue("系统提示的格式变了：$a $n $m $end", a in 0 until n && n < m && m < end)
        return Triple(system.substring(a + authorHead.length, n), system.substring(n + normsHead.length, m), system.substring(m + 2, end))
    }

    private fun budget(): PackBudget = runBlocking { writing.packBudget(workId) }

    private fun assertMatches(b: PackBudget, system: String) {
        val (author, norms, material) = sections(system)
        assertEquals("按最近写的这一章算", "第3章 空船", b.chapterTitle)
        assertEquals("本章资料的字数", material.length, b.briefChars)
        assertEquals("写正文带上的资料总字数", author.length + norms.length + material.length, b.proseChars)
        assertEquals("工作说明", author.length, b.slices.single { it.kind == PackKind.INSTRUCTION }.chars)
        // 检查规则在写作规范那一段里（去掉整章字数），它那一段的字数就是发出去的那一节。
        val rulesAt = norms.indexOf("【检查规则】")
        assertTrue(rulesAt >= 0)
        assertEquals("检查规则", norms.length - rulesAt, b.slices.single { it.kind == PackKind.RULES }.chars)
        assertEquals("写作规范与称谓语气", rulesAt, b.slices.filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE }.sumOf { it.chars })
    }

    @Test
    fun theBarMatchesWhatIsSentForTheFixturePack() {
        val system = writeOnce()
        val b = budget()
        assertMatches(b, system)
        // 小设定包：知识库放得下，没有截掉的。
        assertTrue(b.knowledgeChars in 1..b.knowledgeLimit)
        assertTrue(b.misses.toString(), b.misses.none { it.whole })
    }

    @Test
    fun aBigPackStillMatchesAndShowsWhatDoesNotFit() {
        // 两份大设定，一共三十多万字，都标常用：知识库放不下。
        store.addPackDoc(workId, PackKind.CANON, "北岸地理", bigCanon(260))
        store.addPackDoc(workId, PackKind.REFERENCE, "渡口旧事", bigCanon(200), always = true)
        val total = store.pack(workId).sumOf { store.packText(workId, it.id).length }
        assertTrue("设定包 $total 字", total >= 300_000)

        val system = writeOnce()
        // 量的是真正从头算的一次：放掉记着的设定包（切块、二元组、挑好的资料），换一个没算过的实例。
        store.trimMemory()
        writing = WritingAi(store, ai, scope) { }
        val started = System.nanoTime()
        val b = budget()
        val cold = (System.nanoTime() - started) / 1_000_000
        assertMatches(b, system)

        // 放不下的：大的常用文档改成按需查，标出来、说明为什么；本章资料里截短的段落也列出来。
        val whole = b.misses.filter { it.whole }
        assertTrue(b.misses.toString(), whole.any { it.title == "北岸地理" } || whole.any { it.title == "渡口旧事" })
        whole.forEach { assertTrue(it.reason, "知识库上限" in it.reason && it.chars > 100_000) }
        assertTrue(b.knowledgeChars <= b.knowledgeLimit)
        // 截短的段落（本章资料到了上限，最后一段只带前面一截）另在 PackBudgetsTest 里核对。
        b.misses.filter { !it.whole }.forEach { m -> assertTrue(m.reason, "本章资料上限" in m.reason) }

        // 设定包刚读进来、挑资料都要算的一次，几十万字也在 3 秒内（这台机器上实测几百毫秒）；同一版再算直接用记着的。
        println("设定包 $total 字，从头算一次 $cold 毫秒")
        assertTrue("算一次 $cold 毫秒", cold < 3_000)
        val again = System.nanoTime()
        assertEquals(b, budget())
        assertTrue("再算一次", (System.nanoTime() - again) / 1_000_000 < 50)

        // 设定包一改就重算：新加一份写作规范，写作规范那段变长。
        val before = b.slices.firstOrNull { it.kind == PackKind.STYLE }?.chars ?: 0
        store.addPackDoc(workId, PackKind.STYLE, "补充规范", "对白里不用“不由得”。")
        val after = budget()
        assertTrue((after.slices.firstOrNull { it.kind == PackKind.STYLE }?.chars ?: 0) > before)
        assertMatches(after, writeOnce())
    }
}
