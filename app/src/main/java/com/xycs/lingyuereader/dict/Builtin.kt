package com.xycs.lingyuereader.dict

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** CC-CEDICT 的一条：简体、繁体、拼音（已转成声调符号）、英文释义。 */
data class WordEntry(val simplified: String, val traditional: String, val pinyin: String, val definitions: List<String>)

/** Unihan 里一个字的信息。 */
data class CharEntry(
    val char: String,
    /** 读音，第一个是常用读音；多音字有多个。 */
    val readings: List<String>,
    val radical: String,
    val strokesOutsideRadical: Int?,
    val totalStrokes: Int?,
    val traditional: String,
    val simplified: String,
    val definition: String,
)

/**
 * 按第一列（UTF-16 编码顺序）排好序、一行一条、以制表符分列的文本文件。
 * 文件映射到内存，只在堆上保留每行的起始位置，查找时直接在映射里二分。
 * 内置词库和导入词典的索引都用它。
 */
class SortedLines(file: File) {
    private val buffer: MappedByteBuffer
    private val lineStarts: IntArray

    init {
        RandomAccessFile(file, "r").use { raf ->
            buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
        val limit = buffer.limit()
        val starts = IntArray(countLines(limit))
        var n = 0
        if (limit > 0) starts[n++] = 0
        for (i in 0 until limit - 1) {
            if (buffer.get(i) == NEWLINE) starts[n++] = i + 1
        }
        lineStarts = starts
    }

    private fun countLines(limit: Int): Int {
        if (limit == 0) return 0
        var n = 1
        for (i in 0 until limit - 1) if (buffer.get(i) == NEWLINE) n++
        return n
    }

    val size: Int get() = lineStarts.size

    private fun slice(start: Int, stopAt: Byte): String {
        var end = start
        while (end < buffer.limit() && buffer.get(end) != stopAt && buffer.get(end) != NEWLINE) end++
        val bytes = ByteArray(end - start)
        buffer.duplicate().apply { position(start) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun key(line: Int): String = slice(lineStarts[line], TAB)

    fun line(line: Int): String = slice(lineStarts[line], NEWLINE)

    /** 第一个键不小于 [key] 的行号。 */
    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = lineStarts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (key(mid) < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun contains(key: String): Boolean {
        val i = lowerBound(key)
        return i < size && key(i) == key
    }

    /** 键等于 [key] 的所有行（按列拆开）。 */
    fun matches(key: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var i = lowerBound(key)
        while (i < size && key(i) == key) out += line(i++).split('\t')
        return out
    }

    /** 键以 [prefix] 开头的不同键，最多 [limit] 个。 */
    fun keysWithPrefix(prefix: String, limit: Int): List<String> {
        val out = linkedSetOf<String>()
        var i = lowerBound(prefix)
        while (i < size && out.size < limit) {
            val k = key(i++)
            if (!k.startsWith(prefix)) break
            out += k
        }
        return out.toList()
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val TAB = '\t'.code.toByte()
    }
}

/** CC-CEDICT 词库：简体\t繁体\t数字调拼音\t释义（/ 分隔）。 */
class Cedict(file: File) {
    private val lines = SortedLines(file)

    val size: Int get() = lines.size

    fun contains(word: String): Boolean = lines.contains(word)

    fun lookup(word: String): List<WordEntry> = lines.matches(word).filter { it.size >= 4 }.map { p ->
        WordEntry(p[0], p[1], Pinyin.marks(p[2]), p[3].split('/').filter { it.isNotBlank() })
    }

    /** 以 [prefix] 开头的词（用于“相关词语”），最多 [limit] 个，不含它本身。 */
    fun withPrefix(prefix: String, limit: Int): List<String> =
        lines.keysWithPrefix(prefix, limit + 1).filter { it != prefix }.take(limit)
}

/** Unihan 字表：四万多个字，按需整表读进内存。 */
class Unihan(input: InputStream) {
    private val table = HashMap<String, CharEntry>(50_000)

    init {
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val p = line.split('\t')
            if (p.size < 8) return@forEachLine
            table[p[0]] = CharEntry(
                char = p[0],
                readings = p[1].split(' ').filter { it.isNotEmpty() },
                radical = p[2],
                strokesOutsideRadical = p[3].toIntOrNull(),
                totalStrokes = p[4].toIntOrNull(),
                traditional = p[5],
                simplified = p[6],
                definition = p[7],
            )
        }
    }

    operator fun get(char: String): CharEntry? = table[char]
}

/** 拼音数字调转声调符号：ni3 hao3 → nǐ hǎo，lu:4 → lǜ。 */
object Pinyin {
    private val marks = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'O' to "ŌÓǑÒ",
    )

    fun marks(numbered: String): String = numbered.split(' ').joinToString(" ") { syllable(it) }

    private fun syllable(raw: String): String {
        val s = raw.replace("u:", "ü").replace("U:", "Ü").replace("v", "ü")
        val tone = s.lastOrNull()?.digitToIntOrNull() ?: return s
        val base = s.dropLast(1)
        if (tone !in 1..4) return base
        val lower = base.lowercase()
        // 标调规则：有 a 或 e 标在它上面；ou 标在 o 上；否则标在最后一个元音上。
        val index = when {
            'a' in lower -> lower.indexOf('a')
            'e' in lower -> lower.indexOf('e')
            "ou" in lower -> lower.indexOf('o')
            else -> lower.indexOfLast { it in "aeiouü" }
        }
        if (index < 0) return base
        val c = base[index]
        val marked = marks[c]?.get(tone - 1) ?: return base
        return base.substring(0, index) + marked + base.substring(index + 1)
    }
}

/** 长按某个字时，选出它所在的词。 */
object WordFinder {
    private const val MAX_WORD = 8

    fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /**
     * 在 [text] 的第 [pos] 个字处选词。
     *
     * 汉字：把按住的字所在的这一串汉字整个切成词，取包含它的那个词。切法是词数最少、其次单字最少
     * （最少分词法）。只看“覆盖这个字的最长词”会被旁边的词带偏：“研究生命起源”按“生”会选出“研究生”，
     * 整串切开是“研究 / 生命 / 起源”，选“生命”。词典里没有的字单独成词。
     * 英文、数字取连续的一串；标点返回空。
     */
    fun wordAt(text: String, pos: Int, contains: (String) -> Boolean): IntRange? {
        if (pos !in text.indices) return null
        val c = text[pos]
        if (isHan(c)) {
            // 按住的字所在的这串汉字（最长看前后各 40 字，够一句话）。
            var from = pos
            var to = pos + 1
            while (from > 0 && pos - from < 40 && isHan(text[from - 1])) from--
            while (to < text.length && to - pos < 40 && isHan(text[to])) to++
            return segment(text.substring(from, to), contains).map { (it.first + from)..(it.last + from) }.first { pos in it }
        }
        if (c.isLetterOrDigit()) {
            var s = pos
            var e = pos
            while (s > 0 && text[s - 1].isLetterOrDigit() && !isHan(text[s - 1])) s--
            while (e + 1 < text.length && text[e + 1].isLetterOrDigit() && !isHan(text[e + 1])) e++
            return s..e
        }
        return null
    }

    /** 把一串汉字切成词：词数最少，一样多时单字最少，再一样时前面的词长（动态规划）。 */
    fun segment(han: String, contains: (String) -> Boolean): List<IntRange> {
        val n = han.length
        if (n == 0) return emptyList()
        // cost[i]：前 i 个字的最好切法（词数, 单字数）；from[i]：最后一个词从哪开始。
        val words = IntArray(n + 1) { Int.MAX_VALUE }
        val singles = IntArray(n + 1)
        val from = IntArray(n + 1)
        words[0] = 0
        for (i in 0 until n) {
            if (words[i] == Int.MAX_VALUE) continue
            for (j in i + 1..minOf(n, i + MAX_WORD)) {
                val len = j - i
                if (len > 1 && !contains(han.substring(i, j))) continue
                val w = words[i] + 1
                val sg = singles[i] + if (len == 1) 1 else 0
                if (w < words[j] || (w == words[j] && sg < singles[j]) || (w == words[j] && sg == singles[j] && i < from[j])) {
                    words[j] = w
                    singles[j] = sg
                    from[j] = i
                }
            }
        }
        val out = ArrayDeque<IntRange>()
        var j = n
        while (j > 0) {
            val i = from[j]
            out.addFirst(i until j)
            j = i
        }
        return out.toList()
    }
}

/** 把词库从 assets 复制到应用目录（只在首次或数据更新时做一次），好按文件随机读取。 */
internal fun extract(input: InputStream, target: File) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
    if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
    }
}
