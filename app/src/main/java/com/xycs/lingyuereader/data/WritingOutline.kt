package com.xycs.lingyuereader.data

/**
 * 卷纲细纲里写某一章（或一组章）的一段：第 [from] 到 [to] 章、章名、内容、出自哪份文档。
 * [volume] 是它所在的卷（文档名或上级标题里写了“第X卷”时），按卷编章号的细纲靠它区分。
 */
data class OutlineEntry(
    val from: Int,
    val to: Int,
    val title: String,
    val text: String,
    val doc: String,
    val volume: Int? = null,
    val rank: Int = 0,
    /** “第12、14、40章接续：……”这样一条写好几章的：是互相参照，不当哪一章的细纲（挑资料时照样会挑到）。 */
    val shared: Boolean = false,
) {
    val single: Boolean get() = from == to
}

/**
 * 细纲卡的来历：[card] 是要用的细纲卡；[source] 不为空表示前六项是从细纲文档提取的（作者没改过）；
 * [outline] 是细纲文档里这一章现在的样子（作者改过卡片后，细纲又变了，可以拿来替换）；
 * [missing] 不为空表示有卷纲细纲、但没找到这一章，说明是按什么找的。
 */
data class PlannedCard(val card: ChapterCard, val source: String? = null, val outline: ChapterCard? = null, val outlineSource: String? = null, val missing: String? = null)

/**
 * 从卷纲细纲里认出每一章的细纲，拆成细纲卡的六项。认这几种写法：
 * - 标题：“### 第十九章 雪夜来客”，下面到下一个同级标题为止都是这一章；
 * - 列表项或段落：“- **第十九章 雪夜来客**：……；钩子：……”，缩进的下级列表也算；
 * - 表格：有“章”那一列，其他列按表头归到六项；
 * - 章组：“第十九至二十一章”，这一组里没有单独写的章用它；
 * - 后面的“补记”“修订”里再写到同一章的，接在后面，以后写的为准。
 */
object ChapterOutlines {
    private val headingLine = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val chapterHead = Regex("^第([^章第\\n]{1,24}?)章")
    private val listMarker = Regex("^(?:[-*+•]\\s+|\\d{1,3}[.、)）]\\s*|>\\s*)")
    private val volumeRef = Regex("第([^卷第\\n]{1,8}?)卷")
    private val label = Regex("^([^：:，,。；;（(]{1,8})[：:]\\s*")
    private val povRef = Regex("(?:^|[：:；;，,。、\\s（(])([\\u4e00-\\u9fa5]{2,3})视角")

    /** 从文档里认出所有写到某一章（或章组）的段落。[docs] 是（文档名，正文）。 */
    fun parse(docs: List<Pair<String, String>>): List<OutlineEntry> {
        val out = mutableListOf<OutlineEntry>()
        for ((name, raw) in docs) {
            val text = raw.removePrefix("\uFEFF")
            val docVolume = volumeIn(name)
            val tables = MdTable.parse(text)
            val tableStarts = tables.associateBy { it.start }
            val tableLines = tables.flatMap { t -> t.lines + listOf(t.start, t.start + 1) }.toSet()

            val headings = ArrayList<Pair<Int, String>>()
            fun volume(): Int? = headings.asReversed().firstNotNullOfOrNull { volumeIn(it.second) } ?: docVolume
            var cur: Block? = null
            fun finish() {
                cur?.let { b -> out += b.entries(name, volume()) }
                cur = null
            }
            val lines = text.lines()
            val fenced = MarkdownFences.mask(lines)
            lines.forEachIndexed { i, line ->
                // 项目包里的代码示例不是细纲；也不能混进正在解析的章节正文。
                if (fenced[i]) return@forEachIndexed
                val trimmed = line.trim()
                val h = headingLine.matchEntire(trimmed)
                if (h != null) {
                    val level = h.groupValues[1].length
                    val b = cur
                    if (b != null && (b.level == 0 || b.level >= level)) finish()
                    while (headings.isNotEmpty() && headings.last().first >= level) headings.removeAt(headings.lastIndex)
                    val title = h.groupValues[2].replace("*", "").trim()
                    headings += level to title
                    val open = cur
                    if (open != null) open.sub = title
                    else head(title, bold = true)?.let { hd -> cur = Block(level, 0, hd.ranges, hd.title, hd.shared).also { it.add(hd.rest) } }
                    return@forEachIndexed
                }
                if (i in tableLines) {
                    // A table without a 卷 column inherits its enclosing Markdown heading.
                    tableStarts[i]?.let { out += tableEntries(it, name, volume()) }
                    if (cur?.level == 0) finish()
                    return@forEachIndexed
                }
                val b = cur
                if (trimmed.isEmpty()) {
                    if (b != null) b.blank = true
                    return@forEachIndexed
                }
                val indent = line.length - line.trimStart().length
                val isItem = listMarker.containsMatchIn(trimmed)
                val body = trimmed.replace(listMarker, "")
                // 标题下面的内容都归这个标题，不再拆。
                if (b != null && b.level > 0) {
                    b.add(body)
                    return@forEachIndexed
                }
                val start = start(body)
                if (start != null) {
                    finish()
                    cur = Block(0, indent, start.ranges, start.title, start.shared).also { it.add(start.rest) }
                    return@forEachIndexed
                }
                if (b != null) {
                    // 空行后顶格的，或者同级的另一个列表项：这一章写完了。
                    if (indent <= b.indent && (b.blank || isItem)) finish() else b.add(body)
                }
            }
            finish()
        }
        return out
    }

