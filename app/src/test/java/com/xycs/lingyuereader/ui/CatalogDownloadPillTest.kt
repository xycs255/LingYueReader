package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class CatalogDownloadPillTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetMotion() { Effects.reduced = false }

    @Test
    fun downloadStatesKeepALargeTargetAndAccessibleFeedbackWithoutMotion() {
        Effects.reduced = true
        val inShelf = mutableStateOf(false)
        val downloading = mutableStateOf(false)
        var requests = 0
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors { DownloadPill(inShelf.value, downloading.value, onClick = { requests++ }) }
            }
        }

        val ready = compose.onNodeWithContentDescription("下载")
        val bounds = ready.fetchSemanticsNode().boundsInRoot
        val minTouch = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density * 48f - 1f
        assertTrue("下载触控区宽至少 48 dp", bounds.width >= minTouch)
        assertTrue("下载触控区高至少 48 dp", bounds.height >= minTouch)
        ready.assertHasClickAction().performClick()
        assertEquals(1, requests)

        compose.runOnUiThread { downloading.value = true }
        compose.onNodeWithContentDescription("正在下载").assertIsNotEnabled()
        compose.runOnUiThread { inShelf.value = true; downloading.value = false }
        compose.onNodeWithContentDescription("已在书架").assertIsNotEnabled()
        assertEquals(1, requests)
    }
}
