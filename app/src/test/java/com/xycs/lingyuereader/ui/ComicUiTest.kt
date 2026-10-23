package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Library
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val seed = object : ExternalResource() {
        override fun before() {
            File(context.filesDir, "books").deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putString("pageMode", "SCROLL").commit()
            val fb2 = File("src/test/resources/formats/book.fb2").readText()
            val png = java.util.Base64.getMimeDecoder().decode(Regex("""<binary[^>]*>(.*?)</binary>""", RegexOption.DOT_MATCHES_ALL).find(fb2)!!.groupValues[1].trim())
            val file = File(context.cacheDir, "comic.cbz")
            ZipOutputStream(file.outputStream()).use { z ->
                listOf("01.png", "02.png", "03.png").forEach { z.putNextEntry(ZipEntry(it)); z.write(png); z.closeEntry() }
            }
            val library = Library(context)
            library.add(library.describe(listOf(Uri.fromFile(file))), "测试漫画")
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test
    fun comicPagesOpenFullScreenOnLongPressAndListeningIsOff() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("测试漫画").fetchSemanticsNodes().isNotEmpty() }
        // 有封面图时书名在封面下面，点封面打开。
        compose.onAllNodesWithText("测试漫画")[0].performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, -60f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("全部", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // 漫画没有文字：菜单里的“听书”不可用。
        compose.onRoot().performTouchInput { click(center) }
        compose.onNode(hasText("听书") and hasClickAction()).assertIsNotEnabled()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        // 长按一页打开大图，单击关闭。
        compose.onRoot().performTouchInput { longClick(center.copy(y = height * 0.3f)) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("大图", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("大图", substring = true).performTouchInput { click(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("大图", substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }
}
