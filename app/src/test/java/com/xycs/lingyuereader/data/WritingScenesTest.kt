package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritingScenesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fingerprint = "a".repeat(64)
    private fun scene() = WritingScene("拿到钥匙", "守卫不肯放行", "同伴拿出旧信", "带着钥匙离开")
    private fun plan() = WritingScenePlan(fingerprint, listOf(scene()), instruction = "多写对白", targetLength = "约 3000 字")

    @Test fun parsesFencedSuggestionsAndRejectsMissingOrOversizedFields() {
        val suggested = WritingScenePlan.parseSuggestion(
            "```json\n{\"scenes\":[{\"goal\":\"拿到钥匙\",\"conflict\":\"守卫不肯放行\",\"turn\":\"同伴拿出旧信\",\"exit\":\"带着钥匙离开\"}]}\n```", fingerprint,
            "多写对白", "约 3000 字")
        assertEquals(listOf(scene()), suggested.selectedScenes)
        assertEquals(plan(), suggested)
        assertEquals(suggested, WritingScenePlan.fromJson(suggested.toJson()))
        val oldFormat = suggested.toJson().apply { remove("instruction"); remove("targetLength") }
        assertEquals(suggested.copy(instruction = "", targetLength = ""), WritingScenePlan.fromJson(oldFormat))
        assertTrue(runCatching { WritingScenePlan.parseSuggestion("""{"scenes":[{"goal":"拿到钥匙"}]}""", fingerprint) }.isFailure)
        assertTrue(runCatching { WritingScenePlan(fingerprint, listOf(scene().copy(goal = "字".repeat(241)))).validated() }.isFailure)
        assertTrue(runCatching { WritingScenePlan(fingerprint, List(5) { scene() }).validated() }.isFailure)
        assertTrue(runCatching { plan().copy(scenes = listOf(scene().copy(selected = false))).validated() }.isFailure)
        assertTrue(runCatching { plan().copy(instruction = "字".repeat(2_001)).validated() }.isFailure)
        assertTrue(runCatching { plan().copy(targetLength = "字".repeat(161)).validated() }.isFailure)
        assertTrue(runCatching { WritingScenePlan.fromJson(plan().toJson().put("completed", "0")) }.isFailure)
        assertTrue(runCatching { WritingScenePlan.fromJson(plan().toJson().put("version", "1")) }.isFailure)
        assertTrue(runCatching { WritingScenePlan.fromJson(plan().toJson().apply { remove("version") }) }.isFailure)
        assertTrue(runCatching {
            WritingScenePlan.fromJson(plan().toJson().apply { getJSONArray("scenes").getJSONObject(0).remove("selected") })
        }.isFailure)
    }

    @Test fun completedScenesRequireACandidateHashAndNeverMatchChangedProse() {
        val completed = plan().copy(completed = 1, draftHash = WritingScenePlan.hash("原候选稿。"))
        completed.validated()
        assertTrue(completed.matchesDraft("原候选稿。"))
        assertFalse(completed.matchesDraft("改过的候选稿。"))
        assertTrue(runCatching { completed.copy(draftHash = "").validated() }.isFailure)
        assertTrue(runCatching { completed.copy(completed = 2).validated() }.isFailure)
        assertTrue(runCatching { plan().copy(draftHash = WritingScenePlan.hash("未绑定的旧稿")).validated() }.isFailure)
    }

    @Test fun scenePlansRoundTripThroughBackupAndAreRemovedWithChapter() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val before = source.get(work.id)!!.updatedAt
        source.saveScenePlan(work.id, chapter, plan())
        assertTrue(source.get(work.id)!!.updatedAt > before)

        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        assertEquals(plan(), destination.scenePlan(work.id, chapter))

        destination.deleteChapter(work.id, chapter)
        assertEquals(null, destination.scenePlan(work.id, chapter))
    }

    @Test fun explicitDeletionInANewerBackupClearsAnOlderLocalPlan() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveScenePlan(work.id, chapter, plan())
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        source.saveScenePlan(work.id, chapter, null)
        val cleared = JSONObject(String(source.backup()!!))
        assertTrue(cleared.getJSONArray("works").getJSONObject(0).getJSONObject("scenePlans").isNull(chapter))
        assertEquals(1, destination.restoreBackup(cleared.toString()))
        assertEquals(null, destination.scenePlan(work.id, chapter))
    }

    @Test fun malformedScenePlanInBackupIsRejectedBeforeReplacingTheLocalCopy() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveScenePlan(work.id, chapter, plan())
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        val bad = JSONObject(String(source.backup()!!))
        val entry = bad.getJSONArray("works").getJSONObject(0)
        entry.put("updatedAt", destination.get(work.id)!!.updatedAt + 100)
        entry.getJSONObject("scenePlans").getJSONObject(chapter).getJSONArray("scenes").getJSONObject(0).remove("turn")

        assertTrue(runCatching { destination.validateBackup(bad.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(bad.toString()) }.isFailure)
        assertEquals(plan(), destination.scenePlan(work.id, chapter))

        val missing = JSONObject(String(source.backup()!!))
        missing.getJSONArray("works").getJSONObject(0).getJSONObject("scenePlans").remove(chapter)
        assertTrue(runCatching { destination.validateBackup(missing.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(missing.toString()) }.isFailure)
        assertEquals(plan(), destination.scenePlan(work.id, chapter))
    }

    @Test fun backupChecksCompletedScenesAgainstTheExactCandidate() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val candidate = "已写第一场，作者尚未采纳。"
        source.saveDraft(work.id, chapter, candidate)
        val progress = plan().copy(completed = 1, draftHash = WritingScenePlan.hash(candidate))
        source.saveScenePlan(work.id, chapter, progress)
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))
        assertEquals(progress, destination.scenePlan(work.id, chapter))

        val damaged = JSONObject(String(source.backup()!!))
        val entry = damaged.getJSONArray("works").getJSONObject(0)
        entry.put("updatedAt", destination.get(work.id)!!.updatedAt + 100)
        val otherDraft = "另一份候选稿"
        entry.getJSONObject("drafts").put(chapter, otherDraft)
        entry.getJSONObject("draftSources").put(chapter, DraftReferenceCodec.encode(otherDraft, null))
        assertTrue(runCatching { destination.validateBackup(damaged.toString()) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup(damaged.toString()) }.isFailure)
        assertEquals(candidate, destination.draft(work.id, chapter))
        assertEquals(progress, destination.scenePlan(work.id, chapter))
    }

    @Test fun oldBackupWithoutScenePlansPreservesTheLocalPlan() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveScenePlan(work.id, chapter, plan())
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        val oldFormat = JSONObject(String(source.backup()!!))
        val entry = oldFormat.getJSONArray("works").getJSONObject(0)
        entry.remove("scenePlans")
        entry.put("updatedAt", destination.get(work.id)!!.updatedAt + 100)
        assertEquals(1, destination.restoreBackup(oldFormat.toString()))
        assertEquals(plan(), destination.scenePlan(work.id, chapter))
    }

    @Test fun oldBackupCannotReplaceCandidateBoundToALocalCompletedScene() {
        val source = WorkStore(tmp.newFolder())
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val original = "本机第一场候选稿"
        source.saveDraft(work.id, chapter, original)
        val progress = plan().copy(completed = 1, draftHash = WritingScenePlan.hash(original))
        source.saveScenePlan(work.id, chapter, progress)
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        val oldFormat = JSONObject(String(source.backup()!!))
        val entry = oldFormat.getJSONArray("works").getJSONObject(0)
        entry.remove("scenePlans")
        entry.put("updatedAt", destination.get(work.id)!!.updatedAt + 100)
        val other = "旧备份里不同的候选稿"
        entry.getJSONObject("drafts").put(chapter, other)
        entry.getJSONObject("draftSources").put(chapter, DraftReferenceCodec.encode(other, null))
        val result = destination.restoreBackupReport(oldFormat.toString())
        assertEquals(0, result.restored)
        assertTrue(result.skipped.any { it.file == "$chapter.scene-plan.json" && it.reason.contains("候选稿") })
        assertEquals(original, destination.draft(work.id, chapter))
        assertEquals(progress, destination.scenePlan(work.id, chapter))
    }

    @Test fun mismatchedProgressSkipsTheWholeWorkButBacksUpOtherWorks() {
        val root = tmp.newFolder()
        val source = WorkStore(root)
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        val original = "第一场原稿"
        source.saveDraft(work.id, chapter, original)
        val progress = plan().copy(completed = 1, draftHash = WritingScenePlan.hash(original))
        source.saveScenePlan(work.id, chapter, progress)
        val destination = WorkStore(tmp.newFolder())
        assertEquals(1, destination.restoreBackup(String(source.backup()!!)))

        source.saveDraft(work.id, chapter, "作者更改后的候选稿")
        val good = source.create("另一部作品")
        val report = source.backupReport()!!
        assertTrue(report.skipped.any { it.work == work.title && it.reason.contains("整部作品没有放进备份") })
        val works = JSONObject(String(report.bytes)).getJSONArray("works")
        assertEquals(1, works.length())
        assertEquals(good.id, works.getJSONObject(0).getString("id"))

        val result = destination.restoreBackupReport(String(report.bytes))
        assertEquals(1, result.restored)
        assertEquals(original, destination.draft(work.id, chapter))
        assertEquals(progress, destination.scenePlan(work.id, chapter))
    }

    @Test fun unreadableSourcePlanSkipsTheWholeWorkAndKeepsItsDamagedCopy() {
        val root = tmp.newFolder()
        val source = WorkStore(root)
        val work = source.create("长夜")
        val chapter = work.chapters.single().id
        source.saveScenePlan(work.id, chapter, plan())
        val file = File(root, "works/${work.id}/$chapter.scene-plan.json")
        file.writeText("{broken")
        val good = source.create("另一部作品")

        val report = source.backupReport()!!
        assertTrue(report.skipped.any { it.work == work.title && it.file == "$chapter.scene-plan.json" && it.reason.contains("整部作品没有放进备份") })
        assertEquals(1, JSONObject(String(report.bytes)).getJSONArray("works").length())
        assertEquals(good.id, JSONObject(String(report.bytes)).getJSONArray("works").getJSONObject(0).getString("id"))
        assertEquals("{broken", File(file.path + ".damaged").readText())
    }
}
