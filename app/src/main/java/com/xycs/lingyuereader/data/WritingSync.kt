package com.xycs.lingyuereader.data

/**
 * “写完一章，整理进设定包”：AI 读完一章，提出要补进设定包的新设定、和设定包对不上的冲突、谁知道了什么（察觉），
 * 作者逐条勾选、改过，才写进设定包。这里是不联网的部分：定位写进哪份文档哪一处、写进去、记下哪些章整理过。
 */

/** 整理过的一章：整理时正文的哈希（[ChapterSyncs.hash]，早先记的是 [String.hashCode]，也认）、什么时候整理的。 */
data class SyncMark(val hash: Int, val at: Long)

/** 整理结果的三组。 */
enum class SyncGroup(val label: String) { NEW("新设定"), CONFLICT("冲突"), AWARENESS("察觉") }

/** 写进文档的方式：表格追加一行、追加到某一节、追加到文档末尾、换掉设定包里的一句；[NONE] 是不写（只提示）。 */
enum class SyncPlace { ROW, SECTION, END, REPLACE, NONE }

/**
 * 一条提议。AI 回的字段放在前面；[docId] 往后是定好的位置（[PackSync.plan]）：写进哪份文档（为空是新建一份 [docTitle]）、
 * 怎么写（[place]、[anchor]），[text] 是要写进去的原文（表格的一行、一节、一条），作者可以改。
 */
data class SyncItem(
    val group: SyncGroup,
    val title: String,
    /** 依据：正文里的原句（解析时核对过，正文里一字不差地找得到）。 */
    val quote: String,
    val kind: PackKind,
    /** AI 说要写进的文档（标题）、哪一节。 */
    val doc: String = "",
    val section: String = "",
    /** 人物名、地名、物件名；察觉是“谁”。 */
    val name: String = "",
    /** 分项：人物的称呼、身份、关系、本章；察觉的人物、知道什么、从第几章起、消息从哪来。 */
    val fields: List<Pair<String, String>> = emptyList(),
    val text: String = "",
    /** 冲突：设定包怎么写的、正文怎么写的。 */
    val packSays: String = "",
    val textSays: String = "",
    /** 冲突：是正文写错了（只提示，不改设定包）。 */
    val fixText: Boolean = false,
    val chapterId: String = "",
    /** 这一章按设定算第几章。 */
    val chapter: Int = 0,
    val docId: String? = null,
    val docTitle: String = "",
    val place: SyncPlace = SyncPlace.END,
    /** [SyncPlace.SECTION] 是节名（人物名），[SyncPlace.REPLACE] 是要换掉的设定包原文。 */
    val anchor: String = "",
) {
    /** 能写进设定包的（“正文写错了”的冲突只提示）。 */
    val writable: Boolean get() = place != SyncPlace.NONE && text.isNotBlank()

    /** 默认勾不勾：新设定和察觉勾上，冲突要作者自己看过再勾。 */
    val defaultOn: Boolean get() = writable && group != SyncGroup.CONFLICT

    /** 认这一条（勾选、改过的文字按它记）。 */
    val key: String get() = "$chapterId|${group.name}|$title|$quote"

    fun field(name: String): String = fields.firstOrNull { it.first == name }?.second.orEmpty()

    /** 写在哪：“写进《人物档案》· 追加一行”。 */
    val where: String
        get() = when (place) {
            SyncPlace.NONE -> if (fixText) "正文写错了，只提示，不改设定包" else "不写进设定包"
            else -> (if (docId == null) "新建《${docTitle}》" else "写进《${docTitle}》") + " · " + when (place) {
                SyncPlace.ROW -> "表格追加一行"
                SyncPlace.SECTION -> "追加到“$anchor”"
                SyncPlace.REPLACE -> "改掉原来的写法"
                else -> "追加到末尾"
            }
        }
}

