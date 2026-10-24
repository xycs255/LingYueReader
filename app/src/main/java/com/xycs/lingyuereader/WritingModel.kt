package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.PackDoc
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackImportItem
import com.xycs.lingyuereader.data.PackImportResult
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.RuleReport
import com.xycs.lingyuereader.data.RuleSet
import com.xycs.lingyuereader.data.Snapshot
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.data.WorkExport
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.WritingStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** 导出的格式。 */
enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    TXT("TXT", "txt", "text/plain"),
    EPUB("EPUB", "epub", "application/epub+zip"),
    MARKDOWN("Markdown", "md", "text/markdown"),
}

/**
 * 写作：作品列表、码字统计，以及改作品、存正文。读写盘都排成一队在后台做（先改的先落盘），
 * 改完再更新界面看的 [works]、[stats]。出错时用 [message] 告诉读者。
 */
class WritingModel(
    private val store: WorkStore,
    private val scope: CoroutineScope,
    private val message: (String) -> Unit,
) {
    private val disk = Dispatchers.IO.limitedParallelism(1)

    private val _works = MutableStateFlow<List<Work>>(emptyList())
    val works: StateFlow<List<Work>> = _works.asStateFlow()
    private val _stats = MutableStateFlow(WritingStats())
    val stats: StateFlow<WritingStats> = _stats.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 每章上次存历史版本的时间：写着写着每隔一阵存一版。 */
    private val lastSnapshot = HashMap<String, Long>()

    fun reload() {
        scope.launch {
            val (works, stats) = withContext(disk) { store.list() to store.stats() }
            _works.value = works
            _stats.value = stats
            _loaded.value = true
        }
    }

    fun work(id: String): Work? = _works.value.firstOrNull { it.id == id }

    private fun publish(work: Work?) {
        if (work == null) return
        _works.update { list -> (listOf(work) + list.filterNot { it.id == work.id }).sortedByDescending { it.updatedAt } }
    }

    /** 在写盘队列里做 [block]；做完更新作品列表。 */
    private fun change(failure: String, block: () -> Work?, then: (Work) -> Unit = {}) {
        scope.launch {
            try {
                val work = withContext(disk) { block() }
                publish(work)
                work?.let(then)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("$failure：${e.message ?: "写入出错"}")
            }
        }
    }

    fun create(title: String, author: String, then: (Work) -> Unit) = change("新建失败", { store.create(title, author) }, then)

    fun updateInfo(workId: String, title: String, author: String, synopsis: String) = change("保存失败", {
        store.update(workId) { it.copy(title = title.trim().ifEmpty { it.title }, author = author.trim(), synopsis = synopsis.trim()) }
    })

    /** 记下预览用的书（不算改动作品）。 */
    internal fun setPreviewBook(workId: String, bookId: String?) = store.update(workId, touch = false) { it.copy(previewBookId = bookId) }.also(::publish)

    fun delete(workId: String) {
        scope.launch {
            try {
                withContext(disk) { store.delete(workId) }
                _works.update { list -> list.filterNot { it.id == workId } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("删除失败：${e.message ?: "写入出错"}")
            }
        }
    }

    fun addChapter(workId: String, volumeId: String, then: (WorkChapter) -> Unit = {}) {
        var added: WorkChapter? = null
        change("加章失败", { store.addChapter(workId, volumeId)?.let { (w, c) -> added = c; w } }) { added?.let(then) }
    }

    fun addVolume(workId: String, title: String) = change("加卷失败", { store.addVolume(workId, title) })
    fun renameChapter(workId: String, chapterId: String, title: String) {
        if (title.isBlank()) return
        change("改名失败", { store.renameChapter(workId, chapterId, title) })
    }

    fun renameVolume(workId: String, volumeId: String, title: String) {
        if (title.isBlank()) return
        change("改名失败", { store.renameVolume(workId, volumeId, title) })
    }

    fun deleteChapter(workId: String, chapterId: String) = change("删除失败", { store.deleteChapter(workId, chapterId) })
    fun deleteVolume(workId: String, volumeId: String) = change("删除失败", { store.deleteVolume(workId, volumeId) })
    fun moveChapter(workId: String, chapterId: String, delta: Int) = change("移动失败", { store.moveChapter(workId, chapterId, delta) })

    // ---------- 正文 ----------

    /** 打开一章来写：先把现在的正文存一个历史版本，这次写坏了能回到开写前。 */
    suspend fun open(workId: String, chapterId: String): String = withContext(disk) {
        val text = store.text(workId, chapterId)
        if (text.isNotBlank()) runCatching { store.snapshot(workId, chapterId, text) }
        lastSnapshot[chapterId] = System.currentTimeMillis()
        text
    }

    /** 自动保存。每写 [SNAPSHOT_EVERY] 存一个历史版本；[closing] 为真（离开这一章）时也存一版。 */
    fun save(workId: String, chapterId: String, text: String, closing: Boolean = false) {
        scope.launch {
            try {
                val work = withContext(disk) {
                    val saved = store.saveText(workId, chapterId, text)
                    val now = System.currentTimeMillis()
                    if (text.isNotBlank() && (closing || now - (lastSnapshot[chapterId] ?: 0) >= SNAPSHOT_EVERY)) {
                        store.snapshot(workId, chapterId, text)
                        lastSnapshot[chapterId] = now
                    }
                    saved to store.stats()
                }
                publish(work.first)
                _stats.value = work.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
    }

    /** 等前面排着的保存都写完（预览、导出前用）。 */
    suspend fun flush() = withContext(disk) { }

    suspend fun history(workId: String, chapterId: String): List<Snapshot> = withContext(disk) { store.history(workId, chapterId) }

    suspend fun snapshotText(workId: String, chapterId: String, at: Long): String? = withContext(disk) { store.snapshotText(workId, chapterId, at) }

    fun restore(workId: String, chapterId: String, at: Long, then: () -> Unit) {
        scope.launch {
            try {
                val (work, stats) = withContext(disk) { store.restore(workId, chapterId, at) to store.stats() }
                publish(work)
                _stats.value = stats
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("恢复失败：${e.message ?: "写入出错"}")
            }
        }
    }

    fun setGoal(goal: Int) {
        scope.launch {
            _stats.value = withContext(disk) {
                store.setGoal(goal)
                store.stats()
            }
        }
    }

    // ---------- 状态、细纲卡、变更登记 ----------

    private val _changes = MutableStateFlow<Map<String, List<ChangeEntry>>>(emptyMap())
    /** 各作品的变更登记（打开过的才有），新的在前。 */
    val changes: StateFlow<Map<String, List<ChangeEntry>>> = _changes.asStateFlow()

    fun loadChanges(workId: String) {
        scope.launch {
            runCatching { withContext(disk) { store.changes(workId) } }.onSuccess { list -> _changes.update { it + (workId to list) } }
        }
    }

    private fun refreshChanges(workId: String) {
        _changes.update { it + (workId to store.changes(workId)) }
    }

    /** 改章节状态。从定稿改出去要写 [reason]；[canon] 为真表示改动涉及设定，登记里会提醒同步设定包。 */
    fun setStatus(workId: String, chapterId: String, status: ChapterStatus, reason: String = "", canon: Boolean = false, then: () -> Unit = {}) {
        scope.launch {
            try {
                val work = withContext(disk) {
                    store.setStatus(workId, chapterId, status, reason, canon).also { refreshChanges(workId) }
                }
                publish(work)
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("改状态失败：${e.message ?: "写入出错"}")
            }
        }
    }

    /** 手动登记一条设定变更（和章节无关的也行）。 */
    fun addCanonChange(workId: String, reason: String) {
        scope.launch {
            try {
                withContext(disk) {
                    store.addChange(workId, ChangeEntry(WorkStore.newId(), System.currentTimeMillis(), ChangeKind.CANON, reason = reason.trim(), canon = true))
                    refreshChanges(workId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("登记失败：${e.message ?: "写入出错"}")
            }
        }
    }

    fun markSynced(workId: String, changeId: String, synced: Boolean = true) {
        scope.launch {
            runCatching {
                withContext(disk) {
                    store.markSynced(workId, changeId, synced)
                    refreshChanges(workId)
                }
            }.onFailure { message("保存失败：${it.message ?: "写入出错"}") }
        }
    }

    /** 这一章和上一章的细纲卡（上一章的钩子和承接边界写这一章时要接上）。 */
    suspend fun cards(workId: String, chapterId: String): Pair<ChapterCard, Pair<WorkChapter, ChapterCard>?> = withContext(disk) {
        val chapters = store.get(workId)?.chapters.orEmpty()
        val i = chapters.indexOfFirst { it.id == chapterId }
        store.card(workId, chapterId) to chapters.getOrNull(i - 1)?.let { it to store.card(workId, it.id) }
    }

    fun saveCard(workId: String, chapterId: String, card: ChapterCard) {
        scope.launch {
            runCatching { withContext(disk) { store.saveCard(workId, chapterId, card) } }.onFailure { message("保存失败：${it.message ?: "写入出错"}") }
        }
    }

    // ---------- 设定包 ----------

    private val _packs = MutableStateFlow<Map<String, List<PackDoc>>>(emptyMap())
    /** 各作品的设定包（打开过的才有），按优先级排好。 */
    val packs: StateFlow<Map<String, List<PackDoc>>> = _packs.asStateFlow()

    fun loadPack(workId: String) {
        scope.launch {
            try {
                val docs = withContext(disk) { store.pack(workId) }
                _packs.update { it + (workId to docs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("读取设定包失败：${e.message ?: "读取出错"}")
            }
        }
    }

    /** 在写盘队列里改设定包，改完刷新列表。 */
    private fun changePack(workId: String, failure: String, block: () -> Unit, then: () -> Unit = {}) {
        scope.launch {
            try {
                val docs = withContext(disk) {
                    block()
                    store.pack(workId)
                }
                _packs.update { it + (workId to docs) }
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("$failure：${e.message ?: "写入出错"}")
            }
        }
    }

    fun addDoc(workId: String, kind: PackKind, title: String, then: (PackDoc) -> Unit) {
        var added: PackDoc? = null
        changePack(workId, "新建失败", { added = store.addPackDoc(workId, kind, title, PackImport.template(kind)) }) { added?.let(then) }
    }

    fun updateDoc(workId: String, docId: String, transform: (PackDoc) -> PackDoc) =
        changePack(workId, "保存失败", { store.updatePackDoc(workId, docId, transform) })

    fun deleteDoc(workId: String, docId: String) = changePack(workId, "删除失败", { store.deletePackDoc(workId, docId) })

    /** 打开一份文档来改：先存一版，改坏了能回到打开前。 */
    suspend fun openDoc(workId: String, docId: String): String = withContext(disk) {
        val text = store.packText(workId, docId)
        if (text.isNotBlank()) runCatching { store.snapshot(workId, store.docKey(docId), text) }
        lastSnapshot[store.docKey(docId)] = System.currentTimeMillis()
        text
    }

    /** 自动保存文档（不算码字数）。每写 [SNAPSHOT_EVERY] 和离开时存一版。 */
    fun saveDoc(workId: String, docId: String, text: String, closing: Boolean = false) {
        val key = store.docKey(docId)
        changePack(workId, "保存失败", {
            store.savePackText(workId, docId, text)
            val now = System.currentTimeMillis()
            if (text.isNotBlank() && (closing || now - (lastSnapshot[key] ?: 0) >= SNAPSHOT_EVERY)) {
                store.snapshot(workId, key, text)
                lastSnapshot[key] = now
            }
        })
    }

    fun docKey(docId: String) = store.docKey(docId)

    fun restoreDoc(workId: String, docId: String, at: Long, then: () -> Unit) =
        changePack(workId, "恢复失败", { store.restorePackDoc(workId, docId, at) }, then)

    /** 读选中的文件：zip 按设定包（或一包文本）读，文本文件按文件名认类型。 */
    suspend fun parseImport(files: List<Pair<String, () -> java.io.InputStream>>): List<PackImportItem> = withContext(Dispatchers.IO) {
        files.flatMap { (name, open) ->
            when {
                name.endsWith(".zip", ignoreCase = true) -> PackImport.readZip(open)
                PackImport.isText(name) -> listOf(PackImport.classify(name, com.xycs.lingyuereader.data.TextDecoder.decode(open().use { it.readBytes() })))
                else -> emptyList()
            }
        }
    }

    fun importPack(workId: String, items: List<PackImportItem>, then: (PackImportResult) -> Unit) {
        var result: PackImportResult? = null
        var work: Work? = null
        changePack(workId, "导入失败", {
            result = store.importPack(workId, items)
            work = store.get(workId)
        }) {
            // 正文拆成的章节也要刷新作品列表。
            publish(work)
            result?.let(then)
        }
    }

    suspend fun exportPack(workId: String, out: java.io.OutputStream) = withContext(disk) {
        PackImport.writeZip(store.pack(workId).map { it to store.packText(workId, it.id) }, out)
    }

    // ---------- 检查规则 ----------

    /** 设定包里各份“检查规则”文档的规则，合在一起。 */
    suspend fun rules(workId: String): RuleSet = withContext(disk) { readRules(workId) }

    private fun readRules(workId: String): RuleSet =
        store.pack(workId).filter { it.kind == PackKind.RULES }.fold(RuleSet()) { all, d -> all + CheckRules.parse(store.packText(workId, d.id), d.title) }

    /** 按规则检查一章（存盘的正文）。没有规则时返回 null。 */
    suspend fun checkChapter(workId: String, chapterId: String): RuleReport? {
        val (rules, text) = withContext(disk) { readRules(workId) to store.text(workId, chapterId) }
        if (rules.isEmpty) return null
        return withContext(Dispatchers.Default) { CheckRules.check(text, rules) }
    }

    /** 按规则检查全书（存盘的正文），按目录顺序。正文在写盘队列里读，检查放到后台算，不耽误保存。 */
    suspend fun checkAll(workId: String): List<Pair<WorkChapter, RuleReport>> {
        val (rules, texts) = withContext(disk) {
            val work = store.get(workId) ?: return@withContext RuleSet() to emptyList()
            readRules(workId) to work.chapters.map { it to store.text(workId, it.id) }
        }
        if (rules.isEmpty) return emptyList()
        return withContext(Dispatchers.Default) { texts.map { (c, t) -> c to CheckRules.check(t, rules) } }
    }

    /** 存规则（AI 整理、作者看过的）：写进第一份“检查规则”文档，没有就新建一份；原来的文字先存一版。 */
    fun saveRules(workId: String, text: String, then: (PackDoc) -> Unit = {}) {
        var saved: PackDoc? = null
        changePack(workId, "保存规则失败", {
            val doc = store.pack(workId).firstOrNull { it.kind == PackKind.RULES }
            saved = if (doc == null) {
                store.addPackDoc(workId, PackKind.RULES, PackKind.RULES.label, text)
            } else {
                val old = store.packText(workId, doc.id)
                if (old.isNotBlank()) store.snapshot(workId, store.docKey(doc.id), old)
                store.savePackText(workId, doc.id, text)
            }
        }) { saved?.let(then) }
    }

    // ---------- 导出、预览、备份 ----------

    suspend fun export(workId: String, format: ExportFormat, out: OutputStream) = withContext(disk) {
        val work = requireNotNull(store.get(workId)) { "作品不在了" }
        val text = { c: WorkChapter -> store.text(workId, c.id) }
        when (format) {
            ExportFormat.TXT -> out.write(WorkExport.txt(work, text).toByteArray())
            ExportFormat.MARKDOWN -> out.write(WorkExport.markdown(work, text).toByteArray())
            ExportFormat.EPUB -> WorkExport.epub(work, text, out)
        }
    }

    /** 生成预览用的 EPUB，返回作品和文件。 */
    internal suspend fun writePreview(workId: String): Pair<Work, java.io.File> = withContext(disk) {
        val work = requireNotNull(store.get(workId)) { "作品不在了" }
        val file = store.previewFile(workId)
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { WorkExport.epub(work, { c -> store.text(workId, c.id) }, it, placeholders = true) }
        check(tmp.renameTo(file)) { "无法写入预览文件" }
        work to file
    }

    internal fun backup(): ByteArray? = store.backup()

    internal fun restoreBackup(json: String): Int = store.restoreBackup(json).also {
        _packs.value = emptyMap()
        reload()
    }

    companion object {
        const val SNAPSHOT_EVERY = 10 * 60_000L
    }
}
