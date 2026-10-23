package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/** 行内格式只认 `**加粗**`。返回去掉标记后的文字和加粗区间，批注的位置都按去掉标记后的文字计。 */
object InlineText {
    data class Parsed(val text: String, val bold: List<IntRange>)

    fun parse(raw: String): Parsed {
        if ("**" !in raw) return Parsed(raw, emptyList())
        val parts = raw.split("**")
        val sb = StringBuilder()
        val bold = mutableListOf<IntRange>()
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1 && i < parts.lastIndex) {
                if (part.isNotEmpty()) bold += sb.length until sb.length + part.length
            } else if (i % 2 == 1) {
                sb.append("**") // 落单的标记原样保留
            }
            sb.append(part)
        }
        return Parsed(sb.toString(), bold)
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
