package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.DeviceModels
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 书架和设置页的统一样子：减少动效时书架换样子直接换、删除本机模型先红色确认、深色下主按钮的字读得清。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfAndSettingsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val abis = Build.SUPPORTED_64_BIT_ABIS
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "llm").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 渡口\n　　沈青禾在渡口等船。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "渡口夜话").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 12)
                .put("sources", JSONArray().put(JSONObject().put("name", "渡口夜话.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        }

        override fun after() {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", abis)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @After fun resetMotion() {
        Effects.reduced = false
        compose.mainClock.autoAdvance = true
    }

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /**
     * 网格换成列表：一帧帧往前走，等列表那一行（“未读 · 1 章”）出来，再走两帧，看网格那行小字（“未读”）还在不在。
     * 返回列表出来之后网格还在不在。
     */
    private fun gridStillShownAfterSwitchingToList(): Boolean {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { vm.updateShelfPrefs { it.copy(list = true) } }
        var frames = 0
        while (count("未读 · 1 章") == 0) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("换成列表显示后十帧内应当出现列表", ++frames < 10)
        }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        return count("未读") > 0
    }

    @Test
    fun shelfViewSwitchFadesThroughButIsImmediateWithReducedMotion() {
        waitForText("渡口夜话")
        assertEquals("网格里写着“未读”", 1, count("未读"))
        // 平时：列表淡入时网格还在淡出（原地淡出淡入）。
        assertTrue("列表和网格互换时应当淡出淡入，不是一下子换掉", gridStillShownAfterSwitchingToList())
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { vm.updateShelfPrefs { it.copy(list = false) } }
        compose.waitUntil(5_000) { count("未读 · 1 章") == 0 && count("未读") == 1 }

        // 墨水屏 / 省电：直接换，网格不留着淡出。
        compose.runOnUiThread { vm.updateSettings { it.copy(eInk = true) } }
        compose.waitUntil(5_000) { Effects.noMotion }
        compose.waitForIdle()
        assertTrue("减少动效时列表和网格应当直接换", !gridStillShownAfterSwitchingToList())
    }

    /** 书名下的“⋯”看着小，能点的范围网格和列表里都至少 48dp，点了出菜单。 */
    @Test
    fun shelfMoreButtonsKeepFullTouchTargets() {
        waitForText("渡口夜话")
        fun assertTarget(where: String) {
            val bounds = compose.onNodeWithContentDescription("操作").getUnclippedBoundsInRoot()
            assertTrue("$where 的“⋯”触控区至少 48dp", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        }
        assertTarget("网格")
        compose.runOnUiThread { vm.updateShelfPrefs { it.copy(list = true) } }
        compose.waitUntil(5_000) { count("未读 · 1 章") == 1 && compose.onAllNodesWithContentDescription("操作").fetchSemanticsNodes().size == 1 }
        assertTarget("列表")
        compose.onNodeWithContentDescription("操作").performClick()
        waitForText("详情")
    }

    @Test
    fun deletingADeviceModelAsksFirst() {
        // 假装是 64 位手机：设置页里能选本机模型。
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
        assertTrue(DeviceModels.supported)
        compose.runOnUiThread {
            vm.saveAiConfig(vm.aiConfig.value.withPreset(AiPresets.of(AiPresets.DEVICE)))
            vm.open(Screen.Ai)
        }
        waitForText("本机模型")
        val file = File(context.cacheDir, "小船模型.task").apply { writeText("假模型") }
        compose.runOnUiThread { vm.importDeviceModel(Uri.fromFile(file)) }
        compose.waitUntil(15_000) { vm.deviceModels.value.installed.isNotEmpty() }
        val id = DeviceModels.FILE + "小船模型.task"

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("删除「小船模型」"))
        compose.onNodeWithText("删除「小船模型」").performClick()
        // 先问一句，模型还在。
        waitForText("要用时得重新从文件导入")
        compose.waitForIdle()
        assertEquals(listOf(id), vm.deviceModels.value.installed)
        // 取消：什么都不删。
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("要用时得重新从文件导入", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(id), vm.deviceModels.value.installed)
        // 再点，确认删除。
        compose.onNodeWithText("删除「小船模型」").performClick()
        waitForText("要用时得重新从文件导入")
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(15_000) { vm.deviceModels.value.installed.isEmpty() }
    }

    @Test
    fun primaryButtonTextIsReadableOnALightAccentInDarkMode() {
        waitForText("渡口夜话")
        // 深色 + 琥珀强调色：强调色很浅，写死的白字叠上去只有 2:1 左右。
        compose.runOnUiThread {
            vm.updateAppearance { Appearance(mode = ThemeMode.DARK, accent = Appearance.ACCENTS[1]) }
            vm.open(Screen.Detail("b1"))
        }
        waitForText("开始阅读")
        compose.waitForIdle()
        val pixels = compose.onNodeWithText("开始阅读").captureToImage().let { image ->
            IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        // 出现得最多的是按钮底色，和它差得最远的是字的颜色。
        val fill = pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
        val ink = pixels.maxBy { contrastRatio(Color(it), Color(fill)) }
        val ratio = contrastRatio(Color(ink), Color(fill))
        assertTrue("这个场景里白字应当看不清（强调色够浅），否则测不出问题：${contrastRatio(Color.White, Color(fill))}", contrastRatio(Color.White, Color(fill)) < 3f)
        assertTrue("主按钮的字和底色对比度只有 $ratio", ratio >= 4.5f)
    }
}