object ChapterSyncs {
    /** 半角标点、各式引号对应的统一写法（只用来算哈希）。 */
    private val PUNCT = mapOf(
        ',' to '，', '.' to '。', ';' to '；', ':' to '：', '?' to '？', '!' to '！', '(' to '（', ')' to '）', '~' to '～',
        '“' to '"', '”' to '"', '「' to '"', '」' to '"', '＂' to '"', '‘' to '\'', '’' to '\'', '『' to '\'', '』' to '\'', '＇' to '\'',
    )

    /** 去掉所有空白，统一全半角标点、引号、省略号、破折号：只改了排版的正文规范化以后一样。 */
    internal fun normalize(text: String): String {
        val t = text.replace(Regex("[\\s\\u3000\\u00A0]+"), "")
            .replace(Regex("\\.{3,}|。{3,}|…+|⋯+"), "…")
            .replace(Regex("-{2,}|－{2,}|—+|―+|─+"), "—")
        return buildString(t.length) { t.forEach { append(PUNCT[it] ?: it) } }
    }

    /** 记“已整理”用的哈希：先规范化再算，只改标点、空行的排版不会让整章变回“未整理”。 */
    fun hash(text: String): Int = normalize(text).hashCode()

    /** 这一章整理过、整理以后正文没改过（排版不算改过）。早先记的是正文直接的哈希，也认。 */
    fun isSynced(mark: SyncMark?, text: String): Boolean = mark != null && (mark.hash == hash(text) || mark.hash == text.hashCode())

    /** 有正文、还没整理（或整理以后正文改过）的章，按目录顺序。没整理过的章不用读正文。 */
    fun unsynced(chapters: List<WorkChapter>, marks: Map<String, SyncMark>, text: (String) -> String): List<WorkChapter> =
        chapters.filter { c -> c.words > 0 && marks[c.id]?.let { isSynced(it, text(c.id)) } != true }
}

/** 这部作品里有正文、还没整理的章。[marks] 不给就按存盘的 AI 设置。 */
fun WorkStore.unsynced(workId: String, marks: Map<String, SyncMark>? = null): List<WorkChapter> =
    ChapterSyncs.unsynced(get(workId)?.chapters.orEmpty(), marks ?: assist(workId).synced) { text(workId, it) }

object PackSync {
    /** 人物档案、察觉表的表格里各项对应哪一列（表头含这些字）。 */
    private val characterColumns = listOf(
        "人物" to listOf("人物", "角色", "姓名", "名字"),
        "称呼" to listOf("称呼", "别名", "又名", "称谓", "外号"),
        "身份" to listOf("身份", "职业", "出身", "简介", "设定"),
        "关系" to listOf("关系"),
        "本章" to listOf("本章", "经历", "事件", "出场", "首次", "备注", "说明"),
    )
    private val awarenessColumns = listOf(
        "人物" to listOf("人物", "谁", "角色"),
        "知道什么" to listOf("知道", "内容", "什么", "事情", "察觉", "知情"),
        "从第几章起" to listOf("章", "起", "时间", "卷"),
        "消息从哪来" to listOf("来", "源", "途径", "渠道", "怎么知道"),
    )

    private fun clean(s: String) = s.replace(Regex("[《》「」“”\\s*]"), "")

    /** 人物表：表头有“人物”“姓名”这类列的第一张表。 */
    private fun peopleTable(text: String) = MdTable.parse(text).firstOrNull { it.column("人物", "角色", "姓名", "名字") >= 0 }

    /** 察觉表：表头有“知道”这类列的第一张表，没有就第一张表。 */
    private fun awarenessTable(text: String) = MdTable.parse(text).let { ts -> ts.firstOrNull { it.column("知道", "知情", "察觉") >= 0 } ?: ts.firstOrNull() }

    private fun tableFor(kind: PackKind, text: String): MdTable? = when (kind) {
        PackKind.CHARACTER -> peopleTable(text)
        PackKind.AWARENESS -> awarenessTable(text)
        else -> MdTable.parse(text).firstOrNull()
    }

