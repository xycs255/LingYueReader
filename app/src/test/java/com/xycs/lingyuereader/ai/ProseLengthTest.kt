package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ProseBudget
import com.xycs.lingyuereader.data.WordCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 篇幅说硬：任务卡写区间加上限、“宁短勿长”；AI 拆的节拍加起来不超过全章；流式写时超过上限 1.3 倍在句末收住。
 * 真机上一章目标“3500 到 6000 字”，拆成 8 拍、每拍“（1100字）”，逐拍照写成了八千多字。
 */
class ProseLengthTest {
    @Test
    fun theCardGivesARangeAndACeiling() {
        assertEquals("约 800 字（600–1000 字，最多不超过 1000 字；宁短勿长，这一拍的事写完就收，不要为了凑字加戏）",
            ProseBudget.of(800).card("这一拍的事"))
        // 区间：目标取中间，上限就是作者的区间上限，不再乘 1.25。
        val chapter = ProseBudget.parse("3500 到 6000 字")!!
        assertEquals(ProseBudget(4750, 3500, 6000, range = true), chapter)
        assertEquals(7800, chapter.cutoff)
        assertEquals("约 4750 字（3500–6000 字，最多不超过 6000 字；宁短勿长，这一章的事写完就收，不要为了凑字加戏）",
            WritePrompts.chapterLength("3500 到 6000 字"))
        // 单个数字：上限取目标的 1.25 倍。
        assertEquals(ProseBudget(3000, 2250, 3750), ProseBudget.parse("约 3000 字"))
        assertEquals(3750, ProseBudget.parse("不少于 3000 字")!!.high)
        assertEquals(4000, ProseBudget.parse("不超过 4000 字")!!.high)
        assertEquals("个汉字", ProseBudget.parse("4000—4200个汉字")!!.card("这一章的事").substringAfter("约 4100 ").take(3))
        assertNull(ProseBudget.parse("依本章细纲；没有明确数字时，以完整自然的章节为准"))
        assertEquals("", WritePrompts.chapterLength("依本章细纲"))
    }

    @Test
    fun beatWholeChapterAndToTheEndAllUseTheSameWording() {
        val card = ChapterCard(facts = "林月到渡口", beats = "林月到渡口（800字）\n杜娘开门\n沈默赶来")
        val beat = WritePrompts.beatLength(Beats.task(com.xycs.lingyuereader.data.beats(card), 1, "3500 到 6000 字"))
        assertEquals(ProseBudget.of(800).card("这一拍的事"), beat)
        // 没写篇幅的拍按全章中间值平均分：4750 / 3 ≈ 1600。
        val second = WritePrompts.beatLength(Beats.task(com.xycs.lingyuereader.data.beats(card), 2, "3500 到 6000 字"))
        assertTrue(second, second.startsWith("约 1600 字（1200–2000 字，最多不超过 2000 字；宁短勿长"))
        val left = WritePrompts.remaining("3500 到 6000 字", "林".repeat(2_000))
        assertEquals("约 2700 字（1500–4000 字，最多不超过 4000 字；宁短勿长，剩下的事写完就收，不要为了凑字加戏）", left)
        for (length in listOf(beat, second, left, WritePrompts.chapterLength("3500 到 6000 字"))) {
            assertTrue(length, "最多不超过" in length && "宁短勿长" in length && "不要为了凑字加戏" in length)
        }
    }

