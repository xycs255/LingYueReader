package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs

/** Grouped.kt 的公共组件：配色要在提供者里读、转圈在减少动效时不转、删除确认键是红的、卡片长高不在首帧播。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupedFoundationUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetMotion() { Effects.reduced = false }

    @Test
    fun compactSegmentsKeepFullTouchTargets() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    SegmentedControl(listOf(0 to "目录", 1 to "书签", 2 to "批注"), selected.intValue) {
                        selected.intValue = it
                    }
                }
            }
        }
        val bookmark = compose.onNodeWithText("书签")
        val bounds = bookmark.getUnclippedBoundsInRoot()
        assertTrue("分段控件虽然视觉收紧，触控区仍需至少 48dp", bounds.bottom - bounds.top >= 48.dp)
        bookmark.performClick()
        assertEquals(1, selected.intValue)
    }

    @Test
    fun compactPanelCloseKeepsFullTouchTarget() {
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors { PanelCloseButton("关闭目录", onClick = {}) }
            }
        }
        val bounds = compose.onNodeWithContentDescription("关闭目录").getUnclippedBoundsInRoot()
        assertTrue("面板关闭按钮需至少 48dp", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
    }

    @Test
    fun readingGroupedColorsOutsideAProviderFails() {
        val error = runCatching {
            compose.setContent {
                LingYueTheme { Text("配色", color = LocalGroupedColors.current.label) }
            }
            compose.waitForIdle()
        }.exceptionOrNull()
        val cause = generateSequence(error) { it.cause }.firstOrNull { it is IllegalStateException }
        assertTrue("没有 ProvideGroupedColors 时应当报错，而不是拿到默认的浅色：$error", cause?.message.orEmpty().contains("LocalGroupedColors"))
    }

    @Test
    fun dialogsCarryTheCallersColorsIntoThePopupHost() {
        // 对话框的内容画在最外层的弹层宿主里；调用处没提供配色时按主题补，提供了就带进去。
        var inside: GroupedColors? = null
        val reader = readerGroupedColors(com.xycs.lingyuereader.data.ReaderTheme.NIGHT.palette(), Color(0xFFE0905F))
        compose.setContent {
            LingYueTheme {
                GlassScaffold {
                    GlassDialog(show = true, title = "主题配色") {
                        Text("主题里", color = LocalGroupedColors.current.label)
                    }
                    ProvideGroupedColorsValue(reader) {
                        GlassDialog(show = true, title = "书页配色") {
                            inside = LocalGroupedColors.current
                            Text("书页里", color = LocalGroupedColors.current.label)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("主题里").assertExists()
        compose.onNodeWithText("书页里").assertExists()
        assertEquals(reader, inside)
    }

    @Test
    fun stillDialogShowsWithoutMotion() {
        Effects.reduced = true
        compose.setContent {
            LingYueTheme {
                GlassDialog(show = true, title = "删除这本书", summary = "删了就找不回来") {
                    DialogButtons(onDismiss = {}, onConfirm = {}, confirmText = "删除", destructive = true)
                }
            }
        }
        compose.onNodeWithText("删除这本书").assertExists()
        compose.onNodeWithText("删了就找不回来").assertExists()
        compose.onNodeWithText("取消").assertExists()
    }

    @Test
    fun spinnerStandsStillWithReducedMotion() {
        // 测试里无限动画只在手动推时钟时才跑：推两次时钟各截一张，转圈会变，减少动效时不变。
        fun frames(reduced: Boolean): Pair<Bitmap, Bitmap> {
            Effects.reduced = reduced
            compose.mainClock.advanceTimeBy(100)
            val first = compose.onNodeWithTag("spinner").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(260)
            val second = compose.onNodeWithTag("spinner").captureToImage().asAndroidBitmap()
            return first to second
        }
        val reduced = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    // 换一次 key 让转圈按当前的减少动效设置重新组合。
                    androidx.compose.runtime.key(reduced.intValue) {
                        Box(Modifier.testTag("spinner").background(Color.White).padding(8.dp)) { Spinner(size = 32.dp) }
                    }
                }
            }
        }
        val (a, b) = frames(reduced = false)
        assertFalse("正常时转圈在转", a.sameAs(b))
        compose.runOnUiThread { reduced.intValue = 1 }
        val (c, d) = frames(reduced = true)
        assertTrue("减少动效时转圈不动", c.sameAs(d))
    }

    @Test
    fun destructiveDialogConfirmIsRed() {
        var colors: GroupedColors? = null
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    colors = LocalGroupedColors.current
                    Box(Modifier.width(360.dp)) {
                        DialogButtons(onDismiss = {}, onConfirm = {}, confirmText = "删除", destructive = true)
                    }
                    Box(Modifier.padding(top = 120.dp).width(360.dp)) {
                        DialogButtons(onDismiss = {}, onConfirm = {}, confirmText = "确定")
                    }
                }
            }
        }
        compose.waitForIdle()
        val c = colors!!
        // 红底配白字：删除色不够 4.5:1 时把红压深一点，不换成黑字。浅色的删除色是偏胭脂的红（和朱砂强调色分开），蓝分量稍高。
        val (red, text) = buttonColors(c, destructive = true)
        assertTrue("删除确认键是红色：$red", red.red > 0.55f && red.green < 0.3f && red.blue < 0.4f && red.red > red.blue * 2)
        assertEquals(Color.White, text)
        assertTrue(contrastRatio(text, red) >= 4.5f)
        assertTrue("删除确认键是红底", dominant(compose.onNodeWithText("删除").captureToImage().asAndroidBitmap(), red) > 0.5f)
        assertTrue("普通确认键是强调色底", dominant(compose.onNodeWithText("确定").captureToImage().asAndroidBitmap(), c.tint) > 0.5f)
    }

    @Test
    fun sectionsDoNotGrowOnTheirFirstFrameButAnimateLaterChanges() {
        val rows = mutableIntStateOf(3)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    GroupedSection(Modifier.testTag("section")) {
                        repeat(rows.intValue) { i -> row { Text("第 $i 行", Modifier.height(56.dp)) } }
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("section").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        compose.mainClock.advanceTimeByFrame()
        val first = height()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("第一次显示不播长高动画", first, height(), 0.5f)
        assertTrue("三行都排出来了（$first）", first >= 3 * 56f)

        compose.runOnUiThread { rows.intValue = 5 }
        // 一帧一帧推、每帧都排一次版（时钟停着时排版要靠读取触发）：重组、开始动画各要一帧，之后才看得出在长。
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            height()
        }
        val middle = height()
        assertTrue("加了两行后卡片慢慢长高（$middle）", middle > first + 1f && middle < first + 2 * 56f - 1f)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(first + 2 * 56f, height(), 0.5f)

        // 减少动效时直接到位。
        Effects.reduced = true
        compose.runOnUiThread { rows.intValue = 2 }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            height()
        }
        assertEquals(first - 56f, height(), 0.5f)
    }

    /** 步进器：加减一、到上限 ＋ 变灰、在下限再按 － 回到“不填”、从“不填”按 ＋ 从起点开始、直接输入超出范围收到边上。 */
    @Test
    fun numberStepperStepsClampsAndCanBeLeftEmpty() {
        val value = mutableStateOf<Int?>(null)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    NumberStepper(value.value, { value.value = it }, "计划第几章收", range = 1..3, start = 2)
                }
            }
        }
        // 不填：中间写“不填”，－ 不能点。
        compose.onNodeWithText("不填").assertExists()
        compose.onNodeWithContentDescription("计划第几章收减一").assertIsNotEnabled()
        val plus = compose.onNodeWithContentDescription("计划第几章收加一")
        val bounds = plus.getUnclippedBoundsInRoot()
        assertTrue("步进器的钮触控区至少 48dp", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)

        // 从“不填”按 ＋：从起点 2 开始；再按一次到上限 3，＋ 变灰。
        plus.performClick()
        assertEquals(2, value.value)
        plus.performClick()
        assertEquals(3, value.value)
        plus.assertIsNotEnabled()

        // 减到下限 1，再按 － 回到“不填”。
        compose.onNodeWithContentDescription("计划第几章收减一").performClick()
        compose.onNodeWithContentDescription("计划第几章收减一").performClick()
        assertEquals(1, value.value)
        compose.onNodeWithContentDescription("计划第几章收改为不填").performClick()
        assertEquals(null, value.value)
        compose.onNodeWithText("不填").assertExists()

        // 直接输入：超出上限收到 3，删空就是“不填”。
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("25")
        assertEquals(3, value.value)
        field.assertTextEquals("3")
        field.performTextReplacement("")
        assertEquals(null, value.value)
    }

    /** 不能空的步进器：最小值上 － 变灰，删空输入框不会把值清掉。 */
    @Test
    fun requiredNumberStepperNeverBecomesEmpty() {
        val value = mutableStateOf<Int?>(1)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    NumberStepper(value.value, { value.value = it }, "从第几章生效", range = 1..30, optional = false)
                }
            }
        }
        compose.onNodeWithContentDescription("从第几章生效减一").assertIsNotEnabled()
        compose.onNodeWithContentDescription("从第几章生效加一").performClick()
        assertEquals(2, value.value)
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        assertEquals(2, value.value)
        compose.onNode(hasSetTextAction()).performTextReplacement("12")
        assertEquals(12, value.value)
    }

    /** 截图里接近 [color] 的像素占多少。 */
    private fun dominant(bitmap: Bitmap, color: Color): Float {
        val image = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val target = color.toArgb()
        var hits = 0
        for (x in 0 until image.width) for (y in 0 until image.height) {
            val p = image.getPixel(x, y)
            val close = abs(android.graphics.Color.red(p) - android.graphics.Color.red(target)) < 12 &&
                abs(android.graphics.Color.green(p) - android.graphics.Color.green(target)) < 12 &&
                abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(target)) < 12
            if (close) hits++
        }
        return hits.toFloat() / (image.width * image.height)
    }
}
