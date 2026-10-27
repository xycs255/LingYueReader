package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.SyncGroup
import com.xycs.lingyuereader.data.SyncPlace
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
        val (new, conflicts, aware) = listOf(SyncGroup.NEW, SyncGroup.CONFLICT, SyncGroup.AWARENESS).map { g -> r.items.filter { it.group == g } }
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
        // 旧的回法（只有 new、conflicts、awareness）照样能用：察觉记进账本，是一条知情。
        assertEquals(3, SyncGroup.entries.count { g -> r.items.any { it.group == g } })
        val know = aware.single().entry!!
        assertEquals(SyncPlace.LEDGER, aware.single().place)
        assertEquals(LedgerKind.KNOW, know.kind)
        assertEquals("林月", know.subject)
        assertEquals("沈默是朝廷的卧底", know.text)
        assertEquals(12, know.from)
        assertEquals("自己看出来的", know.via)
        assertEquals("林月这才知道沈默是朝廷的卧底。", know.quote)
        assertEquals(LedgerOrigin.SYNC, know.origin)
    }

    @Test
    fun promptCarriesTheLedgerAndAsksForTheNewGroups() {
        val ledger = ChapterSyncs.ledgerView(
            listOf(
                LedgerEntry(kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 8, payoff = 11),
                LedgerEntry(kind = LedgerKind.STATE, text = "左臂受了伤", subject = "林月", aspect = "身体", from = 10),
            ),
            12, present = listOf("林月"),
        )
        val p = WritePrompts.sync("第十二章 渡口", 12, chapter, ledger = ledger.text)
        assertTrue("P1：顾长风说三日后再来（第 8 章埋，计划第 11 章收）" in p && "林月·身体：左臂受了伤" in p && "到上一章（第 11 章）为止" in p)
        // 账本放在正文后面、要求前面。
        assertTrue(p.indexOf("林月这才知道") < p.indexOf("P1：") && p.indexOf("P1：") < p.indexOf("只找这几类"))
        listOf("\"states\"", "\"events\"", "\"awareness\"", "\"threads_opened\"", "\"threads_closed\"", "\"time\"", "\"payoff\"").forEach { assertTrue(it, it in p) }
        assertTrue(LEDGER_ASPECTS.joinToString("、") in p)
        assertTrue("账本里已有的" in p && "只能写上面账本“开着的伏笔”里列出的编号" in p)
        // 账本空着：不带账本，也不说“账本里已有的”。
        val bare = WritePrompts.sync("第十二章 渡口", 12, chapter)
        assertFalse("【故事账本" in bare || "账本里已有的" in bare)
    }

    @Test
    fun parsesTheLedgerGroupsAndMapsPromiseCodesBack() {
        val promise = LedgerEntry(id = "p-123", kind = LedgerKind.PROMISE, text = "杜娘说老周会回来", from = 5)
        val answer = """{"new":[],"conflicts":[],
            "states":[{"who":"林月","aspect":"所在位置","now":"在渡口的茶馆","quote":"杜娘把茶碗推给林月"},
                      {"who":"顾长风","aspect":"心情","now":"着急","quote":"顾长风在门外喊她“月丫头”。"},
                      {"who":"沈默","aspect":"位置","now":"在京城","quote":"沈默在京城看雪"}],
            "events":[{"what":"老周昨夜死了","quote":"老周昨夜里走了"}],
            "awareness":[],
            "threads_opened":[{"what":"顾长风在门外等林月","payoff":"第15章","quote":"顾长风在门外喊她"},{"what":"林月要查清沈默的来历","payoff":null,"quote":"林月这才知道沈默是朝廷的卧底"}],
            "threads_closed":[{"id":"P1","quote":"老周昨夜里走了"},{"id":"P9","quote":"渡口起了雾。"},{"id":"1","quote":"渡口起了雾。"}],
            "time":{"when":"入冬后的一个雾天","quote":"渡口起了雾。"}}"""
        val r = WritePrompts.parseSync(answer, chapter, "c1", 12, mapOf("P1" to promise))
        // 沈默那条原句是编的：丢掉；P9 不是列出的编号：丢掉，不算原句的。
        assertEquals(1, r.dropped)
        val states = r.items.filter { it.group == SyncGroup.STATE }
        assertEquals(listOf("林月·位置：在渡口的茶馆", "顾长风·心情：着急"), states.map { it.title })
        // 方面照常用的几项认；认不出的照写。
        assertEquals(listOf("位置", "心情"), states.map { it.entry!!.aspect })
        assertEquals(listOf("林月", "顾长风"), states.map { it.entry!!.subject })
        assertTrue(r.items.all { it.place == SyncPlace.LEDGER && it.writable && it.defaultOn && it.where == "记进故事账本" })
        val event = r.items.single { it.group == SyncGroup.EVENT }
        assertEquals(LedgerKind.EVENT, event.entry!!.kind)
        assertEquals("老周昨夜死了", event.text)
        assertEquals("老周昨夜里走了", event.entry!!.quote)
        val promises = r.items.filter { it.group == SyncGroup.PROMISE }
        assertEquals(listOf("埋下：顾长风在门外等林月", "埋下：林月要查清沈默的来历", "收起：杜娘说老周会回来", "收起：杜娘说老周会回来"), promises.map { it.title })
        assertEquals(listOf(15, null), promises.take(2).map { it.entry!!.payoff })
        assertTrue(promises.take(2).all { it.entry!!.kind == LedgerKind.PROMISE && it.entry!!.from == 12 })
        // 编号认回账本那一条（“1”也当 P1）。
        assertEquals(listOf("p-123", "p-123"), promises.drop(2).map { it.closes!!.id })
        val time = r.items.single { it.group == SyncGroup.TIME }
        assertEquals(LedgerKind.TIME, time.entry!!.kind)
        assertEquals("入冬后的一个雾天", time.entry!!.text)
        assertEquals(12, time.entry!!.from)
        // 只回了账本类的也算按格式回了；time 空着不算一条。
        assertEquals(1, parse("""{"events":[{"what":"老周昨夜死了","quote":"老周昨夜里走了"}],"time":{}}""").items.size)
        assertTrue(parse("""{"time":{}}""").items.isEmpty())
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
    fun quotesMustBeASentenceNotJustANameOrAFewWords() {
        val text = "林月，沈默。\n杜娘把茶碗推给林月，说：“老周昨夜里走了。”\n渡口起了雾。"
        val names = listOf("林月", "沈默", "杜娘", "老周")
        // 名字、“说”这类两三个字的片段：正文里一定找得到，不算依据。
        assertNull(WritePrompts.findQuote(text, "林月"))
        assertNull(WritePrompts.findQuote(text, "说："))
        // 不到 6 个字、又不是完整一句的：不算。
        assertNull(WritePrompts.findQuote(text, "杜娘把茶碗"))
        assertNull(WritePrompts.findQuote(text, "老周昨夜"))
        // 完整的短句可以；只是几个人名凑成的一句不行。
        assertEquals("渡口起了雾。", WritePrompts.findQuote(text, "渡口起了雾。"))
        assertEquals("林月，沈默。", WritePrompts.findQuote(text, "林月，沈默。"))
        assertNull(WritePrompts.findQuote(text, "林月，沈默。", names))
        // 够长的照常认。
        assertEquals("杜娘把茶碗推给林月", WritePrompts.findQuote(text, "杜娘把茶碗推给林月", names))
        assertEquals("老周昨夜里走了", WritePrompts.findQuote(text, "“老周昨夜里走了”", names))
        // 解析时依据只填人名的提议丢掉，算进“找不到原句”。
        val r = WritePrompts.parseSync(
            """{"events":[{"what":"林月杀了沈默","quote":"林月"},{"what":"老周走了","quote":"老周昨夜里走了"}]}""",
            text, "c1", 12, names = names,
        )
        assertEquals(1, r.dropped)
        assertEquals(listOf("老周走了"), r.items.map { it.title })
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
