package com.xycs.lingyuereader.data

object BookParser {

    /** 一份来源文件。给了 [file] 时内容按需读取：漫画、PDF 不必整个读进内存。 */
    class SourceBytes private constructor(val name: String, val file: java.io.File?, private val data: ByteArray?) {
        constructor(name: String, bytes: ByteArray) : this(name, null, bytes)
        constructor(name: String, file: java.io.File) : this(name, file, null)

        val bytes: ByteArray by lazy { data ?: file!!.readBytes() }
    }

    fun formatOf(name: String): BookFormat {
        val lower = name.lowercase()
        if (lower.endsWith(".fb2.zip")) return BookFormat.FB2
        return when (lower.substringAfterLast('.', "")) {
            "epub" -> BookFormat.EPUB
            "md", "markdown" -> BookFormat.MARKDOWN
            "mobi", "prc", "azw" -> BookFormat.MOBI
            "azw3", "kf8" -> BookFormat.AZW3
            "fb2", "fbz" -> BookFormat.FB2
            "pdf" -> BookFormat.PDF
            "cbz" -> BookFormat.CBZ
            else -> BookFormat.TXT
        }
    }

    fun isSupported(name: String): Boolean =
        name.lowercase().endsWith(".fb2.zip") || name.substringAfterLast('.', "").lowercase() in setOf(
            "txt", "text", "epub", "md", "markdown", "mobi", "prc", "azw", "azw3", "kf8", "fb2", "fbz", "pdf", "cbz",
        )

    /** 量出 PDF 每页的高宽比；由 Android 端提供（见 PdfPages），测试里可以换掉。 */
    @Volatile
    var pdfAspects: (java.io.File) -> List<Float> = { error("PDF 需要在 Android 上打开") }

    /** FB2 可能是压缩过的（.fb2.zip、.fbz）：取出里面的 .fb2。 */
    private fun fb2Bytes(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return bytes
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".fb2", ignoreCase = true)) return zip.readBytes()
            }
        }
        error("压缩包里没有 .fb2 文件")
    }

    /**
     * 书里的资源（插图、封面），[key] 是解析时记下的“@res/…”后半段：MOBI 的“rec/记录号”、FB2 的“bin/图片id”。
     */
    fun resource(source: SourceBytes, key: String): ByteArray? = when {
        key.startsWith("rec/") -> key.removePrefix("rec/").toIntOrNull()?.let { MobiParser.record(source.bytes, it) }
        key.startsWith("bin/") -> Fb2Parser.binary(fb2Bytes(source.bytes), key.removePrefix("bin/"))
        else -> null
    }

    /**
     * 文件名里的书名。网上下来的文件常写成“《覆汉》（校对版全本）作者：榴弹怕水 (1).txt”：
     * 有书名号就取书名号里的；没有就去掉“（精校版）”“[全本]”这类版本标注、“作者：…”和下载时加的“(1)”。
     */
    fun titleOf(name: String): String {
        val base = name.substringBeforeLast('.').trim().replace(duplicateMark, "").trim()
        bookMarks.find(base)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val cleaned = base
            .replace(authorPart, "")
            .replace(editionTag, "")
            .trim().trim('-', '_', '—', ' ', '·')
        return cleaned.ifEmpty { base.ifEmpty { name } }
    }

    /** 文件名里写了“作者：某某”就取出来。 */
    fun authorOf(name: String): String? =
        authorPart.find(name.substringBeforeLast('.').replace(duplicateMark, ""))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private val duplicateMark = Regex("""\s*[（(]\d{1,2}[)）]\s*$""")
    private val bookMarks = Regex("""《([^》]+)》""")
    private val authorPart = Regex("""\s*[（(\[【]?\s*作者\s*[:：]\s*([^()（）\[\]【】\s_]+)\s*[)）\]】]?""")
    private val editionTag = Regex("""\s*[（(\[【][^()（）\[\]【】]*(?:校对|精校|全本|完本|完结|全集|精排|修订|txt|TXT)[^()（）\[\]【】]*[)）\]】]""")

    private fun parseOne(source: SourceBytes, rules: TextRules): BookContent {
        val fallback = titleOf(source.name)
        return when (formatOf(source.name)) {
            BookFormat.EPUB -> EpubParser.parse(source.bytes, fallback)
            BookFormat.MOBI, BookFormat.AZW3 -> MobiParser.parse(source.bytes, fallback)
            BookFormat.FB2 -> Fb2Parser.parse(fb2Bytes(source.bytes), fallback)
            BookFormat.CBZ -> PageBooks.comic(source)
            BookFormat.PDF -> PageBooks.pdf(source, pdfAspects(requireNotNull(source.file) { "PDF 需要先存成文件" }))
            BookFormat.MARKDOWN -> BookContent(null, null, MarkdownParser.parse(rules.clean(TextDecoder.decode(source.bytes)), fallback))
            else -> BookContent(null, null, TxtParser.parse(rules.clean(TextDecoder.decode(source.bytes)), rules.titleMatcher()))
        }
    }

    /**
     * 多份文件合成一本书（分卷文稿、上下册）。文件先按名字自然排序（第2卷在第10卷前）。
     * 若每一章都有“第X章”序号，就按序号排序；同一章出现在多份文件里时，以排在后面的文件为准，
     * 这样把改过的新稿和旧稿一起导入，读到的是新稿。
     */
    /**
     * 各文件自己从头编号（上册、下册都从第一章起）：后面的文件又从不大于前一个文件开头的章号编起，
     * 而重号的章章名多半不同。这时不能按章号去重（会拿下册的第一章顶掉上册的），按文件顺序接起来。
     * 修订稿只重写其中几章（从中间某章起），或整本重来但章名大多不变，仍按章号以后面的为准。
     */
    private fun restartsNumbering(files: List<List<Chapter>>): Boolean {
        val restarted = files.zipWithNext().any { (a, b) ->
            val first = a.firstOrNull()?.number ?: return@any false
            (b.firstOrNull()?.number ?: Int.MAX_VALUE) <= first
        }
        if (!restarted) return false
        val repeated = files.flatten().groupBy { it.number to it.part }.values.filter { it.size > 1 }
        val renamed = repeated.count { group -> group.map { it.title.trim() }.distinct().size > 1 }
        return renamed * 2 > repeated.size
    }

    fun parse(sources: List<SourceBytes>, rules: TextRules = TextRules()): BookContent {
        // 插图和封面路径前加上来源序号（“序号|书内路径”），导入时据此从对应文件里解出图片。
        val parsed = sources.withIndex()
            .sortedWith(compareBy(NaturalOrder) { it.value.name })
            .map { (i, source) -> tagImages(parseOne(source, rules), i) }
        if (parsed.size == 1) return parsed.single()
        val all = parsed.flatMap { it.chapters }
        val chapters = if (all.isNotEmpty() && all.all { it.number != null } && !restartsNumbering(parsed.map { it.chapters })) {
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
