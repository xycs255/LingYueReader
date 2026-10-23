package com.xycs.lingyuereader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CharUnderTest {
    private val measurer = TextMeasurer(
        createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
        Density(2f),
        LayoutDirection.Ltr,
    )

    @Test
    fun theCharacterUnderTheFingerIsPickedEvenOnItsRightHalf() {
        val l = measurer.measure("银行门口排着长队", TextStyle(fontSize = 20.sp))
        val y = l.size.height / 2f
        for (i in 0 until 8) {
            val box = l.getBoundingBox(i)
            // 字的左半边、右半边都算这个字（离得最近的字缝在右半边时会是下一个字）。
            assertEquals("第 $i 字左半", i, charUnder(l, Offset(box.left + box.width * 0.25f, y)))
            assertEquals("第 $i 字右半", i, charUnder(l, Offset(box.left + box.width * 0.8f, y)))
            // 原来直接用的 getOffsetForPosition 在右半边给的是下一个字——长按选错字的原因。
            assertEquals("最近字缝", i + 1, l.getOffsetForPosition(Offset(box.left + box.width * 0.8f, y)))
        }
        // 行尾之后的空白：最后一个字。
        assertEquals(7, charUnder(l, Offset(l.size.width + 50f, y)))
    }

    @Test
    fun eachLineKeepsItsOwnCharacters() {
        val l = measurer.measure("银行门口排着长队", TextStyle(fontSize = 20.sp), constraints = androidx.compose.ui.unit.Constraints(maxWidth = 170))
        val second = l.getLineStart(1)
        val y = (l.getLineTop(1) + l.getLineBottom(1)) / 2
        // 第二行最左边：第二行的第一个字，不会退回第一行末尾。
        assertEquals(second, charUnder(l, Offset(1f, y)))
        // 第一行最右边之后：第一行最后一个字。
        val y0 = (l.getLineTop(0) + l.getLineBottom(0)) / 2
        assertEquals(second - 1, charUnder(l, Offset(l.size.width.toFloat(), y0)))
    }
}
