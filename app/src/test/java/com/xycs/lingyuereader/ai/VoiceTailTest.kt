package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写法要求（文风、范例、采纳过的改法、去 AI 腔、黑名单）：改稿接在请求最后；写正文时在任务卡前面（任务卡永远在最后）；
 * 系统提示里没有范例；挑错不带。
 */
class VoiceTailTest {
    private val samples = StyleSamples.HEADER + "\n【第一章 渡口·对白】\n“船呢？”沈默问。"
    private val assist = WorkAssist(
        style = "第三人称，冷峻",
        accepted = listOf(StyleExample("润色", "风很大。", "风刮得很猛。", 1)),
        banned = listOf("嘴角勾起", "说来话长"),
    )
    private val tail = WritePrompts.voiceTail(assist, samples)

    private fun task(scope: ProseScope = ProseScope.CHAPTER) = ProseTask(4, "第四章 河桥", ChapterCard(facts = "顾长风北返。"), scope, length = "6000 字")

    @Test
    fun tailHoldsEverythingInOrder() {
        assertTrue(tail.startsWith(WritePrompts.VOICE_HEADER))
        val order = listOf("第三人称，冷峻", StyleSamples.HEADER, "原文：风很大。\n改为：风刮得很猛。", "去掉 AI 腔", "下面这些说法一个都不要用：「嘴角勾起」、「说来话长」")
        val at = order.map { tail.indexOf(it) }
        assertTrue(at.toString(), at.all { it >= 0 })
        assertEquals(at.sorted(), at)
        // 改稿的去 AI 腔写法要求 6–8 条，短而具体。
        assertTrue(WritePrompts.EDIT_RULES.size in 6..8)
        WritePrompts.EDIT_RULES.forEach { assertTrue(it in tail) }
    }

    @Test
    fun clicheSwitchAndEmptyTail() {
        val off = assist.copy(aiCliche = false)
        val t = WritePrompts.voiceTail(off, null)
        assertFalse("去掉 AI 腔" in t)
        // 黑名单是作者自己点的，关了“查 AI 腔”也照样带。
        assertTrue("「说来话长」" in t)
        // 什么都没有：空串，请求原样不变。
        val empty = WritePrompts.voiceTail(WorkAssist(aiCliche = false), null)
        assertEquals("", empty)
        assertEquals(WritePrompts.edit(WriteAction.POLISH, "风很大。", "", "", "渡口"), WritePrompts.edit(WriteAction.POLISH, "风很大。", "", "", "渡口", voice = empty))
    }

    @Test
    fun generationPromptsEndWithTheTaskCardRightAfterTheTail() {
        val prose = WritePrompts.proseTail(assist, samples)
        val prompts = mapOf(
            "接着写" to WritePrompts.continueAt("他站住。", "", task(ProseScope.WORDS), prose),
            "写本章" to WritePrompts.draft("上一章结尾", task(), prose),
            "接着写草稿" to WritePrompts.draftMore("已经写的。", task(ProseScope.TO_END), prose),
            "项目修订" to WritePrompts.projectRevision("顾长风北返。", "意见", task(), prose),
        )
        prompts.forEach { (name, p) ->
            val card = p.indexOf(WritePrompts.CARD_HEADER)
            assertTrue(name, card > 0)
            // 任务卡是最后一段，写法要求紧挨在它前面。
            assertTrue(name, p.substring(0, card).trimEnd().endsWith(prose))
            assertFalse(name, p.substring(card).contains(WritePrompts.PROSE_HEADER))
            assertTrue(name, "「嘴角勾起」" in p)
            assertTrue(name, StyleSamples.HEADER in p)
            assertFalse(name, WritePrompts.VOICE_HEADER in p)
        }
    }

