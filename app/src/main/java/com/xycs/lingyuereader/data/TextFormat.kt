package com.xycs.lingyuereader.data

import org.json.JSONObject

// ---------- 设置 ----------

/** 空行怎么排：不动、连续的压成一行、段与段之间不留。 */
enum class BlankLines(val label: String) {
    KEEP("不动"),
    ONE("压成一行"),
    NONE("不留"),
}

/** 引号风格：外层、里层各一对。 */
enum class QuoteStyle(val label: String, val open: Char, val close: Char, val innerOpen: Char, val innerClose: Char) {
    CURLY("“”‘’", '“', '”', '‘', '’'),
    CORNER("「」『』", '「', '」', '『', '』'),
}

/**
 * 一部作品的排版设置（存在 [WorkAssist.format] 里，随备份走）。各条规则单独开关，默认值照网文的习惯：
 * 中英文之间不加空格，段末缺标点只提示不补。
 */
data class FormatSettings(
    /** 段尾的空白、段首普通的缩进去掉（段首缩进是编辑器显示时加的）；诗歌、落款那样排的段首空白留着。 */
    val trim: Boolean = true,
    /** 汉字、中文标点之间的半角空格去掉（全角空格留着）；英文单词、数字之间的压成一个。 */
    val spaces: Boolean = true,
    val blankLines: BlankLines = BlankLines.ONE,
    /** 中文语境里的半角 , . ? ! : ; ( ) 换成全角。 */
    val punct: Boolean = true,
    /** 直引号按前后推断开闭，换成中文引号；已有的中文引号不改方向（「」风格下按嵌套换成「」『』）。 */
    val quotes: Boolean = true,
    val quoteStyle: QuoteStyle = QuoteStyle.CURLY,
    /** 省略号、破折号统一成两个字宽的“……”“——”。 */
    val marks: Boolean = true,
    /** 连着的逗号、句号合成一个。 */
    val repeats: Boolean = true,
    /** 连着的感叹号、问号最多留 3 个。 */
    val limitBangs: Boolean = false,
    /** 汉字和英文、数字之间加空格（网文一般不加）。 */
    val mixedSpace: Boolean = false,
    /** 以汉字结尾、或对话引号里最后是汉字的段：预览里提示段末缺标点（不自动补）。 */
    val endHint: Boolean = true,
    /** 输入时自动排版（...→……、汉字后的半角标点换全角、引号配对）。 */
    val onInput: Boolean = true,
) {
    /** 一键排版要跑的几类。 */
    val kinds: Set<FormatKind>
        get() = buildSet {
            if (trim) add(FormatKind.TRIM)
            if (spaces) add(FormatKind.SPACE)
            if (blankLines != BlankLines.KEEP) add(FormatKind.BLANK)
            if (punct) add(FormatKind.PUNCT)
            if (quotes) add(FormatKind.QUOTE)
            if (marks) {
                add(FormatKind.ELLIPSIS)
                add(FormatKind.DASH)
            }
            if (repeats) add(FormatKind.REPEAT)
            if (mixedSpace) add(FormatKind.MIXED)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("trim", trim).put("spaces", spaces).put("blankLines", blankLines.name)
        .put("punct", punct).put("quotes", quotes).put("quoteStyle", quoteStyle.name)
        .put("marks", marks).put("repeats", repeats).put("limitBangs", limitBangs)
        .put("mixedSpace", mixedSpace).put("endHint", endHint).put("onInput", onInput)

    companion object {
        fun fromJson(o: JSONObject?): FormatSettings {
            if (o == null) return FormatSettings()
            val d = FormatSettings()
            return FormatSettings(
                trim = o.optBoolean("trim", d.trim),
                spaces = o.optBoolean("spaces", d.spaces),
                blankLines = runCatching { BlankLines.valueOf(o.optString("blankLines")) }.getOrDefault(d.blankLines),
                punct = o.optBoolean("punct", d.punct),
                quotes = o.optBoolean("quotes", d.quotes),
                quoteStyle = runCatching { QuoteStyle.valueOf(o.optString("quoteStyle")) }.getOrDefault(d.quoteStyle),
                marks = o.optBoolean("marks", d.marks),
                repeats = o.optBoolean("repeats", d.repeats),
                limitBangs = o.optBoolean("limitBangs", d.limitBangs),
                mixedSpace = o.optBoolean("mixedSpace", d.mixedSpace),
                endHint = o.optBoolean("endHint", d.endHint),
                onInput = o.optBoolean("onInput", d.onInput),
            )
        }
    }
}

// ---------- 结果 ----------

/** 排版改动的类别（预览里按类计数、勾掉），按预览里列出的先后排。 */
enum class FormatKind(val label: String) {
    PUNCT("标点"),
    QUOTE("引号"),
    ELLIPSIS("省略号"),
    DASH("破折号"),
    SPACE("空格"),
    TRIM("段首尾空白"),
    BLANK("空行"),
    REPEAT("重复标点"),
    MIXED("中英空格"),
}

/** 一处改动：原文 [start, end)（[before]）被 [kind] 这条规则换成了 [after]。 */
data class FormatChange(val kind: FormatKind, val start: Int, val end: Int, val before: String, val after: String)

/** 只提示、不自动改的。 */
enum class FormatHint(val label: String) {
    UNPAIRED("配不上的引号"),
    NO_END("段末缺标点"),
}

/** 一条提示：排好的正文里 [at] 处，所在的那段是 [line]。 */
data class FormatNote(val hint: FormatHint, val at: Int, val line: String)

/**
 * 排一次版的结果：[text] 是排好的正文；[changes] 每处改动记着原文里的位置和规则；[notes] 是配不上的引号、段末缺标点这类提示。
 */
class FormatResult internal constructor(
    val original: String,
    val text: String,
    val changes: List<FormatChange>,
    val notes: List<FormatNote>,
    private val passes: List<FormatPass>,
) {
    val changed: Boolean get() = text != original

    /** 各类改了几处，按 [FormatKind] 的先后。 */
    val counts: Map<FormatKind, Int>
        get() = FormatKind.entries.mapNotNull { k -> changes.count { it.kind == k }.takeIf { it > 0 }?.let { k to it } }.toMap()

    /** 原文里的位置（光标）在排好的正文里在哪。 */
    fun map(pos: Int): Int = passes.fold(pos) { p, pass -> pass.forward(p) }.coerceIn(0, text.length)
}

// ---------- 规则 ----------

/**
 * 自动排版：段首段尾空白、段内多余空格、空行、标点全角化、引号、省略号破折号、重复标点、中英文空格。
 * 一行是一段；“***”“* * *”“——”这类单独一行的场景分隔原样不动。不改汉字本身；排两次和排一次一样（幂等）。
 * 每条规则依次跑，改动换算回原文的位置记下来，给预览用。
 */
object TextFormat {
    /** 规则跑的先后（和预览里列的先后无关）：先定省略号、破折号，再换标点，标点定了才好认引号的语境，最后收拾空格。 */
    private val ORDER = listOf(
        FormatKind.TRIM, FormatKind.BLANK, FormatKind.ELLIPSIS, FormatKind.DASH, FormatKind.PUNCT,
        FormatKind.QUOTE, FormatKind.SPACE, FormatKind.REPEAT, FormatKind.MIXED,
    )

    /** 一轮下来还有能改的（前面的规则给后面的规则造出了新的情况）就再跑一轮，最多几轮。 */
    private const val ROUNDS = 3

    /** 按 [settings] 排 [text]；[kinds] 是这次要跑的几类（预览里勾掉的不跑）。 */
    fun format(text: String, settings: FormatSettings, kinds: Set<FormatKind> = settings.kinds): FormatResult {
        var cur = text
        val passes = mutableListOf<FormatPass>()
        val changes = mutableListOf<FormatChange>()
        for (round in 0 until ROUNDS) {
            var any = false
            for (k in ORDER) {
                if (k !in kinds) continue
                val edits = edits(k, cur, settings).sortedBy { it.start }
                if (edits.isEmpty()) continue
                any = true
                edits.forEach { e ->
                    val s = passes.foldRight(e.start) { pass, p -> pass.back(p, end = false) }
                    val t = passes.foldRight(e.end) { pass, p -> pass.back(p, end = true) }.coerceAtLeast(s)
                    changes += FormatChange(k, s, t, text.substring(s, t), e.text)
                }
                val pass = FormatPass(edits)
                cur = pass.apply(cur)
                passes += pass
            }
            if (!any) break
        }
        return FormatResult(text, cur, changes.sortedBy { it.start }, notes(cur, settings), passes)
    }

    /** 排一段（写作建议按段落认，排版后把记着的段落也换成排好的样子）。 */
    fun formatParagraph(paragraph: String, settings: FormatSettings, kinds: Set<FormatKind> = settings.kinds): String =
        format(paragraph, settings, kinds - FormatKind.BLANK).text

    private fun edits(kind: FormatKind, t: String, s: FormatSettings): List<FormatEdit> = when (kind) {
        FormatKind.TRIM -> trim(t)
        FormatKind.BLANK -> blanks(t, s.blankLines)
        FormatKind.ELLIPSIS -> ellipsis(t)
        FormatKind.DASH -> dashes(t)
        FormatKind.PUNCT -> punct(t)
        FormatKind.QUOTE -> quotes(t, s.quoteStyle)
        FormatKind.SPACE -> spaces(t)
        FormatKind.REPEAT -> repeats(t, s.limitBangs)
        FormatKind.MIXED -> mixed(t)
    }

    /**
     * 1. 段首段尾空白；只有空白的行变成空行。段首只去普通的段首缩进（1–2 个全角空格、1–4 个半角空格或一个制表符，
     * 后面是没有全角空格的普通段落）；更多的、混着的、诗歌对仗那样段内也用全角空格排的（右缩进的落款等）留着。
     */
    private fun trim(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            var a = s
            while (a < e && isSpace(t[a])) a++
            if (a == e) {
                if (e > s) add(FormatEdit(s, e, ""))
                return@lines
            }
            var b = e
            while (b > a && isSpace(t[b - 1])) b--
            if (a > s && plainIndent(t.substring(s, a)) && (a until b).none { t[it] == '　' }) add(FormatEdit(s, a, ""))
            if (b < e) add(FormatEdit(b, e, ""))
        }
    }

    /** 看起来就是普通段首缩进的：1–2 个全角空格、1–4 个半角空格，或一个制表符。 */
    private fun plainIndent(indent: String): Boolean =
        (indent.length <= 2 && indent.all { it == '　' }) || (indent.length <= 4 && indent.all { it == ' ' }) || indent == "\t"

    /** 3. 空行：连续的压成一行（或一行不留），全文开头结尾的空行去掉。只有空白的行也算空行。 */
    private fun blanks(t: String, mode: BlankLines): List<FormatEdit> {
        if (mode == BlankLines.KEEP) return emptyList()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        lines(t) { s, e ->
            starts += s
            ends += e
        }
        val n = starts.size
        val blank = BooleanArray(n) { i -> (starts[i] until ends[i]).all { isSpace(t[it]) } }
        val out = ArrayList<FormatEdit>()
        var i = 0
        while (i < n) {
            if (!blank[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && blank[j]) j++
            val (from, to) = when {
                i == 0 && j == n -> 0 to t.length
                i == 0 -> 0 to starts[j]
                j == n -> ends[i - 1] to t.length
                mode == BlankLines.ONE -> ends[i] to ends[j - 1]
                else -> ends[i - 1] to ends[j - 1]
            }
            if (to > from) out += FormatEdit(from, to, "")
            i = j
        }
        return out
    }

    private val ELLIPSIS = Regex("\\.{3,}|。{3,}|．{3,}|·{3,}|…+|⋯+")

    /** 6. 省略号：... 。。。 ．．． ··· ⋯ 单个… → ……（英文里的 ... 不动）。 */
    private fun ellipsis(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            val line = t.substring(s, e)
            ELLIPSIS.findAll(line).forEach { m ->
                val v = m.value
                val wanted = when (v[0]) {
                    '.' -> cnAround(line, m.range.first, m.range.last + 1)
                    '…' -> v.length == 1 || v.length == 3
                    else -> true
                }
                if (wanted && v != "……") add(FormatEdit(s + m.range.first, s + m.range.last + 1, "……"))
            }
        }
    }

    private val DASH = Regex("-{2,}|—+|─+|－{2,}|–{2,}")

    /** 6. 破折号：-- 单个— ── －－ → ——（单个的只在中文语境里补成双；英文里的不动）。 */
    private fun dashes(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            val line = t.substring(s, e)
            DASH.findAll(line).forEach { m ->
                val v = m.value
                val around = cnAround(line, m.range.first, m.range.last + 1)
                val wanted = when (v[0]) {
                    '—' -> (v.length == 1 && around) || v.length == 3
                    '－' -> true
                    else -> around
                }
                if (wanted && v != "——") add(FormatEdit(s + m.range.first, s + m.range.last + 1, "——"))
            }
        }
    }

    private val PROTECTED = listOf(
        // 网址、邮箱、文件名里的点、冒号、问号不动。网址末尾跟着的句号算正文。
        Regex("(?i)\\b(?:https?|ftp)://[^\\s\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF“”‘’「」]+"),
        Regex("(?i)\\bwww\\.[^\\s\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF“”‘’「」]+"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"),
        Regex("\\b[A-Za-z0-9_-]+\\.[A-Za-z0-9]{1,8}\\b"),
    )

    private const val HALF = ",.?!:;()"
    private const val FULL = "，。？！：；（）"

    /**
     * 4. 中文语境里的半角标点换全角：前后（跳过空格、引号和别的半角标点）挨着汉字或中文标点。
     * 不动：数字里的小数点、千分位、时间（3.14、1,000、12:30），连着的几个点（英文省略号），网址、邮箱、文件名，英文句子；
     * “1.”“A.”这类编号的点；紧跟在英文字母、数字后面的括号和分号（代码、“1)”编号）；:) :( :D 这类表情。
     * 圆括号成对的话一起换。
     */
    private fun punct(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            val line = t.substring(s, e)
            if (line.none { it in HALF }) return@lines
            val guard = BooleanArray(line.length)
            PROTECTED.forEach { r ->
                r.findAll(line).forEach { m ->
                    var end = m.range.last + 1
                    while (end > m.range.first && line[end - 1] in ".,!?;:)") end--
                    for (i in m.range.first until end) guard[i] = true
                }
            }
            // 圆括号配对（网址里的不算）。
            val match = IntArray(line.length) { -1 }
            val stack = ArrayList<Int>()
            for (i in line.indices) {
                when (line[i]) {
                    '(' -> if (!guard[i]) stack += i
                    ')' -> if (!guard[i] && stack.isNotEmpty()) {
                        val o = stack.removeAt(stack.lastIndex)
                        match[o] = i
                        match[i] = o
                    }
                }
            }
            for (i in line.indices) {
                val c = line[i]
                if (guard[i]) continue
                val afterAscii = line.getOrNull(i - 1)?.isAsciiLetterOrDigit() == true
                when {
                    // 代码样的片段：紧跟在英文字母、数字后面的一对圆括号（里面没有汉字）连同后面的分号不动（println(x);）。
                    c == '(' && afterAscii && match[i] >= 0 && (i + 1 until match[i]).none { isHan(line[it]) } -> {
                        guard[i] = true
                        guard[match[i]] = true
                        if (line.getOrNull(match[i] + 1) == ';') guard[match[i] + 1] = true
                    }
                    // 紧跟在英文字母、数字后面、配不上的括号（“1)”这类编号）和分号不动。
                    (c in "()" && afterAscii && match[i] < 0) || (c == ';' && afterAscii) -> guard[i] = true
                    // 表情 :) :( :-) ;) :D :P 不动（配上了的括号不算表情）。
                    c in ":;" -> {
                        val j = if (line.getOrNull(i + 1) == '-') i + 2 else i + 1
                        val face = line.getOrNull(j)
                        val after = line.getOrNull(j + 1)
                        if ((face == '(' || face == ')') && match[j] < 0) {
                            for (k in i..j) guard[k] = true
                        } else if ((face == 'D' || face == 'P') && (after == null || !(after.isAsciiLetterOrDigit() || isHan(after)))) {
                            guard[i] = true
                        }
                    }
                }
            }
            val convert = BooleanArray(line.length)
            for (i in line.indices) {
                val c = line[i]
                if (c !in HALF || guard[i]) continue
                if (c == '.' && (line.getOrNull(i - 1) == '.' || line.getOrNull(i + 1) == '.')) continue
                if (c in ".,:" && line.getOrNull(i - 1)?.isAsciiDigit() == true && line.getOrNull(i + 1)?.isAsciiDigit() == true) continue
                if (c == '.' && numbering(line, i)) continue
                if (cnAround(line, i, i + 1)) convert[i] = true
            }
            // 圆括号成对：一边换了另一边也换（“(abc)他”不排成“(abc）他”）。
            for (i in line.indices) {
                if (line[i] == '(' && match[i] >= 0 && !guard[i] && (convert[i] || convert[match[i]])) {
                    convert[i] = true
                    convert[match[i]] = true
                }
            }
            for (i in line.indices) if (convert[i]) add(FormatEdit(s + i, s + i + 1, FULL[HALF.indexOf(line[i])].toString()))
        }
    }

    /**
     * [i] 处的点是“1.”“A.”这类编号的点：前面是一串数字或单个英文字母，再往前是行首、空白、左括号或标点，后面还有字
     * （“任务:1.击杀”“e.g.他”）。
     */
    private fun numbering(line: String, i: Int): Boolean {
        if (i + 1 >= line.length) return false
        var k = i - 1
        while (k >= 0 && line[k].isAsciiDigit()) k--
        if (k == i - 1) {
            if (k < 0 || !line[k].isAsciiLetter()) return false
            k--
        }
        val before = line.getOrNull(k) ?: return true
        return isSpace(before) || before in "(（[【〔" || before in HALF_MARKS || before in "，。、；：？！"
    }

    /**
     * 5. 引号：已有的中文引号（“”‘’；「」风格下还有「」『』）方向是定的，当锚点，绝不改方向；只有直引号 " ' 的开闭要推断（[resolveQuotes]）。
     * 中文语境的换成 [style] 的一对：“”风格下直引号换成“”‘’；「」风格下直引号和已有的“”‘’按嵌套层级换，外层「」、里层『』。
     * 推断不了的直引号不动，预览里提示。英文单词里的撇号（don't）不算引号。
     */
    private fun quotes(t: String, style: QuoteStyle): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            val line = t.substring(s, e)
            if (line.none { it in QUOTE_MARKS }) return@lines
            val levels = ArrayList<Int>()
            quoteMarks(line, style).forEach { m ->
                if (m.dir == 0) return@forEach
                val open = m.dir > 0
                val target = when (style) {
                    QuoteStyle.CURLY -> if (m.inner) (if (open) style.innerOpen else style.innerClose) else (if (open) style.open else style.close)
                    QuoteStyle.CORNER -> {
                        // 开引号的层级是外面还开着几层；闭引号跟着和它配上的开引号（段首续段的闭引号算外层）。
                        val level = if (open) levels.size.also { levels += it } else levels.removeLastOrNull() ?: 0
                        if (level == 0) (if (open) style.open else style.close) else (if (open) style.innerOpen else style.innerClose)
                    }
                }
                // 已有的「」『』是作者自己排的，不动。
                if (m.cn && line[m.at] != target && line[m.at] !in "「」『』") add(FormatEdit(s + m.at, s + m.at + 1, target.toString()))
            }
        }
    }

    /** 一个引号：在段里的 [at]；[dir] 1 是开、-1 是闭、0 是推断不了的直引号；[inner] 是单引号这一类；[cn] 在中文语境里（该换）。 */
    private class QuoteMark(val at: Int, val dir: Int, val inner: Boolean, val cn: Boolean)

    /** 一段里的引号：双引号、单引号两类分开推断（「」风格下「」算双引号这类、『』算单引号这类），按位置排好。 */
    private fun quoteMarks(line: String, style: QuoteStyle): List<QuoteMark> {
        val corner = style == QuoteStyle.CORNER
        return (quoteFamily(line, inner = false, corner = corner) + quoteFamily(line, inner = true, corner = corner)).sortedBy { it.at }
    }

    private fun quoteFamily(line: String, inner: Boolean, corner: Boolean): List<QuoteMark> {
        val at = ArrayList<Int>()
        val fixed = ArrayList<Int>()
        for (i in line.indices) {
            val d = when (line[i]) {
                '"' -> if (inner) null else 0
                '“' -> if (inner) null else 1
                '”' -> if (inner) null else -1
                '\'' -> if (inner && !apostrophe(line, i)) 0 else null
                '‘' -> if (inner) 1 else null
                '’' -> if (inner) -1 else null
                '「' -> if (corner && !inner) 1 else null
                '」' -> if (corner && !inner) -1 else null
                '『' -> if (corner && inner) 1 else null
                '』' -> if (corner && inner) -1 else null
                else -> null
            } ?: continue
            at += i
            fixed += d
        }
        if (at.isEmpty()) return emptyList()
        val dir = resolveQuotes(line, at, fixed.toIntArray())
        // 配对（续段的闭引号、还开着的开引号没有对家），定中文语境：引号里有汉字，或开引号前、闭引号后挨着中文。
        val partner = IntArray(at.size) { -1 }
        val stack = ArrayList<Int>()
        for (k in at.indices) {
            if (dir[k] > 0) {
                stack += k
            } else if (dir[k] < 0 && stack.isNotEmpty()) {
                val o = stack.removeAt(stack.lastIndex)
                partner[o] = k
                partner[k] = o
            }
        }
        return at.indices.map { k ->
            val i = at[k]
            val cn = when {
                dir[k] == 0 -> false
                partner[k] >= 0 -> {
                    val o = at[minOf(k, partner[k])]
                    val c = at[maxOf(k, partner[k])]
                    (o + 1 until c).any { isHan(line[it]) } || cnBefore(line, o) || cnAfter(line, c + 1)
                }
                dir[k] > 0 -> cnBefore(line, i) || (i + 1 until line.length).any { isHan(line[it]) }
                else -> (0 until i).any { isHan(line[it]) } || cnAfter(line, i + 1)
            }
            QuoteMark(i, dir[k], inner, cn)
        }
    }

    /**
     * 一类引号里直引号的开闭：[fixed] 里锚点是 1（开）或 -1（闭），直引号是 0；返回每个引号的方向，推断不了的是 0。
     * 从段首走到段末，开着的层数不能小于 0；段首可以已经开着一层（跨段对话的续段：段首没有开引号就先出现闭引号）。
     * 两个锚点之间的一串直引号一开一闭交替；段首（或冒号、左括号后面）的只能开，段末的只能闭。
     * 走得通、段末还开着的层数最少的那些读法里，一个直引号方向都一样才算定了；几种读法都说得通的不动。
     * 段末还开着的直引号（单数个的最后一个）也不动。
     */
    private fun resolveQuotes(line: String, at: List<Int>, fixed: IntArray): IntArray {
        val n = at.size
        val dir = fixed.copyOf()
        if (fixed.all { it != 0 }) return dir
        val canOpen = BooleanArray(n) { fixed[it] != 0 || !mustCloseQuote(line, at[it]) }
        val canClose = BooleanArray(n) { fixed[it] != 0 || !mustOpenQuote(line, at[it]) }

        // 状态 = 开着的层数 * 3 + 这串直引号里上一个的方向（0 还没有、1 开、2 闭）；走不通是 -1。
        fun step(state: Int, k: Int, d: Int): Int {
            val prev = state % 3
            val next = if (fixed[k] != 0) {
                if (d != fixed[k]) return -1
                0
            } else {
                if ((prev == 1 && d > 0) || (prev == 2 && d < 0)) return -1
                if ((d > 0 && !canOpen[k]) || (d < 0 && !canClose[k])) return -1
                if (d > 0) 1 else 2
            }
            val depth = state / 3 + d
            return if (depth < 0) -1 else depth * 3 + next
        }
        val reach = Array(n + 1) { HashSet<Int>() }
        reach[0] += 0
        reach[0] += 3
        for (k in 0 until n) for (st in reach[k]) for (d in DIRS) step(st, k, d).takeIf { it >= 0 }?.let { reach[k + 1] += it }
        if (reach[n].isEmpty()) {
            for (k in 0 until n) if (fixed[k] == 0) dir[k] = 0
            return dir
        }
        val least = reach[n].minOf { it / 3 }
        var good: Set<Int> = reach[n].filterTo(HashSet()) { it / 3 == least }
        val chosen = Array(n) { HashSet<Int>() }
        for (k in n - 1 downTo 0) {
            val here = HashSet<Int>()
            for (st in reach[k]) {
                for (d in DIRS) {
                    if (step(st, k, d) in good) {
                        here += st
                        chosen[k] += d
                    }
                }
            }
            good = here
        }
        for (k in 0 until n) if (fixed[k] == 0) dir[k] = chosen[k].singleOrNull() ?: 0
        // 段末还开着的直引号：可能是没打完，也可能是跨段对话的开头，不动。
        val stack = ArrayList<Int>()
        for (k in 0 until n) {
            if (dir[k] > 0) stack += k else if (dir[k] < 0 && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
        stack.forEach { if (fixed[it] == 0) dir[it] = 0 }
        return dir
    }

    private val DIRS = intArrayOf(1, -1)

    /** 直引号只能是开引号：段首，紧跟在冒号、左括号后面，或者前面是空格、后面紧挨着字。 */
    private fun mustOpenQuote(line: String, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && isSpace(line[k])) k--
        if (k < 0 || line[k] in "：:（(【[《〈") return true
        val next = line.getOrNull(i + 1)
        return isSpace(line[i - 1]) && next != null && !isSpace(next)
    }

    /** 直引号只能是闭引号：段末，或者后面是空格、前面紧挨着字。 */
    private fun mustCloseQuote(line: String, i: Int): Boolean {
        var k = i + 1
        while (k < line.length && isSpace(line[k])) k++
        if (k == line.length) return true
        val prev = line.getOrNull(i - 1)
        return isSpace(line[i + 1]) && prev != null && !isSpace(prev)
    }

    /** 英文单词里的撇号：两边都是英文字母。 */
    private fun apostrophe(line: CharSequence, i: Int) = line.getOrNull(i - 1)?.isAsciiLetter() == true && line.getOrNull(i + 1)?.isAsciiLetter() == true

    /**
     * 2. 段内多余空格：两边都是汉字或中文标点的，半角空格去掉（“第三章 归来”也排成“第三章归来”），全角空格（诗歌、对仗、落款里排的）留着；
     * 别的（英文单词、数字之间）压成一个半角空格，连着两个以上全角空格的只留全角空格。段首的空白归第 1 条管。
     */
    private fun spaces(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            var a = s
            while (a < e && isSpace(t[a])) a++
            var b = e
            while (b > a && isSpace(t[b - 1])) b--
            var i = a
            while (i < b) {
                if (!isSpace(t[i])) {
                    i++
                    continue
                }
                var j = i
                while (j < b && isSpace(t[j])) j++
                val wide = (i until j).count { t[it] == '　' }
                val keep = "　".repeat(wide)
                when {
                    (isCn(t[i - 1]) && isCn(t[j])) || wide >= 2 -> if (j - i != wide) add(FormatEdit(i, j, keep))
                    j - i != 1 || t[i] != ' ' -> add(FormatEdit(i, j, " "))
                }
                i = j
            }
        }
    }

    private val REPEAT = Regex("，{2,}|(?<!。)。。(?!。)")
    private val BANGS = Regex("[！？]{4,}")

    /** 7. 重复标点：，，→，；。。→。（三个以上的句号是省略号，不在这里合）；可选“！？最多留 3 个”。 */
    private fun repeats(t: String, limitBangs: Boolean): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            val line = t.substring(s, e)
            REPEAT.findAll(line).forEach { m -> add(FormatEdit(s + m.range.first, s + m.range.last + 1, m.value.take(1))) }
            if (limitBangs) BANGS.findAll(line).forEach { m -> add(FormatEdit(s + m.range.first, s + m.range.last + 1, m.value.take(3))) }
        }
    }

    /** 8. 汉字和英文字母、数字之间加一个空格。 */
    private fun mixed(t: String): List<FormatEdit> = buildList {
        lines(t) { s, e ->
            if (isSeparator(t, s, e)) return@lines
            for (i in s + 1 until e) {
                val a = t[i - 1]
                val b = t[i]
                if ((isHan(a) && b.isAsciiLetterOrDigit()) || (a.isAsciiLetterOrDigit() && isHan(b))) add(FormatEdit(i, i, " "))
            }
        }
    }

    /** 9. 提示：配不上的引号（引号规则开着时）、段末缺标点（以汉字结尾，或引号里最后是汉字）。 */
    private fun notes(t: String, s: FormatSettings): List<FormatNote> = buildList {
        lines(t) { a, b ->
            if (a == b || isSeparator(t, a, b)) return@lines
            val line = t.substring(a, b)
            if (s.quotes && line.any { it in QUOTE_MARKS }) {
                // 推断不了开闭的直引号；没有对家的闭引号（段首续段的第一个除外）；段末还开着的开引号（里层套了同一种引号的也在这里提示）。
                val marks = quoteMarks(line, s.quoteStyle)
                listOf(false, true).forEach { inner ->
                    val open = ArrayList<Int>()
                    var seen = false
                    marks.filter { it.inner == inner }.forEach { m ->
                        when {
                            m.dir == 0 -> add(FormatNote(FormatHint.UNPAIRED, a + m.at, line))
                            m.dir > 0 -> open += m.at
                            open.isNotEmpty() -> open.removeAt(open.lastIndex)
                            !seen -> Unit
                            else -> add(FormatNote(FormatHint.UNPAIRED, a + m.at, line))
                        }
                        seen = true
                    }
                    open.forEach { add(FormatNote(FormatHint.UNPAIRED, a + it, line)) }
                }
            }
            if (s.endHint) {
                val body = line.trimEnd { isSpace(it) }
                val last = body.lastOrNull()
                val missing = when {
                    last == null -> false
                    isHan(last) -> true
                    last in "”」』’" -> body.getOrNull(body.length - 2)?.let(::isHan) == true
                    else -> false
                }
                if (missing) add(FormatNote(FormatHint.NO_END, a + body.length, line))
            }
        }
    }.sortedBy { it.at }
}

