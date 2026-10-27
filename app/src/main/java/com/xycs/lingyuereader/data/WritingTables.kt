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

        /** Split only real column pipes, not escaped pipes or pipes inside inline code spans. */
        private fun cells(line: String): Cells {
            val source = line.trim()
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
                        val n = source.drop(i).takeWhile { it == '`' }.length
                        // An unmatched backtick is ordinary text, so a later pipe remains a delimiter.
                        if (codeTicks == n) codeTicks = 0
                        else if (codeTicks == 0 && hasClosingTicks(source, i + n, n)) codeTicks = n
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

        private fun hasClosingTicks(text: String, from: Int, size: Int): Boolean {
            var i = from
            while (i < text.length) {
                if (text[i] != '`') { i++; continue }
                val run = text.drop(i).takeWhile { it == '`' }.length
                if (run == size) return true
                i += run
            }
            return false
        }

        private fun unescapePipe(raw: String): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                if (raw[i] != '\\') { out.append(raw[i++]); continue }
                val count = raw.drop(i).takeWhile { it == '\\' }.length
                val escapedPipe = i + count < raw.length && raw[i + count] == '|' && count % 2 == 1
                repeat(if (escapedPipe) count / 2 else count) { out.append('\\') }
                i += count
                if (escapedPipe) { out.append('|'); i++ }
            }
            return out.toString()
        }

        private val separator = Regex(":?-+:?")
        private fun isSeparator(cells: Cells, columns: Int): Boolean =
            cells.raw.size == columns && cells.raw.all { separator.matches(it) }

        fun parse(text: String): List<MdTable> {
            val out = mutableListOf<MdTable>()
            val lines = text.lines()
            val fence = MarkdownFenceTracker()
            val fenced = BooleanArray(lines.size) { fence.isInsideOrFence(lines[it]) }
            var i = 0
            while (i < lines.size) {
                val headerCells = if (!fenced[i]) cells(lines[i]) else null
                if (headerCells != null && headerCells.separators > 0 && i + 1 < lines.size && !fenced[i + 1] &&
                    isSeparator(cells(lines[i + 1]), headerCells.raw.size)) {
                    val header = headerCells.values
                    val rows = mutableListOf<List<String>>()
                    val at = mutableListOf<Int>()
                    var j = i + 2
                    while (j < lines.size && !fenced[j] && cells(lines[j]).separators > 0) {
                        rows += cells(lines[j]).values
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

/** Markdown fenced blocks contain examples, not chapter facts or editable tables. */
internal class MarkdownFenceTracker {
    private var marker: Char? = null
    private var length = 0

    /** True for both a fence delimiter and its contents; indices still match the source lines. */
    fun isInsideOrFence(line: String): Boolean {
        val trimmed = line.trimStart(' ')
        val indent = line.length - trimmed.length
        val first = trimmed.firstOrNull()
        val run = if (first == '`' || first == '~') trimmed.takeWhile { it == first }.length else 0
        if (marker == null) {
            if (indent <= 3 && run >= 3) {
                marker = first
                length = run
                return true
            }
            return false
        }
        if (indent <= 3 && first == marker && run >= length && trimmed.drop(run).isBlank()) {
            marker = null
            length = 0
        }
        return true
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
        val fence = MarkdownFenceTracker()
        text.removePrefix("\uFEFF").lines().forEachIndexed { i, line ->
            if (fence.isInsideOrFence(line)) return@forEachIndexed
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
