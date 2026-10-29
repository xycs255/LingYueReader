package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 当书预览以后在规划树上挪了章，再打开预览：阅读位置和书签仍在原来那一章。
 * 两卷各有“第一章”“第二章”（分卷重新数章很常见），只按章名找不回来，得按章 id 换算。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewMoveTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val writingDir get() = File(app.filesDir, "writing")
    private val models = mutableListOf<AppViewModel>()
    private lateinit var workId: String
    private lateinit var upper: String
    private lateinit var lower: String
    private val ids = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        File(app.filesDir, "books").deleteRecursively()
        File(app.filesDir, "books").mkdirs()
        File(app.filesDir, "books/index.json").writeText("[]")
        writingDir.deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val store = WorkStore(writingDir)
        val work = store.create("长夜")
        workId = work.id
        upper = work.volumes.single().id
        store.renameVolume(workId, upper, "上卷")
        lower = store.addVolume(workId, "下卷")!!.volumes.last().id
        val first = work.chapters.single().id
        store.renameChapter(workId, first, "第一章")
        ids["上一"] = first
        ids["上二"] = store.addChapter(workId, upper, "第二章")!!.second.id
        ids["下一"] = store.addChapter(workId, lower, "第一章")!!.second.id
        ids["下二"] = store.addChapter(workId, lower, "第二章")!!.second.id
        store.saveText(workId, ids.getValue("上一"), "林月在渡口等船。", count = false)
        store.saveText(workId, ids.getValue("上二"), "沈默进了茶馆。", count = false)
        store.saveText(workId, ids.getValue("下一"), "老周把灯挂上了城楼。\n杜娘数着铜钱。", count = false)
        store.saveText(workId, ids.getValue("下二"), "顾长风连夜出城。", count = false)
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, condition())
    }

    /** 当书预览，等书加载好（阅读页进来时会调 ensureLoaded，这里替它调）。 */
    private fun preview(vm: AppViewModel, before: Int): String {
        vm.previewWork(workId, null)
        waitUntil("预览没有打开") { vm.stack.value.size > before && vm.stack.value.last() is Screen.Reader }
        val bookId = (vm.stack.value.last() as Screen.Reader).bookId
        vm.ensureLoaded(bookId)
        waitUntil("预览书没有加载") { vm.openBook.value?.entry?.id == bookId && !vm.busy.value }
        return bookId
    }

    @Test
    fun movingAChapterKeepsThePreviewOnTheChapterBeingRead() {
        val vm = AppViewModel(app).also { models += it }
        waitUntil("书架没有加载") { vm.shelfLoaded.value }
        val start = vm.stack.value.size
        val bookId = preview(vm, start)
        // 读到下卷的“第一章”（全书第 3 章），在那里加一个书签。
        val book = vm.openBook.value!!
        assertEquals(listOf("第一章", "第二章", "第一章", "第二章"), book.sourceContent.chapters.map { it.title })
        vm.saveProgress(2, 2, 0, 0)
        vm.toggleBookmark(2, 1, 0)
        waitUntil("进度没有存下") {
            Library(app).list().single { it.id == bookId }.progress?.chapterIndex == 2 &&
                Library(app).marks(bookId).bookmarks.size == 1
        }
        vm.back()

        // 把下卷的“第二章”拖到上卷最前面：读到的那章从第 3 个变成第 4 个。
        var moved = false
        vm.writing.moveChapterTo(workId, ids.getValue("下二"), upper, 0) { moved = true }
        waitUntil("没有挪成") { moved }

        preview(vm, start)
        val reopened = vm.openBook.value!!
        assertEquals(listOf("第二章", "第一章", "第二章", "第一章"), reopened.sourceContent.chapters.map { it.title })
        val progress = reopened.entry.progress!!
        assertEquals("应停在原来读的下卷第一章", 3, progress.chapterIndex)
        assertTrue(reopened.sourceContent.chapters[3].blocks.any { "杜娘" in (it as? com.xycs.lingyuereader.data.Block.Para)?.text.orEmpty() })
        val mark = vm.marks.value.bookmarks.single()
        assertEquals("书签也跟着那一章走", 3, mark.chapterIndex)
        assertFalse(mark.lost)
    }
}
