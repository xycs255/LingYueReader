package com.xycs.lingyuereader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.WritingScene
import com.xycs.lingyuereader.data.WritingScenePlan
import com.xycs.lingyuereader.data.Appearance
import com.xycs.lingyuereader.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingScenePlanUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun reduceMotionForDeterministicDialogs() { Effects.reduced = true }
    @After fun restoreMotionPreference() { Effects.reduced = false }

    private val suggestion = WritingScenePlan(
        taskFingerprint = "a".repeat(64),
        scenes = listOf(
            WritingScene("杜娘开门", "门外人不报姓名", "她认出旧物", "门外人进屋"),
            WritingScene("问出来意", "来者不肯明说", "杜娘决定试探", "话题转到雪夜"),
        ),
    )

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appDir = if (working.name == "app") working else File(working, "app")
        val output = File(appDir, "build/outputs/ui-check/$name.png")
        output.parentFile!!.mkdirs()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return image
    }

    @Test
    fun proposalCannotWriteUntilAuthorExplicitlyApprovesIt() {
        val displayed = mutableStateOf(suggestion)
        val approved = mutableStateOf(false)
        val appearance = mutableStateOf(Appearance(mode = ThemeMode.LIGHT, blur = false, liquidGlass = false))
        var saves = 0
        var writes = 0
        compose.setContent {
            LingYueTheme(appearance.value) {
                ProvideGroupedColors {
                    Column(Modifier.fillMaxSize().background(LocalGroupedColors.current.background)
                        .verticalScroll(rememberScrollState())) {
                        WritingScenePlanSection(
                            plan = displayed.value,
                            approved = approved.value,
                            draftStarted = false,
                            loading = false,
                            error = null,
                            canGenerate = true,
                            canWrite = true,
                            onGenerate = {},
                            onChangePlan = { displayed.value = it },
                            onSavePlan = { saves++; displayed.value = it; approved.value = true },
                            onWriteNext = { writes++ },
                            onReset = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("待确认").assertExists()
        compose.onNodeWithText("按安排写第 1 场").assertDoesNotExist()
        assertEquals(0, saves)
        assertEquals(0, writes)
        compose.onNodeWithText("确认场景安排").performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("已确认").assertExists()
        compose.onNodeWithText("按安排写第 1 场").performClick()
        assertEquals(1, writes)
        val paper = capture("scene-plan-paper")
        appearance.value = Appearance(mode = ThemeMode.DARK, blur = false, liquidGlass = false)
        val night = capture("scene-plan-night")
        assertTrue("夜间页面底色也应切换", android.graphics.Color.red(night.getPixel(2, night.height / 2)) < 80)
        assertTrue("纸色页面底色应保持明亮", android.graphics.Color.red(paper.getPixel(2, paper.height / 2)) > 180)
    }

    @Test
    fun selectionAndFourFieldEditsStayStagedAndExposeReadableTouchTargets() {
        val displayed = mutableStateOf(suggestion)
        var saves = 0
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WritingScenePlanSection(
                            plan = displayed.value,
                            approved = false,
                            draftStarted = false,
                            loading = false,
                            error = null,
                            canGenerate = true,
                            canWrite = true,
                            onGenerate = {},
                            onChangePlan = { displayed.value = it },
                            onSavePlan = { saves++ },
                            onWriteNext = {},
                            onReset = {},
                        )
                    }
                }
            }
        }

        val choose = compose.onNodeWithContentDescription("第 2 场取消选用")
        val bounds = choose.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        choose.performClick()
        assertFalse(displayed.value.scenes[1].selected)
        compose.onNodeWithContentDescription("第 1 场已选，至少保留一场").assertIsNotEnabled()

        val edit = compose.onNodeWithContentDescription("编辑第 1 场安排")
        val editBounds = edit.getUnclippedBoundsInRoot()
        assertTrue(editBounds.right - editBounds.left >= 48.dp && editBounds.bottom - editBounds.top >= 48.dp)
        edit.performClick()
        compose.onNode(hasSetTextAction() and hasText("杜娘开门")).performTextReplacement("杜娘先听脚步")
        compose.onNode(hasSetTextAction() and hasText("门外人不报姓名")).performTextReplacement("陌生人拒绝说出姓名")
        compose.onNode(hasSetTextAction() and hasText("她认出旧物")).performTextReplacement("她看见熟悉的玉佩")
        compose.onNode(hasSetTextAction() and hasText("门外人进屋")).performTextReplacement("她把人带进屋")
        compose.onNodeWithText("保存修改").performClick()
        assertEquals("杜娘先听脚步", displayed.value.scenes[0].goal)
        assertEquals("陌生人拒绝说出姓名", displayed.value.scenes[0].conflict)
        assertEquals("她看见熟悉的玉佩", displayed.value.scenes[0].turn)
        assertEquals("她把人带进屋", displayed.value.scenes[0].exit)
        assertEquals("修改选择与字段不等于批准", 0, saves)
    }

    @Test
    fun startedPlanIsReadOnlyButCanContinueOrExitWithConfirmation() {
        val plan = suggestion.copy(completed = 1, draftHash = "b".repeat(64))
        var writes = 0
        var resets = 0
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WritingScenePlanSection(
                            plan = plan,
                            approved = true,
                            draftStarted = true,
                            loading = false,
                            error = null,
                            canGenerate = true,
                            canWrite = true,
                            onGenerate = {},
                            onChangePlan = { error("Started plan must not be editable") },
                            onSavePlan = { error("Started plan must not be re-approved") },
                            onWriteNext = { writes++ },
                            onReset = { resets++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("已完成 1 / 2 场").assertExists()
        compose.onNodeWithText("已写").assertExists()
        compose.onNodeWithContentDescription("编辑第 1 场安排").assertDoesNotExist()
        compose.onNodeWithText("重新生成场景建议").assertDoesNotExist()
        compose.onNodeWithText("按安排写第 2 场").performClick()
        assertEquals(1, writes)
        compose.onNodeWithText("退出场景安排").performClick()
        assertEquals(0, resets)
        compose.onNodeWithText("退出安排").performClick()
        assertEquals(1, resets)
    }
}
