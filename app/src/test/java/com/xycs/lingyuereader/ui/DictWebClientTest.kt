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
    private val client = DictWebClient(
        dictId = "d1",
        resource = { path -> if (path == "img/a b.png") byteArrayOf(1, 2, 3) else null },
        onEntry = {},
        onLink = {},
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
}
