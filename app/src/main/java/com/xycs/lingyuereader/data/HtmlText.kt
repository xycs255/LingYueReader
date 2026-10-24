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

    /** EPUB3 里内嵌在正文中的脚注正文（`<aside epub:type="footnote">`），阅读时不该跟着正文显示，点角标时再弹出。 */
    private val inlineFootnotes = Regex(
        """<aside\b[^>]*epub:type\s*=\s*["'][^"']*\b(footnote|endnote|rearnote|note)\b[^"']*["'][^>]*>.*?</aside\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** 看起来像注释角标的链接文字：[1]、1、①、*、注1、〔2〕等。 */
    private val noteLabel = Regex("""^\s*[\[［(（〔【]?\s*(\d{1,3}|[*＊†‡]{1,3}|注\s*\d{1,3}|[①-⑳㉑-㉟])\s*[\]］)）〕】]?\s*$""")

    /**
     * [resolveImage] 把图片的相对路径换算成书内路径；为空时忽略图片。
     * [resolveLink] 把链接换算成“书内路径#锚点”；不为空时，指向锚点的注释角标会用 [InlineText.footnote] 标记，
     * 并记进 [noteKeys]。
     */
    fun toBlocks(
        html: String,
        resolveImage: ((String) -> String)? = null,
        resolveLink: ((String) -> String)? = null,
        noteKeys: MutableSet<String>? = null,
    ): List<Block> {
        val source = html.replace(comments, "").replace(dropped, "").let { if (resolveLink != null) it.replace(inlineFootnotes, "") else it }
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

        // 正在读的链接：在 text 里的起点和目标；读到 </a> 时判断是不是注释角标。
        var linkStart = -1
        var linkTarget: String? = null
        var linkIsNoteref = false

        var last = 0
        for (m in tag.findAll(source)) {
            text.append(source, last, m.range.first)
            last = m.range.last + 1
            val name = m.groupValues[2].lowercase().substringAfter(':')
            if (name == "a" && resolveLink != null) {
                if (m.groupValues[1] != "/") {
                    val attrs = m.groupValues[3]
                    val href = attr(attrs, "href")
                    linkTarget = href?.takeIf { '#' in it && !it.startsWith("http", ignoreCase = true) }
                    linkIsNoteref = attr(attrs, "epub:type")?.contains("noteref") == true || attr(attrs, "role") == "doc-noteref"
                    linkStart = if (linkTarget != null) text.length else -1
                } else if (linkStart in 0..text.length && linkTarget != null) {
                    val label = decodeEntities(text.substring(linkStart)).trim()
                    if (label.isNotEmpty() && label.length <= 8 && (linkIsNoteref || noteLabel.matches(label))) {
                        val key = resolveLink(linkTarget!!)
                        text.setLength(linkStart)
                        text.append(InlineText.footnote(label, key))
                        noteKeys?.add(key)
                    }
                    linkStart = -1
                    linkTarget = null
                }
                continue
            }
            if (name in blockTags) linkStart = -1
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

    /**
     * 取 id 为 [id] 的注释正文。注释常见两种写法：整段/整个 aside 带 id；或者段首一个带 id 的小锚点
     * （`<p><a id="n1" href="#r1">[1]</a> 注释文字</p>`），这时取锚点所在的整段。去掉开头的编号和“返回”链接。
     */
    fun noteText(html: String, id: String): String? = NoteIndex(html).text(id)

    /**
     * 一个注释文件的索引：所有带 id（或 name）的标签、所有段落开头的位置，各扫一遍。
     * 一本书几千条尾注都在同一个文件里时，逐条重扫整个文件要几十秒以上。
     */
    class NoteIndex(private val html: String) {
        private class Open(val tag: String, val start: Int, val end: Int, val selfClosing: Boolean)

        private val opens: Map<String, Open> by lazy {
            val out = HashMap<String, Open>()
            for (m in Regex("""<([a-zA-Z][\w:]*)\b([^>]*?)(/?)>""").findAll(html)) {
                for (a in idAttr.findAll(m.groupValues[2])) {
                    out.putIfAbsent(a.groupValues[1], Open(m.groupValues[1].lowercase(), m.range.first, m.range.last, m.groupValues[3] == "/"))
                }
            }
            out
        }

        /** 各个段落开始标签的结束位置（之后就是段落正文），从前往后。 */
        private val blockStarts: IntArray by lazy {
            Regex("""<(p|li|div|aside|dd|dt|section|td|blockquote)\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.range.last + 1 }.toList().toIntArray()
        }

        fun text(id: String): String? {
            val open = opens[id] ?: return null
            val fragment = if (open.tag in setOf("a", "span", "sup", "sub", "b", "i", "em") || open.selfClosing) {
                // 小锚点：取到所在段落结束。
                val i = blockStarts.binarySearch(open.start).let { if (it >= 0) it else -it - 2 }
                val blockStart = if (i >= 0) blockStarts[i] else open.start
                val end = Regex("""</(p|li|div|aside|dd|dt|section|td|blockquote)\s*>""", RegexOption.IGNORE_CASE)
                    .find(html, open.end + 1)?.range?.first ?: html.length
                html.substring(blockStart, end)
            } else {
                html.substring(open.end + 1, closingOf(html, open.tag, open.end + 1))
            }
            return cleanNote(fragment)
        }

        private companion object {
            val idAttr = Regex("""\b(?:id|name)\s*=\s*["']([^"']+)["']""")
        }
    }

    private fun cleanNote(fragment: String): String? {
        val text = plain(fragment.replace(Regex("""(?is)<a\b[^>]*>\s*(↩|↑|︎|返回|back)[^<]*</a>"""), ""))
            .replace(noteLeader, "")
            .trim()
        return text.takeIf { it.isNotEmpty() }?.take(2_000)
    }

    /** 注释开头的编号，如“[1]”“1.”“注1：”“①”。 */
    private val noteLeader = Regex("""^\s*[\[［(（〔【]?\s*(\d{1,3}|[*＊†‡]{1,3}|注\s*\d{1,3}|[①-⑳㉑-㉟])\s*[\]］)）〕】]?\s*[.。:：、]?\s*""")

    /** 从 [from] 起，找与之配对的 </[tag]>（考虑同名标签嵌套）。 */
    private fun closingOf(html: String, tag: String, from: Int): Int {
        val re = Regex("""<(/?)${Regex.escape(tag)}\b[^>]*?(/?)>""", RegexOption.IGNORE_CASE)
        var depth = 1
        for (m in re.findAll(html, from)) {
            if (m.groupValues[2] == "/") continue
            depth += if (m.groupValues[1] == "/") -1 else 1
            if (depth == 0) return m.range.first
        }
        return html.length
    }

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
                // 超出 Unicode 范围的编号（坏掉的书里会有）原样留着，不能让整本书导入失败。
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
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
