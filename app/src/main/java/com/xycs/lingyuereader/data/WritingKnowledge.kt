package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.AiTool
import com.xycs.lingyuereader.ai.AiToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * 写作 AI 拿到的设定包，照作者在 Claude 项目里的用法：
 * - 工作说明（使用说明、提示词）放在最前面，AI 照它办事；
 * - 知识库（标了“常用”的文档）整份放进去，不截、不挑；模型上下文放不下时，从最大的那份起改成按需查；
 * - 其余文档只给目录（文档名和各小节标题），AI 用工具自己读、自己搜。
 * [prefix] 每次请求都一样（文档没改就不变），放在系统提示最前面，服务商能按相同开头缓存。
 */
data class AiPack(
    val prefix: String,
    val instructions: List<PackDoc>,
    val knowledge: List<PackDoc>,
    val onDemand: List<PackDoc>,
    /** 标了常用、但上下文放不下改成按需查的。 */
    val overflow: List<PackDoc>,
    val budget: Int,
) {
    val chars: Int get() = prefix.length
}

object PackKnowledge {
    /** 知识库最多占模型上下文的多少（其余留给本章、上一章正文、查到的东西和回答）。 */
    private const val SHARE = 0.55

    /**
     * 按模型上下文估算知识库能放多少字。中文一个字：DeepSeek 等约 0.6 个 token，Claude 约 1 个；
     * 往宽里估，宁可少放一份让 AI 去查，也不让请求超长。
     */
    fun budget(contextTokens: Int, anthropic: Boolean): Int = (contextTokens * SHARE / (if (anthropic) 1.1 else 0.65)).toInt()

    /** 目录里每份文档最多列几个小节。 */
    private const val MAX_SECTIONS = 60

    fun build(docs: List<Pair<PackDoc, String>>, budget: Int, phase: WritingPhase? = null): AiPack {
        val live = (phase?.let { ProjectSources.forPhase(docs, it) } ?: docs).filter { it.second.isNotBlank() }
        val ordered = live.sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
        val instructions = ordered.filter { it.first.kind == PackKind.INSTRUCTION }
        val always = ordered.filter { it.first.always && it.first.kind != PackKind.INSTRUCTION && it.first.kind != PackKind.RULES }.toMutableList()
        // 放不下：从最大的一份起改成按需查（大的多半是速查、档案，查着用也行；小的规范、表格整份放着最划算）。
        val room = budget - instructions.sumOf { it.second.length }
        val overflow = mutableListOf<Pair<PackDoc, String>>()
        while (always.isNotEmpty() && always.sumOf { it.second.length } > room) {
            val biggest = always.maxBy { it.second.length }
            always -= biggest
            overflow += biggest
        }
        val onDemand = ordered.filter { d -> d !in instructions && d !in always && d.first.kind != PackKind.RULES }
        val out = StringBuilder()
        out.append("下面是作者这部作品的设定包，是作者写给 AI 用的，照它办事。\n")
        out.append("作者的说明里提到的“知识库”“项目”，就是下面整份给出的文档；提到的“云盘”“文件夹”里的文件、没整份给出的文档，用工具读：")
        out.append("read_doc 读一份文档或其中一节，search 按关键词搜设定包和已写的正文，read_chapter 读某一章正文。\n")
        if (phase == null) out.append("设定资料之间有冲突时，按作者说明里写的优先级；没写的，以总纲为准。\n")
        else out.append("本次明确要求优先；目标章细纲与用户已定前文管章内事实和结果，总纲及合订本只核对实际涉及的跨章固定设定。硬事实冲突须列明出处；同版独立和内嵌细纲不叠加事件。规划、样章与待核增量不当作已发生事实。\n")
        if (instructions.isNotEmpty()) {
            out.append("\n=== 作者的工作说明 ===\n")
            instructions.forEach { (d, t) -> out.append("\n【").append(d.title).append("】\n")
                .append("[来源 ").append(ReviewEvidence.ref("P", d.id, t)).append("]\n").append(t.trim()).append('\n') }
        }
        if (always.isNotEmpty()) {
            out.append("\n=== 知识库（整份） ===\n")
            always.forEach { (d, t) -> out.append("\n【").append(d.title).append("】（").append(d.kind.label).append("）\n")
                .append("[来源 ").append(ReviewEvidence.ref("P", d.id, t)).append("]\n").append(t.trim()).append('\n') }
        }
        if (onDemand.isNotEmpty()) {
            out.append("\n=== 按需查阅的文档（没整份给出，要用时用 read_doc 读；可以只读某一节） ===\n")
            onDemand.forEach { (d, t) ->
                out.append("\n《").append(d.title).append("》（").append(d.kind.label).append("，").append(t.length).append(" 字")
                if (d.always) out.append("，是知识库文档，模型上下文放不下改为按需查")
                out.append("，来源 ").append(ReviewEvidence.ref("P", d.id, t)).append("）")
                val heads = PackOutline.sections(t, d.title).map { it.path.joinToString(" › ") }.distinct().take(MAX_SECTIONS)
                if (heads.isNotEmpty()) out.append("小节：").append(heads.joinToString("；"))
                out.append('\n')
            }
        }
        return AiPack(out.toString(), instructions.map { it.first }, always.map { it.first }, onDemand.map { it.first }, overflow.map { it.first }, budget)
    }
}

