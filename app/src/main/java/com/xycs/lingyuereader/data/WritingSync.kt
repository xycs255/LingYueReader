package com.xycs.lingyuereader.data

import java.security.MessageDigest

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * “写完一章，整理进设定包”：AI 读完一章，提出要补进设定包的新设定、和设定包对不上的冲突，
 * 以及随章变化、要记进故事账本的（人物状态、发生的事、谁知道了什么、埋下和收起的伏笔、故事里的时间），
 * 作者逐条勾选、改过，才写进去。这里是不联网的部分：定位写进哪份文档哪一处（或账本）、写进去、记下哪些章整理过。
 */

/** 整理过的一章：整理时正文的哈希（[ChapterSyncs.hash]，早先记的是 [String.hashCode]，也认）、什么时候整理的。 */
data class SyncMark(val hash: Int, val at: Long)

/** 整理结果的几组：前两组写进设定包，后面几组记进故事账本。 */
enum class SyncGroup(val label: String) {
    NEW("新设定"), CONFLICT("冲突"), STATE("人物状态"), EVENT("发生的事"), AWARENESS("察觉"), PROMISE("伏笔"), TIME("时间"),
    /** 数值与持有：只在作品打开了这一类（[WorkAssist.holdings]）时才有。 */
    HOLD("数值与持有");

    /** 记进故事账本的组（冲突里也可能有改账本的，看 [SyncItem.place]）。 */
    val ledger: Boolean get() = this != NEW && this != CONFLICT
}

/**
 * 写进文档的方式：表格追加一行、追加到某一节、追加到文档末尾、换掉设定包里的一句；[LEDGER] 是记进故事账本；
 * [NONE] 是不写（只提示）。
 */
