package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.SyncGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 整理本章进设定包：提示词、解析（畸形 JSON、依据核对、冲突）。 */
class WriteSyncPromptsTest {
    private val chapter = "渡口起了雾。\n杜娘把茶碗推给林月，说：“老周昨夜里走了。”\n顾长风在门外喊她“月丫头”。\n林月这才知道沈默是朝廷的卧底。"

    private fun parse(answer: String) = WritePrompts.parseSync(answer, chapter, "c1", 12)

    @Test
    fun promptAsksForQuotesAndForbidsGuessing() {
        val p = WritePrompts.sync("第十二章 渡口", 12, chapter)
        assertTrue("按设定算第 12 章" in p && "杜娘把茶碗推给林月" in p)
        assertTrue("不推测" in p && "设定包里已经有的不要重复提" in p && "称谓与语气" in p && "防剧透" in p)
        assertTrue("quote" in p && "\"new\"" in p && "\"conflicts\"" in p && "\"awareness\"" in p)
    }

    @Test
    fun parsesAllThreeGroupsFromAFencedAnswerWithChatter() {
        val answer = """好的，整理如下：
```json
{"new":[{"type":"人物","name":"杜娘","kind":"人物档案","alias":"杜掌柜","identity":"渡口茶馆老板娘","relation":"和老周相熟","did":"告诉林月老周死了","quote":"杜娘把茶碗推给林月"},
        {"type":"地名","name":"渡口","kind":"CANON","section":"地名","text":"城东的渡口，常起雾","quote":"「渡口起了雾。」"}],
 "conflicts":[{"title":"老周的生死","kind":"人物档案","doc":"人物档案","pack":"老周 | 第九章战死","chapter":"第十二章才走","fix":"text","quote":"老周昨夜里走了"}],
 "awareness":[{"who":"林月","what":"沈默是朝廷的卧底","type":"知道","since":12,"source":"自己看出来的","quote":"林月这才知道沈默是朝廷的卧底。"}]}
```
以上。"""
        val r = parse(answer)
        assertEquals(0, r.dropped)
        val (new, conflicts, aware) = SyncGroup.entries.map { g -> r.items.filter { it.group == g } }
        assertEquals(listOf("新人物：杜娘", "新地名：渡口"), new.map { it.title })
        assertEquals(PackKind.CHARACTER, new[0].kind)
        assertEquals("杜掌柜", new[0].field("称呼"))
        assertEquals(PackKind.CANON, new[1].kind)
        // 原句两头的引号去掉，核对的是正文里那一句。
        assertEquals("渡口起了雾。", new[1].quote)
        assertEquals(1, conflicts.size)
        assertTrue(conflicts[0].fixText)
        assertEquals("老周 | 第九章战死", conflicts[0].packSays)
        assertEquals("林月：沈默是朝廷的卧底", aware.single().title)
        assertEquals("第12章", aware.single().field("从第几章起"))
        assertTrue(r.items.all { it.chapter == 12 && it.chapterId == "c1" })
    }

    @Test
    fun quotesNotInTheChapterAreDropped() {
        val answer = """{"new":[{"type":"人物","name":"老韩","quote":"老韩提着刀进了门"},{"type":"物件","name":"茶碗","text":"杜娘的粗瓷茶碗","quote":"杜娘把茶碗推给林月"}],
            "awareness":[{"who":"沈默","what":"林月的身世","quote":"沈默早就知道她是谁"}]}"""
        val r = parse(answer)
        assertEquals(2, r.dropped)
        assertEquals(listOf("新物件：茶碗"), r.items.map { it.title })
    }

    @Test
    fun truncatedQuotesMatchByTheirStart() {
        assertEquals("顾长风在门外喊她", WritePrompts.findQuote(chapter, "顾长风在门外喊她……"))
        assertNull(WritePrompts.findQuote(chapter, "顾长风……"))
        assertNull(WritePrompts.findQuote(chapter, "月"))
    }

    @Test
    fun missingFieldsAreTolerated() {
        // 缺 kind、缺 since、缺 conflicts 都行；名字和说明都没有的、谁和什么缺一样的丢掉。
        val r = parse("""{"new":[{"name":"顾长风","type":"人物","quote":"顾长风在门外喊她“月丫头”。"},{"type":"地名","quote":"渡口起了雾"}],
            "awareness":[{"who":"林月","quote":"林月这才知道沈默是朝廷的卧底"},{"who":"林月","what":"（瞒着）她知道了沈默的身份","type":"隐瞒","quote":"林月这才知道"}]}""")
        assertEquals(listOf("新人物：顾长风", "林月：（隐瞒）（瞒着）她知道了沈默的身份"), r.items.map { it.title })
        assertEquals("第12章", r.items[1].field("从第几章起"))
    }

    @Test
    fun answersThatAreNotTheFormatAreErrorsNotEmptyResults() {
        for (bad in listOf("这一章没有要整理的。", "{\"issues\":[]}", "{\"new\": [", "")) {
            try {
                parse(bad)
                fail("“$bad”不该当成解析成功")
            } catch (e: AiException) {
                assertEquals("模型没有按格式回答", e.message)
            }
        }
        // 三类都给了空列表：没什么要整理，不是出错。
        assertTrue(parse("{\"new\":[],\"conflicts\":[],\"awareness\":[]}").items.isEmpty())
    }

    @Test
    fun conflictsSayWhichSideToFix() {
        val r = parse("""{"conflicts":[
            {"title":"顾长风怎么叫林月","kind":"称谓与语气","pack":"顾长风叫林月“林姑娘”","chapter":"叫她“月丫头”","fix":"pack","text":"顾长风私下叫林月“月丫头”","quote":"顾长风在门外喊她“月丫头”。"},
            {"title":"没写原句的","fix":"pack","text":"x"},
            {"chapter":"老周第十二章才走","fix":"正文写错了","quote":"老周昨夜里走了"}]}""")
        assertEquals(1, r.dropped)
        val (pack, text) = r.items
        assertFalse(pack.fixText)
        assertEquals(PackKind.VOICE, pack.kind)
        assertEquals("顾长风私下叫林月“月丫头”", pack.text)
        assertTrue(text.fixText)
        assertEquals("老周第十二章才走", text.title)
        assertEquals("", text.text)
    }

    @Test
    fun longChaptersAreSplitAtParagraphs() {
        val text = List(2_000) { "渡口的雾一直没散，林月坐在窗边数更声。$it" }.joinToString("\n")
        val parts = WritePrompts.syncParts(text)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= WritePrompts.CHAPTER_CHARS })
        // 一字不少，每段都在段落末尾切开。
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.dropLast(1).all { it.endsWith("\n") })
        assertEquals(listOf(chapter), WritePrompts.syncParts(chapter))
        val p = WritePrompts.sync("第十二章 渡口", 12, parts[1], 2, parts.size, parts[0].takeLast(WritePrompts.SYNC_OVERLAP))
        assertTrue("分 ${parts.size} 段整理" in p && "第 2 段" in p && "上一段的末尾" in p && "按设定算第 12 章" in p && "只整理这一段" in p)
        assertTrue(parts[1].take(20) in p)
    }
}
