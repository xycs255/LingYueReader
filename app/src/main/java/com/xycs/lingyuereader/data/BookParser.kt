package com.xycs.lingyuereader.data

object BookParser {

    class SourceBytes(val name: String, val bytes: ByteArray)

    fun formatOf(name: String): BookFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> BookFormat.EPUB
        "md", "markdown" -> BookFormat.MARKDOWN
        else -> BookFormat.TXT
    }

    fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("txt", "text", "epub", "md", "markdown")

    fun titleOf(name: String): String = name.substringBeforeLast('.').trim().ifEmpty { name }

    private fun parseOne(source: SourceBytes, rules: TextRules): BookContent {
        val fallback = titleOf(source.name)
        return when (formatOf(source.name)) {
            BookFormat.EPUB -> EpubParser.parse(source.bytes, fallback)
            BookFormat.MARKDOWN -> BookContent(null, null, MarkdownParser.parse(rules.clean(TextDecoder.decode(source.bytes)), fallback))
            else -> BookContent(null, null, TxtParser.parse(rules.clean(TextDecoder.decode(source.bytes)), rules.titleMatcher()))
        }
    }

    /**
     * 多份文件合成一本书（分卷文稿、上下册）。文件先按名字自然排序（第2卷在第10卷前）。
     * 若每一章都有“第X章”序号，就按序号排序；同一章出现在多份文件里时，以排在后面的文件为准，
     * 这样把改过的新稿和旧稿一起导入，读到的是新稿。
     */
    fun parse(sources: List<SourceBytes>, rules: TextRules = TextRules()): BookContent {
        // 插图和封面路径前加上来源序号（“序号|书内路径”），导入时据此从对应文件里解出图片。
        val parsed = sources.withIndex()
            .sortedWith(compareBy(NaturalOrder) { it.value.name })
            .map { (i, source) -> tagImages(parseOne(source, rules), i) }
        if (parsed.size == 1) return parsed.single()
        val all = parsed.flatMap { it.chapters }
        val chapters = if (all.isNotEmpty() && all.all { it.number != null }) {
            val byKey = linkedMapOf<Pair<Int, Int>, Chapter>()
            all.forEach { byKey[it.number!! to it.part] = it }
            byKey.values.sortedWith(compareBy({ it.number }, { it.part }))
        } else {
            all
        }
        return BookContent(
            title = parsed.firstNotNullOfOrNull { it.title },
            author = parsed.firstNotNullOfOrNull { it.author },
            chapters = chapters,
            description = parsed.firstNotNullOfOrNull { it.description },
            cover = parsed.firstNotNullOfOrNull { it.cover },
            notes = parsed.fold(emptyMap()) { acc, c -> acc + c.notes },
        )
    }

    private fun tagImages(content: BookContent, index: Int): BookContent {
        val chapters = content.chapters.map { c ->
            if (c.blocks.none { it is Block.Image }) c
            else c.withBlocks(c.blocks.map { if (it is Block.Image) it.copy(src = "$index|${it.src}") else it })
        }
        return content.copy(chapters = chapters, cover = content.cover?.let { "$index|$it" })
    }

    fun search(content: BookContent, query: String, limit: Int = 300): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        content.chapters.forEachIndexed { ci, chapter ->
            chapter.blocks.forEachIndexed { bi, block ->
                val text = block.plainText
                var from = 0
                while (true) {
                    val at = text.indexOf(q, from, ignoreCase = true)
                    if (at < 0) break
                    hits += SearchHit(
                        chapterIndex = ci,
                        blockIndex = bi,
                        chapterTitle = chapter.title,
                        before = text.substring(maxOf(0, at - 18), at),
                        match = text.substring(at, at + q.length),
                        after = text.substring(at + q.length, minOf(text.length, at + q.length + 30)),
                    )
                    if (hits.size >= limit) return hits
                    from = at + q.length
                }
            }
        }
        return hits
    }

    /** 文件名里的数字按大小比较：“第6-10章” 排在 “第11-15章” 前面。 */
    object NaturalOrder : Comparator<String> {
        private val chunk = Regex("""\d+|\D+""")
        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a).map { it.value }.toList()
            val y = chunk.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.toBigInteger().compareTo(q.toBigInteger()).takeIf { it != 0 } ?: p.length.compareTo(q.length)
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
