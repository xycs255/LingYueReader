package com.xycs.lingyuereader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestTitleTest {
    @Test
    fun commonPrefixOfFileNames() {
        assertEquals("已定正文", suggestTitle(listOf("已定正文_第1-5章.md", "已定正文_第6-10章.md")))
        assertEquals("三体", suggestTitle(listOf("三体1.txt", "三体2.txt")))
        assertEquals("甲", suggestTitle(listOf("甲.txt", "乙.txt")))
    }
}
