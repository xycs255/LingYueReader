package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** 单页仿真（弧边、镜像背面）：几何关系，以及画出来每一块在该在的地方。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CurlBezierTest {
    private val w = 400f
    private val h = 800f

    private fun near(expected: Offset, actual: Offset, tolerance: Float = 0.5f) =
        assertTrue("$expected vs $actual", (expected - actual).getDistance() <= tolerance)

    @Test
    fun theFoldIsThePerpendicularBisectorAndTheCornerLandsOnTheFinger() {
        assertNull(CurlPoints.of(w, h, 0f, 700f, 700f))
        for ((progress, press, finger) in listOf(Triple(0.2f, 760f, 700f), Triple(0.4f, 760f, 500f), Triple(0.3f, 40f, 120f), Triple(0.5f, -1f, -1f))) {
            val p = CurlPoints.of(w, h, progress, press, finger)!!
            // e、h 都在 a、f 连线的垂直平分线上。
            val g = (p.a + p.f) / 2f
            val af = p.f - p.a
            for (q in listOf(p.e, p.h)) assertEquals(0f, (q - g).x * af.x + (q - g).y * af.y, 0.5f * af.getDistance())
            // 沿折痕镜像，纸角落到手指处。
            near(p.a, p.mirror.map(p.f), 1f)
        }
    }

    @Test
    fun aCornerDragNeverSpillsPastTheLeftEdgeWhileTheFingerIsOnThePage() {
        for (progress in listOf(0.1f, 0.2f, 0.3f, 0.4f)) for (finger in listOf(200f, 400f, 600f, 780f)) {
            val p = CurlPoints.of(w, h, progress, 760f, finger)!!
            if (p.a.x > 0f) assertTrue("$progress $finger c=${p.c}", p.c.x >= -0.5f)
        }
    }

    @Test
    fun pressingTheMiddleTurnsTheWholePageWithANearlyVerticalFold() {
        val p = CurlPoints.of(w, h, 0.3f, 400f, 400f)!!
        assertEquals(h, p.f.y, 0.01f)
        assertTrue(abs(p.a.y - p.f.y) <= 1f)
        // 折痕（e 到 h）几乎竖直：h 在右边很远的上方。
        assertTrue("${p.e} ${p.h}", abs(p.h.x - p.e.x) / abs(p.h.y - p.e.y) < 0.02f)
    }

    /** 下一页蓝、这一页红（左上角一块绿），纸色当背面：每一块颜色对。 */
    @Test
    fun theCurrentPageTheBackAndTheNextPageEachShowWhereTheyShould() {
        val p = CurlPoints.of(w, h, 0.35f, 760f, 600f)!!
        val out = ImageBitmap(w.toInt(), h.toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w, h)) {
            drawRect(Color.Blue)
            drawPageCurl(p, Color(0xFFF5F0E6), 1f) {
                drawRect(Color.Red)
                drawRect(Color.Green, size = Size(80f, 80f))
            }
        }
        val bmp: Bitmap = out.asAndroidBitmap()
        fun px(x: Float, y: Float) = bmp.getPixel(x.toInt(), y.toInt())
        fun isRed(c: Int) = android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 60 && android.graphics.Color.blue(c) < 60
        fun isBlue(c: Int) = android.graphics.Color.blue(c) > 120 && android.graphics.Color.red(c) < 80
        fun isPaper(c: Int) = android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) > 140 && android.graphics.Color.blue(c) > 120
        // 左上远处是这一页。
        assertTrue(isRed(px(200f, 300f)))
        // 纸角原来的位置附近（右下角）露出下一页。
        assertTrue(Integer.toHexString(px(w - 3f, h - 3f)), isBlue(px(w - 3f, h - 3f)))
        // 手指那里是翻过来的背面：纸色，不是红也不是蓝。
        val inBack = p.a + ((p.d + p.i) / 2f - p.a) * 0.2f
        assertTrue(Integer.toHexString(px(inBack.x, inBack.y)), isPaper(px(inBack.x, inBack.y)))
        // 翻完前最后那一下：整页都翻走，只剩下一页。
        val done = CurlPoints.of(w, h, 0.999f, 760f, 760f)!!
        val end = ImageBitmap(w.toInt(), h.toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(end), Size(w, h)) {
            drawRect(Color.Blue)
            drawPageCurl(done, Color(0xFFF5F0E6), 0f) { drawRect(Color.Red) }
        }
        val e = end.asAndroidBitmap()
        for (x in 10 until w.toInt() step 40) for (y in 10 until h.toInt() step 80) assertTrue("$x,$y", !isRed(e.getPixel(x, y)))
    }
}
