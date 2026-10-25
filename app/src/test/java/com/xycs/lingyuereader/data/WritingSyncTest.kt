package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整理进设定包：定位置、写进去（表格追加一行、## 小节、新建察觉表）、存历史版本、变更登记、哪些章整理过。 */
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
    fun awarenessRowsFollowTheTableHeader() {
        val table = "| 谁 | 从第几章起 | 知道什么 | 消息从哪来 |\n| --- | --- | --- | --- |\n| 沈默 | 第3章 | 林月是旧案遗孤 | 老周 |\n"
        val item = PackSync.plan(listOf(knows("林月", "沈默是朝廷的卧底", "杜娘说漏了嘴")), listOf(doc(PackKind.AWARENESS, "察觉表", table))).single()
        assertEquals("| 林月 | 第12章 | 沈默是朝廷的卧底 | 杜娘说漏了嘴 |", item.text)
        assertEquals("| 谁 | 从第几章起 | 知道什么 | 消息从哪来 |\n| --- | --- | --- | --- |\n| 沈默 | 第3章 | 林月是旧案遗孤 | 老周 |\n${item.text}\n", PackSync.apply(table, item))
        // 表里已经有、而且不晚于这一章：不再提。
        assertTrue(PackSync.plan(listOf(knows("沈默", "知道林月是旧案遗孤")), listOf(doc(PackKind.AWARENESS, "察觉表", table))).isEmpty())
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

    @Test
    fun applyingSnapshotsCreatesTheAwarenessTableAndLogsEachItem() {
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
        // 察觉表还没有：提议新建一份。
        assertEquals("新建《察觉表》 · 表格追加一行", items[1].where)
        Thread.sleep(2)
        val counts = s.applySync(w.id, items)
        assertEquals(mapOf("人物档案" to 1, "察觉表" to 1), counts)
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |\n| 杜娘 | 渡口茶馆老板娘 |", s.packText(w.id, people.id))
        // 写之前存了原来的一版；文档的修改时间更新了。
        val history = s.history(w.id, s.docKey(people.id))
        assertEquals("| 人物 | 身份 |\n|---|---|\n| 林月 | 药铺掌柜的女儿 |", s.snapshotText(w.id, s.docKey(people.id), history.first().at))
        assertTrue(s.pack(w.id).first { it.id == people.id }.updatedAt > before)
        val awareness = s.pack(w.id).single { it.kind == PackKind.AWARENESS }
        assertEquals("察觉表", awareness.title)
        assertEquals(PackImport.template(PackKind.AWARENESS) + "| 林月 | 沈默是朝廷的卧底 | 第12章 | 杜娘说漏了嘴 |\n", s.packText(w.id, awareness.id))
        // 每条记一笔变更登记，已经同步过，不算“待同步设定”。
        val changes = s.changes(w.id)
        assertEquals(2, changes.size)
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
}
