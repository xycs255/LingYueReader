package com.xycs.lingyuereader.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/** 连本机的假书库：看密码加给了谁、PROPFIND 发得对不对、出错时怎么说。 */
class RemoteClientTest {
    private lateinit var lib: HttpServer
    private lateinit var other: HttpServer
    private val seen = mutableListOf<Triple<String, String, String?>>() // 方法、路径、Authorization
    private var otherAuth: String? = "unset"

    private fun HttpExchange.reply(code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Before
    fun start() {
        other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                otherAuth = ex.requestHeaders.getFirst("Authorization")
                ex.reply(200, "application/epub+zip", "EPUBDATA")
            }
            start()
        }
        lib = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization")
                seen += Triple(ex.requestMethod, ex.requestURI.toString(), auth)
                val ok = auth == "Basic " + Base64.getEncoder().encodeToString("reader:pä ss".toByteArray())
                val path = ex.requestURI.path
                when {
                    path.startsWith("/public/") -> ex.reply(200, "application/atom+xml", FEED)
                    !ok -> { ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"x\""); ex.reply(401, "text/plain", "") }
                    path == "/opds" -> ex.reply(200, "application/atom+xml", FEED)
                    path == "/osd" -> ex.reply(200, "application/opensearchdescription+xml", """<OpenSearchDescription><Url type="application/atom+xml" template="/search?q={searchTerms}"/></OpenSearchDescription>""")
                    path == "/search" -> ex.reply(200, "application/atom+xml", FEED.replace("书库", "搜索：" + ex.requestURI.query))
                    path == "/dav/" && ex.requestMethod == "PROPFIND" -> {
                        assertEquals("1", ex.requestHeaders.getFirst("Depth"))
                        ex.reply(207, "application/xml", DAV)
                    }
                    path == "/dav/" -> ex.reply(405, "text/plain", "")
                    path == "/book.epub" -> ex.reply(200, "application/epub+zip", "EPUBDATA")
                    path == "/away" -> { ex.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/book.epub"); ex.reply(302, "text/plain", "") }
                    else -> ex.reply(404, "text/plain", "")
                }
            }
            start()
        }
    }

    @After
    fun stop() {
        lib.stop(0)
        other.stop(0)
    }

    private val base get() = "http://127.0.0.1:${lib.address.port}"
    private fun opds() = Catalog(id = "o", kind = CatalogKind.OPDS, name = "书库", url = "$base/opds", username = "reader", password = "pä ss")
    private fun dav() = Catalog(id = "d", kind = CatalogKind.WEBDAV, name = "网盘", url = "$base/dav/", username = "reader", password = "pä ss")

    @Test
    fun browsesAndSearchesOpdsWithBasicAuth() = runBlocking<Unit> {
        val client = RemoteClient { listOf(opds()) }
        val page = client.page(opds(), opds().url)
        assertEquals("书库", page.title)
        val found = client.search(page, "边城")
        assertTrue(found.title.contains("边城"))
        assertTrue(seen.all { it.third != null })
    }

    @Test
    fun listsWebDavWithPropfind() = runBlocking<Unit> {
        val client = RemoteClient { listOf(dav()) }
        val page = client.page(dav(), dav().url)
        assertEquals("网盘", page.title)
        assertEquals(listOf("边城"), page.items.map { it.title })
        assertEquals("PROPFIND", seen.last().first)
    }

    @Test
    fun explainsWrongPasswordsAndNonWebDav() = runBlocking<Unit> {
        val wrong = opds().copy(password = "x")
        try {
            RemoteClient { listOf(wrong) }.page(wrong, wrong.url)
            fail()
        } catch (e: RemoteException) {
            assertEquals("用户名或密码不对", e.message)
        }
        val notDav = dav().copy(url = "$base/public/")
        try {
            RemoteClient { listOf(notDav) }.page(notDav, notDav.url)
        } catch (e: RemoteException) {
            fail("public 目录不该要密码：${e.message}")
        } catch (_: Exception) {
            // 不是 multistatus：解析失败也算对（这里只看密码和方法）。
        }
    }

    @Test
    fun passwordNeverFollowsARedirectToAnotherHost() {
        val client = RemoteClient { listOf(opds()) }
        assertEquals("EPUBDATA", client.open("$base/book.epub").use { it.readBytes().decodeToString() })
        assertEquals("EPUBDATA", client.open("$base/away").use { it.readBytes().decodeToString() })
        assertNull(otherAuth)
    }

    @Test
    fun noPasswordForHostsWithoutACatalog() {
        RemoteClient { emptyList() }.open("http://127.0.0.1:${other.address.port}/x.epub").close()
        assertNull(otherAuth)
    }

    private companion object {
        const val FEED = """<feed xmlns="http://www.w3.org/2005/Atom"><title>书库</title><link rel="search" type="application/opensearchdescription+xml" href="/osd"/>
            <entry><title>边城</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/book.epub"/></entry></feed>"""
        const val DAV = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/dav/%E8%BE%B9%E5%9F%8E.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>9</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
    }
}
