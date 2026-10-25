package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WritingPackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = WorkStore(tmp.newFolder())

    @Test
    fun fileNamesAreSortedIntoKinds() {
        val cases = mapOf(
            "00_使用说明.md" to PackKind.REFERENCE,
            "全本总纲.txt" to PackKind.OUTLINE,
            "人物设定档案.md" to PackKind.CHARACTER,
            "对白与人物语气.md" to PackKind.VOICE,
            "称谓速查.md" to PackKind.VOICE,
            "全书硬性设定与连续性.md" to PackKind.CANON,
            "前三卷_战役卡.md" to PackKind.CANON,
            "人数权限与接续索引.md" to PackKind.INVARIANT,
            "前二十章修订不变量表.md" to PackKind.INVARIANT,
            "察觉表_主角与对手.md" to PackKind.AWARENESS,
            "第一卷细纲_新版.md" to PackKind.VOLUME,
            "全本叙事因果总览.md" to PackKind.VOLUME,
            "正文写作约定.md" to PackKind.STYLE,
            "战争场面写作规范.md" to PackKind.STYLE,
            "某书技法摘要.md" to PackKind.TECHNIQUE,
            "第一卷全卷体检与改写方案.md" to PackKind.REFERENCE,
        )
        cases.forEach { (name, kind) ->
            val item = PackImport.classify(name)
            assertFalse(name, item.asChapters)
            assertEquals(name, kind, item.kind)
        }
        // 正文按章节导入，不当设定。
        assertTrue(PackImport.classify("已定正文_第1-5章.md").asChapters)
        assertTrue(PackImport.classify("第6—10章.txt").asChapters)
        // 写手版、速查这类压缩版比原件低一级。
        assertEquals(PackKind.OUTLINE.priority + 1, PackImport.classify("写手版总纲.md").priority)
        assertEquals("写手版总纲", PackImport.classify("资料/写手版总纲.md").title)
        assertFalse(PackImport.isText("封面.png"))
    }

    @Test
    fun chapterFilesAreSplitAtChapterTitles() {
        val text = "# 已定正文 第1-2章\n\n## 第一章 雪夜\n\n　　他推开门。\n\n　　风雪灌进来。\n\n## 第二章 来客\n\n> 院门外来了人。\n"
        assertEquals(
            listOf("第一章 雪夜" to "他推开门。\n风雪灌进来。", "第二章 来客" to "院门外来了人。"),
            PackImport.chapters(text, "备用"),
        )
        // 纯文本的“第X章”行也认；没有章名就整份当一章。
        assertEquals(listOf("第三章 惊蛰", "第四章 春分"), PackImport.chapters("第三章 惊蛰\n雷声。\n第四章 春分\n雨。", "x").map { it.first })
        assertEquals(listOf("楔子稿" to "只有一段。"), PackImport.chapters("只有一段。", "楔子稿"))
    }

    @Test
    fun zipRoundTripKeepsKindsAndPriorities() {
        val docs = listOf(
            PackDoc("a", PackKind.OUTLINE, "全本总纲", 1, true) to "主线：复仇。",
            PackDoc("b", PackKind.CHARACTER, "人物/档案", 4, false) to "## 林月\n想要：一个家",
        )
        val out = ByteArrayOutputStream()
        PackImport.writeZip(docs, out)
        val items = PackImport.readZip { out.toByteArray().inputStream() }
        assertEquals(listOf("人物/档案", "全本总纲"), items.map { it.title }.sorted())
        val character = items.first { it.kind == PackKind.CHARACTER }
        assertEquals(4, character.priority)
        assertFalse(character.always)
        assertEquals("## 林月\n想要：一个家", character.text)
    }

    @Test
    fun zipWithoutManifestIsClassifiedAndGbkNamesWork() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charset.forName("GBK")).use { zip ->
            zip.putNextEntry(ZipEntry("设定/人物档案.md")); zip.write("## 林月".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("设定/封面.png")); zip.write(byteArrayOf(1, 2)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("__MACOSX/._人物档案.md")); zip.write(byteArrayOf(0)); zip.closeEntry()
        }
        val items = PackImport.readZip { out.toByteArray().inputStream() }
        assertEquals(listOf("人物档案" to PackKind.CHARACTER), items.map { it.title to it.kind })
    }

    @Test
    fun contextPutsHigherPriorityFirstAndSharesTheBudget() {
        val docs = listOf(
            PackDoc("s", PackKind.STYLE, "写作规范", 5, true) to "少用成语。",
            PackDoc("o", PackKind.OUTLINE, "总纲", 1, true) to "主线：复仇。",
            PackDoc("r", PackKind.REFERENCE, "资料", 7, false) to "不常用的资料",
            PackDoc("c", PackKind.CHARACTER, "人物", 3, true) to "林".repeat(5_000),
        )
        val ctx = PackContext.build(docs, limit = 2_000)!!
        assertTrue(ctx.indexOf("【总纲·总纲（优先级 1）】") < ctx.indexOf("【人物档案·人物（优先级 3）】"))
        assertTrue(ctx.indexOf("【人物档案·人物") < ctx.indexOf("【写作规范·写作规范"))
        assertFalse("不常用的资料" in ctx)
        assertTrue("……（后面省略）" in ctx)
        assertTrue("少用成语。" in ctx)
        assertTrue(ctx.length < 2_400)
        assertNull(PackContext.build(listOf(PackDoc("r", PackKind.REFERENCE, "资料", 7, false) to "x")))
    }

    @Test
    fun oldSettingsAreMigratedIntoDocuments() {
        val s = store()
        val w = s.create("长夜")
        s.updateAssist(w.id) {
            it.copy(entries = listOf(
                BibleEntry("1", BibleKind.CHARACTER, "林月", "药铺掌柜的女儿"),
                BibleEntry("2", BibleKind.CHARACTER, "沈默", "游侠"),
                BibleEntry("3", BibleKind.FORESHADOW, "玉佩", "另一半在林月手里"),
            ))
        }
        val docs = s.pack(w.id)
        assertEquals(listOf(PackKind.CHARACTER to "人物", PackKind.REFERENCE to "伏笔"), docs.map { it.kind to it.title })
        assertEquals("## 林月\n药铺掌柜的女儿\n\n## 沈默\n游侠", s.packText(w.id, docs[0].id))
        // 迁移只做一次，旧条目清空。
        assertTrue(s.assist(w.id).entries.isEmpty())
        assertEquals(docs, s.pack(w.id))
    }

    @Test
    fun documentsCanBeEditedVersionedAndDeleted() {
        val s = store()
        val w = s.create("长夜")
        val doc = s.addPackDoc(w.id, PackKind.STYLE, "  写作规范 ", "第一版")
        assertEquals("写作规范", doc.title)
        assertTrue(doc.always)
        s.snapshot(w.id, s.docKey(doc.id), "第一版", at = 1_000)
        val saved = s.savePackText(w.id, doc.id, "第二版，更长一些")!!
        assertEquals(WordCount.of("第二版，更长一些"), saved.words)
        // 设定文档的字数不算码字数。
        assertEquals(0, s.stats().on(LocalDate.now()))
        val changed = s.updatePackDoc(w.id, doc.id) { it.copy(priority = 12, always = false, kind = PackKind.VOICE) }!!
        assertEquals(9 to false, changed.priority to changed.always)
        assertEquals(PackKind.VOICE, s.pack(w.id).single().kind)

        s.restorePackDoc(w.id, doc.id, 1_000)
        assertEquals("第一版", s.packText(w.id, doc.id))
        assertTrue(s.history(w.id, s.docKey(doc.id)).any { s.snapshotText(w.id, s.docKey(doc.id), it.at) == "第二版，更长一些" })

        s.deletePackDoc(w.id, doc.id)
        assertTrue(s.pack(w.id).isEmpty())
        assertEquals("第一版", s.snapshotText(w.id, s.docKey(doc.id), s.history(w.id, s.docKey(doc.id)).first().at))
    }

    @Test
    fun importReplacesSameTitlesAndAddsChaptersWithoutCountingWords() {
        val s = store()
        val w = s.create("长夜")
        val old = s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "旧的")
        val result = s.importPack(
            w.id,
            listOf(
                PackImport.classify("人物档案.md", "新的"),
                PackImport.classify("正文写作约定.md", "少用成语"),
                PackImport.classify("已定正文_第1-2章.md", "## 第一章 雪夜\n他推开门。\n## 第二章 来客\n来人了。"),
                PackImport.classify("不要的.md", "x").copy(include = false),
            ),
        )
        assertEquals(PackImportResult(added = 1, replaced = 1, chapters = 2), result)
        assertEquals("新的", s.packText(w.id, old.id))
        assertEquals("旧的", s.snapshotText(w.id, s.docKey(old.id), s.history(w.id, s.docKey(old.id)).first().at))
        val work = s.get(w.id)!!
        // 新建作品自带的空白“第1章”导入正文后删掉。
        assertEquals(listOf("第一章 雪夜", "第二章 来客"), work.chapters.map { it.title })
        assertEquals("他推开门。", s.text(w.id, work.chapters[0].id))
        assertEquals(0, s.stats().on(LocalDate.now()))
        assertEquals(listOf("人物档案", "正文写作约定"), s.pack(w.id).map { it.title })
    }

    @Test
    fun packTravelsWithTheBackup() {
        val a = store()
        val w = a.create("长夜")
        a.addPackDoc(w.id, PackKind.OUTLINE, "总纲", "主线：复仇。", priority = 1)
        val json = String(a.backup()!!)
        assertTrue(JSONObject(json).getJSONArray("works").getJSONObject(0).getJSONArray("pack").length() == 1)
        val b = store()
        b.restoreBackup(json)
        val doc = b.pack(w.id).single()
        assertEquals(PackKind.OUTLINE to 1, doc.kind to doc.priority)
        assertEquals("主线：复仇。", b.packText(w.id, doc.id))
    }

    @Test
    fun templatesGiveCharacterCardsTheirFiveQuestions() {
        assertTrue(PackImport.template(PackKind.CHARACTER).let { "想要" in it && "害怕" in it && "怎么说话" in it && "标志物" in it && "怎么变" in it })
        assertEquals("", PackImport.template(PackKind.REFERENCE))
    }
}
