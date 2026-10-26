package com.xycs.lingyuereader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingWorkOverviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun primaryRoutesStayVisibleAndSecondaryRoutesRemainReachable() {
        val called = mutableListOf<String>()
        val chapter = WorkChapter("c", "第一章 夜渡", words = 26, status = ChapterStatus.DRAFT)
        val work = Work("w", "长夜", volumes = listOf(Volume("v", "正文", listOf(chapter))))
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    WritingWorkOverview(
                        work = work,
                        packCount = 2,
                        ledgerCount = 3,
                        aiAdviceEnabled = false,
                        pendingChanges = 1,
                        unsyncedChapters = 1,
                        foreshadowNote = "2 条没回收",
                        ruleCount = 2,
                        busy = false,
                        actions = WritingWorkOverviewActions(
                            openChapter = { called += "chapter" },
                            newChapter = { called += "new" },
                            openPack = { called += "pack" },
                            openLedger = { called += "ledger" },
                            openAi = { called += "ai" },
                            preview = { called += "preview" },
                            openChanges = { called += "changes" },
                            openForeshadows = { called += "foreshadows" },
                            openRules = { called += "rules" },
                            organizeUnsynced = { called += "organize" },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithText("继续写").performClick()
        compose.onNodeWithText("设定包").performClick()
        compose.onNodeWithText("故事账本").performClick()
        compose.onNodeWithText("写作 AI").performClick()
        assertEquals(listOf("chapter", "pack", "ledger", "ai"), called)

        compose.onNodeWithText("当书预览").assertDoesNotExist()
        compose.onNodeWithText("更多功能").performClick()
        compose.onNodeWithText("当书预览").assertExists().performClick()
        compose.onNodeWithText("变更登记").assertExists().performClick()
        compose.onNodeWithText("伏笔").assertExists().performClick()
        compose.onNodeWithText("检查规则").assertExists().performClick()
        compose.onNodeWithText("整理没整理的章节").assertExists().performClick()
        assertEquals(
            listOf("chapter", "pack", "ledger", "ai", "preview", "changes", "foreshadows", "rules", "organize"),
            called,
        )
    }
}
