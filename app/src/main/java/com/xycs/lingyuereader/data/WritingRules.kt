package com.xycs.lingyuereader.data

/**
 * 设定包里“检查规则”文档写的规则，灵阅在本机逐章检查，不联网。每行一条，例如：
 * - `字数：3000-6000`（`汉字：3500-6000` 只数汉字）
 * - `至多 1：点了点头、顿了顿／一顿`（每个词各自计数；“／”连起来的合起来算一个）
 * - `合计至多 1：没说话、没吭声`（几个词加在一起计数）
 * - `至少 1：实际上`
 * - `禁用：您、公公`、`句式：不是……而是……`（一次都不能有）
 * - `短段：12 字以内至多 5 处`、`长段：300 字以上至多 2 处`
 * - `对白占比：20%-40%`
 *
 * 词里的“……”代表一句之内中间的任意几个字，“某人”“某某”代表一个称呼（几个字，不带标点）；
 * 带逗号、顿号的说法用引号括起来（`禁用：“他知道，”`），引号里的不拆开；
 * 词后面加“（除外：大概在今天）”时，落在除外的说法里面的不算。
 * 规则前面加“对白”只查引号里的话，加“叙述”或“旁白”只查引号外面的。
 */
enum class RuleScope(val label: String) { ALL(""), NARRATION("叙述"), DIALOGUE("对白") }

/** 要数的一个词：几种写法（合起来计数）、除外的说法。 */
data class RuleTerm(val forms: List<String>, val except: List<String> = emptyList()) {
    val label: String get() = forms.joinToString("／")
}

sealed interface CheckRule {
    /** 写在哪份文档的哪一行（给作者看，方便回去改）。 */
    val source: RuleSource

    /** 章节字数。[han] 为真时只数汉字。 */
    data class Length(val min: Int?, val max: Int?, val han: Boolean, override val source: RuleSource) : CheckRule

    /** 词或句式出现的次数：[combined] 为真时几个词合起来数，否则各数各的。 */
    data class Count(
        val terms: List<RuleTerm>,
        val min: Int?,
        val max: Int?,
        val combined: Boolean,
        val scope: RuleScope,
        override val source: RuleSource,
    ) : CheckRule

    /** 段落长度：[short] 为真时数 [chars] 字以内的段，否则数 [chars] 字以上的段，每章至多 [max] 处。 */
    data class Paragraph(val chars: Int, val short: Boolean, val max: Int, val scope: RuleScope, override val source: RuleSource) : CheckRule

    /** 对白字数占全章的百分比。 */
    data class DialogueRatio(val min: Int?, val max: Int?, override val source: RuleSource) : CheckRule
}

data class RuleSource(val doc: String, val line: Int, val text: String)

/** 读出来的规则；没看懂的行单独列出来，让作者知道哪条没生效。 */
data class RuleSet(val rules: List<CheckRule> = emptyList(), val unknown: List<RuleSource> = emptyList()) {
    val isEmpty: Boolean get() = rules.isEmpty()
    operator fun plus(other: RuleSet) = RuleSet(rules + other.rules, unknown + other.unknown)
}

/** 一条没守住的规则：说明，以及正文里的位置（可能没有，比如字数不够）。[key] 在正文改动、次数变了之后不变。 */
data class RuleHit(val rule: CheckRule, val message: String, val ranges: List<IntRange> = emptyList(), val key: String = "${rule.source.doc}#${rule.source.line}")

/** 一章的统计：字数、段落、段落中位长度、平均句长、对白占比（百分数）。 */
data class ChapterStats(val words: Int, val han: Int, val paragraphs: Int, val medianParagraph: Int, val averageSentence: Int, val dialoguePercent: Int)

data class RuleReport(val hits: List<RuleHit>, val stats: ChapterStats)

object CheckRules {
    private val scopePrefix = Regex("^(对白|叙述|旁白)")
    private val number = "([0-9０-９]+|[零一二两三四五六七八九十百千万]+)"
    private val maxKey = Regex("^(?:每章)?(?:各)?(?:至多|最多|不超过|不多于)$number(?:次|处|个)?$")
    private val combinedKey = Regex("^(?:每章)?(?:合计|合起来|一共|总共)(?:至多|最多|不超过|不多于)$number(?:次|处|个)?$")
    private val minKey = Regex("^(?:每章)?(?:各)?(?:至少|最少|不少于)$number(?:次|处|个)?$")
    private val bannedKey = Regex("^(?:禁用|不用|别用|不要用|禁止)(?:词|词语|句式|称谓|说法|动作|动作拍|口癖|套话)?$|^句式$|^禁用句式$")
    private val numberIn = Regex(number)

