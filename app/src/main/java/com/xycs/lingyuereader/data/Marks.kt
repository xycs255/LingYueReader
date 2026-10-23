package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/** 行内格式只认 `**加粗**`。返回去掉标记后的文字和加粗区间，批注的位置都按去掉标记后的文字计。 */
object InlineText {
    /**
     * 解析后的行内文字：[bold] 是加粗的范围，[footnotes] 是注释角标（显示文字里的范围 → 注释的键）。
     */
    data class Parsed(val text: String, val bold: List<IntRange>, val footnotes: List<Pair<IntRange, String>> = emptyList())

    /** 注释角标的标记：NOTE_START 角标文字 NOTE_KEY 注释键 NOTE_END。用私用区字符，正文里不会出现。 */
    const val NOTE_START = '\uE000'
    const val NOTE_KEY = '\uE001'
    const val NOTE_END = '\uE002'

    fun footnote(label: String, key: String): String = "$NOTE_START$label$NOTE_KEY$key$NOTE_END"

    /** 去掉注释标记里的键，只留角标文字（统计字数、搜索用）。 */
    fun stripNotes(raw: String): String {
        if (NOTE_START !in raw) return raw
        return Regex("$NOTE_START([^$NOTE_KEY]*)$NOTE_KEY[^$NOTE_END]*$NOTE_END").replace(raw) { it.groupValues[1] }
    }

