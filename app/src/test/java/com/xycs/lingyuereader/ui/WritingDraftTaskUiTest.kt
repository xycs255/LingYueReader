package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.CrashLog
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.WritingAi
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.writingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 写整章草稿页编本章任务：改“写作要求”时停笔 [TASK_DEBOUNCE_MS] 毫秒才编一次，预算预估、审读用的任务不另外编；
 * 编的时候内存不够，页面上是一句提示，不闪退。以前每停笔 250 毫秒就编一次，预估、审读任务各再编一次，几次叠在一起把内存撑爆。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingDraftTaskUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""
    private val compiles = AtomicInteger()
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            // AI 设好了（预算预估要算），但地址不通：这个页面打开、改要求都不该发请求。
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:9/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("渡灯")
            workId = w.id
            chapterId = w.chapters[0].id
            store.saveCard(workId, chapterId, ChapterCard(facts = "林月到渡口等老周，沈默来了", hook = "船头的灯灭了"))
            store.addPackDoc(workId, PackKind.STYLE, "写作规范", "对白要短。")
            store.addPackDoc(workId, PackKind.CHARACTER, "人物档案", "林月：渡口长大。\n沈默：话少。")
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun pump() {
        compose.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper()
    }

    private fun waitFor(what: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            pump()
            Thread.sleep(10)
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun open(compiler: (String, String, String, String, () -> Unit) -> com.xycs.lingyuereader.data.ChapterWritingTask?): AppViewModel {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        vm.writingAi.compiler = compiler
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { vm.open(Screen.ChapterDraft(workId, chapterId)) }
        return vm
    }

    @Test
    fun typingTwentyTimesCompilesTheTaskAboutOnce() {
        val store = WorkStore(writingDir)
        open { w, c, i, t, check -> compiles.incrementAndGet(); asked += i; store.writingTask(w, c, i, t, check) }
        waitFor("打开时编好任务") { shown("本章写作任务") }
        waitFor("预算预估") { shown("写本章请求预算") }
        // 打开页面：编一次；预算预估用编好的这份，不另外编。
        assertEquals(1, compiles.get())
        val field = compose.onAllNodes(hasSetTextAction())[0]
        // 作者一个字一个字地打：每个字隔 300 毫秒（以前停 250 毫秒就编，每个字都编一次，预估再编一次）。
        repeat(20) {
            field.performTextInput("短")
            compose.mainClock.advanceTimeBy(300)
            ShadowLooper.idleMainLooper()
        }
        assertEquals("还在打字：一次都不编", 1, compiles.get())
        compose.mainClock.advanceTimeBy(TASK_DEBOUNCE_MS + 100)
        val typed = "短".repeat(20)
        waitFor("停笔后编好") { asked.lastOrNull() == typed && shown("本章写作任务") }
        waitFor("预算预估") { shown("写本章请求预算") }
        compose.mainClock.advanceTimeBy(1_000)
        ShadowLooper.idleMainLooper()
        assertEquals("连续改 20 次，停笔后只编一次：${asked}", 2, compiles.get())
    }

    @Test
    fun runningOutOfMemoryShowsAHintInsteadOfCrashing() {
        CrashLog.use(File(context.filesDir, "crash-test.json"))
        try {
            open { _, _, _, _, _ -> compiles.incrementAndGet(); throw OutOfMemoryError("Failed to allocate a 48 byte allocation") }
            waitFor("页面上报内存不够") { shown("资料太大，内存不够") }
            assertTrue(shown(WritingAi.TASK_TOO_LARGE))
            // 改要求再编：照样只是提示。
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("多写对白")
            compose.mainClock.advanceTimeBy(TASK_DEBOUNCE_MS + 100)
            waitFor("又编了一次") { compiles.get() >= 2 }
            waitFor("还是提示") { shown("资料太大，内存不够") }
            assertTrue("记进了闪退记录（接住的那种）", CrashLog.entries().any { !it.fatal && "OutOfMemoryError" in it.trace })
            assertTrue("没有闪退记录", CrashLog.entries().none { it.fatal })
        } finally {
            CrashLog.use(null)
        }
    }

    /** 细纲卡有 5 拍：预算按逐拍写 5 次请求算（以前写的是“流程 1～1 阶段”）。 */
    @Test
    fun theBudgetCountsOneRequestPerBeat() {
        val store = WorkStore(writingDir)
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(beats = "林月到渡口\n沈默来了\n两人上船\n灯灭了\n老周的信"))
        open { w, c, i, t, check -> store.writingTask(w, c, i, t, check) }
        waitFor("预算预估") { shown("写本章请求预算") }
        waitFor("按拍数算请求") { shown("共 5 次请求：逐拍写 5 拍、每拍 1 次") }
    }
}
