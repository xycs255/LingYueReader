package com.xycs.lingyuereader.data

import kotlin.math.ln
import kotlin.math.sqrt

/** 设定文档切出来的一小块：标题链（连同“**第一卷《渡口》**”这样起头的粗体小标题）和正文。 */
data class PackUnit(val path: List<String>, val text: String)

/**
 * 把设定文档切成小块，挑本章资料时一块一块地打分。长文档（几万字的总纲、人物档案）整份放不下，
 * 只截开头又多半是说明和原则，所以按块挑：标题、粗体小标题下的列表项和段落凑成不超过 [max] 字的一块，
 * 表格每块都带上表头，太长的段落按句子切开。
 */
object PackUnits {
    private val heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val lead = Regex("^\\*\\*([^*\\n]{2,40}?)[：:]?\\*\\*")
    private val item = Regex("^(?:[-*+•]\\s+|\\d{1,3}[.、)）]\\s*)")

    fun split(text: String, max: Int = 900): List<PackUnit> {
        val out = mutableListOf<PackUnit>()
        val headings = ArrayList<Pair<Int, String>>()
        var leadTitle: String? = null
        var tableHead: String? = null
        val buf = StringBuilder()
        fun path() = headings.map { it.second } + listOfNotNull(leadTitle)
        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) out += PackUnit(path(), t)
            buf.setLength(0)
        }
        fun add(line: String) {
            if (buf.isNotEmpty() && buf.length + line.length > max) {
                flush()
                tableHead?.let { buf.append(it).append('\n') }
            }
            if (line.length <= max) {
                buf.append(line).append('\n')
                return
            }
            // 一段太长：按句子切开。
            val piece = StringBuilder()
            Regex("[^。；！？]+[。；！？]?").findAll(line).forEach { s ->
                if (piece.isNotEmpty() && piece.length + s.value.length > max) {
                    buf.append(piece).append('\n')
                    flush()
                    piece.setLength(0)
                }
                piece.append(s.value)
            }
            if (piece.isNotEmpty()) buf.append(piece).append('\n')
        }
        val lines = text.removePrefix("\uFEFF").lines()
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            val h = heading.matchEntire(trimmed)
            if (h != null) {
                flush()
                tableHead = null
                leadTitle = null
                val level = h.groupValues[1].length
                while (headings.isNotEmpty() && headings.last().first >= level) headings.removeAt(headings.lastIndex)
                if (level >= 2) headings += level to h.groupValues[2].replace("*", "").trim()
                return@forEachIndexed
            }
            if (trimmed.isEmpty()) {
                tableHead = null
                // 段落之间：攒够半块就断开，块尽量按段落分。
                if (buf.length >= max / 2) flush() else if (buf.isNotEmpty()) buf.append('\n')
                return@forEachIndexed
            }
            if (trimmed.startsWith("|")) {
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (tableHead == null && next.startsWith("|") && next.contains("--")) {
                    flush()
                    tableHead = trimmed
                    buf.append(trimmed).append('\n')
                    return@forEachIndexed
                }
                if (trimmed.contains("--") && trimmed.replace(Regex("[|:\\-\\s]"), "").isEmpty()) return@forEachIndexed
                add(trimmed)
                return@forEachIndexed
            }
            // 顶格、不是列表项、粗体起头的一行（“**第一卷《渡口》**（……）”）：下面的列表项都归它。
            if (!item.containsMatchIn(trimmed) && raw.first() != ' ' && raw.first() != '\t') {
                lead.find(trimmed)?.let { m ->
                    flush()
                    leadTitle = m.groupValues[1].trim()
                }
            }
            add(trimmed)
        }
        flush()
        return out
    }
}

/** 给设定块打分用的工具：章号引用（不展开范围）、字的二元组和它们有多少区分度。 */
object PackRanking {
    private val ref = Regex("第([^章第卷\\n]{1,24}?)章")

