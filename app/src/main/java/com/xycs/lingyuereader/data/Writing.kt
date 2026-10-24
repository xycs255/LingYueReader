package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一章的状态：先有细纲，再写草稿，改稿时是修订，定稿后正文锁定（要改先登记原因，见 [ChangeEntry]）。
 */
enum class ChapterStatus(val label: String) { OUTLINE("细纲"), DRAFT("草稿"), REVISION("修订"), FINAL("定稿") }

/** 作品里的一章。正文另存一个文件，这里只记章名、字数、最后修改时间和状态。 */
data class WorkChapter(
    val id: String,
    val title: String,
    val words: Int = 0,
    val updatedAt: Long = 0,
    val status: ChapterStatus = ChapterStatus.OUTLINE,
) {
    val locked: Boolean get() = status == ChapterStatus.FINAL
}

/**
 * 一章的细纲卡：细纲只划事实边界，不管怎么写。
 * [facts] 本章发生了什么、结果是什么；[boundary] 后文承接时不能与之冲突的点；[pov] 视角人物；
 * [countdown] 章首的倒计时；[hook] 章末钩子；[threads] 推进了哪几条线。
 */
data class ChapterCard(
    val facts: String = "",
    val boundary: String = "",
    val pov: String = "",
    val countdown: String = "",
    val hook: String = "",
    val threads: String = "",
    /** 写完以后的本章摘要（发生了什么、结果、留下什么）：写后面的章时当前情带给 AI。 */
    val summary: String = "",
) {
    /** 细纲（写之前的六项）是不是空的；摘要不算。 */
    val isEmpty: Boolean get() = listOf(facts, boundary, pov, countdown, hook, threads).all { it.isBlank() }
}

/** 变更登记的种类。 */
enum class ChangeKind(val label: String) { UNLOCK("解锁修改"), FINALIZE("定稿"), CANON("设定变更") }

/**
 * 一条变更登记：哪一章、为什么改。[canon] 为真表示改动涉及不变量或设定，要同步改设定包（不变量、细纲、人物档案、察觉表），
 * 改完标 [synced]。
 */
data class ChangeEntry(
    val id: String,
    val at: Long,
    val kind: ChangeKind,
    val chapterId: String = "",
    val chapterTitle: String = "",
    val reason: String = "",
    val canon: Boolean = false,
    val synced: Boolean = false,
) {
    val pending: Boolean get() = canon && !synced
}

/** 一卷：长篇常按卷分，只有一卷时就是“正文”。 */
data class Volume(val id: String, val title: String, val chapters: List<WorkChapter> = emptyList())

/** 一部作品。[previewBookId] 是“当书预览”时放进书架的那本书。 */
data class Work(
    val id: String,
    val title: String,
    val author: String = "",
    val synopsis: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val volumes: List<Volume> = emptyList(),
    val previewBookId: String? = null,
) {
    val chapters: List<WorkChapter> get() = volumes.flatMap { it.chapters }
    val words: Int get() = volumes.sumOf { v -> v.chapters.sumOf { it.words } }

    fun chapter(id: String): WorkChapter? = volumes.firstNotNullOfOrNull { v -> v.chapters.firstOrNull { it.id == id } }
    fun volumeOf(chapterId: String): Volume? = volumes.firstOrNull { v -> v.chapters.any { it.id == chapterId } }
}

/** 设定集里的条目类别。 */
enum class BibleKind(val label: String) { CHARACTER("人物"), WORLD("世界观"), OUTLINE("大纲"), FORESHADOW("伏笔"), OTHER("其他") }

/** 设定集里的一条：人物、地点势力、大纲、伏笔……写作时作为 AI 的参考。 */
data class BibleEntry(val id: String, val kind: BibleKind, val name: String, val text: String)

/** 作者采纳过的一次 AI 修改：润色时拿最近几条当例子，让 AI 贴近作者的改法。 */
data class StyleExample(val action: String, val before: String, val after: String, val at: Long)

/**
 * 一部作品的 AI 设置和资料：实时检查开没开（默认关，开了才会自动把刚写的段落发给服务商）、
 * 作者写的文风偏好、设定集、采纳过的修改。
 */
data class WorkAssist(
    val realtime: Boolean = false,
    val style: String = "",
    val entries: List<BibleEntry> = emptyList(),
    val accepted: List<StyleExample> = emptyList(),
    val rejected: Int = 0,
) {
    companion object {
        /** 采纳的修改最多记这么多条。 */
        const val KEEP_EXAMPLES = 20
    }
}

/** 历史版本：[at] 是存下的时间，也是文件名。 */
data class Snapshot(val at: Long, val words: Int)

/** 码字统计：每天净增的字数和每天的目标。 */
data class WritingStats(val days: Map<LocalDate, Int> = emptyMap(), val goal: Int = 0) {
    fun on(day: LocalDate): Int = days[day] ?: 0

    /** 连续达标（没设目标时是连续有码字）的天数；今天还没写不算断。 */
    fun streak(today: LocalDate): Int {
        val ok = { d: LocalDate -> on(d) > 0 && on(d) >= goal }
        var day = if (ok(today)) today else today.minusDays(1)
        var n = 0
        while (ok(day)) {
            n++
            day = day.minusDays(1)
        }
        return n
    }
}

object WordCount {
    /** 网文平台的算法：不算空白，标点算字。 */
    fun of(text: String): Int = text.count { !it.isWhitespace() }
}

/**
 * 作品都存在 [root] 下：
 * - works/作品id/work.json：作品信息、卷章结构；works/作品id/章id.txt：每章正文；
 * - history/作品id/章id/时间.txt：历史版本（大，不进系统云备份）；
 * - preview/作品id.epub：当书预览时生成的 EPUB（随时可以重新生成）；
 * - stats.json：每天码字数和目标。
 * 写盘都先写临时文件再改名（[DurableFiles]），写到一半断电也不会坏稿。
 */
