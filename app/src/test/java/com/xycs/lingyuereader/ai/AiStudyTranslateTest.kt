package com.xycs.lingyuereader.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.InlineText
import com.xycs.lingyuereader.data.Sentences
import com.xycs.lingyuereader.data.VocabWord
import com.xycs.lingyuereader.data.Vocabulary
import com.xycs.lingyuereader.data.displayText
import com.xycs.lingyuereader.data.spokenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AiStudyTranslateTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readingNotesKeepTheLatestMarksWhenTooLong() {
        val p = AiStudy.notes("书", "旧".repeat(AiStudy.NOTES_CHARS) + "最新的批注")
        assertTrue(p.contains("最新的批注"))
        assertTrue(p.contains("前面略"))
        assertTrue(p.contains("不编造"))
    }

    @Test
    fun glossesParseOnlyAskedWords() {
        val answer = """```json
            {"items":[{"word":"缤纷","reading":"bīn fēn","gloss":"繁多交杂"},{"word":"别的","gloss":"x"},{"word":"落英","gloss":""}]}
            ```"""
        val got = AiStudy.parseGlosses(answer, setOf("缤纷", "落英"))
        assertEquals(mapOf("缤纷" to ("bīn fēn" to "繁多交杂")), got)
        assertTrue(AiStudy.parseGlosses("不是 JSON", setOf("缤纷")).isEmpty())
    }

    @Test
    fun vocabularyKeepsAiExamplesAndFilledGlosses() {
        val v = Vocabulary(File(tmp.root, "v.json"))
        v.add(VocabWord("缤纷", "", "", "落英缤纷。", "b", "书", 1))
        v.annotate("缤纷", gloss = "繁多交杂", reading = "bīn fēn", example = "节日里彩灯缤纷。")
        val w = Vocabulary(File(tmp.root, "v.json")).list().single()
        assertEquals("繁多交杂", w.gloss)
        assertEquals("bīn fēn", w.reading)
        assertEquals("节日里彩灯缤纷。", w.example)
        v.annotate("缤纷", reading = "x")
        assertEquals("已有的读音不覆盖", "bīn fēn", v.list().single().reading)
    }

    @Test
    fun translationsSitAfterTheOriginalWithoutMovingIt() {
        val raw = InlineText.withTranslation("**落英**缤纷。", "Petals fall in profusion.")
        val parsed = InlineText.parse(raw)
        assertTrue(parsed.text.startsWith("落英缤纷。\n"))
        assertEquals("Petals fall in profusion.", parsed.text.substring(parsed.translation.single().first, parsed.translation.single().last + 1))
        assertEquals(listOf(0..1), parsed.bold)
        assertEquals("**落英**缤纷。", InlineText.stripTranslation(raw))
        assertEquals("落英缤纷。", Block.Para(raw).spokenText)
        // 再接一次译文不会叠两份。
        assertEquals(1, InlineText.parse(InlineText.withTranslation(raw, "新译")).translation.size)
    }

    private val content = BookContent(
        "书", null,
        listOf(
            Chapter("Chapter 1", listOf(Block.Heading(1, "Chapter 1"), Block.Para("It was a bright cold day."), Block.Rule, Block.Para("The clocks were striking thirteen."))),
            Chapter("Chapter 2", listOf(Block.Para("Second."))),
        ),
    )

    @Test
    fun batchesNumberParagraphsAndAnswersMapBack() {
        val batches = AiTranslate.batches(content.chapters[0].blocks)
        assertEquals(listOf(listOf(0, 1, 3)), batches.map { b -> b.map { it.first } })
        val prompt = AiTranslate.prompt(batches.single(), TranslateTarget.CHINESE)
        assertTrue(prompt.contains("[[3]] The clocks were striking thirteen."))
        val got = AiTranslate.parse("[[1]] 第一章\n[[2]] 那是四月里晴朗而寒冷的一天。\n[[3]]  钟敲了十三下。\n[[9]] 多余", batches.single())
        assertEquals(mapOf(0 to "第一章", 1 to "那是四月里晴朗而寒冷的一天。", 3 to "钟敲了十三下。"), got)
        assertEquals(TranslateTarget.CHINESE, AiTranslate.defaultTarget(content, 0))
        val chinese = BookContent("书", null, listOf(Chapter("一", listOf(Block.Para("晋太元中，武陵人捕鱼为业。")))))
        assertEquals(TranslateTarget.VERNACULAR, AiTranslate.defaultTarget(chinese, 0))
    }

    @Test
    fun applyShowsSavedTranslationsAndKeepsBlockCoordinates() {
        val t = Translations(chapters = mapOf(0 to ("Chapter 1" to mapOf(1 to "那是晴朗寒冷的一天。"))))
        val file = File(tmp.root, AiTranslate.FILE)
        AiTranslate.write(file, t)
        assertEquals(t, AiTranslate.read(file))
        val shown = AiTranslate.apply(content, t)
        val blocks = shown.chapters[0].blocks
        assertEquals(content.chapters[0].blocks.size, blocks.size)
        assertTrue(blocks[1].displayText!!.endsWith("那是晴朗寒冷的一天。"))
        assertEquals("It was a bright cold day.", blocks[1].spokenText)
        assertEquals(content.chapters[1], shown.chapters[1])
        // 隐藏、或章名对不上（改稿后章序变了）时原样。
        assertEquals(content, AiTranslate.apply(content, t.copy(show = false)))
        assertFalse(AiTranslate.apply(content, Translations(chapters = mapOf(0 to ("别的章" to mapOf(1 to "x"))))).chapters[0].blocks[1].displayText!!.contains("x"))
        // 听书只读原文。
        assertTrue(Sentences.split(blocks, 0, 0).none { it.text.contains("晴朗") })
    }

    @Test
    fun translationsOfParagraphsThatChangedAreHidden() {
        val hashes = mapOf(0 to mapOf(1 to AiTranslate.hash(content.chapters[0].blocks[1])))
        val t = Translations(chapters = mapOf(0 to ("Chapter 1" to mapOf(1 to "那是晴朗寒冷的一天。"))), hashes = hashes)
        val file = File(tmp.root, "hashed.json")
        AiTranslate.write(file, t)
        assertEquals(t, AiTranslate.read(file))
        assertTrue(AiTranslate.apply(content, t, content).chapters[0].blocks[1].displayText!!.contains("晴朗"))
        // 书刷新后这一段换了内容：同一段号的译文不再显示。
        val changed = content.copy(chapters = listOf(content.chapters[0].withBlocks(content.chapters[0].blocks.mapIndexed { i, b ->
            if (i == 1) com.xycs.lingyuereader.data.Block.Para("A completely different paragraph.") else b
        })) + content.chapters.drop(1))
        assertFalse(AiTranslate.apply(changed, t, changed).chapters[0].blocks[1].displayText!!.contains("晴朗"))
    }
}
