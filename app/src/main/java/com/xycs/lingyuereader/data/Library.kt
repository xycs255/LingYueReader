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
            val content = BookParser.parse(load(dir, sources))
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
            )
            update { it + entry }
            return entry
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun content(entry: BookEntry): BookContent = BookParser.parse(load(File(root, entry.id), entry.sources))

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
            val content = content(entry)
            update { list -> list.map { if (it.id == entry.id) it.copy(chapterCount = content.chapters.size, charCount = content.charCount) else it } }
        }
        return RefreshResult(changed, failed)
    }

    data class RefreshResult(val changed: Int, val failed: Int)

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
        .put("sources", JSONArray().apply {
            sources.forEach { put(JSONObject().put("name", it.name).put("uri", it.uri.orEmpty()).put("file", it.file)) }
        })
        .apply {
            progress?.let {
                put("progress", JSONObject()
                    .put("chapter", it.chapterIndex)
                    .put("title", it.chapterTitle)
                    .put("item", it.itemIndex)
                    .put("offset", it.itemOffset))
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
            progress = optJSONObject("progress")?.let {
                ReadingProgress(it.getInt("chapter"), it.optString("title"), it.optInt("item"), it.optInt("offset"))
            },
        )
    }
}
