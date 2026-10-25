package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChangeKind
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.Foreshadow
import com.xycs.lingyuereader.data.FormatKind
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerVersion
import com.xycs.lingyuereader.data.exportLedgerToPack
import com.xycs.lingyuereader.data.importLedger
import com.xycs.lingyuereader.data.ledgerImportPreview
import com.xycs.lingyuereader.data.volumeNumber
import com.xycs.lingyuereader.data.FormatResult
import com.xycs.lingyuereader.data.FormatSettings
import com.xycs.lingyuereader.data.TextFormat
import com.xycs.lingyuereader.data.PackDoc
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PlannedCard
import com.xycs.lingyuereader.data.plannedCard
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
import com.xycs.lingyuereader.data.WritingBackup
import com.xycs.lingyuereader.data.WritingRestore
import com.xycs.lingyuereader.data.WritingStats
import com.xycs.lingyuereader.data.brief
import com.xycs.lingyuereader.data.chapterNo
import com.xycs.lingyuereader.data.SyncMark
import com.xycs.lingyuereader.data.unsynced
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
    fun save(workId: String, chapterId: String, text: String, closing: Boolean = false, onSaved: () -> Unit = {}) {
        scope.launch {
            try {
                val work = withContext(disk) {
                    // 撤销、重做一次排版（正文在排版前后两版之间来回）：不算码字数。
                    val count = formatSteps[chapterId]?.let { steps ->
                        val old = store.text(workId, chapterId)
                        steps.none { (a, b) -> (old == a && text == b) || (old == b && text == a) }
                    } ?: true
                    val saved = requireNotNull(store.saveText(workId, chapterId, text, count = count)) { "章节不在了" }
                    val now = System.currentTimeMillis()
                    if (text.isNotBlank() && (closing || now - (lastSnapshot[chapterId] ?: 0) >= SNAPSHOT_EVERY)) {
                        store.snapshot(workId, chapterId, text)
                        lastSnapshot[chapterId] = now
                    }
                    saved to store.stats()
                }
                publish(work.first)
                _stats.value = work.second
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
        }
    }

    // ---------- 自动排版 ----------

    /** 每章最近几次排版的前后两版（只在写盘队列里读写）：撤销、重做排版时认出来，不算码字数。 */
    private val formatSteps = HashMap<String, ArrayDeque<Pair<String, String>>>()

    /**
     * 一键排版写进正文：先把排版前的正文照常存（这之前打的字照常算码字数），存一个历史版本，
     * 再存排好的——排版改的标点、空格不算码字数。[then] 在写好以后调用。
     */
    fun applyFormat(workId: String, chapterId: String, before: String, after: String, then: () -> Unit = {}) {
        scope.launch {
            try {
                val (work, stats) = withContext(disk) {
                    requireNotNull(store.saveText(workId, chapterId, before)) { "章节不在了" }
                    if (before.isNotBlank()) store.snapshot(workId, chapterId, before)
                    val saved = requireNotNull(store.saveText(workId, chapterId, after, count = false)) { "章节不在了" }
                    formatSteps.getOrPut(chapterId) { ArrayDeque() }.apply {
                        addLast(before to after)
                        while (size > KEEP_FORMAT_STEPS) removeFirst()
                    }
                    saved to store.stats()
                }
                publish(work)
                _stats.value = stats
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("排版没存上：${e.message ?: "写入出错"}")
            }
        }
    }

    /** 全书排版前先看看：各章排出来的样子（存盘的正文），定稿的章单列（不排）。 */
    suspend fun previewFormat(workId: String, settings: FormatSettings, kinds: Set<FormatKind> = settings.kinds): BookFormat {
        val (chapters, texts) = withContext(disk) {
            val work = store.get(workId) ?: return@withContext emptyList<WorkChapter>() to emptyMap()
            work.chapters to work.chapters.filter { !it.locked }.associate { it.id to store.text(workId, it.id) }
        }
        return withContext(Dispatchers.Default) {
            BookFormat(
                chapters.filter { !it.locked }.map { c -> ChapterFormat(c, TextFormat.format(texts[c.id].orEmpty(), settings, kinds)) },
                chapters.filter { it.locked },
            )
        }
    }

    private val _formatRun = MutableStateFlow<FormatRun?>(null)
    /** 全书排版的进度（没在排、也没排完等着看结果时为空）。 */
    val formatRun: StateFlow<FormatRun?> = _formatRun.asStateFlow()
    private var formatJob: kotlinx.coroutines.Job? = null

    /**
     * 全书排版：按目录顺序一章章排（读存盘的正文，排之前存一个历史版本，排版改的不算码字数），跳过定稿的章；中途可以停（[stopFormat]）。
     */
    fun formatAll(workId: String, settings: FormatSettings, kinds: Set<FormatKind> = settings.kinds) {
        if (formatJob?.isActive == true) return
        val work = work(workId) ?: return
        val todo = work.chapters.filter { !it.locked }
        _formatRun.value = FormatRun(workId, total = todo.size, skipped = work.chapters.filter { it.locked }.map { it.title })
        formatJob = scope.launch {
            try {
                todo.forEachIndexed { i, c ->
                    _formatRun.update { it?.copy(done = i, current = c.title) }
                    val result = withContext(disk) {
                        val now = store.get(workId)?.chapter(c.id)
                        // 排到这一章时刚好被定稿了：也跳过。
                        if (now == null || now.locked) return@withContext null
                        val old = store.text(workId, c.id)
                        val r = TextFormat.format(old, settings, kinds)
                        if (!r.changed) return@withContext r to null
                        if (old.isNotBlank()) store.snapshot(workId, c.id, old)
                        r to store.saveText(workId, c.id, r.text, count = false)
                    }
                    result?.second?.let(::publish)
                    if (result != null && result.first.changed) {
                        _formatRun.update { it?.copy(changed = it.changed + 1, edits = it.edits + result.first.changes.size) }
                    }
                }
                _formatRun.update { it?.copy(done = todo.size, current = null) }
            } catch (e: CancellationException) {
                _formatRun.update { it?.copy(current = null, stopped = true) }
                throw e
            } catch (e: Exception) {
                _formatRun.update { it?.copy(current = null, stopped = true) }
                message("全书排版没排完：${e.message ?: "写入出错"}")
            }
        }
    }

    fun stopFormat() {
        formatJob?.cancel()
    }

    /** 看过结果了：收起进度。 */
    fun clearFormatRun() {
        if (formatJob?.isActive != true) _formatRun.value = null
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
            try {
                _stats.value = withContext(disk) {
                    store.setGoal(goal)
                    store.stats()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存目标失败：${e.message ?: "写入出错"}")
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
    fun setStatus(workId: String, chapterId: String, status: ChapterStatus, reason: String = "", canon: Boolean = false, currentText: String? = null, then: () -> Unit = {}) {
        scope.launch {
            try {
                val (work, stats) = withContext(disk) {
                    val updated = requireNotNull(store.setStatus(workId, chapterId, status, reason, canon, currentText)) { "章节不在了" }
                    refreshChanges(workId)
                    updated to store.stats()
                }
                publish(work)
                _stats.value = stats
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

    /** 这一章和上一章的细纲卡（上一章的钩子和承接边界写这一章时要接上）。没写过的按设定包里的细纲提取。 */
    suspend fun cards(workId: String, chapterId: String): Pair<PlannedCard, Pair<WorkChapter, ChapterCard>?> = withContext(disk) {
        val chapters = store.get(workId)?.chapters.orEmpty()
        val i = chapters.indexOfFirst { it.id == chapterId }
        store.plannedCard(workId, chapterId) to chapters.getOrNull(i - 1)?.let { it to store.plannedCard(workId, it.id).card }
    }

    fun saveCard(workId: String, chapterId: String, card: ChapterCard) {
        scope.launch {
            runCatching { withContext(disk) { store.saveCard(workId, chapterId, card) } }.onFailure { message("保存失败：${it.message ?: "写入出错"}") }
        }
    }

    /**
     * 按字段改细纲卡（见 [WorkStore.updateCard]）：在写盘队列里读出现在的卡再改，只动 [transform] 改到的字段，
     * 不会拿页面上的旧卡片把 AI 刚写的摘要盖掉。[then] 拿到改后的卡。
     */
    fun updateCard(workId: String, chapterId: String, transform: (ChapterCard) -> ChapterCard, then: (ChapterCard) -> Unit = {}) {
        scope.launch {
            try {
                then(withContext(disk) { store.updateCard(workId, chapterId, transform) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("保存失败：${e.message ?: "写入出错"}")
            }
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
    fun saveDoc(workId: String, docId: String, text: String, closing: Boolean = false, onSaved: () -> Unit = {}) {
        val key = store.docKey(docId)
        changePack(workId, "保存失败", {
            requireNotNull(store.savePackText(workId, docId, text)) { "设定文档不在了" }
            val now = System.currentTimeMillis()
            if (text.isNotBlank() && (closing || now - (lastSnapshot[key] ?: 0) >= SNAPSHOT_EVERY)) {
                store.snapshot(workId, key, text)
                lastSnapshot[key] = now
            }
        }, onSaved)
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

    // ---------- 故事账本 ----------

    private val _ledgers = MutableStateFlow<Map<String, List<LedgerEntry>>>(emptyMap())
    /** 各作品的故事账本（打开过的才有）。 */
    val ledgers: StateFlow<Map<String, List<LedgerEntry>>> = _ledgers.asStateFlow()

    fun loadLedger(workId: String) {
        scope.launch {
            try {
                val list = withContext(disk) { store.ledger(workId) }
                _ledgers.update { it + (workId to list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("读取故事账本失败：${e.message ?: "读取出错"}")
            }
        }
    }

    /** 在写盘队列里改账本，改完重读刷新页面；[then] 拿到 [block] 的结果。 */
    private fun <T> changeLedger(workId: String, failure: String, block: () -> T, then: (T) -> Unit = {}) {
        scope.launch {
            try {
                val (result, list) = withContext(disk) { block() to store.ledger(workId) }
                _ledgers.update { it + (workId to list) }
                then(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("$failure：${e.message ?: "写入出错"}")
            }
        }
    }

    /** 手动改账本（加、改、删一条）；[then] 拿到实际新加了几条（重复的不加）。 */
    fun editLedger(workId: String, ops: List<LedgerOp>, label: String, then: (Int) -> Unit = {}) =
        changeLedger(workId, "保存失败", { store.applyLedger(workId, ops, label) }, then)

    /** 设定包的察觉表、伏笔表里有、账本里还没有的条目（导入前给作者勾选）。 */
    suspend fun ledgerImportPreview(workId: String): List<LedgerEntry> = withContext(disk) { store.ledgerImportPreview(workId) }

    fun importLedger(workId: String, entries: List<LedgerEntry>, then: (Int) -> Unit) =
        changeLedger(workId, "导入失败", { store.importLedger(workId, entries) }, then)

    /** 把账本导出成设定包里的一份资料；设定包也跟着刷新。 */
    fun exportLedger(workId: String, then: (PackDoc) -> Unit) {
        var docs: List<PackDoc> = emptyList()
        changeLedger(workId, "导出失败", {
            store.exportLedgerToPack(workId).also { docs = store.pack(workId) }
        }) { doc ->
            _packs.update { it + (workId to docs) }
            then(doc)
        }
    }

    suspend fun ledgerVersions(workId: String): List<LedgerVersion> = withContext(disk) { store.ledgerVersions(workId) }

    suspend fun ledgerVersion(workId: String, at: Long): List<LedgerEntry>? = withContext(disk) { store.ledgerVersion(workId, at) }

    /** 换回某个历史版本（现在的样子也存进历史）。 */
    fun restoreLedger(workId: String, at: Long, then: () -> Unit) =
        changeLedger(workId, "换回失败", { check(store.restoreLedger(workId, at)) { "那个版本读不出来了" } }) { then() }

    /** 账本页“看到第几章”能选的章：按目录顺序，带按设定算的章号（见 [chapterNo]）和第几卷。 */
    suspend fun ledgerChapters(workId: String): List<LedgerChapter> = withContext(disk) {
        val work = store.get(workId) ?: return@withContext emptyList()
        work.chapters.mapNotNull { c -> store.chapterNo(work, c.id)?.let { LedgerChapter(c.id, c.title, it, work.volumeNumber(c.id)) } }
    }

    // ---------- 本章资料、伏笔、审稿跳转 ----------

    /** 写这一章时带给 AI 的资料（[text] 为空时用存盘的正文）。 */
    suspend fun brief(workId: String, chapterId: String, text: String? = null): ChapterBrief? = withContext(disk) { store.brief(workId, chapterId, text) }

    /** 存盘的正文（排在还没写完的保存后面读）。 */
    suspend fun savedText(workId: String, chapterId: String): String = withContext(disk) { store.text(workId, chapterId) }

    /** 有正文、还没整理进设定包（或整理以后正文改过）的章的 id。[marks] 是界面上看到的 AI 设置里记的（可能还没落盘）。 */
    suspend fun unsynced(workId: String, marks: Map<String, SyncMark>): Set<String> = withContext(disk) { store.unsynced(workId, marks).map { it.id }.toSet() }

    /** 从审稿页点了一处问题：回到编辑器，选中原文里的这一句；带了 [Jump.instruction] 时接着按它让 AI 改。编辑器用掉后清空。 */
    val jump = MutableStateFlow<Jump?>(null)

    /** 伏笔表里的伏笔（[Foreshadow.doc] 是文档 id），以及写到了第几章（有字的章里最大的章号）。 */
    suspend fun foreshadows(workId: String): Pair<List<Foreshadow>, Int> = withContext(disk) {
        val docs = store.pack(workId).filter { it.kind == PackKind.FORESHADOW }.map { it.id to store.packText(workId, it.id) }
        val work = store.get(workId)
        val latest = work?.chapters?.filter { it.words > 0 }?.mapNotNull { store.chapterNo(work, it.id) }?.maxOrNull() ?: 0
        ChapterBriefs.foreshadows(docs) to latest
    }

    /** 把一条伏笔标为已回收：改伏笔表那一行（原来的文字先存一版）。 */
    fun markForeshadow(workId: String, f: Foreshadow, then: () -> Unit = {}) = changePack(workId, "保存失败", {
        val old = store.packText(workId, f.doc)
        val new = ChapterBriefs.markDone(old, f, null)
        if (new != old) {
            store.snapshot(workId, store.docKey(f.doc), old)
            store.savePackText(workId, f.doc, new)
        }
    }, then)

    /**
     * AI 写的草稿放进正文：[replace] 为真时替换整章，否则接在后面。原来的正文先存一个历史版本；
     * AI 写的字不算码字数。
     */
    fun applyDraft(workId: String, chapterId: String, draft: String, replace: Boolean, then: () -> Unit = {}) =
        change("放进正文失败", {
            val old = store.text(workId, chapterId)
            if (old.isNotBlank()) store.snapshot(workId, chapterId, old)
            val text = if (replace || old.isBlank()) draft.trim() else old.trimEnd() + "\n" + draft.trim()
            store.saveText(workId, chapterId, text, count = false)
        }) { then() }

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

    /** 同 [backup]，另外报告读不出、没放进备份的文件（[WritingBackup.skipped]），好告诉作者。 */
    internal fun backupReport(): WritingBackup? = store.backupReport()

    internal fun validateBackup(json: String) = store.validateBackup(json)

    internal fun restoreBackup(json: String): Int = restoreBackupReport(json).restored

    /** 同 [restoreBackup]，另外报告整部没有恢复的作品（[WritingRestore.skipped]：本机的较新，或本机的文件读不出）。 */
    internal fun restoreBackupReport(json: String): WritingRestore = store.restoreBackupReport(json).also {
        _packs.value = emptyMap()
        reload()
    }

    companion object {
        const val SNAPSHOT_EVERY = 10 * 60_000L
        /** 每章记着最近几次排版的前后两版。 */
        const val KEEP_FORMAT_STEPS = 5
    }
}

/** 全书排版前的预览：要排的各章、定稿不排的章。 */
data class BookFormat(val chapters: List<ChapterFormat>, val locked: List<WorkChapter>)

/** 一章排出来的样子。 */
data class ChapterFormat(val chapter: WorkChapter, val result: FormatResult)

/**
 * 全书排版的进度：排到第 [done] 章（共 [total] 章），正在排 [current]；改了 [changed] 章、[edits] 处；
 * [skipped] 是定稿没排的章；[stopped] 为真表示中途停了。[current] 为空且没停时表示排完了。
 */
data class FormatRun(
    val workId: String,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val changed: Int = 0,
    val edits: Int = 0,
    val skipped: List<String> = emptyList(),
    val stopped: Boolean = false,
) {
    val running: Boolean get() = current != null || (done < total && !stopped)
}

/** 回到编辑器要选中的一句：哪一章、原文、要不要按一条意见让 AI 改。 */
data class Jump(val chapterId: String, val quote: String, val instruction: String? = null, val reviewFix: ReviewFix? = null)

/** 账本页能翻到的一章：[no] 是按设定算的章号，[volume] 是第几卷。 */
data class LedgerChapter(val id: String, val title: String, val no: Int, val volume: Int)
