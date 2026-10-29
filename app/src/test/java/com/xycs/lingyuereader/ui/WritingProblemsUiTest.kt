package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

/**
 * 编辑器里的“问题”面板：检查规则、AI 腔、写作建议、审稿没处理的合在一起列；状态行合成“N 处问题”，点了打开面板；
 * 顶上按来源筛选、带条数；每类原有的操作（跳到位置、以后都不要、改 / 忽略、已改 / 不改）照常能用，审稿的状态和审稿页同步。
 * 另外：AI 条里没有“矛盾检查”，工具“查”里是“问题”；写作建议设置里没有“查设定”，旧设置里带着它也照常读。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingProblemsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 发给假服务商的每个请求里用户那条消息。 */
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 最后一个句号留给测试里打：写作建议只查打开以后改过的段。 */
    private val text = "林月嘴角勾起，推开客栈的门。\n老周点了点头，没说话。\n沈默再门口等着，天色以晚"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    prompts += user
                    val answer = when {
                        "只找明显的错误" in user || "只找下面这几类问题" in user -> JSONObject().put("issues", JSONArray()
                            .put(JSONObject().put("p", 1).put("wrong", "再门口").put("right", "在门口").put("reason", "错别字").put("kind", "TYPO"))
                            .put(JSONObject().put("p", 1).put("wrong", "以晚").put("right", "已晚").put("reason", "用错的词").put("kind", "TYPO"))).toString()
                        else -> "好。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                start()
            }
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            File(files, "ai-usage.json").delete()
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            workId = w.id
            chapterId = w.chapters[0].id
            store.saveText(workId, chapterId, text)
            store.addPackDoc(workId, PackKind.RULES, "检查规则", "禁用：点了点头\n")
            // 旧版本存下的设置：写作建议开着，只勾了错字和设定。
            store.updateAssist(workId) { it.copy(realtime = true, suggestKinds = listOf("TYPO", "CANON")) }
            // 上次审稿：事实关一处没处理、一处已改；写法关一处没处理；规则关一处（和“规则”重复，不再列）。
            store.saveReview(
                workId, chapterId,
                ChapterReview(
                    text.hashCode(), System.currentTimeMillis(),
                    listOf(
                        GateResult(ReviewGate.FACT, listOf(
                            ReviewIssue("推开客栈的门", "察觉表里林月这时还没到客栈", "改成推开渡口的门"),
                            ReviewIssue("林月嘴角勾起", "林月这时不该笑", state = ReviewIssue.FIXED),
                        )),
                        GateResult(ReviewGate.CRAFT, listOf(ReviewIssue("老周点了点头，没说话。", "写作规范：动作拍不要连用"))),
                        GateResult(ReviewGate.RULES, listOf(ReviewIssue("点了点头", "「点了点头」1 处，不用"))),
                    ),
                ),
            )
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun waitForText(text: String) = waitFor { count(text) > 0 }

    private fun selection(): TextRange = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun range(of: String, text: String = this.text): TextRange = text.indexOf(of).let { TextRange(it, it + of.length) }

    private fun openEditor() {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默再门口等着")
    }

    /** 打最后一个句号，等写作建议回来：两条错字。 */
    private fun typeTheLastStop() {
        val node = compose.onNodeWithContentDescription("正文")
        node.performTextInputSelection(TextRange(text.length))
        node.performTextInput("。")
    }

    @Test
    fun theStatusLineAddsUpAndThePanelFiltersByKind() {
        openEditor()
        // 规则 1 + AI 腔 1 + 审稿没处理的 2（规则关不算，已改的不算）。分开的“条不合规则”“处 AI 腔”都没了。
        waitForText("4 处问题")
        assertEquals(0, count("条不合规则") + count("处 AI 腔") + count("条写作建议"))
        typeTheLastStop()
        waitForText("6 处问题")
        // 旧设置里的“设定”读的时候去掉了：只查错字，照常查。
        assertTrue(prompts.single().contains("只找明显的错误"))

        compose.onNodeWithText("6 处问题", substring = true).performClick()
        waitForText("全部 6")
        listOf("规则 1", "AI 腔 1", "建议 2", "审稿 2").forEach { waitForText(it) }
        // 全部：按在正文里的先后排，最前面是第一句里的 AI 腔，接着是审稿事实关那一句。
        waitForText("「嘴角勾起」")
        waitForText("察觉表里林月这时还没到客栈")
        // 各筛选只列这一类。
        compose.onNodeWithText("规则 1").performClick()
        waitForText("「点了点头」1 处，不用")
        waitForText("对白") // 规则下多一行本章统计
        waitFor { count("「嘴角勾起」") == 0 && count("察觉表里林月这时还没到客栈") == 0 }
        compose.onNodeWithText("AI 腔 1").performClick()
        waitForText("「嘴角勾起」")
        waitFor { count("「点了点头」1 处，不用") == 0 }
        compose.onNodeWithText("建议 2").performClick()
        waitFor { compose.onAllNodesWithContentDescription("错字类建议").fetchSemanticsNodes().size == 2 }
        waitForText("用错的词")
        assertEquals(0, count("「嘴角勾起」"))
        // 筛“审稿”：只剩审稿的两条，说明是哪一次审的。规则关的那条和“规则”重复、已改的那条处理过了：都不列。
        compose.onNodeWithText("审稿 2").performClick()
        waitForText("上次审稿")
        waitForText("写作规范：动作拍不要连用")
        assertEquals(0, count("林月这时不该笑") + count("「点了点头」1 处，不用") + count("用错的词"))
        assertEquals(1, compose.onAllNodesWithContentDescription("审稿事实关").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("审稿写法关").fetchSemanticsNodes().size)
        // 收起再从工具“查”里打开：是同一个面板。
        compose.onNodeWithContentDescription("收起").performClick()
        waitFor { count("上次审稿") == 0 }
        compose.onNodeWithText("查").performClick()
        waitFor { compose.onAllNodesWithContentDescription("问题").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithContentDescription("规则检查").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("问题").performClick()
        waitForText("全部 6")
    }

    @Test
    fun eachKindKeepsItsOwnActions() {
        val store = WorkStore(writingDir)
        openEditor()
        waitForText("4 处问题")
        typeTheLastStop()
        waitForText("6 处问题")
        compose.onNodeWithText("6 处问题", substring = true).performClick()
        waitForText("全部 6")

        // 规则：跳到位置。
        compose.onNodeWithText("规则 1").performClick()
        compose.onNodeWithText("跳到位置").performClick()
        waitFor { selection() == range("点了点头") }

        // AI 腔：跳到位置；以后都不要（加进黑名单，同一处也算黑名单命中）。
        compose.onNodeWithText("AI 腔 1").performClick()
        compose.onNodeWithText("跳到位置").performClick()
        waitFor { selection() == range("嘴角勾起") }
        compose.onNodeWithText("以后都不要").performClick()
        waitFor { store.assist(workId).banned == listOf("嘴角勾起") }
        waitForText("AI 腔 2")
        waitForText("7 处问题")

        // 建议：改一条、忽略一条。
        compose.onNodeWithText("建议 2").performClick()
        waitForText("用错的词")
        compose.onAllNodesWithText("改").onFirst().performClick()
        waitFor { store.text(workId, chapterId).contains("沈默在门口等着") }
        waitForText("建议 1")
        compose.onNodeWithText("忽略").performClick()
        waitForText("建议 0")
        waitForText("这一类都处理了")
        assertTrue(store.text(workId, chapterId).endsWith("天色以晚。"))

        // 审稿：跳到原文；已改、不改写回同一份审稿结果。
        compose.onNodeWithText("审稿 2").performClick()
        waitForText("察觉表里林月这时还没到客栈")
        assertEquals(2, count("按这条让 AI 改"))
        compose.onAllNodesWithText("跳到原文").onFirst().performClick()
        waitFor { selection() == range("推开客栈的门") }
        compose.onAllNodesWithText("已改").onFirst().performClick()
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.FACT)?.issues?.first()?.state == ReviewIssue.FIXED }
        waitForText("审稿 1")
        compose.onNodeWithText("不改").performClick()
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.CRAFT)?.issues?.single()?.state == ReviewIssue.IGNORED }
        waitForText("审稿 0")
        // 审稿页上同样是处理过了。
        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, chapterId)) }
        waitFor { compose.onAllNodesWithContentDescription("事实关：都处理了").fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithContentDescription("写法关：都处理了").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theReviewPageSendsRuleHitsToThePanel() {
        openEditor()
        waitForText("4 处问题")
        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, chapterId)) }
        // 审稿页读的是同一份结果：事实关还有一处没处理。
        waitFor { compose.onAllNodesWithContentDescription("事实关：1 处没处理").fetchSemanticsNodes().isNotEmpty() }
        // 规则关只给条数和入口，不再列一遍。
        assertEquals(0, count("「点了点头」1 处，不用"))
        compose.onNodeWithText("在编辑器的“问题”里看").performScrollTo().performClick()
        waitForText("规则 1")
        waitForText("「点了点头」1 处，不用")
        waitFor { count("察觉表里林月这时还没到客栈") == 0 }
    }

    @Test
    fun aiBarHasNoConsistencyCheckAndSettingsHaveNoCanon() {
        openEditor()
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("改稿").performClick()
        compose.onNodeWithText("读者点评").performScrollTo()
        assertEquals(0, count("矛盾检查"))

        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        compose.onNodeWithText("查口吻").performScrollTo()
        assertEquals(0, compose.onAllNodesWithText("查设定").fetchSemanticsNodes().size)
        // 旧设置里只勾了错字和设定：读出来只剩错字。
        assertEquals(listOf("TYPO"), WorkStore(writingDir).assist(workId).suggestKinds)
        compose.onNodeWithText("查规范").performScrollTo().performClick()
        waitFor { WorkStore(writingDir).assist(workId).suggestKinds == listOf("TYPO", "STYLE") }
    }
}
