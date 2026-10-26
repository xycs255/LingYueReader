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

    private fun render(
        w: Int, content: Bitmap, progress: Float, pressY: Float, left: Float, backside: Bitmap?,
        fingerY: Float = pressY, height: Int = h,
    ): Bitmap {
        val g = CurlCylinder.of(w.toFloat(), height.toFloat(), progress, pressY, fingerY, left)!!
        val shader = RuntimeShader(CURL_SHADER).apply {
            setInputShader("content", BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("backside", BitmapShader(backside ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("left", left)
            setFloatUniform("hasBack", if (backside != null) 1f else 0f)
            setFloatUniform("size", w.toFloat(), height.toFloat())
            setFloatUniform("fold", g.fold.x, g.fold.y)
            setFloatUniform("normal", g.normal.x, g.normal.y)
            setFloatUniform("radius", g.radius)
            setFloatUniform("shadow", 84f)
            setFloatUniform("shade", g.shade)
            setFloatUniform("paper", 0xF5 / 255f, 0xF0 / 255f, 0xE6 / 255f, 1f)
        }
        // 底下垫一页蓝的当“下一页”。着色器只能在 GPU 管线里画：录进 RenderNode，交给 HardwareRenderer。
        val node = RenderNode("curl").apply { setPosition(0, 0, w, height) }
        node.beginRecording().apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, w.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        }
        node.endRecording()
        val reader = ImageReader.newInstance(w, height, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val out = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(plane.buffer)
        }.let { Bitmap.createBitmap(it, 0, 0, w, height) }
        image.close()
        renderer.destroy()
        reader.close()
        System.getenv("CURL_SHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "curl-$w-$height-$progress-$pressY-$fingerY.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        val fold20 = g.fold.x
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

    /**
     * 卷起的纸不透：每一行从左往右，先是这一页（或翻过来的背面），一旦露出下一页（蓝），右边就都是下一页，
     * 中间不会再冒出一截纸来——那就是圆柱顶上漏了缝、透出下一页。角翻、中间拉开、上角，翻到各处都查一遍。
     */
    @Test
    fun theRolledPaperHasNoSeeThroughSeam() {
        for ((progress, press) in listOf(0.08f to 400f, 0.2f to 400f, 0.45f to 400f, 0.2f to 760f, 0.4f to 700f, 0.6f to 700f, 0.3f to 40f, 0.7f to 60f)) {
            val img = render(progress, press)
            for (y in 0 until h step 16) {
                var seenBlue = false
                for (x in 0 until w) {
                    val blue = img.getPixel(x, y).isBlue()
                    if (blue) seenBlue = true
                    else assertTrue("progress $progress press $press at ($x, $y)", !seenBlue || Color.alpha(img.getPixel(x, y)) == 0)
                }
            }
        }
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

    @Test
    fun diagonalLandscapeTurnKeepsThePagesOpaqueAndRevealsTheNextRightPage() {
        val sw = 1280
        val sh = 582
        val spine = sw / 2f
        fun spread(left: Int, right: Int) = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawRect(0f, 0f, spine, sh.toFloat(), Paint().apply { color = left })
                drawRect(spine, 0f, sw.toFloat(), sh.toFloat(), Paint().apply { color = right })
            }
        }
        val current = spread(Color.RED, Color.GREEN)
        val next = spread(Color.YELLOW, Color.BLUE)
        for ((press, finger) in listOf(sh * 0.9f to sh * 0.1f, sh * 0.1f to sh * 0.9f)) {
            val cornerY = if (press > sh / 2f) sh - 4 else 4
            for (progress in listOf(0.2f, 0.5f, 0.7f)) {
                val image = render(sw, current, progress, press, spine, next, fingerY = finger, height = sh)
                val oldLeft = image.getPixel(12, sh / 2)
                assertTrue("旧左页应保持可见：$progress $press", Color.red(oldLeft) > 200 && Color.green(oldLeft) < 80)
                assertTrue("下一面右页应从纸角露出：$progress $press", image.getPixel(sw - 4, cornerY).isBlue())
                if (progress == 0.7f) {
                    var printedBack = false
                    for (y in 16 until sh - 16 step 16) for (x in sw / 3 until spine.toInt() step 16) {
                        val pixel = image.getPixel(x, y)
                        if (Color.red(pixel) > 170 && Color.green(pixel) > 170 && Color.blue(pixel) < 120) printedBack = true
                    }
                    assertTrue("下一面左页应印在翻起的纸背：$press", printedBack)
                }
                // 在任意横向扫描线上，露出新右页后不可再出现旧页／纸背：那会是卷纸中的透明孔洞。
                for (y in 8 until sh - 8 step 16) {
                    var firstBlue = -1
                    for (x in spine.toInt() until sw - 8 step 4) {
                        if (image.getPixel(x, y).isBlue()) {
                            firstBlue = x
                            break
                        }
                    }
                    if (firstBlue >= 0) for (x in firstBlue + 8 until sw - 8 step 4) {
                        assertTrue("双页斜拖漏缝：$progress $press ($x,$y)", image.getPixel(x, y).isBlue())
                    }
                }
            }
        }
    }
}
