package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
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

/**
 * AI 写正文时界面跟着流式出字：长章节、很多小块、中途调工具（先说的半句要清掉）。
 * 编辑器里写、三个方向、AI 写整章草稿逐拍写，界面都不能抛异常（主线程上的异常就是闪退）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WritingStreamUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer

    private fun res(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")) { "缺少 $path" }.use { it.readBytes().decodeToString() }

    private val beats by lazy { res("ch3_beats.txt").split("=====").map { it.trim() } }

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                    val user = list.last { it.getString("role") == "user" }.getString("content")
                    val afterTool = list.last().getString("role") == "tool"
                    val canCall = body.has("tools") && body.optString("tool_choice") != "none"
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    ex.sendResponseHeaders(200, 0)
                    runCatching {
                        ex.responseBody.use { out ->
                            fun send(o: Any) {
                                out.write("data: $o\n\n".toByteArray())
                                out.flush()
                            }
                            fun delta(d: JSONObject, finish: String? = null) = JSONObject().put("choices", JSONArray().put(
                                JSONObject().put("index", 0).put("delta", d).put("finish_reason", finish ?: JSONObject.NULL)))
                            fun stream(text: String) = text.chunked(3).forEachIndexed { i, s ->
                                send(delta(JSONObject().put("content", s)))
                                if (i % 4 == 0) Thread.sleep(1)
                            }
                            if (canCall && !afterTool && "【这次要写的】" in user) {
                                stream("我先查一下。")
                                send(delta(JSONObject().put("tool_calls", JSONArray()
                                    .put(JSONObject().put("index", 0).put("id", "c0").put("type", "function")
                                        .put("function", JSONObject().put("name", "read_doc").put("arguments", """{"title":"人物档案"}""")))
                                    .put(JSONObject().put("index", 1).put("id", "c1").put("type", "function")
                                        .put("function", JSONObject().put("name", "read_chapter").put("arguments", """{"chapter":"2"}"""))))))
                                send(delta(JSONObject(), "tool_calls"))
                            } else {
                                stream(if (Regex("只写第 \\d+ 拍").containsMatchIn(user)) beats.joinToString("\n") else (beats + beats).joinToString("\n"))
                                send(delta(JSONObject(), "stop"))
                            }
                            send("[DONE]")
                        }
                    }
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
            val w = store.create("渡灯")
            workId = w.id
            listOf(
                "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION, "总纲" to PackKind.OUTLINE,
                "第一卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON, "人物档案" to PackKind.CHARACTER,
                "察觉表" to PackKind.AWARENESS, "伏笔表" to PackKind.FORESHADOW, "写作规范" to PackKind.STYLE,
                "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
            ).forEach { (name, kind) -> store.addPackDoc(workId, kind, name, res("pack/$name.md")) }
            val paragraphs = (res("ch1.txt") + "\n" + res("ch2.txt")).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val volume = w.volumes[0].id
            val ids = listOf(w.chapters[0].id) + (2..12).map { store.addChapter(workId, volume)!!.second.id }
            ids.forEachIndexed { i, id ->
                store.renameChapter(workId, id, "第${i + 1}章 渡口${i + 1}")
                store.saveText(workId, id, buildString {
                    var k = i * 5
                    while (length < 3_500) append(paragraphs[k++ % paragraphs.size]).append('\n')
                }.trim(), count = false)
                store.setStatus(workId, id, ChapterStatus.FINAL)
            }
            chapterId = store.addChapter(workId, volume)!!.second.id
            store.renameChapter(workId, chapterId, "第13章 空船")
            store.saveCard(workId, chapterId, ChapterCard(
                facts = "林月到渡口，老周的船上没人；杜娘说昨夜顾长风带人来过；沈默赶来，两人找到另半张药方。", pov = "林月",
                hook = "河上传来漕帮的号子。",
                beats = "1. 林月到渡口，船上没人（800字）\n2. 杜娘开门，说顾长风来过（900字）\n3. 沈默赶来，找到另半张药方（1000字）\n4. 林月决定去白沙渡（700字）",
            ))
            // 光标前已经写了一万多字的长章节。
            store.saveText(workId, chapterId, (beats[0] + "\n" + beats[1] + "\n").repeat(6).trim(), count = false)
            store.updateAssist(workId) { it.copy(temperature = 1.1f, thinking = false, aiCliche = true) }
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private var frames = 0

    /** 等的时候每隔几次真画一帧（画的时候出的异常也要暴露出来）。 */
    private fun waitFor(timeout: Long = 60_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        if (frames++ % 3 == 0) compose.onRoot().captureToImage()
        condition()
    }

    @Test
    fun theEditorAndTheDraftPageKeepUpWithALongStream() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val body = WorkStore(writingDir).text(workId, chapterId)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodesWithText("林月", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // 作者刚在正文里打着字：光标在末尾、正文有焦点。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(body.length))
        for (mode in listOf(InlineMode.TO_END, InlineMode.BEAT, InlineMode.SCENE)) {
            compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, body, body.length, mode, beat = if (mode == InlineMode.BEAT) 3 else null) }
            waitFor { vm.writingAi.inline.value?.loading == false }
            assertEquals(null, vm.writingAi.inline.value?.error)
            assertTrue(vm.writingAi.inline.value!!.text.length > 1_000)
            compose.runOnUiThread { vm.writingAi.discardInline() }
        }
        // 写到一半停下、重写、切版本、写完一版就采纳（别的版还在写）。
        compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, body, body.length, InlineMode.SCENE, variants = true) }
        waitFor { (vm.writingAi.inline.value?.text?.length ?: 0) > 300 }
        compose.runOnUiThread { vm.writingAi.selectInline(1) }
        waitFor { (vm.writingAi.inline.value?.text?.length ?: 0) > 300 }
        compose.runOnUiThread { vm.writingAi.stopInline() }
        waitFor { vm.writingAi.inline.value?.anyLoading == false }
        compose.runOnUiThread { vm.writingAi.retryInline() }
        waitFor { vm.writingAi.inline.value?.loading == false }
        compose.runOnUiThread { vm.writingAi.selectInline(2) }
        compose.runOnUiThread { vm.writingAi.retryInline() }
        waitFor { (vm.writingAi.inline.value?.text?.length ?: 0) > 300 }
        compose.runOnUiThread { vm.writingAi.selectInline(1) }
        waitFor { vm.writingAi.inline.value?.loading == false }
        compose.onNodeWithText("采纳").performClick()
        waitFor { vm.writingAi.inline.value == null }
        // 写的途中去草稿页、再回来。
        val now = WorkStore(writingDir).text(workId, chapterId).ifEmpty { body }
        compose.runOnUiThread { vm.writingAi.writeInline(workId, chapterId, now, now.length, InlineMode.TO_END) }
        waitFor { (vm.writingAi.inline.value?.text?.length ?: 0) > 200 }
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId] != null }
        compose.runOnUiThread { vm.back() }
        waitFor { vm.writingAi.inline.value?.loading == false }
        compose.runOnUiThread { vm.writingAi.discardInline() }

        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId] != null }
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == true }
        waitFor { (vm.writingAi.drafts.value[workId to chapterId]?.beats?.written ?: 0) >= 2 }
        compose.runOnUiThread { vm.writingAi.stopDraft(workId, chapterId) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId, more = true) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == true }
        waitFor(120_000) { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        val d = vm.writingAi.drafts.value[workId to chapterId]!!
        assertEquals(null, d.error)
        assertTrue(d.text.length > 3_000)
        // 整章一次写（没有节拍）、项目包流程。
        WorkStore(writingDir).let { s -> s.saveCard(workId, chapterId, s.card(workId, chapterId).copy(beats = "")) }
        compose.runOnUiThread { vm.writingAi.writeDraft(workId, chapterId) }
        waitFor { vm.writingAi.drafts.value[workId to chapterId]?.loading == true }
        waitFor(120_000) { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertEquals(null, vm.writingAi.drafts.value[workId to chapterId]!!.error)
    }
}
