package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ImportModeUiTest {
    @get:Rule val compose = createComposeRule()

    private val merged = "合成一本书：按章号排序，同一章以后面的文件为准"
    private val separate = "分别导入 3 本书：每个文件成为一本书"

    @Test
    fun paperChoicesAreFullSizeRadioTargetsAndKeepTheirMeaningWhenMotionIsReduced() {
        val choice = mutableStateOf(true)
        val oldReduced = Effects.reduced
        try {
            Effects.reduced = true
            compose.setContent { LingYueTheme { ImportModeChoices(3, choice.value) { choice.value = it } } }

            val first = compose.onNodeWithContentDescription(merged).fetchSemanticsNode()
            val second = compose.onNodeWithContentDescription(separate).fetchSemanticsNode()
            assertEquals(Role.RadioButton, first.config[SemanticsProperties.Role])
            assertEquals(Role.RadioButton, second.config[SemanticsProperties.Role])
            assertTrue(first.config[SemanticsProperties.Selected])
            assertTrue(!second.config[SemanticsProperties.Selected])
            val minTouch = 48f * ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density - 1f
            assertTrue(first.boundsInRoot.height >= minTouch)
            assertTrue(second.boundsInRoot.height >= minTouch)

            compose.onNodeWithContentDescription(separate).performClick()
            assertTrue(compose.onNodeWithContentDescription(separate).fetchSemanticsNode().config[SemanticsProperties.Selected])
            assertTrue(!compose.onNodeWithContentDescription(merged).fetchSemanticsNode().config[SemanticsProperties.Selected])
            compose.onNodeWithContentDescription(merged).performClick()
            assertTrue(compose.onNodeWithContentDescription(merged).fetchSemanticsNode().config[SemanticsProperties.Selected])
        } finally {
            Effects.reduced = oldReduced
        }
    }
}
