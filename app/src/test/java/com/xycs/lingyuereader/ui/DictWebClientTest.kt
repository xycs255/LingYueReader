package com.xycs.lingyuereader.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(3, client.intercept(request("http://www.zdic.net/5357.gif"))!!.data.readBytes().size)
        // 找不到的记下来，面板里告诉读者缺了什么。
        assertEquals(404, client.intercept(request("https://dict.lingyue.local/d1/gif/9999.gif"))!!.statusCode)
        assertEquals(listOf("gif/9999.gif"), missing)
    }
}
