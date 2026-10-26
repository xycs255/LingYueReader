package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 外部范本书的快照。不是本作品的设定、正文、前情或已发生事实。 */
data class WritingReference(
    val id: String,
    val title: String,
    val author: String,
    val sourceName: String,
    val sourceBookId: String? = null,
    val chapters: List<ReferenceChapter>,
    val words: Int,
    val fingerprint: String,
    val importedAt: Long,
)

/** [index] 从 1 起，包含前言；[number] 才是可识别的实际章号，不能用目录位置冒充章号。 */
data class ReferenceChapter(
    val id: String,
    val index: Int,
    val title: String,
    val number: Int?,
    val words: Int,
    val fingerprint: String,
)

enum class ReferencePart(val label: String) { OPENING("开头"), ENDING("结尾"), RANGE("自选片段"), CHAPTER("整章") }

/** 对规范化章正文的精确半开区间 [start, end)，偏移以 Kotlin String 的 UTF-16 字符计。 */
data class ReferenceExcerpt(
    val referenceId: String,
    val referenceTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val part: ReferencePart,
    val start: Int,
    val end: Int,
    val text: String,
    val chapterFingerprint: String,
    val fingerprint: String,
    val truncated: Boolean = false,
    /** The normalized chapter's UTF-16 length, in the same coordinate space as [start] and [end]. */
    val chapterLength: Int,
)

/** 只保存分析，不再复制范本原文；应用表示作者要复用技法建议，不是确认故事事实。 */
data class ReferenceAnalysis(
    val id: String = WorkStore.newId(),
    val referenceId: String,
    val referenceTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val targetChapterId: String,
    val part: ReferencePart,
    val start: Int,
    val end: Int,
    val sourceFingerprint: String,
    val targetFingerprint: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val applied: Boolean = false,
) {
    companion object {
        fun from(excerpt: ReferenceExcerpt, targetChapterId: String, targetFingerprint: String, body: String): ReferenceAnalysis =
            ReferenceAnalysis(referenceId = excerpt.referenceId, referenceTitle = excerpt.referenceTitle,
                chapterId = excerpt.chapterId, chapterTitle = excerpt.chapterTitle, targetChapterId = targetChapterId,
                part = excerpt.part, start = excerpt.start, end = excerpt.end,
                sourceFingerprint = excerpt.fingerprint, targetFingerprint = targetFingerprint, body = body)
    }
}

/** 存储只接收本机已导入书籍的章快照；没有自动注入 PackKnowledge、前情或读设定工具的入口。 */
object WritingReferences {
    const val DIRECTORY = "references"
    const val MAX_REFERENCES = 32
    const val MAX_CHAPTERS = 20_000
    const val MAX_CHAPTER_CHARS = 2_000_000
    const val MAX_BOOK_CHARS = 64_000_000L
    const val MAX_EXCERPT_CHARS = 8_000
    const val MAX_CHAPTER_ANALYSIS_CHARS = 12_000
    const val MAX_ANALYSES = 100
    const val MAX_ANALYSIS_CHARS = 80_000
    /** 外层 writing.json 的上限是 64 MiB，范本在整份备份中的预算另限 24 MiB。 */
    internal const val MAX_BACKUP_BYTES = 24L * 1024 * 1024
    private const val METADATA = "reference.json"
    private const val ANALYSES = "analyses.json"
    internal val ID = Regex("[A-Za-z0-9-]{1,64}")
    private val HASH = Regex("[a-f0-9]{64}")

    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun chapterText(chapter: Chapter): String = chapter.blocks.asSequence()
        .filterNot { it is Block.Image || it == Block.Rule || it == Block.SceneBreak }
        .dropWhile { it.plainText.isBlank() || it is Block.Heading && it.plainText.trim() == chapter.title.trim() }
        .map { it.plainText.replace("\r\n", "\n").replace('\r', '\n').trim().trim('　').trim() }
        .filter { it.isNotBlank() }.joinToString("\n\n")

