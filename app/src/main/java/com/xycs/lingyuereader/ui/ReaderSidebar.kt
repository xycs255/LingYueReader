package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp

/** 阅读时从左边展开目录：保留书页的一部分，列表占满剩余高度。 */
@Composable
internal fun ReaderSidebar(
    show: Boolean,
    background: Color,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = show
    if (!state.currentState && !state.targetState) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val scrim by animateFloatAsState(if (show) 0.30f else 0f, Motion.standard(Motion.SHORT), label = "catalogScrim")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(
                interactionSource = null,
                indication = null,
                onClick = onDismissRequest,
            ).semantics { contentDescription = "关闭目录侧栏" })
            AnimatedVisibility(
                visibleState = state,
                enter = Effects.enter(slideInHorizontally(Motion.standard(Motion.MEDIUM)) { -it } + fadeIn(Motion.standard(Motion.SHORT))),
                exit = Effects.exit(slideOutHorizontally(Motion.exit(Motion.MEDIUM)) { -it } + fadeOut(Motion.exit())),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Box(
                    Modifier
                        .width((maxWidth * 0.86f).coerceAtMost(360.dp))
                        .fillMaxHeight()
                        .shadow(16.dp, RoundedCornerShape(topEnd = Radius.XLarge, bottomEnd = Radius.XLarge))
                        .clip(RoundedCornerShape(topEnd = Radius.XLarge, bottomEnd = Radius.XLarge))
                        .background(background)
                        .clickable(interactionSource = null, indication = null) {},
                ) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) { content() }
                }
            }
        }
    }
}
