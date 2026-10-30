package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.WritingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/**
 * 定稿章解锁改完、重新定稿时的级联核对（本机，不发请求）：原句找不到的账本条目标“待复核”；摘要、故事至今、卷摘要标过期，
 * 下次收尾重新整理；后面各章牵扯到这一章条目的挂进变更登记“待同步”。文字自造、人名虚构。
 */
class FinalCascadeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: WorkStore
    private var workId = ""
    private val ids = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val original = "林月躲进旧仓。\n沈默替她挡了一刀。\n老周藏起的半本账册还在。"
    private val edited = "林月跑去了渡口。\n沈默替她挡了一刀。\n老周藏起的半本账册还在。"

    private val hide = LedgerEntry(id = "hide", kind = LedgerKind.STATE, text = "躲在旧仓", subject = "林月", aspect = "位置", from = 2, quote = "林月躲进旧仓。")
    private val blow = LedgerEntry(id = "blow", kind = LedgerKind.EVENT, text = "沈默替林月挡了一刀", from = 2, quote = "沈默替她挡了一刀。")
    private val book = LedgerEntry(id = "book", kind = LedgerKind.PROMISE, text = "老周藏起的半本账册", from = 2, lastAdvanced = 3, quote = "老周藏起的半本账册还在。")
    private val later = LedgerEntry(id = "later", kind = LedgerKind.STATE, text = "从旧仓出来，到了渡口", subject = "林月", aspect = "位置", from = 4)
    private val other = LedgerEntry(id = "other", kind = LedgerKind.EVENT, text = "杜娘关了茶馆", from = 3)

    @Before
    fun setUp() {
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        ids += w.chapters.single().id
        repeat(3) { ids += store.addChapter(workId, w.volumes.single().id)!!.second.id }
        ids.forEachIndexed { i, id ->
            store.renameChapter(workId, id, "第${i + 1}章 灯")
            store.saveText(workId, id, if (i == 1) original else "第${i + 1}章的正文。", count = false)
            store.saveCard(workId, id, ChapterCard(summary = "第${i + 1}章的摘要。"))
        }
        store.applyLedger(workId, listOf(
            LedgerOp.Add(hide.copy(chapterId = ids[1])), LedgerOp.Add(blow.copy(chapterId = ids[1])), LedgerOp.Add(book.copy(chapterId = ids[1])),
            LedgerOp.Add(later.copy(chapterId = ids[3])), LedgerOp.Add(other.copy(chapterId = ids[2])),
        ), "整理")
        val volume = store.get(workId)!!.volumes.single().id
        store.updateDigests(workId) { it.copy(story = StoryDigest("林月到渡灯镇找铜灯。", ids[2], 3), volumes = mapOf(volume to VolumeDigest("第一卷的卷摘要。", ids.take(3)))) }
        store.setStatus(workId, ids[1], ChapterStatus.FINAL)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun unlockAndEdit(text: String = edited) {
        store.setStatus(workId, ids[1], ChapterStatus.DRAFT, "林月改去渡口")
        store.saveText(workId, ids[1], text, count = false)
    }

    @Test
    fun onlyAChangedRefinalIsChecked() {
        // 刚定稿、没解锁：不做。
        assertFalse(store.refinalPending(workId, ids[1]))
        // 解锁了没改字：不做。
        store.setStatus(workId, ids[1], ChapterStatus.DRAFT, "看一眼")
        assertFalse(store.refinalPending(workId, ids[1]))
        // 改了（编辑器里还没存的也算）：做。
        assertTrue(store.refinalPending(workId, ids[1], edited))
        // 旧登记没有指纹：照样做。
        store.addChange(workId, ChangeEntry(WorkStore.newId(), System.currentTimeMillis() + 10_000, ChangeKind.UNLOCK, ids[1], "第2章 灯", "旧登记"))
        assertNull(store.changes(workId).first().textHash)
        assertTrue(store.refinalPending(workId, ids[1]))
    }

    @Test
    fun aMissingQuoteIsMarkedAndEverythingElseGoesStaleOrPending() {
        unlockAndEdit()
        assertTrue(store.refinalPending(workId, ids[1]))
        store.setStatus(workId, ids[1], ChapterStatus.FINAL)
        val report = store.cascadeFinal(workId, ids[1])

        // 1. 原句不在了的标待复核，内容不动；还在的不标；别的章的不碰。
        val ledger = store.ledger(workId).associateBy { it.id }
        assertTrue(ledger.getValue("hide").recheck)
        assertEquals("躲在旧仓", ledger.getValue("hide").text)
        assertFalse(ledger.getValue("blow").recheck)
        assertFalse(ledger.getValue("book").recheck)
        assertFalse(ledger.getValue("later").recheck)
        assertEquals(1, report.recheck)

        // 2. 摘要、故事至今（写到第 3 章）、卷摘要标过期；摘要的字还留着，整理好之前照旧用。
        assertTrue(store.card(workId, ids[1]).summaryStale)
        assertEquals("第2章的摘要。", store.card(workId, ids[1]).summary)
        val digests = store.digests(workId)
        assertTrue(digests.story!!.stale)
        assertTrue(digests.volumes.values.single().stale)
        assertTrue(report.summary && report.story && report.volume)

        // 3. 后面的：第 4 章林月的位置接的是这一章的；这一章埋的账册在第 3 章推进过。杜娘那条不相干。
        assertEquals(2, report.affected)
        val cascade = store.changes(workId).filter { it.kind == ChangeKind.CASCADE }
        assertTrue(cascade.all { it.pending })
        assertTrue(cascade.any { it.chapterId == ids[3] && it.reason.contains("从旧仓出来") && it.reason.contains("可能要跟着改") })
        assertTrue(cascade.any { it.reason.contains("老周藏起的半本账册") && it.reason.contains("推进或收了") })
        assertTrue(cascade.any { it.reason.contains("标“待复核”") && it.reason.contains("躲在旧仓") })
        assertTrue(cascade.none { it.reason.contains("杜娘") })
        assertTrue(report.message("第2章 灯").contains("1 条账本依据找不到了"))

        // 改摘要就不再过期；作者核过账本那一条（存一下）就不再待复核。
        store.updateCard(workId, ids[1]) { it.copy(summary = "林月跑去了渡口。") }
        assertFalse(store.card(workId, ids[1]).summaryStale)
    }

    @Test
    fun aStaleStoryIsRebuiltFromTheStartOnTheNextFinish() {
        unlockAndEdit()
        store.setStatus(workId, ids[1], ChapterStatus.FINAL)
        store.cascadeFinal(workId, ids[1])
        val work = store.get(workId)!!
        val summaries = ids.associateWith { store.card(workId, it).summary }
        val job = StoryDigests.plan(work, ids[1], "林月跑去了渡口。", summaries, store.digests(workId))
        // 从头整理到原来写到的第 3 章，不倒退；卷摘要也重新汇总。
        assertTrue(job.story)
        assertEquals("", job.oldStory)
        assertEquals(listOf("第1章 灯", "第2章 灯", "第3章 灯"), job.newChapters.map { it.first })
        assertEquals("林月跑去了渡口。", job.newChapters[1].second)
        assertEquals(ids[2], job.throughId)
        assertEquals(work.volumes.single().id, job.volumeId)
        // 存档读回来：过期标记还在，旧数据没有这一项读成没过期。
        assertTrue(StoryDigests.fromJson(JSONObject(StoryDigests.toJson(store.digests(workId)).toString())).story!!.stale)
        assertFalse(StoryDigests.fromJson(JSONObject().put("story", JSONObject().put("text", "旧的").put("throughId", ids[0]))).story!!.stale)
    }

    @Test
    fun theModelRunsItInTheBackgroundAfterRefinalizing() {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val model = WritingModel(store, scope) { messages += it }
        unlockAndEdit()
        runBlocking { model.setStatusNow(workId, ids[1], ChapterStatus.FINAL, currentText = edited) }
        val until = System.currentTimeMillis() + 15_000
        while (store.changes(workId).none { it.kind == ChangeKind.CASCADE }) {
            check(System.currentTimeMillis() < until) { "等不到级联核对" }
            Thread.sleep(20)
        }
        while (messages.none { "改过了" in it }) {
            check(System.currentTimeMillis() < until) { "等不到提示" }
            Thread.sleep(20)
        }
        assertTrue(store.ledger(workId).single { it.id == "hide" }.recheck)
        assertTrue(messages.any { "标了待复核" in it && "待同步" in it })
        // 正文没改、只是解锁又定稿：不做。
        val before = store.changes(workId).count { it.kind == ChangeKind.CASCADE }
        store.setStatus(workId, ids[1], ChapterStatus.DRAFT, "再看一眼")
        runBlocking { model.setStatusNow(workId, ids[1], ChapterStatus.FINAL) }
        Thread.sleep(300)
        assertEquals(before, store.changes(workId).count { it.kind == ChangeKind.CASCADE })
    }
}
