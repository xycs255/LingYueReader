package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/** 草稿页：整章草稿比目标长 30% 以上时在字数旁标出来，可以一键压到目标篇幅（新的一版，可以换回原来那版）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDraftLengthUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 发出去的请求：系统提示、请求正文、整个请求体。 */
    private val asked: MutableList<Triple<String, String, JSONObject>> = Collections.synchronizedList(mutableListOf())

    private val long = "林月走过渡口，看见船不见了。".repeat(30)
    private val condensed = "林月到了渡口，船不见了。杜娘说老周走了。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val system = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += Triple(system, user, body)
                    val answer = if ("精简整章" in user) condensed else "这一章写得很好。"
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
            val w = store.create("渡口")
            workId = w.id
            chapterId = w.chapters[0].id
            store.saveCard(workId, chapterId, ChapterCard(facts = "林月到渡口，老周已经走了", hook = "老周留下一封信"))
            // 全章目标是区间：比目标长多少按区间上限算。
            store.addPackDoc(workId, PackKind.RULES, "检查规则", "字数：100-200\n")
            store.updateAssist(workId) { it.copy(draftPrep = false, temperature = 0.8f) }
            store.saveDraft(workId, chapterId, long)
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
    fun aDraftFarOverTheTargetIsMarkedAndCanBeCondensedAsANewVersion() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        // 420 字，目标 100 到 200 字：比区间上限长 110%。
        waitForText("420 字")
        waitForText("比目标长 110%")
        assertTrue("看草稿不发请求", asked.isEmpty())

        compose.onNodeWithText("压到约 150 字").performScrollTo().performClick()
        waitFor { store.draft(workId, chapterId) == condensed }
        waitForText("压到了 20 字（原来 420 字），可以换回原来那一版")
        // 压到目标以内，不再标。
        assertTrue(compose.onAllNodesWithText("比目标长", substring = true).fetchSemanticsNodes().isEmpty())
        // 走写正文的路径：写正文的系统提示、作品设的温度，请求里给明目标和上限。
        val (system, user, body) = asked.single { "精简整章" in it.second }
        assertTrue(system.startsWith("你是和作者一起写这部小说"))
        assertEquals(0.8, body.getDouble("temperature"), 1e-6)
        assertTrue("压到约 150 字，最多不超过 200 字" in user)
        assertTrue(long in user)

        // 原来那版留着，可以换回。
        compose.onNodeWithText("换回修订前那一版").performScrollTo().performClick()
        waitFor { store.draft(workId, chapterId) == long }
        waitForText("比目标长 110%")
    }
}