class WorkStore(private val root: File) {

    private val works = File(root, "works")
    private val history = File(root, "history")
    private val statsFile = File(root, "stats.json")
    private val clock: () -> Long = System::currentTimeMillis

    private fun dir(workId: String): File {
        require(ID.matches(workId)) { "作品 id 无效" }
        return File(works, workId)
    }

    private fun chapterFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.txt")
    }

    private fun historyDir(workId: String, chapterId: String): File {
        require(ID.matches(workId) && ID.matches(chapterId)) { "id 无效" }
        return File(File(history, workId), chapterId)
    }

    fun previewFile(workId: String): File {
        require(ID.matches(workId)) { "作品 id 无效" }
        return File(File(root, "preview"), "$workId.epub")
    }

    // ---------- 作品 ----------

    @Synchronized
    fun list(): List<Work> = works.listFiles().orEmpty()
        .filter { it.isDirectory && ID.matches(it.name) }
        .mapNotNull { d -> runCatching { read(d.name) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(workId: String): Work? = runCatching { read(workId) }.getOrNull()

    private fun read(workId: String): Work {
        val file = File(dir(workId), WORK)
        return try {
            JSONObject(file.readText()).toWork()
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            throw e
        }
    }

    private fun write(work: Work) {
        val d = dir(work.id).apply { mkdirs() }
        check(DurableFiles.write(File(d, WORK), work.toJson().toString().toByteArray())) { "无法保存作品信息" }
    }

    /** 新作品：一卷“正文”、一个空的第 1 章。 */
    @Synchronized
    fun create(title: String, author: String = ""): Work {
        val now = clock()
        val work = Work(
            id = newId(),
            title = title.trim().ifEmpty { "未命名作品" },
            author = author.trim(),
            createdAt = now,
            updatedAt = now,
            volumes = listOf(Volume(newId(), DEFAULT_VOLUME, listOf(WorkChapter(newId(), "第1章", 0, now)))),
        )
        write(work)
        return work
    }

    /** 改作品信息、卷章结构（不含正文）。返回改后的作品；作品不在了返回空。 */
    @Synchronized
    fun update(workId: String, touch: Boolean = true, transform: (Work) -> Work): Work? {
        val old = get(workId) ?: return null
        val next = transform(old).let { if (touch) it.copy(updatedAt = clock()) else it }
        if (next != old) write(next)
        return next
    }

    @Synchronized
    fun delete(workId: String) {
        dir(workId).deleteRecursively()
        File(history, workId).deleteRecursively()
        previewFile(workId).delete()
    }

    // ---------- 卷章 ----------

    /** 在 [volumeId] 卷末加一章；章名默认按全书章数往下编。 */
    fun addChapter(workId: String, volumeId: String, title: String? = null): Pair<Work, WorkChapter>? {
        var added: WorkChapter? = null
        val work = update(workId) { w ->
            val c = WorkChapter(newId(), title?.trim()?.ifEmpty { null } ?: "第${w.chapters.size + 1}章", 0, clock())
            added = c
            w.copy(volumes = w.volumes.map { v -> if (v.id == volumeId) v.copy(chapters = v.chapters + c) else v })
        } ?: return null
        val c = added ?: return null
        return if (work.chapter(c.id) != null) work to c else null
    }

    fun addVolume(workId: String, title: String): Work? = update(workId) { w ->
        w.copy(volumes = w.volumes + Volume(newId(), title.trim().ifEmpty { "第${w.volumes.size + 1}卷" }))
    }

    fun renameChapter(workId: String, chapterId: String, title: String): Work? = update(workId) { w ->
        w.copy(volumes = w.volumes.map { v -> v.copy(chapters = v.chapters.map { if (it.id == chapterId) it.copy(title = title.trim()) else it }) })
    }

    fun renameVolume(workId: String, volumeId: String, title: String): Work? = update(workId) { w ->
        w.copy(volumes = w.volumes.map { if (it.id == volumeId) it.copy(title = title.trim()) else it })
    }

    /** 删一章：正文先存进历史版本（误删了还能从文件里找回），再删。 */
    @Synchronized
    fun deleteChapter(workId: String, chapterId: String): Work? {
        val text = text(workId, chapterId)
        if (text.isNotBlank()) snapshot(workId, chapterId, text)
        val work = update(workId) { w -> w.copy(volumes = w.volumes.map { v -> v.copy(chapters = v.chapters.filterNot { it.id == chapterId }) }) }
        chapterFile(workId, chapterId).delete()
        cardFile(workId, chapterId).delete()
        reviewFile(workId, chapterId).delete()
        draftFile(workId, chapterId).delete()
        return work
    }

    // ---------- 状态与变更登记 ----------

    /**
     * 改一章的状态。从定稿改出去（解锁）必须写原因，记一条登记；[canon] 为真表示改动涉及设定，要同步设定包。
     * 定稿时把正文存一个历史版本，记一条登记。状态没变不记。
     */
    @Synchronized
    fun setStatus(workId: String, chapterId: String, status: ChapterStatus, reason: String = "", canon: Boolean = false): Work? {
        val work = get(workId) ?: return null
        val c = work.chapter(chapterId) ?: return null
        if (c.status == status) return work
        if (c.locked) require(reason.isNotBlank()) { "改定稿的章节要先写原因" }
        when {
            c.locked -> addChange(workId, ChangeEntry(newId(), clock(), ChangeKind.UNLOCK, c.id, c.title, reason.trim(), canon))
            status == ChapterStatus.FINAL -> {
                text(workId, chapterId).takeIf { it.isNotBlank() }?.let { snapshot(workId, chapterId, it) }
                addChange(workId, ChangeEntry(newId(), clock(), ChangeKind.FINALIZE, c.id, c.title, reason.trim(), canon))
            }
        }
        return update(workId) { w -> w.copy(volumes = w.volumes.map { v -> v.copy(chapters = v.chapters.map { if (it.id == chapterId) it.copy(status = status) else it }) }) }
    }

    private val changesFile get() = "changes.json"

    @Synchronized
    fun changes(workId: String): List<ChangeEntry> {
        val file = File(dir(workId), changesFile)
        if (!file.isFile) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toChange() }.sortedByDescending { it.at }
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            emptyList()
        }
    }

    private fun writeChanges(workId: String, list: List<ChangeEntry>) {
        check(DurableFiles.write(File(dir(workId), changesFile), JSONArray().apply { list.forEach { put(it.toJson()) } }.toString().toByteArray())) { "无法保存变更登记" }
    }

    /** 记一条登记（也用于和章节无关的设定变更）。 */
    @Synchronized
    fun addChange(workId: String, entry: ChangeEntry) {
        require(get(workId) != null) { "作品不在了" }
        val list = changes(workId)
        // 新登记排最前：同一毫秒里连记两条时，时间往后挪一毫秒。
        val at = maxOf(entry.at, (list.maxOfOrNull { it.at } ?: 0L) + 1)
        writeChanges(workId, list + entry.copy(at = at))
    }

    @Synchronized
    fun markSynced(workId: String, changeId: String, synced: Boolean = true) =
        writeChanges(workId, changes(workId).map { if (it.id == changeId) it.copy(synced = synced) else it })

    private fun cardFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.card.json")
    }

    @Synchronized
    fun card(workId: String, chapterId: String): ChapterCard {
        val file = cardFile(workId, chapterId)
        if (!file.isFile) return ChapterCard()
        return try {
            JSONObject(file.readText()).toCard()
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            ChapterCard()
        }
    }

    private fun draftFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.draft.txt")
    }

    /** AI 写的本章草稿（还没放进正文的；离开页面再回来还在）。 */
    @Synchronized
    fun draft(workId: String, chapterId: String): String = draftFile(workId, chapterId).takeIf { it.isFile }?.readText().orEmpty()

    @Synchronized
    fun saveDraft(workId: String, chapterId: String, text: String) {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        if (text.isEmpty()) draftFile(workId, chapterId).delete()
        else check(DurableFiles.write(draftFile(workId, chapterId), text.toByteArray())) { "无法保存草稿" }
    }

    private fun reviewFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.review.json")
    }

    /** 这一章最近一次审稿的结果（可以重新审，不进备份）。 */
    @Synchronized
    fun review(workId: String, chapterId: String): ChapterReview? {
        val file = reviewFile(workId, chapterId)
        if (!file.isFile) return null
        return runCatching { ChapterReview.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    fun saveReview(workId: String, chapterId: String, review: ChapterReview) {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        check(DurableFiles.write(reviewFile(workId, chapterId), review.toJson().toString().toByteArray())) { "无法保存审稿结果" }
    }

    @Synchronized
    fun saveCard(workId: String, chapterId: String, card: ChapterCard) {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        check(DurableFiles.write(cardFile(workId, chapterId), card.toJson().toString().toByteArray())) { "无法保存细纲卡" }
    }

    /** 删一卷，连同卷里的章。至少留一卷。 */
    @Synchronized
    fun deleteVolume(workId: String, volumeId: String): Work? {
        val work = get(workId) ?: return null
        if (work.volumes.size <= 1) return work
        work.volumes.firstOrNull { it.id == volumeId }?.chapters?.forEach { deleteChapter(workId, it.id) }
        return update(workId) { w -> w.copy(volumes = w.volumes.filterNot { it.id == volumeId }) }
    }

    /** 章往前（[delta] < 0）或往后挪一位；在卷首往前挪就挪到上一卷末尾，卷末往后挪就到下一卷开头。 */
    fun moveChapter(workId: String, chapterId: String, delta: Int): Work? = update(workId) { w ->
        val vi = w.volumes.indexOfFirst { v -> v.chapters.any { it.id == chapterId } }
        if (vi < 0) return@update w
        val chapters = w.volumes[vi].chapters.toMutableList()
        val ci = chapters.indexOfFirst { it.id == chapterId }
        val target = ci + delta.coerceIn(-1, 1)
        val volumes = w.volumes.toMutableList()
        when {
            target in chapters.indices -> {
                chapters.add(target, chapters.removeAt(ci))
                volumes[vi] = volumes[vi].copy(chapters = chapters)
            }
            target < 0 && vi > 0 -> {
                val c = chapters.removeAt(ci)
                volumes[vi] = volumes[vi].copy(chapters = chapters)
                volumes[vi - 1] = volumes[vi - 1].let { it.copy(chapters = it.chapters + c) }
            }
            target >= chapters.size && vi < volumes.lastIndex -> {
                val c = chapters.removeAt(ci)
                volumes[vi] = volumes[vi].copy(chapters = chapters)
                volumes[vi + 1] = volumes[vi + 1].let { it.copy(chapters = listOf(c) + it.chapters) }
            }
            else -> return@update w
        }
        w.copy(volumes = volumes)
    }

    // ---------- 正文 ----------

    @Synchronized
    fun text(workId: String, chapterId: String): String =
        chapterFile(workId, chapterId).takeIf { it.isFile }?.readText().orEmpty()

    /** 存一章正文，更新字数，并把净增的字数记到今天。返回改后的作品；正文没变返回原作品。 */
    @Synchronized
    fun saveText(workId: String, chapterId: String, text: String, today: LocalDate = LocalDate.now(), count: Boolean = true): Work? {
        val work = get(workId) ?: return null
        val old = work.chapter(chapterId) ?: return null
        val file = chapterFile(workId, chapterId)
        if (file.isFile && file.readText() == text) return work
        check(!old.locked) { "这一章已定稿，先解锁再改" }
        check(DurableFiles.write(file, text.toByteArray())) { "无法保存正文" }
        val words = WordCount.of(text)
        if (count && words != old.words) addWords(today, words - old.words)
        return update(workId) { w ->
            w.copy(volumes = w.volumes.map { v ->
                v.copy(chapters = v.chapters.map {
                    if (it.id != chapterId) it
                    else it.copy(words = words, updatedAt = clock(), status = if (it.status == ChapterStatus.OUTLINE && words > 0) ChapterStatus.DRAFT else it.status)
                })
            })
        }
    }

    // ---------- 历史版本 ----------

    /** 存一个历史版本；和最近一版一样就不存。每章最多留 [KEEP_SNAPSHOTS] 版。 */
    @Synchronized
    fun snapshot(workId: String, chapterId: String, text: String, at: Long = clock()): Boolean {
        val d = historyDir(workId, chapterId)
        val latest = history(workId, chapterId).firstOrNull()
        if (latest != null && File(d, "${latest.at}.txt").readText() == text) return false
        d.mkdirs()
        val stamp = if (latest != null && at <= latest.at) latest.at + 1 else at
        check(DurableFiles.write(File(d, "$stamp.txt"), text.toByteArray())) { "无法保存历史版本" }
        history(workId, chapterId).drop(KEEP_SNAPSHOTS).forEach { File(d, "${it.at}.txt").delete() }
        return true
    }

    /** 历史版本，新的在前。 */
    @Synchronized
    fun history(workId: String, chapterId: String): List<Snapshot> =
        historyDir(workId, chapterId).listFiles().orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".txt").takeIf { f.name.endsWith(".txt") }?.toLongOrNull()?.let { Snapshot(it, WordCount.of(f.readText())) } }
            .sortedByDescending { it.at }

    @Synchronized
    fun snapshotText(workId: String, chapterId: String, at: Long): String? =
        File(historyDir(workId, chapterId), "$at.txt").takeIf { it.isFile }?.readText()

    /** 换回某个历史版本：现在的正文先存一版，换错了还能换回来。 */
    @Synchronized
    fun restore(workId: String, chapterId: String, at: Long): Work? {
        val text = snapshotText(workId, chapterId, at) ?: return null
        snapshot(workId, chapterId, text(workId, chapterId))
        return saveText(workId, chapterId, text)
    }

    // ---------- AI 设置与设定集 ----------

    @Synchronized
    fun assist(workId: String): WorkAssist {
        val file = File(dir(workId), ASSIST)
        if (!file.isFile) return WorkAssist()
        return try {
            JSONObject(file.readText()).toAssist()
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            WorkAssist()
        }
    }

    @Synchronized
    fun updateAssist(workId: String, transform: (WorkAssist) -> WorkAssist): WorkAssist {
        val d = dir(workId)
        require(File(d, WORK).isFile) { "作品不在了" }
        val next = transform(assist(workId))
        check(DurableFiles.write(File(d, ASSIST), next.toJson().toString().toByteArray())) { "无法保存设定" }
        return next
    }

    // ---------- 设定包 ----------

    private fun packDir(workId: String) = File(dir(workId), PACK)

    private fun packFile(workId: String, docId: String): File {
        require(ID.matches(docId)) { "文档 id 无效" }
        return File(packDir(workId), "$docId.md")
    }

    /** 文档的历史版本和章节放在同一处，键加个前缀分开。 */
    fun docKey(docId: String) = "pack-$docId"

    private fun readPack(workId: String): List<PackDoc>? {
        val file = File(packDir(workId), PACK_INDEX)
        if (!file.isFile) return null
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toPackDoc() }
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            emptyList()
        }
    }

    private fun writePack(workId: String, docs: List<PackDoc>) {
        val d = packDir(workId).apply { mkdirs() }
        check(DurableFiles.write(File(d, PACK_INDEX), JSONArray().apply { docs.forEach { put(it.toJson()) } }.toString().toByteArray())) { "无法保存设定包" }
    }

    /**
     * 设定包里的文档，按优先级排好。第一次打开时，把旧版“设定集”里的条目按类别并成几份文档
     * （人物 → 人物档案，世界观 → 硬性设定，大纲 → 总纲，伏笔和其他 → 资料）。
     */
    @Synchronized
    fun pack(workId: String): List<PackDoc> {
        readPack(workId)?.let { return it.sorted() }
        val entries = assist(workId).entries
        if (entries.isEmpty()) return emptyList()
        val docs = mutableListOf<PackDoc>()
        val groups = listOf(
            BibleKind.CHARACTER to (PackKind.CHARACTER to "人物"),
            BibleKind.WORLD to (PackKind.CANON to "世界观"),
            BibleKind.OUTLINE to (PackKind.OUTLINE to "大纲"),
            BibleKind.FORESHADOW to (PackKind.REFERENCE to "伏笔"),
            BibleKind.OTHER to (PackKind.REFERENCE to "其他设定"),
        )
        groups.forEach { (bk, target) ->
            val list = entries.filter { it.kind == bk }
            if (list.isEmpty()) return@forEach
            val text = list.joinToString("\n\n") { e -> "## ${e.name.ifBlank { "（无标题）" }}\n${e.text}".trim() }
            val doc = PackDoc(newId(), target.first, target.second, updatedAt = clock(), words = WordCount.of(text))
            check(DurableFiles.write(packFile(workId, doc.id).also { it.parentFile?.mkdirs() }, text.toByteArray())) { "无法保存设定" }
            docs += doc
        }
        writePack(workId, docs)
        updateAssist(workId) { it.copy(entries = emptyList()) }
        return docs.sorted()
    }

    private fun List<PackDoc>.sorted() = sortedWith(compareBy({ it.priority }, { it.kind.ordinal }, { it.title }))

    @Synchronized
    fun packText(workId: String, docId: String): String = packFile(workId, docId).takeIf { it.isFile }?.readText().orEmpty()

    /** 新建一份文档。 */
    @Synchronized
    fun addPackDoc(workId: String, kind: PackKind, title: String, text: String = "", priority: Int = kind.priority, always: Boolean = kind.always): PackDoc {
        require(get(workId) != null) { "作品不在了" }
        val doc = PackDoc(newId(), kind, title.trim().ifEmpty { kind.label }, priority.coerceIn(1, 9), always, clock(), WordCount.of(text))
        check(DurableFiles.write(packFile(workId, doc.id).also { it.parentFile?.mkdirs() }, text.toByteArray())) { "无法保存设定" }
        writePack(workId, pack(workId) + doc)
        return doc
    }

    /** 改文档的类型、标题、优先级、常用与否。 */
    @Synchronized
    fun updatePackDoc(workId: String, docId: String, transform: (PackDoc) -> PackDoc): PackDoc? {
        val docs = pack(workId)
        val old = docs.firstOrNull { it.id == docId } ?: return null
        val next = transform(old).let { it.copy(id = old.id, priority = it.priority.coerceIn(1, 9), title = it.title.trim().ifEmpty { old.title }) }
        if (next != old) writePack(workId, docs.map { if (it.id == docId) next else it })
        return next
    }

    /** 存文档正文。没变就不写。 */
    @Synchronized
    fun savePackText(workId: String, docId: String, text: String): PackDoc? {
        val file = packFile(workId, docId)
        if (file.isFile && file.readText() == text) return pack(workId).firstOrNull { it.id == docId }
        if (pack(workId).none { it.id == docId }) return null
        check(DurableFiles.write(file, text.toByteArray())) { "无法保存设定" }
        return updatePackDoc(workId, docId) { it.copy(updatedAt = clock(), words = WordCount.of(text)) }
    }

    /** 删一份文档：正文先存进历史版本。 */
    @Synchronized
    fun deletePackDoc(workId: String, docId: String) {
        val text = packText(workId, docId)
        if (text.isNotBlank()) snapshot(workId, docKey(docId), text)
        writePack(workId, pack(workId).filterNot { it.id == docId })
        packFile(workId, docId).delete()
    }

    /** 换回文档的某个历史版本（现在的先存一版）。 */
    @Synchronized
    fun restorePackDoc(workId: String, docId: String, at: Long): PackDoc? {
        val text = snapshotText(workId, docKey(docId), at) ?: return null
        snapshot(workId, docKey(docId), packText(workId, docId))
        return savePackText(workId, docId, text)
    }

    /**
     * 导入：设定类的文件，同名（标题相同）的替换（旧版存进历史），没有的新加；正文类的拆成章节，接在最后一卷后面，
     * 不算进今天的码字数。
     */
    @Synchronized
    fun importPack(workId: String, items: List<PackImportItem>): PackImportResult {
        var added = 0
        var replaced = 0
        var chapters = 0
        items.filter { it.include }.forEach { item ->
            if (item.asChapters) {
                PackImport.chapters(item.text, item.title).forEach { (title, body) ->
                    val work = get(workId) ?: return@forEach
                    val (_, c) = addChapter(workId, work.volumes.last().id, title) ?: return@forEach
                    saveText(workId, c.id, body, count = false)
                    // 文件名写着“已定”“定稿”的正文，导进来就是定稿。
                    if (Regex("已定|定稿").containsMatchIn(item.fileName)) setStatus(workId, c.id, ChapterStatus.FINAL, "导入已定正文")
                    chapters++
                }
                return@forEach
            }
            val existing = pack(workId).firstOrNull { it.title == item.title }
            if (existing != null) {
                val old = packText(workId, existing.id)
                if (old.isNotBlank() && old != item.text) snapshot(workId, docKey(existing.id), old)
                savePackText(workId, existing.id, item.text)
                updatePackDoc(workId, existing.id) { it.copy(kind = item.kind, priority = item.priority, always = item.always) }
                replaced++
            } else {
                addPackDoc(workId, item.kind, item.title, item.text, item.priority, item.always)
                added++
            }
        }
        return PackImportResult(added, replaced, chapters)
    }

    // ---------- 码字统计 ----------

    @Synchronized
    fun stats(): WritingStats {
        if (!statsFile.isFile) return WritingStats()
        return try {
            val json = JSONObject(statsFile.readText())
            val days = json.optJSONObject("days") ?: JSONObject()
            WritingStats(
                days.keys().asSequence().mapNotNull { k -> runCatching { LocalDate.parse(k) to days.getInt(k) }.getOrNull() }.toMap(),
                json.optInt("goal", 0),
            )
        } catch (e: Exception) {
            DurableFiles.keepDamaged(statsFile)
            WritingStats()
        }
    }

    private fun saveStats(stats: WritingStats) {
        root.mkdirs()
        val days = JSONObject()
        stats.days.toSortedMap().forEach { (d, n) -> days.put(d.toString(), n) }
        check(DurableFiles.write(statsFile, JSONObject().put("goal", stats.goal).put("days", days).toString().toByteArray())) { "无法保存码字统计" }
    }

    @Synchronized
    fun addWords(day: LocalDate, delta: Int) {
        if (delta == 0) return
        val s = stats()
        saveStats(s.copy(days = s.days + (day to s.on(day) + delta)))
    }

    @Synchronized
    fun setGoal(goal: Int) = saveStats(stats().copy(goal = goal.coerceAtLeast(0)))

    // ---------- 备份 ----------

    /** 所有作品（含正文，不含历史版本）和码字统计打成一个 JSON，放进灵阅的备份。没有作品时为空。 */
    @Synchronized
    fun backup(): ByteArray? {
        val all = list()
        if (all.isEmpty() && !statsFile.isFile) return null
        val array = JSONArray()
        all.forEach { w ->
            val texts = JSONObject()
            w.chapters.forEach { c -> texts.put(c.id, text(w.id, c.id)) }
            val pack = JSONArray().apply { pack(w.id).forEach { d -> put(d.toJson().put("text", packText(w.id, d.id))) } }
            val cards = JSONObject().apply { w.chapters.forEach { c -> card(w.id, c.id).takeIf { !it.isEmpty }?.let { put(c.id, it.toJson()) } } }
            val changes = JSONArray().apply { changes(w.id).forEach { put(it.toJson()) } }
            array.put(w.toJson().put("texts", texts).put("assist", assist(w.id).toJson()).put("pack", pack).put("cards", cards).put("changes", changes))
        }
        val stats = stats()
        val days = JSONObject()
        stats.days.forEach { (d, n) -> days.put(d.toString(), n) }
        return JSONObject().put("format", 1).put("works", array).put("goal", stats.goal).put("days", days).toString().toByteArray()
    }

    /**
     * 从备份恢复：本机没有的作品加上；本机也有的，备份里的更新就整部换成备份的（换之前本机每章正文都存一个历史版本），
     * 本机的更新就留着本机的。码字统计按天取大的。返回恢复了几部作品。
     */
    @Synchronized
    fun restoreBackup(json: String): Int {
        val backup = JSONObject(json)
        var restored = 0
        val array = backup.optJSONArray("works") ?: JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val incoming = runCatching { o.toWork() }.getOrNull() ?: continue
            if (!ID.matches(incoming.id) || incoming.chapters.any { !ID.matches(it.id) }) continue
            val local = get(incoming.id)
            if (local != null && local.updatedAt > incoming.updatedAt) continue
            local?.chapters?.forEach { c -> text(local.id, c.id).takeIf { it.isNotBlank() }?.let { snapshot(local.id, c.id, it) } }
            val texts = o.optJSONObject("texts") ?: JSONObject()
            dir(incoming.id).mkdirs()
            incoming.chapters.forEach { c ->
                check(DurableFiles.write(chapterFile(incoming.id, c.id), texts.optString(c.id).toByteArray())) { "无法写入正文" }
            }
            local?.chapters?.filter { c -> incoming.chapter(c.id) == null }?.forEach {
                chapterFile(local.id, it.id).delete()
                cardFile(local.id, it.id).delete()
                reviewFile(local.id, it.id).delete()
                draftFile(local.id, it.id).delete()
            }
            val cards = o.optJSONObject("cards") ?: JSONObject()
            incoming.chapters.forEach { c ->
                val card = cards.optJSONObject(c.id)
                if (card != null) check(DurableFiles.write(cardFile(incoming.id, c.id), card.toString().toByteArray())) { "无法写入细纲卡" }
                else cardFile(incoming.id, c.id).delete()
            }
            o.optJSONArray("changes")?.let { a -> writeChanges(incoming.id, (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toChange() }) }
            // 设定包整份换成备份里的（本机的文档正文先存历史版本）；备份里没有设定包（旧版备份）就按设定集重新迁移。
            local?.let { l -> pack(l.id).forEach { d -> packText(l.id, d.id).takeIf { it.isNotBlank() }?.let { snapshot(l.id, docKey(d.id), it) } } }
            packDir(incoming.id).deleteRecursively()
            o.optJSONArray("pack")?.let { a ->
                val docs = (0 until a.length()).mapNotNull { i ->
                    val d = a.optJSONObject(i) ?: return@mapNotNull null
                    val doc = runCatching { d.toPackDoc() }.getOrNull()?.takeIf { ID.matches(it.id) } ?: return@mapNotNull null
                    check(DurableFiles.write(packFile(incoming.id, doc.id).also { it.parentFile?.mkdirs() }, d.optString("text").toByteArray())) { "无法写入设定" }
                    doc
                }
                writePack(incoming.id, docs)
            }
            o.optJSONObject("assist")?.let { a ->
                runCatching { a.toAssist() }.getOrNull()?.let { assist ->
                    check(DurableFiles.write(File(dir(incoming.id), ASSIST), assist.toJson().toString().toByteArray())) { "无法写入设定" }
                }
            }
            write(incoming.copy(previewBookId = local?.previewBookId ?: incoming.previewBookId))
            restored++
        }
        val days = backup.optJSONObject("days")
        if (days != null) {
            val s = stats()
            val merged = s.days.toMutableMap()
            days.keys().forEach { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { d -> merged[d] = maxOf(merged[d] ?: 0, days.optInt(k)) } }
            saveStats(s.copy(days = merged, goal = if (s.goal == 0) backup.optInt("goal", 0) else s.goal))
        }
        return restored
    }

    companion object {
        const val DEFAULT_VOLUME = "正文"
        const val KEEP_SNAPSHOTS = 40
        private const val WORK = "work.json"
        private const val ASSIST = "assist.json"
        private const val PACK = "pack"
        private const val PACK_INDEX = "index.json"
        private val ID = Regex("""[A-Za-z0-9-]{1,64}""")

        internal fun newId() = UUID.randomUUID().toString()

        private fun Work.toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("author", author).put("synopsis", synopsis)
            .put("createdAt", createdAt).put("updatedAt", updatedAt)
            .put("previewBookId", previewBookId ?: "")
            .put("volumes", JSONArray().apply {
                volumes.forEach { v ->
                    put(JSONObject().put("id", v.id).put("title", v.title).put("chapters", JSONArray().apply {
                        v.chapters.forEach { c -> put(JSONObject().put("id", c.id).put("title", c.title).put("words", c.words).put("updatedAt", c.updatedAt).put("status", c.status.name)) }
                    }))
                }
            })

        private fun ChapterCard.toJson(): JSONObject = JSONObject()
            .put("facts", facts).put("boundary", boundary).put("pov", pov).put("countdown", countdown).put("hook", hook).put("threads", threads)
            .put("summary", summary)

        private fun JSONObject.toCard() =
            ChapterCard(optString("facts"), optString("boundary"), optString("pov"), optString("countdown"), optString("hook"), optString("threads"), optString("summary"))

        private fun ChangeEntry.toJson(): JSONObject = JSONObject()
            .put("id", id).put("at", at).put("kind", kind.name).put("chapterId", chapterId).put("chapterTitle", chapterTitle)
            .put("reason", reason).put("canon", canon).put("synced", synced)

        private fun JSONObject.toChange(): ChangeEntry? {
            val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
            val kind = runCatching { ChangeKind.valueOf(optString("kind")) }.getOrDefault(ChangeKind.CANON)
            return ChangeEntry(id, optLong("at"), kind, optString("chapterId"), optString("chapterTitle"), optString("reason"), optBoolean("canon"), optBoolean("synced"))
        }

        private fun PackDoc.toJson(): JSONObject = JSONObject()
            .put("id", id).put("kind", kind.name).put("title", title).put("priority", priority).put("always", always)
            .put("updatedAt", updatedAt).put("words", words)

        private fun JSONObject.toPackDoc(): PackDoc? {
            val kind = runCatching { PackKind.valueOf(optString("kind")) }.getOrDefault(PackKind.REFERENCE)
            val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
            return PackDoc(id, kind, optString("title"), optInt("priority", kind.priority).coerceIn(1, 9), optBoolean("always", kind.always), optLong("updatedAt"), optInt("words"))
        }

        private fun WorkAssist.toJson(): JSONObject = JSONObject()
            .put("realtime", realtime).put("style", style).put("rejected", rejected)
            .put("entries", JSONArray().apply { entries.forEach { e -> put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("name", e.name).put("text", e.text)) } })
            .put("accepted", JSONArray().apply { accepted.forEach { e -> put(JSONObject().put("action", e.action).put("before", e.before).put("after", e.after).put("at", e.at)) } })

        private fun JSONObject.toAssist(): WorkAssist {
            val es = optJSONArray("entries") ?: JSONArray()
            val ac = optJSONArray("accepted") ?: JSONArray()
            return WorkAssist(
                realtime = optBoolean("realtime"),
                style = optString("style"),
                rejected = optInt("rejected"),
                entries = (0 until es.length()).mapNotNull { i ->
                    val e = es.optJSONObject(i) ?: return@mapNotNull null
                    val kind = runCatching { BibleKind.valueOf(e.optString("kind")) }.getOrDefault(BibleKind.OTHER)
                    BibleEntry(e.optString("id").ifEmpty { newId() }, kind, e.optString("name"), e.optString("text"))
                },
                accepted = (0 until ac.length()).mapNotNull { i ->
                    val e = ac.optJSONObject(i) ?: return@mapNotNull null
                    StyleExample(e.optString("action"), e.optString("before"), e.optString("after"), e.optLong("at"))
                },
            )
        }

        private fun JSONObject.toWork(): Work {
            val vs = getJSONArray("volumes")
            return Work(
                id = getString("id"),
                title = getString("title"),
                author = optString("author"),
                synopsis = optString("synopsis"),
                createdAt = optLong("createdAt"),
                updatedAt = optLong("updatedAt"),
                previewBookId = optString("previewBookId").ifEmpty { null },
                volumes = (0 until vs.length()).map { i ->
                    val v = vs.getJSONObject(i)
                    val cs = v.getJSONArray("chapters")
                    Volume(v.getString("id"), v.getString("title"), (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j)
                        val words = c.optInt("words")
                        // 旧数据没有状态：有字的算草稿，没字的算细纲。
                        val status = runCatching { ChapterStatus.valueOf(c.optString("status")) }.getOrNull()
                            ?: if (words > 0) ChapterStatus.DRAFT else ChapterStatus.OUTLINE
                        WorkChapter(c.getString("id"), c.getString("title"), words, c.optLong("updatedAt"), status)
                    })
                },
            )
        }
    }
}

