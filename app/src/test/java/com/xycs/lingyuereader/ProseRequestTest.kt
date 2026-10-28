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
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 写正文的真实请求（虚构作品《渡灯》，设定包 15 份、3.4 万字，结构照作者的项目包；fixture 在 test/resources/prose-fixture）：
 * 一次请求的篇幅有上限，不带后面章节的细纲、后面卷的细纲和全书结局，任务卡在最后、文风范例在它前面，
 * 修订也带写正文的温度。防止以后又把整份知识库、整份任务包塞回写正文的请求里。
 */
class ProseRequestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 一次写正文的请求（系统提示加请求）最多多少字。改前是 3 万到 4.2 万字。 */
    private val limit = 15_000

    private data class Sent(val system: String, val user: String, val body: JSONObject)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: HttpServer
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""

    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    private val beats by lazy { res("ch3_beats.txt").split("=====").map { it.trim() } }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val msgs = body.getJSONArray("messages")
                val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                val system = list.filter { it.getString("role") == "system" }.joinToString("\n") { it.getString("content") }
                val user = list.last { it.getString("role") == "user" }.getString("content")
                sent += Sent(system, user, body)
                val beat = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.toInt()
                val answer = when {
                    "【当前阶段：审读】" in user -> """{"summary":["节拍齐全"],"missing":[],"issues":[]}"""
                    "这一章现在的稿子：" in user -> (beats + beats).joinToString("\n")
                    beat != null -> beats[beat - 1]
                    else -> beats[2] + "\n" + beats[3]
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
        val port = server.address.port
        val client = AiClient { url -> URL("http://127.0.0.1:$port${url.path}").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))

        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        val volume = w.volumes[0].id
        val ch1 = w.chapters[0].id
        store.renameChapter(workId, ch1, "第1章 雾渡")
        val ch2 = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, ch2, "第2章 茶钱")
        chapterId = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, chapterId, "第3章 空船")
        listOf(
            "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION, "章节审读提示词" to PackKind.INSTRUCTION,
            "章节修订提示词" to PackKind.INSTRUCTION, "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
            "第一卷细纲" to PackKind.VOLUME, "第二卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON,
            "人物档案" to PackKind.CHARACTER, "察觉表" to PackKind.AWARENESS, "伏笔表" to PackKind.FORESHADOW,
            "写作规范" to PackKind.STYLE, "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
        ).forEach { (name, kind) -> store.addPackDoc(workId, kind, name, res("pack/$name.md")) }
        store.saveText(workId, ch1, res("ch1.txt").trim(), count = false)
        store.saveText(workId, ch2, res("ch2.txt").trim(), count = false)
        store.saveCard(workId, ch1, ChapterCard(facts = "林月到渡口问父亲消息，在枣树杈里找到半张药方。", pov = "林月", hook = "药方末行“交周”后面被撕掉。",
            summary = "林月到渡口打听失踪的父亲，老周的船不在。林月回家打枣，在枣树杈里找到父亲藏的半张药方，末行写“交周”。"))
        store.saveCard(workId, ch2, ChapterCard(facts = "杜娘转述老周约林月卯时渡口船上见。", pov = "林月", hook = "窗下脚步声，渡口桩边拴着一条船。",
            summary = "顾长风在茶馆问林守仁去向。杜娘交出灯笼提杆，转述老周的话：卯时到渡口，他在船上等。窗下有脚步声，渡口桩边拴着一条船。"))
        store.setStatus(workId, ch1, ChapterStatus.FINAL)
        store.setStatus(workId, ch2, ChapterStatus.FINAL)
        store.saveCard(workId, chapterId, ChapterCard(
            facts = "卯时林月到渡口，老周的船拴在桩上但船上没人；杜娘说昨夜顾长风带人来过，老周跟他们走了，走前留话“东西在船上”；沈默赶来，两人在船舱夹板里找到另半张药方，后半写着“交周，送白沙渡”；林月决定去白沙渡漕帮总舵，沈默拦不住，说要跟着。",
            boundary = "本章不到白沙渡；不揭沈默身份；老周生死不交代。", pov = "林月", countdown = "老周被带走后第一个白天。",
            hook = "河上传来漕帮的号子，有船朝渡口来。", threads = "药方线、老周线。",
            beats = "1. 卯时林月到渡口，老周的船拴在桩上，船上没人，烟锅还搁在船头（800字）\n" +
                "2. 杜娘开门，说昨夜顾长风带人来过，老周跟他们走了，走前留话“东西在船上”（900字）\n" +
                "3. 沈默赶来，和林月在船舱夹板里找到另半张药方，后半写着“交周，送白沙渡”（1000字）\n" +
                "4. 林月决定去白沙渡漕帮总舵，沈默拦不住，说要跟着去；章末河上传来漕帮的号子（700字）",
        ))
        store.updateAssist(workId) { it.copy(temperature = 1.3f, thinking = false, banned = listOf("不由得", "倒吸一口凉气")) }
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(30)
        }
    }

    /** 写正文的请求：用户消息里有任务卡。 */
    private fun prose() = sent.filter { WritePrompts.CARD_HEADER in it.user }

    /** 不该出现在写第 3 章的请求里的：后面章节的细纲、第二卷的细纲、全书结局、后文里的事。 */
    private val spoilers = listOf(
        "老周在黑水湾烧掉药方", "顾北山在船上套出林月的腰牌", "白先生在黑水湾换走账本",
        "沈默完成卧底任务", "为保女儿自愿入狱", "第二卷生死不明", "全书最后的谜底", "沈默是朝廷暗探；消息来源",
    )

    private fun check(s: Sent, name: String) {
        val all = s.system + s.user
        assertTrue("$name 一次请求 ${all.length} 字，超过 $limit", all.length <= limit)
        spoilers.forEach { assertFalse("$name 带了后文：$it", it in all) }
        // 任务卡是最后一段，文风范例在它前面；来源编号、知识库整份、流程话术都不给写正文的模型。
        val card = s.user.lastIndexOf(WritePrompts.CARD_HEADER)
        assertTrue(name, card > 0 && WritePrompts.CARD_HEADER !in s.user.substring(card + 1))
        assertTrue(name, s.user.indexOf(StyleSamples.HEADER) in 0 until card)
        assertTrue(name, s.user.indexOf(WritePrompts.PROSE_HEADER) in 0 until card)
        listOf("[来源 P_", "=== 知识库（整份） ===", "【本次章节任务】", "资料分工", "事实底稿", "逐项表演", "不强加悬念", "编辑助手")
            .forEach { assertFalse("$name 里有“$it”", it in all) }
        assertTrue(name, s.system.startsWith("你是和作者一起写这部小说《渡灯》的合写搭档"))
        // 写作规范只放一份。
        assertEquals(name, 1, Regex("比喻一章不超过两处").findAll(all).count())
        // 篇幅只说一次：检查规则、作者模板里的整章字数都不交给模型。
        assertFalse(name, Regex("3000.{0,2}4000").containsMatchIn(s.system + s.user.substring(0, card)))
        assertEquals(name, 1, Regex("篇幅：").findAll(all).count())
    }

    @Test
    fun beatByBeatDraftReviewAndRevisionStayLeanAndSpoilerFree() {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        writing.writeDraft(workId, chapterId, workflow = true)
        Thread.sleep(300)
        waitFor("写完") { writing.drafts.value[workId to chapterId]?.loading == false }
        val list = prose()
        // 四拍、一次修订，都是写正文的请求。
        assertEquals(5, list.size)
        list.forEachIndexed { i, s -> check(s, "第 ${i + 1} 个写正文的请求") }
        // 第一拍：从本章开头接上一章结尾（只给最后一段），卡上是这一拍、约 800 字。
        val first = list.first()
        assertTrue(first.user.startsWith("上一章结尾：\n"))
        assertTrue(first.user.substringBefore("\n\n").length <= WritePrompts.ENDING_MAX + 20)
        assertTrue("篇幅：约 800 字" in first.user)
        assertTrue("  1. 卯时林月到渡口，老周的船拴在桩上，船上没人，烟锅还搁在船头  ← 这次写这一拍" in first.user)
        // 每一拍都重申视角和钩子。
        list.take(4).forEach { s ->
            assertTrue("视角：林月" in s.user)
            assertTrue("停在钩子上：河上传来漕帮的号子，有船朝渡口来。" in s.user)
        }
        // 修订：带作者的修订提示词，不带创作提示词；温度和创作一样。
        val revise = list.last()
        assertTrue("这一章现在的稿子：" in revise.user)
        assertTrue("按审读意见修订这一章" in revise.system)
        assertFalse("正文创作提示词" in revise.system)
        assertEquals(1.3, revise.body.getDouble("temperature"), 0.001)
        // 审读的判断标准照旧，本章任务只给一次（系统提示里）。
        val review = sent.first { "【当前阶段：审读】" in it.user }
        assertTrue("核时地、在场与知情" in review.user)
        assertEquals(1, Regex("【本次章节任务】").findAll(review.system + review.user).count())
    }

    @Test
    fun theEditorWritesTheRestOfTheChapterFromTheBeatsLeft() {
        val two = beats[0] + "\n" + beats[1]
        store.saveText(workId, chapterId, two, count = false)
        writing.writeInline(workId, chapterId, two, two.length, InlineMode.TO_END)
        Thread.sleep(300)
        waitFor("写完") { writing.inline.value?.loading == false }
        val s = prose().single()
        check(s, "写到本章结尾")
        // 剩下的两拍都列出来，前两拍按正文认成已写；停在章末钩子上；篇幅是剩下的字数。
        assertTrue("  2. 杜娘开门，说昨夜顾长风带人来过，老周跟他们走了，走前留话“东西在船上”（已写）" in s.user)
        assertTrue("  3. 沈默赶来，和林月在船舱夹板里找到另半张药方，后半写着“交周，送白沙渡”\n" in s.user)
        assertTrue("写到哪里停：把剩下的各拍都写完，停在章末钩子上" in s.user)
        assertNotNull(Regex("篇幅：约 \\d+ 字").find(s.user))
    }
}
