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

/** 作品里的一章。正文另存一个文件，这里只记章名、字数和最后修改时间。 */
data class WorkChapter(val id: String, val title: String, val words: Int = 0, val updatedAt: Long = 0)

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
        return work
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
    fun saveText(workId: String, chapterId: String, text: String, today: LocalDate = LocalDate.now()): Work? {
        val work = get(workId) ?: return null
        val old = work.chapter(chapterId) ?: return null
        val file = chapterFile(workId, chapterId)
        if (file.isFile && file.readText() == text) return work
        check(DurableFiles.write(file, text.toByteArray())) { "无法保存正文" }
        val words = WordCount.of(text)
        if (words != old.words) addWords(today, words - old.words)
        return update(workId) { w ->
            w.copy(volumes = w.volumes.map { v -> v.copy(chapters = v.chapters.map { if (it.id == chapterId) it.copy(words = words, updatedAt = clock()) else it }) })
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
            array.put(w.toJson().put("texts", texts))
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
            local?.chapters?.filter { c -> incoming.chapter(c.id) == null }?.forEach { chapterFile(local.id, it.id).delete() }
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
        private val ID = Regex("""[A-Za-z0-9-]{1,64}""")

        private fun newId() = UUID.randomUUID().toString()

        private fun Work.toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("author", author).put("synopsis", synopsis)
            .put("createdAt", createdAt).put("updatedAt", updatedAt)
            .put("previewBookId", previewBookId ?: "")
            .put("volumes", JSONArray().apply {
                volumes.forEach { v ->
                    put(JSONObject().put("id", v.id).put("title", v.title).put("chapters", JSONArray().apply {
                        v.chapters.forEach { c -> put(JSONObject().put("id", c.id).put("title", c.title).put("words", c.words).put("updatedAt", c.updatedAt)) }
                    }))
                }
            })

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
                        WorkChapter(c.getString("id"), c.getString("title"), c.optInt("words"), c.optLong("updatedAt"))
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
