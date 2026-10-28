package com.xycs.lingyuereader.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.CrashLog
import com.xycs.lingyuereader.CrashLogFile
import com.xycs.lingyuereader.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 闪退记录：上次闪退了，打开时问一次要不要复制；“我的 → 闪退记录”能看、能复制。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class CrashLogUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = TestName()

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val log = File(files, "crash/${CrashLog.FILE}")
            log.delete()
            if (name.methodName != "noPromptWithoutACrash") {
                CrashLog.screen = "Editor"
                CrashLogFile(log).add(CrashLog.entry(IndexOutOfBoundsException("Index 12 out of bounds for length 3"), Thread.currentThread(), fatal = true))
                CrashLog.screen = ""
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(name).around(seed).around(compose)

    private fun waitForText(t: String) = try {
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        val dump = (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
        throw AssertionError("找不到 $t：\n$dump", e)
    }

    /** “我的”页滑过去、停稳了再点“闪退记录”。 */
    private fun openCrashLog() {
        compose.onNodeWithText("我的").performClick()
        // 先等“我的”页滑到位（不然往下滚的时候会把整排页面也拖动）。
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            runCatching { compose.onNodeWithText("主题设置").assertIsDisplayed() }.isSuccess
        }
        // 这一行在“关于”里，可能在屏幕下面：直接点它（不滚动，免得把整排页面也拖动）。
        compose.onNodeWithText("闪退记录", substring = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun clipboard(): String? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun afterACrashAskOnceAndCopyTheReport() {
        // 闪退记录在 Application 里装好了（清单里登记的是 LingYueApp）。
        assertTrue(context.javaClass.name, context is com.xycs.lingyuereader.LingYueApp)
        assertEquals(1, CrashLog.entries().size)
        waitForText("上次灵阅意外退出了")
        compose.onNodeWithText("复制").performClick()
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); CrashLog.unseen() == null }
        val copied = clipboard().orEmpty()
        assertTrue(copied, copied.startsWith("灵阅闪退记录"))
        assertTrue(copied, copied.contains("界面：Editor"))
        assertTrue(copied, copied.contains("java.lang.IndexOutOfBoundsException: Index 12 out of bounds for length 3"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("上次灵阅意外退出了").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun noThanksAlsoCountsAsAsked() {
        waitForText("上次灵阅意外退出了")
        compose.onNodeWithText("不用了").performClick()
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); CrashLog.unseen() == null }
        assertNull(clipboard())
        // 记录还在，“我的 → 闪退记录”里看得到。
        assertEquals(1, CrashLog.entries().size)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("上次灵阅意外退出了").fetchSemanticsNodes().isEmpty() }
        openCrashLog()
        waitForText("闪退 · ")
        compose.onNodeWithText("IndexOutOfBoundsException：Index 12 out of bounds for length 3", substring = true).performClick()
        waitForText("灵阅闪退记录")
        compose.onNodeWithText("复制").performClick()
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); clipboard()?.startsWith("灵阅闪退记录") == true }
    }

    @Test
    fun noPromptWithoutACrash() {
        waitForText("书架")
        ShadowLooper.idleMainLooper()
        assertFalse(compose.onAllNodesWithText("上次灵阅意外退出了").fetchSemanticsNodes().isNotEmpty())
        openCrashLog()
        waitForText("没有闪退记录")
    }
}