    @Test
    fun aiBeatsAddingUpPastTheChapterAreScaledBack() {
        val answer = (1..8).joinToString("\n") { "$it. 林月做了第 $it 件事（1100字）" }
        val fitted = Beats.parse(WritePrompts.parseBeats(answer, "3500 到 6000 字"))
        assertEquals(8, fitted.size)
        val sum = fitted.sumOf { it.length!! }
        assertTrue("各拍加起来 $sum 字", sum <= 6000)
        assertEquals(List(8) { 600 }, fitted.map { it.length })
        // 单个数字的目标：加起来不超过目标的 110%。
        val single = Beats.parse(WritePrompts.parseBeats(answer, "3000 字")).sumOf { it.length!! }
        assertTrue("各拍加起来 $single 字", single <= 3300)
        // 没写篇幅的拍按平均分算进总数，自己不改。
        val mixed = Beats.parse(WritePrompts.parseBeats("1. 林月到渡口（3000字）\n2. 杜娘开门\n3. 沈默赶来（3000字）", "3000 到 4000 字"))
        assertNull(mixed[1].length)
        val total = mixed[0].length!! + mixed[2].length!! + Beats.share("3000 到 4000 字", 3)!!
        assertTrue("加起来 $total 字", total <= 4000)
        // 没超的不动；取不到全章目标的不动。
        assertEquals("林月到渡口（800字）\n杜娘开门（900字）", WritePrompts.parseBeats("1. 林月到渡口（800字）\n2. 杜娘开门（900字）", "3500 到 6000 字"))
        assertEquals(List(8) { 1100 }, Beats.parse(WritePrompts.parseBeats(answer, "依本章细纲")).map { it.length })
    }

    @Test
    fun theAuthorsOwnBeatLengthsAreNeverChanged() {
        // 作者写在细纲卡里的节拍：写哪一拍就用作者写的篇幅，哪怕加起来超过全章。
        val beats = Beats.parse((1..8).joinToString("\n") { "林月做了第 $it 件事（1100字）" })
        assertEquals(1100, Beats.task(beats, 3, "3500 到 6000 字").length)
    }

    @Test
    fun theSplitPromptAsksForBeatsThatAddUpToTheChapter() {
        val ask = WritePrompts.beatsSplit("第一章 渡口", ChapterCard(facts = "林月到渡口"), "3500 到 6000 字", "")
        assertTrue("全章篇幅3500 到 6000 字：按每拍的分量分篇幅，写在行尾，比如“（800字）”，各拍加起来等于全章篇幅，不要超出" in ask)
    }

    private fun cut(limit: Int, vararg pieces: String): String? {
        val c = ProseCutoff(limit, setOf("\n\n（回答太长，后面被截断了。）"))
        return try {
            pieces.forEach(c::feed)
            null
        } catch (e: ProseCut) {
            e.text
        }
    }

    @Test
    fun theCutoffStopsAtTheNextSentenceEnd() {
        // 没超：一直收，不收住。
        assertNull(cut(100, "林月走过渡口。", "杜娘开了门。"))
        // 过了 10 字以后的第一个句末，逐字到达也一样。
        val text = "林月走过渡口，看见船不见了。杜娘开了门。老周走了。"
        assertEquals("林月走过渡口，看见船不见了。", cut(10, text))
        assertEquals("林月走过渡口，看见船不见了。", cut(10, *text.map { it.toString() }.toTypedArray()))
        // 省略号、问叹连用是同一句；引号里的句号不算句末，收在后引号后面。
        assertEquals("林月走过渡口，船呢……", cut(5, "林月走过渡口，船呢……", "杜娘开门。"))
        assertEquals("林月说：“船不见了。老周呢？”", cut(5, "林月说：“船不见了。老周呢？”", "杜娘没答。"))
        assertEquals("你说什么？！", cut(2, "你说什么？！", "林月问。"))
        // 客户端追加的说明不算正文。
        assertNull(cut(5, "林月走过渡口", "\n\n（回答太长，后面被截断了。）"))
        // 重来（查了资料以后）从头数。
        val c = ProseCutoff(10)
        c.feed("林月走过渡口，看见船")
        c.reset()
        c.feed("杜娘开门。")
        try {
            c.feed("老周走了，船也走了，信在桌上。后面")
            fail("该收住了")
        } catch (e: ProseCut) {
            assertEquals("杜娘开门。老周走了，船也走了，信在桌上。", e.text)
            assertTrue(WordCount.of(e.text) > 10)
        }
    }

    @Test
    fun theCondensePromptGivesTheTargetAndCeiling() {
        val p = WritePrompts.condense("林月走过渡口。".repeat(100), "第一章 渡口", ProseBudget.parse("3500 到 6000 字")!!)
        assertTrue("压到约 4750 字，最多不超过 6000 字" in p)
        assertTrue("只输出精简后的完整正文" in p)
        assertFalse("范本" in p)
    }
}
