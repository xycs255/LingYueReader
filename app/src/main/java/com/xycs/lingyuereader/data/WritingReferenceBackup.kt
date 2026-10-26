package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 独立范本的有界快照。partial 不删除本机未包含的范本；旧备份没有本组件时也原样保留。 */
internal data class ReferenceArchive(
    val complete: Boolean,
    val items: List<Pair<WritingReference, Map<String, String>>>,
    val analyses: List<ReferenceAnalysis>,
)

internal object WritingReferenceBackup {
    private const val JSON_OVERHEAD = 256L
    /** 与 Library 的 BACKUP_DATA_FILE / MAX_BACKUP_JSON_BYTES 一致。 */
    const val MAX_WRITING_JSON_BYTES = 64L * 1024 * 1024

    fun snapshot(root: File, budget: Long, skipped: (String, String) -> Unit): JSONObject? {
        val references = WritingReferences.list(root, strict = true)
        val analyses = WritingReferences.analyses(root, strict = true)
        if (budget < JSON_OVERHEAD) {
            references.forEach { skipped(it.title, "范本备份预算不足，原文和关联分析未放进备份；本机原件保留") }
            if (references.isEmpty() && analyses.isNotEmpty()) skipped("范本分析", "范本备份预算不足，分析未放进备份")
            return null
        }
        var used = JSON_OVERHEAD
        var complete = true
        val items = JSONArray()
        val saved = linkedMapOf<String, Pair<WritingReference, Map<String, String>>>()
        for (reference in references) {
            val metadata = WritingReferences.referenceToJson(reference)
            val lowerBound = metadata.toString().toByteArray().size.toLong() + reference.chapters.sumOf { chapter ->
                File(File(root, reference.id), "${chapter.id}.txt").length()
            }
            if (lowerBound > budget - used) {
                complete = false
                skipped(reference.title, "范本原文超过本次备份剩余预算，原文和关联分析未放进备份；请另存原始文件")
                continue
            }
            try {
                val texts = reference.chapters.associate { chapter ->
                    chapter.id to requireNotNull(WritingReferences.text(root, reference, chapter.id)) { "范本章节文件缺失" }
                }
                metadata.put("texts", JSONObject().apply { texts.forEach { (id, text) -> put(id, text) } })
                val size = metadata.toString().toByteArray().size + 1L
                if (size > budget - used) {
                    complete = false
                    skipped(reference.title, "范本原文超过本次备份剩余预算，原文和关联分析未放进备份；请另存原始文件")
                } else {
                    items.put(metadata)
                    saved[reference.id] = reference to texts
                    used += size
                }
            } catch (failure: Exception) {
                complete = false
                skipped(reference.title, "范本原文读不出，原文和关联分析未放进备份：${failure.message.orEmpty()}")
            }
        }
        val savedAnalyses = JSONArray()
        for (analysis in analyses) {
            val (reference, texts) = saved[analysis.referenceId] ?: continue
            val chapter = reference.chapters.firstOrNull { it.id == analysis.chapterId }
            if (chapter == null || !WritingReferences.sourceMatches(reference, chapter, texts.getValue(chapter.id), analysis)) {
                complete = false
                skipped("${analysis.referenceTitle} · ${analysis.chapterTitle}", "范本分析的原文指纹不符，分析未放进备份")
                continue
            }
            val json = WritingReferences.analysisToJson(analysis)
            val size = json.toString().toByteArray().size + 1L
            if (size > budget - used) {
                complete = false
                skipped("${analysis.referenceTitle} · ${analysis.chapterTitle}", "范本备份预算不足，分析未放进备份")
            } else {
                savedAnalyses.put(json)
                used += size
            }
        }
        return JSONObject().put("complete", complete).put("items", items).put("analyses", savedAnalyses)
    }

    /** 在任何作品暂存/替换以前验证路径、计数、文字体积、索引以及原文/分析指纹。 */
    fun parse(value: JSONObject, targetChapterIds: Set<String>): ReferenceArchive {
        require(value.toString().toByteArray().size <= WritingReferences.MAX_BACKUP_BYTES) { "备份范本超过安全上限" }
        require(value.opt("complete") is Boolean) { "备份范本完整标记无效" }
        val raw = value.getJSONArray("items")
        require(raw.length() <= WritingReferences.MAX_REFERENCES) { "备份范本数量过多" }
        val items = (0 until raw.length()).map { i ->
            val item = raw.getJSONObject(i)
            val reference = WritingReferences.referenceFromJson(item)
            val rawTexts = item.getJSONObject("texts")
            require(rawTexts.length() == reference.chapters.size) { "备份范本章节正文缺失或多余" }
            var chars = 0L
            val texts = reference.chapters.associate { chapter ->
                require(rawTexts.opt(chapter.id) is String) { "备份范本章节正文缺失或损坏" }
                val text = rawTexts.getString(chapter.id)
                require(text.isNotBlank() && text.length <= WritingReferences.MAX_CHAPTER_CHARS) { "备份范本章节过长或为空" }
                chars += text.length
                require(chars <= WritingReferences.MAX_BOOK_CHARS && WordCount.of(text) == chapter.words &&
                    WritingReferences.fingerprint(text) == chapter.fingerprint) { "备份范本章节字数或指纹不符" }
                chapter.id to text
            }
            require(WritingReferences.referenceFingerprint(reference.title, reference.author, reference.chapters) == reference.fingerprint) { "备份范本索引指纹不符" }
            reference to texts
        }
        require(items.map { it.first.id }.distinct().size == items.size) { "备份范本 id 重复" }
        val byId = items.associateBy { it.first.id }
        val rawAnalyses = value.getJSONArray("analyses")
        require(rawAnalyses.length() <= WritingReferences.MAX_ANALYSES) { "备份范本分析数量过多" }
        val analyses = (0 until rawAnalyses.length()).map { index ->
            val analysis = WritingReferences.analysisFromJson(rawAnalyses.getJSONObject(index))
            require(analysis.targetChapterId in targetChapterIds) { "备份范本分析引用未知写作章" }
            val (reference, texts) = requireNotNull(byId[analysis.referenceId]) { "备份范本分析引用未知范本" }
            val chapter = requireNotNull(reference.chapters.firstOrNull { it.id == analysis.chapterId }) { "备份范本分析引用未知范本章" }
            require(WritingReferences.sourceMatches(reference, chapter, texts.getValue(chapter.id), analysis)) { "备份范本分析的原文指纹不符" }
            analysis
        }
        require(analyses.map { it.id }.distinct().size == analyses.size) { "备份范本分析 id 重复" }
        return ReferenceArchive(value.getBoolean("complete"), items, analyses)
    }

    fun stage(archive: ReferenceArchive, root: File, targetChapterIds: Set<String>) {
        val previous = if (archive.complete) emptyList() else WritingReferences.analyses(root, strict = true)
        if (archive.complete) check(!root.exists() || root.deleteRecursively()) { "无法暂存范本删除" }
        root.mkdirs()
        archive.items.forEach { (reference, texts) ->
            val target = File(root, reference.id)
            check(!target.exists() || target.deleteRecursively()) { "无法暂存范本替换" }
            WritingReferences.writeReference(target, reference, texts)
        }
        val importedIds = archive.analyses.map { it.id }.toSet()
        val analyses = (archive.analyses + previous.filter { it.id !in importedIds }).filter { it.targetChapterId in targetChapterIds }
        require(analyses.size <= WritingReferences.MAX_ANALYSES) { "恢复后的范本分析数量过多，请先清理旧分析" }
        WritingReferences.writeAnalyses(root, analyses)
    }
}
