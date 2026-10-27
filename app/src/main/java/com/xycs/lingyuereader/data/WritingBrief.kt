package com.xycs.lingyuereader.data

/**
 * 一个人物：名字、别名（字、小名……）、设定文档里写到他的几段（表格行、列表项、小节）。
 * [later] 是表格里“结局”“下场”“怎么变”这类写后文走向的格子：只给 AI 把握方向，不能写进正文。
 */
data class CharacterEntry(val name: String, val aliases: List<String> = emptyList(), val notes: List<String> = emptyList(), val later: List<String> = emptyList()) {
    val names: List<String> get() = listOf(name) + aliases
}

/**
 * 察觉表里的一条：谁、什么事、从第几章（或第几卷）起。[secret] 是“××这一面”“真相”这类视角人物不知道的格子。
 * 下面几项导入故事账本、和账本比对时用：[what] 只是内容那几格（不带谁、章号、消息来源、视角人物不知道的格子），
 * [holders] 是写明知道的人（小节标题里的人名、“人物”“谁”那一列），[via] 是消息从哪来，
 * [hiddenWhat] 是视角人物不知道的格子（不带前缀），[quote] 是原文那一行、那一项。
 */
data class KnowledgeItem(
    val text: String,
    val who: Set<String>,
    val chapter: Int? = null,
    val volume: Int? = null,
    val secret: String = "",
    val what: String = "",
    val holders: Set<String> = emptySet(),
    val via: String = "",
    val hiddenWhat: String = "",
    val quote: String = "",
)

/**
 * 一条伏笔：什么、第几章埋下、计划第几章回收、回收了没有。[doc]、[line] 记它在哪份文档的哪一行（标为已回收时改那一行）。
 * [closed] 是状态里写了的“第几章回收的”（“已回收（第21章）”）；没写为空。
 */
data class Foreshadow(
    val what: String,
    val planted: Int? = null,
    val payoff: Int? = null,
    val done: Boolean = false,
    val doc: String = "",
    val line: Int = -1,
    val statusColumn: Int = -1,
    val closed: Int? = null,
)

/** 前面一章的摘要（细纲卡里写完以后的那一栏）。 */
data class ChapterSummary(val number: Int, val title: String, val summary: String)

/** 本章资料里的一项：哪一类、标题、内容、为什么带上它。 */
data class BriefItem(val group: String, val title: String, val text: String, val reason: String)

/**
 * 写某一章时要带给 AI 的资料：常用文档、出场人物的人物卡、故事账本到这一章为止的快照、到这一章为止知道的事、
 * 到这一章还不该知道的事、没回收的伏笔、提到这一章的细纲和设定。
 */
data class ChapterBrief(
    val chapter: Int,
    val volume: Int,
    val present: List<String>,
    val items: List<BriefItem>,
    val notYet: List<String>,
    val open: List<Foreshadow>,
    val due: List<Foreshadow>,
    /** 作者知道、正文里还不能写出的：出场人物的结局和后文走向、对手的底牌。 */
    val hidden: List<String> = emptyList(),
    /** 故事账本到这一章为止的快照（只有作者知道的已并进 [hidden]，这里不再有）；账本是空的为 null。 */
    val ledger: LedgerSnapshot? = null,
    /** 账本快照最多拼多少字（从本章资料的字数里扣）。 */
    val ledgerLimit: Int = 0,
    /** 账本快照放在 [items] 的第几项前面（前情之后）。 */
    val ledgerAt: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty() && notYet.isEmpty() && open.isEmpty() && due.isEmpty() && hidden.isEmpty() && (ledger == null || ledger.isEmpty)

    /** 账本快照拼成的文字；没有为 null。 */
    val ledgerText: String? get() = ledger?.render(ledgerLimit)

    /** 拼成给 AI 的文字。 */
    fun render(): String? {
        if (isEmpty) return null
        val out = StringBuilder("下面是写第 $chapter 章（第 $volume 卷）要用到的设定资料，按优先级排列；资料之间有冲突时，以前面的为准。\n")
        if (present.isNotEmpty()) out.append("这一章出场或提到的人物：").append(present.joinToString("、")).append("。\n")
        // 账本是最新进展：放在前情之后、察觉和伏笔之前。
        val ledgerText = ledgerText
        items.forEachIndexed { i, it ->
            if (i == ledgerAt && ledgerText != null) out.append('\n').append(ledgerText).append('\n')
            out.append("\n【").append(it.group).append("·").append(it.title).append("】\n").append(it.text).append('\n')
        }
        if (ledgerAt >= items.size && ledgerText != null) out.append('\n').append(ledgerText).append('\n')
        if (due.isNotEmpty()) {
            out.append("\n【计划在这一章回收的伏笔】\n")
            due.forEach { out.append("- ").append(it.what).append(foreshadowWhen(it)).append('\n') }
        }
        val waiting = open - due.toSet()
        if (waiting.isNotEmpty()) {
            out.append("\n【已经埋下、还没回收的伏笔（别忘了，也别提前揭开）】\n")
            waiting.forEach { out.append("- ").append(it.what).append(foreshadowWhen(it)).append('\n') }
        }
        if (notYet.isNotEmpty()) {
            out.append("\n【到这一章为止还没发生、人物还不知道的事（不要让人物提前知道或说破）】\n")
            notYet.forEach { out.append("- ").append(it).append('\n') }
        }
        if (hidden.isNotEmpty()) {
            out.append("\n【只有作者知道的（人物的结局和后文走向、对手的底牌）：只用来把握方向，不能写进正文，不能让视角人物知道，旁白也不能说破】\n")
            hidden.forEach { out.append("- ").append(it).append('\n') }
        }
        return out.toString().trim()
    }

    companion object {
        fun foreshadowWhen(f: Foreshadow): String = listOfNotNull(f.planted?.let { "第${it}章埋下" }, f.payoff?.let { "计划第${it}章回收" })
            .joinToString("，").let { if (it.isEmpty()) "" else "（$it）" }
    }
}