    /** 按表头把各项放进一行：每列放第一个对得上的项，对不上的项并进最后一个空着的格子。 */
    internal fun row(table: MdTable, values: List<Pair<String, String>>, columns: List<Pair<String, List<String>>>): String {
        val cells = MutableList(table.header.size) { "" }
        val used = HashSet<String>()
        table.header.forEachIndexed { i, h ->
            val hit = columns.firstOrNull { (name, keys) -> name !in used && keys.any { it in h } && values.any { it.first == name } } ?: return@forEachIndexed
            used += hit.first
            cells[i] = values.first { it.first == hit.first }.second
        }
        val rest = values.filter { it.first !in used && it.second.isNotBlank() }.joinToString("；") { "${it.first}：${it.second}" }
        if (rest.isNotEmpty() && cells.isNotEmpty()) {
            val i = cells.indexOfLast { it.isBlank() }.takeIf { it >= 0 } ?: cells.lastIndex
            cells[i] = if (cells[i].isBlank()) rest else cells[i] + "；" + rest
        }
        return "| " + cells.joinToString(" | ") { it.replace("|", "／").replace("\n", " ") } + " |"
    }

    /** 文档里写成“## 名字”的那些人物的标题层级（没有就二级）。 */
    private fun personLevel(text: String, names: Collection<String>): Int =
        text.lines().firstNotNullOfOrNull { l -> Regex("^(#{2,4})\\s*(.+?)\\s*$").find(l.trim())?.takeIf { it.groupValues[2] in names }?.groupValues?.get(1)?.length } ?: 2

    private fun heading(lines: List<String>, anchor: String): Int {
        val a = clean(anchor)
        return lines.indexOfFirst { l -> Regex("^#{1,6}\\s+(.+?)\\s*#*$").find(l.trim())?.let { clean(it.groupValues[1]) == a } == true }
    }

    private fun bullet(lines: List<String>, anchor: String): Int {
        val a = Regex.escape(anchor)
        return lines.indexOfFirst { Regex("^[-*]\\s*\\**$a\\**\\s*[：:]").containsMatchIn(it.trim()) }
    }

    /** [anchor] 那一节（标题下面到下一个同级标题）的各行，或写成“- 名字：……”的那一条；都没有是 null。 */
    private fun section(lines: List<String>, anchor: String): List<String>? {
        val h = heading(lines, anchor)
        if (h >= 0) {
            val level = lines[h].trim().takeWhile { it == '#' }.length
            var stop = h + 1
            while (stop < lines.size && !Regex("^#{1,$level}\\s").containsMatchIn(lines[stop].trim())) stop++
            return lines.subList(h + 1, stop)
        }
        return bullet(lines, anchor).takeIf { it >= 0 }?.let { listOf(lines[it]) }
    }

    /** 这几行里已经有“第N章”开头的一行（或一条里有“；第N章”）：本章的经历记过了。 */
    private fun hasChapter(lines: List<String>, chapter: Int): Boolean {
        val r = Regex("(^[-*]?\\s*|[；;]\\s*)第\\s*$chapter\\s*章")
        return lines.any { r.containsMatchIn(it.trim()) }
    }

