package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.Library
import com.xycs.lingyuereader.data.ParserTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FootnoteUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val seed = object : ExternalResource() {
        override fun before() {
            File(context.filesDir, "books").deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putString("pageMode", "SCROLL").commit()
            val file = File(context.cacheDir, "有注释的书.epub").apply { writeBytes(ParserTest().footnoteEpub()) }
            val library = Library(context)
            library.add(library.describe(listOf(Uri.fromFile(file))), null)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun tappingAFootnoteMarkerShowsTheNote() {
        waitForText("有注释的书")
        compose.onAllNodesWithText("有注释的书")[0].performClick()
        waitForText("他读了《论语》")
        val node = compose.onAllNodesWithText("他读了《论语》", substring = true)[0]
        // 找到角标“1”在排版里的位置，点它。
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.first()
        val text = layout.layoutInput.text.text
        val at = text.indexOf("》") + 1
        val box = layout.getBoundingBox(at)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }
        waitForText("儒家经典，记孔子及其弟子言行。")
    }
}
