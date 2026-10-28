package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按节拍写的提示词：任务卡列出全章节拍、标出这次写哪一拍、已写的和后面再写的；篇幅只说这一拍的。拆节拍的回答怎么整理。 */
class WriteBeatPromptsTest {
    private val card = ChapterCard(
        facts = "沈默到渡口，杜娘开门，顾长风带人来。", pov = "沈默", hook = "顾长风说：船是我的。",
        beats = "沈默赶到渡口，发现船不见了\n杜娘开门（600字）\n顾长风带人来了",
    )
    private val plan = Beats.parse(card.beats)

    private fun beat(n: Int) = Beats.task(plan, n, "3000 到 3000 字").let { b ->
        ProseTask(1, "第一章 渡口", card, ProseScope.BEAT, beat = n, length = WritePrompts.beatLength(b))
    }

    @Test
    fun continuingAtTheCursorWritesOnlyTheChosenBeat() {
        val ask = WritePrompts.continueAt("沈默到了渡口。", "", beat(2))
        assertTrue(ask.contains("  1. 沈默赶到渡口，发现船不见了（已写）"))
        assertTrue(ask.contains("  2. 杜娘开门  ← 这次写这一拍"))
        assertTrue(ask.contains("  3. 顾长风带人来了（后面再写；本章结尾，停在钩子上：顾长风说：船是我的。）"))
        assertTrue(ask.contains("写到哪里停：这次只写第 2 拍，写完它的事就停，别开始第 3 拍"))
        assertTrue(ask.contains("篇幅：约 600 字"))
        assertTrue(ask.contains("（分在各拍里写，这次只写第 2 拍的部分）"))
        // 篇幅只有这一个数字：全章篇幅、各拍写在细纲卡里的字数都不再出现。
        assertFalse(ask.contains("3000"))
        assertFalse(ask.contains("（600字）"))
        assertFalse(ask.contains("不强加悬念"))
        assertTrue(ask.endsWith("这些事要写出来，但写成场面和对话，不要列清单式交代。"))
    }

    @Test
    fun theFirstBeatOfADraftAndTheFollowingBeats() {
        val first = WritePrompts.draft("上一章的结尾。", beat(1))
        assertTrue(first.startsWith("上一章结尾：\n上一章的结尾。"))
        assertTrue(first.contains("  1. 沈默赶到渡口，发现船不见了  ← 这次写这一拍"))
        assertTrue(first.contains("  2. 杜娘开门（后面再写，这次别写到）"))
        assertTrue(first.contains("从哪里接：本章开头，紧接上一章结尾（见前面）"))
        assertTrue(first.contains("篇幅：约 1000 字"))
        assertFalse(first.contains("（已写）"))

        // 后面几拍：只带已写的最后约 3000 字；最后一拍停在钩子上。
        val written = "旧".repeat(5_000) + "沈默到了渡口。"
        val last = WritePrompts.draftMore(written, beat(3))
        assertTrue(last.contains("旧".repeat(2_993) + "沈默到了渡口。"))
        assertFalse(last.contains("旧".repeat(3_000)))
        assertTrue(last.contains("这次只写第 3 拍，它是本章最后一拍，写完停在章末钩子上"))
        assertTrue(last.contains("  3. 顾长风带人来了  ← 这次写这一拍（本章结尾，停在钩子上：顾长风说：船是我的。）"))
        assertTrue(last.contains("从哪里接：紧接上文最后一句"))
        // 整章接着写：带最后约 4000 字。
        assertTrue(WritePrompts.draftMore(written, beat(1).copy(scope = ProseScope.TO_END, beat = null)).contains("旧".repeat(3_993)))
    }

    @Test
    fun splitBeatsAnswersAreCleanedIntoOneBeatPerLine() {
        val answer = "好的，按细纲拆成下面几拍：\n```\n1. 沈默赶到渡口，发现船不见了（约 800 字）\n2、杜娘开门\n\n- **顾长风带人来了**（1200字）\n```"
        assertEquals("沈默赶到渡口，发现船不见了（800字）\n杜娘开门\n顾长风带人来了（1200字）", WritePrompts.parseBeats(answer))
        assertEquals("", WritePrompts.parseBeats("以下是节拍：\n\n"))
        // 最多八拍。
        assertEquals(8, WritePrompts.parseBeats((1..12).joinToString("\n") { "$it. 林月第 $it 件事" }).lines().size)

        val ask = WritePrompts.beatsSplit("第一章 渡口", ChapterCard(facts = "沈默到渡口", hook = "杜娘递来一封信"), "3000 到 5000 字", "")
        assertTrue(ask.contains("本章事实与结果：沈默到渡口"))
        assertTrue(ask.contains("拆成 3 到 8 拍"))
        assertTrue(ask.contains("全章篇幅3000 到 5000 字"))
    }

    /** 逐拍写的请求：写法要求（含黑名单）在任务卡前面，任务卡收尾。 */
    @Test
    fun beatPromptsEndWithTheTaskCardAfterTheVoice() {
        val voice = WritePrompts.proseTail(WorkAssist(banned = listOf("嘴角勾起")), null)
        listOf(
            WritePrompts.continueAt("沈默到了渡口。", "", beat(2), voice),
            WritePrompts.draft("", beat(1), voice),
            WritePrompts.draftMore("沈默到了渡口。", beat(2), voice),
        ).forEach { ask ->
            val tail = ask.indexOf(WritePrompts.PROSE_HEADER)
            val card = ask.indexOf(WritePrompts.CARD_HEADER)
            assertTrue(tail in 0 until card)
            assertTrue(ask.substring(tail, card).contains("嘴角勾起"))
            assertTrue(ask.substring(card).contains("这次写这一拍"))
        }
    }
}