    private fun folder(root: File, id: String): File {
        require(ID.matches(id)) { "范本 id 无效" }
        return File(root, id)
    }

    private fun chapterFile(root: File, referenceId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "范本章节 id 无效" }
        return File(folder(root, referenceId), "$chapterId.txt")
    }

    internal fun list(root: File, strict: Boolean = false): List<WritingReference> = root.listFiles().orEmpty()
        .filter { it.isDirectory && ID.matches(it.name) }.mapNotNull { directory ->
            val file = File(directory, METADATA)
            try {
                referenceFromJson(JSONObject(file.readText())).also { require(it.id == directory.name) { "范本索引 id 不匹配" } }
            } catch (failure: Exception) {
                DurableFiles.keepDamaged(file)
                if (strict) throw failure else null
            }
        }.sortedWith(compareByDescending<WritingReference> { it.importedAt }.thenBy { it.id })

    internal fun text(root: File, reference: WritingReference, chapterId: String): String? {
        val chapter = reference.chapters.firstOrNull { it.id == chapterId } ?: return null
        val file = chapterFile(root, reference.id, chapter.id)
        if (!file.isFile) return null
        require(file.length() <= MAX_CHAPTER_CHARS * 4L) { "范本章节文件过大" }
        return file.readText().also { require(fingerprint(it) == chapter.fingerprint) { "范本章节内容已损坏，请重新导入" } }
    }

    internal fun excerpt(reference: WritingReference, chapter: ReferenceChapter, text: String, part: ReferencePart,
                         maxChars: Int, start: Int, end: Int): ReferenceExcerpt {
        require(text.isNotBlank()) { "范本本章没有可分析的文字" }
        val range = when (part) {
            ReferencePart.CHAPTER -> {
                require(text.length <= MAX_CHAPTER_ANALYSIS_CHARS) {
                    "范本本章超过 ${MAX_CHAPTER_ANALYSIS_CHARS} 字，请选择开头、结尾或自选片段后分析，不能据局部判断全章结构"
                }
                0 to text.length
            }
            ReferencePart.OPENING -> {
                require(maxChars in 1..MAX_EXCERPT_CHARS) { "范本片段长度须为 1 至 $MAX_EXCERPT_CHARS 字" }
                0 to safeEnd(text, minOf(text.length, maxChars))
            }
            ReferencePart.ENDING -> {
                require(maxChars in 1..MAX_EXCERPT_CHARS) { "范本片段长度须为 1 至 $MAX_EXCERPT_CHARS 字" }
                safeStart(text, (text.length - maxChars).coerceAtLeast(0)) to text.length
            }
            ReferencePart.RANGE -> {
                require(start >= 0 && end > start && end <= text.length) { "范本片段范围无效，请按本章正文重新选择" }
                require(end - start <= MAX_EXCERPT_CHARS) { "自选范本片段最多 $MAX_EXCERPT_CHARS 字" }
                require(safeStart(text, start) == start && safeEnd(text, end) == end) { "范本片段边界不能截断一个完整字符" }
                start to end
            }
        }
        val selected = text.substring(range.first, range.second)
        require(selected.isNotBlank()) { "范本片段为空或长度不足以包含完整字符，请扩大选择范围" }
        val signature = excerptFingerprint(reference.id, chapter.id, chapter.fingerprint, part, range.first, range.second, selected)
        return ReferenceExcerpt(reference.id, reference.title, chapter.id, chapter.title, part, range.first, range.second,
            selected, chapter.fingerprint, signature, range.first != 0 || range.second != text.length, text.length)
    }

    private fun safeStart(text: String, offset: Int): Int =
        if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset + 1 else offset
    private fun safeEnd(text: String, offset: Int): Int =
        if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset

    private fun excerptFingerprint(referenceId: String, chapterId: String, chapterFingerprint: String,
        part: ReferencePart, start: Int, end: Int, selected: String): String =
        fingerprint("$referenceId|$chapterId|$chapterFingerprint|${part.name}|$start|$end|$selected")

    internal fun import(root: File, sourceName: String, content: BookContent, sourceBookId: String?): WritingReference {
        require(content.chapters.size <= MAX_CHAPTERS) { "范本章节过多，无法导入" }
        val id = WorkStore.newId()
        val chapterTexts = linkedMapOf<String, String>()
        var chars = 0L
        val chapters = content.chapters.mapNotNull { chapter ->
            val body = chapterText(chapter)
            if (body.isBlank()) return@mapNotNull null
            require(body.length <= MAX_CHAPTER_CHARS) { "「${chapter.title}」过长或未识别章界，请先检查分章后导入" }
            chars += body.length
            require(chars <= MAX_BOOK_CHARS) { "范本文字过多，无法安全导入" }
            val chapterId = "c${chapterTexts.size + 1}"
            chapterTexts[chapterId] = body
            val automaticSection = Regex("^第\\s*[0-9０-９]+\\s*节$").matches(chapter.title.trim())
            val chapterTitle = if (automaticSection) "未确认章界 · ${chapter.title}" else chapter.title
            val number = if (automaticSection) null else chapter.number
            ReferenceChapter(chapterId, chapterTexts.size, chapterTitle, number, WordCount.of(body), fingerprint(body))
        }
        require(chapters.isNotEmpty()) { "范本没有可分析的文字章节" }
        val title = content.title?.trim()?.takeIf { it.isNotEmpty() } ?: BookParser.titleOf(sourceName)
        val author = content.author.orEmpty()
        val signature = referenceFingerprint(title, author, chapters)
        val existing = list(root, strict = true)
        existing.firstOrNull { it.fingerprint == signature }?.let { return it }
        require(existing.size < MAX_REFERENCES) { "每部作品最多导入 $MAX_REFERENCES 部范本，请先移除不用的范本" }
        val reference = WritingReference(id, title, author, sourceName, sourceBookId, chapters, chapters.sumOf { it.words }, signature, System.currentTimeMillis())
        root.mkdirs()
        val stage = File(root, ".import-$id")
        check(stage.mkdirs()) { "无法创建范本暂存区" }
        try {
            writeReference(stage, reference, chapterTexts)
            check(stage.renameTo(folder(root, id))) { "无法保存范本" }
        } finally { if (stage.exists()) stage.deleteRecursively() }
        return reference
    }

    internal fun analyses(root: File, strict: Boolean = false): List<ReferenceAnalysis> {
        val file = File(root, ANALYSES)
        if (!file.isFile) return emptyList()
        return try {
            require(file.length() <= MAX_ANALYSES * MAX_ANALYSIS_CHARS * 4L) { "范本分析文件过大" }
            val array = JSONArray(file.readText())
            require(array.length() <= MAX_ANALYSES) { "范本分析数量过多" }
            (0 until array.length()).map { analysisFromJson(array.getJSONObject(it)) }
                .also { require(it.map { a -> a.id }.distinct().size == it.size) { "范本分析 id 重复" } }
                .sortedByDescending { it.createdAt }
        } catch (failure: Exception) {
            DurableFiles.keepDamaged(file)
            if (strict) throw failure else emptyList()
        }
    }

    internal fun writeAnalyses(root: File, values: List<ReferenceAnalysis>) {
        require(values.size <= MAX_ANALYSES) { "范本分析最多保存 $MAX_ANALYSES 份，请先删除旧分析" }
        root.mkdirs()
        check(DurableFiles.write(File(root, ANALYSES), JSONArray().apply { values.forEach { put(analysisToJson(it)) } }.toString().toByteArray())) { "无法保存范本分析" }
    }

    internal fun sourceCurrent(root: File, analysis: ReferenceAnalysis): Boolean = runCatching {
        val reference = list(root).firstOrNull { it.id == analysis.referenceId } ?: return@runCatching false
        val chapter = reference.chapters.firstOrNull { it.id == analysis.chapterId } ?: return@runCatching false
        val body = text(root, reference, chapter.id) ?: return@runCatching false
        sourceMatches(reference, chapter, body, analysis)
    }.getOrDefault(false)

    internal fun sourceMatches(reference: WritingReference, chapter: ReferenceChapter, body: String, analysis: ReferenceAnalysis): Boolean {
        val start = analysis.start
        val end = analysis.end
        if (start < 0 || end <= start || end > body.length || safeStart(body, start) != start || safeEnd(body, end) != end) return false
        if (analysis.part == ReferencePart.OPENING && start != 0) return false
        if (analysis.part == ReferencePart.ENDING && end != body.length) return false
        if (analysis.part == ReferencePart.CHAPTER && (start != 0 || end != body.length || body.length > MAX_CHAPTER_ANALYSIS_CHARS)) return false
        if (analysis.part != ReferencePart.CHAPTER && end - start > MAX_EXCERPT_CHARS) return false
        return excerptFingerprint(reference.id, chapter.id, chapter.fingerprint, analysis.part, start, end, body.substring(start, end)) == analysis.sourceFingerprint
    }

    internal fun referenceFingerprint(title: String, author: String, chapters: List<ReferenceChapter>): String = fingerprint(buildString {
        append(title).append('\n').append(author)
        chapters.forEach { append('\n').append(it.title).append('|').append(it.number).append('|').append(it.fingerprint) }
    })

    internal fun writeReference(directory: File, reference: WritingReference, texts: Map<String, String>) {
        directory.mkdirs()
        reference.chapters.forEach { chapter ->
            check(DurableFiles.write(File(directory, "${chapter.id}.txt"), texts.getValue(chapter.id).toByteArray())) { "无法保存范本章节" }
        }
        check(DurableFiles.write(File(directory, METADATA), referenceToJson(reference).toString().toByteArray())) { "无法保存范本索引" }
    }

    internal fun referenceToJson(value: WritingReference): JSONObject = JSONObject().put("id", value.id).put("title", value.title)
        .put("author", value.author).put("sourceName", value.sourceName).put("sourceBookId", value.sourceBookId ?: "")
        .put("words", value.words).put("fingerprint", value.fingerprint).put("importedAt", value.importedAt)
        .put("chapters", JSONArray().apply { value.chapters.forEach { chapter -> put(JSONObject().put("id", chapter.id)
            .put("index", chapter.index).put("title", chapter.title).put("number", chapter.number ?: JSONObject.NULL)
            .put("words", chapter.words).put("fingerprint", chapter.fingerprint)) } })

    internal fun referenceFromJson(value: JSONObject): WritingReference {
        val id = value.getString("id")
        require(ID.matches(id)) { "范本 id 无效" }
        val array = value.getJSONArray("chapters")
        require(array.length() in 1..MAX_CHAPTERS) { "范本章节数量无效" }
        val chapters = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val chapterId = c.getString("id")
            val index = integer(c, "index", 1, MAX_CHAPTERS.toLong()).toInt()
            val words = integer(c, "words", 0, MAX_CHAPTER_CHARS.toLong()).toInt()
            val number = if (c.isNull("number")) null else integer(c, "number", 0, Int.MAX_VALUE.toLong()).toInt()
            require(ID.matches(chapterId) && index == i + 1 && HASH.matches(c.getString("fingerprint"))) { "范本章节索引损坏" }
            ReferenceChapter(chapterId, index, c.getString("title"), number, words, c.getString("fingerprint"))
        }
        require(chapters.map { it.id }.distinct().size == chapters.size) { "范本章节 id 重复" }
        val words = integer(value, "words", 0, MAX_BOOK_CHARS).toInt()
        require(words == chapters.sumOf { it.words } && HASH.matches(value.getString("fingerprint"))) { "范本字数或指纹损坏" }
        return WritingReference(id, value.getString("title"), value.getString("author"), value.getString("sourceName"),
            value.optString("sourceBookId").takeIf { it.isNotEmpty() }, chapters, words, value.getString("fingerprint"),
            integer(value, "importedAt", 0, Long.MAX_VALUE))
    }

    internal fun analysisToJson(value: ReferenceAnalysis): JSONObject = JSONObject().put("id", value.id)
        .put("referenceId", value.referenceId).put("referenceTitle", value.referenceTitle).put("chapterId", value.chapterId)
        .put("chapterTitle", value.chapterTitle).put("targetChapterId", value.targetChapterId).put("part", value.part.name)
        .put("start", value.start).put("end", value.end).put("sourceFingerprint", value.sourceFingerprint)
        .put("targetFingerprint", value.targetFingerprint).put("body", value.body).put("createdAt", value.createdAt).put("applied", value.applied)

    internal fun analysisFromJson(value: JSONObject): ReferenceAnalysis {
        listOf("id", "referenceId", "chapterId", "targetChapterId").forEach { require(ID.matches(value.getString(it))) { "范本分析 id 无效" } }
        val body = value.getString("body")
        require(body.isNotBlank() && body.length <= MAX_ANALYSIS_CHARS) { "范本分析正文无效或过长" }
        require(HASH.matches(value.getString("sourceFingerprint")) && value.getString("targetFingerprint").isNotBlank()) { "范本分析指纹无效" }
        require(value.opt("applied") is Boolean) { "范本分析应用标记无效" }
        val start = integer(value, "start", 0, MAX_CHAPTER_CHARS.toLong()).toInt()
        val end = integer(value, "end", start + 1L, MAX_CHAPTER_CHARS.toLong()).toInt()
        val part = ReferencePart.valueOf(value.getString("part"))
        require(end - start <= if (part == ReferencePart.CHAPTER) MAX_CHAPTER_ANALYSIS_CHARS else MAX_EXCERPT_CHARS) { "范本分析范围过长" }
        return ReferenceAnalysis(value.getString("id"), value.getString("referenceId"), value.getString("referenceTitle"),
            value.getString("chapterId"), value.getString("chapterTitle"), value.getString("targetChapterId"), part, start, end,
            value.getString("sourceFingerprint"), value.getString("targetFingerprint"), body,
            integer(value, "createdAt", 0, Long.MAX_VALUE), value.getBoolean("applied"))
    }

    private fun integer(value: JSONObject, name: String, min: Long, max: Long): Long {
        val number = value.get(name)
        require(number is Number && number.toString().toLongOrNull()?.let { it in min..max } == true) { "范本 $name 数值无效" }
        return number.toLong()
    }
}