/** 从设定包的文档里认出人物、察觉、伏笔，再按章挑资料。 */
object ChapterBriefs {
    private fun fencedLines(lines: List<String>): BooleanArray = MarkdownFences.mask(lines)
    const val LIMIT = 12_000

    // ---------- 人物 ----------

    /** 列表、加粗里常见的小标签，不是人名。 */
    private val labels = setOf(
        "人物", "主角", "配角", "姓名", "名字", "出身", "性格", "长处", "短处", "外貌", "经历", "关系", "动机", "目标", "弱点", "口头禅",
        "说话", "结局", "下场", "背景", "身份", "定位", "年龄", "备注", "注", "说明", "例", "例如", "想要", "害怕", "标志物", "怎么变",
        "怎么说话", "总规则", "原则", "要点", "注意", "补记", "附", "附注", "处境", "事业", "后续", "作用", "用法", "改动", "变更",
        "人物档案", "人物设定", "人物关系", "核心人物", "主要人物", "次要人物", "角色档案", "角色设定", "角色关系",
        "察觉表", "知情表", "人物知情", "知情状态", "故事账本",
    )
    private val nameChars = Regex("^[\\p{IsHan}·]{1,6}$")
    private val aliasPattern = Regex("(?:^|[，、（(\\s；;])(?:字|小名|乳名|又名|别名|外号|人称|绰号)[：:]?[“「]?(\\p{IsHan}{2,3})")

    /** 写明的称呼：“称呼：顾、顾兄、老顾”（一个字的也算：作者写明了）。 */
    private val calledPattern = Regex("(?:称呼|别称|又称|简称|旁人叫|大家叫)[：:]\\s*([^。\\n|]+)")

    /** 表格里写称呼、别名的列。 */
    private val aliasColumn = Regex("称呼|别称|别名|又名|外号|小名|字号")

    private fun splitNames(cell: String): List<String> =
        cell.split('、', '，', ',', '／', '/', '；', ';', ' ', '　').map { it.trim().trim('“', '”', '「', '」', '"') }
            .filter { it.isNotEmpty() && it.length <= 4 && it.all { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN || ch == '·' } }

    /** “林月（原创，药铺女儿）”“**沈默**，字子静”“1. 老周”→ 名字；不像名字就是 null。 */
    internal fun cleanName(raw: String): String? {
        var s = raw.replace("*", "").trim()
        s = s.replace(Regex("^\\d+[.、)）]\\s*"), "")
        s = s.split('（', '(', '：', ':', '，', ',', ' ', '　').first().trim()
        return s.takeIf { nameChars.matches(it) && it !in labels && !it.startsWith("第") && it.length >= 2 }
    }

    private fun aliases(text: String, name: String) =
        (aliasPattern.findAll(text).map { it.groupValues[1] } + calledPattern.findAll(text).flatMap { splitNames(it.groupValues[1]) })
            .filter { it != name }.distinct().toList()

    /**
     * 人物文档里的人物：表格（表头有“人物”“角色”“姓名”的列，一行一人）、
     * 列表项和段落（“- **林月（原创）：** ……”“**林月**：……”，连同下面缩进的几行）、只写名字的小标题。
     * 同一个人写在几处的，合在一起。
     */
    fun characters(docs: List<Pair<String, String>>): List<CharacterEntry> {
        val found = LinkedHashMap<String, MutableList<String>>()
        val later = HashMap<String, MutableList<String>>()
        val called = HashMap<String, MutableList<String>>()
        fun add(name: String?, note: String) {
            if (name == null || note.isBlank()) return
            found.getOrPut(name) { mutableListOf() } += note.trim()
        }
        // 列表项里加粗的不一定是人名（“**出身：**”“**锋芒与越线：**”这类小标签）：带“与、的、及、和、之”的不算，
        // 名字后面带括号注（“林月（原创）”）或者在表格、标题里也出现过的一定算。
        val bullets = mutableListOf<Triple<String, String, Boolean>>()
        for ((documentTitle, text) in docs) {
            MdTable.parse(text).forEach { t ->
                val col = t.column("人物", "角色", "姓名", "名字")
                if (col < 0) return@forEach
                val future = t.header.indices.filter { it != col && futureColumn.containsMatchIn(t.header[it]) }.toSet()
                val aliasCols = t.header.indices.filter { it != col && aliasColumn.containsMatchIn(t.header[it]) }
                t.rows.forEach { row ->
                    fun cells(cols: List<Int>) = cols.filter { t.cell(row, it).isNotBlank() && t.cell(row, it) != "—" }.joinToString("；") { "${t.header[it]}：${t.cell(row, it)}" }
                    val name = cleanName(t.cell(row, col))
                    val note = cells(t.header.indices.filter { it != col && it !in future })
                    add(name, t.cell(row, col) + if (note.isEmpty()) "" else "——$note")
                    val ahead = cells(future.sorted())
                    if (name != null && ahead.isNotEmpty()) later.getOrPut(name) { mutableListOf() } += ahead
                    if (name != null) aliasCols.forEach { c -> called.getOrPut(name) { mutableListOf() } += splitNames(t.cell(row, c)) }
                }
            }
            val lines = text.lines()
            val fenced = fencedLines(lines)
            var i = 0
            while (i < lines.size) {
                if (fenced[i]) { i++; continue }
                val line = lines[i]
                val bold = Regex("^(?:[-*]\\s+)?\\*\\*(.+?)\\*\\*").find(line.trim())
                val top = !line.startsWith(" ") && !line.startsWith("\t")
                if (bold != null && top) {
                    val name = cleanName(bold.groupValues[1])
                    // 连同下面缩进的子项。
                    val block = StringBuilder(line.trim().removePrefix("- ").removePrefix("* "))
                    var j = i + 1
                    while (j < lines.size && !fenced[j] && (lines[j].startsWith(" ") || lines[j].startsWith("\t")) && lines[j].isNotBlank()) {
                        block.append('\n').append(lines[j].trim())
                        j++
                    }
                    if (name != null) {
                        val after = line.substringAfter("**$name", "")
                        val noted = bold.groupValues[1].contains('（') || bold.groupValues[1].contains('(') ||
                            Regex("^[^：:]{0,3}[（(]|^\\*\\*[，,]?(?:字|小名|乳名|又名|别名)").containsMatchIn(after)
                        bullets += Triple(name, block.toString(), noted)
                    }
                    i = j
                    continue
                }
                i++
            }
            // 封面和资料分类的一级标题（“# 长夜人物”“# 反派”）不是人物。
            val headingLines = text.removePrefix("\uFEFF").lines()
            val cover = PackCover.line(headingLines, fenced, documentTitle)
            PackSections.split(text).forEach { s ->
                val name = s.title.let { t -> cleanName(t)?.takeIf { t.replace(Regex("[（(].*"), "").trim().replace("*", "") == it } }
                val person = PackCover.mayBePerson(headingLines, fenced, s.line, cover)
                if (name != null && person && !s.text.trimStart().startsWith("|")) add(name, s.text)
            }
        }
        val confirmed = found.keys.toSet()
        bullets.forEach { (name, note, noted) -> if (noted || name in confirmed || !Regex("[与的及和之]").containsMatchIn(name)) add(name, note) }
        return found.map { (name, notes) ->
            val all = notes.distinct()
            val alias = (all.flatMap { aliases(it, name) } + called[name].orEmpty()).distinct().filter { a -> a != name && a !in found.keys }
            CharacterEntry(name, alias, all, later[name].orEmpty().distinct())
        }
    }

