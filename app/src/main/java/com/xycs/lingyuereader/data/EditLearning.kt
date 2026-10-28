package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.DraftParagraphDiff
import com.xycs.lingyuereader.ai.TextDiff
import org.json.JSONArray
import org.json.JSONObject

/** AI 写的一段、被采纳进某章正文时的原文（[at] 是采纳的时间）。 */
data class AiOriginal(val at: Long, val text: String)

/** 学到的东西是哪一类：黑名单词（加进 [WorkAssist.banned]）、写法规则（加进 [WorkAssist.learned]）。 */
enum class LearnKind(val label: String) { BAN("黑名单词"), RULE("写法规则") }

/**
 * 一条改法跨章累积的次数：[chapters] 是站得住的各章（章 id → 这章里改了几处）。同一章再收尾一次是重算，换掉这章的数，不叠加。
 * [before]、[after] 是最近一处的例子（AI 原来写的、作者改成的），给作者看为什么提这条。
 */
data class LearnTally(
    val key: String,
    val kind: LearnKind,
    val text: String,
    val chapters: Map<String, Int>,
    val before: String,
    val after: String,
    val at: Long,
) {
    val total: Int get() = chapters.values.sum()
}

/**
 * 草稿页“这段不行”的原因。[fix] 是重写这段时发给 AI 的具体改法，[rule] 是同一个原因点够 [EditLearning.REASON_AT] 次以后，
 * 收尾学习时提出来的写法规则（“其他”没有固定说法，不提）。
 */
enum class FixReason(val label: String, val fix: String, val rule: String?) {
    STIFF("太死板", "写活一点：换成人物会做的具体动作和会说的口语，去掉书面套话和四平八稳的工整句式。",
        "别写得四平八稳：多用人物的具体动作和口语，少用书面套话和工整的句式。"),
    WORDY("啰嗦", "删掉重复的意思和可有可无的修饰，同一件事只说一遍，篇幅压到原来的六七成。",
        "同一个意思只说一遍，删掉可有可无的修饰和铺垫。"),
    OFF_CHARACTER("人物不像", "按人物的身份、性格和说话习惯改：对白要像这个人会说的话，反应要像这个人会有的反应。",
        "人物说话、反应要贴着各自的身份和性格，别千人一腔。"),
    FLAT("没情绪", "给在场人物补一句当下的情绪或动作，让读者看得出他此刻的心思；用动作和对白带出来，不直接写“他很生气”。",
        "写对白时给人物补一句当下的情绪或动作。"),
    THIN("太水", "删掉不推进情节的过场和空话，换成推动局面的动作、冲突或新信息。",
        "不写不推进情节的过场和空话，每段都要推动局面。"),
    OTHER("其他", "", null),
    ;

    companion object {
        fun of(name: String): FixReason? = entries.firstOrNull { it.name == name }
    }
}

/** 收尾页上提给作者的一条：[source] 说这条是怎么来的，[before]、[after] 是例子（“原来 → 你改成”）。 */
data class LearnCandidate(
    val key: String,
    val kind: LearnKind,
    val text: String,
    val before: String,
    val after: String,
    val source: String,
)

/**
 * 一部作品“从改稿里学”的本机数据（作品目录里的 learn.json，随备份走）：
 * - [originals]：各章被采纳进正文的 AI 原稿（章 id → 若干段，旧的在前）；
 * - [tallies]：本机比对出的改法跨章累积的次数；
 * - [dismissed]：作者没勾的候选（[LearnCandidate.key]），以后不再提；
 * - [reasons]：草稿页“这段不行”各原因点了几次（[FixReason] 的名字 → 次数），[reasonExamples] 是各原因最近一次的例子。
 */
data class LearnData(
    val originals: Map<String, List<AiOriginal>> = emptyMap(),
    val tallies: List<LearnTally> = emptyList(),
    val dismissed: List<String> = emptyList(),
    val reasons: Map<String, Int> = emptyMap(),
    val reasonExamples: Map<String, Pair<String, String>> = emptyMap(),
) {
    /** 只留这些章的（恢复备份、章被删以后）。 */
    fun keep(chapters: Set<String>): LearnData = copy(
        originals = originals.filterKeys { it in chapters },
        tallies = tallies.mapNotNull { t -> t.copy(chapters = t.chapters.filterKeys { it in chapters }).takeIf { it.chapters.isNotEmpty() } },
    )
}

