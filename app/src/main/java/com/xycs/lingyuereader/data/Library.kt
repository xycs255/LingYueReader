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
    private val indexFile = File(root, "index.json")

    private companion object {
        const val CACHE = "cache.bin"
        const val BACKUP_MANIFEST = "lingyue-backup.json"
        val BACKUP_ID = Regex("""[A-Za-z0-9-]{1,64}""")
        /** 书以外的应用数据（生词本、阅读统计），按文件名放在 data/ 下。 */
        val BACKUP_DATA_FILE = Regex("""data/(vocabulary|stats)\.json""")
        val BACKUP_BOOK_FILE = Regex("""books/[A-Za-z0-9-]{1,64}/(s\d{1,4}\.[A-Za-z0-9]{1,10}|marks\.json)""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    class Picked(val uri: Uri, val name: String)

    @Synchronized
    fun list(): List<BookEntry> {
        if (!indexFile.isFile) return emptyList()
        val array = runCatching { JSONArray(indexFile.readText()) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toEntry() }.getOrNull() }
    }

    fun describe(uris: List<Uri>): List<Picked> =
        uris.map { Picked(it, displayName(it) ?: it.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt") }

    /** 导入一本书；[files] 多于一个时合成一本。 */
    fun add(files: List<Picked>, title: String?): BookEntry {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            val sources = files.mapIndexed { i, picked ->
                val bytes = readUri(picked.uri)
                val ext = picked.name.substringAfterLast('.', "txt")
                val file = "s$i.$ext"
                File(dir, file).writeBytes(bytes)
                runCatching {
                    context.contentResolver.takePersistableUriPermission(picked.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                SourceRef(picked.name, picked.uri.toString(), file)
            }
            val content = buildCache(dir, sources)
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
            return entry
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
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
        val parsed = BookParser.parse(load(dir, sources))
        val withImages = extractImages(dir, sources, parsed)
        val fingerprint = fingerprint(dir, sources)
        val cacheFile = File(dir, CACHE)
        BookCache.write(cacheFile, withImages, fingerprint)
        return BookCache.read(cacheFile, fingerprint) ?: withImages
    }

    /** 来源副本的大小和修改时间，加上缓存版本；任一变化都会让缓存作废。 */
    private fun fingerprint(dir: File, sources: List<SourceRef>): String =
        "v${BookCache.VERSION}|" + sources.joinToString("|") { s ->
            val f = File(dir, s.file)
            "${s.file}:${f.length()}:${f.lastModified()}"
        }

    /** 把书内路径（“来源序号|路径”）的图片解出到 img/ 目录，块里的路径改成文件名；找不到或不支持的图片去掉。 */
    private fun extractImages(dir: File, sources: List<SourceRef>, content: BookContent): BookContent {
        val wanted = buildSet {
            content.cover?.let(::add)
            content.chapters.forEach { c -> c.blocks.forEach { if (it is Block.Image) add(it.src) } }
        }
        val imgDir = File(dir, "img")
        imgDir.deleteRecursively()
        if (wanted.isEmpty()) return content
        imgDir.mkdirs()
        val saved = mutableMapOf<String, String>()
        wanted.groupBy { it.substringBefore('|').toIntOrNull() }.forEach { (index, refs) ->
            val source = index?.let(sources::getOrNull) ?: return@forEach
            runCatching {
                java.util.zip.ZipFile(File(dir, source.file)).use { zip ->
                    refs.forEach { ref ->
                        val path = ref.substringAfter('|')
                        val ext = path.substringAfterLast('.', "").lowercase()
                        if (ext !in IMAGE_EXTENSIONS) return@forEach
                        val entry = zip.getEntry(path) ?: return@forEach
                        val name = "img/${saved.size}.$ext"
                        zip.getInputStream(entry).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                        saved[ref] = name
                    }
                }
            }
        }
        val chapters = content.chapters.map { c ->
            if (c.blocks.none { it is Block.Image }) c
            else c.withBlocks(c.blocks.mapNotNull { b -> if (b is Block.Image) saved[b.src]?.let { b.copy(src = it) } else b })
        }
        return content.copy(chapters = chapters, cover = content.cover?.let(saved::get))
    }

    /** 从原位置重读每份文件。返回是否有变化；读不到的保留旧副本。 */
    fun refresh(entry: BookEntry): RefreshResult {
        val dir = File(root, entry.id)
        var changed = 0
        var failed = 0
        for (s in entry.sources) {
            val bytes = s.uri?.let { runCatching { readUri(it.toUri()) }.getOrNull() }
            if (bytes == null) {
                failed++
                continue
            }
            val target = File(dir, s.file)
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
                changed++
            }
        }
        if (changed > 0) {
            val content = buildCache(dir, entry.sources)
            update { list ->
                list.map {
                    if (it.id == entry.id) {
                        it.copy(chapterCount = content.chapters.size, charCount = content.charCount, cover = content.cover, description = content.description)
                    } else it
                }
            }
        }
        return RefreshResult(changed, failed)
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
        tmp.renameTo(File(dir, "marks.json"))
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

    data class RestoreResult(val books: Int, val settings: JSONObject?, val data: Map<String, String> = emptyMap())

    /**
     * 备份：书架索引、每本书的来源副本和书签批注，以及传入的设置。缓存和解出的插图可重建，不打包。
     */
    fun backup(out: java.io.OutputStream, settings: JSONObject, data: Map<String, ByteArray> = emptyMap()): Int {
        val entries = list()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(name: String, write: (java.io.OutputStream) -> Unit) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                write(zip)
                zip.closeEntry()
            }
            put(BACKUP_MANIFEST) {
                it.write(JSONObject().put("format", 1).put("createdAt", System.currentTimeMillis()).put("settings", settings).toString().toByteArray())
            }
            put("books/index.json") { it.write(indexFile.readBytesOrEmpty()) }
            data.forEach { (name, bytes) -> put("data/$name") { it.write(bytes) } }
            entries.forEach { e ->
                val dir = File(root, e.id)
                (e.sources.map { it.file } + "marks.json").forEach { name ->
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
            val restored = (0 until books.length()).mapNotNull { i ->
                val e = runCatching { books.getJSONObject(i).toEntry() }.getOrNull() ?: return@mapNotNull null
                if (!BACKUP_ID.matches(e.id)) return@mapNotNull null
                val from = File(staging, "books/${e.id}")
                if (e.sources.any { !File(from, it.file).isFile }) return@mapNotNull null
                val to = File(root, e.id)
                to.deleteRecursively()
                from.copyRecursively(to, overwrite = true)
                e
            }
            val ids = restored.map { it.id }.toSet()
            update { current -> current.filterNot { it.id in ids } + restored }
            return RestoreResult(restored.size, manifest?.optJSONObject("settings"), data)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun File.readBytesOrEmpty(): ByteArray = if (isFile) readBytes() else "[]".toByteArray()

    fun addReadingTime(id: String, millis: Long) = update { list ->
        list.map { if (it.id == id) it.copy(readMillis = it.readMillis + millis) else it }
    }

    fun rename(id: String, title: String) = update { list -> list.map { if (it.id == id) it.copy(title = title) else it } }

    fun saveProgress(id: String, progress: ReadingProgress) = update { list ->
        list.map { if (it.id == id) it.copy(progress = progress, openedAt = System.currentTimeMillis()) else it }
    }

    fun remove(id: String) {
        val entry = list().firstOrNull { it.id == id } ?: return
        entry.sources.mapNotNull { it.uri }.forEach { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        update { list -> list.filterNot { it.id == id } }
        File(root, id).deleteRecursively()
    }

    @Synchronized
    private fun update(transform: (List<BookEntry>) -> List<BookEntry>) {
        val array = JSONArray()
        transform(list()).forEach { array.put(it.toJson()) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(indexFile)
    }

    private fun load(dir: File, sources: List<SourceRef>) =
        sources.map { BookParser.SourceBytes(it.name, File(dir, it.file).readBytes()) }

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
                    .put("char", it.charOffset))
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
            progress = optJSONObject("progress")?.let {
                ReadingProgress(it.getInt("chapter"), it.optString("title"), it.optInt("item"), it.optInt("offset"), it.optInt("char"))
            },
        )
    }
}
