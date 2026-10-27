package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按节拍写的提示词：只写这一拍、上一拍别重写、下一拍别写到、篇幅；没有节拍时一字不变。拆节拍的回答怎么整理。 */
class WriteBeatPromptsTest {
    private val plan = Beats.parse("沈默赶到渡口，发现船不见了\n杜娘开门（600字）\n顾长风带人来了")

    @Test
    fun continuingAtTheCursorWritesOnlyTheChosenBeat() {
        val beat = Beats.task(plan, 2, "3000 到 3000 字")
        val ask = WritePrompts.continueAt("第一章 渡口", "沈默到了渡口。", "", InlineMode.BEAT, "3000 到 3000 字", "", "", beat)
        assertTrue(ask.contains("只写第 2 拍（共 3 拍）：「杜娘开门」。这件事必须写到、写完"))
        assertTrue(ask.contains("上一拍「沈默赶到渡口，发现船不见了」已经写过了，光标前的正文就是，不要重写"))
        assertTrue(ask.contains("下一拍「顾长风带人来了」不要写到：写到第 2 拍收住就停"))
        assertTrue(ask.contains("这一拍篇幅约 600 字"))
        assertTrue(ask.contains("不要另外加情节；细纲卡的事实边界照旧"))
        // 结尾照旧（任务 A 会在后面接写法要求）。
        assertTrue(ask.endsWith("只输出接下去的正文：一行一段，段首不空格；不要章名、小标题、解释。"))

        // 没有节拍：和原来一样，不提“拍”。
        val plain = WritePrompts.continueAt("第一章 渡口", "沈默到了渡口。", "", InlineMode.SCENE, "3000 字", "", "")
        assertFalse(plain.contains("拍"))
        assertEquals(plain, WritePrompts.continueAt("第一章 渡口", "沈默到了渡口。", "", InlineMode.SCENE, "3000 字", "", "", null))
    }

    @Test
    fun theFirstBeatOfADraftAndTheFollowingBeats() {
        val first = WritePrompts.draft("第一章 渡口", "上一章的结尾。", "", "3000 到 3000 字", "", Beats.task(plan, 1, "3000 到 3000 字"))
        assertTrue(first.contains("请只写本章开头的这一拍"))
        assertFalse(first.contains("上一拍"))
        assertTrue(first.contains("这一拍篇幅约 1000 字"))
        assertTrue(first.contains("全章篇幅3000 到 3000 字，这一次只写其中这一拍"))
        assertFalse(first.contains("请写出这一章「"))
        assertEquals(
            WritePrompts.draft("第一章 渡口", "上一章的结尾。", "", "3000 字", ""),
            WritePrompts.draft("第一章 渡口", "上一章的结尾。", "", "3000 字", "", null),
        )

        // 后面几拍：只带已写的最后约 3000 字。
        val written = "旧".repeat(5_000) + "沈默到了渡口。"
        val last = WritePrompts.draftMore("第一章 渡口", written, "", "3000 到 3000 字", Beats.task(plan, 3, "3000 到 3000 字"))
        assertTrue(last.contains("旧".repeat(2_993) + "沈默到了渡口。"))
        assertFalse(last.contains("旧".repeat(3_000)))
        assertTrue(last.contains("这是最后一拍"))
        assertFalse(last.contains("在既定章末自然收束，不强加悬念。全章篇幅"))
        assertTrue(WritePrompts.draftMore("第一章 渡口", written, "", "3000 字").contains("旧".repeat(3_993)))
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
}
