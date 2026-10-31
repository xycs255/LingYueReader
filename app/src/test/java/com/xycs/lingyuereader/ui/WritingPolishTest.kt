package com.xycs.lingyuereader.ui

import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.PackKindStat
import com.xycs.lingyuereader.data.QuoteStyle
import com.xycs.lingyuereader.data.SampleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写作页逐页打磨里几处纯计算：分类格排几列、哪些格挂标记、人物卡的方面怎么分、引号风格的叫法、范例的分类色、草稿页的细纲算不算全空。 */
class WritingPolishTest {
    /** 设定包分类格每格不窄于 112dp：360dp 的屏（左右各缩 12dp，剩 336dp）排两列，411dp 排三列，平板多几列。 */
    @Test fun tileGridHonoursTheMinimumWidth() {
        assertEquals(2, adaptiveColumns(336.dp, 112.dp, 8.dp))
        assertEquals(3, adaptiveColumns(387.dp, 112.dp, 8.dp))
        assertEquals(6, adaptiveColumns(900.dp, 112.dp, 8.dp))
        // 两列也放不下时还是两列（最少两列），不排成一长条。
        assertEquals(2, adaptiveColumns(200.dp, 112.dp, 8.dp))
        // 三列时每格真的不窄于 112dp。
        val cols = adaptiveColumns(387.dp, 112.dp, 8.dp)
        assertTrue((387.dp - 8.dp * (cols - 1)) / cols >= 112.dp)
    }

    /** 有文档的类挂“常用 / 按需”，空的类不挂。 */
    @Test fun onlyFilledKindsCarryTheModeBadge() {
        assertEquals("常用 1", packKindBadge(PackKindStat(PackKind.CHARACTER, docs = 2, words = 40, always = 1)))
        assertEquals("按需", packKindBadge(PackKindStat(PackKind.REFERENCE, docs = 1, words = 10, always = 0)))
        assertNull(packKindBadge(PackKindStat(PackKind.CANON, docs = 0, words = 0, always = 0)))
        assertNull(packKindBadge(PackKindStat(PackKind.INSTRUCTION, docs = 0, words = 0, always = 0)))
    }

    /** 人物卡：记了的方面按常用顺序、自己写的排后面；没记的常用方面合成“+ 添加方面”里的选项。 */
    @Test fun personAspectsSplitIntoFilledAndMissing() {
        fun state(aspect: String, text: String) = LedgerEntry(kind = LedgerKind.STATE, text = text, subject = "林月", aspect = aspect, from = 1)
        val (filled, missing) = ledgerPersonAspects(listOf(state("师门", "青崖"), state("身体", "左臂伤"), state("位置", "渡口")))
        assertEquals(listOf("位置", "身体", "师门"), filled.map { it.aspect })
        assertEquals(LEDGER_ASPECTS - setOf("位置", "身体"), missing)
        val (none, all) = ledgerPersonAspects(emptyList())
        assertTrue(none.isEmpty())
        assertEquals(LEDGER_ASPECTS, all)
    }

    /** 排版设置的引号风格写成“中文引号 “””“直角引号 「」”，不只两个小符号。 */
    @Test fun quoteStylesAreNamed() {
        assertEquals("中文引号 “”", quoteStyleName(QuoteStyle.CURLY))
        assertEquals("直角引号 「」", quoteStyleName(QuoteStyle.CORNER))
    }

    /** 文风范例三类各一个分类色，不用强调色（朱砂看着像出错）、提醒色、成功色。 */
    @Test fun sampleKindsUseCategoryTones() {
        val tones = SampleKind.entries.map(::sampleTone)
        assertEquals(tones.size, tones.distinct().size)
        assertFalse(CategoryTone.TINT in tones)
        assertFalse(CategoryTone.WARNING in tones)
        assertFalse(CategoryTone.SUCCESS in tones)
    }

    /** 草稿页“你的构思”：细纲卡全空才合成一行“先写细纲卡”；只写了节拍、还没读出来都照常排四格。 */
    @Test fun draftIdeaIsEmptyOnlyForABlankCard() {
        assertTrue(draftIdeaEmpty(ChapterCard()))
        assertTrue(draftIdeaEmpty(ChapterCard(summary = "写完以后的摘要不算")))
        assertFalse(draftIdeaEmpty(ChapterCard(beats = "林月到渡口")))
        assertFalse(draftIdeaEmpty(ChapterCard(pov = "沈默")))
        assertFalse(draftIdeaEmpty(null))
    }
}
