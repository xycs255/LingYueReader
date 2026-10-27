package com.xycs.lingyuereader.data

/**
 * 设定文档里的“结构”：章号、Markdown 表格、按标题分的小节。作者照自己的习惯写（散文、列表、表格都行），
 * 灵阅从里面认出“第几章”“谁”“什么时候埋下、什么时候回收”，不要求换成新格式。
 */
object ChapterRefs {
    private val ref = Regex("第([^章第\\n]{1,24}?)章")

    /**
     * 文字里提到的章号：“第12章”“第十二章”“第12—15章”“第六、十七章”“第十八章（上）”都认。
     * 返回每处提到的章号（范围展开成各章，最多 200 章）。“第一卷”这类不是章号的跳过。
     */
    fun find(text: String): List<Int> = ref.findAll(text).flatMap { m -> parse(m.groupValues[1]) ?: emptyList() }.toList()

    /** 章名里的章号：“第十二章 雪夜”→ 12；没有就是 null。 */
    fun number(title: String): Int? = ref.find(title)?.let { parse(it.groupValues[1])?.singleOrNull() }

    private fun parse(inner: String): List<Int>? {
        val out = mutableListOf<Int>()
        for (piece in inner.split('、', '，', ',', '及', '和').map { it.trim() }) {
            if (piece.isEmpty()) return null
            val ends = piece.split(Regex("\\s*[-—–~～至到]+\\s*第?\\s*")).filter { it.isNotEmpty() }
            val nums = ends.map { CheckRules.cn(it.trim()) ?: return null }
            when (nums.size) {
                1 -> out += nums[0]
                2 -> if (nums[1] >= nums[0] && nums[1] - nums[0] <= 200) out += (nums[0]..nums[1]) else return null
                else -> return null
            }
        }
        return out.ifEmpty { null }
    }
}

/** 一张 Markdown 表格：表头、各行；[lines] 是每行在原文里的行号（改某一格时用），[start] 是表头那一行的行号。 */
data class MdTable(val header: List<String>, val rows: List<List<String>>, val lines: List<Int>, val start: Int = -1) {
    /** 表头里第一个含这些字的列。 */
    fun column(vararg keys: String): Int = header.indexOfFirst { h -> keys.any { it in h } }

    fun cell(row: List<String>, col: Int): String = if (col < 0) "" else row.getOrNull(col).orEmpty()

