package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictGlossTest {
    // 汉典类词典的一条：先目录，再基本信息（部首笔画），再释义。
    private val entry = """
        <div class="toc">目录 1 基本信息 2 基本解释 2.1 基本字义 2.2 方言集汇 3 《康熙字典》 4 字源字形</div>
        <h2>基本信息</h2>
        <p>袧 简体部首:衤,部外笔画:5,总笔画:10 繁体部首:衣,部外笔画:5,总笔画:11</p>
        <p>拼音：gōu 注音：ㄍㄡ 五笔 86、98：PUQK</p>
        <h3>基本字义</h3>
        <p>● 袧</p>
        <p>1. 古代丧服上的褶子。</p>
        <p>2. 衣服上的褶子。</p>
        <p>3. 用于人名。</p>
    """.trimIndent()

    @Test
    fun skipsTheTableOfContentsAndCodesAndKeepsTheSenses() {
        val s = DictGloss.summarize(entry)
        assertEquals("gōu", s.reading)
        assertEquals("1. 古代丧服上的褶子。；2. 衣服上的褶子。", s.gloss)
    }

    @Test
    fun plainDefinitionsWithoutNumbersAreKept() {
        val s = DictGloss.summarize("<p>落英</p><p>落花。一说指初开的花。</p>")
        assertEquals("落花。一说指初开的花。", s.gloss)
    }

    @Test
    fun oldGlossesThatAreJustATableOfContentsAreHidden() {
        assertEquals("", DictGloss.clean("目录 1 基本信息 2 基本解释2.1 基本字义 2.2 方言集汇 3 《康熙字典》 4 字源字形 基本信息 袧 简体部首:衤,部外笔画:5"))
        assertEquals("落花", DictGloss.clean("落花"))
    }

    @Test
    fun longGlossesAreCut() {
        val s = DictGloss.summarize("<p>" + "很长的解释，".repeat(40) + "</p>", max = 20)
        assertTrue(s.gloss.length <= 20 && s.gloss.endsWith("…"))
    }
}

class FileNameTitleTest {
    @Test
    fun downloadedFileNamesGiveCleanTitlesAndAuthors() {
        assertEquals("覆汉", BookParser.titleOf("《覆汉》（校对版全本）作者：榴弹怕水 (1).txt"))
        assertEquals("榴弹怕水", BookParser.authorOf("《覆汉》（校对版全本）作者：榴弹怕水 (1).txt"))
        assertEquals("雪中悍刀行", BookParser.titleOf("雪中悍刀行（精校版）作者：烽火戏诸侯.txt"))
        assertEquals("烽火戏诸侯", BookParser.authorOf("雪中悍刀行（精校版）作者：烽火戏诸侯.txt"))
        assertEquals("红楼梦", BookParser.titleOf("红楼梦.epub"))
        assertEquals(null, BookParser.authorOf("红楼梦.epub"))
        assertEquals("第1卷", BookParser.titleOf("第1卷.txt"))
    }
}
