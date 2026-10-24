package com.xycs.lingyuereader.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteParseTest {
    private val feed = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <title>我的书库</title>
          <link rel="search" type="application/opensearchdescription+xml" href="/opds/osd"/>
          <link rel="next" type="application/atom+xml;profile=opds-catalog" href="?page=2"/>
          <entry>
            <title>按作者</title>
            <content type="text">所有作者</content>
            <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="authors"/>
          </entry>
          <entry>
            <title>边城</title>
            <author><name>沈从文</name></author>
            <summary type="html">&lt;p&gt;湘西&amp;nbsp;小城的故事&lt;/p&gt;</summary>
            <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/cover/1/thumb"/>
            <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/download/1/pdf"/>
            <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/download/1/epub"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/download/1/epub-noimages"/>
            <link rel="http://opds-spec.org/acquisition" type="application/zip" href="/download/1/zip"/>
            <link rel="alternate" type="application/atom+xml;type=entry;profile=opds-catalog" href="/entry/1"/>
          </entry>
          <entry><title>只有网页</title><link rel="alternate" type="text/html" href="/x"/></entry>
        </feed>"""

    @Test
    fun parsesNavigationBooksAndLinks() {
        val page = Opds.parse(feed.toByteArray(), "http://nas.local:8083/opds/")
        assertEquals("我的书库", page.title)
        assertEquals("http://nas.local:8083/opds/?page=2", page.next)
        assertEquals("http://nas.local:8083/opds/osd", page.searchDescription)
        assertEquals(2, page.items.size)
        val folder = page.items[0] as RemoteItem.Folder
        assertEquals("http://nas.local:8083/opds/authors", folder.url)
        assertEquals("所有作者", folder.subtitle)
        val book = page.items[1] as RemoteItem.Book
        assertEquals("沈从文", book.author)
        assertEquals("湘西 小城的故事", book.summary)
        assertEquals("http://nas.local:8083/cover/1/thumb", book.cover)
        // EPUB 排在前面，同一格式只留一份，读不了的 zip 不列。
        assertEquals(listOf("EPUB", "PDF"), book.files.map { it.label })
        assertEquals("http://nas.local:8083/download/1/epub", book.files[0].url)
        assertEquals("边城.epub", book.files[0].name)
    }

    @Test
    fun findsSearchTemplates() {
        val osd = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="text/html" template="/search?q={searchTerms}"/>
            <Url type="application/atom+xml" template="/opds/search/{searchTerms}?count={count?}"/>
        </OpenSearchDescription>"""
        val template = Opds.searchTemplate(osd.toByteArray(), "https://lib.example.com/opds/osd")!!
        assertEquals("https://lib.example.com/opds/search/{searchTerms}?count={count?}", template)
        assertEquals("https://lib.example.com/opds/search/%E8%BE%B9%E5%9F%8E%20%E6%B2%88?count=", Opds.searchUrl(template, " 边城 沈 "))
        val inline = Opds.parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><link rel="search" type="application/atom+xml" href="/s?q={searchTerms}"/></feed>""".toByteArray(),
            "https://x.org/opds",
        )
        assertEquals("https://x.org/s?q={searchTerms}", inline.searchTemplate)
        assertNull(inline.searchDescription)
    }

    @Test
    fun rejectsPagesThatAreNotFeeds() {
        val error = runCatching { Opds.parse("<html><body>登录</body></html>".toByteArray(), "https://x.org/") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("OPDS"))
    }

    @Test
    fun parsesWebDavFolders() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/%E4%B9%A6/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/%E4%B9%A6/%E5%B0%8F%E8%AF%B4/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/%E4%B9%A6/%E8%BE%B9%E5%9F%8E.epub</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>204800</d:getcontentlength><d:getlastmodified>Mon, 12 Jan 2026 08:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/%E4%B9%A6/a+b.txt</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/%E4%B9%A6/photo.jpg</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/%E4%B9%A6/.DS_Store</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val items = WebDav.parse(xml.toByteArray(), "https://cloud.example.com/dav/%E4%B9%A6/")
        assertEquals(listOf("小说", "a+b", "边城"), items.map { it.title })
        val folder = items[0] as RemoteItem.Folder
        assertEquals("https://cloud.example.com/dav/%E4%B9%A6/%E5%B0%8F%E8%AF%B4/", folder.url)
        val book = items[2] as RemoteItem.Book
        assertEquals(204800L, book.size)
        assertTrue(book.modified!! > 0)
        assertEquals("边城.epub", book.files.single().name)
        assertEquals("EPUB", book.files.single().label)
        assertEquals("a+b.txt", (items[1] as RemoteItem.Book).files.single().name)
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("a b c.epub", Opds.fileName("a/b:c", "epub"))
        assertEquals("book.txt", Opds.fileName("  ", "txt"))
    }

    @Test
    fun passwordsStayOutOfLogs() {
        val c = Catalog(kind = CatalogKind.WEBDAV, name = "n", url = "http://192.168.1.2/dav", username = "u", password = "secret-pw")
        assertTrue(c.insecure)
        assertTrue("secret" !in c.toString())
    }
}