/** 文档里的小节：Markdown 标题与顶格粗体起头的行，以及各自管的行。 */
object PackOutline {
    data class Section(val level: Int, val title: String, val path: List<String>, val start: Int, val end: Int)

    private val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.+?)\\s*#*$")
    private val lead = Regex("^\\*\\*([^*\\n]{2,40}?)[：:]?\\*\\*")
    private val item = Regex("^(?:[-*+•]\\s+|\\d{1,3}[.、)）]\\s*)")
    private val keyChars = Regex("[《》「」\\s›>]")
    private fun key(value: String) = value.replace(keyChars, "")

    fun sections(text: String, documentTitle: String? = null): List<Section> {
        val lines = text.removePrefix("\uFEFF").lines()
        val marks = mutableListOf<Pair<Int, Pair<Int, String>>>()
        val fenced = MarkdownFences.mask(lines)
        // 封面一级标题（见 [PackCover]）不算一节；别的一级标题管着卷、人物，要留在目录里。
        val cover = PackCover.line(lines, fenced, documentTitle)
        lines.forEachIndexed { i, raw ->
            if (fenced[i]) return@forEachIndexed
            val t = raw.trim()
            heading.matchEntire(raw.trimEnd())?.let { m ->
                val level = m.groupValues[1].length
                val title = m.groupValues[2].replace("*", "").trim()
                if (title.isNotEmpty() && i != cover) marks += i to (level to title)
                return@forEachIndexed
            }
            if (raw.isNotEmpty() && raw[0] != ' ' && raw[0] != '\t' && !item.containsMatchIn(t)) {
                lead.find(t)?.let { m -> marks += i to (7 to m.groupValues[1].trim()) }
            }
        }
        val path = ArrayList<Pair<Int, String>>()
        return marks.mapIndexed { k, (i, lt) ->
            val (level, title) = lt
            while (path.isNotEmpty() && path.last().first >= level) path.removeAt(path.lastIndex)
            path += level to title
            val end = marks.drop(k + 1).firstOrNull { it.second.first <= level }?.first ?: lines.size
            Section(level, title, path.map { it.second }, i, end)
        }
    }

    /** 读一节：标题对上（或含有）[name] 的第一节，连同它下面的小节。 */
    fun read(text: String, name: String, documentTitle: String? = null): String? {
        val wanted = key(name)
        if (wanted.isEmpty()) return null
        val all = sections(text, documentTitle)
        // Resolve an explicit path before a repeated leaf title such as “第一章”.
        val s = all.firstOrNull { key(it.path.joinToString(" › ")) == wanted }
            ?: all.firstOrNull { key(it.title) == wanted }
            ?: all.firstOrNull { wanted in key(it.path.joinToString(" › ")) }
            ?: all.firstOrNull { wanted in key(it.title) }
            ?: return null
        return text.removePrefix("\uFEFF").lines().subList(s.start, s.end).joinToString("\n").trim()
    }
}

/**
 * AI 查设定包用的工具：读文档（或其中一节）、搜关键词、读某一章正文。都在本机读，不联网。
 * 一次请求查到的总字数有上限，到了就请 AI 用已有的资料作答。
 */
