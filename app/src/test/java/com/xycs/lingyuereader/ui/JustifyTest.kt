package com.xycs.lingyuereader.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JustifyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortLinesAreStretchedWithoutMovingLineBreaks() {
        lateinit var measurer: TextMeasurer
        lateinit var density: Density
        compose.setContent {
            measurer = rememberTextMeasurer()
            density = LocalDensity.current
        }
        compose.waitForIdle()
        val style = ReaderStyles(ReaderSettings(), ReaderTheme.PAPER.palette()).para
        val text = AnnotatedString(
            INDENT + "做完这一切后，他才站起身来，脸色有些发白，长长的睫毛不停地抖动着：“老师，还有什么要做的？”" +
                "费介看着他，皱了皱眉，没有想到小家伙居然胆子大到如此的地步。“你……”他顿了顿，“《庆余年》里说：‘年龄不是问题。’”",
        )
        var stretched = 0
        for (width in 300..900 step 7) {
            val c = Constraints(maxWidth = width)
            val before = measurer.measure(text, style, constraints = c)
            val justified = justifyCjk(text, style, width, measurer, density, justifyLastLine = false)
            val after = measurer.measure(justified, style, constraints = c)
            // 换行位置一个都不能变。
            assertEquals(before.lineCount, after.lineCount)
            for (line in 0 until before.lineCount) assertEquals(before.getLineEnd(line), after.getLineEnd(line))
            if (justified === text) continue
            stretched++
            val fontPx = with(density) { style.fontSize.toPx() }
            for (line in 0 until after.lineCount - 1) {
                val gapBefore = width - before.getLineRight(line)
                if (gapBefore < 0.5f || gapBefore > fontPx * 3) continue
                val gapAfter = width - after.getLineRight(line)
                assertTrue("width=$width line=$line gap $gapBefore → $gapAfter", abs(gapAfter) < fontPx * 0.1f)
            }
        }
        assertTrue("至少有一些宽度会出现行尾空白并被补齐", stretched > 0)
    }
}
