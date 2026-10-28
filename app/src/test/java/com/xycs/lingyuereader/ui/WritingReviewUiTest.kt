package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.brief

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
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
import com.xycs.lingyuereader.data.ReviewGate
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingReviewUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 发给假服务商的（系统提示，用户消息）。 */
    private val asked: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val chapterText = "林月早就知道玉佩的来历。她笑了笑，把门闩上。"
    private var firstId = ""
    @Volatile private var slowFact = false
    private val factGate = java.util.concurrent.CountDownLatch(1)

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val system = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += system to user
                    // 事实关等测试放行再回答：测试在审的途中去看别的章。
                    if (slowFact && "请做事实审稿" in user) factGate.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    val answer = when {
                        "请做事实审稿" in user ->
                            """{"issues":[{"quote":"林月早就知道玉佩的来历","problem":"察觉表里林月第五章才知道玉佩来历","fix":"改成她只觉得眼熟"}]}"""
                        else -> """{"issues":[]}"""
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
            val v = w.volumes[0].id
            store.saveText(w.id, w.chapters[0].id, "沈默进了城。")
            store.addChapter(w.id, v)
            val (_, three) = store.addChapter(w.id, v)!!
            store.saveText(w.id, three.id, chapterText)
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 想要 |\n|---|---|\n| 林月（原创） | 一个家 |\n| 沈默 | 查清旧案 |\n")
            store.addPackDoc(
                w.id, PackKind.AWARENESS, "察觉表",
                "### 林月\n\n| 卷 | 线索 |\n| --- | --- |\n| 一 | 沈默腰间有半块玉佩，只觉得眼熟 |\n| 一（第五章） | 杜娘说出玉佩来历 |\n",
            )
            store.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋下 | 回收 | 状态 |\n| --- | --- | --- | --- |\n| 半块玉佩 | 第1章 | 第3章 | |\n")
            store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "对白要像人说话。")
            store.addPackDoc(w.id, PackKind.RULES, "检查规则", "禁用：笑了笑\n")
            workId = w.id
            chapterId = three.id
            firstId = w.chapters[0].id
        }

        override fun after() {
            factGate.countDown()
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
    fun threeGatesThenJumpBackToTheSentence() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        // 这一章计划回收的伏笔，编辑器上方提醒。
        waitForText("这一章计划回收的伏笔：半块玉佩")
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("三道审稿").performClick()
        waitForText("还没有审过")
        compose.onNodeWithText("开始审稿").performClick()
        waitForGate("事实关：1 处")
        waitForGate("写法关：没发现问题")
        waitForGate("规则关：1 处")
        // 规则关的命中只在编辑器的问题面板里列，这里给条数和入口，不再列一遍。
        waitForText("在编辑器的“问题”里看")
        assertTrue(compose.onAllNodesWithText("「笑了笑」1 处，不用", substring = true).fetchSemanticsNodes().isEmpty())

        // 事实关：设定包的知识库整份在前面（人物档案、察觉表），后面是这一章的提示：出场人物、还不该知道的事、该回收的伏笔。
        val (system, user) = asked.first { "请做事实审稿" in it.second }
        assertTrue("=== 知识库（整份） ===" in system && "【人物档案】（人物档案）" in system && "【察觉表】（察觉表）" in system)
        assertTrue("【这一章出场或提到的人物】林月" in system)
        assertTrue("杜娘说出玉佩来历" in system.substringAfter("还没发生"))
        assertTrue("【计划在这一章回收的伏笔】\n- 半块玉佩" in system)
        assertTrue(chapterText in user)
        // 写法关只拿写作规范，不拿设定。
        assertTrue(asked.first { "只按上面的写作规范" in it.second }.second.contains("对白要像人说话"))
        // 审完存下来了。
        val store = WorkStore(writingDir)
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.RULES) != null }
        assertEquals(chapterText.hashCode(), store.review(workId, chapterId)!!.textHash)

        // 点事实关的问题：回到编辑器，选中那一句。
        compose.onNodeWithText("察觉表里林月第五章才知道玉佩来历").performScrollTo().performClick()
        compose.onNodeWithText("去原文").performClick()
        waitFor {
            runCatching { compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] }.getOrNull() == TextRange(0, 11)
        }
    }

    @Test
    fun briefAndForeshadowScreens() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterBrief(workId, chapterId)) }
        waitForText("第 3 章 · 第 1 卷")
        waitForText("出场：林月")
        waitForText("还不该知道的")
        waitForText("计划这一章回收")

        compose.runOnUiThread { vm.open(Screen.Foreshadows(workId)) }
        waitForText("该回收了")
        compose.onNodeWithText("半块玉佩").performClick()
        compose.onNodeWithText("标为已回收").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.pack(workId).first { it.kind == PackKind.FORESHADOW }.let { "已回收" in store.packText(workId, it.id) } }
        // 旧表回收也记到账本；给 AI 的下一章资料不再把它当作未收的伏笔。
        val closed = store.ledger(workId).single { it.kind == LedgerKind.PROMISE && it.text == "半块玉佩" }
        assertTrue(closed.done)
        assertEquals(3, closed.closed)
        assertTrue(store.brief(workId, chapterId)!!.ledger?.open.orEmpty().none { it.id == closed.id })
        waitForText("已回收")
    }

    @Test
    fun foreshadowPageUsesLedgerStateAndCanCloseANewLedgerPromise() {
        val store = WorkStore(writingDir)
        store.applyLedger(
            workId,
            listOf(
                // 来源表还写着未收，当前账本已收；页面必须只保留账本的那一份状态。
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "半块玉佩", from = 1, payoff = 3, done = true, closed = 2)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "合成样本：未送出的纸鹤", from = 1, payoff = 3)),
            ),
            "合成样本",
        )
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Foreshadows(workId)) }
        waitForText("合成样本：未送出的纸鹤")
        assertEquals(1, compose.onAllNodesWithText("半块玉佩").fetchSemanticsNodes().size)
        compose.onNodeWithText("合成样本：未送出的纸鹤").performClick()
        compose.onNodeWithText("标为已回收").performClick()
        waitFor { store.ledger(workId).all { it.kind != LedgerKind.PROMISE || it.done } }
        assertTrue(store.brief(workId, chapterId)!!.ledger?.open.orEmpty().isEmpty())
    }

    @Test
    fun requestingAnAiFixDoesNotMarkAnUnacceptedIssueFixed() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("这一章计划回收的伏笔")
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("三道审稿").performClick()
        waitForText("还没有审过")
        compose.onNodeWithText("开始审稿").performClick()
        waitForGate("事实关：1 处")
        waitForGate("规则关：1 处")
        compose.onNodeWithText("察觉表里林月第五章才知道玉佩来历").performScrollTo().performClick()
        compose.onNodeWithText("按这条让 AI 改").performClick()
        waitFor { vm.writingAi.state.value?.loading == false }
        compose.runOnUiThread { vm.writingAi.close() }
        assertTrue(store.review(workId, chapterId)!!.gate(ReviewGate.FACT)!!.issues.single().open)
        assertEquals(chapterText, store.text(workId, chapterId))
    }

    @Test
    fun briefListsTheLedgerUpToThisChapter() {
        WorkStore(writingDir).applyLedger(
            workId,
            listOf(
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = "在渡口等船", subject = "林月", aspect = "位置", from = 1)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 1, payoff = 3)),
                LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "林月", from = 9)),
            ),
            "测试",
        )
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterBrief(workId, chapterId)) }
        waitForText("故事账本 · 到第 3 章为止")
        waitForText("林月·位置：在渡口等船")
        waitForText("顾长风说三日后再来")
        waitForText("该收")
        // 第 9 章才知道的，列为还不知道。
        waitForText("林月：杜娘是卧底")
        waitForText("还不知道 · 第 9 章起")
    }

    @Test
    fun anotherChaptersReviewIsLeftAlone() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        slowFact = true
        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, chapterId)) }
        waitForText("还没有审过")
        compose.onNodeWithText("开始审稿").performClick()
        waitForText("正在审事实关")
        // 第三章在审时去看第一章的审稿页：第一章还没审过，第三章照样在审。
        compose.runOnUiThread { vm.replaceTop(Screen.ChapterReview(workId, firstId)) }
        waitForText("还没有审过")
        assertEquals(ReviewGate.FACT, vm.writingAi.reviews.value[workId to chapterId]?.running)
        factGate.countDown()
        // 回到第三章：接着审完、存下。
        compose.runOnUiThread { vm.replaceTop(Screen.ChapterReview(workId, chapterId)) }
        waitForGate("事实关：1 处")
        waitForGate("规则关：1 处")
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.RULES) != null }
        assertEquals(null, store.review(workId, firstId))
    }

    @Test
    fun jumpingToASentenceThatChangedOnlyTellsTheAuthor() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("这一章计划回收的伏笔")
        // 开头十几个字一样、后面不一样：不按开头猜，不选中，也不让 AI 改。
        compose.runOnUiThread { vm.writing.jump.value = com.xycs.lingyuereader.Jump(chapterId, "林月早就知道玉佩的来历。她却一直没说", "改成她只觉得眼熟") }
        waitForText("正文里没找到这一句")
        val selection = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue(selection.collapsed)
        assertTrue(vm.writingAi.state.value == null)
        assertTrue(asked.isEmpty())
    }
}
