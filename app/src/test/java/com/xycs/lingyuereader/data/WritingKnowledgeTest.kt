package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.AiToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 设定包照“给 AI 用”的方式给：工作说明在前、知识库整份、放不下的改按需、AI 用工具查。 */
class WritingKnowledgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val guide = "# 使用说明\n先读本说明，再按需读取细纲，不一次读全部。"
    private val outline = "# 全书总纲\n\n## 一、写作原则\n先写人。\n\n## 四、分卷速查\n\n**第一卷《渡口》**\n- 第1章《夜渡》：林月在渡口等船。\n\n**第二卷《对岸》**\n- 沈默在对岸立足。\n" + "备注。".repeat(6_000)
    private val people = "| 人物 | 想要 |\n|---|---|\n| 林月 | 守住渡口 |\n| 沈默 | 过河 |\n"
    private val style = "对白要短。"
    private val volume = "## 细纲\n- **第一章 夜渡**：林月等船；钩子：船没来。\n- **第二章 对岸**：沈默过河。\n"

    private fun docs() = listOf(
        PackDoc("g", PackKind.INSTRUCTION, "00_使用说明", 0, true) to guide,
        PackDoc("o", PackKind.OUTLINE, "全书总纲", 1, true) to outline,
        PackDoc("c", PackKind.CHARACTER, "人物档案", 3, true) to people,
        PackDoc("s", PackKind.STYLE, "写作约定", 5, true) to style,
        PackDoc("v", PackKind.VOLUME, "第一卷细纲", 2, false) to volume,
        PackDoc("r", PackKind.RULES, "检查规则", 5, false) to "禁用：笑了笑",
    )

    @Test
    fun instructionsFirstKnowledgeWholeAndTheRestListed() {
        val big = PackKnowledge.build(docs(), budget = 100_000)
        assertTrue(big.prefix.indexOf("【00_使用说明】") < big.prefix.indexOf("=== 知识库（整份） ==="))
        assertEquals(listOf("全书总纲", "人物档案", "写作约定"), big.knowledge.map { it.title })
        assertTrue(outline.trim() in big.prefix)
        // 卷纲细纲不是常用：只列目录（带小节）让 AI 自己读；检查规则在本机用，不给。
        assertEquals(listOf("第一卷细纲"), big.onDemand.map { it.title })
        assertTrue("《第一卷细纲》（卷纲细纲，" in big.prefix && "小节：细纲" in big.prefix)
        assertFalse("禁用：笑了笑" in big.prefix)

        // 上下文放不下：最大的一份（总纲）改成按需查，小的规范、档案照样整份。
        val small = PackKnowledge.build(docs(), budget = 2_000)
        assertEquals(listOf("人物档案", "写作约定"), small.knowledge.map { it.title })
        assertEquals(listOf("全书总纲"), small.overflow.map { it.title })
        assertTrue("《全书总纲》（总纲，" in small.prefix && "模型上下文放不下改为按需查" in small.prefix)
        assertTrue("小节：一、写作原则；四、分卷速查；四、分卷速查 › 第一卷《渡口》；四、分卷速查 › 第二卷《对岸》" in small.prefix)
        // 文档没改，前缀每次都一样（服务商能缓存）。
        assertEquals(small.prefix, PackKnowledge.build(docs(), budget = 2_000).prefix)
        // DeepSeek（128K）大约能放十万字。
        assertTrue(PackKnowledge.budget(128_000, anthropic = false) in 100_000..115_000)
    }

    @Test
    fun toolsReadSectionsSearchAndChapters() {
        val chapters = listOf(
            WorkChapter("a", "第一章 夜渡", 10, 0) to { "林月在渡口等船。\n老周提着灯过来。" },
            WorkChapter("b", "第二章 对岸", 0, 0) to { "" },
        )
        val tools = PackTools(docs(), chapters)
        fun call(name: String, args: String) = tools.run(AiToolCall("1", name, JSONObject(args)))
        // 读一节：连同下面的粗体小标题一起。
        val part = call("read_doc", """{"title":"《全书总纲》","section":"分卷速查"}""")
        assertTrue(part.startsWith("## 四、分卷速查") && "第二卷《对岸》" in part)
        assertEquals("**第二卷《对岸》**\n- 沈默在对岸立足。", call("read_doc", """{"title":"全书总纲","section":"第二卷"}""").substringBefore("备注").trim())
        // 长文档分页，说清怎么接着读。
        val page = call("read_doc", """{"title":"全书总纲"}""")
        assertTrue(page.contains("用 offset=${PackTools.PAGE} 接着读"))
        assertTrue(call("read_doc", """{"title":"全书总纲","offset":${PackTools.PAGE}}""").startsWith("备注"))
        assertTrue(call("read_doc", """{"title":"不存在"}""").startsWith("设定包里没有《不存在》"))
        // 搜关键词：设定包和正文都搜，带出处（文档 › 小节）。
        val hits = call("search", """{"query":"渡口 等船"}""")
        assertTrue("《全书总纲》 › 四、分卷速查 › 第一卷《渡口》：- 第1章《夜渡》：林月在渡口等船。" in hits)
        assertTrue("正文 第一章 夜渡：林月在渡口等船。" in hits)
        // 读一章：章号、汉字章号、章名都认。
        assertTrue(call("read_chapter", """{"chapter":"1"}""").startsWith("林月在渡口等船。"))
        assertTrue(call("read_chapter", """{"chapter":"一"}""").startsWith("林月"))
        assertEquals("第二章 对岸 还没有正文。", call("read_chapter", """{"chapter":"对岸"}"""))
        assertEquals(
            listOf("读《全书总纲》 › 分卷速查", "读《全书总纲》 › 第二卷", "读《全书总纲》", "读《全书总纲》（从第 ${PackTools.PAGE} 字起）", "搜「渡口 等船」", "读正文 第一章 夜渡", "读正文 第一章 夜渡", "读正文 第二章 对岸"),
            tools.lookups,
        )
    }

    @Test
    fun oneRequestCanOnlyLookUpSoMuch() {
        val huge = PackDoc("h", PackKind.REFERENCE, "资料", 7, false) to "字".repeat(200_000)
        val tools = PackTools(listOf(huge), emptyList())
        var total = 0
        repeat(8) { i -> total += tools.run(AiToolCall("$i", "read_doc", JSONObject().put("title", "资料").put("offset", i * PackTools.PAGE))).length }
        assertTrue(total < PackTools.TOTAL + 200)
        assertTrue(tools.run(AiToolCall("x", "read_doc", JSONObject().put("title", "资料"))).startsWith("这次能查的已经到上限了"))
    }

    @Test
    fun importedMarkdownH1SectionsKeepTheirScopeAndIgnoreFencedExamples() {
        val markdown = """# 第一卷
            |## 第一章
            |林月守在渡口。
            |```md
            |# 假卷
            |## 第一章
            |示例暗号只供排版说明。
            |```
            |# 第二卷
            |## 第一章
            |沈默独自过河。
        """.trimMargin()
        val paths = PackOutline.sections(markdown, "双卷细纲").map { it.path.joinToString(" › ") }
        assertEquals(listOf("第一卷", "第一卷 › 第一章", "第二卷", "第二卷 › 第一章"), paths)
        assertEquals("## 第一章\n沈默独自过河。", PackOutline.read(markdown, "第二卷 › 第一章", "双卷细纲"))
        val document = PackDoc("v", PackKind.VOLUME, "双卷细纲", always = false) to markdown
        val tools = PackTools(listOf(document), emptyList())
        val result = tools.run(AiToolCall("1", "read_doc", JSONObject().put("title", "双卷细纲.MD").put("section", "第二卷 › 第一章")))
        assertEquals("## 第一章\n沈默独自过河。", result)
        assertTrue(PackKnowledge.build(listOf(document), 2_000).prefix.contains("第二卷 › 第一章"))
        assertTrue(tools.run(AiToolCall("2", "search", JSONObject().put("query", "示例暗号"))).startsWith("没搜到"))
        val characters = "# 张飞\n想要守住关口。\n# 赵云\n想要带人出城。"
        assertEquals(listOf("张飞", "赵云"), PackOutline.sections(characters, "人物档案").map { it.title })
        assertEquals("# 赵云\n想要带人出城。", PackOutline.read(characters, "赵云", "人物档案"))
        assertEquals(listOf("人物"), PackOutline.sections("# 人物档案\n## 人物\n张飞与赵云。", "人物档案").map { it.title })
    }

    @Test
    fun chapterHintsCarryTheOutlineAndChapterList() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        s.renameChapter(w.id, w.chapters[0].id, "第一章 夜渡")
        val (_, two) = s.addChapter(w.id, w.volumes[0].id, "第二章 对岸")!!
        s.saveText(w.id, w.chapters[0].id, "林月等船。")
        docs().forEach { (d, t) -> s.addPackDoc(w.id, d.kind, d.title, t, d.priority, d.always) }
        val hints = s.chapterHints(w.id, two.id, "")
        assertTrue(hints.startsWith("【这一章】第二章 对岸（按设定算第 2 章，第 1 卷）"))
        assertTrue("【设定包里写这一章的原文】\n《第一卷细纲》第 2 章「对岸」：\n沈默过河。" in hints)
        assertTrue("【前情】\n- 第 1 章 第一章 夜渡：（按细纲）林月等船" in hints)
        assertTrue("第一章 夜渡（5 字）；第二章 对岸（0 字，就是这一章）" in hints)
        // 导入时“使用说明”认成工作说明，放在最前面。
        assertEquals(PackKind.INSTRUCTION, PackImport.classify("00_使用说明.md").kind)
        assertTrue(s.aiPack(w.id, 100_000).prefix.contains("=== 作者的工作说明 ===\n\n【00_使用说明】"))
    }
}
