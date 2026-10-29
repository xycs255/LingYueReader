package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.ReviewEvidence
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

/** 写本章时 AI 先用工具读上一章，再写；作者看得到它查了什么。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingPackAiUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    // 写本章前整理上一章交接卡的那一次另算（见 HandoffFlowTest）：这里只看写正文的请求。
                    val handoff = (0 until msgs.length()).any { "交给下一章接着写" in msgs.getJSONObject(it).optString("content") }
                    if (!handoff) bodies += body
                    val looked = (0 until msgs.length()).any { msgs.getJSONObject(it).getString("role") == "tool" }
                    val message = if (handoff) {
                        JSONObject().put("role", "assistant").put("content", """{"scene":"茶馆","people":[],"mood":"","open":[],"hook":""}""")
                    } else if (!looked && body.has("tools")) {
                        JSONObject().put("role", "assistant").put("content", "我先看看第一章。").put(
                            "tool_calls",
                            JSONArray().put(JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "read_chapter").put("arguments", "{\"chapter\":\"1\"}"))),
                        )
                    } else {
                        JSONObject().put("role", "assistant").put("content", "沈默推开茶馆的门，雪还在下。")
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", if (looked || handoff) "stop" else "tool_calls")))
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
            val w = store.create("长夜")
            store.renameChapter(w.id, w.chapters[0].id, "第一章 进城")
            store.saveText(w.id, w.chapters[0].id, "沈默进了城，在杜娘的茶馆落脚。")
            store.setStatus(w.id, w.chapters[0].id, ChapterStatus.FINAL)
            val (_, two) = store.addChapter(w.id, w.volumes[0].id, "第二章 雪夜")!!
            store.addPackDoc(w.id, PackKind.INSTRUCTION, "使用说明", "先读本说明，再按需读取细纲。")
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 想要 |\n|---|---|\n| 沈默 | 查清旧案 |\n| 杜娘 | 保住茶馆 |\n")
            store.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "- **第二章 雪夜**：沈默夜里出门；钩子：门外有脚步声。\n")
            workId = w.id
            chapterId = two.id
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

    @Test
    fun theAiReadsWhatItNeedsThenWrites() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.let { !it.loading && it.text.isNotEmpty() } == true }
        // 查之前说的“我先看看第一章。”不算正文。
        assertEquals("沈默推开茶馆的门，雪还在下。", vm.writingAi.drafts.value.getValue(workId to chapterId).text)

        val first = bodies.first()
        val system = first.getJSONArray("messages").getJSONObject(0).getString("content")
        // 写正文：先是合写搭档的身份和作者的说明，再是按这一章挑的资料（出场人物的人物卡）；知识库不整份给，
        // 细纲在能查的资料里、AI 自己读；这一章的细纲在请求最后的任务卡里。
        assertTrue(system.startsWith("你是和作者一起写这部小说"))
        assertTrue(system.indexOf("【使用说明】") in 0 until system.indexOf("【人物·沈默】"))
        assertTrue("《第一卷细纲》" in system)
        assertFalse("=== 知识库（整份） ===" in system)
        val user = first.getJSONArray("messages").let { m -> (0 until m.length()).map { m.getJSONObject(it) }.last { it.getString("role") == "user" } }.getString("content")
        assertTrue(user.substringAfter(WritePrompts.CARD_HEADER).contains("沈默夜里出门"))
        assertEquals(3, first.getJSONArray("tools").length())
        // 工具读到的第一章交回给 AI 了。
        val second = bodies[1].getJSONArray("messages")
        val chapterText = "沈默进了城，在杜娘的茶馆落脚。"
        val returned = second.getJSONObject(second.length() - 1).getString("content")
        val firstChapterId = WorkStore(writingDir).get(workId)!!.chapters.first().id
        assertEquals(chapterText, returned.substringBefore('\n'))
        assertEquals("[来源 ${ReviewEvidence.ref("C", firstChapterId, chapterText)}]", returned.substringAfter('\n'))
        // 作者看得到 AI 查了什么。
        assertEquals(listOf("读正文 第一章 进城"), vm.writingAi.lastRequest.value!!.lookups)

        // 本章资料页列出 AI 拿到的设定包。
        compose.runOnUiThread { vm.open(Screen.ChapterBrief(workId, chapterId)) }
        waitFor { compose.onAllNodesWithText("AI 拿到的设定包").fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithText("知识库 · 整份 · 人物档案").fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithText("按需查阅 · 卷纲细纲").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun disablingTheReviewWorkflowStillKeepsUnapprovedProseOutOfProjectTools() {
        val store = WorkStore(writingDir)
        val firstChapter = store.get(workId)!!.chapters.first().id
        store.setStatus(workId, firstChapter, ChapterStatus.DRAFT, reason = "继续修订前章")
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId, workflow = false) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.let { !it.loading && it.text.isNotBlank() } == true }
        val messages = bodies[1].getJSONArray("messages")
        val returned = messages.getJSONObject(messages.length() - 1).getString("content")
        assertEquals("tool", messages.getJSONObject(messages.length() - 1).getString("role"))
        assertFalse(returned.contains("沈默进了城，在杜娘的茶馆落脚。"))
    }
}
