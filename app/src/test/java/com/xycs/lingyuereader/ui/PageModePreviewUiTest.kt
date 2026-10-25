package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 六种方式共用同一可点按预览；减少动态效果时直接展示结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageModePreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetEffects() {
        Effects.systemNoMotion = false
        Effects.reduced = false
    }

    @Test fun choosingEachModeUpdatesThePreviewAndKeepsSelectionAccessible() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent {
            LingYueTheme {
                val palette = ReaderTheme.PAPER.palette()
                ProvideReaderColors(palette, palette.accent) {
                    PagingPage(settings) { transform -> settings = transform(settings) }
                }
            }
        }
        PageMode.entries.forEach { mode ->
            compose.onNode(hasContentDescription("${mode.label}：", substring = true)).performClick()
            compose.waitForIdle()
            assertEquals(mode, settings.pageMode)
            compose.onNode(hasContentDescription("${mode.label}：", substring = true)).assertIsSelected()
            compose.onNode(hasContentDescription("${mode.label}翻页预览", substring = true)).performClick()
        }
    }

    @Test fun reducedMotionShowsStaticEndState() {
        Effects.systemNoMotion = true
        compose.setContent {
            LingYueTheme {
                val palette = ReaderTheme.NIGHT.palette()
                ProvideReaderColors(palette, Color(0xFFE0905F)) {
                    PageModePreview(PageMode.CURL)
                }
            }
        }
        val preview = compose.onNode(hasContentDescription("仿真翻页预览", substring = true))
        preview.assertExists()
        assertEquals("减少动态效果，显示第2页", preview.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        preview.performClick()
        compose.waitForIdle()
        assertEquals("减少动态效果，显示第1页", preview.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}
