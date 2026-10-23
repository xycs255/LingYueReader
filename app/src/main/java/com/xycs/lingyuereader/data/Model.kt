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

    /** 插图。[src] 解析时是书内路径，导入后改为书籍目录里解出的图片文件名。 */
    data class Image(val src: String, val alt: String = "") : Block

    val plainText: String
        get() = when (this) {
            is Para -> InlineText.stripNotes(text)
            is Heading -> InlineText.stripNotes(text)
            is Quote -> InlineText.stripNotes(text)
            is ListItem -> InlineText.stripNotes(text)
            is Table -> rows.joinToString("\n") { it.joinToString("  ") }
            is Image -> ""
            Rule, SceneBreak -> ""
        }
}

/**
 * 一章。正文块可以直接给出，也可以按需从缓存读取（[loader]）：大部头只在读到某章时才加载那一章，
 * 读过的章节用软引用保留，内存紧张时可被回收，再用到时重新读取。
 */
class Chapter private constructor(
    val title: String,
    /** 标题里的“第X章”序号，合并多份分卷文稿时用来排序去重；认不出时为空。 */
    val number: Int?,
    /** 同号章节的先后：无=0，上=1，中=2，下=3。 */
    val part: Int,
    private val strong: List<Block>?,
    private val loader: (() -> List<Block>)?,
    knownCharCount: Int?,
) {
    constructor(title: String, blocks: List<Block>, number: Int? = null, part: Int = 0) :
        this(title, number, part, blocks, null, null)

    constructor(title: String, number: Int?, part: Int, charCount: Int, loader: () -> List<Block>) :
        this(title, number, part, null, loader, charCount)

    @Volatile
    private var soft: java.lang.ref.SoftReference<List<Block>>? = null

    val blocks: List<Block>
        get() {
            strong?.let { return it }
            soft?.get()?.let { return it }
            synchronized(this) {
                soft?.get()?.let { return it }
                val loaded = loader!!.invoke()
                soft = java.lang.ref.SoftReference(loaded)
                return loaded
            }
        }

    /** 是否已在内存中（测试用）。 */
    internal val isLoaded: Boolean get() = strong != null || soft?.get() != null

    val charCount: Int by lazy { knownCharCount ?: blocks.sumOf { it.plainText.length } }

    fun withBlocks(blocks: List<Block>): Chapter = Chapter(title, blocks, number, part)
}

data class BookContent(
    val title: String?,
    val author: String?,
    val chapters: List<Chapter>,
    val description: String? = null,
    /** 封面图：解析时是书内路径，导入后改为书籍目录里的文件名。 */
    val cover: String? = null,
    /** EPUB 注释：键（“文件路径#锚点”）→ 注释文字。正文里的角标用 [InlineText.footnote] 标记。 */
    val notes: Map<String, String> = emptyMap(),
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
    /** 阅读列表的项序号：0 是章名，正文第 n 块是 n + 1。 */
    val itemIndex: Int,
    /** 滚动模式下该项已滚出屏幕的像素。 */
    val itemOffset: Int,
    /** 翻页模式下当前页从该块的第几个字开始。 */
    val charOffset: Int = 0,
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
    val description: String? = null,
    /** 封面图在书籍目录里的文件名。 */
    val cover: String? = null,
    /** 累计阅读时长。 */
    val readMillis: Long = 0,
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
