package com.xycs.lingyuereader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * 换主题的圆形扩散：先把当前画面拍成一张图盖在上面，换好主题后，从手指点的地方挖一个越来越大的圆，
 * 新主题从圆里露出来，旧画面在圆外收走。拍不下来（比如测试环境）或省电时直接换。
 */
class ThemeReveal internal constructor(private val scope: CoroutineScope) {
    internal var layer: GraphicsLayer? = null
    internal var snapshot by mutableStateOf<ImageBitmap?>(null)
    internal var center = Offset.Unspecified
    internal val progress = Animatable(0f)
    /** 最近一次按下的位置，[change] 不传位置时从这里扩散。 */
    internal var lastTouch = Offset.Unspecified

    fun change(apply: () -> Unit) {
        val layer = layer
        if (Effects.reduced || layer == null || snapshot != null) {
            apply()
            return
        }
        scope.launch {
            val shot = runCatching { layer.toImageBitmap() }.getOrNull()
            if (shot == null) {
                apply()
                return@launch
            }
            center = lastTouch
            progress.snapTo(0f)
            snapshot = shot
            apply()
            try {
                progress.animateTo(1f, tween(Motion.REVEAL, easing = Motion.Emphasized))
            } finally {
                snapshot = null
            }
        }
    }
}

val LocalThemeReveal = staticCompositionLocalOf<ThemeReveal?> { null }

/** 包住整个界面：随时记录画面，换主题时画扩散。 */
@Composable
fun ThemeRevealHost(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val reveal = remember { ThemeReveal(scope) }
    val layer = rememberGraphicsLayer()
    reveal.layer = layer
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) e.changes.firstOrNull()?.let { reveal.lastTouch = it.position }
                    }
                }
            }
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                val shot = reveal.snapshot ?: return@drawWithContent
                val c = if (reveal.center.isSpecified) reveal.center else Offset(size.width / 2, size.height / 2)
                // 扩到最远的角为止。
                val reach = max(max(hypot(c.x, c.y), hypot(size.width - c.x, c.y)), max(hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y)))
                val r = reach * reveal.progress.value
                val hole = Path().apply { addOval(Rect(c, r)) }
                clipPath(hole, ClipOp.Difference) { drawImage(shot) }
            },
    ) {
        CompositionLocalProvider(LocalThemeReveal provides reveal) { content() }
    }
}
