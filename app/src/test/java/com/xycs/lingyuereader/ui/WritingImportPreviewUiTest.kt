package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 导入前的预览：同一次导入里重名的文档看得出会加编号，不再都显示成“新加”。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingImportPreviewUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val writingDir get() = File(context.filesDir, "writing")
    private var workId = ""

    private val seed = object : ExternalResource() {
        override fun before() {
            val files = context.filesDir
            File(files, "books").deleteRecursively()
            File(files, "books").mkdirs()
            File(files, "books/index.json").writeText("[]")
            writingDir.deleteRecursively()
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
            val store = WorkStore(writingDir)
            val w = store.create("长夜")
            store.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", "林月")
            workId = w.id
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun sameNamedDocumentsShowTheirNewTitlesBeforeImport() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val items = listOf(
            PackImport.classify("第一卷/细纲.md", "林月守渡口。"),
            PackImport.classify("第二卷/细纲.md", "沈默在对岸。"),
            PackImport.classify("人物档案.md", "林月、沈默"),
        )
        val appearance = vm.appearance.value
        vm.writing.loadPack(workId)
        compose.runOnUiThread {
            compose.activity.setContent { LingYueTheme(appearance) { WorkPackScreen(vm, workId, initialPreview = items) } }
        }
        waitForText("3 个文件")
        waitForText("和前面的重名，导入为「细纲（2）」")
        waitForText("替换同名")
        compose.onNodeWithText("导入 3 个文件").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.pack(workId).map { it.title }.toSet() == setOf("细纲", "细纲（2）", "人物档案") }
        waitForText("重名的加了编号：细纲（2）")
    }

    /** 改一项用的是分段控件、勾选列表（以前是一排排小芯片）：选法换了，导进去的结果一样。 */
    @Test
    fun editingAnItemWithTheNewControlsChangesWhatIsImported() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val items = listOf(
            PackImport.classify("第一卷/细纲.md", "林月守渡口。"),
            PackImport.classify("杂记.md", "随手记的。"),
        )
        val appearance = vm.appearance.value
        vm.writing.loadPack(workId)
        compose.runOnUiThread {
            compose.activity.setContent { LingYueTheme(appearance) { WorkPackScreen(vm, workId, initialPreview = items) } }
        }
        waitForText("2 个文件")
        fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        fun option(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

        // 杂记：不导入。
        compose.onNodeWithText("杂记").performClick()
        waitForText("当设定")
        tab("不导入").performClick()
        compose.onNodeWithText("好").performClick()
        waitForText("导入 1 个文件")

        // 细纲：改成人物档案（优先级、常用跟着类型走），再改成按需。
        compose.onNodeWithText("细纲").performClick()
        waitForText("当设定")
        option("人物档案").performScrollTo().performClick()
        tab("按需").performScrollTo().performClick()
        compose.onNodeWithText("好").performClick()
        waitForText("人物档案 · 优先级 ${PackKind.CHARACTER.priority} · 按需")

        compose.onNodeWithText("导入 1 个文件").performClick()
        val store = WorkStore(writingDir)
        waitFor { store.pack(workId).any { it.title == "细纲" } }
        val doc = store.pack(workId).single { it.title == "细纲" }
        assertEquals(Triple(PackKind.CHARACTER, PackKind.CHARACTER.priority, false), Triple(doc.kind, doc.priority, doc.always))
        assertTrue(store.pack(workId).none { it.title == "杂记" })
    }
}
