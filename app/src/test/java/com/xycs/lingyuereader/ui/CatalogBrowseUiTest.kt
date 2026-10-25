package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.remote.CatalogStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

/** 浏览书源：右上角“刷新”重新取；翻页失败时底部显示“点此重试”，不一直转圈。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class CatalogBrowseUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    @Volatile private var firstFolder = "林月的书"
    @Volatile private var secondPageFails = true

    private fun feed(title: String, next: String?, folder: String, href: String) = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>$title</title>
          ${next?.let { """<link rel="next" type="application/atom+xml;profile=opds-catalog" href="$it"/>""" }.orEmpty()}
          <entry>
            <title>$folder</title>
            <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="$href"/>
          </entry>
        </feed>"""

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/opds") { ex ->
                    val (status, body) = when (ex.requestURI.path) {
                        "/opds/p2" -> if (secondPageFails) 500 to "出错了" else 200 to feed("测试书库", null, "沈默的书", "/opds/b")
                        else -> 200 to feed("测试书库", "/opds/p2", firstFolder, "/opds/a")
                    }
                    val bytes = body.toByteArray()
                    ex.sendResponseHeaders(status, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                start()
            }
            File(context.filesDir, "books").deleteRecursively()
            val catalog = JSONObject().put("id", "c1").put("kind", "OPDS").put("name", "测试书源")
                .put("url", "http://127.0.0.1:${server.address.port}/opds").put("username", "").put("password", "")
            context.getSharedPreferences(CatalogStore.FILE, Context.MODE_PRIVATE).edit().putString("list", JSONArray().put(catalog).toString()).commit()
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun failedNextPageOffersRetryAndRefreshBypassesTheCache() {
        waitForText("书架还是空的")
        compose.runOnUiThread { vm.open(Screen.CatalogBrowse("c1")) }
        waitForText("林月的书")
        // 列表很短，一打开就到底，马上去取第二页；第二页出错。
        waitForText("加载失败，点此重试")
        secondPageFails = false
        compose.onNodeWithText("加载失败，点此重试").performClick()
        waitForText("沈默的书")

        // 书源上的内容变了：再进来还是缓存，点“刷新”才重新取。
        firstFolder = "杜娘的书"
        compose.runOnUiThread { vm.back(); vm.open(Screen.CatalogBrowse("c1")) }
        waitForText("沈默的书")
        assertEquals(0, compose.onAllNodesWithText("杜娘的书").fetchSemanticsNodes().size)
        compose.onNodeWithText("刷新").performClick()
        waitForText("杜娘的书")
    }

    @Test
    fun unsavedCatalogInputSurvivesRotation() {
        waitForText("书架还是空的")
        compose.runOnUiThread { vm.open(Screen.CatalogEdit(null)) }
        waitForText("添加书源")
        compose.onNodeWithText("名称（可不填）").performTextInput("顾长风的书库")
        compose.onNodeWithText("OPDS 地址").performTextInput("http://192.168.1.8:8083/opds")

        compose.activityRule.scenario.recreate()

        waitForText("顾长风的书库")
        waitForText("http://192.168.1.8:8083/opds")
    }
}