// ---------- 输入时自动排版 ----------

/**
 * 输入时自动排版：作者刚打进去的字就地换——打出 ... 或第三个 . 变成 ……，-- 变成 ——；
 * 汉字后面紧跟着打的半角 , . ? ! : ; 换成全角（数字后面的点不换，好打小数）；直引号按段里的配对变成开引号或闭引号。
 * 只看提交了的字：输入法还在组合（拼音没上屏）时不换。
 */
object TypingFormat {
    /** 把 [start, end) 换成 [text]，光标放到 [cursor]。 */
    data class Fix(val start: Int, val end: Int, val text: String, val cursor: Int)

    /** 一次粘贴进来这么多字就不动了（大段的用“自动排版”）。 */
    const val MAX_INSERT = 2_000

    /**
     * 作者在 [text] 的 [start, end) 打进了字（[text] 里已经有了）：要不要换、怎么换。[composing] 为真（输入法还在组合）时不换。
     */
    fun fix(text: CharSequence, start: Int, end: Int, style: QuoteStyle, composing: Boolean = false): Fix? {
        if (composing || start < 0 || start >= end || end > text.length || end - start > MAX_INSERT) return null
        var p = start
        while (p > 0 && text[p - 1] != '\n') p--
        val out = StringBuilder(text.subSequence(p, start))
        for (i in start until end) type(out, text[i], style)
        val old = text.subSequence(p, end)
        if (out.length == old.length && out.indices.all { out[it] == old[it] }) return null
        var prefix = 0
        val m = minOf(out.length, old.length)
        while (prefix < m && out[prefix] == old[prefix]) prefix++
        return Fix(p + prefix, end, out.substring(prefix), p + out.length)
    }

