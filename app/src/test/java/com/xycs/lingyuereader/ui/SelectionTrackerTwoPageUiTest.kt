package com.xycs.lingyuereader.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 横屏双页同一高度都有文字时，长按右页后拖动仍应命中右页，放大镜取样点也应留在右页。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-xxhdpi")
class SelectionTrackerTwoPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rightPageLongPressAndDragStayOnRightPage() {
        val pressed = mutableListOf<Int>()
        val dragged = mutableListOf<Int>()
        val tracker = SelectionTracker(
            onPress = { block, _ -> pressed += block },
            onDrag = { block, _ -> dragged += block },
            onRelease = {},
            onHandle = { _, _, _, _ -> },
        )
        compose.setContent {
            val density = LocalDensity.current
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("reader")
                    .onGloballyPositioned {
                        tracker.root = it
                        tracker.dp = density.density
                        tracker.lensHalfWidth = with(density) { 58.dp.toPx() }
                    }
                    .pointerInput(tracker) {
                        awaitPointerEventScope {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                                tracker.finger = if (change.pressed) change.position else Offset.Unspecified
                                if (tracker.mode != null) {
                                    change.consume()
                                    if (change.pressed) tracker.track(change.position, 0f, 0f, change.uptimeMillis)
                                    else tracker.finish(change.uptimeMillis)
                                }
                            }
                        }
                    },
            ) {
                Row(Modifier.fillMaxSize()) {
                    listOf("左页独有文字，左页独有内容。", "右页独有文字，右页独有内容。").forEachIndexed { page, text ->
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val key = remember { Any() }
                        fun register() {
                            val measured = layout ?: return
                            val placed = coords ?: return
                            if (placed.isAttached) tracker.register(key, page, placed, measured, { it }, { it })
                        }
                        DisposableEffect(tracker, key) { onDispose { tracker.unregister(key) } }
                        Box(Modifier.weight(1f).padding(24.dp)) {
                            BasicText(
                                text,
                                onTextLayout = { layout = it; register() },
                                modifier = Modifier
                                    .testTag(if (page == 0) "left" else "right")
                                    .onGloballyPositioned { coords = it; register() }
                                    .pointerInput(page) {
                                        detectTapGestures(onLongPress = { tracker.press(page, 2) })
                                    },
                            )
                        }
                    }
                }
            }
        }

        val right = compose.onNodeWithTag("right").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot
        val pressPoint = Offset(right.left - root.left + with(compose.density) { 20.dp.toPx() }, right.center.y - root.top)
        assertTrue("测试点必须位于右页", pressPoint.x > root.width / 2f)
        compose.onNodeWithTag("reader").performTouchInput { down(pressPoint) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { pressed.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, pressed.last())
            assertTrue("右页长按后应有放大镜取样点", tracker.lens.isSpecified)
            assertTrue("放大镜应取右页文字", tracker.lens.x > root.width / 2f)
        }

        compose.onNodeWithTag("reader").performTouchInput { moveTo(pressPoint + Offset(with(compose.density) { 28.dp.toPx() }, 0f)) }
        compose.waitUntil(5_000) { dragged.isNotEmpty() }
        compose.runOnIdle { assertEquals("拖动不能跳到左页", 1, dragged.last()) }
        compose.onNodeWithTag("reader").performTouchInput { up() }
        compose.runOnIdle { assertTrue("松手后应收起放大镜", !tracker.lens.isSpecified) }
    }
}