/** 导出作品：TXT（网文平台常用的格式）、Markdown、EPUB。正文里的一行就是一段。 */
object WorkExport {

    private val sceneBreak = Regex("""^\s*([*＊\-—_·•]\s*){3,}$""")

    /** 正文里的段落：去掉首尾空白（含全角空格），空行不算。 */
    fun paragraphs(text: String): List<String> =
        text.lines().map { it.trim().trim('　').trim() }.filter { it.isNotEmpty() }

    /** 卷名要不要单独印出来：只有一卷且叫“正文”时不印。 */
    private fun Work.showVolumes() = volumes.size > 1 || volumes.singleOrNull()?.title != WorkStore.DEFAULT_VOLUME

    /** 去掉轻量 Markdown 的标记（**粗体**、*斜体*、行首的 #），给 TXT 用。 */
    private fun plain(line: String): String =
        line.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])"""), "$1")
            .replace(Regex("""^#{1,6}\s+"""), "")

    fun txt(work: Work, text: (WorkChapter) -> String): String = buildString {
        append(work.title).append('\n')
        if (work.author.isNotBlank()) append("作者：").append(work.author).append('\n')
        if (work.synopsis.isNotBlank()) append('\n').append("简介：\n").append(paragraphs(work.synopsis).joinToString("\n") { "　　$it" }).append('\n')
        val volumes = work.showVolumes()
        work.volumes.forEach { v ->
            if (volumes) append("\n\n").append(v.title).append('\n')
            v.chapters.forEach { c ->
                append("\n\n").append(c.title).append("\n\n")
                paragraphs(text(c)).forEach { p ->
                    if (sceneBreak.matches(p)) append("\n　　* * *\n") else append("　　").append(plain(p)).append('\n')
                }
            }
        }
    }

    fun markdown(work: Work, text: (WorkChapter) -> String): String = buildString {
        append("# ").append(work.title).append('\n')
        if (work.author.isNotBlank()) append("\n> 作者：").append(work.author).append('\n')
        if (work.synopsis.isNotBlank()) paragraphs(work.synopsis).forEach { append("\n> ").append(it).append('\n') }
        val volumes = work.showVolumes()
        work.volumes.forEach { v ->
            if (volumes) append("\n# ").append(v.title).append('\n')
            v.chapters.forEach { c ->
                append("\n## ").append(c.title).append('\n')
                paragraphs(text(c)).forEach { p -> append('\n').append(if (sceneBreak.matches(p)) "* * *" else p).append('\n') }
            }
        }
    }

    /**
     * EPUB 3（带 EPUB 2 的 NCX 目录，老阅读器也认）。[placeholders] 为真时空章也放一段说明——
     * 当书预览时用，这样预览里的章和作品里的章一一对应。
     */
    fun epub(work: Work, text: (WorkChapter) -> String, out: OutputStream, placeholders: Boolean = false, modified: Long = System.currentTimeMillis()) {
        val volumes = work.showVolumes()
        data class Page(val file: String, val title: String, val body: String, val volume: Volume?)
        val pages = mutableListOf<Page>()
        var n = 0
        work.volumes.forEachIndexed { vi, v ->
            if (volumes) pages += Page("v${vi + 1}.xhtml", v.title, "<h1>${esc(v.title)}</h1>", v)
            v.chapters.forEach { c ->
                n++
                val paras = paragraphs(text(c))
                val body = StringBuilder("<h2>").append(esc(c.title)).append("</h2>\n")
                if (paras.isEmpty() && placeholders) body.append("<p>（这一章还没有内容）</p>\n")
                paras.forEach { p -> body.append(if (sceneBreak.matches(p)) "<hr/>" else "<p>${inline(p)}</p>").append('\n') }
                pages += Page("c$n.xhtml", c.title, body.toString(), null)
            }
        }
        val uid = "urn:uuid:${work.id}"
        val stamp = java.time.Instant.ofEpochMilli(modified).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        ZipOutputStream(out).use { zip ->
            // mimetype 必须是第一个、不压缩。
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
""",
            )
            put("OEBPS/style.css", "body{line-height:1.8;} h1,h2{text-align:center;} p{text-indent:2em;margin:0 0 .4em 0;} hr{border:none;text-align:center;margin:1em 0;} hr:after{content:'* * *';}\n")
            pages.forEach { p ->
                put(
                    "OEBPS/${p.file}",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN"><head><meta charset="UTF-8"/><title>${esc(p.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
${p.body}</body></html>
""",
                )
            }
            // 目录：分卷时卷下套章。
            val nav = StringBuilder()
            val ncx = StringBuilder()
            var order = 0
            var openVolume = false
            pages.forEach { p ->
                if (p.volume != null) {
                    if (openVolume) {
                        nav.append("</ol></li>\n")
                        ncx.append("</navPoint>\n")
                    }
                    order++
                    nav.append("<li><a href=\"${p.file}\">${esc(p.title)}</a><ol>\n")
                    ncx.append("<navPoint id=\"n$order\" playOrder=\"$order\"><navLabel><text>${esc(p.title)}</text></navLabel><content src=\"${p.file}\"/>\n")
                    openVolume = true
                } else {
                    order++
                    nav.append("<li><a href=\"${p.file}\">${esc(p.title)}</a></li>\n")
                    ncx.append("<navPoint id=\"n$order\" playOrder=\"$order\"><navLabel><text>${esc(p.title)}</text></navLabel><content src=\"${p.file}\"/></navPoint>\n")
                }
            }
            if (openVolume) {
                nav.append("</ol></li>\n")
                ncx.append("</navPoint>\n")
            }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN"><head><meta charset="UTF-8"/><title>目录</title></head>
