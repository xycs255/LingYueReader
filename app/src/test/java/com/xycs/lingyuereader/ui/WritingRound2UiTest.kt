package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.DeviceModels
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingRound2UiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private lateinit var server: HttpServer
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val abis = Build.SUPPORTED_64_BIT_ABIS

    private val seed = object : ExternalResource() {
        override fun before() {
            // 假装是 64 位手机：设置页里能选本机模型。
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val msgs = JSONObject(ex.requestBody.readBytes().decodeToString()).getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    asked += user
                    // 写法关慢一点回：作者在审稿途中标“不改”，后面审完、存盘时不能丢。
                    if ("请只按上面的写作规范" in user) Thread.sleep(1_500)
                    val answer = when {
                        "请做事实审稿" in user -> """{"issues":[{"quote":"林月早就知道","problem":"林月这时还不知道","fix":"改成觉得眼熟"},{"quote":"推开门","problem":"门是锁着的","fix":""}]}"""
                        "审稿意见" in user -> "修改后：林月觉得眼熟"
                        "请只按上面的写作规范" in user -> """{"issues":[]}"""
                        else -> "1. 让门外有脚步声。"
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
            File(files, "llm").deleteRecursively()
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.saveText(w.id, w.chapters[0].id, "林月早就知道，她推开门。")
            store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "对白要短。")
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            server.stop(0)
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", abis)
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
    fun reviewIssuesCanBeFixedByAiOrSetAside() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("三道审稿").performClick()
        compose.onNodeWithText("开始审稿").performClick()
        waitForText("一、事实关 · 2 处")
        // 看得到发给 AI 的内容。
        waitForText("看发给 AI 的内容")

        // 第二处：不改。
        compose.onNodeWithText("门是锁着的").performScrollTo().performClick()
        compose.onNodeWithText("不改").performClick()
        waitForText("一、事实关 · 1 处没处理")
        waitForText("二、写法关 · 没发现问题")
        waitForText("一、事实关 · 1 处没处理")
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.FACT)?.issues?.any { it.state == ReviewIssue.IGNORED } == true }

        // 重新审稿：标了不改的还是不改。
        compose.onNodeWithText("重新审稿").performClick()
        waitForText("一、事实关 · 1 处没处理")
        waitForText("二、写法关 · 没发现问题")

        // 第一处：按这条让 AI 改。回到编辑器，选中原句，AI 按意见改，先看对比。
        compose.onNodeWithText("林月这时还不知道").performScrollTo().performClick()
        compose.onNodeWithText("按这条让 AI 改").performClick()
        // 采纳按钮在 AI 回答之前就在面板上：等请求发出、回答回来再点。
        waitFor { asked.any { "审稿意见" in it } }
        waitFor { vm.writingAi.state.value?.let { !it.loading && it.text.isNotEmpty() } == true }
        val req = asked.last { "审稿意见" in it }
        assertTrue("要改的文字：\n林月早就知道" in req)
        assertTrue("审稿意见：林月这时还不知道。改法：改成觉得眼熟" in req)
        compose.onNodeWithText("采纳").performClick()
        waitFor { store.text(workId, chapterId) == "林月觉得眼熟，她推开门。" }
        waitFor { store.review(workId, chapterId)?.gate(ReviewGate.FACT)?.issues?.first { it.quote == "林月早就知道" }?.state == ReviewIssue.FIXED }
    }

    @Test
    fun aiLogSentRequestAndBriefLimit() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        // 要一次灵感：记进 AI 记录。
        compose.runOnUiThread { vm.writingAi.run(workId, chapterId, store.text(workId, chapterId), com.xycs.lingyuereader.WriteAiTask.Idea(com.xycs.lingyuereader.ai.IdeaKind.entries.first())) }
        waitFor { store.aiLog(workId).isNotEmpty() }

        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitForText("最近一次发给 AI 的内容")
        compose.onNodeWithText("最近一次发给 AI 的内容").performScrollTo().performClick()
        waitForText("系统提示（")
        compose.onNodeWithText("好").performClick()
        // 本章资料上限改成 2 万字。
        compose.onNodeWithText("字数上限").performScrollTo().performClick()
        compose.onNodeWithText("20 千字").performClick()
        waitFor { store.assist(workId).briefLimit == 20_000 }

        compose.onNodeWithText("AI 记录").performScrollTo().performClick()
        waitForText("让门外有脚步声")
        compose.onAllNodesWithText("让门外有脚步声", substring = true)[0].performClick()
        compose.onNodeWithText("有用").performClick()
        waitFor { store.aiLog(workId).first().rating == 1 }
        waitForText("有用 1 条")
    }

    @Test
    fun theCardIsTakenFromTheOutlineAndCanBeReplacedAfterEditing() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        store.renameChapter(workId, chapterId, "第1章 渡口")
        store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", "## 细纲\n\n- **第1章 渡口**：林月等船；钩子：船没来。\n- **第2章 对岸**：沈默过河。\n")
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitForText("自动提取")
        waitForText("《第一卷细纲》 第 1 章「渡口」")
        assertEquals(1, compose.onAllNodes(hasSetTextAction() and hasText("船没来。")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("用细纲里的替换").fetchSemanticsNodes().size)

        // 自己改了钩子：不再跟着细纲，可以换回细纲里的。
        compose.onNode(hasSetTextAction() and hasText("船没来。")).performTextReplacement("门外有脚步声。")
        waitFor { store.card(workId, chapterId).hook == "门外有脚步声。" }
        compose.onNodeWithText("用细纲里的替换").performScrollTo().performClick()
        compose.onNodeWithText("替换").performClick()
        waitFor { store.card(workId, chapterId).let { it.hook == "船没来。" && it.auto != 0 } }
        waitForText("自动提取")
    }

    @Test
    fun aDeviceModelCanBeImportedAndChosen() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        assertTrue(DeviceModels.supported)
        compose.runOnUiThread {
            vm.saveAiConfig(vm.aiConfig.value.withPreset(AiPresets.of(AiPresets.DEVICE)))
            vm.open(Screen.Ai)
        }
        waitForText("本机模型")
        waitForText("下载「Qwen2.5 1.5B」")
        val file = File(context.cacheDir, "我的小模型.task").apply { writeText("假模型") }
        compose.runOnUiThread { vm.importDeviceModel(Uri.fromFile(file)) }
        waitFor { vm.aiConfig.value.model == DeviceModels.FILE + "我的小模型.task" }
        waitForText("导入的模型")
        assertTrue(vm.aiConfig.value.ready)
        assertEquals(listOf(DeviceModels.FILE + "我的小模型.task"), vm.deviceModels.value.installed)
    }
}
