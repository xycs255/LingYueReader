package com.xycs.lingyuereader.data

/** 各种格式解析后统一成“章 → 块”，阅读页只认这一种结构。 */
sealed interface Block {
    /** [prose] 为真时首行缩进两格，用于小说正文；笔记、文档类段落不缩进。 */
    data class Para(val text: String, val prose: Boolean = true) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class ListItem(val marker: String, val text: String, val indent: Int = 0) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
    data object SceneBreak : Block

    val plainText: String
        get() = when (this) {
            is Para -> text
            is Heading -> text
            is Quote -> text
            is ListItem -> text
            is Table -> rows.joinToString("\n") { it.joinToString("  ") }
            Rule, SceneBreak -> ""
        }
}

data class Chapter(
    val title: String,
    val blocks: List<Block>,
    /** 标题里的“第X章”序号，合并多份分卷文稿时用来排序去重；认不出时为空。 */
    val number: Int? = null,
    /** 同号章节的先后：无=0，上=1，中=2，下=3。 */
    val part: Int = 0,
) {
    val charCount: Int by lazy { blocks.sumOf { it.plainText.length } }
}

data class BookContent(
    val title: String?,
    val author: String?,
    val chapters: List<Chapter>,
) {
    val charCount: Int get() = chapters.sumOf { it.charCount }
}

enum class BookFormat(val label: String) { TXT("TXT"), EPUB("EPUB"), MARKDOWN("MD"), MIXED("合集") }

data class SourceRef(
    /** 原文件名，用来识别格式和排序。 */
    val name: String,
    /** 来源地址（如云盘文件），“刷新”时从这里重读。 */
    val uri: String?,
    /** 应用私有目录里的副本文件名。 */
    val file: String,
)

data class ReadingProgress(
    val chapterIndex: Int,
    /** 记下章名：文稿刷新后章序可能变了，按章名找回原处。 */
    val chapterTitle: String,
    val itemIndex: Int,
    val itemOffset: Int,
)

data class BookEntry(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val sources: List<SourceRef>,
    val addedAt: Long,
    val openedAt: Long,
    val chapterCount: Int,
    val charCount: Int,
    val progress: ReadingProgress?,
) {
    val readFraction: Float
        get() = progress?.let { (it.chapterIndex + 1f) / chapterCount.coerceAtLeast(1) } ?: 0f
}

data class SearchHit(
    val chapterIndex: Int,
    val blockIndex: Int,
    val chapterTitle: String,
    val before: String,
    val match: String,
    val after: String,
)
