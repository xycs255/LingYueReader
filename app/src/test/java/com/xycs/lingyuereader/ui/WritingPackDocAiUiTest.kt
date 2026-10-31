package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
import com.xycs.lingyuereader.ai.PackDocAi
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.PackImport
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

/**
 * 设定文档的 AI 帮手（假 AI）：新建时起草、空文档起草，按要求改看对比、采纳，精简看核对、采纳；
 * 每样都是先给看、采纳才写入，写入前的一版在历史版本里能回退。首页放不下的文档旁边有“精简”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingPackDocAiUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var factionId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val faction = "## 三大势力\n- 青云宗：宗主顾长风，弟子三百人。\n- 漕帮：帮主钱二爷，五十三岁。\n- 官府：知县老周。\n"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    prompts += user
                    val answer = when {
                        "请起草" in user -> "## 林月\n想要：找到父亲\n害怕：父亲真的死了\n标志物：旧灯笼"
                        "按作者的要求改这份文档" in user -> faction.replace("- 官府：知县老周。", "- 官府：知县老周，四十岁。")
                        "精简到约" in user -> "## 三大势力\n- 青云宗：顾长风，三百人。\n- 漕帮：钱二爷。\n- 官府：老周。"
                        else -> "好"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop")))
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
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("渡灯")
            store.renameChapter(w.id, w.chapters[0].id, "第一章 雾里渡口")
            store.saveText(w.id, w.chapters[0].id, "卯时还没到，青石镇的雾就从河面上漫过来了。")
            store.saveCard(w.id, w.chapters[0].id, ChapterCard(summary = "林月去渡口打听父亲的下落。"))
            factionId = store.addPackDoc(w.id, PackKind.CANON, "势力", faction).id
            workId = w.id
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

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForEnabled(text: String) = waitFor { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun open(screen: Screen) {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(screen) }
    }

    private fun history(docId: String): List<String> {
        val store = WorkStore(writingDir)
        val key = store.docKey(docId)
        return store.history(workId, key).mapNotNull { store.snapshotText(workId, key, it.at) }
    }

    @Test
    fun newDocCanBeDraftedByAiAndOnlyWrittenAfterUseThis() {
        open(Screen.WorkPack(workId))
        waitFor { compose.onAllNodesWithText("文档分类").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("新建文档").performClick()
        waitForText("让 AI 起草")
        compose.onNodeWithText("让 AI 起草").performClick()
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("林月")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("主角的金手指")
        compose.onNodeWithText("新建并起草").performClick()

        // 新文档打开就起草；结果先预览，文档里还是模板。
        waitForEnabled("用这个")
        waitForText("标志物：旧灯笼")
        val prompt = prompts.single()
        assertTrue("主角的金手指" in prompt)
        assertTrue(PackKind.CHARACTER.hint in prompt)
        assertTrue("林月去渡口打听父亲的下落" in prompt)
        assertFalse("卯时还没到" in prompt)
        val store = WorkStore(writingDir)
        val doc = store.pack(workId).single { it.title == "林月" }
        assertEquals(PackImport.template(PackKind.CHARACTER), store.packText(workId, doc.id))

        compose.onNodeWithText("用这个").performClick()
        waitFor { WorkStore(writingDir).packText(workId, doc.id) == "## 林月\n想要：找到父亲\n害怕：父亲真的死了\n标志物：旧灯笼" }
        // 写入前的一版（模板）在历史版本里，能回退。
        waitFor { PackImport.template(PackKind.CHARACTER) in history(doc.id) }
        waitForText("写入前的一版存进了历史版本")
    }

    @Test
    fun emptyDocOffersDraftAndCanBeRedone() {
        val id = WorkStore(writingDir).addPackDoc(workId, PackKind.CANON, "地理", "").id
        open(Screen.PackDoc(workId, id))
        waitForText("还是空的")
        compose.onNodeWithText("让 AI 起草").performClick()
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("青石镇一带的河道")
        compose.onNodeWithText("起草").performClick()
        waitForEnabled("用这个")
        // 重来：再发一次；改一改要求：带着上次的要求回到对话框。
        compose.onNodeWithText("重来").performClick()
        waitFor { prompts.size == 2 }
        waitForEnabled("改一改要求")
        compose.onNodeWithText("改一改要求").performClick()
        waitForText("青石镇一带的河道")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃").performClick()
        waitForText("还是空的")
        assertEquals("", WorkStore(writingDir).packText(workId, id))
    }

    @Test
    fun reviseShowsTheChangedLinesAndAcceptingKeepsTheOldVersion() {
        open(Screen.PackDoc(workId, factionId))
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        // 作者先手写一行（还没存历史版本），再让 AI 改：写入前的这一版要留进历史版本。
        val typed = faction + "- 散修：无门无派。"
        compose.onNodeWithContentDescription("文档内容").performTextInputSelection(TextRange(faction.length))
        compose.onNodeWithContentDescription("文档内容").performTextInput("- 散修：无门无派。")
        waitFor { WorkStore(writingDir).packText(workId, factionId) == typed }
        assertFalse(typed in history(factionId))
        compose.onNodeWithContentDescription("AI 改").performClick()
        waitForText("AI 按要求改")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("补上各角色的年龄")
        compose.onNodeWithText("改").performClick()
        waitForEnabled("采纳")
        // 对比：改了的那一行原稿、新稿各列一次，没改的行照常显示。
        waitForText("原稿中")
        waitForText("新稿中")
        waitForText("知县老周，四十岁。")
        waitForText("（改全文）")
        assertEquals(typed, WorkStore(writingDir).packText(workId, factionId))
        compose.onNodeWithText("采纳").performClick()
        val after = faction.replace("- 官府：知县老周。", "- 官府：知县老周，四十岁。").trimEnd()
        waitFor { WorkStore(writingDir).packText(workId, factionId) == after }
        waitFor { typed in history(factionId) }
        // 提示里能撤回：换回写入前的那一版。
        waitForText("撤回")
        compose.onNodeWithText("撤回").performClick()
        waitFor { WorkStore(writingDir).packText(workId, factionId) == typed }
    }

    @Test
    fun reviseOnlyTheSelection() {
        open(Screen.PackDoc(workId, factionId))
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val at = faction.indexOf("- 官府")
        compose.onNodeWithContentDescription("文档内容").performTextInputSelection(TextRange(at, faction.indexOf('\n', at)))
        compose.onNodeWithContentDescription("AI 改").performClick()
        waitForText("只改选中的")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("补上年龄")
        compose.onNodeWithText("改").performClick()
        waitForEnabled("采纳")
        assertTrue("要改的这一段：\n- 官府：知县老周。" in prompts.single())
    }

    @Test
    fun condenseChecksNamesAndNumbersBeforeWriting() {
        open(Screen.PackDoc(workId, factionId))
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("AI 精简").performClick()
        waitForText("精简到约多少字")
        // 放得下的文档默认压到原长一半。
        waitForText(PackDocAi.condenseTarget("势力", WordCount.of(faction), null).toString())
        compose.onNodeWithText("精简").performClick()
        waitForEnabled("采纳")
        // 本机核对：新稿里丢了年龄、“宗主”这些，列出来。
        waitForText("新稿里找不到")
        waitForText("五十三岁")
        assertTrue("五十三岁" in prompts.single())
        assertEquals(faction, WorkStore(writingDir).packText(workId, factionId))
        compose.onNodeWithText("采纳").performClick()
        waitFor { WorkStore(writingDir).packText(workId, factionId).startsWith("## 三大势力\n- 青云宗：顾长风，三百人。") }
        waitFor { faction in history(factionId) }
    }

    @Test
    fun overBudgetDocsOfferCondenseOnThePackHome() {
        // 知识库上限压得很小：常用的“势力”放不下，首页标红，旁边给“精简”。
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.writingAi.updateAssist(workId) { it.copy(knowledgeLimit = 40) } }
        waitFor { WorkStore(writingDir).assist(workId).knowledgeLimit == 40 }
        open(Screen.WorkPack(workId))
        waitForText("放不下")
        compose.onNodeWithContentDescription("精简“势力”").performScrollTo().performClick()
        // 直接进文档、弹精简，默认字数按预算算。
        waitFor { vm.stack.value.last() is Screen.PackDoc }
        assertEquals(Screen.PackDoc(workId, factionId, condense = true), vm.stack.value.last())
        waitForText("精简到约多少字")
        // 知识库上限 40 字、别的常用文档都放不下：默认压到 40 字（原文比它长）。
        waitFor { compose.onAllNodesWithText("40").fetchSemanticsNodes().isNotEmpty() }
    }
}
