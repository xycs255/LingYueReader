package com.xycs.lingyuereader.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 灵阅的动效规范（说明见 docs/motion.md）。所有动画从这里取时长、缓动和弹簧，
 * 墨水屏 / 省电模式下一律直接跳到终态。
 *
 * 三条原则：
 * - 有来处：东西从哪里来就从哪里出现（书从封面放大打开，主题从点的地方扩散开）；
 * - 跟手：手指能拖的都用弹簧，松手接着当时的速度走；
 * - 短：大多数在 0.15–0.4 秒内结束，阅读时不打扰。
 */
object Motion {
    /** 小元素出现、颜色变化。 */
    const val SHORT = 150
    /** 浮层、卡片、列表项。 */
    const val MEDIUM = 280
    /** 整页切换、打开书。 */
    const val LONG = 420
    /** 换主题时的扩散。 */
    const val REVEAL = 560
    /**
     * 点按 / 音量键翻一页、滚一屏。Klein 与 Bederson（CHI 2005）比较了瞬间跳转和不同时长的滚动动画：
     * 阅读任务里 300 毫秒的动画最好，出错率最多降低 54%——眼睛能跟住文字移到了哪里。
     */
    const val TURN = 300

    /** 标准：起步快、收尾柔，屏幕内的移动都用它。 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 强调：进场时一下冲出来再慢慢停稳（整页、打开书）。 */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** 离场：慢慢起步、加速离开。 */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    private val reduced get() = Effects.noMotion

    fun <T> standard(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(duration, delay, Standard)

    fun <T> emphasized(duration: Int = LONG): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(duration, easing = Emphasized)

    fun <T> exit(duration: Int = SHORT): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(duration, easing = Exit)

    /** 按下、松开：干脆，略带回弹。 */
    fun <T> press(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)

    /** 跟手的东西松手后归位：不回弹。 */
    fun <T> settle(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 弹出来的小东西（气泡、角标）：轻轻弹一下。 */
    fun <T> pop(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 500f)

    /** 列表项的增删、换位；省电时不动。 */
    fun <T> listItem(): FiniteAnimationSpec<T>? = if (reduced) null else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** 按下时缩小一点，松手弹回。 */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.95f): Modifier {
    val down by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down && !Effects.noMotion) pressed else 1f, Motion.press(), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 打开书时封面在屏幕上的位置：阅读页从这里放大出来，返回时缩回这里。 */
object OpenFrom {
    var bookId: String? = null
    /** 封面中心，按窗口宽高的比例。 */
    var centerX = 0.5f
    var centerY = 0.5f
    /** 封面宽度占窗口的比例，决定从多小放大。 */
    var widthFraction = 0.3f

    fun set(bookId: String, bounds: Rect, windowWidth: Float, windowHeight: Float) {
        if (windowWidth <= 0f || windowHeight <= 0f) return
        this.bookId = bookId
        centerX = (bounds.center.x / windowWidth).coerceIn(0f, 1f)
        centerY = (bounds.center.y / windowHeight).coerceIn(0f, 1f)
        widthFraction = (bounds.width / windowWidth).coerceIn(0.1f, 0.9f)
    }

    /**
     * 缩放的支点：整页按 [widthFraction] 缩小后，中心正好落在封面中心。
     * 绕支点 p 缩到 s 倍，页面中心移到 p + s(0.5 − p)；令它等于封面中心 c，得 p = (c − 0.5s) / (1 − s)。
     */
    val pivotX: Float get() = ((centerX - 0.5f * widthFraction) / (1f - widthFraction)).coerceIn(0f, 1f)
    val pivotY: Float get() = ((centerY - 0.5f * widthFraction) / (1f - widthFraction)).coerceIn(0f, 1f)
}


/** 第一次出现时从 0 长到 [target]，之后数值变了再平滑过去（统计数字、进度条）。 */
@Composable
fun animateFromZero(target: Float, duration: Int = 700): Float {
    val value = remember { Animatable(if (Effects.noMotion) target else 0f) }
    LaunchedEffect(target) { value.animateTo(target, Motion.emphasized(duration)) }
    return value.value
}