fun WorkStore.references(workId: String): List<WritingReference> = synchronized(this) {
    if (get(workId) == null) emptyList() else WritingReferences.list(referenceRoot(workId))
}

fun WorkStore.importReference(workId: String, fileName: String, text: String): WritingReference =
    importReferenceContent(workId, fileName, WritingReferenceImport.parse(fileName, text), null)

fun WorkStore.importReference(workId: String, fileName: String, bytes: ByteArray): WritingReference =
    importReferenceContent(workId, fileName, WritingReferenceImport.parse(fileName, bytes), null)

fun WorkStore.importReference(workId: String, fileName: String, file: File): WritingReference =
    importReferenceContent(workId, fileName, WritingReferenceImport.parse(fileName, file), null)

fun WorkStore.importBookReference(workId: String, bookId: String, content: BookContent, sourceName: String = "书架"): WritingReference =
    importReferenceContent(workId, sourceName, content, bookId)

private fun WorkStore.importReferenceContent(workId: String, fileName: String, content: BookContent, bookId: String?): WritingReference = synchronized(this) {
    val reference = WritingReferences.import(referenceRoot(workId), fileName, content, bookId)
    update(workId) { it }
    reference
}

/** 只读一个明确指定的范本章；未知 id 返回空，绝不换成首章、相邻章或整书。 */
fun WorkStore.referenceText(workId: String, referenceId: String, chapterId: String): String? = synchronized(this) {
    val reference = references(workId).firstOrNull { it.id == referenceId } ?: return@synchronized null
    WritingReferences.text(referenceRoot(workId), reference, chapterId)
}

