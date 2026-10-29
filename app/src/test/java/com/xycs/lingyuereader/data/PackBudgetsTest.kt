package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设定包页的九宫格统计和“AI 每次带多少”的拼法（虚构作品，人名都是编的）。 */
class PackBudgetsTest {
    private fun doc(id: String, kind: PackKind, title: String, words: Int, always: Boolean = kind.always) =
        PackDoc(id, kind, title, always = always, words = words)

    @Test
    fun everyKindHasItsCountWordsAndMode() {
        val docs = listOf(
            doc("a", PackKind.CHARACTER, "人物档案", 1_200),
            doc("b", PackKind.CHARACTER, "配角", 300, always = false),
            doc("c", PackKind.OUTLINE, "总纲", 5_000),
            doc("d", PackKind.REFERENCE, "渡口地图说明", 80),
            doc("e", PackKind.REFERENCE, "旧报", 20),
        )
        val stats = PackBudgets.stats(docs)
        // 十三类都在，顺序同 PackKind；没有文档的类也列出来（淡显，点进去新建）。
        assertEquals(PackKind.entries, stats.map { it.kind })
        val character = stats.single { it.kind == PackKind.CHARACTER }
        assertEquals(2, character.docs)
        assertEquals(1_500, character.words)
        assertEquals("常用 1", character.mode)
        assertEquals("常用", stats.single { it.kind == PackKind.OUTLINE }.mode)
        val reference = stats.single { it.kind == PackKind.REFERENCE }
        assertEquals(2 to 100, reference.docs to reference.words)
        assertEquals("按需", reference.mode)
        val empty = stats.single { it.kind == PackKind.CANON }
        assertTrue(empty.empty)
        assertEquals(0, empty.words)
        // 空的类按新建时的默认标：硬性设定默认常用，卷纲细纲默认按需。
        assertEquals("常用", empty.mode)
        assertEquals("按需", stats.single { it.kind == PackKind.VOLUME }.mode)
        assertEquals(docs.size, stats.sumOf { it.docs })
    }

    @Test
    fun styleExcerptsSplitByDocumentAndJoinBack() {
        val docs = listOf(doc("s", PackKind.STYLE, "写作规范", 0), doc("v", PackKind.VOICE, "称谓与语气", 0))
        val text = "【写作规范】\n对白要短。\n\n【称谓与语气（节选）】\n## 林月\n叫老周“周伯”。"
        val parts = PackBudgets.splitStyle(text, docs)
        assertEquals(text, parts.joinToString("") { it.second })
        assertEquals(listOf(PackKind.STYLE, PackKind.VOICE), parts.map { it.first })
        // 对不上标题的整段记在写作规范上。
        assertEquals(listOf(PackKind.STYLE to "随便写"), PackBudgets.splitStyle("随便写", docs))
    }

    @Test
    fun theSlicesAddUpToWhatIsSentAndCutItemsAreListed() {
        val cut = "沈默在渡口等了一夜，".repeat(50).take(400) + "……（后面省略）"
        val brief = ChapterBrief(
            chapter = 3, volume = 1, present = listOf("林月", "沈默"),
            items = listOf(
                BriefItem("前情", "第 2 章 茶钱", "杜娘交出提杆。", "最近几章"),
                BriefItem("人物", "林月", "想要真相，怕连累旁人。", "本章出场"),
                BriefItem("硬性设定", "世界设定 › 渡口", cut, "写到林月"),
            ),
            notYet = listOf("林月还不知道：老周是船主"),
            open = listOf(Foreshadow("半块玉佩", planted = 1, payoff = 12)),
            due = emptyList(),
        )
        val material = brief.render(prose = true, waitingMax = ChapterBrief.WAITING)!!
        val author = "【工作说明】\n先读细纲。\n"
        val pieces = listOf(
            listOf(PackKind.STYLE to "【写作规范】\n对白要短。", PackKind.VOICE to "\n\n【称谓与语气】\n叫老周“周伯”。"),
            listOf(PackKind.RULES to "【检查规则】\n禁用：不由得"),
        )
        val norms = ProsePack.tidy(ProseLengths.strip(pieces.joinToString("\n\n") { p -> p.joinToString("") { it.second } }))
        val knowledge = PackKnowledge.build(
            listOf(doc("k1", PackKind.CANON, "世界设定", 0) to "渡口在城南。".repeat(50), doc("k2", PackKind.CHARACTER, "人物档案", 0) to "林月。"),
            budget = 100,
        )
        val b = PackBudgets.build("第3章 空船", author, norms, pieces, brief, material, 6_000, 12_000, knowledge,
            listOf(doc("k1", PackKind.CANON, "世界设定", 0) to "渡口在城南。".repeat(50), doc("k2", PackKind.CHARACTER, "人物档案", 0) to "林月。"))

        assertEquals(author.trim().length + norms.trim().length + material.length, b.proseChars)
        assertEquals(material.length, b.briefChars)
        // 分段：工作说明、写作规范、称谓与语气、检查规则、人物（人物卡）、硬性设定（切块）、察觉、伏笔、前情账本这些。
        val kinds = b.slices.map { it.kind }.toSet()
        listOf(PackKind.INSTRUCTION, PackKind.STYLE, PackKind.VOICE, PackKind.RULES, PackKind.CHARACTER, PackKind.CANON, PackKind.AWARENESS, PackKind.FORESHADOW, null)
            .forEach { assertTrue("$it", it in kinds) }
        assertEquals(b.slices.sortedByDescending { it.chars }, b.slices)
        assertEquals(PackBudgets.OTHER, b.slices.single { it.kind == null }.label)

        // 截掉的那段标出来，说明只带了多少；知识库放不下的那份整份没带。
        val partial = b.misses.single { !it.whole }
        assertEquals("世界设定 › 渡口", partial.title)
        assertEquals(400, partial.chars)
        assertTrue(partial.reason, "本章资料" in partial.reason && "6000 字" in partial.reason)
        val whole = b.misses.single { it.whole }
        assertEquals("世界设定", whole.title)
        assertEquals(300, whole.chars)
        assertTrue(whole.reason, "知识库上限 100 字" in whole.reason)
        assertTrue(b.knowledgeChars <= b.knowledgeLimit)
        assertEquals(100, b.knowledgeLimit)
    }

    @Test
    fun anEmptyWorkHasOnlyTheAuthorNotes() {
        val b = PackBudgets.build(null, "", "", emptyList(), null, "", 6_000, 12_000, null, emptyList())
        assertEquals(0, b.proseChars)
        assertTrue(b.slices.isEmpty())
        assertFalse(b.misses.any())
        assertEquals("1.2 万字", PackBudgets.words(12_000))
        assertEquals("800 字", PackBudgets.words(800))
    }
}
