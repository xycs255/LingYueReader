package com.xycs.lingyuereader.data

/**
 * Markdown 分两种读法：
 *
 * - **文稿**：标题里有“## 第X章”。每个这样的标题开一章，第一章之前的一级标题和 `>` 说明
 *   视为作者备注，不进正文；段落按小说排版（首行缩进）。
 * - **普通文档**：按出现两次以上的最高一级标题分章，保留列表、表格、引用等排版。
 */
object MarkdownParser {

    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_＊])(\s*\1){2,}\s*$""")
    private val tableSeparator = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun parse(text: String, fallbackTitle: String): List<Chapter> {
        val blocks = blocks(text)
        val isManuscript = blocks.any { it is Block.Heading && ChapterTitles.numberOf(it.text) != null && it.text.startsWith("第") }
        return if (isManuscript) manuscript(blocks) else document(blocks, fallbackTitle)
    }

    private fun manuscript(blocks: List<Block>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var body = mutableListOf<Block>()
        fun flush() {
            val t = title ?: return
            while (body.lastOrNull() == Block.SceneBreak) body.removeAt(body.lastIndex)
            chapters += ChapterTitles.chapter(t, body)
            title = null
        }
        for (b in blocks) {
            when {
                b is Block.Heading && b.text.startsWith("第") && ChapterTitles.numberOf(b.text) != null -> {
                    flush()
                    title = b.text
                    body = mutableListOf()
                }
                // 新的一级标题意味着下一份文稿开始，本章到此为止。
                b is Block.Heading && b.level == 1 -> flush()
                title == null -> Unit
                b is Block.Rule || b is Block.SceneBreak -> if (body.isNotEmpty() && body.last() != Block.SceneBreak) body += Block.SceneBreak
                b is Block.Para -> body += b.copy(prose = true)
                else -> body += b
            }
        }
        flush()
        return chapters
    }

    private fun document(blocks: List<Block>, fallbackTitle: String): List<Chapter> {
        val headings = blocks.filterIsInstance<Block.Heading>()
        val level = (1..6).firstOrNull { l -> headings.count { it.level == l } >= 2 }
        if (level == null) {
            val title = headings.firstOrNull { it.level == 1 }?.text ?: fallbackTitle
            val body = blocks.filterNot { it is Block.Heading && it.level == 1 && it.text == title }
            return if (body.isEmpty()) emptyList() else listOf(Chapter(title, body))
        }
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var body = mutableListOf<Block>()
        fun flush() {
            val t = title ?: fallbackTitle
            if (body.isNotEmpty()) chapters += ChapterTitles.chapter(t, body)
        }
        for (b in blocks) {
            if (b is Block.Heading && b.level <= level) {
                flush()
                title = b.text
                body = mutableListOf()
            } else {
                body += b
            }
        }
        flush()
        return chapters
    }

    fun blocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val table = mutableListOf<List<String>>()
        val quote = mutableListOf<String>()

        fun flushTable() {
            if (table.isNotEmpty()) blocks += Block.Table(table.toList())
            table.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) blocks += Block.Quote(quote.joinToString("\n"))
            quote.clear()
        }

        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trimEnd().unescapeMarkdown()
            val trimmed = line.trim().trim('　').trim()
            if (trimmed.startsWith("|")) {
                flushQuote()
                if (!tableSeparator.matches(trimmed)) {
                    table += trimmed.trim('|').split('|').map { it.trim() }
                }
                continue
            }
            flushTable()
            if (trimmed.startsWith(">")) {
                quote += trimmed.removePrefix(">").trim()
                continue
            }
            flushQuote()
            if (trimmed.isEmpty()) continue
            val h = heading.matchEntire(trimmed)
            when {
                h != null -> blocks += Block.Heading(h.groupValues[1].length, h.groupValues[2].trim().normalizeSpaces())
                rule.matches(trimmed) -> blocks += Block.Rule
                bullet.matches(line) -> bullet.matchEntire(line)!!.let {
                    blocks += Block.ListItem("•", it.groupValues[3], it.groupValues[1].length / 2)
                }
                ordered.matches(line) -> ordered.matchEntire(line)!!.let {
                    blocks += Block.ListItem("${it.groupValues[2]}.", it.groupValues[3], it.groupValues[1].length / 2)
                }
                else -> blocks += Block.Para(trimmed, prose = false)
            }
        }
        flushTable()
        flushQuote()
        return blocks
    }
}

/** 去掉 Markdown 转义，如 `\#`、`\-`、`\*`。 */
internal fun String.unescapeMarkdown(): String =
    replace(Regex("""\\([\\`*_{}\[\]()#+\-.!>|~])"""), "$1")

internal fun String.normalizeSpaces(): String = replace(Regex("""[\s　]+"""), " ").trim()
