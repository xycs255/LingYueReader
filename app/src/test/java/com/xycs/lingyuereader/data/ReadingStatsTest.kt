package com.xycs.lingyuereader.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadingStatsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun recordsTimeAndCharsPerDay() {
        val s = ReadingStats(File(tmp.root, "s.json"))
        s.addTime("b1", 20 * 60_000, today)
        s.addTime("b2", 10 * 60_000, today)
        s.addChars(9_000, today)
        s.addTime("b1", 5 * 60_000, today.minusDays(1))
        val days = s.days()
        assertEquals(30 * 60_000L, days[today]!!.millis)
        assertEquals(20 * 60_000L, days[today]!!.books["b1"])
        assertEquals(9_000L, days[today]!!.chars)
        assertEquals(35 * 60_000L, ReadingStats.total(days, today.minusDays(6), today))
        assertEquals(2, ReadingStats.streak(days, today))
        // 今天还没读：从昨天算起。
        assertEquals(2, ReadingStats.streak(days, today.plusDays(1)))
        assertEquals(0, ReadingStats.streak(days, today.plusDays(3)))
    }

    @Test
    fun readingSpeedFallsBackUntilThereIsEnoughData() {
        val s = ReadingStats(File(tmp.root, "s.json"))
        s.addTime("b1", 5 * 60_000, today)
        s.addChars(3_000, today)
        assertEquals(ReadingStats.DEFAULT_CHARS_PER_MINUTE, ReadingStats.charsPerMinute(s.days(), today))
        s.addTime("b1", 15 * 60_000, today)
        s.addChars(7_000, today)
        assertEquals(500, ReadingStats.charsPerMinute(s.days(), today))
        // 只有时长没有字数的日子（比如听书）不拉低速度。
        s.addTime("b1", 60 * 60_000, today.minusDays(1))
        assertEquals(500, ReadingStats.charsPerMinute(s.days(), today))
    }

    @Test
    fun mergingTheSameBackupTwiceDoesNotDoubleCount() {
        val a = ReadingStats(File(tmp.root, "a.json"))
        a.addTime("b1", 10 * 60_000, today)
        val backup = String(a.raw()!!)
        val b = ReadingStats(File(tmp.root, "b.json"))
        b.addTime("b1", 3 * 60_000, today.minusDays(1))
        b.merge(backup)
        b.merge(backup)
        assertEquals(10 * 60_000L, b.days()[today]!!.millis)
        assertEquals(3 * 60_000L, b.days()[today.minusDays(1)]!!.millis)
    }

    @Test
    fun failedWriteKeepsThePreviousReadingStatsBytes() {
        val file = File(tmp.root, "s.json")
        val s = ReadingStats(file)
        s.addTime("b1", 60_000, today)
        val original = file.readBytes()
        // 阻断 AtomicFile 的新文件：更新失败时旧统计仍应完整可读。
        val blocker = File(tmp.root, "s.json.new")
        assertTrue(blocker.mkdir())
        File(blocker, "keep").writeText("阻止清理")

        assertTrue(runCatching { s.addChars(100, today) }.exceptionOrNull() is IOException)
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, s.raw())
        assertEquals(60_000L, s.days()[today]!!.millis)
        assertEquals(0L, s.days()[today]!!.chars)

        assertTrue(blocker.deleteRecursively())
        s.addChars(100, today)
        assertEquals(100L, s.days()[today]!!.chars)
    }
}