    /** 章号后面的“（上）”“（下）”。 */
    private val partMark = Regex("^[（(]([上中下])[)）]")

    /** 一段的开头认出的章：章号（“第十二、十四章”这样不连着的分成几段）、章名（带“（上）”“（下）”）、后面的内容。 */
    private class Head(val ranges: List<IntRange>, val title: String, val rest: String) {
        val shared: Boolean get() = ranges.size > 1
    }

    /** 一段的开头（去掉列表符号以后）是不是“第X章……”。 */
    private fun start(body: String): Head? {
        if (body.startsWith("**")) {
            val close = body.indexOf("**", 2)
            if (close > 2) {
                val inner = body.substring(2, close)
                val after = body.substring(close + 2).trimStart('：', ':', ' ', '　')
                head(inner, bold = true)?.let { h ->
                    val rest = h.rest
                    val joined = if (rest.isEmpty() || after.isEmpty() || rest.last() in "。；;：:！？") rest + after else "$rest：$after"
                    return Head(h.ranges, h.title, joined)
                }
            }
            return null
        }
        return head(body.trimStart('【'), bold = false)
    }

    /**
     * “第十九章 雪夜来客：内容”“第二十一章《过河》落定。”“第二十九章 渡口（上）”“第十九至二十一章”“第30章黄家……”→ 章号、章名、内容。
     * 章号后面紧跟“的”“起”“末”这类字的是句子里提到某一章（“第十九章的钩子……”），不算。
     * 章名要有书名号，或者在标题、粗体里，或者空一格写、后面跟冒号；否则整段都当内容。
     */
    private fun head(text: String, bold: Boolean): Head? {
        val m = chapterHead.find(text) ?: return null
        val nums = ChapterRefs.find(m.value).distinct().sorted()
        if (nums.isEmpty()) return null
        val ranges = if (nums.last() - nums.first() + 1 == nums.size) listOf(nums.first()..nums.last()) else nums.map { it..it }
        var rest = text.substring(m.value.length)
        var part = partMark.find(rest)?.groupValues?.get(1)
        if (part != null) rest = rest.substring(3)
        val sep = rest.firstOrNull()
        if (!bold && sep != null && sep in "的之里中末前后起以时内外间初") return null
        val spaced = sep != null && sep in " 　\t"
        rest = rest.trimStart(' ', '　', '\t', '·', '—', '-', '.', '、', '】')
        var title = ""
        if (rest.startsWith("《") && rest.indexOf('》') > 0) {
            title = rest.substring(1, rest.indexOf('》'))
            rest = rest.substring(rest.indexOf('》') + 1)
        } else if ((bold || spaced) && !rest.startsWith("：") && !rest.startsWith(":")) {
            val end = rest.indexOfAny(charArrayOf('：', ':', '。', '；', '，', '（', '(')).let { if (it < 0) rest.length else it }
            val candidate = rest.substring(0, end).trim()
            val ends = end == rest.length || rest[end] in "：:" || bold || partMark.containsMatchIn(rest.substring(end))
            if (candidate.length in 1..20 && ends) {
                title = candidate
                rest = rest.substring(end)
            }
        }
        if (part == null && title.isNotEmpty()) partMark.find(rest)?.let { part = it.groupValues[1]; rest = rest.substring(3) }
        rest = rest.trimStart('：', ':', '。', ' ', '　').replace(Regex("^(?:落定|定稿|补记|修订|修改|调整)[。：:，]?"), "")
        val key = clean(title).let { if (it.isNotEmpty() && part != null) "$it（$part）" else it }
        return Head(ranges, key, rest.trim())
    }