/**
 * 从作者的改稿里学：AI 写的字采纳进正文时记下原稿（[record]），“这章写完了”时把原稿和定稿按段对齐（[compare]），
 * 数出作者反复的改法（[observe]），跨章累积（[tally]），站得住的提给作者勾选（[candidates]）。全在本机算，不花钱。
 */
object EditLearning {
    /** 每章最多留多少字的 AI 原稿（最近的），最多几段。 */
    const val CHAPTER_CHARS = 20_000
    const val CHAPTER_PIECES = 40
    /** 一部作品的 AI 原稿一共最多多少字：超了丢最早的。 */
    const val WORK_CHARS = 200_000
    /** 累积的改法、“不再提”最多记多少条（丢最旧的）。 */
    const val KEEP_TALLIES = 300
    const val KEEP_DISMISSED = 300
    /** 一次收尾最多提几条。 */
    const val MAX_CANDIDATES = 10
    /** “这段不行”同一个原因点到这么多次，收尾学习时提成写法规则。 */
    const val REASON_AT = 3
    /** 发给 AI 归纳的“原段 → 改后”最多多少字。 */
    const val AI_CHARS = 6_000

    /*
     * 阈值和理由（宁可少提，不乱提：提多了作者就不看了，黑名单词还会在全文标红）：
     * - 黑名单词：同一个说法在改稿里被删、被换掉，累计 [BAN_HITS] 处以上，而且至少出现在 [MIN_CHAPTERS] 章里；
     *   每章还要求作者删的比留在定稿里的多（定稿里还用着它，说明不是不要这个词，只是那一处不合适）。
     *   只删一次可能是那一句的语境，一章里删两三次也可能是同一个场面的事；跨了章还在删，才是作者的习惯。
     *   长度限在 [AiCliche.BAN_MIN] 到 [BAN_LONGEST] 字：再长就是整句话，进黑名单没用（下次 AI 不会一字不差地写出同一句）。
     * - 写法规则（对白后的解释句、长句拆短）：一章里至少 [RULE_HITS] 处，并且占这一章里“有机会这样改”的段落的 [RULE_RATE]% 以上；
     *   整段删掉、段落压短的分母是这章所有 AI 段落，比例要 [WHOLE_RATE]% 以上。只有一两处可能是那几段本身的问题，
     *   比例够了才说明作者是成片这样改；同样要在 [MIN_CHAPTERS] 章里都站得住才提。
     */
    const val MIN_CHAPTERS = 2
    const val BAN_HITS = 3
    const val BAN_LONGEST = 12
    const val RULE_HITS = 2
    const val RULE_RATE = 25
    const val WHOLE_RATE = 15
    /** 两段算“同一段改过”的相似度下限（字的二元组重合度）：低于它算作者删了一段、另写了一段。 */
    private const val PAIR_SIMILARITY = 0.4
    /** 一句算长句的字数。 */
    private const val LONG_SENTENCE = 40

    private const val DIALOGUE_KEY = "rule:dialogue-tail"
    private const val SPLIT_KEY = "rule:split-long"
    private const val DROP_KEY = "rule:drop-paragraph"
    private const val COMPRESS_KEY = "rule:compress"

    /** 本机比对能提的几条写法规则。 */
    private val RULES = mapOf(
        DIALOGUE_KEY to "对白说完就收住，后面不要再补一句解释人物心思或话里意思的叙述。",
        SPLIT_KEY to "长句拆短：一句话只说一件事，三四十字以上的句子断开写。",
        DROP_KEY to "少写可有可无的整段描写、过场和心理铺陈，不推进情节的段落不写。",
        COMPRESS_KEY to "段落写紧：同一个意思说一遍就够，删掉重复的修饰和铺垫。",
    )

    // ---------- 记原稿 ----------

    /**
     * 记一段采纳进正文的 AI 原稿：[replace] 为真时（整章替换）这章以前记的都不要了——那些字已经不在正文里，
     * 留着会被当成作者删掉的段落。超过每章、全作品的上限丢最早的。
     */
    fun record(data: LearnData, chapterId: String, text: String, at: Long, replace: Boolean = false): LearnData {
        val clean = text.trim().let { if (it.length > CHAPTER_CHARS) clip(it, CHAPTER_CHARS) else it }
        if (clean.isEmpty()) return data
        val old = if (replace) emptyList() else data.originals[chapterId].orEmpty()
        if (old.lastOrNull()?.text == clean) return data
        var pieces = old + AiOriginal(at, clean)
        while (pieces.size > CHAPTER_PIECES || pieces.size > 1 && pieces.sumOf { it.text.length } > CHAPTER_CHARS) pieces = pieces.drop(1)
        return capWork(data.copy(originals = data.originals + (chapterId to pieces)))
    }