    /** 读一份文档里的规则。 */
    fun parse(text: String, doc: String = ""): RuleSet {
        val rules = mutableListOf<CheckRule>()
        val unknown = mutableListOf<RuleSource>()
        text.removePrefix("\uFEFF").lines().forEachIndexed { i, raw ->
            val line = raw.trim().trim('　').trim()
            // 说明、标题、引用、表格分隔线都不是规则。
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("//") || line.startsWith("|")) return@forEachIndexed
            val body = line.replace(Regex("^(?:[-*•]|\\d+[.、)）])\\s*"), "").trim()
            val source = RuleSource(doc, i + 1, body)
            val colon = body.indexOfFirst { it == '：' || it == ':' }
            if (colon <= 0) {
                unknown += source
                return@forEachIndexed
            }
            val value = body.substring(colon + 1).trim()
            // “禁用：”后面还没填词：模板里留的空，不算错。
            if (value.isEmpty()) return@forEachIndexed
            val rule = rule(body.substring(0, colon).replace(Regex("\\s"), ""), value, source)
            if (rule != null) rules += rule else unknown += source
        }
        return RuleSet(rules, unknown)
    }

    private fun rule(rawKey: String, value: String, source: RuleSource): CheckRule? {
        val scope = when (scopePrefix.find(rawKey)?.value) {
            "对白" -> RuleScope.DIALOGUE
            "叙述", "旁白" -> RuleScope.NARRATION
            else -> RuleScope.ALL
        }
        val key = rawKey.replace(scopePrefix, "").ifEmpty { rawKey }
        // “对白占比”的“对白”不是范围前缀。
        if (rawKey.startsWith("对白") && (key.startsWith("占比") || key.startsWith("比例") || key == "字数占比")) {
            val (min, max) = range(value.replace("%", "").replace("％", "")) ?: return null
            return CheckRule.DialogueRatio(min, max, source)
        }
        fun terms() = terms(value).takeIf { it.isNotEmpty() }
        return when {
            key in setOf("字数", "篇幅", "章节字数", "单章字数", "每章字数", "汉字", "汉字数") -> {
                val (min, max) = range(value) ?: return null
                CheckRule.Length(min, max, han = key.startsWith("汉字") || "汉字" in value, source)
            }
            key == "短段" || key == "长段" -> {
                val nums = numberIn.findAll(value).mapNotNull { cn(it.value) }.toList()
                val chars = nums.getOrNull(0) ?: return null
                CheckRule.Paragraph(chars, short = key == "短段", max = nums.getOrNull(1) ?: 0, scope = scope, source = source)
            }
            bannedKey.matches(key) -> CheckRule.Count(terms() ?: return null, null, 0, false, scope, source)
            combinedKey.matches(key) -> CheckRule.Count(terms() ?: return null, null, cn(combinedKey.find(key)!!.groupValues[1]) ?: return null, true, scope, source)
            maxKey.matches(key) -> CheckRule.Count(terms() ?: return null, null, cn(maxKey.find(key)!!.groupValues[1]) ?: return null, false, scope, source)
            minKey.matches(key) -> CheckRule.Count(terms() ?: return null, cn(minKey.find(key)!!.groupValues[1]) ?: return null, null, false, scope, source)
            else -> null
        }
    }

    /** “3000-6000”“三千五至六千”“≥3000”“不超过 6000”。只有一个数又没说上限下限的，算没看懂。 */
    private fun range(value: String): Pair<Int?, Int?>? {
        val nums = numberIn.findAll(value).mapNotNull { cn(it.value) }.toList()
        return when {
            nums.size >= 2 && Regex("[-－—–~～至到]").containsMatchIn(value) -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1])
            nums.size == 1 && Regex("≥|>=|＞=|至少|最少|不少于|以上|起").containsMatchIn(value) -> nums[0] to null
            nums.size == 1 && Regex("≤|<=|＜=|至多|最多|不超过|不多于|封顶|以内|以下").containsMatchIn(value) -> null to nums[0]
            else -> null
        }
    }

    /**
     * 词用顿号、逗号、分号隔开；括号里的“除外”不拆开。引号（“”「」『』）里的也不拆开：
     * 要查带逗号的说法（“他知道，”）时把它用引号括起来。
     */
    private fun terms(value: String): List<RuleTerm> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quoted = 0
        for (ch in value) {
            when {
                ch == '（' || ch == '(' -> { depth++; cur.append(ch) }
                ch == '）' || ch == ')' -> { depth = (depth - 1).coerceAtLeast(0); cur.append(ch) }
                ch == '“' || ch == '「' || ch == '『' -> { quoted++; cur.append(ch) }
                ch == '”' || ch == '」' || ch == '』' -> { quoted = (quoted - 1).coerceAtLeast(0); cur.append(ch) }
                depth == 0 && quoted == 0 && ch in "、，,；;" -> { parts += cur.toString(); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        parts += cur.toString()
        return parts.mapNotNull { part ->
            var p = part.trim()
            var except = emptyList<String>()
            Regex("[（(]\\s*(?:除外|不计|不算|除了)[：:]?(.*?)[）)]\\s*$").find(p)?.let { m ->
                except = m.groupValues[1].split('、', '，', ',', '／', '/').map(::unquote).filter { it.isNotEmpty() }
                p = p.substring(0, m.range.first).trim()
            }
            val forms = p.split('／', '/', '|', '｜').map(::unquote).filter { it.isNotEmpty() && wildcard.replace(it, "").isNotEmpty() }
            if (forms.isEmpty()) null else RuleTerm(forms, except)
        }
    }

    private fun unquote(s: String) = s.trim().trim('“', '”', '"', '「', '」', '‘', '’', '\'', '『', '』').trim()

    private val wildcard = Regex("……|…|\\.\\.\\.|某某|某人")

    /**
     * 一个说法变成正则：“……”是一句之内的几个字，“某人”是不带标点的几个字。
     * 编出来的正则记住：编辑器停笔就查一遍全章，AI 腔清单加上作者的规则有上百个说法，每次都重新编一遍太费。
     */
    internal fun pattern(form: String): Regex = patterns[form] ?: compile(form).also {
        if (patterns.size >= PATTERN_CACHE) patterns.clear()
        patterns[form] = it
    }

    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private const val PATTERN_CACHE = 2_000

    private fun compile(form: String): Regex {
        val out = StringBuilder()
        var last = 0
        val tokens = wildcard.findAll(form).toList()
        tokens.forEach { m ->
            if (m.range.first > last) out.append(Regex.escape(form.substring(last, m.range.first)))
            out.append(if (m.value.startsWith("某")) "[^\\s\\p{P}]{1,8}?" else "[^。！？!?\\n]{0,30}?")
            last = m.range.last + 1
        }
        if (last < form.length) out.append(Regex.escape(form.substring(last)))
        // 开头、结尾的通配不起作用，去掉（否则会吞掉前后的字）。
        var p = out.toString()
        while (true) {
            val trimmed = p.removePrefix("[^。！？!?\\n]{0,30}?").removeSuffix("[^。！？!?\\n]{0,30}?")
            if (trimmed == p) break
            p = trimmed
        }
        return Regex(p)
    }

    /** 引号（“”「」『』）里的字算对白；换行时没关上的引号也到此为止。 */
    internal fun dialogueMask(text: String): BooleanArray {
        val mask = BooleanArray(text.length)
        var depth = 0
        text.forEachIndexed { i, ch ->
            when (ch) {
                '\n' -> depth = 0
                '“', '「', '『' -> { depth++; mask[i] = true }
                '”', '」', '』' -> { mask[i] = depth > 0; depth = (depth - 1).coerceAtLeast(0) }
                else -> mask[i] = depth > 0
            }
        }
        return mask
    }

    private fun isHan(ch: Char) = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN

    private val SENTENCE_BREAK = Regex("[。！？!?…\\n]+")

    fun stats(text: String, mask: BooleanArray = dialogueMask(text)): ChapterStats {
        val paragraphs = text.lines().map { WordCount.of(it) }.filter { it > 0 }
        val sentences = text.split(SENTENCE_BREAK).map { WordCount.of(it) }.filter { it > 1 }
        val words = WordCount.of(text)
        val dialogue = text.indices.count { mask[it] && !text[it].isWhitespace() }
        return ChapterStats(
            words = words,
            han = text.count(::isHan),
            paragraphs = paragraphs.size,
            medianParagraph = paragraphs.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] },
            averageSentence = if (sentences.isEmpty()) 0 else sentences.sum() / sentences.size,
            dialoguePercent = if (words == 0) 0 else dialogue * 100 / words,
        )
    }

    /** 按规则检查一章。 */
    fun check(text: String, rules: RuleSet): RuleReport {
        val mask = dialogueMask(text)
        val stats = stats(text, mask)
        val hits = rules.rules.flatMap { rule ->
            when (rule) {
                is CheckRule.Length -> {
                    val n = if (rule.han) stats.han else stats.words
                    val unit = if (rule.han) "个汉字" else "字"
                    when {
                        rule.min != null && n < rule.min -> listOf(RuleHit(rule, "本章 $n $unit，少于 ${rule.min}"))
                        rule.max != null && n > rule.max -> listOf(RuleHit(rule, "本章 $n $unit，超过 ${rule.max}"))
                        else -> emptyList()
                    }
                }
                is CheckRule.Count -> countHits(text, mask, rule)
                is CheckRule.Paragraph -> {
                    val found = paragraphs(text).filter { r ->
                        val n = WordCount.of(text.substring(r.first, r.last + 1))
                        val inScope = when (rule.scope) {
                            RuleScope.ALL -> true
                            RuleScope.NARRATION -> r.none { mask[it] }
                            RuleScope.DIALOGUE -> r.any { mask[it] }
                        }
                        inScope && if (rule.short) n <= rule.chars else n >= rule.chars
                    }
                    if (found.size <= rule.max) emptyList()
                    else listOf(RuleHit(rule, "${scopeLead(rule.scope)}${rule.chars} 字${if (rule.short) "以内" else "以上"}的段 ${found.size} 处，至多 ${rule.max}", found))
                }
                is CheckRule.DialogueRatio -> {
                    val p = stats.dialoguePercent
                    when {
                        stats.words == 0 -> emptyList()
                        rule.min != null && p < rule.min -> listOf(RuleHit(rule, "对白占 $p%，少于 ${rule.min}%"))
                        rule.max != null && p > rule.max -> listOf(RuleHit(rule, "对白占 $p%，超过 ${rule.max}%"))
                        else -> emptyList()
                    }
                }
            }
        }
        return RuleReport(hits, stats)
    }

    /** 每段（非空行）在正文里的位置。 */
    private fun paragraphs(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            val lead = line.indexOfFirst { !it.isWhitespace() }
            if (lead >= 0) {
                val trail = line.indexOfLast { !it.isWhitespace() }
                out += (start + lead)..(start + trail)
            }
            start = end + 1
        }
        return out
    }

    private fun scopeWord(scope: RuleScope) = when (scope) {
        RuleScope.ALL -> ""
        RuleScope.NARRATION -> "叙述里"
        RuleScope.DIALOGUE -> "对白里"
    }

    /** 数字前面的范围词带个空格：“叙述里 12 字以内”。 */
    private fun scopeLead(scope: RuleScope) = scopeWord(scope).let { if (it.isEmpty()) it else "$it " }

    /** 一个词在正文里出现的位置（几种写法合起来，去掉重叠的和落在除外说法里的）。 */
    internal fun find(text: String, mask: BooleanArray, term: RuleTerm, scope: RuleScope): List<IntRange> {
        val excluded = term.except.flatMap { e -> pattern(e).findAll(text).map { it.range }.toList() }
        val all = term.forms.flatMap { f -> pattern(f).findAll(text).map { it.range }.filter { !it.isEmpty() }.toList() }.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in all) {
            if (out.isNotEmpty() && r.first <= out.last().last) continue
            if (excluded.any { e -> r.first >= e.first && r.last <= e.last }) continue
            val inScope = when (scope) {
                RuleScope.ALL -> true
                RuleScope.DIALOGUE -> mask[r.first]
                RuleScope.NARRATION -> !mask[r.first]
            }
            if (inScope) out += r
        }
        return out
    }

    private fun countHits(text: String, mask: BooleanArray, rule: CheckRule.Count): List<RuleHit> {
        val where = scopeWord(rule.scope)
        if (rule.combined) {
            val ranges = rule.terms.flatMap { find(text, mask, it, rule.scope) }.sortedBy { it.first }
            val label = rule.terms.joinToString("、") { "「${it.label}」" }
            return when {
                rule.max != null && ranges.size > rule.max -> listOf(RuleHit(rule, "$where$label 合计 ${ranges.size} 次，至多 ${rule.max}", ranges))
                rule.min != null && ranges.size < rule.min -> listOf(RuleHit(rule, "$where$label 合计 ${ranges.size} 次，至少 ${rule.min}", ranges))
                else -> emptyList()
            }
        }
        return rule.terms.mapNotNull { term ->
            val ranges = find(text, mask, term, rule.scope)
            when {
                rule.max == 0 && ranges.isNotEmpty() -> RuleHit(rule, "$where「${term.label}」${ranges.size} 处，不用", ranges, key(rule, term))
                rule.max != null && ranges.size > rule.max -> RuleHit(rule, "$where「${term.label}」${ranges.size} 次，至多 ${rule.max}", ranges, key(rule, term))
                rule.min != null && ranges.size < rule.min -> RuleHit(rule, "$where「${term.label}」${ranges.size} 次，至少 ${rule.min}", ranges, key(rule, term))
                else -> null
            }
        }
    }

    private fun key(rule: CheckRule, term: RuleTerm) = "${rule.source.doc}#${rule.source.line}#${term.label}"

    /** 规则的白话说明（规则页上列出来，让作者确认读对了）。 */
    fun describe(rule: CheckRule): String = when (rule) {
        is CheckRule.Length -> {
            val unit = if (rule.han) "个汉字" else "字"
            when {
                rule.min != null && rule.max != null -> "每章 ${rule.min}–${rule.max} $unit"
                rule.min != null -> "每章至少 ${rule.min} $unit"
                else -> "每章至多 ${rule.max} $unit"
            }
        }
        is CheckRule.Count -> {
            val words = rule.terms.joinToString("、") { t -> t.label + if (t.except.isEmpty()) "" else "（${t.except.joinToString("、")}不算）" }
            val where = scopeWord(rule.scope)
            when {
                rule.max == 0 -> "${where}不用：$words"
                rule.combined -> "${where}每章合计至多 ${rule.max} 次：$words"
                rule.max != null -> "${where}每章各至多 ${rule.max} 次：$words"
                else -> "${where}每章各至少 ${rule.min} 次：$words"
            }
        }
        is CheckRule.Paragraph -> "${scopeLead(rule.scope)}${rule.chars} 字${if (rule.short) "以内" else "以上"}的段，每章至多 ${rule.max} 处"
        is CheckRule.DialogueRatio -> when {
            rule.min != null && rule.max != null -> "对白占全章 ${rule.min}%–${rule.max}%"
            rule.min != null -> "对白至少占全章 ${rule.min}%"
            else -> "对白至多占全章 ${rule.max}%"
        }
    }

    /** 阿拉伯数字或中文数字：“12”“十二”“两”“三千五”（= 3500）。 */
    internal fun cn(s: String): Int? {
        val digits = s.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        digits.toIntOrNull()?.let { return it }
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        var total = 0
        var section = 0
        var digit = -1
        var lastUnit = 0
        var zero = false
        for (ch in s) {
            when (ch) {
                '零' -> zero = true
                in d -> digit = d.getValue(ch)
                in units -> {
                    val u = units.getValue(ch)
                    if (u == 10000) {
                        total += (section + digit.coerceAtLeast(0)) * u
                        section = 0
                    } else {
                        section += (if (digit < 0) 1 else digit) * u
                    }
                    digit = -1
                    lastUnit = u
                }
                else -> return null
            }
        }
        // “三千五”：末尾的数接着上一个单位往下一级（五百）。
        // “三千零五”有“零”，就是五。
        if (digit >= 0) section += if (lastUnit >= 100 && !zero) digit * (lastUnit / 10) else digit
        return (total + section).takeIf { s.isNotEmpty() }
    }

    /** 新建“检查规则”文档时的模板。 */
    const val TEMPLATE = """# 检查规则
# 灵阅在本机按这些规则检查每一章，不联网。每行一条；“#”开头的行是说明，不参与检查。
# 词用顿号隔开；“／”连起来的几种写法合起来算一个词；“……”代表一句之内中间的任意几个字，“某人”代表一个称呼。
# 规则前面加“对白”只查引号里的话，加“叙述”只查引号外面的。

字数：3000-6000
至多 2：突然、仿佛
至多 1：看了某人一眼、顿了顿／一顿
合计至多 1：没说话、没吭声、什么都没说
至少 1：
禁用：
句式：不是……而是……
对白禁用：
短段：12 字以内至多 5 处
对白占比：20%-40%
"""
}
