package com.xycs.lingyuereader.data

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object EpubParser {

    private val textTypes = setOf("application/xhtml+xml", "text/html", "application/x-dtbncx+xml")

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.let(TextDecoder::decode)
            ?: error("不是有效的 EPUB：缺少 container.xml")
        val opfPath = attr(Regex("""<rootfile\b[^>]*>""").find(container)?.value.orEmpty(), "full-path")
            ?: error("不是有效的 EPUB：找不到 OPF")
        val opf = files[opfPath]?.let(TextDecoder::decode) ?: error("不是有效的 EPUB：缺少 $opfPath")
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
        val spine = Regex("""<itemref\b[^>]*>""").findAll(opf)
            .filter { attr(it.value, "linear") != "no" }
            .map { ref ->
                val id = attr(ref.value, "idref") ?: error("不是有效的 EPUB：阅读顺序缺少 idref")
                manifest[id] ?: error("不是有效的 EPUB：阅读顺序引用了缺失的文件 $id")
            }
            .filter { it.type in textTypes || it.href.endsWith("html", ignoreCase = true) }
            .toList()

        val tocTitles = tocTitles(files, manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href)
            .ifEmpty { tocTitles(files, attr(spineTag, "toc")?.let(manifest::get)?.href) }

        val chapters = mutableListOf<Chapter>()
        val noteKeys = linkedSetOf<String>()
        for (item in spine) {
            val html = files[item.href]?.let(TextDecoder::decode)
                ?: error("不是有效的 EPUB：正文文件缺失 ${item.href}")
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
        val decoded = HashMap<String, String>()
        val notes = noteKeys.mapNotNull { key ->
            val path = key.substringBefore('#')
            val html = decoded.getOrPut(path) { files[path]?.let(TextDecoder::decode).orEmpty() }
            HtmlText.noteText(html, key.substringAfter('#'))?.let { key to it }
        }.toMap()
        return BookContent(title ?: fallbackTitle, author, chapters, description, cover, notes)
    }

    /** 目录：EPUB3 的 nav 或 EPUB2 的 NCX；同一文件取第一个标题。 */
    private fun tocTitles(files: Map<String, ByteArray>, path: String?): Map<String, String> {
        val text = path?.let(files::get)?.let(TextDecoder::decode) ?: return emptyMap()
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
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val wanted = name.endsWith(".xml", ignoreCase = true) || name.endsWith(".opf", ignoreCase = true) ||
                    name.endsWith(".ncx", ignoreCase = true) || name.endsWith(".xht", ignoreCase = true) ||
                    name.endsWith(".xhtml", ignoreCase = true) || name.endsWith(".html", ignoreCase = true) ||
                    name.endsWith(".htm", ignoreCase = true)
                if (!entry.isDirectory && wanted) files[name] = zip.readBytes()
            }
        }
        return files
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
