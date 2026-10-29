package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
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

/**
 * Issue #17 收尾三页的实测截图：设定包的分类格和“AI 每次带多少”（浅色、深色、墨水屏、放不下标红），
 * 细纲卡的彩色小卡片（浅色、深色、AI 拟的待确认），写作 AI 设置的首屏和“高级设置”，以及 360dp 小屏上的三页。
 * 只出图供设计复核（app/build/outputs/ui-check/pack-*.png），不做像素断言；每张前都断言关键文字，确认真的到了那个状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingPackGridSnapshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var workId = ""
    private var chapterId = ""

    private val seed = object : ExternalResource() {
        private var ink = false
        private var eInk = false
        private var overflow = false
        private var drafted = false

        override fun apply(base: Statement, description: Description): Statement {
            val name = description.methodName.orEmpty()
            ink = name.startsWith("ink")
            eInk = name.startsWith("eink")
            overflow = "Overflow" in name
            drafted = "Drafted" in name
            return super.apply(base, description)
        }

        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            File(files, "writing").deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear().commit()
            seedWork(WorkStore(File(files, "writing")), overflow, drafted)
            Prefs(context).saveAppearance(Appearance(mode = if (ink) ThemeMode.DARK else ThemeMode.LIGHT, blur = false, liquidGlass = false))
            if (eInk) context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("eInk", true).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    /**
     * 作品《长夜》：两章、第 2 章的细纲卡，设定包七类文档；[drafted] 时细纲卡是一键写本章时 AI 拟的、拆的（待确认），
     * [overflow] 时再加两份大设定、知识库上限调到 3 万字。
     */
    private fun seedWork(store: WorkStore, overflow: Boolean, drafted: Boolean) {
        val w = store.create("长夜", "沈青禾")
        workId = w.id
        val v = w.volumes[0].id
        store.renameChapter(w.id, w.chapters[0].id, "第1章 雪夜")
        chapterId = store.addChapter(w.id, v, "第2章 渡口")!!.second.id
        store.saveText(w.id, w.chapters[0].id, "林月推开门，风雪灌进来。")
        store.saveCard(w.id, w.chapters[0].id, ChapterCard(hook = "对岸亮起一盏灯", boundary = "不揭开老周的过去", summary = "林月冒雪到渡口，老周不肯开船。"))
        store.saveCard(w.id, chapterId, ChapterCard(
            facts = "林月求老周夜里渡河，老周拒绝；沈默出现，替她付了船钱", pov = "林月", countdown = "天亮前必须过河",
            hook = "船行到河心，沈默问起那半块玉佩", beats = "林月到渡口，船都拴着\n老周拒绝夜里开船\n沈默替她付了船钱", beatsDone = listOf(1),
            aiOutline = drafted, aiBeats = drafted,
        ))
        store.saveText(w.id, chapterId, "渡口的灯还亮着，老周蹲在船头抽烟。林月把半块玉佩攥在手心。")
        store.addPackDoc(w.id, PackKind.INSTRUCTION, "工作说明", "先读细纲卡，再写正文；资料冲突以总纲为准。")
        store.addPackDoc(w.id, PackKind.OUTLINE, "总纲", "林月追查父亲沉船的真相，最后在天亮前渡过对岸。".repeat(30))
        store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "## 林月\n想要真相，怕连累旁人。\n## 沈默\n话少，随身带一把旧伞。\n## 老周\n守着渡口的旧船。")
        store.addPackDoc(w.id, PackKind.CHARACTER, "配角", "## 杜娘\n城南茶馆老板娘。", always = false)
        store.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋 | 收 |\n| --- | --- | --- |\n| 半块玉佩 | 1 | 12 |")
        store.addPackDoc(w.id, PackKind.STYLE, "写作规范", "对白要短；不写心理独白。")
        store.addPackDoc(w.id, PackKind.RULES, "检查规则", "禁用：不由得")
        store.addPackDoc(w.id, PackKind.REFERENCE, "渡口地图说明", "渡口在城南，对岸是旧盐仓。")
        if (overflow) {
            val big = buildString { repeat(400) { i -> append("## 北岸第${i + 1}处\n").append("林月记得渡口北岸第${i + 1}处有一间茶棚，杜娘每天卯时开门。".repeat(4)).append("\n\n") } }
            store.addPackDoc(w.id, PackKind.CANON, "北岸地理", big)
            store.addPackDoc(w.id, PackKind.TECHNIQUE, "学来的写法", big.replace("北岸", "南岸"))
            store.updateAssist(w.id) { it.copy(knowledgeLimit = 30_000) }
        }
    }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForDescription(text: String) =
        waitFor { compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/pack-$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openPack() {
        compose.runOnUiThread { vm.open(Screen.WorkPack(workId)) }
        waitForText("文档分类")
        // 预算条整张合成一句给读屏念，按读屏文字找。
        waitForDescription("写正文每次带")
    }

    private fun pack(prefix: String) {
        openPack()
        capture("$prefix-pack")
    }

    @Test fun lightPack() = pack("light")

    @Test fun inkPack() = pack("ink")

    @Test fun einkPack() = pack("eink")

    @Test
    fun packOverflow() {
        openPack()
        waitForDescription("份放不下或被截掉")
        capture("overflow-pack")
        compose.onNodeWithContentDescription("写正文每次带", substring = true).performClick()
        waitForText("放不下、被截掉的")
        capture("overflow-pack-detail")
        waitForText("整份没带 · 北岸地理")
        compose.onNodeWithText("好").performClick()
        // 列表里那几份挂着红色的“放不下”。
        compose.onNodeWithText("北岸地理").performScrollTo()
        capture("overflow-pack-list")
    }

    @Test
    fun packFiltered() {
        openPack()
        compose.onNodeWithContentDescription("人物档案，2 份", substring = true).performClick()
        waitForText("人物档案 · 2 份")
        capture("light-pack-filtered")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp")
    fun narrowPack() = pack("360")

    private fun openPlan() {
        compose.runOnUiThread { vm.open(Screen.ChapterCard(workId, chapterId)) }
        waitForText("上一章「第1章 雪夜」留下的")
        waitForText("沈默出现，替她付了船钱")
    }

    private fun plan(prefix: String) {
        openPlan()
        waitForText("3 拍 · 已写 1")
        capture("$prefix-plan")
        // 点一张就地展开：视角人物下面露出文本框。
        compose.onNodeWithText("视角人物", substring = true).performClick()
        // 收着时只有“本章摘要”一个文本框，展开后多一个。
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
        capture("$prefix-plan-edit")
        // 节拍卡：各拍一直列着、已写的勾着；下面本章摘要照旧露着。
        compose.onNodeWithText("AI 概括正文").performScrollTo()
        capture("$prefix-plan-beats")
    }

    @Test fun lightPlan() = plan("light")

    @Test fun inkPlan() = plan("ink")

    @Test
    fun planDrafted() {
        openPlan()
        waitForText("确认这份细纲")
        waitForText("AI 拆的·待确认")
        capture("drafted-plan")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp")
    fun narrowPlanDrafted() {
        openPlan()
        waitForText("确认这份细纲")
        capture("360-plan")
        compose.onNodeWithText("推进的线").performScrollTo()
        capture("360-plan-cards")
    }

    private fun aiSettings(prefix: String) {
        compose.runOnUiThread { vm.open(Screen.WorkAi(workId)) }
        waitForText("这部书的口味")
        waitForText("缺细纲、节拍时 AI 先补上")
        capture("$prefix-ai")
        compose.onNodeWithText("高级设置").performScrollTo()
        capture("$prefix-ai-bottom")
        compose.onNodeWithText("高级设置").performClick()
        waitForText("知识库上限")
        capture("$prefix-ai-advanced")
        compose.onNodeWithText("最近一次发给 AI 的内容").performScrollTo()
        capture("$prefix-ai-advanced-bottom")
    }

    @Test fun lightAiSettings() = aiSettings("light")

    @Test fun inkAiSettings() = aiSettings("ink")

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp")
    fun narrowAiSettings() = aiSettings("360")
}
