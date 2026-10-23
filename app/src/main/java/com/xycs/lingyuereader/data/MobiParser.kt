package com.xycs.lingyuereader.data

import java.nio.charset.Charset

/**
 * Kindle 的 MOBI / AZW3（KF8）电子书，只支持没有 DRM 的。
 *
 * 文件是一个 Palm 数据库：第 0 条记录是各种文件头，之后若干条是压缩过的正文（HTML），再后面是图片等资源。
 * - 老式 MOBI：正文是一整段 HTML，章与章之间用 `<mbp:pagebreak/>` 隔开，目录页里的 `<a filepos=…>` 指向各章起点（字节偏移）；
 * - KF8（AZW3）：正文是一个个 XHTML 文件依次拼起来，按文件分章。
 * 图片记录用“@res/rec/记录号”表示，导入时由 [record] 取出。
 */
object MobiParser {

    private class Palm(val bytes: ByteArray) {
        val count = u16(bytes, 76)
        private val offsets = IntArray(count + 1) { i -> if (i < count) u32(bytes, 78 + 8 * i) else bytes.size }
        fun record(i: Int): ByteArray? =
            if (i in 0 until count && offsets[i] <= offsets[i + 1] && offsets[i + 1] <= bytes.size) bytes.copyOfRange(offsets[i], offsets[i + 1]) else null
    }

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent {
        require(bytes.size > 78) { "不是有效的 MOBI 文件" }
        val palm = Palm(bytes)
        val type = String(bytes, 60, 8, Charsets.ISO_8859_1)
        val r0 = palm.record(0) ?: error("不是有效的 MOBI 文件")
        val compression = u16(r0, 0)
        val textRecords = u16(r0, 8)
        require(u16(r0, 12) == 0) { "这本书有 DRM 加密，灵阅打不开" }
        require(compression == 1 || compression == 2) {
            if (compression == 17480) "这本书用了 HUFF/CDIC 压缩，灵阅暂时打不开。可以用 Calibre 转成 EPUB 再导入" else "不认识的 MOBI 压缩方式"
        }
        val hasMobi = r0.size > 24 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (!hasMobi) {
            // 没有 MOBI 头的 PalmDOC（TEXtREAd）就是纯文本。
            require(type == "TEXtREAd" || type == "BOOKMOBI") { "不是有效的 MOBI 文件" }
            val text = TextDecoder.decode(readText(palm, textRecords, compression, 0))
            return BookContent(fallbackTitle, null, TxtParser.parse(text, TextRules().titleMatcher()))
        }
        val headerLength = u32(r0, 20)
        val encoding = u32(r0, 28)
        val version = u32(r0, 36)
        val firstImage = u32(r0, 0x6C)
        val extraFlags = if (headerLength >= 0xE4 && r0.size >= 0xF4) u16(r0, 0xF2) else 0
        val exth = if (u32(r0, 0x80) and 0x40 != 0) exth(r0, 16 + headerLength) else emptyMap()
        val charset = if (encoding == 1252) Charset.forName("windows-1252") else Charsets.UTF_8

        val fullName = runCatching {
            val off = u32(r0, 0x54)
            val len = u32(r0, 0x58)
            String(r0, off, len, charset).trim()
        }.getOrNull()
        val title = exth[503]?.firstOrNull()?.let { String(it, charset).trim() }?.ifEmpty { null }
            ?: fullName?.ifEmpty { null } ?: fallbackTitle
        val author = exth[100]?.map { String(it, charset).trim() }?.filter { it.isNotEmpty() }?.joinToString("、")?.ifEmpty { null }
        val description = exth[103]?.firstOrNull()?.let { HtmlText.plain(String(it, charset)) }?.ifEmpty { null }
        val cover = exth[201]?.firstOrNull()?.takeIf { it.size >= 4 }?.let { u32(it, 0) }
            ?.takeIf { it != -1 && firstImage > 0 }?.let { "@res/rec/${firstImage + it}" }

        val raw = readText(palm, textRecords, compression, extraFlags)
        val chapters = if (version >= 8) {
            val fdst = u32(r0, 0xC0)
            kf8Chapters(raw, flowEnd(palm.record(fdst), raw.size), firstImage)
        } else {
            mobi6Chapters(raw, charset, firstImage)
        }
        require(chapters.isNotEmpty()) { "没有读出正文" }
        return BookContent(title, author, chapters, description, cover)
    }

    /** 取出第 [index] 条记录（插图、封面）。 */
    fun record(bytes: ByteArray, index: Int): ByteArray? = runCatching { Palm(bytes).record(index) }.getOrNull()

