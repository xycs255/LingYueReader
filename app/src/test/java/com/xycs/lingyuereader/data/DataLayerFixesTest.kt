package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.WritingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 数据层审查的几处修复（《渡灯》，人名、文字都是造的）：删章同步分层前情、写到的那章被删后旧章重收尾不动梗概、
 * 重整梗概的范围是本章之前的全书、往卷卷摘要没汇总全要补且补上之前前情不断档、分段整理的数值合并、
 * 写本章时数值快照取上一章末、原句按句子核对、级联核对写不进账本时照实说、解锁后换过的摘要不标过期、
 * 体检里账本后面章的条目不算推进、体检不重读没变的细纲卡。
 */
class DataLayerFixesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 一章的摘要：两百多字，不写人名（免得按出场人物被另外挑进前情）。 */
    private fun longSummary(n: Int) = ("第${n}章：渡灯镇的河埠头又起了雾，北岸来的船迟了半日，旧仓的门锁被人撬过，灯座下的记号多了一道刻痕，" +
        "茶馆里传说三年前沉船那夜也是这样的雾，镇上的人都闭了门。").repeat(3)

    /** 200 章、5 卷，每卷 40 章，章 id 是 c1…c200，卷 id 是 v1…v5，都是定稿。 */
    private fun bigWork(): Work = Work("w", "渡灯", volumes = (1..5).map { v ->
        Volume("v$v", "第${v}卷", ((v - 1) * 40 + 1..v * 40).map { WorkChapter("c$it", "第${it}章", words = 3_000, status = ChapterStatus.FINAL) })
    })

    private fun ids(range: IntRange) = range.map { "c$it" }

    // ---------- H1 ----------

    @Test
    fun aDeletedThroughChapterStillKeepsTheStoryFromGoingBack() {
        val work = bigWork()
        val gone = work.copy(volumes = work.volumes.map { v -> v.copy(chapters = v.chapters.filterNot { it.id == "c50" }) })
        val sums = (1..200).associate { "c$it" to longSummary(it) }
        // 故事至今写到第 50 章，第 50 章删了；回头重收第 30 章：梗概里有第 31 到 49 章的事，不能并成“写到第 30 章”。
        val job = StoryDigests.plan(gone, "c30", "第三十章", sums, Digests(StoryDigest("写到第五十章的梗概。", "c50", 50)))
        assertFalse(job.story)
        assertEquals("", job.oldStory)
    }

    @Test
    fun deletingAChapterUpdatesTheDigests() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        val volume = w.volumes.single().id
        val ids = mutableListOf(w.chapters.single().id)
        repeat(4) { ids += s.addChapter(w.id, volume)!!.second.id }
        ids.forEachIndexed { i, id -> s.renameChapter(w.id, id, "第${i + 1}章 灯") }
        s.updateDigests(w.id) { Digests(StoryDigest("林月查到第四个记号。", ids[3], 4), mapOf(volume to VolumeDigest("第一卷的卷摘要。", ids.take(4)))) }
        // 删掉故事至今写到的第 4 章：写到哪改成第 3 章，标过期（里面还有第 4 章的事）；卷摘要去掉这一章、标过期。
        s.deleteChapter(w.id, ids[3])
        val d = s.digests(w.id)
        assertEquals(ids[2], d.story!!.throughId)
        assertEquals(3, d.story!!.throughNo)
        assertTrue(d.story!!.stale)
        assertEquals(ids.take(3), d.volumes.getValue(volume).chapters)
        assertTrue(d.volumes.getValue(volume).stale)
        // 第 5 章（现在是第 4 章）收尾：从头整理，写到它为止，不带被删那章。
        val work = s.get(w.id)!!
        val job = StoryDigests.plan(work, ids[4], "第五章", ids.associateWith { "${it}的摘要" }, d)
        assertTrue(job.story)
        assertEquals("", job.oldStory)
        assertEquals(ids[4], job.throughId)
        // 删掉梗概还没写到的章：故事至今不动。
        s.updateDigests(w.id) { it.copy(story = StoryDigest("林月查到第二个记号。", ids[1], 2)) }
        s.deleteChapter(w.id, ids[4])
        assertEquals(StoryDigest("林月查到第二个记号。", ids[1], 2), s.digests(w.id).story)
    }

    // ---------- M1 ----------

    @Test
    fun rebuildingTheStoryCoversTheWholeBookBeforeIt() {
        val work = bigWork()
        val sums = (1..200).associate { "c$it" to longSummary(it) }
        // 故事至今写到第 200 章，第 10 章改过、重新定稿：故事至今和第一卷的卷摘要过期；第二到四卷的卷摘要好好的。
        val digests = Digests(
            StoryDigest("全书梗概。", "c200", 200, stale = true),
            mapOf("v1" to VolumeDigest("第一卷旧的卷摘要。", ids(1..40), stale = true)) +
                (2..4).associate { v -> "v$v" to VolumeDigest("第${v}卷的卷摘要。", ids((v - 1) * 40 + 1..v * 40)) },
        )
        // 在第一卷的第 12 章收尾：梗概照样写到第 200 章，范围是全书。
        val job = StoryDigests.plan(work, "c12", sums.getValue("c12"), sums, digests)
        assertTrue(job.story)
        assertEquals("c200", job.throughId)
        assertEquals("", job.oldStory)
        // 第二到四卷用卷摘要，不再逐章给；第一卷截掉的那几章用它的卷摘要盖住。
        assertEquals(listOf("第1卷", "第2卷", "第3卷", "第4卷"), job.pastVolumes.map { it.first })
        val titles = job.newChapters.map { it.first }.toSet()
        assertTrue((41..160).none { "第${it}章" in titles })
        assertTrue("第200章" in titles)
        assertTrue("第161章" in titles)
        // 本卷（第一卷）过期的卷摘要这次一起重新汇总。
        assertEquals("v1", job.volumeId)
    }

    // ---------- M2 ----------

    @Test
    fun anIncompletePastVolumeIsTidiedOnALaterFinish() {
        val work = bigWork()
        val sums = (1..43).associate { "c$it" to longSummary(it) }
        // 第一卷的卷摘要只汇总了前 20 章（最后几章收尾时没整理成）：写到第二卷第 3 章收尾时补上。
        val half = Digests(volumes = mapOf("v1" to VolumeDigest("前二十章。", ids(1..20))))
        val job = StoryDigests.plan(work, "c43", sums.getValue("c43"), sums, half)
        assertEquals("v1", job.volumeId)
        assertEquals(ids(1..40), job.volumeChapterIds)
        // 一次都没汇总成的往卷也补。
        assertEquals("v1", StoryDigests.plan(work, "c43", sums.getValue("c43"), sums, Digests()).volumeId)
        // 汇总全了就不再要。
        assertNull(StoryDigests.plan(work, "c43", sums.getValue("c43"), sums, Digests(volumes = mapOf("v1" to VolumeDigest("全卷。", ids(1..40))))).volumeId)
    }

    @Test
    fun chaptersAPastVolumeDigestMissedStayInThePast() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        val v1 = w.volumes.single().id
        val ids = mutableListOf(w.chapters.single().id)
        repeat(7) { ids += s.addChapter(w.id, v1)!!.second.id }
        val v2 = s.addVolume(w.id, "第二卷 旧仓")!!.volumes.last().id
        repeat(4) { ids += s.addChapter(w.id, v2)!!.second.id }
        ids.forEachIndexed { i, id -> s.saveCard(w.id, id, ChapterCard(summary = "第${i + 1}章：铜灯上多了第${i + 1}道刻痕。")) }
        // 第一卷 8 章，卷摘要只汇总了前 4 章。
        s.updateDigests(w.id) { Digests(volumes = mapOf(v1 to VolumeDigest("第一卷前四章：铜灯上多了四道刻痕。", ids.take(4)))) }
        val past = s.brief(w.id, ids[11])!!.items.filter { it.group == "前情" }
        assertTrue(past.any { it.reason == ChapterBriefs.VOLUME_REASON })
        // 卷摘要盖到的不再单列；没盖到的第 5、6 章照旧带章摘要（第 7、8 章是最近几章）。
        assertFalse(past.any { "第2道刻痕" in it.text })
        assertTrue(past.any { "第5道刻痕" in it.text })
        assertTrue(past.any { "第6道刻痕" in it.text })
        assertTrue(past.any { "第8道刻痕" in it.text })
    }

    // ---------- M4 ----------

    @Test
    fun aValueChangedInTwoPartsKeepsTheWholeChapter() {
        fun hold(before: String, change: String, after: String, quote: String) = SyncItem(
            SyncGroup.HOLD, "林月·灵石：$before → $change → $after", quote, PackKind.CANON, name = "林月", text = after, chapterId = "c7", chapter = 7,
            place = SyncPlace.LEDGER, entry = LedgerEntry(kind = LedgerKind.HOLD, text = after, subject = "林月", aspect = "灵石", from = 7, quote = quote,
                chapterId = "c7", before = before, delta = change),
        )
        val merged = PackSync.merge(listOf(hold("100", "得了二十", "120", "林月收下二十块灵石。"), hold("120", "花去三十", "90", "林月付了三十块灵石。")))
        val e = merged.single().entry!!
        assertEquals("100", e.before)
        assertEquals("90", e.text)
        assertEquals("90", merged.single().text)
        assertEquals("得了二十；花去三十", e.delta)
        assertTrue(merged.single().title.endsWith("100 → 得了二十；花去三十 → 90"))
    }

    // ---------- M5 ----------

    @Test
    fun writingAChapterSeesTheValuesAtTheEndOfTheChapterBefore() {
        val people = "# 人物档案\n\n| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺女儿 |\n| 沈默 | 外乡人 |"
        val docs = listOf(PackDoc("c", PackKind.CHARACTER, "人物档案", 3, false) to people)
        val ledger = listOf(
            LedgerEntry(kind = LedgerKind.HOLD, text = "炼气", subject = "林月", aspect = "境界", from = 3),
            LedgerEntry(kind = LedgerKind.HOLD, text = "筑基", subject = "林月", aspect = "境界", from = 5, before = "炼气", delta = "突破"),
        )
        // 第 5 章已经整理入账（突破到筑基）：回头重写第 5 章，开头还是炼气。
        val brief = ChapterBriefs.build(docs, "林月推开药铺的门。", "林月闭关", chapter = 5, volume = 1, ledger = ledger).render()!!
        assertTrue(brief.contains("林月·境界：炼气"))
        assertFalse(brief.contains("筑基"))
        // 写第 6 章：第 5 章末已经是筑基。
        val next = ChapterBriefs.build(docs, "林月推开药铺的门。", "林月出关", chapter = 6, volume = 1, ledger = ledger).render()!!
        assertTrue(next.contains("筑基"))
    }

    // ---------- M6 ----------

    @Test
    fun aQuoteIsCheckedSentenceBySentence() {
        val quote = "沈默替她挡了一刀。老周藏起的半本账册还在。"
        // 原句的后一句删了，正文里只剩前一句：原句不在了。
        assertFalse(FinalCascade.quoteKept("林月跑去了渡口。\n沈默替她挡了一刀。\n杜娘关了茶馆。", quote))
        // 正文里一个短句恰好是原句的一部分：不算。
        assertFalse(FinalCascade.quoteKept("林月跑去了渡口。还在。", "老周藏起的半本账册还在。"))
        // 两句都在（换了标点、隔开了）照样算在；改了一个字也算在。
        assertTrue(FinalCascade.quoteKept("沈默替她挡了一刀！林月愣住了。\n老周藏起的半本账册还在。", quote))
        assertTrue(FinalCascade.quoteKept("杜娘说，老周藏起的那半本账册还在。", "老周藏起的半本账册还在。"))
        assertTrue(FinalCascade.quoteKept("前面的话。沈默替她挡了一刀。老周藏起的半本账册还在。后面的话。", quote))
    }

    // ---------- H2（级联核对） ----------

    private fun cascadeWork(root: File = tmp.newFolder()): Pair<WorkStore, Pair<String, String>> {
        val s = WorkStore(root)
        val w = s.create("渡灯")
        val id = w.chapters.single().id
        s.renameChapter(w.id, id, "第1章 灯")
        s.saveText(w.id, id, "林月躲进旧仓。\n沈默替她挡了一刀。", count = false)
        s.saveCard(w.id, id, ChapterCard(summary = "林月躲进旧仓，沈默替她挡刀。"))
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(id = "hide", kind = LedgerKind.STATE, text = "躲在旧仓", subject = "林月", aspect = "位置",
            from = 1, quote = "林月躲进旧仓。", chapterId = id))), "整理")
        s.setStatus(w.id, id, ChapterStatus.FINAL)
        s.setStatus(w.id, id, ChapterStatus.DRAFT, "林月改去渡口")
        s.saveText(w.id, id, "林月跑去了渡口。\n沈默替她挡了一刀。", count = false)
        return s to (w.id to id)
    }

    @Test
    fun aSummaryRewrittenAfterUnlockingIsNotMarkedStale() {
        val (s, ids) = cascadeWork()
        val (workId, id) = ids
        // 收尾时写了新摘要再定稿：新摘要按改后的正文写，不标过期。
        s.updateCard(workId, id) { it.copy(summary = "林月跑去渡口，沈默替她挡刀。") }
        s.setStatus(workId, id, ChapterStatus.FINAL)
        val report = s.cascadeFinal(workId, id)
        assertFalse(s.card(workId, id).summaryStale)
        assertFalse(report.summary)
        // 没换摘要就重新定稿：照旧标过期。
        val (s2, ids2) = cascadeWork()
        s2.setStatus(ids2.first, ids2.second, ChapterStatus.FINAL)
        assertTrue(s2.cascadeFinal(ids2.first, ids2.second).summary)
        assertTrue(s2.card(ids2.first, ids2.second).summaryStale)
    }

    // ---------- L1 ----------

    @Test
    fun aDeletedChapterDoesNotDisableAVolumeDigestForConfirmedOnly() {
        val work = bigWork()
        // 卷摘要里记着一章已经删掉了的：只认定稿时照样用这一卷。
        val digests = Digests(volumes = mapOf("v1" to VolumeDigest("第一卷。", ids(1..40) + "gone")))
        assertEquals(listOf("v1"), StoryDigests.layers(work, "c50", 50, digests, confirmedOnly = true)!!.volumes.map { it.volumeId })
    }

    // ---------- L2 ----------

    @Test
    fun aLedgerThatCannotBeWrittenIsNotReportedAsMarked() {
        val root = tmp.newFolder()
        val (s, ids) = cascadeWork(root)
        val (workId, id) = ids
        s.setStatus(workId, id, ChapterStatus.FINAL)
        // 账本的历史版本目录被一个文件占着：标待复核时存不了历史版本，账本写不进去。
        val dir = File(root, "works/$workId/ledger-history")
        dir.deleteRecursively()
        dir.writeText("占位")
        val report = s.cascadeFinal(workId, id)
        assertFalse(s.ledger(workId).single().recheck)
        assertEquals(0, report.recheck)
        assertTrue(report.message("第1章 灯").contains("没标上"))
        val cascade = s.changes(workId).filter { it.kind == ChangeKind.CASCADE }
        assertTrue(cascade.none { it.reason.contains("已在账本里标") })
        assertTrue(cascade.any { it.reason.contains("没能标") && it.reason.contains("躲在旧仓") })
    }

    // ---------- L3 ----------

    @Test
    fun ledgerEntriesFromLaterChaptersDoNotCountAsRecent() {
        val chapters = (1..40).map { PulseChapter("c$it", it, "第${it}章", "", "", emptyList()) }
        // 顾长风只在前三章有记录；设定包导入的“第 80 章起”还没发生，不算他刚动过。
        val ledger = (1..3).map { LedgerEntry(kind = LedgerKind.STATE, text = "押镖", subject = "顾长风", aspect = "位置", from = it) } +
            LedgerEntry(kind = LedgerKind.STATE, text = "回到渡灯镇", subject = "顾长风", aspect = "位置", from = 80)
        val row = StoryPulse.check(chapters, ledger).single { it.name == "顾长风" }
        assertEquals(PulseState.COLD, row.state)
        assertEquals(3, row.lastNo)
    }

    // ---------- L6 ----------

    @Test
    fun thePulseDoesNotRereadCardsThatDidNotChange() = runBlocking {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡灯")
        val volume = w.volumes.single().id
        val ids = mutableListOf(w.chapters.single().id)
        repeat(3) { ids += s.addChapter(w.id, volume)!!.second.id }
        ids.forEachIndexed { i, id ->
            s.saveText(w.id, id, "第${i + 1}章的正文。", count = false)
            s.saveCard(w.id, id, ChapterCard(threads = "明线：林月查灯会旧案"))
        }
        val model = WritingModel(s, scope) {}
        assertEquals(listOf("明线"), model.pulse(w.id).map { it.name })
        // 作品别处变了（改卷名），细纲卡文件没动：体检用上次算的，不再把每章的卡读一遍。
        // 这里把一张卡的内容换掉、修改时间和大小都不变，看出有没有重读。
        val card = File(root, "works/${w.id}/${ids[0]}.card.json")
        val stamp = card.lastModified()
        card.writeText(card.readText().replace("明线", "暗线"))
        card.setLastModified(stamp)
        s.renameVolume(w.id, volume, "第一卷 灯会")
        assertEquals(listOf("明线"), model.pulse(w.id).map { it.name })
        // 卡真改了（经过存盘）：重新算。
        s.saveCard(w.id, ids[1], ChapterCard(threads = "暗线：沈默的身世"))
        assertTrue(model.pulse(w.id).any { it.name == "暗线" })
    }
}