fun WorkStore.selectedChapterExcerpt(workId: String, referenceId: String, chapterId: String,
    part: ReferencePart = ReferencePart.OPENING, maxChars: Int = 2400, start: Int = 0, end: Int = 0): ReferenceExcerpt? = synchronized(this) {
    val reference = references(workId).firstOrNull { it.id == referenceId } ?: return@synchronized null
    val chapter = reference.chapters.firstOrNull { it.id == chapterId } ?: return@synchronized null
    val text = WritingReferences.text(referenceRoot(workId), reference, chapterId) ?: return@synchronized null
    WritingReferences.excerpt(reference, chapter, text, part, maxChars, start, end)
}

fun WorkStore.referenceAnalyses(workId: String, targetChapterId: String? = null): List<ReferenceAnalysis> = synchronized(this) {
    if (get(workId) == null) emptyList() else WritingReferences.analyses(referenceRoot(workId)).filter { targetChapterId == null || it.targetChapterId == targetChapterId }
}

fun WorkStore.saveReferenceAnalysis(workId: String, analysis: ReferenceAnalysis): ReferenceAnalysis = synchronized(this) {
    require(get(workId)?.chapter(analysis.targetChapterId) != null) { "目标写作章不在了" }
    WritingReferences.analysisFromJson(WritingReferences.analysisToJson(analysis))
    val root = referenceRoot(workId)
    require(WritingReferences.sourceCurrent(root, analysis)) { "范本片段已变化或不在了，请重新分析" }
    val values = WritingReferences.analyses(root, strict = true).filterNot { it.id == analysis.id }
    WritingReferences.writeAnalyses(root, listOf(analysis) + values)
    update(workId) { it }
    analysis
}

