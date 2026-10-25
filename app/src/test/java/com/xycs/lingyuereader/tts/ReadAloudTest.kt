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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
        val interrupted = ReadAloud.activeUtteranceId(2)
        ReadAloud.pause()
        idle() // 排队但尚未处理的旧回调不得推动阅读位置。
        ReadAloud.onUtteranceStart(interrupted)
        ReadAloud.onUtteranceDone(interrupted)
        idle()
        val paused = ReadAloud.state.value!!
        assertEquals(false, paused.playing)
        assertEquals(0, paused.chapterIndex)
        assertEquals("甲一。", paused.current!!.text)

        ReadAloud.resume()
        assertEquals(listOf("甲一。", "甲二。", "甲三。"), engine().spokenTextList.takeLast(3))
        assertEquals(true, ReadAloud.state.value!!.playing)
    }

    @Test
    fun oldCallbacksCannotMovePositionOrAdvanceChapterAfterJumpAndRateChange() {
        start() // 引擎尚未就绪，方便单独验证主线程上的回调。
        val beforeJump = ReadAloud.activeUtteranceId(2)
        ReadAloud.nextSentence()
        assertNotEquals(beforeJump, ReadAloud.activeUtteranceId(2))
        ReadAloud.onUtteranceStart(beforeJump)
        ReadAloud.onUtteranceDone(beforeJump)
        idle()
        assertEquals(0, ReadAloud.state.value!!.chapterIndex)
        assertEquals("甲二。", ReadAloud.state.value!!.current!!.text)

        val beforeRate = ReadAloud.activeUtteranceId(2)
        ReadAloud.setRate(1.5f)
        assertNotEquals(beforeRate, ReadAloud.activeUtteranceId(2))
        ReadAloud.onUtteranceStart(beforeRate)
        ReadAloud.onUtteranceDone(beforeRate)
        idle()
        assertEquals(0, ReadAloud.state.value!!.chapterIndex)
        assertEquals("甲二。", ReadAloud.state.value!!.current!!.text)
        assertEquals(1.5f, ReadAloud.state.value!!.rate)
    }

    @Test
    fun callbacksFromPreviousSessionCannotAffectSameChapter() {
        start()
        val previousSession = ReadAloud.activeUtteranceId(2)
        start()
        assertNotEquals(previousSession, ReadAloud.activeUtteranceId(2))
        ReadAloud.onUtteranceStart(previousSession)
        ReadAloud.onUtteranceDone(previousSession)
        idle()
        assertEquals(0, ReadAloud.state.value!!.chapterIndex)
        assertEquals("甲一。", ReadAloud.state.value!!.current!!.text)
    }

    @Test
    fun oldSystemEngineInitCannotStartOrChangePausedAiSession() {
        start() // 系统引擎仍在初始化，留有待启动回调。
        val oldEngine = engine()
        val requests = AtomicInteger()
        val voice = AiVoice("测试 AI", listOf(VoiceOption("v", "测试")), "v", fingerprint = { it }, fetch = { _, _ ->
            requests.incrementAndGet()
            byteArrayOf()
        })
        ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f, ai = voice, startPaused = true)
        oldEngine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(false, ReadAloud.state.value!!.playing)
        assertEquals("测试 AI", ReadAloud.state.value!!.aiLabel)
        assertEquals(listOf("v"), ReadAloud.state.value!!.voices.map { it.name })
        assertEquals(0, requests.get())
        assertTrue(oldEngine.spokenTextList.isEmpty())
    }

    @Test
    fun oldSystemEngineFailureCannotStopAiSession() {
        start()
        val oldEngine = engine()
        val voice = AiVoice("测试 AI", emptyList(), "v", fingerprint = { it }, fetch = { _, _ -> byteArrayOf() })
        ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f, ai = voice, startPaused = true)
        oldEngine.onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(false, ReadAloud.state.value!!.playing)
        assertNull(ReadAloud.state.value!!.error)
        assertEquals("测试 AI", ReadAloud.state.value!!.aiLabel)
    }

    @Test
    fun callbackFromReleasedEngineCannotStartNewEngineSession() {
        start()
        val oldEngine = engine()
        ReadAloud.resetForTest() // 模拟旧引擎已释放后建立新引擎。
        start()
        val newEngine = engine()
        assertTrue(oldEngine !== newEngine)
        oldEngine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertTrue(newEngine.spokenTextList.isEmpty())
        assertTrue(ReadAloud.state.value!!.playing)
        engineReady()
        assertEquals(listOf("甲一。", "甲二。", "甲三。"), newEngine.spokenTextList)
    }

    @Test
    fun failureFromReleasedEngineCannotClearNewPendingStart() {
        start()
        val oldEngine = engine()
        ReadAloud.resetForTest()
        start()
        val newEngine = engine()
        oldEngine.onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertNull(ReadAloud.state.value!!.error)
        engineReady()
        assertEquals(listOf("甲一。", "甲二。", "甲三。"), newEngine.spokenTextList)
    }

    @Test
    fun speechErrorStopsAtFailedSentenceAndOldCallbacksCannotAdvance() {
        start()
        val failed = ReadAloud.activeUtteranceId(1)
        ReadAloud.onUtteranceStart(failed)
        idle()
        ReadAloud.onUtteranceError(failed)
        idle()

        val stopped = ReadAloud.state.value!!
        assertEquals(false, stopped.playing)
        assertEquals(0, stopped.chapterIndex)
        assertEquals(1, stopped.sentence)
        assertEquals("甲二。", stopped.current!!.text)
        assertTrue(stopped.error!!.contains("朗读失败"))

        ReadAloud.onUtteranceDone(failed)
        ReadAloud.onUtteranceStart(failed)
        ReadAloud.onUtteranceError(failed)
        idle()
        assertEquals(stopped, ReadAloud.state.value)

        ReadAloud.resume()
        assertEquals(true, ReadAloud.state.value!!.playing)
        assertNull(ReadAloud.state.value!!.error)
        engineReady()
        assertEquals(listOf("甲二。", "甲三。"), engine().spokenTextList)
    }

    @Test
    fun engineErrorCallbackDoesNotPretendSentenceFinished() {
        start()
        engineReady()
        val failed = ReadAloud.activeUtteranceId(0)
        engine().utteranceProgressListener.onError(failed, TextToSpeech.ERROR)
        idle()

        val stopped = ReadAloud.state.value!!
        assertEquals(false, stopped.playing)
        assertEquals(0, stopped.chapterIndex)
        assertEquals(0, stopped.sentence)
        assertEquals("甲一。", stopped.current!!.text)
        assertTrue(stopped.error!!.contains("朗读失败"))
    }

    @Test
    fun errorFromOtherSentenceOrInterruptedQueueIsIgnored() {
        start()
        val futureSentence = ReadAloud.activeUtteranceId(2)
        ReadAloud.onUtteranceError(futureSentence)
        idle()
        assertEquals(true, ReadAloud.state.value!!.playing)
        assertNull(ReadAloud.state.value!!.error)

        ReadAloud.nextSentence()
        ReadAloud.onUtteranceError(futureSentence)
        idle()
        assertEquals(true, ReadAloud.state.value!!.playing)
        assertEquals("甲二。", ReadAloud.state.value!!.current!!.text)
        assertNull(ReadAloud.state.value!!.error)
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
        ReadAloud.nextSentence()
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

    @Test
    fun listeningProgressIsSavedByReadAloudItselfWhenStopping() {
        // 界面关了（ViewModel 没了）也要存：进度由 ReadAloud 存，停下时存最后读到的那句。
        val calls = java.util.Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())
        ReadAloud.start(context, "b", "书", book, 0, 0, 0, 1f, saveProgress = { c, b, ch ->
            calls += Triple(c, b, ch)
            com.xycs.lingyuereader.data.ReadingProgress(c, "第一章", b + 1, 0, ch)
        })
        // 引擎尚未就绪：直接跳到第二句，读到哪由这里定。
        ReadAloud.nextSentence()
        assertEquals("甲二。", ReadAloud.state.value!!.current!!.text)
        ReadAloud.stop()
        val deadline = System.currentTimeMillis() + 5_000
        while (ReadAloud.saved.value == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("b", ReadAloud.saved.value!!.first)
        assertEquals(Triple(0, 0, 3), calls.last())
    }

    @Test
    fun switchingBooksCancelsTheOldSleepTimerForReal() {
        start()
        ReadAloud.setTimer(15)
        // 换一本书听：界面显示不定时，旧的定时也不能到点把新书停掉。
        ReadAloud.start(context, "另一本", "书二", book, 0, 0, 0, 1f)
        assertEquals(SleepTimer.Off, ReadAloud.state.value!!.timer)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(16))
        assertEquals("另一本", ReadAloud.state.value?.bookId)
    }
}