    /** 只留开头 [max] 字，尽量断在段尾。 */
    private fun clip(text: String, max: Int): String {
        val cut = text.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cut).trim()
    }

    /** 全作品超了 [WORK_CHARS]：从最早记的丢起。 */
    private fun capWork(data: LearnData): LearnData {
        var total = data.originals.values.sumOf { list -> list.sumOf { it.text.length } }
        if (total <= WORK_CHARS) return data
        val drop = HashSet<Pair<String, Long>>()
        for ((chapter, piece) in data.originals.flatMap { (c, list) -> list.map { c to it } }.sortedBy { it.second.at }) {
            if (total <= WORK_CHARS) break
            drop += chapter to piece.at
            total -= piece.text.length
        }
        val kept = data.originals.mapValues { (c, list) -> list.filterNot { (c to it.at) in drop } }.filterValues { it.isNotEmpty() }
        return data.copy(originals = kept)
    }

    /** 删了一章：这章的原稿、累积的次数都清掉。 */
    fun forgetChapter(data: LearnData, chapterId: String): LearnData =
        data.copy(originals = data.originals - chapterId,
            tallies = data.tallies.mapNotNull { t -> t.copy(chapters = t.chapters - chapterId).takeIf { it.chapters.isNotEmpty() } })

    /** “这段不行”点了一次：选的各原因加一；还没有例子的原因先拿 [paragraph] 当例子（采纳了重写再补上改后的）。 */
    fun countReasons(data: LearnData, reasons: Collection<FixReason>, paragraph: String = ""): LearnData =
        data.copy(reasons = data.reasons + reasons.associate { it.name to (data.reasons[it.name] ?: 0) + 1 },
            reasonExamples = data.reasonExamples + reasons.filter { it.name !in data.reasonExamples && paragraph.isNotBlank() }
                .associate { it.name to (paragraph.take(120) to "") })

    /** 采纳了“这段不行”的重写：记下这几个原因最近的例子。 */
    fun reasonExample(data: LearnData, reasons: Collection<FixReason>, before: String, after: String): LearnData =
        data.copy(reasonExamples = data.reasonExamples + reasons.associate { it.name to (before.take(120) to after.take(120)) })

    /** 作者没勾的：记进“不再提”。 */
    fun dismiss(data: LearnData, keys: Collection<String>): LearnData =
        data.copy(dismissed = (data.dismissed.filterNot { it in keys } + keys).distinct().takeLast(KEEP_DISMISSED))

    // ---------- 比对 ----------

    /**
     * 一章的改稿：[ai] 是 AI 原稿的段落，[pairs] 是作者改过的（原段 → 改后），[deleted] 是整段删掉的，[kept] 是原样留着的。
     */
    data class ChapterEdits(
        val ai: List<String>,
        val kept: List<String>,
        val pairs: List<Pair<String, String>>,
        val deleted: List<String>,
    )

    private fun paragraphs(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 把这章的 AI 原稿和定稿 [final] 按段对齐（[DraftParagraphDiff]：相同的段一个记号，按段比，一万多字的章也只是几百段）。
     * 对不上的一块里，原稿的段和定稿的段按相似度配对：配上的是作者改过的，没配上的原稿段是整段删了，定稿多出来的是作者自己写的（不管）。
     * 没有原稿、定稿是空的、改动太多对不齐时返回空。
     */
    fun compare(originals: List<AiOriginal>, final: String): ChapterEdits? {
        val ai = originals.flatMap { paragraphs(it.text) }
        val fin = paragraphs(final)
        if (ai.isEmpty() || fin.isEmpty()) return null
        val pieces = DraftParagraphDiff.diff(ai.joinToString("\n"), fin.joinToString("\n"), maxEdits = 4_000) ?: return null
        val kept = ArrayList<String>()
        val pairs = ArrayList<Pair<String, String>>()
        val deleted = ArrayList<String>()
        val removed = ArrayList<String>()
        val added = ArrayList<String>()
        fun flush() {
            if (removed.isNotEmpty()) {
                val used = BooleanArray(added.size)
                removed.forEachIndexed { i, before ->
                    // 只在相近的位置找（一大块改动里两两都比太慢），找最像的那段。
                    val center = if (removed.size <= 1) 0 else i * (added.size - 1) / (removed.size - 1).coerceAtLeast(1)
                    var best = -1
                    var score = PAIR_SIMILARITY
                    for (j in (center - 10).coerceAtLeast(0)..(center + 10).coerceAtMost(added.size - 1)) {
                        if (used[j]) continue
                        val s = similarity(before, added[j])
                        if (s >= score) { score = s; best = j }
                    }
                    if (best >= 0) {
                        used[best] = true
                        pairs += before to added[best]
                    } else deleted += before
                }
            }
            removed.clear()
            added.clear()
        }
        for (piece in pieces) when (piece.kind) {
            TextDiff.Kind.SAME -> { flush(); kept += piece.paragraphs }
            TextDiff.Kind.DELETED -> removed += piece.paragraphs
            TextDiff.Kind.INSERTED -> added += piece.paragraphs
        }
        flush()
        return ChapterEdits(ai, kept, pairs, deleted)
    }

    /** 两段像不像：字的二元组重合度（Dice 系数），0 到 1。 */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return if (a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)) 0.5 else 0.0
        val grams = HashMap<Int, Int>()
        for (i in 0 until a.length - 1) {
            val g = a[i].code shl 16 or a[i + 1].code
            grams[g] = (grams[g] ?: 0) + 1
        }
        var common = 0
        for (i in 0 until b.length - 1) {
            val g = b[i].code shl 16 or b[i + 1].code
            val n = grams[g] ?: 0
            if (n > 0) { common++; grams[g] = n - 1 }
        }
        return 2.0 * common / (a.length - 1 + b.length - 1)
    }

    /** 这一章里看出来的一种改法：[hits] 改了几处，[chances] 有机会这样改的有几处（算比例用）。 */
    data class Observation(
        val key: String,
        val kind: LearnKind,
        val text: String,
        val hits: Int,
        val chances: Int,
        val before: String,
        val after: String,
    ) {
        /** 这一章里站不站得住（见上面的阈值说明）。 */
        val holds: Boolean
            get() = when (key) {
                DIALOGUE_KEY, SPLIT_KEY -> hits >= RULE_HITS && hits * 100 >= chances * RULE_RATE
                DROP_KEY, COMPRESS_KEY -> hits >= RULE_HITS && hits * 100 >= chances * WHOLE_RATE
                // 黑名单词：chances 是删掉的加上定稿里还留着的；删的比留的多才算。
                else -> hits >= 1 && hits * 2 > chances
            }
    }

    private const val PUNCT = "，。！？、；：“”‘’「」『』（）()…—～~,.!?;:\"' 　\t"
    private val SENTENCE_END = Regex("(?<=[。！？!?…])")
    private const val SENTENCE_MARKS = "。！？!?"
    private val QUOTE_CLOSE = charArrayOf('”', '」', '』')
    private val QUOTE_OPEN = charArrayOf('“', '「', '『')

    private fun sentences(p: String) = p.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    /** 对白后面的那句叙述（段里有对白、最后一个后引号后面还有四个字以上的叙述）；没有为空。 */
    private fun dialogueTail(p: String): String? {
        val close = p.lastIndexOfAny(QUOTE_CLOSE)
        if (close < 0 || p.indexOfAny(QUOTE_OPEN) !in 0 until close) return null
        val tail = p.substring(close + 1).trim(*PUNCT.toCharArray())
        return tail.takeIf { it.length >= 4 && it.indexOfAny(QUOTE_OPEN) < 0 }
    }

    /** 黑名单词的候选：一处删掉或换掉的说法，去掉两头标点；含标点的按标点拆开。只要两到 [BAN_LONGEST] 个字、都是汉字的。 */
    private fun phrases(deletedText: String): List<String> =
        deletedText.split(*PUNCT.toCharArray()).map { it.trim() }
            .filter { s -> s.length in AiCliche.BAN_MIN..BAN_LONGEST && s.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }

    /** [p] 里包含 [part] 的那一句（找不到用整段），太长的截短。 */
    private fun sentenceWith(p: String, part: String): String =
        (sentences(p).firstOrNull { part in it } ?: p).let { short(it) }

    /** [p] 里和 [like] 最像的那一句。 */
    private fun closestSentence(p: String, like: String): String =
        short(sentences(p).maxByOrNull { similarity(it, like) } ?: p)

    private fun short(s: String, max: Int = 80) = if (s.length <= max) s else s.take(max) + "…"

    /** 出现了几次。 */
    private fun occurrences(text: String, part: String): Int {
        var n = 0
        var i = text.indexOf(part)
        while (i >= 0) { n++; i = text.indexOf(part, i + part.length) }
        return n
    }

    /** 数这一章的改法：被删、被换掉的说法，对白后的解释句被删，长句被拆短，段落整段删掉、被压短。 */
    fun observe(edits: ChapterEdits, final: String): List<Observation> {
        val out = ArrayList<Observation>()
        // 说法：改过的段按字比，删掉的、换掉的那几处。
        val removed = LinkedHashMap<String, Int>()
        val examples = HashMap<String, Pair<String, String>>()
        for ((before, after) in edits.pairs) {
            val diff = TextDiff.diff(before, after, maxEdits = 600) ?: continue
            // 删掉的是整句（带句号这类）：那是删句子，不是不要某个说法，交给下面的几条写法规则。
            for (piece in diff) if (piece.kind == TextDiff.Kind.DELETED && piece.text.none { it in SENTENCE_MARKS }) for (ph in phrases(piece.text)) {
                if (ph in after) continue
                removed[ph] = (removed[ph] ?: 0) + 1
                examples[ph] = sentenceWith(before, ph) to closestSentence(after, sentenceWith(before, ph))
            }
        }
        for ((ph, n) in removed) {
            val (b, a) = examples.getValue(ph)
            out += Observation("ban:$ph", LearnKind.BAN, ph, n, n + occurrences(final, ph), b, a)
        }
        // 对白后的解释句：原段对白后面跟着一句叙述，作者改后对白还在、那句没了。
        val withTail = (edits.kept + edits.pairs.map { it.first }).count { dialogueTail(it) != null }
        val tailCut = edits.pairs.filter { (b, a) ->
            val tail = dialogueTail(b) ?: return@filter false
            a.lastIndexOfAny(QUOTE_CLOSE) >= 0 && tail !in a && dialogueTail(a).let { it == null || similarity(it, tail) < 0.3 }
        }
        if (tailCut.isNotEmpty()) out += rule(DIALOGUE_KEY, tailCut.size, withTail, tailCut.last())
        // 长句拆短：原段有四十字以上的长句，改后句子多了、平均短了，意思差不多。
        fun longest(p: String) = sentences(p).maxOfOrNull { it.length } ?: 0
        val withLong = (edits.kept + edits.pairs.map { it.first }).count { longest(it) >= LONG_SENTENCE }
        val split = edits.pairs.filter { (b, a) ->
            val sb = sentences(b)
            val sa = sentences(a)
            longest(b) >= LONG_SENTENCE && sa.size > sb.size && sa.isNotEmpty() &&
                a.length.toDouble() / sa.size <= 0.75 * b.length / sb.size.coerceAtLeast(1) && similarity(b, a) >= 0.5
        }
        if (split.isNotEmpty()) out += rule(SPLIT_KEY, split.size, withLong, split.last())
        // 整段删掉（太短的一两句不算）、压短到六成以下。
        val dropped = edits.deleted.filter { it.length >= 10 }
        if (dropped.isNotEmpty()) out += rule(DROP_KEY, dropped.size, edits.ai.size, dropped.last() to "（整段删掉）")
        val compressed = edits.pairs.filter { (b, a) -> b.length >= 30 && a.length * 10 <= b.length * 6 }
        if (compressed.isNotEmpty()) out += rule(COMPRESS_KEY, compressed.size, edits.ai.size, compressed.last())
        return out
    }

    private fun rule(key: String, hits: Int, chances: Int, example: Pair<String, String>) =
        Observation(key, LearnKind.RULE, RULES.getValue(key), hits, chances, short(example.first, 120), short(example.second, 120))

    /** 把这一章站得住的改法记进累积数：这章以前记的先去掉（同一章再收尾是重算，不叠加）。 */
    fun tally(data: LearnData, chapterId: String, observed: List<Observation>, at: Long): LearnData {
        val base = data.tallies.mapNotNull { t -> t.copy(chapters = t.chapters - chapterId).takeIf { it.chapters.isNotEmpty() } }
            .associateBy { it.key }.toMutableMap()
        for (o in observed.filter { it.holds }) {
            val old = base[o.key]
            base[o.key] = LearnTally(o.key, o.kind, o.text, (old?.chapters.orEmpty()) + (chapterId to o.hits), o.before, o.after, at)
        }
        return data.copy(tallies = base.values.sortedBy { it.at }.takeLast(KEEP_TALLIES))
    }

    /** 跨章累积够了的（见上面的阈值说明）。 */
    fun proposable(t: LearnTally): Boolean = t.chapters.size >= MIN_CHAPTERS && (t.kind == LearnKind.RULE || t.total >= BAN_HITS)

    /**
     * 这次收尾提给作者的：本机比对累积够了的、“这段不行”点够 [REASON_AT] 次的原因、AI 归纳的 [ai]。
     * 已经在黑名单（或被黑名单里更短的说法盖住）、已经在学到的规则里、作者以前没勾过的，都不再提。
     */
    fun candidates(data: LearnData, assist: WorkAssist, ai: List<LearnCandidate> = emptyList()): List<LearnCandidate> {
        val skip = data.dismissed.toSet()
        val local = data.tallies.filter(::proposable).sortedByDescending { it.total }.map { t ->
            LearnCandidate(t.key, t.kind, t.text, t.before, t.after,
                if (t.kind == LearnKind.BAN) "改稿比对：${t.chapters.size} 章里删了 ${t.total} 处" else "改稿比对：${t.chapters.size} 章里都这样改")
        }
        val reasons = FixReason.entries.filter { r -> r.rule != null && (data.reasons[r.name] ?: 0) >= REASON_AT }.map { r ->
            val (b, a) = data.reasonExamples[r.name] ?: ("" to "")
            LearnCandidate("reason:${r.name}", LearnKind.RULE, r.rule!!, b, a, "“这段不行”点了 ${data.reasons[r.name]} 次「${r.label}」")
        }
        return (local + reasons + ai).filterNot { c ->
            c.key in skip || when (c.kind) {
                LearnKind.BAN -> assist.banned.any { it == c.text || it in c.text }
                LearnKind.RULE -> c.text in assist.learned
            }
        }.distinctBy { it.text }.take(MAX_CANDIDATES)
    }

    /**
     * 挑几对“AI 原段 → 作者改后”给 AI 归纳：改得多的在前，整段删掉的也算（改后写“（整段删掉）”），一共不超过 [limit] 字。
     */
    fun pairsForAi(edits: ChapterEdits, limit: Int = AI_CHARS): List<Pair<String, String>> {
        val all = edits.pairs.sortedBy { (b, a) -> similarity(b, a) } + edits.deleted.filter { it.length >= 10 }.map { it to DELETED }
        val out = ArrayList<Pair<String, String>>()
        var used = 0
        for ((b, a) in all) {
            val size = b.length + a.length
            if (size > limit / 2) continue
            if (used + size > limit) break
            out += b to a
            used += size
        }
        return out
    }

    /** AI 归纳时整段删掉的段，“改后”写这个。 */
    const val DELETED = "（整段删掉）"

    /** AI 归纳的候选的来源说明（收尾页默认不勾这一类）。 */
    const val FROM_AI = "AI 归纳"

    /** AI 归纳回来的不是约定的 JSON。 */
    class ParseError(message: String) : IllegalStateException(message)

    /**
     * 读 AI 归纳的回答（[com.xycs.lingyuereader.ai.WritePrompts.learnRules] 要的 JSON）：每条的例子用它指的那一对（[pairs] 里第 p 对，从 1 数），
     * 指不到的、黑名单词在原段里找不到的、规则写成了原文的，都不要。回答不是那个 JSON 时抛 [ParseError]，不把原文当规则。
     */
    fun parseAi(answer: String, pairs: List<Pair<String, String>>): List<LearnCandidate> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        val o = if (start in 0 until end) runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() else null
        val rules = o?.optJSONArray("rules") ?: throw ParseError("AI 回的不是约定的 JSON，没能归纳出规则")
        val originals = pairs.flatMap { listOf(it.first, it.second) }
        return (0 until rules.length()).mapNotNull { i ->
            val r = rules.optJSONObject(i) ?: return@mapNotNull null
            val kind = if (r.optString("kind").trim().lowercase() == "ban") LearnKind.BAN else LearnKind.RULE
            val text = r.optString("text").trim().trim('“', '”', '「', '」', '"')
            val pair = pairs.getOrNull(r.optInt("p", 0) - 1) ?: return@mapNotNull null
            when (kind) {
                LearnKind.BAN -> if (text.length !in AiCliche.BAN_MIN..AiCliche.BAN_MAX || text !in pair.first || text in pair.second) return@mapNotNull null
                // 规则要是作者的写法要求，不能是正文里的一句话。
                LearnKind.RULE -> if (text.length !in 4..60 || originals.any { text in it || it.length >= 4 && it in text }) return@mapNotNull null
            }
            val before = if (kind == LearnKind.BAN) sentenceWith(pair.first, text) else short(pair.first, 120)
            val after = if (pair.second == DELETED) DELETED else if (kind == LearnKind.BAN) closestSentence(pair.second, before) else short(pair.second, 120)
            LearnCandidate((if (kind == LearnKind.BAN) "ban:" else "ai:") + text, kind, text, before, after, FROM_AI)
        }.distinctBy { it.text }.take(5)
    }

    // ---------- 存盘 ----------

    fun toJson(d: LearnData): JSONObject = JSONObject()
        .put("format", 1)
        .put("originals", JSONObject().apply {
            d.originals.forEach { (c, list) -> put(c, JSONArray().apply { list.forEach { put(JSONObject().put("at", it.at).put("text", it.text)) } }) }
        })
        .put("tallies", JSONArray().apply {
            d.tallies.forEach { t ->
                put(JSONObject().put("key", t.key).put("kind", t.kind.name).put("text", t.text).put("before", t.before).put("after", t.after).put("at", t.at)
                    .put("chapters", JSONObject().apply { t.chapters.forEach { (c, n) -> put(c, n) } }))
            }
        })
        .put("dismissed", JSONArray(d.dismissed))
        .put("reasons", JSONObject().apply { d.reasons.forEach { (k, v) -> put(k, v) } })
        .put("reasonExamples", JSONObject().apply { d.reasonExamples.forEach { (k, v) -> put(k, JSONObject().put("before", v.first).put("after", v.second)) } })

    /** 读回来：缺的项是空的，坏的条目跳过；整个不是 JSON 对象时由调用方处理。 */
    fun fromJson(o: JSONObject): LearnData {
        val originals = o.optJSONObject("originals")?.let { m ->
            m.keys().asSequence().filter { ID.matches(it) }.associateWith { c ->
                val a = m.optJSONArray(c) ?: JSONArray()
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> AiOriginal(e.optLong("at"), e.optString("text")).takeIf { it.text.isNotBlank() } } }
            }.filterValues { it.isNotEmpty() }
        }.orEmpty()
        val tallies = o.optJSONArray("tallies")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { LearnKind.valueOf(t.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                val chapters = t.optJSONObject("chapters")?.let { m -> m.keys().asSequence().filter { ID.matches(it) }.associateWith { m.optInt(it) } }.orEmpty()
                LearnTally(t.optString("key"), kind, t.optString("text"), chapters.filterValues { it > 0 }, t.optString("before"), t.optString("after"), t.optLong("at"))
                    .takeIf { it.key.isNotEmpty() && it.text.isNotEmpty() && it.chapters.isNotEmpty() }
            }
        }.orEmpty()
        val dismissed = o.optJSONArray("dismissed")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        val reasons = o.optJSONObject("reasons")?.let { m -> m.keys().asSequence().filter { FixReason.of(it) != null }.associateWith { m.optInt(it) } }.orEmpty()
        val examples = o.optJSONObject("reasonExamples")?.let { m ->
            m.keys().asSequence().filter { FixReason.of(it) != null }.mapNotNull { k ->
                m.optJSONObject(k)?.let { e -> k to (e.optString("before") to e.optString("after")) }
            }.toMap()
        }.orEmpty()
        return LearnData(originals, tallies.takeLast(KEEP_TALLIES), dismissed.takeLast(KEEP_DISMISSED), reasons, examples)
    }

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
}