    /** “- 第N章：……”这样记本章经历的一行，返回 N。 */
    private fun chapterOf(line: String): Int? = Regex("^[-*]?\\s*第\\s*(\\d+)\\s*章").find(line.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** 察觉 [who] 知道 [what] 在 [text] 里已经有了（表格的一行，或“- 谁：……”的一条）。 */
    private fun knownAwareness(text: String, who: String, what: String): Boolean {
        val inTable = MdTable.parse(text).any { t ->
            val whoCol = t.column("人物", "谁", "角色")
            val whatCol = t.column("知道", "内容", "什么", "事情", "察觉", "知情")
            whatCol >= 0 && t.rows.any { cells ->
                val rowWho = t.cell(cells, whoCol)
                (whoCol < 0 || rowWho.contains(who) || (rowWho.isNotBlank() && who.contains(rowWho))) && similar(t.cell(cells, whatCol), what)
            }
        }
        return inTable || text.lines().any { l -> Regex("^[-*]\\s*${Regex.escape(who)}\\s*[：:]").containsMatchIn(l.trim()) && similar(l.trim().substringAfter('：').substringAfter(':'), what) }
    }

    /** 察觉的“谁”。 */
    private fun whoOf(item: SyncItem) = item.field("人物").ifBlank { item.name }.trim()

    /**
     * 两条提议说的是不是同一个新人物、同一处地方（同名），或同一个人知道了同一件事（跨章、分段去重用）。
     * 给已有人物补本章经历的（追加到他那一节的）每章各记各的，不算重复；冲突不比。
     */
    fun sameThing(a: SyncItem, b: SyncItem): Boolean {
        if (a.group != b.group) return false
        return when (a.group) {
            SyncGroup.NEW -> {
                fun supplement(i: SyncItem) = i.kind == PackKind.CHARACTER && i.place == SyncPlace.SECTION
                val n = clean(a.name)
                n.isNotEmpty() && n == clean(b.name) && (a.kind == PackKind.CHARACTER) == (b.kind == PackKind.CHARACTER) && !supplement(a) && !supplement(b)
            }
            SyncGroup.AWARENESS -> whoOf(a).let { it.isNotEmpty() && it == whoOf(b) } && similar(a.field("知道什么"), b.field("知道什么"))
            SyncGroup.CONFLICT -> false
        }
    }

    /**
     * 同一章分段整理时几段提的同一个人、同一件事并成一条：留前面那条；人物的称呼、身份、关系空着的用后面的补上，本章经历接在后面。
     */
    fun merge(items: List<SyncItem>): List<SyncItem> {
        val out = mutableListOf<SyncItem>()
        items.forEach { item ->
            val i = out.indexOfFirst { it.chapterId == item.chapterId && sameThing(it, item) }
            if (i < 0) {
                out += item
                return@forEach
            }
            val first = out[i]
            if (first.kind != PackKind.CHARACTER || first.group != SyncGroup.NEW) return@forEach
            val names = (first.fields.map { it.first } + item.fields.map { it.first }).distinct()
            out[i] = first.copy(fields = names.map { k ->
                val a = first.field(k)
                val b = item.field(k)
                k to when {
                    b.isBlank() || b in a -> a
                    a.isBlank() -> b
                    k == "本章" -> "$a；$b"
                    else -> a
                }
            })
        }
        return out
    }

    /** 两句说的是不是同一件事：去掉标点后一个包含另一个，或两两相连的字一半以上相同。 */
    internal fun similar(a: String, b: String): Boolean {
        fun norm(s: String) = s.replace(Regex("^(知道|得知|知晓|发现|察觉|明白)了?"), "").replace(Regex("[\\p{P}\\s]"), "")
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x in y || y in x) return true
        fun grams(s: String) = s.windowed(2).toSet()
        val gx = grams(x)
        val gy = grams(y)
        if (gx.isEmpty() || gy.isEmpty()) return false
        return 2.0 * gx.intersect(gy).size / (gx.size + gy.size) >= 0.5
    }

    /** 挑写进哪份文档：AI 说的标题对得上就用它，否则这一类的第一份，没有就新建（返回 null）。 */
    private fun target(item: SyncItem, docs: List<Pair<PackDoc, String>>): Pair<PackDoc, String>? {
        val named = item.doc.takeIf { it.isNotBlank() }?.let { d -> docs.firstOrNull { clean(it.first.title) == clean(d) } }
        if (named != null && (named.first.kind == item.kind || item.kind == PackKind.REFERENCE)) return named
        return docs.firstOrNull { it.first.kind == item.kind } ?: named
    }

    private fun SyncItem.into(doc: Pair<PackDoc, String>?, place: SyncPlace, text: String, anchor: String = ""): SyncItem =
        copy(docId = doc?.first?.id, docTitle = doc?.first?.title ?: docTitle.ifBlank { kind.label }, kind = doc?.first?.kind ?: kind, place = place, text = text, anchor = anchor)

