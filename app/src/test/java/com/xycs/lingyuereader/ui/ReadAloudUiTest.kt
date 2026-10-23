package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.tts.ReadAloudState
import com.xycs.lingyuereader.tts.SleepTimer
import com.xycs.lingyuereader.tts.VoiceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ReadAloudUiTest {
    @get:Rule val compose = createComposeRule()

    private val palette = ReaderTheme.PAPER.palette()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state() = ReadAloudState(
        bookId = "book",
        bookTitle = "测试书",
        chapterIndex = 0,
        chapterTitle = "第一章",
        current = null,
        playing = false,
        rate = 1f,
        timer = SleepTimer.Off,
        voices = listOf(VoiceOption("voice-a", "语音甲"), VoiceOption("voice-b", "语音乙")),
    )

    private fun minTouchHeightPx() = context.resources.displayMetrics.density * 48f - 1f

    @Test
    fun voiceAndTimerChoicesAreReadableRadioTargets() {
        val state = mutableStateOf(state())
        compose.setContent {
            LingYueTheme {
                ReadAloudSheet(
                    state = state.value,
                    palette = palette,
                    onRate = {},
                    onVoice = { state.value = state.value.copy(voice = it) },
                    onTimer = { minutes ->
                        state.value = state.value.copy(timer = if (minutes == null) SleepTimer.Off else SleepTimer.At(Long.MAX_VALUE, minutes))
                    },
                )
            }
        }

        for (label in listOf("默认", "语音甲", "语音乙", "不定时", "15 分钟", "30 分钟", "60 分钟", "本章结束")) {
            val node = compose.onNodeWithText(label).fetchSemanticsNode()
            assertTrue("$label 触控区至少 48 dp", node.boundsInRoot.height >= minTouchHeightPx())
            assertEquals(Role.RadioButton, node.config[SemanticsProperties.Role])
        }
        assertTrue(compose.onNodeWithText("默认").fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertTrue(compose.onNodeWithText("不定时").fetchSemanticsNode().config[SemanticsProperties.Selected])

        compose.onNodeWithText("语音乙").performClick()
        compose.onNodeWithText("15 分钟").performClick()
        assertEquals("voice-b", state.value.voice)
        assertEquals(15, (state.value.timer as SleepTimer.At).minutes)
        assertTrue(compose.onNodeWithText("语音乙").fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertTrue(compose.onNodeWithText("15 分钟").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test
    fun controlBarDetailsOpenSettingsFromFullHeightTarget() {
        var openings = 0
        compose.setContent { LingYueTheme { ReadAloudBar(state(), palette) { openings++ } } }
        val details = compose.onNodeWithContentDescription("打开听书设置").fetchSemanticsNode()
        assertTrue(details.boundsInRoot.height >= minTouchHeightPx())
        assertEquals(Role.Button, details.config[SemanticsProperties.Role])
        compose.onNodeWithContentDescription("打开听书设置").performClick()
        assertEquals(1, openings)
    }
}
