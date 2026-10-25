package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 故事账本接进本章资料：快照进资料和随章提示、和察觉表伏笔表去重、只有作者知道的另列、预算；从设定包导入、导出再读回来。 */
class LedgerBriefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val characters = """
        # 人物档案

        | 人物 | 想要 | 结局 |
        |---|---|---|
        | 林月（原创，药铺女儿） | 一个家 | |
        | 沈默 | 查清旧案 | |
        | 老周 | 守住渡口 | 第九章战死 |

        ## 杜娘
        开茶馆的寡妇，消息灵通。称呼：杜掌柜
    """.trimIndent()

    private val awareness = """
        # 察觉表

        ### 林月

        | 卷 | 线索 | 消息从哪来 | 真相 |
        | --- | --- | --- | --- |
        | 一 | 沈默腰间有半块玉佩 | 自己看见 | |
        | 一（第二章） | 杜娘说出玉佩来历 | 茶馆里听来 | 杜娘是在试探她 |
        | 二 | 沈默亮出另一半 | | |

        ## 其他

        | 谁 | 知道什么 | 从第几章起 |
        | --- | --- | --- |
        | 沈默、杜掌柜 | 老周夜里出过城 | 第3章 |
    """.trimIndent()

    private val foreshadow = """
        | 伏笔 | 埋下 | 回收 | 状态 |
        | --- | --- | --- | --- |
        | 半块玉佩 | 第1章 | 第3章 | |
        | 老周的断指 | 第1章 | 第9章 | |
        | 门闩刀痕 | 第1章 | 第2章 | 已回收 |
        | 渡口的旧船 | 第1章 | 第4章 | 已回收（第3章） |
    """.trimIndent()

    private fun docs(): List<Pair<PackDoc, String>> = listOf(
        PackDoc("c", PackKind.CHARACTER, "人物档案", 3, true) to characters,
        PackDoc("a", PackKind.AWARENESS, "察觉表", 4, false) to awareness,
        PackDoc("f", PackKind.FORESHADOW, "伏笔表", 4, false) to foreshadow,
    )

    private fun awarenessDocs(vararg rows: String): List<Pair<PackDoc, String>> = listOf(
        PackDoc("people", PackKind.CHARACTER, "人物档案", 3, false) to
            "| 人物 | 别名 | 身份 |\n|---|---|---|\n| 林月 | 小月 | 药铺学徒 |\n| 沈默 | 子静 | 镖局账房 |",
        PackDoc("awareness", PackKind.AWARENESS, "察觉表", 4, false) to
            ("| 谁 | 知道什么 | 从第几章起 |\n|---|---|---|\n" + rows.joinToString("\n")),
    )

    private fun state(who: String, aspect: String, text: String, from: Int?) =
        LedgerEntry(id = "s$who$aspect$from", kind = LedgerKind.STATE, text = text, subject = who, aspect = aspect, from = from)

    private fun know(who: String, text: String, from: Int?, volume: Int? = null, via: String = "", secret: Boolean = false) =
        LedgerEntry(id = "k$who$text", kind = LedgerKind.KNOW, text = text, subject = who, from = from, volume = volume, via = via, secret = secret)

    private fun promise(text: String, from: Int?, payoff: Int? = null, done: Boolean = false, closed: Int? = null) =
        LedgerEntry(id = "p$text", kind = LedgerKind.PROMISE, text = text, from = from, payoff = payoff, done = done, closed = closed)

    private val ledger = listOf(
        state("林月", "位置", "在渡口等船", 1),
        state("林月", "身体", "左臂擦伤", 2),
        LedgerEntry(id = "t", kind = LedgerKind.TIME, text = "立冬前一天傍晚", from = 2),
        know("林月", "杜娘说出玉佩来历", 2, via = "茶馆"),
        promise("半块玉佩", 1, payoff = 2),
        LedgerEntry(id = "x", kind = LedgerKind.EVENT, text = "渡口的船停了一班", from = 1),
    )

    private fun count(text: String, word: String) = text.windowed(word.length).count { it == word }

    @Test
    fun snapshotGoesIntoTheBriefAfterThePast() {
        val summaries = listOf(ChapterSummary(1, "夜渡", "林月夜里到了渡口。"))
        val b = ChapterBriefs.build(docs(), "林月推开门，老周不在。", "视角：林月", chapter = 2, volume = 1, summaries = summaries, ledger = ledger)
        val snap = b.ledger
        assertNotNull(snap)
        snap!!
        assertEquals(2, snap.chapter)
        assertEquals(listOf("在渡口等船", "左臂擦伤"), snap.states.map { it.text })
        val text = b.render()!!
        val at = text.indexOf("【故事账本：到第 2 章为止")
        assertTrue(at > 0)
        // 前情之后，人物卡、察觉、伏笔之前。
        assertTrue(text.indexOf("【前情·") in 0 until at)
        assertTrue(at < text.indexOf("【人物·林月】"))
        assertTrue(at < text.indexOf("【察觉·"))
        assertTrue("林月·位置：在渡口等船" in text)
        assertTrue("立冬前一天傍晚" in text)
        assertEquals(b.ledgerText, text.substring(at, at + b.ledgerText!!.length))
    }

    @Test
    fun chapterHintsCarryTheSnapshot() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.renameChapter(w.id, w.chapters[0].id, "第一章 夜渡")
        val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章 对岸")!!
        s.saveCard(w.id, w.chapters[0].id, s.card(w.id, w.chapters[0].id).copy(summary = "林月夜里到了渡口。"))
        docs().forEach { (d, t) -> s.addPackDoc(w.id, d.kind, d.title, t, d.priority, d.always) }
        val without = s.chapterHints(w.id, two.id, "林月推开门。")
        assertFalse("故事账本" in without)
        s.applyLedger(w.id, ledger.map { LedgerOp.Add(it) }, "测试")
        val hints = s.chapterHints(w.id, two.id, "林月推开门。")
        val at = hints.indexOf("【故事账本：到第 2 章为止")
        assertTrue(at > hints.indexOf("【前情】"))
        assertTrue(at < hints.indexOf("【已经埋下、还没回收的伏笔"))
        assertTrue("林月·位置：在渡口等船" in hints)
        // 本章资料里也有。
        assertTrue("林月·位置：在渡口等船" in s.brief(w.id, two.id, "林月推开门。")!!.render()!!)
    }

    @Test
    fun emptyLedgerChangesNothing() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        val (_, two) = s.addChapter(w.id, w.volumes[0].id)!!
        docs().forEach { (d, t) -> s.addPackDoc(w.id, d.kind, d.title, t, d.priority, d.always) }
        val before = s.brief(w.id, two.id, "林月推开门。")!!
        val hintsBefore = s.chapterHints(w.id, two.id, "林月推开门。")
        assertNull(before.ledger)
        // 加了又删光：和没有账本一样。
        s.applyLedger(w.id, ledger.map { LedgerOp.Add(it) }, "加")
        s.applyLedger(w.id, s.ledger(w.id).map { LedgerOp.Remove(it.id) }, "删")
        assertEquals(before.render(), s.brief(w.id, two.id, "林月推开门。")!!.render())
        assertEquals(hintsBefore, s.chapterHints(w.id, two.id, "林月推开门。"))
    }

    @Test
    fun ledgerWinsOverTheAwarenessAndForeshadowTables() {
        val b = ChapterBriefs.build(docs(), "林月推开门。", "", chapter = 2, volume = 1, ledger = ledger)
        val text = b.render()!!
        // 察觉表里和账本一样的那条不再从察觉表列，账本里有一次。
        assertEquals(1, count(text, "杜娘说出玉佩来历"))
        assertFalse("杜娘说出玉佩来历" in b.items.first { it.group == "察觉" }.text)
        // 账本里没有的照旧。
        assertTrue("沈默腰间有半块玉佩" in b.items.first { it.group == "察觉" }.text)
        // 伏笔表里的半块玉佩以账本为准；别的照旧。
        assertEquals(listOf("老周的断指"), b.open.map { it.what })
        assertEquals(1, count(text, "半块玉佩（"))
        // 第二卷的那条还不该知道，账本里没有，照旧列。
        assertTrue(b.notYet.any { "沈默亮出另一半" in it })
        // 包含也算重复：账本写得长一点。
        val longer = listOf(know("林月", "杜娘说出玉佩来历，是旧案物证", 2))
        assertFalse(ChapterBriefs.build(docs(), "林月推开门。", "", 2, 1, ledger = longer).items.first { it.group == "察觉" }.text.contains("杜娘说出玉佩来历"))
    }

    @Test
    fun anotherCharactersKnowledgeDoesNotRemoveTheViewpointCharactersKnowledge() {
        val fact = "玉佩是旧案物证"
        val docs = awarenessDocs("| 林月 | $fact | 第1章 |")
        val b = ChapterBriefs.build(docs, "林月推开茶馆的门。", "", 3, 1, ledger = listOf(know("沈默", fact, 1)))
        assertTrue(b.items.any { it.group == "察觉" && fact in it.text })
        assertTrue(b.ledger?.known.orEmpty().none { it.subject == "沈默" })
        assertTrue(fact in b.render()!!)
    }

    @Test
    fun knowledgeUsesCanonicalPeopleAndTheirAliasesInBothDirections() {
        val fact = "玉佩是旧案物证"
        listOf("林月" to "小月", "小月" to "林月").forEach { (tableWho, ledgerWho) ->
            val b = ChapterBriefs.build(awarenessDocs("| $tableWho | $fact | 第1章 |"), "小月推开茶馆的门。", "", 3, 1, ledger = listOf(know(ledgerWho, fact, 1)))
            assertFalse(b.items.any { it.group == "察觉" && fact in it.text })
            assertEquals(1, count(b.render()!!, fact))
        }
    }

    @Test
    fun aSharedAwarenessRowIsKeptUntilEveryHolderIsRepresented() {
        val fact = "玉佩是旧案物证"
        val docs = awarenessDocs("| 林月、沈默 | $fact | 第1章 |")
        val one = ChapterBriefs.build(docs, "林月和沈默在门外等候。", "", 3, 1, ledger = listOf(know("小月", fact, 1)))
        assertTrue(one.items.any { it.group == "察觉" && fact in it.text })
        val both = ChapterBriefs.build(docs, "林月和沈默在门外等候。", "", 3, 1, ledger = listOf(know("小月", fact, 1), know("子静", fact, 1)))
        assertFalse(both.items.any { it.group == "察觉" && fact in it.text })
        assertEquals(setOf("小月", "子静"), both.ledger!!.known.map { it.subject }.toSet())
    }

    @Test
    fun expiredOrNotYetEffectiveKnowledgeCannotRemoveCurrentAwareness() {
        val fact = "玉佩是旧案物证"
        val docs = awarenessDocs("| 林月 | $fact | 第1章 |")
        val expired = know("林月", fact, 1).copy(until = 2)
        val future = know("小月", fact, 8)
        listOf(expired, future).forEach { entry ->
            val b = ChapterBriefs.build(docs, "林月走进茶馆。", "", 3, 1, ledger = listOf(entry))
            assertTrue(b.items.any { it.group == "察觉" && fact in it.text })
        }
    }

    @Test
    fun futureAwarenessIsReplacedOnlyByTheSameFutureTimeAndPerson() {
        val fact = "玉佩是旧案物证"
        val docs = awarenessDocs("| 林月 | $fact | 第8章 |")
        val matching = ChapterBriefs.build(docs, "小月走进茶馆。", "", 3, 1, ledger = listOf(know("林月", fact, 8)))
        assertTrue(matching.notYet.isEmpty())
        assertEquals(fact, matching.ledger!!.notYet.single().text)
        listOf(know("林月", fact, 9), know("沈默", fact, 8), know("林月", fact, 1)).forEach { entry ->
            val b = ChapterBriefs.build(docs, "小月走进茶馆。", "", 3, 1, ledger = listOf(entry))
            assertTrue(b.notYet.any { fact in it })
        }
    }

    @Test
    fun aSnapshotTruncatedBeforeTheKnowledgeSectionDoesNotEraseTheAwarenessRow() {
        val fact = "玉佩是旧案物证"
        val events = (1..40).map { LedgerEntry(kind = LedgerKind.EVENT, text = "第${it}处渡口改设了新的夜间岗哨，来往船只都要先行登记。", from = 1) }
        val b = ChapterBriefs.build(awarenessDocs("| 林月 | $fact | 第1章 |"), "小月走进茶馆。", "", 3, 1, limit = 1_500, ledger = events + know("林月", fact, 1))
        assertTrue(b.ledger!!.known.any { it.text == fact })
        assertFalse(fact in b.ledgerText!!)
        assertTrue(b.items.any { it.group == "察觉" && fact in it.text })
    }

    @Test
    fun confirmedContextUsesOnlyFinalSummariesAndNeverFallsBackToPlans() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val one = work.chapters.single()
        val (_, two) = store.addChapter(work.id, work.volumes.single().id, "第二章")!!
        val (_, three) = store.addChapter(work.id, work.volumes.single().id, "第三章")!!
        val (_, four) = store.addChapter(work.id, work.volumes.single().id, "第四章")!!
        val (_, current) = store.addChapter(work.id, work.volumes.single().id, "第五章")!!
        val (_, later) = store.addChapter(work.id, work.volumes.single().id, "第六章")!!
        listOf(one, two, three, four, later).forEach { store.saveText(work.id, it.id, "当夜，守门人举起了灯。", count = false) }
        store.saveCard(work.id, one.id, store.card(work.id, one.id).copy(summary = "已定前情：已到渡口。"))
        store.saveCard(work.id, two.id, store.card(work.id, two.id).copy(summary = "草稿前情：还在京城。"))
        store.saveCard(work.id, three.id, store.card(work.id, three.id).copy(summary = "修订前情：先去南门。"))
        store.saveCard(work.id, four.id, store.card(work.id, four.id).copy(facts = "只有细纲：将去北门。"))
        store.saveCard(work.id, later.id, store.card(work.id, later.id).copy(summary = "未来前情：已经离城。"))
        store.setStatus(work.id, one.id, ChapterStatus.FINAL)
        store.setStatus(work.id, three.id, ChapterStatus.REVISION)
        store.setStatus(work.id, four.id, ChapterStatus.FINAL)
        store.setStatus(work.id, later.id, ChapterStatus.FINAL)
        val ordinary = store.brief(work.id, current.id)!!.items.filter { it.group == "前情" }
        assertTrue(ordinary.any { "草稿前情" in it.text })
        assertTrue(ordinary.any { "修订前情" in it.text })
        assertTrue(ordinary.any { "只有细纲" in it.text })
        val confirmed = store.brief(work.id, current.id, confirmedOnly = true)!!.items.filter { it.group == "前情" }
        assertEquals(listOf("已定前情：已到渡口。"), confirmed.map { it.text })
    }

    @Test
    fun secretsOnlyGoToTheAuthorOnlyList() {
        val withSecrets = ledger + listOf(
            know("", "杜娘是对岸派来的", 1, secret = true),
            LedgerEntry(id = "z", kind = LedgerKind.EVENT, text = "第九章战死", subject = "老周", from = 1, secret = true),
            know("林月", "杜娘是在试探她", 2, secret = true),
        )
        val b = ChapterBriefs.build(docs(), "林月推开门，老周不在。", "", chapter = 2, volume = 1, ledger = withSecrets)
        assertTrue(b.ledger!!.secrets.isEmpty())
        assertFalse("对岸派来" in b.ledgerText!!)
        assertTrue(b.hidden.first().contains("杜娘是对岸派来的"))
        // 人物档案里“结局”那格、察觉表里“真相”那格和账本重复的，只留账本那条。
        assertEquals(1, b.hidden.count { "第九章战死" in it })
        assertEquals(1, b.hidden.count { "在试探她" in it })
        val text = b.render()!!
        val author = text.substringAfter("【只有作者知道的")
        assertTrue("杜娘是对岸派来的" in author && "老周——第九章战死" in author)
        assertEquals(1, count(text, "杜娘是对岸派来的"))
    }

    @Test
    fun theSnapshotStaysInsideTheBudget() {
        val many = (1..300).map { LedgerEntry(id = "e$it", kind = LedgerKind.EVENT, text = "渡口第 $it 件事：林月和沈默说了很久的话，杜娘在一旁记账，老周守着门。", from = 1) }
        val big = ChapterBriefs.build(docs(), "林月推开门。", "", 2, 1, limit = 60_000, ledger = many)
        assertEquals(ChapterBriefs.LEDGER_LIMIT, big.ledgerLimit)
        assertTrue(big.ledgerText!!.length <= ChapterBriefs.LEDGER_LIMIT + 30)
        val small = ChapterBriefs.build(docs(), "林月推开门。", "", 2, 1, limit = 3_000, ledger = many)
        assertTrue(small.ledgerLimit <= 1_000)
        assertTrue(small.ledgerText!!.length <= small.ledgerLimit + 30)
        assertTrue("放不下" in small.ledgerText!!)
        // 整份本章资料：加了账本，别的部分让出位置，总字数不超过上限太多。
        val plain = ChapterBriefs.build(docs(), "林月推开门。", "", 2, 1, limit = 3_000)
        val used = small.items.sumOf { it.text.length } + small.ledgerText!!.length
        assertTrue(used <= 3_000 + 100)
        assertTrue(small.render()!!.length <= plain.render()!!.length + small.ledgerLimit + 200)
    }

    @Test
    fun importPreviewFromTheAwarenessAndForeshadowTables() {
        val all = LedgerImport.entries(docs())
        val knows = all.filter { it.kind == LedgerKind.KNOW && !it.secret }
        val first = knows.single { it.text == "沈默腰间有半块玉佩" }
        assertEquals("林月", first.subject)
        assertNull(first.from)
        assertEquals(1, first.volume)
        assertEquals("自己看见", first.via)
        assertEquals("| 一 | 沈默腰间有半块玉佩 | 自己看见 | |", first.quote)
        assertEquals(LedgerOrigin.IMPORT, first.origin)
        val second = knows.single { it.text == "杜娘说出玉佩来历" }
        assertEquals(2, second.from)
        assertEquals(1, second.volume)
        assertEquals(2, knows.single { it.text == "沈默亮出另一半" }.volume)
        // “真相”那格另成一条只有作者知道的。
        val secret = all.single { it.secret }
        assertEquals("杜娘是在试探她", secret.text)
        assertEquals("林月", secret.subject)
        assertEquals(2, secret.from)
        // “谁”那一列拆成每人一条，别名换成本名。
        assertEquals(setOf("沈默", "杜娘"), knows.filter { it.text == "老周夜里出过城" }.map { it.subject }.toSet())
        assertTrue(knows.filter { it.text == "老周夜里出过城" }.all { it.from == 3 })
        // 伏笔：埋、计划收、收了没有、哪章收的。
        val promises = all.filter { it.kind == LedgerKind.PROMISE }.associateBy { it.text }
        assertEquals(4, promises.size)
        assertEquals(1, promises.getValue("半块玉佩").from)
        assertEquals(3, promises.getValue("半块玉佩").payoff)
        assertFalse(promises.getValue("半块玉佩").done)
        assertTrue(promises.getValue("门闩刀痕").done)
        assertNull(promises.getValue("门闩刀痕").closed)
        assertEquals(3, promises.getValue("渡口的旧船").closed)
        assertEquals("| 老周的断指 | 第1章 | 第9章 | |", promises.getValue("老周的断指").quote)

        // 账本里已经有的、同一批重复的去掉。
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        docs().forEach { (d, t) -> s.addPackDoc(w.id, d.kind, d.title, t, d.priority, d.always) }
        s.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔补记", "- 半块玉佩：第1章埋，第3章回收\n- 杜娘的账本：第2章埋，第12章回收")
        s.applyLedger(w.id, listOf(LedgerOp.Add(know("林月", "沈默腰间有半块玉佩，只觉得眼熟", null, volume = 1))), "手动")
        val preview = s.ledgerImportPreview(w.id)
        assertFalse(preview.any { it.text == "沈默腰间有半块玉佩" })
        assertEquals(1, preview.count { it.text == "半块玉佩" })
        assertTrue(preview.any { it.text == "杜娘的账本" && it.from == 2 && it.payoff == 12 })
        assertEquals(all.size - 1 + 1, preview.size)
        assertEquals(preview.size, s.importLedger(w.id, preview))
        assertTrue(s.ledgerImportPreview(w.id).isEmpty())
    }

    @Test
    fun exportThenImportGivesTheSameEntries() {
        val entries = listOf(
            know("林月", "杜娘说出玉佩来历", 2, via = "茶馆"),
            know("沈默", "老周夜里出过城", 12, volume = 2),
            know("杜娘", "林月认得玉佩", null, volume = 3),
            know("林月", "杜娘是在试探她", 2, secret = true),
            know("", "渡口有暗哨", 5),
            promise("半块玉佩", 1, payoff = 3),
            promise("门闩刀痕", 1, payoff = 2, done = true),
            promise("渡口的旧船", 2, payoff = 4, done = true, closed = 5),
            promise("顾长风说三日后再来", 8, payoff = 11),
            state("林月", "位置", "在渡口等船", 1),
            LedgerEntry(id = "t", kind = LedgerKind.TIME, text = "立冬前一天傍晚", from = 2),
            LedgerEntry(id = "x", kind = LedgerKind.EVENT, text = "渡口的船停了一班|改成一天一班", from = 1, until = 6),
        )
        val md = LedgerExport.markdown(entries)
        assertTrue("| 人物 | 知道什么 | 从第几章起 | 消息从哪来 |" in md)
        assertTrue("| 伏笔 | 第几章埋 | 计划第几章收 | 状态 |" in md)
        assertTrue("渡口的船停了一班／改成一天一班" in md)
        assertTrue("| 林月 | 位置 | 在渡口等船 | 第1章 |" in md)
        // 知情那一节放进察觉表、伏笔那一节放进伏笔表，再导入。
        val back = LedgerImport.entries(
            listOf(
                PackDoc("c", PackKind.CHARACTER, "人物档案") to characters,
                PackDoc("a", PackKind.AWARENESS, "察觉表") to PackOutline.read(md, "知情")!!,
                PackDoc("f", PackKind.FORESHADOW, "伏笔表") to PackOutline.read(md, "伏笔")!!,
            ),
        )
        fun sig(e: LedgerEntry) = listOf(e.kind, e.text, e.subject, e.from, e.volume, e.via, e.secret, e.payoff, e.done, e.closed)
        val expected = entries.filter { it.kind == LedgerKind.KNOW || it.kind == LedgerKind.PROMISE }
        assertEquals(expected.map(::sig).toSet(), back.map(::sig).toSet())
        assertEquals(expected.size, back.size)
        // 读回来的都和原来的重复，导入预览里一条也没有。
        assertTrue(LedgerImport.fresh(back, entries).isEmpty())
    }

    @Test
    fun exportToThePackReplacesTheOldCopy() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.applyLedger(w.id, ledger.map { LedgerOp.Add(it) }, "加")
        val doc = s.exportLedgerToPack(w.id)
        assertEquals(LEDGER_EXPORT_TITLE, doc.title)
        assertTrue("在渡口等船" in s.packText(w.id, doc.id))
        s.applyLedger(w.id, listOf(LedgerOp.Add(state("沈默", "位置", "在京城", 1))), "加")
        s.exportLedgerToPack(w.id)
        assertEquals(1, s.pack(w.id).count { it.title == LEDGER_EXPORT_TITLE })
        assertTrue("在京城" in s.packText(w.id, doc.id))
        assertEquals("# $LEDGER_EXPORT_TITLE\n\n故事账本还是空的。\n", LedgerExport.markdown(emptyList()))
    }
}
