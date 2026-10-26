package com.xycs.lingyuereader.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
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
        val expanded = mutableStateOf(emptySet<String>())
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
                            expandedCardIds = expanded.value,
                            reorderMode = false,
                            volumeMenuId = null,
                            chapterMenuId = null,
                            actions = actions(
                                onOutline = { openedOutline = true },
                                onChapter = { openedChapter = it.id },
                                onCard = { openedCard = it.id },
                                onChapterMenu = { longPressed = it.id },
                                onToggleCard = { id -> expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id },
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

        val cardButton = compose.onNodeWithContentDescription("展开${first.title}细纲卡")
        val bounds = cardButton.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        cardButton.performClick()
        compose.onNodeWithText("事实结果 · 承接边界 · 章末去向").performClick()
        assertEquals(first.id, openedCard)
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
                            expandedCardIds = emptySet(),
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

        compose.onNodeWithText("调整章节顺序").performClick()
        val down = compose.onNodeWithContentDescription("下移${first.title}")
        val bounds = down.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        down.performClick()
        assertEquals(first.id to 1, move)
    }

    private fun actions(
        onOutline: () -> Unit = {},
        onChapter: (WorkChapter) -> Unit = {},
        onCard: (WorkChapter) -> Unit = {},
        onChapterMenu: (WorkChapter) -> Unit = {},
        onToggleCard: (String) -> Unit = {},
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
        onToggleCard = onToggleCard,
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
