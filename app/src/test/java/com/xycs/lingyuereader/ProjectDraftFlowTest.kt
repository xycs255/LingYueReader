package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.DraftStage
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.writingTask
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
    private val asked = CopyOnWriteArrayList<Request>()
    private val reviews = AtomicInteger()
    private val reviewEntered = CountDownLatch(1)
    private val releaseReview = CountDownLatch(1)
    @Volatile private var holdReview = false
    @Volatile private var reviewMode = ReviewMode.ISSUE_THEN_PASS

    private enum class ReviewMode { ISSUE_THEN_PASS, SCENE_ISSUE_THEN_PASS, PASS, MALFORMED }
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
                    "【当前阶段：建议场景安排】" in user -> "SCENE_PLAN"
                    "【当前阶段：创作第 " in user -> "SCENE"
                    "【当前阶段：创作】" in user -> "CREATE"
                    "【当前阶段：审读】" in user -> "REVIEW"
                    "【当前阶段：修订】" in user -> "REVISE"
                    else -> "UNEXPECTED"
                }
                asked += Request(phase, system, user)
                val answer = when (phase) {
                    "SCENE_PLAN" -> """{"scenes":[{"goal":"先把门打开","conflict":"陈青犹豫","turn":"他决定开门","exit":"看见门外来客"},{"goal":"借出灯笼","conflict":"来客急着出发","turn":"陈青答应借灯","exit":"留在屋内"}]}"""
                    "SCENE" -> if ("第 1/2 场" in user) "陈青先打开门，看见门外来客。" else "陈青借出灯笼，然后留在屋里。"
                    "CREATE" -> candidate
                    "REVISE" -> revised
                    "REVIEW" -> {
                        val round = reviews.incrementAndGet()
                        if (holdReview) {
                            reviewEntered.countDown()
                            check(releaseReview.await(15, TimeUnit.SECONDS)) { "测试没有放行审读响应" }
                        }
                        when {
                            reviewMode == ReviewMode.MALFORMED -> "这份稿子很好，可以直接使用。"
                            reviewMode == ReviewMode.ISSUE_THEN_PASS && round == 1 -> finding()
                            reviewMode == ReviewMode.SCENE_ISSUE_THEN_PASS && round == 1 ->
                                finding("陈青先打开门，看见门外来客。")
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
        store.addPackDoc(
            workId, PackKind.VOLUME, "第一卷细纲",
            "# 第一卷\n### 第一章 借灯\n事实：陈青先打开门，再看清来客，并决定借出灯笼。\n视角：陈青\n边界：本章留在屋里，不追出来客。\n",
        )
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
        models.forEach { it.viewModelScope.cancel() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun finding(quote: String = candidate): String = JSONObject()
        .put("summary", JSONArray().put("看见来客之前缺少开门动作"))
        .put("missing", JSONArray())
        .put("issues", JSONArray().put(
            JSONObject().put("kind", "FACT").put("quote", quote)
                .put("problem", "门尚未打开，人物已看清屋外来客，因果承接缺了一步")
                .put("basis", "第一卷细纲·第一章：陈青先打开门，再看清来客")
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
    fun authorConfirmsEditedScenesThenWritesOneAtATimeAndReviewsAfterRestart() {
        reviewMode = ReviewMode.PASS
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        vm.writingAi.loadScenePlan(workId, chapterId)
        waitUntil("场景安排尚未载入") { vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false }
        vm.writingAi.suggestScenes(workId, chapterId, targetLength = "10—100字")
        waitUntil("场景建议未完成") { vm.writingAi.scenePlans.value[workId to chapterId]?.suggestion != null }
        val suggested = vm.writingAi.scenePlans.value.getValue(workId to chapterId).suggestion!!
        assertNull("AI 建议不能自动成为作者计划", store.scenePlan(workId, chapterId))
        assertEquals("", store.draft(workId, chapterId))
        assertEquals(original, store.text(workId, chapterId))
        val edited = suggested.copy(scenes = suggested.scenes.mapIndexed { index, scene ->
            if (index == 0) scene.copy(goal = "作者修改：先听清来客身份再开门") else scene
        })
        vm.writingAi.changeSceneProposal(workId, chapterId, edited)
        assertNull(store.scenePlan(workId, chapterId))
        vm.writingAi.approveScenePlan(workId, chapterId, edited, targetLength = "10—100字")
        waitUntil("作者确认的场景安排尚未持久化") {
            store.scenePlan(workId, chapterId) == edited && vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false
        }
        vm.writingAi.changeSceneProposal(workId, chapterId, edited.copy(scenes = edited.scenes.mapIndexed { index, scene ->
            if (index == 0) scene.copy(goal = "一次未确认的改动") else scene
        }))
        vm.writingAi.discardSceneProposal(workId, chapterId)
        assertEquals("放弃新建议不应删去已确认安排", edited, store.scenePlan(workId, chapterId))
        vm.writingAi.writeNextScene(workId, chapterId, targetLength = "10—100字")
        waitUntil("第一场未完成") {
            store.scenePlan(workId, chapterId)?.completed == 1 && vm.writingAi.drafts.value[workId to chapterId]?.loading == false
        }
        assertTrue(asked.any { it.phase == "SCENE" && "作者修改：先听清来客身份再开门" in it.user })
        val first = store.draft(workId, chapterId)
        assertEquals("陈青先打开门，看见门外来客。", first)
        assertTrue(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) }.orEmpty().contains("尚未写完"))

        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        restarted.writingAi.loadScenePlan(workId, chapterId)
        waitUntil("重启后未恢复草稿和场景进度") {
            restarted.writingAi.drafts.value[workId to chapterId]?.text == first &&
                restarted.writingAi.scenePlans.value[workId to chapterId]?.plan?.completed == 1
        }
        restarted.writingAi.writeNextScene(workId, chapterId)
        waitUntil("第二场未完成") {
            store.scenePlan(workId, chapterId)?.completed == 2 && restarted.writingAi.drafts.value[workId to chapterId]?.loading == false
        }
        val manuscript = store.draft(workId, chapterId)
        assertTrue(manuscript.contains(first) && manuscript.contains("陈青借出灯笼"))
        assertTrue(runBlocking { restarted.writingAi.draftBlocked(workId, chapterId) }.orEmpty().contains("尚未完成审读"))
        restarted.writingAi.reviewDraft(workId, chapterId)
        waitUntil("场景稿未通过原有审读") {
            restarted.writingAi.drafts.value[workId to chapterId]?.let { it.stage == DraftStage.READY && !it.loading } == true
        }
        assertNull(runBlocking { restarted.writingAi.draftBlocked(workId, chapterId) })
        assertEquals("生成和审读不能直接改正式正文", original, store.text(workId, chapterId))
        assertEquals(1, asked.count { it.phase == "SCENE_PLAN" })
        assertEquals(2, asked.count { it.phase == "SCENE" })
        assertEquals(1, asked.count { it.phase == "REVIEW" })
    }

    @Test
    fun reviewRevisionRebindsCompletedScenesToRevisedCandidate() {
        reviewMode = ReviewMode.SCENE_ISSUE_THEN_PASS
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        vm.writingAi.loadScenePlan(workId, chapterId)
        waitUntil("场景安排尚未载入") { vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false }
        vm.writingAi.suggestScenes(workId, chapterId)
        waitUntil("场景建议未完成") { vm.writingAi.scenePlans.value[workId to chapterId]?.suggestion != null }
        vm.writingAi.approveScenePlan(workId, chapterId,
            vm.writingAi.scenePlans.value.getValue(workId to chapterId).suggestion!!)
        waitUntil("场景安排尚未保存") {
            store.scenePlan(workId, chapterId) != null && vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false
        }
        vm.writingAi.writeNextScene(workId, chapterId)
        waitUntil("第一场未完成") {
            store.scenePlan(workId, chapterId)?.completed == 1 && vm.writingAi.drafts.value[workId to chapterId]?.loading == false
        }
        vm.writingAi.writeNextScene(workId, chapterId)
        waitUntil("第二场未完成") {
            store.scenePlan(workId, chapterId)?.completed == 2 && vm.writingAi.drafts.value[workId to chapterId]?.loading == false
        }
        vm.writingAi.reviewDraft(workId, chapterId)
        waitUntil("场景审读与修订未完成") {
            vm.writingAi.drafts.value[workId to chapterId]?.let { it.stage == DraftStage.READY && !it.loading } == true
        }
        assertEquals(revised, store.draft(workId, chapterId))
        assertTrue(store.scenePlan(workId, chapterId)!!.matchesDraft(revised))
        assertNull(runBlocking { vm.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertEquals(1, asked.count { it.phase == "REVISE" })
    }

    @Test
    fun changedChapterMaterialsRejectAnApprovedSceneBeforeAnyProseRequest() {
        val vm = model()
        vm.writingAi.loadDraft(workId, chapterId)
        vm.writingAi.loadScenePlan(workId, chapterId)
        waitUntil("场景安排尚未载入") { vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false }
        vm.writingAi.suggestScenes(workId, chapterId)
        waitUntil("场景建议未完成") { vm.writingAi.scenePlans.value[workId to chapterId]?.suggestion != null }
        val plan = vm.writingAi.scenePlans.value.getValue(workId to chapterId).suggestion!!
        vm.writingAi.approveScenePlan(workId, chapterId, plan)
        waitUntil("场景安排尚未保存") {
            store.scenePlan(workId, chapterId) == plan && vm.writingAi.scenePlans.value[workId to chapterId]?.loading == false
        }
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(facts = "修改后的事实：本章不开门"))
        vm.writingAi.writeNextScene(workId, chapterId)
        waitUntil("过期场景未被拒绝") { vm.writingAi.drafts.value[workId to chapterId]?.error?.contains("变化") == true }
        assertEquals(0, asked.count { it.phase == "SCENE" })
        assertEquals("", store.draft(workId, chapterId))
        assertEquals(original, store.text(workId, chapterId))
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
        vm.writingAi.writeDraft(workId, chapterId, workflow = true, targetLength = "10—100字")
        assertEquals(DraftStage.ERROR, completed(vm).stage)
        assertEquals(candidate, store.draft(workId, chapterId))
        val restarted = model()
        restarted.writingAi.loadDraft(workId, chapterId)
        waitUntil("重启后没有读到项目候选稿") { restarted.writingAi.drafts.value.containsKey(workId to chapterId) }
        val restored = restarted.writingAi.drafts.value.getValue(workId to chapterId)
        assertEquals(candidate, restored.text)
        assertTrue("项目候选稿的流程标记必须持久保留", restored.workflow)
        assertEquals(DraftStage.IDLE, restored.stage)
        assertNull(restored.assessment)
        assertNotNull(runBlocking { restarted.writingAi.draftBlocked(workId, chapterId) })
        assertEquals(original, store.text(workId, chapterId))
        assertCounts(create = 1, review = 1, revise = 0)
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
}