    private class Block(val level: Int, val indent: Int, val ranges: List<IntRange>, val title: String, val shared: Boolean) {
        val lines = mutableListOf<String>()
        var blank = false
        /** 章标题下面的小标题（“钩子”“视角”……）：下面的行算在它名下。 */
        var sub: String? = null

        fun add(line: String) {
            if (line.isBlank()) return
            lines += sub?.let { s -> if (label.containsMatchIn(line)) line.trim() else "$s：${line.trim()}" } ?: line.trim()
        }

        fun entries(doc: String, volume: Int?): List<OutlineEntry> {
            val text = lines.joinToString("\n").replace("**", "").trim()
            if (text.isEmpty() && title.isEmpty()) return emptyList()
            return ranges.map { OutlineEntry(it.first, it.last, title, text, doc, volume, shared = shared) }
        }
    }

    /** 表格：“章”那一列认章号，“章名”“标题”认章名，其他列写成“表头：内容”，再按表头归类。 */
    private fun tableEntries(t: MdTable, doc: String, volume: Int?): List<OutlineEntry> {
        val chapterCol = t.header.indexOfFirst { h -> "章" in h && !Regex("章名|标题|题目").containsMatchIn(h) }
        if (chapterCol < 0) return emptyList()
        val titleCol = t.column("章名", "标题", "题目")
        val volumeCol = t.header.indexOfFirst { it.trim() == "卷" || it.trim() == "卷次" }
        // 只有表头里有“事实”“钩子”“边界”这类细纲栏目的表才当细纲；兵额账、人物表这类只是写到某章，挑资料时照样会挑到。
        val outlineLike = t.header.indices.any { it != chapterCol && it != titleCol && fieldOf(t.header[it]) != null }
        return t.rows.flatMap { row ->
            val cell = t.cell(row, chapterCol).replace("*", "").trim()
            // 只认格子开头的章号：“第4—12章（原第5—13章）”是第 4 到 12 章。
            val h = head(cell, bold = false)
            val ranges = h?.ranges ?: listOfNotNull(CheckRules.cn(cell)?.let { it..it })
            if (ranges.isEmpty()) return@flatMap emptyList()
            val title = if (titleCol >= 0) clean(t.cell(row, titleCol)) else h?.title.orEmpty()
            val body = t.header.indices.filter { it != chapterCol && it != titleCol && it != volumeCol }
                .mapNotNull { c -> t.cell(row, c).replace("<br>", "；").takeIf { it.isNotBlank() }?.let { t.header[c].replace("*", "").trim() + "：" + it } }
            val v = if (volumeCol >= 0) CheckRules.cn(t.cell(row, volumeCol).replace(Regex("[第卷]"), "").trim()) else null
            ranges.map { OutlineEntry(it.first, it.last, title, body.joinToString("\n"), doc, v ?: volume, shared = ranges.size > 1 || !outlineLike) }
        }
    }

    private fun volumeIn(text: String): Int? = volumeRef.find(text)?.let { CheckRules.cn(it.groupValues[1].split('至', '-', '—', '、').first().trim()) }

