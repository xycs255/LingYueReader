package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一键写本章的草稿页：空细纲卡点“写本章”，进度依次是拟细纲、拆节拍、写第几拍；写完上方列出“按这些写的”细纲和节拍，
 * AI 拟的挂小标签；作者在细纲卡里改过、确认过，标签就没了。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingOneClickUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val kinds: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 各类请求先卡住，测试看到对应的进度再放行。 */
    private val holds = mapOf("拟细纲" to CountDownLatch(1), "拆节拍" to CountDownLatch(1), "第1拍" to CountDownLatch(1))
    @Volatile private var holding = false
    private val beatText = mapOf(1 to "林月提灯到了渡口。", 2 to "沈默从船上跳下来。", 3 to "老周的灯灭了。")

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    val beat = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.toInt()
                    val kind = when {
                        "拟细纲卡" in user -> "拟细纲"
                        "拆成 3 到 8 拍" in user -> "拆节拍"
                        beat != null -> "第${beat}拍"
                        else -> "整章"
                    }
                    kinds += kind
                    if (holding) holds[kind]?.await(30, TimeUnit.SECONDS)
                    val answer = when {
                        kind == "拟细纲" -> "本章事实与结果：林月在渡口等到沈默\n后文承接边界：老周还活着\n视角人物：林月\n倒计时：无\n章末钩子：老周的灯灭了\n推进的线：明线"
                        kind == "拆节拍" -> "1. 林月提灯到渡口\n2. 沈默下船\n3. 老周的灯灭了"
                        beat != null -> beatText.getValue(beat)
                        else -> "整章写完。"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
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
            val w = store.create("渡口")
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            holds.values.forEach { it.countDown() }
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    /** 按钮可能先显示、等数据读好才可点（慢机器上更明显）：等它可点再点，点早了会被忽略。 */
    private fun waitForEnabled(text: String) = waitFor { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun shown(text: String, substring: Boolean = true) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = waitFor { shown(text) }

    @Test
    fun anEmptyCardIsPlannedSplitAndWrittenWithTheProgressInOrder() {
        holding = true
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        // 写之前就说清楚：AI 先拟细纲、拆节拍，另加两次小请求；开关可以关。
        waitForText("写本章时，AI 先拟细纲、拆节拍")
        waitForText("另加约 2 次小请求")
        waitForText("缺细纲、节拍时 AI 先补上")
        waitForEnabled("写本章")
        compose.onNodeWithText("写本章").performScrollTo().performClick()

        // 进度依次出现：拟细纲 → 拆节拍 → 写第 1 / 3 拍。
        waitForText("正在拟细纲")
        waitFor { kinds.toList() == listOf("拟细纲") }
        holds.getValue("拟细纲").countDown()
        waitForText("正在拆节拍")
        holds.getValue("拆节拍").countDown()
        waitForText("正在写第 1 / 3 拍")
        holds.getValue("第1拍").countDown()
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertEquals(listOf("拟细纲", "拆节拍", "第1拍", "第2拍", "第3拍"), kinds.toList())
        waitFor { store.draft(workId, chapterId) == beatText.values.joinToString("\n") }

        // 上方列出这一版按的细纲和节拍，AI 拟的、拆的挂小标签。
        waitForText("这一章是按下面的细纲和节拍写的")
        waitForText("林月在渡口等到沈默")
        waitForText("3. 老周的灯灭了")
        waitFor { shown("AI 拟的", substring = false) && shown("AI 拆的", substring = false) }
        assertTrue(shown("改细纲 / 节拍"))
        assertTrue(shown("按改过的重写"))
        val card = store.card(workId, chapterId)
        assertTrue(card.aiOutline && card.aiBeats)
    }

    @Test
    fun theTagsGoAwayOnceTheAuthorEditsOrConfirmsTheCard() {
        val store = WorkStore(writingDir)
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月在渡口等到沈默", hook = "老周的灯灭了",
            beats = "林月提灯到渡口\n沈默下船\n老周的灯灭了", aiOutline = true, aiBeats = true))
        store.saveDraft(workId, chapterId, beatText.values.joinToString("\n"))
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitForText("这一章是按下面的细纲和节拍写的")
        waitFor { shown("AI 拟的", substring = false) && shown("AI 拆的", substring = false) }

        // 去细纲卡：同样挂着标签；改一项事实，AI 拟的就没了；节拍点确认。
        compose.onNodeWithText("改细纲 / 节拍").performScrollTo().performClick()
        waitForText("确认这份细纲")
        // 细纲卡的几项是小卡片：先点开“本章事实与结果”那张，下面才有文本框。
        compose.onNodeWithText("本章事实与结果").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("林月在渡口等到沈默")).performTextReplacement("林月在渡口等到沈默，老周没来")
        waitFor { !store.card(workId, chapterId).aiOutline }
        assertEquals("林月在渡口等到沈默，老周没来", store.card(workId, chapterId).facts)
        assertTrue(store.card(workId, chapterId).aiBeats)
        compose.onNodeWithText("确认这些节拍").performScrollTo().performClick()
        waitFor { !store.card(workId, chapterId).aiBeats }
        assertEquals("林月提灯到渡口\n沈默下船\n老周的灯灭了", store.card(workId, chapterId).beats)

        // 回到草稿页：按改过的显示，标签没了。
        compose.runOnUiThread { vm.back() }
        waitForText("林月在渡口等到沈默，老周没来")
        waitForText("这一章是按下面的细纲和节拍写的")
        assertTrue(!shown("AI 拟的", substring = false) && !shown("AI 拆的", substring = false))
        assertTrue(shown("按改过的重写"))
    }

    /** 编辑器“AI 写…”：本章还没有节拍时给一个“先拆节拍”，去细纲卡拆；不拆照样按下面的写法写。 */
    @Test
    fun theEditorOffersToSplitBeatsFirstWhenThereAreNone() {
        val store = WorkStore(writingDir)
        store.saveText(workId, chapterId, "林月推开茶馆的门。", count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月在渡口等到沈默"))
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("林月推开茶馆的门")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("AI 写作")).performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("本章还没有节拍 · 先拆节拍")
        waitForText("写多少")
        compose.onNodeWithText("本章还没有节拍 · 先拆节拍").performClick()
        waitForText("AI 拆节拍")
        assertTrue(kinds.isEmpty())
    }
}