    /**
     * 给 AI 的提议定位置，写好要写进去的原文；设定包里已经有的丢掉。察觉表里已经有、但写的是更晚的章才知道的，改成冲突。
     * [docs] 是现在的设定包（文档和正文）。
     */
    fun plan(items: List<SyncItem>, docs: List<Pair<PackDoc, String>>): List<SyncItem> {
        val people = ChapterBriefs.characters(docs.filter { it.first.kind == PackKind.CHARACTER }.map { it.first.title to it.second }).flatMap { it.names }.toSet()
        val characterText = docs.filter { it.first.kind == PackKind.CHARACTER }.joinToString("\n") { it.second }
        val allText = docs.joinToString("\n") { it.second }
        return items.mapNotNull { item ->
            when (item.group) {
                SyncGroup.NEW -> planNew(item, docs, people, characterText, allText)
                SyncGroup.AWARENESS -> planAwareness(item, docs)
                SyncGroup.CONFLICT -> planConflict(item, docs)
            }
        }.distinctBy { it.key }
    }

    private fun planNew(item: SyncItem, docs: List<Pair<PackDoc, String>>, people: Set<String>, characterText: String, allText: String): SyncItem? {
        val name = item.name.trim()
        if (item.kind == PackKind.CHARACTER) {
            if (name.isEmpty()) return null
            val doc = target(item, docs)
            val text = doc?.second.orEmpty()
            val lines = text.lines()
            val known = name in people || (name.length >= 2 && name in characterText)
            val chapterLine = item.field("本章").takeIf { it.isNotBlank() }?.let { "- 第${item.chapter}章：$it" }
            if (known) {
                // 已有的人物：有他那一节（或那一条）才补本章的新信息，在表格里的不重复提。
                val owner = docs.filter { it.first.kind == PackKind.CHARACTER }.firstOrNull { d -> section(d.second.lines(), name) != null }
                // 这一节里已经记过这一章（同一章再整理一次）：本章这一行不再追加。
                val recorded = owner?.let { hasChapter(section(it.second.lines(), name).orEmpty(), item.chapter) } == true
                val extra = listOfNotNull(
                    item.field("称呼").takeIf { it.isNotBlank() && it !in characterText }?.let { "- 称呼：$it" },
                    item.field("关系").takeIf { it.isNotBlank() && it !in characterText }?.let { "- 关系：$it" },
                    chapterLine?.takeUnless { recorded },
                )
                if (owner == null || extra.isEmpty()) return null
                return item.copy(title = "补充人物：$name").into(owner, SyncPlace.SECTION, extra.joinToString("\n"), name)
            }
            val values = listOf("人物" to name, "称呼" to item.field("称呼"), "身份" to item.field("身份"), "关系" to item.field("关系"), "本章" to item.field("本章").let { if (it.isBlank()) "" else "第${item.chapter}章：$it" })
                .filter { it.second.isNotBlank() || it.first == "人物" }
            val table = peopleTable(text)
            if (table != null) return item.into(doc, SyncPlace.ROW, row(table, values, characterColumns))
            val bulletStyle = lines.any { Regex("^[-*]\\s*[\\p{IsHan}A-Za-z·]{1,8}[：:]").containsMatchIn(it.trim()) } && lines.none { Regex("^#{2,4}\\s").containsMatchIn(it.trim()) }
            val body = if (bulletStyle) {
                "- $name：" + values.drop(1).joinToString("；") { (k, v) -> if (k == "本章") v else "$k：$v" }
            } else {
                "#".repeat(personLevel(text, people)) + " " + name + "\n" +
                    values.drop(1).joinToString("\n") { (k, v) -> if (k == "本章") "- $v" else "- $k：$v" }
            }
            return item.into(doc, SyncPlace.END, body.trim())
        }
        // 地名、物件、组织、规矩：设定包里提到过的不再提。
        val body = item.text.trim().ifEmpty { return null }
        if (name.length >= 2 && name in allText) return null
        if (body.length >= 6 && body in allText) return null
        val line = if (name.isNotEmpty() && !body.startsWith(name)) "- $name：$body" else "- $body"
        val doc = target(item, docs)
        val section = item.section.trim()
        return if (section.isNotEmpty()) item.into(doc, SyncPlace.SECTION, line, section) else item.into(doc, SyncPlace.END, line)
    }

