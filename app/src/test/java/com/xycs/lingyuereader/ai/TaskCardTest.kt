package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ProseLengths
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写正文请求最后的“本次写作任务卡”：逐拍、整章、写到本章结尾、没有节拍四种；篇幅只有一个数字；写法要求和作者规范冲突时听作者的。 */
class TaskCardTest {
    private val card = ChapterCard(
        facts = "卯时林月到渡口，船上没人；杜娘说老周跟顾长风走了；沈默赶来，两人找到另半张药方。本章字数：3000-4000字。",
        boundary = "本章不到白沙渡；不揭沈默身份。", pov = "林月", countdown = "老周被带走后第一个白天。",
        hook = "河上传来漕帮的号子。",
        beats = "林月到渡口，船上没人（800字）\n杜娘开门，说老周走了（900字）\n沈默赶来，找到另半张药方（1000字）",
    )
    private fun task(scope: ProseScope, beat: Int? = null, length: String = "") = ProseTask(3, "第3章 空船", card, scope, beat = beat, length = length)

    /** 卡里只能有大白话：这些内部说法一个都不出现。 */
    private val jargon = listOf("承接边界", "资料分工", "事实底稿", "细纲卡", "逐项表演", "栏目", "章界", "不强加悬念", "当前阶段")

    private fun lengths(text: String) = Regex("篇幅").findAll(text).count()

    @Test
    fun aBeatCardListsEveryBeatAndMarksThisOne() {
        val c = WritePrompts.taskCard(task(ProseScope.BEAT, 2, "约 900 字"))
        assertEquals(
            """
            【这次要写的】
            第 3 章《空船》，视角：林月
            全章节拍：
              1. 林月到渡口，船上没人（已写）
              2. 杜娘开门，说老周走了  ← 这次写这一拍
              3. 沈默赶来，找到另半张药方（后面再写；本章结尾，停在钩子上：河上传来漕帮的号子。）
            从哪里接：紧接上文最后一句（上文见前面）
            写到哪里停：这次只写第 2 拍，写完它的事就停，别开始第 3 拍
            篇幅：约 900 字
            本章必须成立的事：卯时林月到渡口，船上没人；杜娘说老周跟顾长风走了；沈默赶来，两人找到另半张药方。（分在各拍里写，这次只写第 2 拍的部分）
            这一章不写到：本章不到白沙渡；不揭沈默身份。
            时间：老周被带走后第一个白天。
            这些事要写出来，但写成场面和对话，不要列清单式交代。
            """.trimIndent(),
            c,
        )
        jargon.forEach { assertFalse(it, it in c) }
        // 篇幅只有这一拍的一个数字：细纲里的“本章字数”和各拍行尾的字数都去掉了。
        assertEquals(1, lengths(c))
        assertFalse("3000" in c || "（800字）" in c)
    }

    @Test
    fun theLastBeatStopsOnTheHook() {
        val c = WritePrompts.taskCard(task(ProseScope.BEAT, 3, "约 1000 字"))
        assertTrue("  3. 沈默赶来，找到另半张药方  ← 这次写这一拍（本章结尾，停在钩子上：河上传来漕帮的号子。）" in c)
        assertTrue("写到哪里停：这次只写第 3 拍，它是本章最后一拍，写完停在章末钩子上" in c)
        assertTrue("  1. 林月到渡口，船上没人（已写）\n  2. 杜娘开门，说老周走了（已写）" in c)
    }

    @Test
    fun aWholeChapterCardHasTheChapterLengthOnly() {
        val c = WritePrompts.taskCard(task(ProseScope.CHAPTER, length = "3000—4000字").copy(opening = true, previous = true, previousHook = "窗下有脚步声。"))
        assertTrue("上一章停在：窗下有脚步声。" in c)
        assertTrue("从哪里接：本章开头，紧接上一章结尾（见前面）" in c)
        assertTrue("写到哪里停：写完整章，停在章末钩子上" in c)
        assertTrue("篇幅：3000—4000字" in c)
        assertEquals(1, lengths(c))
        assertFalse("这次写这一拍" in c || "（已写）" in c)
    }

