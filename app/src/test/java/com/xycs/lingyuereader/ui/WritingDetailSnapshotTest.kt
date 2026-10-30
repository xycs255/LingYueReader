package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.GateResult
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 写作细节页的实测截图：编辑器四组工具、底栏、故事账本四个标签与对话框、规划树调整顺序、
 * 细纲卡、AI 写整章草稿、三道审稿、设定包、生词本，以及“AI 写”面板、灵感、作品 AI 设置与高级设置、排版设置、“这章写完了”收尾页、
 * 编辑器的“问题”面板（状态行的“N 处问题”、全部和各个筛选）。只截图供设计复核，不做像素断言；
 * 每张截图前都断言关键文字，确认真的点到了那个状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingDetailSnapshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: HttpServer? = null

    private val seed = object : ExternalResource() {
        /** 方法名里带 “Ai” 的测试把写作 AI 配好（指向本地假服务器），其余保持未配置。 */
        private var withAi = false

        /** 方法名以 “ink” 开头的测试用深色（暖墨）主题。 */
        private var ink = false

        /** 方法名以 “eink” 开头的测试打开“墨水屏 / 省电模式”（浅色）。 */
        private var eInk = false

        override fun apply(base: Statement, description: Description): Statement {
            withAi = description.methodName.orEmpty().contains("Ai")
            ink = description.methodName.orEmpty().startsWith("ink")
            eInk = description.methodName.orEmpty().startsWith("eink")
            return super.apply(base, description)
        }

        override fun before() {
            val files = context.filesDir
            // 书架：只有一本书。
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("第一章 渡口\n　　河上的灯一盏盏亮起来。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "渡口夜话").put("author", "沈青禾").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 13)
                .put("sources", JSONArray().put(JSONObject().put("name", "渡口夜话.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
            // 生词本：几个学习中的词，一个已掌握的词；收词时间是这几天，卡片上不显示 1970 年。
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            File(files, "vocabulary.json").writeText(
                """[{"word":"渡口","reading":"dù kǒu","gloss":"ferry crossing","context":"河上的灯一盏盏亮起来，渡口还没有船。","bookTitle":"渡口夜话","addedAt":$now},
                   {"word":"盏","reading":"zhǎn","gloss":"measure word for lamps","context":"河上的灯一盏盏亮起来。","bookTitle":"渡口夜话","addedAt":${now - 1 * day}},
                   {"word":"封航","reading":"fēng háng","gloss":"to suspend shipping","context":"雪下大了，渡口封航。","bookTitle":"渡口夜话","addedAt":${now - 2 * day}},
                   {"word":"船夫","reading":"chuán fū","gloss":"boatman","context":"船夫把桨收进舱里。","bookTitle":"渡口夜话","addedAt":${now - 3 * day},"mastered":true}]""",
            )
            File(files, "writing").deleteRecursively()
            File(files, "llm").deleteRecursively()
            seedWork(WorkStore(File(files, "writing")))
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val ai = context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
            if (withAi) {
                // 照 WritingRound2UiTest：本地假服务器，只回一句话；截图过程中并不真的发请求。
                server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/v1/chat/completions") { ex ->
                        val asked = ex.requestBody.readBytes().decodeToString()
                        // 收尾截图：整理请求先挂着（截“进行中”），放行后回整理结果和摘要；别的请求照旧回一句话。
                        val answer = if (FINISH_SYNC in asked) {
                            finishGate.await(20, TimeUnit.SECONDS)
                            FINISH_ANSWER
                        } else if ("只找明显的错误" in asked || "只找下面这几类问题" in asked) PROBLEMS_SUGGEST
                        else "1. 让门外有脚步声。"
                        val bytes = JSONObject().put("choices", JSONArray().put(JSONObject()
                            .put("message", JSONObject().put("content", answer)).put("finish_reason", "stop")))
                            .toString().toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(200, bytes.size.toLong())
                        ex.responseBody.use { it.write(bytes) }
                    }
                    executor = Executors.newCachedThreadPool()
                    start()
                }
                ai.putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                    .putString("baseUrl", "http://127.0.0.1:${server!!.address.port}/v1").putString("model", "test-model")
            }
            ai.commit()
            Prefs(context).saveAppearance(Appearance(mode = if (ink) ThemeMode.DARK else ThemeMode.LIGHT, blur = false, liquidGlass = false))
            if (eInk) context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("eInk", true).commit()
        }

        override fun after() {
            finishGate.countDown()
            server?.stop(0)
            server = null
        }
    }
    /** 收尾截图里挂住整理请求的闸：截完“进行中”再放行。 */
    private val finishGate = CountDownLatch(1)
    private val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private var workId = ""
    private var chapterThree = ""
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    /** 作品《长夜》：30 章、几章正文与细纲卡、设定包几份文档、账本里的人物、伏笔和知情。 */
    private fun seedWork(store: WorkStore) {
        val work = store.create("长夜", "沈青禾")
        workId = work.id
        val volume = work.volumes[0].id
        val names = listOf(
            "雪夜", "渡口", "夜渡", "客栈", "旧账", "沉船", "玉佩", "茶馆", "约定", "离岸",
            "暗流", "城南", "偷听", "对质", "卧底", "火光", "残卷", "盐帮", "旧人", "归途",
            "风起", "截杀", "石桥", "夜谈", "船票", "破绽", "围城", "对岸", "灯灭", "天亮",
        )
        store.renameChapter(work.id, work.chapters[0].id, "第1章 ${names[0]}")
        val ids = mutableListOf(work.chapters[0].id)
        names.drop(1).forEachIndexed { i, name -> ids += store.addChapter(work.id, volume, "第${i + 2}章 $name")!!.second.id }
        chapterThree = ids[2]
        store.saveText(work.id, ids[0], "林月推开门，风雪灌进来。")
        store.saveText(work.id, ids[1], "渡口的灯还亮着，老周蹲在船头抽烟。")
        store.saveText(work.id, ids[2], "林月把半块玉佩攥在手心。\n\n“今晚还有船吗？”她问。\n\n老周没抬头：“封航了，明天也未必有。”")
        store.saveCard(work.id, ids[1], ChapterCard(
            facts = "林月到渡口，得知封航", boundary = "不揭开老周的过去", pov = "林月",
            hook = "对岸亮起一盏灯", threads = "明线",
        ))
        store.saveCard(work.id, ids[2], ChapterCard(
            facts = "林月求老周夜里渡河，老周拒绝；沈默出现，替她付了船钱",
            boundary = "沈默的来意不点破；玉佩只露一角",
            pov = "林月",
            countdown = "天亮前必须过河",
            hook = "船行到河心，沈默问起那半块玉佩",
            threads = "明线：过河；暗线：玉佩",
        ))
        store.addPackDoc(work.id, PackKind.OUTLINE, "总纲", "林月追查父亲沉船的真相，最后在天亮前渡过对岸。")
        store.addPackDoc(work.id, PackKind.CHARACTER, "人物档案", "林月：想要真相，怕连累旁人。\n沈默：话少，随身带一把旧伞。")
        store.addPackDoc(work.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋 | 收 |\n| 半块玉佩 | 1 | 12 |")
        store.addPackDoc(work.id, PackKind.STYLE, "写作规范", "对白要短；不写心理独白。")
        store.addPackDoc(work.id, PackKind.REFERENCE, "渡口地图说明", "渡口在城南，对岸是旧盐仓。")
        store.applyLedger(work.id, ledgerEntries(), "截图示例")
    }

    private fun state(who: String, aspect: String, text: String, from: Int) =
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.STATE, text = text, subject = who, aspect = aspect, from = from))

    private fun ledgerEntries(): List<LedgerOp> = listOf(
        // 人物状态：每人好几个方面，让那排胶囊溢出。
        state("林月", "位置", "在渡口客栈二楼", 3),
        state("林月", "身体", "左臂刀伤未愈", 2),
        state("林月", "随身", "半块玉佩、一把短刀", 1),
        state("林月", "目标", "找到沉船那夜的账册", 4),
        state("林月", "关系", "与沈默暂时结盟", 9),
        state("林月", "情绪", "戒备，不信任何人", 5),
        state("沈默", "位置", "在城南茶馆", 8),
        state("沈默", "随身", "一把旧伞", 3),
        state("沈默", "目标", "查清盐帮旧账", 6),
        state("沈默", "身份", "自称账房先生", 3),
        state("老周", "位置", "守着渡口的旧船", 2),
        state("老周", "身体", "右腿跛了多年", 2),
        state("老周", "情绪", "提起沉船就沉默", 6),
        state("杜娘", "位置", "城南茶馆柜台后", 8),
        state("杜娘", "身份", "茶馆老板娘", 8),
        state("杜娘", "关系", "与顾长风有旧", 12),
        state("顾长风", "位置", "离开渡口北上", 10),
        state("顾长风", "目标", "三日后回来接人", 9),
        // 伏笔：开着的、过期没收的、已收的、计划未定的。
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "顾长风说三日后再来", subject = "顾长风", from = 9, payoff = 26)),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "老周藏起的半张船票", subject = "老周", from = 2, payoff = 6)),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "半块玉佩的来历", subject = "林月", from = 1, payoff = 12, done = true, closed = 12)),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "渡口那盏不灭的灯", from = 3)),
        // 知情：有人知道、有人后来才知道，以及只有作者知道的。
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "沈默", from = 15, via = "偷听")),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "林月", from = 22, via = "沈默告知")),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "杜娘是卧底", subject = "顾长风", secret = true, origin = LedgerOrigin.IMPORT)),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "沉船那夜有人纵火", subject = "老周", from = 1, via = "亲眼所见")),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "沉船那夜有人纵火", subject = "林月", from = 18, via = "残卷")),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.KNOW, text = "老周就是当年的船主", secret = true, origin = LedgerOrigin.IMPORT)),
        // 一件事和一条时间线。
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "老周的船沉了", from = 3, origin = LedgerOrigin.IMPORT)),
        LedgerOp.Add(LedgerEntry(kind = LedgerKind.TIME, text = "离开渡口第三天", from = 9)),
    )

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun count(text: String, substring: Boolean = true) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private fun waitForText(text: String) = waitFor { count(text) > 0 }

    private fun waitForGone(text: String) = waitFor { count(text) == 0 }

    private fun waitForDescription(text: String) =
        waitFor { compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 在作品页的列表里滚到 [target]。列表先按“作品进度”找到，再按节点 id 认：
     * 滚下去以后“作品进度”就不在树里了，不能一直靠它认列表。
     */
    private fun scrollWorkListTo(target: SemanticsMatcher) {
        compose.waitForIdle()
        val id = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("作品进度"))).fetchSemanticsNode().id
        val list = compose.onNode(SemanticsMatcher("作品页列表") { it.id == id })
        list.performScrollToNode(target)
        // 滚到的那一行贴在列表顶上、被标题栏盖住：再往回滚一点，让它露出来、点得到。
        val back = with(compose.density) { 160.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -back) }
        compose.waitForIdle()
    }

    /** 从书架点进《长夜》作品页。 */
    private fun openWork() {
        waitForText("渡口夜话")
        compose.onNodeWithText("写作").performClick()
        waitForText("长夜")
        compose.onNodeWithText("长夜").performClick()
        waitForText("作品进度")
    }

    /** 在作品页的规划树里点第 3 章，进编辑器。 */
    private fun openChapterThree() {
        openWork()
        val row = hasText("第3章 夜渡") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        // 规划树是异步读出来的：机器忙时列表里可能还没有这一章，或者滚到以后列表又重读了一次、这一行暂时不在；
        // 滚不到、滚到了却找不着，都等一等再滚。
        val deadline = System.nanoTime() + 15_000_000_000L
        while (runCatching { scrollWorkListTo(row); compose.onNode(row).performScrollTo() }.isFailure) {
            check(System.nanoTime() < deadline) { "作品页里一直找不到第 3 章" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        compose.onNode(row).performClick()
        waitForText("半块玉佩攥在手心")
        waitForDescription("AI 写作")
    }

    /** 点底栏“写”：上面展开一排大图标。 */
    private fun openTools() {
        compose.onNodeWithContentDescription("AI 写作").performClick()
        waitForDescription("灵感")
    }

    /** 点底栏的一个标签，等这个标签的第一个入口出现。 */
    private fun pickGroup(label: String, firstShortcut: String) {
        compose.onNodeWithText(label).performClick()
        waitForDescription(firstShortcut)
    }

    @Test
    fun editorToolsBottomCardAndPack() {
        openChapterThree()
        // 没配置写作 AI：底栏是描边的“设置 AI 服务”次要按钮。
        waitForText("设置 AI 服务")
        capture("detail-editor-bottom")

        openTools()
        waitForDescription("整章草稿")
        waitForDescription("标点")
        capture("detail-editor-tools-write")
        pickGroup("查", "三道审稿")
        waitForDescription("问题")
        capture("detail-editor-tools-check")
        pickGroup("整理", "细纲卡")
        waitForDescription("历史版本")
        capture("detail-editor-tools-tidy")
        pickGroup("更多", "章节状态")
        waitForDescription("预览本章")
        capture("detail-editor-tools-more")

        // 细纲卡：从“整理”里点进去。
        pickGroup("整理", "细纲卡")
        compose.onNodeWithContentDescription("细纲卡").performClick()
        waitForText("沈默的来意不点破")
        waitForText("上一章「第2章 渡口」留下的")
        capture("detail-chapter-card")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForDescription("AI 写作")

        // 设定包：不常用，在右上“⋯”里。
        compose.onNodeWithContentDescription("更多").performClick()
        waitForText("设定包")
        compose.onNodeWithText("设定包").performClick()
        waitForText("渡口地图说明")
        waitForText("人物档案")
        capture("detail-pack")
    }

    @Test
    fun editorWithAiBottomDraftAndReview() {
        openChapterThree()
        // 配好写作 AI、正文不空、没有选字：推荐“AI 接着写”。
        waitForText("AI 接着写")
        waitForGone("设置 AI 服务")
        capture("detail-editor-bottom-ai")

        // AI 写整章草稿：没有候选稿。
        openTools()
        compose.onNodeWithContentDescription("整章草稿").performClick()
        waitForText("开始创作")
        waitForGone("正在读取已保存的候选稿")
        waitForGone("正在核对本章任务与资料")
        waitForText("本章写作任务")
        waitForGone("正在按本章资料估算")
        capture("detail-draft")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForDescription("AI 写作")

        // 三道审稿：还没开始审。
        openTools()
        pickGroup("查", "三道审稿")
        compose.onNodeWithContentDescription("三道审稿").performClick()
        waitForText("还没有审过")
        waitForText("开始审稿")
        waitForText("依次审三关")
        capture("detail-review")
    }

    @Test
    fun ledgerTabsAndDialogs() {
        openWork()
        compose.onNodeWithText("故事账本").performScrollTo().performClick()
        // 从作品页进来默认是“人物”标签。
        waitForText("人物状态 · 点方面直接调整")
        waitForText("身体 · 左臂刀伤未愈")
        capture("detail-ledger-people")

        compose.onNodeWithText("伏笔").performClick()
        waitForText("还没回收 · 拖动计划收章")
        waitForText("老周藏起的半张船票")
        capture("detail-ledger-foreshadow")

        compose.onNodeWithText("知情").performClick()
        waitForText("谁知道什么 · 点格子核对")
        waitForText("真相（作者）")
        capture("detail-ledger-know")

        compose.onNodeWithText("全部记录").performClick()
        waitForText("人物状态 · 杜娘")
        capture("detail-ledger-all")

        // 看到第几章：章节选择对话框。
        compose.onNodeWithText("看到第几章").performClick()
        waitForText("列出所有条目")
        waitForText("第3章 夜渡")
        capture("detail-ledger-chapter-pick")
        // 快速定位：拖到第 20 章，列表滚过去；拖回第 1 章，“全部”又露出来。
        waitForDescription("快速定位")
        compose.onNodeWithContentDescription("快速定位").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        waitForText("第20章 归途")
        waitForGone("第3章 夜渡")
        capture("detail-ledger-chapter-quick")
        compose.onNodeWithContentDescription("快速定位").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForText("列出所有条目")
        compose.onNodeWithText("列出所有条目").performClick()
        waitForGone("列出所有条目")

        // 点开一条：编辑对话框。章号是步进器（第 8 章起，止章不填）。
        compose.onNodeWithText("茶馆老板娘").performScrollTo().performClick()
        waitForText("改一条")
        waitForText("删除这一条")
        capture("detail-ledger-edit")
        compose.onNodeWithContentDescription("到第几章止加一").performScrollTo()
        compose.onNode(hasSetTextAction() and hasText("8")).assertExists()
        waitForText("不填")
        capture("detail-ledger-edit-steppers")
    }

    @Test
    fun workTreeReorder() {
        openWork()
        // 滚到“调整章节顺序”，让它和前几章同在画面里。
        scrollWorkListTo(hasText("调整章节顺序"))
        compose.onNodeWithText("调整章节顺序").performClick()
        waitForText("完成调整")
        waitForText("用每章右侧的箭头上移、下移")
        waitForDescription("下移第1章 雪夜")
        capture("detail-work-tree-reorder")
    }

    /** 深色（暖墨）下再拍一遍这次改过的几处：规划树调整顺序、账本人物与知情、编辑对话框、三道审稿、生词本。 */
    @Test
    fun inkLedgerTreeReviewAndVocabulary() {
        openWork()
        scrollWorkListTo(hasText("调整章节顺序"))
        compose.onNodeWithText("调整章节顺序").performClick()
        waitForDescription("下移第1章 雪夜")
        capture("ink-detail-work-tree-reorder")
        compose.onNodeWithText("完成调整").performClick()
        waitForText("调整章节顺序")

        compose.runOnUiThread { vm.open(Screen.WorkLedger(workId)) }
        waitForText("人物状态 · 点方面直接调整")
        waitForText("身体 · 左臂刀伤未愈")
        capture("ink-detail-ledger-people")
        compose.onNodeWithText("知情").performClick()
        waitForText("谁知道什么 · 点格子核对")
        capture("ink-detail-ledger-know")
        compose.onNodeWithText("全部记录").performClick()
        waitForText("人物状态 · 杜娘")
        compose.onNodeWithText("茶馆老板娘").performScrollTo().performClick()
        waitForText("改一条")
        compose.onNodeWithContentDescription("到第几章止加一").performScrollTo()
        capture("ink-detail-ledger-edit-steppers")
        compose.onNodeWithText("取消").performClick()
        waitForGone("改一条")

        compose.runOnUiThread { vm.open(Screen.ChapterReview(workId, chapterThree)) }
        waitForText("还没有审过")
        waitForText("依次审三关")
        capture("ink-detail-review")
        // 一层层返回：审稿 → 账本 → 作品页 → 写作首页，再点底栏的生词本。
        compose.onNodeWithContentDescription("返回").performClick()
        waitForGone("依次审三关")
        waitForText("看到第几章")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("作品进度")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForGone("作品进度")
        compose.onNodeWithText("生词本").performClick()
        waitForText("学习中 3")
        waitForText("复习 3")
        capture("ink-detail-vocabulary-cards")
    }

    @Test
    fun vocabularyCardsAndSingleBookShelf() {
        waitForText("渡口夜话")
        capture("detail-shelf-one")
        compose.onNodeWithText("生词本").performClick()
        waitForText("学习中 3")
        waitForText("已掌握 1")
        waitForText("fēng háng")
        waitForText("复习 3")
        capture("detail-vocabulary-cards")
    }

    /** 第 3 章细纲卡加三拍、第 1 拍写过：“AI 写”面板列节拍，推荐“写第 2 拍”。 */
    private fun seedBeats() {
        val store = WorkStore(File(context.filesDir, "writing"))
        store.saveCard(workId, chapterThree, store.card(workId, chapterThree).copy(
            beats = "林月求老周夜里渡河\n沈默出现，替她付了船钱\n船行到河心，沈默问起玉佩", beatsDone = listOf(1)))
    }

    /** 写作流程精简：“AI 写”面板（节拍、写多少、3 个方向）、推荐“写第 N 拍”、更多菜单、灵感（问主意、选范本）。 */
    private fun aiWriteAndCompanion(prefix: String) {
        seedBeats()
        openChapterThree()
        waitForDescription("智能推荐：写第 2 拍")
        capture("$prefix-editor-bottom-beat")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("AI 写…").performClick()
        waitForText("从光标处往下写")
        waitForText("写第 2 拍")
        capture("$prefix-ai-write-panel")
        compose.onNodeWithText("出 3 个方向的版本（花费约 3 倍）").performScrollTo()
        capture("$prefix-ai-write-panel-bottom")
        compose.onNodeWithText("取消").performClick()
        waitForGone("从光标处往下写")
        compose.onNodeWithText("整理").performClick()
        waitForText("细纲卡")
        capture("$prefix-editor-more-menu")
        // “整理”里点“细纲卡”，再返回编辑器。
        compose.onNodeWithText("细纲卡").performClick()
        waitForText("沈默的来意不点破")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForDescription("AI 写作")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("灵感").performClick()
        waitForText("问主意")
        waitForText("没选范本")
        capture("$prefix-companion")
    }

    @Test
    fun simplifyAiWritePanelAndCompanion() = aiWriteAndCompanion("simplify")

    @Test
    fun inkSimplifyAiWritePanelAndCompanion() = aiWriteAndCompanion("ink-simplify")

    /** 作品 AI 设置分两层、高级设置、排版设置默认只露两样和展开后。 */
    @Test
    fun simplifyAiSettingsAndFormat() {
        openWork()
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitForText("这部书的口味")
        capture("simplify-work-ai-top")
        compose.onNodeWithText("高级设置").performScrollTo()
        capture("simplify-work-ai-bottom")
        compose.onNodeWithText("高级设置").performClick()
        waitForText("知识库上限")
        capture("simplify-work-ai-advanced")
        compose.onNodeWithText("最近一次发给 AI 的内容").performScrollTo()
        capture("simplify-work-ai-advanced-bottom")
        compose.runOnUiThread { vm.open(Screen.WorkFormatSettings(workId)) }
        waitForText("输入时自动排版")
        waitForText("更多排版规则")
        capture("simplify-format")
        compose.onNodeWithText("更多排版规则").performClick()
        waitForText("段首段尾空白")
        capture("simplify-format-more")
    }

    /**
     * “这章写完了”收尾页：开始前（勾选项、本机检查的提醒）、进行中（整理和摘要合成的那次请求还挂着）、确认（摘要、整理结果、完成收尾）。
     * 加一条检查规则，让“还有 N 条不合规则”的提醒露出来。
     */
    private fun finishChapter(prefix: String) {
        WorkStore(File(context.filesDir, "writing")).addPackDoc(workId, PackKind.RULES, "检查规则", "禁用：没抬头\n")
        openChapterThree()
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithText("这章写完了").performClick()
        waitForText("收尾要做的")
        waitForText("条不合规则")
        capture("$prefix-finish-setup")
        compose.onNodeWithText("开始收尾").performClick()
        waitForDescription("整理进设定包和账本：在做")
        waitForDescription("写本章摘要：在做")
        capture("$prefix-finish-running")
        finishGate.countDown()
        waitForText("完成收尾")
        waitForText("本章摘要")
        waitForText("新规矩：夜里封航")
        capture("$prefix-finish-confirm")
    }

    @Test
    fun finishChapterAiPages() = finishChapter("finish")

    @Test
    fun inkFinishChapterAiPages() = finishChapter("ink-finish")

    @Test
    fun einkFinishChapterAiPages() = finishChapter("eink-finish")

    /**
     * 编辑器的“问题”面板：第 3 章有一条检查规则的命中、一处 AI 腔、两条写作建议（打完最后一句才查）、上次审稿没处理的两条。
     * 拍状态行的“N 处问题”，再拍面板的全部和四个筛选。
     */
    private fun problemsPanel(prefix: String) {
        val store = WorkStore(File(context.filesDir, "writing"))
        store.addPackDoc(workId, PackKind.RULES, "检查规则", "禁用：没抬头\n")
        store.saveText(workId, chapterThree, "林月把半块玉佩攥在手心，嘴角勾起。\n\n“今晚还有船吗？”她问。\n\n老周没抬头：“封航了，明天也末必有")
        store.updateAssist(workId) { it.copy(realtime = true) }
        store.saveReview(
            workId, chapterThree,
            ChapterReview(
                1, System.currentTimeMillis(),
                listOf(
                    GateResult(ReviewGate.FACT, listOf(ReviewIssue("“今晚还有船吗？”她问。", "细纲卡里林月上一章已经知道封航，不该再问", "改成她明知封航还来求老周"))),
                    GateResult(ReviewGate.CRAFT, listOf(ReviewIssue("林月把半块玉佩攥在手心，嘴角勾起。", "写作规范：不写心理独白，神态少用套话"))),
                ),
            ),
        )
        openChapterThree()
        val body = compose.onNodeWithContentDescription("正文")
        body.performTextInputSelection(androidx.compose.ui.text.TextRange(body.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].length))
        body.performTextInput("。”")
        waitForText("6 处问题")
        capture("$prefix-problems-status")
        compose.onNodeWithText("6 处问题", substring = true).performClick()
        waitForText("全部 6")
        waitForText("「嘴角勾起」")
        capture("$prefix-problems-all")
        compose.onNodeWithText("规则 1").performClick()
        waitForText("「没抬头」1 处，不用")
        capture("$prefix-problems-rules")
        compose.onNodeWithText("AI 腔 1").performClick()
        waitForText("以后都不要")
        capture("$prefix-problems-cliche")
        compose.onNodeWithText("建议 2").performClick()
        waitForText("老周说话短")
        capture("$prefix-problems-suggest")
        compose.onNodeWithText("审稿 2").performClick()
        waitForText("上次审稿")
        waitForText("按这条让 AI 改")
        capture("$prefix-problems-review")
        // AI 条：没有“矛盾检查”，读者点评是最后一项。
        compose.onNodeWithContentDescription("收起").performClick()
        waitForGone("上次审稿")
        compose.onNodeWithContentDescription("AI 写作").performClick()
        compose.onNodeWithText("改稿").performClick()
        compose.onNodeWithText("读者点评").performScrollTo()
        check(count("矛盾检查") == 0)
        capture("$prefix-problems-ai-bar")
    }

    @Test
    fun problemsPanelAi() = problemsPanel("after")

    @Test
    fun einkProblemsPanelAi() = problemsPanel("eink-after")

    @Test
    fun inkProblemsPanelAi() = problemsPanel("ink-after")

    private companion object {
        /** 问题面板截图里写作建议的回答：一处错字、一处口吻（都在打完的最后一段里）。 */
        val PROBLEMS_SUGGEST: String = JSONObject().put("issues", JSONArray()
            .put(JSONObject().put("p", 1).put("wrong", "末必").put("right", "未必").put("reason", "错别字").put("kind", "TYPO"))
            .put(JSONObject().put("p", 1).put("wrong", "封航了，明天也末必有").put("right", "封航了。").put("reason", "老周说话短，不解释")
                .put("kind", "VOICE").put("source", "《人物档案》老周"))).toString()

        /** 认出整理请求的一句（见 WritePrompts.sync）。 */
        const val FINISH_SYNC = "作者写完了这一章，请整理它"

        /** 第 3 章的整理结果和摘要：依据都是第 3 章正文里的原句。 */
        val FINISH_ANSWER: String = JSONObject()
            .put("new", JSONArray().put(JSONObject().put("type", "规矩").put("name", "夜里封航").put("kind", "硬性设定")
                .put("text", "渡口入夜封航，船家不渡人").put("quote", "封航了，明天也未必有。")))
            .put("conflicts", JSONArray())
            .put("states", JSONArray().put(JSONObject().put("who", "林月").put("aspect", "随身").put("now", "攥着半块玉佩").put("quote", "林月把半块玉佩攥在手心。")))
            .put("events", JSONArray().put(JSONObject().put("what", "渡口封航，今晚没有船").put("quote", "封航了，明天也未必有。")))
            .put("threads_opened", JSONArray().put(JSONObject().put("what", "林月非过河不可的缘由").put("quote", "“今晚还有船吗？”她问。")))
            .put("summary", "夜里林月攥着半块玉佩来到渡口，求老周今晚渡她过河。老周说已经封航，明天也未必有船。林月必须在天亮前过河，章末仍没找到船。")
            .toString()
    }
}
