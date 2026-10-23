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
        assertEquals("灵阅备份-20261023-2140.zip", name)
        assertEquals(listOf(name), dir.list()!!.toList())
        assertEquals("同步盘", backups.state().label)
        assertFalse(backups.due())

        clock += AutoBackup.DAY
        assertTrue(backups.due())
    }

    @Test
    fun keepsTheNewestSevenAndCleansUpHalfWrittenFiles() {
        backups.setFolder("folder", "同步盘")
        (1..9).forEach { File(dir, "灵阅备份-202610%02d-0800.zip".format(it)).writeText("old") }
        File(dir, "灵阅备份-20261001-0800.zip.part").writeText("broken")
        File(dir, "别的文件.txt").writeText("keep")

        backups.run { it.write("new".toByteArray()) }.getOrThrow()
        val left = dir.list()!!.sorted()
        assertEquals(8, left.size)
        assertTrue("别的文件.txt" in left)
        assertTrue("灵阅备份-20261023-2140.zip" in left)
        // 最旧的三份没了（新的一份加上留下的六份旧的，正好七份）。
        assertFalse("灵阅备份-20261003-0800.zip" in left)
        assertTrue("灵阅备份-20261004-0800.zip" in left)
        assertTrue(left.none { it.endsWith(".part") })
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
    fun turningItOffForgetsTheFolder() {
        backups.setFolder("folder", "同步盘")
        backups.setFolder(null, "")
        assertFalse(backups.state().enabled)
        assertFalse(backups.due())
        assertTrue(backups.run { }.isFailure)
    }
}
