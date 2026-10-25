package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
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

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val system = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += system to user
                    val answer = when {
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
        compose.onNodeWithText("AI 写本章").performClick()
        waitForText("这一章想怎么写")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("多写对白")
        compose.onNodeWithText("写本章").performClick()
        waitForText("杜娘把门打开，雪灌了进来。")
        // 章名和“本章完”去掉了；草稿存下来了。
        waitFor { store.draft(workId, c2) == "杜娘把门打开，雪灌了进来。\n“谁？”她问。" }
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
    fun aReviewAnswerInTheWrongShapeIsReportedNotIgnored() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, c2)) }
        waitForText("还没有审过")
        compose.onNodeWithText("开始审稿").performClick()
        waitForText("一、事实关 · 出错")
        waitForText("二、写法关 · 出错")
        waitForText("AI 的回答不是要求的格式")
        waitForText("三、规则关 · 1 处")
    }
}
