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
        private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

        private fun isSeparator(line: String) = line.trim().matches(Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$"))

        fun parse(text: String): List<MdTable> {
            val out = mutableListOf<MdTable>()
            val lines = text.lines()
            var i = 0
            while (i < lines.size) {
                if (lines[i].trim().startsWith("|") && i + 1 < lines.size && isSeparator(lines[i + 1])) {
                    val header = cells(lines[i])
                    val rows = mutableListOf<List<String>>()
                    val at = mutableListOf<Int>()
                    var j = i + 2
                    while (j < lines.size && lines[j].trim().startsWith("|")) {
                        rows += cells(lines[j])
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
            val row = cells(lines[line]).toMutableList()
            while (row.size <= col) row += ""
            row[col] = value.replace("|", "／")
            lines[line] = "| " + row.joinToString(" | ") + " |"
            return lines.joinToString("\n")
        }
    }
}

/** 文档里的一节：标题链（“人物 › 林月”）、正文、起始行号。 */
data class PackSection(val path: List<String>, val text: String, val line: Int) {
    val title: String get() = path.lastOrNull().orEmpty()
}

object PackSections {
    private val heading = Regex("^(#{1,4})\\s+(.+?)\\s*#*$")

    /**
     * 按二到四级标题分节（一级标题当文档名，不分）。太长的节再按空行分段切成几块，每块不超过 [chunk] 字，
     * 标题链跟着走，挑资料时一块一块地挑。
     */
    fun split(text: String, chunk: Int = 1_200): List<PackSection> {
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
                        if (piece.isNotEmpty() && piece.length + para.length > chunk) {
                            out += PackSection(p, piece.toString().trim(), start)
                            piece.setLength(0)
                        }
                        piece.append(para.take(chunk * 2)).append("\n\n")
                    }
                    if (piece.isNotBlank()) out += PackSection(p, piece.toString().trim(), start)
                }
            }
            body.setLength(0)
        }
        text.removePrefix("\uFEFF").lines().forEachIndexed { i, line ->
            val m = heading.matchEntire(line.trim())
            if (m != null && m.groupValues[1].length >= 2) {
                flush()
                val level = m.groupValues[1].length
                while (path.isNotEmpty() && path.last().first >= level) path.removeAt(path.lastIndex)
                path += level to m.groupValues[2].replace("*", "").trim()
                start = i
            } else if (m == null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }
}
