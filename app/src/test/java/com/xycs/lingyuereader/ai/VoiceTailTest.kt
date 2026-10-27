package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterWritingTask
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写法要求（文风、范例、采纳过的改法、去 AI 腔、黑名单）接在生成正文的请求最后；系统提示里没有范例；挑错不带。 */
class VoiceTailTest {
    private val samples = StyleSamples.HEADER + "\n【第一章 渡口·对白】\n“船呢？”沈默问。"
    private val assist = WorkAssist(
        style = "第三人称，冷峻",
        accepted = listOf(StyleExample("润色", "风很大。", "风刮得很猛。", 1)),
        banned = listOf("嘴角勾起", "说来话长"),
    )
    private val tail = WritePrompts.voiceTail(assist, samples)

    private fun task() = ChapterWritingTask(
        title = "第四章 河桥", chapter = 4, volume = 1, instruction = "", targetLength = "6000 字",
        previous = "城门在他身后合上。", previousLabel = "用户定稿第三章", card = ChapterCard(facts = "顾长风北返。"),
        sourceNames = emptyList(), warnings = emptyList(), blocking = emptyList(), project = true, fingerprint = "f",
    )

    @Test
    fun tailHoldsEverythingInOrder() {
        assertTrue(tail.startsWith(WritePrompts.VOICE_HEADER))
        val order = listOf("第三人称，冷峻", StyleSamples.HEADER, "原文：风很大。\n改为：风刮得很猛。", "去掉 AI 腔", "下面这些说法一个都不要用：「嘴角勾起」、「说来话长」")
        val at = order.map { tail.indexOf(it) }
        assertTrue(at.toString(), at.all { it >= 0 })
        assertEquals(at.sorted(), at)
        // 去 AI 腔的写法要求 6–8 条，短而具体。
        assertTrue(WritePrompts.VOICE_RULES.size in 6..8)
        WritePrompts.VOICE_RULES.forEach { assertTrue(it in tail) }
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
        assertEquals(WritePrompts.draft("渡口", "", "", "3000 字", ""), WritePrompts.draft("渡口", "", "", "3000 字", "", empty))
    }

    @Test
    fun generationPromptsEndWithTheTail() {
        val prompts = mapOf(
            "接着写" to WritePrompts.continueAt("河桥", "他站住。", "", InlineMode.WORDS, "6000 字", "", "禁用：您", tail),
            "写本章" to WritePrompts.draft("河桥", "上一章结尾", "禁用：您", "6000 字", "多写对白", tail),
            "接着写草稿" to WritePrompts.draftMore("河桥", "已经写的。", "禁用：您", "6000 字", tail),
            "项目创作" to WritePrompts.projectDraft(task(), "对白可以长说。", tail),
            "项目修订" to WritePrompts.projectRevision(task(), "顾长风北返。", "意见", "对白可以长说。", tail),
        )
        prompts.forEach { (name, p) ->
            assertTrue(name, p.endsWith(tail))
            assertTrue(name, "「嘴角勾起」" in p)
            assertTrue(name, StyleSamples.HEADER in p)
        }
        // 写法要求紧贴在“请写”之后。
        val draft = prompts.getValue("写本章")
        assertTrue(draft.indexOf(WritePrompts.VOICE_HEADER) > draft.indexOf("请写出这一章"))
        val inline = prompts.getValue("接着写")
        assertTrue(inline.indexOf(WritePrompts.VOICE_HEADER) > inline.indexOf("请从光标处接着往下写正文"))
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
}
