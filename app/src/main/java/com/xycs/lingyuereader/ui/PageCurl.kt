package com.xycs.lingyuereader.ui

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 仿真翻页时手指在哪：按下的高度决定掀哪个角，之后角跟着手指上下走。
 * 点按、音量键翻页时清成 -1，从右下角翻（同 Legado 的做法）。
 */
internal class CurlTouch {
    var pressY by mutableFloatStateOf(-1f)
    var fingerY by mutableFloatStateOf(-1f)

    fun reset() {
        pressY = -1f
        fingerY = -1f
    }
}

/**
 * 被拉着的那一角：原来在哪（first），现在拉到了哪（second）。[progress] 从 0（没动）到 1（整页翻走）。
 *
 * - 上、下三分之一按下：掀右上角 / 右下角，角的高度跟着手指，按得离角越远，角提得越高；
 * - 中间三分之一按下：整页竖着卷过去，不掀角；
 * - 没有手指（点按、按键）：从右下角翻。
 *
 * 角横向走 [progress] × 两倍页宽：翻完时正好落在左边一页宽之外，这页整个离开屏幕。
 * 双页时这页纸从 [left]（书脊）到 [width]，翻完时绕书脊翻到左边，正好盖住左页。
 * 翻到头和尾，角都回到原来的高度，免得翻完那一下跳一跳。
 */
internal fun curlCorner(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float, left: Float = 0f): Pair<Offset, Offset> {
    val t = progress.coerceIn(0f, 1f)
    val y0 = if (pressY < 0f) height * 0.9f else pressY
    val fy = if (fingerY < 0f) y0 else fingerY
    val x = width - 2 * (width - left) * t
    if (y0 > height / 3 && y0 < height * 2 / 3) return Offset(width, y0) to Offset(x, y0)
    val cornerY = if (y0 >= height / 2) height else 0f
    val bump = sin(PI.toFloat() * t).let { 1 - (1 - it) * (1 - it) }
    val y = cornerY + ((y0 - cornerY) * 0.7f + (fy - y0)) * bump
    // 角不能往页外拉（另一角会翘起来），也不能提得太高、折痕太陡。
    val clamped = if (cornerY > 0f) y.coerceIn(height * 0.35f, height) else y.coerceIn(0f, height * 0.65f)
    return Offset(width, cornerY) to Offset(x, clamped)
}

/**
 * 平折的几何（Android 13 以下用）。要翻走的那页，一角被拉到 [curlCorner] 算出的位置，
 * 折痕是原来的角和它新位置连线的垂直平分线：折痕左边的纸平铺不动（[flat]），右边那块（[lifted]）翻过来，
 * 背面朝上盖在平铺的纸上，露出底下的下一页。
 */
internal class CurlGeometry(
    /** 折痕上的一点。 */
    val fold: Offset,
    /** 垂直于折痕、指向翻起那一侧的单位向量。 */
    val normal: Offset,
    val flat: List<Offset>,
    val lifted: List<Offset>,
) {
    /** 关于折痕的镜像：翻起的那块翻过来后在哪里。 */
    fun reflect(p: Offset): Offset = p - normal * (2 * dot(p - fold, normal))

    val matrix: Matrix
        get() {
            val (nx, ny) = normal
            val k = 2 * dot(fold, normal)
            return Matrix(
                floatArrayOf(
                    1 - 2 * nx * nx, -2 * nx * ny, 0f, 0f,
                    -2 * nx * ny, 1 - 2 * ny * ny, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    k * nx, k * ny, 0f, 1f,
                ),
            )
        }

    companion object {
        fun of(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float = -1f, left: Float = 0f): CurlGeometry? {
            if (width - left <= 0f || height <= 0f || progress <= 0.002f) return null
            val (corner, dragged) = curlCorner(width, height, progress, pressY, fingerY, left)
            val d = corner - dragged
            val len = d.getDistance()
            if (len < 0.5f) return null
            val normal = d / len
            val fold = (corner + dragged) / 2f
            val page = listOf(Offset(left, 0f), Offset(width, 0f), Offset(width, height), Offset(left, height))
            return CurlGeometry(fold, normal, clip(page, fold, normal, keepLifted = false), clip(page, fold, normal, keepLifted = true))
        }

        /** 用折痕把（凸）多边形切开，留下一侧。 */
        private fun clip(polygon: List<Offset>, fold: Offset, normal: Offset, keepLifted: Boolean): List<Offset> {
            fun side(p: Offset) = dot(p - fold, normal).let { if (keepLifted) it else -it }
            val out = mutableListOf<Offset>()
            polygon.forEachIndexed { i, a ->
                val b = polygon[(i + 1) % polygon.size]
                val sa = side(a)
                val sb = side(b)
                if (sa >= 0) out += a
                if ((sa >= 0) != (sb >= 0)) out += a + (b - a) * (sa / (sa - sb))
            }
            return out
        }
    }
}

