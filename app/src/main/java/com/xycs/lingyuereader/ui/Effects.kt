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

    /** 动画：省电模式下直接出现、直接消失。 */
    fun enter(normal: EnterTransition): EnterTransition = if (reduced) EnterTransition.None else normal
    fun exit(normal: ExitTransition): ExitTransition = if (reduced) ExitTransition.None else normal

    /** 墨水屏的书页：白底黑字；选了夜间就黑底白字。 */
    fun eInkPalette(night: Boolean): ReaderPalette =
        if (night) ReaderPalette(Color.Black, Color.White, Color(0xFFBBBBBB), Color.White, Color.Black, true)
        else ReaderPalette(Color.White, Color.Black, Color(0xFF444444), Color.Black, Color.White, false)
}
