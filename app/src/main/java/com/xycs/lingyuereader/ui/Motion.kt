package com.xycs.lingyuereader.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
    /**
     * 仿真翻页点按 / 按键翻一页：纸要卷起来、翻过去、落下，300 毫秒只够“啪”一下。
     * 用长一些的时长和先慢后快再慢的曲线（[Curl]），看得出纸是被掀起来的。
     */
    const val CURL_TURN = 440

    /** 书页配色随环境光、日落变暖渐变（读者亲手换配色用 [MEDIUM]）。 */
    const val PALETTE = 3000
    /** 远眺提醒的远景淡入、淡出。 */
    const val REST_FADE_IN = 1500
    const val REST_FADE_OUT = 1200
    /** 滚动模式翻一屏后，原来底边那道短线先停 [MARKER_HOLD] 再用 [MARKER_FADE] 褪掉。 */
    const val MARKER_HOLD = 500
    const val MARKER_FADE = 1400
    /** 跳转落点那一段的淡强调色：停 [LANDING_HOLD] 再用 [LANDING_FADE] 褪掉。 */
    const val LANDING_HOLD = 700
    const val LANDING_FADE = 1600
    /** 统计页打开时数字从 0 往上数（[animateFromZero] 的默认时长）。 */
    const val COUNT_UP = 900

    /** 标准：起步快、收尾柔，屏幕内的移动都用它。 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 强调：进场时一下冲出来再慢慢停稳（整页、打开书）。 */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** 离场：慢慢起步、加速离开。 */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** 仿真翻页：纸先被慢慢掀起，中间翻得快，落下时再放慢。 */
    val Curl = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)

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

    /**
     * 浮层从它贴着的底边升起（一半高度）并淡入：编辑器键盘上方的面板、页底的条。
     * 和阅读页的听书条、自动滚动条一样：强调缓动 [MEDIUM] 升起、[SHORT] 淡入；减少动效时直接出现。
     */
    fun edgeEnter(): EnterTransition =
        Effects.enter(slideInVertically(emphasized(MEDIUM)) { it / 2 } + fadeIn(standard(SHORT)))

    /** [edgeEnter] 的反向：用离场缓动落回底边并淡出；减少动效时直接消失。 */
    fun edgeExit(): ExitTransition =
        Effects.exit(slideOutVertically(exit()) { it / 2 } + fadeOut(exit()))

    /**
     * 原地换内容（标签页、上一条 / 下一条）：旧的 [SHORT] 里淡出，新的稍后淡入，高矮用标准缓动过去；
     * 给 AnimatedContent 的 transitionSpec 用。减少动效时直接换，高度也不渐变。
     */
    fun fadeThrough(): ContentTransform =
        if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else ContentTransform(
            fadeIn(standard(MEDIUM, delay = SHORT / 2)),
            fadeOut(exit(SHORT)),
            sizeTransform = SizeTransform(clip = false) { _, _ -> standard(MEDIUM) },
        )
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
fun animateFromZero(target: Float, duration: Int = Motion.COUNT_UP): Float {
    val value = remember { Animatable(if (Effects.noMotion) target else 0f) }
    LaunchedEffect(target) { value.animateTo(target, Motion.emphasized(duration)) }
    return value.value
}