    @Test
    fun writingToTheEndListsTheBeatsLeftAndTheRemainingLength() {
        val written = "林".repeat(1_300)
        val left = WritePrompts.remaining("3000—4000字", written)
        assertEquals("约 2200 字", left)
        val c = WritePrompts.taskCard(task(ProseScope.TO_END, 2, left))
        assertTrue("  1. 林月到渡口，船上没人（已写）\n  2. 杜娘开门，说老周走了\n  3. 沈默赶来，找到另半张药方（本章结尾，停在钩子上：河上传来漕帮的号子。）" in c)
        assertTrue("写到哪里停：把剩下的各拍都写完，停在章末钩子上" in c)
        assertTrue("篇幅：约 2200 字" in c)
        assertFalse("不强加悬念" in c)
        // 不知道写到第几拍：从上文停下的地方接，别从第一拍重写。
        assertTrue("全章节拍（从上文停下的地方接着写，没写到的拍都要写到）：" in WritePrompts.taskCard(task(ProseScope.TO_END)))
        // 写得差不多了：不再给一个数字催它凑字。
        assertEquals("", WritePrompts.remaining("3000—4000字", "林".repeat(3_400)))
        assertEquals("", WritePrompts.remaining("依本章细纲", written))
    }

    @Test
    fun withoutBeatsTheCardIsBuiltFromTheFactsPovAndHook() {
        val c = WritePrompts.taskCard(ProseTask(3, "空船", card.copy(beats = ""), ProseScope.CHAPTER, length = "3000—4000字"))
        assertFalse("节拍" in c || "拍" in c)
        assertTrue("第 3 章《空船》，视角：林月" in c)
        assertTrue("章末停在：河上传来漕帮的号子。" in c)
        assertTrue("本章必须成立的事：卯时林月到渡口" in c)
        assertTrue(c.endsWith("这些事要写出来，但写成场面和对话，不要列清单式交代。"))
        // 卡上什么事实都没有：不硬加“要写出来”的那一句。
        val bare = WritePrompts.taskCard(ProseTask(null, "空船", ChapterCard(), ProseScope.SCENE))
        assertTrue(bare.startsWith("【这次要写的】\n这一章《空船》"))
        assertTrue("写到哪里停：写完眼下这一场就停" in bare)
        assertFalse("要写出来" in bare || "篇幅" in bare)
    }

    @Test
    fun theCardIsAlwaysTheLastPartAfterFlavorSamplesAndVoice() {
        val assist = WorkAssist(flavor = "轻松带吐槽的穿越历史爽文，主角爱用历史知识算计人。", style = "短句为主", banned = listOf("嘴角勾起"))
        val samples = StyleSamples.HEADER + "\n【对白 · 出自「第1章」】\n“船呢？”沈默问。"
        val voice = WritePrompts.proseTail(assist, samples)
        val p = WritePrompts.draft("上一章的最后一句。", task(ProseScope.BEAT, 1, "约 800 字"), voice, reference = "范本的要求")
        val order = listOf("上一章结尾：", "范本的要求", WritePrompts.FLAVOR_HEADER + "轻松带吐槽", StyleSamples.HEADER, WritePrompts.PROSE_HEADER, "「嘴角勾起」", WritePrompts.CARD_HEADER)
        val at = order.map { p.indexOf(it) }
        assertTrue(at.toString(), at.all { it >= 0 })
        assertEquals(at.sorted(), at)
        assertTrue(p.endsWith(WritePrompts.taskCard(task(ProseScope.BEAT, 1, "约 800 字").copy(opening = true, previous = true))))
        // 没填口味：不带这一行。
        assertFalse(WritePrompts.FLAVOR_HEADER in WritePrompts.proseTail(WorkAssist(), null))
    }

    /** 正面的写法要求 6–8 条；作者规范里写了同类规矩的，内置那条让步，不同时给两个说法。 */
    @Test
    fun voiceRulesArePositiveAndYieldToTheAuthor() {
        val plain = WritePrompts.voiceRules("")
        assertTrue(plain.size in 6..8)
        assertTrue(plain.any { "可以直接写他在想什么" in it })
        assertTrue(plain.any { "吐槽" in it && "玩个梗" in it })
        assertTrue(plain.any { "紧的地方短句快推" in it })
        // 压情绪的旧规则不再发给写正文的模型。
        listOf("不替读者解释人物心理", "能删的形容词、副词就删", "比喻一章不超过两三处").forEach { old -> assertTrue(plain.none { old in it }) }

        val author = "心理活动可以写，但一次不超过两句。紧的地方句子短，不要连着三句以上的长句。叙述者不要吐槽。"
        val yielded = WritePrompts.voiceRules(author)
        assertTrue(yielded.none { "可以直接写他在想什么" in it })
        assertTrue(yielded.any { "心里想的写多少照作者的规范" in it })
        assertTrue(yielded.none { "短句快推" in it })
        assertTrue(yielded.none { "玩个梗" in it })
        assertEquals(plain.size - 1, yielded.size)
        // 穿越的书才提历史知识。
        assertTrue(plain.none { "历史知识" in it })
        assertTrue(WritePrompts.voiceRules("", "穿越历史爽文").any { "历史知识" in it })
        // 写法要求里只留一句去套话，加黑名单。
        val tail = WritePrompts.proseTail(WorkAssist(banned = listOf("说来话长")), null, author)
        assertTrue("避开“嘴角勾起”“眼中闪过”这类套话" in tail && "「说来话长」" in tail)
        assertFalse("可以直接写他在想什么" in tail)
        assertFalse("避开" in WritePrompts.proseTail(WorkAssist(aiCliche = false), null))
    }

