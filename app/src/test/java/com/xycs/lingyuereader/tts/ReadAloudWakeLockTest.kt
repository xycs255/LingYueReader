package com.xycs.lingyuereader.tts

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 锁屏听书：AI、离线语音合成下一句时 CPU 不能睡下；暂停、停止就放开，不白耗电。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadAloudWakeLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val book = BookContent(null, null, listOf(Chapter("第一章", listOf(Block.Para("杜娘点灯。老周添茶。")))))

    @Before
    fun setUp() = ReadAloud.resetForTest()

    @After
    fun tearDown() = ReadAloud.resetForTest()

    @Test
    fun wakeLockIsHeldWhileSynthesizingAndReleasedOnPauseAndStop() {
        val asked = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val voice = AiVoice(
            "离线语音", listOf(VoiceOption("0", "女声")), "0", fingerprint = { "wake-test|$it" },
            fetch = { _, _ ->
                asked.countDown()
                gate.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("测试不放音")
            },
            offline = true,
        )
        ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f, ai = voice)
        // 合成在主线程的协程里排上、在后台线程里调用：等的时候要让主线程跑起来。
        val deadline = System.currentTimeMillis() + 10_000
        while (asked.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(asked.count == 0L)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertTrue("正在合成第一句：要拿着唤醒锁", lock.isHeld)
        ReadAloud.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("暂停后放开", lock.isHeld)

        ReadAloud.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lock.isHeld)
        ReadAloud.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("停止后放开", lock.isHeld)
        gate.countDown()
    }
}
