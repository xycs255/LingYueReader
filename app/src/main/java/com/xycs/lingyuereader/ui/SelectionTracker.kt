package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult

/**
 * 正文选字的总调度，挂在阅读区最外层：
 * - 各文字块把自己的排版和位置登记进来（[register]），这样手指在屏幕上的任何位置都能换算成“第几块第几个字”，
 *   划选可以跨段落；
 * - 长按选字（[press]）或按住把手（[beginHandle]）后，后续的移动由阅读区统一跟踪（[track]），
 *   起手的那一段即使滚出屏幕被回收，拖动也不会断；
 * - 手指靠近上下边缘时给出 [edge]，滚动模式据此自动滚动。
 */
internal class SelectionTracker(
    private val onPress: (block: Int, char: Int) -> Unit,
    private val onDrag: (block: Int, char: Int) -> Unit,
    private val onRelease: () -> Unit,
    private val onHandle: (start: Boolean, block: Int, cursor: Int) -> Unit,
) : TextSelector {
    enum class Mode { SELECT, START_HANDLE, END_HANDLE }

    private class Entry(
        val block: Int,
        var coords: LayoutCoordinates,
        var layout: TextLayoutResult,
        val toChar: (Int) -> Int,
        val toLayout: (Int) -> Int,
    )

    private val entries = LinkedHashMap<Any, Entry>()

    /** 阅读区的坐标（[track] 收到的位置都相对于它）。 */
    var root: LayoutCoordinates? = null

    /** 正在跟踪的拖动；为空表示没有。 */
    var mode: Mode? = null
        private set

    /** 登记的块位置变了就加一，用到选区位置的地方据此重新计算（比如操作条跟着滚动）。 */
    var version by mutableIntStateOf(0)
        private set

    /** 手指在上边缘为 -1、下边缘为 1、其余为 0。 */
    var edge by mutableIntStateOf(0)
        private set

    /** 滚动模式登记的滚动函数（翻页模式没有，跨段只在当前页内）。 */
    var scroller: (suspend (Float) -> Unit)? = null

    private var last: Offset? = null

    override fun press(block: Int, char: Int) {
        mode = Mode.SELECT
        last = null
        onPress(block, char)
    }

    override fun beginHandle(start: Boolean) {
        mode = if (start) Mode.START_HANDLE else Mode.END_HANDLE
        last = null
    }

    override fun register(key: Any, block: Int, coords: LayoutCoordinates, layout: TextLayoutResult, toChar: (Int) -> Int, toLayout: (Int) -> Int) {
        val e = entries[key]
        if (e == null || e.block != block) {
            entries[key] = Entry(block, coords, layout, toChar, toLayout)
        } else {
            e.coords = coords
            e.layout = layout
        }
        version++
    }

    override fun unregister(key: Any) {
        if (entries.remove(key) != null) version++
    }

    /** 手指移到 [position]（阅读区坐标）。把手的位置在手指上方，换算时往上挪 [handleLift]。 */
    fun track(position: Offset, edgeSize: Float, handleLift: Float) {
        val m = mode ?: return
        last = position
        val h = root?.size?.height ?: 0
        edge = when {
            h == 0 -> 0
            position.y < edgeSize -> -1
            position.y > h - edgeSize -> 1
            else -> 0
        }
        val target = if (m == Mode.SELECT) position else position.copy(y = position.y - handleLift)
        val (block, offset) = resolve(target) ?: return
        when (m) {
            Mode.SELECT -> onDrag(block, offset)
            Mode.START_HANDLE -> onHandle(true, block, offset)
            Mode.END_HANDLE -> onHandle(false, block, offset)
        }
    }

    /** 自动滚动后，手指没动但下面的字变了：按上次的位置再算一次。 */
    fun retrack(edgeSize: Float, handleLift: Float) {
        last?.let { track(it, edgeSize, handleLift) }
    }

    fun finish() {
        val m = mode ?: return
        mode = null
        edge = 0
        last = null
        if (m == Mode.SELECT) onRelease()
    }

    /** 阅读区里某点落在哪一块的哪个字上；不在任何块里时取上下最近的块。 */
    private fun resolve(p: Offset): Pair<Int, Int>? {
        val r = root?.takeIf { it.isAttached } ?: return null
        val width = r.size.width.toFloat()
        var best: Pair<Entry, Rect>? = null
        var bestDistance = Float.MAX_VALUE
        for (e in entries.values) {
            if (!e.coords.isAttached) continue
            val b = r.localBoundingBoxOf(e.coords, clipBounds = false)
            // 翻页模式里左右两页都排着，不在屏幕上的那页不算。
            if (b.right <= 0f || b.left >= width) continue
            val d = when {
                p.y < b.top -> b.top - p.y
                p.y > b.bottom -> p.y - b.bottom
                else -> 0f
            }
            if (d < bestDistance) {
                bestDistance = d
                best = e to b
            }
        }
        val (e, b) = best ?: return null
        val local = Offset(
            (p.x - b.left).coerceIn(0f, e.layout.size.width.toFloat()),
            (p.y - b.top).coerceIn(0f, (e.layout.size.height - 1).coerceAtLeast(0).toFloat()),
        )
        return e.block to e.toChar(e.layout.getOffsetForPosition(local))
    }

    /**
     * 选区在阅读区里的范围（首字所在行的顶到末字所在行的底），给操作条定位。
     * 首字或末字所在的块不在屏幕上时，用阅读区的上边或下边代替。读 [version] 以便滚动时重新计算。
     */
    fun bounds(h: Highlight): Rect? {
        version
        val r = root?.takeIf { it.isAttached } ?: return null
        val width = r.size.width.toFloat()
        fun visible(block: Int) = entries.values.filter { it.block == block && it.coords.isAttached }
            .map { it to r.localBoundingBoxOf(it.coords, clipBounds = false) }
            .firstOrNull { (_, b) -> b.right > 0f && b.left < width }
        fun lineTop(e: Entry, b: Rect, char: Int): Float? {
            val len = e.layout.layoutInput.text.length
            val o = e.toLayout(char)
            if (o < 0 || o >= len) return null
            return b.top + e.layout.getLineTop(e.layout.getLineForOffset(o))
        }
        fun lineBottom(e: Entry, b: Rect, char: Int): Float? {
            val len = e.layout.layoutInput.text.length
            val o = e.toLayout(char)
            if (o < 0 || o >= len) return null
            return b.top + e.layout.getLineBottom(e.layout.getLineForOffset(o))
        }
        val top = visible(h.startBlock)?.let { (e, b) -> lineTop(e, b, h.startChar) } ?: 0f
        val bottom = visible(h.endBlock)?.let { (e, b) -> lineBottom(e, b, h.endChar) } ?: r.size.height.toFloat()
        return Rect(0f, top.coerceAtLeast(0f), width, bottom.coerceAtMost(r.size.height.toFloat()))
    }
}
