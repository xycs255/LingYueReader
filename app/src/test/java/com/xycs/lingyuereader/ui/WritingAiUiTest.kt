package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.EditLearning
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
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
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    /** 每个请求的系统提示和整个请求体。 */
    private val systems: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val draft = "他推门进来。\n风很大。\n他再门口等着，天色以晚。"
    private val slowSuggestionStarted = CountDownLatch(1)
    private val slowSuggestionGate = CountDownLatch(1)

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                    val msgs = body.getJSONArray("messages")
                    val user = msgs.getJSONObject(msgs.length() - 1).getString("content")
                    prompts += user
                    bodies += body
                    systems += (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
                    if (name.methodName in setOf("realtimeQueuesLatestEditAfterSlowRequest", "realtimeDropsPendingEditWhenStopped",
                            "realtimeMalformedResponseWaitsForNextEditToRetry") && prompts.size == 1 &&
                        ("只找明显的错误" in user || "只找下面这几类问题" in user)) {
                        slowSuggestionStarted.countDown()
                        slowSuggestionGate.await(20, TimeUnit.SECONDS)
                    }
                    val answer = when {
                        name.methodName == "realtimeMalformedResponseWaitsForNextEditToRetry" && prompts.size == 1 -> """{"note":"missing issues"}"""
                        "只找明显的错误" in user || "只找下面这几类问题" in user -> """{"issues":[{"p":1,"wrong":"再门口","right":"在门口","reason":"错别字"}]}"""
                        "润色这段文字" in user -> "修改后：风刮得很猛。"
                        "扩写这段" in user -> "风刮得很猛，吹得门窗"
                        "精简这段" in user -> "风"
                        else -> "1. 让他听见门外的脚步声。"
                    }
                    // 扩写：写到长度上限被截断；缩写：模型中途停了。
                    val finish = when {
                        "扩写这段" in user -> "length"
                        "精简这段" in user -> "content_filter"
                        else -> "stop"
                    }
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", finish)))
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
            val realtime = name.methodName.startsWith("realtime")
            // 写作建议的几个测试：最后一个句号留给测试里打（打开时已有的段落不查，改过的才查）。
            store.saveText(w.id, w.chapters[0].id, if (realtime) draft.removeSuffix("。") else draft)
            if (realtime) store.updateAssist(w.id) { it.copy(realtime = true,
                suggestGap = if (name.methodName in setOf("realtimeQueuesLatestEditAfterSlowRequest", "realtimeDropsPendingEditWhenStopped",
                        "realtimeMalformedResponseWaitsForNextEditToRetry")) 0 else it.suggestGap) }
            if (name.methodName == "realtimeTypoOnlyIsAPlainProofread") {
                store.updateAssist(w.id) { it.copy(suggestKinds = listOf("TYPO")) }
                store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "## 林月\n药铺掌柜的女儿")
            }
            if (name.methodName == "realtimeStopsAtTheDailyCapEvenAfterARestart") {
                // 进程重启前今天已经发了 50 次（默认一天最多 50 次）。
                context.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).edit()
                    .putString("suggest_count.${w.id}", "${LocalDate.now()}|50").commit()
            }
            workId = w.id
            chapterId = w.chapters[0].id
        }

        override fun after() {
            slowSuggestionGate.countDown()
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
    fun acceptingAPolishReplacesTheAiOriginalSoItIsNotLearnedAsTheAuthors() {
        // 这章是 AI 写的（记了原稿）；作者在编辑器里让 AI 润色一段并采纳。
        WorkStore(writingDir).recordAiText(workId, chapterId, draft)
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(8))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("润色").performClick()
        waitForText("风刮得很大猛。")
        compose.onNodeWithText("采纳").performClick()
        val store = WorkStore(writingDir)
        val after = "他推门进来。\n风刮得很猛。\n他再门口等着，天色以晚。"
        waitFor { store.text(workId, chapterId) == after }
        // 原稿跟着换成 AI 改后的：收尾比对时这段算原样留着，不算作者改的。
        waitFor { store.learning(workId).originals[chapterId]?.map { it.text } == listOf(after) }
        val edits = EditLearning.compare(store.learning(workId).originals.getValue(chapterId), store.text(workId, chapterId))!!
        assertTrue(edits.pairs.isEmpty() && edits.deleted.isEmpty())
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
        // 问主意在书灵里（AI 条上不再有“灵感…”）。
        compose.onNodeWithContentDescription("AI 写作").performClick()
        assertTrue(compose.onAllNodesWithText("灵感…").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("书灵").performClick()
        waitForText("问主意")
        listOf("细节和描写", "起名", "卡文了", "随便问").forEach { waitForText(it) }
        compose.onNodeWithText("接下来怎么写").performClick()
        waitForText("让他听见门外的脚步声")
        assertTrue("给出接下来剧情的 3 个不同走向" in prompts.single())
        compose.onNodeWithText("关闭").performClick()
        Thread.sleep(1200)
        ShadowLooper.idleMainLooper()
        assertEquals("他推门进来。\n风很大。\n他再门口等着，天色以晚。", WorkStore(writingDir).text(workId, chapterId))
    }

    /** 在正文末尾打字。 */
    private fun typeAtEnd(s: String) {
        val node = compose.onNodeWithContentDescription("正文")
        node.performTextInputSelection(TextRange(WorkStore(writingDir).text(workId, chapterId).length))
        node.performTextInput(s)
    }

    @Test
    fun realtimeCheckFindsATypoAndFixesIt() {
        openEditor()
        waitForText("写作建议已开")
        // 打开时已经写好的段落算查过：光打开、不打字，什么都不发。
        Thread.sleep(4_000)
        ShadowLooper.idleMainLooper()
        assertTrue(prompts.isEmpty())
        // 改了最后一段：停笔几秒，只发改过的这一段（短段不发）。
        typeAtEnd("。")
        // 状态行合成一个数（这一章没有检查规则、没有 AI 腔）。
        waitForText("1 处问题")
        val prompt = prompts.single()
        assertTrue("1. 他再门口等着，天色以晚。" in prompt)
        assertFalse("风很大。" in prompt || "他推门进来。" in prompt)
        // 带设定包，但不给工具：一次请求就答。今天发了几次按作品记在盘上（进程重启也接着算）。
        assertFalse(bodies.single().has("tools"))
        assertEquals("${LocalDate.now()}|1", context.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).getString("suggest_count.$workId", null))
        // 点状态行打开问题面板：这条在“建议”里，能改。
        compose.onNodeWithText("1 处问题", substring = true).performClick()
        waitForText("错别字")
        compose.onNodeWithText("改").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.text(workId, chapterId) == "他推门进来。\n风很大。\n他在门口等着，天色以晚。" }
    }

    @Test
    fun realtimeQueuesLatestEditAfterSlowRequest() {
        openEditor()
        waitForText("写作建议已开")
        typeAtEnd("。")
        waitFor { slowSuggestionStarted.count == 0L }
        // 第一段检查还在等服务商响应；停笔后新内容也必须得到一次检查。
        typeAtEnd("风声又紧。")
        Thread.sleep(3_500)
        ShadowLooper.idleMainLooper()
        assertEquals(1, prompts.size)
        slowSuggestionGate.countDown()
        waitFor(20_000) { prompts.size == 2 }
        assertTrue("风声又紧。" in prompts[1])
        assertEquals(2, prompts.size)
    }

    @Test
    fun realtimeDropsPendingEditWhenStopped() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor()
        waitForText("写作建议已开")
        typeAtEnd("。")
        waitFor { slowSuggestionStarted.count == 0L }
        typeAtEnd("风声又紧。")
        Thread.sleep(3_500)
        ShadowLooper.idleMainLooper()
        assertEquals(1, prompts.size)
        compose.runOnUiThread { vm.writingAi.stopRealtime() }
        slowSuggestionGate.countDown()
        waitFor { vm.writingAi.realtime.value.chapterId.isEmpty() && !vm.writingAi.realtime.value.checking }
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        assertEquals(1, prompts.size)
    }

    @Test
    fun realtimeMalformedResponseWaitsForNextEditToRetry() {
        openEditor()
        waitForText("写作建议已开")
        typeAtEnd("。")
        waitFor { slowSuggestionStarted.count == 0L }
        typeAtEnd("风声又紧。")
        Thread.sleep(3_500)
        ShadowLooper.idleMainLooper()
        assertEquals(1, prompts.size)
        // 没查成写在问题面板里：点状态行打开。
        compose.onNodeWithText("写作建议已开", substring = true).performClick()
        slowSuggestionGate.countDown()
        waitForText("写作建议没查成")
        waitForText("缺少 issues 数组")
        Thread.sleep(700)
        ShadowLooper.idleMainLooper()
        assertEquals("格式错误后不能自动重发收费请求", 1, prompts.size)
        // 新一次停笔才重试，且必须发送当下最新正文。
        typeAtEnd("灯未熄。")
        waitFor(20_000) { prompts.size == 2 }
        val latestParagraph = WorkStore(writingDir).text(workId, chapterId).lines().last().trim()
        assertTrue("两次修改都须保留在正文：$latestParagraph", "风声又紧。" in latestParagraph && "灯未熄。" in latestParagraph)
        assertTrue("第二次请求须包含当下最新段落：${prompts[1]}", latestParagraph in prompts[1])
    }

    @Test
    fun realtimeTypoOnlyIsAPlainProofread() {
        openEditor()
        waitForText("写作建议已开")
        typeAtEnd("。")
        waitForText("1 处问题")
        // 只查错字：普通的挑错，不带设定包。
        assertTrue("只找明显的错误" in prompts.single())
        assertFalse("药铺掌柜的女儿" in systems.single())
        assertFalse(bodies.single().has("tools"))
    }

    @Test
    fun realtimeStopsAtTheDailyCapEvenAfterARestart() {
        openEditor()
        waitForText("写作建议已开")
        typeAtEnd("。")
        // 为什么没查写在问题面板里。
        compose.onNodeWithText("写作建议已开", substring = true).performClick()
        waitForText("今天的 50 次用完了")
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun aCutOffOrStoppedEditCannotBeAccepted() {
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(8))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        // 扩写被长度上限截断：报错，不能采纳。
        compose.onNodeWithText("扩写").performScrollTo().performClick()
        waitForText("回答太长")
        compose.onNodeWithText("采纳").assertIsNotEnabled()
        // 缩写时模型中途停了（拒答或内容过滤）：整段改稿按失败处理，不能采纳，那行说明也不出现。
        compose.onNodeWithText("缩写").performScrollTo().performClick()
        waitForText("模型拒绝回答，或内容被服务商过滤")
        compose.onNodeWithText("采纳").assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText("模型中途停止了回答", substring = true).fetchSemanticsNodes().isEmpty())
        Thread.sleep(1200)
        ShadowLooper.idleMainLooper()
        assertEquals(draft, WorkStore(writingDir).text(workId, chapterId))
    }

    @Test
    fun leavingTheEditorDoesNotCountAsNotAccepted() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(8))
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("润色").performClick()
        waitForText("风刮得很大猛。")
        // 没看完就离开编辑器：面板收起，但不记成“作者不要这种改法”。
        compose.runOnUiThread { vm.back() }
        waitFor { vm.writingAi.state.value == null }
        Thread.sleep(1200)
        ShadowLooper.idleMainLooper()
        val assist = WorkStore(writingDir).assist(workId)
        assertEquals(0, assist.rejected)
        assertTrue(assist.declined.isEmpty())
    }

    @Test
    fun withoutRealtimeNothingIsSentAutomatically() {
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(3))
        Thread.sleep(4_000)
        ShadowLooper.idleMainLooper()
        assertTrue(prompts.isEmpty())
    }

    /** 书灵里问主意：起名先问一句给什么起名，问的时候带上；结果能插到光标处。 */
    @Test
    fun theCompanionAsksForNamesAndTheIdeaCanBeInserted() {
        openEditor()
        compose.onNodeWithContentDescription("书灵").performClick()
        waitForText("问主意")
        compose.onNodeWithText("起名").performClick()
        waitForText("给什么起名？")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("杜娘的茶馆")
        compose.onNodeWithText("好").performClick()
        waitForText("让他听见门外的脚步声")
        assertTrue(prompts.single().contains("杜娘的茶馆"))
        // 字流出来时回答还没收完，“插到光标处”要等回答完整才可点；慢机器上不等就点会落空。
        waitFor { compose.onAllNodes(hasText("插到光标处") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("插到光标处").performClick()
        waitFor { "让他听见门外的脚步声" in WorkStore(writingDir).text(workId, chapterId) }
    }

    @Test
    fun aiWriterUsesAnExplicitReferenceAndWaitsForAuthorAcceptance() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        openEditor()
        compose.onNodeWithContentDescription("书灵").performClick()
        waitForText("没选范本")
        compose.onNodeWithText("选择范本").performClick()
        waitForText("本章还没有范本分析")
        compose.onNodeWithText("关闭").performClick()

        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(3))
        compose.onNodeWithContentDescription("正文").performTextInput("又")
        Thread.sleep(4_000)
        ShadowLooper.idleMainLooper()
        assertTrue("打字、移动光标和打开书灵都不能自动发请求", prompts.isEmpty())

        compose.onNodeWithText("选择范本").performClick()
        compose.onNodeWithText("新增/分析范本").performClick()
        waitForText("写作范本")
        val store = WorkStore(writingDir)
        val reference = store.importReference(workId, "合成范本.txt", "第一章 模拟范本\n门外有人叩门。她先问身份，再把信封压在桌角。")
        val chapter = reference.chapters.single()
        val excerpt = store.selectedChapterExcerpt(workId, reference.id, chapter.id)!!
        store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId, "test-target", "开头先听见叩门声，再让对白试探身份。"))
        val sourceLabel = "《${reference.title}》 · ${chapter.title}"
        compose.runOnUiThread { vm.back() }
        waitFor { compose.onAllNodesWithContentDescription("正文").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("选择范本").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("书灵").performClick()
        }
        waitForText("选择范本")
        compose.onNodeWithText("选择范本").performClick()
        waitForText(sourceLabel)
        compose.onNodeWithText(sourceLabel).performClick()
        waitForText("换范本")
        assertTrue("选中范本也不能自动发请求", prompts.isEmpty())

        // 写正文从“AI 写…”面板进：选了范本时多一行“照范本写法”，默认开着。
        compose.onNodeWithContentDescription("关闭书灵").performClick()
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        val referenceRow = "照范本写法：《${reference.title}》·${chapter.title}"
        waitForText(referenceRow)
        compose.onNodeWithText(referenceRow).performScrollTo().assertIsOn()
        compose.onNodeWithText("按要求写…").performScrollTo().performClick()
        compose.onNodeWithText("开始写").performClick()
        waitForText("想让 AI 怎么写？")
        assertTrue("只有作者给出构思并提交才请求模型", prompts.isEmpty())
        val intent = "来人递出军令，守门人先试探身份"
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(intent)
        compose.onNodeWithText("好").performClick()
        waitFor { vm.writingAi.inline.value?.let { !it.loading && it.text.isNotBlank() } == true }
        val candidate = vm.writingAi.inline.value!!
        assertTrue(prompts.single().contains(intent))
        assertTrue(prompts.single().contains("门外有人叩门"))
        assertEquals("作者采纳前，候选正文不写入章节", candidate.base, store.text(workId, chapterId))

        compose.onNodeWithText("采纳").performClick()
        val accepted = candidate.base.substring(0, candidate.at) + candidate.text + candidate.base.substring(candidate.at)
        waitFor { store.text(workId, chapterId) == accepted }
    }

    @Test
    fun pastingStyleAndLeavingImmediatelyStillSavesIt() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val requested = "合成样本：对白短一些，人物各有语气；叙述保持清楚。"
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        compose.onNodeWithText("对文风的要求").performScrollTo()
        // 同一个事件里输入并返回，不给 600ms 防抖机会；退出补存必须读到字段的最新值。
        compose.onAllNodes(hasSetTextAction())[0].performSemanticsAction(SemanticsActions.SetText) { set ->
            set(AnnotatedString(requested))
            vm.back()
        }
        waitFor { WorkStore(writingDir).assist(workId).style == requested }
        assertTrue(prompts.isEmpty())
    }

    /** 作品 AI 设置里“这部书的口味”：文风旁边，空着时显示例子；边打边存，离开也补存。 */
    @Test
    fun theBooksFlavorIsSavedNextToTheStyle() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitFor { vm.writingAi.assists.value[workId] != null }
        compose.onNodeWithText("这部书的口味").performScrollTo()
        compose.onNodeWithText(FLAVOR_EXAMPLE).assertExists()
        val flavor = "合成样本：轻松带吐槽，主角爱算计，叙述者偶尔玩梗。"
        compose.onAllNodes(hasSetTextAction())[1].performSemanticsAction(SemanticsActions.SetText) { set ->
            set(AnnotatedString(flavor))
            vm.back()
        }
        waitFor { WorkStore(writingDir).assist(workId).flavor == flavor }
        // 文风要求没被动过。
        assertEquals("", WorkStore(writingDir).assist(workId).style)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun dialogsOpenOnTheWorkAndBiblePages() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val store = WorkStore(writingDir)
        // 作品页：长按章节弹出菜单，选“改名”（菜单、对话框要挂在页面的浮层宿主上才看得见）。
        compose.runOnUiThread { vm.open(Screen.Work(workId)) }
        waitForText("作品进度")
        // 概览里的“继续写”也写着章名；规划树懒加载，先滚到能长按的章行。
        val chapterRow = hasText("第1章") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("non-empty scroll viewport") {
            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }).performScrollToNode(chapterRow)
        compose.onNode(chapterRow).performTouchInput { longClick() }
        waitForText("上移")
        compose.onNodeWithText("改名").performClick()
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("第一章 雪夜")
        compose.onNodeWithText("好").performClick()
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
        // 优先级是 1–9 的滑块（读屏能直接设值）。
        compose.onNodeWithContentDescription("优先级").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("保存").performClick()
        waitFor { store.pack(workId).single().priority == 1 }
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("优先级 1 · 常用")
    }

    /** 设定包页“导入设定包”一行：直接问导入文件还是文件夹，选了就开系统选择器。 */
    @Test
    fun theImportRowAsksFilesOrFolderAndOpensThePicker() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { vm.open(Screen.WorkPack(workId)) }
        waitForText("设定包还是空的")
        compose.onNodeWithText("导入设定包").performClick()
        waitForText("导入文件夹")
        compose.onNodeWithText("导入文件").performClick()
        val files = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, files.action)
        waitFor { compose.onAllNodesWithText("导入文件夹").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("导入设定包").performClick()
        waitForText("导入文件夹")
        compose.onNodeWithText("导入文件夹").performClick()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity.action)
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
        compose.onNode(hasText("不导入") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithText("导入 2 个文件").performClick()
        waitFor { store.pack(workId).map { it.title } == listOf("人物设定档案") }
        waitFor { store.get(workId)!!.chapters.map { it.title } == listOf("第1章", "第一章 雪夜", "第二章 来客") }
        waitForText("人物设定档案")
    }
}
