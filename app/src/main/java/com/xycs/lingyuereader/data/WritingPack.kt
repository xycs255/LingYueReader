package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 设定包里的文档类型。[priority] 是默认优先级（数字小的优先：冲突时以它为准），
 * [always] 是默认是否“常用”（每次请 AI 都带上；“按需”的只在用到时带）。
 */
enum class PackKind(val label: String, val priority: Int, val always: Boolean, val hint: String) {
    INSTRUCTION("工作说明", 0, true, "给 AI 的工作说明：先读什么、以谁为准、怎么改稿；放在最前面，AI 照它办事"),
    OUTLINE("总纲", 1, true, "全书走向、主线、结局；冲突时最终以它为准"),
    INVARIANT("不变量", 2, true, "定下来就不能动的硬数字、生死身份、要回收的原话和物件"),
    VOLUME("卷纲细纲", 2, false, "每卷、每章的事实边界与钩子"),
    CANON("硬性设定", 3, true, "年代、制度、地理、势力、资源等事实"),
    CHARACTER("人物档案", 3, true, "人物卡：想要、害怕、怎么说话、标志物、怎么变"),
    AWARENESS("察觉表", 4, true, "谁从第几章起知道什么、消息从哪来"),
    FORESHADOW("伏笔表", 4, true, "埋下了什么、第几章埋、计划第几章回收、回收了没有"),
    STYLE("写作规范", 5, true, "怎么把事实写成小说：篇幅、对白、旁白、禁用词"),
    VOICE("称谓与语气", 5, true, "称谓边界、各人物说话的腔调"),
    RULES("检查规则", 5, false, "灵阅在本机逐章检查：篇幅、口头禅次数、禁用词和句式、段落长短"),
    TECHNIQUE("技法参考", 6, true, "学来的写法，只取方法不抄原文"),
    REFERENCE("资料", 7, false, "其他参考资料、体检报告、说明"),
}

/** 设定包里的一份文档。正文另存一个文件。 */
data class PackDoc(
    val id: String,
    val kind: PackKind,
    val title: String,
    val priority: Int = kind.priority,
    val always: Boolean = kind.always,
    val updatedAt: Long = 0,
    val words: Int = 0,
)

/** 导入时的一份文件：认成哪类、要不要导、是当设定还是当正文拆成章节。 */
data class PackImportItem(
    val fileName: String,
    val text: String,
    val kind: PackKind,
    val asChapters: Boolean = false,
    /** Empty source files are shown in preview but never selected to replace author material. */
    val include: Boolean = text.isNotBlank(),
    val priority: Int = kind.priority,
    val always: Boolean = kind.always,
    /** 导入后的标题：默认是去掉文件夹和扩展名的文件名。 */
    val title: String = fileName.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { fileName },
    /** 原文里有解不出的字（U+FFFD），预览时提示核对编码。解析导入时算一次，预览重组时不再扫全文。 */
    val garbled: Boolean = '\uFFFD' in text,
)

/** 导入的结果：新加几份、替换几份（旧版进历史）、导入几章正文；[renamed] 是和同一次导入里前面的项重名、加了编号的标题。 */
data class PackImportResult(val added: Int, val replaced: Int, val chapters: Int, val renamed: List<String> = emptyList())

object PackImport {
    /** 和 [taken] 里的标题重名时加编号：“人物档案”→“人物档案（2）”。 */
    fun uniqueTitle(title: String, taken: Set<String>): String {
        if (title !in taken) return title
        var n = 2
        while ("$title（$n）" in taken) n++
        return "$title（$n）"
    }

    /**
     * 各导入项导入后的标题：设定文档和同一次导入里前面的文档重名时加编号（当新文档加，不把前面那份盖掉），
     * 编号不和作品里已有的标题 [existing] 撞；正文和不导的项原样。导入和导入前的预览都按它算。
     */
    fun docTitles(items: List<PackImportItem>, existing: Set<String>): List<String> {
        val used = HashSet<String>()
        return items.map { item ->
            if (!item.include || item.asChapters) return@map item.title
            val title = if (item.title in used) uniqueTitle(item.title, used + existing) else item.title
            used += title
            title
        }
    }

