package com.xycs.lingyuereader.ui

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OrientationUiTest : PageTurnUiBase("SLIDE") {
    override fun prefs(editor: SharedPreferences.Editor) {
        editor.putString("orientation", "LANDSCAPE")
    }

    private fun requested(): Int {
        var o = 0
        compose.activityRule.scenario.onActivity { o = it.requestedOrientation }
        return o
    }

    @Test
    fun readerLocksToLandscapeAndShelfFollowsTheSystemAgain() {
        waitForText("测试书")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, requested())
        open()
        waitForText("第 1/")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, requested())
    }
}