    /** 文字里提到的章：“第12章”→12..12，“第4至12章”“第4—12章”→4..12，“第六、十七章”→6..6 和 17..17。 */
    fun refs(text: String): List<IntRange> = ref.findAll(text).flatMap { m ->
        m.groupValues[1].split('、', '，', ',', '及', '和').mapNotNull { piece ->
            val ends = piece.trim().split(Regex("\\s*[-—–~～至到]+\\s*第?\\s*")).filter { it.isNotEmpty() }.map { CheckRules.cn(it.trim()) ?: return@mapNotNull null }
            when (ends.size) {
                1 -> ends[0]..ends[0]
                2 -> if (ends[1] >= ends[0]) ends[0]..ends[1] else null
                else -> null
            }
        }
    }.toList()

    /**
     * 这一块写没写到第 [chapter] 章：单独写到这一章最有用，写到一小段范围（“第 18 至 20 章”）次之，
     * “第一至四十章”这样的大范围几乎不算；写到前后一两章的也算一点。返回（分数，说明）。
     */
    fun chapterScore(refs: List<IntRange>, chapter: Int): Pair<Double, String?> {
        var best = 0.0
        var why: String? = null
        var near = 0.0
        var hits = 0
        refs.forEach { r ->
            val width = r.last - r.first
            if (chapter in r) {
                val s = when {
                    width == 0 -> 6.0
                    width <= 3 -> 4.0
                    width <= 10 -> 1.5
                    else -> 0.0
                }
                if (s > 0) hits++
                if (s > best) {
                    best = s
                    why = if (width == 0) "提到第 $chapter 章" else "提到第 ${r.first}—${r.last} 章"
                }
            } else if (width <= 3 && (chapter - r.last in 1..2 || r.first - chapter in 1..2)) {
                near = 1.0
            }
        }
        if (best == 0.0 && near > 0) return near to "提到前后几章"
        return (best + (hits - 1).coerceAtLeast(0) * 0.5).coerceAtMost(8.0) to why
    }

    private val volumeRef = Regex("第([^卷第章\\n]{1,6}?)卷")

    /** 标题里写到的卷号：“第七卷《失地》”→ 7，“第四至十九卷”→ 4 到 19。 */
    fun volumes(text: String): Set<Int> = volumeRef.findAll(text).flatMap { m ->
        val ends = m.groupValues[1].split(Regex("[-—–~～至到、]")).mapNotNull { CheckRules.cn(it.trim()) }
        when (ends.size) {
            1 -> listOf(ends[0])
            2 -> if (ends[1] >= ends[0] && ends[1] - ends[0] < 30) (ends[0]..ends[1]).toList() else emptyList()
            else -> emptyList()
        }
    }.toSet()

    /** 1→“一”，12→“十二”，40→“四十”（卷号、章号写成汉字时对照用，到九十九）。 */
    fun chinese(n: Int): String {
        val d = "零一二三四五六七八九"
        if (n !in 1..99) return n.toString()
        val tens = n / 10
        val ones = n % 10
        return (if (tens == 0) "" else (if (tens == 1) "" else d[tens].toString()) + "十") + (if (ones == 0) "" else d[ones].toString())
    }

    /** 字的二元组（只取汉字、字母、数字连着的部分）。 */
    fun bigrams(text: String): Set<String> {
        val out = HashSet<String>()
        var prev = ' '
        for (ch in text) {
            val ok = Character.isLetterOrDigit(ch)
            if (ok && Character.isLetterOrDigit(prev)) out += "$prev$ch"
            prev = if (ok) ch else ' '
        }
        return out
    }

    /** 一批块里各二元组的区分度：到处都有的（主角名字、“第一”）几乎不算，少见的算得多。 */
    class Lexicon(units: List<Set<String>>) {
        private val df = HashMap<String, Int>()
        private val n = units.size.coerceAtLeast(1)

        init {
            units.forEach { u -> u.forEach { df[it] = (df[it] ?: 0) + 1 } }
        }

        fun idf(b: String): Double = ln((n + 1.0) / ((df[b] ?: 0) + 0.5))

        /** 这一块和 [query] 用词重合多少（按区分度加权，长块不占便宜）。 */
        fun overlap(unit: Set<String>, query: Set<String>): Double {
            if (unit.isEmpty() || query.isEmpty()) return 0.0
            var s = 0.0
            val (small, big) = if (unit.size < query.size) unit to query else query to unit
            small.forEach { if (it in big) s += idf(it) }
            return s / sqrt(unit.size + 20.0)
        }
    }
}
