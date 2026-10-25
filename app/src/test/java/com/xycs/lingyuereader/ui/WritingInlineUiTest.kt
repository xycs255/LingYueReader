package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.SuggestKind
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
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Collections

/** AI 在编辑器里直接写（先显示、采纳才进正文），和写作时的建议（划线、光标点上去看、采纳、不改、这类不提）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingInlineUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他，说老周昨夜就死了。"
    private val name = TestName()
    private val slowGate = java.util.concurrent.CountDownLatch(1)

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    prompts += user
                    // “慢慢写”：等测试放行再回答，好在写的途中再点一次。
                    if ("慢慢写" in user) slowGate.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    val answer = when {
                        "请从光标处接着往下写正文" in user -> "雪还在下。\n沈默在门口站了一会儿。"
                        "只找下面这几类问题" in user ->
                            """{"issues":[{"p":1,"wrong":"老周昨夜就死了","right":"老周昨夜病倒了","reason":"按设定老周这时还活着","kind":"CANON","source":"《人物档案》老周"}]}"""
                        else -> """{"issues":[]}"""
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
            val w = store.create("长夜")
            // 写作建议：最后一个句号留给测试里打（打开时已有的段落不查，改过的才查）。
            store.saveText(w.id, w.chapters[0].id, if (name.methodName.startsWith("suggestions")) text.removeSuffix("。") else text, count = false)
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 结局 |\n|---|---|\n| 老周 | 第九章战死 |\n")
            if (name.methodName.startsWith("suggestions")) store.updateAssist(w.id) { it.copy(realtime = true) }
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            slowGate.countDown()
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aiWritesAtTheCursorAndOnlyAcceptedTextGoesIn() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        val before = store.stats().on(LocalDate.now())
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        // 先打一个字再撤销：有了可以“重做”的一步。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("正文").performTextInput("啊")
        compose.onNodeWithContentDescription("撤销").performClick()
        waitFor { compose.onAllNodesWithText("昨夜就死了。啊", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 接着写").performClick()
        waitForText("从光标处往下写")
        compose.onNodeWithText("写下一场").performClick()
        waitForText("AI 写了")
        // 写的字只显示在光标处，正文没变；请求带着设定包、光标前后的正文和“写完这一场”。
        waitFor { store.text(workId, chapterId) == text }
        // 等着采纳时正文只读：快捷标点、撤销重做都改不了正文。
        compose.onNodeWithContentDescription("重做").performClick()
        compose.onNodeWithContentDescription("换回标点").performClick()
        compose.onNodeWithContentDescription("插入，").performScrollTo().performClick()
        waitForText("AI 写的还没采纳或撤回")
        Thread.sleep(1_200)
        ShadowLooper.idleMainLooper()
        assertEquals(text, store.text(workId, chapterId))
        // 提示条盖在键盘上方那一排上：等它收起再切回 AI 操作。
        waitFor { compose.onAllNodesWithText("AI 写的还没采纳或撤回", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("AI 写作").performClick()
        val ask = prompts.first { "请从光标处接着往下写正文" in it }
        assertTrue("杜娘抬头看他，说老周昨夜就死了。\n【光标在这里】" in ask && "写完眼下这一场就停" in ask)

        // 撤回：什么都不留。
        compose.onNodeWithText("撤回").performClick()
        waitFor { vm.writingAi.inline.value == null }
        assertEquals(text, store.text(workId, chapterId))

        // 再写一次，采纳：上一段说完了，另起一段接上；原来的正文存进历史，AI 写的字不算码字数。
        compose.onNodeWithText("AI 接着写").performClick()
        compose.onNodeWithText("写下一场").performClick()
        waitForText("AI 写了")
        compose.onNodeWithText("采纳").performClick()
        val expected = "$text\n雪还在下。\n沈默在门口站了一会儿。"
        waitFor { store.text(workId, chapterId) == expected }
        assertTrue(store.history(workId, chapterId).any { store.snapshotText(workId, chapterId, it.at) == text })
        Thread.sleep(1_200)
        ShadowLooper.idleMainLooper()
        assertEquals(before, store.stats().on(LocalDate.now()))
    }

    @Test
    fun suggestionsAreUnderlinedAndShownAtTheCursor() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        // 打完第二段：只发改过的这一段。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length - 1))
        compose.onNodeWithContentDescription("正文").performTextInput("。")
        waitForText("1 条写作建议")
        // 发的是写作建议：带着设定包，按开着的几类挑。
        val ask = prompts.first { "只找下面这几类问题" in it }
        assertTrue("- 设定：" in ask && "- 口吻：" in ask && "1. 杜娘抬头看他，说老周昨夜就死了。" in ask)
        assertFalse("沈默推开茶馆的门" in ask)
        // 光标点到划线的地方：显示这条建议。
        val at = text.indexOf("昨夜就死了")
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(at))
        waitForText("按设定老周这时还活着")
        waitForText("依据：《人物档案》老周")
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.text(workId, chapterId) == text.replace("昨夜就死了", "昨夜病倒了") }

        // “设定类不提”：以后不查这一类（两次请求之间有间隔，这里直接调）。
        compose.runOnUiThread { vm.writingAi.muteKind(workId, SuggestKind.CANON.name) }
        waitFor { store.assist(workId).suggestKinds == listOf("TYPO", "STYLE", "VOICE") }
        assertFalse(SuggestKind.CANON.name in store.assist(workId).suggestKinds)
    }

    @Test
    fun startingAgainWhileWritingKeepsTheNewRunGoing() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
        compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, text, text.length, InlineMode.CUSTOM, "慢慢写") }
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
        compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, text, text.length, InlineMode.CUSTOM, "慢慢写") }
        // 旧的那次被取消了：它的“停下了”不能把新的这次标成写完。
        Thread.sleep(1_200)
        ShadowLooper.idleMainLooper()
        assertTrue(vm.writingAi.inline.value!!.loading)
        slowGate.countDown()
        waitFor { vm.writingAi.inline.value?.loading == false }
        val done = vm.writingAi.inline.value!!
        assertEquals("\n雪还在下。\n沈默在门口站了一会儿。" to null, done.text to done.note)
    }

    @Test
    fun organizedCharactersReplaceTheirOwnSection() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        val first = store.pack(workId).single()
        val second = store.addPackDoc(workId, PackKind.CHARACTER, "配角", "## 杜娘\n茶馆老板娘\n\n## 顾长风\n镖师")
        compose.runOnUiThread {
            vm.writingAi.addProposals(workId, listOf(BibleProposal("杜娘", "茶馆老板娘，消息灵通", true), BibleProposal("林月", "药铺掌柜的女儿", false)))
        }
        waitFor { "消息灵通" in store.packText(workId, second.id) }
        // 杜娘在第二份人物档案里：换掉她那一节，不另加一节同名的。
        val today = LocalDate.now()
        assertEquals("## 杜娘\n茶馆老板娘，消息灵通\n（AI 整理，$today）\n\n## 顾长风\n镖师", store.packText(workId, second.id))
        // 新人物追加到第一份；原来的文字先存了历史版本。
        waitFor { store.packText(workId, first.id).endsWith("## 林月\n药铺掌柜的女儿") }
        assertTrue(store.history(workId, store.docKey(second.id)).isNotEmpty())
    }
}
