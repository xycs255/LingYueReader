package com.xycs.lingyuereader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 闪退记录：留最近 3 次、同样的意外只数次数、不带正文、下次打开问一次。 */
class CrashLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        CrashLog.use(tmp.newFile("crash-log.json").also { it.delete() })
        CrashLog.screen = "Editor"
    }

    @After
    fun tearDown() {
        CrashLog.use(null)
        CrashLog.screen = ""
    }

    private fun crash(n: Int, at: Long) = CrashLogFile(tmp.root.resolve("crash-log.json"))
        .add(CrashLog.entry(IllegalStateException("第 $n 次"), Thread.currentThread(), fatal = true, at = at))

    @Test
    fun keepsTheLastThreeCrashesNewestFirst() {
        (1..5).forEach { crash(it, at = it * 1_000L) }
        val list = CrashLog.entries()
        assertEquals(3, list.size)
        assertEquals(listOf(5_000L, 4_000L, 3_000L), list.map { it.at })
        assertTrue(list.all { it.fatal && it.screen == "Editor" && it.trace.startsWith("java.lang.IllegalStateException") })
        // 堆栈里有出错的代码位置。
        assertTrue(list.first().trace.contains("\tat com.xycs.lingyuereader.CrashLogTest"))
    }

    @Test
    fun theSameCaughtErrorIsCountedNotRepeatedAndNeverPushesOutCrashes() {
        crash(1, at = 1_000L)
        val e = IndexOutOfBoundsException("Index 9 out of bounds for length 3")
        repeat(4) { CrashLog.caught(e) }
        CrashLog.caught(OutOfMemoryError("Failed to allocate a 12345 byte allocation"))
        val list = CrashLog.entries()
        assertEquals(1, list.count { it.fatal })
        val caught = list.filter { !it.fatal }
        assertEquals(2, caught.size)
        assertEquals(4, caught.single { "IndexOutOfBounds" in it.trace }.count)
        assertTrue(CrashLog.report(caught.single { "IndexOutOfBounds" in it.trace }).contains("次数：4"))
    }

    @Test
    fun theRecordNeverCarriesProse() {
        val prose = "林月把两半药方叠在一起，揣进怀里。沈默看着她"
        CrashLog.caught(IllegalArgumentException("Value $prose of type java.lang.String cannot be converted", RuntimeException("「$prose」")))
        val all = CrashLog.entries().joinToString { CrashLog.report(it) }
        assertFalse(all.contains("林月"))
        assertFalse(all.contains("药方"))
        assertTrue(all, all.contains("Value 〔"))
        assertTrue(all.contains("of type java.lang.String cannot be converted"))
        assertTrue(all.contains("Caused by: java.lang.RuntimeException"))
    }

    /** 英文正文、拼音、整段 JSON 也不进记录：只留数字、标点、类名和常见的报错用语。 */
    @Test
    fun englishAndJsonFragmentsAreHiddenToo() {
        CrashLog.caught(org.json.JSONException("Value {\"title\":\"Moonlit Ferry\",\"text\":\"Lin Yue folded the two halves\"} of type org.json.JSONObject cannot be converted to JSONArray"))
        CrashLog.caught(NumberFormatException("For input string: \"Shen Mo waited by the river\""))
        val all = CrashLog.entries().joinToString { CrashLog.report(it) }
        listOf("Moonlit", "Ferry", "Lin Yue", "folded", "halves", "Shen", "river", "waited").forEach { assertFalse(it, all.contains(it)) }
        assertTrue(all, all.contains("of type org.json.JSONObject cannot be converted to JSONArray"))
        assertTrue(all, all.contains("For input string: \"〔"))
        // 常见的报错照旧看得懂。
        assertEquals("Index 9 out of bounds for length 3", CrashLog.mask("Index 9 out of bounds for length 3"))
        assertEquals("Failed to allocate a 12345 byte allocation", CrashLog.mask("Failed to allocate a 12345 byte allocation"))
    }

    @Test
    fun askOnceAfterACrash() {
        assertNull(CrashLog.unseen())
        CrashLog.caught(NullPointerException())
        // 接住的意外不弹。
        assertNull(CrashLog.unseen())
        crash(1, at = 1_000L)
        crash(2, at = 2_000L)
        val unseen = CrashLog.unseen()
        assertNotNull(unseen)
        assertEquals(2_000L, unseen!!.at)
        val report = CrashLog.report(unseen)
        assertTrue(report.startsWith("灵阅闪退记录\n时间："))
        assertTrue(report.contains("界面：Editor"))
        CrashLog.markSeen()
        assertNull(CrashLog.unseen())
        assertEquals(3, CrashLog.entries().size)
        CrashLog.clear()
        assertTrue(CrashLog.entries().isEmpty())
    }

    @Test
    fun saysWhatTheAiWasDoing() {
        CrashLog.busy = { "AI 在写整章草稿" }
        try {
            CrashLog.caught(StackOverflowError())
            assertEquals("Editor（AI 在写整章草稿）", CrashLog.entries().single().screen)
        } finally {
            CrashLog.busy = { "" }
        }
    }

    @Test
    fun programBugsAreTellApartFromOrdinaryFailures() {
        assertTrue(CrashLog.isBug(OutOfMemoryError()))
        assertTrue(CrashLog.isBug(StackOverflowError()))
        assertTrue(CrashLog.isBug(IndexOutOfBoundsException()))
        assertTrue(CrashLog.isBug(NullPointerException()))
        assertFalse(CrashLog.isBug(java.io.IOException("断网了")))
        assertFalse(CrashLog.isBug(IllegalStateException("作品不在了")))
        assertFalse(CrashLog.isBug(com.xycs.lingyuereader.ai.AiException("服务商报错")))
    }
}
