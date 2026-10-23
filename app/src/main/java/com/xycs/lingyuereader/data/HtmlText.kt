package com.xycs.lingyuereader.data

/**
 * 把 EPUB 里的 XHTML 粗略转成块。电子书的 HTML 常常不规范，这里不建 DOM，
 * 只按标签流切分：遇到块级标签就收尾当前段落。图片、样式、脚本一律略过。
 */
object HtmlText {

    private val tag = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9:]*)([^>]*?)(/?)>""")
    private val dropped = Regex("""<(head|script|style|title)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val comments = Regex("""<!--.*?-->|<!\[CDATA\[.*?]]>|<![^>]*>|<\?[^>]*\?>""", RegexOption.DOT_MATCHES_ALL)
    private val blockTags = setOf(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
        "br", "hr", "tr", "td", "th", "dt", "dd", "pre", "figure", "figcaption", "body", "aside",
    )

    /** [resolveImage] 把图片的相对路径换算成书内路径；为空时忽略图片。 */
    fun toBlocks(html: String, resolveImage: ((String) -> String)? = null): List<Block> {
        val source = html.replace(comments, "").replace(dropped, "")
        val blocks = mutableListOf<Block>()
        val text = StringBuilder()
        val open = ArrayDeque<String>()

        fun flush() {
            val t = decodeEntities(text.toString()).replace(Regex("""\s+"""), " ").trim().trim('　').trim()
            text.clear()
            if (t.isEmpty()) return
            val context = open.lastOrNull { it.startsWith("h") && it.length == 2 || it == "li" || it == "blockquote" }
            blocks += when {
                context == null -> Block.Para(t)
                context == "li" -> Block.ListItem("•", t)
                context == "blockquote" -> Block.Quote(t)
                else -> Block.Heading(context[1].digitToInt(), t)
            }
        }

        var last = 0
        for (m in tag.findAll(source)) {
            text.append(source, last, m.range.first)
            last = m.range.last + 1
            val name = m.groupValues[2].lowercase().substringAfter(':')
            if ((name == "img" || name == "image") && m.groupValues[1] != "/" && resolveImage != null) {
                val attrs = m.groupValues[3]
                val src = attr(attrs, "src") ?: attr(attrs, "xlink:href") ?: attr(attrs, "href")
                if (src != null && !src.startsWith("data:")) {
                    flush()
                    blocks += Block.Image(resolveImage(src.substringBefore('#')), attr(attrs, "alt").orEmpty())
                }
                continue
            }
            if (name !in blockTags) continue
            flush()
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[4] == "/"
            when {
                name == "hr" -> blocks += Block.Rule
                name == "br" -> Unit
                closing -> {
                    val i = open.lastIndexOf(name)
                    if (i >= 0) while (open.size > i) open.removeLast()
                }
                !selfClosing -> open.addLast(name)
            }
        }
        text.append(source, last, source.length)
        flush()
        return blocks
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(2)?.let(::decodeEntities)

    /** 去掉标签、合并空白，得到纯文字（生词本里的释义摘要用）。 */
    fun plain(html: String): String =
        decodeEntities(
            html.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
                .replace(Regex("""(?i)<br\s*/?>|</(p|div|li|h\d)>"""), " ")
                .replace(Regex("<[^>]*>"), ""),
        ).replace(Regex("""\s+"""), " ").trim()

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> named[e]
            } ?: m.value
        }
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "middot" to "·", "copy" to "©", "ensp" to " ", "emsp" to "　",
    )
}