class PackTools(
    private val docs: List<Pair<PackDoc, String>>,
    private val chapters: List<Pair<WorkChapter, () -> String>>,
    /** Original chapter numbers before FINAL-only filtering; null preserves the legacy ordinal lookup. */
    private val chapterNumbers: Map<String, Int>? = null,
) {
    private var used = 0

    /** AI 查了什么（给作者看）。 */
    val lookups = mutableListOf<String>()

    fun run(call: AiToolCall): String {
        val a = call.arguments
        val out = when (call.name) {
            READ_DOC -> readDoc(a.optString("title"), a.optString("section"), a.optInt("offset", 0))
            SEARCH -> search(a.optString("query"))
            READ_CHAPTER -> readChapter(a.optString("chapter"), a.optInt("offset", 0))
            else -> "没有这个工具：${call.name}"
        }
        if (used >= TOTAL) return "这次能查的已经到上限了，请根据已经查到的资料作答。"
        val cut = if (used + out.length > TOTAL) out.take(TOTAL - used) + "\n（这次能查的到上限了，后面没给出。）" else out
        used += cut.length
        return cut
    }

    private fun clean(s: String) = s.replace(Regex("[《》「」“”\\s]"), "")

    private fun findDoc(title: String): Pair<PackDoc, String>? {
        val key = clean(title).replace(Regex("\\.(?:md|markdown|txt)$", RegexOption.IGNORE_CASE), "")
        if (key.isEmpty()) return null
        fun normalized(name: String) = clean(name).replace(Regex("\\.(?:md|markdown|txt)$", RegexOption.IGNORE_CASE), "")
        return docs.firstOrNull { normalized(it.first.title) == key } ?: docs.firstOrNull { key in normalized(it.first.title) || normalized(it.first.title) in key }
    }

    private fun page(text: String, offset: Int, max: Int): String {
        val from = offset.coerceIn(0, text.length)
        val part = text.substring(from, minOf(text.length, from + max))
        val rest = text.length - from - part.length
        return if (rest > 0) "$part\n（还有 $rest 字，用 offset=${from + part.length} 接着读）" else part
    }

    private fun readDoc(title: String, section: String, offset: Int): String {
        val (d, t) = findDoc(title) ?: return "设定包里没有《$title》。有这些：" + docs.joinToString("、") { "《${it.first.title}》" }
        if (section.isNotBlank()) {
            val s = PackOutline.read(t, section, d.title)
            lookups += "读《${d.title}》 › $section" + if (s == null) "（没找到这一节）" else ""
            return s?.let { page(it, offset, PAGE) + "\n[来源 ${ReviewEvidence.ref("P", d.id, t)}]" }
                ?: "《${d.title}》里没有叫“$section”的小节。小节有：" + PackOutline.sections(t, d.title).joinToString("；") { it.path.joinToString(" › ") }.take(2_000)
        }
        lookups += "读《${d.title}》" + if (offset > 0) "（从第 $offset 字起）" else ""
        return page(t, offset, PAGE) + "\n[来源 ${ReviewEvidence.ref("P", d.id, t)}]"
    }

    private fun search(query: String): String {
        val words = query.split(Regex("[\\s,，、；;]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        if (words.isEmpty()) return "要搜什么？给几个关键词。"
        lookups += "搜「${words.joinToString(" ")}」"
        data class Hit(val where: String, val text: String, val score: Int)
        val hits = mutableListOf<Hit>()
        docs.forEach { (d, t) ->
            val sections = PackOutline.sections(t, d.title)
            val sourceRef = ReviewEvidence.ref("P", d.id, t)
            paragraphs(t).forEach { (line, p) ->
                val n = words.count { it in p }
                if (n == 0) return@forEach
                val s = sections.lastOrNull { line >= it.start && line < it.end }
                hits += Hit("《${d.title}》" + (s?.let { " › " + it.path.joinToString(" › ") } ?: ""),
                    snippet(p, words) + " [来源 $sourceRef]", n * 10 - p.length / 2_000)
            }
        }
        chapters.forEach { (c, text) ->
            val chapterText = text()
            val sourceRef = ReviewEvidence.ref("C", c.id, chapterText)
            paragraphs(chapterText).forEach { (_, p) ->
                val n = words.count { it in p }
                if (n > 0) hits += Hit("正文 ${c.title}", snippet(p, words) + " [来源 $sourceRef]",
                    n * 10 - p.length / 2_000)
            }
        }
        val top = hits.sortedByDescending { it.score }.take(MAX_HITS)
        if (top.isEmpty()) return "没搜到“${words.joinToString(" ")}”。"
        return top.joinToString("\n\n") { "${it.where}：${it.text}" }
    }

    /** 按段落、列表项、表格行切开：（起始行号，内容）。 */
    private fun paragraphs(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        val buf = StringBuilder()
        var start = 0
        val lines = text.lines()
        val fenced = MarkdownFences.mask(lines)
        lines.forEachIndexed { i, raw ->
            if (fenced[i]) {
                if (buf.isNotEmpty()) { out += start to buf.toString().trim(); buf.setLength(0) }
                return@forEachIndexed
            }
            val t = raw.trim()
            val boundary = t.isEmpty() || t.startsWith("#") || t.startsWith("- ") || t.startsWith("* ") || t.startsWith("|") || Regex("^\\d{1,3}[.、]").containsMatchIn(t)
            if (boundary && buf.isNotEmpty()) {
                out += start to buf.toString().trim()
                buf.setLength(0)
            }
            if (t.isNotEmpty()) {
                if (buf.isEmpty()) start = i
                buf.append(t).append('\n')
            }
        }
        if (buf.isNotEmpty()) out += start to buf.toString().trim()
        return out
    }

    private fun snippet(p: String, words: List<String>): String {
        val t = p.trim()
        if (t.length <= SNIPPET) return t
        val at = words.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - SNIPPET / 3).coerceAtLeast(0)
        return (if (from > 0) "…" else "") + t.substring(from, minOf(t.length, from + SNIPPET)) + if (from + SNIPPET < t.length) "…" else ""
    }

    private fun readChapter(which: String, offset: Int): String {
        val key = which.trim()
        val n = key.toIntOrNull() ?: CheckRules.cn(key) ?: ChapterRefs.number(key) ?: ChapterRefs.number("第${key}章")
        val hit = chapters.firstOrNull { (c, _) -> c.title == key }
            ?: n?.let { num -> chapters.firstOrNull { (c, _) -> ChapterRefs.number(c.title) == num }
                ?: if (chapterNumbers == null) chapters.getOrNull(num - 1) else chapters.firstOrNull { (c, _) -> chapterNumbers[c.id] == num } }
            ?: chapters.firstOrNull { (c, _) -> key.isNotEmpty() && key in c.title }
            ?: return "没有这一章：$which。已写的章：" + chapters.joinToString("、") { it.first.title }.take(2_000)
        val text = hit.second()
        lookups += "读正文 ${hit.first.title}" + if (offset > 0) "（从第 $offset 字起）" else ""
        if (text.isBlank()) return "${hit.first.title} 还没有正文。"
        return page(text, offset, PAGE) + "\n[来源 ${ReviewEvidence.ref("C", hit.first.id, text)}]"
    }

    companion object {
        const val READ_DOC = "read_doc"
        const val SEARCH = "search"
        const val READ_CHAPTER = "read_chapter"

        /** 一次读多少字（长文档分几次读）。 */
        const val PAGE = 12_000

        /** 一次请求最多查到多少字。 */
        const val TOTAL = 60_000

        private const val MAX_HITS = 12
        private const val SNIPPET = 300

        private fun schema(vararg props: Triple<String, String, String>, required: List<String>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (name, type, desc) -> put(name, JSONObject().put("type", type).put("description", desc)) } })
            .put("required", JSONArray(required))

        val TOOLS = listOf(
            AiTool(
                READ_DOC,
                "读设定包里的一份文档，或其中一节（小节名见目录）。长文档分页，回复末尾会说怎么接着读。",
                schema(
                    Triple("title", "string", "文档名，如“第一卷细纲”"),
                    Triple("section", "string", "只读这一节（小节标题或其中几个字），不填读整份"),
                    Triple("offset", "integer", "从第几个字接着读，默认 0"),
                    required = listOf("title"),
                ),
            ),
            AiTool(
                SEARCH,
                "按关键词搜整个设定包和已写的正文（人名、地名、物件、数字、事件），返回出处和上下文。几个词用空格隔开。",
                schema(Triple("query", "string", "关键词，如“老周 渡口”"), required = listOf("query")),
            ),
            AiTool(
                READ_CHAPTER,
                "读已写的某一章正文。",
                schema(
                    Triple("chapter", "string", "章号（如 34 或“三十四”）或章名"),
                    Triple("offset", "integer", "从第几个字接着读，默认 0"),
                    required = listOf("chapter"),
                ),
            ),
        )
    }
}

