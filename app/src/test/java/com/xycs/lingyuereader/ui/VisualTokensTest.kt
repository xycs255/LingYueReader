package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 字号、圆角 token：每档的取值、档数，公共组件确实用的是 token，页面源码里不再直接写字号、圆角数字（注明了原因的特殊处除外）。
 * 还有这一批改的几个公共组件：说明折成两行加“展开”、不能点的动作行带说明、输入框的占位和标签。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualTokensTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typeScaleHasSevenStepsWithMatchingLineHeights() {
        val steps = listOf(
            TypeScale.Display to TypeScale.DisplayLine,
            TypeScale.Title to TypeScale.TitleLine,
            TypeScale.Headline to TypeScale.HeadlineLine,
            TypeScale.Body to TypeScale.BodyLine,
            TypeScale.Caption to TypeScale.CaptionLine,
            TypeScale.Footnote to TypeScale.FootnoteLine,
            TypeScale.Micro to TypeScale.MicroLine,
        )
        assertEquals(listOf(34f, 22f, 17f, 15f, 13f, 12f, 11f), steps.map { it.first.value })
        // 从大到小排、互不相同；行高至少是字号的 1.15 倍，多行说明不挤（大数字本来就紧一些）。
        assertEquals(steps.map { it.first.value }.sortedDescending(), steps.map { it.first.value })
        steps.forEach { (size, line) -> assertTrue("$size 的行高 $line", line.value >= size.value * 1.15f) }
    }

    @Test
    fun radiusHasFourStepsPlusFull() {
        assertEquals(listOf(8.dp, 12.dp, 16.dp, 20.dp), listOf(Radius.Small, Radius.Medium, Radius.Large, Radius.XLarge))
    }

    /** 行是能点的、会合并子节点：按文字找没合并的那个 Text 节点。 */
    private fun text(t: String) = compose.onNodeWithText(t, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.fontSize(): TextUnit {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().layoutInput.style.fontSize
    }

    @Test
    fun sharedComponentsUseTheTokens() {
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) {
                ProvideGroupedColors {
                    Column {
                        GroupedSection(header = "组名", footer = "说明") {
                            row { NavRow("一行", value = "当前值") {} }
                            row { ActionRow("一个动作") {} }
                        }
                        Tag("定稿", LocalGroupedColors.current.success)
                        PrimaryButton("开始阅读", onClick = {})
                        PillButton("复制", onClick = {})
                        EmptyState(LingYueIcons.Book, "空的标题", "空的说明")
                    }
                }
            }
        }
        assertEquals(TypeScale.Body, text("一行").fontSize())
        assertEquals(TypeScale.Caption, text("当前值").fontSize())
        assertEquals(TypeScale.Body, text("一个动作").fontSize())
        assertEquals(TypeScale.Caption, text("说明").fontSize())
        assertEquals(TypeScale.Footnote, text("定稿").fontSize())
        assertEquals(TypeScale.Headline, text("开始阅读").fontSize())
        assertEquals(TypeScale.Body, text("复制").fontSize())
        assertEquals(TypeScale.Headline, text("空的标题").fontSize())
        assertEquals(TypeScale.Caption, text("空的说明").fontSize())
    }

    /** 界面源码在哪：Robolectric 的工作目录可能是 app，也可能是仓库根目录。 */
    private fun uiSources(): List<File> {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val app = if (working.name == "app") working else File(working, "app")
        val dir = File(app, "src/main/java/com/xycs/lingyuereader/ui")
        assertTrue("找不到界面源码 $dir", dir.isDirectory)
        return dir.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /**
     * 直接写数字的字号、圆角：每一处的同一行或前面三行里要有一句注释说为什么特殊，
     * 不然就该改成引用 [TypeScale]、[Radius]。
     */
    @Test
    fun hardCodedSizesAreOnlyTheDocumentedSpecialCases() {
        val literal = Regex("""fontSize = \d+(\.\d+)?\.sp|lineHeight = \d+(\.\d+)?\.sp|RoundedCornerShape\([^)]*\d+\.dp|cornerRadius = [^,\n]*\d+\.dp""")
        val unexplained = mutableListOf<String>()
        var found = 0
        uiSources().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (!literal.containsMatchIn(line) || line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                found++
                val context = lines.subList(maxOf(0, i - 3), i + 1)
                if (context.none { it.contains("//") }) unexplained += "${file.name}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("这些字号、圆角直接写了数字，又没注明原因：\n" + unexplained.joinToString("\n"), unexplained.isEmpty())
        // 还剩的特殊处是少数几处（封面、迷你书页、编辑器正文、对话框……），不是又散回去了。
        assertTrue("直接写数字的字号、圆角有 $found 处", found <= 30)
    }

    @Test
    fun longFooterFoldsToTwoLinesWithExpand() {
        val long = "这是一段很长的说明。".repeat(20)
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) {
                ProvideGroupedColors {
                    Column(Modifier.width(360.dp)) {
                        GroupedSection(footer = long) { row { NavRow("一行") {} } }
                        GroupedSection(footer = "短说明") { row { NavRow("另一行") {} } }
                    }
                }
            }
        }
        // 长的那段折成两行，只有它下面有“展开”；读屏照旧拿得到全文。
        assertEquals(1, compose.onAllNodesWithText("展开", useUnmergedTree = true).fetchSemanticsNodes().size)
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        assertEquals(FOOTER_MAX_LINES, layout.first().lineCount)
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("收起").assertExists()
        layout.clear()
        compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        assertTrue(layout.first().lineCount > FOOTER_MAX_LINES)
    }

    @Test
    fun disabledActionStaysTintedAndSaysWhy() {
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) {
                ProvideGroupedColors {
                    GroupedSection { row { ActionRow("测试连接", enabled = false, disabledHint = "先填好地址") {} } }
                }
            }
        }
        compose.onNodeWithText("先填好地址").assertExists()
        compose.onNodeWithText("测试连接", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("测试连接")[0].assertIsNotEnabled()
    }

    @Test
    fun textFieldPlaceholderIsQuietAndLabelStaysAfterTyping() {
        var value by mutableStateOf("")
        compose.setContent {
            LingYueTheme(Appearance(mode = ThemeMode.LIGHT)) {
                ProvideGroupedColors {
                    GroupedTextField(value = value, onValueChange = { value = it }, label = "接口地址", singleLine = true)
                }
            }
        }
        // 空着：标签当占位，第三级灰、正文字号。
        assertEquals(TypeScale.Body, compose.onNodeWithText("接口地址", useUnmergedTree = true).fontSize())
        compose.onNode(hasSetTextAction()).performTextInput("https://example.org")
        compose.waitForIdle()
        assertEquals("https://example.org", value)
        // 填了以后标签缩成一行小字留着，认得出这一格是什么。
        assertEquals(TypeScale.Footnote, compose.onNodeWithText("接口地址", useUnmergedTree = true).fontSize())
        val text = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("https://example.org", text)
        assertTrue(TypeScale.Body > 14.sp)
    }
}
