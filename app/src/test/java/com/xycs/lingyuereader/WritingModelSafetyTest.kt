package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritingModelSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun finalizeCallbackSeesTheLatestSavedTextAndPublishedState() = runBlocking {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completed = CompletableDeferred<Unit>()
        val model = WritingModel(store, scope) { throw AssertionError(it) }
        val text = "还没等到八百毫秒自动保存的文字。"
        try {
            model.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = text) {
                assertEquals(text, store.text(work.id, chapter))
                assertTrue(model.work(work.id)!!.chapter(chapter)!!.locked)
                assertEquals(WordCount.of(text), model.work(work.id)!!.chapter(chapter)!!.words)
                completed.complete(Unit)
            }
            withTimeout(5_000) { completed.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedSaveNeverRunsTheSuccessCallbackOrLocksTheChapter() = runBlocking {
        val root = tmp.newFolder()
        val store = WorkStore(root)
        val work = store.create("长夜")
        val chapter = work.chapters.single().id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val error = CompletableDeferred<String>()
        val model = WritingModel(store, scope) { error.complete(it) }
        File(root, "works/${work.id}/$chapter.txt.tmp").mkdir()
        var success = false
        try {
            model.setStatus(work.id, chapter, ChapterStatus.FINAL, currentText = "还没保存的新稿。") { success = true }
            assertTrue(withTimeout(5_000) { error.await() }.startsWith("改状态失败"))
            model.flush()
            assertFalse(success)
            assertFalse(store.get(work.id)!!.chapter(chapter)!!.locked)
        } finally {
            scope.cancel()
        }
    }
}
