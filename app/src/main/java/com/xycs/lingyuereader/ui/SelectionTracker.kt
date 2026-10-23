package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.isSpecified
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
 *
 * 手感上的几处处理参照 Android 系统 TextView（AOSP Editor）和 Compose 的选字：
 * 行间回差、放大镜锁在行中线、长按后先过触摸容差再开始划、换行后把选字点抬到手指上方、松手时撤回抬手那一下的抖动。
 */
internal class SelectionTracker(
    private val onPress: (block: Int, char: Int) -> Unit,
    private val onDrag: (block: Int, char: Int) -> Unit,
    private val onRelease: () -> Unit,
    /** [lineChanged]：这次换到了另一行（或另一段），按词对齐。 */
    private val onHandle: (start: Boolean, block: Int, cursor: Int, lineChanged: Boolean) -> Unit,
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

    /** 1dp 是多少像素，触摸容差、行间回差等按它换算；由阅读页设定。 */
    var dp = 1f

    /** 触摸容差（像素）：长按后手指移过这么远才开始划选，免得按住时手指一抖就把选好的词改掉。 */
    var touchSlop = 8f

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
    var scroller: (suspend (Float) -> Float)? = null

    private var last: Offset? = null
    private var lastTime = 0L

    /** 长按的位置；手指离开它超过 [touchSlop] 后才开始划选（[moved]）。 */
    private var pressAt = Offset.Unspecified

    /** 手指已经开始划（或按住了把手）；之后选区的每次变化都轻震一下。 */
    var moved = false
        private set

    /** 划选时手指换过行：之后选的是手指上方一点的字，手指不挡住正在选的那行（AOSP 的做法）。 */
    private var lifted = false

    override fun press(block: Int, char: Int) {
        mode = Mode.SELECT
        reset()
        pressAt = finger
        // 一按住就在这一行出放大镜，不等手指移动。
        if (finger.isSpecified) resolve(finger, caret = false)
        onPress(block, char)
    }

    /** 手指和把手光标的相对位置，按下时定下。 */
    private var grab: Offset? = null

    /** 按住了选区起点或终点的把手，[caret] 是那一端光标所在行的中点（阅读区坐标）。之后的移动由阅读区跟踪。 */
    fun beginHandle(start: Boolean, caret: Offset) {
        mode = if (start) Mode.START_HANDLE else Mode.END_HANDLE
        reset()
        moved = true
        // 手指按下的位置阅读区先收到（[finger] 已是按下处），这时就定下手指和光标的相对位置。
        grab = finger.takeIf { it.isSpecified }?.let { it - caret }
        resolve(caret, caret = true)
    }

    private fun reset() {
        last = null
        stuck = null
        lifted = false
        moved = false
        grab = null
        history.clear()
        lens = Offset.Unspecified
    }

    /** 手指当前的位置（阅读区坐标），按下时就开始更新。 */
    var finger by mutableStateOf(Offset.Unspecified)

    /**
     * 放大镜要放大的点（阅读区坐标）：高度锁在正在选的那一行的中线，横向跟手但不超出这一行的字；
     * 手指横向离开这一行的字太远（超过放大镜半宽）时为 Unspecified，放大镜收起。不在选字时也是 Unspecified。
     * 跟系统和 Compose 的做法一样：手指上下抖一点，放大镜里的字不跟着晃。
     */
    var lens by mutableStateOf(Offset.Unspecified)
        private set

    /** 放大镜半宽（像素），由阅读页设定，用来判断手指是否离开了这一行。 */
    var lensHalfWidth = 0f

    /** 上一次落在哪一块的哪一行：手指在两行交界附近时留在原来那行，不来回跳。 */
    private var stuck: Pair<Entry, Int>? = null

    /** 最近几次选到的位置和时间，松手时用来撤回抬手那一下的抖动。 */
    private class Step(val time: Long, val block: Int, val offset: Int, val lineChanged: Boolean)
    private val history = ArrayDeque<Step>()

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

    /**
     * 手指在 [time] 时刻移到 [position]（阅读区坐标）。拖把手时按按下那一刻手指和光标的相对位置换算（按下去选区不跳）；
     * 拿不到光标位置时退回往上挪 [handleLift]。
     */
    fun track(position: Offset, edgeSize: Float, handleLift: Float, time: Long = lastTime) {
        val m = mode ?: return
        if (!position.isSpecified) return
        last = position
        lastTime = time
        if (!moved) {
            if (pressAt.isSpecified && (position - pressAt).getDistance() <= touchSlop) return
            moved = true
        }
        val h = root?.size?.height ?: 0
        edge = when {
            h == 0 -> 0
            position.y < edgeSize -> -1
            position.y > h - edgeSize -> 1
            else -> 0
        }
        val target = when (m) {
            Mode.SELECT -> if (lifted) position - Offset(0f, SELECT_LIFT * dp) else position
            else -> position - (grab ?: Offset(0f, handleLift))
        }
        val before = stuck
        // 选字时取手指下面的字；拖把手时取光标（字缝）位置。
        val (block, offset) = resolve(target, caret = m != Mode.SELECT) ?: return
        val lineChanged = before != null && stuck != null && (before.first !== stuck!!.first || before.second != stuck!!.second)
        if (m == Mode.SELECT && lineChanged) lifted = true
        val prev = history.lastOrNull()
        if (prev != null && prev.block == block && prev.offset == offset) return
        history.addLast(Step(time, block, offset, lineChanged))
        while (history.size > 5) history.removeFirst()
        emit(m, block, offset, lineChanged)
    }

    private fun emit(m: Mode, block: Int, offset: Int, lineChanged: Boolean) {
        when (m) {
            Mode.SELECT -> onDrag(block, offset)
            Mode.START_HANDLE -> onHandle(true, block, offset, lineChanged)
            Mode.END_HANDLE -> onHandle(false, block, offset, lineChanged)
        }
    }

    /** 自动滚动后，手指没动但下面的字变了：按上次的位置再算一次。 */
    fun retrack(edgeSize: Float, handleLift: Float) {
        last?.let { track(it, edgeSize, handleLift) }
    }

    /**
     * 松手。抬手时指腹一滚，最后一下常常多挪一个字：如果最后一次变化发生在抬手前 150 毫秒内、
     * 而之前那个位置已经停了 350 毫秒以上，就退回之前那个位置（AOSP Editor 的抬手过滤）。
     */
    fun finish(time: Long = lastTime) {
        val m = mode ?: return
        val steps = history.toList()
        if (steps.size >= 2) {
            val lastStep = steps[steps.size - 1]
            val stable = steps[steps.size - 2]
            if (time - lastStep.time < UP_JITTER_MS && lastStep.time - stable.time > STABLE_MS) {
                emit(m, stable.block, stable.offset, lineChanged = false)
            }
        }
        mode = null
        edge = 0
        reset()
        pressAt = Offset.Unspecified
        if (m == Mode.SELECT) onRelease()
    }

    /** [p]（阅读区坐标）是否按在文字上：长按这里才会选字，蓄力环只在这时出现。 */
    fun overText(p: Offset): Boolean {
        val r = root?.takeIf { it.isAttached } ?: return false
        return entries.values.any { e ->
            if (!e.coords.isAttached) return@any false
            val b = r.localBoundingBoxOf(e.coords, clipBounds = false)
            if (!b.contains(p)) return@any false
            val l = e.layout
            val y = p.y - b.top
            if (y < 0f || y > l.size.height) return@any false
            p.x - b.left <= l.getLineRight(l.getLineForVerticalPosition(y))
        }
    }

    /** 阅读区里某点落在哪一块的哪个字上；不在任何块里时取上下最近的块。 */
    private fun resolve(p: Offset, caret: Boolean): Pair<Int, Int>? {
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
        val l = e.layout
        val y = (p.y - b.top).coerceIn(0f, (l.size.height - 1).coerceAtLeast(0).toFloat())
        var line = l.getLineForVerticalPosition(y)
        // 行间回差：只差一行时，手指要越过上一次那行的上下沿半行高（至少 8dp、至多 45dp 的范围内）才换行，
        // 停在两行交界处不会来回跳；一下差了两行以上就直接换（同 AOSP Editor）。
        stuck?.let { (se, sl) ->
            if (se === e && sl != line && kotlin.math.abs(sl - line) == 1 && sl < l.lineCount) {
                val top = l.getLineTop(sl)
                val bottom = l.getLineBottom(sl)
                val height = bottom - top
                val slop = ((height * (1f + LINE_SLOP)).coerceIn(8 * dp, 45 * dp) - height).coerceAtLeast(0f)
                if (y >= top - slop && y <= bottom + slop) line = sl
            }
        }
        stuck = e to line
        val lineY = (l.getLineTop(line) + l.getLineBottom(line)) / 2
        val local = Offset((p.x - b.left).coerceIn(0f, l.size.width.toFloat()), lineY)
        // 放大镜锁在这一行；手指横向离开这一行的字超过放大镜半宽就收起。
        val left = l.getLineLeft(line)
        val right = l.getLineRight(line)
        lens = if (p.x - b.left < left - lensHalfWidth || p.x - b.left > right + lensHalfWidth) Offset.Unspecified
        else Offset(b.left + local.x.coerceIn(left, right), b.top + lineY)
        return e.block to e.toChar(if (caret) l.getOffsetForPosition(local) else charUnder(l, local))
    }

    /**
     * 第 [block] 块文字里光标 [cursor]（字缝，0 是第一个字前面）在阅读区里的位置：
     * left = right = 光标的横坐标，top、bottom 是它所在行的上下沿。不在屏幕上时为空。
     * [atLineEnd] 为真时，正好落在换行处的光标算上一行的行尾（选区终点），否则算下一行的行首（起点）。
     * 读 [version] 以便滚动、翻页时跟着走。
     */
    fun caret(block: Int, cursor: Int, atLineEnd: Boolean): Rect? {
        version
        val r = root?.takeIf { it.isAttached } ?: return null
        val width = r.size.width.toFloat()
        for (e in entries.values) {
            if (e.block != block || !e.coords.isAttached) continue
            val len = e.layout.layoutInput.text.length
            val o = e.toLayout(cursor)
            // 翻页时一段可能拆在两页，各页只管自己那一截。
            if (o < 0 || o > len || (o == 0 && atLineEnd && cursor > 0) || (o == len && !atLineEnd && cursor < len)) continue
            val b = r.localBoundingBoxOf(e.coords, clipBounds = false)
            if (b.right <= 0f || b.left >= width) continue
            val l = e.layout
            val line = if (atLineEnd && o > 0) l.getLineForOffset(o - 1) else l.getLineForOffset(o)
            val x = if (atLineEnd && o > 0 && l.getLineForOffset(o) != line) l.getLineRight(line) else l.getHorizontalPosition(o, usePrimaryDirection = true)
            return Rect(b.left + x, b.top + l.getLineTop(line), b.left + x, b.top + l.getLineBottom(line))
        }
        return null
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

/** 行间回差占行高的比例。 */
private const val LINE_SLOP = 0.5f

/** 划选换过行后，选字点抬到手指上方这么多 dp（系统把手高 22dp 的七成）。 */
private const val SELECT_LIFT = 15f

/** 抬手过滤：最后一次变化离抬手不到这么久，且之前的位置停过 [STABLE_MS]，就退回之前的位置。 */
private const val UP_JITTER_MS = 150L
private const val STABLE_MS = 350L