    fun parse(raw: String): Parsed {
        if ("**" !in raw && NOTE_START !in raw) return Parsed(raw, emptyList())
        // 成对的 ** 才算加粗；落单的最后一个原样保留。
        val pairs = Regex("""\*\*""").findAll(raw).count() / 2 * 2
        val sb = StringBuilder()
        val bold = mutableListOf<IntRange>()
        val notes = mutableListOf<Pair<IntRange, String>>()
        var boldStart = -1
        var seen = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '*' && i + 1 < raw.length && raw[i + 1] == '*' && seen < pairs) {
                seen++
                if (boldStart < 0) {
                    boldStart = sb.length
                } else {
                    if (sb.length > boldStart) bold += boldStart until sb.length
                    boldStart = -1
                }
                i += 2
                continue
            }
            if (c == NOTE_START) {
                val keyAt = raw.indexOf(NOTE_KEY, i + 1)
                val end = if (keyAt > 0) raw.indexOf(NOTE_END, keyAt + 1) else -1
                if (end > 0) {
                    val label = raw.substring(i + 1, keyAt)
                    val start = sb.length
                    sb.append(label)
                    if (label.isNotEmpty()) notes += (start until sb.length) to raw.substring(keyAt + 1, end)
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return Parsed(sb.toString(), bold, notes)
    }
}

/** 可以划线、批注的块所显示的文字；表格、分隔线等返回空。 */
val Block.displayText: String?
    get() = when (this) {
        is Block.Para -> InlineText.parse(text).text
        is Block.Heading -> InlineText.parse(text).text
        is Block.Quote -> InlineText.parse(text).text
        is Block.ListItem -> InlineText.parse(text).text
        else -> null
    }

enum class MarkColor(val label: String, val argb: Long) {
    YELLOW("黄", 0x66F5C518),
    GREEN("绿", 0x5566BB6A),
    BLUE("蓝", 0x5542A5F5),
    RED("红", 0x55EF5350),
}

data class Bookmark(
    val id: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    /** 阅读列表项序号（块序号 + 1）。 */
    val itemIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
)

data class Note(
    val id: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val blockIndex: Int,
    val start: Int,
    val end: Int,
    /** 划线的原文，改稿后靠它重新找到位置。 */
    val quote: String,
    val text: String,
    val color: MarkColor,
    val createdAt: Long,
    /** 改稿后原文找不到了：仍然保留，只是不再显示在正文里。 */
    val lost: Boolean = false,
)

data class Marks(
    val bookmarks: List<Bookmark> = emptyList(),
    val notes: List<Note> = emptyList(),
) {
    fun notesIn(chapterIndex: Int, blockIndex: Int): List<Note> =
        notes.filter { !it.lost && it.chapterIndex == chapterIndex && it.blockIndex == blockIndex }

    /**
     * 文稿刷新后章节和段落可能挪动。先按章名找章，再在章内按原文找段落和位置；
     * 原文已被改掉的批注标为 [Note.lost]。书签只需按章名对齐章序。
     */
    fun resolve(content: BookContent): Marks {
        fun chapterOf(index: Int, title: String): Int {
            if (content.chapters.getOrNull(index)?.title == title) return index
            return content.chapters.indexOfFirst { it.title == title }
        }
        val notes = notes.map { note ->
            val ci = chapterOf(note.chapterIndex, note.chapterTitle)
            val chapter = content.chapters.getOrNull(ci) ?: return@map note.copy(lost = true)
            val sameBlock = chapter.blocks.getOrNull(note.blockIndex)?.displayText
            if (sameBlock != null && note.end <= sameBlock.length && sameBlock.substring(note.start, note.end) == note.quote) {
                return@map note.copy(chapterIndex = ci, lost = false)
            }
            // 先找原段落附近，再找全章。
            val order = chapter.blocks.indices.sortedBy { kotlin.math.abs(it - note.blockIndex) }
            for (bi in order) {
                val text = chapter.blocks[bi].displayText ?: continue
                val at = text.indexOf(note.quote)
                if (at >= 0) return@map note.copy(chapterIndex = ci, blockIndex = bi, start = at, end = at + note.quote.length, lost = false)
            }
            note.copy(chapterIndex = ci, lost = true)
        }
        val bookmarks = bookmarks.mapNotNull { b ->
            val ci = chapterOf(b.chapterIndex, b.chapterTitle)
            if (ci < 0) null else b.copy(chapterIndex = ci)
        }
        return Marks(bookmarks, notes)
    }

    /** 导出成 Markdown，按章节顺序排列，便于贴给协作者或放回云盘。 */
    fun toMarkdown(bookTitle: String, content: BookContent): String = buildString {
        append("# 《").append(bookTitle).append("》批注\n")
        val sorted = notes.sortedWith(compareBy({ it.lost }, { it.chapterIndex }, { it.blockIndex }, { it.start }))
        var lastChapter: String? = null
        for (n in sorted) {
            val heading = if (n.lost) "原文已改动的批注" else content.chapters.getOrNull(n.chapterIndex)?.title ?: n.chapterTitle
            if (heading != lastChapter) {
                append("\n## ").append(heading).append('\n')
                lastChapter = heading
            }
            append('\n')
            n.quote.lines().forEach { append("> ").append(it).append('\n') }
            if (n.text.isNotBlank()) append('\n').append(n.text.trim()).append('\n')
        }
        if (notes.isEmpty()) append("\n（暂无批注）\n")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("bookmarks", JSONArray().apply {
            bookmarks.forEach {
                put(JSONObject().put("id", it.id).put("chapter", it.chapterIndex).put("title", it.chapterTitle)
                    .put("item", it.itemIndex).put("char", it.charOffset).put("excerpt", it.excerpt).put("at", it.createdAt))
            }
        })
        .put("notes", JSONArray().apply {
            notes.forEach {
                put(JSONObject().put("id", it.id).put("chapter", it.chapterIndex).put("title", it.chapterTitle)
                    .put("block", it.blockIndex).put("start", it.start).put("end", it.end).put("quote", it.quote)
                    .put("text", it.text).put("color", it.color.name).put("at", it.createdAt))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): Marks {
            val b = o.optJSONArray("bookmarks") ?: JSONArray()
            val n = o.optJSONArray("notes") ?: JSONArray()
            return Marks(
                bookmarks = (0 until b.length()).map { i ->
                    val j = b.getJSONObject(i)
                    Bookmark(j.getString("id"), j.getInt("chapter"), j.getString("title"), j.getInt("item"),
                        j.optInt("char"), j.optString("excerpt"), j.optLong("at"))
                },
                notes = (0 until n.length()).map { i ->
                    val j = n.getJSONObject(i)
                    Note(j.getString("id"), j.getInt("chapter"), j.getString("title"), j.getInt("block"),
                        j.getInt("start"), j.getInt("end"), j.getString("quote"), j.optString("text"),
                        runCatching { MarkColor.valueOf(j.getString("color")) }.getOrDefault(MarkColor.YELLOW), j.optLong("at"))
                },
            )
        }
    }
}