    private fun type(out: StringBuilder, c: Char, style: QuoteStyle) {
        val prev = out.lastOrNull()
        when (c) {
            '.' -> {
                val run = trailing(out) { it == '.' || it == '。' }
                when {
                    run >= 2 -> replaceTail(out, run, "……")
                    // “他说。”后面又打一个点：多半是在打省略号，先退成两个点，第三个点再变成“……”。
                    run == 1 && prev == '。' -> replaceTail(out, 1, "..")
                    prev != null && (isHan(prev) || prev in CLOSERS) -> out.append('。')
                    else -> out.append('.')
                }
            }
            '。' -> {
                val run = trailing(out) { it == '.' || it == '。' }
                if (run >= 2) replaceTail(out, run, "……") else out.append(c)
            }
            '-' -> if (prev == '-') replaceTail(out, 1, "——") else out.append(c)
            ',', '?', '!', ':', ';' -> out.append(if (prev != null && (isHan(prev) || prev in CLOSERS || prev in "？！…—")) "，？！：；"[",?!:;".indexOf(c)] else c)
            '"' -> quote(out, style.open, style.close, c)
            '\'' -> if (prev?.isAsciiLetter() == true) out.append(c) else quote(out, style.innerOpen, style.innerClose, c)
            else -> out.append(c)
        }
    }