    private fun readText(palm: Palm, count: Int, compression: Int, extraFlags: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..count) {
            val rec = palm.record(i) ?: break
            val data = rec.copyOf(rec.size - trailingSize(rec, extraFlags))
            out.write(if (compression == 2) palmDoc(data) else data)
        }
        return out.toByteArray()
    }

    /** 记录末尾的附加数据（多字节字符的续接、索引信息），解压前去掉。 */
    private fun trailingSize(rec: ByteArray, flags: Int): Int {
        var size = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) {
                var value = 0
                var shift = 0
                for (j in 1..4) {
                    val p = rec.size - size - j
                    if (p < 0) break
                    val b = rec[p].toInt() and 0xFF
                    value = value or ((b and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 != 0) break
                }
                size += value
            }
            f = f shr 1
        }
        if (flags and 1 != 0) {
            val p = rec.size - size - 1
            if (p >= 0) size += (rec[p].toInt() and 3) + 1
        }
        return size.coerceIn(0, rec.size)
    }

    /** PalmDOC 压缩（LZ77 的一种）。 */
    internal fun palmDoc(data: ByteArray): ByteArray {
        var buf = ByteArray(data.size * 2 + 16)
        var n = 0
        fun put(b: Int) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = b.toByte()
        }
        var i = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 1..8 -> repeat(c) { if (i < data.size) put(data[i++].toInt()) }
                c >= 0xC0 -> {
                    put(' '.code)
                    put(c xor 0x80)
                }
                else -> {
                    if (i >= data.size) break
                    val v = ((c shl 8) or (data[i++].toInt() and 0xFF)) and 0x3FFF
                    val dist = v shr 3
                    val len = (v and 7) + 3
                    if (dist == 0 || dist > n) continue
                    repeat(len) { put(buf[n - dist].toInt()) }
                }
            }
        }
        return buf.copyOf(n)
    }

    private fun exth(r0: ByteArray, start: Int): Map<Int, List<ByteArray>> {
        if (start + 12 > r0.size || String(r0, start, 4, Charsets.ISO_8859_1) != "EXTH") return emptyMap()
        val count = u32(r0, start + 8)
        val result = HashMap<Int, MutableList<ByteArray>>()
        var p = start + 12
        repeat(count) {
            if (p + 8 > r0.size) return result
            val type = u32(r0, p)
            val len = u32(r0, p + 4)
            if (len < 8 || p + len > r0.size) return result
            result.getOrPut(type) { mutableListOf() } += r0.copyOfRange(p + 8, p + len)
            p += len
        }
        return result
    }

    /** KF8 的正文分几段（HTML、CSS、SVG…），FDST 记着每段的范围；第一段是 HTML。 */
    private fun flowEnd(fdst: ByteArray?, total: Int): Int {
        if (fdst == null || fdst.size < 20 || String(fdst, 0, 4, Charsets.ISO_8859_1) != "FDST") return total
        return u32(fdst, 16).coerceIn(0, total).takeIf { it > 0 } ?: total
    }

    private val fileStart = Regex("""<\?xml\b|<html[\s>]""", RegexOption.IGNORE_CASE)
    private val kindleEmbed = Regex("""kindle:embed:([0-9A-Va-v]{4})""")

    private fun kf8Chapters(raw: ByteArray, end: Int, firstImage: Int): List<Chapter> {
        val text = String(raw, 0, end, Charsets.UTF_8)
        val starts = fileStart.findAll(text).map { it.range.first }.toMutableList()
        if (starts.firstOrNull() != 0) starts.add(0, 0)
        val files = starts.zipWithNext { a, b -> text.substring(a, b) } + text.substring(starts.last())
        val resolve = { src: String ->
            kindleEmbed.find(src)?.groupValues?.get(1)?.toInt(32)?.let { "@res/rec/${firstImage + it - 1}" } ?: src
        }
        val chapters = mutableListOf<Chapter>()
        for (html in files) {
            // 目录页（几乎全是指向书内位置的链接）不单独成章。
            if (isToc(html, "kindle:pos:fid")) continue
            var blocks = HtmlText.toBlocks(html, resolve)
            if (blocks.none { it is Block.Para || it is Block.Quote || it is Block.ListItem || it is Block.Image }) continue
            val heading = blocks.firstOrNull() as? Block.Heading
            val title = heading?.text ?: "第 ${chapters.size + 1} 节"
            if (heading != null) blocks = blocks.drop(1)
            chapters += ChapterTitles.chapter(title, blocks)
        }
        return chapters
    }

    private val links = Regex("""(?is)<a\b[^>]*>(.*?)</a>""")

    /** 至少有两个指向书内（[marker]）的链接，除了链接只剩一个“目录”标题那么多的字，就是目录页。 */
    private fun isToc(html: String, marker: String): Boolean {
        if (Regex(marker).findAll(html).count() < 2) return false
        val body = html.replace(Regex("""(?is)<head\b.*?</head>"""), "")
        val linkText = links.findAll(body).sumOf { HtmlText.plain(it.groupValues[1]).length }
        val other = HtmlText.plain(body.replace(links, "")).length
        return other < linkText + 30
    }

    private val filepos = Regex("""<a\b[^>]*?\bfilepos\s*=\s*["']?0*(\d+)["']?[^>]*>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val pagebreak = Regex("""<mbp:pagebreak\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val recindex = Regex("""\brecindex\s*=\s*["']?0*(\d+)["']?""", RegexOption.IGNORE_CASE)

    private fun mobi6Chapters(raw: ByteArray, charset: Charset, firstImage: Int): List<Chapter> {
        // 按字节处理：filepos 是解压后正文的字节偏移。ISO-8859-1 一个字节对应一个字符，方便找标签。
        val bytesText = String(raw, Charsets.ISO_8859_1)
        val breaks = listOf(0) + pagebreak.findAll(bytesText).map { it.range.first }.toList() + raw.size
        val pages = breaks.zipWithNext().filter { (a, b) -> b > a }
        fun pageOf(pos: Int) = pages.indexOfFirst { (a, b) -> pos in a until b }

        val links = filepos.findAll(bytesText).map { m ->
            m.range.first to (m.groupValues[1].toInt() to HtmlText.plain(String(m.groupValues[2].toByteArray(Charsets.ISO_8859_1), charset)))
        }.toList()
        // 目录页：guide 里标了 toc 的那一页；没标时取链接最多、几乎只有链接的一页。
        val guideToc = Regex("""<reference\b[^>]*type\s*=\s*["']?toc["']?[^>]*filepos\s*=\s*["']?0*(\d+)""", RegexOption.IGNORE_CASE)
            .find(bytesText)?.groupValues?.get(1)?.toInt()?.let(::pageOf)?.takeIf { it >= 0 }
        val tocPage = guideToc ?: links.groupBy { pageOf(it.first) }.maxByOrNull { it.value.size }
            ?.takeIf { (page, l) ->
                l.size >= 3 && page >= 0 && run {
                    val (a, b) = pages[page]
                    HtmlText.plain(String(raw.copyOfRange(a, b), charset).replace(Regex("""(?is)<a\b[^>]*>.*?</a>"""), "")).length < 40
                }
            }?.key
        val tocRange = tocPage?.let { pages[it] }
        val entries = links.filter { tocRange != null && it.first in tocRange.first until tocRange.second }
            .map { it.second }
            .filter { (pos, title) -> title.isNotEmpty() && pos < raw.size && (tocRange == null || pos !in tocRange.first until tocRange.second) }
            .distinctBy { it.first }
            .sortedBy { it.first }

        fun blocksOf(from: Int, to: Int): List<Block> {
            // 去掉落在这一段里的目录页。
            val parts = if (tocRange != null && tocRange.first < to && tocRange.second > from) {
                listOf(from to tocRange.first, tocRange.second to to).filter { it.second > it.first }
            } else {
                listOf(from to to)
            }
            return parts.flatMap { (x, y) ->
                val html = String(raw.copyOfRange(x, y), charset)
                    .replace(recindex) { m -> "src=\"@res/rec/${firstImage + m.groupValues[1].toInt() - 1}\"" }
                HtmlText.toBlocks(html, { it })
            }
        }

        fun hasBody(blocks: List<Block>) = blocks.any { it is Block.Para || it is Block.Quote || it is Block.ListItem || it is Block.Image }
        val chapters = mutableListOf<Chapter>()
        fun add(title: String, blocks: List<Block>) {
            val first = blocks.firstOrNull()?.plainText?.normalizeSpaces()
            chapters += ChapterTitles.chapter(title, if (first == title.normalizeSpaces()) blocks.drop(1) else blocks)
        }

        if (entries.size >= 2) {
            val front = blocksOf(0, entries.first().first)
            // 开头只有封面图、书名页时不单独成章。
            if (front.count { it is Block.Para || it is Block.Quote || it is Block.ListItem } >= 2) add("开头", front)
            entries.forEachIndexed { i, (pos, title) ->
                val blocks = blocksOf(pos, entries.getOrNull(i + 1)?.first ?: raw.size)
                if (hasBody(blocks)) add(title, blocks)
            }
        } else {
            pages.forEachIndexed { i, (a, b) ->
                if (i == tocPage) return@forEachIndexed
                val blocks = blocksOf(a, b)
                if (!hasBody(blocks)) return@forEachIndexed
                val first = blocks.first()
                val title = (first as? Block.Heading)?.text
                    ?: first.plainText.takeIf { first is Block.Para && it.length <= 30 && blocks.size > 1 }
                    ?: "第 ${chapters.size + 1} 节"
                add(title, blocks)
            }
        }
        return chapters
    }

    private fun u16(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Int =
        if (o + 4 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
