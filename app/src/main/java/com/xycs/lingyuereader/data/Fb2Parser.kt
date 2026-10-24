package com.xycs.lingyuereader.data

import java.nio.charset.Charset
import java.util.Base64

/**
 * FictionBook（FB2）：一个 XML 文件，正文按 `<section>` 分章，插图以 base64 放在文末的 `<binary>` 里，
 * 注释在 `<body name="notes">` 里。这里把它换成 HTML 再交给 [HtmlText]，和 EPUB 走同一套转换。
 * 插图用“@res/bin/图片id”表示，导入时由 [binary] 取出。
 */
object Fb2Parser {

    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent {
        val xml = decode(bytes)
        require(Regex("<FictionBook\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "不是有效的 FB2 文件" }
        val info = Regex("""<title-info\b[^>]*>(.*?)</title-info>""", opts).find(xml)?.groupValues?.get(1).orEmpty()
        val title = Regex("""<book-title\b[^>]*>(.*?)</book-title>""", opts).find(info)?.groupValues?.get(1)
            ?.let(HtmlText::plain)?.ifEmpty { null } ?: fallbackTitle
        val author = Regex("""<author\b[^>]*>(.*?)</author>""", opts).findAll(info).map { m ->
            val a = m.groupValues[1]
            val parts = listOf("last-name", "first-name", "middle-name").mapNotNull { tag ->
                Regex("""<$tag\b[^>]*>(.*?)</$tag>""", opts).find(a)?.groupValues?.get(1)?.let(HtmlText::plain)?.ifEmpty { null }
            }
            parts.joinToString(" ").ifEmpty {
                Regex("""<nickname\b[^>]*>(.*?)</nickname>""", opts).find(a)?.groupValues?.get(1)?.let(HtmlText::plain).orEmpty()
            }
        }.filter { it.isNotEmpty() }.joinToString("、").ifEmpty { null }
        val description = Regex("""<annotation\b[^>]*>(.*?)</annotation>""", opts).find(info)?.groupValues?.get(1)
            ?.let(HtmlText::plain)?.ifEmpty { null }
        val cover = Regex("""<coverpage\b[^>]*>.*?href\s*=\s*["']#([^"']+)["']""", opts).find(info)?.groupValues?.get(1)?.let { "@res/bin/$it" }

        val bodies = Regex("""<body\b([^>]*)>(.*?)</body>""", opts).findAll(xml).toList()
        val isNotes = { attrs: String -> Regex("""name\s*=\s*["'](notes|comments|footnotes)["']""", RegexOption.IGNORE_CASE).containsMatchIn(attrs) }
        val main = bodies.firstOrNull { !isNotes(it.groupValues[1]) }?.groupValues?.get(2) ?: error("没有读出正文")
        val notesXml = bodies.filter { isNotes(it.groupValues[1]) }.joinToString("\n") { it.groupValues[2] }

        // 只有一个顶层 section、里面又分了 section 时，按里面的分章（常见于“整本书一个大 section”的写法）。
        var sections = topSections(main)
        while (sections.size == 1 && topSections(inner(sections[0])).size > 1) sections = topSections(inner(sections[0]))
        if (sections.isEmpty()) sections = listOf("<section>$main</section>")

        val noteKeys = linkedSetOf<String>()
        val chapters = mutableListOf<Chapter>()
        for (section in sections) {
            var body = inner(section)
            var chapterTitle = Regex("""^\s*<title\b[^>]*>(.*?)</title>""", opts).find(body)?.let { m ->
                body = body.substring(m.range.last + 1)
                HtmlText.plain(m.groupValues[1].replace(Regex("""</p>""", RegexOption.IGNORE_CASE), " "))
            }?.ifEmpty { null }
            var blocks = HtmlText.toBlocks(toHtml(body), { it }, { href -> "notes#" + href.substringAfter('#') }, noteKeys)
            if (blocks.none { it is Block.Para || it is Block.Quote || it is Block.ListItem || it is Block.Image }) continue
            if (chapterTitle == null) {
                // 没有 <title> 时，开头一段加粗的短句或“第X章”就是章名。
                val firstP = Regex("""^\s*<p\b[^>]*>(.*?)</p>""", opts).find(body)?.groupValues?.get(1).orEmpty()
                val first = blocks.first().plainText
                val bold = Regex("""^\s*<strong\b[^>]*>.*</strong>\s*$""", opts).matches(firstP)
                if (blocks.size > 1 && first.length <= 40 && (bold || ChapterTitles.isTxtChapterTitle(first))) {
                    chapterTitle = first
                    blocks = blocks.drop(1)
                }
            }
            chapters += ChapterTitles.chapter(chapterTitle ?: "第 ${chapters.size + 1} 节", blocks)
        }
        require(chapters.isNotEmpty()) { "没有读出正文" }
        val noteIndex = HtmlText.NoteIndex(notesXml)
        val notes = noteKeys.mapNotNull { key -> noteIndex.text(key.substringAfter('#'))?.let { key to it } }.toMap()
        return BookContent(title, author, chapters, description, cover, notes)
    }

    /** 取出 id 为 [id] 的插图。 */
    /** 书里所有的图（id → 原始字节）：解出插图时一次取完，不必每张图都把整本书解码、扫一遍。 */
    fun binaries(bytes: ByteArray): Map<String, ByteArray> {
        val xml = decode(bytes)
        return Regex("""<binary\b[^>]*\bid\s*=\s*["']([^"']+)["'][^>]*>(.*?)</binary>""", opts).findAll(xml).mapNotNull { m ->
            runCatching { m.groupValues[1] to Base64.getMimeDecoder().decode(m.groupValues[2].trim()) }.getOrNull()
        }.toMap()
    }

    fun binary(bytes: ByteArray, id: String): ByteArray? {
        val xml = decode(bytes)
        val data = Regex("""<binary\b[^>]*\bid\s*=\s*["']${Regex.escape(id)}["'][^>]*>(.*?)</binary>""", opts).find(xml)?.groupValues?.get(1)
            ?: return null
        return runCatching { Base64.getMimeDecoder().decode(data.trim()) }.getOrNull()
    }

    private fun decode(bytes: ByteArray): String {
        // XML 声明里写了编码（俄文书常见 windows-1251）就按它解。
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("""encoding\s*=\s*["']([A-Za-z0-9_\-]+)["']""").find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return if (charset != null && charset != Charsets.UTF_8) String(bytes, charset) else TextDecoder.decode(bytes)
    }

    /** [xml] 里最外一层的 section（含标签本身）。 */
    private fun topSections(xml: String): List<String> {
        val tag = Regex("""<(/?)section\b[^>]*?(/?)>""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (m in tag.findAll(xml)) {
            if (m.groupValues[2] == "/") continue
            if (m.groupValues[1] != "/") {
                if (depth == 0) start = m.range.first
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0 && start >= 0) result += xml.substring(start, m.range.last + 1)
            }
        }
        return result
    }

    private fun inner(section: String): String =
        section.substringAfter('>').substringBeforeLast("</", section.substringAfter('>'))

    /** FB2 标签换成 HTML：小节标题当二级标题，诗和引文当引用，插图换成 img。 */
    private fun toHtml(xml: String): String = xml
        .replace(Regex("""<image\b[^>]*?href\s*=\s*["']#([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)) { "<img src=\"@res/bin/${it.groupValues[1]}\"/>" }
        .replace(Regex("""<a\b([^>]*?)\b[\w]+:href\s*=\s*(["'])(#[^"']+)\2([^>]*)>""", RegexOption.IGNORE_CASE)) { m ->
            val note = Regex("""type\s*=\s*["']note["']""").containsMatchIn(m.groupValues[1] + m.groupValues[4])
            "<a href=\"${m.groupValues[3]}\"${if (note) " epub:type=\"noteref\"" else ""}>"
        }
        .replace(Regex("""<(/?)title\b[^>]*>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}h3>" }
        .replace(Regex("""<(/?)subtitle\b[^>]*>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}h4>" }
        .replace(Regex("""<(/?)(epigraph|cite|poem)\b[^>]*>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}blockquote>" }
        .replace(Regex("""<(/?)(v|text-author)\b[^>]*>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}p>" }
        .replace(Regex("""<(/?)(stanza|section)\b[^>]*>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}div>" }
        .replace(Regex("""<empty-line\s*/>""", RegexOption.IGNORE_CASE), "")
}
