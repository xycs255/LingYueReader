package com.xycs.lingyuereader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiTalk
import com.xycs.lingyuereader.ai.AiTranslate
import com.xycs.lingyuereader.ai.SpeechConfig
import com.xycs.lingyuereader.ai.Talk
import com.xycs.lingyuereader.ai.TalkLine
import com.xycs.lingyuereader.ai.TalkScope
import com.xycs.lingyuereader.ai.TalkStyle
import com.xycs.lingyuereader.ai.TranslateTarget
import com.xycs.lingyuereader.ai.Translations
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.tts.AiVoice
import com.xycs.lingyuereader.tts.ReadAloud
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** 阅读 AI、本机模型、分享进来的文字：审查里查出的几处问题。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class AiReadingFixesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: HttpServer
    /** 收到的每次对话请求（发给模型的最后一句）。 */
    private val asked = CopyOnWriteArrayList<String>()
    /** 模型的回答：按请求内容给。 */
    @Volatile private var answer: (String) -> String = { "" }

    @Before
    fun setUp() {
        File(app.filesDir, "books").deleteRecursively()
        app.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear().commit()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val body = JSONObject(ex.requestBody.readBytes().decodeToString())
                val messages = body.getJSONArray("messages")
                val prompt = messages.getJSONObject(messages.length() - 1).getString("content")
                asked += prompt
                val bytes = ("data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer(prompt))))) +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        ReadAloud.stop()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待超时", condition())
    }

    private fun aiConfig() = AiConfig(enabled = true, preset = AiPresets.LOCAL, baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "chat")

    /** 导入一本两段的书、打开它，AI 连到测试服务。 */
    private fun openBook(): Pair<AppViewModel, String> {
        val library = Library(app)
        val source = File(app.cacheDir, "ai-fixes.txt").apply { writeText("第一章 林月\n　　林月推开竹门，山风灌了进来。\n　　沈默在院里扫落叶。\n") }
        val entry = library.add(library.describe(listOf(Uri.fromFile(source))), null)
        val vm = AppViewModel(app)
        vm.saveAiConfig(aiConfig())
        waitUntil { vm.shelf.value.any { it.id == entry.id } }
        vm.ensureLoaded(entry.id)
        waitUntil { vm.openBook.value?.entry?.id == entry.id }
        return vm to entry.id
    }

    @Test
    fun openingTheTalkPanelOnlyReadsSavedScripts() {
        val (vm, id) = openBook()
        val title = vm.openBook.value!!.content.chapters[0].title
        answer = { """{"title":"林月进山","lines":[{"s":0,"t":"今天讲林月。"},{"s":1,"t":"她去了哪？"}]}""" }

        // 打开面板、换形式：没存过稿子就什么也不发。
        vm.openTalk(0, 0, TalkScope.CHAPTER, TalkStyle.DUO, cachedOnly = true)
        vm.openTalk(0, 0, TalkScope.CHAPTER, TalkStyle.SOLO, cachedOnly = true)
        waitUntil { !vm.talk.value.loading }
        Thread.sleep(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("没点生成不能发请求", asked.isEmpty())
        assertNull(vm.talk.value.talk)
        assertNull(vm.talk.value.error)

        // 存过的直接打开。
        val file = Library(app).bookFile(id, AiTalk.FILE)
        AiTalk.write(file, mapOf(AiTalk.key(TalkScope.CHAPTER, TalkStyle.DUO, 0, title) to Talk("存过的稿", listOf(TalkLine(0, "存过的一句。")), at = 1)))
        vm.openTalk(0, 0, TalkScope.CHAPTER, TalkStyle.DUO, cachedOnly = true)
        waitUntil { vm.talk.value.talk != null }
        assertEquals("存过的稿", vm.talk.value.talk!!.title)
        assertTrue(asked.isEmpty())

        // 读者点了“生成”才写。
        vm.openTalk(0, 0, TalkScope.CHAPTER, TalkStyle.SOLO)
        waitUntil { vm.talk.value.talk != null && !vm.talk.value.loading }
        assertEquals("林月进山", vm.talk.value.talk!!.title)
        assertEquals(1, asked.size)
        vm.stopTalk()
    }

    @Test
    fun aTranslationThatCannotBeSavedIsReportedAndKeepsEarlierOnes() {
        val (vm, id) = openBook()
        val file = Library(app).bookFile(id, AiTranslate.FILE)
        val old = Translations(chapters = mapOf(5 to ("第六章 老周" to mapOf(0 to "旧译文。"))))
        AiTranslate.write(file, old)
        val before = file.readText()
        answer = { "[[1]] 第一章 林月\n[[2]] Lin Yue pushed the bamboo door open.\n[[3]] Shen Mo swept the yard." }
        File(file.parentFile, file.name + ".tmp").mkdir() // 写临时文件的位置被占住
        vm.consumeMessage()
        vm.translateChapter(0, TranslateTarget.ENGLISH)
        waitUntil { vm.translate.value.error != null }
        assertTrue(vm.translate.value.error!!.contains("没能保存"))
        assertFalse("不能提示已翻译", vm.messages.value.orEmpty().contains("已翻译"))
        assertEquals("旧译文原样留着", before, file.readText())
    }

    @Test
    fun aModelInstalledAfterSwitchingProvidersDoesNotRenameTheirModel() {
        File(app.filesDir, "llm").deleteRecursively()
        val vm = AppViewModel(app)
        vm.saveAiConfig(aiConfig())
        val model = File(app.cacheDir, "reader.task").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.importDeviceModel(Uri.fromFile(model))
        waitUntil { vm.deviceModels.value.task == null && vm.deviceModels.value.installed.isNotEmpty() }
        assertEquals("用的是别家服务商：模型名不动", "chat", vm.aiConfig.value.model)
        assertFalse(vm.aiConfig.value.onDevice)

        // 正在用本机模型时，装好的直接选用。
        vm.saveAiConfig(vm.aiConfig.value.withPreset(AiPresets.of(AiPresets.DEVICE)))
        val second = File(app.cacheDir, "second.task").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        vm.importDeviceModel(Uri.fromFile(second))
        waitUntil { vm.aiConfig.value.model == "file:second.task" }
        File(app.filesDir, "llm").deleteRecursively()
    }

    @Test
    fun sharingTextWithTheSameTitleTwiceImportsTheNewText() {
        val vm = AppViewModel(app)
        waitUntil { vm.shelfLoaded.value }
        fun share(body: String): String {
            vm.openSharedText(body, "杜娘的信")
            waitUntil { (vm.stack.value.lastOrNull() as? Screen.Reader) != null }
            val id = (vm.stack.value.last() as Screen.Reader).bookId
            vm.back()
            return id
        }
        val first = share("杜娘写信给顾长风。".repeat(30))
        val second = share("顾长风回信给杜娘。".repeat(30))
        assertNotEquals("第二次分享的是新内容，不能打开上次那本", first, second)
        assertEquals("内容一样的还是找回书架上那本", second, share("顾长风回信给杜娘。".repeat(30)))
    }

    @Test
    fun sharingSeveralFilesFromAnotherTabShowsTheShelf() {
        val vm = AppViewModel(app)
        waitUntil { vm.shelfLoaded.value }
        vm.selectTab(HomeTab.Write)
        val a = File(app.cacheDir, "沈默甲.txt").apply { writeText("第一章\n　　甲。\n") }
        val b = File(app.cacheDir, "沈默乙.txt").apply { writeText("第一章\n　　乙。\n") }
        vm.openExternal(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        waitUntil { vm.pendingImport.value != null }
        assertEquals("问合成还是分开的对话框在书架页上", HomeTab.Shelf, vm.homeTab.value)
        vm.dismissImport()
    }

    @Test
    fun aiReadAloudDoesNotKeepTheViewModelAlive() {
        val (vm, _) = openBook()
        vm.saveAiConfig(vm.aiConfig.value.copy(speech = SpeechConfig(enabled = true, baseUrl = "http://127.0.0.1:1/v1", model = "tts", voice = "v")))
        vm.startReadAloud(0, 0, 0, startPaused = true)
        val field = ReadAloud::class.java.getDeclaredField("ai").apply { isAccessible = true }
        val voice = field.get(null) as AiVoice
        assertTrue(voice.fetch.javaClass.declaredFields.none { AppViewModel::class.java.isAssignableFrom(it.type) })
    }
}