    companion object {
        private data class Cells(val raw: List<String>, val separators: Int) {
            val values: List<String> get() = raw.map { unescapePipe(it) }
        }

        private val noCells = Cells(emptyList(), 0)

        /**
         * Split only real column pipes, not escaped pipes or pipes inside inline code spans.
         * 按下标扫一遍，不复制剩下的字符串：一行很长、反引号很多时也是线性的。
         */
        private fun cells(line: String): Cells {
            if ('|' !in line) return noCells
            val source = line.trim()
            val closing = closingTicks(source)
            val pieces = mutableListOf<String>()
            val cell = StringBuilder()
            var pipes = 0
            var codeTicks = 0
            var i = 0
            while (i < source.length) {
                when (source[i]) {
                    '\\' -> {
                        if (i + 1 < source.length && source[i + 1] in charArrayOf('\\', '|', '`')) {
                            cell.append(source, i, i + 2)
                            i += 2
                        } else cell.append(source[i++])
                    }
                    '`' -> {
                        var n = 0
                        while (i + n < source.length && source[i + n] == '`') n++
                        // An unmatched backtick is ordinary text, so a later pipe remains a delimiter.
                        if (codeTicks == n) codeTicks = 0
                        else if (codeTicks == 0 && (closing[n] ?: -1) > i) codeTicks = n
                        cell.append(source, i, i + n)
                        i += n
                    }
                    '|' -> {
                        if (codeTicks == 0) {
                            pieces += cell.toString().trim()
                            cell.setLength(0)
                            pipes++
                        } else cell.append('|')
                        i++
                    }
                    else -> cell.append(source[i++])
                }
            }
            pieces += cell.toString().trim()
            if (source.startsWith('|') && pieces.firstOrNull()?.isEmpty() == true) pieces.removeAt(0)
            if (source.endsWith('|') && pieces.lastOrNull()?.isEmpty() == true) pieces.removeAt(pieces.lastIndex)
            return Cells(pieces, pipes)
        }

        /** 每种长度的反引号串最后一次出现的位置：某处开头的串后面有没有一样长的串来闭合，查这张表就行。 */
        private fun closingTicks(text: String): Map<Int, Int> {
            val last = HashMap<Int, Int>()
            var i = 0
            while (i < text.length) {
                if (text[i] != '`') { i++; continue }
                var run = 0
                while (i + run < text.length && text[i + run] == '`') run++
                last[run] = i
                i += run
            }
            return last
        }

        private fun unescapePipe(raw: String): String {
            if ('\\' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                if (raw[i] != '\\') { out.append(raw[i++]); continue }
                var count = 0
                while (i + count < raw.length && raw[i + count] == '\\') count++
                val escapedPipe = i + count < raw.length && raw[i + count] == '|' && count % 2 == 1
                repeat(if (escapedPipe) count / 2 else count) { out.append('\\') }
                i += count
                if (escapedPipe) { out.append('|'); i++ }
            }
            return out.toString()
        }

        private val separator = Regex(":?-+:?")

        /**
         * 分隔行：只由 -、:、| 和空白组成，至少有一个竖线和一格“---”。手写的表格分隔行常比表头少几格或多几格，
         * 不要求列数和表头一样：列以表头为准，行里缺的格当空，多出的格不归任何一列。
         */
        private fun isSeparator(cells: Cells): Boolean =
            cells.separators > 0 && cells.raw.any { it.isNotEmpty() } && cells.raw.all { it.isEmpty() || separator.matches(it) }

        fun parse(text: String): List<MdTable> {
            val out = mutableListOf<MdTable>()
            val lines = text.lines()
            val fenced = MarkdownFences.mask(lines)
            var i = 0
            while (i < lines.size) {
                val headerCells = if (!fenced[i]) cells(lines[i]) else null
                if (headerCells != null && headerCells.separators > 0 && i + 1 < lines.size && !fenced[i + 1] &&
                    isSeparator(cells(lines[i + 1]))) {
                    val header = headerCells.values
                    val rows = mutableListOf<List<String>>()
                    val at = mutableListOf<Int>()
                    // 表头用竖线起头的，各行也要竖线起头：表格后面紧跟的“注：A|B 两条线……”这类说明不是表格行。
                    val leadingPipe = lines[i].trimStart().startsWith('|')
                    var j = i + 2
                    while (j < lines.size && !fenced[j]) {
                        if (leadingPipe && !lines[j].trimStart().startsWith('|')) break
                        val row = cells(lines[j])
                        if (row.separators == 0) break
                        rows += row.values
                        at += j
                        j++
                    }
                    out += MdTable(header, rows, at, i)
                    i = j
                } else {
                    i++
                }
            }
            return out
        }

        /** 改原文第 [line] 行（一行表格）的第 [col] 格；列不够就补。 */
        fun setCell(text: String, line: Int, col: Int, value: String): String {
            val lines = text.lines().toMutableList()
            require(line in lines.indices && col >= 0) { "表格单元格位置无效" }
            val row = cells(lines[line]).raw.toMutableList()
            while (row.size <= col) row += ""
            row[col] = value.replace("|", "\\|")
            lines[line] = "| " + row.joinToString(" | ") + " |"
            return lines.joinToString("\n")
        }
    }
}

/**
 * Markdown 代码围栏（``` 或 ~~~ 包起来的块）：里面是格式示例，不是章节、事实或可编辑的表格。
 *
 * 设定包不记文档原来是 Markdown 还是 TXT，作者也常把 ~~~、``` 当场景分隔线用，所以只认“看起来像代码块”的围栏，
 * 拿不准就当普通文字（和改版前一样照常解析）：
 * - 开头一行最多缩进 3 个空格，是三个以上同样的 ` 或 ~；反引号开头的那行后面不能再有反引号。
 * - 必须闭合：后面有一行是同一种符号、不短于开头、后面没有别的字。读到文末还没闭合的，开头那行当普通文字，
 *   后面照常解析（GitHub 显示时也不当代码块）。
 * - 开头带语言标记（“```markdown”“~~~text”）的，是围栏。
 * - 没有语言标记的，除了表格行（含 |），里面不能有写到章号（“第X章”）的行，而且至少有一行像标记或代码
 *   （# 标题、| 表格、<、{ 开头，或者整行都是 ASCII 字符）；否则两行都当分隔线，中间的内容照常解析。
 *   这样 TXT 和 Markdown 里用 ~~~ 隔开的章节、场景都不会被吞掉。
 */