private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

/**
 * 卷着的纸（Android 13 起用）：纸不是沿一条线折过去，而是绕着一根圆柱卷起来，从上往下看——
 *
 * - 折痕（[fold]）左边平铺不动；
 * - 往右是圆柱：靠下的半圈是正面（往上翻起，越翻越侧、越暗），靠上的半圈是背面，翻过顶以后平铺回左边；
 * - 再往右是空的，露出底下的下一页，贴着圆柱有一道阴影。
 *
 * 被拉着的角在 [curlCorner] 给的位置，所以折痕要从那里往回推：角离原位 L，绕过半圈多走 πR，折痕在离原角 (L + πR) / 2 处。
 * 刚拉动时 L 很小，半径跟着缩小（R ≤ L / π），角不会一按下就跳开。
 *
 * [radius] 是最大半径。着色器里每一处（沿折痕方向）的半径还要看那里折痕外面还剩多少纸：
 * 剩的纸绕不满半圈就卷得紧一些（R = 剩下的纸 / π）。这样纸总能整个卷过去，圆柱顶上不会漏出一道透明的缝；
 * 靠近页边的地方纸少、卷得紧，中间纸多、卷得松，像真纸被拉起时的锥形，翻起那块的边自然是弯的。
 * 双页时纸钉在书脊（[curlCorner] 的 left）上：最后四分之一半径收到 0，纸正好平平地落在左页上，换成下一面时不跳。
 */
internal data class CurlCylinder(
    val fold: Offset,
    val normal: Offset,
    val radius: Float,
    /** 阴影深浅：最后四分之一淡掉，翻完那一下左边不留一道影子。 */
    val shade: Float,
) {
    companion object {
        fun of(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float, left: Float = 0f): CurlCylinder? {
            val page = width - left
            if (page <= 0f || height <= 0f || progress <= 0.002f) return null
            val (corner, dragged) = curlCorner(width, height, progress, pressY, fingerY, left)
            val d = corner - dragged
            val len = d.getDistance()
            if (len < 0.5f) return null
            val normal = d / len
            val landing = if (left > 0f) (1f - progress.coerceIn(0f, 1f)) * page / 2f else Float.MAX_VALUE
            // 卷得粗一些（短边的 1/5）：翻起的纸大半在圆柱上，看得出是卷起来的，而不是折出一块平平的三角。
            val radius = minOf(min(page, height) / 5f, len / PI.toFloat(), landing)
            val fromCorner = (len + PI.toFloat() * radius) / 2f
            val shade = ((1f - progress) * 4f).coerceIn(0f, 1f)
            return CurlCylinder(corner - normal * fromCorner, normal, radius, shade)
        }
    }
}

internal const val CURL_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 fold;
uniform float2 normal;
uniform float radius;
uniform float shadow;
uniform float shade;
uniform half4 paper;
// 翻的这页纸的左边：单页是 0；双页是书脊，左边那页一直平放着不动。
uniform float left;
// 双页时纸的背面就是下一面的左页（hasBack = 1）；没有时用纸色。
uniform shader backside;
uniform float hasBack;

const float PI = 3.14159265;

// 纸上这点还在页内多深（像素）；在页外是负的。用来给纸边抗锯齿。
float inside(float2 q) {
    return min(min(q.x - left, size.x - q.x), min(q.y, size.y - q.y));
}

