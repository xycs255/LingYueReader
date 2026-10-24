package com.xycs.lingyuereader.remote

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.data.Library
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

/** 从书源下载的书：和本地导入一样进书架；“刷新”时重新下载，内容变了才更新。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RemoteLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private var text = "第一章 开端\n正文第一段。\n"

    @Before
    fun start() {
        File(context.filesDir, "books").deleteRecursively()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val bytes = text.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun downloadsIntoTheShelfAndRefreshesFromTheSource() {
        val library = Library(context)
        library.remote = RemoteClient { emptyList() }::open
        val url = "http://127.0.0.1:${server.address.port}/dav/%E8%BE%B9%E5%9F%8E.txt"
        val entry = library.add(listOf(Library.Picked(Uri.parse(url), "边城.txt")), null)
        assertEquals("边城", entry.title)
        assertEquals(url, entry.sources.single().uri)
        assertEquals(0, library.refresh(entry).changed)
        text += "第二章 后来\n又有了新的一章。\n"
        val result = library.refresh(entry)
        assertEquals(1, result.changed)
        assertEquals(0, result.failed)
        assertTrue(library.list().single().chapterCount >= 2)
        // 同一个地址不会重复进书架。
        assertTrue(runCatching { library.add(listOf(Library.Picked(Uri.parse(url), "边城.txt")), null) }.isFailure)
    }
}
