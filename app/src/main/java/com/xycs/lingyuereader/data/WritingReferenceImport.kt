package com.xycs.lingyuereader.data

import java.io.File

/**
 * 独立范本的正文解析。范本不经过设定包分类，也不转成当前作品的章节或已发生事实。
 *
 * 阅读器为排版自动生成的“第几节”不能充当范本的真实章界；这里保留长章，找不到章界时明确标记原文。
 */
object WritingReferenceImport {
    const val MAX_TEXT_BYTES = 128L * 1024 * 1024
    const val MAX_EPUB_BYTES = 256L * 1024 * 1024
    const val UNSPLIT_TITLE = "未分章原文（未识别章节边界）"
    private const val NUMBER = "0-9０-９零〇一二两三四五六七八九十百千万"
    private val chineseChapter = Regex("""^第\s*([$NUMBER]+)\s*([章回])(?:\s*[:：、.．·—\-]\s*|\s*)(.*)$""")
    private val englishChapter = Regex("""^Chapter\s+([0-9０-９]+)(?:\s*[:：.．—\-]\s*|\s*)(.*)$""", RegexOption.IGNORE_CASE)
    private val namedChapter = Regex("""^(?:序章|序言|前言|楔子|引子|尾声|终章|后记|番外)(?:\s*[:：、.．·—\-]\s*|\s+)?[^。！？!?，,；;]{0,45}$""")
    private val volume = Regex("""^第\s*[$NUMBER]+\s*[卷部集篇](?:\s*[:：、.．·—\-]\s*|\s*).{0,45}$""")
    private val contents = Regex("""^(?:目\s*录|总目录|章节目录|Contents|Table\s+of\s+Contents)\s*[:：]?$""", RegexOption.IGNORE_CASE)
    private val bodyStart = Regex("""^(?:正文|正文开始|正文卷)\s*[:：]?$""")
    private val pageSuffix = Regex("""(?:\.{2,}|…{2,}|·{3,}|\s{2,})\s*\d+\s*$""")

    fun parse(fileName: String, text: String): BookContent {
        require(extension(fileName) in setOf("txt", "text")) { "范本目前支持 TXT 和 EPUB；EPUB 请从文件导入" }
        require(utf8WithinLimit(text, MAX_TEXT_BYTES)) { "范本 TXT 过大，无法安全导入（上限 128 MB）" }
        return parseText(fileName, text)
    }

    fun parse(fileName: String, bytes: ByteArray): BookContent {
        val ext = extension(fileName)
        val limit = byteLimit(fileName)
        require(bytes.size.toLong() <= limit) { "范本文件过大，无法安全导入（上限 ${limit / 1024 / 1024} MB）" }
        return if (ext == "epub") parseEpub(fileName, bytes) else parseText(fileName, TextDecoder.decode(bytes))
    }

    fun parse(fileName: String, file: File): BookContent {
        val limit = byteLimit(fileName)
        require(file.length() <= limit) { "范本文件过大，无法安全导入（上限 ${limit / 1024 / 1024} MB）" }
        return parse(fileName, file.readBytes())
    }

    /** 供选文件时做有界读取；不认识的格式不先读入内存。 */
    fun byteLimit(fileName: String): Long = when (extension(fileName)) {
        "txt", "text" -> MAX_TEXT_BYTES
        "epub" -> MAX_EPUB_BYTES
        else -> throw IllegalArgumentException("范本目前支持 TXT 和 EPUB")
    }

    private fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
    private fun sourceTitle(fileName: String): String = BookParser.titleOf(fileName.substringAfterLast('/').substringAfterLast('\\'))

    /** 只认独立标题行，不把正文里提及“第一章”或“Chapter 1”的句子切开。 */
    private fun isChapterTitle(line: String): Boolean {
        if (line.isBlank() || line.length > 80 || line.any { it in "。，,；;“”「」『』" }) return false
        return chineseChapter.matches(line) || englishChapter.matches(line) || namedChapter.matches(line)
    }

    private fun chapterNumber(title: String): Int? =
        (chineseChapter.matchEntire(title) ?: englishChapter.matchEntire(title))?.groupValues?.get(1)?.let { raw ->
            val normalized = raw.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
            val digits = "零一二三四五六七八九"
            when {
                normalized.all { it.isDigit() } -> normalized.toIntOrNull()
                normalized.all { it in digits || it == '〇' || it == '两' } -> normalized.map {
                    when (it) { '〇' -> '0'; '两' -> '2'; else -> '0' + digits.indexOf(it) }
                }.joinToString("").toIntOrNull()
                else -> ChapterTitles.parseChineseNumber(normalized)
            }
        }