internal object MarkdownFences {
    private class Fence(val marker: Char, val length: Int, val info: String)

    private fun opening(line: String): Fence? {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return null
        val first = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        var run = 0
        while (run < trimmed.length && trimmed[run] == first) run++
        if (run < 3) return null
        val info = trimmed.substring(run).trim()
        if (first == '`' && '`' in info) return null
        return Fence(first, run, info)
    }

    /** 能当结尾的一行（只有 [marker]，最多缩进 3 格）有几个符号；不能当结尾是 0。 */
    private fun closingRun(line: String, marker: Char): Int {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return 0
        var run = 0
        while (run < trimmed.length && trimmed[run] == marker) run++
        return if (run >= 3 && trimmed.substring(run).isBlank()) run else 0
    }

    /** 从每一行往后，能当结尾的行里最长的符号数：没闭合的开头不用一行行往后找。 */
    private fun closingSuffix(lines: List<String>, marker: Char): IntArray {
        val out = IntArray(lines.size + 1)
        for (k in lines.indices.reversed()) out[k] = maxOf(out[k + 1], closingRun(lines[k], marker))
        return out
    }

    private val codeStart = Regex("^\\s*[#|<{]")

    /** 没有语言标记的块像不像代码：除了表格行，不写章号；至少一行像标记或代码。 */
    private fun looksLikeCode(lines: List<String>, from: Int, until: Int): Boolean {
        var code = false
        for (k in from until until) {
            val line = lines[k]
            if ('|' !in line && ChapterRefs.find(line).isNotEmpty()) return false
            if (!code && line.isNotBlank() && (codeStart.containsMatchIn(line) || line.all { it.code < 0x80 })) code = true
        }
        return code
    }

    /** 每一行是不是围栏（围栏行和里面的内容都算）；下标和原文的行一一对应。 */
    fun mask(lines: List<String>): BooleanArray {
        val out = BooleanArray(lines.size)
        var ticks: IntArray? = null
        var tildes: IntArray? = null
        var i = 0
        while (i < lines.size) {
            val fence = opening(lines[i])
            if (fence == null) { i++; continue }
            val suffix = if (fence.marker == '`') ticks ?: closingSuffix(lines, '`').also { ticks = it }
            else tildes ?: closingSuffix(lines, '~').also { tildes = it }
            if (suffix[i + 1] < fence.length) { i++; continue }
            var end = i + 1
            while (end < lines.size && closingRun(lines[end], fence.marker) < fence.length) end++
            if (end < lines.size && (fence.info.isNotEmpty() || looksLikeCode(lines, i + 1, end))) {
                for (k in i..end) out[k] = true
                i = end + 1
            } else {
                // 没闭合，或者看着像分隔线：开头这行当普通文字，从下一行接着找。
                i++
            }
        }
        return out
    }
}

/** 文档里的一节：标题链（“人物 › 林月”）、正文、起始行号。 */
data class PackSection(val path: List<String>, val text: String, val line: Int) {
    val title: String get() = path.lastOrNull().orEmpty()
}

object PackSections {
    private val heading = Regex("^(#{1,4})\\s+(.+?)\\s*#*$")