// 从折痕上的 f 点顺着 normal 往外，纸还剩多长。
float paperBeyond(float2 f) {
    float tx = 1000000.0;
    float ty = 1000000.0;
    if (normal.x > 0.0001) tx = (size.x - f.x) / normal.x;
    if (normal.x < -0.0001) tx = (left - f.x) / normal.x;
    if (normal.y > 0.0001) ty = (size.y - f.y) / normal.y;
    if (normal.y < -0.0001) ty = (0.0 - f.y) / normal.y;
    return max(min(tx, ty), 0.0);
}

// 背面：素纸，翻过来朝着光，比正面略亮一点；双页时印着下一面的左页。
half4 back(float2 q, float light) {
    half3 rgb;
    if (hasBack > 0.5) {
        // 翻平以后正好绕书脊落到左边，所以按书脊对称去取下一面左页上的字。
        half4 b = backside.eval(float2(2.0 * left - q.x, q.y));
        rgb = mix(paper.rgb, b.rgb, b.a);
    } else {
        rgb = mix(paper.rgb, half3(1.0), 0.18);
    }
    return half4(min(rgb * half(light), half3(1.0)), 1.0);
}

half4 front(float2 q, float light) {
    half4 c = content.eval(q);
    return half4(c.rgb * half(light), c.a);
}

half4 over(half4 under, half4 top, float coverage) {
    return mix(under, top, half(clamp(coverage + 0.5, 0.0, 1.0)));
}

half4 darken(half4 c, float a) {
    return half4(c.rgb * half(1.0 - a), c.a);
}

