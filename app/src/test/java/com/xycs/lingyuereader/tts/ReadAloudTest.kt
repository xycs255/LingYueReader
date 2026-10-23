package com.xycs.lingyuereader.tts

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadAloudTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val book = BookContent(
        null, null,
        listOf(
            Chapter("第一章", listOf(Block.Para("甲一。甲二。甲三。"))),
            Chapter("第二章", listOf(Block.Para("乙一。"))),
        ),
    )

    @Before
    fun setUp() {
        ReadAloud.resetForTest()
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
    }

    @After
    fun tearDown() = ReadAloud.resetForTest()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun engine(): ShadowTextToSpeech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    private fun start() = ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f)

    /** 语音引擎初始化完成：只执行引擎就绪这一步，后续的朗读回调留在队列里。 */
    private fun engineReady() {
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).runOneTask()
    }

    @Test
    fun readsWholeBookChapterByChapterThenStops() {
        start()
        engineReady()
        assertEquals(listOf("甲一。", "甲二。", "甲三。"), engine().spokenTextList)
        // 引擎逐句回调：读完第一章自动接第二章，全书读完后停止。
        idle()
        assertEquals(listOf("甲一。", "甲二。", "甲三。", "乙一。"), engine().spokenTextList)
        assertNull(ReadAloud.state.value)
    }

    @Test
    fun endOfChapterTimerStopsAfterThisChapter() {
        start()
        ReadAloud.setTimer(0)
        engineReady()
        idle()
        assertEquals(listOf("甲一。", "甲二。", "甲三。"), engine().spokenTextList)
        assertNull(ReadAloud.state.value)
    }

    @Test
    fun pauseHoldsPositionAndResumeContinuesFromCurrentSentence() {
        start()
        engineReady()
        ReadAloud.pause()
        idle() // 暂停后引擎的回调只更新位置，不会接着读下一章
        val paused = ReadAloud.state.value!!
        assertEquals(false, paused.playing)
        assertEquals(0, paused.chapterIndex)
        assertEquals("甲三。", paused.current!!.text)

        ReadAloud.resume()
        assertEquals("甲三。", engine().lastSpokenText)
        assertEquals(true, ReadAloud.state.value!!.playing)
    }

    @Test
    fun skipsBetweenSentencesAndReportsProgress() {
        start()
        engineReady()
        assertEquals(3, ReadAloud.state.value!!.sentences)
        ReadAloud.nextSentence()
        assertEquals(1, ReadAloud.state.value!!.sentence)
        assertEquals("甲二。", ReadAloud.state.value!!.current!!.text)
        // 从这一句重新排队朗读。
        assertEquals("甲三。", engine().lastSpokenText)
        ReadAloud.previousSentence()
        ReadAloud.previousSentence()
        assertEquals(0, ReadAloud.state.value!!.sentence)
        // 章末再“下一句”就到下一章。
        ReadAloud.nextSentence()
        ReadAloud.nextSentence()
        ReadAloud.nextSentence()
        assertEquals(1, ReadAloud.state.value!!.chapterIndex)
        assertEquals("本章 100%", com.xycs.lingyuereader.ui.progressLabel(ReadAloud.state.value!!))
    }

    @Test
    fun notificationActionsControlReading() {
        start()
        engineReady()
        // 先暂停：测试环境里引擎会瞬间“读完”所有句子，暂停后位置才稳定。
        ReadAloud.pause()
        idle()
        val service = org.robolectric.Robolectric.buildService(ReadAloudService::class.java).create().get()
        try {
            // 媒体会话已登记（耳机、蓝牙按键由系统分发给它；测试环境不会真的转发按键）。
            assertTrue(service.sessionToken != null)
            fun send(action: String) =
                service.onStartCommand(android.content.Intent(context, ReadAloudService::class.java).setAction(action), 0, 1)
            val at = ReadAloud.state.value!!.sentence
            send(ReadAloudService.ACTION_PREVIOUS)
            assertEquals(at - 1, ReadAloud.state.value!!.sentence)
            send(ReadAloudService.ACTION_NEXT)
            assertEquals(at, ReadAloud.state.value!!.sentence)
            send(ReadAloudService.ACTION_TOGGLE)
            assertEquals(true, ReadAloud.state.value!!.playing)
        } finally {
            service.onDestroy()
        }
    }
}
