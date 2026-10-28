package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一拍出几个版本：三个方向各写进任务卡（在写到哪里停、篇幅之后），任务卡照旧收尾；只写一版时提示词一字不变。 */
class WriteDirectionPromptsTest {
    private val card = ChapterCard(facts = "沈默到渡口。", beats = "沈默赶到渡口，发现船不见了\n杜娘开门（600字）\n顾长风带人来了")
    private val plan = Beats.parse(card.beats)
    private val beat = Beats.task(plan, 2, "3000 到 3000 字")
    private val voice = WritePrompts.proseTail(WorkAssist(style = "短句为主", banned = listOf("嘴角勾起")), null)

    private fun ask(direction: WriteDirection?, mode: ProseScope = ProseScope.BEAT) = WritePrompts.continueAt(
        "沈默到了渡口。", "林月在船头等着。",
        ProseTask(1, "第一章 渡口", card, mode, beat = if (mode == ProseScope.BEAT) 2 else null,
            length = if (mode == ProseScope.BEAT) WritePrompts.beatLength(beat) else "", direction = direction),
        voice,
    )

    @Test
    fun eachDirectionCarriesItsOwnRuleInsideTheTaskCard() {
        val asks = WriteDirection.entries.associateWith { ask(it) }
        asks.forEach { (direction, text) ->
            val own = text.indexOf(direction.rule)
            val card = text.indexOf(WritePrompts.CARD_HEADER)
            assertTrue("${direction.label} 要写进它自己的方向", own > card)
            assertTrue(text.contains("【这一版的方向：${direction.label}】"))
            // 方向在“写到哪里停”之后；写法要求在任务卡前面。
            assertTrue(text.indexOf("只写第 2 拍") in card until own)
            assertTrue(text.indexOf(WritePrompts.PROSE_HEADER) in 0 until card)
            // 不混进别的方向。
            WriteDirection.entries.filter { it != direction }.forEach { other -> assertFalse(text.contains(other.rule)) }
        }
        assertTrue(WriteDirection.CONFLICT.rule.contains("矛盾更尖锐"))
        assertTrue(WriteDirection.RESTRAIN.rule.contains("不说破"))
        assertTrue(WriteDirection.SURPRISE.rule.contains("不提前写后面的事"))
        // 写下一场的三版也一样。
        val scene = ask(WriteDirection.SURPRISE, ProseScope.SCENE)
        assertTrue(scene.indexOf("写完眼下这一场就停") < scene.indexOf(WriteDirection.SURPRISE.rule))
    }

    @Test
    fun aSingleVersionPromptIsUnchanged() {
        val single = ask(null)
        assertFalse(single.contains("这一版的方向"))
        // 方向只是任务卡里多出的一行，别的一字不差。
        val conflict = ask(WriteDirection.CONFLICT)
        assertEquals(single, conflict.replace(WritePrompts.directionRule(WriteDirection.CONFLICT) + "\n", ""))
    }
}