    private fun planAwareness(item: SyncItem, docs: List<Pair<PackDoc, String>>): SyncItem? {
        val who = item.field("人物").ifBlank { item.name }.trim()
        val what = item.field("知道什么").trim()
        if (who.isEmpty() || what.isEmpty()) return null
        val since = item.field("从第几章起").ifBlank { "第${item.chapter}章" }
        val source = item.field("消息从哪来")
        val values = listOf("人物" to who, "知道什么" to what, "从第几章起" to since, "消息从哪来" to source)
        val own = docs.filter { it.first.kind == PackKind.AWARENESS }
        // 察觉表里已经有这件事：写的章不晚于这一章，不再提；写的是后面的章，正文提前说破了，改成冲突。
        for ((doc, text) in own) {
            val lines = text.lines()
            MdTable.parse(text).forEach { t ->
                val whoCol = t.column("人物", "谁", "角色")
                val whatCol = t.column("知道", "内容", "什么", "事情", "察觉", "知情")
                val timeCol = t.column("章", "起", "时间")
                if (whatCol < 0) return@forEach
                t.rows.forEachIndexed { r, cells ->
                    val rowWho = t.cell(cells, whoCol)
                    val sameWho = whoCol < 0 || rowWho.contains(who) || (rowWho.isNotBlank() && who.contains(rowWho))
                    if (!sameWho || !similar(t.cell(cells, whatCol), what)) return@forEachIndexed
                    val at = ChapterRefs.find(t.cell(cells, timeCol)).minOrNull() ?: t.cell(cells, timeCol).trim().toIntOrNull()
                    if (at == null || at <= item.chapter) return null
                    val line = lines[t.lines[r]]
                    val fixed = if (timeCol >= 0) MdTable.setCell(text, t.lines[r], timeCol, "第${item.chapter}章").lines()[t.lines[r]] else ""
                    return item.copy(
                        group = SyncGroup.CONFLICT,
                        title = "察觉表写$who 第${at}章才知道，正文第${item.chapter}章就知道了",
                        packSays = line.trim(),
                        textSays = what,
                        docId = doc.id,
                        docTitle = doc.title,
                        kind = PackKind.AWARENESS,
                        place = if (fixed.isEmpty()) SyncPlace.NONE else SyncPlace.REPLACE,
                        anchor = line,
                        text = fixed,
                    )
                }
            }
        }
        val doc = own.firstOrNull()
        val table = doc?.second?.let { awarenessTable(it) }
        return when {
            doc == null -> item.copy(docTitle = PackKind.AWARENESS.label).into(null, SyncPlace.ROW, row(MdTable.parse(PackImport.template(PackKind.AWARENESS)).first(), values, awarenessColumns))
            table != null -> item.into(doc, SyncPlace.ROW, row(table, values, awarenessColumns))
            else -> item.into(doc, SyncPlace.END, "- $who：${since}起$what" + if (source.isNotBlank()) "（$source）" else "")
        }
    }

    private fun planConflict(item: SyncItem, docs: List<Pair<PackDoc, String>>): SyncItem {
        val named = target(item, docs)
        if (item.fixText) return item.into(named, SyncPlace.NONE, "")
        val fix = item.text.trim()
        if (fix.isEmpty()) return item.into(named, SyncPlace.NONE, "")
        // 设定包里那句原话找得到、只有一处：直接换掉；找不到就补在那一节（或末尾），注明是哪一章改的。
        val says = item.packSays.trim()
        if (says.length >= 4) {
            val owner = (listOfNotNull(named) + docs).firstOrNull { (_, t) -> t.indexOf(says).let { it >= 0 && t.indexOf(says, it + 1) < 0 } }
            if (owner != null) return item.into(owner, SyncPlace.REPLACE, fix, says)
        }
        val line = "- " + fix.removePrefix("- ") + "（整理第${item.chapter}章时改）"
        return if (item.section.isNotBlank()) item.into(named, SyncPlace.SECTION, line, item.section.trim()) else item.into(named, SyncPlace.END, line)
    }

