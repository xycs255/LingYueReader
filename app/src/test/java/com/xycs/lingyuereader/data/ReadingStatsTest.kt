package com.xycs.lingyuereader.data

import android.util.AtomicFile
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
import java.io.FileOutputStream
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

    @Test
    @Config(sdk = [26, 28, 29, 36])
    fun readFailureAfterCommitNeverRollsBackReadingStats() {
        val file = File(tmp.root, "s.json")
        ReadingStats(file).addTime("b1", 60_000, today)
        val original = file.readBytes()
        val injected = object : AtomicFile(file) {
            var committed = false
            var failWrites = 0

            override fun finishWrite(str: FileOutputStream?) {
                super.finishWrite(str)
                committed = true
            }

            override fun readFully(): ByteArray {
                if (committed) {
                    committed = false
                    throw IOException("injected readFully after finishWrite")
                }
                return super.readFully()
            }

            override fun failWrite(str: FileOutputStream?) {
                failWrites++
                super.failWrite(str)
            }
        }

        val error = runCatching { ReadingStats(file, injected).addChars(100, today) }.exceptionOrNull()

        assertTrue(error is IOException && error.message == "injected readFully after finishWrite")
        assertEquals(0, injected.failWrites)
        assertTrue(file.isFile)
        assertTrue(!original.contentEquals(file.readBytes()))
        val saved = ReadingStats(file).days().getValue(today)
        assertEquals(60_000L, saved.millis)
        assertEquals(100L, saved.chars)
    }

    @Test
    @Config(sdk = [26, 28, 29, 36])
    fun readFailureBeforeUpdatePreservesReadingStatsInsteadOfWritingEmptyMap() {
        val file = File(tmp.root, "s.json")
        ReadingStats(file).addTime("b1", 60_000, today)
        val original = file.readBytes()
        val injected = object : AtomicFile(file) {
            var failRead = true
            var startedWrites = 0

            override fun readFully(): ByteArray {
                if (failRead) throw IOException("injected readFully before update")
                return super.readFully()
            }

            override fun startWrite(): FileOutputStream {
                startedWrites++
                return super.startWrite()
            }
        }
        val stats = ReadingStats(file, injected)

        assertTrue(runCatching { stats.days() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { stats.addChars(100, today) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, injected.startedWrites)
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, File(tmp.root, "s.json.damaged").readBytes())

        injected.failRead = false
        stats.addChars(100, today)
        assertEquals(60_000L, stats.days().getValue(today).millis)
        assertEquals(100L, stats.days().getValue(today).chars)
    }

    @Test
    fun malformedExistingDaysCannotBeSilentlyReplacedByNextUpdate() {
        val file = File(tmp.root, "s.json")
        file.writeText("""{"days":[]}""")
        val original = file.readBytes()
        val stats = ReadingStats(file)

        assertTrue(runCatching { stats.addChars(100, today) }.exceptionOrNull() is IllegalStateException)
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, File(tmp.root, "s.json.damaged").readBytes())
    }

    @Test
    fun invalidExistingNumbersCannotBeCoercedToZeroAndOverwritten() {
        val badValues = listOf(
            """{"days":{"2026-10-23":{"ms":"bad","chars":8}}}""",
            """{"days":{"2026-10-23":{"ms":1.5,"chars":8}}}""",
            """{"days":{"2026-10-23":{"ms":8,"chars":-1}}}""",
            """{"days":{"2026-10-23":{"ms":8,"books":{"b1":"bad"}}}}""",
            """{"days":{"2026-10-23":{"ms":8,"books":{"b1":-1}}}}""",
        )
        badValues.forEachIndexed { i, json ->
            val file = File(tmp.root, "bad-$i.json").apply { writeText(json) }
            val original = file.readBytes()

            assertTrue(runCatching { ReadingStats(file).addChars(100, today) }.exceptionOrNull() is IllegalStateException)
            assertArrayEquals(original, file.readBytes())
            assertArrayEquals(original, File(tmp.root, "bad-$i.json.damaged").readBytes())
        }
    }

    @Test
    fun missingNumericFieldsInOlderStatsStillDefaultToZero() {
        val file = File(tmp.root, "legacy.json").apply { writeText("""{"days":{"2026-10-23":{}}}""") }
        val stats = ReadingStats(file)
        stats.addChars(100, today)
        assertEquals(DayStats(chars = 100), stats.days().getValue(today))
    }

    @Test
    fun backupValidationRejectsBadNumbersWithoutTouchingLocalStats() {
        val file = File(tmp.root, "s.json")
        val stats = ReadingStats(file)
        stats.addChars(50, today)
        val original = file.readBytes()

        assertTrue(runCatching { stats.validateBackup("""{"days":{"2026-10-23":{"chars":"bad"}}}""") }.isFailure)
        assertArrayEquals(original, file.readBytes())
        stats.validateBackup("""{"days":{"2026-10-23":{}}}""")
        assertArrayEquals(original, file.readBytes())
    }

    /** 本机统计坏了：从备份恢复直接采用备份里的数，坏文件先另存；之后照常记录。 */
    @Test
    fun mergeReplacesADamagedLocalFileWithTheBackupAndKeepsTheDamagedCopy() {
        val file = File(tmp.root, "s.json")
        file.writeText("""{"days":[]}""")
        val damaged = file.readBytes()
        val stats = ReadingStats(file)
        assertTrue(runCatching { stats.days() }.isFailure)

        stats.merge("""{"days":{"2026-10-23":{"ms":120000,"chars":800,"books":{"b1":120000}}}}""")

        assertEquals(DayStats(120_000, 800, mapOf("b1" to 120_000L)), stats.days().getValue(today))
        assertArrayEquals(damaged, File(tmp.root, "s.json.damaged").readBytes())
        stats.addChars(100, today)
        assertEquals(900L, stats.days().getValue(today).chars)
    }

    /** 早先已另存过一份不同的坏文件：这次的坏内容另存成带时间的副本，不覆盖原来那份。 */
    @Test
    fun mergeKeepsEveryDamagedVersion() {
        val file = File(tmp.root, "s.json")
        File(tmp.root, "s.json.damaged").writeText("更早的坏文件")
        file.writeText("""{"days":{"2026-10-23":{"ms":"bad"}}}""")
        val damaged = file.readBytes()

        ReadingStats(file).merge("""{"days":{"2026-10-23":{"ms":60000}}}""")

        assertEquals("更早的坏文件", File(tmp.root, "s.json.damaged").readText())
        val dated = tmp.root.listFiles().orEmpty().single { it.name.startsWith("s.json.damaged-") }
        assertArrayEquals(damaged, dated.readBytes())
        assertEquals(60_000L, ReadingStats(file).days().getValue(today).millis)
    }

    /** 读盘本身失败（不是内容坏）时不能当成坏文件覆盖；坏的备份也不碰本机文件。 */
    @Test
    fun mergeDoesNotOverwriteWhenTheLocalFileCannotBeReadOrTheBackupIsBad() {
        val file = File(tmp.root, "s.json")
        ReadingStats(file).addTime("b1", 60_000L, today)
        val original = file.readBytes()
        val injected = object : AtomicFile(file) {
            override fun readFully(): ByteArray = throw IOException("injected read failure")
        }

        assertTrue(runCatching { ReadingStats(file, injected).merge("""{"days":{}}""") }.exceptionOrNull() is IOException)
        assertArrayEquals(original, file.readBytes())
        assertTrue(runCatching { ReadingStats(file).merge("""{"days":[]}""") }.isFailure)
        assertArrayEquals(original, file.readBytes())
    }
}