/**
 * 写正文用的设定包：不整份放知识库（那是给审稿、整理用的），只放作者写给 AI 的说明和一张能查的资料清单；
 * 本章用得上的资料另外按章挑（[brief] 的 `prose`），别的让模型需要时用工具查。
 * 都不带“[来源 …]”编号：编号是审读核对出处用的，写正文时只会让模型以为自己在填核对表。
 */
object ProsePack {
    /**
     * 作者写给 AI 的说明（工作说明、这一阶段的提示词：创作用正文创作提示词，修订用章节修订提示词），去掉整章字数。没有为空。
     */
    fun instructions(docs: List<Pair<PackDoc, String>>, phase: WritingPhase = WritingPhase.CREATE): String =
        ProjectSources.forPhase(docs, phase).filter { it.first.kind == PackKind.INSTRUCTION && it.second.isNotBlank() }
            .sortedWith(compareBy({ it.first.priority }, { it.first.title }))
            .mapNotNull { (d, t) -> ProseLengths.strip(t).trim().takeIf { it.isNotEmpty() }?.let { "【${d.title}】\n$it" } }
            .joinToString("\n\n").let(::tidy)

    /** 去掉和“【文档名】”重复的一级标题（“【写作规范】”下面紧跟的“# 写作规范”）。 */
    fun tidy(text: String): String = text.replace(repeatedTitle, "【$1】\n")

