package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.layoutKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 拖亮度条、调语速这类不影响排版的设置，不能让滚动模式的正文重建、跳回行首。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNoReflowUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 开端\n" + (1..40).joinToString("\n") { "　　第${it}段，城门口排着长队，人人手里拿着一张纸，等着进城去看灯会。" } + "\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 1200)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
                .putString("pageMode", "SCROLL").putBoolean("breakReminder", false).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun topOf(text: String) = compose.onAllNodesWithText(text, substring = true)[0].fetchSemanticsNode().boundsInRoot.top

    @Test
    fun brightnessAndSpeechRateDoNotMoveTheText() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performClick()
        waitForText("第3段")
        // 慢慢往上拖一段不整行的距离，停在两行之间。
        compose.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY + 100f), Offset(centerX, centerY - 37f), durationMillis = 1_500)
        }
        compose.waitForIdle()
        val before = topOf("第5段")
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread {
            vm.updateSettings { it.copy(brightness = 0.3f) }
            vm.updateSettings { it.copy(ttsRate = 1.4f) }
        }
        compose.waitForIdle()
        assertEquals(before, topOf("第5段"), 0.5f)
    }

    @Test
    fun onlyLayoutSettingsAreInTheLayoutKey() {
        val base = ReaderSettings()
        assertEquals(base.layoutKey, base.copy(brightness = 0.2f, ttsRate = 2f, ttsVoice = "x", autoScrollLines = 9f, theme = com.xycs.lingyuereader.data.ReaderTheme.NIGHT).layoutKey)
        assertNotEquals(base.layoutKey, base.copy(fontSize = base.fontSize + 1).layoutKey)
        assertNotEquals(base.layoutKey, base.copy(margin = base.margin + 4).layoutKey)
    }
}
