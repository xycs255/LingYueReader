package com.xycs.lingyuereader.ui

import android.content.Context
import androidx.activity.compose.setContent
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
}