    private fun chapter(title: String, blocks: List<Block>): Chapter = Chapter(title, blocks, chapterNumber(title), ChapterTitles.partOf(title))

    private fun parseText(fileName: String, text: String): BookContent {
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var blocks = mutableListOf<Block>()
        var sawBoundary = false
        var inContents = false
        var sawContents = false
        var bodyAfterContents = false

        fun flush() {
            if (blocks.isNotEmpty()) {
                chapters += chapter(title ?: if (sawBoundary) "前置文字" else UNSPLIT_TITLE, blocks)
            }
            blocks = mutableListOf()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim().trim('\uFEFF', '　').trim()
            if (line.isEmpty()) continue
            if (contents.matches(line)) {
                flush()
                title = null
                inContents = true
                sawContents = true
                continue
            }
            if (bodyStart.matches(line)) {
                // 目录末尾的“正文”是分区标签，不是上一条目录标题的内容。
                title = null
                inContents = false
                continue
            }
            if (volume.matches(line)) {
                flush()
                title = null
                continue
            }
            if (isChapterTitle(line)) {
                flush()
                title = line.replace(Regex("""[\s　]+"""), " ")
                sawBoundary = true
                continue
            }
            if (inContents) {
                // 带页码的目录项、下载说明不算正文。只有真实标题之后的叙述才结束目录。
                if (title == null || pageSuffix.containsMatchIn(line)) continue
                inContents = false
            }
            blocks += Block.Para(line)
            if (sawContents) bodyAfterContents = true
        }
        flush()
        require(chapters.isNotEmpty()) { "范本没有可分析的正文；目录列表不能作为章节正文" }
        require(!sawContents || bodyAfterContents) { "范本只有目录和前置说明，没有可分析的章节正文" }
        // 尚未遇见标题就保存的开头说明，不能伪装成第一章，也不能留“未分章”暗示整本没有章界。
        val fixed = if (sawBoundary && chapters.first().title == UNSPLIT_TITLE) {
            listOf(Chapter("前置文字", chapters.first().blocks)) + chapters.drop(1)
        } else chapters
        return BookContent(sourceTitle(fileName), BookParser.authorOf(fileName), fixed)
    }

    private fun parseEpub(fileName: String, bytes: ByteArray): BookContent {
        val book = EpubParser.parse(bytes, sourceTitle(fileName))
        // 阅读器会为缺失的 spine 文件生成提示页。它不能成为用于分析的“范本原文”。
        require(book.chapters.none { c -> c.blocks.any { b ->
            b is Block.Para && !b.prose && b.text.startsWith("这一章的文件（") && b.text.contains("在 EPUB 里缺失")
        } }) { "范本 EPUB 有缺失的正文文件，请修复后重新导入" }
        val chapters = book.chapters.flatMap { original ->
            if (contents.matches(original.title.trim())) emptyList()
            else splitEpubChapter(original)
        }.filter { c -> c.blocks.any { it.plainText.isNotBlank() } }
        require(chapters.isNotEmpty()) { "范本 EPUB 没有可分析的文字正文" }
        return book.copy(chapters = chapters, cover = null, notes = emptyMap())
    }

    /** 一个 XHTML 可以含多章；仅高层真实章标题分章，普通小节保留在本章。 */
    private fun splitEpubChapter(original: Chapter): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var title = original.title
        var blocks = mutableListOf<Block>()
        fun flush() {
            if (blocks.any { it.plainText.isNotBlank() }) chapters += chapter(title, blocks)
            blocks = mutableListOf()
        }
        for (block in original.blocks) {
            if (block is Block.Heading && block.level <= 2 && isChapterTitle(block.text.trim())) {
                flush()
                title = block.text.trim()
            } else blocks += block
        }
        flush()
        return chapters
    }

    /** 已解码文本的入口也限制体积；计数而不另分配一整份 UTF-8 字节数组。 */
    private fun utf8WithinLimit(text: String, limit: Long): Boolean {
        if (text.length.toLong() > limit) return false
        var size = 0L
        var index = 0
        while (index < text.length) {
            val c = text[index]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
                else -> 3
            }
            if (size > limit) return false
            index++
        }
        return true
    }
}
