package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.lifecycle.ViewModelProvider
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.TextRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BookRulesUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            val dir = File(files, "books/b1").apply { mkdirs() }
            File(dir, "s0.txt").writeText("【楔子】\n那年冬天。本章未完\n【一 下山】\n他下了山。本章未完\n【二 进城】\n他进了城。\n")
            val entry = JSONObject()
                .put("id", "b1").put("title", "测试书").put("author", "").put("format", "TXT")
                .put("addedAt", 1L).put("openedAt", 0L).put("chapterCount", 1).put("charCount", 20)
                .put("sources", JSONArray().put(JSONObject().put("name", "测试书.txt").put("uri", "").put("file", "s0.txt")))
            File(files, "books/index.json").writeText(JSONArray().put(entry).toString())
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun pickATocPresetAddACleanupRuleAndSave() {
        waitForText("测试书")
        compose.onAllNodesWithText("测试书")[0].performTouchInput { longClick() }
        waitForText("详情")
        compose.onNodeWithText("详情").performClick()
        waitForText("书籍详情")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("净化与分章"))
        compose.onNodeWithText("净化与分章").performClick()
        waitForText("按这个规则只分出 1 章")
        compose.onNodeWithText("【章名】").performClick()
        waitForText("将分为 3 章")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("查找"))
        compose.onNodeWithText("查找").performTextInput("本章未完")
        waitForText("将匹配 2 处")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("添加"))
        compose.onNodeWithText("添加").performClick()
        waitForText("匹配 2 处")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存并重新分章"))
        compose.onNodeWithText("保存并重新分章").performClick()
        compose.waitUntil(15_000) {
            JSONArray(File(context.filesDir, "books/index.json").readText()).getJSONObject(0).getInt("chapterCount") == 3
        }
        val rules = JSONObject(File(context.filesDir, "books/b1/rules.json").readText())
        assertEquals("本章未完", rules.getJSONArray("replace").getJSONObject(0).getString("pattern"))
    }

    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    /** 直接打开规则页（详情页的入口上面的用例已经走过）。 */
    private fun openRules() {
        waitForText("测试书")
        compose.runOnUiThread { vm.open(Screen.BookRules("b1")) }
        waitForText("按这个规则只分出 1 章")
    }

    @Test
    fun slowRegexCannotBeAddedOrSavedAndSavingItAnywayEndsBusy() {
        // 一行几十个英文字母：带反向引用的嵌套重复会在这一行上回溯几小时。
        File(context.filesDir, "books/b1/s0.txt").appendText("shenmolaozhouduniangguchangfenglinyuexyz!\n")
        val slow = """^((\w)+\2?)+$"""
        val slowToc = """^((\w)+\2?)+\s*$"""
        val saved = TextRules.timeoutMillis
        TextRules.timeoutMillis = 500
        try {
            openRules()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("按正则匹配"))
            compose.onNodeWithText("按正则匹配").performClick()
            compose.onNodeWithText("查找").performTextInput(slow)
            // 写法像会灾难性回溯：先不统计，说明原因；点“仍然试一下”才在这本书上跑。
            waitForText("有套在一起的重复")
            waitForText("先没统计")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("添加"))
            compose.onNodeWithText("添加").assertIsNotEnabled()
            compose.onNodeWithText("仍然试一下").performClick()
            waitForText("正则太慢，在这本书上跑不完")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("添加"))
            compose.onNodeWithText("添加").assertIsNotEnabled()

            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("分章正则"))
            // 换一种写法（上面点过“仍然试一下”的那个不再拦）。
            compose.onNodeWithText("分章正则").performTextInput(slowToc)
            waitForText("先没试分章")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存并重新分章"))
            compose.onNodeWithText("保存并重新分章").assertIsNotEnabled()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("仍然试一下"))
            compose.onNodeWithText("仍然试一下").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("正则太慢，在这本书上跑不完，请改写分章或净化规则")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存并重新分章"))
            compose.onNodeWithText("保存并重新分章").assertIsNotEnabled()

            // 绕过界面硬存：保存失败，“忙”照样结束，书的规则没变。
            val entry = vm.shelf.value.single()
            compose.runOnUiThread { vm.saveRules(entry, TextRules(toc = slow)) }
            compose.waitUntil(15_000) { !vm.busy.value && !File(context.filesDir, "books").listFiles().orEmpty().any { it.name.startsWith(".rules-") } }
            compose.waitForIdle()
            assertFalse(vm.busy.value)
            assertFalse(File(context.filesDir, "books/b1/rules.json").exists())
        } finally {
            TextRules.timeoutMillis = saved
        }
    }

    @Test
    fun savingRulesDoesNotPopAScreenOpenedMeanwhile() {
        waitForText("测试书")
        val entry = vm.shelf.value.single()
        // 规则页之上又打开了详情页：整理完不能把详情页弹掉。
        compose.runOnUiThread {
            vm.open(Screen.BookRules("b1"))
            vm.open(Screen.Detail("b1"))
            vm.saveRules(entry, TextRules(toc = TextRules.TOC_PRESETS.first { it.label == "【章名】" }.pattern))
        }
        compose.waitUntil(15_000) {
            JSONArray(File(context.filesDir, "books/index.json").readText()).getJSONObject(0).getInt("chapterCount") == 3 && !vm.busy.value
        }
        compose.waitForIdle()
        assertEquals(Screen.Detail("b1"), vm.stack.value.last())
        assertEquals(Screen.BookRules("b1"), vm.stack.value[vm.stack.value.size - 2])
    }

    @Test
    fun unsavedRulesSurviveRotation() {
        openRules()
        compose.onNodeWithText("【章名】").performClick()
        waitForText("将分为 3 章")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("查找"))
        compose.onNodeWithText("查找").performTextInput("本章未完")
        waitForText("将匹配 2 处")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("添加"))
        compose.onNodeWithText("添加").performClick()
        waitForText("匹配 2 处")
        // 还没点“添加”的那条也要留着。
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("查找"))
        compose.onNodeWithText("查找").performTextInput("他下了山")

        compose.activityRule.scenario.recreate()

        waitForText("将分为 3 章")
        waitForText("→ 删除 · 匹配 2 处")
        waitForText("将匹配 1 处")
        waitForText("他下了山")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存并重新分章"))
        compose.onNodeWithText("保存并重新分章").performClick()
        compose.waitUntil(15_000) {
            JSONArray(File(context.filesDir, "books/index.json").readText()).getJSONObject(0).getInt("chapterCount") == 3
        }
        val rules = JSONObject(File(context.filesDir, "books/b1/rules.json").readText())
        assertEquals(TextRules.TOC_PRESETS.first { it.label == "【章名】" }.pattern, rules.getString("toc"))
        assertEquals("本章未完", rules.getJSONArray("replace").getJSONObject(0).getString("pattern"))
    }
}
