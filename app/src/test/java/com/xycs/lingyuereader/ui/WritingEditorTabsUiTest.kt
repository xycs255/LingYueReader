package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress

/**
 * 编辑器的新入口：底栏“写 / 查 / 整理 / 更多”四个标签（每项点下去进对地方）、顶栏的上一章 / 下一章、
 * ✦ 按位置推荐、一次性的“菜单挪到底部了”提示；以 shot 开头的几个出截图给作者看（ink 开头深色、eink 开头墨水屏）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingEditorTabsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val name = TestName()
    private var workId = ""
    private val ids = mutableListOf<String>()
    private lateinit var server: HttpServer
    private val text = "林月嘴角勾起，看着窗外的雨。\n沈默推门进来，抖了抖伞上的水。\n顾长风坐在柜台后面算账。"

    private val seed = object : ExternalResource() {
        override fun before() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { ex ->
                    ex.requestBody.readBytes()
                    val bytes = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "老周的伞还靠在门边。")).put("finish_reason", "stop")))
                        .toString().toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                start()
            }
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:${server.address.port}/v1").putString("model", "test-model").commit()
            val method = name.methodName
            Prefs(context).saveAppearance(Appearance(mode = if (method.startsWith("ink")) ThemeMode.DARK else ThemeMode.LIGHT, blur = false, liquidGlass = false))
            if (method.startsWith("eink")) context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("eInk", true).commit()
            // 截图里不要那条一次性提示（另有一张专门截它）。
            if (method.contains("shot", ignoreCase = true) && !method.contains("Tip")) {
                context.getSharedPreferences("writing_editor", Context.MODE_PRIVATE).edit().putBoolean("tabs_tip", true).commit()
            }
            val store = WorkStore(writingDir)
            val w = store.create("长夜", "沈默")
            workId = w.id
            ids.clear()
            ids += w.chapters[0].id
            store.renameChapter(w.id, ids[0], "第一章 雨夜")
            ids += store.addChapter(w.id, w.volumes[0].id, "第二章 渡口")!!.second.id
            ids += store.addChapter(w.id, w.volumes[0].id, "第三章 旧账")!!.second.id
            store.saveText(w.id, ids[0], text)
            store.saveText(w.id, ids[1], "天终于亮了。")
            store.saveCard(w.id, ids[0], ChapterCard(facts = "林月在药铺等人", beats = "林月看雨\n杜娘开门\n林月识破沈默", beatsDone = listOf(1)))
        }

        override fun after() {
            server.stop(0)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasDescription(t: String, substring: Boolean = false) =
        compose.onAllNodesWithContentDescription(t, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(t: String) = waitFor { has(t) }
    private fun top() = vm.stack.value.last()

    private fun openEditor(index: Int = 0) {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, ids[index])) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        waitFor { hasDescription("智能推荐：", substring = true) }
    }

    private fun back(to: (Screen) -> Boolean = { it is Screen.Editor }) {
        compose.runOnUiThread { vm.back() }
        waitFor { to(top()) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
    }

    private fun tab(group: EditorToolGroup) = compose.onNodeWithContentDescription(group.description ?: group.label)
        .takeIf { group.description != null } ?: compose.onNodeWithText(group.label)

    private fun body() = compose.onNodeWithContentDescription("正文").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** 四个标签都能展开、再点收起；每个标签抽两项点下去，进的是对的地方。 */
    @Test
    fun everyTabOpensAndItsEntriesGoToTheRightPlace() {
        openEditor()
        // 写：AI 写… 弹出“AI 写”对话框；标点换成一排快捷标点，点了插进正文。
        tab(EditorToolGroup.WRITE).performClick()
        listOf("AI 写…", "灵感", "整章草稿", "改稿", "标点").forEach { waitForText(it) }
        tab(EditorToolGroup.WRITE).assertIsSelected()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("从光标处往下写")
        compose.onNodeWithText("取消").performClick()
        waitFor { !has("从光标处往下写") }
        tab(EditorToolGroup.WRITE).performClick()
        compose.onNodeWithText("标点").performClick()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("插入……").performClick()
        waitFor { body() == "$text……" }
        // 再点一次“写”：收起。
        tab(EditorToolGroup.WRITE).performClick()
        waitFor { !hasDescription("插入……") }

        // 查：问题打开问题面板；本章资料进本章资料页。
        tab(EditorToolGroup.CHECK).performClick()
        listOf("问题", "本章资料", "故事账本", "三道审稿").forEach { waitForText(it) }
        compose.onNodeWithText("问题").performClick()
        waitFor { hasDescription("收起") }
        tab(EditorToolGroup.CHECK).performClick()
        compose.onNodeWithText("本章资料").performClick()
        waitFor { top() is Screen.ChapterBrief }
        back()

        // 整理：细纲卡进细纲卡页；自动排版打开排版预览。
        tab(EditorToolGroup.ORGANIZE).performClick()
        listOf("细纲卡", "这章写完了", "自动排版", "历史版本").forEach { waitForText(it) }
        compose.onNodeWithText("细纲卡").performClick()
        waitFor { top() is Screen.ChapterCard }
        back()
        tab(EditorToolGroup.ORGANIZE).performClick()
        compose.onNodeWithText("自动排版").performClick()
        waitFor { hasDescription("排版设置", substring = true) || has("排版设置") }

        // 更多：章节状态弹状态对话框；改章名弹改名框。
        tab(EditorToolGroup.MORE).performClick()
        listOf("章节状态", "改章名", "预览本章", "听本章").forEach { waitForText(it) }
        assertFalse("不是最后一章，没有“写下一章”", has("写下一章"))
        compose.onNodeWithText("章节状态").performClick()
        waitForText("的状态")
        compose.onNodeWithText("取消").performClick()
        waitFor { !has("的状态") }
        tab(EditorToolGroup.MORE).performClick()
        compose.onNodeWithText("改章名").performClick()
        waitFor { has("保存") && has("章名") }
    }

    /** 右上“⋯”只留不常用的几样，旧菜单的其余项都不在那里了。 */
    @Test
    fun theOverflowMenuOnlyKeepsTheRareOnes() {
        openEditor()
        compose.onNodeWithContentDescription("更多").performClick()
        EDITOR_OVERFLOW.forEach { waitForText(it.label) }
        listOf("细纲卡", "预览本章", "历史版本", "改章名").forEach { assertFalse(it, has(it)) }
        compose.onNodeWithText("设定包").performClick()
        waitFor { top() is Screen.WorkPack }
    }

    /** 章名两边的箭头换章；第一章没有上一章、最后一章没有下一章，那一边变灰（最后一章“更多”里有“写下一章”）。 */
    @Test
    fun arrowsStepThroughChaptersAndGreyOutAtTheEnds() {
        openEditor()
        compose.onNodeWithContentDescription("上一章").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一章").assertIsEnabled().performClick()
        waitFor { (top() as? Screen.Editor)?.chapterId == ids[1] }
        waitForText("天终于亮了。")
        waitFor { compose.onAllNodesWithContentDescription("正文").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("上一章").assertIsEnabled()
        compose.onNodeWithContentDescription("下一章").performClick()
        waitFor { (top() as? Screen.Editor)?.chapterId == ids[2] }
        waitFor { compose.onAllNodesWithContentDescription("正文").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("下一章").assertIsNotEnabled()
        tab(EditorToolGroup.MORE).performClick()
        waitForText("写下一章")
        compose.onNodeWithContentDescription("上一章").performClick()
        waitFor { (top() as? Screen.Editor)?.chapterId == ids[1] }
    }

    /** “菜单挪到底部了”只提示一次：换章、再打开别的作品都不再出现。 */
    @Test
    fun theMovedMenuTipShowsOnlyOnce() {
        openEditor()
        waitForText("菜单挪到底部了")
        assertFalse(vm.editorTabsTipPending())
        compose.onNodeWithText("知道了").performClick()
        waitFor { !has("菜单挪到底部了") }
        compose.runOnUiThread { vm.back() }
        waitFor { top() !is Screen.Editor }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        openEditor(1)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertFalse(has("菜单挪到底部了"))
        assertEquals(false, vm.editorTabsTipPending())
    }

    /** 点了标签，提示跟着收起。 */
    @Test
    fun tappingATabDismissesTheTip() {
        openEditor()
        waitForText("菜单挪到底部了")
        tab(EditorToolGroup.CHECK).performClick()
        waitFor { !has("菜单挪到底部了") }
    }

    /** ✦ 跟着光标走：选中文字是润色（+ 扩写、去 AI 腔），段中间是润色这段，章末有没写的拍是写第 2 拍。 */
    @Test
    fun sparkFollowsTheCaret() {
        openEditor()
        waitFor { hasDescription("智能推荐：写第 2 拍", substring = true) }
        // 选中“林月嘴角勾起”：命中 AI 腔，三项都给。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(0, 6))
        waitFor { hasDescription("智能推荐：润色", substring = true) }
        waitFor { hasDescription("还有 2 项推荐") }
        compose.onNodeWithContentDescription("还有 2 项推荐").performClick()
        waitForText("扩写")
        waitForText("去 AI 腔")
        compose.onNodeWithContentDescription("收起推荐").performClick()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(18))
        waitFor { hasDescription("智能推荐：润色这段", substring = true) }
        // 点推荐的“写第 2 拍”：直接在光标处写，结果先显示等采纳。
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        waitFor { hasDescription("智能推荐：写第 2 拍", substring = true) }
        compose.onNodeWithContentDescription("智能推荐：写第 2 拍", substring = true).performClick()
        waitForText("采纳")
    }

    // ---------- 截图 ----------

    private fun capture(name: String) {
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/editor/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 浅色：键盘收起，四个标签各展开一次；✦ 在三种位置。 */
    @Test
    fun shotLight() {
        openEditor()
        waitFor { hasDescription("智能推荐：写第 2 拍", substring = true) }
        capture("light-collapsed")
        EditorToolGroup.entries.forEach { g ->
            tab(g).performClick()
            waitFor { has(editorTabEntries(g, locked = false, lastChapter = false).first().label) }
            compose.mainClock.advanceTimeBy(600)
            capture("light-tab-${g.name.lowercase()}")
        }
        tab(EditorToolGroup.MORE).performClick()
        // ✦：章末（有没写的拍）、段中间、选中文字。
        compose.onNodeWithContentDescription("还有 1 项推荐").performClick()
        compose.mainClock.advanceTimeBy(600)
        capture("spark-chapter-end")
        compose.onNodeWithContentDescription("收起推荐").performClick()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(18))
        waitFor { hasDescription("还有 1 项推荐") }
        compose.onNodeWithContentDescription("还有 1 项推荐").performClick()
        compose.mainClock.advanceTimeBy(600)
        capture("spark-mid-paragraph")
        compose.onNodeWithContentDescription("收起推荐").performClick()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(0, 6))
        waitFor { hasDescription("还有 2 项推荐") }
        compose.onNodeWithContentDescription("还有 2 项推荐").performClick()
        compose.mainClock.advanceTimeBy(600)
        capture("spark-selection")
    }

    @Test
    fun shotTip() {
        openEditor()
        waitForText("菜单挪到底部了")
        compose.mainClock.advanceTimeBy(600)
        capture("light-tip")
    }

    @Test
    fun inkShotDark() {
        openEditor()
        capture("dark-collapsed")
        tab(EditorToolGroup.WRITE).performClick()
        waitForText("灵感")
        compose.mainClock.advanceTimeBy(600)
        capture("dark-tab-write")
    }

    @Test
    fun einkShot() {
        openEditor()
        capture("eink-collapsed")
        tab(EditorToolGroup.CHECK).performClick()
        waitForText("故事账本")
        capture("eink-tab-check")
    }

    /**
     * 360dp 宽、键盘占掉下半屏（Robolectric 模拟不了键盘，把屏幕高压到一半）：展开一个标签后，那排和标签合成一行，
     * 正文还看得见至少 5 行（一行 32sp）。
     */
    @Test
    @Config(qualifiers = "w360dp-h320dp")
    fun smallShotKeyboardHalfScreen() {
        openEditor()
        capture("small360-keyboard-collapsed")
        tab(EditorToolGroup.WRITE).performClick()
        waitForText("灵感")
        compose.mainClock.advanceTimeBy(600)
        // 合成一行：左边是“收起”的标签，原来的四个标签不在了。
        waitFor { hasDescription("收起“写”") }
        assertFalse(has("整理"))
        val body = compose.onNodeWithContentDescription("正文").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        // 正文从顶栏下面（返回钮下沿再加顶栏的 6dp 留白）往下 12dp 开始，到正文框底边上方 4dp（矮屏）为止。
        val visible = (body.bottom - 4.dp) - (back.bottom + 6.dp + 12.dp)
        assertTrue("正文只剩 $visible 高，放不下 5 行", visible >= 32.dp * 5)
        capture("small360-keyboard-tab-write")
        compose.onNodeWithText("改稿").performClick()
        waitForText("更生动")
        capture("small360-keyboard-revise")
    }

    /** 360 × 640 的小屏，键盘收起：展开的那排在标签上面。 */
    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun smallShot() {
        openEditor()
        tab(EditorToolGroup.WRITE).performClick()
        waitForText("灵感")
        compose.mainClock.advanceTimeBy(600)
        capture("small360-tab-write")
    }
}