half4 main(float2 p) {
    float d = dot(p - fold, normal);
    // 这一处折痕外面的纸绕不满半圈时卷紧一些，纸总能整个卷过去。
    float r = clamp(paperBeyond(p - normal * d) / PI, 0.5, max(radius, 0.5));
    if (d <= 0.0) {
        // 平铺的正面（双页时还有书脊左边那页）。翻过来的背面压在上面，纸边在底下投一道窄而软的影子。
        half4 base = content.eval(p);
        float2 q = p + normal * (PI * r - 2.0 * d);
        float2 outside = max(max(float2(left, 0.0) - q, q - size), 0.0);
        float gap = length(outside);
        if (gap > 0.0) {
            float u = clamp(gap / (shadow * 0.6), 0.0, 1.0);
            base = darken(base, shade * 0.16 * (1.0 - u) * (1.0 - u));
        }
        // 背面刚从圆柱顶上下来那段最亮，往远处平铺的地方稍暗，看得出纸是斜着搭下来的。
        float light = 1.0 - 0.05 * smoothstep(0.0, r * 3.0, -d);
        return over(base, back(q, light), inside(q));
    }
    // 圆柱外：露出的下一页。贴着圆柱的地方阴影最深，往外很快淡掉。
    float t = clamp((d - r) / shadow, 0.0, 1.0);
    float dark = shade * 0.30 * exp(-4.0 * t) * (1.0 - t);
    half4 result = half4(0.0, 0.0, 0.0, half(dark));
    if (p.x < left) {
        // 双页：纸已经翻过书脊，底下是不动的左页，阴影落在它上面。
        result = darken(content.eval(p), dark);
    }
    if (d < r) {
        float a = asin(clamp(d / r, 0.0, 1.0));
        float c = cos(a);
        // 正面：从平铺处往上翻，越翻越侧向，越暗；翻到侧面时最暗。
        float2 qf = p + normal * (r * a - d);
        result = over(result, front(qf, 0.62 + 0.38 * c), inside(qf));
        // 背面：圆柱顶上朝着光最亮，带一道高光；往侧面转过去变暗。
        float2 qb = p + normal * (r * (PI - a) - d);
        float light = 0.74 + 0.28 * c + 0.08 * pow(c, 24.0);
        result = over(result, back(qb, light), inside(qb));
    }
    return result;
}
"""

/** Android 13 起用卷纸着色器；Robolectric 里没有 GPU，照旧用平折。 */
internal val curlShaderSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Build.FINGERPRINT != "robolectric"

/**
 * 画正在被翻走的一页。[progress] 为 0 时原样画，不额外开离屏缓冲。
 * Android 13 起纸是卷着的（[CurlCylinder]），以下版本是平折（[CurlGeometry]）。
 *
 * 双页时 [spine] 是书脊在宽度上的位置（0.5）：只有右页翻起，绕书脊翻到左边；[backside] 是下一面的样子，
 * 翻起来的纸背面印的就是它的左页。单页时 [spine] 为 0，背面是纸色。
 */
internal fun Modifier.pageCurl(
    progress: () -> Float,
    touch: CurlTouch,
    paper: Color,
    spine: Float = 0f,
    backside: () -> ImageBitmap? = { null },
): Modifier = when {
    // 单页：经典仿真（弧边、镜像背面、渐变阴影）。
    spine <= 0f -> curlBezier(progress, touch, paper)
    // 双页：纸绕书脊卷过去。
    curlShaderSupported -> curlShader(progress, touch, paper, spine, backside)
    else -> curlFlat(progress, touch, paper, spine, backside)
}

/** 上一帧的卷纸效果：纸没动（手指按住不动、卡在半路）时不重建。 */
private class CurlEffectCache {
    var key: Any? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var image: ImageBitmap? = null
    var imageShader: Shader? = null
}

private data class CurlKey(val width: Float, val height: Float, val cylinder: CurlCylinder, val left: Float, val paper: Color, val back: ImageBitmap?)

/** 没有背面图时给着色器的占位：一像素透明。 */
private val emptyBackside: Shader by lazy {
    BitmapShader(createBitmap(1, 1), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.curlShader(progress: () -> Float, touch: CurlTouch, paper: Color, spine: Float, backside: () -> ImageBitmap?): Modifier = composed {
    val shader = remember { RuntimeShader(CURL_SHADER) }
    val cache = remember { CurlEffectCache() }
    graphicsLayer {
        val left = size.width * spine
        val c = CurlCylinder.of(size.width, size.height, progress(), touch.pressY, touch.fingerY, left)
        renderEffect = c?.let {
            val back = backside()
            val key = CurlKey(size.width, size.height, it, left, paper, back)
            if (key != cache.key) {
                if (back !== cache.image) {
                    cache.image = back
                    cache.imageShader = back?.let { img -> BitmapShader(img.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                }
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("fold", it.fold.x, it.fold.y)
                shader.setFloatUniform("normal", it.normal.x, it.normal.y)
                shader.setFloatUniform("radius", it.radius)
                shader.setFloatUniform("shadow", 28.dp.toPx())
                shader.setFloatUniform("shade", it.shade)
                shader.setFloatUniform("paper", paper.red, paper.green, paper.blue, 1f)
                shader.setFloatUniform("left", left)
                shader.setFloatUniform("hasBack", if (cache.imageShader != null) 1f else 0f)
                shader.setInputShader("backside", cache.imageShader ?: emptyBackside)
                cache.effect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                cache.key = key
            }
            cache.effect
        }
    }
}

private fun List<Offset>.toPath() = Path().also { path ->
    forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
}

/** 平折：平铺的部分照常画，翻起的部分镜像过来、蒙一层纸色当作背面（隐约透出反着的字），折痕处加阴影。 */
private fun Modifier.curlFlat(progress: () -> Float, touch: CurlTouch, paper: Color, spine: Float, backside: () -> ImageBitmap?): Modifier = drawWithContent {
    val left = size.width * spine
    val g = CurlGeometry.of(size.width, size.height, progress(), touch.pressY, touch.fingerY, left)
    if (g == null) {
        drawContent()
        return@drawWithContent
    }
    // 双页：书脊左边那页不动。
    if (left > 0f) clipRect(right = left) { this@drawWithContent.drawContent() }
    if (g.flat.size >= 3) clipPath(g.flat.toPath()) { this@drawWithContent.drawContent() }
    if (g.lifted.size < 3) return@drawWithContent
    val lifted = g.lifted.toPath()
    // 掀起来露出的下一页上，折痕旁边一道阴影。
    clipPath(lifted) {
        drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent), start = g.fold, end = g.fold + g.normal * 32.dp.toPx()))
    }
    // 翻过来的背面。
    val depth = g.lifted.maxOf { dot(it - g.fold, g.normal) }.coerceAtLeast(1f)
    val back = backside()
    withTransform({ transform(g.matrix) }) {
        clipPath(lifted) {
            if (back != null) {
                // 双页：背面是下一面的左页，按书脊对称印在纸上。
                drawRect(paper)
                withTransform({ scale(-1f, 1f, pivot = Offset(left, 0f)) }) { drawImage(back) }
            } else {
                this@drawWithContent.drawContent()
                drawRect(paper.copy(alpha = 0.86f))
            }
            // 靠折痕处弯着，暗一些；往外渐亮。
            drawRect(
                Brush.linearGradient(
                    0f to Color.Black.copy(alpha = 0.18f),
                    0.35f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.05f),
                    start = g.fold,
                    end = g.fold + g.normal * depth,
                ),
            )
        }
    }
    drawPath(g.lifted.map(g::reflect).toPath(), Color.Black.copy(alpha = 0.12f), style = Stroke(1.dp.toPx()))
}

/**
 * 经典的书页仿真（国内阅读器常见的那种）：手指拉着纸角（[f]）到 [a]，折痕是 a、f 连线的垂直平分线，
 * 翻起的那块是一块弧边的纸，背面朝上盖在这一页上，掀开的地方露出下一页。
 *
 * - [e]、[h]：折痕和下边（或上边）、右边的交点，也是两条弧边的控制点；
 * - [c]、[j]：两条弧边在页边上的起点；[b]、[k]：弧边和 a 到 e、a 到 h 两条纸边的交点；
 * - [d]、[i]：两条弧的中点，d 到 i 是翻过去的纸和露出的下一页之间那条线。
 *
 * 当前页去掉 c→e→b→a→k→h→j→f 围起的一块（[turned]）；这块里，c、d、i、j、f 那边露出下一页（[revealed]），
 * 其余是翻过来的背面（[back]）。背面是当前页沿 e、h 连线镜像过去的。
 */
internal data class CurlPoints(
    val a: Offset,
    val f: Offset,
    val e: Offset,
    val h: Offset,
    val c: Offset,
    val j: Offset,
    val b: Offset,
    val k: Offset,
    val d: Offset,
    val i: Offset,
) {
    fun turned(): Path = Path().apply {
        moveTo(c.x, c.y)
        quadraticTo(e.x, e.y, b.x, b.y)
        lineTo(a.x, a.y)
        lineTo(k.x, k.y)
        quadraticTo(h.x, h.y, j.x, j.y)
        lineTo(f.x, f.y)
        close()
    }

    private fun revealSide(): Path = Path().apply {
        moveTo(c.x, c.y)
        lineTo(d.x, d.y)
        lineTo(i.x, i.y)
        lineTo(j.x, j.y)
        lineTo(f.x, f.y)
        close()
    }

    fun revealed(): Path = Path().apply { op(turned(), revealSide(), PathOperation.Intersect) }

    fun back(): Path = Path().apply { op(turned(), revealSide(), PathOperation.Difference) }

    /** 沿 e、h 连线（折痕）的镜像：纸角 f 落到 a。 */
    val mirror: Matrix
        get() {
            val dx = h.x - e.x
            val dy = h.y - e.y
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
            val ux = dx / len
            val uy = dy / len
            // 反射矩阵 R = 2uuᵀ − I，绕 e 点。
            val r00 = 2 * ux * ux - 1
            val r01 = 2 * ux * uy
            val r11 = 2 * uy * uy - 1
            val tx = e.x - (r00 * e.x + r01 * e.y)
            val ty = e.y - (r01 * e.x + r11 * e.y)
            return Matrix(
                floatArrayOf(
                    r00, r01, 0f, 0f,
                    r01, r11, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    tx, ty, 0f, 1f,
                ),
            )
        }

    companion object {
        /**
         * 纸角拉到哪：横向走 [progress] × 两倍页宽，竖向跟手指（[curlCorner]）。中间三分之一按下时按下边的角算，
         * 手指几乎贴着下边，折痕近乎竖直，整页往左翻。手指还在页内时，翻起的纸边不能伸出页面左边（伸出去就把纸角往回收）。
         */
        fun of(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float): CurlPoints? {
            if (width <= 0f || height <= 0f || progress <= 0.002f) return null
            val (corner, dragged) = curlCorner(width, height, progress, pressY, fingerY)
            val bottom = corner.y >= height / 2
            val f = Offset(width, if (bottom) height else 0f)
            val middle = corner.y != 0f && corner.y != height
            var ax = dragged.x.coerceAtMost(width - 0.5f)
            var ay = if (middle) f.y + (if (bottom) -1f else 1f) else dragged.y
            if (abs(ay - f.y) < 1f) ay = f.y + if (bottom) -1f else 1f
            var p = compute(Offset(ax, ay), f)
            if (ax > 0f && ax < width && (p.c.x < 0f || p.c.x > width)) {
                val cx = if (p.c.x < 0f) width - p.c.x else p.c.x
                val w1 = abs(f.x - ax)
                val w2 = width * w1 / cx
                ax = abs(f.x - w2)
                val h2 = abs(f.x - ax) * abs(f.y - ay) / w1
                ay = if (bottom) f.y - h2 else f.y + h2
                if (abs(ay - f.y) < 1f) ay = f.y + if (bottom) -1f else 1f
                p = compute(Offset(ax, ay), f)
            }
            return p
        }

        private fun compute(a: Offset, f: Offset): CurlPoints {
            val g = (a + f) / 2f
            val e = Offset(g.x - (f.y - g.y) * (f.y - g.y) / (f.x - g.x), f.y)
            val h = Offset(f.x, g.y - (f.x - g.x) * (f.x - g.x) / (f.y - g.y))
            val c = Offset(e.x - (f.x - e.x) / 2f, f.y)
            val j = Offset(f.x, h.y - (f.y - h.y) / 2f)
            val b = cross(a, e, c, j)
            val k = cross(a, h, c, j)
            val d = Offset((c.x + 2 * e.x + b.x) / 4f, (2 * e.y + c.y + b.y) / 4f)
            val i = Offset((j.x + 2 * h.x + k.x) / 4f, (2 * h.y + j.y + k.y) / 4f)
            return CurlPoints(a, f, e, h, c, j, b, k, d, i)
        }

        /** 直线 p1p2 和 p3p4 的交点；平行时取 p2。 */
        private fun cross(p1: Offset, p2: Offset, p3: Offset, p4: Offset): Offset {
            val d1 = p2 - p1
            val d2 = p4 - p3
            val den = d1.x * d2.y - d1.y * d2.x
            if (abs(den) < 1e-6f) return p2
            val t = ((p3.x - p1.x) * d2.y - (p3.y - p1.y) * d2.x) / den
            return p1 + d1 * t
        }
    }
}

/**
 * 画一页经典仿真：掀开处透出底下的下一页（下一页由翻页器垫在下面），贴着折痕一道阴影；当前页去掉翻起的那块；
 * 翻起的纸边在当前页上投一道窄影；最上面是翻过来的背面——不透明的纸，隐约透出反着的字，靠折痕处暗一些。
 *
 * 各块的边都是用路径画出来的遮罩（离屏层里用 DstOut 挖掉不要的部分），不是 clipPath：硬件画布上的 clipPath 没有抗锯齿，
 * 斜边和弧边一动就是一圈锯齿在闪。[shade] 在翻完前的最后一段淡到 0，翻完那一下不留影子。
 */
internal fun DrawScope.drawPageCurl(p: CurlPoints, paper: Color, shade: Float, drawPage: DrawScope.() -> Unit) {
    val turned = p.turned()
    val reach = (p.f - p.a).getDistance()
    val n = (p.f - p.a) / reach.coerceAtLeast(1f)
    val fold = (p.d + p.i) / 2f
    val whole = Rect(Offset.Zero, size)
    val turnedBounds = turned.getBounds().intersect(whole)

    /** 在离屏层里画 [content]，再用 [mask] 抠形状（[keep] 为真留下路径里面，否则挖掉路径里面），边是抗锯齿的。 */
    fun masked(bounds: Rect, mask: Path, keep: Boolean, alpha: Float = 1f, content: DrawScope.() -> Unit) {
        if (bounds.isEmpty) return
        drawIntoCanvas { it.saveLayer(bounds, Paint().apply { this.alpha = alpha }) }
        content()
        // 只留里面时挖掉“层减去路径”那块（路径外面）；DstIn 只作用在路径画到的地方，管不到路径外面。
        val cut = if (keep) {
            Path().apply {
                fillType = PathFillType.EvenOdd
                // 矩形放大几像素：它自己的抗锯齿边落在层外面，不在纸上留一圈淡线。
                addRect(bounds.inflate(4f))
                addPath(mask)
            }
        } else {
            mask
        }
        drawPath(cut, Color.Black, blendMode = BlendMode.DstOut)
        drawIntoCanvas { it.restore() }
    }

    // 1. 掀开处的下一页上，贴着折痕一道阴影，往纸角那边淡掉（画在翻起区里，背面会盖住其余部分）。
    val revealShadow = (reach / 4f).coerceIn(8.dp.toPx(), 36.dp.toPx())
    if (shade > 0f) {
        masked(turnedBounds, turned, keep = true) {
            drawRect(
                Brush.linearGradient(
                    0f to Color.Black.copy(alpha = 0.30f * shade),
                    0.35f to Color.Black.copy(alpha = 0.10f * shade),
                    1f to Color.Transparent,
                    start = fold,
                    end = fold + n * revealShadow,
                ),
            )
        }
    }
    // 2. 当前页，挖掉翻起的那块。
    masked(whole, turned, keep = false) { drawPage() }
    // 3. 翻起的纸边投在当前页上的影子：沿 a→e、a→h 两条纸边各一条窄带，往纸外面淡掉。
    if (shade > 0f) {
        val edgeShadow = (reach / 10f).coerceIn(4.dp.toPx(), 18.dp.toPx())
        val inner = (p.a + p.d + p.i) / 3f
        masked(whole, turned, keep = false) {
            for (end in listOf(p.e, p.h)) {
                val dir = end - p.a
                val len = dir.getDistance().coerceAtLeast(1f)
                var out = Offset(-dir.y / len, dir.x / len)
                if (dot(out, inner - p.a) > 0f) out = -out
                val band = Path().apply {
                    moveTo(p.a.x, p.a.y)
                    lineTo(end.x, end.y)
                    lineTo(end.x + out.x * edgeShadow, end.y + out.y * edgeShadow)
                    lineTo(p.a.x + out.x * edgeShadow, p.a.y + out.y * edgeShadow)
                    close()
                }
                drawPath(band, Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.16f * shade), Color.Transparent), start = p.a, end = p.a + out * edgeShadow))
            }
        }
    }
    // 4. 翻过来的背面：先铺一层不透明的纸，再隐约透出镜像过去的字，最后是靠折痕处的暗。
    val back = p.back()
    masked(back.getBounds().intersect(whole), back, keep = true) {
        drawRect(paper)
        drawIntoCanvas { it.saveLayer(whole, Paint().apply { alpha = 0.10f }) }
        withTransform({ transform(p.mirror) }) { drawPage() }
        drawIntoCanvas { it.restore() }
        val depth = dot(p.a - fold, -n).coerceAtLeast(1f)
        drawRect(
            Brush.linearGradient(
                0f to Color.Black.copy(alpha = 0.16f),
                0.3f to Color.Black.copy(alpha = 0.03f),
                1f to Color.Transparent,
                start = fold,
                end = fold - n * depth,
            ),
        )
    }
}

/** 单页仿真：所有版本都用 [drawPageCurl]，不需要着色器。 */
private fun Modifier.curlBezier(progress: () -> Float, touch: CurlTouch, paper: Color): Modifier = drawWithContent {
    val t = progress()
    val p = CurlPoints.of(size.width, size.height, t, touch.pressY, touch.fingerY)
    if (p == null) {
        drawContent()
        return@drawWithContent
    }
    val shade = ((1f - t) * 4f).coerceIn(0f, 1f)
    drawPageCurl(p, paper, shade) { this@drawWithContent.drawContent() }
}