fun WorkStore.setReferenceAnalysisApplied(workId: String, id: String, applied: Boolean): Boolean = synchronized(this) {
    if (get(workId) == null) return@synchronized false
    val root = referenceRoot(workId)
    val values = WritingReferences.analyses(root, strict = true)
    val analysis = values.firstOrNull { it.id == id } ?: return@synchronized false
    if (applied) require(WritingReferences.sourceCurrent(root, analysis)) { "范本片段已变化或不在了，请重新分析" }
    WritingReferences.writeAnalyses(root, values.map { if (it.id == id) it.copy(applied = applied) else it })
    update(workId) { it }
    true
}

fun WorkStore.deleteReferenceAnalysis(workId: String, id: String): Boolean = synchronized(this) {
    if (get(workId) == null) return@synchronized false
    val root = referenceRoot(workId)
    val values = WritingReferences.analyses(root, strict = true)
    if (values.none { it.id == id }) return@synchronized false
    WritingReferences.writeAnalyses(root, values.filterNot { it.id == id })
    update(workId) { it }
    true
}

fun WorkStore.deleteReference(workId: String, referenceId: String): Boolean = synchronized(this) {
    if (references(workId).none { it.id == referenceId }) return@synchronized false
    val root = referenceRoot(workId)
    require(WritingReferences.ID.matches(referenceId)) { "范本 id 无效" }
    val values = WritingReferences.analyses(root, strict = true)
    check(File(root, referenceId).deleteRecursively()) { "无法删除范本" }
    WritingReferences.writeAnalyses(root, values.filterNot { it.referenceId == referenceId })
    update(workId) { it }
    true
}

fun WorkStore.referenceAnalysisCurrent(workId: String, analysis: ReferenceAnalysis, targetFingerprint: String): Boolean = synchronized(this) {
    get(workId)?.chapter(analysis.targetChapterId) != null && analysis.targetFingerprint == targetFingerprint &&
        WritingReferences.sourceCurrent(referenceRoot(workId), analysis)
}
