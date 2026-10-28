package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.xycs.lingyuereader.ai.WriteDirection
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** 写正文的温度（作品 AI 设置）和一拍出 3 个方向的版本：分段切换、采纳选中的那一版、一版出错不影响别的、撤回什么都不插。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingVariantsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 请求体里的温度（没有为空），和 [prompts] 一一对应。 */
    private val temperatures: MutableList<Double?> = Collections.synchronizedList(mutableListOf())
    private val text = "沈默推开茶馆的门。"
    private val written = mapOf(
        WriteDirection.CONFLICT to "沈默一把揪住杜娘的袖子。",
        WriteDirection.RESTRAIN to "杜娘没说话，把门又关上一半。",
        WriteDirection.SURPRISE to "门外跑进来一个送信的孩子。",
    )
    /** 这个方向的请求回 500（一版出错）。 */
    @Volatile
    private var failing: WriteDirection? = null

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    synchronized(prompts) {
                        prompts += user
                        temperatures += if (body.has("temperature")) body.getDouble("temperature") else null
                    }
                    val direction = WriteDirection.entries.firstOrNull { "【这一版的方向：${it.label}】" in user }
                    if (direction != null && direction == failing) {
                        val err = """{"error":{"message":"服务商忙"}}""".toByteArray()
                        ex.sendResponseHeaders(500, err.size.toLong())
                        runCatching { ex.responseBody.use { it.write(err) } }
                        return@createContext
                    }
                    val answer = direction?.let { written.getValue(it) } ?: "雪还在下。"
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
            store.saveCard(w.id, w.chapters[0].id, ChapterCard(facts = "沈默到渡口，老周已经走了",
                beats = "沈默赶到渡口，发现船不见了\n杜娘开门\n老周的信", beatsDone = listOf(1)))
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

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun openEditor(vm: AppViewModel) {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
    }

    /** 编辑器“AI 写…”面板：默认选第 2 拍，勾上“出 3 个方向的版本”，写第 2 拍。 */
    private fun writeBeatInThreeDirections(vm: AppViewModel) {
        openEditor(vm)
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("写第 2 拍")
        compose.onNodeWithText("出 3 个方向的版本（花费约 3 倍）").performScrollTo().performClick()
        // 勾选按作品记住。
        waitFor { WorkStore(writingDir).assist(workId).beatVariants }
        compose.onNodeWithText("写第 2 拍").performClick()
    }

    private fun prompt(direction: WriteDirection) = synchronized(prompts) { prompts.filter { "【这一版的方向：${direction.label}】" in it } }

    @Test
    fun theWorkSettingsSaveTheWritingTemperature() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        compose.onNodeWithText("写正文的温度").performScrollTo()
        waitForText("越高越不套路，也越容易跑偏")
        waitForText("服务商默认")
        compose.onNodeWithText("写正文的温度").performClick()
        // 选项对话框：五档，1.5 带一句说明。
        waitForText("DeepSeek 推荐的创作值")
        listOf("稳一点 0.7", "标准 1.0", "放开些 1.3").forEach { waitForText(it) }
        compose.onNodeWithText("很放开 1.5").performClick()
        waitFor { WorkStore(writingDir).assist(workId).temperature == 1.5f }
        waitForText("很放开 1.5")
        // 改回服务商默认。
        compose.onNodeWithText("写正文的温度").performClick()
        waitForText("DeepSeek 推荐的创作值")
        compose.onNodeWithText("服务商默认").performClick()
        waitFor { WorkStore(writingDir).assist(workId).temperature == null }
    }

    /** 作品 AI 设置分两层：常用的直接列出；上限、频率、文风范例怎么挑、学你的改法、最近一次发给 AI 的内容在“高级设置”里，照样能改。 */
    @Test
    fun theAdvancedSettingsHoldTheRarelyChangedItems() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        listOf("服务商", "设定包", "写作范本", "对文风的要求", "用定稿章当文风范例", "写正文的温度", "查 AI 腔", "我的黑名单", "写作时给建议", "AI 记录", "用量")
            .forEach { compose.onNodeWithText(it).performScrollTo() }
        listOf("知识库上限", "本章资料上限", "两次至少隔", "一天最多", "最多带", "看看挑了哪几段", "记住的采纳", "最近一次发给 AI 的内容")
            .forEach { assertTrue("$it 收进了高级设置", compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty()) }
        // 开了写作建议才带出三类子开关；查设定交给审稿的事实关，这里没有。
        compose.onNodeWithText("写作时给建议").performScrollTo().performClick()
        waitFor { WorkStore(writingDir).assist(workId).realtime }
        listOf("查错字", "查规范", "查口吻").forEach { compose.onNodeWithText(it).performScrollTo() }
        assertTrue(compose.onAllNodesWithText("查设定").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("高级设置").performScrollTo().performClick()
        waitForText("知识库上限")
        listOf("知识库上限", "本章资料上限", "两次至少隔", "一天最多", "最多带", "没有定稿章时用字数最多的章", "看看挑了哪几段",
            "记住的采纳", "记住的没采纳", "没用上的建议", "最近一次发给 AI 的内容").forEach { compose.onNodeWithText(it).performScrollTo() }
        // 值和存储不变：在这里改两次至少隔，存进作品设置。
        compose.onNodeWithText("两次至少隔").performScrollTo().performClick()
        compose.onNodeWithText("60 秒").performClick()
        waitFor { WorkStore(writingDir).assist(workId).suggestGap == 60 }
    }

    @Test
    fun threeDirectionsAreWrittenAndTheSelectedOneIsAccepted() {
        WorkStore(writingDir).updateAssist(workId) { it.copy(temperature = 1.3f) }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        writeBeatInThreeDirections(vm)
        WriteDirection.entries.forEach { waitForText("${it.label}·写好") }
        // 三个方向各一个请求，都写这一拍、都带写正文的温度。
        WriteDirection.entries.forEach { d ->
            val ask = prompt(d).single()
            assertTrue(ask.contains("只写第 2 拍"))
            assertTrue(ask.contains(d.rule))
        }
        assertEquals(listOf(1.3, 1.3, 1.3), temperatures.toList())
        // 花费是三版合计（按三次算）。
        assertEquals(3, vm.writingAi.inline.value!!.usage!!.requests)
        // 默认看第一版；切到“按下不发”，预览和采纳都换成它。
        assertEquals(text + "\n" + written.getValue(WriteDirection.CONFLICT), vm.writingAi.inline.value!!.let { it.base.substring(0, it.at) + it.text })
        compose.onNodeWithText("按下不发·写好").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 1 }
        waitFor { vm.writingAi.inline.value?.text == "\n" + written.getValue(WriteDirection.RESTRAIN) }
        assertEquals(listOf(1), store.card(workId, chapterId).beatsDone)
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.text(workId, chapterId) == text + "\n" + written.getValue(WriteDirection.RESTRAIN) }
        waitFor { store.card(workId, chapterId).beatsDone == listOf(1, 2) }
        assertNull(vm.writingAi.inline.value)
    }

    @Test
    fun oneFailedDirectionLeavesTheOthersAcceptable() {
        failing = WriteDirection.SURPRISE
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        writeBeatInThreeDirections(vm)
        waitForText("意外插入·出错")
        waitForText("冲突升级·写好")
        waitForText("按下不发·写好")
        // 出错的那一版不能采纳，说出了什么错。
        compose.onNodeWithText("意外插入·出错").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 2 }
        waitForText("服务商暂时出了问题")
        compose.onNodeWithText("采纳").assertIsNotEnabled()
        // 另外两版照样能采纳。
        compose.onNodeWithText("冲突升级·写好").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 0 }
        compose.onNodeWithText("采纳").assertIsEnabled().performClick()
        waitFor { store.text(workId, chapterId) == text + "\n" + written.getValue(WriteDirection.CONFLICT) }
        waitFor { store.card(workId, chapterId).beatsDone == listOf(1, 2) }
    }

    @Test
    fun rewritingRedoesOnlyTheSelectedVersionAndDiscardingInsertsNothing() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        openEditor(vm)
        // “AI 写…”面板：选“写下一场”，勾“出 3 个方向的版本”。这个勾只对写这一拍、写下一场有效。
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("从光标处往下写")
        val several = "出 3 个方向的版本（花费约 3 倍）"
        compose.onNodeWithText("写到本章结尾").performScrollTo().performClick()
        compose.onNodeWithText(several).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("写下一场").performScrollTo().performClick()
        compose.onNodeWithText(several).performScrollTo().assertIsEnabled().performClick()
        waitFor { WorkStore(writingDir).assist(workId).beatVariants }
        compose.onNodeWithText("开始写").performClick()
        WriteDirection.entries.forEach { waitForText("${it.label}·写好") }
        WriteDirection.entries.forEach { d -> assertTrue(prompt(d).single().contains("写完眼下这一场就停")) }
        // 没设温度：请求里不带。
        assertTrue(temperatures.all { it == null })

        // 重写只重写选中的那一版。
        compose.onNodeWithText("意外插入·写好").performClick()
        waitFor { vm.writingAi.inline.value?.selected == 2 }
        compose.onNodeWithText("重写").performClick()
        waitFor { prompt(WriteDirection.SURPRISE).size == 2 }
        waitForText("意外插入·写好")
        assertEquals(1, prompt(WriteDirection.CONFLICT).size)
        assertEquals(1, prompt(WriteDirection.RESTRAIN).size)

        // 撤回：三版都不要，正文不变。
        compose.onNodeWithText("撤回").performClick()
        waitFor { vm.writingAi.inline.value == null }
        Thread.sleep(800)
        ShadowLooper.idleMainLooper()
        assertEquals(text, store.text(workId, chapterId))
        assertEquals(listOf(1), store.card(workId, chapterId).beatsDone)
    }
}
