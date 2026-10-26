package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.xycs.lingyuereader.data.ChapterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 章节状态的小标签（草稿、修订、定稿）从配色里取色：浅色、深色、很浅的自选强调色下都读得清。 */
class WritingStatusColorTest {
    private val tints = listOf(
        Color(0xFF3482FF), // miuix 默认的蓝
        Color(0xFFFFB3C6), // 深色 Monet 常见的浅粉
        Color(0xFFFFE08A), // 很浅的琥珀
    )

    @Test
    fun statusTagsAreReadableInLightAndDark() {
        for (dark in listOf(false, true)) {
            for (tint in tints) {
                val c = groupedColors(dark, tint)
                ChapterStatus.entries.forEach { status ->
                    val fg = statusColor(status, c)
                    // 小标签的字画在它自己的淡色底上，底下是卡片。
                    val tagFill = fg.copy(alpha = 0.14f).compositeOver(c.cell)
                    val onTag = contrastRatio(fg, tagFill)
                    val onCell = contrastRatio(fg, c.cell)
                    assertTrue("${if (dark) "深色" else "浅色"} $tint ${status.label}：标签上 $onTag", onTag >= 4.5f)
                    assertTrue("${if (dark) "深色" else "浅色"} $tint ${status.label}：卡片上 $onCell", onCell >= 4.5f)
                }
            }
        }
    }

    @Test
    fun statusColorsComeFromTheSemanticColors() {
        for (dark in listOf(false, true)) {
            val c = groupedColors(dark, tints[0])
            // 修订用提醒色、定稿用成功色、细纲用次要色，不再写死 iOS 的橙和绿（为了小标签读得清最多再压深一点点）。
            assertTrue(contrastRatio(c.warning, statusColor(ChapterStatus.REVISION, c)) < 1.2f)
            assertTrue(contrastRatio(c.success, statusColor(ChapterStatus.FINAL, c)) < 1.2f)
            // 纸色卡片上的淡色标签会略微压深次要色，以保证标签底上的对比度。
            assertTrue(contrastRatio(c.secondary, statusColor(ChapterStatus.OUTLINE, c)) < 1.2f)
            assertTrue(statusColor(ChapterStatus.REVISION, c) != Color(0xFFFF9500) && statusColor(ChapterStatus.FINAL, c) != Color(0xFF34C759))
            // 四种状态一眼分得开。
            assertEquals(4, ChapterStatus.entries.map { statusColor(it, c) }.distinct().size)
        }
    }
}
