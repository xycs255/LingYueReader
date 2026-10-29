package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.DraftBeats
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
import java.util.Collections

/** 节拍：细纲卡页拆节拍、勾已写；编辑器“AI 写…”面板里写这一拍、智能推荐“写第 N 拍”；“AI 写整章草稿”逐拍写，候选稿是各拍拼起来的。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingBeatsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "沈默推开茶馆的门。"
    private val beatText = mapOf(1 to "沈默到了渡口，船不见了。", 2 to "杜娘开了门。", 3 to "老周的信在桌上压着。")

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    prompts += user
                    val beat = Regex("只写第 (\\d+) 拍").find(user)?.groupValues?.get(1)?.toInt()
                    val answer = when {
                        "拆成 3 到 8 拍" in user -> "1. 沈默赶到渡口，船不见了（800字）\n2. 杜娘开门\n3. 老周的信"
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
            store.saveText(w.id, w.chapters[0].id, text, count = false)
            store.addPackDoc(w.id, PackKind.RULES, "检查规则", "字数：至少 5\n")
            store.saveCard(w.id, w.chapters[0].id, ChapterCard(facts = "沈默到渡口，老周已经走了", hook = "老周留下一封信"))
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

    /** 按钮可能先显示、等数据读好才可点（慢机器上更明显）：等它可点再点，点早了会被忽略。 */
    private fun waitForEnabled(text: String) = waitFor { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun setBeats(beats: String, done: List<Int> = emptyList()) {
        val store = WorkStore(writingDir)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(beats = beats, beatsDone = done))
    }

    @Test
    fun theCardScreenSplitsBeatsForReviewAndTicksWrittenOnes() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitForText("AI 拆节拍")
        // 一句说明：一行一拍，行尾可写篇幅。
        waitForText("一行一拍，可以在行尾写篇幅")
        waitForEnabled("AI 拆节拍")
        compose.onNodeWithText("AI 拆节拍").performScrollTo().performClick()
        // 拆好的先给作者看、改，没用之前不进卡片。
        waitForText("AI 拆的节拍")
        assertEquals("", store.card(workId, chapterId).beats)
        compose.onNodeWithText("用这份").performClick()
        waitFor { store.card(workId, chapterId).beats == "沈默赶到渡口，船不见了（800字）\n杜娘开门\n老周的信" }
        assertTrue(prompts.single { "拆成 3 到 8 拍" in it }.contains("沈默到渡口，老周已经走了"))

        // 每拍一行，可以手动勾成已写。
        waitForText("2. 杜娘开门")
        compose.onNodeWithText("2. 杜娘开门").performScrollTo().performClick()
        waitFor { store.card(workId, chapterId).beatsDone == listOf(2) }
        waitForText("已写进正文")

        // 已经有节拍：先问一句，不用就不动。
        waitForEnabled("AI 拆节拍")
        compose.onNodeWithText("AI 拆节拍").performScrollTo().performClick()
        waitForText("覆盖现有节拍？")
        compose.onNodeWithText("重新拆").performClick()
        waitForText("AI 拆的节拍")
        compose.onNodeWithText("不用").performClick()
        Thread.sleep(800)
        ShadowLooper.idleMainLooper()
        assertEquals("沈默赶到渡口，船不见了（800字）\n杜娘开门\n老周的信", store.card(workId, chapterId).beats)
        assertEquals(listOf(2), store.card(workId, chapterId).beatsDone)
    }

    @Test
    fun theEditorWritesTheFirstUnwrittenBeatAndRecordsItWhenAccepted() {
        setBeats("沈默赶到渡口，发现船不见了\n杜娘开门\n老周的信", done = listOf(1))
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        // 面板上面列各拍，下面是写多少；第 1 拍写过了，默认选第 2 拍。
        waitForText("写第 2 拍")
        compose.onNodeWithText("2. 杜娘开门").assertIsSelected()
        waitForText("写多少")
        listOf("写下一场", "写到本章结尾", "写 500 字左右", "按要求写…").forEach { compose.onNodeWithText(it).performScrollTo() }
        compose.onNodeWithText("写第 2 拍").performClick()
        waitForText("AI 写了")
        val ask = prompts.single { "只写第 2 拍" in it }
        assertTrue(ask.contains("  3. 老周的信（后面再写"))
        assertTrue(ask.contains("别开始第 3 拍"))
        // 采纳以后才记成已写。
        assertEquals(listOf(1), store.card(workId, chapterId).beatsDone)
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.text(workId, chapterId) == text + "\n" + beatText.getValue(2) }
        waitFor { store.card(workId, chapterId).beatsDone == listOf(1, 2) }
    }

    /** 本章有没写的拍：智能推荐写着“写第 N 拍”，一点就写，不弹面板；采纳后推荐换成下一拍，都写完了推荐“这章写完了”。 */
    @Test
    fun theSmartRecommendationWritesTheNextBeatInOneTap() {
        setBeats("沈默赶到渡口，发现船不见了\n杜娘开门\n老周的信", done = listOf(1))
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        waitFor { compose.onAllNodesWithContentDescription("智能推荐：写第 2 拍", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("智能推荐：写第 2 拍", substring = true).performClick()
        waitForText("AI 写了")
        // 没有弹“AI 写”面板，直接写了第 2 拍。
        assertTrue(compose.onAllNodesWithText("写多少").fetchSemanticsNodes().isEmpty())
        assertTrue(prompts.single().contains("只写第 2 拍"))
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.card(workId, chapterId).beatsDone == listOf(1, 2) }
        waitFor { compose.onAllNodesWithContentDescription("智能推荐：写第 3 拍", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("智能推荐：写第 3 拍", substring = true).performClick()
        waitForText("AI 写了")
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.card(workId, chapterId).beatsDone == listOf(1, 2, 3) }
        waitFor { compose.onAllNodesWithContentDescription("智能推荐：这章写完了，细纲卡的 3 拍都写完了", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(text + "\n" + beatText.getValue(2) + "\n" + beatText.getValue(3), store.text(workId, chapterId))
    }

    @Test
    fun aiWritesTheChapterBeatByBeatIntoOneCandidate() {
        setBeats("沈默赶到渡口，发现船不见了\n杜娘开门\n老周的信（500字）")
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        // 节拍只读地列出来，带“去改节拍”。
        waitForText("去改节拍")
        waitForText("3. 老周的信")
        // 按钮要等本章任务编好才可点：在慢机器上先出现、后可点，点早了会被忽略。
        waitFor { compose.onAllNodes(hasText("写本章") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("写本章").performScrollTo().performClick()
        val all = beatText.values.joinToString("\n")
        waitFor { store.draft(workId, chapterId) == all }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertEquals(DraftBeats(3, 3), store.draftContent(workId, chapterId).beats)
        assertEquals(listOf(1, 2, 3), prompts.mapNotNull { Regex("只写第 (\\d+) 拍").find(it)?.groupValues?.get(1)?.toInt() })
        waitForText("已写进候选稿")
        waitForText("老周的信在桌上压着。")
    }
}
