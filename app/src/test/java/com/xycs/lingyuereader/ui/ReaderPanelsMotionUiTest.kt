package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookFormat
import com.xycs.lingyuereader.data.Bookmark
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import com.xycs.lingyuereader.data.ReaderTheme
import com.xycs.lingyuereader.data.SearchHit
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 目录面板的标签页和书签列表、全书架搜索的结果：动效加上后，减少动效时直接换，增删、增量出现时内容不乱。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ReaderPanelsMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetMotion() { Effects.reduced = false }

    private val chapters = listOf(
        Chapter("第一章 晨雾", listOf(Block.Para("雾从河面上升起来。"))),
        Chapter("第二章 渡口", listOf(Block.Para("渡船靠岸时天刚亮。"))),
    )

    private fun bookmark(id: String, chapter: Int, item: Int, excerpt: String) =
        Bookmark(id, chapter, chapters[chapter].title, item, 0, excerpt, createdAt = 0)

    private fun showCatalog(marks: () -> Marks, onDeleteBookmark: (String) -> Unit = {}) {
        compose.setContent {
            LingYueTheme {
                CatalogSheet(
                    chapters = chapters,
                    current = 0,
                    marks = marks(),
                    palette = ReaderTheme.PAPER.palette(),
                    onPickChapter = {},
                    onPickPosition = { _, _, _ -> },
                    onDeleteBookmark = onDeleteBookmark,
                    onDeleteNote = {},
                    onExportNotes = {},
                    bookTitle = "虚构的书",
                )
            }
        }
    }

    /** 点“书签”后走 [frames] 帧（还不到最短的 150 毫秒）。 */
    private fun switchToBookmarks(frames: Int = 3) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("书签 1").performClick()
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun catalogTabsSwitchStraightAwayWhenMotionIsReduced() {
        Effects.reduced = true
        showCatalog({ Marks(bookmarks = listOf(bookmark("b1", 1, 1, "渡船靠岸"))) })
        compose.onNodeWithText("第一章 晨雾").assertExists()
        switchToBookmarks()
        // 不横移、不淡出：几帧之内目录已经换成书签，不会两页叠着。
        compose.onNodeWithText("渡船靠岸").assertExists()
        compose.onNodeWithText("第一章 晨雾").assertDoesNotExist()
    }

    @Test
    fun catalogTabsSlideWithNormalMotionAndSettle() {
        showCatalog({ Marks(bookmarks = listOf(bookmark("b1", 1, 1, "渡船靠岸"))) })
        switchToBookmarks()
        // 横移途中旧页还在离场，新页已经进来。
        compose.onNodeWithText("渡船靠岸").assertExists()
        compose.onNodeWithText("第一章 晨雾").assertExists()
        compose.mainClock.advanceTimeBy(Motion.MEDIUM * 2L)
        compose.onNodeWithText("第一章 晨雾").assertDoesNotExist()
        compose.onNodeWithText("渡船靠岸").assertExists()
    }

    @Test
    fun deletingABookmarkLeavesTheOthersInOrder() {
        var marks by mutableStateOf(
            Marks(
                bookmarks = listOf(
                    bookmark("b3", 1, 5, "第三条书签"),
                    bookmark("b1", 0, 1, "第一条书签"),
                    bookmark("b2", 1, 1, "第二条书签"),
                ),
            ),
        )
        showCatalog({ marks }) { id -> marks = marks.copy(bookmarks = marks.bookmarks.filterNot { it.id == id }) }
        compose.onNodeWithText("书签 3").performClick()
        compose.waitForIdle()
        // 按章节、位置排好：第一、第二、第三；删掉中间那条。
        compose.onAllNodesWithContentDescription("删除")[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("第二条书签").assertDoesNotExist()
        compose.onNodeWithText("第一条书签").assertExists()
        compose.onNodeWithText("第三条书签").assertExists()
        compose.onNodeWithText("书签 2").assertExists()
        assertEquals(listOf("b1", "b3"), marks.bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.itemIndex })).map { it.id })
        // 再删到一条不剩：换成卡片里的一行“还没有书签”。
        compose.onAllNodesWithContentDescription("删除")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("删除")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("还没有书签", substring = true).assertExists()
    }

    private fun book(id: String, title: String, vararg matches: String) = AppViewModel.BookHits(
        BookEntry(id, title, null, BookFormat.TXT, emptyList(), 0, 0, 1, 100, null),
        matches.mapIndexed { i, m -> SearchHit(0, i, 0, "第一章", "前文", m, "后文") },
        more = false,
    )

    @Test
    fun librarySearchResultsArriveBookByBookWithTheRightContent() {
        var state by mutableStateOf(AppViewModel.LibrarySearchState("灯", emptyList(), done = 0, total = 3))
        var opened: Pair<String, String>? = null
        compose.setContent {
            LingYueTheme {
                GlassScaffold { LibrarySearchList(state, PaddingValues()) { book, hit -> opened = book.entry.title to hit.match } }
            }
        }
        // 进度写在卡片里的一行，不写在组名里。
        compose.onNodeWithText("正在搜索 0/3 本…").assertExists()

        state = state.copy(results = listOf(book("a", "甲书", "灯塔", "灯火")), done = 1)
        compose.waitForIdle()
        compose.onNodeWithText("正在搜索 1/3 本…").assertExists()
        compose.onNodeWithText("《甲书》 · 2 处").assertExists()

        // 第二本接在后面，第一本的命中不变。
        state = state.copy(results = state.results + book("b", "乙书", "灯笼"), done = 2)
        compose.waitForIdle()
        compose.onNodeWithText("《甲书》 · 2 处").assertExists()
        compose.onNodeWithText("《乙书》 · 1 处").assertExists()
        assertEquals(1, compose.onAllNodesWithText("前文灯塔后文…").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("前文灯火后文…").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("前文灯笼后文…").fetchSemanticsNodes().size)

        state = state.copy(done = 3)
        compose.waitForIdle()
        compose.onNodeWithText("在 2 本书里找到 3 处").assertExists()
        compose.onNodeWithText("正在搜索", substring = true).assertDoesNotExist()

        compose.onNodeWithText("前文灯笼后文…").performClick()
        assertEquals("乙书" to "灯笼", opened)
    }
}
