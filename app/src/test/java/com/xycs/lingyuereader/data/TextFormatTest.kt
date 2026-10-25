package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormatTest {
    private val all = FormatSettings()

    /** 只开 [kind] 这一类时排出来的样子。 */
    private fun only(text: String, kind: FormatKind, settings: FormatSettings = all) = TextFormat.format(text, settings, setOf(kind)).text

    private fun fmt(text: String, settings: FormatSettings = all) = TextFormat.format(text, settings).text

    // ---------- 1. 段首段尾空白 ----------

    @Test
    fun trimsParagraphEnds() {
        assertEquals("林月推开门。\n沈默没抬头。", only("　　林月推开门。  \n\t沈默没抬头。　", FormatKind.TRIM))
        // 只有空白的行变成空行；中间的空格不归这条管。
        assertEquals("老周 说\n\n杜娘", only("老周 说\n   \n杜娘", FormatKind.TRIM))
        // 关着时不动。
        assertEquals("　　渡口。", fmt("　　渡口。", all.copy(trim = false)))
    }

    // ---------- 2. 段内多余空格 ----------

    @Test
    fun removesSpacesBetweenChineseAndSqueezesEnglish() {
        assertEquals("林月看了沈默一眼，没说话。", only("林月 看了  沈默一眼 ， 没说话。", FormatKind.SPACE))
        assertEquals("老周念道：Hello world, here.", only("老周念道：Hello   world,　here.", FormatKind.SPACE))
        // 汉字和英文之间的一个空格留着（加不加空格是第 8 条的事）。
        assertEquals("他用 iPhone 拍照", only("他用 iPhone 拍照", FormatKind.SPACE))
        assertEquals("1 000 人", only("1  000  人", FormatKind.SPACE))
    }

    // ---------- 3. 空行 ----------

    @Test
    fun collapsesBlankLinesButKeepsSceneBreaks() {
        val text = "\n林月走了。\n\n\n\n***\n\n\n沈默留下。\n\n"
        assertEquals("林月走了。\n\n***\n\n沈默留下。", only(text, FormatKind.BLANK))
        assertEquals("林月走了。\n***\n沈默留下。", only(text, FormatKind.BLANK, all.copy(blankLines = BlankLines.NONE)))
        // “* * *”“——”单独一行的分隔原样不动（不换成别的、不去空格）。
        val breaks = "渡口起雾。\n* * *\n——\n老周来了。"
        assertEquals(breaks, fmt(breaks))
        assertEquals("a\n\n\nb", fmt("a\n\n\nb", all.copy(blankLines = BlankLines.KEEP)))
        // 只有空白的几行也算空行。
        assertEquals("林月。\n\n沈默。", fmt("林月。\n  \n　\n沈默。"))
    }

    // ---------- 4. 标点全角化 ----------

    @Test
    fun widensPunctuationInChineseOnly() {
        assertEquals("林月说，走吧。沈默问：去哪？", only("林月说,走吧.沈默问:去哪?", FormatKind.PUNCT))
        assertEquals("老周（掌柜）笑了！", only("老周(掌柜)笑了!", FormatKind.PUNCT))
        assertEquals("杜娘；渡口", only("杜娘;渡口", FormatKind.PUNCT))
        // 前后隔着空格、问号叹号连用也算。
        assertEquals("真的？！他说", only("真的?!他说", FormatKind.PUNCT))
        assertEquals("林月（Lin Yue）", only("林月(Lin Yue)", FormatKind.PUNCT))
    }

    @Test
    fun leavesNumbersUrlsAndEnglishAlone() {
        val keep = listOf(
            "圆周率是3.14，不是3。",
            "一共1,000两银子。",
            "约在12:30见。",
            "网址是http://a.b/c?x=1，记下了。",
            "English sentence, here.",
            "文件名是a.txt，别删。",
            "写信到lin@yue.cn，沈默说。",
            "他念道：Wait...",
            "***",
        )
        keep.forEach { assertEquals(it, only(it, FormatKind.PUNCT)) }
        // 网址后面跟着的句号算正文。
        assertEquals("看www.example.com。然后走", only("看www.example.com.然后走", FormatKind.PUNCT))
        // 成对的圆括号一起换。
        assertEquals("（abc）他说", only("(abc)他说", FormatKind.PUNCT))
    }

    // ---------- 5. 引号 ----------

    @Test
    fun pairsQuotesInOrder() {
        assertEquals("林月说：“走吧。”", only("林月说：\"走吧。\"", FormatKind.QUOTE))
        // 嵌套：外层双引号、里层单引号分开配。
        assertEquals("沈默说：“老周讲‘渡口没船’。”", only("沈默说：\"老周讲'渡口没船'。\"", FormatKind.QUOTE))
        // 方向反了的弯引号按顺序摆正。
        assertEquals("“你来了。”杜娘说。", only("”你来了。“杜娘说。", FormatKind.QUOTE))
        // 已经是中文引号的不动。
        val done = "“好。”林月说，“走‘这边’。”"
        assertEquals(done, only(done, FormatKind.QUOTE))
        // 「」风格。
        val corner = all.copy(quoteStyle = QuoteStyle.CORNER)
        assertEquals("林月说：「老周讲『走』。」", only("林月说：“老周讲‘走’。”", FormatKind.QUOTE, corner))
        // 英文里的引号、撇号不动。
        assertEquals("He said \"don't\".", only("He said \"don't\".", FormatKind.QUOTE))
        assertEquals("“Don't go,”林月说。", only("\"Don't go,\"林月说。", FormatKind.QUOTE))
    }

    @Test
    fun unpairedQuotesAreLeftAndNoted() {
        val r = TextFormat.format("林月说：\"走吧。\"沈默说：\"不走", all)
        assertEquals("林月说：“走吧。”沈默说：\"不走", r.text)
        val note = r.notes.single { it.hint == FormatHint.UNPAIRED }
        assertEquals('"', r.text[note.at])
        // 再排一次也一样，提示还在。
        val again = TextFormat.format(r.text, all)
        assertEquals(r.text, again.text)
        assertTrue(again.notes.any { it.hint == FormatHint.UNPAIRED })
    }

    // ---------- 6. 省略号、破折号 ----------

    @Test
    fun unifiesEllipsisAndDash() {
        listOf("他说...", "他说。。。", "他说．．．", "他说···", "他说…", "他说⋯⋯", "他说......").forEach {
            assertEquals(it, "他说……", only(it, FormatKind.ELLIPSIS))
        }
        assertEquals("林月——沈默", only("林月--沈默", FormatKind.DASH))
        assertEquals("林月——沈默", only("林月—沈默", FormatKind.DASH))
        assertEquals("林月——沈默", only("林月──沈默", FormatKind.DASH))
        assertEquals("林月——沈默", only("林月——沈默", FormatKind.DASH))
        // 英文里的不动。
        assertEquals("Wait... no -- stop", fmt("Wait... no -- stop"))
        assertEquals("a—b", only("a—b", FormatKind.DASH))
    }

    // ---------- 7. 重复标点 ----------

    @Test
    fun mergesRepeatedCommasAndPeriods() {
        assertEquals("林月，沈默。", only("林月，，，沈默。。", FormatKind.REPEAT))
        // 有意的叠用留着。
        assertEquals("什么！！！？？", only("什么！！！？？", FormatKind.REPEAT))
        assertEquals("什么！！！", only("什么！！！！！", FormatKind.REPEAT, all.copy(limitBangs = true)))
        // 三个句号是省略号，交给省略号那条。
        assertEquals("他说……", fmt("他说。。。"))
    }

    // ---------- 8. 中英文空格 ----------

    @Test
    fun mixedSpaceIsOffByDefault() {
        assertEquals("林月用iPhone拍了3张", fmt("林月用iPhone拍了3张"))
        assertEquals("林月用 iPhone 拍了 3 张", fmt("林月用iPhone拍了3张", all.copy(mixedSpace = true)))
    }

    // ---------- 9. 段末缺标点 ----------

    @Test
    fun hintsMissingEndPunctuation() {
        val r = TextFormat.format("林月推开门\n“你来了”\n沈默点头。\n“好。”", all)
        val hints = r.notes.filter { it.hint == FormatHint.NO_END }.map { it.line }
        assertEquals(listOf("林月推开门", "“你来了”"), hints)
        // 不自动补。
        assertTrue(r.text.startsWith("林月推开门\n"))
        assertTrue(TextFormat.format("林月推开门", all.copy(endHint = false)).notes.isEmpty())
    }

    // ---------- 综合 ----------

    @Test
    fun wholeChapterIsIdempotentAndKeepsHanzi() {
        val samples = listOf(
            "  林月说:\"老周,你来了...\"\n\n\n沈默 没说话 --  只是看着渡口.  \n***\n杜娘问:'几点?' 老周答:12:30.",
            "\"Hi\",林月说.他(沈默)笑了!!!真的?!\n网址http://a.b/c?x=1.文件a.txt,1,000两,3.14.",
            "”反了“他说……。。。\n——\n“没配上\n林月——沈默—老周──杜娘",
            "He said \"don't\", ok... 然后林月 , 沈默 。。",
        )
        listOf(all, all.copy(quoteStyle = QuoteStyle.CORNER, mixedSpace = true, limitBangs = true, blankLines = BlankLines.NONE)).forEach { s ->
            samples.forEach { text ->
                val once = TextFormat.format(text, s).text
                assertEquals("排两次和排一次一样：$text", once, TextFormat.format(once, s).text)
                // 汉字一个不多、一个不少，顺序也不变。
                assertEquals(text.filter(::isHan), once.filter(::isHan))
            }
        }
    }

    @Test
    fun recordsWhereEachChangeIs() {
        val text = "林月说:\"走吧...\""
        val r = TextFormat.format(text, all)
        assertEquals("林月说：“走吧……”", r.text)
        val byKind = r.changes.associateBy { it.kind }
        assertEquals(setOf(FormatKind.PUNCT, FormatKind.QUOTE, FormatKind.ELLIPSIS), byKind.keys)
        val colon = byKind.getValue(FormatKind.PUNCT)
        assertEquals(3, colon.start)
        assertEquals(":", colon.before)
        assertEquals("：", colon.after)
        val dots = byKind.getValue(FormatKind.ELLIPSIS)
        assertEquals("...", text.substring(dots.start, dots.end))
        assertEquals(2, r.changes.count { it.kind == FormatKind.QUOTE })
        assertEquals(mapOf(FormatKind.PUNCT to 1, FormatKind.QUOTE to 2, FormatKind.ELLIPSIS to 1), r.counts)
        // 光标换算：原文末尾 → 排好的末尾。
        assertEquals(r.text.length, r.map(text.length))
        // 勾掉一类就不跑那一类。
        assertEquals("林月说：“走吧...”", TextFormat.format(text, all, all.kinds - FormatKind.ELLIPSIS).text)
        assertFalse(TextFormat.format("林月推开门。", all).changed)
    }

    @Test
    fun settingsRoundTripThroughJson() {
        val s = FormatSettings(trim = false, blankLines = BlankLines.NONE, quoteStyle = QuoteStyle.CORNER, mixedSpace = true, onInput = false)
        assertEquals(s, FormatSettings.fromJson(s.toJson()))
        assertEquals(FormatSettings(), FormatSettings.fromJson(null))
        assertEquals(FormatSettings(), FormatSettings.fromJson(org.json.JSONObject("{\"blankLines\":\"WHAT\"}")))
    }

    @Test
    fun settingsGoWithTheBackup() {
        val dirs = listOf(java.nio.file.Files.createTempDirectory("fmt-a").toFile(), java.nio.file.Files.createTempDirectory("fmt-b").toFile())
        try {
            val a = WorkStore(dirs[0])
            val w = a.create("渡口")
            val s = FormatSettings(quoteStyle = QuoteStyle.CORNER, onInput = false, blankLines = BlankLines.NONE)
            a.updateAssist(w.id) { it.copy(format = s) }
            val b = WorkStore(dirs[1])
            assertEquals(1, b.restoreBackup(String(a.backup()!!)))
            assertEquals(s, b.assist(w.id).format)
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    // ---------- 输入时 ----------

    private fun typed(before: String, typed: String, style: QuoteStyle = QuoteStyle.CURLY, composing: Boolean = false): String {
        val text = before + typed
        val f = TypingFormat.fix(text, before.length, text.length, style, composing) ?: return text
        return text.substring(0, f.start) + f.text + text.substring(f.end)
    }

    /** 一个字一个字地打。 */
    private fun typeEach(s: String, style: QuoteStyle = QuoteStyle.CURLY): String = s.fold("") { acc, c -> typed(acc, c.toString(), style) }

    @Test
    fun typingConvertsAsYouGo() {
        assertEquals("他说……", typeEach("他说..."))
        assertEquals("他说……", typed("", "他说..."))
        assertEquals("他说..", typeEach("他说.."))
        assertEquals("林月——", typeEach("林月--"))
        assertEquals("林月，沈默。", typeEach("林月,沈默."))
        assertEquals("真的？！", typeEach("真的?!"))
        // 数字后面的点不换（好打小数），英文里的不换。
        assertEquals("3.14", typeEach("3.14"))
        assertEquals("v1.2", typeEach("v1.2"))
        assertEquals("Hi, ok.", typeEach("Hi, ok."))
        assertEquals("12:30", typeEach("12:30"))
        // 引号按段里配对。
        assertEquals("“”", typeEach("\"\""))
        assertEquals("林月说：“走‘这边’。”", typeEach("林月说：\"走'这边'。\""))
        assertEquals("「好」", typeEach("\"好\"", QuoteStyle.CORNER))
        assertEquals("don't", typeEach("don't"))
        // 换了一段重新配。
        assertEquals("“好”\n“", typeEach("\"好\"\n\""))
    }

    @Test
    fun typingWaitsForTheImeComposition() {
        // 拼音还在组合中：不换。
        assertNull(TypingFormat.fix("他说,", 2, 3, QuoteStyle.CURLY, composing = true))
        // 提交了：换，光标落在换好的字后面。
        val f = TypingFormat.fix("他说,", 2, 3, QuoteStyle.CURLY)!!
        assertEquals(TypingFormat.Fix(2, 3, "，", 3), f)
        // 没有要换的。
        assertNull(TypingFormat.fix("他说。", 2, 3, QuoteStyle.CURLY))
        // 一大段粘贴不动。
        val big = "啊,".repeat(TypingFormat.MAX_INSERT)
        assertNull(TypingFormat.fix(big, 0, big.length, QuoteStyle.CURLY))
    }
}