    /** 写后文走向的表头：结局、下场、怎么变、记忆止于、后续…… */
    private val futureColumn = Regex("结局|下场|怎么变|记忆止于|后续|终局|命运|归宿|死于|死因")

    /** 视角人物不知道的表头：“曹操这一面”“对方”“真相”“内心”“底牌”…… */
    private val secretColumn = Regex("这一面|对方|真相|内心|底牌|暗线|作者")

    /** 一段设定里写到后面章节的句子（提到的章号都在 [chapter] 之后）挑出来：返回（现在可用的，后文的）。 */
    internal fun splitByChapter(text: String, chapter: Int): Pair<String, String> {
        val now = StringBuilder()
        val later = StringBuilder()
        Regex("[^。；;\n]+[。；;]?\n?").findAll(text).forEach { m ->
            val refs = ChapterRefs.find(m.value)
            (if (refs.isNotEmpty() && refs.min() > chapter) later else now).append(m.value)
        }
        return now.toString().trim() to later.toString().trim()
    }

    /** 人物在这一章出现几次（名字和别名都算）。 */
    fun mentions(c: CharacterEntry, text: String): Int = c.names.sumOf { n -> count(text, n) }

    private fun count(text: String, word: String): Int {
        if (word.isEmpty()) return 0
        var n = 0
        var at = text.indexOf(word)
        while (at >= 0) {
            n++
            at = text.indexOf(word, at + word.length)
        }
        return n
    }

    // ---------- 察觉 ----------

    private val volumeRef = Regex("第([^卷第\\n]{1,8}?)卷")

    /** “四至五”“十四（二〇九年）”“一（第十三章，本轮新增）”→ 卷号（范围取开头）。 */
    internal fun volumeOf(cell: String): Int? {
        volumeRef.find(cell)?.let { m -> CheckRules.cn(m.groupValues[1].split('至', '-', '—', '、').first().trim())?.let { return it } }
        val head = cell.replace("*", "").split('（', '(').first().split('至', '-', '—', '~', '、').first().trim()
        return CheckRules.cn(head)
    }

    /** 察觉表里写消息来源的表头：“消息从哪来”“来源”“怎么得知”…… */
    private val viaColumn = Regex("从哪|来源|得知|途径|消息")

