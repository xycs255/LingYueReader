package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRevisionSnapshotTest {
    @Test fun snapshotSurvivesAChangedCurrentCandidate() {
        val initial = "林月推门。\n院内无人。"
        val revised = "林月轻推门。\n院内有人应声。"
        val saved = DraftRevisionCodec.encode(revised, initial).toString()
        assertEquals(initial, DraftRevisionCodec.decode(saved, revised).initialText)
        assertTrue(runCatching { DraftRevisionCodec.decode(saved, initial) }.isFailure)
    }

    @Test fun snapshotRejectsChangedFirstDraftAndInvalidStructure() {
        val saved = DraftRevisionCodec.encode("现稿", "初稿")
        assertTrue(runCatching { DraftRevisionCodec.decode(JSONObject(saved.toString()).put("initialText", "伪造初稿").toString(), "现稿") }.isFailure)
        assertTrue(runCatching { DraftRevisionCodec.decode(JSONObject(saved.toString()).put("version", 2).toString(), "现稿") }.isFailure)
        assertTrue(runCatching { DraftRevisionCodec.decode("{bad-json", "现稿") }.isFailure)
    }

    @Test fun restoredFirstDraftKeepsRevisedProseAsAnAuthenticatedCounterpart() {
        val initial = "原稿一。\n原稿二。"
        val revised = "修订一。\n修订二。"
        val snapshot = DraftRevisionCodec.encode(initial, initial, revised).toString()
        assertEquals(DraftRevisionSnapshot(initial, revised), DraftRevisionCodec.decode(snapshot, initial))
        assertTrue(runCatching { DraftRevisionCodec.decode(snapshot, revised) }.isFailure)
        val damaged = JSONObject(snapshot).put("alternateText", "受损的修订稿")
        assertTrue(runCatching { DraftRevisionCodec.decode(damaged.toString(), initial) }.isFailure)
    }

    @Test fun oversizedFirstDraftCannotEnterAutomaticRevision() {
        val huge = "写".repeat(DraftRevisionCodec.MAX_BYTES / 3 + 1)
        assertTrue(runCatching { DraftRevisionCodec.encode("现稿", huge) }.isFailure)
    }
}
