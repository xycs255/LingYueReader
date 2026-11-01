package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.PageTransition
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * “深色模式时用夜间背景”：应用里手动选了深色（系统是浅色），新安装打开书就是墨夜，不是亮的纸白；
 * 升级上来的老用户（迁移时记了只认系统深色）照旧是纸白，直到自己动一次开关。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NightFollowsAppDarkUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = TestName()
    private val upgraded get() = name.methodName.startsWith("upgraded")
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 渡口\n　　河上的灯一盏盏亮起来。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "渡口夜话").put("author", "林月").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 13)
                .put("sources", JSONArray().put(JSONObject().put("name", "渡口夜话.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
            sp.edit().clear().commit()
            // 老用户：已经存过设置、还没有设置版本号，迁移会记下只认系统深色。
            if (upgraded) sp.edit().putFloat("fontSize", 19f).commit()
            val prefs = Prefs(context)
            prefs.saveAppearance(Appearance(mode = ThemeMode.DARK, blur = false, liquidGlass = false, transition = PageTransition.NONE))
            prefs.saveSettings(prefs.settings().copy(ambientLight = false, eveningWarmth = false))
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    private fun pageLuminance(): Float {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("渡口夜话", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("渡口夜话")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("河上的灯", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val px = compose.onRoot().captureToImage().toPixelMap()
        // 书页底色：靠下、靠左的空白处（正文只有一行，在上面）。
        return px[(px.width * 0.05f).toInt(), (px.height * 0.7f).toInt()].luminance()
    }

    @Test
    fun freshInstallReadsOnTheNightPageWhenTheAppIsDark() {
        val l = pageLuminance()
        assertTrue("应用是深色时书页应是墨夜，亮度 $l", l < 0.1f)
    }

    @Test
    fun upgradedUserKeepsThePaperPageUntilTheyTouchTheSwitch() {
        assertTrue(Prefs(context).settings().autoNightSystemOnly)
        val l = pageLuminance()
        assertTrue("老用户照旧只认系统深色（系统是浅色），书页应是纸白，亮度 $l", l > 0.7f)
    }
}
