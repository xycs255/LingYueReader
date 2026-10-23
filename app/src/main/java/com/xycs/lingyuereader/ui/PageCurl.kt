package com.xycs.lingyuereader.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 仿真翻页的几何。要翻走的那页，右边一角（手指在下半屏按下是右下角，上半屏是右上角）被拉到左边，
 * 折痕是原来的角和它新位置连线的垂直平分线：折痕左边的纸平铺不动（[flat]），右边那块（[lifted]）翻过来，
 * 背面朝上盖在平铺的纸上，露出底下的下一页。
 *
 * [progress] 从 0（没动）到 1（整页翻过去），折痕跟着从右边退到左边；翻到一半时角被提得最高，折痕最斜。
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
        fun of(width: Float, height: Float, progress: Float, touchY: Float): CurlGeometry? {
            if (width <= 0f || height <= 0f || progress <= 0.002f) return null
            val t = progress.coerceAtMost(1f)
            val y = if (touchY < 0f) height * 0.8f else touchY
            val cornerY = if (y >= height / 2) height else 0f
            // 手指离那个角越远，角被提得越高；翻完时回落，折痕正好退到最左边。
            val lift = (y - cornerY).coerceIn(-height / 2, height / 2) * 0.7f * sin(PI.toFloat() * t)
            val corner = Offset(width, cornerY)
            val dragged = Offset(width * (1 - 2 * t), cornerY + lift)
            val d = corner - dragged
            val len = d.getDistance()
            if (len < 0.5f) return null
            val normal = d / len
            val fold = (corner + dragged) / 2f
            val page = listOf(Offset(0f, 0f), Offset(width, 0f), Offset(width, height), Offset(0f, height))
            return CurlGeometry(fold, normal, clip(page, fold, normal, keepLifted = false), clip(page, fold, normal, keepLifted = true))
        }

        private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

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

private fun List<Offset>.toPath() = Path().also { path ->
    forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
}

/**
 * 画正在被翻走的一页：平铺的部分照常画，翻起的部分镜像过来、蒙一层纸色当作背面（隐约透出反着的字），
 * 折痕处加阴影。[progress] 为 0 时原样画。
 */
internal fun Modifier.pageCurl(progress: () -> Float, touchY: () -> Float, paper: Color): Modifier = drawWithContent {
    val g = CurlGeometry.of(size.width, size.height, progress(), touchY())
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
    val depth = g.lifted.maxOf { (it - g.fold).let { d -> d.x * g.normal.x + d.y * g.normal.y } }.coerceAtLeast(1f)
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
