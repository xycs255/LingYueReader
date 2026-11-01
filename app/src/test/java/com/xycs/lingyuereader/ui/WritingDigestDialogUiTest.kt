package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.PulseKind
import com.xycs.lingyuereader.data.PulseRow
import com.xycs.lingyuereader.data.PulseState
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 《渡灯》的前情对话框和体检表（只摆这两个对话框，文字自造、人名虚构）：
 * 改故事至今时后台刚整理完前情，不冲掉作者正在打的字；体检表每行读屏只念一遍。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDigestDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private var initial by mutableStateOf("林月到了渡灯镇。")
    private val saved = mutableListOf<String>()

    private fun showDigestDialog() {
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                GlassScaffold {
                    DigestEditDialog(
                        title = "故事至今",
                        summary = STORY_SUMMARY,
                        initial = initial,
                        edited = false,
                        label = "一行一句，按发生的先后",
                        onDismiss = {},
                        onSave = { saved += it },
                    )
                }
                }
            }
        }
        compose.onNodeWithText("林月到了渡灯镇。").assertExists()
    }

    private fun fieldText() = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test
    fun aBackgroundUpdateDoesNotWipeWhatTheAuthorIsTyping() {
        showDigestDialog()
        compose.onNode(hasSetTextAction()).performTextReplacement("林月到了渡灯镇，沈默在渡口等她。")
        // 对话框开着，后台收尾整理完前情：存下的故事至今换了一版。
        compose.runOnIdle { initial = "林月到了渡灯镇。\n杜娘交出了铜灯。" }
        compose.waitForIdle()
        assertEquals("林月到了渡灯镇，沈默在渡口等她。", fieldText())
        compose.onNodeWithText("前情刚被自动更新过", substring = true).assertExists()
        compose.onNodeWithText("存下").performClick()
        assertEquals(listOf("林月到了渡灯镇，沈默在渡口等她。"), saved)
    }

    @Test
    fun theAuthorCanTakeTheNewVersionInstead() {
        showDigestDialog()
        compose.onNode(hasSetTextAction()).performTextReplacement("林月到了渡灯镇，沈默在渡口等她。")
        compose.runOnIdle { initial = "林月到了渡灯镇。\n杜娘交出了铜灯。" }
        compose.waitForIdle()
        compose.onNodeWithText("换成新的").performClick()
        compose.waitForIdle()
        assertEquals("林月到了渡灯镇。\n杜娘交出了铜灯。", fieldText())
        assertEquals(0, compose.onAllNodesWithText("前情刚被自动更新过", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anUntouchedDialogFollowsTheNewVersionWithoutANote() {
        showDigestDialog()
        compose.runOnIdle { initial = "林月到了渡灯镇。\n杜娘交出了铜灯。" }
        compose.waitForIdle()
        assertEquals("林月到了渡灯镇。\n杜娘交出了铜灯。", fieldText())
        assertEquals(0, compose.onAllNodesWithText("前情刚被自动更新过", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun eachPulseLineIsReadOnceAndTheCarryButtonStaysItsOwnNode() {
        val carried = mutableListOf<String>()
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                GlassScaffold {
                    PulseDialog(
                        listOf(PulseRow(PulseKind.THREAD, "铜灯暗线", PulseState.COLD, lastNo = 3, lastTitle = "旧仓", idle = 9)),
                        onDismiss = {},
                    ) { carried += it.name }
                }
                }
            }
        }
        // 名字和两枚标签合成一句；里面的零碎（名字、“线”“冷了”）不再各是一个读屏节点（读屏走的是合并后的树）。
        assertEquals(1, compose.onAllNodesWithContentDescription("线铜灯暗线，冷了，最近推进在第 3 章").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("铜灯暗线").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("冷了").fetchSemanticsNodes().size)
        // 说明一句、“带进下一章”按钮各自独立。
        compose.onNodeWithText("最近推进：第 3 章《旧仓》").assertExists()
        compose.onNode(hasText("带进下一章") and hasClickAction()).performClick()
        assertEquals(listOf("铜灯暗线"), carried)
    }
}