    private val repeatedTitle = Regex("【([^】\n]+)】\n#\\s*\\1\\s*\n")

    /** 能用工具查的资料（文档名），工作说明和检查规则不列（已经给了，或只给本机用）。 */
    fun catalog(docs: List<Pair<PackDoc, String>>, phase: WritingPhase = WritingPhase.CREATE): List<String> =
        ProjectSources.forPhase(docs, phase)
            .filter { it.first.kind != PackKind.INSTRUCTION && it.first.kind != PackKind.RULES && it.second.isNotBlank() }
            .sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
            .map { it.first.title }.distinct()
}

/**
 * 写正文时，篇幅只在最后的任务卡里说一次。作者的说明、写作规范、检查规则、细纲里写的整章字数（“每章3000-4000字”
 * “字数：3000-4000”），交给模型前去掉：同一个请求里两个篇幅数字，模型要么写过界，要么注水。
 * 本机检查照旧按检查规则数字数，这里只改发给模型的文字。
 */
object ProseLengths {
    private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万]+"
    private val amount = Regex("($NUM)\\s*(?:[—－–\\-~～至到]\\s*($NUM)\\s*)?(?:个)?(?:汉)?字")
    private val lead = Regex("^\\s*(?:[-*+•]\\s+|\\d{1,3}[.、)）]\\s*)?")
    private val clause = Regex("[^。；;！？!?\\n]+[。；;！？!?]*")

    /** 这一句写的是整章（或更大）的字数：数到一千字以上的才算，“开头三百字内”“12 字以内”不算。 */
    fun chapterScale(text: String): Boolean = amount.findAll(text).any { m ->
        (CheckRules.cn(m.groupValues[1].replace('〇', '零')) ?: 0) >= 1_000
    }

    fun strip(text: String): String = text.lines().mapNotNull { line ->
        if (CheckRules.parse(line).rules.any { it is CheckRule.Length }) return@mapNotNull null
        val head = lead.find(line)?.value.orEmpty()
        val pieces = clause.findAll(line.substring(head.length)).map { it.value }.toList()
        val kept = pieces.filterNot(::chapterScale)
        when {
            kept.size == pieces.size -> line
            kept.isEmpty() -> null
            else -> head + kept.joinToString("").trimStart()
        }
    }.joinToString("\n")
}

/** 这部作品给写作 AI 的设定包（[budget] 是知识库最多放多少字）。 */
fun WorkStore.aiPack(workId: String, budget: Int, phase: WritingPhase? = null): AiPack =
    PackKnowledge.build(packSources(workId).raw, budget, phase)

/** AI 查设定包和正文用的工具。[upTo] 不为空时只能读到这一章为止（防剧透：后面的章不给看）。 */
fun WorkStore.packTools(workId: String, upTo: String? = null, phase: WritingPhase? = null, confirmedOnly: Boolean = false): PackTools {
    val allChapters = get(workId)?.chapters.orEmpty()
    val chapters = allChapters.let { all ->
        val i = upTo?.let { id -> all.indexOfFirst { it.id == id } } ?: -1
        when {
            i >= 0 -> all.take(i + 1)
            upTo != null && confirmedOnly -> emptyList()
            else -> all
        }
    }.filter { !confirmedOnly || it.status == ChapterStatus.FINAL }
    val docs = packSources(workId).let { sources -> phase?.let { sources.forPhase(it) } ?: sources.raw }
    val numbers = if (confirmedOnly) allChapters.mapIndexed { i, c -> c.id to (ChapterRefs.number(c.title) ?: i + 1) }.toMap() else null
    return PackTools(docs, chapters.map { c -> c to { text(workId, c.id) } }, numbers)
}

