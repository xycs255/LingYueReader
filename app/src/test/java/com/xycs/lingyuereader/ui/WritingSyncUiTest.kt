package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/** 写完一章，整理进设定包：编辑器菜单 → 结果页各组 → 勾选、改 → 写进设定包、记进故事账本；作品页的“未整理”；定稿时的提示。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingSyncUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    /** 请求正文里含这几个字的回 500（模拟分段整理时有一段没成）。 */
    @Volatile
    private var failWhen: String? = null
    /** 为真时带工具的请求回 400“不支持工具”（模拟局域网上不支持工具的模型）。 */
    @Volatile
    private var rejectTools = false
    private val text = "渡口起了雾。\n杜娘把茶碗推给林月，说老周昨夜里走了。\n顾长风在门外喊她“月丫头”。\n林月这才知道沈默是朝廷的卧底。"

    /** 模型的回答；账本那个测试换成带账本几组的。 */
    @Volatile
    private var answer = JSONObject()
        .put(
            "new",
            JSONArray()
                .put(JSONObject().put("type", "人物").put("name", "杜娘").put("kind", "人物档案").put("identity", "渡口茶馆老板娘").put("quote", "杜娘把茶碗推给林月"))
                .put(JSONObject().put("type", "地名").put("name", "渡口").put("kind", "硬性设定").put("section", "地名").put("text", "城东的渡口，常起雾").put("quote", "渡口起了雾。"))
                // 编出来的原句：正文里没有，丢掉。
                .put(JSONObject().put("type", "人物").put("name", "老韩").put("quote", "老韩提着刀进了门")),
        )
        .put(
            "conflicts",
            JSONArray().put(
                JSONObject().put("title", "顾长风怎么叫林月").put("kind", "称谓与语气").put("pack", "顾长风叫林月“林姑娘”。").put("chapter", "喊她“月丫头”")
                    .put("fix", "pack").put("text", "顾长风私下叫林月“月丫头”。").put("quote", "顾长风在门外喊她“月丫头”。"),
            ),
        )
        .put("awareness", JSONArray().put(JSONObject().put("who", "林月").put("what", "沈默是朝廷的卧底").put("since", "第12章").put("source", "杜娘说漏了嘴").put("quote", "林月这才知道沈默是朝廷的卧底。")))
        .toString()

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    bodies += body
                    if (rejectTools && body.has("tools")) {
                        val err = JSONObject().put("error", JSONObject().put("message", "registry.local/渡口:7b does not support tools")).toString().toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(400, err.size.toLong())
                        runCatching { ex.responseBody.use { it.write(err) } }
                        return@createContext
                    }
                    val fail = failWhen
                    if (fail != null && fail in lastMessage(body)) {
                        val err = JSONObject().put("error", JSONObject().put("message", "服务器忙")).toString().toByteArray()
                        ex.sendResponseHeaders(500, err.size.toLong())
                        runCatching { ex.responseBody.use { it.write(err) } }
                        return@createContext
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "整理好了：\n```json\n$answer\n```")).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    runCatching { ex.responseBody.use { it.write(bytes) } }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.renameChapter(w.id, w.chapters[0].id, "第十二章 渡口")
            store.saveText(w.id, w.chapters[0].id, text, count = false)
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 沈默 | 镖局的账房 |")
            store.addPackDoc(w.id, PackKind.CANON, "硬性设定", "## 地名\n- 青石镇：林月的家乡")
            store.addPackDoc(w.id, PackKind.VOICE, "称谓与语气", "## 顾长风\n顾长风叫林月“林姑娘”。")
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(t: String) = waitFor { has(t) }

    /** 结果页是懒加载的列表：先滚到那一行（找不到不报错），再看在不在。 */
    private fun seen(t: String): Boolean {
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(t, substring = true)) }
        ShadowLooper.idleMainLooper()
        return has(t)
    }

    private fun chapterRowMatcher() = hasText("第十二章 渡口") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    /** Scroll to the actual chapter row; overview's continuation tile also contains its title. */
    private fun seeChapterRow(): Boolean {
        val row = chapterRowMatcher()
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(row) }
        ShadowLooper.idleMainLooper()
        return compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()
    }

    private fun chapterRowShowsUnsynced(): Boolean =
        seeChapterRow() && compose.onAllNodes(chapterRowMatcher() and hasText("未整理", substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun press(t: String) {
        waitFor { seen(t) }
        compose.onNodeWithText(t).performClick()
    }

    private fun lastMessage(body: JSONObject): String = body.getJSONArray("messages").let { it.getJSONObject(it.length() - 1).optString("content") }

    /** 一章写得很长（两万来字）：开头是那几句有依据的，后面是凑字数的段落。 */
    private fun longText(): String = text + "\n" + List(1_100) { "雾气贴着河面慢慢地散开，船桨一下一下地响。$it" }.joinToString("\n")

    private fun openSyncFromEditor() {
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("整理本章进设定包").performClick()
        waitForText("新人物：杜娘")
    }

    @Test
    fun syncFromTheEditorWritesOnlyWhatWasPicked() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("渡口起了雾")
        openSyncFromEditor()
        // 一次请求，带着设定包和查阅工具；说的是第 12 章。
        assertEquals(1, bodies.size)
        val body = bodies.single()
        assertTrue(body.has("tools"))
        val messages = body.getJSONArray("messages")
        assertTrue("药铺掌柜的女儿" in messages.getJSONObject(0).getString("content"))
        assertTrue("按设定算第 12 章" in messages.getJSONObject(messages.length() - 1).getString("content"))

        // 三组；编出来的那条不在；冲突默认不勾，新设定、察觉默认勾上。
        listOf("新设定", "冲突", "察觉", "新地名：渡口", "顾长风怎么叫林月", "林月：沈默是朝廷的卧底", "有 1 条在正文里找不到").forEach { assertTrue(it, seen(it)) }
        assertFalse(seen("老韩"))
        assertTrue(seen("写进设定包和账本（3 条）"))

        // 不要“渡口”这一条；杜娘那条点开改一改。
        seen("新地名：渡口")
        compose.onNodeWithContentDescription("勾选：新地名：渡口").performClick()
        waitFor { seen("写进设定包和账本（2 条）") }
        press("新人物：杜娘")
        waitFor { compose.onAllNodes(hasSetTextAction() and hasText("| 杜娘 |", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("| 杜娘 |", substring = true)).performTextReplacement("| 杜娘 | 渡口茶馆的老板娘，消息灵通 |")
        assertTrue(seen("依据：「杜娘把茶碗推给林月」"))

        press("写进设定包和账本（2 条）")
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        waitFor { "消息灵通" in store.packText(workId, people.id) }
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 沈默 | 镖局的账房 |\n| 杜娘 | 渡口茶馆的老板娘，消息灵通 |", store.packText(workId, people.id))
        // 察觉记进故事账本（以前写进察觉表），不再新建察觉表。
        waitFor { store.ledger(workId).isNotEmpty() }
        val know = store.ledger(workId).single()
        assertEquals(LedgerKind.KNOW, know.kind)
        assertEquals("林月" to "沈默是朝廷的卧底", know.subject to know.text)
        assertEquals(12, know.from)
        assertEquals("杜娘说漏了嘴", know.via)
        assertTrue(store.pack(workId).none { it.kind == PackKind.AWARENESS })
        // 没勾的没写：渡口不进硬性设定，称谓与语气照旧。
        assertEquals("## 地名\n- 青石镇：林月的家乡", store.packText(workId, store.pack(workId).single { it.kind == PackKind.CANON }.id))
        assertEquals("## 顾长风\n顾长风叫林月“林姑娘”。", store.packText(workId, store.pack(workId).single { it.kind == PackKind.VOICE }.id))
        // 原来的人物档案存了历史版本；变更登记里写进设定包的每条一笔（账本有自己的历史版本，不记）。
        assertTrue(store.history(workId, store.docKey(people.id)).isNotEmpty())
        waitFor { store.changes(workId).size == 1 }
        val changes = store.changes(workId)
        assertTrue(changes.all { it.reason.startsWith("整理第12章：") })
        // 回到编辑器，提示写了几条进哪几份。
        waitForText("写进了 2 条：《人物档案》1 条、故事账本 1 条")
        waitFor { store.assist(workId).synced.containsKey(chapterId) }

        // 作品页：这一章不显示“未整理”；正文改过以后又显示。
        compose.runOnUiThread { vm.back(); vm.open(Screen.Work(workId)) }
        waitFor { seeChapterRow() }
        assertFalse(chapterRowShowsUnsynced())
        compose.runOnUiThread { vm.writing.save(workId, chapterId, text + "\n雾散了。") }
        waitFor { chapterRowShowsUnsynced() }
    }

    @Test
    fun conflictsCanBePickedAndOnlyThenRewriteThePack() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("渡口起了雾")
        openSyncFromEditor()
        seen("顾长风怎么叫林月")
        compose.onNodeWithContentDescription("勾选：顾长风怎么叫林月").performClick()
        press("写进设定包和账本（4 条）")
        val voice = store.pack(workId).single { it.kind == PackKind.VOICE }
        waitFor { "月丫头" in store.packText(workId, voice.id) }
        assertEquals("## 顾长风\n顾长风私下叫林月“月丫头”。", store.packText(workId, voice.id))
        waitFor { "- 渡口：城东的渡口，常起雾" in store.packText(workId, store.pack(workId).single { it.kind == PackKind.CANON }.id) }
    }

    @Test
    fun finalizingAsksOnceAndCanBeToldToStop() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("渡口起了雾")
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("章节状态").performClick()
        waitForText("的状态")
        compose.onAllNodesWithText("定稿").onLast().performClick()
        compose.onNodeWithText("好").performClick()
        waitForText("要不要顺手整理进设定包？")
        // 勾“不再提示”，这次“整理”：打开结果页，以后不再问。
        compose.onNodeWithText("不再提示").performClick()
        // 底栏也有个“整理”标签：点的是对话框里的按钮。
        compose.onNode(hasText("整理") and !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        waitForText("新人物：杜娘")
        waitFor { !store.assist(workId).syncTip }
        assertTrue(store.get(workId)!!.chapter(chapterId)!!.locked)
    }

    @Test
    fun aSingleChapterIsMergedIntoUnwrittenBatchResultsAndRepeatsAreNotPicked() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        val (_, two) = store.addChapter(workId, store.get(workId)!!.volumes[0].id, "第十三章 茶馆")!!
        store.saveText(workId, two.id, text, count = false)
        compose.runOnUiThread { vm.open(Screen.ChapterSync(workId)); vm.writingAi.syncAll(workId) }
        waitFor { vm.writingAi.sync.value?.let { it.running == null && it.results.size == 2 } == true }
        assertEquals(2, bodies.size)
        // 两章都写到杜娘、渡口、林月知道沈默是卧底：后面那章的默认不勾，标“前面章已有”。
        assertTrue(seen("前面章已有"))
        assertTrue(seen("写进设定包和账本（3 条）"))

        // 还没写进去，又在编辑器里整理了第十二章：并进来，不冲掉第十三章的结果。
        compose.runOnUiThread { vm.writingAi.syncChapter(workId, chapterId) }
        waitForText("已并进还没写入的整理结果")
        waitFor { vm.writingAi.sync.value?.let { it.running == null && it.results.size == 2 } == true }
        assertEquals(setOf(chapterId, two.id), vm.writingAi.sync.value!!.results.map { it.chapterId }.toSet())
        assertTrue(seen("写进设定包和账本（3 条）"))

        // 把后面那章的杜娘也勾上：写的时候再查一遍，人物档案里还是只有一行杜娘。
        val repeat = vm.writingAi.sync.value!!.let { s -> s.items.first { it.key in s.repeats && it.title == "新人物：杜娘" } }
        compose.runOnUiThread { vm.writingAi.toggleSync(repeat.key, true) }
        // 等提示条收起（它盖在按钮上）。
        waitFor { !has("已并进还没写入的整理结果") }
        press("写进设定包和账本（4 条）")
        waitFor { store.assist(workId).synced.keys.containsAll(listOf(chapterId, two.id)) }
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        assertEquals(1, store.packText(workId, people.id).lines().count { "杜娘" in it })
        // 两章都说林月知道沈默是卧底：账本里只记一条。
        assertEquals(1, store.ledger(workId).count { it.kind == LedgerKind.KNOW })
    }

    @Test
    fun ledgerGroupsCanBeEditedAndGoIntoTheLedgerAsOneBatch() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        store.applyLedger(workId, listOf(LedgerOp.Add(LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "杜娘说老周会回来", from = 3))), "手动")
        store.applyLedger(workId, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "在青石镇", subject = "林月", aspect = "位置", from = 2))), "手动")
        val versions = store.ledgerVersions(workId).size
        fun one(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
        answer = JSONObject()
            .put("new", JSONArray()).put("conflicts", JSONArray())
            .put("states", JSONArray().put(one("who" to "林月", "aspect" to "位置", "now" to "在渡口的茶馆", "quote" to "杜娘把茶碗推给林月")))
            .put("events", JSONArray().put(one("what" to "老周昨夜死了", "quote" to "说老周昨夜里走了")))
            .put("awareness", JSONArray().put(one("who" to "林月", "what" to "沈默是朝廷的卧底", "since" to "第12章", "source" to "杜娘说漏了嘴", "quote" to "林月这才知道沈默是朝廷的卧底。")))
            .put("threads_opened", JSONArray().put(one("what" to "顾长风在门外等林月", "quote" to "顾长风在门外喊她“月丫头”。")))
            // P7 不是列出的编号：丢掉。
            .put("threads_closed", JSONArray().put(one("id" to "P1", "quote" to "说老周昨夜里走了")).put(one("id" to "P7", "quote" to "渡口起了雾。")))
            .put("time", one("when" to "起雾的早上", "quote" to "渡口起了雾。"))
            .toString()
        compose.runOnUiThread { vm.open(Screen.ChapterSync(workId)); vm.writingAi.syncChapter(workId, chapterId) }
        waitForText("林月·位置：在渡口的茶馆")
        // 请求里带着到上一章为止的账本：开着的伏笔带编号、出场人物的状态。
        val prompt = lastMessage(bodies.single())
        assertTrue("P1：杜娘说老周会回来" in prompt && "林月·位置：在青石镇" in prompt && "\"threads_closed\"" in prompt)
        listOf("人物状态", "发生的事", "察觉", "伏笔", "时间", "老周昨夜死了", "埋下：顾长风在门外等林月", "收起：杜娘说老周会回来", "时间：起雾的早上")
            .forEach { assertTrue(it, seen(it)) }
        assertTrue(seen("记进故事账本（6 条）"))

        // 人物状态：改方面、改文字。
        press("林月·位置：在渡口的茶馆")
        waitFor { seen("方面") }
        compose.onNodeWithText("方面").performClick()
        compose.onNodeWithText("情绪").performClick()
        waitFor { vm.writingAi.sync.value!!.aspects.values.contains("情绪") }
        compose.onNode(hasSetTextAction() and hasText("在渡口的茶馆")).performTextReplacement("在渡口茶馆的里间")
        // 收伏笔：写明收的是账本里哪一条。
        press("收起：杜娘说老周会回来")
        assertTrue(seen("收的是账本里这一条：杜娘说老周会回来（第 3 章埋）"))
        // 新伏笔：改计划第几章收。
        press("埋下：顾长风在门外等林月")
        waitFor { compose.onAllNodes(hasSetTextAction() and hasText("顾长风在门外等林月")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("15")
        waitFor { seen("计划第 15 章收") }

        press("记进故事账本（6 条）")
        waitFor { vm.writingAi.sync.value == null }
        val ledger = store.ledger(workId)
        val state = ledger.single { it.kind == LedgerKind.STATE && it.from == 12 }
        assertEquals("林月" to "情绪", state.subject to state.aspect)
        assertEquals("在渡口茶馆的里间", state.text)
        assertEquals("老周昨夜死了", ledger.single { it.kind == LedgerKind.EVENT }.text)
        assertEquals("杜娘说漏了嘴", ledger.single { it.kind == LedgerKind.KNOW }.via)
        assertEquals(15, ledger.single { it.kind == LedgerKind.PROMISE && it.id != "p1" }.payoff)
        assertTrue(ledger.single { it.id == "p1" }.let { it.done && it.closed == 12 && it.quote == "说老周昨夜里走了" })
        assertEquals("起雾的早上", ledger.single { it.kind == LedgerKind.TIME }.text)
        // 一章一批：账本只多了一个历史版本；设定包、变更登记都没动。
        assertEquals(versions + 1, store.ledgerVersions(workId).size)
        assertEquals("整理第 12 章", store.ledgerVersions(workId).first().label)
        assertTrue(store.changes(workId).isEmpty())
        assertTrue(store.pack(workId).none { it.kind == PackKind.AWARENESS })
        waitForText("写进了 6 条：故事账本 6 条")
        waitFor { store.assist(workId).synced.containsKey(chapterId) }
    }

    @Test
    fun aLongChapterIsSyncedInPartsAndCountsAsSynced() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        store.saveText(workId, chapterId, longText(), count = false)
        compose.runOnUiThread { vm.open(Screen.ChapterSync(workId)); vm.writingAi.syncChapter(workId, chapterId) }
        waitForText("分 2 段整理")
        // 两次请求：第二次带着前一段的末尾；两段提的同一个人并成一条。
        assertEquals(2, bodies.size)
        assertTrue("第 1 段" in lastMessage(bodies[0]) && "第 2 段" in lastMessage(bodies[1]) && "上一段的末尾" in lastMessage(bodies[1]))
        assertEquals(1, vm.writingAi.sync.value!!.items.count { it.title == "新人物：杜娘" })
        press("写进设定包和账本（3 条）")
        waitFor { store.assist(workId).synced.containsKey(chapterId) }
    }

    @Test
    fun aFailedPartKeepsTheLongChapterUnsynced() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        store.saveText(workId, chapterId, longText(), count = false)
        failWhen = "第 2 段"
        compose.runOnUiThread { vm.open(Screen.ChapterSync(workId)); vm.writingAi.syncChapter(workId, chapterId) }
        waitForText("没整理全")
        assertEquals(2, bodies.size)
        // 第一段的提议仅供核对；失败章不提供勾选，也不能写进设定包。
        assertTrue(vm.writingAi.sync.value!!.picked.isEmpty())
        assertTrue(seen("待重整"))
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        assertFalse("杜娘" in store.packText(workId, people.id))
        assertFalse(store.assist(workId).synced.containsKey(chapterId))

        failWhen = null
        compose.runOnUiThread { vm.writingAi.syncChapter(workId, chapterId) }
        waitFor { vm.writingAi.sync.value?.results?.singleOrNull()?.let { it.error == null && it.items.isNotEmpty() } == true }
        press("写进设定包和账本（3 条）")
        waitFor { "杜娘" in store.packText(workId, people.id) }
        waitFor { store.assist(workId).synced.containsKey(chapterId) }
    }

    @Test
    fun aModelWithoutToolsStillSyncsAndSaysThePackWasNotConsulted() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        rejectTools = true
        compose.runOnUiThread { vm.open(Screen.ChapterSync(workId)); vm.writingAi.syncChapter(workId, chapterId) }
        waitFor { vm.writingAi.sync.value?.results?.singleOrNull() != null && vm.writingAi.sync.value?.running == null }
        val result = vm.writingAi.sync.value!!.results.single()
        // 先带工具被拒，再不带工具重发一次；整理成功，记下“没查设定包”。
        assertEquals(null, result.error)
        assertTrue(result.noTools)
        assertTrue(result.items.isNotEmpty())
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].has("tools"))
        assertFalse(bodies[1].has("tools"))
        // 设定包整份在系统提示里。
        assertTrue("药铺掌柜的女儿" in bodies[1].getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(seen("这次没能查设定包"))
        assertTrue(seen("结果可能漏掉冲突"))
    }
}
