package com.xycs.lingyuereader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSampleTest {
    private fun assertBounded(width: Int, height: Int, sample: Int) {
        assertTrue("宽 ${width / sample}", width / sample <= 4096)
        assertTrue("高 ${height / sample}", height / sample <= 4096)
        assertTrue("像素 ${(width / sample).toLong() * (height / sample)}", (width / sample).toLong() * (height / sample) <= 16L * 1024 * 1024)
    }

    @Test
    fun ordinaryPicturesAreSampledByWidthOnly() {
        assertEquals(1, BookImages.sampleFor(1000, 1500, 1080))
        assertEquals(2, BookImages.sampleFor(2400, 3200, 1080))
        assertEquals(4, BookImages.sampleFor(4800, 3200, 1080))
    }

    @Test
    fun veryLongStripsAreAlsoSampledByHeight() {
        // 条漫的一页 800×30000：按宽度不用降，按高度要降到 4096 以内。
        val sample = BookImages.sampleFor(800, 30_000, 1080)
        assertEquals(8, sample)
        assertBounded(800, 30_000, sample)
    }

    @Test
    fun hugeScansAreBoundedForTheZoomView() {
        // 长按放大：显示宽度最大 2400；4000×7000 的扫描页高度超了单边上限。
        val sample = BookImages.sampleFor(4000, 7000, 2400)
        assertEquals(2, sample)
        assertBounded(4000, 7000, sample)
        val bigger = BookImages.sampleFor(9000, 9000, 2400)
        assertBounded(9000, 9000, bigger)
    }

    @Test
    fun unreadableBoundsDoNotLoop() {
        assertEquals(1, BookImages.sampleFor(-1, -1, 1080))
        assertEquals(1, BookImages.sampleFor(0, 0, 1080))
    }
}
