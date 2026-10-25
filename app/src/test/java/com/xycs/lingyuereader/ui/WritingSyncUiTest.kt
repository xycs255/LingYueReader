package com.xycs.lingyuereader.ui

import android.content.Context
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

/** 写完一章，整理进设定包：编辑器菜单 → 结果页三组 → 勾选、改 → 写进设定包；作品页的“未整理”；定稿时的提示。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingSyncUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val text = "渡口起了雾。\n杜娘把茶碗推给林月，说老周昨夜里走了。\n顾长风在门外喊她“月丫头”。\n林月这才知道沈默是朝廷的卧底。"

    private val answer = JSONObject()
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

    private fun press(t: String) {
        waitFor { seen(t) }
        compose.onNodeWithText(t).performClick()
    }

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
        assertTrue(seen("写进设定包（3 条）"))

        // 不要“渡口”这一条；杜娘那条点开改一改。
        seen("新地名：渡口")
        compose.onNodeWithContentDescription("勾选：新地名：渡口").performClick()
        waitFor { seen("写进设定包（2 条）") }
        press("新人物：杜娘")
        waitFor { compose.onAllNodes(hasSetTextAction() and hasText("| 杜娘 |", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("| 杜娘 |", substring = true)).performTextReplacement("| 杜娘 | 渡口茶馆的老板娘，消息灵通 |")
        assertTrue(seen("依据：「杜娘把茶碗推给林月」"))

        press("写进设定包（2 条）")
        val people = store.pack(workId).first { it.kind == PackKind.CHARACTER }
        waitFor { "消息灵通" in store.packText(workId, people.id) }
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 沈默 | 镖局的账房 |\n| 杜娘 | 渡口茶馆的老板娘，消息灵通 |", store.packText(workId, people.id))
        waitFor { store.pack(workId).any { it.kind == PackKind.AWARENESS } }
        val awareness = store.pack(workId).single { it.kind == PackKind.AWARENESS }
        assertTrue("| 林月 | 沈默是朝廷的卧底 | 第12章 | 杜娘说漏了嘴 |" in store.packText(workId, awareness.id))
        // 没勾的没写：渡口不进硬性设定，称谓与语气照旧。
        assertEquals("## 地名\n- 青石镇：林月的家乡", store.packText(workId, store.pack(workId).single { it.kind == PackKind.CANON }.id))
        assertEquals("## 顾长风\n顾长风叫林月“林姑娘”。", store.packText(workId, store.pack(workId).single { it.kind == PackKind.VOICE }.id))
        // 原来的人物档案存了历史版本；变更登记里每条一笔。
        assertTrue(store.history(workId, store.docKey(people.id)).isNotEmpty())
        waitFor { store.changes(workId).size == 2 }
        val changes = store.changes(workId)
        assertTrue(changes.all { it.reason.startsWith("整理第12章：") })
        // 回到编辑器，提示写了几条进哪几份。
        waitForText("写进了 2 条")
        waitFor { store.assist(workId).synced.containsKey(chapterId) }

        // 作品页：这一章不显示“未整理”；正文改过以后又显示。
        compose.runOnUiThread { vm.back(); vm.open(Screen.Work(workId)) }
        waitForText("第十二章 渡口")
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        assertFalse(has("未整理"))
        compose.runOnUiThread { vm.writing.save(workId, chapterId, text + "\n雾散了。") }
        waitForText("未整理")
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
        press("写进设定包（4 条）")
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
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("章节状态").performClick()
        waitForText("的状态")
        compose.onAllNodesWithText("定稿").onLast().performClick()
        compose.onNodeWithText("好").performClick()
        waitForText("要不要顺手整理进设定包？")
        // 勾“不再提示”，这次“整理”：打开结果页，以后不再问。
        compose.onNodeWithText("不再提示").performClick()
        compose.onNodeWithText("整理").performClick()
        waitForText("新人物：杜娘")
        waitFor { !store.assist(workId).syncTip }
        assertTrue(store.get(workId)!!.chapter(chapterId)!!.locked)
    }
}