    @Test
    fun theWritingRoleIsACoAuthor() {
        val s = WritePrompts.proseSystem("渡灯", WorkAssist(style = "短句为主"), "【工作说明】\n我给细纲，你写正文。", "【写作规范】\n比喻一章不超过两处。", "写第 3 章用得上的资料：……")
        assertTrue(s.startsWith("你是和作者一起写这部小说《渡灯》的合写搭档，负责先写出好看的初稿，作者再改。"))
        assertFalse("编辑助手" in s)
        // 文风要求放在请求最后（写法要求那里），系统提示里不重复。
        assertFalse("短句为主" in s)
        val stable = WritePrompts.proseSystem("渡灯", WorkAssist(style = "短句为主"), "【工作说明】\n我给细纲，你写正文。", "【写作规范】\n比喻一章不超过两处。", "")
        assertTrue(s.startsWith(stable))
        // 改稿、审稿的身份照旧。
        assertTrue(WritePrompts.system("渡灯", WorkAssist()).startsWith("你是写作软件“灵阅”里的编辑助手"))
        assertTrue("细节拿不准" in WritePrompts.proseToolGuide(listOf("人物档案")) && "《人物档案》" in WritePrompts.proseToolGuide(listOf("人物档案")))
    }

    @Test
    fun thePreviousChapterEndIsCutAtAWholeParagraph() {
        val paragraphs = (1..80).map { "第${it}段，林月往渡口走了几步，又停下来看了看河面。" }
        val text = paragraphs.joinToString("\n")
        val end = WritePrompts.ending(text)
        assertTrue(end.length in WritePrompts.ENDING_MIN..WritePrompts.ENDING_MAX)
        assertTrue(end.startsWith("第") && end.lines().all { it in paragraphs })
        assertTrue(end.endsWith(paragraphs.last()))
        // 一整段很长：从完整的一句起。
        val long = (1..200).joinToString("") { "林月数到第${it}下。" }
        val cut = WritePrompts.ending(long)
        assertTrue(cut.length in WritePrompts.ENDING_MIN..WritePrompts.ENDING_MAX)
        assertTrue(cut.startsWith("林月数到第"))
        // 短的原样。
        assertEquals("雨停了。", WritePrompts.ending("雨停了。"))
    }

    @Test
    fun chapterLengthsAreTakenOutOfTheAuthorsText() {
        val style = "## 篇幅\n- 每章3000-4000字。不注水，不为凑字加插曲。\n- 开头三百字内进入事件。\n按本章细纲写一章完整正文，3000-4000字。\n字数：3000-4000\n至多 1：仿佛\n短段：12 字以内至多 5 处"
        assertEquals("## 篇幅\n- 不注水，不为凑字加插曲。\n- 开头三百字内进入事件。\n至多 1：仿佛\n短段：12 字以内至多 5 处", ProseLengths.strip(style))
    }

    /** 写到本章结尾、各拍都写过了（拍数加一）：各拍全标已写，只收住这一章，不让模型重写最后一拍。 */
    @Test
    fun everyBeatWrittenOnlyWrapsUpTheChapter() {
        val c = WritePrompts.taskCard(task(ProseScope.TO_END, 4))
        assertTrue(c, "全章节拍（各拍都写了）：" in c)
        assertEquals(3, Regex("（已写）").findAll(c).count())
        assertTrue(c, "写到哪里停：各拍都写了，别再重写哪一拍；把这一章收住，停在章末钩子上" in c)
        assertFalse("把剩下的各拍都写完" in c)
        // 编辑器里勾的“已写”不该标到候选稿上：整章写时任务卡里不出现“（已写）”。
        val fresh = WritePrompts.taskCard(ProseTask(3, "第3章 空船", card.copy(beatsDone = emptyList()), ProseScope.CHAPTER))
        assertFalse("（已写）" in fresh)
    }
}
