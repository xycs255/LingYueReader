package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import com.xycs.lingyuereader.FinishStage
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.LedgerKind
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
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/**
 * “这章写完了”：智能推荐什么时候出现；收尾页全流程（一次请求拿到整理和摘要 → 确认 → 完成收尾，写进账本和设定包、细纲卡有摘要、
 * 章节定稿、不弹整理提示）；勾了审稿、审出问题时停下，选“继续收尾”能走完；AI 没配置时只能定稿；写入中途一步没成，重试不重复写。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingFinishUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 收到的请求：最后那句用户的话。 */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "渡口起了雾。\n杜娘把茶碗推给林月，说老周昨夜里走了。\n顾长风在门外喊她“月丫头”。\n林月这才知道沈默是朝廷的卧底。"
    private val summary = "林月在渡口茶馆从杜娘口中得知老周昨夜死了，顾长风在门外叫她；她这才知道沈默是朝廷的卧底。"

    private fun isSync(user: String) = "作者写完了这一章，请整理它" in user
    private fun isSummary(user: String) = user.trimEnd().endsWith("只输出摘要本身。")
    private fun isReview(user: String) = "{\"issues\":" in user

    private fun syncAnswer(withSummary: Boolean) = JSONObject()
        .put("new", JSONArray().put(JSONObject().put("type", "人物").put("name", "杜娘").put("kind", "人物档案").put("identity", "渡口茶馆老板娘").put("quote", "杜娘把茶碗推给林月")))
        .put("conflicts", JSONArray())
        .put("events", JSONArray().put(JSONObject().put("what", "老周昨夜死了").put("quote", "说老周昨夜里走了")))
        .put("awareness", JSONArray().put(JSONObject().put("who", "林月").put("what", "沈默是朝廷的卧底").put("since", "第12章").put("source", "杜娘说漏了嘴").put("quote", "林月这才知道沈默是朝廷的卧底。")))
        .apply { if (withSummary) put("summary", summary) }
        .toString()

    private val reviewAnswer = JSONObject().put(
        "issues",
        JSONArray()
            .put(JSONObject().put("quote", "渡口起了雾。").put("problem", "上一章说是晴天").put("fix", "交代天变了"))
            .put(JSONObject().put("quote", "顾长风在门外喊她“月丫头”。").put("problem", "设定里顾长风叫她林姑娘").put("fix", "统一称呼")),
    ).toString()

    private val seed = object : ExternalResource() {
        /** 方法名里带 “NoAi” 的测试不配置写作 AI。 */
        private var withAi = true

        override fun apply(base: Statement, description: Description): Statement {
            withAi = !description.methodName.orEmpty().contains("NoAi")
            return super.apply(base, description)
        }

        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val messages = body.getJSONArray("messages")
                    val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.optString("content")
                    // 收尾里的“整理交接卡”另算（见 WritingHandoffUiTest）：这里只数整理、摘要、审稿的请求。
                    val handoff = "交给下一章接着写" in user
                    if (!handoff) asked += user
                    val answer = when {
                        handoff -> """{"scene":"渡口","people":[],"mood":"","open":[],"hook":""}"""
                        isSync(user) -> syncAnswer("\"summary\"" in user)
                        isSummary(user) -> summary
                        isReview(user) -> reviewAnswer
                        else -> "好的。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop")))
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
            val ai = context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
            if (withAi) {
                ai.putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                    .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model")
            }
            ai.commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.renameChapter(w.id, w.chapters[0].id, "第十二章 渡口")
            store.saveText(w.id, w.chapters[0].id, text, count = false)
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 沈默 | 镖局的账房 |")
            store.addPackDoc(w.id, PackKind.CANON, "硬性设定", "## 地名\n- 青石镇：林月的家乡")
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

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val store get() = WorkStore(writingDir)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun hasDescription(t: String) = compose.onAllNodesWithContentDescription(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(t: String) = waitFor { has(t) }

    /** 收尾页是懒加载的列表：先滚到那一行（找不到不报错），再看在不在。 */
    private fun seen(t: String): Boolean {
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(t, substring = true)) }
        ShadowLooper.idleMainLooper()
        return has(t)
    }

    private fun press(t: String) {
        waitFor { seen(t) }
        compose.onNodeWithText(t).performClick()
    }

    private fun openEditor() {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("渡口起了雾")
    }

    /** 从编辑器右上角“更多”进收尾页。 */
    private fun openFinishFromMenu() {
        openEditor()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("这章写完了").performClick()
        waitForText("收尾要做的")
    }

    private fun stage() = vm.writingAi.finish.value?.stage

    @Test
    fun theRecommendationOffersFinishingOnceTheBeatsAreDone() {
        store.saveCard(workId, chapterId, ChapterCard(beats = "杜娘说老周走了\n林月识破沈默", beatsDone = listOf(1, 2)))
        openEditor()
        waitFor { hasDescription("智能推荐：这章写完了，细纲卡的 2 拍都写完了") }
        compose.onNodeWithContentDescription("智能推荐：这章写完了", substring = true).performClick()
        waitForText("收尾要做的")
        // 默认：定稿固定勾上不能取消，整理、摘要勾上，审稿不勾。
        compose.onNode(hasText("定稿") and isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNode(hasText("整理入账") and isToggleable()).assertIsOn()
        compose.onNode(hasText("写本章摘要") and isToggleable()).assertIsOn()
        compose.onNode(hasText("顺带三道审稿") and isToggleable()).assertIsOff()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun theRecommendationOffersFinishingOnceTheLengthIsReached() {
        store.addPackDoc(workId, PackKind.RULES, "检查规则", "字数：至少 20\n")
        // 还没到：推荐照旧是接着写。
        store.saveText(workId, chapterId, "渡口起了雾。", count = false)
        openEditor()
        waitFor { hasDescription("智能推荐：AI 接着写") }
        // 接着写到字数够了：推荐换成“这章写完了”。
        compose.onNodeWithContentDescription("正文").performTextReplacement(text)
        waitFor { hasDescription("智能推荐：这章写完了，已写") }
        // 工具“整理”组的第一格也是它。
        compose.onNodeWithContentDescription("打开四组工具").performClick()
        compose.onNodeWithText("整理").performClick()
        waitFor { hasDescription("这章写完了") }
    }

    @Test
    fun theWholeFlowSyncsSummarizesAndFinalizesInOneRequest() {
        openFinishFromMenu()
        press("开始")
        waitFor { seen("完成收尾") }
        // 整理和摘要合成了一次请求。
        assertEquals(1, asked.size)
        assertTrue(isSync(asked.single()) && "9. summary（本章摘要）" in asked.single())
        // 确认页：摘要预填 AI 写的（可以改），整理结果照整理页的条目列表。
        waitFor { compose.onAllNodes(hasSetTextAction() and hasText(summary)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(seen("新人物：杜娘"))
        assertTrue(seen("林月：沈默是朝廷的卧底"))

        press("完成收尾")
        waitFor { store.get(workId)!!.chapter(chapterId)!!.status == ChapterStatus.FINAL }
        // 回到编辑器：锁定了，没有弹“要不要顺手整理进设定包？”。
        waitForText("这一章定稿了")
        waitFor { stage() == FinishStage.DONE }
        waitForText("渡口起了雾")
        assertFalse(has("要不要顺手整理进设定包？"))
        assertEquals(summary, store.card(workId, chapterId).summary)
        assertEquals(setOf(LedgerKind.EVENT, LedgerKind.KNOW), store.ledger(workId).map { it.kind }.toSet())
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        assertTrue("杜娘" in store.packText(workId, people.id))
        waitFor { store.assist(workId).synced.containsKey(chapterId) }
        // 定稿照旧：存了历史版本，登记里有一笔定稿。
        assertTrue(store.changes(workId).any { it.kind == ChangeKind.FINALIZE && it.chapterId == chapterId })
        assertTrue(store.history(workId, chapterId).isNotEmpty())
        // 定稿后入口都不在了：更多菜单里没有“这章写完了”。
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("解锁修改")
        assertFalse(has("这章写完了"))
        assertEquals(1, asked.size)
    }

    @Test
    fun reviewIssuesStopTheFlowAndItCanGoOn() {
        openFinishFromMenu()
        compose.onNodeWithText("顺带三道审稿").performClick()
        waitFor { store.assist(workId).finish.review }
        press("开始")
        waitForText("审出 2 条问题")
        assertEquals(FinishStage.REVIEW_FOUND, stage())
        // 停在选择处：还没整理。
        assertTrue(asked.none(::isSync))
        assertTrue(has("回去改"))
        press("不管，继续收尾")
        waitFor { seen("完成收尾") }
        assertEquals(1, asked.count(::isSync))
        press("完成收尾")
        waitFor { store.get(workId)!!.chapter(chapterId)!!.locked }
        assertEquals(summary, store.card(workId, chapterId).summary)
        // 审稿结果照常存下，审稿页上看得到。
        assertTrue(store.review(workId, chapterId)!!.gates.sumOf { it.issues.size } >= 2)
    }

    @Test
    fun withNoAiOnlyFinalizingIsPossible() {
        openFinishFromMenu()
        compose.onNode(hasText("整理入账") and isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNode(hasText("写本章摘要") and isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNode(hasText("顺带三道审稿") and isToggleable()).assertIsOff().assertIsNotEnabled()
        assertTrue(has("没设置 AI"))
        press("开始")
        // 只有定稿：直接到确认，“完成收尾”就是定稿。
        waitFor { seen("完成收尾") }
        assertFalse(has("本章摘要"))
        assertFalse(has("整理结果"))
        press("完成收尾")
        waitFor { store.get(workId)!!.chapter(chapterId)!!.locked }
        assertTrue(asked.isEmpty())
        assertTrue(store.ledger(workId).isEmpty())
        assertEquals("", store.card(workId, chapterId).summary)
    }

    @Test
    fun aFailedWriteIsRetriedWithoutWritingTwice() {
        openFinishFromMenu()
        press("开始")
        waitFor { seen("完成收尾") }
        // 等确认的时候，作者在细纲卡页上自己写了摘要：写摘要那一步不盖掉它，停下。
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(summary = "沈默写的另一版摘要"))
        press("完成收尾")
        waitForText("把摘要写进细纲卡没成")
        // 整理条目已经写进去了；没定稿。
        val ledger = store.ledger(workId)
        val versions = store.ledgerVersions(workId).size
        val changes = store.changes(workId).size
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        val pack = store.packText(workId, people.id)
        assertEquals(2, ledger.size)
        assertFalse(store.get(workId)!!.chapter(chapterId)!!.locked)
        assertTrue(hasDescription("写入整理条目：好了"))
        // 确认页上现在给“用新的 / 留旧的”，默认留旧的；选用新的再试。
        waitFor { seen("细纲卡里已有：沈默写的另一版摘要") }
        compose.onNodeWithText("用新的").performClick()
        press("重试剩下的步骤")
        waitFor { store.get(workId)!!.chapter(chapterId)!!.locked }
        assertEquals(summary, store.card(workId, chapterId).summary)
        // 整理没有再写一遍：账本、账本的历史版本、设定包、整理的登记都和第一次一样。
        assertEquals(ledger.map { it.id }, store.ledger(workId).map { it.id })
        assertEquals(versions, store.ledgerVersions(workId).size)
        assertEquals(pack, store.packText(workId, people.id))
        assertEquals(changes + 1, store.changes(workId).size)
        assertEquals(1, store.changes(workId).count { it.kind == ChangeKind.FINALIZE })
        assertEquals(1, asked.size)
    }

    /** A 章收尾还没完（等着确认）时进 B 章的收尾页：不悄悄掐掉 A，说一声，可以去看看或先停下它。 */
    @Test
    fun anotherChapterWaitsForTheOneThatIsFinishing() {
        val other = store.addChapter(workId, store.get(workId)!!.volumes[0].id)!!.second.id
        store.renameChapter(workId, other, "第十三章 雾散")
        compose.runOnUiThread {
            vm.writingAi.openFinish(workId, chapterId)
            vm.writingAi.setFinishOptions(FinishOptions(sync = true, summary = true))
            vm.writingAi.startFinish()
        }
        waitFor { stage() == FinishStage.CONFIRM }
        compose.runOnUiThread { vm.open(Screen.ChapterFinish(workId, other)) }
        waitForText("《第十二章 渡口》正在收尾，等它完成或先停下")
        assertEquals(chapterId, vm.writingAi.finish.value?.chapterId)
        assertEquals(FinishStage.CONFIRM, stage())
        // 先停下它：A 回到勾选，B 打开。
        press("先停下它")
        waitForText("收尾要做的")
        assertEquals(other, vm.writingAi.finish.value?.chapterId)
        assertEquals(FinishStage.SETUP, stage())
    }
}
