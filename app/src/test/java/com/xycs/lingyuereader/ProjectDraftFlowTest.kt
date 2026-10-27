package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.DraftReferenceProvenance
import com.xycs.lingyuereader.data.DraftRevisionSnapshot
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.ReviewEvidence
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.writingTask
import com.xycs.lingyuereader.data.importReference
import com.xycs.lingyuereader.data.saveReferenceAnalysis
import com.xycs.lingyuereader.data.selectedChapterExcerpt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic manuscripts and a loopback-only fake provider exercise the real application AI wiring. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectDraftFlowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val writingDir get() = File(app.filesDir, "writing")
    private val store get() = WorkStore(writingDir)
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val models = mutableListOf<AppViewModel>()
    private lateinit var workId: String
    private lateinit var chapterId: String
    private lateinit var reviewTemplateId: String
    private lateinit var outlineSourceRef: String
    private val asked = CopyOnWriteArrayList<Request>()
    private val reviews = AtomicInteger()
    private val reviewEntered = CountDownLatch(1)
    private val releaseReview = CountDownLatch(1)
    @Volatile private var holdReview = false
    /** 只在第几次审读时卡住（0 表示不按轮次卡）。 */
    @Volatile private var holdReviewRound = 0
    @Volatile private var reviewMode = ReviewMode.ISSUE_THEN_PASS
    @Volatile private var failCreate = false
    @Volatile private var streamFenceOnly = false
    @Volatile private var streamFirstText = "```markdown\n"
    @Volatile private var holdFence = false
    @Volatile private var holdBeforeFence = false
    @Volatile private var fenceEntered = CountDownLatch(1)
    @Volatile private var releaseFence = CountDownLatch(1)
    @Volatile private var createEntered = CountDownLatch(1)
    @Volatile private var releaseFirstToken = CountDownLatch(1)

    private enum class ReviewMode { ISSUE_THEN_PASS, PASS, MALFORMED, ISSUE_THEN_MALFORMED }
    private data class Request(val phase: String, val system: String, val user: String) {
        val all: String get() = "$system\n$user"
    }

    private val original = "合成旧稿：陈青还在屋里等着。"
    private val candidate = "陈青伸手放下茶碗，已经看见了门外的来客。"
    private val revised = "门外响起敲门声。\n“等一下。”陈青说。他拨开门闩，把来客让进屋，转身取下灯笼借给他。"
    private val createMarker = "CREATE_STAGE_TEMPLATE_ONLY"
    private val reviewMarker = "REVIEW_STAGE_TEMPLATE_ONLY"
    private val reviseMarker = "REVISE_STAGE_TEMPLATE_ONLY"
    private val styleMarker = "STYLE_MATERIAL_SHARED"
    private val outlineText = "# 第一卷\n### 第一章 借灯\n事实：陈青先打开门，再看清来客，并决定借出灯笼。\n视角：陈青\n边界：本章留在屋里，不追出来客。\n"
    private val pass = """{"summary":[],"missing":[],"issues":[]}"""

    @Before
    fun setUp() {
        File(app.filesDir, "books").deleteRecursively()
        File(app.filesDir, "books").mkdirs()
        File(app.filesDir, "books/index.json").writeText("[]")
        writingDir.deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("writing_ai", Context.MODE_PRIVATE).edit().clear().commit()
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val list = (0 until messages.length()).map { messages.getJSONObject(it) }
                val system = list.filter { it.optString("role") == "system" }.joinToString("\n") { it.optString("content") }
                val user = list.last { it.optString("role") == "user" }.getString("content")
                val phase = when {
                    "【当前阶段：创作】" in user -> "CREATE"
                    "【当前阶段：审读】" in user -> "REVIEW"
                    "【当前阶段：修订】" in user -> "REVISE"
                    streamFenceOnly && ("请写出这一章" in user || "请从最后一句接着往下写" in user) -> "CREATE"
                    else -> "UNEXPECTED"
                }
                asked += Request(phase, system, user)
                if (phase == "CREATE" && failCreate) {
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                    return@createContext
                }
                if (phase == "CREATE" && streamFenceOnly) {
                    val entered = fenceEntered
                    val release = releaseFence
                    val first = releaseFirstToken
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    runCatching {
                        exchange.responseBody.use { out ->
                            createEntered.countDown()
                            if (holdBeforeFence) first.await(15, TimeUnit.SECONDS)
                            val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta",
                                JSONObject().put("content", streamFirstText))))
                            out.write("data: $delta\n\n".toByteArray())
                            out.flush()
                            entered.countDown()
                            if (holdFence) release.await(15, TimeUnit.SECONDS)
                            out.write("data: [DONE]\n\n".toByteArray())
                        }
                    }
                    return@createContext
                }
                val answer = when (phase) {
                    "CREATE" -> candidate
                    "REVISE" -> revised
                    "REVIEW" -> {
                        val round = reviews.incrementAndGet()
                        if (holdReview || round == holdReviewRound) {
                            reviewEntered.countDown()
                            check(releaseReview.await(15, TimeUnit.SECONDS)) { "测试没有放行审读响应" }
                        }
                        when {
                            reviewMode == ReviewMode.MALFORMED -> "这份稿子很好，可以直接使用。"
                            reviewMode == ReviewMode.ISSUE_THEN_MALFORMED -> if (round == 1) finding() else "这份稿子很好，可以直接使用。"
                            reviewMode == ReviewMode.ISSUE_THEN_PASS && round == 1 -> finding()
                            else -> pass
                        }
                    }
                    else -> "测试收到意外请求"
                }
                val bytes = JSONObject().put(
                    "choices", JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", "stop"),
                    ),
                ).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            this.executor = this@ProjectDraftFlowTest.executor
            start()
        }
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
            .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "synthetic-model").commit()
        val work = store.create("合成项目：借灯")
        workId = work.id
        chapterId = work.chapters.single().id
        store.renameChapter(workId, chapterId, "第一章 借灯")
        store.saveText(workId, chapterId, original)
        val outline = store.addPackDoc(workId, PackKind.VOLUME, "第一卷细纲", outlineText)
        outlineSourceRef = ReviewEvidence.ref("P", outline.id, outlineText.trim())
        store.addPackDoc(workId, PackKind.STYLE, "写作规范", "$styleMarker\n对白保持人物的自然语气，不写讲义。")
        store.addPackDoc(workId, PackKind.INSTRUCTION, "完整正文创作提示词", "$createMarker\n只输出本章完整正文。")
        reviewTemplateId = store.addPackDoc(workId, PackKind.INSTRUCTION, "章节审读提示词", "$reviewMarker\n只给有原句和资料依据的问题。").id
        store.addPackDoc(workId, PackKind.INSTRUCTION, "章节修订提示词", "$reviseMarker\n只按核实的必改意见修订。")
        store.applyLedger(
            workId,
            listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "合成账本：旧城在天黑前关了门。", from = 1))),
            "合成样本",
        )
    }

    @After
    fun tearDown() {
        releaseReview.countDown()
        releaseFence.countDown()
        releaseFirstToken.countDown()
        models.forEach { it.viewModelScope.cancel() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun finding(): String = JSONObject()
        .put("summary", JSONArray().put("看见来客之前缺少开门动作"))
        .put("missing", JSONArray())
        .put("issues", JSONArray().put(
            JSONObject().put("kind", "FACT").put("quote", candidate)
                .put("problem", "门尚未打开，人物已看清屋外来客，因果承接缺了一步")
                .put("basis", "第一卷细纲·第一章：陈青先打开门，再看清来客")
                .put("source_ref", outlineSourceRef)
                .put("source_quote", "陈青先打开门，再看清来客")
                .put("fix", "在看清来客之前补回开门动作").put("required", true),
        )).toString()

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("$message；已收请求=${asked.map { it.phase }}", condition())
    }

    private fun model(): AppViewModel = AppViewModel(app).also { vm ->
        models += vm
        waitUntil("写作数据尚未载入") { vm.writing.loaded.value }
    }

    private fun completed(vm: AppViewModel): DraftState {
        waitUntil("章节流程尚未结束") { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        return vm.writingAi.drafts.value.getValue(workId to chapterId)
    }

    private fun assertCounts(create: Int, review: Int, revise: Int) {
        assertEquals(create, asked.count { it.phase == "CREATE" })
        assertEquals(review, asked.count { it.phase == "REVIEW" })
        assertEquals(revise, asked.count { it.phase == "REVISE" })
        assertEquals(create + review + revise, asked.size)
    }

    @Test
    fun creatingReviewingRevisingAndRereviewingProduceOnlyAnUnadoptedCandidate() {
        val vm = model()
        val ledgerBefore = store.ledger(workId)
        val statusBefore = store.get(workId)!!.chapter(chapterId)!!.status
        vm.writingAi.writeDraft(workId, chapterId, "保留陈青借灯前的犹豫", workflow = true, targetLength = "10—100字")
        val state = completed(vm)
        assertEquals(state.error, DraftStage.READY, state.stage)
        assertTrue(state.workflow)
        assertEquals(revised, state.text)
        assertEquals(revised, store.draft(workId, chapterId))
        assertTrue(state.assessment!!.passed)
        assertEquals(1, state.assessment.round)
        assertTrue(state.assessment.matches(revised, state.task!!.fingerprint))
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertEquals(statusBefore, store.get(workId)!!.chapter(chapterId)!!.status)
        assertEquals(ledgerBefore, store.ledger(workId))
        assertCounts(create = 1, review = 2, revise = 1)
        val markers = mapOf("CREATE" to createMarker, "REVIEW" to reviewMarker, "REVISE" to reviseMarker)
        asked.forEach { request ->
            assertTrue("本阶段模板缺失：${request.phase}", markers.getValue(request.phase) in request.system)
            markers.filterKeys { it != request.phase }.values.forEach { other ->
                assertFalse("${request.phase}混入其他阶段模板：$other", other in request.all)
            }
            assertTrue("阶段请求丢失共同写作规范", styleMarker in request.all)
        }
        val revision = asked.single { it.phase == "REVISE" }
        assertTrue(candidate in revision.user)
        assertTrue("开门动作" in revision.user)
        assertTrue(asked.last { it.phase == "REVIEW" }.user.contains(revised))
    }

    @Test
    fun malformedReviewCannotPassAndPreservesTheGeneratedCandidate() {
        reviewMode = ReviewMode.MALFORMED
        val vm = model()
        val ledgerBefore = store.ledger(workId)
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        val state = completed(vm)
        assertEquals(DraftStage.ERROR, state.stage)
        assertNotNull(state.error)
        assertNull(state.assessment)
        assertEquals(candidate, state.text)
        assertEquals(candidate, store.draft(workId, chapterId))
        assertNotNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertEquals(ledgerBefore, store.ledger(workId))
        assertCounts(create = 1, review = 1, revise = 0)
    }

    @Test
    fun materialChangesDuringReviewInvalidateItsPassUntilTheCandidateIsActuallyReviewedAgain() {
        reviewMode = ReviewMode.PASS
        holdReview = true
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        waitUntil("审读请求尚未到达假服务商") { reviewEntered.count == 0L }
        val originalFingerprint = vm.writingAi.drafts.value.getValue(workId to chapterId).task!!.fingerprint
        store.savePackText(workId, reviewTemplateId, "$reviewMarker\n新版本补充：另核开门前后的因果顺序。")
        val newFingerprint = store.writingTask(workId, chapterId, lengthOverride = "10—100字")!!.fingerprint
        assertNotEquals(originalFingerprint, newFingerprint)
        releaseReview.countDown()
        var state = completed(vm)
        assertEquals(DraftStage.NEEDS_REVIEW, state.stage)
        assertTrue(state.error.orEmpty().contains("变化"))
        assertFalse(state.assessment!!.matches(state.text, newFingerprint))
        assertTrue(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) }.orEmpty().contains("过时"))
        assertCounts(create = 1, review = 1, revise = 0)
        // 重新审读只审已有候选，不再创作；新的报告必须绑定新资料指纹。
        holdReview = false
        vm.writingAi.reviewDraft(workId, chapterId, targetLength = "10—100字")
        state = completed(vm)
        assertEquals(state.error, DraftStage.READY, state.stage)
        assertTrue(state.assessment!!.matches(candidate, newFingerprint))
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertCounts(create = 1, review = 2, revise = 0)
    }

    @Test
    fun missingHardChapterFactsMakeNoHttpRequests() {
        val missing = store.create("合成项目：尚无章界")
        store.addPackDoc(missing.id, PackKind.INSTRUCTION, "完整正文创作提示词", "$createMarker\n严守目标章事实。")
        val vm = model()
        vm.writingAi.writeDraft(missing.id, missing.chapters.single().id, workflow = true)
        waitUntil("缺少硬资料的任务没有结束") {
            vm.writingAi.drafts.value[missing.id to missing.chapters.single().id]?.loading == false
        }
        val state = vm.writingAi.drafts.value.getValue(missing.id to missing.chapters.single().id)
        assertEquals(DraftStage.ERROR, state.stage)
        assertTrue(state.task!!.blocking.isNotEmpty())
        assertTrue(state.error.orEmpty().contains("事实与结果"))
        assertEquals("", store.text(missing.id, missing.chapters.single().id))
        assertEquals("", store.draft(missing.id, missing.chapters.single().id))
        assertCounts(create = 0, review = 0, revise = 0)
    }

    @Test
    fun restoringAFailedProjectCandidateNeverTurnsItIntoAnUnreviewedLegacyDraft() {
        reviewMode = ReviewMode.MALFORMED
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, "保留借灯前的犹豫", workflow = true, targetLength = "10—100字")
        assertEquals(DraftStage.ERROR, completed(vm).stage)
        assertEquals(candidate, store.draft(workId, chapterId))
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后没有读到项目候选稿") { restarted.writingAi.drafts.value.containsKey(workId to chapterId) }
        val restored = restarted.writingAi.drafts.value.getValue(workId to chapterId)
        assertEquals(candidate, restored.text)
        assertTrue("项目候选稿的流程标记必须持久保留", restored.workflow)
        assertEquals("保留借灯前的犹豫", restored.instruction)
        assertEquals("10—100字", restored.targetLength)
        assertEquals(DraftStage.IDLE, restored.stage)
        assertNull(restored.assessment)
        assertNotNull(runBlocking { restarted.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertCounts(create = 1, review = 1, revise = 0)
    }

    @Test
    fun emptyCandidateRestoresExplicitReviewChoiceAndAuthorIntent() {
        store.setDraftIntent(workId, chapterId, "先写门外声响", "约120字", false)
        val first = model()
        first.writingAi.loadDraft(workId, chapterId)
        waitUntil("未载入作者要求") { first.writingAi.drafts.value.containsKey(workId to chapterId) }
        assertEquals(false, first.writingAi.drafts.value.getValue(workId to chapterId).workflowChoice)
        assertEquals("先写门外声响", first.writingAi.drafts.value.getValue(workId to chapterId).instruction)
        assertEquals("约120字", first.writingAi.drafts.value.getValue(workId to chapterId).targetLength)
        first.writingAi.saveDraftIntent(workId, chapterId, "改成先问来客", "约200字", true)
        waitUntil("新选项未写入磁盘") { store.draftIntent(workId, chapterId)?.workflowChoice == true }
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后未载入作者要求") { restarted.writingAi.drafts.value.containsKey(workId to chapterId) }
        val restored = restarted.writingAi.drafts.value.getValue(workId to chapterId)
        assertEquals("改成先问来客", restored.instruction)
        assertEquals("约200字", restored.targetLength)
        assertEquals(true, restored.workflowChoice)
        assertEquals("", restored.text)
    }

    @Test
    fun malformedDraftIntentDoesNotSilentlyDropOnlyTheBrokenChapter() {
        store.setDraftIntent(workId, chapterId, "原要求", "120字", true)
        val assistFile = File(writingDir, "works/$workId/assist.json")
        val broken = JSONObject(assistFile.readText()).put("draftIntents", JSONObject().put(chapterId, "坏条目"))
        assistFile.writeText(broken.toString())
        assertTrue(store.assist(workId).draftIntents.isEmpty())
        assertTrue(File(assistFile.parentFile, "assist.json.damaged").isFile)
    }

    @Test
    fun failedRegenerationBeforeOutputKeepsTheExistingCandidateInMemoryAndOnDisk() {
        for (workflow in listOf(false, true)) {
            val vm = model()
            val old = "先前写好的候选稿，不能因本次失败而消失。"
            store.saveDraft(workId, chapterId, old)
            store.setDraftWorkflow(workId, chapterId, true)
            vm.writingAi.loadDraft(workId, chapterId)
            waitUntil("旧候选稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == old }
            store.setStatus(workId, chapterId, ChapterStatus.FINAL)
            vm.writingAi.writeDraft(workId, chapterId, "再写一版", workflow = workflow)
            assertEquals("请求开始时仍须看得到旧候选稿", old, vm.writingAi.drafts.value[workId to chapterId]?.text)
            val failed = completed(vm)
            // 本次失败的原因只作提示，不挂到旧稿上挡采纳。
            assertTrue(failed.notice.orEmpty().contains("定稿"))
            assertNull(failed.error)
            assertEquals(old, failed.text)
            assertEquals(old, store.draft(workId, chapterId))
            assertTrue(chapterId in store.assist(workId).draftWorkflows)
            assertEquals(0, asked.size)
            vm.writingAi.forget()
            store.setStatus(workId, chapterId, ChapterStatus.REVISION, "测试重新开始")
        }
    }

    @Test
    fun providerFailureBeforeFirstTokenKeepsTheOldProjectCandidate() {
        val old = "已经审阅中的旧候选稿。"
        store.saveDraft(workId, chapterId, old)
        store.setDraftWorkflow(workId, chapterId, true)
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("旧候选稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == old }
        failCreate = true
        vm.writingAi.writeDraft(workId, chapterId, "按旧设定重写", workflow = true, targetLength = "10—100字")
        assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
        val failed = completed(vm)
        assertEquals("旧稿保留自己的阶段", DraftStage.IDLE, failed.stage)
        assertNull(failed.error)
        assertNotNull(failed.notice)
        assertEquals(old, failed.text)
        assertEquals(old, store.draft(workId, chapterId))
        assertCounts(create = 1, review = 0, revise = 0)
    }

    @Test
    fun streamedFenceWithoutProseCannotReplaceAnOldCandidateOnFailure() {
        streamFenceOnly = true
        for (workflow in listOf(false, true)) {
            val old = "围栏之前已经存在的候选稿。"
            store.saveDraft(workId, chapterId, old)
            store.setDraftWorkflow(workId, chapterId, true)
            val vm = model()
            vm.writingAi.loadDraft(workId, chapterId)
            waitUntil("旧稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == old }
            vm.writingAi.writeDraft(workId, chapterId, workflow = workflow, targetLength = "10—100字")
            val failed = completed(vm)
            assertNull(failed.error)
            assertNotNull(failed.notice)
            assertEquals(old, failed.text)
            assertEquals(old, store.draft(workId, chapterId))
            assertTrue(chapterId in store.assist(workId).draftWorkflows)
        }
        assertCounts(create = 2, review = 0, revise = 0)
    }

    @Test
    fun stoppingAfterOnlyAStreamedFenceKeepsTheOldCandidate() {
        streamFenceOnly = true
        holdFence = true
        for (workflow in listOf(false, true)) {
            fenceEntered = CountDownLatch(1)
            releaseFence = CountDownLatch(1)
            val old = "停止生成前保存的候选稿。"
            store.saveDraft(workId, chapterId, old)
            store.setDraftWorkflow(workId, chapterId, true)
            val vm = model()
            vm.writingAi.loadDraft(workId, chapterId)
            waitUntil("旧稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == old }
            vm.writingAi.writeDraft(workId, chapterId, workflow = workflow, targetLength = "10—100字")
            waitUntil("围栏 token 尚未送达") { fenceEntered.count == 0L }
            assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
            vm.writingAi.stopDraft(workId, chapterId)
            waitUntil("停止后旧候选稿未保留") { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
            assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
            waitUntil("停止后旧候选稿未落盘") { store.draft(workId, chapterId) == old }
            assertTrue(chapterId in store.assist(workId).draftWorkflows)
            releaseFence.countDown()
        }
    }

    @Test
    fun stoppingBeforeFirstTokenKeepsOldReviewRequirementAndSource() {
        streamFenceOnly = true
        holdBeforeFence = true
        val old = "已有范本来源且需要审读的旧候选稿。"
        val priorSource = DraftReferenceProvenance(java.util.UUID.randomUUID().toString(), "a".repeat(64), old.length, true)
        store.saveDraft(workId, chapterId, old, priorSource)
        store.setDraftWorkflow(workId, chapterId, true)
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("旧稿来源没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.referenceValidated == true }
        vm.writingAi.writeDraft(workId, chapterId, workflow = false, targetLength = "10—100字", referenceAnalysisId = null)
        waitUntil("创作请求尚未进入假服务商") { createEntered.count == 0L }
        assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
        assertEquals(priorSource.analysisId, vm.writingAi.drafts.value[workId to chapterId]?.referenceAnalysisId)
        assertTrue(vm.writingAi.drafts.value[workId to chapterId]?.workflow == true)
        vm.writingAi.stopDraft(workId, chapterId)
        releaseFirstToken.countDown()
        waitUntil("停止后旧稿未保留") { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
        assertEquals(priorSource, store.draftContent(workId, chapterId).reference)
        assertTrue(chapterId in store.assist(workId).draftWorkflows)
    }

    @Test
    fun cancellingAfterOnlyAStreamedFenceKeepsTheOldProjectCandidate() {
        streamFenceOnly = true
        holdFence = true
        val old = "应用退出前保存的项目候选稿。"
        store.saveDraft(workId, chapterId, old)
        store.setDraftWorkflow(workId, chapterId, true)
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("旧稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == old }
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        waitUntil("围栏 token 尚未送达") { fenceEntered.count == 0L }
        vm.viewModelScope.cancel()
        releaseFence.countDown()
        waitUntil("取消后旧候选稿未保留") { vm.writingAi.drafts.value[workId to chapterId]?.loading == false }
        assertEquals(old, vm.writingAi.drafts.value[workId to chapterId]?.text)
        assertEquals(old, store.draft(workId, chapterId))
    }

    @Test
    fun authorEditingACandidateInvalidatesItsPassingReportWithoutChangingTheManuscript() {
        reviewMode = ReviewMode.PASS
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        assertEquals(DraftStage.READY, completed(vm).stage)
        val edited = candidate + "陈青又想了一会儿。"
        vm.writingAi.updateDraftText(workId, chapterId, edited)
        waitUntil("作者的候选稿修改尚未保存") { store.draft(workId, chapterId) == edited }
        val state = vm.writingAi.drafts.value.getValue(workId to chapterId)
        assertEquals(DraftStage.IDLE, state.stage)
        assertNull(state.assessment)
        assertNotNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertCounts(create = 1, review = 1, revise = 0)
    }

    @Test
    fun backupReloadCannotRetainThePreviousInMemoryCandidatesPass() {
        reviewMode = ReviewMode.PASS
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        assertEquals(DraftStage.READY, completed(vm).stage)
        val restoredBytes = candidate + "恢复的候选版本多了一句。"
        vm.writingAi.forget()
        store.saveDraft(workId, chapterId, restoredBytes)
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("恢复后没有重新读取候选版本") { vm.writingAi.drafts.value[workId to chapterId]?.text == restoredBytes }
        val state = vm.writingAi.drafts.value.getValue(workId to chapterId)
        assertTrue(state.workflow)
        assertEquals(DraftStage.IDLE, state.stage)
        assertNull(state.assessment)
        assertNotNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertCounts(create = 1, review = 1, revise = 0)
    }

    @Test
    fun automaticRevisionKeepsFirstDraftAcrossRestartAndRestorationInvalidatesReview() {
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        val ready = completed(vm)
        assertEquals(revised, ready.text)
        assertEquals(candidate, ready.initialText)
        assertEquals(candidate, store.draftContent(workId, chapterId).revision?.initialText)

        vm.writingAi.restoreInitialDraft(workId, chapterId)
        waitUntil("创作初稿尚未安全恢复") { store.draft(workId, chapterId) == candidate }
        val restored = vm.writingAi.drafts.value.getValue(workId to chapterId)
        assertEquals(revised, restored.alternateText)
        assertNull(restored.assessment)
        assertEquals(DraftStage.IDLE, restored.stage)
        assertNotNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })

        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后对照修订稿没有读取") {
            restarted.writingAi.drafts.value[workId to chapterId]?.alternateText == revised
        }
        restarted.writingAi.switchBackToRevisedDraft(workId, chapterId)
        waitUntil("修订稿尚未切回") { store.draft(workId, chapterId) == revised }
        assertNull(restarted.writingAi.drafts.value.getValue(workId to chapterId).assessment)
        assertEquals(candidate, store.draftContent(workId, chapterId).revision?.initialText)
    }

    @Test
    fun failedFirstDraftSnapshotStopsRevisionAndShowsTheDiskCandidate() {
        val sidecar = File(writingDir, "works/$workId/$chapterId.draft.revision.json")
        val obstacle = File(sidecar.parentFile, sidecar.name + ".tmp")
        assertTrue(obstacle.mkdir())
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        val state = completed(vm)

        assertEquals(candidate, store.draft(workId, chapterId))
        assertEquals(candidate, state.text)
        assertNull(state.initialText)
        assertNull(store.draftContent(workId, chapterId).revision)
        assertTrue(state.error?.contains("保存失败") == true)
        assertEquals(0, asked.count { it.phase == "REVISE" })
        assertTrue(obstacle.delete())
    }

    @Test
    fun stoppingAStreamingRewriteKeepsTheOldCompleteCandidateAndItsFirstDraft() {
        streamFenceOnly = true
        streamFirstText = "流出的新稿首段。"
        holdFence = true
        store.saveDraft(workId, chapterId, "原修订稿", revision = DraftRevisionSnapshot("原初稿"))
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("原候选稿尚未读出") { vm.writingAi.drafts.value[workId to chapterId]?.initialText == "原初稿" }

        vm.writingAi.writeDraft(workId, chapterId, workflow = false)
        waitUntil("新稿首段尚未流出") { vm.writingAi.drafts.value[workId to chapterId]?.text == streamFirstText }
        vm.writingAi.stopDraft(workId, chapterId)
        waitUntil("停止后旧稿尚未复原") {
            store.draft(workId, chapterId) == "原修订稿" && vm.writingAi.drafts.value[workId to chapterId]?.loading == false
        }
        assertEquals("原初稿", WorkStore(writingDir).draftContent(workId, chapterId).revision?.initialText)
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后旧初稿遗失") { restarted.writingAi.drafts.value[workId to chapterId]?.initialText == "原初稿" }
        assertEquals("原修订稿", restarted.writingAi.drafts.value.getValue(workId to chapterId).text)
    }

    @Test
    fun rejectedSourceCopyAfterFirstTokenKeepsOldCandidateAndSnapshot() {
        val sourceText = "山门外的雪一直下到天明，旧驿站的灯还亮着，来人沿石阶走了三十步才终于抬手敲门。"
        val book = store.importReference(workId, "合成范本.txt", "第一章 雪夜\n$sourceText")
        val excerpt = store.selectedChapterExcerpt(workId, book.id, book.chapters.single().id, ReferencePart.OPENING, 100)!!
        val analysis = store.saveReferenceAnalysis(workId, ReferenceAnalysis.from(excerpt, chapterId,
            "原章节指纹", "先以天气和远处灯光建立等待感，再让人物走近门口。"))
        store.saveDraft(workId, chapterId, "原修订稿", revision = DraftRevisionSnapshot("原初稿"))
        streamFenceOnly = true
        streamFirstText = sourceText
        holdFence = true
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("原候选稿尚未读出") { vm.writingAi.drafts.value[workId to chapterId]?.initialText == "原初稿" }

        vm.writingAi.writeDraft(workId, chapterId, workflow = false, referenceAnalysisId = analysis.id)
        waitUntil("违规范本复制的首段尚未流出") { vm.writingAi.drafts.value[workId to chapterId]?.text == sourceText }
        releaseFence.countDown()
        waitUntil("范本拒绝后旧候选稿未恢复") {
            vm.writingAi.drafts.value[workId to chapterId]?.let { !it.loading && it.text == "原修订稿" } == true
        }
        assertEquals("原修订稿", store.draft(workId, chapterId))
        assertEquals("原初稿", WorkStore(writingDir).draftContent(workId, chapterId).revision?.initialText)
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后首稿快照遗失") { restarted.writingAi.drafts.value[workId to chapterId]?.initialText == "原初稿" }
    }

    @Test
    fun failedEditAndDeleteRestoreVisibleCandidateWhenSnapshotCannotBeRead() {
        store.saveDraft(workId, chapterId, "原修订稿", revision = DraftRevisionSnapshot("原初稿"))
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        waitUntil("原候选稿尚未读出") { vm.writingAi.drafts.value[workId to chapterId]?.initialText == "原初稿" }
        val sidecar = File(writingDir, "works/$workId/$chapterId.draft.revision.json")
        assertTrue(sidecar.delete())
        assertTrue(sidecar.mkdir())
        File(sidecar, "unreadable").writeText("保留的原件")

        vm.writingAi.updateDraftText(workId, chapterId, "覆盖稿")
        waitUntil("失败的编辑没有反馈") { vm.messages.value?.contains("候选稿保存失败") == true }
        assertEquals("原修订稿", vm.writingAi.drafts.value.getValue(workId to chapterId).text)
        assertEquals("原修订稿", store.draft(workId, chapterId))

        vm.writingAi.clearDraft(workId, chapterId)
        waitUntil("失败的删除没有反馈") { vm.messages.value?.contains("删除草稿失败") == true }
        assertEquals("原修订稿", vm.writingAi.drafts.value.getValue(workId to chapterId).text)
        assertEquals("原修订稿", store.draft(workId, chapterId))
        assertEquals("保留的原件", File(sidecar, "unreadable").readText())
    }

    @Test
    fun reviseThisDraftKeepsTheAuthorsEditAsTheVersionToSwitchBackTo() {
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        val first = completed(vm)
        assertEquals(revised, first.text)
        assertEquals(candidate, first.initialText)
        // 作者手改修订后的稿子（保留审读引用的原句），再点“审读并修订此稿”，审读又给出必改项。
        val edited = candidate + "林月在门外喊了一声，沈默没有应。"
        vm.writingAi.updateDraftText(workId, chapterId, edited)
        waitUntil("手改稿尚未保存") { store.draft(workId, chapterId) == edited }
        reviews.set(0)
        vm.writingAi.reviewDraft(workId, chapterId, targetLength = "10—100字")
        val state = completed(vm)
        assertEquals(state.error, DraftStage.READY, state.stage)
        assertEquals(revised, state.text)
        assertEquals("修订前必须把作者手改的这一版留底", edited, state.initialText)
        assertEquals(edited, store.draftContent(workId, chapterId).revision?.initialText)
        vm.writingAi.restoreInitialDraft(workId, chapterId)
        waitUntil("手改稿没能换回") { store.draft(workId, chapterId) == edited }
        assertEquals(revised, vm.writingAi.drafts.value.getValue(workId to chapterId).alternateText)
        assertTrue("修订请求针对的是手改稿", edited in asked.last { it.phase == "REVISE" }.user)
    }

    @Test
    fun aSavedRevisionSurvivesAFailedOrStoppedReReview() {
        for (stop in listOf(false, true)) {
            store.saveDraft(workId, chapterId, candidate)
            store.setDraftWorkflow(workId, chapterId, true)
            reviews.set(0)
            reviewMode = if (stop) ReviewMode.ISSUE_THEN_PASS else ReviewMode.ISSUE_THEN_MALFORMED
            holdReviewRound = if (stop) 2 else 0
            val vm = model()
            vm.writingAi.loadDraft(workId, chapterId)
            waitUntil("旧候选稿没有载入") { vm.writingAi.drafts.value[workId to chapterId]?.text == candidate }
            vm.writingAi.reviewDraft(workId, chapterId, targetLength = "10—100字")
            val state = if (stop) {
                waitUntil("复审请求尚未到达") { reviewEntered.count == 0L }
                vm.writingAi.stopDraft(workId, chapterId)
                completed(vm)
            } else completed(vm)
            assertEquals("已付费的修订稿不能被回滚", revised, state.text)
            assertEquals(candidate, state.initialText)
            assertNull(state.assessment)
            if (stop) {
                assertEquals(DraftStage.STOPPED, state.stage)
                assertEquals(WritingAi.REVISION_SAVED, state.notice)
            } else {
                assertEquals(DraftStage.ERROR, state.stage)
                assertTrue(state.error.orEmpty(), state.error.orEmpty().startsWith(WritingAi.REVISION_SAVED))
            }
            waitUntil("修订稿没有留在磁盘上") { store.draft(workId, chapterId) == revised }
            assertEquals(candidate, store.draftContent(workId, chapterId).revision?.initialText)
            assertNotNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
            releaseReview.countDown()
            vm.writingAi.forget()
        }
    }

    @Test
    fun aFailedOrStoppedRewriteRestoresTheOldCandidateWithItsPassingReview() {
        reviewMode = ReviewMode.PASS
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        val ready = completed(vm)
        assertEquals(DraftStage.READY, ready.stage)
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        streamFenceOnly = true
        failCreate = true
        for (workflow in listOf(true, false)) {
            vm.writingAi.writeDraft(workId, chapterId, "林月先开口", workflow = workflow, targetLength = "10—100字")
            val failed = completed(vm)
            assertEquals(candidate, failed.text)
            assertEquals(DraftStage.READY, failed.stage)
            assertEquals(ready.assessment, failed.assessment)
            assertNull("本次的请求错误不能挡住旧稿采纳", failed.error)
            assertTrue(failed.notice.orEmpty(), failed.notice.orEmpty().contains("已保留上一版"))
            assertEquals("林月先开口", failed.instruction)
            assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        }
        failCreate = false
        holdBeforeFence = true
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        waitUntil("创作请求尚未进入假服务商") { createEntered.count == 0L }
        vm.writingAi.stopDraft(workId, chapterId)
        releaseFirstToken.countDown()
        val stopped = completed(vm)
        assertEquals(candidate, stopped.text)
        assertEquals(DraftStage.READY, stopped.stage)
        assertEquals(ready.assessment, stopped.assessment)
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        waitUntil("停止后旧稿未落盘") { store.draft(workId, chapterId) == candidate }
    }

    @Test
    fun aNewDirectionForTheNextRewriteDoesNotInvalidateTheCurrentReview() {
        reviewMode = ReviewMode.PASS
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, "保留借灯前的犹豫", workflow = true, targetLength = "10—100字")
        val ready = completed(vm)
        assertEquals(DraftStage.READY, ready.stage)
        vm.writingAi.saveDraftIntent(workId, chapterId, "下次让林月先开口", "约300字", true)
        waitUntil("新方向未保存") { store.draftIntent(workId, chapterId)?.instruction == "下次让林月先开口" }
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(ready.assessment!!.materials, runBlocking { vm.writingAi.reviewTask(workId, chapterId) }!!.fingerprint)
        // 真正的资料变化仍让审读失效。
        store.savePackText(workId, reviewTemplateId, "$reviewMarker\n新版本补充：另核渡口一带的灯火。")
        assertTrue(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) }.orEmpty().contains("过时"))
    }

    @Test
    fun aProjectChapterNeedsReviewWhetherOrNotTheWorkflowSwitchIsOn() {
        reviewMode = ReviewMode.PASS
        streamFenceOnly = true
        streamFirstText = "林月推门进来，把灯笼挂在渡口的柱子上。"
        val vm = model()
        vm.writingAi.writeDraft(workId, chapterId, workflow = false, targetLength = "10—100字")
        val written = completed(vm)
        assertEquals(streamFirstText, written.text)
        assertFalse(written.workflow)
        assertNull("旧审读不能挂在新稿下面", written.assessment)
        val before = runBlocking { vm.writingAi.draftBlocked(workId, chapterId) }
        assertNotNull("关掉开关写的项目章也要先审读", before)
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后没有读到候选稿") { restarted.writingAi.drafts.value.containsKey(workId to chapterId) }
        assertEquals("重启前后判定一致", before, runBlocking { restarted.writingAi.draftBlocked(workId, chapterId) })
    }
}
