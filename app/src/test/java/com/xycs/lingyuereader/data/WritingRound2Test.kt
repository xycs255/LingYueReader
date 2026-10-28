package com.xycs.lingyuereader.data

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiMessage
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.DeviceLlm
import com.xycs.lingyuereader.ai.DeviceModel
import com.xycs.lingyuereader.ai.DeviceModelException
import com.xycs.lingyuereader.ai.DeviceModelFiles
import com.xycs.lingyuereader.ai.DeviceModels
import com.xycs.lingyuereader.ai.DevicePrompt
import com.xycs.lingyuereader.ai.WritePrompts
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest

class WritingRound2Test {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun thePlanNumberOnTheCardDecidesWhichChapterThisIs() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val c = w.chapters[0].id
        s.renameChapter(w.id, c, "第二十章 雪夜（上）")
        assertEquals(20, s.chapterNo(s.get(w.id)!!, c))
        listOf("十八", "18", "第十八章").forEach { plan ->
            s.saveCard(w.id, c, ChapterCard(plan = plan))
            assertEquals(plan, 18, s.chapterNo(s.get(w.id)!!, c))
        }
        // 察觉表按细纲章号算：第十九章才知道的，这一章（细纲第十八章）还不该知道。
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物", "| 人物 | 想要 |\n|---|---|\n| 林月 | 回家 |\n")
        s.addPackDoc(w.id, PackKind.AWARENESS, "察觉表", "- 第十九章，林月知道玉佩的来历。\n- 第十七章，林月见到玉佩。\n")
        s.saveText(w.id, c, "林月推开门。")
        val b = s.brief(w.id, c)!!
        assertEquals(18, b.chapter)
        assertTrue(b.notYet.any { "来历" in it })
        assertTrue(b.items.first { it.group == "察觉" }.text.contains("见到玉佩"))
    }

    @Test
    fun formsOfAddressCountAsTheCharacter() {
        val people = """
            | 人物 | 称呼 | 想要 |
            |---|---|---|
            | 顾长风 | 顾、老顾、顾兄 | 一个家 |
            - **周伯（门房）：** 称呼：老周、周老头。话少。
        """.trimIndent()
        val found = ChapterBriefs.characters(listOf("人物" to people))
        assertEquals(listOf("顾", "老顾", "顾兄"), found.first { it.name == "顾长风" }.aliases)
        assertEquals(listOf("老周", "周老头"), found.first { it.name == "周伯" }.aliases)
        // 正文里只叫“老顾”“周老头”，也认得出是谁。
        val docs = listOf(PackDoc("c", PackKind.CHARACTER, "人物", 3, false) to people)
        assertEquals(setOf("顾长风", "周伯"), ChapterBriefs.build(docs, "老顾看了周老头一眼。", "", 1, 1).present.toSet())
        // 称谓与语气文档里写到的人也并进来。
        val voice = listOf(PackDoc("v", PackKind.VOICE, "称谓", 5, false) to "- **杜娘（原创）：** 称呼：杜掌柜。说话快。")
        assertEquals(listOf("杜娘"), ChapterBriefs.build(voice, "杜掌柜来了。", "", 1, 1).present)
    }

    @Test
    fun longStyleGuidesAreExcerpted() {
        val short = PackDoc("s", PackKind.STYLE, "规范", 5, true) to "## 对白\n要短。"
        assertEquals("【规范】\n## 对白\n要短。", ChapterBriefs.styleFor(listOf(short), emptyList()))
        val long = PackDoc("l", PackKind.STYLE, "长规范", 5, true) to (1..10).joinToString("\n\n") { i ->
            "## 第${i}轮\n" + (if (i == 3) "林月说话慢。" else "") + "规则$i。".repeat(80)
        }
        val out = ChapterBriefs.styleFor(listOf(long), listOf("林月"), limit = 2_000)
        assertTrue(out.startsWith("【长规范（节选）】"))
        // 写到出场人物的小节、最后补的小节优先；按原来的顺序拼。
        assertTrue("## 第3轮" in out && "## 第10轮" in out)
        assertFalse("## 第1轮\n" in out)
        assertTrue(out.indexOf("## 第3轮") < out.indexOf("## 第10轮"))
        assertTrue(out.length <= 2_100)
    }

    @Test
    fun aiLogIsKeptNewestFirstAndRated() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        (1..3).forEach { s.addAiLog(w.id, WriteRecord("r$it", it * 10L, w.chapters[0].id, "第1章", "灵感", "", "第$it 条")) }
        assertEquals(listOf("r3", "r2", "r1"), s.aiLog(w.id).map { it.id })
        s.rateAiLog(w.id, "r2", -1)
        assertEquals(-1, s.aiLog(w.id).first { it.id == "r2" }.rating)
        s.deleteAiLog(w.id, "r1")
        assertEquals(listOf("r3", "r2"), s.aiLog(w.id).map { it.id })
        repeat(WriteRecord.KEEP + 5) { s.addAiLog(w.id, WriteRecord("x$it", 1_000L + it, "", "", "灵感", "", "x")) }
        assertEquals(WriteRecord.KEEP, s.aiLog(w.id).size)
    }

    /** 写作建议不再查设定（审稿的事实关做）：旧设置里存着 CANON 的照常读，读出来没有它；没存过的默认三类。 */
    @Test
    fun oldSuggestKindsWithCanonStillRead() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        assertEquals(listOf("TYPO", "STYLE", "VOICE"), s.assist(w.id).suggestKinds)
        // 照旧版本存下的样子：四类都在，还夹着一个认不得的。
        s.updateAssist(w.id) { it.copy(realtime = true, suggestKinds = listOf("TYPO", "CANON", "STYLE", "VOICE", "WHATEVER")) }
        val a = s.assist(w.id)
        assertTrue(a.realtime)
        assertEquals(listOf("TYPO", "STYLE", "VOICE"), a.suggestKinds)
        // 只开了查设定的：读出来一类都没有，不报错。
        s.updateAssist(w.id) { it.copy(suggestKinds = listOf("CANON")) }
        assertEquals(emptyList<String>(), s.assist(w.id).suggestKinds)
    }

    /** “矛盾检查”去掉了（审稿的事实关做这件事）：以前记下的那几条照样读得出、列得出，名字不变。 */
    @Test
    fun oldConsistencyRecordsStillRead() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.addAiLog(w.id, WriteRecord("old", 10L, w.chapters[0].id, "第1章", "矛盾检查", "", "林月第三章说的是左臂受伤。"))
        s.addAiLog(w.id, WriteRecord("new", 20L, w.chapters[0].id, "第1章", "读者点评", "", "节奏稍慢。"))
        assertEquals(listOf("读者点评", "矛盾检查"), s.aiLog(w.id).map { it.kind })
        assertEquals("林月第三章说的是左臂受伤。", s.aiLog(w.id).last().text)
    }

    @Test
    fun issueMarksAndDeclinedEditsAreStored() {
        val r = ChapterReview(1, 2, listOf(GateResult(ReviewGate.FACT, listOf(ReviewIssue("一句", "问题", "改法", ReviewIssue.IGNORED)))))
        assertEquals(r, ChapterReview.fromJson(r.toJson()))
        assertFalse(r.gates[0].issues[0].open)

        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.updateAssist(w.id) { it.copy(declined = listOf(StyleExample("润色", "风很大。", "狂风呼啸，天地变色。", 1)), briefLimit = 20_000) }
        val a = s.assist(w.id)
        assertEquals(20_000, a.briefLimit)
        // 没采纳的改法在请求最后的写法要求里，不在系统提示里。
        val voice = WritePrompts.voiceTail(a, null, examples = true)
        assertTrue("作者没有采纳的改法（不要这样改）：\n原文：风很大。\n没采纳的改法：狂风呼啸，天地变色。" in voice)
        assertFalse("没有采纳" in WritePrompts.voiceTail(a, null, examples = false))
        assertFalse("没有采纳" in WritePrompts.system("长夜", a))
    }

    @Test
    fun devicePromptsAreCutToFit() {
        val msgs = listOf(AiMessage("user", "正文" + "字".repeat(100) + "要求：润色"))
        assertEquals("资料\n\n" + msgs[0].content, DevicePrompt.fit("资料", msgs, maxTokens = 4096))
        // 放不下：先截资料，要求（在最后）留着。
        val cut = DevicePrompt.fit("资".repeat(3_000), msgs, maxTokens = 1_000)
        assertTrue(cut.endsWith("要求：润色"))
        assertTrue(cut.length < 1_000)
        val tiny = DevicePrompt.fit("资料", listOf(AiMessage("user", "前文".repeat(2_000) + "要求：润色")), maxTokens = 600)
        assertTrue(tiny.endsWith("要求：润色") && "（前面省略）" in tiny)
    }

    @Test
    fun deviceModelsAreDownloadedVerifiedAndImported() = runBlocking {
        val bytes = "假模型".repeat(1_000).toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/m.task") { ex ->
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val root = tmp.newFolder()
            val files = DeviceModelFiles(root, urlOf = { "http://127.0.0.1:${server.address.port}/m.task" })
            val good = DeviceModel("test", "测试", "m.task", "", bytes.size.toLong(), sha, 4096, "")
            files.download(good) { _, _, _ -> }
            assertTrue(files.installed("test"))
            val bad = good.copy(id = "bad", fileName = "bad.task", sha256 = "0".repeat(64))
            assertTrue(runCatching { files.download(bad) { _, _, _ -> } }.exceptionOrNull() is DeviceModelException)
            assertFalse(root.resolve("bad.task").exists())
            // 导入：要是 .task。
            val id = files.import("我的模型.task", { "x".toByteArray().inputStream() }, 1) { _, _, _ -> }
            assertEquals(DeviceModels.FILE + "我的模型.task", id)
            assertTrue(id in files.installedIds())
            assertEquals("我的模型", DeviceModels.label(id))
            assertTrue(runCatching { files.import("模型.bin", { "x".toByteArray().inputStream() }, 1) { _, _, _ -> } }.exceptionOrNull() is DeviceModelException)
            files.delete(id)
            assertFalse(id in files.installedIds())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theDevicePresetGoesToTheDeviceModelNotTheNetwork() = runBlocking {
        val c = AiConfig(enabled = true).withPreset(AiPresets.of(AiPresets.DEVICE)).copy(model = "qwen2.5-1.5b")
        assertTrue(c.onDevice && c.ready)
        assertEquals("本机", c.providerLabel)
        assertFalse(c.copy(model = "").complete)
        val client = AiClient { error("本机模型不该联网") }
        assertTrue(runCatching { client.stream(c, "系统", listOf(AiMessage("user", "你好"))) }.exceptionOrNull()?.message!!.contains("64 位"))
        val seen = mutableListOf<String>()
        client.device = object : DeviceLlm {
            override suspend fun generate(modelId: String, system: String, messages: List<AiMessage>, onText: (String) -> Unit): String {
                seen += modelId
                onText("你")
                onText("好")
                return "你好"
            }

            override fun release() {}
        }
        val pieces = mutableListOf<String>()
        assertEquals("你好", client.stream(c, "系统", listOf(AiMessage("user", "你好"))) { pieces += it })
        assertEquals(listOf("qwen2.5-1.5b"), seen)
        assertEquals(listOf("你", "好"), pieces)
        // 设置存下来再读，还是本机模型。
        assertEquals("DEVICE", JSONObject().put("p", c.protocol.name).getString("p"))
    }
}
