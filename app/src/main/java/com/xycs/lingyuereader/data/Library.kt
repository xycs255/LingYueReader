package com.xycs.lingyuereader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 书架。每本书一个目录，存导入文件的副本，离线也能读；同时记下来源地址，
 * 原文件（如云盘里的文稿）改过之后可以“刷新”。
 */
/**
 * 整个应用共用一个书架实例：写书架索引的锁在实例上，两个实例同时写会互相覆盖。
 * 听书在界面关掉后还会存进度，用的必须是同一个实例。
 */
object SharedLibrary {
    @Volatile
    private var library: Library? = null

    @Synchronized
    fun get(context: Context): Library {
        val root = File(context.filesDir, "books").path
        val app = context.applicationContext
        return library?.takeIf { it.rootPath == root && it.appContext === app } ?: Library(app).also { library = it }
    }
}

class Library(private val context: Context) {

    private val root = File(context.filesDir, "books").apply { mkdirs() }
    internal val rootPath: String get() = root.path
    internal val appContext: Context get() = context
    private val indexFile = File(root, "index.json")
    /** 可注入目录改名失败，验证已提交但未清理时不会允许后续索引写入。 */
    internal var retireSwapRename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    init {
        BookParser.pdfAspects = PdfPages::aspects
        recoverInterruptedRules()
        recoverInterruptedSwaps()
        // 第一次启动时就建立空索引。之后索引若失踪，不能把仍在磁盘上的书当作空书架改写。
        if (!indexFile.exists() && !hasBookDirectories()) {
            check(DurableFiles.write(indexFile, "[]".toByteArray())) { "无法建立书架索引" }
        }
    }