enum class SyncPlace { ROW, SECTION, END, REPLACE, LEDGER, NONE }

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
    /** 这一章在第几卷：账本里只写了“第几卷起”的条目按它判断到没到。 */
    val volume: Int? = null,
    val docId: String? = null,
    val docTitle: String = "",
    val place: SyncPlace = SyncPlace.END,
    /** [SyncPlace.SECTION] 是节名（人物名），[SyncPlace.REPLACE] 是要换掉的设定包原文。 */
    val anchor: String = "",
    /** 记进账本的那一条（[text] 是它的内容，作者可以改）；账本里已有这一条（同一个 id）时是改它。 */
    val entry: LedgerEntry? = null,
    /** 收伏笔：收的是账本里的哪一条。 */
    val closes: LedgerEntry? = null,
    /** A SECTION planned against an existing heading or bullet must not create a new section if it disappears. */
    val plannedSectionExists: Boolean? = null,
    /** 账本里看起来像同一条、又不完全一样的那一条：照常列出，标“可能重复”，默认不勾，让作者自己看。 */
    val maybeSame: LedgerEntry? = null,
    /** 揭晓：正文里这个人知道了账本里只有作者知道的那一条（[LedgerEntry.secret]）。 */
    val reveals: LedgerEntry? = null,
    /** 推进伏笔：推进的是账本里的哪一条（AI 引用了它的编号、附了原句）。 */
    val advances: LedgerEntry? = null,
    /** 数值与持有：这一条的期初和账本里同一人同一项最新记的期末（这一条）对不上。照常列出，默认不勾。 */
    val holdMismatch: LedgerEntry? = null,
) {
    /** 能写进去的（“正文写错了”的冲突只提示）。 */
    val writable: Boolean
        get() = when (place) {
            SyncPlace.NONE -> false
            SyncPlace.LEDGER -> closes != null || advances != null || (entry != null && text.isNotBlank())
            else -> text.isNotBlank()
        }

    /** 默认勾不勾：新设定和记进账本的勾上，冲突、可能和账本里某条重复的要作者自己看过再勾。 */
    val defaultOn: Boolean get() = writable && group != SyncGroup.CONFLICT && maybeSame == null && holdMismatch == null

    /** 认这一条（勾选、改过的文字按它记）。 */
    val key: String get() = "$chapterId|${group.name}|$title|$quote"

    fun field(name: String): String = fields.firstOrNull { it.first == name }?.second.orEmpty()

    /** 写在哪：“写进《人物档案》· 追加一行”。 */
    val where: String
        get() = when (place) {
            SyncPlace.NONE -> if (fixText) "正文写错了，只提示，不改设定包" else "不写进设定包"
            SyncPlace.LEDGER -> "记进故事账本"
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

    /** Exact in-memory source check (apart from CRLF/LF) for pending proposals; SyncMark keeps its older hash. */
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** 这一章整理过、整理以后正文没改过（排版不算改过）。早先记的是正文直接的哈希，也认。 */
    fun isSynced(mark: SyncMark?, text: String): Boolean = mark != null && (mark.hash == hash(text) || mark.hash == text.hashCode())

    /** 有正文、还没整理（或整理以后正文改过）的章，按目录顺序。没整理过的章不用读正文。 */
    fun unsynced(chapters: List<WorkChapter>, marks: Map<String, SyncMark>, text: (String) -> String): List<WorkChapter> =
        chapters.filter { c -> c.words > 0 && marks[c.id]?.let { isSynced(it, text(c.id)) } != true }

    /** 整理一章时带给 AI 的账本现状最多多少字。 */
    const val LEDGER_CHARS = 3_000

    /**
     * 整理第 [chapter] 章时带给 AI 的账本现状：到上一章为止开着的伏笔（每条一个短编号，收伏笔时 AI 用编号指认）、
     * 出场人物 [present] 此刻的状态、他们已经知道的事。不给这一章和后面的（防剧透），也不给只有作者知道的。
     * 最多 [limit] 字，放不下的省略并注明；伏笔放最前面。账本里没有可给的返回空的。
     */
    fun ledgerView(entries: List<LedgerEntry>, chapter: Int, volume: Int? = null, present: Collection<String> = emptyList(), limit: Int = LEDGER_CHARS,
                   holdings: Boolean = false): SyncLedger {
        val names = present.filter { it.isNotBlank() }.toSet()
        val snap = StoryLedger.snapshot(entries, chapter - 1, volume, names)
        // 没有出场人物时 snapshot 不按人物筛：这里人物状态、知情都不给。
        val states = if (names.isEmpty()) emptyList() else snap.states.filter { it.subject in names }
        val known = if (names.isEmpty()) emptyList() else snap.known
        // 数值与持有：开着这一类时给出场人物的当前值，AI 写期初照它写。
        val holds = if (!holdings || names.isEmpty()) emptyList() else snap.holds
        if (snap.open.isEmpty() && states.isEmpty() && known.isEmpty() && holds.isEmpty()) return SyncLedger("", emptyMap())
        val out = StringBuilder("【故事账本：到上一章（第 ${chapter - 1} 章）为止】\n")
        val codes = LinkedHashMap<String, LedgerEntry>()
        var skipped = 0
        // 留出末尾注明省略的地方。
        val room = limit - 60
        fun at(e: LedgerEntry) = e.whenLabel.let { if (it.isEmpty()) "" else "（$it）" }
        fun section(title: String, list: List<LedgerEntry>, listed: (LedgerEntry) -> Unit = {}, line: (LedgerEntry) -> String) {
            if (list.isEmpty()) return
            val head = "\n$title\n"
            if (out.length + head.length > room) {
                skipped += list.size
                return
            }
            out.append(head)
            list.forEach { e ->
                val l = "- " + line(e).take(200) + "\n"
                if (out.length + l.length > room) {
                    skipped++
                } else {
                    out.append(l)
                    listed(e)
                }
            }
        }
        // 伏笔列出了才给编号：编号连着，没列出的没有编号。
        section("开着的伏笔（推进、收伏笔时用这里的编号指认）：", snap.open, listed = { codes["P${codes.size + 1}"] = it }) { e ->
            "P${codes.size + 1}：${e.text}${at(e)}" + (e.lastAdvanced?.takeIf { it < chapter }?.let { "（最近推进：第 $it 章）" }.orEmpty())
        }
        section("这一章出场人物此刻的状态：", states) { e -> e.subject + (if (e.aspect.isNotBlank()) "·" + e.aspect else "") + "：" + e.text + at(e) }
        section("出场人物现在的数值与持有（期初照这里写）：", holds) { e -> Holdings.line(e) }
        section("已有的知情：", known) { e ->
            (if (e.subject.isNotBlank()) e.subject + "：" else "") + e.text + at(e) + (if (e.via.isNotBlank()) "，消息来自" + e.via else "")
        }
        if (skipped > 0) out.append("（账本太长，还有 $skipped 条没列出；没列出的伏笔不要收）\n")
        return SyncLedger(out.toString().trim(), codes)
    }
}

/** 整理一章时带给 AI 的账本现状：[text] 是拼好的文字（没有可给的为空），[codes] 是开着的伏笔的短编号（P1、P2……）各是哪一条。 */
data class SyncLedger(val text: String, val codes: Map<String, LedgerEntry>)

/** 这部作品里有正文、还没整理的章。[marks] 不给就按存盘的 AI 设置。 */
fun WorkStore.unsynced(workId: String, marks: Map<String, SyncMark>? = null): List<WorkChapter> =
    ChapterSyncs.unsynced(get(workId)?.chapters.orEmpty(), marks ?: assist(workId).synced) { text(workId, it) }

/**
 * 整理第 [number] 章时带给 AI 的账本现状（[ChapterSyncs.ledgerView]）。出场人物：人物档案里的人（连同别名）、
 * 账本里记过的人，名字在正文 [text] 里出现的。
 */
fun WorkStore.syncLedger(workId: String, chapterId: String, number: Int, text: String): SyncLedger {
    val work = get(workId) ?: return SyncLedger("", emptyMap())
    val entries = ledger(workId)
    if (entries.isEmpty()) return SyncLedger("", emptyMap())
    val docs = pack(workId).filter { it.kind == PackKind.CHARACTER }.map { it.title to packText(workId, it.id) }
    val people = ChapterBriefs.characters(docs).filter { c -> c.names.any { it.length >= 2 && it in text } }.flatMap { it.names }
    val present = (people + entries.map { it.subject }.filter { it.length >= 2 && it in text }).toSet()
    return ChapterSyncs.ledgerView(entries, number, work.volumeNumber(chapterId), present, holdings = assist(workId).holdings)
}

/** 这部作品里的人名：人物档案里的人（连同别名）、账本里记过的人。整理时核对原句“不能只是人名”用。 */
fun WorkStore.syncNames(workId: String): Set<String> {
    val docs = pack(workId).filter { it.kind == PackKind.CHARACTER }.map { it.title to packText(workId, it.id) }
    return (ChapterBriefs.characters(docs).flatMap { it.names } + ledger(workId).map { it.subject.trim() })
        .filter { it.length >= 2 }.toSet()
}

object PackSync {
    /** 人物档案、察觉表的表格里各项对应哪一列（表头含这些字）。 */
    private val characterColumns = listOf(
        "人物" to listOf("人物", "角色", "姓名", "名字"),
        "称呼" to listOf("称呼", "别名", "又名", "称谓", "外号"),
        "身份" to listOf("身份", "职业", "出身", "简介", "设定"),
        "关系" to listOf("关系"),
        "本章" to listOf("本章", "经历", "事件", "出场", "首次", "备注", "说明"),
    )

    /** 记进账本的那几条在 [WorkStore.applySync] 的结果里算在这个名字下。 */
    const val LEDGER = "故事账本"

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

    /** 察觉的“谁”。 */
    private fun whoOf(item: SyncItem) = item.field("人物").ifBlank { item.name }.trim()

    private fun stateWho(item: SyncItem) = clean(item.entry?.subject?.ifBlank { item.name } ?: item.name)

    /** 状态里的否定、程度、位置细节不能靠包含关系判成相同；只忽略空白和标点差异。 */
    private fun sameState(a: String, b: String): Boolean {
        fun value(s: String) = ChapterSyncs.normalize(s).replace(Regex("\\p{P}"), "")
        return value(a).let { it.isNotEmpty() && it == value(b) }
    }

    /**
     * 两条提议说的是不是同一个新人物、同一处地方（同名），或同一个人知道了同一件事（跨章、分段去重用）。
     * 给已有人物补本章经历的（追加到他那一节的）每章各记各的，不算重复；冲突不比。
     * 账本类：同一人物同一方面的同一状态、同一件事、同一条伏笔（收伏笔看收的是不是同一条）；时间每章各记各的，同一章的几段只留一条。
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
            SyncGroup.STATE -> stateWho(a).let { it.isNotEmpty() && it == stateWho(b) } && a.entry?.aspect.orEmpty() == b.entry?.aspect.orEmpty() && sameState(a.text, b.text)
            SyncGroup.EVENT -> similar(a.text, b.text)
            SyncGroup.PROMISE -> when {
                a.closes != null || b.closes != null -> a.closes?.id == b.closes?.id
                a.advances != null || b.advances != null -> a.advances?.id == b.advances?.id && a.chapterId == b.chapterId
                else -> similar(a.text, b.text)
            }
            SyncGroup.HOLD -> a.entry?.let { x -> b.entry?.let { y -> x.subject == y.subject && x.aspect == y.aspect && a.chapterId == b.chapterId } } == true
            SyncGroup.TIME -> a.chapterId == b.chapterId
            SyncGroup.CONFLICT -> false
        }
    }

    /**
     * 跨章默认去重：人物状态只和同一人物同一方面的上一条有效状态比。
     * “在渡口 → 在京城 → 在渡口”最后一次回到渡口是新变化，不能拿第一章的状态抹掉。
     * 没有方面的状态彼此独立，不替作者猜哪些属于同一方面；真正写入时仍会检查重复。
     */
    fun repeatedBefore(item: SyncItem, previous: List<SyncItem>): Boolean {
        val before = previous.filter { it.chapterId != item.chapterId }
        if (item.group != SyncGroup.STATE) return before.any { sameThing(it, item) }
        val state = item.entry ?: return false
        val who = clean(state.subject.ifBlank { item.name })
        if (who.isEmpty() || state.aspect.isBlank()) return false
        val latest = before.lastOrNull { old ->
            val entry = old.entry
            old.group == SyncGroup.STATE && entry != null && entry.validAt(item.chapter, item.volume) &&
                clean(entry.subject.ifBlank { old.name }) == who && entry.aspect == state.aspect
        } ?: return false
        return sameState(latest.text, item.text)
    }

    /**
     * 同一章分段整理时几段提的同一个人、同一件事并成一条：留前面那条；人物的称呼、身份、关系空着的用后面的补上，本章经历接在后面。
     * 数值与持有例外：期初用前一段的、期末用后一段的，变化接起来，记的是这一章从头到尾的账。
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
            if (first.group == SyncGroup.HOLD) {
                out[i] = holdMerged(first, item)
                return@forEach
            }
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

    /** 同一章前后两段记的同一人同一项：期初取前一段，期末（和依据）取后一段，变化按先后接起来。 */
    private fun holdMerged(first: SyncItem, later: SyncItem): SyncItem {
        val a = first.entry ?: return later
        val b = later.entry ?: return first
        val before = a.before.ifBlank { b.before }
        val delta = listOf(a.delta, b.delta).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("；")
        val title = "${b.subject}·${b.aspect}：" + listOf(before, delta, b.text).filter { it.isNotEmpty() }.joinToString(" → ")
        return later.copy(title = title, entry = b.copy(before = before, delta = delta))
    }

    /** 比较用的写法：去掉开头的“知道了”这类字、标点和空白。 */
    private fun norm(s: String) = s.trim().replace(Regex("^(知道|得知|知晓|发现|察觉|明白)了?"), "").replace(Regex("[\\p{P}\\p{S}\\s]"), "")

    /** 句子里的数目：阿拉伯数字、连写的中文数字（单独一个“一”多是“一直”“一起”，不算）。 */
    private val NUMBER = Regex("[0-9０-９]+|[零〇一二两三四五六七八九十百千万]{2,}|[零〇二两三四五六七八九十百千万]")

    /** 两句完全是同一句（规范化以后一样）：定位置时直接跳过、写之前再查一遍（重试不写出两份）只认这个。 */
    internal fun same(a: String, b: String): Boolean = norm(a).let { it.isNotEmpty() && it == norm(b) }

    /**
     * 两句看起来像不像同一件事：只用来标“可能重复”（默认不勾）、跨章标“前面章已有”、同一章分段并成一条，不用来直接丢掉。
     * 人名（[names]：账本、人物档案里的名字）和数目要一致；去掉相同的开头结尾、两边只剩一两个不同的字的，
     * 是换了一个人、一个数、一处地方（“林远杀了赵四”“林远杀了赵五”），不算；
     * 其余：短的一方至少 4 字、占长的一方六成以上且被包含（或只是中间少了几个字），或两两相连的字七成以上相同。
     */
    internal fun similar(a: String, b: String, names: Collection<String> = emptyList()): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (numbers(x) != numbers(y)) return false
        if (names.any { n -> n.length >= 2 && (n in x) != (n in y) }) return false
        return similarShape(x, y)
    }

    private fun numbers(s: String) = NUMBER.findAll(s).map { it.value }.toSet()

    /** [similar] 的后半段：两边已经规范化、不相同，数目和人名已经一致。 */
    private fun similarShape(x: String, y: String): Boolean {
        val short = minOf(x.length, y.length)
        var head = 0
        while (head < short && x[head] == y[head]) head++
        var tail = 0
        while (tail < short - head && x[x.length - 1 - tail] == y[y.length - 1 - tail]) tail++
        if (x.length - head - tail in 1..2 && y.length - head - tail in 1..2) return false
        // 包含：短的一方整句在长的一方里，或者只是中间多了几个字（“沈默是卧底”“沈默是朝廷的卧底”）。
        if (short >= 4 && short * 5 >= maxOf(x.length, y.length) * 3 && (head + tail >= short || x in y || y in x)) return true
        fun grams(s: String) = s.windowed(2).toSet()
        val gx = grams(x)
        val gy = grams(y)
        if (gx.isEmpty() || gy.isEmpty()) return false
        return 2.0 * gx.intersect(gy).size / (gx.size + gy.size) >= 0.7
    }

    /**
     * 账本的查重索引：[plan] 里一次建好，每条提议只和可能像的比。以前每条提议都和账本里同类的每一条比，
     * 每比一次都跑两遍正则、把人名表扫一遍；账本几千条、人名几百个时整理一章要卡几秒。
     * - 完全一样（[same]）：按规范化以后的文字建表，直接查；
     * - 看起来像（[similar]）：数目要一样、各个人名在不在要一样，按这两样分组，只和同一组的比。
     * 结果和逐条比一样：同一组里按账本原来的顺序找第一条。
     */
    internal class LedgerIndex(val ledger: List<LedgerEntry>, names: Collection<String> = emptyList()) {
        private val keyNames = names.filter { it.length >= 2 }.distinct()
        private val norms: Array<String> = Array(ledger.size) { norm(ledger[it].text) }
        private val exact: Map<String, List<Int>> by lazy { ledger.indices.filter { norms[it].isNotEmpty() }.groupBy { norms[it] } }
        private val groups: Map<List<Any>, List<Int>> by lazy { ledger.indices.filter { norms[it].isNotEmpty() }.groupBy { signature(norms[it]) } }

        private fun signature(x: String): List<Any> = listOf(numbers(x), keyNames.filter { it in x })

        /** 账本里有没有和 [text] 完全一样（[same]）、又满足 [accept] 的一条。 */
        fun anySame(text: String, accept: (LedgerEntry) -> Boolean): Boolean {
            val y = norm(text)
            if (y.isEmpty()) return false
            return exact[y]?.any { accept(ledger[it]) } == true
        }

        /** 账本里第一条和 [text] 看起来像（[similar]，带着建索引时的人名）、又满足 [accept] 的。 */
        fun firstSimilar(text: String, accept: (LedgerEntry) -> Boolean): LedgerEntry? {
            val y = norm(text)
            if (y.isEmpty()) return null
            val candidates = groups[signature(y)] ?: return null
            for (i in candidates) {
                val e = ledger[i]
                if (accept(e) && (norms[i] == y || similarShape(norms[i], y))) return e
            }
            return null
        }
    }

    /** 挑写进哪份文档：AI 说的标题对得上就用它，否则这一类的第一份，没有就新建（返回 null）。 */
    private fun target(item: SyncItem, docs: List<Pair<PackDoc, String>>): Pair<PackDoc, String>? {
        val named = item.doc.takeIf { it.isNotBlank() }?.let { d -> docs.firstOrNull { clean(it.first.title) == clean(d) } }
        if (named != null && (named.first.kind == item.kind || item.kind == PackKind.REFERENCE)) return named
        return docs.firstOrNull { it.first.kind == item.kind } ?: named
    }

    private fun SyncItem.into(doc: Pair<PackDoc, String>?, place: SyncPlace, text: String, anchor: String = ""): SyncItem =
        copy(docId = doc?.first?.id, docTitle = doc?.first?.title ?: docTitle.ifBlank { kind.label },
            kind = doc?.first?.kind ?: kind, place = place, text = text, anchor = anchor,
            plannedSectionExists = if (place == SyncPlace.SECTION && doc != null) section(doc.second.lines(), anchor) != null else null)

    /**
     * 给 AI 的提议定位置，写好要写进去的原文；设定包里已经有的、账本里完全一样的丢掉，账本里看起来像的照常列出、标“可能重复”。
     * 察觉表、账本里已经有、但写的是更晚的章才知道的，改成冲突。
     * [docs] 是现在的设定包（文档和正文），[ledger] 是现在的故事账本。
     */
    fun plan(items: List<SyncItem>, docs: List<Pair<PackDoc, String>>, ledger: List<LedgerEntry> = emptyList()): List<SyncItem> {
        val people = ChapterBriefs.characters(docs.filter { it.first.kind == PackKind.CHARACTER }.map { it.first.title to it.second }).flatMap { it.names }.toSet()
        val characterText = docs.filter { it.first.kind == PackKind.CHARACTER }.joinToString("\n") { it.second }
        val allText = docs.joinToString("\n") { it.second }
        // 认“像不像同一条”时要一致的人名：人物档案里的（连同别名）、账本里记过的。
        val names = people + ledger.map { it.subject.trim() }.filter { it.length >= 2 }
        val index = LedgerIndex(ledger, names)
        return items.mapNotNull { item ->
            when (item.group) {
                SyncGroup.NEW -> planNew(item, docs, people, characterText, allText)
                SyncGroup.AWARENESS -> planAwareness(item, docs, index)
                SyncGroup.CONFLICT -> planConflict(item, docs)
                SyncGroup.STATE, SyncGroup.EVENT, SyncGroup.PROMISE, SyncGroup.TIME ->
                    item.takeIf { (it.entry != null || it.closes != null || it.advances != null) && !inLedger(it, index) }
                        ?.let { it.copy(place = SyncPlace.LEDGER, maybeSame = alike(it, index)) }
                SyncGroup.HOLD -> item.takeIf { it.entry != null && !inLedger(it, index) }
                    ?.let { it.copy(place = SyncPlace.LEDGER, holdMismatch = holdMismatch(it, ledger)) }
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

    /**
     * 察觉记进账本（知情）。察觉表里已经有、不晚于这一章的不再提，写的是后面的章的改成冲突（勾上改察觉表那一行）；
     * 账本里也一样，写的是后面的章（后面的卷）的改成冲突，勾上把账本那一条改成从这一章起。
     * 账本里只有作者知道的那几条（[LedgerEntry.secret]）不算谁已经知道：正文里有人知道了，是揭晓，照常提、默认勾上。
     */
    private fun planAwareness(item: SyncItem, docs: List<Pair<PackDoc, String>>, index: LedgerIndex): SyncItem? {
        val who = item.field("人物").ifBlank { item.name }.trim()
        val what = item.field("知道什么").trim()
        if (who.isEmpty() || what.isEmpty()) return null
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
        val entry = item.entry ?: LedgerEntry(
            kind = LedgerKind.KNOW, text = what, subject = who, from = ChapterRefs.number(item.field("从第几章起")) ?: item.chapter,
            via = item.field("消息从哪来"), quote = item.quote, chapterId = item.chapterId, origin = LedgerOrigin.SYNC,
        )
        val planned = item.copy(place = SyncPlace.LEDGER, text = what, entry = entry, docId = null, docTitle = LEDGER)
        val later = index.firstSimilar(what) {
            it.kind == LedgerKind.KNOW && !it.secret && sameWho(it.subject, who) && !it.reached(item.chapter, item.volume)
        }
        if (later == null) {
            if (inLedger(planned, index)) return null
            // 账本里记着这件事只有作者知道（导入时察觉表“真相”那一格）：这一章他知道了。
            val secret = index.firstSimilar(what) {
                it.kind == LedgerKind.KNOW && it.secret && (it.subject.isBlank() || sameWho(it.subject, who)) && it.validAt(item.chapter, item.volume)
            }
            if (secret != null) return planned.copy(title = "${who}从第${entry.from ?: item.chapter}章起知道了（这条原本只有作者知道）", reveals = secret)
            return planned.copy(maybeSame = alike(planned, index))
        }
        val at = later.from?.let { "第${it}章" } ?: later.volume?.let { "第${it}卷" } ?: "后面"
        return planned.copy(
            group = SyncGroup.CONFLICT,
            title = "账本里写$who ${at}才知道，正文第${item.chapter}章就知道了",
            packSays = later.text + later.whenLabel.let { if (it.isEmpty()) "" else "（$it）" },
            textSays = what,
            text = later.text,
            entry = later.copy(from = item.chapter, volume = null, quote = item.quote, chapterId = item.chapterId),
        )
    }

    /** 是不是同一个人：名字（去掉空白、书名号）完全一样；“林远”和“林远山”是两个人。 */
    private fun sameWho(a: String, b: String): Boolean {
        val x = clean(a)
        return x.isNotEmpty() && x == clean(b)
    }

    /**
     * 账本里已经有这一条了（定位置、写之前、重试时再查一遍用）：人物状态看那个人那一方面到这一章为止最新的一条；
     * 发生的事、伏笔看有没有完全一样的（[same]）；知情看同一个人是不是已经记过知道这件事（只有作者知道的不算）；时间看这一章记过没有。
     * 收伏笔：那一条已经收了（或者账本里已经没有那一条了）。改账本已有一条的（冲突里改章号的）：已经改成这一章了。
     * 只是看起来像的不算有（[alike]），照常列出给作者看。
     */
    fun inLedger(item: SyncItem, ledger: List<LedgerEntry>): Boolean = inLedger(item, LedgerIndex(ledger))

    private fun inLedger(item: SyncItem, index: LedgerIndex): Boolean {
        val ledger = index.ledger
        item.closes?.let { c -> return ledger.firstOrNull { it.id == c.id }?.done ?: true }
        // 推进：那一条已经记到这一章或更后面推进过了、已经收了，或者账本里没有那一条了。
        item.advances?.let { a ->
            val now = ledger.firstOrNull { it.id == a.id } ?: return true
            return now.done || (now.lastAdvanced ?: Int.MIN_VALUE) >= item.chapter
        }
        val e = item.entry ?: return true
        ledger.firstOrNull { it.id == e.id }?.let { return it.from == e.from && it.volume == e.volume }
        val text = item.text.ifBlank { e.text }
        val n = item.chapter
        return when (e.kind) {
            LedgerKind.STATE -> {
                val same = ledger.filter { it.kind == LedgerKind.STATE && clean(it.subject) == clean(e.subject) && it.validAt(n, item.volume) }
                if (e.aspect.isBlank()) same.any { sameState(it.text, text) }
                else same.filter { it.aspect == e.aspect }.maxWithOrNull(compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt }))?.let { sameState(it.text, text) } == true
            }
            LedgerKind.EVENT -> index.anySame(text) { it.kind == LedgerKind.EVENT }
            LedgerKind.KNOW -> index.anySame(text) { it.kind == LedgerKind.KNOW && !it.secret && sameWho(it.subject, e.subject) }
            LedgerKind.PROMISE -> index.anySame(text) { it.kind == LedgerKind.PROMISE }
            LedgerKind.TIME -> index.anySame(text) { it.kind == LedgerKind.TIME && it.from == e.from }
            // 数值与持有：同一人同一项这一章已经记过一样的期末。
            LedgerKind.HOLD -> ledger.any { it.kind == LedgerKind.HOLD && clean(it.subject) == clean(e.subject) && it.aspect == e.aspect && it.from == e.from && sameState(it.text, text) }
        }
    }

    /**
     * 数值与持有的一条提议，期初对不上账本里同一人同一项、这一章之前最新的那一条的期末：返回那一条（确认页上标出来，默认不勾）。
     * 账本里还没有这一项、提议没写期初的不比。
     */
    internal fun holdMismatch(item: SyncItem, ledger: List<LedgerEntry>): LedgerEntry? {
        val e = item.entry ?: return null
        if (e.before.isBlank()) return null
        val last = ledger.filter {
            it.kind == LedgerKind.HOLD && clean(it.subject) == clean(e.subject) && it.aspect == e.aspect && (it.from ?: 0) < item.chapter
        }.maxWithOrNull(compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt })) ?: return null
        val differs = if (e.item && last.item) Holdings.held(last) != Holdings.held(last.copy(text = e.before)) else Holdings.norm(last.text) != Holdings.norm(e.before)
        return last.takeIf { differs }
    }

    /**
     * 账本里看起来像同一条、又不完全一样的那一条（[similar]，[names] 是要一致的人名），没有是 null。
     * 人物状态、收伏笔不比：它们有各自精确的判定（[inLedger]）。知情只和同一个人已经知道了的比，只有作者知道的不算。
     */
    internal fun alike(item: SyncItem, ledger: List<LedgerEntry>, names: Collection<String> = emptyList()): LedgerEntry? = alike(item, LedgerIndex(ledger, names))

    private fun alike(item: SyncItem, index: LedgerIndex): LedgerEntry? {
        if (item.closes != null) return null
        val e = item.entry ?: return null
        val text = item.text.ifBlank { e.text }
        return when (e.kind) {
            LedgerKind.STATE -> null
            LedgerKind.EVENT -> index.firstSimilar(text) { it.kind == LedgerKind.EVENT }
            LedgerKind.KNOW -> index.firstSimilar(text) {
                it.kind == LedgerKind.KNOW && !it.secret && sameWho(it.subject, e.subject) && it.reached(item.chapter, item.volume)
            }
            LedgerKind.PROMISE -> index.firstSimilar(text) { it.kind == LedgerKind.PROMISE }
            LedgerKind.TIME -> index.firstSimilar(text) { it.kind == LedgerKind.TIME && it.from == e.from }
            LedgerKind.HOLD -> null
        }
    }

    /**
     * 一条记进账本的提议对应的改动：收伏笔是 [LedgerOp.Close]；账本 [ledger] 里已经有这一条（同一个 id，冲突里改章号的）是 [LedgerOp.Update]，
     * 只改章号、依据和内容，其余照账本现在的样子（不拿整理时看到的旧样子覆盖）；否则是 [LedgerOp.Add]。内容用 [SyncItem.text]（作者可能改过）。
     * 揭晓（[SyncItem.reveals]）还要把那一条只有作者知道的设成到这一章止：这个人从这一章起知道了，它对他不再是秘密。
     */
    fun ledgerOps(item: SyncItem, ledger: List<LedgerEntry>): List<LedgerOp> {
        item.closes?.let { return listOf(LedgerOp.Close(it.id, item.chapter.takeIf { n -> n > 0 }, item.quote, item.chapterId)) }
        item.advances?.let { return if (item.chapter > 0) listOf(LedgerOp.Advance(it.id, item.chapter, item.quote)) else emptyList() }
        val e = item.entry ?: return emptyList()
        val text = item.text.trim().ifEmpty { return emptyList() }
        ledger.firstOrNull { it.id == e.id }?.let { current ->
            return listOf(LedgerOp.Update(current.copy(text = text, from = e.from, volume = e.volume, quote = e.quote, chapterId = e.chapterId)))
        }
        val add = LedgerOp.Add(e.copy(text = text))
        val from = e.from ?: item.chapter
        val secret = item.reveals?.let { r -> ledger.firstOrNull { it.id == r.id } }
            ?.takeIf { it.secret && sameWho(it.subject, e.subject) && (it.until == null || it.until > from) }
            ?: return listOf(add)
        return listOf(add, LedgerOp.Update(secret.copy(until = from)))
    }

    /** [ledgerOps] 的第一条（收伏笔、加一条、改一条）。 */
    fun ledgerOp(item: SyncItem, ledger: List<LedgerEntry>): LedgerOp? = ledgerOps(item, ledger).firstOrNull()

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
            SyncPlace.NONE, SyncPlace.LEDGER -> text
            SyncPlace.END -> end(text)
            SyncPlace.REPLACE -> {
                val at = text.indexOf(item.anchor)
                check(item.anchor.isNotEmpty() && at >= 0 && text.indexOf(item.anchor, at + 1) < 0) {
                    "《${item.docTitle}》原定要替换的文字已变化，请重新整理后核对"
                }
                text.replaceRange(at, at + item.anchor.length, add)
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
                check(item.plannedSectionExists != true) { "《${item.docTitle}》原定小节已变化，请重新整理后核对" }
                // 还没有这一节：在末尾新起一节。
                if (text.isBlank()) "## ${item.anchor}\n$add" else text.trimEnd() + "\n\n## ${item.anchor}\n" + add
            }
        }
    }
}