    /** 这段里开了没关的就关上；段首、汉字或中文标点后面开一个；别的（英文里）照打。 */
    private fun quote(out: StringBuilder, open: Char, close: Char, typed: Char) {
        val para = out.lastIndexOf("\n") + 1
        var depth = 0
        for (i in para until out.length) {
            if (out[i] == open) depth++ else if (out[i] == close && depth > 0) depth--
        }
        val prev = out.lastOrNull()
        out.append(
            when {
                depth > 0 -> close
                prev == null || prev == '\n' || isCn(prev) -> open
                else -> typed
            },
        )
    }

    private fun replaceTail(out: StringBuilder, n: Int, with: String) {
        out.setLength(out.length - n)
        out.append(with)
    }

    private inline fun trailing(out: StringBuilder, match: (Char) -> Boolean): Int {
        var n = 0
        while (n < out.length && match(out[out.length - 1 - n])) n++
        return n
    }

    /** 后面跟着打标点时算“中文语境”的收尾符号。 */
    private const val CLOSERS = "”’」』）》"
}

// ---------- 工具 ----------

/** 一条规则对当时正文的一处改动：[start, end) 换成 [text]。 */
internal class FormatEdit(val start: Int, val end: Int, val text: String)

/** 一条规则跑一遍的全部改动（按位置排好、互不重叠）：能把前后的位置互相换算。 */
internal class FormatPass(private val edits: List<FormatEdit>) {
    private val outStart = IntArray(edits.size)
    private val shiftBefore = IntArray(edits.size)

