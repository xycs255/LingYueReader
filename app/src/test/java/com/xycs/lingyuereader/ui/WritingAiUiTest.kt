package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
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
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingAiUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val name = TestName()
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    /** 发给假服务商的每个请求里用户那条消息。 */
    private val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    prompts += user
                    val answer = when {
                        "只找明显的错误" in user -> """{"issues":[{"p":1,"wrong":"再门口","right":"在门口","reason":"错别字"}]}"""
                        "润色这段文字" in user -> "修改后：风刮得很猛。"
                        else -> "1. 让他听见门外的脚步声。"
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
            store.saveText(w.id, w.chapters[0].id, "他推门进来。\n风很大。\n他再门口等着，天色以晚。")
            if (name.methodName.startsWith("realtime")) store.updateAssist(w.id) { it.copy(realtime = true) }
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    /** 编辑框的光标一直在闪，界面不“空闲”：手动处理主线程消息，协程才能往下走。 */
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun openEditor() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun polishTheParagraphAtTheCursorAndAcceptIt() {
        openEditor()
        // 光标放在“风很大。”这一段里。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(8))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("润色").performClick()
        // 先看对比：删掉的“大”还在（划掉显示），加上的“刮得”“猛”插在里面；“修改后：”这种多余的话去掉了。
        waitForText("风刮得很大猛。")
        compose.onNodeWithText("改后").performClick()
        waitForText("风刮得很猛。")
        val prompt = prompts.single()
        assertTrue("要改的文字：\n风很大。" in prompt)
        assertTrue("前文（只作语境，不要改）：\n他推门进来。" in prompt)

        compose.onNodeWithText("采纳").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId) == "他推门进来。\n风刮得很猛。\n他再门口等着，天色以晚。" }
        // 记下了这次采纳，以后润色拿它当例子。
        waitFor { store.assist(workId).accepted.singleOrNull()?.after == "风刮得很猛。" }
        waitFor { compose.onAllNodesWithText("采纳").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun ideasAreShownButNeverChangeTheDraft() {
        openEditor()
        compose.onNodeWithContentDescription("AI 写作").performClick()
        // 操作条横着滑：灵感在后面，先滑过去。
        compose.onNodeWithText("灵感…").performScrollTo().performClick()
        waitForText("接下来怎么写")
        compose.onNodeWithText("接下来怎么写").performClick()
        waitForText("让他听见门外的脚步声")
        assertTrue("给出接下来剧情的 3 个不同走向" in prompts.single())
        compose.onNodeWithText("关闭").performClick()
        Thread.sleep(1200)
        ShadowLooper.idleMainLooper()
        assertEquals("他推门进来。\n风很大。\n他再门口等着，天色以晚。", WorkStore(writingDir).text(workId, chapterId))
    }

    @Test
    fun realtimeCheckFindsATypoAndFixesIt() {
        openEditor()
        // 作品开了实时检查：停笔几秒，自动把够长的段落发去挑错（短段不发）。
        waitForText("发现 1 处可能的问题")
        val prompt = prompts.single()
        assertTrue("1. 他再门口等着，天色以晚。" in prompt)
        assertFalse("风很大。" in prompt)
        waitForText("实时检查已开")
        compose.onNodeWithText("发现 1 处可能的问题").performClick()
        waitForText("错别字")
        compose.onNodeWithText("改").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId) == "他推门进来。\n风很大。\n他在门口等着，天色以晚。" }
    }

    @Test
    fun withoutRealtimeNothingIsSentAutomatically() {
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(3))
        Thread.sleep(4_000)
        ShadowLooper.idleMainLooper()
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun dialogsOpenOnTheWorkAndBiblePages() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        // 作品页：长按章节改名（对话框要挂在页面的浮层宿主上才看得见）。
        compose.runOnUiThread { vm.open(Screen.Work(workId)) }
        waitForText("新的一章")
        // “继续写”那行也写着“第1章”，章节列表在它下面。
        compose.onAllNodesWithText("第1章").onLast().performTouchInput { longClick() }
        waitForText("上移")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("第一章 雪夜")
        compose.onNodeWithText("完成").performClick()
        waitFor { store.get(workId)!!.chapters[0].title == "第一章 雪夜" }

        // 设定包：新建一份人物档案（带人物卡模板），写进去，改优先级。
        compose.runOnUiThread { vm.open(Screen.WorkPack(workId)) }
        waitForText("设定包还是空的")
        compose.onNodeWithText("新建文档").performClick()
        waitForText("想要、害怕、怎么说话")
        compose.onNodeWithText("新建").performClick()
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && store.pack(workId).size == 1 }
        val doc = store.pack(workId).single()
        val wordsToday = store.stats().on(java.time.LocalDate.now())
        assertEquals(com.xycs.lingyuereader.data.PackKind.CHARACTER to "人物档案", doc.kind to doc.title)
        waitFor { compose.onAllNodesWithText("想要：", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("文档内容").performTextReplacement("## 林月\n药铺掌柜的女儿，左手有疤")
        waitFor { store.packText(workId, doc.id) == "## 林月\n药铺掌柜的女儿，左手有疤" }
        // 设定字数不算码字数。
        assertEquals(wordsToday, store.stats().on(java.time.LocalDate.now()))
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("属性（类型、优先级）").performClick()
        waitForText("文档属性")
        compose.onNodeWithContentDescription("优先级1").performClick()
        compose.onNodeWithText("保存").performClick()
        waitFor { store.pack(workId).single().priority == 1 }
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("优先级 1 · 常用")
    }

    @Test
    fun importReadsAZipThroughTheViewModelAndThePreviewImportsIt() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        // 一个“设定包”压缩包：两份设定、一份正文。
        val zip = File(context.cacheDir, "设定包.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            listOf(
                "设定包/人物设定档案.md" to "## 林月\n想要：一个家",
                "设定包/正文写作约定.md" to "少用成语。",
                "设定包/已定正文_第1-2章.md" to "## 第一章 雪夜\n他推开门。\n## 第二章 来客\n来人了。",
            ).forEach { (name, text) ->
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        var items: List<com.xycs.lingyuereader.data.PackImportItem>? = null
        compose.runOnUiThread { vm.viewModelScope.launch { items = vm.readImportFiles(listOf(android.net.Uri.fromFile(zip))) } }
        waitFor { items != null }
        assertEquals(
            listOf("人物设定档案" to false, "已定正文_第1-2章" to true, "正文写作约定" to false),
            items!!.map { it.title to it.asChapters }.sortedBy { it.first },
        )

        val appearance = vm.appearance.value
        compose.runOnUiThread {
            compose.activity.setContent { LingYueTheme(appearance) { WorkPackScreen(vm, workId, initialPreview = items) } }
        }
        waitForText("3 个文件")
        waitForText("正文（拆成章节）")
        // 不导入写作约定：点那一行，选“不导入”。
        compose.onNodeWithText("正文写作约定").performClick()
        waitForText("不导入")
        compose.onNodeWithContentDescription("导入与否不导入").performClick()
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithText("导入 2 个文件").performClick()
        waitFor { store.pack(workId).map { it.title } == listOf("人物设定档案") }
        waitFor { store.get(workId)!!.chapters.map { it.title } == listOf("第1章", "第一章 雪夜", "第二章 来客") }
        waitForText("人物设定档案")
    }
}
