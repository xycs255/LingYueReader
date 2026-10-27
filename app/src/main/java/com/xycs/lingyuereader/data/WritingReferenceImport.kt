package com.xycs.lingyuereader.data

import java.io.File
import java.io.InputStream

/**
 * 独立范本的正文解析。范本不经过设定包分类，也不转成当前作品的章节或已发生事实。
 *
 * 阅读器为排版自动生成的“第几节”不能充当范本的真实章界；这里保留长章，找不到章界时明确标记原文。
 */
object WritingReferenceImport {
    /**
     * TXT 解析时，逐行读出的段落和导入时拼出的各章正文各占一份 UTF-16 文字：32 MB 的 GB18030 中文约
     * 1600 万字、两份约 64 MB；纯 ASCII 的最坏情况约 128 MB，仍在常见 256 MB 堆以内。文件按行流式解码，
     * 不再同时持有原始字节和整本解码结果。一千万字的长篇 GBK 也只有约 20 MB。
     */
    const val MAX_TEXT_BYTES = 32L * 1024 * 1024
    /**
     * EPUB 从临时文件按条目随用随读，只解压正文用到的文本条目，图片不解压；体积大多是插图，
     * 常驻内存的只有正文文字。64 MB 能放下常见的带插图轻小说，也限制了临时文件和解压检查的耗时。
     */
    const val MAX_EPUB_BYTES = 64L * 1024 * 1024
    const val TOO_LARGE = "范本文件太大，手机内存不够解析，请先拆分后导入"
    const val UNSPLIT_TITLE = "未分章原文（未识别章节边界）"
    private const val NUMBER = "0-9０-９零〇一二两三四五六七八九十百千万"
    private val chineseChapter = Regex("""^第\s*([$NUMBER]+)\s*([章回])(?:\s*[:：、.．·—\-]\s*|\s*)(.*)$""")
    private val englishChapter = Regex("""^Chapter\s+([0-9０-９]+)(?:\s*[:：.．—\-]\s*|\s*)(.*)$""", RegexOption.IGNORE_CASE)
    private val namedChapter = Regex("""^(?:序章|序言|前言|楔子|引子|尾声|终章|后记|番外)(?:\s*[:：、.．·—\-]\s*|\s+)?[^。！？!?，,；;]{0,45}$""")
    private val volumeHead = Regex("""^第\s*[$NUMBER]+\s*(卷|部分?|集|篇)""")
    private val inlineChapter = Regex("""第\s*[$NUMBER]+\s*[章回]|Chapter\s+[0-9０-９]""", RegexOption.IGNORE_CASE)
    private val contents = Regex("""^(?:目\s*录|总目录|章节目录|Contents|Table\s+of\s+Contents)\s*[:：]?$""", RegexOption.IGNORE_CASE)
    private val bodyStart = Regex("""^(?:正文|正文开始|正文卷)\s*[:：]?$""")
    private val pageSuffix = Regex("""(?:\.{2,}|…{2,}|·{3,}|\s{2,})\s*\d+\s*$""")

    fun parse(fileName: String, text: String): BookContent {
        require(extension(fileName) in setOf("txt", "text")) { "范本目前支持 TXT 和 EPUB；EPUB 请从文件导入" }
        require(utf8WithinLimit(text, MAX_TEXT_BYTES)) { "范本 TXT 过大，无法安全导入（上限 ${MAX_TEXT_BYTES / 1024 / 1024} MB）" }
        return parseText(fileName, text.lineSequence())
    }

    fun parse(fileName: String, bytes: ByteArray): BookContent {
        val ext = extension(fileName)
        val limit = byteLimit(fileName)
        require(bytes.size.toLong() <= limit) { "范本文件过大，无法安全导入（上限 ${limit / 1024 / 1024} MB）" }
        return if (ext == "epub") parseEpub(EpubParser.parse(bytes, sourceTitle(fileName)))
        else parseText(fileName, TextDecoder.decode(bytes).lineSequence())
    }

    /**
     * 选文件导入走这里：超过上限的在读取之前就拒绝；TXT 按行流式解码，EPUB 只解压用到的文本条目。
     * 内存仍然不够时给出“文件太大”，不让 App 闪退。
     */
    fun parse(fileName: String, file: File): BookContent {
        val limit = byteLimit(fileName)
        require(file.length() <= limit) { "范本文件过大，无法安全导入（上限 ${limit / 1024 / 1024} MB）" }
        return try {
            if (extension(fileName) == "epub") parseEpub(EpubParser.parse(file, sourceTitle(fileName)))
            else TextDecoder.readLines(file) { lines -> parseText(fileName, lines) }
        } catch (_: OutOfMemoryError) {
            throw IllegalArgumentException(TOO_LARGE)
        }
    }

    /** 选文件时把文档复制成临时文件：边读边写，超过上限立即停下，不在内存里攒整份文件。 */
    fun copyBounded(input: InputStream, target: File, limit: Long) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "范本文件过大，请先拆分后导入（上限 ${limit / 1024 / 1024} MB）" }
                output.write(buffer, 0, count)
            }
        }
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

    /**
     * 卷、部、集、篇只是分组。返回 null 表示这行不是卷名；否则返回同一行里的章标题（没有则为空串）。
     * 卷名行要像标题：不长、没有句读；“第一部手机……”“第三篇论文……”这类正文句子照常当正文。
     */
    private fun volumeLine(line: String): String? {
        val head = volumeHead.find(line) ?: return null
        if (line.length > 80 || line.any { it in "。，,；;“”「」『』！？!?…" }) return null
        val rest = line.substring(head.range.last + 1)
        // 部、集、篇后面直接接字多半是正文（“第一部手机”）；卷名后面要有分隔或直接结束。
        val inline = inlineChapter.find(rest)
        if (head.groupValues[1] != "卷" && rest.isNotEmpty() && rest.first() !in " \t　:：、.．·—-" && inline?.range?.first != 0) return null
        val chapter = inline?.let { rest.substring(it.range.first).trim() }?.takeIf(::isChapterTitle)
        if (chapter == null && rest.trim().length > 45) return null
        return chapter.orEmpty()
    }

    private fun parseText(fileName: String, lines: Sequence<String>): BookContent {
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

        for (raw in lines) {
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
            val volumeChapter = volumeLine(line)
            if (volumeChapter != null) {
                flush()
                // “第一卷 风起 第一章 少年”：卷名只作分组，同一行的章标题保留。
                title = volumeChapter.takeIf { it.isNotEmpty() }?.replace(Regex("""[\s　]+"""), " ")
                if (title != null) sawBoundary = true
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

    private fun parseEpub(book: BookContent): BookContent {
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
