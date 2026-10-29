package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.EditLearning
import com.xycs.lingyuereader.data.FinishOptions
import com.xycs.lingyuereader.data.FixReason
import com.xycs.lingyuereader.data.LearnKind
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从改稿里学的整条路（假的官方 DeepSeek V4 服务，文本自造、人名虚构）：三个入口采纳 AI 的字都会记原稿；
 * 收尾时 AI 归纳的请求不带温度、关思考，回 JSON 能读成候选，格式不对如实报错；“这段不行”的请求带前后段、原因换成的改法、温度，
 * 采纳后草稿只换这一段，原因点满 3 次收尾时提出来；学到的规则写正文、改稿带上，审稿、拟细纲、摘要不带。
 */
class EditLearningFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""
    private val text = "沈默推开茶馆的门。\n杜娘抬头看他。"
    private val draftText = "顾长风推门进来，带进一身雨气。\n林月抬头看他，没说话。\n杜娘把茶碗放下，转身去关窗。"
    private val rewritten = "林月攥紧了袖口，抬头看他，半天才挤出一句：“你还知道回来。”"

    private data class Asked(val user: String, val body: JSONObject) {
        val thinking: String? get() = body.optJSONObject("thinking")?.getString("type")
    }
    private val asked = CopyOnWriteArrayList<Asked>()

    /** AI 归纳请求的回答。 */
    @Volatile
    private var learnAnswer = """{"rules":[]}"""

    /** “这段不行”重写请求的回答。 */
    @Volatile
    private var fixAnswer = rewritten

    /** 写正文请求的回答。 */
    @Volatile
    private var prose = draftText

    /** 不为空时写正文的请求先等着它（让草稿停在“在写”）。 */
    @Volatile
    private var hold: java.util.concurrent.CountDownLatch? = null

    private fun isLearn(user: String) = "请归纳作者反复的改法" in user
    private fun isFix(user: String) = "要重写的这一段" in user

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.optString("role") == "user" }.getString("content")
                asked += Asked(user, body)
                val answer = when {
                    isLearn(user) -> learnAnswer
                    isFix(user) -> fixAnswer
                    "细纲卡" in user && "JSON" in user -> """{"facts":"沈默到渡口"}"""
                    "{\"issues\":" in user -> """{"issues":[]}"""
                    else -> { hold?.await(10, java.util.concurrent.TimeUnit.SECONDS); prose }
                }
                val reply = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                val bytes = reply.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                runCatching { ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡口")
        workId = w.id
        chapterId = w.chapters[0].id
        store.saveText(workId, chapterId, text, count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "顾长风回到渡口，林月和他闹别扭"))
        store.addPackDoc(workId, PackKind.STYLE, "写作规范", "对白要短，不用成语堆砌。")
        // 官方 DeepSeek V4 Pro：请求发给 api.deepseek.com，实际连到本机的假服务。
        val client = AiClient { _ -> URL("http://127.0.0.1:${server.address.port}/chat/completions").openConnection() as HttpURLConnection }
        val ai = AiAssistant(scope, null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true).withKey("sk-test"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private fun originals() = store.learning(workId).originals[chapterId].orEmpty().map { it.text }

    private fun writeDraft() {
        asked.clear()
        writing.writeDraft(workId, chapterId)
        waitFor("草稿") { writing.drafts.value[workId to chapterId]?.let { !it.loading && it.text.isNotBlank() } == true }
    }

    @Test
    fun allThreeWaysOfAcceptingAiProseRecordTheOriginal() {
        // 编辑器里“AI 写”的采纳。
        prose = "杜娘把门闩插上。"
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE)
        waitFor("AI 写") { writing.inline.value?.let { !it.anyLoading } == true }
        val inline = writing.inline.value!!.text
        assertNotNull(writing.acceptInline(text))
        waitFor("记下原稿") { originals().isNotEmpty() }
        assertEquals(listOf(inline.trim()), originals())

        // 逐拍写的采纳。
        store.saveCard(workId, chapterId, ChapterCard(facts = "顾长风回到渡口", beats = "顾长风进门\n林月不理他"))
        val now = store.text(workId, chapterId)
        prose = "林月背过身去，只当没看见他。"
        writing.writeInline(workId, chapterId, now, now.length, InlineMode.BEAT, beat = 2)
        waitFor("写这一拍") { writing.inline.value?.let { !it.anyLoading } == true }
        assertNotNull(writing.acceptInline(now))
        waitFor("记下这一拍") { originals().size == 2 }

        // 草稿页“放进正文”（接在后面）：再记一段；替换整章时以前记的作废。
        val model = WritingModel(store, scope) { throw AssertionError(it) }
        var placed = false
        model.applyDraft(workId, chapterId, draftText, replace = false) { placed = true }
        waitFor("放进正文") { placed }
        assertEquals(3, originals().size)
        assertEquals(draftText, originals().last())
        placed = false
        model.applyDraft(workId, chapterId, draftText, replace = true) { placed = true }
        waitFor("替换正文") { placed }
        assertEquals(listOf(draftText), originals())
    }

    /** 记一章：AI 原稿是 [ai]，作者改成 [final]。 */
    private fun seedChapter(id: String, ai: String, final: String) {
        store.saveText(workId, id, ai, count = false)
        store.recordAiText(workId, id, ai)
        store.saveText(workId, id, final, count = false)
    }

    private val ban = "眼中闪过一丝慌乱"

    private fun cutChapter(n: Int): Pair<String, String> {
        val ai = List(n) { i -> "林月${ban}，把第${i + 1}盏灯笼挂上船头。" } + "老周在岸上喊了一声，杜娘没应。"
        val fin = List(n) { i -> "林月把第${i + 1}盏灯笼挂上船头。" } + "老周在岸上喊了一声，杜娘没应。"
        return ai.joinToString("\n") to fin.joinToString("\n")
    }

    private fun finish(options: FinishOptions = FinishOptions(sync = false, summary = false, review = false, learn = true)): FinishRun {
        writing.openFinish(workId, chapterId)
        waitFor("收尾页读完") { writing.finish.value?.learnable == true }
        writing.setFinishOptions(options)
        writing.startFinish()
        waitFor("走到确认") { writing.finish.value?.stage == FinishStage.CONFIRM }
        return writing.finish.value!!
    }

    @Test
    fun finishingLearnsLocallyAndAsksTheAiWithoutTemperatureOrThinking() {
        store.updateAssist(workId) { it.copy(temperature = 0.9f, thinking = true) }
        // 前一章已经删过两处，这一章又删一处：跨了章，提黑名单词。
        val prior = store.addChapter(workId, store.get(workId)!!.volumes.single().id)!!.second.id
        cutChapter(2).let { (a, f) -> seedChapter(prior, a, f) }
        val (a, f) = cutChapter(1)
        seedChapter(chapterId, a, f)
        // 前一章先收一次（只学）。
        val saved = chapterId
        chapterId = prior
        learnAnswer = """{"rules":[]}"""
        finish()
        writing.applyFinish(finalize = { w, c -> store.setStatus(w, c, ChapterStatus.FINAL) })
        waitFor("前一章收完") { writing.finish.value?.stage == FinishStage.DONE }
        chapterId = saved

        learnAnswer = """{"rules":[{"kind":"rule","text":"动作写在对白前面，别补解释","p":1}]}"""
        asked.clear()
        val run = finish()
        val learn = asked.single { isLearn(it.user) }
        assertFalse(learn.body.has("temperature"))
        assertEquals("disabled", learn.thinking)
        assertTrue("林月${ban}" in learn.user)
        val local = run.candidates.single { it.kind == LearnKind.BAN }
        assertEquals(ban, local.text)
        val fromAi = run.candidates.single { it.source == "AI 归纳" }
        assertEquals("动作写在对白前面，别补解释", fromAi.text)
        // 本机比对的默认勾上，AI 归纳的不勾。
        assertTrue(local.key in run.learnPicked)
        assertFalse(fromAi.key in run.learnPicked)

        // 作者只勾了黑名单词、AI 那条点了“不要”：黑名单词进黑名单，AI 那条记进“不再提”。
        writing.declineFinishLearn(fromAi.key, true)
        assertFalse(fromAi.key in writing.finish.value!!.learnPicked)
        writing.applyFinish(finalize = { w, c -> store.setStatus(w, c, ChapterStatus.FINAL) })
        waitFor("收完") { writing.finish.value?.stage == FinishStage.DONE }
        assertTrue(ban in store.assist(workId).banned)
        assertTrue(store.assist(workId).learned.isEmpty())
        assertTrue(fromAi.key in store.learning(workId).dismissed)
    }

    @Test
    fun aMalformedAiAnswerIsReportedAndNotTurnedIntoRules() {
        val (a, f) = cutChapter(2)
        seedChapter(chapterId, a, f)
        learnAnswer = "作者喜欢把灯笼写得具体一些。"
        val run = finish()
        assertTrue(asked.any { isLearn(it.user) })
        assertTrue(run.learnError!!.contains("JSON"))
        assertTrue(run.candidates.none { it.source == "AI 归纳" })
        assertTrue(run.candidates.none { "灯笼" in it.text })
    }

    @Test
    fun rejectingAParagraphRewritesOnlyThatOneAndCountsTheReason() {
        store.updateAssist(workId) { it.copy(temperature = 0.9f, learned = listOf("对白说完就收住。")) }
        writeDraft()
        assertEquals(draftText, writing.drafts.value[workId to chapterId]!!.text)

        asked.clear()
        writing.fixParagraph(workId, chapterId, 1, setOf(FixReason.FLAT, FixReason.OTHER), "她该生气")
        waitFor("重写这段") { writing.paragraphFix.value?.loading == false }
        val fix = writing.paragraphFix.value!!
        assertEquals(null, fix.error)
        assertEquals(rewritten, fix.text)
        assertEquals("林月抬头看他，没说话。", fix.original)
        val request = asked.single()
        // 前后各一段、要改的这段、原因换成的改法、写法要求（带学到的规则）、写正文的温度。
        assertTrue("前一段（只作语境，不要改）：\n顾长风推门进来，带进一身雨气。" in request.user)
        assertTrue("后一段（只作语境，不要改）：\n杜娘把茶碗放下，转身去关窗。" in request.user)
        assertTrue("要重写的这一段：\n林月抬头看他，没说话。" in request.user)
        assertTrue(FixReason.FLAT.fix in request.user)
        assertTrue("作者的意见：她该生气" in request.user)
        assertTrue(WritePrompts.PROSE_HEADER in request.user && WritePrompts.LEARNED_HEADER in request.user)
        assertEquals(0.9, request.body.getDouble("temperature"), 1e-9)
        // 还没采纳：不计数。
        assertEquals(null, store.learning(workId).reasons[FixReason.FLAT.name])

        // 采纳：草稿只换了这一段，存了盘，原来那版能换回。
        writing.acceptParagraphFix()
        waitFor("换好") { writing.drafts.value[workId to chapterId]?.let { !it.loading && it.text != draftText } == true }
        val after = writing.drafts.value[workId to chapterId]!!
        val lines = after.text.split('\n')
        assertEquals(listOf("顾长风推门进来，带进一身雨气。", rewritten, "杜娘把茶碗放下，转身去关窗。"), lines)
        assertEquals(draftText, after.initialText)
        waitFor("草稿存盘") { store.draft(workId, chapterId) == after.text }
        // 例子和次数在草稿存盘之后补记：采纳了才算一次。
        waitFor("记下例子") { store.learning(workId).reasonExamples[FixReason.FLAT.name]?.second == rewritten }
        assertEquals(1, store.learning(workId).reasons[FixReason.FLAT.name])

        // 点了又放弃的不算。
        repeat(2) {
            writing.fixParagraph(workId, chapterId, 0, setOf(FixReason.FLAT))
            waitFor("又重写") { writing.paragraphFix.value?.loading == false }
            writing.dropParagraphFix()
        }
        assertEquals(1, store.learning(workId).reasons[FixReason.FLAT.name])
        // 同一个原因再采纳两次（够 3 次），收尾学习时提出来。
        for (n in 2..3) {
            writing.fixParagraph(workId, chapterId, 0, setOf(FixReason.FLAT))
            waitFor("又重写") { writing.paragraphFix.value?.let { !it.loading && it.error == null } == true }
            writing.acceptParagraphFix()
            waitFor("采纳第 $n 次") { store.learning(workId).reasons[FixReason.FLAT.name] == n }
        }
        store.recordAiText(workId, chapterId, store.text(workId, chapterId))
        val run = finish()
        val c = run.candidates.single { it.key == "reason:${FixReason.FLAT.name}" }
        assertEquals("写对白时给人物补一句当下的情绪或动作。", c.text)
        assertTrue(c.key in run.learnPicked)
        writing.applyFinish(finalize = { w, ch -> store.setStatus(w, ch, ChapterStatus.FINAL) })
        waitFor("收完") { writing.finish.value?.stage == FinishStage.DONE }
        assertTrue(c.text in store.assist(workId).learned)
    }

    @Test
    fun failedRewritesAreNotCountedAsReasons() {
        writeDraft()
        // 模型没写出来：连点三次“太死板”，都失败了。
        fixAnswer = ""
        repeat(3) {
            writing.fixParagraph(workId, chapterId, 1, setOf(FixReason.STIFF))
            waitFor("重写失败") { writing.paragraphFix.value?.let { !it.loading && it.error != null } == true }
            writing.dropParagraphFix()
        }
        assertEquals(3, asked.count { isFix(it.user) })
        val data = store.learning(workId)
        assertEquals(null, data.reasons[FixReason.STIFF.name])
        assertEquals(null, data.reasonExamples[FixReason.STIFF.name])
        assertTrue(EditLearning.candidates(data, store.assist(workId)).none { it.key == "reason:${FixReason.STIFF.name}" })
    }

    @Test
    fun aRewriteSavedButNotFullyRecordedShowsWhatIsOnDisk() {
        writeDraft()
        val key = workId to chapterId
        writing.fixParagraph(workId, chapterId, 1, setOf(FixReason.FLAT))
        waitFor("重写这段") { writing.paragraphFix.value?.let { !it.loading && it.error == null } == true }
        // 草稿写进去以后，清“项目包流程”标记那一步写不成（作品 AI 设置的临时文件位置被占了）。
        store.setDraftWorkflow(workId, chapterId, true)
        val blocked = File(tmp.root, "writing/works/$workId/assist.json.tmp")
        assertTrue(blocked.mkdirs())
        try {
            writing.acceptParagraphFix()
            val replaced = listOf("顾长风推门进来，带进一身雨气。", rewritten, "杜娘把茶碗放下，转身去关窗。").joinToString("\n")
            waitFor("草稿存盘") { store.draft(workId, chapterId) == replaced }
            waitFor("对账") { writing.drafts.value[key]?.let { !it.loading && it.error != null } == true }
            // 界面按磁盘上实际的内容显示（新稿），不退回旧稿装作“没换”。
            assertEquals(replaced, writing.drafts.value[key]!!.text)
            // 没存全不算采纳：不计数。
            assertEquals(null, store.learning(workId).reasons[FixReason.FLAT.name])
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun aiCandidatesTheAuthorDidNotLookAtAreNotDismissed() {
        val prior = store.addChapter(workId, store.get(workId)!!.volumes.single().id)!!.second.id
        cutChapter(2).let { (a, f) -> seedChapter(prior, a, f) }
        val (a, f) = cutChapter(1)
        seedChapter(chapterId, a, f)
        val saved = chapterId
        chapterId = prior
        finish()
        writing.applyFinish(finalize = { w, c -> store.setStatus(w, c, ChapterStatus.FINAL) })
        waitFor("前一章收完") { writing.finish.value?.stage == FinishStage.DONE }
        chapterId = saved

        learnAnswer = """{"rules":[{"kind":"ban","text":"$ban","p":1},{"kind":"rule","text":"动作写在对白前面，别补解释","p":1}]}"""
        val run = finish()
        val local = run.candidates.single { it.key == "ban:$ban" }
        // 同一个词 AI 也提了：本机的在前，AI 的被并掉；AI 的规则单列，默认不勾。
        val fromAi = run.candidates.single { it.source == EditLearning.FROM_AI }
        assertTrue(fromAi.key.startsWith("ai:"))
        // 本机那条作者取消了勾；AI 那条没动，直接完成。
        writing.toggleFinishLearn(local.key, false)
        writing.applyFinish(finalize = { w, c -> store.setStatus(w, c, ChapterStatus.FINAL) })
        waitFor("收完") { writing.finish.value?.stage == FinishStage.DONE }
        val dismissed = store.learning(workId).dismissed
        assertTrue(local.key in dismissed)
        assertFalse(fromAi.key in dismissed)
        assertTrue(store.assist(workId).learned.isEmpty())
    }

    @Test
    fun noParagraphRewriteWhileTheDraftIsBusy() {
        writeDraft()
        val key = workId to chapterId
        // 草稿在写（审读时也是这个状态）：点了不发请求。
        val latch = java.util.concurrent.CountDownLatch(1)
        hold = latch
        writing.writeDraft(workId, chapterId)
        waitFor("开始写") { writing.drafts.value[key]?.loading == true }
        val before = asked.size
        writing.fixParagraph(workId, chapterId, 1, setOf(FixReason.WORDY))
        assertEquals(null, writing.paragraphFix.value)
        latch.countDown()
        hold = null
        waitFor("写完") { writing.drafts.value[key]?.loading == false }
        assertTrue(asked.drop(before).none { isFix(it.user) })
        assertEquals(null, store.learning(workId).reasons[FixReason.WORDY.name])
    }

    @Test
    fun learnedRulesReachProseAndEditsButNotReviewsPlansOrSummaries() {
        store.updateAssist(workId) { it.copy(learned = listOf("对白说完就收住。")) }
        // 审稿（三道审稿）、拟细纲、摘要：不带。
        asked.clear()
        runBlocking { writing.startReview(workId, chapterId, text).join() }
        assertTrue(asked.isNotEmpty())
        runBlocking { runCatching { writing.planCard(workId, chapterId) } }
        runBlocking { writing.summarize(workId, chapterId) }
        assertTrue(asked.none { WritePrompts.LEARNED_HEADER in it.user || "对白说完就收住" in it.user })
        // 写正文、改稿：带。
        writeDraft()
        assertTrue(asked.any { WritePrompts.LEARNED_HEADER in it.user && "对白说完就收住" in it.user })
        asked.clear()
        writing.run(workId, chapterId, text, WriteAiTask.Edit(WriteAction.POLISH, "杜娘抬头看他。", 10, 17))
        waitFor("润色") { writing.state.value?.loading == false && asked.isNotEmpty() }
        assertTrue("对白说完就收住" in asked.single().user)
        assertTrue(EditLearning.REASON_AT == 3)
    }
}
