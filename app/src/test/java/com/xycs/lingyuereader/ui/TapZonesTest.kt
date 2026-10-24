package com.xycs.lingyuereader.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.TapAction
import com.xycs.lingyuereader.data.TapZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class TapZonesTest {
    @Test
    fun zonesAreRowsOfThreeAndSurviveSaving() {
        assertEquals(0, TapZones.indexOf(0.1f, 0.1f))
        assertEquals(4, TapZones.indexOf(0.5f, 0.5f))
        assertEquals(8, TapZones.indexOf(1f, 1f))
        assertEquals(6, TapZones.indexOf(0f, 0.9f))
        val custom = TapZones.ONE_HAND.toMutableList().also { it[0] = TapAction.NONE }
        assertEquals(custom, TapZones.decode(TapZones.encode(custom)))
        // 坏数据回到默认。
        assertEquals(TapZones.DEFAULT, TapZones.decode("PREV,NEXT"))
        assertEquals(TapZones.DEFAULT, TapZones.decode(null))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class OneHandTapUiTest : PageTurnUiBase("SLIDE") {
    override fun prefs(editor: SharedPreferences.Editor) {
        editor.putString("tapZones", TapZones.encode(TapZones.ONE_HAND))
    }

    @Test
    fun tappingTheLeftEdgeTurnsForwardOneHanded() {
        open()
        waitForText("第 1/")
        compose.onRoot().performTouchInput { click(Offset(width * 0.1f, height * 0.8f)) }
        waitForText("第 2/")
        compose.onRoot().performTouchInput { click(Offset(width * 0.1f, height * 0.2f)) }
        waitForText("第 3/")
    }

    @Test
    fun pinchingChangesTheFontSize() {
        open()
        waitForText("第 1/")
        compose.onRoot().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(200f, 0f), center + Offset(40f, 0f), center + Offset(200f, 0f))
        }
        compose.waitUntil(5_000) {
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).getFloat("fontSize", 19f) > 19f
        }
        val size = context.getSharedPreferences("reader", Context.MODE_PRIVATE).getFloat("fontSize", 19f)
        assertTrue("$size", size <= 30f && size == kotlin.math.round(size))
    }
}