    /**
     * 察觉表里的条目：表格每行一条（“卷”或“章”那列定时间，小节标题里的人名、“人物”“谁”那一列和行里提到的人名算“谁”）；
     * 列表项里带章号或卷号的也算一条。
     */
    fun knowledge(docs: List<Pair<String, String>>, people: Collection<String>): List<KnowledgeItem> {
        val out = mutableListOf<KnowledgeItem>()
        fun names(text: String) = people.filter { it.length >= 2 && it in text }.toSet()
        for ((documentTitle, text) in docs) {
            val sections = PackSections.split(text, chunk = Int.MAX_VALUE)
            val tables = MdTable.parse(text)
            val lines = text.lines()
            val fenced = fencedLines(lines)
            tables.forEach { t ->
                val firstLine = t.lines.firstOrNull() ?: return@forEach
                val section = sections.lastOrNull { it.line <= firstLine }
                val coverTitle = documentTitle.replace(Regex("\\.(?:md|markdown|txt)$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[《》「」\\s]"), "")
                val owners = section?.path.orEmpty().filter {
                    it.replace(Regex("[《》「」\\s]"), "") != coverTitle && !PackCover.looksLikeCategory(it)
                }.mapNotNull { cleanName(it) }.toSet()
                val timeCol = t.column("章", "卷", "时间", "从")
                val secretCols = t.header.indices.filter { it != timeCol && secretColumn.containsMatchIn(t.header[it]) }.toSet()
                val whoCol = t.header.indices.firstOrNull { it != timeCol && it !in secretCols && ("人物" in t.header[it] || "谁" in t.header[it]) } ?: -1
                val viaCol = t.header.indices.firstOrNull { it != timeCol && it != whoCol && it !in secretCols && viaColumn.containsMatchIn(t.header[it]) } ?: -1
                t.rows.forEachIndexed { r, row ->
                    val cell = t.cell(row, timeCol)
                    fun filled(cols: List<Int>) = cols.filter { t.cell(row, it).isNotBlank() && t.cell(row, it) !in setOf("—", "无", "-") }
                    fun cells(cols: List<Int>) = filled(cols).joinToString("；") { "${t.header[it]}：${t.cell(row, it)}" }
                    // 只有一格的直接用那一格，几格的带上表头。
                    fun plain(cols: List<Int>) = filled(cols).let { f -> if (f.size == 1) t.cell(row, f[0]) else cells(f) }
                    val body = cells(t.header.indices.filter { it != timeCol && it !in secretCols })
                    val secret = cells(secretCols.sorted())
                    val chapter = ChapterRefs.find(cell).minOrNull()
                    val volume = if (t.header.getOrNull(timeCol)?.contains("卷") == true) volumeOf(cell) else volumeRef.find(cell)?.let { volumeOf(it.value) }
                    val label = listOfNotNull(volume?.let { "第${it}卷" }, chapter?.let { "第${it}章" }).joinToString("")
                    val head = (if (owners.isEmpty()) "" else owners.joinToString("、") + "，") + (if (label.isEmpty()) "" else "$label：")
                    val listed = t.cell(row, whoCol).split('、', '，', ',', '／', '/', '；', ';').mapNotNull { cleanName(it) }.toSet()
                    out += KnowledgeItem(
                        head + body, owners + listed + names(body), chapter, volume, if (secret.isEmpty()) "" else head + secret,
                        what = plain(t.header.indices.filter { it != timeCol && it != whoCol && it != viaCol && it !in secretCols }),
                        holders = owners + listed,
                        via = t.cell(row, viaCol).takeIf { it !in setOf("—", "无", "-") }.orEmpty(),
                        hiddenWhat = plain(secretCols.sorted()),
                        quote = lines.getOrNull(t.lines[r]).orEmpty().trim(),
                    )
                }
            }
            val inTable = tables.flatMap { it.lines }.toSet()
            lines.forEachIndexed { i, raw ->
                val line = raw.trim()
                if (fenced[i] || i in inTable || line.startsWith("|") || line.startsWith("#") || !Regex("^(?:[-*]|\\d+[.、])\\s*").containsMatchIn(line)) return@forEachIndexed
                val chapter = ChapterRefs.find(line).minOrNull()
                val volume = volumeRef.find(line)?.let { volumeOf(it.value) }
                if (chapter == null && volume == null) return@forEachIndexed
                val body = line.replace(Regex("^(?:[-*]|\\d+[.、])\\s*"), "").replace("**", "")
                out += KnowledgeItem(body, names(body), chapter, volume, what = body, quote = line)
            }
        }
        return out
    }

    // ---------- 伏笔 ----------

    private val doneWords = Regex("已回收|已兑现|已揭|已完成|✓|✔")

    /** “已回收（第21章）”里写的第几章回收的：回收字样后面的第一个章号；没写为空。 */
    private fun closedAt(text: String): Int? = doneWords.find(text)?.let { m -> ChapterRefs.find(text.substring(m.range.last + 1)).firstOrNull() }

    /** 伏笔表：表格（伏笔、埋下、回收、状态几列）或列表项（“- 玉佩：第3章埋，第20章回收”）。 */
    fun foreshadows(docs: List<Pair<String, String>>): List<Foreshadow> {
        val out = mutableListOf<Foreshadow>()
        for ((doc, text) in docs) {
            val tables = MdTable.parse(text)
            tables.forEach { t ->
                val whatCol = t.column("伏笔", "线索", "物件", "内容", "什么").takeIf { it >= 0 } ?: 0
                val plantCol = t.column("埋", "铺", "出现")
                val payCol = t.column("回收", "兑现", "揭", "收")
                val stateCol = t.column("状态", "进度", "回收了")
                if (plantCol < 0 && payCol < 0) return@forEach
                t.rows.forEachIndexed { r, row ->
                    val what = t.cell(row, whatCol).replace("**", "")
                    if (what.isBlank()) return@forEachIndexed
                    out += Foreshadow(
                        what,
                        ChapterRefs.find(t.cell(row, plantCol)).minOrNull() ?: t.cell(row, plantCol).toIntOrNull(),
                        ChapterRefs.find(t.cell(row, payCol)).minOrNull() ?: t.cell(row, payCol).toIntOrNull(),
                        doneWords.containsMatchIn(t.cell(row, stateCol)) || doneWords.containsMatchIn(t.cell(row, payCol)),
                        doc,
                        t.lines[r],
                        stateCol,
                        closedAt(t.cell(row, stateCol)) ?: closedAt(t.cell(row, payCol)),
                    )
                }
            }
            val inTable = tables.flatMap { it.lines }.toSet()
            val lines = text.lines()
            val fenced = fencedLines(lines)
            lines.forEachIndexed { i, raw ->
                val line = raw.trim()
                if (fenced[i] || i in inTable || !Regex("^[-*]\\s+").containsMatchIn(line)) return@forEachIndexed
                val refs = ChapterRefs.find(line)
                if (refs.isEmpty()) return@forEachIndexed
                val body = line.replace(Regex("^[-*]\\s+"), "").replace("**", "")
                val what = body.split('：', ':', '（', '(').first().trim().ifEmpty { body }
                out += Foreshadow(what, refs.first(), refs.getOrNull(1), doneWords.containsMatchIn(line), doc, i, closed = closedAt(line))
            }
        }
        return out
    }

