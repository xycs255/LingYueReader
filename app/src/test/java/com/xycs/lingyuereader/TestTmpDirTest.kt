package com.xycs.lingyuereader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 测试写的文件在内存盘上（app/build.gradle.kts 把 java.io.tmpdir 指到 /dev/shm）：
 * 以前在磁盘上，别的构建大量写盘时一次 fsync、改名能卡几秒，《渡灯》这类写作流程的界面测试就在 15 秒的等待上偶发超时。
 * 没有 /dev/shm 的机器（比如 macOS）不检查。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TestTmpDirTest {
    @Test
    fun appDataOfTheTestsLivesOnTheRamDisk() {
        assumeTrue(File("/dev/shm").isDirectory && File("/dev/shm").canWrite())
        val tmp = File(System.getProperty("java.io.tmpdir")!!).canonicalPath
        assertTrue("java.io.tmpdir 应在 /dev/shm 下，实际是 $tmp", tmp.startsWith("/dev/shm/"))
        val files = ApplicationProvider.getApplicationContext<Context>().filesDir.canonicalPath
        assertTrue("Robolectric 的应用数据目录应在内存盘上，实际是 $files", files.startsWith(tmp))
        // 真能写：林月的一条笔记写进去、读回来。
        val note = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "渡灯-笔记.txt")
        note.writeText("林月在渡口等沈默。")
        assertTrue(note.readText() == "林月在渡口等沈默。")
    }
}
