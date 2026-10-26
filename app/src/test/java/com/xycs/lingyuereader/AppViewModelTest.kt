package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.SharedLibrary
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
import java.util.concurrent.CountDownLatch
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

    /** 索引里一条记录坏了：其余的书照常显示，写书架被拒绝时提示，不崩溃，也不改写索引。 */
    @Test
    fun damagedIndexEntryKeepsOtherBooksAndFailedWritesDoNotCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(app)
        val good = library.add(library.describe(listOf(Uri.fromFile(File(app.cacheDir, "好书.txt").apply { writeText("第一章 起\n　　正文。\n") }))), null)
        val bad = library.add(library.describe(listOf(Uri.fromFile(File(app.cacheDir, "坏记录.txt").apply { writeText("第一章 起\n　　另一本。\n") }))), null)
        val index = File(app.filesDir, "books/index.json")
        val array = org.json.JSONArray(index.readText())
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("id") == bad.id) array.getJSONObject(i).put("sources", "已损坏")
        }
        index.writeText(array.toString())
        val damagedIndex = index.readBytes()

        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.isNotEmpty() }
        assertEquals(listOf(good.id), vm.shelf.value.map { it.id })
        assertTrue(vm.messages.value.orEmpty().contains("读不出"))
        vm.consumeMessage()

        vm.ensureLoaded(good.id)
        waitUntilReady { vm.openBook.value?.entry?.id == good.id }
        vm.saveProgress(0, 1, 0, 1)
        waitUntilReady { vm.messages.value.orEmpty().contains("保存阅读进度失败") }
        // 同一种失败只提示一次。
        vm.consumeMessage()
        vm.saveProgress(0, 1, 0, 2)
        Thread.sleep(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, vm.messages.value)
        assertArrayEquals(damagedIndex, index.readBytes())
    }

    /** 从文件管理器打开已导入过、之后又改过的同一个文件：打开书架上那本并提示可刷新，不再报“已在书架上”。 */
    @Test
    fun openingAChangedSourceFileOpensTheBookOnTheShelf() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(app)
        val file = File(app.cacheDir, "追更.txt").apply { writeText("第一章 起\n　　正文。\n") }
        val uri = Uri.fromFile(file)
        val entry = library.add(library.describe(listOf(uri)), null)
        file.writeText("第一章 起\n　　正文。\n第二章 续\n　　作者追更了。\n")

        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.openExternal(listOf(uri))
        waitUntilReady { (vm.stack.value.lastOrNull() as? Screen.Reader)?.bookId == entry.id }
        assertTrue(vm.messages.value.orEmpty().contains("原文件有改动"))
        assertEquals(1, vm.shelf.value.size)
    }

    @Test
    fun readerRebuiltAfterLandingStartsFromProgressNotTheJumpTarget() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = Library(app)
        val source = File(app.cacheDir, "landing.txt").apply { writeText("第一章 起\n　　一。\n第二章 承\n　　二。\n　　三。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }

        // 翻进第二章：栈顶记的是“第二章开头”。在那里读了一阵、存过进度之后，重建阅读页要用进度。
        val jump = Screen.Reader(entry.id, 1, null)
        vm.open(jump)
        assertFalse(vm.landedOn(jump))
        vm.saveProgress(1, 2, 0, 0)
        assertTrue(vm.landedOn(jump))
        // 再跳一次同样的目标是新的一页：还没到达。
        val again = Screen.Reader(entry.id, 1, null)
        assertFalse(again == jump)
        assertFalse(vm.landedOn(again))
    }

    @Test
    fun bookSearchJumpsToTheExactOccurrenceAndClearsCancelledResults() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = Library(app)
        val source = File(app.cacheDir, "search-offset.txt").apply { writeText("第一章 起\n　　前文目标。后文目标。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }
        vm.open(Screen.Reader(entry.id))
        vm.open(Screen.Search(entry.id))
        vm.search("目标")
        assertEquals("目标", vm.bookSearch.value.query)
        // 短查询可能在下一条断言前完成；只验证最终结果，不依赖后台调度时序。
        waitUntilReady { !vm.bookSearch.value.running && vm.bookSearch.value.hits.size == 2 }
        val second = vm.bookSearch.value.hits[1]
        val paragraph = vm.openBook.value!!.content.chapters[second.chapterIndex].blocks[second.blockIndex].displayText!!
        assertEquals(paragraph.indexOf("目标", paragraph.indexOf("目标") + 1), second.charOffset)
        vm.jumpFromSearch(entry.id, second.chapterIndex, second.blockIndex, second.charOffset)
        assertEquals(second.charOffset, (vm.stack.value.last() as Screen.Reader).char)

        vm.search("这段文字不存在")
        waitUntilReady { !vm.bookSearch.value.running }
        assertTrue(vm.bookSearch.value.hits.isEmpty())
        assertEquals(null, vm.bookSearch.value.error)

        vm.search("目标")
        vm.search("")
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", vm.bookSearch.value.query)
        assertFalse(vm.bookSearch.value.running)
        assertTrue(vm.bookSearch.value.hits.isEmpty())
    }

    @Test
    fun corruptMarksLeaveTextReadableAndBlockEdits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = Library(app)
        val source = File(app.cacheDir, "corrupt-marks.txt").apply { writeText("第一章 起\n　　正文。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val marks = library.bookFile(entry.id, "marks.json")
        marks.writeText("{broken")
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }
        assertTrue(vm.messages.value.orEmpty().contains("书签批注文件损坏"))
        assertTrue(File(marks.path + ".damaged").isFile)
        val block = vm.openBook.value!!.sourceContent.chapters[0].blocks.indexOfFirst { it.displayText != null }
        vm.toggleBookmark(0, block, 0)
        assertEquals("{broken", marks.readText())
        assertTrue(vm.marks.value.bookmarks.isEmpty())
        assertTrue(vm.messages.value.orEmpty().contains("请从备份恢复"))
    }

    @Test
    fun exitFlushIsOnDiskBeforeReturnAndOldReaderCannotOverwriteNewPage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = Library(app)
        val source = File(app.cacheDir, "immediate-exit.txt").apply { writeText("第一章 起\n　　甲乙丙丁戊己。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }
        val oldPage = Screen.Reader(entry.id, 0, 0)
        vm.open(oldPage)
        vm.saveProgressFor(entry.id, 0, 1, 0, 1, oldPage.seq)
        val newPage = Screen.Reader(entry.id, 0, 0)
        vm.replaceTop(newPage)
        vm.flushProgressFor(entry.id, 0, 1, 0, 4, newPage.seq)
        assertEquals(4, library.list().single { it.id == entry.id }.progress!!.charOffset)
        vm.flushProgressFor(entry.id, 0, 1, 0, 1, oldPage.seq)
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, library.list().single { it.id == entry.id }.progress!!.charOffset)
    }

    @Test
    fun exitFlushWaitIsBoundedWhenLibraryIsBusyAndEventuallyPersists() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = SharedLibrary.get(app)
        val source = File(app.cacheDir, "busy-flush.txt").apply { writeText("第一章 起\n　　甲乙丙丁戊己。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }
        val page = Screen.Reader(entry.id, 0, 0)
        vm.open(page)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(library) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            vm.flushProgressFor(entry.id, 0, 1, 0, 3, page.seq)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("主线程等待应受限，实际 ${elapsed}ms", elapsed < 1_000)
        } finally {
            release.countDown()
            holder.join(2_000)
        }
        waitUntilReady { library.list().single { it.id == entry.id }.progress?.charOffset == 3 }
    }

    @Test
    fun damagedCharacterTableShowsErrorWithoutDiscardingTheFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "books").deleteRecursively()
        val library = Library(app)
        val source = File(app.cacheDir, "character-read.txt").apply { writeText("第一章 起\n　　甲乙丙丁。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val table = library.bookFile(entry.id, "ai-characters.json").apply { writeText("{broken") }
        val vm = AppViewModel(app)
        waitUntilReady { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntilReady { vm.openBook.value?.entry?.id == entry.id }
        vm.loadCharacters(0, 0, 2)
        waitUntilReady { vm.characters.value.error != null }
        assertTrue(vm.characters.value.error.orEmpty().contains("读取失败"))
        assertEquals("{broken", table.readText())
    }

    private fun resetExtras(app: Application) {
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        app.filesDir.listFiles().orEmpty().filter { it.name.startsWith("stats.json") || it.name.startsWith("vocabulary.json") }.forEach { it.delete() }
    }

    private fun waitForMessage(vm: AppViewModel, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while ((!vm.messages.value.orEmpty().contains(text) || vm.busy.value) && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 附加数据各自恢复、互不影响：生词本那份坏了不挡住书、统计和设置；本机统计文件坏了时直接采用备份里的；
     * 恢复了设置以后按新设置重新排阅读目标提醒。
     */
    @Test
    fun restoreHandlesEachExtraSeparatelyAndRepairsADamagedLocalStatsFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetExtras(app)
        val library = Library(app)
        val source = File(app.cacheDir, "附加数据恢复.txt").apply { writeText("第一章 起\n　　林月推开门。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val backup = ByteArrayOutputStream()
        library.backup(
            backup,
            JSONObject().put("dailyGoal", JSONObject().put("i", 30)),
            mapOf(
                "vocabulary.json" to """[{"word":"门口"},{}]""".toByteArray(),
                "stats.json" to """{"days":{"2026-10-23":{"ms":120000}}}""".toByteArray(),
            ),
        )
        library.remove(entry.id)
        val stats = File(app.filesDir, "stats.json").apply { writeText("""{"days":[]}""") }
        val file = File(app.cacheDir, "附加数据恢复.zip").apply { writeBytes(backup.toByteArray()) }

        val vm = AppViewModel(app)
        waitUntilReady { vm.messages.value.orEmpty().contains("阅读统计读取失败") }
        vm.restore(Uri.fromFile(file))
        waitForMessage(vm, "已恢复")

        val message = vm.messages.value.orEmpty()
        assertTrue(message, message.contains("已恢复 1 本书"))
        assertTrue(message, message.contains("附加数据恢复失败") && message.contains("生词本"))
        assertFalse(message, message.contains("阅读统计"))
        assertEquals(listOf(entry.id), vm.shelf.value.map { it.id })
        assertEquals(120_000L, com.xycs.lingyuereader.data.ReadingStats(stats).days().getValue(java.time.LocalDate.of(2026, 10, 23)).millis)
        assertEquals("""{"days":[]}""", File(app.filesDir, "stats.json.damaged").readText())
        assertEquals(30, Prefs(app).settings().dailyGoal)
        val alarms = Shadows.shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(alarms.nextScheduledAlarm != null)
    }

    /** 本机统计读不出时，备份照常完成，只是不带这一项，并告诉用户；坏的那份不打进包（恢复时会被拒）。 */
    @Test
    fun backupLeavesOutAnUnreadableStatsFileAndSaysSo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetExtras(app)
        val library = Library(app)
        val source = File(app.cacheDir, "附加数据备份.txt").apply { writeText("第一章 起\n　　老周点了灯。\n") }
        library.add(library.describe(listOf(Uri.fromFile(source))), null)
        File(app.filesDir, "stats.json").writeText("""{"days":[]}""")
        File(app.filesDir, "vocabulary.json").writeText("""[{"word":"灯"}]""")
        val target = File(app.cacheDir, "附加数据备份.zip")

        val vm = AppViewModel(app)
        waitUntilReady { vm.messages.value.orEmpty().contains("阅读统计读取失败") }
        vm.backup(Uri.fromFile(target))
        waitForMessage(vm, "已备份")

        val message = vm.messages.value.orEmpty()
        assertTrue(message, message.contains("已备份 1 本书") && message.contains("阅读统计读不出"))
        val names = java.util.zip.ZipInputStream(target.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue(names.contains("data/vocabulary.json"))
        assertFalse(names.contains("data/stats.json"))
        assertEquals("""{"days":[]}""", File(app.filesDir, "stats.json").readText())
    }
}
