package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeCareTest {
    @Test
    fun lightLevelsHaveWideHysteresis() {
        assertEquals(LightLevel.NORMAL, nextLightLevel(null, 300f))
        assertEquals(LightLevel.DIM, nextLightLevel(null, 20f))
        assertEquals(LightLevel.DARK, nextLightLevel(null, 2f))
        // 进出门槛相差好几倍。
        assertEquals(LightLevel.NORMAL, nextLightLevel(LightLevel.NORMAL, 30f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.NORMAL, 20f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.DIM, 70f))
        assertEquals(LightLevel.NORMAL, nextLightLevel(LightLevel.DIM, 90f))
        assertEquals(LightLevel.DARK, nextLightLevel(LightLevel.DARK, 12f))
        assertEquals(LightLevel.DIM, nextLightLevel(LightLevel.DARK, 20f))
        assertEquals(LightLevel.NORMAL, nextLightLevel(LightLevel.DARK, 200f))
    }

    /** 按 500 ms 一次喂读数，返回经过的档位变化。 */
    private fun run(filter: AmbientLightFilter, seconds: Int, start: Long, lux: (Long) -> Float): List<LightLevel> {
        val seen = mutableListOf<LightLevel>()
        var t = start
        while (t < start + seconds * 1000L) {
            val level = filter.update(lux(t), t)
            if (seen.lastOrNull() != level) seen += level
            t += 500
        }
        return seen
    }

    @Test
    fun noisyReadingsAroundTheThresholdDoNotFlicker() {
        val filter = AmbientLightFilter()
        // 读数在 10–60 lx 之间乱跳十分钟（典型的台灯边缘、手机晃动）。
        val random = java.util.Random(1)
        val seen = run(filter, 600, 0) { 10f + random.nextFloat() * 50f }
        assertTrue("$seen", seen.size <= 2)
    }

    @Test
    fun aBriefShadowDoesNotSwitchToNight() {
        val filter = AmbientLightFilter()
        run(filter, 60, 0) { 200f }
        // 手遮住传感器 5 秒。
        val seen = run(filter, 60, 60_000) { t -> if (t < 65_000) 0f else 200f }
        assertEquals(listOf(LightLevel.NORMAL), seen)
    }

    @Test
    fun lightsOffSwitchesToNightAndLightsOnSwitchesBack() {
        val filter = AmbientLightFilter()
        run(filter, 60, 0) { 200f }
        val dark = run(filter, 60, 60_000) { 0.5f }
        assertEquals(LightLevel.DARK, dark.last())
        val on = run(filter, 60, 120_000) { 300f }
        assertEquals(LightLevel.NORMAL, on.last())
    }

    @Test
    fun switchesAreAtLeastThirtySecondsApart() {
        val filter = AmbientLightFilter()
        val changes = mutableListOf<Long>()
        var last: LightLevel? = null
        var t = 0L
        // 每 8 秒开关一次灯。
        while (t < 300_000) {
            val level = filter.update(if ((t / 8_000) % 2 == 0L) 300f else 0.5f, t)
            if (level != last) {
                changes += t
                last = level
            }
            t += 500
        }
        changes.zipWithNext().forEach { (a, b) -> assertTrue("$changes", b - a >= 30_000) }
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

class AutoScrollMathTest {
    @org.junit.Test
    fun speedFollowsReadingSpeedWithinLimits() {
        org.junit.Assert.assertEquals(25f, autoScrollLinesFor(400, 16f), 0.01f)
        org.junit.Assert.assertEquals(AUTO_MAX_LINES, autoScrollLinesFor(5000, 16f), 0f)
        org.junit.Assert.assertEquals(AUTO_MIN_LINES, autoScrollLinesFor(10, 16f), 0f)
        // 一页 12 行、每分钟 24 行：每页 30 秒。
        org.junit.Assert.assertEquals(30_000L, autoPageMillis(12f, 24f))
    }
}