    init {
        var shift = 0
        edits.forEachIndexed { i, e ->
            shiftBefore[i] = shift
            outStart[i] = e.start + shift
            shift += e.text.length - (e.end - e.start)
        }
    }

    fun apply(t: String): String {
        val sb = StringBuilder(t.length + 16)
        var at = 0
        edits.forEach { e ->
            sb.append(t, at, e.start).append(e.text)
            at = e.end
        }
        return sb.append(t, at, t.length).toString()
    }

    /** 改后的位置 [p] 在改前的哪里；落在换上去的字里时，起点算到那处改动的开头，终点算到结尾。 */
    fun back(p: Int, end: Boolean): Int {
        var lo = 0
        var hi = edits.size - 1
        var idx = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (outStart[m] <= p) {
                idx = m
                lo = m + 1
            } else hi = m - 1
        }
        if (idx < 0) return p
        val e = edits[idx]
        val os = outStart[idx]
        val oe = os + e.text.length
        return when {
            p == os -> e.start
            p < oe -> if (end) e.end else e.start
            else -> p - shiftBefore[idx] - (e.text.length - (e.end - e.start))
        }
    }

    /** 改前的位置 [p] 在改后的哪里；落在被换掉的字里时算到换上去的字后面。 */
    fun forward(p: Int): Int {
        var lo = 0
        var hi = edits.size - 1
        var idx = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (edits[m].start <= p) {
                idx = m
                lo = m + 1
            } else hi = m - 1
        }
        if (idx < 0) return p
        val e = edits[idx]
        return when {
            p == e.start -> outStart[idx]
            p < e.end -> outStart[idx] + e.text.length
            else -> p + shiftBefore[idx] + e.text.length - (e.end - e.start)
        }
    }
}

