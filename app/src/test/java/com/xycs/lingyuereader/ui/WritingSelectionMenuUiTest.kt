package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
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
 * 选中文字后的菜单：系统的剪切、复制……后面加了润色、扩写、缩写、去 AI 腔（选中的命中了才有）、问 AI。
 * 系统的浮动操作条是另一个窗口，Robolectric 里点不到、也截不到：这里把操作条换成一个照样列出全部菜单项的条（同一份菜单数据），
 * 点“润色”看走的是不是原来的润色流程（先看对比，采纳才换进正文）。
 */
@RunWith(AndroidJUnit4::class)
// API 26：系统放大镜（API 28 起）在 Robolectric 的原生绘制下收起时会崩，选字测试避开它。
@Config(sdk = [26], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingSelectionMenuUiTest {
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
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
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

    @Test
    fun polishFromTheSelectionMenuRunsTheUsualPolishFlow() {
        val vm = open()
        // 等本机检查标出 AI 腔（“嘴角勾起”），选中菜单才知道给不给“去 AI 腔”。
        waitFor { compose.onAllNodesWithText("处问题", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(null, vm.writingAi.state.value)
        select()
        // 选中的是第一行的词，那一行有 AI 腔：五项都在。
        listOf("润色", "扩写", "缩写", "去 AI 腔", "问 AI").forEach { assertTrue(it, has(it)) }
        // 系统自带的几项还在前面。
        assertTrue(has("复制") || has("Copy"))
        capture("selection-menu")
        compose.onNodeWithText("润色").performClick()
        waitFor { prompts.any { "润色这段文字" in it } }
        // 和 AI 条一样：先在面板里看对比，采纳才换进正文。
        waitFor { has("采纳") }
        assertEquals(text, body())
        compose.onNodeWithText("采纳").performClick()
        waitFor { body().contains("林月笑了笑") }
        waitFor { WorkStore(writingDir).text(workId, chapterId).contains("林月笑了笑") }
    }

    @Test
    fun theMenuItemsDependOnWhatIsSelected() {
        assertEquals(listOf("润色", "扩写", "缩写", "问 AI"), selectionMenu(cliche = false).map { it.label })
        assertEquals(listOf("润色", "扩写", "缩写", "去 AI 腔", "问 AI"), selectionMenu(cliche = true).map { it.label })
    }

    private fun body() = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun capture(name: String) {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/editor/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
