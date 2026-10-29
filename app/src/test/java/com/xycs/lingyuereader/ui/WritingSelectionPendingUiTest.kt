package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.basic.Text
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/**
 * AI 改稿的结果还在等采纳时，选中菜单、底栏都不能再发起新的改稿把它换掉。
 * 系统的浮动操作条 Robolectric 里点不到：和 [WritingSelectionMenuUiTest] 一样换成一个列出同一份菜单项的条。
 */
@RunWith(AndroidJUnit4::class)
// API 26：系统放大镜（API 28 起）在 Robolectric 的原生绘制下收起时会崩，选字测试避开它。
@Config(sdk = [26], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingSelectionPendingUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val text = "林月嘴角勾起，看着窗外的雨。\n沈默推门进来。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    prompts += user
                    val answer = if ("润色这段文字" in user) "林月笑了笑" else "老周的伞还靠在门边。"
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
            context.getSharedPreferences("writing_editor", Context.MODE_PRIVATE).edit().putBoolean("tabs_tip", true).commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            Prefs(context).saveAppearance(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false))
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.renameChapter(w.id, w.chapters[0].id, "第一章 雨夜")
            store.saveText(w.id, w.chapters[0].id, text)
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val models = mutableListOf<AppViewModel>()

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
    }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun has(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    /** 代替系统操作条：记下要显示的菜单，关掉时结束。 */
    private class FakeToolbar : TextContextMenuProvider {
        var shown by mutableStateOf<TextContextMenuDataProvider?>(null)
        private var finish: (() -> Unit)? = null
        val session = object : TextContextMenuSession {
            override fun close() {
                finish?.invoke()
            }
        }

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            try {
                suspendCancellableCoroutine { cont ->
                    shown = dataProvider
                    finish = { if (cont.isActive) cont.resume(Unit) }
                }
            } finally {
                shown = null
                finish = null
            }
        }
    }

    private val toolbar = FakeToolbar()

    /** 打开编辑器；选中文字时的操作条换成一排写着菜单项名字的块（和系统操作条用的是同一份菜单）。 */
    private fun open(): AppViewModel {
        // 自己建 ViewModel、不向活动要：和 WritingSelectionMenuUiTest 同在一个 API 26 的沙盒里时，向活动要到的那个
        // 还连着上一个测试的作品列表（原因没查清），编辑器读不到这一章。
        val vm = AppViewModel(ApplicationProvider.getApplicationContext()).also { models += it }
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false)) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    Box(Modifier.fillMaxSize()) {
                        App(vm)
                        toolbar.shown?.let { data ->
                            Box(Modifier.fillMaxSize().padding(top = 150.dp), contentAlignment = Alignment.TopCenter) {
                                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2B2B2B)).padding(horizontal = 4.dp)) {
                                    data.data().components.filterIsInstance<TextContextMenuItem>().forEach { item ->
                                        Text(
                                            item.label,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.clickable { item.onClick(toolbar.session) }.padding(horizontal = 7.dp, vertical = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        return vm
    }

    /** 双击“林月”那几个字：选中一个词，弹出操作条（长按会出放大镜，Robolectric 里收不掉）。 */
    private fun select() {
        // 第一行在屏幕上 82dp 左右（顶栏下面）；正文框的顶不一定贴着屏幕顶，按它的位置换算。
        val top = compose.onNodeWithContentDescription("正文").getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("正文").performTouchInput {
            doubleClick(Offset(112.dp.toPx(), (82.dp - top).toPx()))
        }
        waitFor { has("润色") }
    }

    /**
     * 润色的候选稿还在等采纳：再选一段点菜单里的“扩写”、底栏“写 → 改稿 → 扩写”，都不能发起新的改稿把它换掉，
     * 提示和 ✦ 上说的一样（先采纳或放弃）。
     */
    @Test
    fun aPendingResultIsNotReplacedFromTheMenuOrTheBar() {
        val vm = open()
        waitFor { body() == text }
        select()
        compose.onNodeWithText("润色").performClick()
        waitFor { prompts.any { "润色这段文字" in it } }
        waitFor { vm.writingAi.state.value?.let { !it.loading && it.text == "林月笑了笑" } == true && has("采纳") }
        val pending = vm.writingAi.state.value
        // 又选中一段：菜单照样弹出，点“扩写”只提示。
        compose.onNodeWithContentDescription("正文").performTouchInput { click(Offset(1f, 1f)) }
        waitFor { toolbar.shown == null }
        val top = compose.onNodeWithContentDescription("正文").getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("正文").performTouchInput { doubleClick(Offset(112.dp.toPx(), (82.dp - top).toPx())) }
        waitFor { toolbar.shown != null && has("扩写") }
        compose.onNodeWithText("扩写").performClick()
        waitFor { has("先采纳或放弃上一个结果") }
        // 提示条盖在底栏上：等它收起再点底栏。
        waitFor { !has("先采纳或放弃上一个结果") }
        // 底栏“写 → 改稿 → 扩写”也一样。
        compose.onNodeWithContentDescription("AI 写作").performClick()
        waitFor { has("改稿") }
        compose.onNodeWithText("改稿").performClick()
        waitFor { compose.onAllNodesWithText("扩写").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("扩写").performClick()
        waitFor { has("先采纳或放弃上一个结果") }
        assertEquals("没有发出新的请求", 1, prompts.size)
        assertEquals("候选稿还是原来那份", pending, vm.writingAi.state.value)
        assertTrue(has("采纳"))
    }

    private fun body() = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}