    private companion object {
        const val CACHE = "cache.bin"
        const val RULES = "rules.json"
        const val RULES_INDEX_BEFORE = "index-before.json"
        const val RULES_COMMITTED = "committed"
        const val SWAP_INDEX_BEFORE = "index-before.json"
        const val SWAP_INDEX_AFTER = "index-after.json"
        const val SWAP_IDS = "ids.json"
        const val SWAP_PREPARED = "prepared-swap"
        const val SWAP_COMMITTED = "committed-swap"
        const val SWAP_ROLLED_BACK = "rolled-back-swap"
        const val SWAP_CLEANUP = ".swap-cleanup-"
        val RULES_STAGE = Regex("""\.rules-([A-Za-z0-9-]{1,64})-([0-9a-fA-F-]{36})""")
        val REFRESH_STAGE = Regex("""\.refresh-([A-Za-z0-9-]{1,64})-[0-9a-fA-F-]{36}""")
        const val BACKUP_MANIFEST = "lingyue-backup.json"
        const val MAX_BACKUP_ENTRIES = 50_000
        const val MAX_BACKUP_UNPACKED_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_BACKUP_MANIFEST_BYTES = 1024 * 1024L
        const val MAX_BACKUP_JSON_BYTES = 64L * 1024 * 1024
        val BACKUP_ID = Regex("""[A-Za-z0-9-]{1,64}""")
        val BACKUP_SOURCE_FILE = Regex("""s\d{1,4}\.[A-Za-z0-9]{1,10}""")
        /** 书以外的应用数据（生词本、阅读统计、写作的作品），按文件名放在 data/ 下。 */
        val BACKUP_DATA_FILE = Regex("""data/(vocabulary|stats|writing)\.json""")
        val BACKUP_BOOK_FILE = Regex("""books/[A-Za-z0-9-]{1,64}/(s\d{1,4}\.[A-Za-z0-9]{1,10}|marks\.json|rules\.json|ai-[a-z0-9-]{1,40}\.(json|md|bin))""")

        /** 每本书的 AI 数据（读书笔记、整章翻译、讲书稿、人物表等）：备份时打包，恢复时保留本机已有的。 */
        val AI_BOOK_FILE = Regex("""ai-[a-z0-9-]{1,40}\.(json|md|bin)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    class Picked(val uri: Uri, val name: String)

    /** 书架上读得出的书。整份索引损坏时抛错；个别条目读不出时跳过它们（见 [damagedEntries]）。 */
    @Synchronized
    fun list(): List<BookEntry> = readIndex().first

    /** 索引里读不出的条目数。大于 0 时 [update] 拒绝改写索引，免得把这些书从书架上抹掉。 */
    @Synchronized
    fun damagedEntries(): Int = readIndex().second

    private fun readIndex(): Pair<List<BookEntry>, Int> {
        if (!indexFile.isFile) {
            check(!indexFile.exists() && !hasBookDirectories()) {
                "书架索引缺失；原书籍文件已保留，已停止写入，请先恢复索引"
            }
            return emptyList<BookEntry>() to 0
        }
        return parseIndex(indexFile.readText())
    }

    private fun hasBookDirectories(): Boolean = root.listFiles().orEmpty()
        .any { it.isDirectory && BACKUP_ID.matches(it.name) }

    private fun parseIndex(json: String): Pair<List<BookEntry>, Int> {
        val array = try {
            JSONArray(json)
        } catch (error: Exception) {
            throw IllegalStateException("书架索引损坏，已停止写入并保留原文件", error)
        }
        var damaged = 0
        val entries = (0 until array.length()).mapNotNull { i ->
            runCatching { array.getJSONObject(i).toEntry() }.onFailure { damaged++ }.getOrNull()
        }
        return entries to damaged
    }

    /** 来自书源（OPDS、WebDAV）的书：来源地址是 http(s)，由书源客户端下载。没设时这类地址打不开。 */
    var remote: ((String) -> java.io.InputStream)? = null

    /** 打开一份来源：书源的地址交给 [remote]，其余走 ContentResolver。 */
    private fun openSource(uri: Uri): java.io.InputStream? =
        if (uri.scheme == "http" || uri.scheme == "https") requireNotNull(remote) { "书源不可用" }(uri.toString())
        else context.contentResolver.openInputStream(uri)

    private class SourceTooLargeException(message: String) : IllegalArgumentException(message)

    private fun sourceLimit(name: String): Long = when (BookParser.formatOf(name)) {
            BookFormat.EPUB -> 256L * 1024 * 1024
            BookFormat.PDF, BookFormat.CBZ -> 2L * 1024 * 1024 * 1024
            else -> 128L * 1024 * 1024
        }

    private fun copySource(uri: Uri, name: String, target: File, checkCancelled: () -> Unit = {}): File {
        val limit = sourceLimit(name)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法准备书籍文件目录" }
        requireNotNull(openSource(uri)) { "无法打开文件：$name" }.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw SourceTooLargeException("$name 文件过大，无法安全导入（上限 ${limit / 1024 / 1024} MB）")
                    output.write(buffer, 0, count)
                }
            }
        }
        return target
    }

    fun describe(uris: List<Uri>): List<Picked> =
        uris.map { Picked(it, displayName(it) ?: it.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt") }

    /**
     * 导入一本书；[files] 多于一个时合成一本。
     * 不加锁：复制和解析一本大书要好几秒，只在新建的书目录里进行；最后写索引（[update]）时才加锁。
     * 这样导入时保存进度、改名不必排队，点“取消”也能马上生效。
     */
    fun add(files: List<Picked>, title: String?, checkCancelled: () -> Unit = {}): BookEntry {
        require(files.isNotEmpty()) { "没有选择文件" }
        val existing = list()
        // 同一来源可能已改写；读者应使用“刷新”，避免重新导入时生成第二本书。
        if (existing.any { entry -> entry.sources.map { it.uri } == files.map { it.uri.toString() } }) {
            throw IllegalArgumentException("这本书已在书架上，可使用刷新更新内容")
        }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            val sources = files.mapIndexed { i, picked ->
                checkCancelled()
                val ext = picked.name.substringAfterLast('.', "txt").lowercase()
                require(ext.matches(Regex("[a-z0-9]{1,10}"))) { "文件扩展名无效：${picked.name}" }
                val file = "s$i.$ext"
                copySource(picked.uri, picked.name, File(dir, file), checkCancelled)
                require(File(dir, file).length() > 0L) { "文件为空：${picked.name}" }
                SourceRef(picked.name, picked.uri.toString(), file)
            }
            // 不同 URI 的逐字节相同文件也只保留一份。先比长度，再分块比较，避免长篇书全文常驻内存。
            if (existing.any { entry -> checkCancelled(); sameSources(entry, sources, dir, checkCancelled) }) {
                throw IllegalArgumentException("这本书已在书架上，可使用刷新更新内容")
            }
            checkCancelled()
            val content = buildCache(dir, sources)
            checkCancelled()
            require(content.chapters.isNotEmpty()) { "没有读出正文" }
            val now = System.currentTimeMillis()
            val entry = BookEntry(
                id = id,
                title = title?.trim()?.ifEmpty { null } ?: content.title ?: BookParser.titleOf(files.first().name),
                author = content.author ?: BookParser.authorOf(files.first().name),
                format = if (files.map { BookParser.formatOf(it.name) }.distinct().size == 1) BookParser.formatOf(files.first().name) else BookFormat.MIXED,
                sources = sources,
                addedAt = now,
                openedAt = 0,
                chapterCount = content.chapters.size,
                charCount = content.charCount,
                progress = null,
                description = content.description,
                cover = content.cover,
            )
            update { it + entry }
            // 解析、索引均成功后才持有 URI 授权；失败或取消不会留下无主授权。
            files.forEach { picked ->
                runCatching { context.contentResolver.takePersistableUriPermission(picked.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            return entry
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun sameSources(entry: BookEntry, sources: List<SourceRef>, incoming: File, checkCancelled: () -> Unit): Boolean {
        if (entry.sources.size != sources.size || entry.sources.map { it.name } != sources.map { it.name }) return false
        return entry.sources.zip(sources).all { (old, fresh) ->
            val a = File(File(root, entry.id), old.file)
            val b = File(incoming, fresh.file)
            sameFile(a, b, checkCancelled)
        }
    }

    private fun sameFile(a: File, b: File, checkCancelled: () -> Unit): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        a.inputStream().buffered().use { left ->
            b.inputStream().buffered().use { right ->
                val first = ByteArray(DEFAULT_BUFFER_SIZE)
                val second = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val size = left.read(first)
                    if (size < 0) return true
                    var filled = 0
                    while (filled < size) {
                        val read = right.read(second, filled, size - filled)
                        if (read < 0) return false
                        filled += read
                    }
                    for (i in 0 until size) if (first[i] != second[i]) return false
                }
            }
        }
    }

    /** 读出一本书。优先用缓存（只读目录，章节按需加载）；缓存不在或过期时重新解析并写缓存。 */
    fun content(entry: BookEntry): BookContent {
        val dir = File(root, entry.id)
        BookCache.read(File(dir, CACHE), fingerprint(dir, entry.sources))?.let { return it }
        // 同一本书只许一处重建（全书架搜索和打开书可能同时触发）：两处一起写缓存、清插图目录会把对方的弄坏。
        synchronized(buildLocks.getOrPut(dir.path) { Any() }) {
            return BookCache.read(File(dir, CACHE), fingerprint(dir, entry.sources)) ?: buildCache(dir, entry.sources)
        }
    }

    private val buildLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 书籍目录里的文件（解出的插图、封面）。 */
    fun bookFile(id: String, name: String): File = File(File(root, id), name)

    /**
     * 解析来源文件，把 EPUB 里用到的插图和封面解到书籍目录，写缓存，再从缓存读回（释放解析时占用的内存）。
     */
    private fun buildCache(dir: File, sources: List<SourceRef>): BookContent {
        val parsed = BookParser.parse(load(dir, sources), rulesIn(dir))
        val withImages = extractImages(dir, sources, parsed)
        val fingerprint = fingerprint(dir, sources)
        val cacheFile = File(dir, CACHE)
        BookCache.write(cacheFile, withImages, fingerprint)
        return BookCache.read(cacheFile, fingerprint) ?: withImages
    }

    /** 来源副本的大小和修改时间、文本规则，加上缓存版本；任一变化都会让缓存作废。 */
    private fun fingerprint(dir: File, sources: List<SourceRef>): String =
        "v${BookCache.VERSION}|" + sources.joinToString("|") { s ->
            val f = File(dir, s.file)
            "${s.file}:${f.length()}:${f.lastModified()}"
        } + rulesIn(dir).let { if (it.isEmpty) "" else "|rules:" + it.toJson().toString().hashCode() }

    private fun rulesIn(dir: File): TextRules {
        val f = File(dir, RULES)
        if (!f.isFile) return TextRules()
        return runCatching { TextRules.fromJson(JSONObject(f.readText())) }.getOrDefault(TextRules())
    }

    fun rules(id: String): TextRules = rulesIn(File(root, id))

    /** 来源文件解码后的全文（规则页预览用）。 */
    fun sourceTexts(entry: BookEntry): List<String> {
        val dir = File(root, entry.id)
        return entry.sources.map {
            val file = File(dir, it.file)
            require(file.length() <= 128L * 1024 * 1024) { "${it.name} 文件过大，无法预览文本规则" }
            TextDecoder.decode(file.readBytes())
        }
    }

    /** 按 [rules] 试解析，不写缓存（规则页预览分章用）。 */
    fun previewWith(entry: BookEntry, rules: TextRules): BookContent = BookParser.parse(load(File(root, entry.id), entry.sources), rules)

    /** 保存文本规则并重新解析。 */
    @Synchronized
    fun saveRules(entry: BookEntry, rules: TextRules): BookContent {
        recoverInterruptedRules()
        val dir = File(root, entry.id)
        require(dir.isDirectory) { "书籍文件不存在" }
        val staging = File(root, ".rules-${entry.id}-${UUID.randomUUID()}")
        val next = File(staging, "next")
        val previous = File(staging, "previous")
        var swapped = false
        var finished = false
        try {
            // 规则、插图和缓存都只改临时副本；重解析失败不能让原书变得不可读。
            check(dir.copyRecursively(next)) { "无法准备书籍规则目录" }
            val stagedRules = File(next, RULES)
            if (rules.isEmpty && rules.replace.isEmpty()) {
                check(!stagedRules.exists() || stagedRules.delete()) { "无法清除旧文本规则" }
            } else {
                stagedRules.writeText(rules.toJson().toString())
            }
            val content = buildCache(next, entry.sources)
            require(content.chapters.isNotEmpty()) { "新规则没有读出正文" }
            // 交换前保存旧索引。若进程死在目录交换与索引提交之间，启动时将二者一起回滚。
            val indexBefore = indexFile.readBytes()
            parseIndex(indexBefore.toString(Charsets.UTF_8))
            File(staging, RULES_INDEX_BEFORE).writeBytes(indexBefore)
            try {
                check(dir.renameTo(previous)) { "无法保存旧书籍文件" }
                swapped = true
                check(next.renameTo(dir)) { "无法应用新的文本规则" }
                update { list ->
                    list.map {
                        if (it.id == entry.id) it.copy(chapterCount = content.chapters.size, charCount = content.charCount)
                        else it
                    }
                }
                // buildCache 返回的章节懒读 staging；提交前先从正式路径重新打开。
                val installed = this.content(entry)
                val marker = File(staging, "$RULES_COMMITTED.tmp")
                marker.writeText("ok")
                check(marker.renameTo(File(staging, RULES_COMMITTED))) { "无法完成文本规则事务" }
                finished = true
                return installed
            } catch (error: Throwable) {
                if (swapped) {
                    try {
                        rollbackRules(staging, dir)
                        finished = true
                    } catch (rollbackError: Throwable) {
                        error.addSuppressed(rollbackError)
                    }
                }
                throw error
            }
        } finally {
            // 回滚失败时保留 previous、旧索引快照与可能的新书副本，留待下次启动重试。
            if (finished || !swapped) staging.deleteRecursively()
        }
    }

    /** 目录交换之前先保存原索引和全部目标 ID；提交标记必须在索引写好以后落盘。 */
    private fun prepareSwap(staging: File, ids: List<String>) {
        clearPendingCommittedSwaps()
        require(ids.isNotEmpty() && ids.size == ids.distinct().size && ids.all(BACKUP_ID::matches)) { "书籍事务 ID 无效" }
        check(staging.isDirectory || staging.mkdirs()) { "无法准备书籍事务目录" }
        val (entries, damaged) = readIndex()
        check(damaged == 0) { "书架索引里有损坏记录，未替换书籍" }
        val indexedIds = entries.map { it.id }.toSet()
        check(ids.none { id -> File(root, id).exists() && id !in indexedIds }) {
            "书架上存在未索引的同 ID 书籍文件，未替换原文件"
        }
        val before = indexFile.readBytes()
        check(entries.size == JSONArray(before.toString(Charsets.UTF_8)).length()) { "书架索引不完整，未替换书籍" }
        check(DurableFiles.write(File(staging, SWAP_INDEX_BEFORE), before)) { "无法保存原书架索引" }
        check(DurableFiles.write(File(staging, SWAP_IDS), JSONArray(ids).toString().toByteArray())) { "无法记录书籍事务" }
        check(DurableFiles.write(File(staging, SWAP_PREPARED), byteArrayOf(1))) { "无法开始书籍事务" }
    }

    private fun swapIds(staging: File): List<String> {
        val array = JSONArray(File(staging, SWAP_IDS).readText())
        val ids = (0 until array.length()).map { array.getString(it) }
        require(ids.isNotEmpty() && ids.size == ids.distinct().size && ids.all(BACKUP_ID::matches)) { "书籍事务记录损坏" }
        return ids
    }

    /**
     * 未提交时把所有目标目录与索引一起退回原版。previous 始终保留到整个回滚完成，
     * 所以即使回滚自己再次被杀，下次启动也能从同一份旧目录重试。
     */
    private fun rollbackSwap(staging: File) {
        val ids = swapIds(staging)
        val before = File(staging, SWAP_INDEX_BEFORE).readBytes()
        val (oldEntries, damaged) = parseIndex(before.toString(Charsets.UTF_8))
        check(damaged == 0 && oldEntries.size == JSONArray(before.toString(Charsets.UTF_8)).length()) { "原书架索引快照损坏" }
        val oldIds = oldEntries.map { it.id }.toSet()
        val previous = File(staging, "previous")
        val discard = File(staging, "discard")
        ids.forEach { id ->
            val dir = File(root, id)
            val old = File(previous, id)
            if (old.exists()) {
                check(old.isDirectory) { "原书籍副本损坏：$id" }
                if (dir.exists()) {
                    check(discard.isDirectory || discard.mkdirs()) { "无法准备事务回滚目录" }
                    check(dir.renameTo(File(discard, "$id-${UUID.randomUUID()}"))) { "无法暂存未提交的书籍：$id" }
                }
                check(old.copyRecursively(dir)) { "无法还原原书籍：$id" }
            } else if (id !in oldIds && dir.exists()) {
                // 备份新加的书也须撤销；原索引并不认识它。
                check(discard.isDirectory || discard.mkdirs()) { "无法准备事务回滚目录" }
                check(dir.renameTo(File(discard, "$id-${UUID.randomUUID()}"))) { "无法撤销未提交的新书：$id" }
            } else if (id in oldIds) {
                check(dir.isDirectory) { "原书籍目录失踪：$id" }
            }
        }
        if (!indexFile.isFile || !indexFile.readBytes().contentEquals(before)) {
            check(DurableFiles.write(indexFile, before)) { "无法还原原书架索引" }
            check(indexFile.readBytes().contentEquals(before)) { "原书架索引回读不一致" }
        }
        check(DurableFiles.write(File(staging, SWAP_ROLLED_BACK), byteArrayOf(1))) { "无法完成书籍事务回滚" }
        retireSwap(staging)
    }

    /** 先改掉目录名再删，避免清理时被杀且删掉提交标记后误判为未提交。 */
    private fun retireSwap(staging: File): Boolean {
        if (!staging.exists()) return true
        val retired = File(root, "$SWAP_CLEANUP${UUID.randomUUID()}")
        if (!retireSwapRename(staging, retired)) return false
        retired.deleteRecursively()
        return true
    }

    /** 若提交后旧副本尚未安全退役，先完成清理；不能让后续合法进度改写 index-after。 */
    private fun clearPendingCommittedSwaps() {
        root.listFiles().orEmpty().filter { staging ->
            staging.isDirectory && (REFRESH_STAGE.matches(staging.name) || staging.name.startsWith(".restore-")) &&
                File(staging, SWAP_COMMITTED).isFile
        }.forEach { staging ->
            val after = File(staging, SWAP_INDEX_AFTER)
            check(after.isFile && indexFile.isFile && indexFile.readBytes().contentEquals(after.readBytes()) &&
                swapIds(staging).all { File(root, it).isDirectory }) { "上次书籍事务尚未确认，已停止后续写入" }
            check(retireSwap(staging)) { "上次书籍事务已提交，但旧副本清理失败，已停止后续写入" }
        }
    }

    private fun commitSwap(staging: File) {
        check(DurableFiles.write(File(staging, SWAP_INDEX_AFTER), indexFile.readBytes())) { "无法确认新书架索引" }
        check(DurableFiles.write(File(staging, SWAP_COMMITTED), byteArrayOf(1))) { "无法完成书籍事务" }
        retireSwap(staging)
    }

    /** 进程死亡后，按事务标记选择整组提交或整组回滚；旧版无日志的临时目录仍做保守找回。 */
    private fun recoverInterruptedSwaps() {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(SWAP_CLEANUP) }.forEach { it.deleteRecursively() }
        root.listFiles().orEmpty().forEach { staging ->
            if (!staging.isDirectory) return@forEach
            val refreshId = REFRESH_STAGE.matchEntire(staging.name)?.groupValues?.get(1)
            val restoring = staging.name.startsWith(".restore-")
            if (refreshId == null && !restoring) return@forEach
            if (File(staging, SWAP_ROLLED_BACK).isFile) {
                retireSwap(staging)
                return@forEach
            }
            if (File(staging, SWAP_PREPARED).isFile) {
                val after = File(staging, SWAP_INDEX_AFTER)
                if (File(staging, SWAP_COMMITTED).isFile && after.isFile && indexFile.isFile &&
                    indexFile.readBytes().contentEquals(after.readBytes()) && swapIds(staging).all { File(root, it).isDirectory }
                ) {
                    check(retireSwap(staging)) { "已提交书籍事务的旧副本清理失败，已停止后续写入" }
                } else {
                    rollbackSwap(staging)
                }
                return@forEach
            }
            // 旧版事务没有索引快照：仅在正式目录失踪时找回 previous，不猜测目录内容。
            val previous = File(staging, "previous")
            if (refreshId != null) {
                val dir = File(root, refreshId)
                if (previous.isDirectory && !dir.exists()) previous.renameTo(dir)
            } else {
                previous.listFiles().orEmpty().forEach { old ->
                    val dir = File(root, old.name)
                    if (old.isDirectory && !dir.exists()) old.renameTo(dir)
                }
            }
            // 还没放回去的（原处已有目录）留着，不删唯一可能的旧副本；放回了就清掉整个临时目录。
            if (previous.list().isNullOrEmpty() || (refreshId != null && !previous.exists())) staging.deleteRecursively()
        }
    }

    /** 修复进程死亡时遗留的规则目录事务；在首次读取书架前执行。 */
    private fun recoverInterruptedRules() {
        root.listFiles().orEmpty().forEach { staging ->
            if (!staging.isDirectory) return@forEach
            val id = RULES_STAGE.matchEntire(staging.name)?.groupValues?.get(1) ?: return@forEach
            val dir = File(root, id)
            val committed = File(staging, RULES_COMMITTED).isFile
            if (committed && dir.isDirectory) {
                staging.deleteRecursively()
                return@forEach
            }
            // 已提交的目录异常失踪时仍优先救回旧副本，不能把索引中的书当成空书。
            rollbackRules(staging, dir)
            staging.deleteRecursively()
        }
    }

    /** 未提交的交换总是恢复旧目录与旧索引；abandoned 使中途再次死亡时可幂等重试。 */
    private fun rollbackRules(staging: File, dir: File) {
        val previous = File(staging, "previous")
        val abandoned = File(staging, "abandoned")
        val changed = previous.isDirectory || abandoned.isDirectory
        if (previous.isDirectory) {
            if (dir.exists()) {
                check(!abandoned.exists() && dir.renameTo(abandoned)) { "无法暂存未提交的规则目录：${dir.absolutePath}" }
            }
            check(previous.renameTo(dir)) { "无法还原旧书籍文件：${previous.absolutePath}" }
        } else {
            check(dir.isDirectory) { "规则事务的旧书籍目录失踪：${dir.absolutePath}" }
        }
        if (changed) {
            File(staging, RULES_INDEX_BEFORE).takeIf { it.isFile }?.let { snapshot ->
                val bytes = snapshot.readBytes()
                parseIndex(bytes.toString(Charsets.UTF_8))
                if (!indexFile.isFile || !indexFile.readBytes().contentEquals(bytes)) {
                    val tmp = File(root, "index.rules-recovery-${UUID.randomUUID()}.tmp")
                    try {
                        tmp.writeBytes(bytes)
                        check(tmp.renameTo(indexFile)) { "无法还原规则变更前的书架索引" }
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
    }

    /**
     * 把书内路径（“来源序号|路径”）的图片解出到 img/ 目录，块里的路径改成文件名；找不到或不支持的图片去掉。
     * 漫画和 PDF 的页不解出（太大），改成“zip:来源文件:包内路径”“pdf:来源文件:页号:高宽比”，阅读时从原文件里取；
     * 只有它们的封面另存一张小图。
     */
    private fun extractImages(dir: File, sources: List<SourceRef>, content: BookContent): BookContent {
        val imgDir = File(dir, "img")
        imgDir.deleteRecursively()
        val refs = buildSet {
            content.cover?.let(::add)
            content.chapters.forEach { c -> c.blocks.forEach { if (it is Block.Image) add(it.src) } }
        }
        if (refs.isEmpty()) return content
        imgDir.mkdirs()
        var count = 0
        fun newFile(ext: String) = "img/${count++}.$ext"

        /** 漫画、PDF 的页：不解出，记成从原文件读取的形式。 */
        fun lazyRef(source: SourceRef, path: String): String? = when {
            path.startsWith("@zip/") -> "zip:${source.file}:${path.removePrefix("@zip/")}"
            path.startsWith("@pdf/") -> path.removePrefix("@pdf/").split('/').takeIf { it.size == 2 }?.let { "pdf:${source.file}:${it[0]}:${it[1]}" }
            else -> null
        }

        // 每份来源只读一次、解一次（插图多的 MOBI、FB2 每张图都重读整本书要读好几 GB）。
        val resourceReaders = HashMap<String, (String) -> ByteArray?>()
        fun resourcesOf(source: SourceRef, file: File) = resourceReaders.getOrPut(source.file) { BookParser.resources(BookParser.SourceBytes(source.name, file)) }

        /** 解出一张图到 img/，返回文件名。 */
        fun extract(source: SourceRef, path: String): String? = runCatching {
            val file = File(dir, source.file)
            when {
                path.startsWith("@res/") -> {
                    val data = resourcesOf(source, file)(path.removePrefix("@res/")) ?: return@runCatching null
                    val ext = PageBooks.imageExtension(data) ?: return@runCatching null
                    newFile(ext).also { File(dir, it).writeBytes(data) }
                }
                path.startsWith("@pdf/") -> {
                    val page = path.removePrefix("@pdf/").substringBefore('/').toInt()
                    newFile("jpg").takeIf { PdfPages.saveJpeg(file, page, 360, File(dir, it)) }
                }
                else -> {
                    val inner = path.removePrefix("@zip/")
                    val ext = inner.substringAfterLast('.', "").lowercase()
                    if (ext !in IMAGE_EXTENSIONS) return@runCatching null
                    PageBooks.openZip(file).use { zip ->
                        val entry = zip.getEntry(inner) ?: return@runCatching null
                        newFile(ext).also { name -> zip.getInputStream(entry).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } } }
                    }
                }
            }
        }.getOrNull()

        val resolved = HashMap<String, String?>()
        fun pageSrc(ref: String): String? = resolved.getOrPut(ref) {
            val source = ref.substringBefore('|').toIntOrNull()?.let(sources::getOrNull) ?: return@getOrPut null
            val path = ref.substringAfter('|')
            lazyRef(source, path) ?: extract(source, path)
        }
        val chapters = content.chapters.map { c ->
            if (c.blocks.none { it is Block.Image }) c
            else c.withBlocks(c.blocks.mapNotNull { b -> if (b is Block.Image) pageSrc(b.src)?.let { b.copy(src = it) } else b })
        }
        // 封面要能当普通图片文件读：漫画、PDF 的封面另存一张。
        val cover = content.cover?.let { ref ->
            val source = ref.substringBefore('|').toIntOrNull()?.let(sources::getOrNull) ?: return@let null
            val path = ref.substringAfter('|')
            if (lazyRef(source, path) != null) extract(source, path) else pageSrc(ref)
        }
        return content.copy(chapters = chapters, cover = cover)
    }

    /**
     * 从原位置重读每份文件。先在临时目录验证新书，失败时保留旧副本和缓存。
     * 读来源（可能要从书源下载）不占书架的锁：慢的网络不能让存进度、书签这些写书架的操作都排队等着。
     */
    fun refresh(entry: BookEntry): RefreshResult {
        require(File(root, entry.id).isDirectory) { "书籍文件不存在" }
        val staging = File(root, ".refresh-${entry.id}-${UUID.randomUUID()}")
        val downloaded = File(staging, "downloaded")
        try {
            val read = entry.sources.associate { s ->
                val file = s.uri?.let { uri ->
                    runCatching { copySource(uri.toUri(), s.name, File(downloaded, s.file)) }.getOrElse { error ->
                        if (error is SourceTooLargeException) throw error
                        null
                    }
                }
                s.file to file
            }
            return synchronized(this) { refreshLocked(entry, staging, read) }
        } finally {
            downloaded.deleteRecursively()
            // 有事务日志的目录要留给下次启动恢复；未开始交换的临时副本可以清理。
            if (!File(staging, SWAP_PREPARED).isFile && !File(staging, "previous").exists()) staging.deleteRecursively()
        }
    }

    private fun refreshLocked(entry: BookEntry, staging: File, read: Map<String, File?>): RefreshResult {
        val dir = File(root, entry.id)
        require(dir.isDirectory) { "书籍文件不存在" }
        val incoming = File(staging, "incoming")
        val next = File(staging, "next")
        val previous = File(staging, "previous/${entry.id}")
        var changed = 0
        var failed = 0
        try {
            for (s in entry.sources) {
                val replacement = read[s.file]
                if (replacement == null) {
                    failed++
                    continue
                }
                val target = File(dir, s.file)
                if (!sameFile(target, replacement, {})) {
                    require(replacement.length() > 0L) { "来源文件为空：${s.name}" }
                    check(incoming.isDirectory || incoming.mkdirs()) { "无法准备书籍刷新目录" }
                    replacement.copyTo(File(incoming, s.file))
                    changed++
                }
            }
            if (changed == 0) return RefreshResult(0, failed)

            check(dir.copyRecursively(next)) { "无法复制书籍文件" }
            for (s in entry.sources) {
                val replacement = File(incoming, s.file)
                if (replacement.isFile) replacement.copyTo(File(next, s.file), overwrite = true)
            }

            val content = buildCache(next, entry.sources)
            require(content.chapters.isNotEmpty()) { "没有读出正文" }

            // 两次改名均在 books/ 所在文件系统内。索引写入失败时也恢复旧目录。
            try {
                prepareSwap(staging, listOf(entry.id))
                check(previous.parentFile!!.isDirectory || previous.parentFile!!.mkdirs()) { "无法准备原书籍目录" }
                check(dir.renameTo(previous)) { "无法保存旧书籍文件" }
                check(next.renameTo(dir)) { "无法替换书籍文件" }
                update { list ->
                    list.map {
                        if (it.id == entry.id) {
                            it.copy(chapterCount = content.chapters.size, charCount = content.charCount, cover = content.cover, description = content.description)
                        } else it
                    }
                }
                commitSwap(staging)
            } catch (error: Throwable) {
                if (File(staging, SWAP_PREPARED).isFile && !File(staging, SWAP_COMMITTED).isFile) {
                    try {
                        rollbackSwap(staging)
                    } catch (rollbackError: Throwable) {
                        error.addSuppressed(rollbackError)
                    }
                }
                throw error
            }
            return RefreshResult(changed, failed)
        } finally {
            if (!File(staging, SWAP_PREPARED).isFile && !File(staging, "previous").exists()) staging.deleteRecursively()
        }
    }

    data class RefreshResult(val changed: Int, val failed: Int)

    class CorruptMarksException(cause: Throwable) : IllegalStateException("书签批注文件损坏；原文件已保留，请从备份恢复", cause)

    private fun readMarksFile(file: File): Marks = try {
        require(file.isFile) { "书签路径不是文件" }
        parseMarks(JSONObject(file.readText()), requireArrays = false)
    } catch (error: Exception) {
        DurableFiles.keepDamaged(file)
        throw CorruptMarksException(error)
    }

    fun marks(id: String): Marks {
        val file = File(File(root, id), "marks.json")
        if (!file.exists()) return Marks()
        return readMarksFile(file)
    }

    /** 本机旧版可能缺少顶层数组；一旦字段存在，不能让 fromJson 的 opt* 静默替换错误值。 */
    private fun parseMarks(json: JSONObject, requireArrays: Boolean): Marks {
        require((!requireArrays && !json.has("bookmarks") || json.optJSONArray("bookmarks") != null) &&
            (!requireArrays && !json.has("notes") || json.optJSONArray("notes") != null)) { "书签文件结构损坏" }
        fun longInteger(value: Any?): Boolean = value is Byte || value is Short || value is Int || value is Long
        fun intInteger(value: Any?): Boolean = longInteger(value) && (value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        fun checkFields(item: JSONObject, requiredText: List<String>, requiredInt: List<String>,
                        optionalText: List<String>, optionalInt: List<String>, optionalLong: List<String>, optionalBool: List<String>) {
            requiredText.forEach { key -> require(item.opt(key) is String) { "书签字段 $key 类型错误" } }
            requiredInt.forEach { key -> require(intInteger(item.opt(key))) { "书签字段 $key 类型错误" } }
            optionalText.forEach { key -> require(!item.has(key) || item.opt(key) is String) { "书签字段 $key 类型错误" } }
            optionalInt.forEach { key -> require(!item.has(key) || intInteger(item.opt(key))) { "书签字段 $key 类型错误" } }
            optionalLong.forEach { key -> require(!item.has(key) || longInteger(item.opt(key))) { "书签字段 $key 类型错误" } }
            optionalBool.forEach { key -> require(!item.has(key) || item.opt(key) is Boolean) { "书签字段 $key 类型错误" } }
        }
        val bookmarks = json.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bookmarks.length()) {
            val item = bookmarks.getJSONObject(i)
            checkFields(item, listOf("id", "title"), listOf("chapter", "item"),
                listOf("excerpt", "anchorText"), listOf("char", "anchorOffset"), listOf("at"), listOf("lost"))
        }
        val notes = json.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notes.length()) {
            val item = notes.getJSONObject(i)
            checkFields(item, listOf("id", "title", "quote"), listOf("chapter", "block", "start", "end"),
                listOf("text", "color"), emptyList(), listOf("at"), listOf("lost"))
            if (item.has("color")) require(MarkColor.values().any { it.name == item.getString("color") }) { "批注颜色无效" }
        }
        val marks = Marks.fromJson(json)
        // 只查位置是否自洽。章名、引文可以为空：EPUB 目录里的空标题会原样成为章名，
        // 应用自己就能写出这样的书签，不能因此把整本书挡在恢复之外。
        require(marks.bookmarks.all { mark ->
            mark.id.isNotBlank() && mark.chapterIndex >= 0 &&
                mark.itemIndex >= 0 && mark.charOffset >= 0 && mark.anchor.offset in 0..mark.anchor.text.length
        }) { "书签位置损坏" }
        require(marks.notes.all { note ->
            note.id.isNotBlank() && note.chapterIndex >= 0 &&
                note.blockIndex >= 0 && note.start >= 0 && note.end > note.start
        }) { "批注范围损坏" }
        return marks
    }

    /** 备份里一旦声明有书签文件，就必须能完整读回；坏书签不能替换本机的。 */
    private fun validateBackupMarks(dir: File) {
        val file = File(dir, "marks.json")
        if (!file.exists()) return
        require(file.isFile) { "书签文件无效" }
        parseMarks(JSONObject(file.readText()), requireArrays = true)
    }

    @Synchronized
    fun saveMarks(id: String, marks: Marks) {
        val dir = File(root, id).takeIf { it.isDirectory } ?: return
        val file = File(dir, "marks.json")
        // 即使调用方把读坏的书签当成空列表，也不能用它覆盖仍可人工恢复的原文件。
        if (file.exists()) readMarksFile(file)
        check(DurableFiles.write(file, marks.toJson().toString().toByteArray())) { "无法保存书签与批注" }
    }

    /** 从别的应用打开同一个文件时，找回书架上已有的那本，而不是再导入一份。 */
    fun findSame(picked: Picked): BookEntry? {
        val candidates = list().filter { e -> e.sources.size == 1 && e.sources[0].name == picked.name }
        if (candidates.isEmpty()) return null
        val incoming = File(context.cacheDir, "compare-${UUID.randomUUID()}")
        return try {
            if (runCatching { copySource(picked.uri, picked.name, incoming) }.isFailure) return null
            candidates.firstOrNull { e ->
                sameFile(File(File(root, e.id), e.sources[0].file), incoming, {})
            }
        } finally {
            incoming.delete()
        }
    }

    /** 书架上来源正是这个文件（同一个 URI、只有这一份来源）的书；内容可能已经和书架上的不同。 */
    fun findBySource(picked: Picked): BookEntry? =
        list().firstOrNull { e -> e.sources.size == 1 && e.sources[0].uri == picked.uri.toString() }

    data class RestoreResult(
        val books: Int,
        val settings: JSONObject?,
        val data: Map<String, String> = emptyMap(),
        val skippedBooks: Int = 0,
    )

    /**
     * 备份：书架索引、每本书的来源副本和书签批注，以及传入的设置。缓存和解出的插图可重建，不打包。
     */
    // 不加锁：打包整个书架可能要几分钟，期间保存进度、改名、删书都不该排队等它。
    // 索引按改名原子替换，读到的总是完整的一份；书签批注同样先写临时文件再改名。
    /**
     * 备份里不留书源的下载地址：有的书源（如 Kavita）把 API 密钥写在下载链接的路径里，
     * 备份文件常被存到云盘、发给别人。恢复后这些书不能“从原文件刷新”，从书源重新下载即可。
     */
    private fun withoutRemoteAddresses(indexBytes: ByteArray): ByteArray {
        val array = runCatching { JSONArray(indexBytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return indexBytes
        for (i in 0 until array.length()) {
            val sources = array.optJSONObject(i)?.optJSONArray("sources") ?: continue
            for (k in 0 until sources.length()) {
                val source = sources.optJSONObject(k) ?: continue
                val uri = source.optString("uri")
                if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) source.put("uri", "")
            }
        }
        return array.toString().toByteArray()
    }

    fun backup(out: java.io.OutputStream, settings: JSONObject, data: Map<String, ByteArray> = emptyMap()): Int {
        val indexBytes = if (indexFile.isFile) indexFile.readBytes() else {
            check(root.listFiles().orEmpty().none { it.isDirectory && BACKUP_ID.matches(it.name) }) {
                "书架索引缺失；原书籍文件已保留，请先恢复索引"
            }
            "[]".toByteArray()
        }
        // 读不出的个别条目原样留在打包的索引里，能读的书照常备份：书架出问题时正是最需要备份的时候。
        val entries = parseIndex(indexBytes.toString(Charsets.UTF_8)).first
        // 来源副本是恢复阅读所必需的，任何缺失都必须使整份备份失败。
        entries.forEach { entry ->
            check(BACKUP_ID.matches(entry.id) && entry.sources.isNotEmpty()) { "书架包含无效的书籍记录" }
            entry.sources.forEach { source ->
                check(BACKUP_SOURCE_FILE.matches(source.file) && File(File(root, entry.id), source.file).isFile) {
                    "书籍来源文件缺失：${entry.title} / ${source.name}"
                }
            }
        }
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(name: String, write: (java.io.OutputStream) -> Unit) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                write(zip)
                zip.closeEntry()
            }
            put(BACKUP_MANIFEST) {
                it.write(JSONObject().put("format", 1).put("createdAt", System.currentTimeMillis()).put("settings", settings).toString().toByteArray())
            }
            put("books/index.json") { it.write(withoutRemoteAddresses(indexBytes)) }
            data.forEach { (name, bytes) -> put("data/$name") { it.write(bytes) } }
            entries.forEach { e ->
                val dir = File(root, e.id)
                e.sources.forEach { source ->
                    val f = File(dir, source.file)
                    check(f.isFile) { "书籍来源文件缺失：${e.title} / ${source.name}" }
                    put("books/${e.id}/${source.file}") { o -> f.inputStream().use { it.copyTo(o) } }
                }
                val extras = listOf("marks.json", RULES) + dir.list { _, n -> AI_BOOK_FILE.matches(n) }.orEmpty().sorted()
                extras.forEach { name ->
                    val f = File(dir, name)
                    if (f.isFile) put("books/${e.id}/$name") { o -> f.inputStream().use { it.copyTo(o) } }
                }
            }
        }
        return entries.size
    }

    /**
     * 恢复：备份里的书加回书架，同一本书（同一 id）用备份里的版本整体替换。
     * 只接受固定格式的条目路径，防止压缩包里的“../”之类路径写到书架目录之外。
     */
    @Synchronized
    fun restore(input: java.io.InputStream, validateData: (Map<String, String>) -> Unit = {}): RestoreResult {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var index: JSONArray? = null
            val data = HashMap<String, String>()
            java.util.zip.ZipInputStream(input).use { zip ->
                var entryCount = 0
                var unpackedBytes = 0L
                val names = HashSet<String>()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                fun consume(output: java.io.OutputStream? = null, entryLimit: Long = MAX_BACKUP_UNPACKED_BYTES) {
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        unpackedBytes += read
                        require(entryBytes <= entryLimit && unpackedBytes <= MAX_BACKUP_UNPACKED_BYTES) {
                            "备份解压内容过大，未修改现有数据"
                        }
                        output?.write(buffer, 0, read)
                    }
                }
                fun readSmall(limit: Long): ByteArray = ByteArrayOutputStream().also { consume(it, limit) }.toByteArray()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(++entryCount <= MAX_BACKUP_ENTRIES) { "备份条目过多，未修改现有数据" }
                    require(names.add(name)) { "备份包含重复文件，未修改现有数据" }
                    when {
                        entry.isDirectory -> consume()
                        name == BACKUP_MANIFEST -> manifest = JSONObject(readSmall(MAX_BACKUP_MANIFEST_BYTES).toString(Charsets.UTF_8))
                        name == "books/index.json" -> index = JSONArray(readSmall(MAX_BACKUP_JSON_BYTES).toString(Charsets.UTF_8))
                        BACKUP_DATA_FILE.matches(name) -> data[name.removePrefix("data/")] = readSmall(MAX_BACKUP_JSON_BYTES).toString(Charsets.UTF_8)
                        BACKUP_BOOK_FILE.matches(name) -> {
                            val target = File(staging, name)
                            target.parentFile!!.mkdirs()
                            val fileName = name.substringAfterLast('/')
                            val limit = when {
                                BACKUP_SOURCE_FILE.matches(fileName) -> sourceLimit(fileName)
                                fileName.endsWith(".json") -> MAX_BACKUP_JSON_BYTES
                                else -> MAX_BACKUP_UNPACKED_BYTES
                            }
                            target.outputStream().use { consume(it, limit) }
                        }
                        else -> consume() // 不认识的条目也要计入解压上限
                    }
                }
            }
            // 调用方可在任何目录交换前验证书外数据；验证失败时本次恢复不修改旧书。
            validateData(data)
            val books = index ?: throw IllegalArgumentException("不是灵阅的备份文件")
            requireNotNull(manifest) { "不是灵阅的备份文件" }
            // 同一目录不能安装两次：重复 ID 会让回滚把刚还原的旧书再次移走。
            val seenIds = HashSet<String>()
            for (i in 0 until books.length()) {
                val id = books.optJSONObject(i)?.optString("id").orEmpty()
                if (BACKUP_ID.matches(id)) require(seenIds.add(id)) { "备份索引包含重复书籍 ID，未修改现有数据" }
            }
            val candidates = (0 until books.length()).mapNotNull { i ->
                val e = runCatching { books.getJSONObject(i).toEntry() }.getOrNull() ?: return@mapNotNull null
                if (!BACKUP_ID.matches(e.id)) return@mapNotNull null
                val from = File(staging, "books/${e.id}")
                if (e.sources.isEmpty() || e.sources.any { !BACKUP_SOURCE_FILE.matches(it.file) || !File(from, it.file).isFile }) return@mapNotNull null
                e
            }
            if (candidates.isEmpty()) {
                require(books.length() == 0) { "备份中的书籍文件不完整，未修改现有数据" }
                return RestoreResult(0, manifest?.optJSONObject("settings"), data)
            }
            val transaction = File(root, ".restore-${UUID.randomUUID()}")
            val prepared = File(transaction, "prepared")
            val previous = File(transaction, "previous")
            val restored = mutableListOf<BookEntry>()
            try {
                // 先把所有书完整复制到 books/ 所在文件系统，复制失败不影响现有书籍。
                for (e in candidates) {
                    val from = File(staging, "books/${e.id}")
                    val next = File(prepared, e.id)
                    check(from.copyRecursively(next)) { "无法复制备份书籍：${e.title}" }
                    val existing = File(root, e.id)
                    // 新书也须读得出正文；不能把损坏的 EPUB 等报作“已恢复”。
                    val content = runCatching {
                        if (existing.isDirectory) {
                            require(e.sources.none { source ->
                                val old = File(existing, source.file)
                                File(next, source.file).length() == 0L && old.isFile && old.length() > 0L
                            }) { "来源文件为空" }
                        }
                        validateBackupMarks(next)
                        // 旧版备份可能没有 marks.json；缺席不代表同意清空本机已有书签。
                        val oldMarks = File(existing, "marks.json")
                        if (!File(next, "marks.json").exists() && oldMarks.exists()) {
                            require(oldMarks.isFile) { "本机书签文件无效" }
                            oldMarks.copyTo(File(next, "marks.json"))
                        }
                        // 备份里没有的 AI 数据（旧版备份不含这些，或备份之后才生成的）沿用本机的，不随整个目录一起被换掉。
                        existing.listFiles { f -> f.isFile && AI_BOOK_FILE.matches(f.name) }?.forEach { f ->
                            val target = File(next, f.name)
                            if (!target.exists()) f.copyTo(target)
                        }
                        val parsed = buildCache(next, e.sources)
                        require(parsed.chapters.isNotEmpty()) { "没有读出正文" }
                        parsed
                    }.getOrNull()
                    if (content == null) {
                        next.deleteRecursively()
                        continue
                    }
                    restored += e.copy(chapterCount = content.chapters.size, charCount = content.charCount,
                        cover = content.cover, description = content.description)
                }
                require(restored.isNotEmpty()) { "备份中的书籍均无法读取（正文或书签损坏），未修改现有数据" }
                try {
                    prepareSwap(transaction, restored.map { it.id })
                    for (e in restored) {
                        val current = File(root, e.id)
                        val next = File(prepared, e.id)
                        if (current.exists()) {
                            check(previous.isDirectory || previous.mkdirs()) { "无法准备书籍恢复目录" }
                            check(current.renameTo(File(previous, e.id))) { "无法保存旧书籍文件：${e.title}" }
                        }
                        check(next.renameTo(current)) { "无法恢复书籍文件：${e.title}" }
                    }
                    val ids = restored.map { it.id }.toSet()
                    update { current -> current.filterNot { it.id in ids } + restored }
                    commitSwap(transaction)
                } catch (error: Throwable) {
                    if (File(transaction, SWAP_PREPARED).isFile && !File(transaction, SWAP_COMMITTED).isFile) {
                        try {
                            rollbackSwap(transaction)
                        } catch (rollbackError: Throwable) {
                            error.addSuppressed(rollbackError)
                        }
                    }
                    throw error
                }
                return RestoreResult(restored.size, manifest?.optJSONObject("settings"), data, books.length() - restored.size)
            } finally {
                // 有日志的事务留给下次启动；未开始目录交换的临时副本可以删除。
                if (!File(transaction, SWAP_PREPARED).isFile && !previous.exists()) transaction.deleteRecursively()
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    fun addReadingTime(id: String, millis: Long) = update { list ->
        list.map { if (it.id == id) it.copy(readMillis = it.readMillis + millis) else it }
    }

    fun rename(id: String, title: String) = update { list -> list.map { if (it.id == id) it.copy(title = title) else it } }

    fun setGroups(id: String, groups: List<String>) = update { list -> list.map { if (it.id == id) it.copy(groups = groups.distinct()) else it } }

    /** 分组改名（并到同名分组里也行）；[to] 为空就是删除分组，书留在书架上。 */
    fun renameGroup(from: String, to: String?) = update { list ->
        list.map { e ->
            if (from !in e.groups) e
            else e.copy(groups = e.groups.flatMap { g -> if (g == from) listOfNotNull(to) else listOf(g) }.distinct())
        }
    }

    fun saveProgress(id: String, progress: ReadingProgress) = update { list ->
        list.map { if (it.id == id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it }
    }

    @Synchronized
    fun remove(id: String) {
        val entry = list().firstOrNull { it.id == id } ?: return
        update { list -> list.filterNot { it.id == id } }
        entry.sources.mapNotNull { it.uri }.forEach { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        File(root, id).deleteRecursively()
    }

    @Synchronized
    private fun update(transform: (List<BookEntry>) -> List<BookEntry>) {
        clearPendingCommittedSwaps()
        val (entries, damaged) = readIndex()
        check(damaged == 0) { "书架索引里有 $damaged 条记录读不出，已停止写入并保留原文件" }
        val array = JSONArray()
        transform(entries).forEach { array.put(it.toJson()) }
        check(DurableFiles.write(indexFile, array.toString().toByteArray())) { "无法保存书架索引" }
    }

    private fun load(dir: File, sources: List<SourceRef>) =
        sources.map { BookParser.SourceBytes(it.name, File(dir, it.file)) }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun BookEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author.orEmpty())
        .put("format", format.name)
        .put("addedAt", addedAt)
        .put("openedAt", openedAt)
        .put("chapterCount", chapterCount)
        .put("charCount", charCount)
        .put("description", description.orEmpty())
        .put("cover", cover.orEmpty())
        .put("readMillis", readMillis)
        .put("groups", JSONArray(groups))
        .put("sources", JSONArray().apply {
            sources.forEach { put(JSONObject().put("name", it.name).put("uri", it.uri.orEmpty()).put("file", it.file)) }
        })
        .apply {
            progress?.let {
                put("progress", JSONObject()
                    .put("chapter", it.chapterIndex)
                    .put("title", it.chapterTitle)
                    .put("item", it.itemIndex)
                    .put("offset", it.itemOffset)
                    .put("char", it.charOffset)
                    .put("anchorText", it.anchor.text)
                    .put("anchorOffset", it.anchor.offset))
            }
        }

    private fun JSONObject.toEntry(): BookEntry {
        val src = getJSONArray("sources")
        return BookEntry(
            id = getString("id"),
            title = getString("title"),
            author = optString("author").ifEmpty { null },
            format = runCatching { BookFormat.valueOf(getString("format")) }.getOrDefault(BookFormat.TXT),
            sources = (0 until src.length()).map { i ->
                val o = src.getJSONObject(i)
                SourceRef(o.getString("name"), o.optString("uri").ifEmpty { null }, o.getString("file"))
            },
            addedAt = getLong("addedAt"),
            openedAt = optLong("openedAt"),
            chapterCount = optInt("chapterCount"),
            charCount = optInt("charCount"),
            description = optString("description").ifEmpty { null },
            cover = optString("cover").ifEmpty { null },
            readMillis = optLong("readMillis"),
            groups = optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            progress = optJSONObject("progress")?.let {
                ReadingProgress(
                    it.getInt("chapter"), it.optString("title"), it.optInt("item"), it.optInt("offset"), it.optInt("char"),
                    TextAnchor(it.optString("anchorText"), it.optInt("anchorOffset")),
                )
            },
        )
    }
}
