package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一拍出几个版本：三个方向的提示词各含自己的方向要求，放在节拍要求之后、写法要求之前；只写一版时提示词一字不变。 */
class WriteDirectionPromptsTest {
    private val plan = Beats.parse("沈默赶到渡口，发现船不见了\n杜娘开门（600字）\n顾长风带人来了")
    private val beat = Beats.task(plan, 2, "3000 到 3000 字")
    private val voice = WritePrompts.voiceTail(WorkAssist(style = "短句为主", banned = listOf("嘴角勾起")), null)

    private fun ask(direction: WriteDirection?, mode: InlineMode = InlineMode.BEAT) = WritePrompts.continueAt(
        "第一章 渡口", "沈默到了渡口。", "林月在船头等着。", mode, "3000 到 3000 字", "", "字数：至少 5",
        voice = voice, beat = beat.takeIf { mode == InlineMode.BEAT }, direction = direction,
    )

    @Test
    fun eachDirectionCarriesItsOwnRuleBeforeTheVoiceTail() {
        val asks = WriteDirection.entries.associateWith { ask(it) }
        asks.forEach { (direction, text) ->
            val own = text.indexOf(direction.rule)
            val tail = text.indexOf(WritePrompts.VOICE_HEADER)
            assertTrue("${direction.label} 要写进它自己的方向", own > 0)
            assertTrue(text.contains("【这一版的方向：${direction.label}】"))
            // 方向在节拍要求之后、写法要求之前；以写法要求收尾。
            assertTrue(text.indexOf("只写第 2 拍") in 0 until own)
            assertTrue(own < tail)
            assertTrue(text.endsWith(voice.trim()))
            // 节拍、细纲、写法要求照旧遵守。
            assertTrue(text.contains("节拍、细纲卡的事实边界和后面的写法要求都照旧遵守"))
            // 不混进别的方向。
            WriteDirection.entries.filter { it != direction }.forEach { other -> assertFalse(text.contains(other.rule)) }
        }
        assertTrue(WriteDirection.CONFLICT.rule.contains("矛盾更尖锐"))
        assertTrue(WriteDirection.RESTRAIN.rule.contains("不说破"))
        assertTrue(WriteDirection.SURPRISE.rule.contains("不提前写后面的事"))
        // 写下一场的三版也一样。
        val scene = ask(WriteDirection.SURPRISE, InlineMode.SCENE)
        assertTrue(scene.indexOf("写完眼下这一场就停") < scene.indexOf(WriteDirection.SURPRISE.rule))
        assertTrue(scene.endsWith(voice.trim()))
    }

    @Test
    fun aSingleVersionPromptIsUnchanged() {
        val single = ask(null)
        assertFalse(single.contains("这一版的方向"))
        assertEquals(single, WritePrompts.continueAt("第一章 渡口", "沈默到了渡口。", "林月在船头等着。", InlineMode.BEAT, "3000 到 3000 字", "",
            "字数：至少 5", voice = voice, beat = beat))
        // 方向只是加在写法要求前面的一段，别的一字不差。
        val conflict = ask(WriteDirection.CONFLICT)
        assertEquals(single, conflict.replace("\n\n" + WritePrompts.directionRule(WriteDirection.CONFLICT), ""))
    }
}
