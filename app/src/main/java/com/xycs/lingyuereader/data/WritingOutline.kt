package com.xycs.lingyuereader.data

/**
 * 卷纲细纲里写某一章（或一组章）的一段：第 [from] 到 [to] 章、章名、内容、出自哪份文档。
 * [volume] 是它所在的卷（文档名或上级标题里写了“第X卷”时），按卷编章号的细纲靠它区分。
 */
data class OutlineEntry(val from: Int, val to: Int, val title: String, val text: String, val doc: String, val volume: Int? = null) {
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
            val tableLines = tables.flatMap { t -> t.lines + listOfNotNull(t.lines.firstOrNull()?.minus(1), t.lines.firstOrNull()?.minus(2)) }.toSet()
            tables.forEach { t -> out += tableEntries(t, name, docVolume) }

            val headings = ArrayList<Pair<Int, String>>()
            fun volume(): Int? = headings.asReversed().firstNotNullOfOrNull { volumeIn(it.second) } ?: docVolume
            var cur: Block? = null
            fun finish() {
                cur?.let { b -> b.entry(name, volume())?.let { out += it } }
                cur = null
            }
            text.lines().forEachIndexed { i, line ->
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
                    else head(title, bold = true)?.let { (range, t, rest) -> cur = Block(level, 0, range, t).also { it.add(rest) } }
                    return@forEachIndexed
                }
                if (i in tableLines) {
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
                    val (range, t, rest) = start
                    cur = Block(0, indent, range, t).also { it.add(rest) }
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

    /** 一段的开头（去掉列表符号以后）是不是“第X章……”：返回（章号范围，章名，后面的内容）。 */
    private fun start(body: String): Triple<IntRange, String, String>? {
        if (body.startsWith("**")) {
            val close = body.indexOf("**", 2)
            if (close > 2) {
                val inner = body.substring(2, close)
                val after = body.substring(close + 2).trimStart('：', ':', ' ', '　')
                head(inner, bold = true)?.let { (range, title, rest) ->
                    val joined = if (rest.isEmpty() || after.isEmpty() || rest.last() in "。；;：:！？") rest + after else "$rest：$after"
                    return Triple(range, title, joined)
                }
            }
            return null
        }
        return head(body.trimStart('【'), bold = false)
    }

    /**
     * “第十九章 雪夜来客：内容”“第二十一章《过河》落定。”“第十九至二十一章”→（范围，章名，内容）。
     * 不是标题、粗体时，章号后面得跟着空格、冒号、书名号这类，免得把“第十九章的钩子……”这样的句子当成一章。
     */
    private fun head(text: String, bold: Boolean): Triple<IntRange, String, String>? {
        val m = chapterHead.find(text) ?: return null
        val nums = ChapterRefs.find(m.value)
        if (nums.isEmpty()) return null
        var rest = text.substring(m.value.length)
        rest = rest.removePrefix("（上）").removePrefix("（下）").removePrefix("（中）")
        val next = rest.firstOrNull()
        if (!bold && next != null && next !in " 　：:《「“（(—-·.、|】\t") return null
        rest = rest.trimStart(' ', '　', '·', '—', '-', '.', '、', '】', '\t')
        val title: String
        if (rest.startsWith("《") && rest.indexOf('》') > 0) {
            title = rest.substring(1, rest.indexOf('》'))
            rest = rest.substring(rest.indexOf('》') + 1)
        } else if (rest.startsWith("：") || rest.startsWith(":")) {
            title = ""
        } else {
            val end = rest.indexOfAny(charArrayOf('：', ':', '。', '；', '（', '(', '，')).let { if (it < 0) rest.length else it }
            title = if (end <= 20) rest.substring(0, end).trim() else ""
            rest = if (end <= 20) rest.substring(end) else rest
        }
        rest = rest.trimStart('：', ':', '。', ' ', '　').replace(Regex("^(?:落定|定稿|补记|修订|修改|调整)[。：:，]?"), "")
        return Triple(nums.min()..nums.max(), clean(title), rest.trim())
    }

    private class Block(val level: Int, val indent: Int, val range: IntRange, val title: String) {
        val lines = mutableListOf<String>()
        var blank = false
        /** 章标题下面的小标题（“钩子”“视角”……）：下面的行算在它名下。 */
        var sub: String? = null

        fun add(line: String) {
            if (line.isBlank()) return
            lines += sub?.let { s -> if (label.containsMatchIn(line)) line.trim() else "$s：${line.trim()}" } ?: line.trim()
        }

        fun entry(doc: String, volume: Int?): OutlineEntry? {
            val text = lines.joinToString("\n").replace("**", "").trim()
            if (text.isEmpty() && title.isEmpty()) return null
            return OutlineEntry(range.first, range.last, title, text, doc, volume)
        }
    }

    /** 表格：“章”那一列认章号，“章名”“标题”认章名，其他列写成“表头：内容”，再按表头归类。 */
    private fun tableEntries(t: MdTable, doc: String, volume: Int?): List<OutlineEntry> {
        val chapterCol = t.header.indexOfFirst { h -> "章" in h && !Regex("章名|标题|题目").containsMatchIn(h) }
        if (chapterCol < 0) return emptyList()
        val titleCol = t.column("章名", "标题", "题目")
        val volumeCol = t.header.indexOfFirst { it.trim() == "卷" || it.trim() == "卷次" }
        return t.rows.mapNotNull { row ->
            val cell = t.cell(row, chapterCol).replace("*", "")
            val nums = ChapterRefs.find(cell).ifEmpty { listOfNotNull(CheckRules.cn(cell.trim())) }
            if (nums.isEmpty()) return@mapNotNull null
            val title = if (titleCol >= 0) t.cell(row, titleCol) else head(cell, bold = true)?.second.orEmpty()
            val body = t.header.indices.filter { it != chapterCol && it != titleCol && it != volumeCol }
                .mapNotNull { c -> t.cell(row, c).replace("<br>", "；").takeIf { it.isNotBlank() }?.let { t.header[c].replace("*", "").trim() + "：" + it } }
            val v = if (volumeCol >= 0) CheckRules.cn(t.cell(row, volumeCol).replace(Regex("[第卷]"), "").trim()) else null
            OutlineEntry(nums.min(), nums.max(), clean(title), body.joinToString("\n"), doc, v ?: volume)
        }
    }

    private fun volumeIn(text: String): Int? = volumeRef.find(text)?.let { CheckRules.cn(it.groupValues[1].split('至', '-', '—', '、').first().trim()) }

    /** 章名去掉书名号、引号、（上）（下），用来和目录里的章名对。 */
    internal fun clean(title: String): String =
        title.replace(Regex("[《》「」“”\"*]"), "").replace(Regex("[（(][上中下][)）]$"), "").trim()

    /** 目录里的章名去掉“第X章”，剩下的名字。 */
    internal fun nameOf(chapterTitle: String): String = clean(chapterTitle.replace(chapterHead, "").trim().trimStart('：', ':', '·', '—', ' ', '　'))

    /**
     * 找写这一章的段落：章名对得上的优先（细纲章号顺延时更准）；再按章号找（细纲按卷编号时挑同一卷的）；
     * 都没有就用包含这一章的章组。[byNumberOnly] 为真时只按章号找（细纲卡里填了细纲章号）。
     */
    fun match(entries: List<OutlineEntry>, chapter: Int?, volume: Int, title: String, byNumberOnly: Boolean = false): List<OutlineEntry> {
        val name = nameOf(title)
        if (!byNumberOnly && name.length >= 2) {
            val named = entries.filter { it.title == name }
            // 后面“第一章修订落定”这类补记没写章名，按这一章的章号接上。
            if (named.isNotEmpty()) return named + entries.filter { it.single && it.from == chapter && revision(it.title) && it !in named }
        }
        if (chapter == null) return emptyList()
        val exact = entries.filter { it.single && it.from == chapter }
        if (exact.isNotEmpty()) {
            val sameVolume = exact.filter { it.volume == volume }
            if (sameVolume.isNotEmpty() && exact.any { it.volume != null && it.volume != volume }) return sameVolume
            return exact.filter { it.volume == null || it.volume == volume }.ifEmpty { exact }
        }
        val groups = entries.filter { !it.single && chapter in it.from..it.to }
        return groups.minByOrNull { it.to - it.from }?.let { best -> groups.filter { it.to - it.from == best.to - best.from } }.orEmpty()
    }

    /** 不是章名、是“修订”“落定”“补记”这类说明的。 */
    private fun revision(title: String) = title.isEmpty() || Regex("修订|落定|补记|修改|调整|改动|定稿").containsMatchIn(title)

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

/** 前六项换成从细纲提取的，记下指纹；摘要、细纲章号不动。 */
fun ChapterCard.withOutline(o: ChapterCard): ChapterCard =
    copy(facts = o.facts, boundary = o.boundary, pov = o.pov, countdown = o.countdown, hook = o.hook, threads = o.threads, auto = o.outlineHash())

private fun ChapterCard.sameFields(o: ChapterCard) = outlineHash() == o.outlineHash()

/**
 * 这一章要用的细纲卡：作者写过的照用；没写过，或者是从细纲提取、作者没改过的，就按设定包里卷纲细纲（没有就总纲）
 * 现在写的这一章重新提取。细纲文档改了，没改过的卡跟着变。
 */
fun WorkStore.plannedCard(workId: String, chapterId: String): PlannedCard {
    val stored = card(workId, chapterId)
    val work = get(workId) ?: return PlannedCard(stored)
    val chapter = work.chapter(chapterId) ?: return PlannedCard(stored)
    val docs = pack(workId).filter { it.kind == PackKind.VOLUME || it.kind == PackKind.OUTLINE }
    if (docs.isEmpty()) return PlannedCard(stored)
    val number = chapterNo(work, chapterId)
    val planned = stored.plan.isNotBlank()
    val found = listOf(PackKind.VOLUME, PackKind.OUTLINE).firstNotNullOfOrNull { kind ->
        val texts = docs.filter { it.kind == kind }.map { it.title to packText(workId, it.id) }
        ChapterOutlines.match(ChapterOutlines.parse(texts), number, work.volumeNumber(chapterId), chapter.title, planned).ifEmpty { null }
    }
    if (found == null) {
        if (docs.none { it.kind == PackKind.VOLUME }) return PlannedCard(stored)
        val name = ChapterOutlines.nameOf(chapter.title)
        return PlannedCard(stored, missing = "按第 ${number ?: "?"} 章" + (if (name.length >= 2 && !planned) "、章名「$name」" else "") + "找的")
    }
    val outline = ChapterOutlines.card(found)
    val where = found.map { it.doc }.distinct().joinToString("、") { "《$it》" } + " " +
        found.first().let { if (it.single) "第 ${it.from} 章" else "第 ${it.from}—${it.to} 章" } + found.first().title.let { if (it.isNotEmpty()) "「$it」" else "" }
    val untouched = stored.isEmpty || (stored.auto != 0 && stored.auto == stored.outlineHash())
    return if (untouched) {
        PlannedCard(stored.withOutline(outline), source = where, outline = outline, outlineSource = where)
    } else {
        PlannedCard(stored, outline = outline.takeIf { !it.isEmpty && !it.sameFields(stored) }, outlineSource = where)
    }
}
