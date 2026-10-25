package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 写作编辑器键盘上方的动效：面板平滑升起、减少动效时直接出现；写作建议条要光标停稳才出来；
 * 采纳 AI 写的字后正文只有一份；换章滑过去后正文是新的一章；建议的四种颜色深浅色下都读得清。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingEditorMotionUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private var secondId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他，说老周昨夜就死了。"
    private val name = TestName()
    /** 读者点评先不回答：面板停在“正在想”，高矮不再变，好量正文框。 */
    private val reviewGate = CountDownLatch(1)

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    prompts += user
                    val answer = when {
                        "请从光标处接着往下写正文" in user -> "雪还在下。\n沈默在门口站了一会儿。"
                        "只找下面这几类问题" in user ->
                            """{"issues":[{"p":1,"wrong":"老周昨夜就死了","right":"老周昨夜病倒了","reason":"按设定老周这时还活着","kind":"CANON","source":"《人物档案》老周"}]}"""
                        else -> {
                            reviewGate.await(30, TimeUnit.SECONDS)
                            "读来顺畅。"
                        }
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    runCatching { ex.responseBody.use { it.write(bytes) } }
                }
                executor = Executors.newCachedThreadPool()
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
            val suggestions = name.methodName.startsWith("suggestion")
            // 写作建议：最后一个句号留给测试里打（打开时已有的段落不查，改过的才查）。
            store.saveText(w.id, w.chapters[0].id, if (suggestions) text.removeSuffix("。") else text, count = false)
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 结局 |\n|---|---|\n| 老周 | 第九章战死 |\n")
            if (suggestions) store.updateAssist(w.id) { it.copy(realtime = true) }
            val (_, two) = store.addChapter(w.id, w.volumes[0].id, "第二章 天亮")!!
            store.saveText(w.id, two.id, "天终于亮了。", count = false)
            workId = w.id
            chapterId = w.chapters[0].id
            secondId = two.id
        }

        override fun after() {
            reviewGate.countDown()
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    @After
    fun resetMotion() {
        compose.mainClock.autoAdvance = true
        Effects.systemNoMotion = false
    }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(t: String) = waitFor { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun shown(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** 时钟停着，一帧帧往前走（主线程的消息也处理掉，状态流的新值才收得到）。 */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(16)
            left -= 16
        }
        ShadowLooper.idleMainLooper()
    }

    private fun bodyHeight(): Float = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().boundsInRoot.height

    private fun openEditor(vm: AppViewModel) {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitForText("沈默推开茶馆的门")
    }

    /** 弹出读者点评的面板，量正文框在头几帧和停稳后的高度。 */
    private fun bodyHeightsWhenThePanelOpens(): Pair<Float, Float> {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor(vm)
        val before = bodyHeight()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { vm.writingAi.run(workId, chapterId, text, WriteAiTask.Review) }
        advance(48)
        assertTrue("点评面板应当已经出来", shown("读者点评"))
        val early = bodyHeight()
        advance(1_000)
        val settled = bodyHeight()
        assertTrue("面板占了地方，正文框应当变矮：$before → $settled", settled < before)
        return early to settled
    }

    @Test
    fun panelRisesSmoothlySoTheBodyShrinksGradually() {
        val (early, settled) = bodyHeightsWhenThePanelOpens()
        // 头几帧面板还在长高：正文框只矮了一部分，不是一下子缩到底。
        assertTrue("正文框应当慢慢变矮（头几帧 $early，停稳 $settled）", early > settled + 1f)
    }

    @Test
    fun reducedMotionShowsThePanelAtOnce() {
        compose.runOnUiThread { Effects.systemNoMotion = true }
        val (early, settled) = bodyHeightsWhenThePanelOpens()
        assertEquals("减少动效时面板直接出现，正文框一下到位", settled, early, 0.5f)
    }

    @Test
    fun suggestionBarWaitsForTheCaretToSettle() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor(vm)
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length - 1))
        compose.onNodeWithContentDescription("正文").performTextInput("。")
        waitForText("1 条写作建议")
        val at = text.indexOf("昨夜就死了")
        compose.mainClock.autoAdvance = false
        // 光标掠过划线的地方（停了不到 150 毫秒就挪开）：建议条不出来，不闪。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(at))
        advance(64)
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(0))
        repeat(6) {
            advance(64)
            assertTrue("光标只是掠过，建议条不该出来", !shown("按设定老周这时还活着"))
        }
        // 停稳了才出来。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(at))
        advance(64)
        assertTrue("还没停稳，建议条先不出来", !shown("按设定老周这时还活着"))
        advance(400)
        assertTrue("停稳了，建议条出来", shown("按设定老周这时还活着"))
        // 采纳：那条马上收起，正文换好。
        compose.onNodeWithText("采纳").performClick()
        advance(600)
        assertTrue(!shown("按设定老周这时还活着"))
        compose.mainClock.autoAdvance = true
        waitFor { WorkStore(writingDir).text(workId, chapterId) == text.replace("昨夜就死了", "昨夜病倒了") }
    }

    @Test
    fun acceptedTextAppearsOnceAndSettlesIntoTheBody() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        openEditor(vm)
        compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE) }
        waitForText("AI 写了")
        val written = vm.writingAi.inline.value!!.text
        val expected = text + written
        fun body() = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("采纳").performClick()
        // 采纳的那几帧里（浅色正在变成正文色）正文也只有一份，AI 写的字不叠两遍。
        repeat(4) {
            advance(32)
            assertEquals(expected, body())
        }
        advance(1_000)
        assertEquals(expected, body())
        compose.mainClock.autoAdvance = true
        waitFor { store.text(workId, chapterId) == expected }
        assertTrue(compose.onAllNodesWithText("AI 写了", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun nextChapterSlidesInWithItsOwnText() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor(vm)
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("正文").performTextInput("门外起风了。")
        compose.runOnUiThread { vm.replaceTop(Screen.Editor(workId, secondId)) }
        waitForText("天终于亮了。")
        // 滑完只剩新的一章；上一章离开时存好了。
        waitFor { compose.onAllNodesWithContentDescription("正文").fetchSemanticsNodes().size == 1 }
        assertTrue(compose.onAllNodesWithText("沈默推开茶馆的门", substring = true).fetchSemanticsNodes().isEmpty())
        waitFor { WorkStore(writingDir).text(workId, chapterId) == text + "门外起风了。" }
        // 往前翻一章：方向反过来。
        assertEquals(false, editorStepForward(vm, Screen.Editor(workId, secondId), Screen.Editor(workId, chapterId)))
        assertEquals(true, editorStepForward(vm, Screen.Editor(workId, chapterId), Screen.Editor(workId, secondId)))
        assertEquals(null, editorStepForward(vm, Screen.Work(workId), Screen.Editor(workId, chapterId)))
    }

    @Test
    fun suggestionColorsAreReadableInLightAndDark() {
        // 默认蓝、深色 Monet 那种浅粉、琥珀：四类建议的颜色在卡片和页面底上都 ≥ 4.5:1，而且四类分得开。
        val tints = listOf(Color(0xFF3482FF), Color(0xFFFFB0C8), Color(0xFFE0905F))
        for (dark in listOf(false, true)) {
            for (tint in tints) {
                val c = groupedColors(dark, tint)
                val colors = SuggestKind.entries.map { suggestColor(it.name, c) }
                colors.zip(SuggestKind.entries).forEach { (color, kind) ->
                    assertTrue("$kind 深色=$dark 强调色=$tint 在卡片上不够清楚", contrastRatio(color, c.cell) >= 4.5f)
                    assertTrue("$kind 深色=$dark 强调色=$tint 在页面底上不够清楚", contrastRatio(color, c.background) >= 4.5f)
                }
                assertEquals("四类建议颜色要分得开", colors.size, colors.toSet().size)
            }
        }
        // 深色下错字不是浅色下那个红：跟着配色走。
        assertNotEquals(suggestColor("TYPO", groupedColors(false, tints[0])), suggestColor("TYPO", groupedColors(true, tints[0])))
    }
}