    /** 章名去掉书名号、引号，括号统一成全角，用来和目录里的章名对。“（上）”“（下）”留着：上下章是两章。 */
    internal fun clean(title: String): String =
        title.replace(Regex("[《》「」“”\"*]"), "").replace('(', '（').replace(')', '）').trim()

    /** 目录里的章名去掉“第X章”，剩下的名字；“第十八章（上） 渡口”→“渡口（上）”。 */
    internal fun nameOf(chapterTitle: String): String {
        var rest = chapterTitle.trim().replace(chapterHead, "").trim()
        val part = partMark.find(rest)?.groupValues?.get(1)
        if (part != null) rest = rest.substring(3)
        val name = clean(rest.trimStart('：', ':', '·', '—', ' ', '　'))
        return if (part != null && name.isNotEmpty() && !name.endsWith("（$part）")) "$name（$part）" else name
    }

    /**
     * 找写这一章的段落：章名对得上的优先（细纲章号顺延时更准）；再按章号找（细纲按卷编号时挑同一卷的）；
     * 都没有就用包含这一章的章组。[byNumberOnly] 为真时只按章号找（细纲卡里填了细纲章号）。
     */
    fun match(all: List<OutlineEntry>, chapter: Int?, volume: Int, title: String, byNumberOnly: Boolean = false): List<OutlineEntry> {
        // A chapter number or title may repeat in another volume. Never use facts explicitly
        // scoped to that other volume; entries without volume scope still apply to either one.
        val entries = all.filter { !it.shared && (it.volume == null || it.volume == volume) }
        val name = nameOf(title)
        if (!byNumberOnly && name.length >= 2) {
            val named = entries.filter { it.title == name }
            // 后面“第一章修订落定”这类补记按这一章的章号接上；同一份文档里没写章名的也接上（别的文档章号可能是旧的，不接）。
            val docs = named.map { it.doc }.toSet()
            if (named.isNotEmpty()) return ordered(named + entries.filter { e ->
                e.single && e.from == chapter && e !in named && e.title != name &&
                    (e.title.isNotEmpty() && revision(e.title) || e.title.isEmpty() && e.doc in docs)
            })
        }
        if (chapter == null) return emptyList()
        val exact = entries.filter { it.single && it.from == chapter }
        if (exact.isNotEmpty()) return ordered(exact)
        val groups = entries.filter { !it.single && chapter in it.from..it.to }
        return groups.minByOrNull { it.to - it.from }?.let { best -> groups.filter { it.to - it.from == best.to - best.from } }.orEmpty()
    }

    /** 正经写这一章的在前（卷纲细纲、接续索引、总纲依次），“修订落定”这类补记在后；同一份文档里按原来的先后。 */
    private fun ordered(list: List<OutlineEntry>): List<OutlineEntry> = list.sortedWith(compareBy({ if (revision(it.title)) 1 else 0 }, { it.rank }))

    /** 不是章名、是“修订”“落定”“补记”这类说明的。 */
    internal fun revision(title: String) = title.isEmpty() || Regex("修订|落定|补记|修改|调整|改动|定稿").containsMatchIn(title)

    private enum class Field { FACTS, BOUNDARY, POV, COUNTDOWN, HOOK, THREADS }

    private fun fieldOf(name: String): Field? = when {
        Regex("钩子|章末|结尾|悬念|收尾").containsMatchIn(name) -> Field.HOOK
        Regex("边界|承接|红线|禁区|不能|不可|不得").containsMatchIn(name) -> Field.BOUNDARY
        Regex("视角").containsMatchIn(name) -> Field.POV
        Regex("倒计时|倒数").containsMatchIn(name) -> Field.COUNTDOWN
        Regex("线$|推进|弧").containsMatchIn(name) -> Field.THREADS
        Regex("事实|结果|剧情|情节|内容|梗概|概要|事件|发生|要点|节拍|场景").containsMatchIn(name) -> Field.FACTS
        else -> null
    }

