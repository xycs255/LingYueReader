package com.xycs.lingyuereader.data

/**
 * 一个人物：名字、别名（字、小名……）、设定文档里写到他的几段（表格行、列表项、小节）。
 * [later] 是表格里“结局”“下场”“怎么变”这类写后文走向的格子：只给 AI 把握方向，不能写进正文。
 */
data class CharacterEntry(val name: String, val aliases: List<String> = emptyList(), val notes: List<String> = emptyList(), val later: List<String> = emptyList()) {
    val names: List<String> get() = listOf(name) + aliases
}

/** 察觉表里的一条：谁、什么事、从第几章（或第几卷）起。[secret] 是“××这一面”“真相”这类视角人物不知道的格子。 */
data class KnowledgeItem(val text: String, val who: Set<String>, val chapter: Int? = null, val volume: Int? = null, val secret: String = "")

/** 一条伏笔：什么、第几章埋下、计划第几章回收、回收了没有。[doc]、[line] 记它在哪份文档的哪一行（标为已回收时改那一行）。 */
data class Foreshadow(
    val what: String,
    val planted: Int? = null,
    val payoff: Int? = null,
    val done: Boolean = false,
    val doc: String = "",
    val line: Int = -1,
    val statusColumn: Int = -1,
)

/** 前面一章的摘要（细纲卡里写完以后的那一栏）。 */
data class ChapterSummary(val number: Int, val title: String, val summary: String)

/** 本章资料里的一项：哪一类、标题、内容、为什么带上它。 */
data class BriefItem(val group: String, val title: String, val text: String, val reason: String)

