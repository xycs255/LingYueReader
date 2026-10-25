package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.PageMode
import com.xycs.lingyuereader.data.ReaderSettings
import com.xycs.lingyuereader.data.ReaderTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

/** 可供审阅的实际 Compose 组件截图；Robolectric 渲染，非真机录屏。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionChoiceScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetMotion() { Effects.reduced = false }

    @Test fun importModeChoicesCapture() {
        Effects.reduced = true
        compose.setContent {
            LingYueTheme {
                Column(Modifier.width(360.dp).background(Color(0xFFF7F4EF)).testTag("capture").padding(16.dp)) {
                    Text("导入 3 个文件", color = Color(0xFF332B24), fontSize = 19.sp,
                        modifier = Modifier.padding(bottom = 14.dp))
                    ImportModeChoices(3, merge = true, onSelect = {})
                }
            }
        }
        capture("import-modes")
    }

    @Test fun paperPageModeCapture() {
        Effects.reduced = true
        compose.setContent {
            LingYueTheme {
                val palette = ReaderTheme.PAPER.palette()
                ProvideReaderColors(palette, palette.accent) {
                    Column(Modifier.width(360.dp).background(Color(0xFFF7F4EF)).testTag("capture")) {
                        PagingPage(ReaderSettings(pageMode = PageMode.CURL)) { }
                    }
                }
            }
        }
        capture("page-mode-preview")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("capture").captureToImage().asAndroidBitmap()
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (workingDir.name == "app") workingDir else File(workingDir, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { stream ->
            check(bitmap.copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
