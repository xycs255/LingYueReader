package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.StyleSamplesTest
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

/**
 * 花费透明和文风范例的界面：假 AI 返回 usage，AI 续写面板写完显示“约 ¥… · 缓存命中 …%”，
 * “最近一次发给 AI 的内容”写着输入、命中、输出和钱，用量页按功能、按作品列出；文风范例设置和“看看挑了哪几段”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class AiUsageUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他，说老周昨夜就死了。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    ex.requestBody.readBytes()
                    val bytes = JSONObject()
                        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "雪还在下。\n沈默在门口站了一会儿。")).put("finish_reason", "stop")))
                        .put("usage", JSONObject().put("prompt_tokens", 12_000).put("completion_tokens", 300).put("prompt_cache_hit_tokens", 11_000).put("prompt_cache_miss_tokens", 1_000))
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
            // 局域网模型，自己填了单价（人民币，每百万 token：输入 2、命中 0.2、输出 8）。
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model")
                .putString("prices", """{"local":{"input":2,"cached":0.2,"output":8,"usd":false}}""").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            val first = w.chapters[0].id
            store.renameChapter(w.id, first, "第一章 渡口")
            store.saveText(w.id, first, StyleSamplesTest.chapterText(), count = false)
            store.setStatus(w.id, first, ChapterStatus.FINAL)
            val (_, second) = store.addChapter(w.id, w.volumes[0].id, "第二章 茶馆")!!
            store.saveText(w.id, second.id, text, count = false)
            workId = w.id
            chapterId = second.id
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

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun costShowsAfterWritingAndInUsagePages() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 接着写").performClick()
        compose.onNodeWithText("写下一场").performClick()
        waitForText("AI 写了")
        // 续写面板写完：一行小字，花了约多少、缓存命中多少。
        waitForText("缓存命中 92%")
        compose.onAllNodesWithText("约 ¥", substring = true).onFirst()
        val inline = vm.writingAi.inline.value!!
        assertEquals(12_000L, inline.usage!!.input)
        assertEquals((1_000 * 2.0 + 11_000 * 0.2 + 300 * 8.0) / 1_000_000, inline.cost!!.cny, 1e-12)
        // 记进了账本：今天、这部作品、“AI 接着写”。
        waitFor { vm.aiUsage.value.days[LocalDate.now()].orEmpty().any { it.work == workId && it.feature == AiFeature.INLINE.name } }
        compose.onNodeWithText("撤回").performClick()

        // 写作 AI 设置：最近一次发给 AI 的内容里有用量，“用量”一行写着今天约多少。
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitForText("最近一次发给 AI 的内容")
        compose.onNodeWithText("最近一次发给 AI 的内容").performScrollTo().performClick()
        waitForText("输入 1.2 万 token（缓存命中 1.1 万）")
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithText("用量").performScrollTo().performClick()

        // 用量页：先只看这部作品，按功能；切到全部再看按作品。
        waitForText("按功能")
        assertTrue(compose.onAllNodesWithText("AI 接着写", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("全部作品").performClick()
        waitForText("按作品")
        // 分段控件上一个，按作品里一个。
        waitFor { compose.onAllNodesWithText("《长夜》").fetchSemanticsNodes().size >= 2 }

        // 我的 → AI 助手：价格与用量。
        compose.runOnUiThread { vm.open(Screen.Ai) }
        waitForText("价格与用量")
        compose.onNodeWithText("单价（你填的）").performScrollTo()
        waitForText("输入 ¥2 · 命中 ¥0.2 · 输出 ¥8")
    }

    @Test
    fun styleSamplesSettingsListAndSkip() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitForText("用定稿章当文风范例")
        compose.onNodeWithText("看看挑了哪几段").performScrollTo().performClick()
        waitForText("出自「第一章 渡口」")
        val before = vm.writingAi.stylePickBlocking()
        compose.onAllNodesWithText("不用这段").onFirst().performClick()
        waitFor { vm.writingAi.assists.value[workId]?.sampleSkip?.size == 1 }
        // 记住了：再挑不再有它。
        val skipped = vm.writingAi.assists.value[workId]!!.sampleSkip.single()
        assertTrue(before.any { it.key == skipped })
        waitForText("恢复成自动挑的")
    }

    /** 测试里同步挑一次（看“不用这段”前有哪几段）。 */
    private fun com.xycs.lingyuereader.WritingAi.stylePickBlocking() = kotlinx.coroutines.runBlocking { stylePick(workId, null, local = false).samples }
}
