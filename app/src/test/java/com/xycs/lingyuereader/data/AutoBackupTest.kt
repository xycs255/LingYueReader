package com.xycs.lingyuereader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AutoBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "sync").apply { deleteRecursively(); mkdirs() }
    private var clock = Calendar.getInstance().apply { set(2026, 9, 23, 21, 40, 0) }.timeInMillis
    private val backups = AutoBackup(context, { FileFolder(dir) }, { clock })

    @Before
    fun setUp() {
        context.getSharedPreferences("auto-backup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun backsUpOncePerDayAfterAFolderIsChosen() {
        assertFalse(backups.due())
        backups.setFolder("folder", "同步盘")
        assertTrue(backups.due())

        val name = backups.run { it.write("zip".toByteArray()) }.getOrThrow()
        assertEquals("灵阅自动备份-20261023-2140.zip", name)
        assertEquals(listOf(name), dir.list()!!.toList())
        assertEquals("同步盘", backups.state().label)
        assertFalse(backups.due())

        clock += AutoBackup.DAY
        assertTrue(backups.due())
    }

    @Test
    fun keepsTheNewestSevenWithoutDeletingAnotherDevicesPartialBackup() {
        backups.setFolder("folder", "同步盘")
        (1..9).forEach { File(dir, "灵阅自动备份-202610%02d-0800.zip".format(it)).writeText("old") }
        File(dir, "灵阅自动备份-20261001-0800.zip.other-device.part").writeText("still writing")
        File(dir, "别的文件.txt").writeText("keep")

        backups.run { it.write("new".toByteArray()) }.getOrThrow()
        val left = dir.list()!!.sorted()
        assertEquals(9, left.size)
        assertTrue("别的文件.txt" in left)
        assertTrue("灵阅自动备份-20261023-2140.zip" in left)
        // 最旧的三份没了（新的一份加上留下的六份旧的，正好七份）。
        assertFalse("灵阅自动备份-20261003-0800.zip" in left)
        assertTrue("灵阅自动备份-20261004-0800.zip" in left)
        assertTrue("灵阅自动备份-20261001-0800.zip.other-device.part" in left)
    }

    /** 手动备份默认也叫“灵阅备份-日期-时间.zip”，放在同一个文件夹里时不能被当成旧的自动备份删掉。 */
    @Test
    fun manualBackupsInTheSameFolderAreNeverCleanedUp() {
        backups.setFolder("folder", "同步盘")
        val manual = (1..9).map { "灵阅备份-202610%02d-0800.zip".format(it) }
        manual.forEach { File(dir, it).writeText("manual") }

        repeat(9) { i -> backups.run { it.write("auto-$i".toByteArray()) }.getOrThrow() }

        val left = dir.list()!!.toSet()
        assertTrue(left.containsAll(manual))
        assertEquals(AutoBackup.KEEP, left.count { AutoBackup.BACKUP_NAME.matches(it) })
        assertTrue(manual.all { File(dir, it).readText() == "manual" })
    }

    @Test
    fun aFailedBackupLeavesNothingBehindAndIsRetried() {
        backups.setFolder("folder", "同步盘")
        val result = backups.run { error("磁盘满了") }
        assertTrue(result.isFailure)
        assertEquals(0, dir.list()!!.size)
        assertEquals("磁盘满了", backups.state().lastError)
        assertTrue(backups.due())
    }

    @Test
    fun aFailedBackupCleansOnlyItsOwnPartialFile() {
        backups.setFolder("folder", "同步盘")
        val otherDevicesPart = File(dir, "灵阅备份-20261023-2140.zip.other-device.part")
        otherDevicesPart.writeText("still writing")

        val result = backups.run { stream ->
            stream.write("partial".toByteArray())
            error("磁盘满了")
        }

        assertTrue(result.isFailure)
        assertEquals(listOf(otherDevicesPart.name), dir.list()!!.toList())
        assertEquals("still writing", otherDevicesPart.readText())
    }

    @Test
    fun anotherBackupInTheSameMinuteKeepsThePreviousCopy() {
        backups.setFolder("folder", "同步盘")
        val first = backups.run { it.write("first".toByteArray()) }.getOrThrow()
        val second = backups.run { it.write("second".toByteArray()) }.getOrThrow()

        assertEquals("灵阅自动备份-20261023-2140.zip", first)
        assertEquals("灵阅自动备份-20261023-2140-0002.zip", second)
        assertEquals("first", File(dir, first).readText())
        assertEquals("second", File(dir, second).readText())
        assertEquals(second, backups.state().lastFile)
        assertTrue(dir.list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun failedRenameInTheSameMinutePreservesThePreviousBackup() {
        backups.setFolder("folder", "同步盘")
        val first = backups.run { it.write("first".toByteArray()) }.getOrThrow()
        val failing = AutoBackup(context, {
            object : BackupFolder by FileFolder(dir) {
                override fun rename(from: String, to: String): Unit = error("改不了文件名")
            }
        }, { clock })

        val result = failing.run { it.write("second".toByteArray()) }

        assertTrue(result.isFailure)
        assertEquals("first", File(dir, first).readText())
        assertEquals(listOf(first), dir.list()!!.toList())
        assertEquals(first, backups.state().lastFile)
        assertEquals("改不了文件名", backups.state().lastError)
    }

    @Test
    fun sameMinuteCopiesStillKeepOnlyTheNewestSeven() {
        backups.setFolder("folder", "同步盘")
        repeat(9) { i -> backups.run { it.write("copy-$i".toByteArray()) }.getOrThrow() }

        val kept = dir.list()!!.filter { AutoBackup.BACKUP_NAME.matches(it) }.sorted()
        assertEquals(AutoBackup.KEEP, kept.size)
        assertFalse("灵阅自动备份-20261023-2140.zip" in kept)
        assertFalse("灵阅自动备份-20261023-2140-0002.zip" in kept)
        assertTrue("灵阅自动备份-20261023-2140-0009.zip" in kept)
        assertEquals("copy-8", File(dir, kept.last()).readText())
        assertEquals(kept.last(), backups.state().lastFile)
    }

    @Test
    fun turningItOffForgetsTheFolder() {
        backups.setFolder("folder", "同步盘")
        backups.setFolder(null, "")
        assertFalse(backups.state().enabled)
        assertFalse(backups.due())
        assertTrue(backups.run { }.isFailure)
    }

    @Test
    fun leftoverPartsFromAnInterruptedBackupAreCleanedUp() {
        backups.setFolder("folder", "同步盘")
        // 上次备份到一半应用被系统停掉了，留下了临时文件。
        File(dir, "灵阅自动备份-20261022-0900.zip.123e4567-e89b-12d3-a456-426614174000.part").writeText("half")
        // 旧版自动备份留下的临时文件也清掉。
        File(dir, "灵阅备份-20261021-0900.zip.123e4567-e89b-12d3-a456-426614174001.part").writeText("half")
        File(dir, "别的文件.part").writeText("keep")
        backups.run { it.write("ok".toByteArray()) }.getOrThrow()
        assertTrue(dir.list()!!.none { it.startsWith("灵阅") && it.endsWith(".part") })
        assertTrue(File(dir, "别的文件.part").isFile)
    }
}
