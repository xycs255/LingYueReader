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

        data class Item(val href: String, val type: String, val properties: String)
        val manifest = Regex("""<item\b[^>]*>""").findAll(opf).mapNotNull { m ->
            val id = attr(m.value, "id") ?: return@mapNotNull null
            val href = attr(m.value, "href") ?: return@mapNotNull null
            id to Item(resolve(base, href), attr(m.value, "media-type").orEmpty(), attr(m.value, "properties").orEmpty())
        }.toMap()
        val spineTag = Regex("""<spine\b[^>]*>""").find(opf)?.value.orEmpty()
        val spine = Regex("""<itemref\b[^>]*>""").findAll(opf)
            .filter { attr(it.value, "linear") != "no" }
            .mapNotNull { attr(it.value, "idref")?.let(manifest::get) }
            .filter { it.type in textTypes || it.href.endsWith("html", ignoreCase = true) }
            .toList()

        val tocTitles = tocTitles(files, manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href)
            .ifEmpty { tocTitles(files, attr(spineTag, "toc")?.let(manifest::get)?.href) }

        val chapters = mutableListOf<Chapter>()
        for (item in spine) {
            val html = files[item.href]?.let(TextDecoder::decode) ?: continue
            var blocks = HtmlText.toBlocks(html)
            if (blocks.none { it is Block.Para || it is Block.Quote || it is Block.ListItem }) continue // 封面、插图页
            val firstHeading = blocks.firstOrNull() as? Block.Heading
            val chapterTitle = tocTitles[item.href] ?: firstHeading?.text ?: "第 ${chapters.size + 1} 节"
            if (firstHeading != null && firstHeading.text.normalizeSpaces() == chapterTitle.normalizeSpaces()) {
                blocks = blocks.drop(1)
            }
            chapters += ChapterTitles.chapter(chapterTitle, blocks)
        }
        return BookContent(title ?: fallbackTitle, author, chapters)
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
                val wanted = name.endsWith(".xml") || name.endsWith(".opf") || name.endsWith(".ncx") ||
                    name.endsWith("html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)
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