/**
 * 把作者勾选的提议写进设定包：同一份文档的几条依次写进去，写之前把原来的文字存一个历史版本；
 * 没有对应文档的新建一份。每写一条在变更登记里记一笔“整理第 N 章：……”。返回写进了哪几份文档、各几条
 * （记进账本的算在 [PackSync.LEDGER] 下）。
 * 写的时候用已经写进去的文字再查一遍（[PackSync.apply]）：前几章写过的同一个人、上次写到一半出错已经写进去的，跳过、不计数，
 * 所以出错以后重试不会写出两份。
 * 记进账本的：按章号先后，用账本现在的样子（连同前面刚记的）逐条再查一遍（[PackSync.inLedger]，完全一样才算有），已经有的跳过，
 * 所以重试、几章提了同一件事都只记一次。整次一批写进去（[WorkStore.applyLedger]），只存一个历史版本：
 * 批量整理几十章也只留“整理前”那一版，不会把更早的版本挤掉。账本有自己的历史版本，不再记变更登记。
 */
fun WorkStore.applySync(workId: String, items: List<SyncItem>, sourceResults: List<com.xycs.lingyuereader.ChapterSyncResult>): Map<String, Int> = withProcessLock {
    if (items.isEmpty()) return@withProcessLock emptyMap()
    check(sourceResults.isNotEmpty()) { "整理提议缺少原文快照，请重新整理后核对" }
    val work = requireNotNull(get(workId)) { "作品不在了" }
    val chosenChapters = items.mapTo(HashSet()) { it.chapterId }
    val selectedResults = sourceResults.filter { it.chapterId in chosenChapters }
    check(selectedResults.map { it.chapterId }.toSet().size == selectedResults.size) {
        "整理结果包含重复章节，请重新整理后核对"
    }
    check(selectedResults.size == chosenChapters.size) { "整理提议缺少对应章节的原文快照，请重新整理后核对" }
    val byChapter = selectedResults.associateBy { it.chapterId }
    check(items.all { item -> byChapter[item.chapterId]?.items?.any { it.key == item.key } == true }) {
        "整理提议与章节原文快照不匹配，请重新整理后核对"
    }
    check(selectedResults.all { it.error == null }) { "选中的章节尚未整理完成，请重新整理后核对" }
    selectedResults.forEach { result ->
        check(SHA256_HEX.matches(result.sourceDigest)) { "《${result.title}》缺少有效原文快照，请重新整理后核对" }
        val current = text(workId, result.chapterId)
        check(work.chapter(result.chapterId) != null && ChapterSyncs.digest(current) == result.sourceDigest) {
            "《${result.title}》正文已变化，本次整理提议已过时；请重新整理后核对"
        }
    }
    val docs = pack(workId)
    val changed = LinkedHashMap<String, String>()
    val created = LinkedHashMap<Pair<PackKind, String>, String>()
    val counts = LinkedHashMap<String, Int>()
    val done = mutableListOf<Pair<SyncItem, String>>()
    items.filter { it.writable && it.place != SyncPlace.LEDGER }.forEach { item ->
        val fallback = item.docTitle.ifBlank { item.kind.label }
        // 要新建的文档：上次写到一半出错时可能已经建好了，就写进那一份，不再建第二份。
        check(item.docId == null || docs.any { it.id == item.docId }) {
            "《${item.docTitle}》原定文档已删除，请重新整理后核对"
        }
        val id = item.docId ?: docs.firstOrNull { it.kind == item.kind && it.title == fallback }?.id
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
    var working = ledger(workId)
    val ops = mutableListOf<LedgerOp>()
    val written = mutableListOf<SyncItem>()
    items.filter { it.writable && it.place == SyncPlace.LEDGER }.sortedBy { it.chapter }.forEach { item ->
        if (PackSync.inLedger(item, working)) return@forEach
        val more = PackSync.ledgerOps(item, working).ifEmpty { return@forEach }
        // 用不上的改动（和已有的重复、找不到那一条的）不算。
        val next = StoryLedger.apply(working, more, 0).first
        if (next == working) return@forEach
        working = next
        ops += more
        written += item
    }
    if (ops.isNotEmpty()) {
        val chapters = written.map { it.chapter }.distinct()
        val label = if (chapters.size == 1) "整理第 ${chapters.single()} 章" else "批量整理第 ${chapters.first()}–${chapters.last()} 章（${chapters.size} 章）"
        applyLedger(workId, ops, label)
        counts[PackSync.LEDGER] = written.size
    }
    counts
}