    /**
     * 写之前用现在的文字 [text] 再查一遍：前面几条（批量时前面几章）可能已经写过同一个人、同一处地方、同一件事，
     * 上次写到一半出错、重试时这一条可能已经写进去了。已经有的返回 null（跳过）；
     * 写成一节（或一条）的人物已经有了，改成把本章这一行补进他那一节。
     */
    private fun recheck(text: String, item: SyncItem): SyncItem? {
        if (text.isBlank()) return item
        val add = item.text.trim('\n')
        val lines = text.lines()
        val have = lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        // 原样写过了（重试）。
        if (item.place == SyncPlace.REPLACE) {
            if (item.anchor.isNotEmpty() && item.anchor !in text && add.trim() in text) return null
        } else if (item.place != SyncPlace.SECTION && add.lines().all { it.isBlank() || it.trim() in have }) {
            return null
        }
        val name = item.name.trim()
        return when {
            item.group == SyncGroup.NEW && item.kind == PackKind.CHARACTER && item.place != SyncPlace.SECTION -> {
                if (name.isEmpty()) return item
                if (section(lines, name) != null) {
                    // 前面已经给他起了一节：只把本章的经历补进去（这一章已经记过的话，写的时候再跳过）。
                    val did = item.field("本章").ifBlank { return null }
                    return item.copy(place = SyncPlace.SECTION, anchor = name, text = "- 第${item.chapter}章：$did")
                }
                val inTable = MdTable.parse(text).any { t ->
                    val col = t.column("人物", "角色", "姓名", "名字")
                    col >= 0 && t.rows.any { clean(t.cell(it, col)) == clean(name) }
                }
                if (inTable) null else item
            }
            item.group == SyncGroup.NEW && item.kind != PackKind.CHARACTER -> if (name.length >= 2 && name in text) null else item
            item.group == SyncGroup.AWARENESS -> {
                val who = whoOf(item)
                if (who.isNotEmpty() && knownAwareness(text, who, item.field("知道什么"))) null else item
            }
            else -> item
        }
    }

    /** 要补进 [have]（某一节的各行）的几行里，去掉已经有的、已经记过那一章的。 */
    private fun fresh(add: List<String>, have: List<String>): List<String> {
        val set = have.map { it.trim() }.toHashSet()
        return add.filter { l -> l.isNotBlank() && l.trim() !in set && chapterOf(l)?.let { hasChapter(have, it) } != true }
    }