    /**
     * 按一到四级标题分节；只有文档标题而没有正文时不单列一节。太长的节再按空行分段切成几块，每块不超过 [chunk] 字，
     * 标题链跟着走，挑资料时一块一块地挑。
     */
    fun split(text: String, chunk: Int = 1_200): List<PackSection> {
        require(chunk > 0)
        val out = mutableListOf<PackSection>()
        val path = ArrayList<Pair<Int, String>>()
        val body = StringBuilder()
        var start = 0
        fun flush() {
            val t = body.toString().trim()
            if (t.isNotEmpty()) {
                val p = path.map { it.second }
                if (t.length <= chunk) {
                    out += PackSection(p, t, start)
                } else {
                    // 按空行（段、列表块）切，凑满一块再开新块。
                    val piece = StringBuilder()
                    t.split(Regex("\\n\\s*\\n")).forEach { para ->
                        if (para.length > chunk) {
                            if (piece.isNotBlank()) {
                                out += PackSection(p, piece.toString().trim(), start)
                                piece.setLength(0)
                            }
                            para.chunked(chunk).forEach { part -> out += PackSection(p, part, start) }
                            return@forEach
                        }
                        if (piece.isNotEmpty() && piece.length + para.length > chunk) {
                            out += PackSection(p, piece.toString().trim(), start)
                            piece.setLength(0)
                        }
                        piece.append(para).append("\n\n")
                    }
                    if (piece.isNotBlank()) out += PackSection(p, piece.toString().trim(), start)
                }
            }
            body.setLength(0)
        }
        val lines = text.removePrefix("\uFEFF").lines()
        val fenced = MarkdownFences.mask(lines)
        lines.forEachIndexed { i, line ->
            if (fenced[i]) return@forEachIndexed
            val indent = line.length - line.trimStart(' ').length
            val m = heading.matchEntire(line.trim())?.takeIf { indent <= 3 }
            if (m != null) {
                flush()
                val level = m.groupValues[1].length
                while (path.isNotEmpty() && path.last().first >= level) path.removeAt(path.lastIndex)
                path += level to m.groupValues[2].replace("*", "").trim()
                start = i
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }
}

/**
 * 文档开头的封面一级标题：文档名、资料分类名，不是卷、人物这类管着内容的标题。细纲、人物、分节和按需查阅都用这里的判断。
 * 封面是围栏外第一行有字的，它是一级标题，并且
 * - 和文档名一样（不管书名号、空白、粗体和 .md/.txt 后缀），或者
 * - 像资料分类名（“长夜人物”“反派”“设定”“目录”），而且没写卷号、章号。
 */
internal object PackCover {
    private val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.+?)\\s*#*$")
    private val category = Regex("人物|角色|人设|主角|配角|反派|设定|世界观|目录|索引|大纲|总纲|细纲|档案|资料|伏笔|察觉|知情|一览|名单")
    private val numbered = Regex("第[^章卷第\\n]{1,8}?[章卷]")
    private val extension = Regex("\\.(?:md|markdown|txt)$", RegexOption.IGNORE_CASE)
    private val keyChars = Regex("[《》「」*\\s]")

    private fun key(title: String) = title.replace(extension, "").replace(keyChars, "")

    /** 这一行是几级标题、标题是什么；不是标题就是 null。 */
    private fun heading(line: String): Pair<Int, String>? =
        heading.matchEntire(line.trimEnd())?.let { it.groupValues[1].length to it.groupValues[2].replace("*", "").trim() }

    /** 像资料分类名、不是具体某个人或某一卷的标题。 */
    fun looksLikeCategory(title: String): Boolean = category.containsMatchIn(title) && !numbered.containsMatchIn(title)

    /** 封面标题在第几行；没有封面是 -1。[fenced] 是 [MarkdownFences.mask] 的结果。 */
    fun line(lines: List<String>, fenced: BooleanArray, documentTitle: String?): Int {
        val first = lines.indices.firstOrNull { !fenced[it] && lines[it].isNotBlank() } ?: return -1
        val (level, title) = heading(lines[first]) ?: return -1
        if (level != 1 || title.isEmpty()) return -1
        val sameAsDocument = documentTitle != null && key(title) == key(documentTitle)
        return if (sameAsDocument || looksLikeCategory(title)) first else -1
    }

    /**
     * 第 [index] 行的一级标题能不能当人名：封面、像资料分类名的、下面紧跟二级标题的（“# 反派”“# 第一部”下面再分人）都不是人物。
     * 不是一级标题的行不归这里管，返回 true。[cover] 是 [line] 的结果。
     */
    fun mayBePerson(lines: List<String>, fenced: BooleanArray, index: Int, cover: Int): Boolean {
        if (index == cover) return false
        val (level, title) = heading(lines.getOrNull(index) ?: return true) ?: return true
        if (level != 1) return true
        if (looksLikeCategory(title)) return false
        val next = (index + 1 until lines.size).firstOrNull { !fenced[it] && lines[it].isNotBlank() } ?: return true
        return heading(lines[next])?.first != 2
    }
}
