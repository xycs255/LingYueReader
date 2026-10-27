package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    /** 细纲章号：发布章号顺延（比如大场面拆成上下章）以后，设定里的“第几章”按它算。空着按章名、目录顺序。 */
    val plan: String = "",
    /** 从卷纲细纲自动提取时，前六项的指纹；作者改过前六项就对不上了，以后不再跟着细纲变。0 表示不是提取的。 */
    val auto: Int = 0,
    /** 独立于正文的修改时间；恢复旧备份时不能用正文时间覆盖后来写的细纲。 */
    val updatedAt: Long = 0,
    /**
     * 节拍：一行一拍，写这一章的哪一步（比如“沈默赶到渡口，发现船不见了（800字）”），见 [Beats]。
     * 不算进 [auto] 的指纹：节拍是作者自己拆的，细纲文档改了也不跟着变。
     */
    val beats: String = "",
    /** 哪几拍已经写进正文（拍的序号，从 1 数）。节拍条数变了就清空，见 [withBeats]。 */
    val beatsDone: List<Int> = emptyList(),
) {
    /** 写之前的六项是不是都空着：判断要不要从细纲提取、算不算作者手写的细纲时看它。 */
    val outlineEmpty: Boolean get() = listOf(facts, boundary, pov, countdown, hook, threads).all { it.isBlank() }

    /** 细纲卡是不是空的：六项和节拍都没写；摘要不算。只写了节拍也算有内容。 */
    val isEmpty: Boolean get() = outlineEmpty && beats.isBlank()
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

/** 作者为本章候选稿填写的要求；与候选正文一起随作品备份。 */
data class DraftIntent(val instruction: String = "", val targetLength: String = "", val workflowChoice: Boolean? = null)

/**
 * 一部作品的 AI 设置和资料：写作建议开没开（默认关，开了才会自动把刚写的段落发给服务商）、
 * 作者写的文风偏好、设定集、采纳过的修改。
 */
data class WorkAssist(
    val realtime: Boolean = false,
    val style: String = "",
    val entries: List<BibleEntry> = emptyList(),
    val accepted: List<StyleExample> = emptyList(),
    val rejected: Int = 0,
    /** 作者没采纳的修改（最近 [KEEP_REJECTED] 条）：再改时告诉 AI 别这样改。 */
    val declined: List<StyleExample> = emptyList(),
    /** 本章资料最多带多少字（上下文长的模型可以放宽）。 */
    val briefLimit: Int = 12_000,
    /** 设定包的知识库最多整份放多少字；0 按模型的上下文算。 */
    val knowledgeLimit: Int = 0,
    /** 写作建议查哪几类（SuggestKind 的名字）。 */
    val suggestKinds: List<String> = SUGGEST_KINDS,
    /** 写作建议两次请求至少隔多少秒。 */
    val suggestGap: Int = 20,
    /** 写作建议一天最多发多少次（0 不限）。 */
    val suggestCap: Int = 50,
    /** 整理进设定包过的章（章id → 整理时正文的哈希）：正文改过就对不上，又算没整理。 */
    val synced: Map<String, SyncMark> = emptyMap(),
    /** 把一章标为定稿时，问一句“要不要顺手整理进设定包”。 */
    val syncTip: Boolean = true,
    /** 自动排版的规则和“输入时自动排版”（作品级，随备份走）。 */
    val format: FormatSettings = FormatSettings(),
    /** 文风范例（从定稿章里挑几段给 AI 学写法）一共带多少字；0 是不带。见 [StyleSamples]。 */
    val sampleLimit: Int = StyleSamples.DEFAULT_LIMIT,
    /** 没有定稿章时，退回用字数最多的几章。 */
    val sampleFallback: Boolean = true,
    /** 作者点了“不用这段”的范例（[StyleSample.key]）。 */
    val sampleSkip: List<String> = emptyList(),
    /** 各类范例点了几次“换一段”（[SampleKind] 的名字 → 次数）。 */
    val sampleShift: Map<String, Int> = emptyMap(),
    /** 这些章的候选稿必须重新审读后才能采纳；只记要求，不持久化“通过”。 */
    val draftWorkflows: Set<String> = emptySet(),
    /** 上次写这一章时的作者要求和目标篇幅，重启后仍可继续按同一任务写。 */
    val draftIntents: Map<String, DraftIntent> = emptyMap(),
) {
    /** 挑文风范例用的设置。 */
    val sampleOptions: SampleOptions get() = SampleOptions(sampleLimit, sampleSkip.toSet(), sampleShift)

    companion object {
        /** 采纳的修改最多记这么多条。 */
        const val KEEP_EXAMPLES = 20
        const val KEEP_REJECTED = 10
        val BRIEF_LIMITS = listOf(6_000, 12_000, 20_000, 32_000)
        val KNOWLEDGE_LIMITS = listOf(0, 30_000, 60_000, 100_000, 150_000, 300_000)
        val SUGGEST_KINDS = listOf("TYPO", "CANON", "STYLE", "VOICE")
        val SUGGEST_GAPS = listOf(10, 20, 40, 60, 120)
        val SUGGEST_CAPS = listOf(50, 100, 200, 500, 0)
    }
}

/** 历史版本：[at] 是存下的时间，也是文件名。 */
data class Snapshot(val at: Long, val words: Int)

/**
 * 备份或恢复写作时没有处理的一项：[work] 是作品名（作品信息读不出时尽量从文件里认出书名，认不出是“作品 + id 开头”；码字统计为空），
 * [file] 是作品文件夹里的哪个文件（恢复时整部跳过为空），[reason] 是为什么。
 */
data class WritingSkip(val work: String, val file: String, val reason: String) {
    /** 给人看的一句：“《长夜》abc.card.json：「第3章」的细纲卡读不出，没有放进备份”。 */
    val label: String get() = listOf(if (work.isEmpty()) "" else "《$work》", file).joinToString("").let { if (it.isEmpty()) reason else "$it：$reason" }
}

/** 写作部分的备份：[bytes] 放进灵阅的备份（writing.json）；[skipped] 是读不出、没放进去或只放了空的文件，要告诉作者。 */
class WritingBackup(val bytes: ByteArray, val skipped: List<WritingSkip>)

/** 从备份恢复写作的结果：恢复了 [restored] 部；[skipped] 是整部没有恢复的作品（本机的较新，或本机的文件读不出）。 */
data class WritingRestore(val restored: Int, val skipped: List<WritingSkip>)

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
class WorkStore(
    private val root: File,
    private val savePhaseHook: ((String) -> Unit)? = null,
    // Keep this last: existing restore fault injection uses a trailing lambda.
    private val restoreSwapHook: ((String) -> Unit)? = null,
) {

    private val works = File(root, "works")
    private val history = File(root, "history")
    private val statsFile = File(root, "stats.json")
    private val clock: () -> Long = System::currentTimeMillis
    private val processLock = processLocks.computeIfAbsent(root.canonicalPath) { Any() }

    /** Keep a read/validate/write sequence atomic with other stores rooted at the same directory. */
    @Synchronized
    internal fun <T> withProcessLock(block: () -> T): T = synchronized(processLock) { block() }

    init {
        synchronized(processLock) {
            recoverInterruptedRestore()
            works.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }.forEach { work ->
                // A failed repair remains journaled. Restore must then leave this work alone.
                runCatching { recoverPendingSave(work.name) }
            }
        }
    }

    /** A marker is durable before component data changes and is cleared only after work.json commits. */
    private fun pendingSave(workId: String) = File(dir(workId), PENDING_SAVE)
    private fun recoveredSave(workId: String) = File(dir(workId), RECOVERED_SAVE)
    private fun draftPrevious(workId: String) = File(dir(workId), DRAFT_PREVIOUS)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun componentFile(workId: String, kind: String, id: String): File = when (kind) {
        "text" -> chapterFile(workId, id)
        "pack" -> packFile(workId, id)
        "draft" -> draftFile(workId, id)
        "ledger" -> File(dir(workId), LEDGER)
        else -> error("写作保存日志的组件类型无效")
    }

    private fun beginSave(workId: String, kind: String, componentId: String, bytes: ByteArray,
                          day: LocalDate? = null, statsBefore: Int? = null, statsAfter: Int? = null,
                          draftSource: ByteArray? = null, draftRevision: ByteArray? = null) {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能覆盖作品" }
        val component = componentFile(workId, kind, componentId)
        val original = component.takeIf { it.isFile }?.readBytes() ?: byteArrayOf()
        val marker = JSONObject().put("kind", kind).put("id", componentId).put("digest", digest(bytes))
            .put("beforeDigest", digest(original))
        if (kind == "draft") {
            require(bytes.isEmpty() == (draftSource == null)) { "草稿正文与来源必须一起保存" }
            require(bytes.isNotEmpty() || draftRevision == null) { "空草稿不能附带创作初稿" }
            val previous = draftPrevious(workId)
            check(!previous.exists() || previous.deleteRecursively()) { "无法清理旧草稿保存暂存区" }
            check(previous.mkdirs()) { "无法暂存原草稿" }
            val source = draftReferenceFile(workId, componentId)
            val oldSource = source.takeIf { it.isFile }?.readBytes()
            val revision = draftRevisionFile(workId, componentId)
            val oldRevision = revision.takeIf { it.isFile }?.readBytes()
            if (component.isFile) check(DurableFiles.write(File(previous, "body"), original)) { "无法暂存原草稿正文" }
            if (oldSource != null) check(DurableFiles.write(File(previous, "source"), oldSource)) { "无法暂存原草稿来源" }
            if (oldRevision != null) check(DurableFiles.write(File(previous, "revision"), oldRevision)) { "无法暂存原稿版本" }
            marker.put("bodyExists", bytes.isNotEmpty()).put("sourceExists", draftSource != null)
                .put("sourceDigest", digest(draftSource ?: byteArrayOf()))
                .put("beforeBodyExists", component.isFile).put("beforeSourceExists", oldSource != null)
                .put("beforeSourceDigest", digest(oldSource ?: byteArrayOf()))
                .put("revisionExists", draftRevision != null).put("revisionDigest", digest(draftRevision ?: byteArrayOf()))
                .put("beforeRevisionExists", oldRevision != null).put("beforeRevisionDigest", digest(oldRevision ?: byteArrayOf()))
        }
        if (day != null && statsBefore != null && statsAfter != null) {
            marker.put("day", day.toString()).put("statsBefore", statsBefore).put("statsAfter", statsAfter)
        }
        check(DurableFiles.write(pendingSave(workId), marker.toString().toByteArray())) { "无法建立写作保存日志" }
    }

    private fun completeSave(workId: String) {
        check(pendingSave(workId).delete()) { "无法清理写作保存日志" }
        // Later edits to another component do not make an older backup safe for recovered prose.
        val previous = draftPrevious(workId)
        if (previous.exists()) check(previous.deleteRecursively()) { "无法清理草稿保存暂存区" }
    }

    /** Restore all previous draft components if an interruption left a partial new version. */
    private fun recoverDraftPair(workId: String, chapterId: String, marker: JSONObject): Boolean {
        val body = draftFile(workId, chapterId)
        val source = draftReferenceFile(workId, chapterId)
        val revision = draftRevisionFile(workId, chapterId)
        fun matches(file: File, exists: Boolean, hash: String): Boolean =
            if (!exists) !file.exists() else file.isFile && digest(file.readBytes()) == hash
        if (matches(body, marker.getBoolean("bodyExists"), marker.getString("digest")) &&
            matches(source, marker.getBoolean("sourceExists"), marker.getString("sourceDigest")) &&
            (!marker.has("revisionDigest") || matches(revision, marker.getBoolean("revisionExists"), marker.getString("revisionDigest")))) return true

        val previous = draftPrevious(workId)
        fun previousBytes(name: String, exists: Boolean, hash: String): ByteArray? {
            if (!exists) return null
            val file = File(previous, name)
            check(file.isFile) { "原草稿暂存文件丢失，已保留保存日志" }
            return file.readBytes().also { check(digest(it) == hash) { "原草稿暂存文件损坏，已保留保存日志" } }
        }
        val oldBody = previousBytes("body", marker.getBoolean("beforeBodyExists"), marker.getString("beforeDigest"))
        val oldSource = previousBytes("source", marker.getBoolean("beforeSourceExists"), marker.getString("beforeSourceDigest"))
        val oldRevision = if (marker.has("beforeRevisionDigest"))
            previousBytes("revision", marker.getBoolean("beforeRevisionExists"), marker.getString("beforeRevisionDigest")) else null
        fun restore(file: File, old: ByteArray?) {
            if (old == null) check(!file.exists() || file.delete()) { "无法删除未完成的草稿组件" }
            else if (!file.isFile || !file.readBytes().contentEquals(old))
                check(DurableFiles.write(file, old)) { "无法找回原草稿组件" }
        }
        restore(body, oldBody)
        restore(source, oldSource)
        if (marker.has("beforeRevisionDigest")) restore(revision, oldRevision)
        return false
    }

    /** Repair a committed component; roll back a draft only when its body/source pair is incomplete. */
    private fun recoverPendingSave(workId: String) = synchronized(processLock) { recoverPendingSaveLocked(workId) }

    private fun recoverPendingSaveLocked(workId: String) {
        val markerFile = pendingSave(workId)
        if (!markerFile.isFile) {
            if (markerFile.exists()) return // An invalid journal path must not discard a draft rollback snapshot.
            val previous = draftPrevious(workId)
            if (previous.exists()) check(previous.deleteRecursively()) { "无法清理草稿保存暂存区" }
            return
        }
        val marker = JSONObject(markerFile.readText())
        val kind = marker.getString("kind")
        val id = marker.getString("id")
        require(ID.matches(id)) { "写作保存日志的组件 id 无效" }
        val component = componentFile(workId, kind, id)
        if (kind == "draft" && marker.has("sourceDigest") && !recoverDraftPair(workId, id, marker)) {
            check(markerFile.delete()) { "无法清理已回退的草稿保存日志" }
            val previous = draftPrevious(workId)
            check(!previous.exists() || previous.deleteRecursively()) { "无法清理原草稿暂存区" }
            return
        }
        val actualDigest = digest(component.takeIf { it.isFile }?.readBytes() ?: byteArrayOf())
        if (actualDigest != marker.getString("digest")) {
            // A crash before the first component write leaves the original bytes intact.
            // Older draft journals did not track the provenance pair.
            if (actualDigest == marker.getString("beforeDigest")) check(markerFile.delete()) { "无法清理未开始的写作保存" }
            return
        }
        val old = read(workId)
        var repaired = old
        when (kind) {
            "text" -> {
                require(old.chapter(id) != null) { "写作保存日志指向不存在的章节" }
                val words = WordCount.of(component.readText())
                repaired = repaired.copy(volumes = repaired.volumes.map { volume ->
                    volume.copy(chapters = volume.chapters.map { chapter ->
                        if (chapter.id != id) chapter else chapter.copy(words = words,
                            updatedAt = maxOf(clock(), chapter.updatedAt + 1),
                            status = if (chapter.status == ChapterStatus.OUTLINE && words > 0) ChapterStatus.DRAFT else chapter.status)
                    })
                })
            }
            "pack" -> {
                val index = File(packDir(workId), PACK_INDEX)
                val raw = JSONArray(index.readText())
                val docs = (0 until raw.length()).map { requireNotNull(raw.getJSONObject(it).toPackDoc()) { "设定索引损坏" } }
                require(docs.any { it.id == id }) { "写作保存日志指向不存在的设定文档" }
                val words = WordCount.of(component.readText())
                val updated = docs.map { doc -> if (doc.id != id) doc else doc.copy(words = words, updatedAt = maxOf(clock(), doc.updatedAt + 1)) }
                check(DurableFiles.write(index, JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString().toByteArray())) {
                    "无法修复设定索引"
                }
            }
            "draft" -> require(old.chapter(id) != null) { "写作保存日志指向不存在的章节" }
        }
        if (marker.has("day")) {
            val day = LocalDate.parse(marker.getString("day"))
            val before = marker.getInt("statsBefore")
            val after = marker.getInt("statsAfter")
            val stats = strictStats()
            when (stats.on(day)) {
                before -> saveStats(stats.copy(days = stats.days + (day to after)))
                after -> Unit
                else -> error("码字统计在中断后发生变化，已保留日志供人工核对")
            }
        }
        write(repaired.copy(updatedAt = maxOf(clock(), old.updatedAt + 1)))
        // 留一个修复标记：时间写错（写成将来）的备份恢复时，修复出来的正文、设定、账本先存进历史版本，
        // 候选稿保留本机的，不会被悄悄换掉。恢复成功后标记随旧目录换掉（见 stageRestoreWork）。
        val guard = recoveredSave(workId)
        if (guard.exists()) {
            // The guard means at least one local component was recovered. Keep it even if
            // another component recovers later; its contents are not used for restore matching.
            check(markerFile.delete()) { "无法完成写作保存修复" }
        } else check(markerFile.renameTo(guard)) { "无法完成写作保存修复" }
        val previous = draftPrevious(workId)
        if (previous.exists()) check(previous.deleteRecursively()) { "无法清理原草稿暂存区" }
    }

    /** A restore is swapped one work at a time; this journal restores the old set after a crash. */
    private fun recoverInterruptedRestore() {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(RESTORE_PREFIX) }.forEach { stage ->
            val journal = File(stage, "journal.json")
            if (!journal.isFile) {
                check(stage.deleteRecursively()) { "无法清理未完成的写作恢复暂存区" }
                return@forEach
            }
            if (!File(stage, "committed").isFile) rollbackRestore(stage)
            // Removing the journal first makes cleanup safe even if the process dies
            // while deleting stage files (including the committed marker).
            if (journal.delete()) check(stage.deleteRecursively()) { "无法清理写作恢复暂存区" }
        }
    }

    private fun rollbackRestore(stage: File) {
        val journal = JSONObject(File(stage, "journal.json").readText())
        val ids = journal.getJSONArray("ids")
        val existing = journal.optJSONArray("existing")?.let { list ->
            (0 until list.length()).map { list.getString(it) }.toSet()
        }.orEmpty()
        val prepared = File(stage, "prepared")
        val previous = File(stage, "previous")
        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            check(ID.matches(id)) { "恢复日志中的作品 id 无效" }
            val old = File(previous, id)
            val target = dir(id)
            if (old.exists()) {
                check(!target.exists() || target.deleteRecursively()) { "无法回滚写作作品" }
                check(old.renameTo(target)) { "无法找回恢复前的作品" }
            } else if (id !in existing && !File(prepared, id).exists() && target.exists()) {
                // This work did not exist before the restore, and was already installed.
                check(target.deleteRecursively()) { "无法回滚新恢复的作品" }
            }
        }
        if (journal.optBoolean("stats")) {
            val old = File(stage, "stats.previous")
            val preparedStats = File(stage, "stats.prepared")
            if (old.exists()) {
                check(!statsFile.exists() || statsFile.delete()) { "无法回滚码字统计" }
                check(old.renameTo(statsFile)) { "无法找回恢复前的码字统计" }
            } else if (!journal.optBoolean("statsExisted") && !preparedStats.exists() && statsFile.exists()) {
                check(statsFile.delete()) { "无法回滚码字统计" }
            }
        }
    }

    private fun dir(workId: String): File {
        require(ID.matches(workId)) { "作品 id 无效" }
        return File(works, workId)
    }

    /** 范本书与分析独立于设定包、账本和正文；只在作者明确选择范本时读取。 */
    internal fun referenceRoot(workId: String): File {
        require(get(workId) != null) { "作品不在了" }
        return File(dir(workId), WritingReferences.DIRECTORY)
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
        // Consecutive edits can happen within one millisecond. The restore conflict check
        // needs a strictly newer revision even when the wall clock has not advanced.
        val next = transform(old).let { if (touch) it.copy(updatedAt = maxOf(clock(), old.updatedAt + 1)) else it }
        if (next != old) write(next)
        return next
    }

    @Synchronized
    fun delete(workId: String) = synchronized(processLock) { deleteLocked(workId) }

    private fun deleteLocked(workId: String) {
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
    fun deleteChapter(workId: String, chapterId: String): Work? = synchronized(processLock) { deleteChapterLocked(workId, chapterId) }

    private fun deleteChapterLocked(workId: String, chapterId: String): Work? {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能删除章节" }
        val text = text(workId, chapterId)
        if (text.isNotBlank()) snapshot(workId, chapterId, text)
        val work = update(workId) { w -> w.copy(volumes = w.volumes.map { v -> v.copy(chapters = v.chapters.filterNot { it.id == chapterId }) }) }
        chapterFile(workId, chapterId).delete()
        cardFile(workId, chapterId).delete()
        reviewFile(workId, chapterId).delete()
        draftFile(workId, chapterId).delete()
        draftReferenceFile(workId, chapterId).delete()
        draftRevisionFile(workId, chapterId).delete()
        // 指向这章的范本分析一起清掉；清不掉也不挡删章，备份和恢复会跳过这类孤儿分析。
        runCatching { WritingReferences.forgetTarget(File(dir(workId), WritingReferences.DIRECTORY), chapterId) }
        return work
    }

    // ---------- 状态与变更登记 ----------

    /**
     * 改一章的状态。从定稿改出去（解锁）必须写原因，记一条登记；[canon] 为真表示改动涉及设定，要同步设定包。
     * 定稿时把正文存一个历史版本，记一条登记。状态没变不记。
     */
    @Synchronized
    fun setStatus(workId: String, chapterId: String, status: ChapterStatus, reason: String = "", canon: Boolean = false, currentText: String? = null): Work? {
        var work = get(workId) ?: return null
        var c = work.chapter(chapterId) ?: return null
        // 编辑器的自动保存可能还在等待停笔。先在同一把锁里保存眼前的正文，
        // 成功后才锁定；保存失败时保留可编辑状态，不能把旧盘稿误当成定稿。
        if (status == ChapterStatus.FINAL && currentText != null && !c.locked) {
            work = requireNotNull(saveText(workId, chapterId, currentText)) { "章节不在了" }
            c = requireNotNull(work.chapter(chapterId)) { "章节不在了" }
        }
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
        update(workId) { it }
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

    // ---------- 故事账本 ----------

    private fun ledgerHistoryDir(workId: String) = File(dir(workId), LEDGER_HISTORY)

    /** 故事账本（见 [StoryLedger]）；还没有是空的。读不出时另存坏文件、当作空的。 */
    @Synchronized
    fun ledger(workId: String): List<LedgerEntry> {
        val file = File(dir(workId), LEDGER)
        if (!file.isFile) return emptyList()
        return try {
            StoryLedger.parse(file.readText())
        } catch (e: Exception) {
            check(DurableFiles.keptDamaged(file, file.readBytes())) { "故事账本损坏，且无法保存原件" }
            emptyList()
        }
    }

    /**
     * 改账本：一批改动 [ops] 一起写进去（写不进去就都不算），改之前的样子存进历史（[label] 说明为什么改）。
     * 返回实际新加了几条（重复的不加）。
     */
    @Synchronized
    fun applyLedger(workId: String, ops: List<LedgerOp>, label: String): Int =
        synchronized(processLock) { applyLedgerLocked(workId, ops, label) }

    private fun applyLedgerLocked(workId: String, ops: List<LedgerOp>, label: String): Int {
        require(File(dir(workId), WORK).isFile) { "作品不在了" }
        val source = File(dir(workId), LEDGER)
        if (source.isFile) {
            val raw = source.readBytes()
            try { StoryLedger.parse(raw.toString(Charsets.UTF_8)) } catch (failure: Exception) {
                check(DurableFiles.keptDamaged(source, raw)) { "故事账本损坏，且无法保存原件" }
                throw IllegalStateException("故事账本损坏，已保存原件，请先核对后再修改", failure)
            }
        }
        val before = ledger(workId)
        val (after, added) = StoryLedger.apply(before, ops, clock())
        if (after == before) return 0
        saveLedger(workId, before, after, label)
        return added
    }

    /** 把账本 [entries] 存成 [folder] 里的一个历史版本（[label] 说明之后为什么改），只留最近 [LEDGER_KEEP] 版。 */
    private fun archiveLedger(folder: File, entries: List<LedgerEntry>, label: String) {
        folder.mkdirs()
        val taken = folder.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }.toSet()
        var at = clock()
        while (at in taken) at++
        val old = StoryLedger.toJson(entries).put("label", label)
        check(DurableFiles.write(File(folder, "$at.json"), old.toString().toByteArray())) { "无法保存账本的历史版本" }
        folder.listFiles().orEmpty().filter { it.name.removeSuffix(".json").toLongOrNull() != null }
            .sortedByDescending { it.name.removeSuffix(".json").toLong() }.drop(LEDGER_KEEP).forEach { it.delete() }
    }

    private fun saveLedger(workId: String, before: List<LedgerEntry>, after: List<LedgerEntry>, label: String) {
        if (File(dir(workId), LEDGER).isFile) archiveLedger(ledgerHistoryDir(workId), before, label)
        val bytes = StoryLedger.toJson(after).toString().toByteArray()
        beginSave(workId, "ledger", workId, bytes)
        check(DurableFiles.write(File(dir(workId), LEDGER), bytes)) { "无法保存故事账本" }
        savePhaseHook?.invoke("ledger-body")
        update(workId) { it }
        completeSave(workId)
    }

    /** 账本的历史版本（每次改之前的样子），新的在前。 */
    @Synchronized
    fun ledgerVersions(workId: String): List<LedgerVersion> =
        ledgerHistoryDir(workId).listFiles().orEmpty().mapNotNull { f ->
            val at = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
            runCatching {
                val o = JSONObject(f.readText())
                LedgerVersion(at, o.optString("label"), o.optJSONArray("entries")?.length() ?: 0)
            }.getOrNull()
        }.sortedByDescending { it.at }

    /** 某个历史版本的账本；读不出返回 null。 */
    @Synchronized
    fun ledgerVersion(workId: String, at: Long): List<LedgerEntry>? =
        File(ledgerHistoryDir(workId), "$at.json").takeIf { it.isFile }?.let { runCatching { StoryLedger.parse(it.readText()) }.getOrNull() }

    /** 换回某个历史版本（现在的样子也存进历史，可以再换回来）。 */
    @Synchronized
    fun restoreLedger(workId: String, at: Long): Boolean = synchronized(processLock) { restoreLedgerLocked(workId, at) }

    private fun restoreLedgerLocked(workId: String, at: Long): Boolean {
        val old = ledgerVersion(workId, at) ?: return false
        val now = ledger(workId)
        if (old == now) return true
        saveLedger(workId, now, old, "换回旧版本")
        return true
    }

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

    // ---------- 写作 AI 记录 ----------

    private fun logFile(workId: String) = File(dir(workId), "ailog.json")

    /** 写作 AI 的记录，新的在前。 */
    @Synchronized
    fun aiLog(workId: String): List<WriteRecord> {
        val file = logFile(workId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(WriteRecord::fromJson) }
        }.getOrDefault(emptyList()).sortedByDescending { it.at }
    }

    private fun writeLog(workId: String, list: List<WriteRecord>) {
        require(File(dir(workId), WORK).isFile) { "作品不在了" }
        check(DurableFiles.write(logFile(workId), JSONArray().apply { list.take(WriteRecord.KEEP).forEach { put(it.toJson()) } }.toString().toByteArray())) { "无法保存记录" }
    }

    @Synchronized
    fun addAiLog(workId: String, record: WriteRecord) = writeLog(workId, listOf(record) + aiLog(workId))

    @Synchronized
    fun rateAiLog(workId: String, id: String, rating: Int) = writeLog(workId, aiLog(workId).map { if (it.id == id) it.copy(rating = rating) else it })

    @Synchronized
    fun deleteAiLog(workId: String, id: String) = writeLog(workId, aiLog(workId).filterNot { it.id == id })

    private fun draftFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.draft.txt")
    }

    private fun draftReferenceFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.draft.reference.json")
    }

    private fun draftRevisionFile(workId: String, chapterId: String): File {
        require(ID.matches(chapterId)) { "章节 id 无效" }
        return File(dir(workId), "$chapterId.draft.revision.json")
    }

    /** AI 写的本章草稿（还没放进正文的；离开页面再回来还在）。 */
    @Synchronized
    fun draft(workId: String, chapterId: String): String = draftFile(workId, chapterId).takeIf { it.isFile }?.readText().orEmpty()

    /** A damaged or mismatched provenance file is an adoption blocker, not an absent reference. */
    @Synchronized
    fun draftContent(workId: String, chapterId: String): DraftContent {
        val body = draft(workId, chapterId)
        if (body.isEmpty()) return DraftContent(body)
        val file = draftReferenceFile(workId, chapterId)
        val provenance = if (!file.isFile) DraftContent(body,
            provenanceError = "旧草稿缺少来源记录，无法核验范本，请重新生成后采纳", blocked = true)
        else try {
            DraftReferenceCodec.decode(file.readText(), body)
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            DraftContent(body, provenanceError = "草稿的范本来源记录损坏或与正文不一致，请重新生成", blocked = true)
        }
        val revision = draftRevisionFile(workId, chapterId)
        if (!revision.exists()) return provenance
        return try {
            provenance.copy(revision = DraftRevisionCodec.decode(revision.readText(), body))
        } catch (e: Exception) {
            val kept = revision.isFile && runCatching { DurableFiles.keptDamaged(revision, revision.readBytes()) }.getOrDefault(false)
            provenance.copy(revisionError = if (kept) "创作初稿或对照版本损坏，已另存问题文件；请检查备份"
                else "创作初稿或对照版本损坏，无法安全留底；已阻止草稿覆盖")
        }
    }

    @Synchronized
    fun saveDraft(workId: String, chapterId: String, text: String, reference: DraftReferenceProvenance? = null,
                  blocked: Boolean = false, revision: DraftRevisionSnapshot? = null, beats: DraftBeats? = null) =
        synchronized(processLock) { saveDraftLocked(workId, chapterId, text, reference, blocked, revision, beats) }

    /** [beats] 是逐拍写的进度：和正文、来源记录在同一次保存里落盘（见 [DraftBeats]）。 */
    private fun saveDraftLocked(workId: String, chapterId: String, text: String, reference: DraftReferenceProvenance?,
                                blocked: Boolean, revision: DraftRevisionSnapshot?, beats: DraftBeats?) {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能覆盖草稿" }
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        val file = draftFile(workId, chapterId)
        val source = draftReferenceFile(workId, chapterId)
        val revisionFile = draftRevisionFile(workId, chapterId)
        // A readable draft must not make its only damaged revision snapshot disappear.
        // This guard also covers callers that never opened the draft screen.
        if (revisionFile.exists()) {
            check(revisionFile.isFile) { "创作初稿文件不是普通文件，已阻止覆盖" }
            val oldBytes = revisionFile.readBytes()
            val oldBody = file.takeIf { it.isFile }?.readText().orEmpty()
            if (runCatching { DraftRevisionCodec.decode(oldBytes.toString(Charsets.UTF_8), oldBody) }.isFailure)
                check(DurableFiles.keptDamaged(revisionFile, oldBytes)) { "损坏的创作初稿无法安全留底，已阻止覆盖" }
        }
        if (text.isEmpty()) {
            require(revision == null) { "空草稿不能附带创作初稿" }
            if (!file.exists() && !source.exists() && !revisionFile.exists()) return
            beginSave(workId, "draft", chapterId, byteArrayOf(), draftSource = null)
            try {
                check(!file.exists() || file.delete()) { "无法删除草稿" }
                savePhaseHook?.invoke("draft-body")
                check(!source.exists() || source.delete()) { "无法删除草稿范本来源" }
                check(!revisionFile.exists() || revisionFile.delete()) { "无法删除创作初稿" }
            } catch (failure: Exception) {
                runCatching { recoverPendingSave(workId) }
                throw failure
            }
        } else {
            val provenance = DraftReferenceCodec.encode(text, reference, blocked, beats).toString()
            val snapshot = revision?.let { DraftRevisionCodec.encode(text, it.initialText, it.alternateText).toString() }
            if (file.isFile && source.isFile && file.readText() == text && source.readText() == provenance &&
                (if (snapshot == null) !revisionFile.exists() else revisionFile.isFile && revisionFile.readText() == snapshot)) return
            beginSave(workId, "draft", chapterId, text.toByteArray(), draftSource = provenance.toByteArray(),
                draftRevision = snapshot?.toByteArray())
            try {
                if (!file.isFile || file.readText() != text) {
                    check(DurableFiles.write(file, text.toByteArray())) { "无法保存草稿" }
                }
                savePhaseHook?.invoke("draft-body")
                check(DurableFiles.write(source, provenance.toByteArray())) { "无法保存草稿范本来源" }
                if (snapshot == null) check(!revisionFile.exists() || revisionFile.delete()) { "无法清理旧创作初稿" }
                else check(DurableFiles.write(revisionFile, snapshot.toByteArray())) { "无法保存创作初稿" }
            } catch (failure: Exception) {
                runCatching { recoverPendingSave(workId) }
                throw failure
            }
        }
        // 草稿也是作者的内容：仅改草稿或明确删除草稿时，备份也必须算新版本。
        update(workId) { it }
        completeSave(workId)
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
        val old = card(workId, chapterId)
        val next = card.copy(updatedAt = maxOf(clock(), old.updatedAt + 1))
        check(DurableFiles.write(cardFile(workId, chapterId), next.toJson().toString().toByteArray())) { "无法保存细纲卡" }
        update(workId) { it }
    }

    /**
     * 按字段改细纲卡：在同一把锁里读出现在的卡、改、写回，只动 [transform] 改到的字段
     * （比如 `updateCard(w, c) { it.copy(summary = 摘要) }`），不会拿旧卡片把别处刚写的字段盖掉。
     * 返回改后的卡；没改动不写盘，返回原卡。
     */
    @Synchronized
    fun updateCard(workId: String, chapterId: String, transform: (ChapterCard) -> ChapterCard): ChapterCard {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        val old = card(workId, chapterId)
        val changed = transform(old)
        if (changed.copy(updatedAt = old.updatedAt) == old) return old
        val next = changed.copy(updatedAt = maxOf(clock(), old.updatedAt + 1))
        check(DurableFiles.write(cardFile(workId, chapterId), next.toJson().toString().toByteArray())) { "无法保存细纲卡" }
        update(workId) { it }
        return next
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
    fun saveText(workId: String, chapterId: String, text: String, today: LocalDate = LocalDate.now(), count: Boolean = true): Work? =
        synchronized(processLock) { saveTextLocked(workId, chapterId, text, today, count) }

    private fun saveTextLocked(workId: String, chapterId: String, text: String, today: LocalDate, count: Boolean): Work? {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能覆盖正文" }
        val work = get(workId) ?: return null
        val old = work.chapter(chapterId) ?: return null
        val file = chapterFile(workId, chapterId)
        val words = WordCount.of(text)
        val sameText = file.isFile && file.readText() == text
        if (sameText && old.words == words) return work
        if (!sameText) check(!old.locked) { "这一章已定稿，先解锁再改" }
        // 统计坏了（原文件已另存一份）：正文照常保存，只是这次不计数，也不覆盖坏的统计文件。
        val previousStats = if (count && words != old.words) statsOrDamaged() else null
        val before = previousStats?.on(today)
        beginSave(workId, "text", chapterId, text.toByteArray(), today, before, before?.plus(words - old.words))
        check(DurableFiles.write(file, text.toByteArray())) { "无法保存正文" }
        previousStats?.let { s -> saveStats(s.copy(days = s.days + (today to s.on(today) + words - old.words))) }
        val saved = update(workId) { w ->
            w.copy(volumes = w.volumes.map { v ->
                v.copy(chapters = v.chapters.map {
                    if (it.id != chapterId) it
                    else it.copy(words = words, updatedAt = clock(), status = if (it.status == ChapterStatus.OUTLINE && words > 0) ChapterStatus.DRAFT else it.status)
                })
            })
        }
        completeSave(workId)
        return saved
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
        update(workId) { it }
        return next
    }

    /** 记住候选稿的审读要求；不改稿件，也不把任何旧报告变成有效的通过记录。 */
    @Synchronized
    fun setDraftWorkflow(workId: String, chapterId: String, required: Boolean): WorkAssist {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        val old = assist(workId)
        val chapters = if (required) old.draftWorkflows + chapterId else old.draftWorkflows - chapterId
        if (chapters == old.draftWorkflows) return old
        return updateAssist(workId) { it.copy(draftWorkflows = chapters) }
    }

    @Synchronized
    fun draftIntent(workId: String, chapterId: String): DraftIntent? = assist(workId).draftIntents[chapterId]

    /** 请求发出前保存作者本次构思，避免关闭页面后仅留下正文、丢掉写作任务。 */
    @Synchronized
    fun setDraftIntent(workId: String, chapterId: String, instruction: String, targetLength: String, workflowChoice: Boolean? = null): WorkAssist {
        require(get(workId)?.chapter(chapterId) != null) { "章节不在了" }
        val old = assist(workId)
        val next = DraftIntent(instruction, targetLength, workflowChoice)
        val intents = if (next == DraftIntent()) old.draftIntents - chapterId else old.draftIntents + (chapterId to next)
        if (intents == old.draftIntents) return old
        return updateAssist(workId) { it.copy(draftIntents = intents) }
    }

    // ---------- 文风范例的挑选缓存 ----------

    /** 挑好的文风范例（[StyleSamples.encodeCache] 的格式）；没有或读不出为空。不进备份：丢了就重挑。 */
    @Synchronized
    fun sampleCache(workId: String): String? = File(dir(workId), SAMPLES).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    @Synchronized
    fun saveSampleCache(workId: String, raw: String) {
        val d = dir(workId)
        if (!File(d, WORK).isFile) return
        DurableFiles.write(File(d, SAMPLES), raw.toByteArray())
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
        update(workId) { it }
    }

    /**
     * 设定包里的文档，按优先级排好。第一次打开时，把旧版“设定集”里的条目按类别并成几份文档
     * （人物 → 人物档案，世界观 → 硬性设定，大纲 → 总纲，伏笔和其他 → 资料）。
     */
    @Synchronized
    fun pack(workId: String): List<PackDoc> {
        synchronized(processLock) {
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
    }

    private fun List<PackDoc>.sorted() = sortedWith(compareBy({ it.priority }, { it.kind.ordinal }, { it.title }))

    @Synchronized
    fun packText(workId: String, docId: String): String = packFile(workId, docId).takeIf { it.isFile }?.readText().orEmpty()

    /** 新建一份文档。 */
    @Synchronized
    fun addPackDoc(workId: String, kind: PackKind, title: String, text: String = "", priority: Int = kind.priority, always: Boolean = kind.always): PackDoc {
        synchronized(processLock) {
        require(get(workId) != null) { "作品不在了" }
        val doc = PackDoc(newId(), kind, title.trim().ifEmpty { kind.label }, priority.coerceIn(1, 9), always, clock(), WordCount.of(text))
        check(DurableFiles.write(packFile(workId, doc.id).also { it.parentFile?.mkdirs() }, text.toByteArray())) { "无法保存设定" }
        writePack(workId, pack(workId) + doc)
        return doc
        }
    }

    /** 改文档的类型、标题、优先级、常用与否。 */
    @Synchronized
    fun updatePackDoc(workId: String, docId: String, transform: (PackDoc) -> PackDoc): PackDoc? {
        synchronized(processLock) {
        val docs = pack(workId)
        val old = docs.firstOrNull { it.id == docId } ?: return null
        val next = transform(old).let { it.copy(id = old.id, priority = it.priority.coerceIn(1, 9), title = it.title.trim().ifEmpty { old.title }) }
        if (next == old) return old
        val stamped = next.copy(updatedAt = maxOf(clock(), old.updatedAt + 1, next.updatedAt))
        writePack(workId, docs.map { if (it.id == docId) stamped else it })
        return stamped
        }
    }

    /** 存文档正文。没变就不写。 */
    @Synchronized
    fun savePackText(workId: String, docId: String, text: String): PackDoc? = synchronized(processLock) { savePackTextLocked(workId, docId, text) }

    private fun savePackTextLocked(workId: String, docId: String, text: String): PackDoc? {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能覆盖设定" }
        val file = packFile(workId, docId)
        if (file.isFile && file.readText() == text) {
            val doc = pack(workId).firstOrNull { it.id == docId }
            if (doc == null || doc.words == WordCount.of(text)) return doc
        }
        if (pack(workId).none { it.id == docId }) return null
        beginSave(workId, "pack", docId, text.toByteArray())
        check(DurableFiles.write(file, text.toByteArray())) { "无法保存设定" }
        val saved = updatePackDoc(workId, docId) { it.copy(words = WordCount.of(text), updatedAt = maxOf(clock(), it.updatedAt + 1)) }
        completeSave(workId)
        return saved
    }

    /** 删一份文档：正文先存进历史版本。 */
    @Synchronized
    fun deletePackDoc(workId: String, docId: String) = synchronized(processLock) { deletePackDocLocked(workId, docId) }

    private fun deletePackDocLocked(workId: String, docId: String) {
        recoverPendingSave(workId)
        check(!pendingSave(workId).exists()) { "上次写作保存未能修复，暂不能删除设定文档" }
        val text = packText(workId, docId)
        if (text.isNotBlank()) snapshot(workId, docKey(docId), text)
        writePack(workId, pack(workId).filterNot { it.id == docId })
        packFile(workId, docId).delete()
    }

    /** 换回文档的某个历史版本（现在的先存一版）。 */
    @Synchronized
    fun restorePackDoc(workId: String, docId: String, at: Long): PackDoc? {
        synchronized(processLock) {
        val text = snapshotText(workId, docKey(docId), at) ?: return null
        snapshot(workId, docKey(docId), packText(workId, docId))
        return savePackText(workId, docId, text)
        }
    }

    /**
     * 导入：设定类的文件，同名（标题相同）的替换（旧版存进历史），没有的新加；正文类的拆成章节，接在最后一卷后面，
     * 不算进今天的码字数。同一次导入里新加的文档和章不参与同名匹配，导入项之间重名的加编号（见 [PackImport.docTitles]）。
     */
    @Synchronized
    fun importPack(workId: String, items: List<PackImportItem>): PackImportResult {
        require(items.none { it.include && it.text.isBlank() }) { "空白的 Markdown/TXT 文件不能导入或覆盖现有资料" }
        synchronized(processLock) {
        var added = 0
        var replaced = 0
        var chapters = 0
        val renamed = mutableListOf<String>()
        // 新建作品自带的空白“第1章”：导入正文时用不上，删掉；它也不参与同名匹配（不然同名的第1章会写进它再被删掉）。
        val blank = get(workId)?.chapters?.singleOrNull()?.takeIf { it.words == 0 && it.title == "第1章" && text(workId, it.id).isBlank() }
        val included = items.filter { it.include }
        // 正文文件按去掉文件夹的文件名里的数字排（“第6-10章”在“第11-15章”前面），设定文档保持原来的顺序。
        val manuscripts = included.filter { it.asChapters }.sortedWith(
            compareBy<PackImportItem, String>(BookParser.NaturalOrder) { it.fileName.substringAfterLast('/') }
                .thenBy(BookParser.NaturalOrder) { it.fileName },
        )
        val createdChapters = HashSet<String>()
        val chapterTitles = HashSet<String>()
        manuscripts.forEach { item ->
            val fileName = item.fileName.substringAfterLast('/')
            PackImport.chapters(item.text, item.title, markdown = item.fileName.endsWith(".md", true) ||
                item.fileName.endsWith(".markdown", true)).forEach { (heading, body) ->
                val work = get(workId) ?: return@forEach
                // 两卷都从“第1章”编号这类：后一章加编号另起一章，不把本次前面导入的那章盖掉。
                val title = if (heading in chapterTitles) PackImport.uniqueTitle(heading, chapterTitles + work.chapters.map { it.title }) else heading
                chapterTitles += title
                if (title != heading) renamed += title
                // 同名的章再导一次：换成新稿（旧稿进历史），不重复加。
                val same = if (title != heading) null
                else work.chapters.firstOrNull { it.title == title && it.id != blank?.id && it.id !in createdChapters }
                val c = same ?: addChapter(workId, work.volumes.last().id, title)?.second?.also { createdChapters += it.id } ?: return@forEach
                if (same != null) {
                    val old = text(workId, c.id)
                    if (old == body) return@forEach
                    if (old.isNotBlank()) snapshot(workId, c.id, old)
                    // 定稿的章换稿：先解锁，变更登记里记一笔。
                    if (same.locked) setStatus(workId, c.id, ChapterStatus.REVISION, "重新导入正文：$fileName")
                }
                saveText(workId, c.id, body, count = false)
                // 文件名（不看文件夹）写着“已定”“定稿”的正文，导进来就是定稿；“未定稿”“待定稿”不算。
                if (FINAL_NAME.containsMatchIn(fileName)) setStatus(workId, c.id, ChapterStatus.FINAL, "导入已定正文")
                chapters++
            }
        }
        val docs = included.filterNot { it.asChapters }
        val titles = PackImport.docTitles(docs, pack(workId).map { it.title }.toSet())
        val createdDocs = HashSet<String>()
        docs.forEachIndexed { i, item ->
            val title = titles[i]
            if (title != item.title) renamed += title
            val existing = if (title != item.title) null else pack(workId).firstOrNull { it.title == title && it.id !in createdDocs }
            if (existing != null) {
                val old = packText(workId, existing.id)
                if (old.isNotBlank() && old != item.text) snapshot(workId, docKey(existing.id), old)
                savePackText(workId, existing.id, item.text)
                updatePackDoc(workId, existing.id) { it.copy(kind = item.kind, priority = item.priority, always = item.always) }
                replaced++
            } else {
                createdDocs += addPackDoc(workId, item.kind, title, item.text, item.priority, item.always).id
                added++
            }
        }
        // 删之前再看一眼：空白章确实还空着才删。
        val stillBlank = blank?.let { b -> get(workId)?.chapter(b.id)?.let { it.words == 0 && text(workId, it.id).isBlank() } } == true
        if (blank != null && stillBlank && chapters > 0 && (get(workId)?.chapters?.size ?: 0) > 1) deleteChapter(workId, blank.id)
        return PackImportResult(added, replaced, chapters, renamed)
        }
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

    private fun strictStats(): WritingStats {
        if (!statsFile.isFile) return WritingStats()
        val raw = JSONObject(statsFile.readText())
        val rawDays = if (raw.has("days")) raw.getJSONObject("days") else JSONObject()
        val days = rawDays.keys().asSequence().associate { key ->
            val value = rawDays.checkedInteger(key, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "码字统计损坏")
            LocalDate.parse(key) to value!!.toInt()
        }
        val goal = raw.checkedInteger("goal", 0, Int.MAX_VALUE.toLong(), "码字目标损坏") ?: 0
        return WritingStats(days, goal.toInt())
    }

    /** Check actual JSON number types and ranges; optInt/optLong silently default or truncate. */
    private fun JSONObject.checkedInteger(key: String, min: Long, max: Long, error: String): Long? {
        if (!has(key)) return null
        val value = (opt(key) as? Number)?.toString()?.toLongOrNull()
        require(value != null && value in min..max) { error }
        return value
    }

    /**
     * 改统计前严格读一次。文件读不出时先另存一份（[DurableFiles.keepDamaged]；已有的副本内容不同就另起一个名字），
     * 存下了返回 null，由调用方跳过计数或从空的重新记；连副本都存不下就报错，绝不覆盖唯一的一份。
     */
    private fun statsOrDamaged(): WritingStats? = try {
        strictStats()
    } catch (e: Exception) {
        DurableFiles.keepDamaged(statsFile)
        val bytes = statsFile.readBytes()
        val copies = root.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(statsFile.name + ".damaged") }
        if (copies.none { it.readBytes().contentEquals(bytes) }) {
            val extra = File(root, statsFile.name + ".damaged-" + clock())
            check(runCatching { statsFile.copyTo(extra) }.isSuccess) { "码字统计损坏，且无法另存一份" }
        }
        null
    }

    /** 记净增的字数。统计文件坏了时跳过（坏文件已另存一份）。 */
    @Synchronized
    fun addWords(day: LocalDate, delta: Int) {
        if (delta == 0) return
        val s = statsOrDamaged() ?: return
        saveStats(s.copy(days = s.days + (day to s.on(day) + delta)))
    }

    /** 设每天的目标。统计文件坏了时（坏文件已另存一份）从空的统计重新记，作者设目标就是一条修好的路。 */
    @Synchronized
    fun setGoal(goal: Int) = saveStats((statsOrDamaged() ?: WritingStats()).copy(goal = goal.coerceAtLeast(0)))

    // ---------- 备份 ----------

    /** 所有作品（含正文，不含历史版本）和码字统计打成一个 JSON，放进灵阅的备份。没有作品时为空。读不出的文件见 [backupReport]。 */
    @Synchronized
    fun backup(): ByteArray? = backupReport()?.bytes

    /**
     * 同 [backup]，另外报告没能放进去的文件。某个文件读不出时不再让整份备份失败：
     * - 作品信息（work.json）读不出：这部作品整部不放；
     * - 细纲卡读不出：这张卡不放；变更登记、AI 设置、设定包索引读不出：这一项不放（恢复时保留那台手机上的）；
     * - 码字统计读不出：统计不放；
     * - 章节正文、设定文档的文件不见了：放空的，也报告出来。
     * 读不出的文件都先另存一份（.damaged）。
     */
    @Synchronized
    fun backupReport(): WritingBackup? = synchronized(processLock) { backupReportLocked() }

    private fun backupReportLocked(): WritingBackup? {
        works.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }.forEach { work ->
            runCatching { recoverPendingSave(work.name) }
        }
        val dirs = works.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }
        if (dirs.isEmpty() && !statsFile.isFile) return null
        val skipped = mutableListOf<WritingSkip>()
        // list() is deliberately tolerant for the shelf UI. A backup must not silently
        // omit a work whose metadata failed to parse: it is reported to the caller instead.
        val all = dirs.mapNotNull { d ->
            if (pendingSave(d.name).exists()) {
                val title = runCatching { read(d.name).title }.getOrDefault("作品 ${d.name.take(8)}")
                skipped += WritingSkip(title, PENDING_SAVE, "上次写作保存尚未修复，整部作品没有放进备份")
                return@mapNotNull null
            }
            try {
                read(d.name)
            } catch (e: Exception) {
                // 读不出也尽量认出书名，好让作者知道是哪一部。
                val raw = runCatching { File(d, WORK).readText() }.getOrDefault("")
                val title = Regex(""""title"\s*:\s*"([^"\\]+)"""").find(raw)?.groupValues?.get(1) ?: "作品 ${d.name.take(8)}"
                skipped += WritingSkip(title, WORK, "作品信息读不出，整部作品没有放进备份")
                null
            }
        }
        val array = JSONArray()
        all.forEach { w ->
            fun skip(file: String, reason: String) { skipped += WritingSkip(w.title, file, reason) }
            /** 读一个附加文件；读不出就另存一份、记下来，返回 null。 */
            fun <T> component(file: File, reason: String, parse: (String) -> T): T? = try {
                parse(file.readText())
            } catch (e: Exception) {
                DurableFiles.keepDamaged(file)
                skip(file.relativeTo(dir(w.id)).path, reason)
                null
            }
            val index = File(packDir(w.id), PACK_INDEX)
            val packReadable = !index.isFile || component(index, "设定包索引读不出，设定包没有放进备份") { raw ->
                val docs = JSONArray(raw)
                (0 until docs.length()).forEach { i -> requireNotNull(docs.getJSONObject(i).toPackDoc()) { "设定包索引损坏" } }
            } != null
            val texts = JSONObject()
            val drafts = JSONObject()
            val draftSources = JSONObject()
            val draftRevisions = JSONObject()
            w.chapters.forEach { c ->
                val source = chapterFile(w.id, c.id)
                if (!source.isFile && c.words > 0) skip(source.name, "「${c.title}」的正文文件不见了，备份里这一章是空的")
                texts.put(c.id, text(w.id, c.id))
                val draftSource = draftFile(w.id, c.id)
                // 空字符串明确表示没有草稿（包括作者点了“不要这版”）；读不出的
                // 草稿不放这个键，恢复时保留另一台手机上的原稿。
                val draft = if (draftSource.isFile) component(draftSource, "「${c.title}」的 AI 草稿读不出，没有放进备份") { it } else ""
                val revisionFile = draftRevisionFile(w.id, c.id)
                val hadRevision = revisionFile.exists()
                val revision = if (draft != null && hadRevision)
                    component(revisionFile, "「${c.title}」的创作初稿快照损坏，草稿及初稿均未放进备份") { raw ->
                        DraftRevisionCodec.decode(raw, draft)
                        JSONObject(raw)
                    } else null
                val sourceFile = draftReferenceFile(w.id, c.id)
                val provenance = if (draft != null && draft.isNotEmpty() && sourceFile.isFile) {
                    component(sourceFile, "「${c.title}」的范本来源记录损坏，已保留候选稿但恢复后禁止采纳") { raw ->
                        DraftReferenceCodec.decode(raw, draft)
                        JSONObject(raw)
                    }
                } else null
                if (draft != null && (!hadRevision || revision != null)) {
                    if (draft.isNotEmpty() && !sourceFile.isFile)
                        skip(sourceFile.name, "「${c.title}」的草稿来源记录缺失，已保留候选稿但恢复后禁止采纳")
                    // Keep readable prose while explicitly blocking adoption when the marker was lost or damaged.
                    val safeProvenance = if (draft.isNotEmpty())
                        provenance ?: DraftReferenceCodec.encode(draft, null, blocked = true)
                    else JSONObject.NULL
                    drafts.put(c.id, draft)
                    draftSources.put(c.id, safeProvenance)
                    draftRevisions.put(c.id, revision ?: JSONObject.NULL)
                }
            }
            val pack = if (!packReadable) null else JSONArray().apply { pack(w.id).forEach { d ->
                val source = packFile(w.id, d.id)
                if (!source.isFile && d.words > 0) skip("$PACK/${source.name}", "设定文档「${d.title}」的文件不见了，备份里是空的")
                put(d.toJson().put("text", packText(w.id, d.id)))
            } }
            val cards = JSONObject().apply { w.chapters.forEach { c ->
                val source = cardFile(w.id, c.id)
                val card = if (source.isFile) component(source, "「${c.title}」的细纲卡读不出，没有放进备份") { JSONObject(it).toCard() } else ChapterCard()
                card?.takeIf { !it.isEmpty || it.summary.isNotBlank() || it.plan.isNotBlank() || it.auto != 0 || it.beatsDone.isNotEmpty() }
                    ?.let { put(c.id, it.toJson()) }
            } }
            val changesSource = File(dir(w.id), changesFile)
            val changes = if (changesSource.isFile) component(changesSource, "变更登记读不出，没有放进备份") { text ->
                JSONArray(text).also { raw -> (0 until raw.length()).forEach { i -> requireNotNull(raw.getJSONObject(i).toChange()) { "变更登记损坏" } } }
            } else JSONArray()
            val assistSource = File(dir(w.id), ASSIST)
            val assist = if (assistSource.isFile) component(assistSource, "AI 设置和设定集读不出，没有放进备份") { JSONObject(it).toAssist() } else WorkAssist()
            // pack() may migrate an old settings bundle and touch the work timestamp.
            // 读不出的项不放这个键：恢复时没有这个键就保留那台手机上原来的。
            val entry = read(w.id).toJson().put("texts", texts).put("cards", cards).put("drafts", drafts)
                .put("draftSources", draftSources).put("draftRevisions", draftRevisions)
            assist?.let { entry.put("assist", it.toJson()) }
            pack?.let { entry.put("pack", it) }
            changes?.let { entry.put("changes", it) }
            val ledgerSource = File(dir(w.id), LEDGER)
            if (ledgerSource.isFile) component(ledgerSource, "故事账本读不出，没有放进备份") { StoryLedger.parse(it) }
                ?.let { entry.put("ledger", StoryLedger.toJson(it).getJSONArray("entries")) }
            array.put(entry)
        }
        val backup = JSONObject().put("format", 1).put("works", array)
        val stats = try {
            strictStats()
        } catch (e: Exception) {
            DurableFiles.keepDamaged(statsFile)
            skipped += WritingSkip("", statsFile.name, "码字统计读不出，没有放进备份")
            null
        }
        stats?.let { value ->
            val days = JSONObject()
            value.days.forEach { (d, n) -> days.put(d.toString(), n) }
            backup.put("goal", value.goal).put("days", days)
        }
        // 外层备份把 writing.json 当作最多 64 MiB 的 JSON 读入。先给作者正文等原有内容
        // 留足空间，再按整份备份 24 MiB 的预算收范本；大范本逐部报告，不产生无法恢复的备份。
        val originalBytes = backup.toString().toByteArray().size.toLong()
        check(originalBytes <= WritingReferenceBackup.MAX_WRITING_JSON_BYTES) { "写作数据超过 64 MB，无法生成可恢复的备份" }
        var referenceBudget = minOf(WritingReferences.MAX_BACKUP_BYTES,
            (WritingReferenceBackup.MAX_WRITING_JSON_BYTES - originalBytes - 1024 * 1024).coerceAtLeast(0))
        all.forEachIndexed { i, work ->
            try {
                val snapshot = WritingReferenceBackup.snapshot(File(dir(work.id), WritingReferences.DIRECTORY), referenceBudget,
                    work.chapters.map { it.id }.toSet()) { title, reason ->
                    skipped += WritingSkip(work.title, "${WritingReferences.DIRECTORY}/$title", reason)
                }
                if (snapshot != null) {
                    val bytes = snapshot.toString().toByteArray().size + 32L
                    if (bytes <= referenceBudget) {
                        array.getJSONObject(i).put("references", snapshot)
                        referenceBudget -= bytes
                    }
                }
            } catch (failure: Exception) {
                skipped += WritingSkip(work.title, WritingReferences.DIRECTORY, "范本或分析索引读不出，没有放进备份：${failure.message.orEmpty()}")
            }
        }
        val bytes = backup.toString().toByteArray()
        check(bytes.size.toLong() <= WritingReferenceBackup.MAX_WRITING_JSON_BYTES) { "写作数据超过 64 MB，无法生成可恢复的备份" }
        return WritingBackup(bytes, skipped)
    }

    private data class RestoreWork(
        val incoming: Work,
        val local: Work?,
        val texts: Map<String, String>,
        val drafts: Map<String, String>?,
        val draftSources: Map<String, String?>?,
        val draftRevisions: Map<String, String?>?,
        val cards: Map<String, ChapterCard>?,
        val changes: List<ChangeEntry>?,
        val ledger: List<LedgerEntry>?,
        val pack: List<Pair<PackDoc, String>>?,
        val localPack: List<PackDoc>,
        val assist: WorkAssist?,
        val references: ReferenceArchive?,
    )

    /**
     * Reject malformed archive components before changing any live work.
     * 返回 null 表示这部不恢复：本机的较新、或本机的文件读不出时，记进 [skipped] 告诉作者。
     */
    private fun parseRestoreWork(o: JSONObject, skipped: MutableList<WritingSkip>): RestoreWork? {
        val incoming = o.toWork()
        require(ID.matches(incoming.id)) { "备份作品 id 无效" }
        val volumeIds = incoming.volumes.map { it.id }
        val chapterIds = incoming.chapters.map { it.id }
        require(volumeIds.all(ID::matches) && volumeIds.size == volumeIds.toSet().size) { "备份卷 id 无效或重复" }
        require(chapterIds.all(ID::matches) && chapterIds.size == chapterIds.toSet().size) { "备份章节 id 无效或重复" }
        require(incoming.chapters.all { it.words >= 0 }) { "备份章节字数无效" }
        val textObject = o.getJSONObject("texts")
        val texts = chapterIds.associateWith { id ->
            require(textObject.has(id) && textObject.opt(id) is String) { "备份章节正文缺失或损坏" }
            textObject.getString(id)
        }
        val drafts = if (o.has("drafts")) {
            val raw = o.getJSONObject("drafts")
            raw.keys().asSequence().associateWith { id ->
                require(id in chapterIds && raw.opt(id) is String) { "备份 AI 草稿缺失或损坏" }
                raw.getString(id)
            }
        } else null
        val draftSources = if (o.has("draftSources") && drafts != null) {
            val raw = o.getJSONObject("draftSources")
            require(raw.length() == drafts.size) { "备份草稿来源数量不符" }
            raw.keys().asSequence().associateWith { id ->
                require(id in drafts) { "备份草稿来源指向未知章节" }
                val value = raw.get(id)
                if (value == JSONObject.NULL) null else {
                    require(value is JSONObject && drafts.getValue(id).isNotEmpty()) { "备份草稿来源格式无效" }
                    DraftReferenceCodec.decode(value.toString(), drafts.getValue(id))
                    value.toString()
                }
            }
        } else null // Backups made before this feature, or without a drafts component, preserve local drafts.
        val draftRevisions = if (o.has("draftRevisions") && drafts != null) {
            val raw = o.getJSONObject("draftRevisions")
            require(raw.length() == drafts.size) { "备份草稿版本数量不符" }
            raw.keys().asSequence().associateWith { id ->
                require(id in drafts) { "备份草稿版本指向未知章节" }
                val value = raw.get(id)
                if (value == JSONObject.NULL) null else {
                    require(value is JSONObject && drafts.getValue(id).isNotEmpty()) { "备份草稿版本格式无效" }
                    DraftRevisionCodec.decode(value.toString(), drafts.getValue(id))
                    value.toString()
                }
            }
        } else null
        val cards = if (o.has("cards")) {
            val raw = o.getJSONObject("cards")
            raw.keys().asSequence().associateWith { id ->
                require(id in chapterIds) { "备份细纲卡引用未知章节" }
                val cardJson = raw.getJSONObject(id)
                listOf("facts", "boundary", "pov", "countdown", "hook", "threads", "summary", "plan", "beats").forEach { key ->
                    require(!cardJson.has(key) || cardJson.opt(key) is String) { "备份细纲卡结构损坏" }
                }
                if (cardJson.has("beatsDone")) {
                    val done = cardJson.opt("beatsDone")
                    require(done is JSONArray && (0 until done.length()).all { (done.opt(it) as? Number)?.toLong() in 1L..Beats.MAX.toLong() }) { "备份细纲卡节拍损坏" }
                }
                cardJson.checkedInteger("auto", Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "备份细纲卡指纹损坏")
                cardJson.checkedInteger("updatedAt", 0, Long.MAX_VALUE, "备份细纲卡时间损坏")
                cardJson.toCard()
            }
        } else null

        val pack = if (o.has("pack")) {
            val raw = o.getJSONArray("pack")
            val docs = (0 until raw.length()).map { index ->
                val item = raw.getJSONObject(index)
                item.checkedInteger("priority", 0, 9, "备份设定优先级损坏")
                require(!item.has("always") || item.opt("always") is Boolean) { "备份设定常用标记损坏" }
                item.checkedInteger("updatedAt", 0, Long.MAX_VALUE, "备份设定时间损坏")
                item.checkedInteger("words", 0, Int.MAX_VALUE.toLong(), "备份设定字数损坏")
                val doc = requireNotNull(item.toPackDoc()) { "备份设定文档无效" }
                require(ID.matches(doc.id) && doc.words >= 0 && item.opt("text") is String) { "备份设定正文缺失或损坏" }
                require(runCatching { PackKind.valueOf(item.getString("kind")) }.isSuccess) { "备份设定类型无效" }
                doc to item.getString("text")
            }
            require(docs.map { it.first.id }.distinct().size == docs.size) { "备份设定文档 id 重复" }
            docs
        } else null

        val changes = if (o.has("changes")) {
            val raw = o.getJSONArray("changes")
            (0 until raw.length()).map { index ->
                requireNotNull(raw.getJSONObject(index).toChange()) { "备份变更记录无效" }
            }
        } else null
        val ledger = if (o.has("ledger")) {
            val raw = o.getJSONArray("ledger")
            (0 until raw.length()).map { index ->
                requireNotNull(LedgerEntry.fromJson(raw.getJSONObject(index))) { "备份故事账本无效" }
            }
        } else null
        val assist = if (o.has("assist")) o.getJSONObject("assist").toAssist() else null
        val references = if (o.has("references")) WritingReferenceBackup.parse(o.getJSONObject("references"), chapterIds.toSet()) { title, reason ->
            skipped += WritingSkip(incoming.title, "${WritingReferences.DIRECTORY}/$title", reason)
        } else null

        val localDir = dir(incoming.id)
        if (localDir.exists()) require(localDir.isDirectory) { "本机作品路径无效" }
        if (pendingSave(incoming.id).exists()) {
            skipped += WritingSkip(incoming.title, "", "本机有中断后保留的写作内容；为避免覆盖，已跳过这部作品")
            return null
        }
        // 中断后修复过的作品：正文、设定文档、账本恢复前照常存进历史版本，候选稿和备份不同的保留本机的；
        // 恢复成功后这个标记随旧作品目录一起换掉，不会让这部作品以后每次恢复都被跳过。
        val recovered = recoveredSave(incoming.id).exists()
        // Do not use get(): a damaged local work must never be mistaken for an absent one.
        // 本机的作品信息或设定索引读不出：这一部原样不动（坏文件另存一份），报告出来，不让整份恢复失败。
        fun <T> readLocal(file: File, parse: (String) -> T): T? = try {
            parse(file.readText())
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            skipped += WritingSkip(incoming.title, file.relativeTo(localDir).path, "本机的这个文件读不出，这部作品没有用备份覆盖")
            null
        }
        val local = if (localDir.exists()) readLocal(File(localDir, WORK)) { JSONObject(it).toWork() } ?: return null else null
        val localIndex = File(File(localDir, PACK), PACK_INDEX)
        val localPack = if (local == null || !localIndex.isFile) emptyList() else readLocal(localIndex) { text ->
            val raw = JSONArray(text)
            (0 until raw.length()).map { indexAt ->
                requireNotNull(raw.getJSONObject(indexAt).toPackDoc()) { "本机设定索引损坏" }
            }
        } ?: return null

        // Old app versions did not touch Work.updatedAt for pack/card edits. A backup
        // cannot replace a component whose own timestamp does not prove it newer.
        if (local != null && local.updatedAt >= incoming.updatedAt) {
            // 时间一样是同一版，不用提；本机的更新才告诉作者。
            if (local.updatedAt > incoming.updatedAt) skipped += WritingSkip(local.title, "", "本机的比备份里的新，保留本机的")
            return null
        }
        val removedDraftChapter = local?.chapters?.firstOrNull { chapter ->
            if (incoming.chapter(chapter.id) != null) false else {
                val revision = draftRevisionFile(incoming.id, chapter.id)
                val draft = draftFile(incoming.id, chapter.id)
                revision.exists() || draft.exists() &&
                    (!draft.isFile || runCatching { draft.readText().isNotBlank() }.getOrDefault(true))
            }
        }
        if (removedDraftChapter != null) {
            skipped += WritingSkip(incoming.title, removedDraftChapter.id + ".draft.txt",
                "备份删除的章节仍有本机候选稿或创作初稿；整部作品暂不恢复")
            return null
        }
        if (local != null) {
            val incomingDocs = pack?.associate { it.first.id to it.first }
            if (incomingDocs != null) {
                val hasUnresolvedDoc = localPack.any { current ->
                    val replacement = incomingDocs[current.id]
                    if (replacement == null) current.updatedAt == 0L || current.updatedAt >= incoming.updatedAt
                    else {
                        val currentText = File(File(localDir, PACK), current.id + ".md").takeIf { it.isFile }?.readText().orEmpty()
                        val incomingText = pack.first { it.first.id == current.id }.second
                        (current != replacement || currentText != incomingText) && replacement.updatedAt <= current.updatedAt
                    }
                }
                if (hasUnresolvedDoc) {
                    skipped += WritingSkip(local.title, "", "本机有设定文档比备份里的新，保留本机的")
                    return null
                }
            }
            if (cards != null) {
                val hasUnresolvedCard = cards.any { (chapterId, replacement) ->
                    val source = File(localDir, chapterId + ".card.json")
                    if (!source.isFile) false else {
                        // 本机的卡读不出：另存一份，用备份里的。
                        val current = runCatching { JSONObject(source.readText()).toCard() }
                            .onFailure { DurableFiles.keepDamaged(source) }.getOrNull() ?: return@any false
                        current != replacement && replacement.updatedAt <= current.updatedAt
                    }
                }
                if (hasUnresolvedCard) {
                    skipped += WritingSkip(local.title, "", "本机有细纲卡比备份里的新，保留本机的")
                    return null
                }
            }
        }
        // The restore history does not archive AI candidate versions. Keep a local pair if
        // either side of an incoming pair differs. A damaged local snapshot must be backed up
        // before other components of this work can be restored over it.
        val omittedDrafts = mutableSetOf<String>()
        if (local != null && drafts != null) drafts.forEach { (id, incomingBody) ->
            val revision = draftRevisionFile(incoming.id, id)
            if (!revision.exists()) return@forEach
            if (!revision.isFile) {
                skipped += WritingSkip(incoming.title, revision.name, "本机创作初稿路径无效，无法留底；整部作品暂不恢复")
                return null
            }
            val localBytes = runCatching { revision.readBytes() }.getOrNull()
            val localBody = runCatching { draftFile(incoming.id, id).readText() }.getOrNull()
            if (localBytes == null || localBody == null) {
                skipped += WritingSkip(incoming.title, revision.name, "本机草稿或初稿读不出，无法留底；整部作品暂不恢复")
                return null
            }
            val localSnapshot = runCatching { DraftRevisionCodec.decode(localBytes.toString(Charsets.UTF_8), localBody) }.getOrNull()
            if (localSnapshot == null) {
                if (!DurableFiles.keptDamaged(revision, localBytes)) {
                    skipped += WritingSkip(incoming.title, revision.name, "损坏的本机创作初稿无法安全留底；整部作品暂不恢复")
                    return null
                }
                omittedDrafts += id
                skipped += WritingSkip(incoming.title, revision.name, "本机创作初稿损坏，已留底并保留本机草稿与初稿")
                return@forEach
            }
            val incomingSnapshot = draftRevisions?.get(id)?.let { DraftRevisionCodec.decode(it, incomingBody) }
            if (incomingBody != localBody || incomingSnapshot != null && incomingSnapshot != localSnapshot) {
                omittedDrafts += id
                skipped += WritingSkip(incoming.title, "$id.draft.txt", "备份候选稿与本机不同，且本机有创作初稿；保留本机草稿与初稿")
            }
        }
        // 候选稿不进历史版本：中断后修复的作品，本机候选稿和备份不同就保留本机的。
        if (recovered && local != null && drafts != null) drafts.forEach { (id, incomingBody) ->
            val file = draftFile(incoming.id, id)
            if (id in omittedDrafts || !file.exists()) return@forEach
            val localBody = runCatching { file.readText() }.getOrNull()
            if (localBody == null) {
                skipped += WritingSkip(incoming.title, "$id.draft.txt", "本机中断后修复的候选稿读不出，无法留底；整部作品暂不恢复")
                return null
            }
            if (localBody != incomingBody) {
                omittedDrafts += id
                skipped += WritingSkip(incoming.title, "$id.draft.txt", "本机候选稿是中断后修复的，和备份里的不同；保留本机草稿")
            }
        }
        return RestoreWork(incoming, local, texts, drafts?.filterKeys { it !in omittedDrafts },
            draftSources?.filterKeys { it !in omittedDrafts }, draftRevisions?.filterKeys { it !in omittedDrafts }, cards, changes, ledger,
            pack, localPack, assist, references)
    }

    /** Validate writing data before the outer app restore replaces any books. */
    @Synchronized
    fun validateBackup(json: String) {
        require(json.toByteArray().size.toLong() <= WritingReferenceBackup.MAX_WRITING_JSON_BYTES) { "备份写作数据超过 64 MB" }
        preflightRestoreBackup(JSONObject(json))
    }

    private class RestorePreflight(val plans: List<RestoreWork>, val days: Map<LocalDate, Int>?, val skipped: List<WritingSkip>)

    private fun preflightRestoreBackup(backup: JSONObject): RestorePreflight {
        val array = if (backup.has("works")) backup.getJSONArray("works") else JSONArray()
        val seen = mutableSetOf<String>()
        val skipped = mutableListOf<WritingSkip>()
        var referenceBytes = 0L
        val plans = (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            val id = entry.getString("id")
            require(seen.add(id)) { "备份作品 id 重复" }
            if (entry.has("references")) {
                referenceBytes += entry.getJSONObject("references").toString().toByteArray().size
                require(referenceBytes <= WritingReferences.MAX_BACKUP_BYTES) { "备份范本总量超过安全上限" }
            }
            parseRestoreWork(entry, skipped)
        }
        val days = if (backup.has("days")) {
            val raw = backup.getJSONObject("days")
            raw.keys().asSequence().associate { key ->
                val day = LocalDate.parse(key)
                val value = raw.checkedInteger(key, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), "备份码字统计损坏")!!
                day to value.toInt()
            }
        } else null
        backup.checkedInteger("goal", 0, Int.MAX_VALUE.toLong(), "备份码字目标损坏")
        return RestorePreflight(plans, days, skipped)
    }

    private fun stageRestoreWork(plan: RestoreWork, destination: File) {
        if (plan.local != null) {
            check(dir(plan.incoming.id).copyRecursively(destination, overwrite = true)) { "无法暂存本机作品" }
        } else check(destination.mkdirs()) { "无法创建作品暂存区" }
        val incoming = plan.incoming
        plan.local?.chapters?.filter { incoming.chapter(it.id) == null }?.forEach { old ->
            listOf(old.id + ".txt", old.id + ".card.json", old.id + ".review.json", old.id + ".draft.txt",
                old.id + ".draft.reference.json", old.id + ".draft.revision.json")
                .forEach { name -> check(!File(destination, name).exists() || File(destination, name).delete()) { "无法暂存章节删除" } }
        }
        plan.texts.forEach { (id, body) ->
            check(DurableFiles.write(File(destination, id + ".txt"), body.toByteArray())) { "无法暂存章节正文" }
        }
        plan.drafts?.forEach { (id, body) ->
            val file = File(destination, id + ".draft.txt")
            val revision = File(destination, id + ".draft.revision.json")
            val keepLocalRevision = plan.draftRevisions?.get(id) == null && revision.isFile && file.isFile && file.readText() == body
            if (body.isEmpty()) check(!file.exists() || file.delete()) { "无法暂存草稿删除" }
            else check(DurableFiles.write(file, body.toByteArray())) { "无法暂存 AI 草稿" }
            val provenance = File(destination, id + ".draft.reference.json")
            val source = plan.draftSources?.get(id)
            if (source == null) check(!provenance.exists() || provenance.delete()) { "无法暂存草稿来源删除" }
            else check(DurableFiles.write(provenance, source.toByteArray())) { "无法暂存草稿来源" }
            val snapshot = plan.draftRevisions?.get(id)
            when {
                snapshot != null -> check(DurableFiles.write(revision, snapshot.toByteArray())) { "无法暂存创作初稿" }
                keepLocalRevision -> Unit
                else -> check(!revision.exists() || revision.delete()) { "无法暂存创作初稿删除" }
            }
        }
        plan.cards?.forEach { (id, card) ->
            check(DurableFiles.write(File(destination, id + ".card.json"), card.toJson().toString().toByteArray())) { "无法暂存细纲卡" }
        }
        plan.changes?.let { entries ->
            val value = JSONArray().apply { entries.forEach { put(it.toJson()) } }
            check(DurableFiles.write(File(destination, changesFile), value.toString().toByteArray())) { "无法暂存变更登记" }
        }
        plan.ledger?.let { entries ->
            val target = File(destination, LEDGER)
            // 本机账本和备份里的不同：先存进账本的历史版本（照正文、设定文档恢复前留底），恢复后在“历史版本”里找得回来。
            // 账本历史在作品目录里，要写进暂存的这一份，才会随恢复一起换上去。
            if (target.isFile) {
                val raw = target.readBytes()
                val local = runCatching { StoryLedger.parse(raw.toString(Charsets.UTF_8)) }.getOrNull()
                if (local == null) check(DurableFiles.keptDamaged(target, raw)) { "本机故事账本损坏，且无法保存原件" }
                else if (local != entries) archiveLedger(File(destination, LEDGER_HISTORY), local, "从备份恢复")
            }
            check(DurableFiles.write(target, StoryLedger.toJson(entries).toString().toByteArray())) { "无法暂存故事账本" }
        }
        plan.pack?.let { docs ->
            val folder = File(destination, PACK)
            check(!folder.exists() || folder.deleteRecursively()) { "无法暂存设定包" }
            check(folder.mkdirs()) { "无法创建设定包暂存区" }
            docs.forEach { (doc, body) ->
                check(DurableFiles.write(File(folder, doc.id + ".md"), body.toByteArray())) { "无法暂存设定正文" }
            }
            val index = JSONArray().apply { docs.forEach { put(it.first.toJson()) } }
            check(DurableFiles.write(File(folder, PACK_INDEX), index.toString().toByteArray())) { "无法暂存设定索引" }
        }
        plan.assist?.let {
            check(DurableFiles.write(File(destination, ASSIST), it.toJson().toString().toByteArray())) { "无法暂存设定" }
        }
        // 中断修复的标记只留在换下来的旧目录里：提交后恢复的作品不再带着它；回滚时旧目录连同标记一起换回来。
        val guard = File(destination, RECOVERED_SAVE)
        check(!guard.exists() || guard.delete()) { "无法清理中断修复标记" }
        plan.references?.let { WritingReferenceBackup.stage(it, File(destination, WritingReferences.DIRECTORY), incoming.chapters.map { chapter -> chapter.id }.toSet()) }
        val metadata = incoming.copy(previewBookId = plan.local?.previewBookId ?: incoming.previewBookId)
        check(DurableFiles.write(File(destination, WORK), metadata.toJson().toString().toByteArray())) { "无法暂存作品信息" }
    }

    private fun snapshotBeforeRestore(plan: RestoreWork) {
        plan.local?.chapters?.forEach { chapter ->
            val source = chapterFile(plan.incoming.id, chapter.id)
            source.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { snapshot(plan.incoming.id, chapter.id, it) }
        }
        plan.localPack.forEach { doc ->
            val source = packFile(plan.incoming.id, doc.id)
            source.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { snapshot(plan.incoming.id, docKey(doc.id), it) }
        }
    }

    /**
     * Validate the whole archive, stage every selected work, then swap with a rollback
     * journal. On process death the next WorkStore initialization restores all old works.
     */
    @Synchronized
    fun restoreBackup(json: String): Int = restoreBackupReport(json).restored

    /** 同 [restoreBackup]，另外报告整部没有恢复的作品（本机的较新，或本机的文件读不出）。 */
    @Synchronized
    fun restoreBackupReport(json: String): WritingRestore = synchronized(processLock) { restoreBackupReportLocked(json) }

    private fun restoreBackupReportLocked(json: String): WritingRestore {
        require(json.toByteArray().size.toLong() <= WritingReferenceBackup.MAX_WRITING_JSON_BYTES) { "备份写作数据超过 64 MB" }
        val backup = JSONObject(json)
        val preflight = preflightRestoreBackup(backup)
        val plans = preflight.plans
        val days = preflight.days
        if (plans.isEmpty() && days == null) return WritingRestore(0, preflight.skipped)
        val mergedStats = days?.let { incoming ->
            // 本机的统计读不出（已另存一份）：直接用备份里的。
            val current = statsOrDamaged() ?: WritingStats()
            val combined = current.days.toMutableMap()
            incoming.forEach { (day, count) -> combined[day] = maxOf(combined[day] ?: 0, count) }
            current.copy(days = combined, goal = if (current.goal == 0) backup.optInt("goal", 0) else current.goal)
        }

        val stage = File(root, RESTORE_PREFIX + newId())
        val prepared = File(stage, "prepared")
        val previous = File(stage, "previous")
        check(prepared.mkdirs() && previous.mkdirs()) { "无法创建写作恢复暂存区" }
        var journaled = false
        try {
            plans.forEach { plan -> stageRestoreWork(plan, File(prepared, plan.incoming.id)) }
            plans.forEach(::snapshotBeforeRestore)
            mergedStats?.let { value ->
                if (statsFile.exists()) statsFile.copyTo(File(stage, "stats.previous"))
                val rawDays = JSONObject().apply { value.days.forEach { (day, count) -> put(day.toString(), count) } }
                val raw = JSONObject().put("goal", value.goal).put("days", rawDays)
                check(DurableFiles.write(File(stage, "stats.prepared"), raw.toString().toByteArray())) { "无法暂存码字统计" }
            }
            // A durable journal is written only after all staging is complete.
            val ids = JSONArray().apply { plans.forEach { put(it.incoming.id) } }
            val existing = JSONArray().apply { plans.filter { it.local != null }.forEach { put(it.incoming.id) } }
            val record = JSONObject().put("ids", ids).put("existing", existing)
                .put("stats", mergedStats != null).put("statsExisted", statsFile.exists())
            check(DurableFiles.write(File(stage, "journal.json"), record.toString().toByteArray())) { "无法建立恢复日志" }
            journaled = true
            works.mkdirs()
            plans.forEach { plan ->
                plan.local?.let {
                    check(dir(it.id).renameTo(File(previous, it.id))) { "无法暂存恢复前的作品" }
                }
                check(File(prepared, plan.incoming.id).renameTo(dir(plan.incoming.id))) { "无法安装恢复的作品" }
                restoreSwapHook?.invoke(plan.incoming.id)
            }
            if (mergedStats != null) {
                if (statsFile.exists()) check(statsFile.delete()) { "无法替换码字统计" }
                check(File(stage, "stats.prepared").renameTo(statsFile)) { "无法安装码字统计" }
                restoreSwapHook?.invoke("stats")
            }
            check(DurableFiles.write(File(stage, "committed"), byteArrayOf(1))) { "无法完成恢复提交" }
        } catch (failure: Throwable) {
            if (journaled) {
                try { rollbackRestore(stage) } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                    throw failure
                }
            }
            if (!journaled || File(stage, "journal.json").delete()) stage.deleteRecursively()
            throw failure
        }
        if (File(stage, "journal.json").delete()) stage.deleteRecursively()
        return WritingRestore(plans.size, preflight.skipped)
    }

    companion object {
        /** Multiple WorkStore instances can share one directory (including multiwindow activities). */
        private val processLocks = ConcurrentHashMap<String, Any>()
        const val DEFAULT_VOLUME = "正文"
        const val KEEP_SNAPSHOTS = 40
        private const val WORK = "work.json"
        private const val ASSIST = "assist.json"
        /** 文风范例的挑选缓存。 */
        private const val SAMPLES = "style-samples.json"
        private const val LEDGER = "ledger.json"
        private const val LEDGER_HISTORY = "ledger-history"
        /** 账本留几个历史版本。 */
        private const val LEDGER_KEEP = 30
        private const val PACK = "pack"
        private const val PACK_INDEX = "index.json"
        private const val PENDING_SAVE = ".pending-save.json"
        private const val RECOVERED_SAVE = ".recovered-save.json"
        private const val DRAFT_PREVIOUS = ".draft-save-previous"
        private const val RESTORE_PREFIX = ".restore-writing-"
        /** 文件名写着“已定”“定稿”的正文是定稿；前面是“未”“待”“非”“不”的不算。 */
        private val FINAL_NAME = Regex("(?<![未待非不])(?:已定|定稿)")
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
            .put("summary", summary).put("plan", plan).put("auto", auto).put("updatedAt", updatedAt)
            .put("beats", beats).put("beatsDone", JSONArray(beatsDone))

        // 旧卡片没有节拍两项：读成空的。
        private fun JSONObject.toCard() =
            ChapterCard(optString("facts"), optString("boundary"), optString("pov"), optString("countdown"), optString("hook"), optString("threads"), optString("summary"), optString("plan"), optInt("auto"), optLong("updatedAt"),
                beats = optString("beats"),
                beatsDone = optJSONArray("beatsDone")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() } }.orEmpty())

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
            return PackDoc(id, kind, optString("title"), optInt("priority", kind.priority).coerceIn(0, 9), optBoolean("always", kind.always), optLong("updatedAt"), optInt("words"))
        }

        private fun WorkAssist.toJson(): JSONObject = JSONObject()
            .put("realtime", realtime).put("style", style).put("rejected", rejected)
            .put("entries", JSONArray().apply { entries.forEach { e -> put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("name", e.name).put("text", e.text)) } })
            .put("accepted", JSONArray().apply { accepted.forEach { e -> put(JSONObject().put("action", e.action).put("before", e.before).put("after", e.after).put("at", e.at)) } })
            .put("declined", JSONArray().apply { declined.forEach { e -> put(JSONObject().put("action", e.action).put("before", e.before).put("after", e.after).put("at", e.at)) } })
            .put("briefLimit", briefLimit)
            .put("knowledgeLimit", knowledgeLimit)
            .put("suggestKinds", JSONArray(suggestKinds))
            .put("suggestGap", suggestGap)
            .put("suggestCap", suggestCap)
            .put("synced", JSONObject().apply { synced.forEach { (id, m) -> put(id, JSONObject().put("hash", m.hash).put("at", m.at)) } })
            .put("syncTip", syncTip)
            .put("format", format.toJson())
            .put("sampleLimit", sampleLimit)
            .put("sampleFallback", sampleFallback)
            .put("sampleSkip", JSONArray(sampleSkip))
            .put("sampleShift", JSONObject().apply { sampleShift.forEach { (k, v) -> put(k, v) } })
            .put("draftWorkflows", JSONArray(draftWorkflows.sorted()))
            .put("draftIntents", JSONObject().apply { draftIntents.forEach { (id, intent) ->
                put(id, JSONObject().put("instruction", intent.instruction).put("targetLength", intent.targetLength).apply {
                    intent.workflowChoice?.let { put("workflowChoice", it) }
                })
            } })

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
                declined = (optJSONArray("declined") ?: JSONArray()).let { dc ->
                    (0 until dc.length()).mapNotNull { i ->
                        val e = dc.optJSONObject(i) ?: return@mapNotNull null
                        StyleExample(e.optString("action"), e.optString("before"), e.optString("after"), e.optLong("at"))
                    }
                },
                briefLimit = optInt("briefLimit", 12_000),
                knowledgeLimit = optInt("knowledgeLimit", 0),
                suggestKinds = optJSONArray("suggestKinds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: WorkAssist.SUGGEST_KINDS,
                suggestGap = optInt("suggestGap", 20),
                suggestCap = optInt("suggestCap", 50),
                synced = optJSONObject("synced")?.let { o ->
                    o.keys().asSequence().mapNotNull { id -> o.optJSONObject(id)?.let { m -> id to SyncMark(m.optInt("hash"), m.optLong("at")) } }.toMap()
                }.orEmpty(),
                syncTip = optBoolean("syncTip", true),
                format = FormatSettings.fromJson(optJSONObject("format")),
                sampleLimit = optInt("sampleLimit", StyleSamples.DEFAULT_LIMIT),
                sampleFallback = optBoolean("sampleFallback", true),
                sampleSkip = optJSONArray("sampleSkip")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
                sampleShift = optJSONObject("sampleShift")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty(),
                draftWorkflows = if (has("draftWorkflows")) getJSONArray("draftWorkflows").let { a ->
                    (0 until a.length()).map { i ->
                        require(a.get(i) is String && ID.matches(a.getString(i))) { "草稿审读状态损坏" }
                        a.getString(i)
                    }.toSet()
                } else emptySet(),
                draftIntents = if (has("draftIntents")) getJSONObject("draftIntents").let { intents ->
                    intents.keys().asSequence().associateWith { id ->
                        require(ID.matches(id)) { "草稿写作要求章节 ID 损坏" }
                        val value = intents.getJSONObject(id)
                        val instruction = value.get("instruction")
                        val targetLength = value.get("targetLength")
                        require(instruction is String && targetLength is String) { "草稿写作要求损坏" }
                        val choice = if (value.has("workflowChoice")) value.get("workflowChoice").let {
                            require(it is Boolean) { "草稿写作选项损坏" }
                            it
                        } else null
                        DraftIntent(instruction, targetLength, choice)
                    }
                } else emptyMap(),
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
