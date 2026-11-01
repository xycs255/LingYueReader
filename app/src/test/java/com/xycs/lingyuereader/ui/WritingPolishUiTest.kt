package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.ai.WritePrompts
import com.xycs.lingyuereader.data.QuoteStyle
import com.xycs.lingyuereader.data.WorkStore
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

/**
 * 写作页逐页打磨的几处布局（真页面、真存盘，人名虚构）：黑名单列表在上、右上“+”弹对话框加一条；
 * 360dp 宽时 ✦ 只显示标题、不显示理由（411dp 照旧带理由）；排版设置的引号风格写成“中文引号 “””“直角引号 「」”。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingPolishUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""
    private var chapterId = ""

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            // AI 填的是假地址：这里只看界面，不发请求。
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                .putString("baseUrl", "http://127.0.0.1:9/v1").putString("model", "test-model").commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜", "沈默")
            store.saveText(w.id, w.chapters[0].id, "林月推开门，风雪灌进来。", count = false)
            store.updateAssist(w.id) { it.ban("说来话长") }
            workId = w.id
            chapterId = w.chapters[0].id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }
    private fun has(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun store() = WorkStore(writingDir)

    /** 黑名单：进来先看到已有的（没有输入框压在上面）；右上“+”弹对话框，已有的说法不能再加并说为什么。 */
    @Test
    fun bannedListComesFirstAndThePlusOpensADialog() {
        compose.runOnUiThread { vm.open(Screen.WorkBanned(workId)) }
        waitFor { has("说来话长") }
        assertTrue("页面上没有常驻的输入框", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("1 条").assertExists()
        compose.onNodeWithContentDescription("加进黑名单").performClick()
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onAllNodes(hasSetTextAction()).onLast()
        val confirm = { compose.onAllNodesWithText("加入黑名单").onLast() }
        confirm().assertIsNotEnabled()
        field.performTextInput("说来话长")
        waitFor { has("这个说法已经在黑名单里了") }
        confirm().assertIsNotEnabled()
        field.performTextReplacement("不由得")
        confirm().assertIsEnabled().performClick()
        waitFor { store().assist(workId).banned == listOf("说来话长", "不由得") }
        waitFor { has("2 条") }
    }

    /** 360dp 宽：✦ 只显示“✦ 名字”，理由不画出来（读屏照旧念）。 */
    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun sparkShowsOnlyItsTitleOnASmallPhone() {
        val reason = openEditorAndReadSparkReason()
        assertTrue("360dp 不画理由「$reason」", compose.onAllNodesWithText(reason).fetchSemanticsNodes().isEmpty())
    }

    /** 411dp 宽：✦ 下面一行灰色理由照旧。 */
    @Test
    fun sparkKeepsItsReasonOnARegularPhone() {
        val reason = openEditorAndReadSparkReason()
        assertTrue("411dp 照旧画理由「$reason」", compose.onAllNodesWithText(reason).fetchSemanticsNodes().isNotEmpty())
    }

    private fun openEditorAndReadSparkReason(): String {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithContentDescription("智能推荐：", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val description = compose.onAllNodesWithContentDescription("智能推荐：", substring = true).fetchSemanticsNodes().first()
            .config[SemanticsProperties.ContentDescription].single()
        val reason = description.substringAfter("，")
        assertTrue(description, reason.isNotBlank() && reason != description)
        return reason
    }

    /** 排版设置：引号风格的两格写名字加一对引号，点了换风格。 */
    @Test
    fun quoteStyleSegmentsAreNamed() {
        compose.runOnUiThread { vm.open(Screen.WorkFormatSettings(workId)) }
        waitFor { has("中文引号 “”") }
        compose.onNodeWithText("直角引号 「」").performClick()
        waitFor { store().assist(workId).format.quoteStyle == QuoteStyle.CORNER }
    }
}

/** “先挑一个走向”的 3 张选择卡：一张一张竖着排、整张能点，读屏把序号、走向和看点连起来念。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp")
class WritingDirectionCardsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachDirectionIsOneTappableCard() {
        val picked = mutableListOf<String>()
        val options = listOf(
            WritePrompts.PlanOption("林月独自撑船过河", hook = "一个人和一条冻住的河", end = "对岸亮起一盏灯"),
            WritePrompts.PlanOption("老周说出十年前的事", change = "林月知道了父亲的死因"),
            WritePrompts.PlanOption("沈默追来，杜娘调停"),
        )
        compose.setContent { LingYueTheme { ProvideGroupedColors { DirectionOptions(options) { picked += it.what } } } }
        compose.onNodeWithContentDescription("第 1 个：林月独自撑船过河；看点：一个人和一条冻住的河；章末停在：对岸亮起一盏灯").assertExists()
        compose.onNodeWithContentDescription("第 2 个：老周说出十年前的事；本章改变：林月知道了父亲的死因").performClick()
        compose.onNodeWithContentDescription("第 3 个：沈默追来，杜娘调停").performClick()
        assertEquals(listOf("老周说出十年前的事", "沈默追来，杜娘调停"), picked)
        // 三张竖着排：后一张在前一张下面，360dp 宽也不挤成一排。
        val tops = (1..3).map { i -> compose.onNodeWithContentDescription("第 $i 个：", substring = true).fetchSemanticsNode().boundsInRoot }
        assertTrue(tops[0].bottom <= tops[1].top && tops[1].bottom <= tops[2].top)
    }
}
