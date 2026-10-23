package com.xycs.lingyuereader.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 墨水屏 / 省电模式的总开关：开着时不画玻璃、不做动画、书页纯黑白。
 * 墨水屏刷新慢，动画和半透明只会留下残影；旧手机上也能省些电。
 */
object Effects {
    var reduced by mutableStateOf(false)

    /**
     * 系统里关掉了动画（无障碍“移除动画”，或开发者选项里动画时长缩放为 0）。
     * 对前庭敏感、看动画头晕的人，非必要的动效都应能关掉（WCAG 2.3.3）；只关动效，玻璃和配色照旧。
     */
    var systemNoMotion by mutableStateOf(false)

    /** 不做动画：省电模式，或系统关了动画。 */
    val noMotion: Boolean get() = reduced || systemNoMotion

    /** 动画：不做动画时直接出现、直接消失。 */
    fun enter(normal: EnterTransition): EnterTransition = if (noMotion) EnterTransition.None else normal
    fun exit(normal: ExitTransition): ExitTransition = if (noMotion) ExitTransition.None else normal

    /** 墨水屏的书页：白底黑字；选了夜间就黑底白字。 */
    fun eInkPalette(night: Boolean): ReaderPalette =
        if (night) ReaderPalette(Color.Black, Color.White, Color(0xFFBBBBBB), Color.White, Color.Black, true)
        else ReaderPalette(Color.White, Color.Black, Color(0xFF444444), Color.Black, Color.White, false)
}
