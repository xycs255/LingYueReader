package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 卷纸着色器在 CPU 上画一遍：编译得过，画出来的每一块都在该在的地方。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CurlShaderTest {
    private val w = 400
    private val h = 800
    private val paper = Color.rgb(0xF5, 0xF0, 0xE6)

    /** 一页纸：纸色底、几行“字”，左上角一块红，好认出翻过来后的方向。 */
    private fun page(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        c.drawColor(paper)
        val ink = Paint().apply { color = Color.rgb(0x33, 0x33, 0x33) }
        for (y in 60 until h - 40 step 36) c.drawRect(30f, y.toFloat(), (w - 30).toFloat(), y + 14f, ink)
        c.drawRect(0f, 0f, 120f, 120f, Paint().apply { color = Color.RED })
    }

    private fun render(progress: Float, pressY: Float): Bitmap = render(w, page(), progress, pressY, 0f, null)

    private fun render(w: Int, content: Bitmap, progress: Float, pressY: Float, left: Float, backside: Bitmap?): Bitmap {
        val g = CurlCylinder.of(w.toFloat(), h.toFloat(), progress, pressY, pressY, left)!!
        val shader = RuntimeShader(CURL_SHADER).apply {
            setInputShader("content", BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("backside", BitmapShader(backside ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("left", left)
            setFloatUniform("bend", g.bend)
            setFloatUniform("chord", g.chord)
            setFloatUniform("hasBack", if (backside != null) 1f else 0f)
            setFloatUniform("size", w.toFloat(), h.toFloat())
            setFloatUniform("fold", g.fold.x, g.fold.y)
            setFloatUniform("normal", g.normal.x, g.normal.y)
            setFloatUniform("radius", g.radius)
            setFloatUniform("shadow", 84f)
            setFloatUniform("shade", g.shade)
            setFloatUniform("paper", 0xF5 / 255f, 0xF0 / 255f, 0xE6 / 255f, 1f)
        }
        // 底下垫一页蓝的当“下一页”。着色器只能在 GPU 管线里画：录进 RenderNode，交给 HardwareRenderer。
        val node = RenderNode("curl").apply { setPosition(0, 0, w, h) }
        node.beginRecording().apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        }
        node.endRecording()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val out = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(plane.buffer)
        }.let { Bitmap.createBitmap(it, 0, 0, w, h) }
        image.close()
        renderer.destroy()
        reader.close()
        System.getenv("CURL_SHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "curl-$w-$progress-$pressY.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return out
    }

    private fun Int.isBlue() = Color.blue(this) > 150 && Color.red(this) < 60

    @Test
    fun rollsThePageOverAndRevealsTheNextOne() {
        // 中间按下翻到 0.4：折痕竖直。
        val half = render(0.4f, 400f)
        val g = CurlCylinder.of(w.toFloat(), h.toFloat(), 0.4f, 400f, 400f)!!
        // 最左边还是原来那页的红块（翻过来的那块在旁边投下一点影子）。
        half.getPixel(20, 20).let { assertTrue(Integer.toHexString(it), Color.red(it) > 230 && Color.green(it) < 20 && Color.blue(it) < 20) }
        // 圆柱右边一大段是露出来的下一页（带阴影，仍偏蓝）。
        assertTrue(half.getPixel(w - 5, 400).isBlue())
        // 折痕是弯的：离按下处（中间）越远越往右让。第 20 行的折痕在这里。
        val t = (20f - g.fold.y) / g.chord
        val fold20 = g.fold.x + g.bend * minOf(t * t, 2f)
        // 折痕左边一点是翻过来的背面：纸色，不是红也不是蓝。
        val back = half.getPixel((fold20 - 10).toInt(), 20)
        assertTrue(Integer.toHexString(back), Color.red(back) > 180 && Color.blue(back) > 150)
        // 底下的红块不会出现在折痕右边。
        for (x in (fold20 + g.radius + 2).toInt() until w) assertTrue(!half.getPixel(x, 20).let { Color.red(it) > 200 && Color.green(it) < 80 })
    }

    @Test
    fun cornerCurlAndTheEnd() {
        val corner = render(0.3f, 600f)
        // 右下角掀开露出下一页，右上角还压着。
        assertTrue(corner.getPixel(w - 3, h - 3).isBlue())
        assertTrue(!corner.getPixel(w - 3, 3).isBlue())
        render(0.6f, 40f)
        // 翻完：整页离开，露出的下一页上没有残留的阴影。
        val done = render(0.999f, 780f)
        for (x in 0 until w step 20) assertEquals(Color.BLUE, done.getPixel(x, h / 2))
    }

    /** 中间按下拉开时，折痕中间翻得最远，上下两头落在后面：露出的下一页在中间那行最宽。 */
    @Test
    fun theFoldBendsLikePaperPulledInTheMiddle() {
        val g = CurlCylinder.of(w.toFloat(), h.toFloat(), 0.4f, 400f, 400f)!!
        assertTrue(g.bend > 10f)
        val img = render(0.4f, 400f)
        fun firstBlue(y: Int) = (0 until w).first { img.getPixel(it, y).isBlue() }
        assertTrue("${firstBlue(400)} ${firstBlue(40)}", firstBlue(400) + 10 < firstBlue(40))
        assertTrue(firstBlue(400) + 10 < firstBlue(h - 40))
        // 刚拉动和快翻完时纸是平的，不弯。
        assertTrue(CurlCylinder.of(w.toFloat(), h.toFloat(), 0.999f, 400f, 400f)!!.bend < 2f)
    }

    /** 双页：左页红、右页绿；下一面左页黄、右页蓝（垫在底下）。 */
    @Test
    fun twoPageCurlTurnsTheRightPageAroundTheSpine() {
        val sw = 800
        fun spread(l: Int, rgt: Int) = Bitmap.createBitmap(sw, h, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawRect(0f, 0f, sw / 2f, h.toFloat(), Paint().apply { color = l })
            c.drawRect(sw / 2f, 0f, sw.toFloat(), h.toFloat(), Paint().apply { color = rgt })
        }
        val current = spread(Color.RED, Color.GREEN)
        val next = spread(Color.YELLOW, Color.BLUE)
        val spine = sw / 2f
        fun Int.isRed() = Color.red(this) > 200 && Color.green(this) < 60 && Color.blue(this) < 60
        fun Int.isYellowish() = Color.red(this) > 180 && Color.green(this) > 180 && Color.blue(this) < 100

        // 刚掀起：左页原样不动，右页右边露出下一面的右页（蓝）。
        val early = render(sw, current, 0.2f, 400f, spine, next)
        assertTrue(early.getPixel(20, 400).isRed())
        assertTrue(early.getPixel(spine.toInt() - 4, 400).isRed())
        assertTrue(early.getPixel(sw - 4, 400).isBlue())

        // 翻到一大半：纸翻过书脊，盖在左页上的是背面，印着下一面的左页（黄）；左页最左边还露着。
        val g = CurlCylinder.of(sw.toFloat(), h.toFloat(), 0.7f, 400f, 400f, spine)!!
        val half = render(sw, current, 0.7f, 400f, spine, next)
        assertTrue(half.getPixel(4, 400).isRed())
        assertTrue(half.getPixel(spine.toInt() - 40, 400).isYellowish())
        val back = half.getPixel((g.fold.x - 10).toInt(), 400)
        assertTrue(Integer.toHexString(back), back.isYellowish())
        // 圆柱右边是下一面的右页；当前的右页（绿）一点不剩。
        assertTrue(half.getPixel(sw - 4, 400).isBlue())
        assertTrue(half.getPixel((g.fold.x + g.radius + 30).toInt(), 400).isBlue())
        for (x in 0 until sw step 10) assertTrue(Color.green(half.getPixel(x, 400)).let { it < 200 || Color.red(half.getPixel(x, 400)) > 150 })

        // 翻完：纸平平落在左页上，左半边全是下一面的左页，右半边是下一面的右页——和换过去之后一样，不跳。
        val done = render(sw, current, 0.999f, 780f, spine, next)
        for (x in 8 until sw step 40) {
            val px = done.getPixel(x, 400)
            if (x < spine - 8) assertTrue("$x ${Integer.toHexString(px)}", px.isYellowish())
            if (x > spine + 8) assertTrue("$x ${Integer.toHexString(px)}", px.isBlue())
        }
    }
}
