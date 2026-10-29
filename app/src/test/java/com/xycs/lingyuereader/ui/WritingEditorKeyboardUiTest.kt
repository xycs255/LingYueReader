package com.xycs.lingyuereader.ui

import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ThemeMode
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 编辑器和键盘：键盘升起、收起的每一帧不把整块编辑器重组一遍；矮屏门槛有回差；一次性提示不挡正文、打字就收；
 * 系统字号放大时底栏不折行、✦ 不被挤没。Robolectric 没有键盘，这里直接往界面上发输入法的 inset。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingEditorKeyboardUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private val name = TestName()
    private var workId = ""
    private var chapterId = ""
    private val text = "林月看着窗外的雨。\n沈默推门进来，抖了抖伞上的水。\n顾长风坐在柜台后面算账。"

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val method = name.methodName
            // 字号放大那条要 ✦ 旁边有“+N”：写作 AI 要是设好的（不会真的发请求）。
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().clear().apply {
                if (method.contains("Font")) putBoolean("enabled", true).putString("preset", "local").putString("protocol", "OPENAI")
                    .putString("baseUrl", "http://127.0.0.1:9/v1").putString("model", "test-model")
            }.commit()
            // 只有专门看提示的几条要那条一次性提示。
            context.getSharedPreferences("writing_editor", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("tabs_tip", !method.contains("Tip")).commit()
            Prefs(context).saveAppearance(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false))
            when {
                method.contains("LargeFont") -> RuntimeEnvironment.setFontScale(1.15f)
                method.contains("HugeFont") -> RuntimeEnvironment.setFontScale(1.3f)
            }
            val store = WorkStore(writingDir)
            val w = store.create("长夜", "沈默")
            workId = w.id
            chapterId = w.chapters[0].id
            store.saveText(w.id, chapterId, text)
        }

        override fun after() {
            RuntimeEnvironment.setFontScale(1f)
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
    private fun hasDescription(t: String) = compose.onAllNodesWithContentDescription(t).fetchSemanticsNodes().isNotEmpty()

    private fun openEditor() {
        compose.runOnUiThread { vm.open(Screen.Editor(workId, chapterId)) }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithContentDescription("智能推荐：", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 屏幕的高（dp）。 */
    private fun screenHeight(): Float = with(compose.density) { compose.onRoot().fetchSemanticsNode().size.height.toDp().value }

    /** 假装输入法升到 [imeDp] 高：和真机一样发给界面一份新的 inset。 */
    private fun keyboard(imeDp: Float) {
        val px = with(compose.density) { imeDp.dp.roundToPx() }
        compose.runOnUiThread {
            val root = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), px > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        compose.waitForIdle()
    }

    /** 让键盘上面剩下 [left] dp。 */
    private fun leave(left: Float) = keyboard(screenHeight() - left)

    private fun changes(): Int = compose.runOnIdle { EditorTrace.bodyCompositions }

    /**
     * 键盘动画逐帧发 inset：编辑器只跟着换布局，不该每一帧都重组（正文框、标记、面板、底栏整块重来）。
     * 20 帧里重组的次数应当是个位数（只有越过矮屏门槛那一下才要换）。
     */
    @Test
    fun keyboardFramesDoNotRecomposeTheEditor() {
        openEditor()
        keyboard(0f)
        compose.waitForIdle()
        val before = changes()
        // 都在矮屏门槛以上：从 100dp 升到 290dp，一帧 10dp。
        for (i in 0 until 20) keyboard(100f + i * 10)
        val recomposed = changes() - before
        assertTrue("键盘升起的 20 帧里重组了 $recomposed 次", recomposed <= 3)
    }

    /** 矮屏门槛有回差：低于 460dp 才进矮屏、高于 500dp 才退出，剩下的高在 480 上下晃时不来回跳。 */
    @Test
    fun theCompactLayoutHasHysteresis() {
        openEditor()
        compose.onNodeWithContentDescription("AI 写作").performClick()
        waitFor { has("灵感") }
        fun compact() = hasDescription("收起“写”")
        val seen = listOf(470f, 450f, 490f, 470f, 510f).map { left ->
            leave(left)
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            compact()
        }
        assertEquals(listOf(false, true, true, true, false), seen)
    }

    /** 一次性提示在正文框外面（不盖住最后两行），打字就收起；真显示出来了才记成提示过。 */
    @Test
    fun theTipStaysOutOfTheTextAndGoesAwayWhenTyping() {
        openEditor()
        waitFor { has("菜单挪到底部了") }
        val tip = compose.onNodeWithText("菜单挪到底部了", substring = true).getUnclippedBoundsInRoot()
        val body = compose.onNodeWithContentDescription("正文").getUnclippedBoundsInRoot()
        assertTrue("提示（${tip.top}）盖在正文框（到 ${body.bottom}）上", tip.top >= body.bottom)
        waitFor { !vm.editorTabsTipPending() }
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithContentDescription("正文").performTextInput("她")
        waitFor { !has("菜单挪到底部了") }
    }

    /** 系统字号调大（1.15 倍），360dp 宽的矮屏：展开的“写”那排五格仍在一行，不折成两行。 */
    @Test
    @Config(qualifiers = "w360dp-h320dp")
    fun compactRowStaysOnOneLineWithLargeFont() {
        openEditor()
        compose.onNodeWithContentDescription("AI 写作").performClick()
        waitFor { hasDescription("收起“写”") }
        val tops = editorTabEntries(EditorToolGroup.WRITE, locked = false, lastChapter = true).map { e ->
            waitFor { hasDescription(e.label) }
            compose.onNodeWithContentDescription(e.label).getUnclippedBoundsInRoot().top
        }
        assertEquals("五格应在同一行：$tops", 1, tops.distinct().size)
    }

    /** 字号 1.3 倍、360dp 宽，选中文字（✦ 右边有“+2”）：四个标签不把 ✦ 挤没，✦ 至少还有 88dp 宽，看得见推荐的是什么。 */
    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun sparkKeepsRoomWithHugeFont() {
        openEditor()
        compose.onNodeWithContentDescription("正文").performTextInputSelection(TextRange(0, 4))
        waitFor { compose.onAllNodesWithContentDescription("项推荐", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val spark = compose.onNodeWithContentDescription("智能推荐：", substring = true).getUnclippedBoundsInRoot()
        assertTrue("✦ 只剩 ${spark.right - spark.left} 宽", spark.right - spark.left >= 88.dp)
    }
}