    /**
     * 把一条写进文档 [text]（新建的文档是空的）。位置在写的时候再找一次：同一份文档前面几条可能已经改过它；
     * 已经写过的不再写（返回原文），所以重复执行也不会写出两份。
     */
    fun apply(text: String, planned: SyncItem): String {
        val item = recheck(text, planned) ?: return text
        val add = item.text.trim('\n')
        if (add.isBlank()) return text
        fun end(t: String) = if (t.isBlank()) add else t.trimEnd() + "\n\n" + add
        return when (item.place) {
            SyncPlace.NONE -> text
            SyncPlace.END -> end(text)
            SyncPlace.REPLACE -> {
                val at = text.indexOf(item.anchor)
                if (item.anchor.isNotEmpty() && at >= 0 && text.indexOf(item.anchor, at + 1) < 0) text.replaceRange(at, at + item.anchor.length, add) else end(text)
            }
            SyncPlace.ROW -> {
                val base = if (text.isBlank() && item.kind == PackKind.AWARENESS) PackImport.template(PackKind.AWARENESS) else text
                val table = tableFor(item.kind, base) ?: return end(text)
                val lines = base.lines().toMutableList()
                val after = table.lines.lastOrNull() ?: (table.start + 1)
                lines.addAll(after + 1, add.lines())
                lines.joinToString("\n")
            }
            SyncPlace.SECTION -> {
                val lines = text.lines().toMutableList()
                val h = heading(lines, item.anchor)
                if (h >= 0) {
                    val level = lines[h].trim().takeWhile { it == '#' }.length
                    var stop = h + 1
                    while (stop < lines.size && !Regex("^#{1,$level}\\s").containsMatchIn(lines[stop].trim())) stop++
                    // 这一节里已经有的行、已经记过的那一章不再追加。
                    val rest = fresh(add.lines(), lines.subList(h + 1, stop))
                    if (rest.isEmpty()) return text
                    var at = stop
                    while (at - 1 > h && lines[at - 1].isBlank()) at--
                    lines.addAll(at, rest)
                    return lines.joinToString("\n")
                }
                val b = bullet(lines, item.anchor)
                if (b >= 0) {
                    val parts = add.lines().map { it.trim().removePrefix("- ").removePrefix("* ") }
                        .filter { p -> p.isNotBlank() && p !in lines[b] && chapterOf(p)?.let { hasChapter(listOf(lines[b]), it) } != true }
                    if (parts.isEmpty()) return text
                    lines[b] = lines[b].trimEnd() + "；" + parts.joinToString("；")
                    return lines.joinToString("\n")
                }
                // 还没有这一节：在末尾新起一节。
                if (text.isBlank()) "## ${item.anchor}\n$add" else text.trimEnd() + "\n\n## ${item.anchor}\n" + add
            }
        }
    }
}

/**
 * 把作者勾选的提议写进设定包：同一份文档的几条依次写进去，写之前把原来的文字存一个历史版本；
 * 没有对应文档的新建一份。每写一条在变更登记里记一笔“整理第 N 章：……”。返回写进了哪几份文档、各几条。
 * 写的时候用已经写进去的文字再查一遍（[PackSync.apply]）：前几章写过的同一个人、上次写到一半出错已经写进去的，跳过、不计数，
 * 所以出错以后重试不会写出两份。
 */
fun WorkStore.applySync(workId: String, items: List<SyncItem>): Map<String, Int> {
    val work = requireNotNull(get(workId)) { "作品不在了" }
    val docs = pack(workId)
    val changed = LinkedHashMap<String, String>()
    val created = LinkedHashMap<Pair<PackKind, String>, String>()
    val counts = LinkedHashMap<String, Int>()
    val done = mutableListOf<Pair<SyncItem, String>>()
    items.filter { it.writable }.forEach { item ->
        val fallback = item.docTitle.ifBlank { item.kind.label }
        // 要新建的文档：上次写到一半出错时可能已经建好了，就写进那一份，不再建第二份。
        val id = item.docId?.takeIf { id -> docs.any { it.id == id } } ?: docs.firstOrNull { it.kind == item.kind && it.title == fallback }?.id
        val title = if (id != null) docs.first { it.id == id }.title else fallback
        if (id != null) {
            val before = changed[id] ?: packText(workId, id)
            val after = PackSync.apply(before, item)
            if (after == before) return@forEach
            changed[id] = after
        } else {
            val key = item.kind to title
            val before = created[key].orEmpty()
            val after = PackSync.apply(before, item)
            if (after == before) return@forEach
            created[key] = after
        }
        counts[title] = (counts[title] ?: 0) + 1
        done += item to title
    }
    changed.forEach { (id, text) ->
        val old = packText(workId, id)
        if (old == text) return@forEach
        if (old.isNotBlank()) snapshot(workId, docKey(id), old)
        savePackText(workId, id, text)
    }
    created.forEach { (key, text) -> addPackDoc(workId, key.first, key.second, text) }
    done.forEach { (item, title) ->
        val chapterTitle = work.chapter(item.chapterId)?.title.orEmpty()
        addChange(workId, ChangeEntry(WorkStore.newId(), System.currentTimeMillis(), ChangeKind.CANON, item.chapterId, chapterTitle, "整理第${item.chapter}章：${item.title}（《$title》）", canon = true, synced = true))
    }
    return counts
}
