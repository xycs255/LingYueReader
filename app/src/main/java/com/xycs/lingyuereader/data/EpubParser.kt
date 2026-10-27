package com.xycs.lingyuereader.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object EpubParser {

    private val textTypes = setOf("application/xhtml+xml", "text/html", "application/x-dtbncx+xml")
    // EPUBs can contain highly compressed XHTML. Keep the import bounded before retaining
    // decoded entries in memory; a normal long novel is well below these limits.
    private const val MAX_COMPRESSED_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 96L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent = parse(unzip(bytes)::get, fallbackTitle)

    /**
     * 从文件按条目随用随读：只解压 container、OPF、目录和正文用到的文本条目，图片等其余条目不解压，
     * 读过的正文字节用完就丢，不像 [unzip] 那样把全部文本条目同时留在内存里。
     */
    fun parse(file: File, fallbackTitle: String): BookContent {
        val zip = try { ZipFile(file) } catch (e: IOException) {
            throw IllegalArgumentException("不是有效的 EPUB：${e.message ?: "压缩包打不开"}")
        }
        return zip.use {
            require(zip.size() <= MAX_ENTRIES) { "EPUB 内文件过多，无法安全导入" }
            var retained = 0L
            parse({ name ->
                val entry = zip.getEntry(name)?.takeIf { wanted(name) && !it.isDirectory }
                entry?.let { found ->
                    zip.getInputStream(found).use { input ->
                        read(input) { size -> require(retained + size <= MAX_TEXT_BYTES) { "EPUB 正文过大，无法安全导入" } }
                    }.also { retained += it.size }
                }
            }, fallbackTitle)
        }
    }

    private fun parse(files: (String) -> ByteArray?, fallbackTitle: String): BookContent {
        val container = files("META-INF/container.xml")?.let(TextDecoder::decode)
            ?: error("不是有效的 EPUB：缺少 container.xml")
        val opfPath = attr(Regex("""<rootfile\b[^>]*>""").find(container)?.value.orEmpty(), "full-path")
            ?: error("不是有效的 EPUB：找不到 OPF")
        val opf = files(opfPath)?.let(TextDecoder::decode) ?: error("不是有效的 EPUB：缺少 $opfPath")
        val base = opfPath.substringBeforeLast('/', "")

        val title = Regex("""<dc:title\b[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.let { HtmlText.decodeEntities(it).trim() }?.ifEmpty { null }
        val author = Regex("""<dc:creator\b[^>]*>(.*?)</dc:creator>""", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.let { HtmlText.decodeEntities(it).trim() }?.ifEmpty { null }
        // 简介里常夹着 HTML 标签（有时还被转义过一次）。
        val description = Regex("""<dc:description\b[^>]*>(.*?)</dc:description>""", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)
            ?.let { HtmlText.decodeEntities(HtmlText.decodeEntities(it)).replace(Regex("<[^>]+>"), "").normalizeSpaces() }
            ?.ifEmpty { null }

        data class Item(val href: String, val type: String, val properties: String)
        val manifest = Regex("""<item\b[^>]*>""").findAll(opf).mapNotNull { m ->
            val id = attr(m.value, "id") ?: return@mapNotNull null
            val href = attr(m.value, "href") ?: return@mapNotNull null
            id to Item(resolve(base, href), attr(m.value, "media-type").orEmpty(), attr(m.value, "properties").orEmpty())
        }.toMap()
        // 封面：EPUB3 的 properties="cover-image"，或 EPUB2 的 <meta name="cover" content="图片id">。
        val coverId = Regex("""<meta\b[^>]*>""").findAll(opf).map { it.value }
            .firstOrNull { attr(it, "name") == "cover" }?.let { attr(it, "content") }
        var cover = (manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') } ?: coverId?.let(manifest::get))
            ?.takeIf { it.type.startsWith("image/") }?.href
        val spineTag = Regex("""<spine\b[^>]*>""").find(opf)?.value.orEmpty()
        // 阅读顺序里引用了清单中没有的项目时略过：不知道它是什么（可能是图片页），没法替它占位。
        val spine = Regex("""<itemref\b[^>]*>""").findAll(opf)
            .filter { attr(it.value, "linear") != "no" }
            .mapNotNull { ref -> attr(ref.value, "idref")?.let(manifest::get) }
            .filter { it.type in textTypes || it.href.endsWith("html", ignoreCase = true) }
            .toList()

        val tocTitles = tocTitles(files, manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href)
            .ifEmpty { tocTitles(files, attr(spineTag, "toc")?.let(manifest::get)?.href) }

        val chapters = mutableListOf<Chapter>()
        var missing = 0
        val noteKeys = linkedSetOf<String>()
        for (item in spine) {
            val bytes = files(item.href)
            if (bytes == null) {
                // 正文文件在压缩包里缺失：不让整本书打不开，也不悄悄少一章，留一章说明缺了什么。
                chapters += ChapterTitles.chapter(
                    tocTitles[item.href] ?: "缺失的章节",
                    listOf(Block.Para("这一章的文件（${item.href}）在 EPUB 里缺失，其余章节照常阅读。", prose = false)),
                )
                missing++
                continue
            }
            val html = TextDecoder.decode(bytes)
            val htmlBase = item.href.substringBeforeLast('/', "")
            // 注释链接换算成“书内路径#锚点”；只写 #锚点 的指向本文件。
            val link = { href: String ->
                val path = href.substringBefore('#')
                (if (path.isEmpty()) item.href else resolve(htmlBase, path)) + "#" + href.substringAfter('#')
            }
            var blocks = HtmlText.toBlocks(html, { src -> resolve(htmlBase, src) }, link, noteKeys)
            // 只有封面图的页面不单独成章（封面另有显示）；没文字也没图的页面略过。
            blocks = blocks.filterNot { it is Block.Image && it.src == cover }
            // 没声明封面时，开头只有一张图的页面就当作封面。
            if (cover == null && chapters.isEmpty() && blocks.size == 1 && blocks[0] is Block.Image) {
                cover = (blocks[0] as Block.Image).src
                continue
            }
            if (blocks.none { it is Block.Para || it is Block.Quote || it is Block.ListItem || it is Block.Image }) continue
            val firstHeading = blocks.firstOrNull() as? Block.Heading
            val chapterTitle = tocTitles[item.href] ?: firstHeading?.text
                ?: if (blocks.all { it is Block.Image }) "插图" else "第 ${chapters.size + 1} 节"
            if (firstHeading != null && firstHeading.text.normalizeSpaces() == chapterTitle.normalizeSpaces()) {
                blocks = blocks.drop(1)
            }
            chapters += ChapterTitles.chapter(chapterTitle, blocks)
        }
        require(missing == 0 || chapters.size > missing) { "不是有效的 EPUB：正文文件都缺失" }
        // 每个注释文件只解码、建一次索引。
        val indexes = HashMap<String, HtmlText.NoteIndex>()
        val notes = noteKeys.mapNotNull { key ->
            val path = key.substringBefore('#')
            val index = indexes.getOrPut(path) { HtmlText.NoteIndex(files(path)?.let(TextDecoder::decode).orEmpty()) }
            index.text(key.substringAfter('#'))?.let { key to it }
        }.toMap()
        return BookContent(title ?: fallbackTitle, author, chapters, description, cover, notes)
    }

    /** 目录：EPUB3 的 nav 或 EPUB2 的 NCX；同一文件取第一个标题。 */
    private fun tocTitles(files: (String) -> ByteArray?, path: String?): Map<String, String> {
        val text = path?.let(files)?.let(TextDecoder::decode) ?: return emptyMap()
        val base = path.substringBeforeLast('/', "")
        val result = linkedMapOf<String, String>()
        if (path.endsWith(".ncx", ignoreCase = true)) {
            Regex("""<navPoint\b.*?<text>(.*?)</text>.*?<content\b[^>]*?src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).forEach { m ->
                    val href = resolve(base, m.groupValues[2].substringBefore('#'))
                    result.putIfAbsent(href, HtmlText.decodeEntities(m.groupValues[1]).normalizeSpaces())
                }
        } else {
            val nav = Regex("""<nav\b[^>]*toc[^>]*>(.*?)</nav>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: text
            Regex("""<a\b[^>]*?href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(nav).forEach { m ->
                val href = resolve(base, m.groupValues[1].substringBefore('#'))
                val label = HtmlText.decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).normalizeSpaces()
                if (label.isNotEmpty()) result.putIfAbsent(href, label)
            }
        }
        return result
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size.toLong() <= MAX_COMPRESSED_BYTES) { "EPUB 文件过大，无法安全导入" }
        val files = mutableMapOf<String, ByteArray>()
        val buffer = ByteArray(16 * 1024)
        var entries = 0
        var total = 0L
        var retained = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "EPUB 内文件过多，无法安全导入" }
                val name = entry.name
                val wanted = wanted(name)
                // A directory entry can still carry compressed bytes. Drain it through the
                // same bounded loop so nextEntry() cannot skip decompression outside our limit.
                val output = if (wanted && !entry.isDirectory) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    total += count
                    require(total <= MAX_UNCOMPRESSED_BYTES) { "EPUB 解压内容过大，无法安全导入" }
                    if (output != null) {
                        require(size <= MAX_ENTRY_BYTES && retained + size <= MAX_TEXT_BYTES) {
                            "EPUB 正文过大，无法安全导入"
                        }
                        output.write(buffer, 0, count)
                    }
                }
                if (output != null) {
                    retained += size
                    files[name] = output.toByteArray()
                }
            }
        }
        return files
    }

    /** 只有这些文本条目会被解压保留；图片、字体等一律不留。 */
    private fun wanted(name: String): Boolean =
        name.endsWith(".xml", ignoreCase = true) || name.endsWith(".opf", ignoreCase = true) ||
            name.endsWith(".ncx", ignoreCase = true) || name.endsWith(".xht", ignoreCase = true) ||
            name.endsWith(".xhtml", ignoreCase = true) || name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true)

    /** 有界地读出一个条目；[check] 收到读到目前为止的字节数，超限时抛出。 */
    private fun read(input: InputStream, check: (Long) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= MAX_ENTRY_BYTES) { "EPUB 正文过大，无法安全导入" }
            check(size)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""(?:^|\s)$name\s*=\s*(["'])(.*?)\1""").find(tag)?.groupValues?.get(2)?.let(HtmlText::decodeEntities)

    internal fun resolve(base: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = (if (base.isEmpty()) decoded else "$base/$decoded").split('/')
        val out = ArrayDeque<String>()
        for (p in parts) when (p) {
            "", "." -> Unit
            ".." -> out.removeLastOrNull()
            else -> out.addLast(p)
        }
        return out.joinToString("/")
    }
}