/**
 * 写某一章时随请求带的（每次不一样的部分，放在设定包后面）：这一章是第几章、细纲里写这一章的原文、出场人物、
 * 前情、故事账本到这一章为止的快照、伏笔、到这一章还不该知道的事、只有作者知道的，以及已写的章目录（用 read_chapter 读）。
 * [upTo] 为真时章目录只列到这一章为止（和只能读到这一章的查阅工具一致，防剧透）。
 * [noAhead] 为真时本章资料也不带后文（拟细纲用）：后面章节的细纲、全书结局、“只有作者知道的”、察觉表里后面才发生的都不给，
 * 只剩本章和以前的，加上设定包里写这一章的原文（见 [brief] 的 `prose`）。
 */
fun WorkStore.chapterHints(workId: String, chapterId: String, text: String, upTo: Boolean = false, noAhead: Boolean = false): String {
    val work = get(workId) ?: return ""
    val b = brief(workId, chapterId, text, limit = 4_000, prose = noAhead)
    val entries = outlineEntries(workId)
    val number = chapterNo(work, chapterId)
    val chapter = work.chapter(chapterId)
    val own = chapterOutlineEntries(work, chapterId, entries)
    val out = StringBuilder()
    out.append("【这一章】").append(chapter?.title.orEmpty())
    if (number != null) out.append("（按设定算第 ").append(number).append(" 章")
    b?.let { out.append("，第 ").append(it.volume).append(" 卷") }
    if (number != null) out.append("）")
    out.append('\n')
    if (own.isNotEmpty()) {
        out.append("\n【设定包里写这一章的原文】\n")
        own.forEach { e -> out.append("《").append(e.doc).append("》").append(if (e.single) "第 ${e.from} 章" else "第 ${e.from}—${e.to} 章").append(e.title.let { if (it.isNotEmpty()) "「$it」" else "" }).append("：\n").append(e.text.trim()).append('\n') }
    }
    if (b != null) {
        if (b.present.isNotEmpty()) out.append("\n【这一章出场或提到的人物】").append(b.present.joinToString("、")).append('\n')
        val past = b.items.filter { it.group == "前情" }
        if (past.isNotEmpty()) {
            out.append("\n【前情】\n")
            past.sortedBy { ChapterBriefs.pastOrder(it) }.forEach { out.append("- ").append(it.title).append("：").append(it.text).append('\n') }
        }
        // 账本是最新进展：放在前情之后、伏笔和察觉之前。
        b.ledgerText?.let { out.append('\n').append(it).append('\n') }
        if (b.due.isNotEmpty()) out.append("\n【计划在这一章回收的伏笔】\n").append(b.due.joinToString("\n") { "- " + it.what + ChapterBrief.foreshadowWhen(it) }).append('\n')
        val waiting = b.open - b.due.toSet()
        if (waiting.isNotEmpty()) out.append("\n【已经埋下、还没回收的伏笔（别忘了，也别提前揭开）】\n").append(waiting.joinToString("\n") { "- " + it.what + ChapterBrief.foreshadowWhen(it) }).append('\n')
        if (b.notYet.isNotEmpty()) out.append("\n【按察觉表，到这一章为止还没发生、人物还不知道的事（不要让人物提前知道或说破）】\n").append(b.notYet.joinToString("\n") { "- $it" }).append('\n')
        if (b.hidden.isNotEmpty()) out.append("\n【只有作者知道的（结局、后文走向、对手底牌）：只用来把握方向，不能写进正文】\n").append(b.hidden.joinToString("\n") { "- $it" }).append('\n')
    }
    out.append("\n【已写的章（用 read_chapter 读全文）】\n")
    val listed = if (upTo) work.chapters.indexOfFirst { it.id == chapterId }.let { i -> if (i >= 0) work.chapters.take(i + 1) else work.chapters } else work.chapters
    out.append(listed.joinToString("；") { c -> c.title + "（" + c.words + " 字" + (if (c.id == chapterId) "，就是这一章" else "") + "）" }.take(4_000)).append('\n')
    return out.toString().trim()
}
