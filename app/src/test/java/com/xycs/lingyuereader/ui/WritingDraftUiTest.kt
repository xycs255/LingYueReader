package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.LocalDate
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDraftUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var c1 = ""
    private var c2 = ""
    private lateinit var server: HttpServer
    private val asked: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    @Volatile private var authorWritesSummary = false
    @Volatile private var validWorkflowReview = false
    private val release = java.util.concurrent.CountDownLatch(1)

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val system = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += system to user
                    if ("慢慢写" in user) {
                        // 流式回答：先给一句，停很久再给下一句（测试在中间停下）。
                        ex.responseHeaders.add("Content-Type", "text/event-stream")
                        ex.sendResponseHeaders(200, 0)
                        runCatching {
                            ex.responseBody.use { out ->
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"沈默走进雪里。\"}}]}\n\n".toByteArray())
                                out.flush()
                                // 等到测试结束（最多半分钟）才给下一句：测试一定是在写的途中停下的。
                                release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"\\n他没有回头。\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
                            }
                        }
                        return@createContext
                    }
                    // 补写摘要写到第二章时，作者正好在细纲卡里自己写了摘要。
                    if ("概括这一章" in user && "旧稿一句。" in user && authorWritesSummary) {
                        WorkStore(writingDir).let { it.saveCard(workId, c2, it.card(workId, c2).copy(summary = "作者自己写的摘要")) }
                    }
                    val answer = when {
                        "只返回一个 JSON 对象" in user -> """{"nodes":[{"kind":"信息","label":"声响引题","quote":"夜里有人叩门"},{"kind":"动作","label":"靠近灯盏","quote":"移近灯盏"}],"dialogue":null}"""
                        validWorkflowReview && "【当前阶段：审读】" in user -> """{"summary":["对白语气需作者最终确认"],"missing":[],"issues":[]}"""
                        "请写出这一章" in user -> "第二章 来客\n\n　　杜娘把门打开，雪灌了进来。\n“谁？”她问。\n（本章完）"
                        "请从最后一句接着往下写" in user -> "门外的人没有回答。"
                        "概括这一章" in user -> "沈默进城，在杜娘的茶馆落脚。"
                        // 审稿：故意不按格式回答。
                        else -> "这一章写得很好，没有什么问题。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
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
            store.saveText(w.id, w.chapters[0].id, "沈默进了城。天黑前，他推开了茶馆的门。")
            val (_, two) = store.addChapter(w.id, w.volumes[0].id)!!
            store.saveText(w.id, two.id, "旧稿一句。")
            store.saveCard(w.id, two.id, ChapterCard(facts = "杜娘开门，门外来了人", hook = "门外的人不说话"))
            store.addPackDoc(w.id, PackKind.RULES, "检查规则", "字数：至少 10\n")
            store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "对白要短。")
            workId = w.id
            c1 = w.chapters[0].id
            c2 = two.id
        }

        override fun after() {
            release.countDown()
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

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** 每道审稿的结果写在卡片第一行，读屏念成“事实关：1 处”（组名里不再写状态）。 */
    private fun waitForGate(text: String) = waitFor { compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun chapterBudgetPreviewUsesLocalMaterialsWithoutSendingAiRequest() {
        WorkStore(writingDir).saveText(workId, c2, "")
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }

        waitForText("写本章请求预算")
        waitForText("test-model")
        waitForText("未设置此模型单价")
        assertTrue("估算本章材料不能自行发起 AI 请求", asked.isEmpty())
    }

    @Test
    fun authorSelectsARealReferenceBeforeAiWritesTheDraft() {
        val store = WorkStore(writingDir)
        val book = store.importReference(workId, "合成范本.txt", "第一章 门外\n夜里有人叩门。掌柜先问来者身份，等对方迟疑后才移近灯盏。")
        val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.single().id, ReferencePart.OPENING, 100)!!
        val analysis = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, c2, "旧正文指纹",
            "此合成片段先给出声音，再通过一次对白和动作改变人物处境。"))
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("你的构思")
        waitForText("AI 拟写法")
        compose.onNodeWithText("写作范本").performScrollTo().performClick()
        waitForText("AI 写作范本")
        compose.onNodeWithText("《${book.title}》 · ${book.chapters.single().title}").performClick()
        waitFor { vm.companionReference(workId, c2) == analysis.id }
        waitForText("写法图与原文范围")
        assertTrue("范本细节应先收起，让写本章和候选稿更靠前",
            compose.onAllNodesWithText("生成写法图（会发送一次 AI 请求）").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("展开写法图与原文范围").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithContentDescription("范本开头，选中第", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("选范本与看范围不能自行发起 AI 请求", asked.isEmpty())

        compose.onNodeWithText("生成写法图（会发送一次 AI 请求）").performScrollTo().performClick()
        waitForText("声响引题")
        waitForText("靠近灯盏")
        assertEquals("写法图只由作者点击后请求一次", 1, asked.count { (_, user) -> "只返回一个 JSON 对象" in user })
        assertEquals("分析范本不能自行写正文", 0, asked.count { (_, user) -> "请写出这一章" in user })
        compose.onNodeWithText("写作范本").performScrollTo().performClick()
        compose.onNodeWithText("不使用范本").performClick()
        compose.waitForIdle()
        assertTrue("取消范本后不能露出上一张写法图", compose.onAllNodesWithText("声响引题", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("写作范本").performClick()
        compose.onNodeWithText("《${book.title}》 · ${book.chapters.single().title}").performClick()
        waitForText("声响引题")

        compose.onNodeWithText("写本章").performScrollTo().performClick()
        waitFor { asked.any { (system, user) -> "夜里有人叩门" in system + user } }
        waitForText("杜娘把门打开")
        waitForText("来源：范本片段已核验 · 本章任务资料")
        assertEquals("AI 只应在作者点击写本章后写一版", 1, asked.count { (_, user) -> "请写出这一章" in user })
        assertEquals("候选稿不能自动进入正文", "旧稿一句。", store.text(workId, c2))
    }

    @Test
    fun aiWritesTheChapterThenItGoesIntoTheText() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        // 先补写摘要：第一章有正文、没有摘要。
        compose.runOnUiThread { vm.writingAi.summarizeAll(workId) }
        waitFor { store.card(workId, c1).summary == "沈默进城，在杜娘的茶馆落脚。" }
        // 第二章也有正文，一并写了；细纲卡里原来的事实还在。
        waitFor { store.card(workId, c2).summary.isNotEmpty() }
        assertEquals("杜娘开门，门外来了人", store.card(workId, c2).facts)
        val todayBefore = store.stats().on(LocalDate.now())

        compose.runOnUiThread { vm.open(Screen.Editor(workId, c2)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("AI 写整章草稿").performClick()
        waitForText("这一章想怎么写")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("多写对白")
        waitForText("本章写作任务")
        compose.onNodeWithText("写本章").performScrollTo().performClick()
        waitForText("杜娘把门打开，雪灌了进来。")
        // 章名和“本章完”去掉了；草稿存下来了。
        waitFor { store.draft(workId, c2) == "杜娘把门打开，雪灌了进来。\n“谁？”她问。" }
        waitForText("来源：本章任务资料 · 未使用范本")
        waitForText("审读：未启用自动审读，请自行通读")
        val (system, user) = asked.first { "请写出这一章" in it.second }
        assertTrue("篇幅不少于 10 字" in user)
        assertTrue("作者这次的要求：多写对白" in user)
        assertTrue("上一章结尾：\n沈默进了城。" in user)
        assertTrue("对白要短。" in user && "字数：至少 10" in user)
        assertTrue("本章细纲卡" in system && "杜娘开门，门外来了人" in system)
        // 前一章的摘要当前情带上了；设定包整份在前面。
        assertTrue("【前情】\n- 第 1 章 第1章：沈默进城，在杜娘的茶馆落脚。" in system)
        assertTrue(system.startsWith("下面是作者这部作品的设定包"))

        // 接着写：接在草稿后面。
        compose.onNodeWithText("接着写").performScrollTo().performClick()
        waitFor { store.draft(workId, c2).endsWith("门外的人没有回答。") }

        compose.onNodeWithText("放进正文").performScrollTo().performClick()
        compose.onNodeWithText("替换正文").performClick()
        waitFor { store.text(workId, c2) == "杜娘把门打开，雪灌了进来。\n“谁？”她问。\n门外的人没有回答。" }
        // 原来的正文存了一版；AI 写的字不算码字数；草稿清掉了。
        assertTrue(store.history(workId, c2).any { store.snapshotText(workId, c2, it.at) == "旧稿一句。" })
        assertEquals(todayBefore, store.stats().on(LocalDate.now()))
        waitFor { store.draft(workId, c2).isEmpty() }
        // 回到编辑器，看到的是新正文。
        waitForText("门外的人没有回答。")
    }

    @Test
    fun workflowPassShowsReviewStatusBesideAdoptionWhileEvidenceStaysExpandable() {
        validWorkflowReview = true
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, c2, workflow = true) }
        waitFor { vm.writingAi.drafts.value[workId to c2]?.let { !it.loading && it.assessment?.passed == true } == true }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("审读：已通过，上方可展开证据")
        waitForText("放进正文")
        assertTrue("完整审读证据仍可按需展开",
            compose.onAllNodesWithText("对白语气需作者最终确认", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("展开审读证据与建议").performScrollTo().performClick()
        waitForText("对白语气需作者最终确认")
    }

    @Test
    fun aPassedReviewSurvivesPolishRecordsAndNewDirectionsButStaleMaterialsShowOneStatus() {
        validWorkflowReview = true
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, c2, "保留杜娘的迟疑", workflow = true) }
        waitFor { vm.writingAi.drafts.value[workId to c2]?.let { !it.loading && it.assessment?.passed == true } == true }
        // 在别章接受、关掉润色：只是文风范例，不让本稿审读失效。
        store.updateAssist(workId) { it.copy(
            accepted = listOf(StyleExample("润色", "沈默说完。", "沈默说到这里停了停。", 1)),
            declined = listOf(StyleExample("改稿", "老周进门。", "老周从不回头。", 2))) }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("审读：已通过，上方可展开证据")
        waitForText("可供采纳")
        // 只改下一次写的要求：当前稿仍按审读时的要求判断，可以采纳。
        compose.onNode(hasSetTextAction() and hasText("保留杜娘的迟疑")).performTextReplacement("改成林月先开口")
        waitFor { store.draftIntent(workId, c2)?.instruction == "改成林月先开口" }
        waitForText("审读：已通过，上方可展开证据")
        assertNull(kotlinx.coroutines.runBlocking { vm.writingAi.draftBlocked(workId, c2) })

        // 细纲变了：状态标签和“本稿审读”一起显示失效，不再一个绿一个“待核对”。
        store.saveCard(workId, c2, store.card(workId, c2).copy(facts = "杜娘没有开门，顾长风在门外等到天亮"))
        compose.runOnUiThread { vm.open(Screen.Editor(workId, c2)) }
        waitForText("旧稿一句。")
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("审读已过时")
        waitForText("待核对")
        assertTrue("资料变了不能再显示可供采纳",
            compose.onAllNodesWithText("可供采纳").fetchSemanticsNodes().isEmpty())
        waitForText("审读：未通过或已失效")
    }

    @Test
    fun reopeningACandidateRestoresItsRequestWithoutOverwritingNewEdits() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, c2, "保留人物的迟疑", targetLength = "约30字") }
        waitFor { vm.writingAi.drafts.value[workId to c2]?.let { !it.loading && it.text.isNotBlank() } == true }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("本章写作任务")
        compose.onNode(hasSetTextAction() and hasText("保留人物的迟疑")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("约30字")).assertExists()
        compose.runOnUiThread { vm.open(Screen.Editor(workId, c2)) }
        waitForText("旧稿一句。")
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("本章写作任务")
        compose.onNode(hasSetTextAction() and hasText("保留人物的迟疑")).performTextReplacement("保留人物的犹豫和改口")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("保留人物的犹豫和改口")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("约30字")).assertExists()
    }

    @Test
    fun reviewSwitchRestoresAnExplicitChoiceBeforeAnyCandidateExists() {
        val store = WorkStore(writingDir)
        store.setDraftIntent(workId, c2, "先让杜娘开门", "约200字", false)
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("本章写作任务")
        compose.onNode(hasSetTextAction() and hasText("先让杜娘开门")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("约200字")).assertExists()
        compose.onNodeWithText("创作后审读、修订").performScrollTo().assertIsOff()
        compose.onNodeWithText("创作后审读、修订").performClick()
        compose.onNodeWithText("创作后审读、修订").assertIsOn()
        waitFor { store.draftIntent(workId, c2)?.workflowChoice == true }
        assertEquals("", store.draft(workId, c2))
    }

    @Test
    fun editingRequestWhileProjectTaskLoadsDoesNotSaveItsReviewDefaultAsAnExplicitChoice() {
        val store = WorkStore(writingDir)
        store.addPackDoc(workId, PackKind.INSTRUCTION, "完整正文创作提示词", "只输出本章完整正文。")
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("这一章想怎么写")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("多留一点悬念")
        waitFor { store.draftIntent(workId, c2)?.instruction == "多留一点悬念" }
        assertEquals("没有手动切换时，项目审读默认值不能写成显式选择", null,
            store.draftIntent(workId, c2)?.workflowChoice)
        waitForText("本章写作任务")
        compose.onNodeWithText("创作后审读、修订").performScrollTo().assertIsOn()
        compose.runOnUiThread { vm.open(Screen.Editor(workId, c2)) }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("本章写作任务")
        compose.onNodeWithText("创作后审读、修订").performScrollTo().assertIsOn()
        assertEquals(null, store.draftIntent(workId, c2)?.workflowChoice)
    }

    @Test
    fun aReviewAnswerInTheWrongShapeIsReportedNotIgnored() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, c2)) }
        waitForText("还没有审过")
        compose.onNodeWithText("开始审稿").performClick()
        waitForGate("事实关：出错")
        waitForGate("写法关：出错")
        waitForText("AI 的回答不是要求的格式")
        waitForGate("规则关：1 处")
    }

    @Test
    fun aSummaryTheAuthorWroteMeanwhileIsKept() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        authorWritesSummary = true
        compose.runOnUiThread { vm.writingAi.summarizeAll(workId) }
        waitFor { store.card(workId, c1).summary == "沈默进城，在杜娘的茶馆落脚。" }
        waitFor { vm.writingAi.summaries.value == null && asked.count { "概括这一章" in it.second } == 2 }
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        // 写入前重新读了卡片：作者写的摘要没被盖掉，细纲也还在。
        assertEquals("作者自己写的摘要", store.card(workId, c2).summary)
        assertEquals("杜娘开门，门外来了人", store.card(workId, c2).facts)
    }

    @Test
    fun draftsOfTwoChaptersDoNotMixAndStoppingKeepsWhatWasWritten() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, c1, "慢慢写") }
        waitFor { vm.writingAi.drafts.value[workId to c1]?.text == "沈默走进雪里。" }
        // 第一章还在写：打开第二章的草稿页，不动第一章的状态。
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, c2)) }
        waitForText("这一章想怎么写")
        waitFor { vm.writingAi.drafts.value.containsKey(workId to c2) }
        assertTrue(vm.writingAi.drafts.value.getValue(workId to c1).loading)
        // 回到第一章的草稿页：还在写，没有“放进正文”。
        compose.runOnUiThread { vm.replaceTop(Screen.ChapterDraft(workId, c1)) }
        waitForText("正在写")
        assertTrue(compose.onAllNodesWithText("放进正文").fetchSemanticsNodes().isEmpty())
        // 停下：写出来的存成草稿，可以接着写。
        compose.onNodeWithText("停止").performClick()
        waitFor { store.draft(workId, c1) == "沈默走进雪里。" }
        assertFalse(vm.writingAi.drafts.value.getValue(workId to c1).loading)
        waitForText("放进正文")
    }
}
