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
import com.xycs.lingyuereader.data.DraftReferenceProvenance
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
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

/**
 * 草稿页：照范本写的一稿“已核验”的标记丢了（改过、压过篇幅、写到一半停下），或范本变了，点“审读并修订此稿”不再只说
 * “请从头重写”：可以重新核验范本，或者不用范本直接审读（先查一遍有没有照抄），实在不行才从头重写。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDraftReferenceUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private var analysisId = ""
    private var selected = ""
    private lateinit var server: HttpServer
    /** 发出去的请求正文（最后一条用户消息）。 */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val draft = "林月停在渡口，先听见木船撞岸，再决定要不要上船。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val user = (0 until msgs.length()).map { msgs.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    asked += user
                    val answer = if ("【当前阶段：审读】" in user) """{"summary":[],"missing":[],"issues":[]}""" else "测试收到意外请求"
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
            store.renameChapter(workId, chapterId, "第一章 渡河")
            store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", "# 第一卷\n### 第一章 渡河\n事实：林月在渡口问船，决定付钱过河。\n视角：林月\n边界：章末到对岸。\n")
            store.addPackDoc(workId, PackKind.INSTRUCTION, "完整正文创作提示词", "请输出完整小说正文。")
            store.addPackDoc(workId, PackKind.INSTRUCTION, "章节审读提示词", "只反馈有证据的问题。")
            store.addPackDoc(workId, PackKind.INSTRUCTION, "章节修订提示词", "只修改核实的必改问题。")
            store.updateAssist(workId) { it.copy(draftPrep = false) }
            val book = store.importReference(workId, "合成范本.txt",
                "第一章 门前\n沈默先敲门，再听门里的人拒绝，然后换一种问法。“门还开吗？”他问。屋里的人没有回答，只把灯挪到窗边。后来天亮了，他才离开。")
            val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.single().id, ReferencePart.OPENING, 200)!!
            selected = excerpt.text
            analysisId = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId, "旧正文指纹", "合成写法分析。")).id
            // 照这份范本写的项目稿，“已核验”的标记丢了（比如压过篇幅、改过一处）。
            store.saveDraft(workId, chapterId, draft, DraftReferenceProvenance(analysisId, excerpt.fingerprint, excerpt.chapterLength, false))
            store.setDraftWorkflow(workId, chapterId, true)
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

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = waitFor { shown(text) }

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun open(): AppViewModel {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        vm.chooseCompanionReference(workId, chapterId, analysisId)
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        waitForText("审读并修订此稿")
        return vm
    }

    private fun reviews() = asked.count { "【当前阶段：审读】" in it }

    @Test
    fun aLostMarkIsRecheckedThenTheDraftIsReviewed() {
        val vm = open()
        val store = WorkStore(writingDir)
        // 标记丢了：页面上直接给出“重新核验范本”，不用先撞一次错。
        waitForText("重新核验范本")
        click("审读并修订此稿")
        waitForText("点“重新核验范本”就行")
        assertEquals("拦下时不发请求", 0, asked.size)
        click("重新核验范本")
        waitForText("范本核验通过")
        waitFor { store.draftContent(workId, chapterId).reference?.verified == true }
        assertTrue("核验通过以后不再显示出路", !shown("不用范本，直接审读这一稿"))
        click("审读并修订此稿")
        waitFor { reviews() == 1 && vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertNull(vm.writingAi.drafts.value[workId to chapterId]?.error)
        waitForText("可供采纳")
        // 稿子还挂着原来的范本。
        assertEquals(analysisId, store.draftContent(workId, chapterId).reference?.analysisId)
    }

    @Test
    fun withoutTheReferenceTheDraftIsCheckedForCopyingThenReviewed() {
        val vm = open()
        val store = WorkStore(writingDir)
        // 先放一稿照抄了范本的：不能去掉范本直接审读，说清楚是哪一段。
        val copied = draft + "\n" + selected
        vm.writingAi.updateDraftText(workId, chapterId, copied)
        waitFor { store.draft(workId, chapterId) == copied }
        click("不用范本，直接审读这一稿")
        waitForText("一字不差：“")
        waitForText("改掉那几处再重新核验")
        assertEquals("没过核验不发请求", 0, asked.size)
        assertEquals(analysisId, store.draftContent(workId, chapterId).reference?.analysisId)

        // 改掉照抄的那段：去掉范本关联、正文不动，直接审读；下次写用的范本选择也清掉。
        vm.writingAi.updateDraftText(workId, chapterId, draft)
        waitFor { store.draft(workId, chapterId) == draft }
        click("不用范本，直接审读这一稿")
        waitFor { reviews() == 1 && vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        val d = vm.writingAi.drafts.value[workId to chapterId]!!
        assertNull(d.error)
        assertNull(d.referenceAnalysisId)
        assertEquals(draft, d.text)
        waitFor { store.draftContent(workId, chapterId).let { it.text == draft && it.reference == null } }
        assertNull(vm.companionReference(workId, chapterId))
        waitForText("可供采纳")
        waitForText("来源：本章任务资料 · 未使用范本")
        assertTrue(!shown("范本核验"))
    }
}
