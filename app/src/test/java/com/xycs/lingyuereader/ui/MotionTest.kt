package com.xycs.lingyuereader.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test
    fun theShrunkPageLandsOnTheCover() {
        // 1000×2000 的窗口，封面在 (100, 300)–(400, 720)。
        val cover = Rect(100f, 300f, 400f, 720f)
        OpenFrom.set("b", cover, 1000f, 2000f)
        val s = OpenFrom.widthFraction
        assertEquals(0.3f, s, 1e-4f)
        // 绕支点缩到 s 倍后，整页的中心。
        val cx = (OpenFrom.pivotX + s * (0.5f - OpenFrom.pivotX)) * 1000f
        val cy = (OpenFrom.pivotY + s * (0.5f - OpenFrom.pivotY)) * 2000f
        assertEquals(cover.center.x, cx, 0.5f)
        assertEquals(cover.center.y, cy, 0.5f)
    }
}
