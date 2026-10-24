package com.xycs.lingyuereader.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
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
 * 翻到头和尾，角都回到原来的高度，免得翻完那一下跳一跳。
 */
internal fun curlCorner(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float): Pair<Offset, Offset> {
    val t = progress.coerceIn(0f, 1f)
    val y0 = if (pressY < 0f) height * 0.9f else pressY
    val fy = if (fingerY < 0f) y0 else fingerY
    val x = width - 2 * width * t
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
        fun of(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float = -1f): CurlGeometry? {
            if (width <= 0f || height <= 0f || progress <= 0.002f) return null
            val (corner, dragged) = curlCorner(width, height, progress, pressY, fingerY)
            val d = corner - dragged
            val len = d.getDistance()
            if (len < 0.5f) return null
            val normal = d / len
            val fold = (corner + dragged) / 2f
            val page = listOf(Offset(0f, 0f), Offset(width, 0f), Offset(width, height), Offset(0f, height))
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
 * 卷着的纸（Android 13 起用）：纸不是沿一条线折过去，而是绕着一根半径 [radius] 的圆柱卷起来，
 * 从上往下看——
 *
 * - 折痕（[fold]）左边平铺不动；
 * - 往右 [radius] 以内是圆柱：靠下的半圈是正面，越往外越斜、越暗；靠上的半圈是背面，翻过顶以后接着平铺回左边；
 * - 再往右是空的，露出底下的下一页，靠近圆柱处有一道阴影。
 *
 * 被拉着的角在 [curlCorner] 给的位置，所以折痕要从那里往回推：角离原位 L，绕过半圈多走 πR，折痕在离原角 (L + πR) / 2 处。
 * 刚开始拉时 L 很小，半径跟着缩小（R ≤ L / π），角不会一按下就跳开。
 */
internal class CurlCylinder(
    val fold: Offset,
    val normal: Offset,
    val radius: Float,
    /** 阴影深浅：最后四分之一淡掉，翻完那一下左边不留一道影子。 */
    val shade: Float,
) {
    companion object {
        fun of(width: Float, height: Float, progress: Float, pressY: Float, fingerY: Float): CurlCylinder? {
            if (width <= 0f || height <= 0f || progress <= 0.002f) return null
            val (corner, dragged) = curlCorner(width, height, progress, pressY, fingerY)
            val d = corner - dragged
            val len = d.getDistance()
            if (len < 0.5f) return null
            val normal = d / len
            val radius = min(min(width, height) / 8f, len / PI.toFloat())
            val fromCorner = (len + PI.toFloat() * radius) / 2f
            val shade = ((1f - progress) * 4f).coerceIn(0f, 1f)
            return CurlCylinder(corner - normal * fromCorner, normal, radius, shade)
        }
    }
}

/** 正面平铺、正面在圆柱上、背面在圆柱上、背面平铺、露出的下一页上的阴影，见 [CurlCylinder]。 */
internal const val CURL_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 fold;
uniform float2 normal;
uniform float radius;
uniform float shadow;
uniform float shade;
uniform half4 paper;

const float PI = 3.14159265;

// 纸上这点还在页内多深（像素）；在页外是负的。用来给纸边抗锯齿。
float inside(float2 q) {
    return min(min(q.x, size.x - q.x), min(q.y, size.y - q.y));
}

half4 back(float2 q, float light) {
    half4 c = content.eval(q);
    // 背面是纸色，隐约透出反着的字。
    half3 rgb = mix(c.rgb, paper.rgb, 0.86) * light;
    return half4(rgb, 1.0);
}

half4 front(float2 q, float light) {
    half4 c = content.eval(q);
    return half4(c.rgb * light, c.a);
}

half4 over(half4 under, half4 top, float coverage) {
    return mix(under, top, half(clamp(coverage + 0.5, 0.0, 1.0)));
}

half4 main(float2 p) {
    float d = dot(p - fold, normal);
    float r = max(radius, 0.5);
    if (d <= 0.0) {
        // 平铺的正面；翻过来的背面压在上面，边上投一道淡影。
        half4 base = content.eval(p);
        float s = PI * r - d;
        float2 q = p + normal * (s - d);
        float2 outside = max(max(-q, q - size), 0.0);
        float gap = length(outside);
        if (gap > 0.0) {
            float a = shade * 0.22 * pow(1.0 - clamp(gap / shadow, 0.0, 1.0), 2.0);
            base = half4(base.rgb * half(1.0 - a), base.a);
        }
        return over(base, back(q, 1.0), inside(q));
    }
    // 圆柱外：空出来的地方，靠近圆柱的阴影最深。
    float fade = clamp((d - r) / shadow, 0.0, 1.0);
    half4 result = half4(0.0, 0.0, 0.0, half(shade * 0.38 * (1.0 - fade) * (1.0 - fade)));
    if (d < r) {
        float a = asin(d / r);
        // 正面：从平铺处往上转，越转越侧向，越暗。
        float2 qf = p + normal * (r * a - d);
        result = over(result, front(qf, 0.78 + 0.22 * cos(a)), inside(qf));
        // 背面：翻过顶部朝上，顶上最亮；靠外的一侧侧向光，暗。
        float2 qb = p + normal * (r * (PI - a) - d);
        result = over(result, back(qb, 0.72 + 0.28 * cos(a)), inside(qb));
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
 */
internal fun Modifier.pageCurl(progress: () -> Float, touch: CurlTouch, paper: Color): Modifier =
    if (curlShaderSupported) curlShader(progress, touch, paper) else curlFlat(progress, touch, paper)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.curlShader(progress: () -> Float, touch: CurlTouch, paper: Color): Modifier = composed {
    val shader = remember { RuntimeShader(CURL_SHADER) }
    graphicsLayer {
        val c = CurlCylinder.of(size.width, size.height, progress(), touch.pressY, touch.fingerY)
        renderEffect = c?.let {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("fold", it.fold.x, it.fold.y)
            shader.setFloatUniform("normal", it.normal.x, it.normal.y)
            shader.setFloatUniform("radius", it.radius)
            shader.setFloatUniform("shadow", 28.dp.toPx())
            shader.setFloatUniform("shade", it.shade)
            shader.setFloatUniform("paper", paper.red, paper.green, paper.blue, 1f)
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

private fun List<Offset>.toPath() = Path().also { path ->
    forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
}

/** 平折：平铺的部分照常画，翻起的部分镜像过来、蒙一层纸色当作背面（隐约透出反着的字），折痕处加阴影。 */
private fun Modifier.curlFlat(progress: () -> Float, touch: CurlTouch, paper: Color): Modifier = drawWithContent {
    val g = CurlGeometry.of(size.width, size.height, progress(), touch.pressY, touch.fingerY)
    if (g == null) {
        drawContent()
        return@drawWithContent
    }
    if (g.flat.size >= 3) clipPath(g.flat.toPath()) { this@drawWithContent.drawContent() }
    if (g.lifted.size < 3) return@drawWithContent
    val lifted = g.lifted.toPath()
    // 掀起来露出的下一页上，折痕旁边一道阴影。
    clipPath(lifted) {
        drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent), start = g.fold, end = g.fold + g.normal * 32.dp.toPx()))
    }
    // 翻过来的背面。
    val depth = g.lifted.maxOf { dot(it - g.fold, g.normal) }.coerceAtLeast(1f)
    withTransform({ transform(g.matrix) }) {
        clipPath(lifted) {
            this@drawWithContent.drawContent()
            drawRect(paper.copy(alpha = 0.86f))
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