    /** 标为已回收：表格改“状态”那格（没有状态列就在表头、分隔行和这一行最后补一列“状态”），列表项在行尾加“（已回收）”。 */
    fun markDone(text: String, f: Foreshadow, chapter: Int?): String {
        val lines = text.lines()
        if (f.line !in lines.indices) return text
        val mark = "已回收" + (chapter?.let { "（第${it}章）" } ?: "")
        val table = MdTable.parse(text).firstOrNull { f.line in it.lines }
        return if (table != null) {
            if (f.statusColumn >= 0) return MdTable.setCell(text, f.line, f.statusColumn, mark)
            // 只补这一行的话，多出的那格在表头之外，读的时候读不到：表头和分隔行也补上。
            val col = table.header.size
            val head = table.lines.first() - 2
            val withHeader = MdTable.setCell(MdTable.setCell(text, head, col, "状态"), head + 1, col, "---")
            MdTable.setCell(withHeader, f.line, col, mark)
        } else {
            lines.toMutableList().also { it[f.line] = it[f.line].trimEnd() + "（$mark）" }.joinToString("\n")
        }
    }

    // ---------- 按章挑资料 ----------

    private val structured = setOf(PackKind.CHARACTER, PackKind.AWARENESS, PackKind.FORESHADOW, PackKind.RULES)

    /** 写作规范、称谓与语气另外节选，不进按块挑的资料。 */
    private val styleKinds = setOf(PackKind.STYLE, PackKind.VOICE)

    /** 人物卡最多带几个（出场多的在前，其余只列名字）。 */
    const val MAX_CHARACTERS = 8

    /** 常用文档在这个字数以内的整份带上；更长的切块，和别的文档一起按这一章挑。 */
    const val SMALL_DOC = 2_500

    /** 按块挑的资料，分数到这个才带上。 */
    private const val MIN_SCORE = 2.5

