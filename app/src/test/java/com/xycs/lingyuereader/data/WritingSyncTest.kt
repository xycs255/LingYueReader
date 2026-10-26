package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ChapterSyncResult
import com.xycs.lingyuereader.SyncSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整理进设定包：定位置、写进去（表格追加一行、## 小节）、存历史版本、变更登记、哪些章整理过；随章变化的记进故事账本（一章一批、只记一次）。 */
class WritingSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = WorkStore(tmp.newFolder())

    private fun doc(kind: PackKind, title: String, text: String, id: String = title) = PackDoc(id, kind, title) to text

    private fun person(name: String, vararg fields: Pair<String, String>) =
        SyncItem(SyncGroup.NEW, "新人物：$name", "原句", PackKind.CHARACTER, name = name, fields = fields.toList(), chapterId = "c", chapter = 12)

    private fun knows(who: String, what: String, source: String = "") =
        SyncItem(SyncGroup.AWARENESS, "$who：$what", "原句", PackKind.AWARENESS, name = who, fields = listOf("人物" to who, "知道什么" to what, "从第几章起" to "第12章", "消息从哪来" to source), chapterId = "c", chapter = 12)

    @Test
    fun newPersonBecomesARowWhenTheCharacterFileIsATable() {
        val table = "# 人物\n\n| 人物 | 身份 | 关系 | 备注 |\n| --- | --- | --- | --- |\n| 林月 | 药铺掌柜的女儿 | 沈默的旧识 | |\n\n## 其他\n以后补。"
        val docs = listOf(doc(PackKind.CHARACTER, "人物档案", table))
        val item = PackSync.plan(listOf(person("杜娘", "称呼" to "杜掌柜", "身份" to "渡口茶馆老板娘", "关系" to "和老周相熟", "本章" to "告诉林月老周死了")), docs).single()
        assertEquals(SyncPlace.ROW, item.place)
        assertEquals("人物档案", item.docId)
        // 本章做的事放进“备注”；没有“称呼”列，称呼并进最后一格。
        assertEquals("| 杜娘 | 渡口茶馆老板娘 | 和老周相熟 | 第12章：告诉林月老周死了；称呼：杜掌柜 |", item.text)
        val out = PackSync.apply(table, item)
        assertEquals(
            "# 人物\n\n| 人物 | 身份 | 关系 | 备注 |\n| --- | --- | --- | --- |\n| 林月 | 药铺掌柜的女儿 | 沈默的旧识 | |\n" + item.text + "\n\n## 其他\n以后补。",
            out,
        )
        // 写进去以后照样读得出这个人。
        assertTrue(ChapterBriefs.characters(listOf("人物档案" to out)).any { it.name == "杜娘" })
    }

    @Test
    fun newPersonBecomesASectionWhenTheFileUsesHeadings() {
        val text = "# 人物\n\n### 林月\n想要：查清旧案\n\n### 沈默\n想要：活下去\n"
        val item = PackSync.plan(listOf(person("顾长风", "身份" to "镖师", "本章" to "在渡口喊林月“月丫头”")), listOf(doc(PackKind.CHARACTER, "人物档案", text))).single()
        // 照文档里人物标题的层级（三级）新起一节，加在末尾。
        assertEquals("### 顾长风\n- 身份：镖师\n- 第12章：在渡口喊林月“月丫头”", item.text)
        assertEquals(SyncPlace.END, item.place)
        assertEquals(text.trimEnd() + "\n\n" + item.text, PackSync.apply(text, item))
    }

    @Test
    fun knownPeopleOnlyGetNewFactsAppendedToTheirOwnSection() {
        val text = "## 林月\n想要：查清旧案\n\n## 沈默\n想要：活下去"
        val docs = listOf(doc(PackKind.CHARACTER, "人物档案", text))
        val item = PackSync.plan(listOf(person("林月", "关系" to "知道了沈默是卧底", "本章" to "在渡口茶馆听说老周死了")), docs).single()
        assertEquals("补充人物：林月", item.title)
        assertEquals(SyncPlace.SECTION, item.place)
        assertEquals("林月", item.anchor)
        // 追加在“## 林月”这一节的末尾（“## 沈默”前面），不另起同名的一节。
        assertEquals(
            "## 林月\n想要：查清旧案\n- 关系：知道了沈默是卧底\n- 第12章：在渡口茶馆听说老周死了\n\n## 沈默\n想要：活下去",
            PackSync.apply(text, item),
        )
        // 只在表格里的人物：不重复提。
        val table = listOf(doc(PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |"))
        assertTrue(PackSync.plan(listOf(person("林月", "本章" to "听说老周死了")), table).isEmpty())
    }

    @Test
    fun placesGoUnderTheirSectionAndKnownOnesAreDropped() {
        val canon = "# 硬性设定\n\n## 地名\n- 青石镇：林月的家乡\n\n## 规矩\n- 宵禁：亥时以后不许出门"
        val docs = listOf(doc(PackKind.CANON, "硬性设定", canon), doc(PackKind.OUTLINE, "总纲", "第三卷林月去京城。"))
        val place = SyncItem(SyncGroup.NEW, "新地名：渡口", "原句", PackKind.CANON, section = "地名", name = "渡口", text = "城东的渡口，常起雾", chapterId = "c", chapter = 12)
        val known = place.copy(title = "新地名：京城", name = "京城", text = "都城")
        val planned = PackSync.plan(listOf(place, known), docs)
        assertEquals(listOf("新地名：渡口"), planned.map { it.title })
        val out = PackSync.apply(canon, planned.single())
        assertEquals("# 硬性设定\n\n## 地名\n- 青石镇：林月的家乡\n- 渡口：城东的渡口，常起雾\n\n## 规矩\n- 宵禁：亥时以后不许出门", out)
        // 没有这一节：在末尾新起一节；同一份文档里第二条再写进这一节，不重复起节。
        val obj = place.copy(title = "新物件：铜哨", section = "物件", name = "铜哨", text = "沈默随身带的铜哨")
        val obj2 = place.copy(title = "新物件：茶碗", section = "物件", name = "茶碗", text = "杜娘的粗瓷茶碗")
        val (a, b) = PackSync.plan(listOf(obj, obj2), docs)
        assertEquals(canon + "\n\n## 物件\n- 铜哨：沈默随身带的铜哨\n- 茶碗：杜娘的粗瓷茶碗", PackSync.apply(PackSync.apply(canon, a), b))
    }

    @Test
    fun awarenessGoesIntoTheLedgerNotTheAwarenessTable() {
        val table = "| 谁 | 从第几章起 | 知道什么 | 消息从哪来 |\n| --- | --- | --- | --- |\n| 沈默 | 第3章 | 林月是旧案遗孤 | 老周 |\n"
        val docs = listOf(doc(PackKind.AWARENESS, "察觉表", table))
        val item = PackSync.plan(listOf(knows("林月", "沈默是朝廷的卧底", "杜娘说漏了嘴")), docs).single()
        // 以前写成察觉表的一行；现在记进账本，变成一条知情。
        assertEquals(SyncPlace.LEDGER, item.place)
        assertEquals("记进故事账本", item.where)
        assertTrue(item.defaultOn)
        val entry = item.entry!!
        assertEquals(LedgerKind.KNOW, entry.kind)
        assertEquals("林月", entry.subject)
        assertEquals("沈默是朝廷的卧底", entry.text)
        assertEquals(12, entry.from)
        assertEquals("杜娘说漏了嘴", entry.via)
        // 不改察觉表。
        assertEquals(table, PackSync.apply(table, item))
        // 察觉表里已经有、而且不晚于这一章：不再提。
        assertTrue(PackSync.plan(listOf(knows("沈默", "知道林月是旧案遗孤")), docs).isEmpty())
        // 账本里已经有：也不再提。
        val ledger = listOf(LedgerEntry(kind = LedgerKind.KNOW, text = "沈默是朝廷的卧底", subject = "林月", from = 10))
        assertTrue(PackSync.plan(listOf(knows("林月", "沈默是朝廷派来的卧底")), docs, ledger).isEmpty())
    }

    @Test
    fun awarenessTheLedgerSaysComesLaterIsAConflictThatMovesTheEntry() {
        val s = store()
        val w = s.create("长夜")
        val c = w.chapters[0].id
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(id = "k1", kind = LedgerKind.KNOW, text = "沈默是卧底", subject = "林月", from = 30, via = "顾长风"))), "手动")
        val item = PackSync.plan(listOf(knows("林月", "沈默是朝廷的卧底").copy(chapterId = c)), emptyList(), s.ledger(w.id)).single()
        assertEquals(SyncGroup.CONFLICT, item.group)
        assertEquals(SyncPlace.LEDGER, item.place)
        assertFalse(item.defaultOn)
        assertEquals("账本里写林月 第30章才知道，正文第12章就知道了", item.title)
        assertEquals("沈默是卧底（第 30 章起）", item.packSays)
        // 勾上：账本那一条改成从这一章起，不另加一条；再写一次没有变化。
        assertEquals(mapOf(PackSync.LEDGER to 1), s.applySync(w.id, listOf(item)))
        val k = s.ledger(w.id).single()
        assertEquals("k1", k.id)
        assertEquals(12, k.from)
        assertEquals("顾长风", k.via)
        assertEquals(emptyMap<String, Int>(), s.applySync(w.id, listOf(item)))
    }

    @Test
    fun ledgerItemsArePlannedAndWhatTheLedgerHasIsDropped() {
        val ledger = listOf(
            LedgerEntry(kind = LedgerKind.STATE, text = "在渡口", subject = "林月", aspect = "位置", from = 10),
            LedgerEntry(kind = LedgerKind.EVENT, text = "老周死在渡口", from = 9),
            LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 8),
            LedgerEntry(id = "p2", kind = LedgerKind.PROMISE, text = "沈默欠杜娘一坛酒", from = 3, done = true, closed = 7),
        )
        fun entry(kind: LedgerKind, text: String, subject: String = "", aspect: String = "") =
            LedgerEntry(kind = kind, text = text, subject = subject, aspect = aspect, from = 12, origin = LedgerOrigin.SYNC)
        fun ledgerItem(group: SyncGroup, text: String, e: LedgerEntry? = null, closes: LedgerEntry? = null) =
            SyncItem(group, text, "原句", PackKind.CANON, name = e?.subject.orEmpty(), text = text, chapterId = "c", chapter = 12, entry = e, closes = closes)
        val items = listOf(
            // 林月还在渡口：和最新的状态一样，不提；到了京城：提。
            ledgerItem(SyncGroup.STATE, "在渡口", entry(LedgerKind.STATE, "在渡口", "林月", "位置")),
            ledgerItem(SyncGroup.STATE, "到了京城", entry(LedgerKind.STATE, "到了京城", "林月", "位置")),
            ledgerItem(SyncGroup.EVENT, "老周死在了渡口", entry(LedgerKind.EVENT, "老周死在了渡口")),
            ledgerItem(SyncGroup.EVENT, "渡口的船停了", entry(LedgerKind.EVENT, "渡口的船停了")),
            ledgerItem(SyncGroup.PROMISE, "顾长风说三日后再来", entry(LedgerKind.PROMISE, "顾长风说三日后再来")),
            ledgerItem(SyncGroup.PROMISE, "收起：顾长风说三日后再来", closes = ledger[2]),
            // 已经收了的：不再收。
            ledgerItem(SyncGroup.PROMISE, "收起：沈默欠杜娘一坛酒", closes = ledger[3]),
            ledgerItem(SyncGroup.TIME, "入冬第三天", entry(LedgerKind.TIME, "入冬第三天")),
        )
        val planned = PackSync.plan(items, emptyList(), ledger)
        assertEquals(listOf("到了京城", "渡口的船停了", "收起：顾长风说三日后再来", "入冬第三天"), planned.map { it.title })
        assertTrue(planned.all { it.place == SyncPlace.LEDGER && it.writable && it.defaultOn && it.where == "记进故事账本" })
        // 生成的改动：加一条、收一条。
        assertEquals(LedgerOp.Add(planned[0].entry!!), PackSync.ledgerOp(planned[0], ledger))
        assertEquals(LedgerOp.Close("p1", 12, "原句", "c"), PackSync.ledgerOp(planned[2], ledger))
    }

    @Test
    fun ledgerItemsAreWrittenAsOneBatchPerChapterAndOnlyOnce() {
        val s = store()
        val w = s.create("长夜")
        val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章")!!
        val one = w.chapters[0].id
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 1))), "手动")
        fun item(group: SyncGroup, kind: LedgerKind, text: String, chapterId: String, n: Int, subject: String = "", aspect: String = "") =
            SyncItem(group, text, "原句", PackKind.CANON, name = subject, text = text, chapterId = chapterId, chapter = n, place = SyncPlace.LEDGER,
                entry = LedgerEntry(kind = kind, text = text, subject = subject, aspect = aspect, from = n, quote = "原句", chapterId = chapterId, origin = LedgerOrigin.SYNC))
        val close = SyncItem(SyncGroup.PROMISE, "收起：顾长风说三日后再来", "顾长风推门进来", PackKind.FORESHADOW, text = "顾长风说三日后再来", chapterId = two.id, chapter = 2, place = SyncPlace.LEDGER, closes = s.ledger(w.id).single())
        val items = listOf(
            item(SyncGroup.STATE, LedgerKind.STATE, "左臂受了伤", one, 1, "林月", "身体"),
            item(SyncGroup.EVENT, LedgerKind.EVENT, "老周死了", one, 1),
            item(SyncGroup.TIME, LedgerKind.TIME, "入冬第一天", one, 1),
            item(SyncGroup.EVENT, LedgerKind.EVENT, "老周死了。", two.id, 2),
            item(SyncGroup.STATE, LedgerKind.STATE, "伤好了", two.id, 2, "林月", "身体"),
            close,
        )
        val versions = s.ledgerVersions(w.id).size
        // 第二章重复的“老周死了”不再记；第一章三条、第二章两条。
        assertEquals(mapOf(PackSync.LEDGER to 5), s.applySync(w.id, items))
        // 一章一批：账本的历史版本多了两个，按章号先后。
        assertEquals(listOf("整理第 2 章", "整理第 1 章"), s.ledgerVersions(w.id).take(2).map { it.label })
        assertEquals(versions + 2, s.ledgerVersions(w.id).size)
        val ledger = s.ledger(w.id)
        assertEquals(1, ledger.count { it.kind == LedgerKind.EVENT })
        assertTrue(ledger.single { it.id == "p1" }.let { it.done && it.closed == 2 && it.quote == "顾长风推门进来" && it.chapterId == two.id })
        assertTrue(ledger.filter { it.kind != LedgerKind.PROMISE }.all { it.origin == LedgerOrigin.SYNC && it.quote == "原句" })
        // 到第二章为止，林月身体这一方面是最新的“伤好了”。
        assertEquals("伤好了", StoryLedger.snapshot(ledger, 2).states.single().text)
        // 重试：账本不变，也不再存历史版本；变更登记不记账本的。
        assertEquals(emptyMap<String, Int>(), s.applySync(w.id, items))
        assertEquals(ledger, s.ledger(w.id))
        assertEquals(versions + 2, s.ledgerVersions(w.id).size)
        assertTrue(s.changes(w.id).isEmpty())
    }

    @Test
    fun editedTextAspectAndPayoffTakeEffect() {
        val s = store()
        val w = s.create("长夜")
        val c = w.chapters[0].id
        val state = SyncItem(SyncGroup.STATE, "林月·位置：在渡口", "原句", PackKind.CHARACTER, name = "林月", text = "在渡口", chapterId = c, chapter = 12, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.STATE, text = "在渡口", subject = "林月", aspect = "位置", from = 12, origin = LedgerOrigin.SYNC))
        val promise = SyncItem(SyncGroup.PROMISE, "埋下：顾长风说三日后再来", "原句", PackKind.FORESHADOW, text = "顾长风说三日后再来", chapterId = c, chapter = 12, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 12, origin = LedgerOrigin.SYNC))
        val session = SyncSession("w").with(ChapterSyncResult(c, "第十二章", 12, 0, listOf(state, promise))).copy(
            edits = mapOf(state.key to "在渡口的茶馆里"),
            aspects = mapOf(state.key to "情绪"),
            payoffs = mapOf(promise.key to 15),
        )
        assertEquals(setOf(state.key, promise.key), session.picked)
        assertEquals(mapOf(PackSync.LEDGER to 2), s.applySync(w.id, session.chosen))
        val ledger = s.ledger(w.id)
        val st = ledger.single { it.kind == LedgerKind.STATE }
        assertEquals("在渡口的茶馆里", st.text)
        assertEquals("情绪", st.aspect)
        assertEquals(15, ledger.single { it.kind == LedgerKind.PROMISE }.payoff)
        // 计划第几章收改成空着。
        assertNull(session.copy(payoffs = mapOf(promise.key to null)).chosen.last().entry!!.payoff)
    }

    @Test
    fun ledgerItemsRepeatedByLaterChaptersAreNotPickedByDefault() {
        fun event(id: String, n: Int, text: String) = SyncItem(SyncGroup.EVENT, text, "原句", PackKind.CANON, text = text, chapterId = id, chapter = n, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.EVENT, text = text, from = n))
        fun time(id: String, n: Int) = SyncItem(SyncGroup.TIME, "时间：当夜", "原句", PackKind.CANON, text = "当夜", chapterId = id, chapter = n, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.TIME, text = "当夜", from = n))
        val p = LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 1)
        fun close(id: String, n: Int) = SyncItem(SyncGroup.PROMISE, "收起：${p.text}", "原句$n", PackKind.FORESHADOW, text = p.text, chapterId = id, chapter = n, place = SyncPlace.LEDGER, closes = p)
        val s = SyncSession("w")
            .with(ChapterSyncResult("c4", "第4章", 4, 0, listOf(event("c4", 4, "老周死在渡口"), time("c4", 4), close("c4", 4))))
            .with(ChapterSyncResult("c5", "第5章", 5, 0, listOf(event("c5", 5, "老周死在了渡口"), time("c5", 5), close("c5", 5))))
        val later = s.results.last().items
        // 同一件事、收同一条伏笔：后面那章的不勾；时间每章各记各的。
        assertEquals(setOf(later[0].key, later[2].key), s.repeats)
        assertTrue(later[1].key in s.picked)
        assertEquals(4, s.picked.size)
    }

    @Test
    fun batchKeepsAStateThatReturnsToAnEarlierValueAndWritesTheRightSnapshot() {
        val store = store()
        val work = store.create("长夜")
        val one = work.chapters.single()
        val (_, two) = store.addChapter(work.id, work.volumes.single().id, "第二章")!!
        val (_, three) = store.addChapter(work.id, work.volumes.single().id, "第三章")!!
        val (_, four) = store.addChapter(work.id, work.volumes.single().id, "第四章")!!
        fun state(c: WorkChapter, number: Int, where: String): SyncItem {
            val quote = "林月$where。"
            val item = SyncItem(
                SyncGroup.STATE, "林月·位置：$where", quote, PackKind.CHARACTER, name = "林月", text = where,
                chapterId = c.id, chapter = number, place = SyncPlace.LEDGER,
                entry = LedgerEntry(kind = LedgerKind.STATE, text = where, subject = "林月", aspect = "位置", from = number),
            )
            return PackSync.plan(listOf(item), emptyList(), store.ledger(work.id)).single()
        }
        val a = state(one, 1, "在渡口")
        val b = state(two, 2, "在京城")
        val back = state(three, 3, "在渡口")
        val unchanged = state(four, 4, "在渡口")
        val session = listOf(a, b, back, unchanged).fold(SyncSession(work.id)) { current, item ->
            current.with(ChapterSyncResult(item.chapterId, "第${item.chapter}章", item.chapter, 0, listOf(item)))
        }
        assertEquals(setOf(unchanged.key), session.repeats)
        assertEquals(listOf(a.key, b.key, back.key), session.chosen.map { it.key })
        assertEquals(mapOf(PackSync.LEDGER to 3), store.applySync(work.id, session.chosen))
        val ledger = store.ledger(work.id)
        assertEquals("在京城", StoryLedger.snapshot(ledger, 2).states.single().text)
        assertEquals("在渡口", StoryLedger.snapshot(ledger, 3).states.single().text)
        assertEquals("在渡口", StoryLedger.snapshot(ledger, 4).states.single().text)
        assertEquals(listOf(1, 2, 3), ledger.map { it.from })
        assertEquals(emptyMap<String, Int>(), store.applySync(work.id, session.chosen))
    }

    @Test
    fun insertingTheMiddleChapterRestoresTheLaterStateToTheDefaultSelection() {
        fun state(id: String, chapter: Int, text: String) = SyncItem(
            SyncGroup.STATE, "林月·位置：$text", "原句$id", PackKind.CHARACTER, name = "林月", text = text,
            chapterId = id, chapter = chapter, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.STATE, text = text, subject = "林月", aspect = "位置", from = chapter),
        )
        fun result(item: SyncItem) = ChapterSyncResult(item.chapterId, "第${item.chapter}章", item.chapter, 0, listOf(item))
        val one = state("c1", 1, "在渡口")
        val two = state("c2", 2, "在京城")
        val three = state("c3", 3, "在渡口")
        val before = SyncSession("w").with(result(one)).with(result(three))
        assertEquals(setOf(three.key), before.repeats)
        assertFalse(three.key in before.picked)
        val after = before.with(result(two))
        assertTrue(after.repeats.isEmpty())
        assertEquals(setOf(one.key, two.key, three.key), after.picked)
    }

    @Test
    fun anExpiredStateAndAnUnrelatedAspectDoNotHideANewState() {
        fun state(id: String, chapter: Int, aspect: String, until: Int? = null) = SyncItem(
            SyncGroup.STATE, "林月·$aspect：无伤", "原句$id", PackKind.CHARACTER, text = "无伤",
            chapterId = id, chapter = chapter, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.STATE, text = "无伤", subject = "林月", aspect = aspect, from = chapter, until = until),
        )
        val expired = state("c1", 1, "身体", until = 2)
        val unrelated = state("c2", 2, "情绪")
        val current = state("c3", 3, "身体")
        assertFalse(PackSync.repeatedBefore(current, listOf(expired, unrelated)))
        assertTrue(PackSync.repeatedBefore(current, listOf(expired.copy(entry = expired.entry!!.copy(until = null)))))
    }

    @Test
    fun stateDeduplicationNeverDropsANegationOrANewLocationDetail() {
        fun state(id: String, number: Int, text: String) = SyncItem(
            SyncGroup.STATE, "林月·身体：$text", "原句$id", PackKind.CHARACTER, name = "林月", text = text,
            chapterId = id, chapter = number, place = SyncPlace.LEDGER,
            entry = LedgerEntry(kind = LedgerKind.STATE, text = text, subject = "林月", aspect = "身体", from = number),
        )
        val hurt = state("c1", 1, "受伤")
        val unhurt = state("c2", 2, "未受伤")
        assertFalse(PackSync.sameThing(hurt, unhurt))
        assertFalse(PackSync.repeatedBefore(unhurt, listOf(hurt)))
        assertFalse(PackSync.inLedger(unhurt, listOf(hurt.entry!!)))
        val here = state("c1", 1, "在渡口").let { it.copy(entry = it.entry!!.copy(aspect = "位置")) }
        val detail = state("c2", 2, "在渡口的茶馆").let { it.copy(entry = it.entry!!.copy(aspect = "位置")) }
        assertFalse(PackSync.inLedger(detail, listOf(here.entry!!)))
        val punctuationOnly = here.copy(chapterId = "c2", chapter = 2, text = " 在渡口。 ", entry = here.entry!!.copy(id = "punctuation", from = 2))
        assertTrue(PackSync.inLedger(punctuationOnly, listOf(here.entry!!)))
    }

    @Test
    fun theLedgerViewNumbersOpenPromisesAndStopsAtTheLastChapter() {
        val entries = listOf(
            LedgerEntry(id = "a", kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", from = 8, payoff = 11),
            LedgerEntry(id = "b", kind = LedgerKind.PROMISE, text = "杜娘藏着一封信", from = 9),
            // 收了的、这一章才埋的、只有作者知道的：都不给。
            LedgerEntry(id = "c", kind = LedgerKind.PROMISE, text = "沈默欠杜娘一坛酒", from = 3, done = true, closed = 7),
            LedgerEntry(id = "d", kind = LedgerKind.PROMISE, text = "渡口下面埋着东西", from = 12),
            LedgerEntry(id = "e", kind = LedgerKind.PROMISE, text = "老周没死", from = 2, secret = true),
            LedgerEntry(kind = LedgerKind.STATE, text = "左臂受了伤", subject = "林月", aspect = "身体", from = 10),
            LedgerEntry(kind = LedgerKind.STATE, text = "在京城", subject = "顾长风", aspect = "位置", from = 10),
            LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "林月", from = 11, via = "偷听"),
            LedgerEntry(kind = LedgerKind.KNOW, text = "沈默的身世", subject = "林月", from = 20),
        )
        val view = ChapterSyncs.ledgerView(entries, 12, present = listOf("林月"))
        assertEquals(mapOf("P1" to entries[0], "P2" to entries[1]), view.codes)
        assertTrue("【故事账本：到上一章（第 11 章）为止】" in view.text)
        assertTrue("- P1：顾长风说三日后再来（第 8 章埋，计划第 11 章收）" in view.text)
        assertTrue("- P2：杜娘藏着一封信" in view.text)
        assertTrue("林月·身体：左臂受了伤" in view.text && "林月：杜娘是卧底（第 11 章起），消息来自偷听" in view.text)
        listOf("一坛酒", "埋着东西", "老周没死", "在京城", "身世").forEach { assertFalse(it, it in view.text) }
        // 太长：放不下的省略、注明，没列出的伏笔没有编号。
        val many = (1..200).map { LedgerEntry(id = "p$it", kind = LedgerKind.PROMISE, text = "渡口的第 $it 条伏笔，写得很长很长很长很长很长", from = 1) }
        val cut = ChapterSyncs.ledgerView(many, 12)
        assertTrue(cut.text.length <= ChapterSyncs.LEDGER_CHARS)
        assertTrue("没列出" in cut.text)
        assertTrue(cut.codes.size in 10 until 200)
        assertEquals("p${cut.codes.size}", cut.codes["P${cut.codes.size}"]!!.id)
        // 账本里没有可给的：空的。
        assertEquals(SyncLedger("", emptyMap()), ChapterSyncs.ledgerView(emptyList(), 12))
    }

    @Test
    fun awarenessThatTheTableSaysComesLaterIsAConflict() {
        val table = "| 人物 | 知道什么 | 从第几章起 | 消息从哪来 |\n| --- | --- | --- | --- |\n| 林月 | 沈默是卧底 | 第30章 | 顾长风 |"
        val item = PackSync.plan(listOf(knows("林月", "沈默是朝廷的卧底")), listOf(doc(PackKind.AWARENESS, "察觉表", table))).single()
        assertEquals(SyncGroup.CONFLICT, item.group)
        assertFalse(item.defaultOn)
        assertEquals("| 林月 | 沈默是卧底 | 第30章 | 顾长风 |", item.packSays)
        assertEquals(SyncPlace.REPLACE, item.place)
        // 选了改设定包：那一行的章号改成这一章。
        assertEquals("| 人物 | 知道什么 | 从第几章起 | 消息从哪来 |\n| --- | --- | --- | --- |\n| 林月 | 沈默是卧底 | 第12章 | 顾长风 |", PackSync.apply(table, item))
    }

    @Test
    fun conflictsReplaceThePackWordingOrOnlyPoint() {
        val voice = "## 顾长风\n顾长风叫林月“林姑娘”，从不叫小名。"
        val docs = listOf(doc(PackKind.VOICE, "称谓与语气", voice))
        val fix = SyncItem(
            SyncGroup.CONFLICT, "顾长风怎么叫林月", "原句", PackKind.VOICE, packSays = "顾长风叫林月“林姑娘”，从不叫小名。",
            text = "顾长风私下叫林月“月丫头”。", chapterId = "c", chapter = 12,
        )
        val wrong = fix.copy(title = "老周的生死", fixText = true, text = "")
        val (a, b) = PackSync.plan(listOf(fix, wrong), docs)
        assertEquals(SyncPlace.REPLACE, a.place)
        assertEquals("## 顾长风\n顾长风私下叫林月“月丫头”。", PackSync.apply(voice, a))
        // 正文写错了：只提示，不写。
        assertFalse(b.writable)
        assertEquals(voice, PackSync.apply(voice, b))
        // 设定包里找不到那句原话：补在末尾，注明哪一章改的。
        val loose = PackSync.plan(listOf(fix.copy(packSays = "设定包里没有这句")), docs).single()
        assertEquals(SyncPlace.END, loose.place)
        assertEquals("- 顾长风私下叫林月“月丫头”。（整理第12章时改）", loose.text)
    }

    /** 察觉以前新建察觉表、写一行、记一笔变更登记；现在记进账本，不建察觉表、不记变更登记（账本有自己的历史版本）。 */
    @Test
    fun applyingSnapshotsTheDocAndPutsAwarenessIntoTheLedger() {
        val s = store()
        val w = s.create("长夜")
        val c = w.chapters[0]
        s.saveText(w.id, c.id, "林月这才知道沈默是朝廷的卧底。", count = false)
        val people = s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |")
        val before = s.pack(w.id).first { it.id == people.id }.updatedAt
        val docs = s.pack(w.id).map { it to s.packText(w.id, it.id) }
        val items = PackSync.plan(
            listOf(person("杜娘", "身份" to "渡口茶馆老板娘"), knows("林月", "沈默是朝廷的卧底", "杜娘说漏了嘴")).map { it.copy(chapterId = c.id) },
            docs,
        )
        assertEquals("记进故事账本", items[1].where)
        Thread.sleep(2)
        val counts = s.applySync(w.id, items)
        assertEquals(mapOf("人物档案" to 1, PackSync.LEDGER to 1), counts)
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 杜娘 | 渡口茶馆老板娘 |", s.packText(w.id, people.id))
        // 写之前存了原来的一版；文档的修改时间更新了。
        val history = s.history(w.id, s.docKey(people.id))
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |", s.snapshotText(w.id, s.docKey(people.id), history.first().at))
        assertTrue(s.pack(w.id).first { it.id == people.id }.updatedAt > before)
        assertTrue(s.pack(w.id).none { it.kind == PackKind.AWARENESS })
        val know = s.ledger(w.id).single()
        assertEquals(LedgerKind.KNOW, know.kind)
        assertEquals("林月", know.subject)
        assertEquals("沈默是朝廷的卧底", know.text)
        assertEquals(12, know.from)
        assertEquals("杜娘说漏了嘴", know.via)
        assertEquals(c.id, know.chapterId)
        assertEquals(LedgerOrigin.SYNC, know.origin)
        // 写进设定包的每条记一笔变更登记，已经同步过，不算“待同步设定”。
        val changes = s.changes(w.id)
        assertEquals(1, changes.size)
        assertTrue(changes.all { it.reason.startsWith("整理第12章：") && it.chapterId == c.id && it.kind == ChangeKind.CANON && !it.pending })
        assertTrue(changes.any { it.reason == "整理第12章：新人物：杜娘（《人物档案》）" })
    }

    @Test
    fun editedTextIsWrittenAsIsAndNoteOnlyItemsAreSkipped() {
        val s = store()
        val w = s.create("长夜")
        val canon = s.addPackDoc(w.id, PackKind.CANON, "硬性设定", "## 地名\n- 青石镇：林月的家乡")
        val docs = s.pack(w.id).map { it to s.packText(w.id, it.id) }
        val place = SyncItem(SyncGroup.NEW, "新地名：渡口", "原句", PackKind.CANON, section = "地名", name = "渡口", text = "城东渡口", chapterId = w.chapters[0].id, chapter = 1)
        val planned = PackSync.plan(listOf(place), docs).single().copy(text = "- 渡口：城东的老渡口，冬天封河")
        val note = SyncItem(SyncGroup.CONFLICT, "老周的生死", "原句", PackKind.CHARACTER, fixText = true, chapterId = w.chapters[0].id, chapter = 1).let { PackSync.plan(listOf(it), docs).single() }
        assertEquals(mapOf("硬性设定" to 1), s.applySync(w.id, listOf(planned, note)))
        assertEquals("## 地名\n- 青石镇：林月的家乡\n- 渡口：城东的老渡口，冬天封河", s.packText(w.id, canon.id))
        assertEquals(1, s.changes(w.id).size)
    }

    @Test
    fun chaptersCountAsSyncedUntilTheirTextChanges() {
        val s = store()
        val w = s.create("长夜")
        val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章")!!
        val one = w.chapters[0]
        s.saveText(w.id, one.id, "渡口起了雾。", count = false)
        s.saveText(w.id, two.id, "杜娘把茶碗推给林月。", count = false)
        s.addChapter(w.id, w.volumes[0].id, "第三章（空）")
        assertEquals(listOf(one.id, two.id), s.unsynced(w.id).map { it.id })
        s.updateAssist(w.id) { it.copy(synced = mapOf(one.id to SyncMark("渡口起了雾。".hashCode(), 1L))) }
        assertEquals(listOf(two.id), s.unsynced(w.id).map { it.id })
        assertTrue(ChapterSyncs.isSynced(s.assist(w.id).synced[one.id], "渡口起了雾。"))
        assertFalse(ChapterSyncs.isSynced(null, "渡口起了雾。"))
        // 正文改过：又算没整理。
        s.saveText(w.id, one.id, "渡口起了大雾。", count = false)
        assertEquals(listOf(one.id, two.id), s.unsynced(w.id).map { it.id })
        // 记在 AI 设置里，跟着备份走；读回来一样。
        val json = JSONObject(String(s.backup()!!))
        assertTrue(json.toString().contains("\"synced\""))
        assertEquals(SyncMark("渡口起了雾。".hashCode(), 1L), s.assist(w.id).synced[one.id])
        assertTrue(s.assist(w.id).syncTip)
        s.updateAssist(w.id) { it.copy(syncTip = false) }
        assertFalse(s.assist(w.id).syncTip)
        assertNull(s.assist(w.id).synced[two.id])
    }

    @Test
    fun lookupsWhileSyncingStopAtThisChapter() {
        val s = store()
        val w = s.create("长夜")
        val one = w.chapters[0]
        s.renameChapter(w.id, one.id, "第一章 渡口")
        s.saveText(w.id, one.id, "渡口起了雾。", count = false)
        val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章 真相")!!
        s.saveText(w.id, two.id, "沈默其实是朝廷的卧底。", count = false)
        val read = { tools: PackTools, which: String -> tools.run(com.xycs.lingyuereader.ai.AiToolCall("1", PackTools.READ_CHAPTER, JSONObject().put("chapter", which))) }
        // 整理第一章时，后面的章读不到（防剧透）；平常照样能读。
        assertFalse("卧底" in read(s.packTools(w.id, upTo = one.id), "第二章 真相"))
        assertTrue("渡口起了雾" in read(s.packTools(w.id, upTo = one.id), "第一章 渡口"))
        assertTrue("卧底" in read(s.packTools(w.id), "第二章 真相"))
    }

    @Test
    fun theSamePersonPlaceOrAwarenessFromSeveralChaptersIsWrittenOnce() {
        val s = store()
        val w = s.create("长夜")
        val c = w.chapters[0].id
        val people = s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |")
        val canon = s.addPackDoc(w.id, PackKind.CANON, "硬性设定", "## 地名\n- 青石镇：林月的家乡")
        val docs = s.pack(w.id).map { it to s.packText(w.id, it.id) }
        // 第 3、4、5 章各提一次老周、渡口、林月知道沈默是卧底：每章都拿同一份旧设定包定位置，都会提出来。
        val items = (3..5).flatMap { n ->
            PackSync.plan(
                listOf(
                    person("老周", "身份" to "渡口的船夫", "本章" to "撑船").copy(chapterId = c, chapter = n),
                    SyncItem(SyncGroup.NEW, "新地名：渡口", "原句", PackKind.CANON, section = "地名", name = "渡口", text = if (n == 3) "城东的渡口" else "城东渡口，常起雾", chapterId = c, chapter = n),
                    knows("林月", if (n == 3) "沈默是朝廷的卧底" else "沈默是朝廷派来的卧底").copy(chapterId = c, chapter = n),
                ),
                docs,
            )
        }
        assertEquals(9, items.size)
        // 写的时候用已经写进去的文字再查：每样只写一次。
        assertEquals(mapOf("人物档案" to 1, "硬性设定" to 1, PackSync.LEDGER to 1), s.applySync(w.id, items))
        assertEquals(1, s.packText(w.id, people.id).lines().count { "老周" in it })
        assertEquals("## 地名\n- 青石镇：林月的家乡\n- 渡口：城东的渡口", s.packText(w.id, canon.id))
        // 察觉记进账本：三章说的同一件事只记一条（第 3 章那条）。
        assertEquals(listOf("沈默是朝廷的卧底"), s.ledger(w.id).map { it.text })
        assertEquals(2, s.changes(w.id).size)
    }

    @Test
    fun aPersonGivenASectionByAnEarlierChapterOnlyGetsTheLaterChapterLine() {
        val text = "# 人物\n\n## 林月\n想要：查清旧案"
        val docs = listOf(doc(PackKind.CHARACTER, "人物档案", text))
        val three = PackSync.plan(listOf(person("老周", "身份" to "船夫", "本章" to "撑船送林月过河").copy(chapter = 3)), docs).single()
        val four = PackSync.plan(listOf(person("老周", "身份" to "船夫", "本章" to "在渡口等沈默").copy(chapter = 4)), docs).single()
        assertTrue(PackSync.sameThing(three, four))
        // 第 4 章的不再新起一节“## 老周”，并进第 3 章起的那一节。
        assertEquals(text + "\n\n## 老周\n- 身份：船夫\n- 第3章：撑船送林月过河\n- 第4章：在渡口等沈默", PackSync.apply(PackSync.apply(text, three), four))
    }

    @Test
    fun syncingTheSameChapterAgainDoesNotRepeatItsLine() {
        val text = "## 林月\n想要：查清旧案\n\n## 沈默\n想要：活下去"
        val first = PackSync.plan(listOf(person("林月", "本章" to "在渡口茶馆听说老周死了")), listOf(doc(PackKind.CHARACTER, "人物档案", text))).single()
        val once = PackSync.apply(text, first)
        assertEquals("## 林月\n想要：查清旧案\n- 第12章：在渡口茶馆听说老周死了\n\n## 沈默\n想要：活下去", once)
        val now = listOf(doc(PackKind.CHARACTER, "人物档案", once))
        // 再整理一次（AI 换了个说法）：这一节里已经有第 12 章，不再提。
        assertTrue(PackSync.plan(listOf(person("林月", "本章" to "听杜娘说老周走了")), now).isEmpty())
        // 两次的结果都写（或写到一半重试）：还是只有一行。
        assertEquals(once, PackSync.apply(once, first))
        assertEquals(once, PackSync.apply(once, first.copy(text = "- 第12章：听杜娘说老周走了")))
        // 新的称呼照样补，本章这一行不重复。
        assertEquals("- 称呼：月丫头", PackSync.plan(listOf(person("林月", "称呼" to "月丫头", "本章" to "听杜娘说老周走了")), now).single().text)
        // 写成“- 名字：……”一条的人物也一样。
        val bullets = "- 林月：药铺掌柜的女儿；第12章：听说老周死了"
        assertEquals(bullets, PackSync.apply(bullets, first.copy(text = "- 第12章：听杜娘说老周走了")))
    }

    @Test
    fun formattingPunctuationAndBlankLinesKeepsAChapterSynced() {
        val raw = "渡口起了雾,林月说:\"走吧...\"\n\n\n  沈默没动--他在等人."
        val mark = SyncMark(ChapterSyncs.hash(raw), 1L)
        // 排版：全角标点、弯引号、省略号、破折号、空行、段首空格。
        assertTrue(ChapterSyncs.isSynced(mark, "渡口起了雾，林月说：“走吧……”\n\n　　沈默没动——他在等人。"))
        assertFalse(ChapterSyncs.isSynced(mark, "渡口起了大雾，林月说：“走吧……”\n沈默没动——他在等人。"))
        // 早先按正文直接算的哈希也认。
        assertTrue(ChapterSyncs.isSynced(SyncMark(raw.hashCode(), 1L), raw))
        val s = store()
        val w = s.create("长夜")
        val one = w.chapters[0]
        s.saveText(w.id, one.id, raw, count = false)
        s.updateAssist(w.id) { it.copy(synced = mapOf(one.id to mark)) }
        s.saveText(w.id, one.id, "渡口起了雾，林月说：“走吧……”\n沈默没动——他在等人。", count = false)
        assertTrue(s.unsynced(w.id).isEmpty())
    }

    @Test
    fun writingAgainAfterAFailureSkipsWhatIsAlreadyThere() {
        val s = store()
        val w = s.create("长夜")
        val c = w.chapters[0].id
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |")
        val docs = s.pack(w.id).map { it to s.packText(w.id, it.id) }
        val items = PackSync.plan(listOf(person("杜娘", "身份" to "渡口茶馆老板娘"), knows("林月", "沈默是朝廷的卧底")).map { it.copy(chapterId = c) }, docs)
        assertEquals(mapOf("人物档案" to 1, PackSync.LEDGER to 1), s.applySync(w.id, items))
        val texts = s.pack(w.id).associate { it.id to s.packText(w.id, it.id) }
        val ledger = s.ledger(w.id)
        // 再写一次（上次写到一半出错，重试）：已经有了的不再写，账本也不再记第二条。
        assertEquals(emptyMap<String, Int>(), s.applySync(w.id, items))
        assertEquals(texts, s.pack(w.id).associate { it.id to s.packText(w.id, it.id) })
        assertEquals(ledger, s.ledger(w.id))
        assertEquals(1, s.changes(w.id).size)
    }

    @Test
    fun laterChaptersRepeatingAnEarlierOneAreNotPickedByDefault() {
        val docs = listOf(doc(PackKind.CHARACTER, "人物档案", "| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |"))
        fun result(id: String, n: Int, vararg items: SyncItem) = ChapterSyncResult(id, "第${n}章", n, 0, PackSync.plan(items.map { it.copy(chapterId = id, chapter = n) }, docs))
        val s4 = SyncSession("w").with(result("c4", 4, person("老周", "身份" to "船夫"), knows("林月", "沈默是朝廷的卧底")))
        assertEquals(2, s4.picked.size)
        val s5 = s4.with(result("c5", 5, person("老周", "身份" to "船夫"), person("杜娘")))
        val (zhou5, du5) = s5.results.last().items
        // 第 5 章的老周：前面章已有，不勾；杜娘照样勾上。
        assertEquals(setOf(zhou5.key), s5.repeats)
        assertFalse(zhou5.key in s5.picked)
        assertTrue(du5.key in s5.picked)
        // 后来单独整理了更前面的第 3 章，并进来：第 3 章的老周勾上，第 4 章的老周变成重复、取消勾选。
        val s3 = s5.with(result("c3", 3, person("老周", "身份" to "船夫")))
        assertEquals(3, s3.results.size)
        assertTrue(s3.results.last().items.single().key in s3.picked)
        assertFalse(s3.results.first().items.first().key in s3.picked)
        assertTrue(s3.results.first().items.last().key in s3.picked)
        // 同一章再整理一次：换掉原来的结果。
        assertEquals(3, s3.with(result("c3", 3, person("老周"))).results.size)
    }

    @Test
    fun partsOfALongChapterMergeTheSamePerson() {
        val a = person("老周", "身份" to "船夫", "本章" to "撑船送林月过河")
        val b = person("老周", "关系" to "杜娘的表哥", "本章" to "在渡口等沈默")
        val merged = PackSync.merge(listOf(a, knows("林月", "沈默是朝廷的卧底"), b, knows("林月", "沈默是朝廷派来的卧底")))
        assertEquals(2, merged.size)
        val zhou = merged.first()
        assertEquals("船夫", zhou.field("身份"))
        assertEquals("杜娘的表哥", zhou.field("关系"))
        assertEquals("撑船送林月过河；在渡口等沈默", zhou.field("本章"))
    }
}