    @Test
    fun editsThatWriteNewSentencesCarryItProofreadDoesNot() {
        listOf(WriteAction.POLISH, WriteAction.VIVID, WriteAction.EXPAND, WriteAction.CONDENSE, WriteAction.CONTINUE, WriteAction.CUSTOM).forEach { a ->
            val p = WritePrompts.edit(a, "风很大。", "他推门进来。", "", "第一章", "短一点", tail)
            assertTrue(a.name, p.endsWith(tail))
        }
        val proof = WritePrompts.edit(WriteAction.PROOFREAD, "风很大。", "他推门进来。", "", "第一章", "", tail)
        assertFalse(WritePrompts.VOICE_HEADER in proof)
        assertFalse(StyleSamples.HEADER in proof)
        assertFalse("嘴角勾起" in proof)
    }

    @Test
    fun systemPromptHasNoSamplesOrExamples() {
        val s = WritePrompts.system("渡口", assist, "本章提示：林月守渡口")
        assertFalse(StyleSamples.HEADER in s)
        assertFalse("改为：" in s)
        assertFalse(WritePrompts.VOICE_HEADER in s)
        // 文风要求两处都有：系统提示里那份留着，写法要求里再说一遍。
        assertTrue("第三人称，冷峻" in s)
        // 审稿、拟细纲、摘要、写作建议不带写法要求。
        listOf(
            WritePrompts.review("正文", "第一章"),
            WritePrompts.plan("第一章", "", null, null, ""),
            WritePrompts.summary("正文", "第一章"),
            WritePrompts.suggest(listOf("他推门进来。"), setOf(SuggestKind.TYPO)),
        ).forEach { assertFalse(WritePrompts.VOICE_HEADER in it) }
    }

    @Test
    fun declichePromptAndAnswer() {
        val paragraphs = listOf("林月嘴角勾起，看着窗外。" to listOf("嘴角勾起"), "沈默倒吸一口凉气。" to listOf("倒吸一口凉气"))
        val p = WritePrompts.decliche(paragraphs)
        assertTrue("1. 林月嘴角勾起，看着窗外。\n   [标出：「嘴角勾起」]" in p)
        assertTrue("只改标出的说法和它所在的那一句" in p && "不改对白的内容" in p)
        assertTrue("{\"issues\":[{\"p\":1" in p)
        // 回答照写作建议的格式读；删掉（right 空）也算一条。
        val found = WritePrompts.parseIssues(
            """{"issues":[{"p":1,"wrong":"林月嘴角勾起，","right":"林月笑了一下，","reason":"换成动作"},{"p":2,"wrong":"倒吸一口凉气","right":"","reason":"删"}]}""",
            paragraphs.map { it.first },
        )
        assertEquals(listOf("林月笑了一下，", ""), found.map { it.right })
        // 采纳记进“采纳过的修改”，带着改法的名字。
        val a = WritePrompts.remember(WorkAssist(), "去 AI 腔", "林月嘴角勾起，看着窗外。", "林月笑了一下，看着窗外。", 1)
        assertEquals("去 AI 腔", a.accepted.single().action)
        // 去 AI 腔不是停笔自动查的那几类，设置里没有开关。
        assertFalse(SuggestKind.CLICHE.realtime)
        assertTrue(SuggestKind.entries.filter { it.realtime }.none { it == SuggestKind.CLICHE })
    }

    /** 查设定交给审稿的事实关：写作建议能选的只剩错字、规范、口吻，提示词里不再要 AI 查设定。 */
    @Test
    fun suggestionsNoLongerCheckCanon() {
        assertEquals(listOf(SuggestKind.TYPO, SuggestKind.STYLE, SuggestKind.VOICE), SuggestKind.entries.filter { it.realtime })
        val p = WritePrompts.suggest(listOf("林月推门进来。"), setOf(SuggestKind.TYPO, SuggestKind.STYLE, SuggestKind.VOICE))
        assertFalse("- 设定：" in p || "CANON" in p || "设定、规范" in p)
        assertTrue("- 规范：" in p && "- 口吻：" in p && "规范、口吻这两类必须在上面给出的设定里有明确依据" in p)
        // 只查错字：不提“要有依据”。
        assertFalse("明确依据" in WritePrompts.suggest(listOf("林月推门进来。"), setOf(SuggestKind.TYPO)))
        // 以前的建议、旧设置里还有这一类：名字照样认得，不当成错字。
        assertEquals(SuggestKind.CANON, SuggestKind.of("CANON"))
    }
}