    /**
     * 把找到的段落拆成细纲卡的六项：带“钩子：”“视角：”“倒计时：”“边界：”“××线：”这类标签的分句归到对应的项，
     * “某某视角”认作视角人物，其余都是本章事实与结果。不止一段时（章组、后面的补记），后面的标“补记”接在后面。
     */
    fun card(entries: List<OutlineEntry>): ChapterCard {
        val fields = Field.entries.associateWith { mutableListOf<String>() }
        entries.forEachIndexed { i, e ->
            val facts = mutableListOf<String>()
            e.text.lines().filter { it.isNotBlank() }.forEach { line ->
                val kept = mutableListOf<String>()
                line.split('；', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seg ->
                    val m = label.find(seg)
                    val f = m?.let { fieldOf(it.groupValues[1]) } ?: if (seg.startsWith("倒计时")) Field.COUNTDOWN else null
                    val value = if (m != null && f != null) seg.substring(m.value.length).trim() else seg
                    when (f) {
                        null -> kept += seg
                        Field.FACTS -> kept += value
                        else -> fields.getValue(f) += value
                    }
                }
                if (kept.isNotEmpty()) facts += kept.joinToString("；")
            }
            if (fields.getValue(Field.POV).isEmpty()) {
                povRef.findAll(e.text).map { it.groupValues[1] }.distinct().take(3).forEach { fields.getValue(Field.POV) += it }
            }
            if (facts.isNotEmpty()) {
                val head = when {
                    i > 0 && e.doc != entries[0].doc -> "【另见《${e.doc}》】"
                    i > 0 -> "【补记】"
                    !e.single -> "【第 ${e.from}—${e.to} 章章组】"
                    else -> ""
                }
                fields.getValue(Field.FACTS) += head + facts.joinToString("\n")
            }
        }
        fun get(f: Field, sep: String = "\n") = fields.getValue(f).distinct().joinToString(sep)
        return ChapterCard(
            facts = get(Field.FACTS),
            boundary = get(Field.BOUNDARY),
            pov = get(Field.POV, "、"),
            countdown = get(Field.COUNTDOWN),
            hook = get(Field.HOOK),
            threads = get(Field.THREADS),
        )
    }
}

/** 细纲卡前六项的指纹：看作者改没改过提取出来的内容。 */
internal fun ChapterCard.outlineHash(): Int = listOf(facts, boundary, pov, countdown, hook, threads).hashCode()

/** 前六项换成从细纲提取的，记下指纹；摘要、细纲章号、节拍不动。换成细纲里的就不再是 AI 拟的。 */
fun ChapterCard.withOutline(o: ChapterCard): ChapterCard =
    copy(facts = o.facts, boundary = o.boundary, pov = o.pov, countdown = o.countdown, hook = o.hook, threads = o.threads, auto = o.outlineHash(), aiOutline = false)

private fun ChapterCard.sameFields(o: ChapterCard) = outlineHash() == o.outlineHash()

/** 参考样章和审读报告不能因提到章号就变成细纲；明确的规划与固定事实才参与提取。 */
private val OUTLINE_KINDS = listOf(PackKind.VOLUME, PackKind.INVARIANT, PackKind.OUTLINE, PackKind.CANON)

/** 解析过的文档按（文档、改动时间、字数）记着，挑资料时一章要找好几次。 */
private val outlineCache = object : LinkedHashMap<String, List<OutlineEntry>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OutlineEntry>>?) = size > 64
}

/** 设定包里所有写到某一章的段落（卷纲细纲在前）。设定包没变就用记着的（见 [PackSources.outlineEntries]）。 */
fun WorkStore.outlineEntries(workId: String): List<OutlineEntry> = packSources(workId).outlineEntries

