package com.xycs.lingyuereader.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DictWebClientTest {
    private val missing = mutableListOf<String>()
    private val client = DictWebClient(
        dictId = "d1",
        resource = { path -> if (path == "img/a b.png" || path == "5357.gif") byteArrayOf(1, 2, 3) else null },
        onEntry = {},
        onLink = {},
        onMissing = { missing += it },
    )

    private fun request(url: String, mainFrame: Boolean = false) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun theEntryPageItselfIsNotBlocked() {
        // 新版 WebView 会把 loadDataWithBaseURL 的页面本身也交给拦截；以前这里返回 404，释义变成“网页无法使用”。
        assertNull(client.intercept(request("https://dict.lingyue.local/d1/", mainFrame = true)))
        assertNull(client.intercept(request("data:text/html;charset=utf-8;base64,PGI+", mainFrame = false)))
    }

    @Test
    fun dictionaryResourcesAreServedAndEverythingElseStaysOffline() {
        val image = client.intercept(request("https://dict.lingyue.local/d1/img/a%20b.png"))!!
        assertEquals("image/png", image.mimeType)
        assertEquals(3, image.data.readBytes().size)
        assertEquals(404, client.intercept(request("https://dict.lingyue.local/d1/missing.css"))!!.statusCode)
        assertEquals(404, client.intercept(request("https://example.com/track.js"))!!.statusCode)
    }

    @Test
    fun oddResourceAddressesAreLookedUpInTheDictionaryToo() {
        // 词典里写成 file:///5357.gif，或者原网站的网址：都按路径到词典里找。
        assertEquals(3, client.intercept(request("file:///5357.gif"))!!.data.readBytes().size)
        // MDict 最常见的写法：file:// 后面直接跟文件名，按网址规则文件名成了“主机名”。
        assertEquals(3, client.intercept(request("file://5357.gif"))!!.data.readBytes().size)
        assertEquals("img/a b.png", client.resourcePath(Uri.parse("file://img/a%20b.png")))
        assertEquals(3, client.intercept(request("http://www.zdic.net/5357.gif"))!!.data.readBytes().size)
        // 找不到的记下来，面板里告诉读者缺了什么。
        assertEquals(404, client.intercept(request("https://dict.lingyue.local/d1/gif/9999.gif"))!!.statusCode)
        assertEquals(listOf("gif/9999.gif"), missing)
    }

    @Test
    fun fileUrlsInEntriesBecomeRelativeSoTheyReachTheDictionary() {
        assertEquals("""<img src="5357.gif" alt="5357.gif">""", localResourceUrls("""<img src="file://5357.gif" alt="5357.gif">"""))
        assertEquals("<img src='img/a.png'>", localResourceUrls("<img src='file:///img/a.png'>"))
        assertEquals("""<link href="style.css">""", localResourceUrls("""<link href="FILE://style.css">"""))
        assertEquals("""<div style="background:url('bg.png')">""", localResourceUrls("""<div style="background:url('file://bg.png')">"""))
        // 发音、跳词条的链接不动。
        assertEquals("""<a href="sound://a.mp3">""", localResourceUrls("""<a href="sound://a.mp3">"""))
    }

    @Test
    fun imagesAreInlinedFromTheDictionaryAndMissingOnesAreReported() {
        val gif = byteArrayOf(0x47, 0x49, 0x46)
        val (html, missing) = inlineImages(
            """<p><img src="5357.gif" alt="5357.gif"> <img src='file://9999.gif'> <img src="data:image/png;base64,AA"></p>""",
        ) { path -> if (path == "5357.gif") gif else null }
        assertTrue(html, html.contains("""src="data:image/gif;base64,R0lG""""))
        assertTrue(html, html.contains("data:image/png;base64,AA"))
        assertEquals(listOf("9999.gif"), missing)
    }
}
