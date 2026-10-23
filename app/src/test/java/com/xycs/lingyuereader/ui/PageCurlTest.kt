package com.xycs.lingyuereader.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlTest {
    private fun near(expected: Offset, actual: Offset) =
        assertTrue("$expected ≠ $actual", (expected - actual).getDistance() < 0.01f)

    @Test
    fun foldFollowsProgressAndTheCornerLandsWhereItWasDragged() {
        assertNull(CurlGeometry.of(400f, 800f, 0f, 700f))
        // 从右下角正下方翻：折痕竖直，在一半进度时位于中线。
        val g = CurlGeometry.of(400f, 800f, 0.5f, 800f)!!
        near(Offset(1f, 0f), g.normal)
        near(Offset(200f, 800f), g.fold)
        assertEquals(200f, g.flat.maxOf { it.x }, 0.01f)
        assertEquals(200f, g.lifted.minOf { it.x }, 0.01f)
        // 右下角翻过来落在最左边。
        near(Offset(0f, 800f), g.reflect(Offset(400f, 800f)))
        // 矩阵和镜像算出来一样。
        near(g.reflect(Offset(330f, 120f)), g.matrix.map(Offset(330f, 120f)))
    }

    @Test
    fun touchingHigherLiftsTheCornerAndTheFoldSlants() {
        val g = CurlGeometry.of(400f, 800f, 0.5f, 500f)!!
        // 下半屏按：掀右下角，折痕斜着，右下角那边翻起得更多。
        assertTrue(g.normal.y > 0f && g.normal.x > 0f)
        assertTrue(Offset(400f, 800f) in g.lifted)
        assertTrue(g.flat.any { it == Offset(0f, 800f) })
        // 上半屏按：掀右上角。
        val top = CurlGeometry.of(400f, 800f, 0.5f, 100f)!!
        assertTrue(top.normal.y < 0f)
        assertTrue(Offset(400f, 0f) in top.lifted)
        // 翻完：整页都翻起来了。
        val done = CurlGeometry.of(400f, 800f, 1f, 500f)!!
        assertTrue(done.flat.all { it.x <= 0.01f })
    }
}
