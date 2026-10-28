package com.xycs.lingyuereader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.SyncMark
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.chapterNo
import org.junit.Assert.assertEquals
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
 * 作品页图形化的实测：在真的作品页上拖动规划树调顺序、撤销，长按不挪弹菜单，上下箭头备用模式；
 * 以及作品页（浅色、深色、墨水屏、360dp 小屏）和拖动中的一帧截图，图存到 app/build/outputs/ui-check/tree/，只供设计复核。
 * 方法名以 “shot” 开头的种一部 3 卷 60 章的作品；“ink” 开头用深色，“eink” 开头打开墨水屏模式。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingTreeDragUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var ids = listOf<String>()

    private val seed = object : ExternalResource() {
        private var method = ""

        override fun apply(base: Statement, description: Description): Statement {
            method = description.methodName.orEmpty()
            return super.apply(base, description)
        }

        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val store = WorkStore(writingDir)
            if (method.lowercase().contains("shot")) seedBig(store) else seedSmall(store)
            val dark = method.startsWith("ink")
            Prefs(context).saveAppearance(Appearance(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, blur = false, liquidGlass = false))
            if (method.startsWith("eink")) context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("eInk", true).commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    /** 一卷五章，章名不写第几章（章号按目录顺序算）；账本里有按章号记的伏笔。 */
    private fun seedSmall(store: WorkStore) {
        val w = store.create("长夜", "沈默")
        workId = w.id
        val v = w.volumes[0].id
        store.renameChapter(w.id, w.chapters[0].id, "雪夜")
        ids = listOf(w.chapters[0].id) + listOf("渡口", "夜渡", "客栈", "旧账").map { store.addChapter(w.id, v, it)!!.second.id }
        store.saveText(w.id, ids[0], "林月推开门，风雪灌进来。")
        store.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "半块玉佩", from = 1, payoff = 3))), "测试")
    }

    /** 3 卷 60 章：第一卷多半定稿，第二卷修订、草稿混着，第三卷多是细纲；几章有细纲卡和节拍，一章是 AI 拟的待确认。 */
    private fun seedBig(store: WorkStore) {
        val w = store.create("长夜", "沈默")
        workId = w.id
        store.renameVolume(w.id, w.volumes[0].id, "第一卷 渡口")
        val volumes = listOf(w.volumes[0].id) +
            listOf("第二卷 夜航", "第三卷 对岸").map { title -> store.addVolume(w.id, title)!!.volumes.last().id }
        val names = listOf(
            "雪夜", "渡口", "夜渡", "客栈", "旧账", "沉船", "玉佩", "茶馆", "约定", "离岸",
            "暗流", "城南", "偷听", "对质", "卧底", "火光", "残卷", "盐帮", "旧人", "归途",
            "风起", "截杀", "石桥", "夜谈", "船票", "破绽", "围城", "对岸", "灯灭", "天亮",
        )
        val all = mutableListOf<String>()
        for (n in 1..60) {
            val title = "第${n}章 ${names[(n - 1) % names.size]}"
            val volume = volumes[(n - 1) / 20]
            val id = if (n == 1) w.chapters[0].id.also { store.renameChapter(w.id, it, title) } else store.addChapter(w.id, volume, title)!!.second.id
            all += id
        }
        ids = all
        val sentence = "林月把半块玉佩攥在手心，老周没有抬头。"
        val texts = mutableMapOf<String, String>()
        all.forEachIndexed { i, id ->
            val n = i + 1
            val words = when {
                n <= 20 -> 3200 + (n * 97) % 1500
                n <= 40 -> if (n % 4 == 0) 0 else 1500 + (n * 131) % 4200
                else -> if (n % 6 == 0) 2100 else 0
            }
            if (words > 0) {
                val text = sentence.repeat(words / sentence.length + 1).take(words)
                texts[id] = text
                store.saveText(w.id, id, text)
            }
            val status = when {
                n <= 17 -> ChapterStatus.FINAL
                n <= 20 -> ChapterStatus.REVISION
                n <= 40 -> if (words == 0) ChapterStatus.OUTLINE else if (n % 3 == 0) ChapterStatus.REVISION else ChapterStatus.DRAFT
                else -> if (words > 0) ChapterStatus.DRAFT else ChapterStatus.OUTLINE
            }
            if (status != ChapterStatus.OUTLINE && status != ChapterStatus.DRAFT) store.setStatus(w.id, id, status)
        }
        listOf(20, 21, 22, 23).forEach { n ->
            store.saveCard(w.id, all[n - 1], ChapterCard(facts = "林月到渡口，得知封航", pov = "林月", beats = "林月赶到渡口\n老周拒绝开船\n沈默替她付了船钱"))
        }
        store.saveCard(w.id, all[23], ChapterCard(facts = "沈默说出玉佩的来历", hook = "对岸亮起一盏灯", aiOutline = true))
        store.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋 | 收 |\n| 半块玉佩 | 1 | 24 |")
        store.addPackDoc(w.id, PackKind.RULES, "检查规则", "字数：3000-5000")
        store.applyLedger(w.id, listOf(
            LedgerEntry(kind = LedgerKind.PROMISE, text = "半块玉佩", from = 21, payoff = 24),
            LedgerEntry(kind = LedgerKind.PROMISE, text = "对岸的灯", from = 21, payoff = 30),
            LedgerEntry(kind = LedgerKind.PROMISE, text = "老周的旧伤", from = 22, payoff = 24),
        ).map { LedgerOp.Add(it) }, "截图示例")
        // 大多数有正文的章整理过；留两三章没整理，色块右上角有小红点。
        val unsynced = setOf(all[18], all[25], all[33])
        store.updateAssist(w.id) { a -> a.copy(synced = texts.filterKeys { it !in unsynced }.mapValues { SyncMark(ChapterSyncs.hash(it.value), 1L) }) }
        store.setGoal(3000)
    }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun chapterRow(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

    private fun titles(): List<String> = WorkStore(writingDir).get(workId)!!.chapters.map { it.title }

    private fun openWork() {
        waitFor { compose.onAllNodesWithText("写作").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("写作").performClick()
        waitForText("长夜")
        compose.onNodeWithText("长夜").performClick()
        waitForText("作品进度")
    }

    /** 滚到规划树里 [title] 那一章，让它的上边停在离屏幕顶 [at] dp 处（露在标题栏下面、离底边也远）。 */
    private fun scrollTreeTo(title: String, at: Int = 300) =
        scrollListTo(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), at)

    private fun scrollListTo(target: SemanticsMatcher, at: Int = 300) {
        compose.waitForIdle()
        val id = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("作品进度"))).fetchSemanticsNode().id
        val list = compose.onNode(SemanticsMatcher("作品页列表") { it.id == id })
        list.performScrollToNode(target)
        compose.waitForIdle()
        val top = compose.onNode(target).getUnclippedBoundsInRoot().top
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, with(compose.density) { (top - at.dp).toPx() }) }
        compose.waitForIdle()
    }

    /** 长按 [from] 那一章，拖到 [to] 那一章的下半（不松手）。 */
    private fun dragOnto(from: String, to: String) {
        val src = chapterRow(from).getUnclippedBoundsInRoot()
        val dst = chapterRow(to).getUnclippedBoundsInRoot()
        chapterRow(from).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            val target = (dst.bottom - 8.dp - src.top).toPx()
            val steps = 12
            repeat(steps) { i ->
                moveTo(Offset(centerX, centerY + (target - centerY) * (i + 1) / steps))
                advanceEventTime(24)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun dragChangesTheOrderAndUndoPutsItBack() {
        openWork()
        scrollTreeTo("渡口")
        dragOnto("雪夜", "夜渡")
        // 拖着时：原位变淡，一张浮起的卡片跟手，落点处有指示线。
        waitForText("松手移到这里")
        chapterRow("雪夜").performTouchInput { up() }
        waitForText("已把《雪夜》移到第 3 章")
        waitFor { titles() == listOf("渡口", "夜渡", "雪夜", "客栈", "旧账") }
        // 账本里按章号记的伏笔（第 1 章埋、第 3 章收）仍指向原来那两章：章号固定进了细纲卡。
        val store = WorkStore(writingDir)
        assertEquals(1, store.chapterNo(store.get(workId)!!, ids[0]))
        assertEquals(3, store.chapterNo(store.get(workId)!!, ids[2]))
        waitForText("章固定了细纲章号")

        compose.onNodeWithText("撤销").performClick()
        waitFor { titles() == listOf("雪夜", "渡口", "夜渡", "客栈", "旧账") }
        waitForText("已撤销移动")
        assertEquals("", store.card(workId, ids[0]).plan)
    }

    @Test
    fun longPressWithoutMovingStillOpensTheMenuAndDraggingBackChangesNothing() {
        openWork()
        scrollTreeTo("渡口")
        chapterRow("渡口").performTouchInput { longClick() }
        waitForText("改名")
        compose.onNodeWithText("状态：细纲").assertExists()
        compose.onNodeWithText("改名").performClick()
        waitForText("章名")
        compose.onNodeWithText("取消").performClick()

        // 长按后只挪了一点点（不到拖动的距离）就松手：还是菜单，顺序不变。
        chapterRow("夜渡").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 4.dp.toPx()))
            up()
        }
        waitForText("改名")
        assertEquals(listOf("雪夜", "渡口", "夜渡", "客栈", "旧账"), titles())

        compose.onNodeWithText("改名").performClick()
        waitForText("章名")
        compose.onNodeWithText("取消").performClick()

        // 拖出去再拖回原位松手：什么都不改，也没有提示。
        val before = WorkStore(writingDir).get(workId)!!.updatedAt
        chapterRow("客栈").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 60.dp.toPx()))
            advanceEventTime(24)
            moveBy(Offset(0f, -60.dp.toPx()))
            advanceEventTime(24)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("雪夜", "渡口", "夜渡", "客栈", "旧账"), titles())
        assertEquals(before, WorkStore(writingDir).get(workId)!!.updatedAt)
        assertEquals(0, compose.onAllNodesWithText("已把", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun arrowsStillWorkAsTheFallback() {
        openWork()
        scrollListTo(hasText("调整章节顺序"))
        compose.onNodeWithText("调整章节顺序").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { compose.onAllNodesWithContentDescription("下移雪夜").fetchSemanticsNodes().isNotEmpty() }
        scrollTreeTo("雪夜")
        compose.onNodeWithContentDescription("下移雪夜").performClick()
        waitFor { titles() == listOf("渡口", "雪夜", "夜渡", "客栈", "旧账") }
        scrollTreeTo("雪夜")
        compose.onNodeWithContentDescription("上移雪夜").performClick()
        waitFor { titles() == listOf("雪夜", "渡口", "夜渡", "客栈", "旧账") }
        compose.onNodeWithText("完成调整").performClick()
        waitForText("调整章节顺序")
    }

    // ---------- 截图 ----------

    private fun capture(name: String) {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/tree/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shootWorkPage(prefix: String) {
        openWork()
        waitForText("条伏笔没收")
        waitForText("定稿 17 / 共 60 章")
        waitForText("3 章待整理")
        capture("$prefix-work")
        scrollListTo(hasText("20 章 · 定稿 0", substring = true), at = 220)
        waitFor { compose.onAllNodesWithContentDescription("埋下", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-tree")
    }

    @Test
    fun shotWorkPageLight() = shootWorkPage("light")

    @Test
    fun inkShotWorkPageDark() = shootWorkPage("dark")

    @Test
    fun einkShotWorkPage() = shootWorkPage("eink")

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun smallShotWorkPage() = shootWorkPage("small360")

    @Test
    fun shotDragFrame() {
        openWork()
        scrollListTo(hasText("20 章 · 定稿 0", substring = true), at = 220)
        dragOnto("第21章 风起", "第23章 石桥")
        waitForText("松手移到这里")
        capture("drag-frame")
        chapterRow("第21章 风起").performTouchInput { up() }
        waitForText("已把《第21章 风起》移到第 23 章")
        capture("drag-dropped")
    }
}
