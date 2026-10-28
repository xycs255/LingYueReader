package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.StreamTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 流式写正文时边收边清理（[WritePrompts.DraftStream]）：任何时候都和对收到的全部字做 cleanDraft 一样；界面刷新的节流。 */
class DraftStreamTest {
    private val samples = listOf(
        "```markdown\n第三章 空船\n\n　　天还黑着，林月就出了门。\n“周伯。”\n没人应。\n（本章完）\n```",
        "# 第41章\r\n沈默蹲在篷子里。\r\n\r\n林月解开麻绳。\n\n（模型中途停止了回答。）",
        "第五章 雪夜\n第五章 雪夜\n杜娘把灯笼递过去。\n未完待续",
        "~~~text\n顾长风笑了一声。\n~~~\n老周没说话。",
        "  \n\n  　林月把药方揣进怀里。　\n\n沈默跟在后面。\n",
        "林月说：“模型拒绝回答这个问题。”\n模型拒绝回答这个问题。\n",
    )

    @Test
    fun matchesCleanDraftAfterEveryPiece() {
        val rnd = Random(7)
        repeat(300) { round ->
            val all = samples[round % samples.size] + if (round % 3 == 0) "\n" + samples[(round + 1) % samples.size] else ""
            val stream = WritePrompts.DraftStream()
            var i = 0
            var got = ""
            while (i < all.length) {
                val n = rnd.nextInt(1, 6).coerceAtMost(all.length - i)
                stream.append(all.substring(i, i + n))
                i += n
                got = all.substring(0, i)
                val want = WritePrompts.cleanDraft(got)
                assertEquals("第 $round 轮，收到 ${got.length} 字时", want, stream.text())
                assertEquals(want.isNotBlank(), stream.hasText)
            }
            assertEquals(WritePrompts.cleanDraft(got), stream.text())
        }
    }

    @Test
    fun resetStartsOverWhenTheModelGoesToLookThingsUp() {
        val stream = WritePrompts.DraftStream()
        stream.append("我先查一下人物档案。\n第三章 空船\n")
        stream.reset()
        assertFalse(stream.hasText)
        stream.append("第三章 空船\n天还黑着，林月就出了门。")
        // 从头来以后，第一行照样当章名去掉。
        assertEquals("天还黑着，林月就出了门。", stream.text())
    }

    @Test
    fun theScreenRefreshesAtMostEveryInterval() {
        var now = 1_000L
        val tick = StreamTick(150) { now }
        assertTrue("第一块马上刷", tick.due())
        now += 100
        assertFalse(tick.due())
        now += 60
        assertTrue(tick.due())
        now += 10
        assertFalse(tick.due())
        tick.reset()
        assertTrue("查完资料从头写，第一块也马上刷", tick.due())
    }
}
