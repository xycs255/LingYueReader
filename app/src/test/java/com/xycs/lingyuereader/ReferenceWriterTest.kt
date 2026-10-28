package com.xycs.lingyuereader

import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.DraftBeats
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.referenceText
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/** The reference is synthetic; the fake provider never reads a real book or touches a live API. */
class ReferenceWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private lateinit var storeRoot: File
    private lateinit var store: WorkStore
    private lateinit var assistant: AiAssistant
    private lateinit var writing: WritingAi
    private lateinit var workId: String
    private lateinit var chapterId: String
    private lateinit var referenceId: String
    private lateinit var referenceChapterId: String
    private lateinit var analysis: ReferenceAnalysis
    private lateinit var selectedText: String
    private val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val original = "原稿标记：主角还在山路上。"
    private val drafted = "主角停在渡口，先听见木船撞岸，再决定要不要上船。"
    @Volatile private var modelAnswer = drafted
    @Volatile private var projectResponses = false
    @Volatile private var reviewAnswer = """{"summary":[],"missing":[],"issues":[]}"""
    /** 写正文的请求依次拿这里的回答（空了用 [modelAnswer]）；"HTTP 503" 是让这一次请求失败。 */
    private val createAnswers = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** 不为空时，写正文的请求流式先给 [streamFirst]，再等它放行才结束。 */
    @Volatile private var holdCreate: java.util.concurrent.CountDownLatch? = null
    @Volatile private var streamFirst = ""

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().decodeToString())
                requests += request
                val messages = request.getJSONArray("messages")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }
                    .last { it.optString("role") == "user" }.optString("content")
                val reviewing = projectResponses && "【当前阶段：审读】" in user
                val hold = holdCreate
                if (!reviewing && hold != null) {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    runCatching {
                        exchange.responseBody.use { out ->
                            val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", streamFirst))))
                            out.write("data: $delta\n\n".toByteArray())
                            out.flush()
                            hold.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            out.write("data: [DONE]\n\n".toByteArray())
                        }
                    }
                    return@createContext
                }
                val answer = if (reviewing) reviewAnswer else createAnswers.poll() ?: modelAnswer
                if (answer == "HTTP 503") {
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                    return@createContext
                }
                val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", answer))
                    .put("finish_reason", "stop")))
                    .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 30))
                    .toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            this.executor = this@ReferenceWriterTest.executor
            start()
        }
        storeRoot = tmp.newFolder("reference-writing")
        store = WorkStore(storeRoot)
        val work = store.create("目标作品")
        workId = work.id
        chapterId = work.chapters.single().id
        store.saveText(workId, chapterId, original, count = false)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(
            facts = "人定事实：付出代价才能渡河。", boundary = "章末在对岸。"))
        val reference = store.importReference(workId, "合成范本.txt", """
            第一章 门前
            未选原文标记：他走过街角，看见旧铺子的招牌。
            已选原文标记：她先敲门，再听门里的人拒绝，然后换一种问法。
            “门还开吗？”她问。屋里的人没有回答，只把灯挪到窗边。
            未选结尾标记：后来天空亮了，她才离开。
            第二章 次日
            严禁发送别章标记：隔天的事与第一章无关。
        """.trimIndent())
        referenceId = reference.id
        referenceChapterId = reference.chapters.first().id
        val source = store.referenceText(workId, referenceId, referenceChapterId)!!
        val start = source.indexOf("已选原文标记")
        val end = source.indexOf("未选结尾标记")
        require(start >= 0 && end > start)
        val excerpt = store.selectedChapterExcerpt(workId, referenceId, referenceChapterId,
            ReferencePart.RANGE, maxChars = 500, start = start, end = end)!!
        selectedText = excerpt.text
        analysis = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId,
            "old-target-fingerprint", "历史分析结论标记：旧稿应该写铺子；不能注入新稿。"))
        assistant = AiAssistant(scope, null, bookFile = { _, _ -> null })
        assistant.saveConfig(AiConfig(enabled = true, preset = AiPresets.CUSTOM,
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "test-model").withKey("test-key"))
        writing = WritingAi(store, assistant, scope) { }
    }

    @After fun tearDown() {
        scope.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(10)
    }

    private fun sentText(): String = requests.joinToString("\n") { request ->
        val messages = request.getJSONArray("messages")
        (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).getString("content") }
    }

    private suspend fun persistedCandidate(): String {
        val expected = writing.drafts.value[workId to chapterId]!!.text
        waitFor { store.draft(workId, chapterId) == expected &&
            store.draftContent(workId, chapterId).reference?.verified == true }
        return expected
    }

    private fun projectPack() {
        store.renameChapter(workId, chapterId, "第一章 渡河")
        store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", """
            # 第一卷
            ### 第一章 渡河
            事实：主角在渡口询问船主，决定付出代价后过河。
            视角：主角
            边界：章末到达对岸，不凭空改变渡河代价。
        """.trimIndent())
        store.addPackDoc(workId, PackKind.INSTRUCTION, "完整正文创作提示词", "请输出完整小说正文。")
        store.addPackDoc(workId, PackKind.INSTRUCTION, "章节审读提示词", "只反馈有证据的问题。")
        store.addPackDoc(workId, PackKind.INSTRUCTION, "章节修订提示词", "只修改核实的必改问题。")
        projectResponses = true
    }

    private fun secondAnalysis(): ReferenceAnalysis {
        val source = store.referenceText(workId, referenceId, referenceChapterId)!!
        val excerpt = store.selectedChapterExcerpt(workId, referenceId, referenceChapterId,
            ReferencePart.RANGE, maxChars = 500, start = 0, end = source.indexOf("已选原文标记"))!!
        return store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId,
            "other-target", "第二条合成写法分析"))
    }

    @Test fun selectedReferenceGuidesCandidateWithoutWritingTheManuscript() = runBlocking {
        writing.writeDraft(workId, chapterId, instruction = "写出渡河代价", referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertNull(writing.drafts.value[workId to chapterId]?.error)
        val sent = sentText()
        assertTrue(sent.contains("已选原文标记"))
        assertTrue(sent.contains("她先敲门，再听门里的人拒绝，然后换一种问法。"))
        assertFalse(sent.contains("未选原文标记"))
        assertFalse(sent.contains("未选结尾标记"))
        assertFalse(sent.contains("严禁发送别章标记"))
        assertFalse(sent.contains("历史分析结论标记"))
        assertTrue(sent.contains("人定事实"))
        assertTrue(sent.contains("不能照搬范本中的人物"))
        assertTrue(sent.contains("本章任务为准"))
        assertTrue(writing.drafts.value[workId to chapterId]!!.text.contains("渡口"))
        assertEquals(original, store.text(workId, chapterId))

        // The author must not adopt this candidate after its source chapter changes.
        val path = java.io.File(store.referenceRoot(workId), "$referenceId/$referenceChapterId.txt")
        path.writeText(path.readText().replace("未选结尾标记", "已变结尾标记"))
        assertTrue(writing.draftBlocked(workId, chapterId)?.contains("范本") == true)
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun missingOrDamagedSelectionFailsClosedBeforeAnyAiRequest() = runBlocking {
        writing.writeDraft(workId, chapterId, referenceAnalysisId = "missing")
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertTrue(writing.drafts.value[workId to chapterId]?.error?.isNotBlank() == true)
        assertTrue(requests.isEmpty())

        val path = java.io.File(store.referenceRoot(workId), "$referenceId/$referenceChapterId.txt")
        // Change only text outside the chosen range. A substring-only check would miss this.
        val savedSource = path.readText()
        assertTrue(savedSource.contains("未选原文标记"))
        path.writeText(savedSource.replace("未选原文标记", "未选原文修改"))
        writing.writeDraft(workId, chapterId, referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertTrue(writing.drafts.value[workId to chapterId]?.error?.isNotBlank() == true)
        assertTrue(requests.isEmpty())

        path.writeText(savedSource)
        assertTrue(path.delete())
        writing.writeDraft(workId, chapterId, referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertTrue(writing.drafts.value[workId to chapterId]?.error?.isNotBlank() == true)
        assertTrue(requests.isEmpty())
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun inlineReferenceNeedsAnExplicitAcceptAndRetryKeepsItsSource() = runBlocking {
        writing.writeInline(workId, chapterId, original, original.length, InlineMode.WORDS,
            referenceAnalysisId = analysis.id)
        waitFor { writing.inline.value?.loading == false }
        assertNull(writing.inline.value?.error)
        assertTrue(sentText().contains("已选原文标记"))
        assertEquals(original, store.text(workId, chapterId))
        requests.clear()
        writing.retryInline()
        waitFor { writing.inline.value?.loading == false && requests.isNotEmpty() }
        assertTrue(sentText().contains("已选原文标记"))
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun copiedSourceIsRejectedAndCannotBeAccepted() = runBlocking {
        assertTrue(selectedText.length > 30)
        modelAnswer = selectedText
        writing.writeDraft(workId, chapterId, referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        val candidate = writing.drafts.value[workId to chapterId]!!
        assertTrue(candidate.error?.isNotBlank() == true)
        assertFalse(candidate.text.contains("已选原文标记"))
        assertFalse(store.draft(workId, chapterId).contains("已选原文标记"))
        assertEquals(original, store.text(workId, chapterId))

        writing.writeInline(workId, chapterId, original, original.length, InlineMode.WORDS,
            referenceAnalysisId = analysis.id)
        waitFor { writing.inline.value?.loading == false }
        assertTrue(writing.inline.value?.error?.isNotBlank() == true)
        assertNull(writing.acceptInline(original))
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun withoutReferenceWritingStillWorksAndDoesNotSeeSource() = runBlocking {
        writing.writeDraft(workId, chapterId)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertNull(writing.drafts.value[workId to chapterId]?.error)
        assertFalse(sentText().contains("已选原文标记"))
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun reopenedReferenceCandidateKeepsItsSourceAndBlocksAfterSourceDeletion() = runBlocking {
        writing.writeDraft(workId, chapterId, referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertNull(writing.drafts.value[workId to chapterId]?.error)
        val candidate = persistedCandidate()
        val saved = store.draftContent(workId, chapterId).reference!!
        assertEquals(analysis.id, saved.analysisId)
        assertEquals(analysis.sourceFingerprint, saved.sourceFingerprint)
        assertNotNull(saved.chapterLength)

        val reopenedStore = WorkStore(storeRoot)
        val reopened = WritingAi(reopenedStore, assistant, scope) { }
        reopened.loadDraft(workId, chapterId)
        waitFor { reopened.drafts.value.containsKey(workId to chapterId) }
        val loaded = reopened.drafts.value[workId to chapterId]!!
        assertEquals(candidate, loaded.text)
        assertEquals(analysis.id, loaded.referenceAnalysisId)
        assertEquals(saved.sourceFingerprint, loaded.referenceFingerprint)
        assertEquals(saved.chapterLength, loaded.referenceChapterLength)
        assertNull(reopened.draftBlocked(workId, chapterId))

        val source = File(reopenedStore.referenceRoot(workId), "$referenceId/$referenceChapterId.txt")
        assertTrue(source.delete())
        assertNotNull(reopened.draftBlocked(workId, chapterId))
        assertEquals(original, reopenedStore.text(workId, chapterId))
    }

    @Test fun backupRestoresProvenanceAndMissingSidecarBlocksReopenedCandidate() = runBlocking {
        writing.writeDraft(workId, chapterId, referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        val candidate = persistedCandidate()
        val restoredRoot = tmp.newFolder("restored-writing")
        val restored = WorkStore(restoredRoot)
        assertEquals(1, restored.restoreBackup(String(store.backup()!!)))
        val restoredContent = restored.draftContent(workId, chapterId)
        assertEquals(candidate, restoredContent.text)
        assertEquals(analysis.id, restoredContent.reference?.analysisId)
        assertEquals(analysis.sourceFingerprint, restoredContent.reference?.sourceFingerprint)
        assertTrue(restoredContent.reference?.verified == true)
        assertNotNull(restored.referenceText(workId, referenceId, referenceChapterId))

        val sidecar = File(restored.referenceRoot(workId).parentFile, "$chapterId.draft.reference.json")
        assertTrue(sidecar.delete())
        val reopened = WritingAi(WorkStore(restoredRoot), assistant, scope) { }
        reopened.loadDraft(workId, chapterId)
        waitFor { reopened.drafts.value.containsKey(workId to chapterId) }
        val loaded = reopened.drafts.value[workId to chapterId]!!
        assertEquals(candidate, loaded.text)
        assertTrue(loaded.referenceProvenanceBlocked)
        assertNotNull(reopened.draftBlocked(workId, chapterId))
        assertEquals(original, restored.text(workId, chapterId))
    }

    @Test fun freshProjectWriteReplacesBlockedLegacyCandidateAndCanPassReview() = runBlocking {
        projectPack()
        store.saveDraft(workId, chapterId, "无来源旧候选标记", blocked = true)
        store.setDraftWorkflow(workId, chapterId, true)
        val reopenedStore = WorkStore(storeRoot)
        val reopened = WritingAi(reopenedStore, assistant, scope) { }
        reopened.loadDraft(workId, chapterId)
        waitFor { reopened.drafts.value.containsKey(workId to chapterId) }
        assertTrue(reopened.drafts.value[workId to chapterId]!!.referenceProvenanceBlocked)
        assertNotNull(reopened.draftBlocked(workId, chapterId))

        reopened.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字",
            referenceAnalysisId = analysis.id)
        waitFor { reopened.drafts.value[workId to chapterId]?.loading == false }
        val fresh = reopened.drafts.value[workId to chapterId]!!
        assertEquals(fresh.error, DraftStage.READY, fresh.stage)
        assertEquals(drafted, fresh.text)
        assertFalse(fresh.referenceProvenanceBlocked)
        assertTrue(fresh.referenceValidated)
        assertNull(reopened.draftBlocked(workId, chapterId))
        waitFor { reopenedStore.draftContent(workId, chapterId).reference?.verified == true }
        assertFalse(reopenedStore.draftContent(workId, chapterId).blocked)
        assertEquals(original, reopenedStore.text(workId, chapterId))
    }

    @Test fun continuingNonemptyCandidateCannotSwitchReferenceBeforeAnyRequest() = runBlocking {
        val other = secondAnalysis()
        suspend fun generated(referenceId: String?) {
            writing.writeDraft(workId, chapterId, referenceAnalysisId = referenceId)
            waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
            assertNull(writing.drafts.value[workId to chapterId]?.error)
            val candidate = writing.drafts.value[workId to chapterId]!!.text
            waitFor { store.draft(workId, chapterId) == candidate }
            requests.clear()
        }
        suspend fun rejected(referenceId: String?) {
            writing.writeDraft(workId, chapterId, more = true, referenceAnalysisId = referenceId)
            waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
            assertNotNull(writing.drafts.value[workId to chapterId]?.error)
            assertTrue("切换范本不应请求模型", requests.isEmpty())
        }
        generated(analysis.id)
        rejected(other.id)
        generated(analysis.id)
        rejected(null)
        generated(null)
        rejected(other.id)
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun projectReviewCanRecoverAnOrdinaryErrorButRejectChangedAnalysisUnderSameId() = runBlocking {
        projectPack()
        reviewAnswer = "这不是约定的审读 JSON"
        writing.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字",
            referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        val failed = writing.drafts.value[workId to chapterId]!!
        assertEquals(DraftStage.ERROR, failed.stage)
        assertNotNull(failed.error)
        assertEquals(drafted, failed.text)
        assertTrue(failed.referenceValidated)

        reviewAnswer = """{"summary":[],"missing":[],"issues":[]}"""
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        val reviewed = writing.drafts.value[workId to chapterId]!!
        assertEquals(reviewed.error, DraftStage.READY, reviewed.stage)
        assertNull(writing.draftBlocked(workId, chapterId))
        val sentBefore = requests.size

        val replacement = secondAnalysis().copy(id = analysis.id)
        store.saveReferenceAnalysis(workId, replacement)
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        assertNotNull(writing.drafts.value[workId to chapterId]?.error)
        assertEquals("同一 ID 指向其他范本时不能请求模型", sentBefore, requests.size)
        assertNotNull(writing.draftBlocked(workId, chapterId))
        assertEquals(original, store.text(workId, chapterId))
    }

    /** 项目包、逐拍写，拍拍写长了在句末收住：写完存盘的各拍是核验过的。 */
    private fun beatsAndLongAnswers(answer: String = "主角停在渡口。" + "木船撞岸，他听着水声，想了一阵。".repeat(30)) {
        projectPack()
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(beats = "1. 主角走到渡口\n2. 主角付出代价过河"))
        modelAnswer = answer
    }

    private suspend fun idle(): DraftState {
        waitFor { writing.drafts.value[workId to chapterId]?.loading == false }
        return writing.drafts.value[workId to chapterId]!!
    }

    private fun reviews(): Int = requests.count { r ->
        val m = r.getJSONArray("messages")
        (0 until m.length()).any { "【当前阶段：审读】" in m.getJSONObject(it).optString("content") }
    }

    @Test fun cappedBeatsThenCondensedDraftCanStillBeReviewed() = runBlocking {
        // 真机上卡住的流程：项目包 + 范本 + 逐拍写（拍拍收住），稿子比目标长，点“压到约 N 字”，再点“审读并修订此稿”。
        beatsAndLongAnswers()
        writing.writeDraft(workId, chapterId, workflow = true, targetLength = "100—200字", referenceAnalysisId = analysis.id)
        val written = idle()
        assertTrue(written.notice.orEmpty(), written.notice.orEmpty().contains("收住"))
        assertTrue(written.referenceValidated)
        assertEquals(DraftBeats(2, 2), written.beats)
        writing.condenseDraft(workId, chapterId, "100—200字")
        waitFor { writing.drafts.value[workId to chapterId]?.let { !it.loading && it.text != written.text } == true }
        val condensed = writing.drafts.value[workId to chapterId]!!.text
        waitFor { store.draft(workId, chapterId) == condensed }
        val before = reviews()
        writing.reviewDraft(workId, chapterId, targetLength = "100—200字")
        val reviewed = idle()
        assertNull("以前这里报“原候选稿的范本来源未核验或已变化，请从头重写”", reviewed.error)
        assertNull(reviewed.referenceIssue)
        assertTrue("真的审读了", reviews() > before)
        // 压出来的新一版重新核过范本，和正文、范本指纹一起落盘。
        assertTrue(store.draftContent(workId, chapterId).reference?.verified == true)
        assertEquals(original, store.text(workId, chapterId))
    }

    @Test fun aChapterStoppedHalfWayKeepsAVerifiedSourceAndCanBeReviewed() = runBlocking {
        projectPack()
        val release = java.util.concurrent.CountDownLatch(1)
        streamFirst = "主角停在渡口，先听见木船撞岸。"
        holdCreate = release
        writing.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字", referenceAnalysisId = analysis.id)
        waitFor { writing.drafts.value[workId to chapterId]?.text?.contains("木船撞岸") == true }
        writing.stopDraft(workId, chapterId)
        release.countDown()
        holdCreate = null
        val stopped = idle()
        assertEquals(DraftStage.STOPPED, stopped.stage)
        waitFor { store.draft(workId, chapterId) == stopped.text }
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        val reviewed = idle()
        assertNull("以前这里报“原候选稿的范本来源未核验或已变化，请从头重写”", reviewed.error)
        assertEquals(stopped.text, reviewed.text)
        // 停下时留下的半章也核过范本：标成已核验，随正文落盘。
        assertTrue(reviewed.referenceValidated)
        assertTrue(store.draftContent(workId, chapterId).reference?.verified == true)
    }

    @Test fun aBeatThatCopiesTheSourceKeepsTheVerifiedBeatsBeforeIt() = runBlocking {
        beatsAndLongAnswers(drafted)
        // 第二拍照抄范本：这一拍不要，第一拍（核验过、已存盘）留着，不退回空稿。
        createAnswers += drafted
        createAnswers += selectedText
        writing.writeDraft(workId, chapterId, workflow = true, targetLength = "100—200字", referenceAnalysisId = analysis.id)
        val failed = idle()
        assertTrue(failed.error.orEmpty(), failed.error.orEmpty().contains("照抄范本"))
        assertEquals(drafted, failed.text)
        assertEquals(DraftBeats(1, 2), failed.beats)
        assertTrue(failed.referenceValidated)
        assertNull("这次新写的那拍没过，不是已写稿的问题", failed.referenceIssue)
        waitFor { store.draftContent(workId, chapterId).let { it.text == drafted && it.reference?.verified == true && it.beats == DraftBeats(1, 2) } }
        // 单拍出错（服务商失败）也一样：前面的拍留着，可以审读。
        writing.reviewDraft(workId, chapterId, targetLength = "100—200字")
        val reviewed = idle()
        assertNull(reviewed.error)
        assertEquals(drafted, reviewed.text)
    }

    @Test fun aLostMarkIsRecheckedAndAChangedSourceCanBeDroppedOnlyIfNothingIsCopied() = runBlocking {
        projectPack()
        writing.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字", referenceAnalysisId = analysis.id)
        idle()
        // 作者手改一处：标记没了，审读前被拦下，给出路（不是“请从头重写”）。
        writing.updateDraftText(workId, chapterId, drafted + "\n他把船钱放在桩上。")
        waitFor { store.draft(workId, chapterId).endsWith("桩上。") }
        val before = requests.size
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        val blocked = idle()
        assertEquals(DraftReferenceIssue.UNVERIFIED, blocked.referenceIssue)
        assertTrue(blocked.error.orEmpty(), blocked.error.orEmpty().contains("重新核验范本"))
        assertEquals("拦下时不发请求", before, requests.size)
        writing.recheckDraftReference(workId, chapterId)
        val rechecked = idle()
        assertTrue(rechecked.referenceValidated)
        assertNull(rechecked.error)
        assertNull(rechecked.referenceIssue)
        waitFor { store.draftContent(workId, chapterId).reference?.verified == true }
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        assertNull(idle().error)

        // 范本改了：重新核验不行；稿子里照抄了一段现在的范本，也不能去掉范本直接审读，说清楚是哪一段。
        val copied = drafted + "\n" + selectedText
        writing.updateDraftText(workId, chapterId, copied)
        waitFor { store.draft(workId, chapterId) == copied }
        // 同一条分析改指一段更长的原文（仍含原来那段）：范本变了。
        val source = store.referenceText(workId, referenceId, referenceChapterId)!!
        val wider = store.selectedChapterExcerpt(workId, referenceId, referenceChapterId,
            ReferencePart.RANGE, maxChars = 500, start = 0, end = source.indexOf("未选结尾标记"))!!
        store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(wider, chapterId, "other-target", "改过的合成写法分析").copy(id = analysis.id))
        writing.reviewDraft(workId, chapterId, targetLength = "10—100字")
        assertEquals(DraftReferenceIssue.CHANGED, idle().referenceIssue)
        writing.reviewWithoutReference(workId, chapterId, targetLength = "10—100字")
        val refused = idle()
        assertEquals(DraftReferenceIssue.COPIED, refused.referenceIssue)
        assertTrue(refused.error.orEmpty(), refused.error.orEmpty().contains("一字不差：“"))
        assertEquals(analysis.id, refused.referenceAnalysisId)
        assertEquals(analysis.id, store.draftContent(workId, chapterId).reference?.analysisId)

        // 删掉照抄的那段：去掉范本关联（正文不动）、直接审读。
        writing.updateDraftText(workId, chapterId, drafted)
        waitFor { store.draft(workId, chapterId) == drafted }
        var detached = false
        val sent = reviews()
        writing.reviewWithoutReference(workId, chapterId, targetLength = "10—100字") { detached = true }
        waitFor { detached && reviews() > sent }
        val reviewed = idle()
        assertNull(reviewed.error)
        assertNull(reviewed.referenceAnalysisId)
        assertEquals(drafted, reviewed.text)
        waitFor { store.draftContent(workId, chapterId).let { it.text == drafted && it.reference == null && !it.blocked } }
        assertNull(writing.draftBlocked(workId, chapterId))
        assertEquals(original, store.text(workId, chapterId))
    }
}
