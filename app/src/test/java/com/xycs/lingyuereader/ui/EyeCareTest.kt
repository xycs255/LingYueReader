package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeCareTest {
    @Test
    fun lightLevelsHaveHysteresis() {
        assertEquals(LightLevel.NORMAL, nextLightLevel(null, 300f))
        assertEquals(LightLevel.DIM, nextLightLevel(null, 20f))
        assertEquals(LightLevel.DARK, nextLightLevel(null, 2f))
        // 在门槛附近抖动不切换。
        assertEquals(LightLevel.NORMAL, nextLightLevel(LightLevel.NORMAL, 35f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.NORMAL, 25f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.DIM, 50f))
        assertEquals(LightLevel.NORMAL, nextLightLevel(LightLevel.DIM, 80f))
        assertEquals(LightLevel.DARK, nextLightLevel(LightLevel.DARK, 9f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.DARK, 20f))
    }

    @Test
    fun warmthFollowsTheEvening() {
        assertEquals(0f, eveningWarmth(12 * 60), 0f)
        assertEquals(0f, eveningWarmth(18 * 60 + 30), 0f)
        assertEquals(0.5f, eveningWarmth(19 * 60 + 45), 0.01f)
        assertEquals(1f, eveningWarmth(23 * 60), 0f)
        assertEquals(1f, eveningWarmth(3 * 60), 0f)
        assertEquals(0f, eveningWarmth(7 * 60), 0f)
    }

    @Test
    fun everyAdjustedPaletteKeepsAAAContrast() {
        for (theme in ReaderTheme.entries) {
            for (dim in listOf(1f, 0.9f)) {
                for (warmth in listOf(0f, 0.5f, 1f)) {
                    val p = theme.palette().adjusted(dim, warmth)
                    val ratio = contrastRatio(p.text, p.background)
                    assertTrue("$theme dim=$dim warmth=$warmth ratio=$ratio", ratio >= 7f)
                }
            }
        }
    }

    @Test
    fun noPureWhiteOrMaximumGlare() {
        val day = ReaderTheme.PAPER.palette()
        assertTrue(day.background.red < 0.97f)
        val night = ReaderTheme.NIGHT.palette()
        // 夜间文字偏暖：红 > 蓝，且不是纯白。
        assertTrue(night.text.red > night.text.blue && night.text.red < 0.9f)
        assertTrue(night.accent.red > night.accent.blue)
    }
}
