package com.xycs.lingyuereader

import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiClient
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.writingTask
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 写正文的协程里漏出“错误”（Error：栈溢出、内存不够这类，不是 Exception）时不能闪退。
 * 以前几条写正文的路径只接 Exception：Error 漏到 viewModelScope（没有异常处理器）就是闪退。
 * 这里让连接一打开就抛 StackOverflowError，协程放在和 viewModelScope 一样的单线程“主线程”上，
 * 漏出来的都记下（真机上就是闪退）。改好以后：界面上是一句报错，闪退记录里记一条，什么都不漏。
 */
class WritingCrashTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val escaped: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
    private val scope = CoroutineScope(SupervisorJob() + mainThread.asCoroutineDispatcher() + CoroutineExceptionHandler { _, e -> escaped += e })
    private lateinit var store: WorkStore
    private lateinit var writing: WritingAi
    private var workId = ""
    private var chapterId = ""

    @Before
    fun setUp() {
        CrashLog.use(tmp.root.resolve("crash-log.json"))
        val client = AiClient { _ -> throw StackOverflowError() }
        val ai = AiAssistant(CoroutineScope(SupervisorJob() + Dispatchers.Default), null, client, bookFile = { _, _ -> null })
        ai.saveConfig(AiConfig(enabled = true, preset = AiPresets.DEEPSEEK, protocol = AiProtocol.OPENAI, baseUrl = "https://api.deepseek.com",
            model = AiPresets.DEEPSEEK_V4_PRO, keys = mapOf(AiPresets.DEEPSEEK to "sk-test")))
        store = WorkStore(tmp.newFolder("writing"))
        val w = store.create("渡灯")
        workId = w.id
        chapterId = w.chapters[0].id
        store.saveText(workId, chapterId, "天还黑着，林月就出了门。", count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月到渡口，船上没人。", pov = "林月",
            beats = "1. 林月到渡口（800字）\n2. 杜娘开门（900字）"))
        writing = WritingAi(store, ai, scope) { }
    }

    @After
    fun tearDown() {
        scope.cancel()
        mainThread.shutdownNow()
        CrashLog.use(null)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!done() && escaped.isEmpty()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(20)
        }
    }

    private fun noCrash() = assertEquals("漏出来的异常（真机上就是闪退）：$escaped", emptyList<Throwable>(), escaped.toList())

    @Test
    fun anErrorWhileWritingInTheEditorBecomesAMessage() {
        val text = store.text(workId, chapterId)
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE)
        waitFor("编辑器里写完") { writing.inline.value?.loading == false }
        noCrash()
        val error = writing.inline.value?.error.orEmpty()
        assertTrue(error, "出了意外（StackOverflowError）" in error)
        assertTrue(CrashLog.entries().any { !it.fatal && it.trace.startsWith("java.lang.StackOverflowError") })
    }

    @Test
    fun anErrorInOneOfThreeDirectionsBecomesAMessage() {
        val text = store.text(workId, chapterId)
        writing.writeInline(workId, chapterId, text, text.length, InlineMode.SCENE, variants = true)
        waitFor("三个方向写完") { writing.inline.value?.variants?.all { !it.loading } == true }
        noCrash()
        writing.inline.value!!.variants.forEach { assertTrue(it.error.orEmpty(), "出了意外" in it.error.orEmpty()) }
    }

    @Test
    fun anErrorWhileWritingTheChapterDraftBecomesAMessage() {
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        writing.writeDraft(workId, chapterId)
        Thread.sleep(100)
        waitFor("草稿写完") { writing.drafts.value[workId to chapterId]?.loading == false }
        noCrash()
        val d = writing.drafts.value[workId to chapterId]!!
        assertFalse(d.loading)
        assertTrue(d.error.orEmpty(), "出了意外（StackOverflowError）" in d.error.orEmpty())
    }

    @Test
    fun anErrorInTheProjectFlowBecomesAMessage() {
        // 照作者的项目包：设定包齐全、前一章定稿，项目包流程才走得到写正文。
        fun res(path: String) = requireNotNull(javaClass.getResourceAsStream("/prose-fixture/$path")).use { it.readBytes().decodeToString() }
        listOf(
            "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION,
            "章节审读提示词" to PackKind.INSTRUCTION, "章节修订提示词" to PackKind.INSTRUCTION,
            "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
            "第一卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON,
            "人物档案" to PackKind.CHARACTER, "察觉表" to PackKind.AWARENESS,
            "伏笔表" to PackKind.FORESHADOW, "写作规范" to PackKind.STYLE,
            "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
        ).forEach { (name, kind) -> store.addPackDoc(workId, kind, name, res("pack/$name.md")) }
        store.renameChapter(workId, chapterId, "第1章 雾渡")
        store.saveText(workId, chapterId, res("ch1.txt").trim(), count = false)
        store.saveCard(workId, chapterId, ChapterCard(facts = "林月到渡口问父亲消息。", pov = "林月", hook = "药方末行被撕掉。",
            summary = "林月到渡口打听失踪的父亲，在枣树杈里找到半张药方。"))
        store.setStatus(workId, chapterId, ChapterStatus.FINAL)
        val volume = store.get(workId)!!.volumes[0].id
        chapterId = store.addChapter(workId, volume)!!.second.id
        store.renameChapter(workId, chapterId, "第2章 茶钱")
        store.saveCard(workId, chapterId, ChapterCard(facts = "杜娘转述老周约林月卯时渡口船上见。", pov = "林月", hook = "窗下脚步声。",
            beats = "1. 顾长风在茶馆问话（900字）\n2. 杜娘交出灯笼提杆（900字）"))
        val task = store.writingTask(workId, chapterId)
        assertTrue("项目包流程要能开始写：${task?.blocking}", task?.project == true && task.blocking.isEmpty())
        writing.loadDraft(workId, chapterId)
        waitFor("读草稿") { writing.drafts.value[workId to chapterId] != null }
        writing.writeDraft(workId, chapterId, workflow = true)
        Thread.sleep(100)
        waitFor("项目包流程停下") { writing.drafts.value[workId to chapterId]?.loading == false }
        noCrash()
        val d = writing.drafts.value[workId to chapterId]!!
        assertTrue(d.error.orEmpty(), "出了意外（StackOverflowError）" in d.error.orEmpty())
    }
}
