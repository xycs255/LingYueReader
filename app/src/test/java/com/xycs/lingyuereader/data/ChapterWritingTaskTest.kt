package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.AiToolCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterWritingTaskTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun source(id: String, kind: PackKind, title: String, text: String) = PackDoc(id, kind, title) to text
    private val one = """
        # 第一卷
        ### 第一章 河桥
        事实：陈青带着三人过桥，只获准查看木料；章末仍在北岸。
        视角：陈青
        边界：本章不进城。
    """.trimIndent()

    private fun compilation(vararg files: Pair<String, String>) = files.mapIndexed { i, (name, text) ->
        "════════\n【${i + 1}】来源${i + 1}\n来源文件：$name\n════════\n\n$text\n"
    }.joinToString("\n")

    @Test fun stageTemplatesAreIsolatedInBothIndependentAndEmbeddedSources() {
        val docs = listOf(
            source("bundle", PackKind.REFERENCE, "合订本", compilation(
                "使用说明.md" to "公共工作说明。",
                "写作/完整正文创作提示词.txt" to "创作专用口令。",
                "写作/章节审读提示词.txt" to "审读专用口令。",
                "写作/章节修订提示词.txt" to "修订专用口令。",
            )),
            source("review", PackKind.INSTRUCTION, "章节审读提示词", "审读专用口令。"),
        )
        fun text(phase: WritingPhase) = PackKnowledge.build(docs, 20_000, phase).prefix
        assertTrue(text(WritingPhase.CREATE).contains("创作专用口令"))
        assertFalse(text(WritingPhase.CREATE).contains("审读专用口令"))
        assertFalse(text(WritingPhase.CREATE).contains("修订专用口令"))
        assertTrue(text(WritingPhase.REVIEW).contains("审读专用口令"))
        assertFalse(text(WritingPhase.REVIEW).contains("创作专用口令"))
        assertTrue(text(WritingPhase.REVISE).contains("修订专用口令"))
        assertFalse(text(WritingPhase.ASSIST).contains("创作专用口令"))
        assertTrue(text(WritingPhase.SYNC).contains("公共工作说明"))
        assertEquals(1, ProjectSources.forPhase(docs, WritingPhase.REVIEW).count { it.first.title == "章节审读提示词" })
    }

    @Test fun independentAndEmbeddedOutlineAreOneFactEvenWhenCompilationWasImportedFirst() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北")
        s.renameChapter(w.id, w.chapters[0].id, "第一章 河桥")
        s.addPackDoc(w.id, PackKind.REFERENCE, "合订本", compilation("分章/第一卷细纲.md" to one, "使用说明.md" to "按目标章写。"))
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", one)
        val entries = s.outlineEntries(w.id)
        assertEquals(1, entries.count { it.from == 1 && it.to == 1 })
        val task = s.writingTask(w.id, w.chapters[0].id)!!
        assertTrue(task.project)
        assertTrue(task.blocking.isEmpty())
        assertFalse(task.card.facts.contains("另见"))
        assertEquals(1, task.sourceNames.count { it == "第一卷细纲" })
    }

    @Test fun differentlyNamedCopiesOfTheSameOutlineAreOneFact() {
        val docs = listOf(source("a", PackKind.VOLUME, "第一卷细纲", one), source("b", PackKind.VOLUME, "现行分章安排", one))
        assertEquals(1, ProjectSources.forPhase(docs, WritingPhase.CREATE).size)
    }

    @Test fun aDifferentEmbeddedOutlineDoesNotAppendConflictingFactsAndIsFlaggedForVersionChecking() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        val embedded = one.replace("只获准查看木料", "获准率兵进城")
        s.addPackDoc(w.id, PackKind.REFERENCE, "合订本", compilation("第一卷细纲.md" to embedded, "使用说明.md" to "当前版。"))
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", one)
        val task = s.writingTask(w.id, c)!!
        assertTrue(task.card.facts.contains("只获准查看木料"))
        assertFalse(task.card.facts.contains("获准率兵进城"))
        assertTrue(task.warnings.any { "版本差异待核" in it })
    }

    @Test fun explicitLengthThenTargetChapterThenScopedExceptionThenRulesWin() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", one)
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "使用说明", "第一至三章约3500—5000汉字弹性试写，其余按本章细纲。")
        s.addPackDoc(w.id, PackKind.RULES, "检查规则", "字数：6000-8000")
        assertEquals("约 3500—5000汉字", s.writingTask(w.id, c)!!.targetLength)
        assertEquals("不少于 6500 汉字", s.writingTask(w.id, c, "本次不少于6500汉字")!!.targetLength)
        assertEquals("7200—8400字", s.writingTask(w.id, c, "本次不少于6500汉字", "7200—8400字")!!.targetLength)
        s.saveCard(w.id, c, ChapterCard(facts = "篇幅：7100—8500字。陈青过桥，章末留在北岸。"))
        assertEquals("7100—8500字", s.writingTask(w.id, c)!!.targetLength)
        repeat(3) { s.addChapter(w.id, w.volumes[0].id) }
        val fourth = s.get(w.id)!!.chapters[3]
        s.saveCard(w.id, fourth.id, ChapterCard(facts = "陈青获准进城，见到旧友。"))
        assertEquals("6000—8000 字", s.writingTask(w.id, fourth.id)!!.targetLength)
    }

    @Test fun chineseNumeralVolumeHeaderClausesStayInsideTheirChapterRangesAndNarrowExceptionsWin() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北")
        repeat(7) { s.addChapter(w.id, w.volumes[0].id) }
        val header = """
            # 第一卷
            第一至二章先按约二千二百至三千六百汉字的弹性篇幅试写；第三至八章仍四千八百字起步。
            第五至六章允许六千二百至七千四百汉字。
        """.trimIndent()
        val entries = (1..8).joinToString("\n") { "### 第${it}章 测试${it}\n事实：陈青完成本章的选择，留在桥边。" }
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "$header\n$entries")
        val chapters = s.get(w.id)!!.chapters
        assertEquals("约 2200—3600汉字", s.writingTask(w.id, chapters[0].id)!!.targetLength)
        assertEquals("约 2200—3600汉字", s.writingTask(w.id, chapters[1].id)!!.targetLength)
        assertEquals("不少于 4800 字", s.writingTask(w.id, chapters[2].id)!!.targetLength)
        assertEquals("不少于 4800 字", s.writingTask(w.id, chapters[3].id)!!.targetLength)
        assertEquals("6200—7400汉字", s.writingTask(w.id, chapters[4].id)!!.targetLength)
        assertEquals("6200—7400汉字", s.writingTask(w.id, chapters[5].id)!!.targetLength)
        assertEquals("不少于 4800 字", s.writingTask(w.id, chapters[7].id)!!.targetLength)
        // Enumerated exceptions can share one trailing allowance without changing the broad default.
        assertEquals("6100—7300字", ProjectLengths.scoped(listOf("第三章有数日交接，第五至六章跨季，允许六千一百至七千三百字。"), 3))
        assertNull(ProjectLengths.scoped(listOf("第一至二章约两千至三千字；第五至六章四千字起步。"), 4))
        assertEquals("不少于 5600 汉字", ProjectLengths.find("本次不少于五千六百汉字"))
        assertEquals("约 3200字", ProjectLengths.find("这次写三千二百字左右"))
    }

    @Test fun missingHardFactsBlockProjectButAnEmptyOrdinaryWorkCanStillBeUsed() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        assertFalse(s.writingTask(w.id, c)!!.project)
        assertTrue(s.writingTask(w.id, c)!!.blocking.isEmpty())
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "完整正文创作提示词", "遵守本章事实边界。")
        s.saveCard(w.id, c, ChapterCard(hook = "有人到来"))
        assertTrue(s.writingTask(w.id, c)!!.blocking.any { "事实与结果" in it })
        s.saveCard(w.id, c, ChapterCard(facts = "陈青看清来人身份，决定留宿。"))
        assertTrue(s.writingTask(w.id, c)!!.blocking.isEmpty())
    }

    @Test fun aChapterGroupRequiresARealSingleChapterBoundaryRatherThanPackingTheGroupIntoOneChapter() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.addPackDoc(w.id, PackKind.VOLUME, "第一卷细纲", "### 第一至三章 河桥\n事实：进城、相认、离城，三章展开。")
        assertTrue(s.writingTask(w.id, c)!!.blocking.any { "章组" in it })
        s.saveCard(w.id, c, ChapterCard(facts = "本章只过桥，得知城门已闭；留宿北岸。", boundary = "下一章才进城。"))
        assertTrue(s.writingTask(w.id, c)!!.blocking.isEmpty())
    }

    @Test fun aTargetOutlineLocatesTheVolumeWhenImportedChaptersShareOneCatalogVolume() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.renameChapter(w.id, c, "第十章 河桥")
        s.addPackDoc(w.id, PackKind.VOLUME, "第二卷细纲", "# 第二卷\n### 第十章 河桥\n事实：陈青留在桥边，等待旧友。")
        val task = s.writingTask(w.id, c)!!
        assertEquals(2, task.volume)
        assertEquals(10, task.chapter)
        assertTrue(task.warnings.any { "卷次不同" in it })
    }

    @Test fun unconfirmedPreviousDraftAndItsSummaryNeverBecomeAcceptedContext() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c1 = w.chapters[0].id
        s.saveText(w.id, c1, "草稿独有事实：陈青已经当上城守。")
        s.saveCard(w.id, c1, ChapterCard(facts = "规划独有：陈青明年当上城守。", summary = "未定摘要独有：陈青当上城守。"))
        val (_, c2) = s.addChapter(w.id, w.volumes[0].id)!!
        s.saveCard(w.id, c2.id, ChapterCard(facts = "陈青回桥边等人。"))
        var task = s.writingTask(w.id, c2.id)!!
        assertEquals("", task.previous)
        assertFalse(task.context.contains("未定摘要独有"))
        assertTrue(task.previousLabel.contains("细纲"))
        s.setStatus(w.id, c1, ChapterStatus.FINAL)
        task = s.writingTask(w.id, c2.id)!!
        assertTrue(task.previous.contains("草稿独有事实"))
        assertTrue(task.context.contains("未定摘要独有"))
    }

    @Test fun taskFingerprintTracksCurrentMaterialsAndRequirements() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.saveCard(w.id, c, ChapterCard(facts = "陈青留在桥边。"))
        val first = s.writingTask(w.id, c)!!
        assertEquals(first.fingerprint, s.writingTask(w.id, c)!!.fingerprint)
        assertNotEquals(first.fingerprint, s.writingTask(w.id, c, "对白更生活一些")!!.fingerprint)
        s.saveCard(w.id, c, ChapterCard(facts = "陈青离开桥边。"))
        assertNotEquals(first.fingerprint, s.writingTask(w.id, c)!!.fingerprint)
    }

    @Test fun editingOnlyTheReviewOrRevisionTemplateInvalidatesTheTaskFingerprint() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.saveCard(w.id, c, ChapterCard(facts = "陈青留在桥边。"))
        val review = s.addPackDoc(w.id, PackKind.INSTRUCTION, "章节审读提示词", "核事实。")
        val revision = s.addPackDoc(w.id, PackKind.INSTRUCTION, "章节修订提示词", "依原稿修。")
        val first = s.writingTask(w.id, c)!!.fingerprint
        s.savePackText(w.id, review.id, "核事实，并单独连读对白。")
        val second = s.writingTask(w.id, c)!!.fingerprint
        assertNotEquals(first, second)
        s.savePackText(w.id, revision.id, "依原稿修，保留有效心理。")
        assertNotEquals(second, s.writingTask(w.id, c)!!.fingerprint)
    }

    @Test fun changingAuthorStyleExamplesOrSampleChoicesInvalidatesTheTaskFingerprint() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.saveCard(w.id, c, ChapterCard(facts = "陈青留在桥边。"))
        var previous = s.writingTask(w.id, c)!!.fingerprint
        fun change(transform: (WorkAssist) -> WorkAssist) {
            s.updateAssist(w.id, transform)
            val next = s.writingTask(w.id, c)!!.fingerprint
            assertNotEquals(previous, next)
            previous = next
        }
        change { it.copy(style = "保留普通生活对白。") }
        change { it.copy(accepted = listOf(StyleExample("润色", "他说完。", "他说到这里停了停。", 1))) }
        change { it.copy(declined = listOf(StyleExample("改稿", "他进门。", "真正的强者从不回头。", 2))) }
        change { it.copy(sampleLimit = 800) }
        change { it.copy(sampleFallback = false) }
        change { it.copy(sampleSkip = listOf("sample-a")) }
        change { it.copy(sampleShift = mapOf("DIALOGUE" to 1)) }
    }

    @Test fun changingTheLedgerOutsideTheRenderedBudgetStillInvalidatesTheTaskFingerprint() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c = w.chapters[0].id
        s.saveCard(w.id, c, ChapterCard(facts = "陈青留在桥边。"))
        val first = s.writingTask(w.id, c)!!.fingerprint
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "遥远地区的另一件事。", from = 50))), "新增规划事实")
        assertNotEquals(first, s.writingTask(w.id, c)!!.fingerprint)
    }

    @Test fun referencesAreLabelledAndExplicitArchivesDoNotJoinCurrentFacts() {
        val docs = listOf(
            source("sample", PackKind.VOICE, "对白验证样章", "试写人物已经远行。"),
            source("old", PackKind.VOLUME, "第一卷细纲（旧版）", "旧稿独有事实。"),
            source("background", PackKind.CANON, "旧版制度对照", "这是背景中的旧制度。"),
        )
        val selected = ProjectSources.forPhase(docs, WritingPhase.CREATE)
        val sample = selected.single { it.first.id == "sample" }
        assertEquals(PackKind.REFERENCE, sample.first.kind)
        assertFalse(sample.first.always)
        assertTrue(sample.second.contains("不是用户已定正文"))
        assertFalse(selected.any { it.first.id == "old" })
        assertTrue(selected.any { it.first.id == "background" })
    }

    @Test fun confirmedOnlyToolsKeepOriginalChapterNumbersAndStageBoundaries() {
        val s = WorkStore(tmp.newFolder()); val w = s.create("桥北"); val c1 = w.chapters[0].id
        s.saveText(w.id, c1, "第一章有效事实。"); s.setStatus(w.id, c1, ChapterStatus.FINAL)
        val (_, c2) = s.addChapter(w.id, w.volumes[0].id)!!
        s.saveText(w.id, c2.id, "第二章未定事实。")
        val (_, c3) = s.addChapter(w.id, w.volumes[0].id)!!
        s.saveText(w.id, c3.id, "第三章有效事实。"); s.setStatus(w.id, c3.id, ChapterStatus.FINAL)
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "章节修订提示词", "修订私有口令。")
        s.addPackDoc(w.id, PackKind.INSTRUCTION, "完整正文创作提示词", "创作私有口令。")
        val tools = s.packTools(w.id, c3.id, WritingPhase.REVIEW, confirmedOnly = true)
        fun read(which: String) = tools.run(AiToolCall("x", PackTools.READ_CHAPTER, JSONObject().put("chapter", which)))
        assertTrue(read("1").contains("第一章有效事实"))
        assertFalse(read("2").contains("第三章有效事实"))
        assertFalse(read("2").contains("第二章未定事实"))
        assertTrue(read("3").contains("第三章有效事实"))
        val search = tools.run(AiToolCall("s", PackTools.SEARCH, JSONObject().put("query", "口令")))
        assertFalse(search.contains("创作私有口令"))
        assertFalse(search.contains("修订私有口令"))
    }
}