<body><nav epub:type="toc" id="toc"><h1>目录</h1><ol>
$nav</ol></nav></body></html>
""",
            )
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="$uid"/></head>
<docTitle><text>${esc(work.title)}</text></docTitle><navMap>
$ncx</navMap></ncx>
""",
            )
            val manifest = pages.joinToString("\n") { "<item id=\"${it.file.substringBefore('.')}\" href=\"${it.file}\" media-type=\"application/xhtml+xml\"/>" }
            val spine = pages.joinToString("\n") { "<itemref idref=\"${it.file.substringBefore('.')}\"/>" }
            val meta = buildString {
                append("<dc:identifier id=\"uid\">$uid</dc:identifier>\n")
                append("<dc:title>${esc(work.title)}</dc:title>\n")
                append("<dc:language>zh-CN</dc:language>\n")
                if (work.author.isNotBlank()) append("<dc:creator>${esc(work.author)}</dc:creator>\n")
                if (work.synopsis.isNotBlank()) append("<dc:description>${esc(work.synopsis)}</dc:description>\n")
                append("<meta property=\"dcterms:modified\">$stamp</meta>\n")
            }
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="zh-CN">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
$meta</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="css" href="style.css" media-type="text/css"/>
$manifest
</manifest>
<spine toc="ncx">
$spine
</spine>
</package>
""",
            )
        }
    }

    private fun esc(s: String): String = buildString {
        s.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                // XML 不许的控制字符直接丢掉，不然整本 EPUB 打不开。
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ') append(ch)
            }
        }
    }

    /** 段内的轻量 Markdown：**粗体**、*斜体*；行首的 # 当小标题去掉。 */
    private fun inline(line: String): String =
        esc(line.replace(Regex("""^#{1,6}\s+"""), ""))
            .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
            .replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])"""), "<i>$1</i>")
}
