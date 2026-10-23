package com.xycs.lingyuereader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 书架。每本书一个目录，存导入文件的副本，离线也能读；同时记下来源地址，
 * 原文件（如云盘里的文稿）改过之后可以“刷新”。
 */
class Library(private val context: Context) {

    private val root = File(context.filesDir, "books").apply { mkdirs() }

    init {
        BookParser.pdfAspects = PdfPages::aspects
    }
    private val indexFile = File(root, "index.json")

    private companion object {
        const val CACHE = "cache.bin"
        const val RULES = "rules.json"
        const val BACKUP_MANIFEST = "lingyue-backup.json"
        val BACKUP_ID = Regex("""[A-Za-z0-9-]{1,64}""")
        val BACKUP_SOURCE_FILE = Regex("""s\d{1,4}\.[A-Za-z0-9]{1,10}""")
        /** 书以外的应用数据（生词本、阅读统计），按文件名放在 data/ 下。 */
        val BACKUP_DATA_FILE = Regex("""data/(vocabulary|stats)\.json""")
        val BACKUP_BOOK_FILE = Regex("""books/[A-Za-z0-9-]{1,64}/(s\d{1,4}\.[A-Za-z0-9]{1,10}|marks\.json|rules\.json)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    class Picked(val uri: Uri, val name: String)

    @Synchronized
    fun list(): List<BookEntry> {
        if (!indexFile.isFile) return emptyList()
        return parseIndex(indexFile.readText())
    }

    private fun parseIndex(json: String): List<BookEntry> {
        val array = try {
            JSONArray(json)
        } catch (error: Exception) {
            throw IllegalStateException("书架索引损坏，已停止写入并保留原文件", error)
        }
        return try {
            (0 until array.length()).map { array.getJSONObject(it).toEntry() }
        } catch (error: Exception) {
            throw IllegalStateException("书架索引损坏，已停止写入并保留原文件", error)
        }
    }

    fun describe(uris: List<Uri>): List<Picked> =
        uris.map { Picked(it, displayName(it) ?: it.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt") }

    /** 导入一本书；[files] 多于一个时合成一本。 */
    @Synchronized
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
                requireNotNull(context.contentResolver.openInputStream(picked.uri)) { "无法打开文件：${picked.name}" }.use { input ->
                    File(dir, file).outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
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
                author = content.author,
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
        return BookCache.read(File(dir, CACHE), fingerprint(dir, entry.sources)) ?: buildCache(dir, entry.sources)
    }

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
        return entry.sources.map { TextDecoder.decode(File(dir, it.file).readBytes()) }
    }

    /** 按 [rules] 试解析，不写缓存（规则页预览分章用）。 */
    fun previewWith(entry: BookEntry, rules: TextRules): BookContent = BookParser.parse(load(File(root, entry.id), entry.sources), rules)

    /** 保存文本规则并重新解析。 */
    @Synchronized
    fun saveRules(entry: BookEntry, rules: TextRules): BookContent {
        val dir = File(root, entry.id)
        require(dir.isDirectory) { "书籍文件不存在" }
        val staging = File(root, ".rules-${entry.id}-${UUID.randomUUID()}")
        val next = File(staging, "next")
        val previous = File(staging, "previous")
        var committed = false
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
            try {
                check(dir.renameTo(previous)) { "无法保存旧书籍文件" }
                check(next.renameTo(dir)) { "无法应用新的文本规则" }
                update { list ->
                    list.map {
                        if (it.id == entry.id) it.copy(chapterCount = content.chapters.size, charCount = content.charCount)
                        else it
                    }
                }
                committed = true
            } catch (error: Throwable) {
                if (previous.isDirectory) {
                    if (dir.exists() && !dir.renameTo(next) && !dir.deleteRecursively()) {
                        error.addSuppressed(IllegalStateException("无法移走新规则书籍文件"))
                    }
                    if (!previous.renameTo(dir)) {
                        error.addSuppressed(IllegalStateException("无法还原旧书籍文件：${previous.absolutePath}"))
                    }
                }
                throw error
            }
            // buildCache 的章节按需读取 staging/cache.bin；目录已移动，须从正式路径重新打开。
            return this.content(entry)
        } finally {
            // 回滚失败时留下 previous，保留唯一的旧副本供人工恢复。
            if (committed || !previous.exists()) staging.deleteRecursively()
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

        /** 解出一张图到 img/，返回文件名。 */
        fun extract(source: SourceRef, path: String): String? = runCatching {
            val file = File(dir, source.file)
            when {
                path.startsWith("@res/") -> {
                    val data = BookParser.resource(BookParser.SourceBytes(source.name, file), path.removePrefix("@res/")) ?: return@runCatching null
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
                    java.util.zip.ZipFile(file).use { zip ->
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

    /** 从原位置重读每份文件。先在临时目录验证新书，失败时保留旧副本和缓存。 */
    @Synchronized
    fun refresh(entry: BookEntry): RefreshResult {
        val dir = File(root, entry.id)
        require(dir.isDirectory) { "书籍文件不存在" }
        val staging = File(root, ".refresh-${entry.id}-${UUID.randomUUID()}")
        val incoming = File(staging, "incoming")
        val next = File(staging, "next")
        val previous = File(staging, "previous")
        var changed = 0
        var failed = 0
        var committed = false
        try {
            for (s in entry.sources) {
                val bytes = s.uri?.let { runCatching { readUri(it.toUri()) }.getOrNull() }
                if (bytes == null) {
                    failed++
                    continue
                }
                val target = File(dir, s.file)
                if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                    require(bytes.isNotEmpty()) { "来源文件为空：${s.name}" }
                    check(incoming.isDirectory || incoming.mkdirs()) { "无法准备书籍刷新目录" }
                    File(incoming, s.file).writeBytes(bytes)
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
                check(dir.renameTo(previous)) { "无法保存旧书籍文件" }
                check(next.renameTo(dir)) { "无法替换书籍文件" }
                update { list ->
                    list.map {
                        if (it.id == entry.id) {
                            it.copy(chapterCount = content.chapters.size, charCount = content.charCount, cover = content.cover, description = content.description)
                        } else it
                    }
                }
                committed = true
            } catch (error: Throwable) {
                if (previous.isDirectory) {
                    if (dir.exists() && !dir.renameTo(next) && !dir.deleteRecursively()) {
                        error.addSuppressed(IllegalStateException("无法移走刷新后的书籍文件"))
                    }
                    if (!previous.renameTo(dir)) {
                        error.addSuppressed(IllegalStateException("无法恢复旧书籍文件：${previous.absolutePath}"))
                    }
                }
                throw error
            }
            return RefreshResult(changed, failed)
        } finally {
            // 回滚失败时留下 previous，避免把唯一的旧副本也删掉。
            if (committed || !previous.exists()) staging.deleteRecursively()
        }
    }

    data class RefreshResult(val changed: Int, val failed: Int)

    fun marks(id: String): Marks {
        val file = File(File(root, id), "marks.json")
        if (!file.isFile) return Marks()
        return runCatching { Marks.fromJson(JSONObject(file.readText())) }.getOrDefault(Marks())
    }

    @Synchronized
    fun saveMarks(id: String, marks: Marks) {
        val dir = File(root, id).takeIf { it.isDirectory } ?: return
        val tmp = File(dir, "marks.json.tmp")
        tmp.writeText(marks.toJson().toString())
        check(tmp.renameTo(File(dir, "marks.json"))) { "无法保存书签与批注" }
    }

    /** 从别的应用打开同一个文件时，找回书架上已有的那本，而不是再导入一份。 */
    fun findSame(picked: Picked): BookEntry? {
        val candidates = list().filter { e -> e.sources.size == 1 && e.sources[0].name == picked.name }
        if (candidates.isEmpty()) return null
        val bytes = runCatching { readUri(picked.uri) }.getOrNull() ?: return null
        return candidates.firstOrNull { e ->
            val f = File(File(root, e.id), e.sources[0].file)
            f.isFile && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)
        }
    }

    data class RestoreResult(
        val books: Int,
        val settings: JSONObject?,
        val data: Map<String, String> = emptyMap(),
        val skippedBooks: Int = 0,
    )

    /**
     * 备份：书架索引、每本书的来源副本和书签批注，以及传入的设置。缓存和解出的插图可重建，不打包。
     */
    @Synchronized
    fun backup(out: java.io.OutputStream, settings: JSONObject, data: Map<String, ByteArray> = emptyMap()): Int {
        val indexBytes = if (indexFile.isFile) indexFile.readBytes() else {
            check(root.listFiles().orEmpty().none { it.isDirectory && BACKUP_ID.matches(it.name) }) {
                "书架索引缺失；原书籍文件已保留，请先恢复索引"
            }
            "[]".toByteArray()
        }
        val entries = parseIndex(indexBytes.toString(Charsets.UTF_8))
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
            put("books/index.json") { it.write(indexBytes) }
            data.forEach { (name, bytes) -> put("data/$name") { it.write(bytes) } }
            entries.forEach { e ->
                val dir = File(root, e.id)
                e.sources.forEach { source ->
                    val f = File(dir, source.file)
                    check(f.isFile) { "书籍来源文件缺失：${e.title} / ${source.name}" }
                    put("books/${e.id}/${source.file}") { o -> f.inputStream().use { it.copyTo(o) } }
                }
                listOf("marks.json", RULES).forEach { name ->
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
    fun restore(input: java.io.InputStream): RestoreResult {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var index: JSONArray? = null
            val data = HashMap<String, String>()
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == BACKUP_MANIFEST -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        name == "books/index.json" -> index = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        BACKUP_DATA_FILE.matches(name) -> data[name.removePrefix("data/")] = zip.readBytes().toString(Charsets.UTF_8)
                        BACKUP_BOOK_FILE.matches(name) -> {
                            val target = File(staging, name)
                            target.parentFile!!.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                        else -> Unit // 不认识的条目一律忽略
                    }
                }
            }
            val books = index ?: throw IllegalArgumentException("不是灵阅的备份文件")
            requireNotNull(manifest) { "不是灵阅的备份文件" }
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
            val installed = mutableListOf<String>()
            val movedOld = mutableListOf<String>()
            var committed = false
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
                require(restored.isNotEmpty()) { "备份中的书籍均无法读取，未修改现有数据" }
                try {
                    for (e in restored) {
                        val current = File(root, e.id)
                        val next = File(prepared, e.id)
                        if (current.exists()) {
                            check(previous.isDirectory || previous.mkdirs()) { "无法准备书籍恢复目录" }
                            check(current.renameTo(File(previous, e.id))) { "无法保存旧书籍文件：${e.title}" }
                            movedOld += e.id
                        }
                        check(next.renameTo(current)) { "无法恢复书籍文件：${e.title}" }
                        installed += e.id
                    }
                    val ids = restored.map { it.id }.toSet()
                    update { current -> current.filterNot { it.id in ids } + restored }
                    committed = true
                } catch (error: Throwable) {
                    // 倒序撤销所有已安装的书，最后还原原目录及原索引。
                    for (e in restored.asReversed()) {
                        val current = File(root, e.id)
                        if (e.id in installed && current.exists()) {
                            val discard = File(transaction, "discard/${e.id}")
                            discard.parentFile?.mkdirs()
                            if (!current.renameTo(discard) && !current.deleteRecursively()) {
                                error.addSuppressed(IllegalStateException("无法移走恢复后的书籍文件：${e.title}"))
                            }
                        }
                        if (e.id in movedOld && !File(previous, e.id).renameTo(current)) {
                            error.addSuppressed(IllegalStateException("无法还原旧书籍文件：${e.title}"))
                        }
                    }
                    throw error
                }
                return RestoreResult(restored.size, manifest?.optJSONObject("settings"), data, books.length() - restored.size)
            } finally {
                // 如果回滚也失败，保留 previous 中的原书副本供恢复。
                if (committed || previous.listFiles().isNullOrEmpty()) transaction.deleteRecursively()
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
        val array = JSONArray()
        transform(list()).forEach { array.put(it.toJson()) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(array.toString())
        check(tmp.renameTo(indexFile)) { "无法保存书架索引" }
    }

    private fun load(dir: File, sources: List<SourceRef>) =
        sources.map { BookParser.SourceBytes(it.name, File(dir, it.file)) }

    private fun readUri(uri: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开文件" }.use { it.readBytes() }

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
