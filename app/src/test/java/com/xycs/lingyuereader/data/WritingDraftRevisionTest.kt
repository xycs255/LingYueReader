package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritingDraftRevisionTest {
    @get:Rule val tmp = TemporaryFolder()
    private class SimulatedProcessDeath : Error()

    private fun revisionFile(root: File, workId: String, chapterId: String) =
        File(root, "works/$workId/$chapterId.draft.revision.json")

    private fun newer(backup: String, than: Long): String = JSONObject(backup).apply {
        getJSONArray("works").getJSONObject(0).put("updatedAt", than + 1_000_000)
    }.toString()

    @Test fun firstDraftAndRevisedCandidateStayPairedAcrossRestartAndAuthorEdit() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "初稿", revision = DraftRevisionSnapshot("初稿"))
        store.saveDraft(work.id, chapter, "修订一", revision = DraftRevisionSnapshot("初稿"))

        assertEquals(DraftRevisionSnapshot("初稿"), WorkStore(root).draftContent(work.id, chapter).revision)
        store.saveDraft(work.id, chapter, "作者又改了", revision = DraftRevisionSnapshot("初稿"))
        assertEquals("初稿", WorkStore(root).draftContent(work.id, chapter).revision?.initialText)
        assertEquals("作者又改了", store.draft(work.id, chapter))
        store.saveDraft(work.id, chapter, "初稿", revision = DraftRevisionSnapshot("初稿", "作者又改了"))
        assertEquals(DraftRevisionSnapshot("初稿", "作者又改了"), WorkStore(root).draftContent(work.id, chapter).revision)
        store.saveDraft(work.id, chapter, "", revision = null)
        assertFalse(revisionFile(root, work.id, chapter).exists())
    }

    @Test fun processDeathAndSidecarWriteFailureRestoreTheOldTriple() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val first = DraftRevisionSnapshot("创作初稿")
        store.saveDraft(work.id, chapter, "旧修订稿", revision = first)
        val oldRevisionBytes = revisionFile(root, work.id, chapter).readBytes()

        val killed = WorkStore(root, savePhaseHook = { if (it == "draft-body") throw SimulatedProcessDeath() })
        assertTrue(runCatching { killed.saveDraft(work.id, chapter, "新修订稿", revision = first) }.exceptionOrNull() is SimulatedProcessDeath)
        val reopened = WorkStore(root)
        assertEquals("旧修订稿", reopened.draft(work.id, chapter))
        assertEquals(first, reopened.draftContent(work.id, chapter).revision)
        assertTrue(oldRevisionBytes.contentEquals(revisionFile(root, work.id, chapter).readBytes()))

        val sidecar = revisionFile(root, work.id, chapter)
        val obstacle = File(sidecar.parentFile, sidecar.name + ".tmp")
        assertTrue(obstacle.mkdir())
        assertTrue(runCatching { reopened.saveDraft(work.id, chapter, "再次修订", revision = first) }.isFailure)
        assertTrue(obstacle.delete())
        assertEquals("旧修订稿", WorkStore(root).draft(work.id, chapter))
        assertTrue(oldRevisionBytes.contentEquals(sidecar.readBytes()))
    }

    @Test fun damagedSnapshotIsNeverDiscardedWithoutVerifiedPreservation() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "修订稿", revision = DraftRevisionSnapshot("初稿"))
        val sidecar = revisionFile(root, work.id, chapter)
        val damaged = "{broken-snapshot".toByteArray()
        sidecar.writeBytes(damaged)

        val content = store.draftContent(work.id, chapter)
        assertEquals("修订稿", content.text)
        assertNotNull(content.revisionError)
        val preserved = File(sidecar.parentFile, sidecar.name + ".damaged")
        assertTrue(preserved.isFile)
        assertTrue(damaged.contentEquals(preserved.readBytes()))
        store.saveDraft(work.id, chapter, "新稿")
        assertTrue(damaged.contentEquals(preserved.readBytes()))
        assertEquals("新稿", WorkStore(root).draft(work.id, chapter))
    }

    @Test fun snapshotThatCannotBeReadBlocksOverwriteOfTheCandidate() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "正文", revision = DraftRevisionSnapshot("初稿"))
        val sidecar = revisionFile(root, work.id, chapter)
        assertTrue(sidecar.delete())
        assertTrue(sidecar.mkdir())
        File(sidecar, "only-copy").writeText("保留中的版本")

        assertTrue(runCatching { store.saveDraft(work.id, chapter, "覆盖") }.isFailure)
        assertEquals("正文", WorkStore(root).draft(work.id, chapter))
        assertEquals("保留中的版本", File(sidecar, "only-copy").readText())
    }

    @Test fun backupSkipsBothDraftAndSourceWhenItsSnapshotIsDamaged() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "修订稿", revision = DraftRevisionSnapshot("初稿"))
        revisionFile(root, work.id, chapter).writeText("{broken")

        val report = store.backupReport()!!
        val entry = JSONObject(String(report.bytes)).getJSONArray("works").getJSONObject(0)
        assertTrue(report.skipped.any { it.file.contains("draft.revision") && it.reason.contains("草稿及初稿均未放进备份") })
        assertFalse(entry.getJSONObject("drafts").has(chapter))
        assertFalse(entry.getJSONObject("draftSources").has(chapter))
        assertFalse(entry.getJSONObject("draftRevisions").has(chapter))
    }

    @Test fun backupRestoresSnapshotAndRejectsTamperedFingerprintBeforeChangingLiveWork() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "修订稿", revision = DraftRevisionSnapshot("初稿"))
        val archive = String(source.backup()!!)
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(archive))
        assertEquals(DraftRevisionSnapshot("初稿"), destination.draftContent(work.id, chapter).revision)

        val bad = JSONObject(archive)
        bad.getJSONArray("works").getJSONObject(0).getJSONObject("draftRevisions")
            .getJSONObject(chapter).put("initialText", "伪造初稿")
        assertTrue(runCatching { destination.validateBackup(bad.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(bad.toString()) }.isFailure)
        assertEquals("修订稿", destination.draft(work.id, chapter))
        assertEquals("初稿", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun olderArchiveWithSameProseKeepsLocalSnapshot() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "共同稿")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, chapter, "共同稿", revision = DraftRevisionSnapshot("初稿"))
        val old = JSONObject(String(source.backup()!!))
        old.getJSONArray("works").getJSONObject(0).remove("draftRevisions")

        assertEquals(1, destination.restoreBackup(newer(old.toString(), destination.get(work.id)!!.updatedAt)))
        assertEquals("共同稿", destination.draft(work.id, chapter))
        assertEquals("初稿", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun olderArchiveWithDifferentProseSkipsOnlyItsDraftWhenLocalSnapshotExists() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "旧备份稿")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, chapter, "本机修订稿", revision = DraftRevisionSnapshot("本机初稿"))
        val old = JSONObject(String(source.backup()!!))
        old.getJSONArray("works").getJSONObject(0).remove("draftRevisions")

        val report = destination.restoreBackupReport(newer(old.toString(), destination.get(work.id)!!.updatedAt))
        assertEquals(1, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("保留本机草稿与初稿") })
        assertEquals("本机修订稿", destination.draft(work.id, chapter))
        assertEquals("本机初稿", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun explicitNullSnapshotWithSameProseKeepsTheLocalFirstDraft() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "共同稿")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, chapter, "共同稿", revision = DraftRevisionSnapshot("本机初稿"))
        val archive = String(source.backup()!!)
        assertEquals(JSONObject.NULL, JSONObject(archive).getJSONArray("works").getJSONObject(0)
            .getJSONObject("draftRevisions").get(chapter))

        assertEquals(1, destination.restoreBackup(newer(archive, destination.get(work.id)!!.updatedAt)))
        assertEquals("共同稿", destination.draft(work.id, chapter))
        assertEquals("本机初稿", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun explicitNullSnapshotWithDifferentProseSkipsTheIncomingCandidate() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "备份里的稿")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, chapter, "本机修订稿", revision = DraftRevisionSnapshot("本机初稿"))

        val report = destination.restoreBackupReport(newer(String(source.backup()!!), destination.get(work.id)!!.updatedAt))
        assertEquals(1, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("保留本机草稿与初稿") })
        assertEquals("本机修订稿", destination.draft(work.id, chapter))
        assertEquals("本机初稿", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun differentValidBackupPairCannotOverwriteALocalCandidateAndFirstDraft() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "备份修订稿 A", revision = DraftRevisionSnapshot("备份初稿 A"))
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, chapter, "本机修订稿 B", revision = DraftRevisionSnapshot("本机初稿 B"))

        val report = destination.restoreBackupReport(newer(String(source.backup()!!), destination.get(work.id)!!.updatedAt))
        assertEquals(1, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("保留本机草稿与初稿") })
        assertEquals("本机修订稿 B", destination.draft(work.id, chapter))
        assertEquals("本机初稿 B", destination.draftContent(work.id, chapter).revision?.initialText)
    }

    @Test fun damagedLocalSnapshotIsPreservedAndCopiedBeforeNewerArchiveRestoresOtherComponents() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "备份修订稿", revision = DraftRevisionSnapshot("备份初稿"))
        val root = tmp.newFolder()
        val destination = WorkStore(root)
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val sidecar = revisionFile(root, work.id, chapter)
        val damaged = "{damaged-local-version".toByteArray()
        sidecar.writeBytes(damaged)
        source.saveDraft(work.id, chapter, "备份更新稿", revision = DraftRevisionSnapshot("备份初稿"))

        val report = destination.restoreBackupReport(newer(String(source.backup()!!), destination.get(work.id)!!.updatedAt))
        assertEquals(1, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("已留底并保留本机草稿与初稿") })
        assertEquals("备份修订稿", destination.draft(work.id, chapter))
        assertTrue(damaged.contentEquals(sidecar.readBytes()))
        assertTrue(damaged.contentEquals(File(sidecar.parentFile, sidecar.name + ".damaged").readBytes()))
    }

    @Test fun unreadableLocalSnapshotPathRejectsRestoreBeforeAnyReplacement() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "备份修订稿", revision = DraftRevisionSnapshot("备份初稿"))
        val root = tmp.newFolder()
        val destination = WorkStore(root)
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val sidecar = revisionFile(root, work.id, chapter)
        assertTrue(sidecar.delete())
        assertTrue(sidecar.mkdir())
        File(sidecar, "preserved").writeText("必须保留")

        val report = destination.restoreBackupReport(newer(String(source.backup()!!), destination.get(work.id)!!.updatedAt))
        assertEquals(0, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("无法留底") })
        assertEquals("备份修订稿", destination.draft(work.id, chapter))
        assertEquals("必须保留", File(sidecar, "preserved").readText())
    }

    @Test fun backupDeletingAChapterCannotDiscardItsLocalCandidateAndFirstDraft() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val (_, second) = source.addChapter(work.id, work.volumes.single().id)!!
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        destination.saveDraft(work.id, second.id, "本机候选稿", revision = DraftRevisionSnapshot("本机初稿"))
        source.deleteChapter(work.id, second.id)

        val report = destination.restoreBackupReport(newer(String(source.backup()!!), destination.get(work.id)!!.updatedAt))
        assertEquals(0, report.restored)
        assertTrue(report.skipped.any { it.reason.contains("备份删除的章节仍有本机候选稿或创作初稿") })
        assertNotNull(destination.get(work.id)!!.chapter(second.id))
        assertEquals("本机候选稿", destination.draft(work.id, second.id))
        assertEquals("本机初稿", destination.draftContent(work.id, second.id).revision?.initialText)
    }
}