/**
 * 写某一章时要带给 AI 的资料：常用文档、出场人物的人物卡、到这一章为止知道的事、
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
) {
    val isEmpty: Boolean get() = items.isEmpty() && notYet.isEmpty() && open.isEmpty() && due.isEmpty() && hidden.isEmpty()

    /** 拼成给 AI 的文字。 */
    fun render(): String? {
        if (isEmpty) return null
        val out = StringBuilder("下面是写第 $chapter 章（第 $volume 卷）要用到的设定资料，按优先级排列；资料之间有冲突时，以前面的为准。\n")
        if (present.isNotEmpty()) out.append("这一章出场或提到的人物：").append(present.joinToString("、")).append("。\n")
        items.forEach { out.append("\n【").append(it.group).append("·").append(it.title).append("】\n").append(it.text).append('\n') }
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
    const val LIMIT = 12_000

    // ---------- 人物 ----------

    /** 列表、加粗里常见的小标签，不是人名。 */
    private val labels = setOf(
        "人物", "主角", "配角", "姓名", "名字", "出身", "性格", "长处", "短处", "外貌", "经历", "关系", "动机", "目标", "弱点", "口头禅",
        "说话", "结局", "下场", "背景", "身份", "定位", "年龄", "备注", "注", "说明", "例", "例如", "想要", "害怕", "标志物", "怎么变",
        "怎么说话", "总规则", "原则", "要点", "注意", "补记", "附", "附注", "处境", "事业", "后续", "作用", "用法", "改动", "变更",
    )
    private val nameChars = Regex("^[\\p{IsHan}·]{1,6}$")
    private val aliasPattern = Regex("(?:^|[，、（(\\s；;])(?:字|小名|乳名|又名|别名|外号|人称|绰号)[：:]?[“「]?(\\p{IsHan}{2,3})")

    /** “林月（原创，药铺女儿）”“**沈默**，字子静”“1. 老周”→ 名字；不像名字就是 null。 */
    internal fun cleanName(raw: String): String? {
        var s = raw.replace("*", "").trim()
        s = s.replace(Regex("^\\d+[.、)）]\\s*"), "")
        s = s.split('（', '(', '：', ':', '，', ',', ' ', '　').first().trim()
        return s.takeIf { nameChars.matches(it) && it !in labels && !it.startsWith("第") && it.length >= 2 }
    }

    private fun aliases(text: String, name: String) = aliasPattern.findAll(text).map { it.groupValues[1] }.filter { it != name }.distinct().toList()

    /**
     * 人物文档里的人物：表格（表头有“人物”“角色”“姓名”的列，一行一人）、
     * 列表项和段落（“- **林月（原创）：** ……”“**林月**：……”，连同下面缩进的几行）、只写名字的小标题。
     * 同一个人写在几处的，合在一起。
     */
    fun characters(docs: List<Pair<String, String>>): List<CharacterEntry> {
        val found = LinkedHashMap<String, MutableList<String>>()
        val later = HashMap<String, MutableList<String>>()
        fun add(name: String?, note: String) {
            if (name == null || note.isBlank()) return
            found.getOrPut(name) { mutableListOf() } += note.trim()
        }
        // 列表项里加粗的不一定是人名（“**出身：**”“**锋芒与越线：**”这类小标签）：带“与、的、及、和、之”的不算，
        // 名字后面带括号注（“林月（原创）”）或者在表格、标题里也出现过的一定算。
        val bullets = mutableListOf<Triple<String, String, Boolean>>()
        for ((_, text) in docs) {
            MdTable.parse(text).forEach { t ->
                val col = t.column("人物", "角色", "姓名", "名字")
                if (col < 0) return@forEach
                val future = t.header.indices.filter { it != col && futureColumn.containsMatchIn(t.header[it]) }.toSet()
                t.rows.forEach { row ->
                    fun cells(cols: List<Int>) = cols.filter { t.cell(row, it).isNotBlank() && t.cell(row, it) != "—" }.joinToString("；") { "${t.header[it]}：${t.cell(row, it)}" }
                    val name = cleanName(t.cell(row, col))
                    val note = cells(t.header.indices.filter { it != col && it !in future })
                    add(name, t.cell(row, col) + if (note.isEmpty()) "" else "——$note")
                    val ahead = cells(future.sorted())
                    if (name != null && ahead.isNotEmpty()) later.getOrPut(name) { mutableListOf() } += ahead
                }
            }
            val lines = text.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val bold = Regex("^(?:[-*]\\s+)?\\*\\*(.+?)\\*\\*").find(line.trim())
                val top = !line.startsWith(" ") && !line.startsWith("\t")
                if (bold != null && top) {
                    val name = cleanName(bold.groupValues[1])
                    // 连同下面缩进的子项。
                    val block = StringBuilder(line.trim().removePrefix("- ").removePrefix("* "))
                    var j = i + 1
                    while (j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t")) && lines[j].isNotBlank()) {
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
            PackSections.split(text).forEach { s ->
                val name = s.title.let { t -> cleanName(t)?.takeIf { t.replace(Regex("[（(].*"), "").trim().replace("*", "") == it } }
                if (name != null && !s.text.trimStart().startsWith("|")) add(name, s.text)
            }
        }
        val confirmed = found.keys.toSet()
        bullets.forEach { (name, note, noted) -> if (noted || name in confirmed || !Regex("[与的及和之]").containsMatchIn(name)) add(name, note) }
        return found.map { (name, notes) ->
            val all = notes.distinct()
            CharacterEntry(name, all.flatMap { aliases(it, name) }.distinct().filter { a -> a !in found.keys || a == name }, all, later[name].orEmpty().distinct())
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

    /**
     * 察觉表里的条目：表格每行一条（“卷”或“章”那列定时间，小节标题里的人名和行里提到的人名算“谁”）；
     * 列表项里带章号或卷号的也算一条。
     */
    fun knowledge(docs: List<Pair<String, String>>, people: Collection<String>): List<KnowledgeItem> {
        val out = mutableListOf<KnowledgeItem>()
        fun names(text: String) = people.filter { it.length >= 2 && it in text }.toSet()
        for ((_, text) in docs) {
            val sections = PackSections.split(text, chunk = Int.MAX_VALUE)
            val tables = MdTable.parse(text)
            val lines = text.lines()
            tables.forEach { t ->
                val firstLine = t.lines.firstOrNull() ?: return@forEach
                val section = sections.lastOrNull { it.line <= firstLine }
                val owners = section?.path.orEmpty().mapNotNull { cleanName(it) }.toSet()
                val timeCol = t.column("章", "卷", "时间", "从")
                val secretCols = t.header.indices.filter { it != timeCol && secretColumn.containsMatchIn(t.header[it]) }.toSet()
                t.rows.forEach { row ->
                    val cell = t.cell(row, timeCol)
                    fun cells(cols: List<Int>) = cols.filter { t.cell(row, it).isNotBlank() && t.cell(row, it) !in setOf("—", "无", "-") }.joinToString("；") { "${t.header[it]}：${t.cell(row, it)}" }
                    val body = cells(t.header.indices.filter { it != timeCol && it !in secretCols })
                    val secret = cells(secretCols.sorted())
                    val chapter = ChapterRefs.find(cell).minOrNull()
                    val volume = if (t.header.getOrNull(timeCol)?.contains("卷") == true) volumeOf(cell) else volumeRef.find(cell)?.let { volumeOf(it.value) }
                    val label = listOfNotNull(volume?.let { "第${it}卷" }, chapter?.let { "第${it}章" }).joinToString("")
                    val head = (if (owners.isEmpty()) "" else owners.joinToString("、") + "，") + (if (label.isEmpty()) "" else "$label：")
                    out += KnowledgeItem(head + body, owners + names(body), chapter, volume, if (secret.isEmpty()) "" else head + secret)
                }
            }
            val inTable = tables.flatMap { it.lines }.toSet()
            lines.forEachIndexed { i, raw ->
                val line = raw.trim()
                if (i in inTable || line.startsWith("|") || line.startsWith("#") || !Regex("^(?:[-*]|\\d+[.、])\\s*").containsMatchIn(line)) return@forEachIndexed
                val chapter = ChapterRefs.find(line).minOrNull()
                val volume = volumeRef.find(line)?.let { volumeOf(it.value) }
                if (chapter == null && volume == null) return@forEachIndexed
                val body = line.replace(Regex("^(?:[-*]|\\d+[.、])\\s*"), "").replace("**", "")
                out += KnowledgeItem(body, names(body), chapter, volume)
            }
        }
        return out
    }

    // ---------- 伏笔 ----------

    private val doneWords = Regex("已回收|已兑现|已揭|已完成|✓|✔")

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
                    )
                }
            }
            val inTable = tables.flatMap { it.lines }.toSet()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (i in inTable || !Regex("^[-*]\\s+").containsMatchIn(line)) return@forEachIndexed
                val refs = ChapterRefs.find(line)
                if (refs.isEmpty()) return@forEachIndexed
                val body = line.replace(Regex("^[-*]\\s+"), "").replace("**", "")
                val what = body.split('：', ':', '（', '(').first().trim().ifEmpty { body }
                out += Foreshadow(what, refs.first(), refs.getOrNull(1), doneWords.containsMatchIn(line), doc, i)
            }
        }
        return out
    }

    /** 标为已回收：表格改“状态”那格（没有状态列就补在最后），列表项在行尾加“（已回收）”。 */
    fun markDone(text: String, f: Foreshadow, chapter: Int?): String {
        val lines = text.lines()
        if (f.line !in lines.indices) return text
        val mark = "已回收" + (chapter?.let { "（第${it}章）" } ?: "")
        return if (lines[f.line].trim().startsWith("|")) {
            val cols = MdTable.parse(text).firstOrNull { f.line in it.lines }?.header?.size ?: 0
            MdTable.setCell(text, f.line, if (f.statusColumn >= 0) f.statusColumn else cols, mark)
        } else {
            lines.toMutableList().also { it[f.line] = it[f.line].trimEnd() + "（$mark）" }.joinToString("\n")
        }
    }

    // ---------- 按章挑资料 ----------

    private val structured = setOf(PackKind.CHARACTER, PackKind.AWARENESS, PackKind.FORESHADOW, PackKind.RULES)

    /**
     * 挑第 [chapter] 章（第 [volume] 卷）要带的资料。[text] 是这一章的正文，[card] 是细纲卡的文字（出场人物也从这里认）。
     * 先放常用文档（最多一半字数），再放出场人物的人物卡、到这一章为止知道的事和伏笔，最后放提到这一章或出场人物的其他小节。
     */
    fun build(
        docs: List<Pair<PackDoc, String>>,
        text: String,
        card: String,
        chapter: Int,
        volume: Int,
        limit: Int = LIMIT,
        summaries: List<ChapterSummary> = emptyList(),
    ): ChapterBrief {
        val items = mutableListOf<BriefItem>()
        val hidden = mutableListOf<String>()
        var left = limit
        val sorted = docs.filter { it.second.isNotBlank() }.sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
        val characters = characters(sorted.filter { it.first.kind == PackKind.CHARACTER }.map { it.first.title to it.second })
        val scope = "$card\n$text"
        val present = characters.map { it to mentions(it, scope) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        val presentNames = present.map { it.name }

        // 一、常用文档：整份带上（人物、察觉这类按章挑的除外；这一章还认不出人物时，常用的人物文档也整份带）。
        val always = sorted.filter { (d, _) -> d.always && (d.kind !in structured || (d.kind == PackKind.CHARACTER && present.isEmpty())) }
        var budget = limit / 2
        always.forEachIndexed { i, (d, t) ->
            val quota = budget / (always.size - i)
            val cut = cut(t.trim(), quota)
            items += BriefItem(d.kind.label, d.title, cut, "常用（优先级 ${d.priority}）")
            budget -= cut.length
            left -= cut.length
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

        // 三、出场人物的人物卡：出现次数多的在前。写到后面章节的句子、“结局”“怎么变”这类格子另列为只有作者知道的。
        var charBudget = left * 2 / 5
        present.forEach { c ->
            if (charBudget <= 100) return@forEach
            val (now, ahead) = splitByChapter(c.notes.joinToString("\n"), chapter)
            val body = cut(now, minOf(charBudget, 1_500))
            items += BriefItem("人物", c.name, body, "本章出场" + if (c.aliases.isEmpty()) "" else "（也叫${c.aliases.joinToString("、")}）")
            charBudget -= body.length
            left -= body.length
            (c.later + listOf(ahead)).filter { it.isNotBlank() }.forEach { hidden += c.name + "——" + cut(it, 300) }
        }

        // 三、察觉：出场人物到这一章为止知道的（离这一章近的在前），以及还不该知道的。
        val allNames = characters.flatMap { it.names }
        val knowledge = knowledge(sorted.filter { it.first.kind == PackKind.AWARENESS }.map { it.first.title to it.second }, allNames)
        fun reached(k: KnowledgeItem) = k.chapter?.let { it <= chapter } ?: k.volume?.let { it <= volume } ?: true
        val presentAll = present.flatMap { it.names }.toSet()
        val relevant = knowledge.filter { k -> k.who.isEmpty() || k.who.any { it in presentAll } }
        val known = relevant.filter(::reached).sortedByDescending { it.chapter ?: ((it.volume ?: 0) * 1000) }
        known.filter { it.secret.isNotBlank() }.take(8).forEach { hidden += cut(it.secret, 200) }
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
        val notYet = relevant.filterNot(::reached)
            .sortedBy { it.chapter ?: ((it.volume ?: 0) * 1000) }
            .take(12)
            .map { cut(it.text, 160) }
        left -= notYet.sumOf { it.length }

        // 四、伏笔：埋下了还没回收的；计划这一章回收的另列。
        val foreshadows = foreshadows(sorted.filter { it.first.kind == PackKind.FORESHADOW }.map { it.first.title to it.second })
        val open = foreshadows.filter { !it.done && (it.planted ?: 0) <= chapter }
        val due = open.filter { it.payoff == chapter }
        left -= open.sumOf { it.what.length + 20 }

        // 五、其他按需的文档里提到这一章（或前后一章）、出场人物的小节。
        val candidates = sorted.filter { (d, _) -> !d.always && d.kind !in structured }.flatMap { (d, t) ->
            PackSections.split(t).mapNotNull { s ->
                val refs = ChapterRefs.find(s.text + " " + s.path.joinToString(" "))
                var score = 0
                var why = ""
                if (chapter in refs) { score += 5; why = "提到第 $chapter 章" }
                else if (chapter - 1 in refs || chapter + 1 in refs) { score += 2; why = "提到前后一章" }
                val names = presentNames.filter { n -> n in s.text }.take(3)
                score += names.size
                if (names.isNotEmpty() && why.isEmpty()) why = "写到" + names.joinToString("、")
                if (score >= 2) Triple(score, d, s) to why else null
            }
        }.sortedWith(compareByDescending<Pair<Triple<Int, PackDoc, PackSection>, String>> { it.first.first }.thenBy { it.first.second.priority })
        candidates.forEach { (t, why) ->
            val (_, d, s) = t
            if (left <= 200) return@forEach
            val body = cut(s.text, minOf(left, 2_000))
            items += BriefItem(d.kind.label, (listOf(d.title) + s.path).joinToString(" › "), body, why)
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
        return ChapterBrief(chapter, volume, presentNames, items, notYet, open, due, hiddenCut)
    }

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

/** 读设定包、细纲卡和这一章，挑本章资料。[text] 为空时用存盘的正文。 */
fun WorkStore.brief(workId: String, chapterId: String, text: String? = null, limit: Int = ChapterBriefs.LIMIT): ChapterBrief? {
    val work = get(workId) ?: return null
    val number = work.chapterNumber(chapterId) ?: return null
    val docs = pack(workId).map { it to packText(workId, it.id) }
    val card = card(workId, chapterId).let { listOf(it.facts, it.boundary, it.pov, it.countdown, it.hook, it.threads).joinToString("\n") }
    // 目录里排在这一章前面的章的摘要。
    val index = work.chapters.indexOfFirst { it.id == chapterId }
    val summaries = work.chapters.take(index).mapNotNull { c ->
        card(workId, c.id).summary.takeIf { it.isNotBlank() }?.let { ChapterSummary(work.chapterNumber(c.id) ?: 0, c.title, it) }
    }
    return ChapterBriefs.build(docs, text ?: text(workId, chapterId), card, number, work.volumeNumber(chapterId), limit, summaries)
}
