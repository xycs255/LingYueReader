package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onLast
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.ReaderTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.SensorEventBuilder
import java.io.File

private val context: Context get() = ApplicationProvider.getApplicationContext()

private fun seedShortBook() {
    val files = context.filesDir
    File(files, "books").deleteRecursively()
    val dir = File(files, "books/b1").apply { mkdirs() }
    File(dir, "s0.txt").writeText("第一章 开端\n　　正文一段。\n")
    val entry = JSONObject()
        .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
        .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 5)
        .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
    File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
    context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EyeCareUiTest {
    private val sensors get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val seed = object : ExternalResource() {
        override fun before() {
            seedShortBook()
            shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_LIGHT))
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBook() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("正文一段")
    }

    @Test
    fun breakReminderAppearsAfterTwentyMinutesAndEnds() {
        openBook()
        compose.mainClock.autoAdvance = false
        // 19 分钟时还没有提醒。
        compose.mainClock.advanceTimeBy(19 * 60_000L)
        compose.onNodeWithText("看看远处").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(60_000L + 2_000)
        compose.onNodeWithText("看看远处").assertExists()
        // 倒计时走完 20 秒自动收起（时钟手动推进，倒计时动画不会自己跑完）。
        compose.mainClock.advanceTimeBy(22_000L)
        compose.onNodeWithText("看看远处").assertDoesNotExist()
        // 再读 20 分钟又会提醒，这次点“跳过”。
        compose.mainClock.advanceTimeBy(20 * 60_000L + 2_000)
        compose.onNodeWithText("看看远处").assertExists()
        compose.onNodeWithText("跳过").performClick()
        compose.mainClock.advanceTimeBy(2_000L)
        compose.onNodeWithText("看看远处").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionReminderStillCountsDownWithoutFading() {
        openBook()
        compose.runOnUiThread { Effects.systemNoMotion = true }
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(20 * 60_000L + 1_000L)
            compose.onNodeWithText("看看远处").assertExists()
            fun secondsLeft(): Int {
                val description = compose.onNodeWithContentDescription("还剩", substring = true)
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
                return Regex("""\d+""").find(description)!!.value.toInt()
            }
            val before = secondsLeft()
            compose.mainClock.advanceTimeBy(3_000L)
            assertTrue("倒计时应继续更新", secondsLeft() < before)
            compose.mainClock.advanceTimeBy(20_000L)
            compose.onNodeWithText("看看远处").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { Effects.systemNoMotion = false }
        }
    }

    @Test
    fun darkRoomSuggestsTurningOnALamp() {
        openBook()
        val event = SensorEventBuilder.newBuilder()
            .setSensor(sensors.getDefaultSensor(Sensor.TYPE_LIGHT)!!)
            .setValues(floatArrayOf(1f))
            .build()
        shadowOf(sensors).sendSensorEventToListeners(event)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(61_000)
        compose.mainClock.autoAdvance = true
        waitForText("开一盏灯")
    }

    @Test
    fun settingsShowTheLightReadingInsteadOfSayingThereIsNoSensor() {
        openBook()
        val event = SensorEventBuilder.newBuilder()
            .setSensor(sensors.getDefaultSensor(Sensor.TYPE_LIGHT)!!)
            .setValues(floatArrayOf(300f))
            .build()
        shadowOf(sensors).sendSensorEventToListeners(event)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("更多").performClick()
        // 设置面板画在最外层，也要拿到阅读页的光线读数；读数写在“护眼”页。
        compose.onAllNodesWithText("护眼").onLast().performScrollTo().performClick()
        waitForText("当前环境光约")
        compose.onNodeWithText("没有光线传感器", substring = true).assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaletteReducedMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun themeChangeSnapsWhenMotionIsDisabled() {
        val target = mutableStateOf(ReaderTheme.PAPER.palette())
        Effects.systemNoMotion = true
        try {
            compose.setContent {
                Column {
                    val displayed = animatePalette(target.value)
                    Box(Modifier.size(64.dp).background(displayed.background).testTag("palette"))
                    BasicText("切换", modifier = Modifier.clickable { target.value = ReaderTheme.NIGHT.palette() })
                }
            }
            compose.mainClock.autoAdvance = false
            fun pageLuminance(): Float {
                val pixels = compose.onNodeWithTag("palette").captureToImage().toPixelMap()
                return pixels[pixels.width / 2, pixels.height / 2].luminance()
            }
            assertTrue("初始应显示纸色", pageLuminance() > 0.75f)
            compose.onNodeWithText("切换").performClick()
            compose.waitForIdle()
            // 只推进 100 ms，足够完成重组，但不足以完成原先的 3 秒渐变。
            compose.mainClock.advanceTimeBy(100L)
            assertEquals(ReaderTheme.NIGHT.palette(), target.value)
            assertTrue("减少动效时书页应立即转为夜间色", pageLuminance() < 0.05f)
        } finally {
            compose.mainClock.autoAdvance = true
            Effects.systemNoMotion = false
        }
    }
}
