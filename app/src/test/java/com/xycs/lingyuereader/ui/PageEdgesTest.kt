package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.PageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEdgesTest {
    @Test
    fun longerBooksAreThickerWithinLimits() {
        assertEquals(4f, bookThickness(0).value, 0.01f)
        assertEquals(10f, bookThickness(300_000).value, 0.01f)
        assertEquals(14f, bookThickness(10_000_000).value, 0.01f)
        assertTrue(bookThickness(100_000) < bookThickness(1_000_000))
    }

    @Test
    fun theLiftedPageCastsTheDeepestShadowWhenItReallyLifts() {
        assertTrue(turnShadow(PageMode.CURL) > turnShadow(PageMode.COVER))
        assertTrue(turnShadow(PageMode.COVER) > turnShadow(PageMode.SLIDE))
        assertEquals(0f, turnShadow(PageMode.NONE), 0f)
    }
}
