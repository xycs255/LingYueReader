package com.xycs.lingyuereader.remote

import com.xycs.lingyuereader.data.BookParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** 可以下载的一份文件：[name] 带扩展名，导入时按它认格式。 */
data class RemoteFile(val url: String, val name: String, val label: String)

/** 浏览页里的一行。 */
sealed interface RemoteItem {
    val title: String

    /** 下一层：OPDS 的分类，或 WebDAV 的子文件夹。 */
    data class Folder(override val title: String, val url: String, val subtitle: String? = null) : RemoteItem

    data class Book(
        override val title: String,
        val author: String? = null,
        val summary: String? = null,
        /** 封面缩略图地址。 */
        val cover: String? = null,
        /** 可下载的格式，按推荐的先后。 */
        val files: List<RemoteFile>,
        val size: Long? = null,
        val modified: Long? = null,
    ) : RemoteItem
}

/** 一页：标题、条目、下一页（OPDS 分页）、搜索方式。 */
data class RemotePage(
    val title: String,
    val items: List<RemoteItem>,
    val next: String? = null,
    /** OPDS 的搜索：直接是带 {searchTerms} 的模板，或 OpenSearch 描述文件的地址（要再取一次）。 */
    val searchTemplate: String? = null,
    val searchDescription: String? = null,
)

internal object Xml {
    fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // 不展开外部实体，防 XXE。
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun children(e: Element, ns: String, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) {
            if (n is Element && n.localName == name && (n.namespaceURI ?: "") == ns) out += n
            n = n.nextSibling
        }
        return out
    }

    fun child(e: Element, ns: String, name: String): Element? = children(e, ns, name).firstOrNull()

    fun text(e: Element?): String? = e?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun resolve(base: String, href: String): String = runCatching { URI(base).resolve(href.trim().replace(" ", "%20")).toString() }.getOrDefault(href)

    /** 搜索模板里的 {searchTerms}、{count?} 不是合法的 URI 字符：先转义再解析，解析完换回来。 */
    fun resolveTemplate(base: String, template: String): String =
        resolve(base, template.replace("{", "%7B").replace("}", "%7D")).replace("%7B", "{").replace("%7D", "}")
}

/** OPDS 1.x（Atom）书目。 */
object Opds {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val ACQUISITION = "http://opds-spec.org/acquisition"

    /** 媒体类型 → 扩展名、显示名；灵阅读不了的格式不列。 */
    private val FORMATS = linkedMapOf(
        "application/epub+zip" to ("epub" to "EPUB"),
        "application/x-mobi8-ebook" to ("azw3" to "AZW3"),
        "application/vnd.amazon.mobi8-ebook" to ("azw3" to "AZW3"),
        "application/x-mobipocket-ebook" to ("mobi" to "MOBI"),
        "application/vnd.amazon.ebook" to ("azw" to "AZW"),
        "application/x-fictionbook+xml" to ("fb2" to "FB2"),
        "application/fb2" to ("fb2" to "FB2"),
        "application/pdf" to ("pdf" to "PDF"),
        "application/vnd.comicbook+zip" to ("cbz" to "CBZ"),
        "application/x-cbz" to ("cbz" to "CBZ"),
        "text/markdown" to ("md" to "Markdown"),
        "text/plain" to ("txt" to "TXT"),
    )

    fun parse(bytes: ByteArray, baseUrl: String): RemotePage {
        val root = Xml.parse(bytes).documentElement
        require(root.localName == "feed" && root.namespaceURI == ATOM) { "不是 OPDS 书目（Atom feed）" }
        var next: String? = null
        var template: String? = null
        var description: String? = null
        for (l in Xml.children(root, ATOM, "link")) {
            val rel = l.getAttribute("rel")
            val href = l.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            val type = l.getAttribute("type")
            when {
                rel == "next" -> next = Xml.resolve(baseUrl, href)
                rel == "search" && type.contains("opensearchdescription") -> description = Xml.resolve(baseUrl, href)
                rel == "search" && "{searchTerms}" in href -> template = Xml.resolveTemplate(baseUrl, href)
            }
        }
        val items = Xml.children(root, ATOM, "entry").mapNotNull { entry(it, baseUrl) }
        return RemotePage(Xml.text(Xml.child(root, ATOM, "title")) ?: "书目", items, next, template, description)
    }

