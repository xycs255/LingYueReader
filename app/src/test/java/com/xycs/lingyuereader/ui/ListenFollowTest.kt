package com.xycs.lingyuereader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenFollowTest {
    @Test
    fun followsOnlyWhenTheListeningChapterReallyChangesWhilePlaying() {
        // 第一次组合：听书停在第 3 章，读者刚从搜索结果跳到第 7 章，不弹回去（暂停或正在读都一样）。
        assertEquals(null, followListening(before = 3, now = 3, playing = false, shown = 7))
        assertEquals(null, followListening(before = 3, now = 3, playing = true, shown = 7))
        // 正在读，读进了第 4 章：跟过去。
        assertEquals(4, followListening(before = 3, now = 4, playing = true, shown = 3))
        // 暂停着换了章（不是读进去的）：不跟。
        assertEquals(null, followListening(before = 3, now = 4, playing = false, shown = 3))
        // 已经在那一章、或听书停了：不动。
        assertEquals(null, followListening(before = 3, now = 4, playing = true, shown = 4))
        assertEquals(null, followListening(before = 3, now = null, playing = false, shown = 3))
    }
}