    /**
     * 挑第 [chapter] 章（第 [volume] 卷）要带的资料。[text] 是这一章的正文，[card] 是细纲卡的文字（出场人物也从这里认），
     * [query] 是拿来比用词的（细纲卡、前一两章的细纲或摘要、正文结尾），[skip] 是细纲卡里已经有的段落（不再重复带）。
     * 先放短的常用文档，再放写作规范节选（[style] 为真时）、前情、故事账本的快照（[ledger] 不为空时）、
     * 出场人物的人物卡、到这一章为止知道的事和伏笔，最后把其余文档切块，按写没写到这一章、写没写到出场人物、
     * 和细纲用词重合多少打分，分高的先放。察觉表、伏笔表里和账本重复的条目不再列（账本优先）。
     */
    fun build(
        docs: List<Pair<PackDoc, String>>,
        text: String,
        card: String,
        chapter: Int,
        volume: Int,
        limit: Int = LIMIT,
        summaries: List<ChapterSummary> = emptyList(),
        query: String = card,
        skip: List<String> = emptyList(),
        style: Boolean = false,
        ledger: List<LedgerEntry> = emptyList(),
    ): ChapterBrief {
        val items = mutableListOf<BriefItem>()
        val hidden = mutableListOf<String>()
        var left = limit
        val sorted = docs.filter { it.second.isNotBlank() }.sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
        // 人物从人物档案认；称谓与语气文档里写到的人（怎么说话、怎么称呼）也并进来。
        val characters = characters(sorted.filter { it.first.kind == PackKind.CHARACTER || it.first.kind == PackKind.VOICE }.map { it.first.title to it.second })
        val scope = "$card\n$text"
        val present = characters.map { it to mentions(it, scope) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        val presentNames = present.map { it.name }

        // 一、短的常用文档整份带上；长的切块和别的文档一起挑（只截开头多半是说明和原则，用不上）。
        // 这一章还认不出人物时，短的人物文档也整份带。
        var budget = limit / 4
        val whole = mutableSetOf<String>()
        sorted.filter { (d, t) ->
            d.always && (d.kind !in structured || (d.kind == PackKind.CHARACTER && present.isEmpty())) && d.kind !in styleKinds && t.trim().length <= SMALL_DOC
        }.forEach { (d, t) ->
            val body = t.trim()
            if (body.length > budget) return@forEach
            items += BriefItem(d.kind.label, d.title, body, "常用（优先级 ${d.priority}）")
            whole += d.id
            budget -= body.length
            left -= body.length
        }
        // 写作规范、称谓与语气：节选（写到出场人物的小节、后补的小节优先）。写正文、写法关另外带全，这里不重复。
        if (style) {
            val rules = styleFor(sorted.filter { it.first.kind in styleKinds }, presentNames, minOf(STYLE_IN_BRIEF, left / 5))
            if (rules.isNotBlank()) {
                items += BriefItem("写作规范", "节选", rules, "写作规范、称谓与语气")
                left -= rules.length
            }
        }

        // 二、前情：最近几章的摘要，更早的章里写到出场人物的也带（离这一章近的在前）。
        val before = summaries.filter { it.number < chapter && it.summary.isNotBlank() }.sortedByDescending { it.number }
        val recent = before.take(RECENT_SUMMARIES)
        val older = before.drop(RECENT_SUMMARIES).filter { s -> presentNames.any { it in s.summary } }
        var pastBudget = left / 4
        (recent + older).forEach { s ->
            if (pastBudget <= 100) return@forEach
            val body = cut(s.summary.trim(), minOf(pastBudget, 600))
            items += BriefItem("前情", "第 ${s.number} 章 ${s.title}".trim(), body, if (s in recent) "最近几章" else "写到" + presentNames.filter { it in s.summary }.joinToString("、"))
            pastBudget -= body.length
            left -= body.length
        }

        // 故事账本：到这一章为止的快照，是最新进展，紧跟前情。最多占剩下的三分之一、不超过 LEDGER_LIMIT。
        // 只有作者知道的条目并进 hidden，放在最前面（账本优先），快照里不再重复。
        val snapshot = if (ledger.isEmpty()) null else StoryLedger.snapshot(ledger, chapter, volume, present.flatMap { it.names })
        val ledgerAt = items.size
        val ledgerLimit = minOf(LEDGER_LIMIT, left / 3)
        val shown = snapshot?.copy(secrets = emptyList())?.takeIf { !it.isEmpty }
        val ledgerText = shown?.render(ledgerLimit)
        ledgerText?.let { left -= it.length }
        snapshot?.secrets?.forEach { e -> hidden += (if (e.subject.isNotBlank()) e.subject + "——" else "") + cut(e.text, 300) }
        // 只用本章实际展示的快照替代察觉表。快照被预算截短时，保守保留察觉表，避免删掉恰好没列出的那条。
        val completeSnapshot = shown?.takeIf { (it.render(Int.MAX_VALUE)?.length ?: 0) <= ledgerLimit }
        val ledgerKnow = completeSnapshot?.known.orEmpty().filter { ledgerText?.contains(it.text) == true }
        val ledgerNotYet = completeSnapshot?.notYet.orEmpty().filter { it.until == null || it.until > chapter }.filter { ledgerText?.contains(it.text) == true }
        val ledgerSecret = ledger.filter { it.secret }.map { it.text }
        val ledgerPromise = ledger.filter { it.kind == LedgerKind.PROMISE }.map { it.text }
        fun hide(h: String) {
            if (ledgerSecret.none { same(it, h) }) hidden += h
        }

        // 三、出场人物的人物卡：出现次数多的在前。写到后面章节的句子、“结局”“怎么变”这类格子另列为只有作者知道的。
        var charBudget = left / 3
        present.take(MAX_CHARACTERS).forEach { c ->
            if (charBudget <= 100) return@forEach
            val (now, ahead) = splitByChapter(c.notes.joinToString("\n"), chapter)
            val body = cut(now, minOf(charBudget, 800))
            items += BriefItem("人物", c.name, body, "本章出场" + if (c.aliases.isEmpty()) "" else "（也叫${c.aliases.joinToString("、")}）")
            charBudget -= body.length
            left -= body.length
            (c.later + listOf(ahead)).filter { it.isNotBlank() }.forEach { hide(c.name + "——" + cut(it, 300)) }
        }

        // 三、察觉：出场人物到这一章为止知道的（离这一章近的在前），以及还不该知道的。
        val allNames = characters.flatMap { it.names }
        val canonical = characters.flatMap { c -> c.names.map { it to c.name } }.toMap()
        fun people(names: Collection<String>): Set<String> = names
            .map { it.replace(Regex("[《》「」“”\\s*]"), "") }
            .filter { it.isNotBlank() && (canonical.isEmpty() || it in canonical) }
            .map { canonical[it] ?: it }.toSet()
        fun holders(k: KnowledgeItem): Set<String> = people(k.holders).ifEmpty { people(k.who) }
        fun subject(e: LedgerEntry): Set<String> = people(e.subject.split('、', '，', ',', '／', '/', '；', ';'))
        val knowledge = knowledge(sorted.filter { it.first.kind == PackKind.AWARENESS }.map { it.first.title to it.second }, allNames)
        fun reached(k: KnowledgeItem) = k.chapter?.let { it <= chapter } ?: k.volume?.let { it <= volume } ?: true
        val presentAll = present.flatMap { it.names }.toSet()
        // 同一事实由不同人物知道，不能互相替代；一行写了多人时，只有每个人都已在快照中才整行省去。
        // 已知与尚未知分开匹配：未生效或已经失效的账本记录不能吞掉人物当前的知情。
        fun inLedger(k: KnowledgeItem): Boolean {
            val candidates = if (reached(k)) ledgerKnow else ledgerNotYet.filter { it.from == k.chapter && (it.from != null || it.volume == k.volume) }
            val matching = candidates.filter { same(it.text, k.what.ifBlank { k.text }) || same(it.text, k.text) }
            val who = holders(k)
            return if (who.isEmpty()) matching.any { it.subject.isBlank() }
            else who.all { name -> matching.any { name in subject(it) } }
        }
        val relevant = knowledge.filter { k -> k.who.isEmpty() || k.who.any { it in presentAll } }
        val reachedAll = relevant.filter(::reached).sortedByDescending { it.chapter ?: ((it.volume ?: 0) * 1000) }
        reachedAll.filter { it.secret.isNotBlank() && ledgerSecret.none { s -> same(s, it.hiddenWhat.ifBlank { it.secret }) } }
            .take(8).forEach { hide(cut(it.secret, 200)) }
        val known = reachedAll.filterNot(::inLedger)
        var knowBudget = left / 3
        val knownText = StringBuilder()
        known.forEach { k ->
            val line = "- " + cut(k.text, 300) + "\n"
            if (knowBudget - line.length < 0) return@forEach
            knownText.append(line)
            knowBudget -= line.length
        }
        if (knownText.isNotEmpty()) {
            items += BriefItem("察觉", "到第 $chapter 章为止知道的", knownText.toString().trim(), "出场人物的察觉表")
            left -= knownText.length
        }
        val notYet = relevant.filterNot(::reached).filterNot(::inLedger)
            .sortedBy { it.chapter ?: ((it.volume ?: 0) * 1000) }
            .take(12)
            .map { cut(it.text, 160) }
        left -= notYet.sumOf { it.length }

        // 四、伏笔：埋下了还没回收的；计划这一章回收的另列。
        val foreshadows = foreshadows(sorted.filter { it.first.kind == PackKind.FORESHADOW }.map { it.first.title to it.second })
            .filter { f -> ledgerPromise.none { same(it, f.what) } }
        val open = foreshadows.filter { !it.done && (it.planted ?: 0) <= chapter }
        val due = open.filter { it.payoff == chapter }
        left -= open.sumOf { it.what.length + 20 }

        // 五、其余文档切块打分：写到这一章（单独写到的最算数，大范围几乎不算）、写到出场人物、和细纲卡用词重合。
        val skipKeys = skip.map { it.replace(Regex("\\s"), "").take(24) }.filter { it.length >= 4 }
        // 账本导出的那份资料和上面的快照重复，账本不空时不再挑。
        val pool = sorted.filter { (d, _) -> d.id !in whole && d.kind !in structured && d.kind !in styleKinds && (ledger.isEmpty() || d.title != LEDGER_EXPORT_TITLE) }.flatMap { (d, t) ->
            PackUnits.split(t, documentTitle = d.title).filter { u -> skipKeys.none { it in u.text.replace(Regex("\\s"), "") } }.map { d to it }
        }
        val grams = pool.map { (_, u) -> PackRanking.bigrams(u.text) }
        val lexicon = PackRanking.Lexicon(grams)
        val asked = PackRanking.bigrams(query)
        val ranked = pool.mapIndexedNotNull { i, (d, u) ->
            val head = u.path.joinToString(" ")
            val refs = PackRanking.refs(head + "\n" + u.text)
            val (chapterScore, chapterWhy) = PackRanking.chapterScore(refs, chapter)
            val names = presentNames.filter { it in u.text }.take(3)
            val similar = minOf(4.0, lexicon.overlap(grams[i], asked) * 2.0)
            var score = chapterScore + names.size * 0.8 + similar + (7 - d.priority) * 0.2 + if (d.always) 0.5 else 0.0
            // 别的卷（标题、粗体小标题里写着“第七卷”）：前面的卷少算，后面隔了一卷以上的是后文，不带。
            val volumes = PackRanking.volumes(head)
            if (volumes.isNotEmpty() && volume !in volumes) {
                if (volumes.all { it > volume + 1 }) return@mapIndexedNotNull null
                score -= if (volumes.all { it < volume }) 1.5 else 0.5
            }
            // 正文里只说到后面隔了一卷以上的（“第十一至十二卷”），多半是后文。
            val mentioned = PackRanking.volumes(u.text)
            if (volumes.isEmpty() && mentioned.isNotEmpty() && mentioned.all { it > volume + 1 }) score -= 1.5
            // 只写到远处几章的：离这一章远的少算，只写后面几章的多半是后文，也少算。
            if (chapterScore == 0.0 && refs.isNotEmpty()) {
                if (refs.minOf { r -> if (chapter < r.first) r.first - chapter else chapter - r.last } > 10) score -= 2.0
                if (refs.all { it.first > chapter + 3 }) score -= 2.0
            }
            if (score < MIN_SCORE) return@mapIndexedNotNull null
            val why = chapterWhy ?: if (names.isNotEmpty()) "写到" + names.joinToString("、") else "和本章细纲相关"
            Triple(score, d to u, why)
        }.sortedWith(compareByDescending<Triple<Double, Pair<PackDoc, PackUnit>, String>> { it.first }.thenBy { it.second.first.priority })
        ranked.forEach { (_, du, why) ->
            val (d, u) = du
            if (left <= 200) return@forEach
            val body = cut(u.text, minOf(left, 1_500))
            items += BriefItem(d.kind.label, (listOf(d.title) + u.path).joinToString(" › "), body, why)
            left -= body.length
        }
        // 只有作者知道的也算字数，限一段，不挤掉别的。
        val hiddenCut = mutableListOf<String>()
        var hiddenBudget = minOf(1_500, maxOf(left, 300))
        hidden.forEach { h ->
            if (hiddenBudget - h.length < 0) return@forEach
            hiddenCut += h
            hiddenBudget -= h.length
        }
        return ChapterBrief(chapter, volume, presentNames, items, notYet, open, due, hiddenCut, shown, ledgerLimit, ledgerAt)
    }

    /** 账本快照最多拼多少字。 */
    const val LEDGER_LIMIT = 4_000

    /** 两段文字说的是同一件事：规范化以后相同，或一方包含另一方（短的那段至少两个字）。比对账本和设定包用。 */
    internal fun same(a: String, b: String): Boolean {
        val x = ChapterSyncs.normalize(a)
        val y = ChapterSyncs.normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // 包含也算重复，但短的一方要够长、占长的一方的四成以上：只写“玉佩”的一条不能把所有提到玉佩的都去掉。
        val short = minOf(x.length, y.length)
        return x == y || (short >= 4 && short * 5 >= maxOf(x.length, y.length) * 2 && (x in y || y in x))
    }

    /**
     * 写作规范、称谓与语气太长时节选：每份不超过剩下的字数就整份带；超了就按小节挑——
     * 先挑写到出场人物的小节，再从后往前挑（规范后面写的多是新补充、以它为准的），按原来的顺序拼回去。
     */
    fun styleFor(docs: List<Pair<PackDoc, String>>, present: Collection<String>, limit: Int = STYLE_LIMIT): String {
        val out = StringBuilder()
        var left = limit
        docs.filter { it.second.isNotBlank() }.sortedBy { it.first.priority }.forEach { (d, t) ->
            if (left <= 200) return@forEach
            val text = t.trim()
            if (text.length <= left) {
                out.append("【").append(d.title).append("】\n").append(text).append("\n\n")
                left -= text.length
                return@forEach
            }
            val sections = PackSections.split(text)
            val picked = sortedSetOf<Int>()
            var budget = left
            fun take(i: Int) {
                val n = sections[i].text.length + 20
                if (i !in picked && n <= budget) { picked += i; budget -= n }
            }
            sections.indices.filter { i -> present.any { it.length >= 2 && it in sections[i].text } }.forEach(::take)
            sections.indices.reversed().forEach(::take)
            if (picked.isEmpty()) return@forEach
            out.append("【").append(d.title).append("（节选）】\n")
            picked.forEach { i -> out.append("## ").append(sections[i].path.joinToString(" › ")).append('\n').append(sections[i].text).append("\n\n") }
            left = budget
        }
        return out.toString().trim()
    }

    /** 写作规范最多带多少字。 */
    const val STYLE_LIMIT = 12_000

    /** 本章资料里带的写作规范节选（改稿、灵感这些不另带规范的时候用）。 */
    const val STYLE_IN_BRIEF = 2_500

    /** 带上的前情摘要：前面这几章的都带。 */
    const val RECENT_SUMMARIES = 5

    private fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(maxOf(max, 0)) + "……（后面省略）"
}

/** 第几章、第几卷：章名、卷名里写了“第X章”“第X卷”的照写的算，没写的按目录顺序。 */
fun Work.chapterNumber(chapterId: String): Int? {
    val i = chapters.indexOfFirst { it.id == chapterId }
    if (i < 0) return null
    return ChapterRefs.number(chapters[i].title) ?: (i + 1)
}

fun Work.volumeNumber(chapterId: String): Int {
    val i = volumes.indexOfFirst { v -> v.chapters.any { it.id == chapterId } }
    return volumes.getOrNull(i)?.title?.let { t -> Regex("第(.{1,6}?)卷").find(t)?.let { CheckRules.cn(it.groupValues[1]) } } ?: (i + 1)
}

/** 这一章按设定算是第几章：细纲卡里写了细纲章号就按它，否则按章名、目录顺序。 */
fun WorkStore.chapterNo(work: Work, chapterId: String): Int? {
    val plan = card(work.id, chapterId).plan.trim()
    if (plan.isNotEmpty()) (CheckRules.cn(plan) ?: ChapterRefs.number(plan) ?: ChapterRefs.number("第${plan}章"))?.let { return it }
    return work.chapterNumber(chapterId)
}

/**
 * 读设定包、细纲卡和这一章，挑本章资料。[text] 为空时用存盘的正文；[limit] 为空时按作品里设的上限；
 * [style] 为真时带写作规范节选（写正文、写法关另外带全，不用）。
 * [confirmedOnly] 为真时，前情只用已定稿章的摘要，不把未定稿的摘要或细纲计划当作已经发生的事实。
 */
fun WorkStore.brief(workId: String, chapterId: String, text: String? = null, limit: Int? = null, style: Boolean = false, confirmedOnly: Boolean = false): ChapterBrief? {
    val work = get(workId) ?: return null
    val number = chapterNo(work, chapterId) ?: return null
    val docs = pack(workId).map { it to packText(workId, it.id) }
    val entries = outlineEntries(workId)
    val planned = plannedCard(workId, chapterId, entries)
    val card = planned.card.let { listOf(it.facts, it.boundary, it.pov, it.countdown, it.hook, it.threads).joinToString("\n") }
    val own = chapterOutlineEntries(work, chapterId, entries)
    // Only this chapter's matched outline can override the catalog volume. A nearby earlier
    // outline may belong to another volume and cannot establish the current chapter's scope.
    val volume = own.firstNotNullOfOrNull { it.volume }
        ?: work.volumeNumber(chapterId)
    // 前情：写了摘要的章用摘要；最近几章没写摘要的，用它的细纲卡（从细纲提取的也算）。
    val index = work.chapters.indexOfFirst { it.id == chapterId }
    val before = work.chapters.take(index).filter { !confirmedOnly || it.status == ChapterStatus.FINAL }
    val summaries = before.mapIndexedNotNull { i, c ->
        val stored = card(workId, c.id)
        val no = chapterNo(work, c.id) ?: 0
        when {
            stored.summary.isNotBlank() -> ChapterSummary(no, c.title, stored.summary)
            !confirmedOnly && i >= before.size - ChapterBriefs.RECENT_SUMMARIES ->
                plannedCard(workId, c.id, entries).card.facts.takeIf { it.isNotBlank() }?.let { ChapterSummary(no, c.title, "（按细纲）$it") }
            else -> null
        }
    }
    val body = text ?: text(workId, chapterId)
    val query = listOf(card, summaries.takeLast(2).joinToString("\n") { it.summary }, body.takeLast(1_500)).joinToString("\n")
    return ChapterBriefs.build(docs, body, card, number, volume, limit ?: assist(workId).briefLimit, summaries, query, own.map { it.text }, style, ledger(workId))
}