/** 一行一行地走：[block] 拿到每行的 [start, end)（不含换行）。 */
private inline fun lines(t: String, block: (Int, Int) -> Unit) {
    var s = 0
    while (true) {
        val n = t.indexOf('\n', s)
        val e = if (n < 0) t.length else n
        block(s, e)
        if (n < 0) break
        s = n + 1
    }
}

private val SEPARATOR = Regex("[\\s\\u3000*＊\\-—─=~～·•#＃×+＋_※☆★○●◇◆]+")

/** 场景分隔行（“***”“* * *”“——”“~~~”）：原样不动。 */
private fun isSeparator(t: String, s: Int, e: Int): Boolean {
    if (s == e) return false
    val line = t.subSequence(s, e)
    return line.any { !isSpace(it) } && SEPARATOR.matches(line)
}

internal fun isHan(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' || c == '〇'

private const val CN_PUNCT = "，。、；：？！…—～·“”‘’「」『』（）《》〈〉【】〔〕"

/** 汉字或中文标点。 */
internal fun isCn(c: Char): Boolean = isHan(c) || c in CN_PUNCT

internal fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '　' || c == ' '

private fun Char.isAsciiDigit() = this in '0'..'9'
private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
private fun Char.isAsciiLetterOrDigit() = isAsciiDigit() || isAsciiLetter()

/** 认语境时跳过的：空白、半角标点、各种引号（它们自己还没定）。 */
private fun skippable(c: Char) = isSpace(c) || c in HALF_MARKS || c in QUOTE_MARKS
private const val HALF_MARKS = ",.?!:;()"
private const val QUOTE_MARKS = "\"'“”‘’「」『』"

private fun cnBefore(line: CharSequence, i: Int): Boolean {
    var k = i - 1
    while (k >= 0 && skippable(line[k])) k--
    return k >= 0 && isCn(line[k])
}

private fun cnAfter(line: CharSequence, i: Int): Boolean {
    var k = i
    while (k < line.length && skippable(line[k])) k++
    return k < line.length && isCn(line[k])
}

/** [start, end) 前后（跳过空白、半角标点、引号）挨着汉字或中文标点。 */
private fun cnAround(line: CharSequence, start: Int, end: Int) = cnBefore(line, start) || cnAfter(line, end)
