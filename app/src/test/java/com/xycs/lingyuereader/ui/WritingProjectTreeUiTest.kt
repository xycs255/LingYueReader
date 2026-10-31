package com.xycs.lingyuereader.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class WritingProjectTreeUiTest {
    @get:Rule val compose = createComposeRule()

    private val first = WorkChapter("c1", "第一章 雪夜", words = 1200, status = ChapterStatus.DRAFT)
    private val second = WorkChapter("c2", "第二章 天亮", words = 0, status = ChapterStatus.OUTLINE)
    private val volume = Volume("v1", "第一卷", listOf(first, second))
    private val work = Work("w1", "长夜", volumes = listOf(volume))

    @Test
    fun chapterStillOpensEditorWhileTaskCardAndLongPressRemainReachable() {
        var openedChapter: String? = null
        var openedCard: String? = null
        var openedOutline = false
        var longPressed: String? = null
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    LazyColumn {
                        writingPlanningTreeItems(
                            work = work,
                            unsyncedIds = setOf(first.id),
                            showUnsynced = true,
                            collapsedVolumeIds = emptySet(),
                            reorderMode = false,
                            volumeMenuId = null,
                            chapterMenuId = null,
                            actions = actions(
                                onOutline = { openedOutline = true },
                                onChapter = { openedChapter = it.id },
                                onCard = { openedCard = it.id },
                                onChapterMenu = { longPressed = it.id },
                            ),
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("总纲 · 长夜").performClick()
        assertTrue(openedOutline)
        // Existing tests and TalkBack rely on the chapter title retaining a long-click action.
        val chapter = compose.onNode(hasText(first.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        chapter.performClick()
        assertEquals(first.id, openedChapter)
        chapter.performTouchInput { longClick() }
        assertEquals(first.id, longPressed)
        compose.onNodeWithText("未整理", substring = true).assertExists()

        // 细纲卡图标一点直接进细纲卡页，不再先展开一行。
        val cardButton = compose.onNodeWithContentDescription("打开${first.title}细纲卡")
        val bounds = cardButton.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        cardButton.performClick()
        assertEquals(first.id, openedCard)
        compose.onNodeWithText("事实结果 · 承接边界 · 章末去向").assertDoesNotExist()
        compose.onNodeWithText("新的一章").assertExists()
    }

    @Test
    fun volumesFoldWithoutRemovingDataAndReorderButtonsIdentifyChapters() {
        val collapsed = mutableStateOf(emptySet<String>())
        val reorder = mutableStateOf(false)
        var move: Pair<String, Int>? = null
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    LazyColumn {
                        writingPlanningTreeItems(
                            work = work,
                            unsyncedIds = emptySet(),
                            showUnsynced = false,
                            collapsedVolumeIds = collapsed.value,
                            reorderMode = reorder.value,
                            volumeMenuId = null,
                            chapterMenuId = null,
                            actions = actions(
                                onToggleVolume = { id -> collapsed.value = if (id in collapsed.value) collapsed.value - id else collapsed.value + id },
                                onToggleReorder = { reorder.value = !reorder.value },
                                onMove = { chapter, direction -> move = chapter.id to direction },
                            ),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("第一卷").performClick()
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithText("第一卷").performClick()
        compose.onNodeWithText(first.title).assertExists()

        compose.onNodeWithContentDescription("调整章节顺序").performClick()
        compose.onNodeWithContentDescription("完成调整").assertExists()
        val down = compose.onNodeWithContentDescription("下移${first.title}")
        val bounds = down.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        // 紧凑箭头在章这一行的右侧，和章名同一行，不在章下面另起一排。
        val row = compose.onNode(hasText(first.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).getUnclippedBoundsInRoot()
        assertTrue("箭头和章名在同一行", bounds.top >= row.top && bounds.bottom <= row.bottom)
        down.performClick()
        assertEquals(first.id to 1, move)
        // 到头的方向不能点：第一章不能再上移，最后一章不能再下移。
        compose.onNodeWithContentDescription("上移${first.title}").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下移${second.title}").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上移${second.title}").performClick()
        assertEquals(second.id to -1, move)
        // 调整模式里不显示细纲卡按钮，完成后回来。
        compose.onNodeWithContentDescription("打开${first.title}细纲卡").assertDoesNotExist()
        compose.onNodeWithContentDescription("完成调整").performClick()
        compose.onNodeWithContentDescription("打开${first.title}细纲卡").assertExists()
        compose.onNodeWithContentDescription("下移${first.title}").assertDoesNotExist()
    }

    private fun actions(
        onOutline: () -> Unit = {},
        onChapter: (WorkChapter) -> Unit = {},
        onCard: (WorkChapter) -> Unit = {},
        onChapterMenu: (WorkChapter) -> Unit = {},
        onToggleVolume: (String) -> Unit = {},
        onToggleReorder: () -> Unit = {},
        onMove: (WorkChapter, Int) -> Unit = { _, _ -> },
    ) = WritingTreeActions(
        onOpenOutline = onOutline,
        onToggleVolume = onToggleVolume,
        onOpenVolumeMenu = {},
        onDismissVolumeMenu = {},
        onRenameVolume = {},
        onDeleteVolume = {},
        onOpenChapter = onChapter,
        onOpenCard = onCard,
        onOpenChapterMenu = onChapterMenu,
        onDismissChapterMenu = {},
        onRenameChapter = {},
        onStatusChapter = {},
        onMoveChapter = onMove,
        onDeleteChapter = {},
        onAddChapter = {},
        onToggleReorder = onToggleReorder,
    )
}
