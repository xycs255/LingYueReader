package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritingReferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): Triple<File, WorkStore, Work> {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        return Triple(root, store, store.create("当前作品"))
    }

    private fun importedReference(store: WorkStore, work: Work): WritingReference = store.importReference(work.id, "合成范本.txt", """
        前言
        这是独立范本的前言，并非第一章。
        第一章 开门
        范本甲独有标记：门外站着送信的人。
        他没有立刻回答，只把信放在桌上。
        第二章 等候
        范本乙独有标记：风停以后，灯还亮着。
        门外的脚步声渐渐远了。
    """.trimIndent())

    private fun expectRejected(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun referenceSnapshotsSurviveStoreRecreationWithoutCreatingStoryChapters() {
        val (root, store, work) = fixture()
        val reference = importedReference(store, work)
        val reopened = WorkStore(root)
        assertEquals(reference, reopened.references(work.id).single())
        assertEquals(listOf(null, 1, 2), reference.chapters.map { it.number })
        assertEquals(listOf(1, 2, 3), reference.chapters.map { it.index })
        assertEquals("前言", reference.chapters.first().title)
        val actualFirst = reference.chapters.single { it.number == 1 }
        assertTrue(reopened.referenceText(work.id, reference.id, actualFirst.id)!!.startsWith("范本甲独有标记"))
        assertFalse(reopened.referenceText(work.id, reference.id, actualFirst.id)!!.contains("第一章 开门"))
        assertEquals(work.chapters, reopened.get(work.id)!!.chapters)
        assertEquals("", reopened.text(work.id, work.chapters.single().id))
        assertEquals(reference.id, importedReference(reopened, work).id)
        assertEquals(1, reopened.references(work.id).size)
    }

    @Test fun identicalChapterTitlesStillHaveDistinctIdsAndOnlyExactIdsAreRead() {
        val (_, store, work) = fixture()
        val reference = store.importBookReference(work.id, "shelf-book", BookContent("同名章节范本", "合成作者", listOf(
            Chapter("第一章 开门", listOf(Block.Para("前卷独有：门向里开。")), 1),
            Chapter("第一章 开门", listOf(Block.Para("后卷独有：门向外开。")), 1),
        )))
        assertEquals("shelf-book", reference.sourceBookId)
        assertNotEquals(reference.chapters[0].id, reference.chapters[1].id)
        assertEquals("前卷独有：门向里开。", store.referenceText(work.id, reference.id, reference.chapters[0].id))
        assertEquals("后卷独有：门向外开。", store.referenceText(work.id, reference.id, reference.chapters[1].id))
        assertNull(store.referenceText(work.id, reference.id, "missing"))
        assertNull(store.referenceText(work.id, "missing", reference.chapters[0].id))
        assertNull(store.selectedChapterExcerpt(work.id, reference.id, "missing"))
        assertNull(store.referenceText(work.id, "../../outside", reference.chapters[0].id))
        assertNull(store.referenceText(work.id, reference.id, "../../outside"))
    }

    @Test fun openingEndingAndCustomRangesStayInsideTheSelectedChapter() {
        val (_, store, work) = fixture()
        val reference = importedReference(store, work)
        val chapter = reference.chapters.single { it.number == 1 }
        val text = store.referenceText(work.id, reference.id, chapter.id)!!
        val opening = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.OPENING, maxChars = 8)!!
        val ending = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.ENDING, maxChars = 9)!!
        val custom = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 3, end = 11)!!
        assertEquals(text.take(8), opening.text)
        assertEquals(0, opening.start)
        assertEquals(8, opening.end)
        assertEquals(text.takeLast(9), ending.text)
        assertEquals(text.length - 9, ending.start)
        assertEquals(text.length, ending.end)
        assertEquals(text.substring(3, 11), custom.text)
        assertEquals(chapter.fingerprint, custom.chapterFingerprint)
        listOf(opening, ending, custom).forEach { excerpt ->
            assertEquals(chapter.id, excerpt.chapterId)
            assertTrue(excerpt.truncated)
            assertFalse(excerpt.text.contains("范本乙独有标记"))
        }
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 1, end = text.length + 1) }
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = -1, end = 5) }
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.OPENING, maxChars = WritingReferences.MAX_EXCERPT_CHARS + 1) }
    }

    @Test fun boundedExcerptsNeverSplitUtf16SurrogatePairsAndCustomCutsAreRejected() {
        val (_, store, work) = fixture()
        val reference = store.importReference(work.id, "字符.txt", "第一章 字符\n甲😀乙😀丙")
        val chapter = reference.chapters.single()
        val opening = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.OPENING, maxChars = 2)!!
        val ending = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.ENDING, maxChars = 2)!!
        val emoji = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 1, end = 3)!!
        assertEquals("甲", opening.text)
        assertEquals(1, opening.end)
        assertEquals("丙", ending.text)
        assertEquals(6, ending.start)
        assertEquals("😀", emoji.text)
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 2, end = 3) }
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 1, end = 2) }
        val analysis = ReferenceAnalysis.from(opening, work.chapters.single().id, "target-v1", "分析完整字符边界。")
        store.saveReferenceAnalysis(work.id, analysis)
        assertTrue(store.referenceAnalysisCurrent(work.id, analysis, "target-v1"))
        val onlyEmoji = store.importReference(work.id, "单字符.txt", "第一章 单字符\n😀")
        val onlyChapter = onlyEmoji.chapters.single().id
        expectRejected { store.selectedChapterExcerpt(work.id, onlyEmoji.id, onlyChapter, ReferencePart.OPENING, maxChars = 1) }
        expectRejected { store.selectedChapterExcerpt(work.id, onlyEmoji.id, onlyChapter, ReferencePart.ENDING, maxChars = 1) }
    }

    @Test fun chapterAnalysisTakesTheEntireChapterAndRefusesOversizedChapter() {
        val (_, store, work) = fixture()
        val limit = WritingReferences.MAX_CHAPTER_ANALYSIS_CHARS
        val reference = store.importBookReference(work.id, "shelf-book", BookContent("长度范本", null, listOf(
            Chapter("第一章 正好", listOf(Block.Para("甲".repeat(limit))), 1),
            Chapter("第二章 过长", listOf(Block.Para("乙".repeat(limit + 1))), 2),
        )))
        val exact = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters[0].id, ReferencePart.CHAPTER, maxChars = 1)!!
        assertEquals(limit, exact.text.length)
        assertEquals(0, exact.start)
        assertEquals(limit, exact.end)
        assertFalse(exact.truncated)
        expectRejected { store.selectedChapterExcerpt(work.id, reference.id, reference.chapters[1].id, ReferencePart.CHAPTER) }
        val bounded = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters[1].id, ReferencePart.OPENING, maxChars = 200)!!
        assertEquals(200, bounded.text.length)
        assertTrue(bounded.truncated)
    }

    @Test fun analysesPersistApplyUnapplyAndDeleteWithoutCopyingSourceText() {
        val (root, store, work) = fixture()
        val reference = importedReference(store, work)
        val excerpt = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters.single { it.number == 1 }.id, maxChars = 12)!!
        val target = work.chapters.single().id
        val analysis = ReferenceAnalysis.from(excerpt, target, "target-v1", "开头以动作引入人物，把原因延后说明。")
        assertEquals(analysis, store.saveReferenceAnalysis(work.id, analysis))
        assertEquals(analysis, WorkStore(root).referenceAnalyses(work.id, target).single())
        assertTrue(store.setReferenceAnalysisApplied(work.id, analysis.id, true))
        assertTrue(store.referenceAnalyses(work.id).single().applied)
        assertTrue(store.setReferenceAnalysisApplied(work.id, analysis.id, false))
        assertFalse(store.referenceAnalyses(work.id).single().applied)
        assertFalse(store.setReferenceAnalysisApplied(work.id, "missing", true))
        val persisted = File(store.referenceRoot(work.id), "analyses.json").readText()
        assertFalse(persisted.contains("范本甲独有标记：门外站着送信的人。"))
        assertTrue(store.deleteReferenceAnalysis(work.id, analysis.id))
        assertTrue(store.referenceAnalyses(work.id).isEmpty())
        assertFalse(store.deleteReferenceAnalysis(work.id, analysis.id))
    }

    @Test fun sourceFingerprintAndTargetFingerprintHaveIndependentValidityChecks() {
        val (_, store, work) = fixture()
        val reference = importedReference(store, work)
        val chapter = reference.chapters.single { it.number == 1 }
        val excerpt = store.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.ENDING, maxChars = 9)!!
        val analysis = ReferenceAnalysis.from(excerpt, work.chapters.single().id, "target-v1", "结尾只评价当前片段的收束方式。")
        store.saveReferenceAnalysis(work.id, analysis)
        assertTrue(store.referenceAnalysisCurrent(work.id, analysis, "target-v1"))
        assertFalse(store.referenceAnalysisCurrent(work.id, analysis, "target-v2"))
        expectRejected { store.saveReferenceAnalysis(work.id, analysis.copy(id = WorkStore.newId(), sourceFingerprint = "0".repeat(64))) }
        expectRejected { store.saveReferenceAnalysis(work.id, analysis.copy(id = "../outside")) }
        expectRejected { store.saveReferenceAnalysis(work.id, analysis.copy(targetChapterId = "missing")) }
        assertEquals(listOf(analysis), store.referenceAnalyses(work.id))
        val sourceFile = File(File(store.referenceRoot(work.id), reference.id), "${chapter.id}.txt")
        sourceFile.writeText("范本文件被修改后的内容。")
        assertFalse(store.referenceAnalysisCurrent(work.id, analysis, "target-v1"))
        expectRejected { store.setReferenceAnalysisApplied(work.id, analysis.id, true) }
        expectRejected { store.saveReferenceAnalysis(work.id, analysis.copy(body = "再次分析")) }
        assertEquals(analysis, store.referenceAnalyses(work.id).single())
    }

    @Test fun appliedTechniqueAnalysisAndReferenceProseNeverBecomeStoryFactsOrPackKnowledge() {
        val (_, store, work) = fixture()
        val target = work.chapters.single().id
        store.saveCard(work.id, target, ChapterCard(facts = "本章主角只在桥边等人。", boundary = "本章不进城。"))
        val beforeCard = store.card(work.id, target)
        val reference = importedReference(store, work)
        val excerpt = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters.single { it.number == 1 }.id)!!
        val analysis = ReferenceAnalysis.from(excerpt, target, "target-v1", "分析独有标记：用对话间的犹豫表现关系，不移植人物和事件。")
        store.saveReferenceAnalysis(work.id, analysis)
        store.setReferenceAnalysisApplied(work.id, analysis.id, true)
        assertTrue(store.pack(work.id).isEmpty())
        assertTrue(store.ledger(work.id).isEmpty())
        assertEquals(beforeCard, store.card(work.id, target))
        assertEquals("", store.text(work.id, target))
        val normalContexts = listOf(
            store.aiPack(work.id, 20_000).prefix,
            store.aiPack(work.id, 20_000, WritingPhase.CREATE).prefix,
            store.writingTask(work.id, target)!!.render(),
            store.chapterHints(work.id, target, ""),
        )
        normalContexts.forEach { context ->
            assertFalse(context.contains("范本甲独有标记"))
            assertFalse(context.contains("范本乙独有标记"))
            assertFalse(context.contains("分析独有标记"))
        }
    }

    @Test fun removingOneReferenceAlsoRemovesItsAnalysesWithoutAffectingOtherReferences() {
        val (_, store, work) = fixture()
        val first = importedReference(store, work)
        val second = store.importReference(work.id, "另一范本.txt", "第一章 灯下\n另一范本独有的灯下情景。")
        for (reference in listOf(first, second)) {
            val excerpt = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
            store.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, work.chapters.single().id, "target-v1", "只保存这部范本的技法分析。"))
        }
        assertTrue(store.deleteReference(work.id, first.id))
        assertFalse(store.deleteReference(work.id, first.id))
        assertEquals(listOf(second.id), store.references(work.id).map { it.id })
        assertEquals(listOf(second.id), store.referenceAnalyses(work.id).map { it.referenceId })
        assertNull(store.referenceText(work.id, first.id, first.chapters.first().id))
        assertTrue(store.referenceText(work.id, second.id, second.chapters.single().id)!!.contains("另一范本独有"))
    }

    @Test fun automaticReadingSectionsNeverMasqueradeAsRealChapterNumbers() {
        val (_, store, work) = fixture()
        val reference = store.importBookReference(work.id, "old-shelf-book", BookContent("旧缓存范本", null, listOf(
            Chapter("第 1 节", listOf(Block.Para("阅读器自动切出的第一段原文。")), 1),
            Chapter("第 2 节", listOf(Block.Para("阅读器自动切出的第二段原文。")), 2),
        )))
        assertEquals(listOf(null, null), reference.chapters.map { it.number })
        assertTrue(reference.chapters.all { it.title.contains("未确认章界") })
        assertEquals(listOf(1, 2), reference.chapters.map { it.index })
    }

    @Test fun backupRoundTripRestoresIndependentReferencesAndAppliedAnalyses() {
        val (_, source, work) = fixture()
        val target = work.chapters.single().id
        source.saveText(work.id, target, "当前作品的独立正文。")
        val reference = importedReference(source, work)
        val chapter = reference.chapters.single { it.number == 1 }
        val excerpt = source.selectedChapterExcerpt(work.id, reference.id, chapter.id, ReferencePart.RANGE, start = 3, end = 15)!!
        val analysis = ReferenceAnalysis.from(excerpt, target, "target-v1", "只复用开场延迟解释的技法。")
        source.saveReferenceAnalysis(work.id, analysis)
        source.setReferenceAnalysisApplied(work.id, analysis.id, true)
        val archive = JSONObject(String(source.backup()!!))
        val component = archive.getJSONArray("works").getJSONObject(0).getJSONObject("references")
        assertTrue(component.getBoolean("complete"))
        assertEquals(1, component.getJSONArray("items").length())
        assertEquals(1, component.getJSONArray("analyses").length())
        val destinationRoot = tmp.newFolder()
        val destination = WorkStore(destinationRoot)
        destination.validateBackup(archive.toString())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        val reopened = WorkStore(destinationRoot)
        assertEquals(reference, reopened.references(work.id).single())
        assertEquals(source.referenceText(work.id, reference.id, chapter.id), reopened.referenceText(work.id, reference.id, chapter.id))
        assertEquals(analysis.copy(applied = true), reopened.referenceAnalyses(work.id).single())
        assertTrue(reopened.referenceAnalysisCurrent(work.id, reopened.referenceAnalyses(work.id).single(), "target-v1"))
        assertEquals("当前作品的独立正文。", reopened.text(work.id, target))
        assertTrue(reopened.pack(work.id).isEmpty())
        assertTrue(reopened.ledger(work.id).isEmpty())
    }

    @Test fun legacyBackupWithoutReferencesPreservesExistingLocalReferencesAndAnalyses() {
        val (_, source, work) = fixture()
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val reference = importedReference(destination, work)
        val target = work.chapters.single().id
        val excerpt = destination.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        val analysis = ReferenceAnalysis.from(excerpt, target, "target-v1", "本机已有的分析不能因旧备份缺键而消失。")
        destination.saveReferenceAnalysis(work.id, analysis)
        val archive = JSONObject(String(source.backup()!!))
        val incoming = archive.getJSONArray("works").getJSONObject(0)
        incoming.remove("references")
        incoming.put("updatedAt", destination.get(work.id)!!.updatedAt + 1_000)
        incoming.getJSONObject("texts").put(target, "旧备份里的较新正文。")
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals("旧备份里的较新正文。", destination.text(work.id, target))
        assertEquals(reference, destination.references(work.id).single())
        assertEquals(analysis, destination.referenceAnalyses(work.id).single())
        assertTrue(destination.referenceText(work.id, reference.id, reference.chapters.last().id)!!.contains("范本乙独有标记"))
    }

    @Test fun partialReferenceBackupPreservesLocalItemsAndAnalysesItDidNotContain() {
        val (_, source, work) = fixture()
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val localReference = importedReference(destination, work)
        val target = work.chapters.single().id
        val localExcerpt = destination.selectedChapterExcerpt(work.id, localReference.id, localReference.chapters.last().id)!!
        val localAnalysis = ReferenceAnalysis.from(localExcerpt, target, "target-v1", "本机保留的结尾分析。")
        destination.saveReferenceAnalysis(work.id, localAnalysis)
        val remoteReference = source.importReference(work.id, "远端范本.txt", "第一章 雨后\n远端范本独有标记：雨停以后，他收起雨伞。")
        val remoteExcerpt = source.selectedChapterExcerpt(work.id, remoteReference.id, remoteReference.chapters.single().id)!!
        val remoteAnalysis = ReferenceAnalysis.from(remoteExcerpt, target, "target-v1", "远端新增的开头分析。")
        source.saveReferenceAnalysis(work.id, remoteAnalysis)
        val archive = JSONObject(String(source.backup()!!))
        val incoming = archive.getJSONArray("works").getJSONObject(0)
        incoming.put("updatedAt", destination.get(work.id)!!.updatedAt + 1_000)
        incoming.getJSONObject("references").put("complete", false)
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals(setOf(localReference.id, remoteReference.id), destination.references(work.id).map { it.id }.toSet())
        assertEquals(setOf(localAnalysis.id, remoteAnalysis.id), destination.referenceAnalyses(work.id).map { it.id }.toSet())
        assertTrue(destination.referenceText(work.id, localReference.id, localReference.chapters.last().id)!!.contains("范本乙独有标记"))
        assertTrue(destination.referenceText(work.id, remoteReference.id, remoteReference.chapters.single().id)!!.contains("远端范本独有标记"))
    }

    @Test fun malformedReferenceSnapshotsFailBeforeReplacingAnyExistingTargetData() {
        val (_, source, work) = fixture()
        val target = work.chapters.single().id
        val reference = importedReference(source, work)
        val excerpt = source.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        source.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, target, "target-v1", "必须保留下来的本机分析。"))
        val archive = JSONObject(String(source.backup()!!))
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        destination.saveText(work.id, target, "本机正文保护标记。")
        val beforeReference = destination.references(work.id)
        val beforeAnalyses = destination.referenceAnalyses(work.id)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("items").getJSONObject(0).put("id", "../escape") },
            { it.getJSONArray("items").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("id", "../escape") },
            { it.getJSONArray("items").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("fingerprint", "0".repeat(64)) },
            { it.getJSONArray("items").getJSONObject(0).put("fingerprint", "0".repeat(64)) },
            { it.getJSONArray("analyses").getJSONObject(0).put("sourceFingerprint", "0".repeat(64)) },
            { it.getJSONArray("analyses").getJSONObject(0).put("targetChapterId", "../escape") },
        )
        mutations.forEach { mutate ->
            val malformed = JSONObject(archive.toString())
            val incoming = malformed.getJSONArray("works").getJSONObject(0)
            incoming.put("updatedAt", destination.get(work.id)!!.updatedAt + 1_000)
            mutate(incoming.getJSONObject("references"))
            expectRejected { destination.validateBackup(malformed.toString()) }
            expectRejected { destination.restoreBackup(malformed.toString()) }
            assertEquals("本机正文保护标记。", destination.text(work.id, target))
            assertEquals(beforeReference, destination.references(work.id))
            assertEquals(beforeAnalyses, destination.referenceAnalyses(work.id))
            assertFalse(File(destination.referenceRoot(work.id).parentFile, "escape").exists())
        }
    }

    @Test fun exhaustedSnapshotBudgetReportsOmissionsAndStagesWithoutDeletingLocalContent() {
        val (_, source, work) = fixture()
        val reference = importedReference(source, work)
        val excerpt = source.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        source.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, work.chapters.single().id, "target-v1", "预算不足时随原文一起省略的分析。"))
        val skipped = mutableListOf<Pair<String, String>>()
        val snapshot = WritingReferenceBackup.snapshot(source.referenceRoot(work.id), budget = 256, targetChapterIds = work.chapters.map { it.id }.toSet()) { title, reason -> skipped += title to reason }!!
        assertFalse(snapshot.getBoolean("complete"))
        assertEquals(0, snapshot.getJSONArray("items").length())
        assertEquals(0, snapshot.getJSONArray("analyses").length())
        assertEquals(listOf(reference.title), skipped.map { it.first })
        assertTrue(skipped.single().second.contains("预算"))
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val beforeReferences = destination.references(work.id)
        val beforeAnalyses = destination.referenceAnalyses(work.id)
        val chapterIds = setOf(work.chapters.single().id)
        WritingReferenceBackup.stage(WritingReferenceBackup.parse(snapshot, chapterIds), destination.referenceRoot(work.id), chapterIds)
        assertEquals(beforeReferences, destination.references(work.id))
        assertEquals(beforeAnalyses, destination.referenceAnalyses(work.id))
        assertTrue(destination.referenceText(work.id, reference.id, reference.chapters.last().id)!!.contains("范本乙独有标记"))
        assertNull(WritingReferenceBackup.snapshot(source.referenceRoot(work.id), budget = 255, targetChapterIds = work.chapters.map { it.id }.toSet()) { _, _ -> })
        // 先检查实际文件体积：异常增大的文件应按预算省略，不读入后再因指纹错误失败。
        val oversizedFile = File(File(source.referenceRoot(work.id), reference.id), "${reference.chapters.last().id}.txt")
        java.io.RandomAccessFile(oversizedFile, "rw").use { it.setLength(20_000) }
        val oversizedSkipped = mutableListOf<String>()
        val oversized = WritingReferenceBackup.snapshot(source.referenceRoot(work.id), budget = 10_000, targetChapterIds = work.chapters.map { it.id }.toSet()) { _, reason -> oversizedSkipped += reason }!!
        assertFalse(oversized.getBoolean("complete"))
        assertEquals(0, oversized.getJSONArray("items").length())
        assertTrue(oversizedSkipped.single().contains("预算"))
        assertFalse(oversizedSkipped.single().contains("读不出"))
    }

    @Test fun deletingAChapterDropsItsAnalysesAndTheBackupStillRestores() {
        val (_, source, work) = fixture()
        val kept = work.chapters.single().id
        val volume = work.volumes.single().id
        val (_, removed) = source.addChapter(work.id, volume, "第二章 渡口")!!
        val reference = importedReference(source, work)
        val excerpt = source.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        val keptAnalysis = ReferenceAnalysis.from(excerpt, kept, "target-v1", "林月这一章借鉴的开场延迟。")
        source.saveReferenceAnalysis(work.id, keptAnalysis)
        source.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, removed.id, "target-v1", "沈默那一章的分析，删章后应一起清掉。"))
        source.deleteChapter(work.id, removed.id)
        assertEquals(listOf(keptAnalysis.id), source.referenceAnalyses(work.id).map { it.id })
        val report = source.backupReport()!!
        assertTrue(report.skipped.isEmpty())
        val destination = WorkStore(tmp.newFolder())
        destination.validateBackup(String(report.bytes))
        val restored = destination.restoreBackupReport(String(report.bytes))
        assertEquals(1, restored.restored)
        assertEquals(listOf(keptAnalysis.id), destination.referenceAnalyses(work.id).map { it.id })
    }

    @Test fun deletingAVolumeDropsAnalysesOfEveryChapterInIt() {
        val (_, store, work) = fixture()
        val second = store.addVolume(work.id, "第二卷")!!.volumes.last().id
        val (_, chapter) = store.addChapter(work.id, second, "第一章 夜渡")!!
        val reference = importedReference(store, work)
        val excerpt = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        store.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, chapter.id, "target-v1", "老周撑船那一章的分析。"))
        store.deleteVolume(work.id, second)
        assertTrue(store.referenceAnalyses(work.id).isEmpty())
    }

    @Test fun orphanAnalysesFromOlderVersionsAreSkippedInBackupAndRestore() {
        val (_, source, work) = fixture()
        val target = work.chapters.single().id
        val reference = importedReference(source, work)
        val excerpt = source.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        val live = ReferenceAnalysis.from(excerpt, target, "target-v1", "仍然有效的分析。")
        source.saveReferenceAnalysis(work.id, live)
        // 旧版本删章不清分析：直接写出一条指向不存在章节的孤儿分析。
        val orphan = ReferenceAnalysis.from(excerpt, "gone-chapter", "target-v1", "指向已删章的孤儿分析。")
        WritingReferences.writeAnalyses(source.referenceRoot(work.id), listOf(orphan, live))
        val report = source.backupReport()!!
        assertTrue(report.skipped.any { it.reason.contains("已删除") })
        val component = JSONObject(String(report.bytes)).getJSONArray("works").getJSONObject(0).getJSONObject("references")
        assertEquals(1, component.getJSONArray("analyses").length())

        // 旧版本做出的备份里已经带着孤儿分析：跳过这一条并报告，其余照常恢复。
        val archive = JSONObject(String(report.bytes))
        archive.getJSONArray("works").getJSONObject(0).getJSONObject("references").getJSONArray("analyses")
            .put(WritingReferences.analysisToJson(orphan))
        val destination = WorkStore(tmp.newFolder())
        destination.validateBackup(archive.toString())
        val restored = destination.restoreBackupReport(archive.toString())
        assertEquals(1, restored.restored)
        assertTrue(restored.skipped.any { it.reason.contains("这条分析没有恢复") })
        assertEquals(listOf(live.id), destination.referenceAnalyses(work.id).map { it.id })
    }

    @Test fun analysisRoomIsCheckedBeforeRequestsAndOrphansDoNotUseIt() {
        val (_, store, work) = fixture()
        val target = work.chapters.single().id
        val reference = importedReference(store, work)
        val excerpt = store.selectedChapterExcerpt(work.id, reference.id, reference.chapters.last().id)!!
        val root = store.referenceRoot(work.id)
        val full = (1..WritingReferences.MAX_ANALYSES).map { ReferenceAnalysis.from(excerpt, target, "target-v1", "渡口第 $it 份分析。") }
        WritingReferences.writeAnalyses(root, full)
        val error = runCatching { store.requireReferenceAnalysisRoom(work.id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("先删几条旧分析"))
        // 孤儿分析不占条数；下次保存时顺手清掉。
        val orphans = full.take(10).map { it.copy(targetChapterId = "gone-chapter") }
        WritingReferences.writeAnalyses(root, orphans + full.drop(10))
        store.requireReferenceAnalysisRoom(work.id)
        store.saveReferenceAnalysis(work.id, ReferenceAnalysis.from(excerpt, target, "target-v1", "新的一份分析。"))
        assertEquals(91, store.referenceAnalyses(work.id).size)
        assertTrue(store.referenceAnalyses(work.id).none { it.targetChapterId == "gone-chapter" })
    }

    @Test fun interruptedImportStagingDirectoriesAreCleanedUp() {
        val (_, store, work) = fixture()
        val root = store.referenceRoot(work.id)
        val stale = File(root, ".import-stale").apply { mkdirs() }
        File(stale, "c1.txt").writeText("中断时留下的半份范本。")
        store.references(work.id)
        assertFalse(stale.exists())
        val again = File(root, ".import-again").apply { mkdirs() }
        importedReference(store, work)
        assertFalse(again.exists())
        assertEquals(1, store.references(work.id).size)
    }
}
