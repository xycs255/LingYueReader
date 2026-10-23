package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.ChineseConvert
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.displayText
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelTest {
    private fun waitUntilReady(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待阅读数据加载超时", condition())
    }

    private fun assertBookmarkSurvivesScriptSwitchAndFreshViewModel(
        title: String,
        paragraph: String,
        sourceWord: String,
        convertedWord: String,
        mode: ChineseConvert,
    ) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(app)
        val source = File(app.cacheDir, "conversion-book.txt").apply { writeText("$title\n　　$paragraph\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val sourceChapter = library.content(entry).chapters.single()
        val block = sourceChapter.blocks.indexOfFirst { it.displayText?.contains(sourceWord) == true }
        assertTrue(block >= 0)
        val char = sourceChapter.blocks[block].displayText!!.indexOf(sourceWord)
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }

        vm.updateSettings { it.copy(chineseConvert = mode) }
        waitUntilReady { vm.openBook.value?.content?.chapters?.single()?.blocks?.any { block ->
            block.displayText?.contains(convertedWord) == true
        } == true }
        vm.toggleBookmark(0, block, char)
        val marksFile = library.bookFile(entry.id, "marks.json")
        waitUntilReady { marksFile.isFile && runCatching { Marks.fromJson(JSONObject(marksFile.readText())).bookmarks.size }.getOrDefault(0) == 1 }
        val persisted = Marks.fromJson(JSONObject(marksFile.readText())).bookmarks.single()
        assertEquals(title, persisted.chapterTitle)
        assertEquals(char, persisted.charOffset)
        assertTrue(persisted.anchor.text.contains(sourceWord))

        // 新 ViewModel 相当于重新启动后从磁盘加载书架、设置与书签。
        val restartedInConvertedMode = AppViewModel(app)
        waitUntilReady { restartedInConvertedMode.shelf.value.any { it.id == entry.id } }
        restartedInConvertedMode.ensureLoaded(entry.id)
        waitUntilReady { restartedInConvertedMode.openBook.value?.entry?.id == entry.id }
        assertFalse(restartedInConvertedMode.marks.value.bookmarks.single().lost)
        assertEquals(char, restartedInConvertedMode.marks.value.bookmarks.single().charOffset)

        vm.updateSettings { it.copy(chineseConvert = ChineseConvert.NONE) }
        waitUntilReady { vm.openBook.value?.content?.chapters?.single()?.title == title }
        assertFalse(vm.marks.value.bookmarks.single().lost)
        assertEquals(char, vm.marks.value.bookmarks.single().charOffset)
        val restartedInOriginalMode = AppViewModel(app)
        waitUntilReady { restartedInOriginalMode.shelf.value.any { it.id == entry.id } }
        restartedInOriginalMode.ensureLoaded(entry.id)
        waitUntilReady { restartedInOriginalMode.openBook.value?.entry?.id == entry.id }
        assertFalse(restartedInOriginalMode.marks.value.bookmarks.single().lost)
        assertEquals(char, restartedInOriginalMode.marks.value.bookmarks.single().charOffset)
    }

    @Test
    fun simplifiedBookmarkCreatedInTraditionalViewSurvivesReturnAndRestart() =
        assertBookmarkSurvivesScriptSwitchAndFreshViewModel(
            "第一章 开端", "她的头发很长，后来皇后也来了。", "头发", "頭髮", ChineseConvert.TO_TRADITIONAL,
        )

    @Test
    fun traditionalBookmarkCreatedInSimplifiedViewSurvivesReturnAndRestart() =
        assertBookmarkSurvivesScriptSwitchAndFreshViewModel(
            "第一章 開端", "她的頭髮很長，後來皇后也來了。", "頭髮", "头发", ChineseConvert.TO_SIMPLIFIED,
        )

    @Test
    fun manualBackupLeavesTheChosenTargetUntouchedWhenARequiredSourceIsMissing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(app)
        val source = File(app.cacheDir, "manual-backup-source.txt").apply { writeText("第一章 起\n　　正文。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        assertTrue(library.bookFile(entry.id, entry.sources.single().file).delete())
        val target = File(app.cacheDir, "manual-backup-target.zip").apply { writeText("原有目标内容") }
        val original = target.readBytes()

        val vm = AppViewModel(app)
        vm.backup(Uri.fromFile(target))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while ((vm.messages.value == null || vm.busy.value) && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(vm.messages.value.orEmpty().contains("备份失败"))
        assertFalse(vm.busy.value)
        assertArrayEquals(original, target.readBytes())
        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("manual-backup-") && it.name.endsWith(".zip") && it != target })
    }

    @Test
    fun malformedBackupSettingsLeaveRestoredBooksVisibleAndReleaseBusyState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(app)
        val source = File(app.cacheDir, "viewmodel-restore.txt").apply {
            writeText("第一章 起\n　　可以继续阅读的正文。\n")
        }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val backup = ByteArrayOutputStream()
        library.backup(backup, JSONObject().put("fontSize", JSONObject().put("s", "invalid")))
        library.remove(entry.id)

        val file = File(app.cacheDir, "viewmodel-restore.zip").apply { writeBytes(backup.toByteArray()) }
        val vm = AppViewModel(app)
        vm.restore(Uri.fromFile(file))

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while ((vm.messages.value == null || vm.busy.value) && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(vm.messages.value.orEmpty().contains("但附加数据恢复失败"))
        assertFalse(vm.busy.value)
        assertEquals(entry.id, vm.shelf.value.single().id)
        assertEquals(ReaderSettings().fontSize, Prefs(app).settings().fontSize, 0f)
    }
}
