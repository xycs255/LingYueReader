package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 故事账本：改动一批写进去、按章取快照、拼给 AI 的文字、存盘、历史版本、备份恢复。 */
class StoryLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun state(who: String, aspect: String, text: String, from: Int?, until: Int? = null, id: String = "$who$aspect$from") =
        LedgerEntry(id = id, kind = LedgerKind.STATE, text = text, subject = who, aspect = aspect, from = from, until = until)

    private fun know(who: String, text: String, from: Int?, volume: Int? = null, via: String = "") =
        LedgerEntry(id = "k$who$text", kind = LedgerKind.KNOW, text = text, subject = who, from = from, volume = volume, via = via)

    private fun promise(text: String, from: Int, payoff: Int? = null, done: Boolean = false, closed: Int? = null) =
        LedgerEntry(id = "p$text", kind = LedgerKind.PROMISE, text = text, from = from, payoff = payoff, done = done, closed = closed)

    @Test
    fun addSkipsDuplicatesUpdateAndCloseAndRemove() {
        val a = state("林月", "位置", "在渡口", 3)
        val (one, added) = StoryLedger.apply(emptyList(), listOf(LedgerOp.Add(a), LedgerOp.Add(a.copy(id = "x", text = " 在渡口 "))), 10)
        assertEquals(1, added)
        assertEquals(10, one.single().updatedAt)
        val p = promise("顾长风说三日后再来", 8, payoff = 11)
        val (two, _) = StoryLedger.apply(one, listOf(LedgerOp.Add(p), LedgerOp.Update(a.copy(text = "在药铺")), LedgerOp.Close(p.id, 11, "他果然来了")), 20)
        assertEquals("在药铺", two.first { it.id == a.id }.text)
        val closed = two.first { it.id == p.id }
        assertTrue(closed.done)
        assertEquals(11, closed.closed)
        assertEquals("他果然来了", closed.quote)
        val (three, _) = StoryLedger.apply(two, listOf(LedgerOp.Remove(a.id), LedgerOp.Close("不存在", 3)), 30)
        assertEquals(listOf(p.id), three.map { it.id })
    }

    @Test
    fun aStateCanReturnToAnEarlierValueInALaterChapter() {
        val (entries, added) = StoryLedger.apply(
            emptyList(),
            listOf(LedgerOp.Add(state("林月", "位置", "在渡口", 3)), LedgerOp.Add(state("林月", "位置", "在京城", 6)), LedgerOp.Add(state("林月", "位置", "在渡口", 9))),
            1,
        )
        assertEquals(3, added)
        assertEquals("在渡口", StoryLedger.snapshot(entries, 9).states.single().text)
        assertEquals("在京城", StoryLedger.snapshot(entries, 8).states.single().text)
    }

    @Test
    fun stateTakesTheLatestEntryPerPersonAndAspect() {
        val entries = listOf(
            state("林月", "位置", "在药铺", 1),
            state("林月", "位置", "在渡口", 12),
            state("林月", "身体", "左臂受伤", 10, until = 15),
            state("沈默", "位置", "在京城", 2),
        )
        val at12 = StoryLedger.snapshot(entries, 12)
        assertEquals(listOf("在渡口", "左臂受伤", "在京城"), at12.states.map { it.text })
        val at11 = StoryLedger.snapshot(entries, 11)
        assertEquals("在药铺", at11.states.first { it.subject == "林月" && it.aspect == "位置" }.text)
        // 第 15 章伤好了；只看出场的林月。
        val at15 = StoryLedger.snapshot(entries, 15, present = listOf("林月"))
        assertEquals(listOf("在渡口"), at15.states.map { it.text })
    }

    @Test
    fun knowledgeSplitsIntoKnownAndNotYetAndVolumesCount() {
        val entries = listOf(
            know("沈默", "杜娘是卧底", 15, via = "偷听"),
            know("林月", "老周死了", 5),
            know("林月", "渡口有暗道", null, volume = 2),
        )
        val s = StoryLedger.snapshot(entries, 12, volume = 1)
        assertEquals(listOf("老周死了"), s.known.map { it.text })
        assertEquals(listOf("杜娘是卧底", "渡口有暗道"), s.notYet.map { it.text })
        assertTrue(StoryLedger.snapshot(entries, 12, volume = 2).known.any { it.text == "渡口有暗道" })
    }

    @Test
    fun promisesOpenDueOverdueAndClosedLater() {
        val entries = listOf(
            promise("三日后再来", 8, payoff = 11),
            promise("老周的信", 3, payoff = 12),
            promise("暗道", 4, done = true, closed = 14),
            promise("早收的", 2, done = true, closed = null),
            promise("还没埋", 20),
        )
        val s = StoryLedger.snapshot(entries, 12)
        assertEquals(listOf("老周的信", "暗道", "三日后再来"), s.open.map { it.text })
        assertEquals(listOf("老周的信"), s.due.map { it.text })
        assertEquals(listOf("三日后再来"), s.overdue.map { it.text })
        assertFalse(StoryLedger.snapshot(entries, 14).open.any { it.text == "暗道" })
    }

    @Test
    fun timeUsesThisChapterOrTheLatestBefore() {
        val t = { text: String, from: Int -> LedgerEntry(id = text, kind = LedgerKind.TIME, text = text, from = from) }
        val entries = listOf(t("腊月初三", 10), t("腊月初三夜里", 12))
        assertEquals(listOf("腊月初三"), StoryLedger.snapshot(entries, 11).time.map { it.text })
        assertEquals(listOf("腊月初三夜里"), StoryLedger.snapshot(entries, 12).time.map { it.text })
    }

    @Test
    fun renderPutsTimeAndStateFirstAndRespectsTheLimit() {
        val entries = listOf(
            LedgerEntry(id = "t", kind = LedgerKind.TIME, text = "腊月初三", from = 12),
            state("林月", "位置", "在渡口", 12),
            promise("老周的信", 3, payoff = 12),
            LedgerEntry(id = "s", kind = LedgerKind.EVENT, text = "杜娘其实是卧底", secret = true, from = 1),
        ) + (1..80).map { LedgerEntry(id = "e$it", kind = LedgerKind.EVENT, text = "第 $it 件事" + "很长".repeat(20), from = 1) }
        val text = StoryLedger.snapshot(entries, 12).render(2_000)!!
        assertTrue(text.length <= 2_100)
        assertTrue(text.indexOf("腊月初三") < text.indexOf("林月·位置：在渡口"))
        assertTrue(text.indexOf("林月·位置") < text.indexOf("计划在这一章收的伏笔"))
        assertTrue("放不下" in text)
        // 只有作者知道的不进“发生的事”。
        assertFalse(StoryLedger.snapshot(entries, 12).events.any { it.secret })
        assertNull(StoryLedger.snapshot(emptyList(), 3).render())
    }

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val e = LedgerEntry(
            id = "1", kind = LedgerKind.PROMISE, text = "老周的信", subject = "林月", aspect = "随身", from = 3, volume = 1, until = 9,
            payoff = 12, done = true, closed = 12, via = "杜娘", secret = true, quote = "信还在怀里", chapterId = "c3", origin = LedgerOrigin.SYNC, updatedAt = 5,
        )
        assertEquals(listOf(e), StoryLedger.parse(StoryLedger.toJson(listOf(e)).toString()))
        assertNull(LedgerEntry.fromJson(JSONObject().put("kind", "STATE").put("text", " ")))
    }

    @Test
    fun aMalformedEntryRejectsTheWholeLedgerAndTheOriginalSurvivesARejectedEdit() {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("渡口")
        val original = StoryLedger.toJson(listOf(state("林月", "位置", "在渡口", 1)))
            .apply { getJSONArray("entries").put(JSONObject().put("kind", "FUTURE_KIND").put("text", "不可丢失")) }
            .toString()
        val file = java.io.File(root, "works/${work.id}/ledger.json")
        file.writeText(original)

        assertTrue(runCatching { StoryLedger.parse(original) }.isFailure)
        assertTrue(store.ledger(work.id).isEmpty())
        val damaged = java.io.File(file.parentFile, "ledger.json.damaged")
        assertEquals(original, damaged.readText())

        assertTrue(runCatching {
            store.applyLedger(work.id, listOf(LedgerOp.Add(state("沈默", "位置", "在京城", 2))), "新状态")
        }.isFailure)
        assertEquals(original, file.readText())
        assertEquals(original, damaged.readText())
    }

    @Test
    fun nonObjectBlankOrUnknownFieldEntriesAreNotDropped() {
        val good = state("林月", "位置", "在渡口", 1).toJson()
        val badEntries = listOf(
            JSONObject.NULL,
            "不是条目",
            JSONObject().put("kind", "EVENT").put("text", " "),
            JSONObject().put("kind", "EVENT").put("text", "有效").put("futureField", "需保留"),
            JSONObject().put("kind", "EVENT").put("text", "有效").put("origin", "FUTURE_ORIGIN"),
        )
        badEntries.forEach { bad ->
            val raw = JSONObject().put("entries", JSONArray().put(good).put(bad)).toString()
            assertTrue("bad=$bad", runCatching { StoryLedger.parse(raw) }.isFailure)
        }
        assertTrue(runCatching { StoryLedger.parse(JSONObject().put("format", 2).put("entries", JSONArray().put(good)).toString()) }.isFailure)
    }

    @Test
    fun presentInvalidNumericBoundariesRejectTheEntryAndTheWholeLedger() {
        val boundaries = listOf("from", "volume", "until", "payoff", "closed")
        val invalid = listOf(-1, "not-a-number", 1.5, Int.MAX_VALUE.toLong() + 1)
        boundaries.forEach { key ->
            invalid.forEach { value ->
                val entry = LedgerEntry(id = "a", kind = LedgerKind.EVENT, text = "渡口失守").toJson().put(key, value)
                assertTrue("$key=$value", runCatching { LedgerEntry.fromJson(entry) }.isFailure)
                val raw = JSONObject().put("entries", JSONArray().put(entry)).toString()
                assertTrue("$key=$value", runCatching { StoryLedger.parse(raw) }.isFailure)
            }
        }
        val badTime = LedgerEntry(id = "a", kind = LedgerKind.EVENT, text = "渡口失守").toJson().put("updatedAt", "not-a-time")
        assertTrue(runCatching { LedgerEntry.fromJson(badTime) }.isFailure)
    }

    @Test
    fun legacyEntriesMayOmitOptionalFieldsAndUseExactNumericStrings() {
        val legacy = JSONObject().put("kind", "EVENT").put("text", "渡口失守")
        val entry = LedgerEntry.fromJson(legacy)!!
        assertEquals(LedgerOrigin.MANUAL, entry.origin)
        assertNull(entry.from)
        assertNull(entry.closed)
        assertFalse(entry.done)
        assertEquals(0, entry.updatedAt)
        assertEquals(1, StoryLedger.parse(JSONObject().put("entries", JSONArray().put(legacy)).toString()).size)

        val numericString = legacy.put("from", "12").put("updatedAt", "42")
        assertEquals(12, LedgerEntry.fromJson(numericString)?.from)
        assertEquals(42L, LedgerEntry.fromJson(numericString)?.updatedAt)
    }

    @Test
    fun storeKeepsHistoryAndRestores() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡口")
        assertTrue(s.ledger(w.id).isEmpty())
        assertEquals(1, s.applyLedger(w.id, listOf(LedgerOp.Add(state("林月", "位置", "在渡口", 1))), "导入"))
        assertTrue(s.ledgerVersions(w.id).isEmpty())
        val id = s.ledger(w.id).single().id
        s.applyLedger(w.id, listOf(LedgerOp.Remove(id)), "删掉一条")
        assertTrue(s.ledger(w.id).isEmpty())
        val v = s.ledgerVersions(w.id).single()
        assertEquals("删掉一条", v.label)
        assertEquals(1, v.count)
        assertTrue(s.restoreLedger(w.id, v.at))
        assertEquals("在渡口", s.ledger(w.id).single().text)
        assertEquals(2, s.ledgerVersions(w.id).size)
        // 什么都没改的不存历史。
        assertEquals(0, s.applyLedger(w.id, listOf(LedgerOp.Remove("不存在")), "没改"))
        assertEquals(2, s.ledgerVersions(w.id).size)
    }

    @Test
    fun damagedLedgerIsKeptAndReadAsEmpty() {
        val root = tmp.newFolder()
        val s = WorkStore(root)
        val w = s.create("渡口")
        val file = java.io.File(root, "works/${w.id}/ledger.json")
        file.writeText("{坏了")
        assertTrue(s.ledger(w.id).isEmpty())
        assertTrue(java.io.File(file.parentFile, "ledger.json.damaged").isFile)
    }

    @Test
    fun backupCarriesTheLedger() {
        val a = WorkStore(tmp.newFolder())
        val w = a.create("渡口")
        a.applyLedger(w.id, listOf(LedgerOp.Add(know("沈默", "杜娘是卧底", 15)), LedgerOp.Add(promise("老周的信", 3, payoff = 12))), "导入")
        val b = WorkStore(tmp.newFolder())
        assertEquals(1, b.restoreBackup(String(a.backup()!!)))
        assertEquals(a.ledger(w.id), b.ledger(w.id))
        assertNotNull(b.ledger(w.id).firstOrNull { it.kind == LedgerKind.PROMISE })
    }

    @Test
    fun damagedBackupLedgerNeverReplacesExistingLedger() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("渡口")
        store.applyLedger(work.id, listOf(LedgerOp.Add(state("林月", "位置", "在渡口", 1))), "原状态")
        val before = store.ledger(work.id)
        val backup = JSONObject(String(store.backup()!!))
        val entries = backup.getJSONArray("works").getJSONObject(0).getJSONArray("ledger")
        entries.put(JSONObject().put("kind", "FUTURE_KIND").put("text", "不能丢失"))
        assertTrue(runCatching { store.restoreBackup(backup.toString()) }.isFailure)
        assertEquals(before, store.ledger(work.id))

        entries.remove(entries.length() - 1)
        entries.getJSONObject(0).put("from", "not-a-chapter")
        assertTrue(runCatching { store.restoreBackup(backup.toString()) }.isFailure)
        assertEquals(before, store.ledger(work.id))
    }
}
