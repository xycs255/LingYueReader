package com.xycs.lingyuereader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 划选按词伸缩：长按后拖动按整词；拖把手往外按词、往回按字、换行按词。 */
class SelectionSnapTest {
    // 第 0 块：“研究|生命|起源|，|我们”
    private val words = listOf(0..1, 2..3, 4..5, 6..6, 7..8)
    private val wordOf = { p: TextPos -> if (p.block == 0) words.first { p.char in it } else null }

    private fun sel(start: Int, end: Int, anchor: IntRange = start..end) =
        TextSelection(0, TextPos(0, start), TextPos(0, end), TextPos(0, anchor.first), TextPos(0, anchor.last), dragging = true, anchorIsWord = true)

    @Test
    fun dragAfterLongPressGrowsByWholeWords() {
        // 长按“生命”，拖到“起”：选到“起源”整个词。
        val s = SelectionSnap.drag(sel(2, 3), TextPos(0, 4), wordOf)
        assertEquals(TextPos(0, 2) to TextPos(0, 5), s.start to s.end)
        // 往前拖到“究”：从“研究”开头起，长按的词仍在里面。
        val back = SelectionSnap.drag(sel(2, 3), TextPos(0, 1), wordOf)
        assertEquals(TextPos(0, 0) to TextPos(0, 3), back.start to back.end)
        // 还没切好词的段落按单字。
        val other = SelectionSnap.drag(sel(2, 3), TextPos(1, 7), wordOf)
        assertEquals(TextPos(1, 7), other.end)
    }

    @Test
    fun endHandleSnapsOutwardAndMovesByCharInward() {
        // 终点停在词尾（“生命”的“命”），往外拉到“起”：对齐到“起源”的“源”。
        assertEquals(TextPos(0, 5), SelectionSnap.end(sel(0, 3), TextPos(0, 4), lineChanged = false, wordOf))
        // 往回收到“生”：按单字，停在“生”。
        assertEquals(TextPos(0, 2), SelectionSnap.end(sel(0, 5), TextPos(0, 2), lineChanged = false, wordOf))
        // 终点停在词中间（“生”），往外拉：按单字，方便微调。
        assertEquals(TextPos(0, 4), SelectionSnap.end(sel(0, 2), TextPos(0, 4), lineChanged = false, wordOf))
        // 换了一行：对齐到词。
        assertEquals(TextPos(0, 3), SelectionSnap.end(sel(0, 5), TextPos(0, 2), lineChanged = true, wordOf))
    }

    @Test
    fun startHandleMirrorsTheEndHandle() {
        // 起点在词首（“起”），往前拉到“命”：对齐到“生命”的“生”。
        assertEquals(TextPos(0, 2), SelectionSnap.start(sel(4, 8), TextPos(0, 3), lineChanged = false, wordOf))
        // 往后收到“源”：按单字。
        assertEquals(TextPos(0, 5), SelectionSnap.start(sel(2, 8), TextPos(0, 5), lineChanged = false, wordOf))
    }
}