/** [docs] 是写正文阶段筛过的设定包（[ProjectSources.forPhase]）。 */
internal fun outlineEntriesOf(workId: String, docs: List<Pair<PackDoc, String>>): List<OutlineEntry> =
    docs.filter { it.first.kind in OUTLINE_KINDS }.sortedBy { OUTLINE_KINDS.indexOf(it.first.kind) * 100 + it.first.priority }.flatMapIndexed { rank, (d, text) ->
        val key = "$workId/${d.id}/${d.updatedAt}/${d.words}"
        val parsed = synchronized(outlineCache) { outlineCache[key] } ?: ChapterOutlines.parse(listOf(d.title to text)).also { synchronized(outlineCache) { outlineCache[key] = it } }
        parsed.map { it.copy(rank = rank) }
    }.distinctBy { listOf(it.from, it.to, it.title, it.volume, it.text.replace(Regex("\\s|　"), "")) }

/** A newly imported work may still have one placeholder “正文” volume for all chapters. */
internal fun WorkStore.chapterOutlineEntries(work: Work, chapterId: String, entries: List<OutlineEntry>): List<OutlineEntry> {
    val chapter = work.chapter(chapterId) ?: return emptyList()
    val number = chapterNo(work, chapterId)
    val catalogVolume = work.volumeNumber(chapterId)
    val byNumberOnly = card(work.id, chapterId).plan.isNotBlank()
    val scoped = ChapterOutlines.match(entries, number, catalogVolume, chapter.title, byNumberOnly)
    if (scoped.isNotEmpty() || byNumberOnly || number == null ||
        work.volumes.size != 1 || work.volumes.single().title != WorkStore.DEFAULT_VOLUME
    ) return scoped

    // Infer a different volume only from a unique exact chapter number AND name. Never use a
    // foreign volume as the ordinary fallback: repeated chapter numbers and names are common.
    val name = ChapterOutlines.nameOf(chapter.title).takeIf { it.length >= 2 } ?: return scoped
    val namedVolumes = entries.asSequence().filter { !it.shared && it.volume != null && it.title == name }
        .mapNotNull { it.volume }.distinct().toList()
    if (namedVolumes.size != 1) return scoped
    val inferred = namedVolumes.single()
    if (inferred == catalogVolume || entries.none {
            !it.shared && it.volume == inferred && it.single && it.from == number && it.title == name
        }
    ) return scoped
    return ChapterOutlines.match(entries, number, inferred, chapter.title)
}

/**
 * 这一章要用的细纲卡：作者写过的照用；没写过，或者是从细纲提取、作者没改过的，就按设定包里写到这一章的段落
 * （卷纲细纲、接续索引、总纲……）重新提取。细纲文档改了，没改过的卡跟着变。[entries] 传进来可以省掉重复解析。
 */
fun WorkStore.plannedCard(workId: String, chapterId: String, entries: List<OutlineEntry>? = null): PlannedCard {
    val stored = card(workId, chapterId)
    val work = get(workId) ?: return PlannedCard(stored)
    val chapter = work.chapter(chapterId) ?: return PlannedCard(stored)
    val all = entries ?: outlineEntries(workId)
    val number = chapterNo(work, chapterId)
    val planned = stored.plan.isNotBlank()
    val found = chapterOutlineEntries(work, chapterId, all)
    if (found.isEmpty()) {
        if (pack(workId).none { it.kind == PackKind.VOLUME || it.kind == PackKind.OUTLINE }) return PlannedCard(stored)
        val name = ChapterOutlines.nameOf(chapter.title)
        return PlannedCard(stored, missing = "按第 ${number ?: "?"} 章" + (if (name.length >= 2 && !planned) "、章名「$name」" else "") + "找的")
    }
    val outline = ChapterOutlines.card(found)
    val first = found.first()
    val where = found.map { it.doc }.distinct().joinToString("、") { "《$it》" } + " " +
        (if (first.single) "第 ${first.from} 章" else "第 ${first.from}—${first.to} 章") +
        first.title.let { if (it.isNotEmpty() && !ChapterOutlines.revision(it)) "「$it」" else "" }
    val untouched = stored.outlineEmpty || (stored.auto != 0 && stored.auto == stored.outlineHash())
    return if (untouched) {
        PlannedCard(stored.withOutline(outline), source = where, outline = outline, outlineSource = where)
    } else {
        PlannedCard(stored, outline = outline.takeIf { !it.outlineEmpty && !it.sameFields(stored) }, outlineSource = where)
    }
}