    private fun entry(e: Element, base: String): RemoteItem? {
        val title = Xml.text(Xml.child(e, ATOM, "title")) ?: return null
        val links = Xml.children(e, ATOM, "link")
        val files = ArrayList<RemoteFile>()
        var cover: String? = null
        var thumbnail: String? = null
        var nav: String? = null
        for (l in links) {
            val rel = l.getAttribute("rel")
            val href = l.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            val type = l.getAttribute("type").substringBefore(';').trim().lowercase()
            when {
                rel == ACQUISITION || rel == "$ACQUISITION/open-access" -> {
                    val (ext, label) = FORMATS[type] ?: continue
                    files += RemoteFile(Xml.resolve(base, href), fileName(title, ext), label)
                }
                rel == "http://opds-spec.org/image/thumbnail" || rel == "x-stanza-cover-image-thumbnail" -> thumbnail = Xml.resolve(base, href)
                rel == "http://opds-spec.org/image" || rel == "x-stanza-cover-image" -> cover = Xml.resolve(base, href)
                type == "application/atom+xml" && !l.getAttribute("type").contains("type=entry") && nav == null -> nav = Xml.resolve(base, href)
            }
        }
        val summary = (Xml.text(Xml.child(e, ATOM, "summary")) ?: Xml.text(Xml.child(e, ATOM, "content")))?.let(::plain)
        if (files.isNotEmpty()) {
            val authors = Xml.children(e, ATOM, "author").mapNotNull { Xml.text(Xml.child(it, ATOM, "name")) }
            // 同一格式只留一份（古登堡的 EPUB 有带图、不带图两份，留前面那份）；按 EPUB 优先排。
            val order = FORMATS.values.map { it.second }
            val unique = files.distinctBy { it.label }.sortedBy { order.indexOf(it.label) }
            return RemoteItem.Book(title, authors.joinToString("、").ifEmpty { null }, summary, thumbnail ?: cover, unique)
        }
        return nav?.let { RemoteItem.Folder(title, it, summary) }
    }

    /** OpenSearch 描述文件里找 Atom 结果的模板。 */
    fun searchTemplate(bytes: ByteArray, baseUrl: String): String? {
        val root = Xml.parse(bytes).documentElement
        var n: Node? = root.firstChild
        var fallback: String? = null
        while (n != null) {
            if (n is Element && n.localName == "Url") {
                val t = n.getAttribute("template").takeIf { "{searchTerms}" in it }
                if (t != null) {
                    if (n.getAttribute("type").contains("atom")) return Xml.resolveTemplate(baseUrl, t)
                    fallback = fallback ?: t
                }
            }
            n = n.nextSibling
        }
        return fallback?.let { Xml.resolveTemplate(baseUrl, it) }
    }

    /** 把模板里的 {searchTerms} 换成搜索词；可选参数（{count?}）去掉，页码从 1 起。 */
    fun searchUrl(template: String, query: String): String = template
        .replace("{searchTerms}", URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
        .replace(Regex("""\{startPage\??}"""), "1")
        .replace(Regex("""\{startIndex\??}"""), "1")
        .replace(Regex("""\{[^}]*\?}"""), "")

    private fun plain(s: String): String = s.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    /** 下载的文件名：书名 + 扩展名，去掉文件名里不能有的字符。 */
    internal fun fileName(title: String, ext: String): String =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "book" } + ".$ext"
}

/** WebDAV 文件夹：PROPFIND 列出子文件夹和书。 */
object WebDav {
    private const val DAV = "DAV:"

    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

    /** 解析 multistatus：子文件夹在前，书按名字排；跳过文件夹自身、隐藏文件和灵阅读不了的文件。 */
    fun parse(bytes: ByteArray, folderUrl: String): List<RemoteItem> {
        val root = Xml.parse(bytes).documentElement
        require(root.localName == "multistatus") { "不是 WebDAV 的回复" }
        val self = pathOf(folderUrl)
        val folders = ArrayList<RemoteItem.Folder>()
        val books = ArrayList<RemoteItem.Book>()
        for (r in Xml.children(root, DAV, "response")) {
            val href = Xml.text(Xml.child(r, DAV, "href")) ?: continue
            val url = Xml.resolve(folderUrl, href)
            if (pathOf(url) == self) continue
            // 取状态是 200 的那组属性。
            val props = Xml.children(r, DAV, "propstat")
                .filter { (Xml.text(Xml.child(it, DAV, "status")) ?: "200").contains(" 200") || Xml.child(it, DAV, "status") == null }
                .mapNotNull { Xml.child(it, DAV, "prop") }
            val prop = props.firstOrNull()
            val isDir = props.any { p -> Xml.child(p, DAV, "resourcetype")?.let { Xml.child(it, DAV, "collection") } != null } || href.endsWith("/")
            val name = props.firstNotNullOfOrNull { Xml.text(Xml.child(it, DAV, "displayname")) }
                ?: decode(pathOf(url).trimEnd('/').substringAfterLast('/'))
            if (name.isEmpty() || name.startsWith(".")) continue
            if (isDir) {
                folders += RemoteItem.Folder(name, if (url.endsWith("/")) url else "$url/")
            } else if (BookParser.isSupported(name)) {
                val size = prop?.let { Xml.text(Xml.child(it, DAV, "getcontentlength"))?.toLongOrNull() }
                val modified = prop?.let { Xml.text(Xml.child(it, DAV, "getlastmodified")) }?.let(::httpDate)
                val label = BookParser.formatOf(name).label
                books += RemoteItem.Book(name.substringBeforeLast('.'), files = listOf(RemoteFile(url, name, label)), size = size, modified = modified)
            }
        }
        val collator = Collator.getInstance(Locale.CHINA)
        return folders.sortedWith { a, b -> collator.compare(a.title, b.title) } + books.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    private fun pathOf(url: String): String = decode(runCatching { URI(url).rawPath }.getOrNull() ?: url).trimEnd('/')

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun httpDate(s: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time
    }.getOrNull()
}
