package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class WritingCommitRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private class SimulatedProcessDeath : Error()

    /** A directory at DurableFiles' temp path deterministically fails the metadata write. */
    private fun blockMetadata(root: File, workId: String): File =
        File(root, "works/$workId/work.json.tmp").apply { assertTrue(mkdir()) }

    private fun futureBackup(backup: String, laterThan: Long): String = JSONObject(backup).apply {
        getJSONArray("works").getJSONObject(0).put("updatedAt", laterThan + 1_000_000)
    }.toString()

    @Test fun chapterCommitIsRepairedOnceAndOlderBackupCannotReplaceNewText() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val backup = String(store.backup()!!)
        val day = LocalDate.of(2026, 10, 27)
        val body = "新的正文，停在元数据提交之前。"
        val obstacle = blockMetadata(root, work.id)

        assertTrue(runCatching { store.saveText(work.id, chapter, body, day) }.isFailure)
        assertEquals(body, store.text(work.id, chapter))
        assertEquals(0, store.get(work.id)!!.chapter(chapter)!!.words)
        assertEquals(WordCount.of(body), store.stats().on(day))
        assertTrue(File(root, "works/${work.id}/.pending-save.json").isFile)

        assertTrue(obstacle.delete())
        val reopened = WorkStore(root)
        assertEquals(WordCount.of(body), reopened.get(work.id)!!.chapter(chapter)!!.words)
        assertEquals(WordCount.of(body), reopened.stats().on(day))
        reopened.saveText(work.id, chapter, body, day)
        assertEquals(WordCount.of(body), reopened.stats().on(day))
        assertEquals(0, reopened.restoreBackupReport(futureBackup(backup, reopened.get(work.id)!!.updatedAt)).restored)
        assertEquals(body, reopened.text(work.id, chapter))
    }

    @Test fun chapterCommitBeforeStatsIsCountedOnceAfterRestart() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val day = LocalDate.of(2026, 10, 27)
        val obstacle = File(root, "stats.json.tmp").apply { assertTrue(mkdir()) }
        val body = "统计写不进去，正文已经保存。"

        assertTrue(runCatching { store.saveText(work.id, chapter, body, day) }.isFailure)
        assertEquals(body, store.text(work.id, chapter))
        assertEquals(0, store.stats().on(day))
        assertTrue(obstacle.delete())

        val reopened = WorkStore(root)
        assertEquals(WordCount.of(body), reopened.get(work.id)!!.chapter(chapter)!!.words)
        assertEquals(WordCount.of(body), reopened.stats().on(day))
        WorkStore(root).saveText(work.id, chapter, body, day)
        assertEquals(WordCount.of(body), WorkStore(root).stats().on(day))
    }

    @Test fun draftCommitAfterBodySurvivesFutureStampedBackup() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "旧候选稿。")
        val backup = String(store.backup()!!)
        val obstacle = blockMetadata(root, work.id)

        assertTrue(runCatching { store.saveDraft(work.id, chapter, "新候选稿。") }.isFailure)
        assertEquals("新候选稿。", store.draft(work.id, chapter))
        assertTrue(obstacle.delete())

        val reopened = WorkStore(root)
        val report = reopened.restoreBackupReport(futureBackup(backup, reopened.get(work.id)!!.updatedAt))
        assertEquals(0, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("中断后保留") })
        assertEquals("新候选稿。", reopened.draft(work.id, chapter))
        assertFalse(reopened.draftContent(work.id, chapter).blocked)
    }

    @Test fun unrelatedLaterEditDoesNotRemoveRecoveredProseGuard() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val oldBackup = String(store.backup()!!)
        val obstacle = blockMetadata(root, work.id)
        assertTrue(runCatching { store.saveText(work.id, chapter, "恢复后的正文") }.isFailure)
        assertTrue(obstacle.delete())

        val reopened = WorkStore(root)
        reopened.saveDraft(work.id, chapter, "另一份候选稿")
        val guarded = File(root, "works/${work.id}/.recovered-save.json")
        assertTrue(guarded.isFile)
        val report = WorkStore(root).restoreBackupReport(futureBackup(oldBackup, reopened.get(work.id)!!.updatedAt))
        assertEquals(0, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("中断后保留") })
        assertEquals("恢复后的正文", WorkStore(root).text(work.id, chapter))
    }

    @Test fun draftSourceWriteFailureKeepsPreviousCandidateAndReference() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val old = "原候选稿，作者已经改过。"
        val oldReference = DraftReferenceProvenance(java.util.UUID.randomUUID().toString(), "a".repeat(64), old.length, true)
        store.saveDraft(work.id, chapter, old, oldReference)
        val source = File(root, "works/${work.id}/$chapter.draft.reference.json")
        val priorSourceBytes = source.readBytes()
        val obstacle = File(source.parentFile, source.name + ".tmp").apply { assertTrue(mkdir()) }

        assertTrue(runCatching { store.saveDraft(work.id, chapter, "新候选稿", blocked = true) }.isFailure)
        assertEquals(old, store.draft(work.id, chapter))
        assertTrue(priorSourceBytes.contentEquals(source.readBytes()))
        assertFalse(File(source.parentFile, ".pending-save.json").exists())
        assertTrue(obstacle.delete())
        val reopened = WorkStore(root)
        assertEquals(old, reopened.draft(work.id, chapter))
        assertEquals(oldReference, reopened.draftContent(work.id, chapter).reference)
        assertFalse(reopened.draftContent(work.id, chapter).blocked)
    }

    @Test fun restartRollsBackDraftBodyWhenProcessDiesBeforeSourceCommit() {
        val root = tmp.newFolder()
        val initial = WorkStore(root)
        val work = initial.create("长夜")
        val chapter = work.chapters.single().id
        val old = "带来源的旧候选稿"
        val oldReference = DraftReferenceProvenance(java.util.UUID.randomUUID().toString(), "b".repeat(64), old.length, true)
        initial.saveDraft(work.id, chapter, old, oldReference)
        val killed = WorkStore(root, savePhaseHook = { if (it == "draft-body") throw SimulatedProcessDeath() })

        assertTrue(runCatching { killed.saveDraft(work.id, chapter, "进程中断前的新稿") }.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals("进程中断前的新稿", killed.draft(work.id, chapter))
        assertTrue(File(root, "works/${work.id}/.pending-save.json").isFile)
        val reopened = WorkStore(root)
        assertEquals(old, reopened.draft(work.id, chapter))
        assertEquals(oldReference, reopened.draftContent(work.id, chapter).reference)
        assertFalse(reopened.draftContent(work.id, chapter).blocked)
        assertFalse(File(root, "works/${work.id}/.pending-save.json").exists())
    }

    @Test fun nonRegularSaveJournalKeepsRollbackSnapshotAndBlocksOverwrite() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "原候选稿")
        val folder = File(root, "works/${work.id}")
        val snapshot = File(folder, ".draft-save-previous/body").apply {
            assertTrue(parentFile!!.mkdirs())
            writeText("保留的原件")
        }
        assertTrue(File(folder, ".pending-save.json").mkdir())

        assertTrue(runCatching { store.saveDraft(work.id, chapter, "新候选稿") }.isFailure)
        assertEquals("原候选稿", store.draft(work.id, chapter))
        assertEquals("保留的原件", snapshot.readText())
        assertTrue(store.backupReport()!!.skipped.any { it.reason.contains("尚未修复") })
    }

    @Test fun unresolvedChapterSaveBlocksDeleteBeforeRemovingAnyFiles() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveText(work.id, chapter, "原正文")
        val obstacle = blockMetadata(root, work.id)
        assertTrue(runCatching { store.saveText(work.id, chapter, "更新后的正文") }.isFailure)

        assertTrue(runCatching { store.deleteChapter(work.id, chapter) }.isFailure)
        assertEquals("更新后的正文", store.text(work.id, chapter))
        assertTrue(store.get(work.id)!!.chapter(chapter) != null)
        assertTrue(obstacle.delete())
        val reopened = WorkStore(root)
        assertEquals("更新后的正文", reopened.text(work.id, chapter))
        assertTrue(reopened.deleteChapter(work.id, chapter) != null)
        assertFalse(File(root, "works/${work.id}/.pending-save.json").exists())
    }

    @Test fun unresolvedPackSaveBlocksDeleteBeforeRemovingDocument() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val doc = store.addPackDoc(work.id, PackKind.CANON, "设定", "旧设定")
        val obstacle = File(root, "works/${work.id}/pack/index.json.tmp").apply { assertTrue(mkdir()) }
        assertTrue(runCatching { store.savePackText(work.id, doc.id, "新设定") }.isFailure)

        assertTrue(runCatching { store.deletePackDoc(work.id, doc.id) }.isFailure)
        assertEquals("新设定", store.packText(work.id, doc.id))
        assertTrue(store.pack(work.id).any { it.id == doc.id })
        assertTrue(obstacle.delete())
        val reopened = WorkStore(root)
        reopened.deletePackDoc(work.id, doc.id)
        assertFalse(File(root, "works/${work.id}/.pending-save.json").exists())
        assertTrue(reopened.pack(work.id).none { it.id == doc.id })
    }

    @Test fun ledgerAndPackCommitsRecoverTheirOwnMetadata() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val pack = store.addPackDoc(work.id, PackKind.CANON, "设定", "旧设定")
        val backup = String(store.backup()!!)

        val indexObstacle = File(root, "works/${work.id}/pack/index.json.tmp").apply { assertTrue(mkdir()) }
        assertTrue(runCatching { store.savePackText(work.id, pack.id, "新设定，增加了人物。") }.isFailure)
        assertTrue(indexObstacle.delete())
        val afterPack = WorkStore(root)
        assertEquals(WordCount.of("新设定，增加了人物。"), afterPack.pack(work.id).single().words)
        assertEquals(0, afterPack.restoreBackupReport(futureBackup(backup, afterPack.get(work.id)!!.updatedAt)).restored)

        val obstacle = blockMetadata(root, work.id)
        assertTrue(runCatching { afterPack.applyLedger(work.id,
            listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "渡口起火"))), "记下新事件") }.isFailure)
        assertTrue(obstacle.delete())
        val afterLedger = WorkStore(root)
        assertEquals("渡口起火", afterLedger.ledger(work.id).single().text)
        assertEquals(0, afterLedger.restoreBackupReport(futureBackup(backup, afterLedger.get(work.id)!!.updatedAt)).restored)
        assertEquals("渡口起火", afterLedger.ledger(work.id).single().text)
    }
}
