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
    fun aTurningPageMovesFromOneStackToTheOther() {
        // 一页占全书 1%：往后翻到一半，读过那摞多半页；往前翻反过来；不会翻出全书。
        assertEquals(0.405f, readShare(0.4f, 0.5f, 0.01f), 1e-4f)
        assertEquals(0.395f, readShare(0.4f, -0.5f, 0.01f), 1e-4f)
        assertEquals(1f, readShare(0.999f, 1f, 0.01f), 1e-4f)
        assertEquals(0f, readShare(0f, -1f, 0.01f), 1e-4f)
    }

    @Test
    fun theLiftedPageCastsTheDeepestShadowWhenItReallyLifts() {
        assertTrue(turnShadow(PageMode.CURL) > turnShadow(PageMode.COVER))
        assertTrue(turnShadow(PageMode.COVER) > turnShadow(PageMode.SLIDE))
        assertEquals(0f, turnShadow(PageMode.NONE), 0f)
    }
}
