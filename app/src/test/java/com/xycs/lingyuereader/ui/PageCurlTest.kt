package com.xycs.lingyuereader.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        val g = CurlGeometry.of(400f, 800f, 0.5f, 650f)!!
        // 下三分之一按：掀右下角，折痕斜着，右下角那边翻起得更多。
        assertTrue(g.normal.y > 0f && g.normal.x > 0f)
        assertTrue(Offset(400f, 800f) in g.lifted)
        assertTrue(g.flat.any { it == Offset(0f, 800f) })
        // 上三分之一按：掀右上角。
        val top = CurlGeometry.of(400f, 800f, 0.5f, 100f)!!
        assertTrue(top.normal.y < 0f)
        assertTrue(Offset(400f, 0f) in top.lifted)
        // 翻完：整页都翻起来了。
        val done = CurlGeometry.of(400f, 800f, 1f, 500f)!!
        assertTrue(done.flat.all { it.x <= 0.01f })
    }

    @Test
    fun pressingTheMiddleRollsTheWholePageAndTheCornerFollowsTheFinger() {
        // 中间三分之一按下：折痕竖直，手指上下移也不掀角。
        val middle = CurlGeometry.of(400f, 800f, 0.4f, 400f, fingerY = 100f)!!
        near(Offset(1f, 0f), middle.normal)
        // 右下角按住往上拖：角跟着往上走，但不会提过 0.35 倍页高。
        val (_, low) = curlCorner(400f, 800f, 0.5f, 780f, 780f)
        val (_, high) = curlCorner(400f, 800f, 0.5f, 780f, 600f)
        assertTrue(high.y < low.y)
        val (_, tooHigh) = curlCorner(400f, 800f, 0.5f, 780f, 0f)
        assertEquals(800f * 0.35f, tooHigh.y, 0.01f)
        // 角不会被拉到页外。
        val (_, below) = curlCorner(400f, 800f, 0.5f, 700f, 1200f)
        assertEquals(800f, below.y, 0.01f)
        // 点按、按键翻页（没有手指）从右下角翻。
        val (corner, _) = curlCorner(400f, 800f, 0.3f, -1f, -1f)
        near(Offset(400f, 800f), corner)
    }

    @Test
    fun theRolledPageStartsAtTheCornerAndLeavesTheScreenWhenDone() {
        assertNull(CurlCylinder.of(400f, 800f, 0f, 700f, 700f))
        // 刚拉动：半径跟着缩小，折痕就在角旁边，不会一按下就跳开。
        val start = CurlCylinder.of(400f, 800f, 0.01f, 400f, 400f)!!
        assertTrue(start.radius <= 8f / Math.PI.toFloat() + 0.01f)
        assertTrue(start.fold.x > 390f)
        // 翻到一半：半径到顶（短边的 1/5）。
        assertEquals(80f, CurlCylinder.of(400f, 800f, 0.5f, 400f, 400f)!!.radius, 0.01f)
        // 翻完：圆柱和翻过去的背面都在屏幕左边以外。
        listOf(400f, 780f, 20f).forEach { y ->
            val done = CurlCylinder.of(400f, 800f, 1f, y, y)!!
            assertTrue("$y: ${done.fold}", done.fold.x + done.radius <= 0.01f)
        }
    }

    @Test
    fun twoPageCurlLandsFlatOnTheLeftPage() {
        // 书脊在 400：角从右边走到最左，纸钉在书脊上。
        val (_, end) = curlCorner(800f, 800f, 1f, 700f, 700f, left = 400f)
        assertEquals(0f, end.x, 0.01f)
        // 翻完：半径收到 0，折痕正好在书脊上，纸平平落在左页上。
        val done = CurlCylinder.of(800f, 800f, 1f, 700f, 700f, left = 400f)!!
        assertEquals(0f, done.radius, 0.01f)
        assertEquals(400f, done.fold.x, 0.5f)
        // 平折：翻起的那块只在右页，翻完时镜像正好盖住左页。
        val g = CurlGeometry.of(800f, 800f, 0.5f, 400f, left = 400f)!!
        assertTrue(g.lifted.all { it.x >= 400f - 0.01f })
        assertTrue(g.flat.all { it.x >= 400f - 0.01f })
        val flatDone = CurlGeometry.of(800f, 800f, 1f, 400f, left = 400f)!!
        flatDone.lifted.map(flatDone::reflect).forEach { assertTrue("$it", it.x <= 400.01f) }
    }

    @Test
    fun landscapeCornerDragKeepsFoldNearVerticalWithoutRestrictingPortrait() {
        val width = 1280f
        val height = 582f
        val spine = width / 2f
        for ((press, finger) in listOf(height * 0.9f to height * 0.1f, height * 0.1f to height * 0.9f)) {
            for (progress in listOf(0.2f, 0.5f, 0.8f)) {
                val (corner, dragged) = curlCorner(width, height, progress, press, finger, left = spine)
                val dx = corner.x - dragged.x
                val dy = abs(corner.y - dragged.y)
                assertTrue("双页斜拖 $progress: dy=$dy dx=$dx", dy <= dx * 0.25f + 0.5f)
                assertTrue("双页仍应随手指轻微倾斜", dy > 0f)
                val cylinder = CurlCylinder.of(width, height, progress, press, finger, left = spine)!!
                assertTrue("圆柱法向应跟随受限折角", abs(cylinder.normal.y) <= 0.25f)
                assertTrue("双页纸卷不应盖住太宽的书面", cylinder.radius <= height / 8f + 0.5f)
            }
            // 限幅只用于横屏双页；竖屏单页仍允许手指显著提起纸角。
            val (corner, dragged) = curlCorner(width, height, 0.5f, press, finger)
            assertTrue(abs(corner.y - dragged.y) > (corner.x - dragged.x) * 0.25f)
        }
    }
}
