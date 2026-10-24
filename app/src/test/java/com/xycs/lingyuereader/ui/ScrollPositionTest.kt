package com.xycs.lingyuereader.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.rememberCoroutineScope

/** 滚动模式不必每一帧都报阅读位置：滚动中最多每 200 毫秒一次，停下后报最终位置。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollPositionTest {
    @get:Rule
    val compose = createComposeRule()

    private val noSelect = object : TextSelector {
        override fun press(block: Int, char: Int) {}
        override fun register(key: Any, block: Int, coords: LayoutCoordinates, layout: TextLayoutResult, toChar: (Int) -> Int, toLayout: (Int) -> Int) {}
        override fun unregister(key: Any) {}
    }

    @Test
    fun reportsAFewTimesWhileScrollingAndTheFinalPositionWhenItStops() {
        val chapter = Chapter("第一章", (1..80).map { Block.Para("第${it}段，城门口排着长队，挑担的、推车的、牵驴的，一直排到护城河边。") })
        val reports = mutableListOf<Int>()
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            ScrollReader(
                chapter = chapter, chapterIndex = 0, nextTitle = null,
                styles = ReaderStyles(ReaderSettings(), ReaderTheme.PAPER.palette()), marks = Marks(), listState = list,
                topInset = 0.dp, bottomInset = 0.dp, onTap = {}, selector = noSelect,
                onPosition = { item, _, _, _ -> reports += item }, onNext = {}, registerTurner = {},
            )
        }
        compose.waitForIdle()
        reports.clear()
        compose.mainClock.autoAdvance = false
        // 一秒钟、60 帧，每帧滚 12 像素。
        repeat(60) {
            scope.launch { list.scrollBy(12f) }
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue("滚动中报了 ${reports.size} 次", reports.size in 2..8)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(list.firstVisibleItemIndex, reports.last())
    }
}
