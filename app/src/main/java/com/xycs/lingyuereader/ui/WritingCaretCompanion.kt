package com.xycs.lingyuereader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.R
import com.xycs.lingyuereader.ai.AiConfig
import kotlin.math.roundToInt

/** 光标位置来自实际排版结果，再换算进正文视口；内置滚动和键盘缩放均由此一起处理。 */
internal data class EditorCompanionPosition(val caret: Rect, val lineEnd: Boolean, val contentRight: Float)

internal fun editorCompanionPosition(
    layout: TextLayoutResult,
    selection: Int,
    text: LayoutCoordinates,
    viewport: LayoutCoordinates,
): EditorCompanionPosition? {
    if (!text.isAttached || !viewport.isAttached) return null
    val offset = selection.coerceIn(0, layout.layoutInput.text.length)
    val cursor = layout.getCursorRect(offset)
    val origin = viewport.localPositionOf(text, Offset.Zero)
    val caret = Rect(
        origin.x + cursor.left,
        origin.y + cursor.top,
        origin.x + cursor.right,
        origin.y + cursor.bottom,
    )
    // 垂直滚动在绘制时再算，避免每滚动一像素就重组整个编辑器。
    if (caret.right <= 0f || caret.left >= viewport.size.width) return null
    val line = layout.getLineForOffset(offset)
    return EditorCompanionPosition(
        caret = caret,
        lineEnd = offset == layout.getLineEnd(line, visibleEnd = true),
        contentRight = origin.x + text.size.width,
    )
}

/** 六位角色是灵阅原创形象，按当前模型选气质；未知模型用通用书灵。 */
internal fun companionIndex(config: AiConfig): Int {
    if (!config.ready) return 0
    val model = config.model.lowercase()
    return when {
        config.preset == "claude" || "claude" in model -> 1 // 编辑者
        config.preset == "deepseek" || "deepseek" in model -> 3 // 推演者
        config.preset == "qwen" || "qwen" in model -> 4 // 文脉者
        "gemini" in model -> 2 // 观察者
        "doubao" in model -> 5 // 陪伴者
        else -> 0 // 策划者，含 GPT、Kimi、GLM 及未识别的模型
    }
}

/** 只绘制，不拦截点击、选择或输入法事件；头像待笔停稳才在行末或页边出现。 */
@Composable
internal fun WritingCaretCompanion(position: EditorCompanionPosition, scroll: ScrollState, config: AiConfig, accent: Color) {
    val portraits = ImageBitmap.imageResource(R.drawable.writing_companions)
    val index = companionIndex(config)
    val opacity = if (Effects.noMotion) 1f else {
        val breathing = rememberInfiniteTransition(label = "writingCompanion")
        breathing.animateFloat(
            initialValue = 0.83f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse),
            label = "breathing",
        ).value
    }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val y = scroll.value.toFloat()
        val caret = Rect(position.caret.left, position.caret.top - y,
            position.caret.right, position.caret.bottom - y)
        if (caret.bottom <= 0f || caret.top >= size.height) return@Canvas
        val alpha = opacity
        val icon = 22.dp.toPx().roundToInt()
        val gap = 4.dp.toPx()
        // 行末有空白时贴近光标；光标在文字中间时退到当前行外侧页边，避免压住正文。
        val beside = position.lineEnd && caret.right + gap + icon < position.contentRight - gap
        val left = if (beside) caret.right + gap else if (caret.left < size.width / 2f) 0f else size.width - icon
        val top = ((caret.top + caret.bottom - icon) / 2f).coerceIn(0f, (size.height - icon).coerceAtLeast(0f))
        clipRect(0f, 0f, size.width, size.height) {
            drawLine(
                color = accent,
                start = Offset(caret.left, caret.top.coerceAtLeast(0f)),
                end = Offset(caret.left, caret.bottom.coerceAtMost(size.height)),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(accent.copy(alpha = 0.10f * alpha), icon / 2f, Offset(left + icon / 2f, top + icon / 2f))
            // 原图为 3×2 透明图，每格 512px。取头像局部以便 22dp 大小时仍能认出表情。
            val portraitShape = Path().apply { addOval(Rect(left, top, left + icon, top + icon)) }
            clipPath(portraitShape) {
                drawImage(
                    image = portraits,
                    srcOffset = IntOffset(index % 3 * 512 + 106, index / 3 * 512 + 58),
                    srcSize = IntSize(300, 300),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(icon, icon),
                    alpha = alpha,
                )
            }
        }
    }
}
