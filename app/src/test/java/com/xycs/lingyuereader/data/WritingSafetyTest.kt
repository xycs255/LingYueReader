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

class WritingSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun finalizeSavesTheEditorBufferBeforeLockingAndSnapshotsThatText() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveText(work.id, chapter, "盘上的旧稿。")
        val current = "盘上的旧稿。\n刚刚粘贴、还没等到自动保存的新段落。"

        val finalized = store.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = current)!!

        assertEquals(current, store.text(work.id, chapter))
        assertEquals(WordCount.of(current), finalized.chapter(chapter)!!.words)
        assertEquals(WordCount.of(current), store.stats().on(LocalDate.now()))
        assertTrue(finalized.chapter(chapter)!!.locked)
        assertEquals(current, store.snapshotText(work.id, chapter, store.history(work.id, chapter).first().at))
        assertEquals(ChangeKind.FINALIZE, store.changes(work.id).single().kind)
        // 稍后到达的自动保存、退出保存是同一份正文，不能因已锁定而丢失。
        store.saveText(work.id, chapter, current)
        assertEquals(current, store.text(work.id, chapter))
    }

    @Test
    fun failedEditorSaveDoesNotLockTheOldTextOrRecordAFinalization() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveText(work.id, chapter, "盘上的旧稿。")
        // 确定性地阻止正文临时文件写入；不依赖磁盘空间或当前用户权限。
        File(root, "works/${work.id}/$chapter.txt.tmp").mkdir()

        assertTrue(runCatching {
            store.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = "还没存的新稿。")
        }.isFailure)

        assertEquals("盘上的旧稿。", store.text(work.id, chapter))
        assertFalse(store.get(work.id)!!.chapter(chapter)!!.locked)
        assertTrue(store.changes(work.id).isEmpty())
        assertTrue(store.history(work.id, chapter).isEmpty())
    }

    @Test
    fun finalizingAnEmptyEditorBufferAlsoPersistsTheDeletion() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveText(work.id, chapter, "这段已经被作者删掉。")

        store.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = "")

        assertEquals("", store.text(work.id, chapter))
        assertEquals(0, store.get(work.id)!!.chapter(chapter)!!.words)
        assertTrue(store.get(work.id)!!.chapter(chapter)!!.locked)
    }

    @Test
    fun currentTextCannotBypassAnAlreadyFinalizedChapter() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = "已定稿。")

        store.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = "偷偷改掉。")

        assertEquals("已定稿。", store.text(work.id, chapter))
        assertEquals(1, store.changes(work.id).size)
    }

    @Test
    fun backupRestoresAnUnacceptedOrStoppedDraftOnANewDevice() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val partial = "写到一半按了停止，作者还没有采纳。\n这一段仍然是草稿。"
        source.saveDraft(work.id, chapter, partial)
        val destination = WorkStore(tmp.newFolder())

        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        assertEquals(partial, destination.draft(work.id, chapter))
        assertEquals("", destination.text(work.id, chapter))
        assertEquals(0, destination.get(work.id)!!.chapter(chapter)!!.words)
    }

    @Test
    fun deletingADraftCreatesANewerBackupAndClearsAnOlderLocalDraft() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "后来被作者撤回的草稿。")
        val before = source.get(work.id)!!.updatedAt
        val destination = WorkStore(tmp.newFolder())
        destination.restoreBackup(String(source.backup()!!))

        source.saveDraft(work.id, chapter, "")
        assertTrue(source.get(work.id)!!.updatedAt > before)
        val cleared = JSONObject(String(source.backup()!!))
        assertEquals("", cleared.getJSONArray("works").getJSONObject(0).getJSONObject("drafts").getString(chapter))
        assertEquals(1, destination.restoreBackup(cleared.toString()))
        assertEquals("", destination.draft(work.id, chapter))
    }

    @Test
    fun legacyBackupWithoutDraftsPreservesTheLocalDraft() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val archive = JSONObject(String(source.backup()!!))
        val entry = archive.getJSONArray("works").getJSONObject(0)
        entry.remove("drafts")
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals("", destination.draft(work.id, chapter))
        destination.saveDraft(work.id, chapter, "本机未采纳的草稿。")
        // 旧版备份不认识草稿：即使恢复了更新的正文，也不能把本机草稿当成删除。
        entry.put("updatedAt", destination.get(work.id)!!.updatedAt + 100)

        assertEquals(1, destination.restoreBackup(archive.toString()))

        assertEquals("本机未采纳的草稿。", destination.draft(work.id, chapter))
    }

    @Test
    fun malformedDraftsRejectTheArchiveBeforeAnyWorkChanges() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "完整草稿。")
        val good = String(source.backup()!!)
        val destination = WorkStore(tmp.newFolder())
        destination.restoreBackup(good)
        val invalidType = JSONObject(good)
        invalidType.getJSONArray("works").getJSONObject(0).getJSONObject("drafts").put(chapter, 42)
        val invalidId = JSONObject(good)
        invalidId.getJSONArray("works").getJSONObject(0).getJSONObject("drafts").put("unknown-chapter", "越界草稿")

        for (bad in listOf(invalidType, invalidId)) {
            assertTrue(runCatching { destination.validateBackup(bad.toString()) }.isFailure)
            assertTrue(runCatching { destination.restoreBackup(bad.toString()) }.isFailure)
            assertEquals("完整草稿。", destination.draft(work.id, chapter))
        }
    }

    @Test
    fun unchangedDraftDoesNotAdvanceTheWorkRevision() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        store.saveDraft(work.id, chapter, "未采纳草稿。")
        val revision = store.get(work.id)!!.updatedAt

        store.saveDraft(work.id, chapter, "未采纳草稿。")

        assertEquals(revision, store.get(work.id)!!.updatedAt)
    }

    @Test
    fun reviewRequirementSurvivesRestartAndBackupWithoutPersistingAPass() {
        val root = tmp.newFolder()
        val source = WorkStore(root)
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "还没有通过审读的项目稿。")
        source.setDraftWorkflow(work.id, chapter, required = true)

        assertEquals(setOf(chapter), WorkStore(root).assist(work.id).draftWorkflows)
        val archive = JSONObject(String(source.backup()!!))
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(archive.toString()))
        assertEquals(setOf(chapter), destination.assist(work.id).draftWorkflows)
        assertEquals("还没有通过审读的项目稿。", destination.draft(work.id, chapter))
        assertFalse(archive.getJSONArray("works").getJSONObject(0).getJSONObject("assist").has("assessment"))

        source.setDraftWorkflow(work.id, chapter, required = false)
        assertTrue(source.assist(work.id).draftWorkflows.isEmpty())
        assertEquals("还没有通过审读的项目稿。", source.draft(work.id, chapter))
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        assertTrue(destination.assist(work.id).draftWorkflows.isEmpty())
    }

    @Test
    fun legacyAssistAndBackupWithoutReviewRequirementRemainReadable() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveDraft(work.id, chapter, "旧版本保存的草稿。")
        source.setDraftWorkflow(work.id, chapter, required = true)
        val archive = JSONObject(String(source.backup()!!))
        archive.getJSONArray("works").getJSONObject(0).getJSONObject("assist").remove("draftWorkflows")
        val destination = WorkStore(tmp.newFolder())

        assertEquals(1, destination.restoreBackup(archive.toString()))

        assertTrue(destination.assist(work.id).draftWorkflows.isEmpty())
        assertEquals("旧版本保存的草稿。", destination.draft(work.id, chapter))
    }
}