    /** 认得的文本文件。 */
    fun isText(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt", "text")

    private val rules: List<Pair<Regex, PackKind>> = listOf(
        Regex("使用说明|工作说明|工作流程|提示词|(?i)readme") to PackKind.INSTRUCTION,
        Regex("检查规则|自查清单|口癖清单") to PackKind.RULES,
        Regex("伏笔") to PackKind.FORESHADOW,
        Regex("不变量") to PackKind.INVARIANT,
        Regex("察觉|知情") to PackKind.AWARENESS,
        // 称谓、语气放在人物前面：“对白与人物语气”是语气规范，不是人物档案。
        Regex("称谓|对白|语气|口吻|腔调") to PackKind.VOICE,
        Regex("人物|角色|人设") to PackKind.CHARACTER,
        Regex("技法|样板|摘要") to PackKind.TECHNIQUE,
        Regex("约定|规范|写作要求|文风") to PackKind.STYLE,
        Regex("细纲|卷纲|章纲|分章|章组|分镜|因果") to PackKind.VOLUME,
        Regex("接续|索引") to PackKind.INVARIANT,
        Regex("总纲|大纲") to PackKind.OUTLINE,
        Regex("硬性|设定|世界观|战役|地图|制度|势力|年表") to PackKind.CANON,
    )

    /** 正文（已定正文、正文第几章）：导入成章节，不当设定。 */
    private val chapterFile = Regex("正文|第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[-—–~～至到]\\s*[0-9０-９一二三四五六七八九十百]+\\s*章")

    /** 按文件名认类型。写手版、速查这类压缩版比原件低一级。 */
    fun classify(fileName: String, text: String = ""): PackImportItem {
        val name = fileName.substringAfterLast('/').substringBeforeLast('.')
        val path = fileName.replace('\\', '/')
        // A reference manuscript is not an accepted chapter, even when “正文” occurs in its filename.
        if (Regex("(?:^|/)(?:参考|参考资料|维护记录)(?:/|$)|验证样章|参考样章|未定样章|试写样章|排版参照|样章参照").containsMatchIn(path)) {
            return PackImportItem(fileName, text, PackKind.REFERENCE, asChapters = false, always = false)
        }
        // Stage templates often contain “正文”; recognise their role before deciding to split chapters.
        if (Regex("提示词|(?i:prompt)").containsMatchIn(name)) {
            return PackImportItem(fileName, text, PackKind.INSTRUCTION, asChapters = false)
        }
        val planning = Regex("细纲|卷纲|章纲|章组|分章|分镜|总纲|大纲|叙事因果").containsMatchIn(name)
        if (!planning && chapterFile.containsMatchIn(name) && !Regex("约定|规范|写法").containsMatchIn(name)) {
            return PackImportItem(fileName, text, PackKind.REFERENCE, asChapters = true)
        }
        val kind = rules.firstOrNull { (r, _) -> r.containsMatchIn(name) }?.second ?: PackKind.REFERENCE
        val lower = Regex("写手版|速查|简版|压缩").containsMatchIn(name)
        return PackImportItem(fileName, text, kind, priority = kind.priority + if (lower) 1 else 0)
    }

    /** 正文按章名拆开：Markdown 标题或“第X章”“楔子”这类单独成行的章名。没有章名时整份当一章。 */
    fun chapters(text: String, fallbackTitle: String, markdown: Boolean = true): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val t = title
            val b = body.toString().trim()
            if (t != null) out += t to b else if (b.isNotEmpty()) out += fallbackTitle to b
            body.setLength(0)
        }
        var preface: Pair<String, String>? = null
        val lines = text.removePrefix("\uFEFF").lines()
        // Markdown \u7684\u4EE3\u7801\u5757\u6574\u5757\u7559\u5728\u6B63\u6587\u91CC\uFF0C\u91CC\u9762\u7684\u201C\u7B2CX\u7AE0\u201D\u4E0D\u62C6\u7AE0\u3002\u56F4\u680F\u89C4\u5219\u89C1 [MarkdownFences]\uFF1A\u6CA1\u95ED\u5408\u7684\u3001
        // \u6CA1\u6709\u8BED\u8A00\u6807\u8BB0\u53C8\u5199\u7740\u7AE0\u53F7\u7684 ~~~ \u5F53\u573A\u666F\u5206\u9694\u7EBF\uFF0C\u7167\u5E38\u62C6\u7AE0\uFF1BTXT \u4E0D\u8BA4\u56F4\u680F\u3002
        val fenced = if (markdown) MarkdownFences.mask(lines) else BooleanArray(lines.size)
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim().trim('　').trim()
            if (fenced[index]) {
                if (line.isNotEmpty()) body.append(line).append('\n')
                continue
            }
            val heading = Regex("""^#{1,4}\s+(.+)$""").matchEntire(line)?.groupValues?.get(1)?.trim()
            val isTitle = heading?.let { ChapterTitles.isTxtChapterTitle(it) || it.startsWith("第") } ?: ChapterTitles.isTxtChapterTitle(line)
            if (isTitle) {
                // 第一章前面的（“承上一份”“本文件随写作追加”这类说明）不是正文，有章名时不单独成章。
                if (title == null) {
                    preface = body.toString().trim().takeIf { it.isNotEmpty() }?.let { fallbackTitle to it }
                    body.setLength(0)
                } else {
                    flush()
                }
                title = heading ?: line
                continue
            }
            // 文件头的一级标题（文件名、说明）不当正文。
            if (heading != null && title == null) continue
            if (line.isEmpty()) continue
            body.append(line.removePrefix("> ")).append('\n')
        }
        flush()
        if (out.isEmpty()) preface?.let { out += it }
        return out.filter { it.second.isNotBlank() || it.first.isNotBlank() }
    }

    // ---------- 打包 ----------

    private const val MANIFEST = "pack.json"
    const val MAX_TEXT_BYTES = 16 * 1024 * 1024
    const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    const val MAX_IMPORT_FILES = 512
    const val MAX_IMPORT_SOURCES = 512
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 2_048
    const val MAX_ARCHIVE_EXPANDED_BYTES = 128 * 1024 * 1024
    private const val MAX_PATH_CHARS = 512

    private fun sizeLabel(bytes: Int): String = when {
        bytes % (1024 * 1024) == 0 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes 字节"
    }

    /** Read incrementally so a large file or compressed ZIP entry cannot allocate without bound. */
    private fun limitedBytes(input: InputStream, name: String, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        val buffer = ByteArray(8_192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= maxBytes) { "$name 超过 ${sizeLabel(maxBytes)} 导入上限" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun discardBytes(input: InputStream, maxBytes: Int, archiveLimit: Int): Int {
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            require(total.toLong() + read <= maxBytes) { "ZIP 解压后超过 ${sizeLabel(archiveLimit)} 上限" }
            total += read
        }
    }

    fun readText(name: String, input: InputStream, maxBytes: Int = MAX_TEXT_BYTES): String {
        require(maxBytes in 1..MAX_TEXT_BYTES) { "文本导入大小限制无效" }
        return TextDecoder.decode(limitedBytes(input, name, maxBytes))
    }

    /** ZIP names are logical source paths, never filesystem destinations. */
    private fun zipPath(raw: String): String {
        require(raw.isNotEmpty() && raw.length <= MAX_PATH_CHARS) { "ZIP 文件路径无效或过长" }
        val path = Normalizer.normalize(raw.replace('\\', '/'), Normalizer.Form.NFC)
        require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "ZIP 不支持绝对路径：$raw" }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." } && parts.none { part -> part.any { it < ' ' } }) {
            "ZIP 文件路径无效：$raw"
        }
        return parts.joinToString("/")
    }

    private class ZipNameCharsetException(cause: IllegalArgumentException) : RuntimeException(cause)

    /** 设定包打成 zip：每份文档一个 .md，外加 pack.json 记类型、优先级、常用与否，导回来原样还原。 */
    fun writeZip(docs: List<Pair<PackDoc, String>>, out: OutputStream) {
        val used = HashSet<String>()
        val titles = HashSet<String>()
        val manifest = JSONArray()
        ZipOutputStream(out).use { zip ->
            docs.forEach { (doc, text) ->
                val base = doc.title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "未命名" }
                var name = "$base.md"
                var n = 2
                while (!used.add(name)) name = "$base（${n++}）.md"
                // 标题也去重：两份同名的文档导回来时不会按同名互相替换。
                val title = uniqueTitle(doc.title, titles).also { titles += it }
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
                manifest.put(JSONObject().put("file", name).put("title", title).put("kind", doc.kind.name).put("priority", doc.priority).put("always", doc.always))
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("format", 1).put("docs", manifest).toString(2).toByteArray())
            zip.closeEntry()
        }
    }

    /** 读 zip 里的文本文件；有 pack.json 时按它还原属性。文件名先试 UTF-8，再试 GBK。 */
    fun readZip(open: () -> InputStream): List<PackImportItem> = readZip(open, MAX_IMPORT_BYTES, MAX_IMPORT_FILES)

    /** Budgeted overload for a batch of selected files. */
    fun readZip(
        open: () -> InputStream,
        maxTextBytes: Int = MAX_IMPORT_BYTES,
        maxFiles: Int = MAX_IMPORT_FILES,
        maxArchiveBytes: Int = MAX_ARCHIVE_EXPANDED_BYTES,
    ): List<PackImportItem> = readZipMeasured(open, maxTextBytes, maxFiles, maxArchiveBytes).first

    /** Return expanded bytes as well so a batch can share one ZIP budget. */
    internal fun readZipMeasured(
        open: () -> InputStream,
        maxTextBytes: Int,
        maxFiles: Int,
        maxArchiveBytes: Int,
    ): Pair<List<PackImportItem>, Int> {
        require(maxTextBytes in 1..MAX_IMPORT_BYTES && maxFiles in 1..MAX_IMPORT_FILES &&
            maxArchiveBytes in 1..MAX_ARCHIVE_EXPANDED_BYTES) { "ZIP 导入大小限制无效" }
        val files = linkedMapOf<String, String>()
        var manifest: JSONObject? = null
        var expandedBytes = 0L
        fun read(charset: Charset) {
            files.clear()
            manifest = null
            var entries = 0
            var totalBytes = 0L
            expandedBytes = 0L
            val seen = HashSet<String>()
            ZipInputStream(open(), charset).use { zip ->
                while (true) {
                    val e = try { zip.nextEntry } catch (error: IllegalArgumentException) {
                        throw ZipNameCharsetException(error)
                    } ?: break
                    require(++entries <= MAX_ARCHIVE_ENTRIES) { "ZIP 内文件过多（最多 $MAX_ARCHIVE_ENTRIES 项）" }
                    val name = zipPath(e.name)
                    require(seen.add(name)) { "ZIP 内重复文件路径：$name" }
                    if (e.isDirectory) continue
                    val base = name.substringAfterLast('/')
                    val remainingArchive = (maxArchiveBytes - expandedBytes).toInt()
                    require(remainingArchive > 0) { "ZIP 解压后超过 ${sizeLabel(maxArchiveBytes)} 上限" }
                    if (base == ".DS_Store" || base.startsWith("._") || name.startsWith("__MACOSX/") ||
                        (base != MANIFEST && !isText(base))) {
                        expandedBytes += discardBytes(zip, remainingArchive, maxArchiveBytes)
                        continue
                    }
                    when {
                        base == MANIFEST -> {
                            require(manifest == null) { "ZIP 内有多份 pack.json" }
                            val bytes = limitedBytes(zip, name, minOf(MAX_MANIFEST_BYTES, remainingArchive))
                            expandedBytes += bytes.size
                            manifest = try { JSONObject(TextDecoder.decode(bytes)) }
                            catch (error: Exception) { throw IllegalArgumentException("$name 损坏：${error.message ?: "元数据无法读取"}", error) }
                        }
                        else -> {
                            require(files.size < maxFiles) { "ZIP 内文本文件过多（最多 $maxFiles 份）" }
                            val allowed = minOf(MAX_TEXT_BYTES.toLong(), maxTextBytes - totalBytes, remainingArchive.toLong()).toInt()
                            require(allowed > 0) { "ZIP 文本总量超过 ${sizeLabel(maxTextBytes)} 上限" }
                            val bytes = limitedBytes(zip, name, allowed)
                            totalBytes += bytes.size
                            expandedBytes += bytes.size
                            files[name] = try { TextDecoder.decode(bytes) }
                            catch (error: Exception) { throw IllegalArgumentException("$name 无法解码：${error.message ?: "文本损坏"}", error) }
                        }
                    }
                }
            }
            require(entries > 0) { "ZIP 文件损坏或为空" }
        }
        try {
            read(Charsets.UTF_8)
        } catch (e: ZipNameCharsetException) {
            read(Charset.forName("GBK"))
        }
        require(files.isNotEmpty()) { "ZIP 中没有可导入的 Markdown/TXT 文件" }
        val meta = linkedMapOf<String, JSONObject>()
        manifest?.let { root ->
            require(root.opt("format") is Number && root.getInt("format") == 1) { "pack.json 版本不受支持" }
            val docs = root.optJSONArray("docs") ?: throw IllegalArgumentException("pack.json 缺少 docs 列表")
            for (i in 0 until docs.length()) {
                val m = docs.optJSONObject(i) ?: throw IllegalArgumentException("pack.json 第 ${i + 1} 项格式无效")
                val path = zipPath(m.optString("file"))
                require(meta.putIfAbsent(path, m) == null) { "pack.json 重复文件：$path" }
                require(m.opt("kind") is String && runCatching { PackKind.valueOf(m.getString("kind")) }.isSuccess &&
                    m.opt("priority") is Number && m.opt("always") is Boolean &&
                    (!m.has("title") || m.opt("title") is String)) { "pack.json 中 $path 的属性格式无效" }
            }
        }
        val baseCounts = files.keys.groupingBy { it.substringAfterLast('/') }.eachCount()
        val usedMeta = HashSet<String>()
        val items = files.map { (name, text) ->
            val base = name.substringAfterLast('/')
            val key = when {
                name in meta -> name
                baseCounts[base] == 1 && base in meta -> base
                else -> null
            }
            val m = key?.let { usedMeta += it; meta[it] }
            if (m != null) {
                val kind = PackKind.valueOf(m.getString("kind"))
                val item = PackImportItem(name, text, kind, priority = m.optInt("priority", kind.priority), always = m.optBoolean("always", kind.always))
                m.optString("title").takeIf { it.isNotBlank() }?.let { item.copy(title = it) } ?: item
            } else {
                classify(name, text)
            }
        }.sortedBy { it.fileName }
        require(usedMeta.size == meta.size) { "pack.json 引用了 ZIP 中不存在或有歧义的文件" }
        return items to expandedBytes.toInt()
    }

    /** 新建文档时的模板：人物档案给人物卡的五问，写作规范给常用的几条，检查规则给写法示例，其余空着。 */
    fun template(kind: PackKind): String = when (kind) {
        PackKind.CHARACTER -> "## 人物名\n想要：\n害怕：\n怎么说话：\n标志物：\n怎么变：\n"
        PackKind.STYLE -> "## 篇幅\n\n## 对白\n\n## 旁白\n\n## 禁用词与口癖\n"
        PackKind.INVARIANT -> "## 硬数字\n\n## 生死与身份\n\n## 要回收的原话与物件\n\n## 允许改动的范围\n"
        PackKind.AWARENESS -> "| 人物 | 知道什么 | 从第几章起 | 消息从哪来 |\n| --- | --- | --- | --- |\n"
        PackKind.OUTLINE -> "## 一句话\n\n## 主线\n\n## 结局\n"
        PackKind.RULES -> CheckRules.TEMPLATE
        PackKind.FORESHADOW -> "| 伏笔 | 埋下 | 回收 | 状态 |\n| --- | --- | --- | --- |\n| （例）半块玉佩 | 第3章 | 第20章 | |\n"
        else -> ""
    }
}

/** 请 AI 时带上的设定：常用的文档按优先级排好，超出字数就按份数平分、截断。 */
object PackContext {
    const val LIMIT = 8_000

    fun build(docs: List<Pair<PackDoc, String>>, limit: Int = LIMIT): String? {
        val picked = docs.filter { (d, t) -> d.always && t.isNotBlank() }.sortedWith(compareBy({ it.first.priority }, { it.first.kind.ordinal }, { it.first.title }))
        if (picked.isEmpty()) return null
        val out = StringBuilder("下面是作品的设定资料，按优先级排列（数字小的优先）；资料之间有冲突时，以优先级高的为准。\n")
        var left = limit
        picked.forEachIndexed { i, (doc, text) ->
            if (left <= 200) {
                out.append("（还有 ${picked.size - i} 份资料因为太长没有带上）\n")
                return out.toString().trim()
            }
            val quota = left / (picked.size - i)
            val body = text.trim()
            val cut = if (body.length > quota) body.take(quota) + "……（后面省略）" else body
            out.append("\n【").append(doc.kind.label).append("·").append(doc.title).append("（优先级 ").append(doc.priority).append("）】\n").append(cut).append('\n')
            left -= cut.length
        }
        return out.toString().trim()
    }
}
