package com.xycs.lingyuereader.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                    // 作品页上它在列表的一项里，几块从上往下排；这里用一列代替。
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    WritingWorkOverview(
                        work = work,
                        packCount = 2,
                        ledgerCount = 3,
                        aiAdviceEnabled = false,
                        pendingChanges = 1,
                        unsyncedChapters = 1,
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
                            openRules = { called += "rules" },
                            organizeUnsynced = { called += "organize" },
                        ),
                    )
                    }
                }
            }
        }

        compose.onNodeWithText("继续写").performClick()
        compose.onNodeWithText("设定包").performClick()
        compose.onNodeWithText("故事账本").performClick()
        compose.onNodeWithText("写作 AI").performClick()
        assertEquals(listOf("chapter", "pack", "ledger", "ai"), called)

        // 进度卡上的“待整理”“待同步设定”能点：一个直接整理，一个打开变更登记。
        compose.onNodeWithText("1 章待整理 ›").performClick()
        compose.onNodeWithText("1 条待同步设定 ›").performClick()
        assertEquals(listOf("chapter", "pack", "ledger", "ai", "organize", "changes"), called)

        // “更多功能”在本页展开（读屏念已收起 / 已展开），没有组名“其他写作工具”，也没有单独的“伏笔”“整理没整理的章节”行。
        compose.onNodeWithText("其他写作工具").assertDoesNotExist()
        compose.onNodeWithText("当书预览").assertDoesNotExist()
        compose.onNodeWithText("更多功能").performScrollTo().assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起"))
        compose.onNodeWithText("更多功能").performClick()
        compose.onNodeWithText("更多功能").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        compose.onNodeWithText("当书预览").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("变更登记").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("检查规则").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("伏笔").assertDoesNotExist()
        compose.onNodeWithText("整理没整理的章节").assertDoesNotExist()
        assertEquals(
            listOf("chapter", "pack", "ledger", "ai", "organize", "changes", "preview", "changes", "rules"),
            called,
        )
    }

    /** 约 3 卷 60 章、状态混着的虚构作品：色块带和大卡片用。 */
    private fun mixedWork(): Work {
        val statuses = listOf(ChapterStatus.FINAL, ChapterStatus.FINAL, ChapterStatus.REVISION, ChapterStatus.DRAFT, ChapterStatus.OUTLINE)
        val volumes = (1..3).map { v ->
            Volume("v$v", "第${listOf("一", "二", "三")[v - 1]}卷", (1..20).map { i ->
                val n = (v - 1) * 20 + i
                WorkChapter("c$n", "第${n}章 渡口$n", words = if (n % 5 == 0) 0 else 3000 + n * 10, updatedAt = n.toLong(), status = statuses[n % 5])
            })
        }
        return Work("w", "长夜", author = "沈默", volumes = volumes)
    }

    private fun setOverview(work: Work, opened: MutableList<String>, menu: MutableList<String> = mutableListOf()) {
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        WritingWorkOverview(
                            work = work,
                            packCount = 7,
                            ledgerCount = 12,
                            aiAdviceEnabled = true,
                            pendingChanges = 0,
                            unsyncedChapters = 2,
                            ruleCount = 0,
                            busy = false,
                            unsyncedIds = setOf("c3", "c4"),
                            promises = PromiseCounts(total = 6, open = 4, due = 0),
                            aiProvider = "DeepSeek",
                            todayWords = 1200,
                            dailyGoal = 3000,
                            actions = WritingWorkOverviewActions(
                                openChapter = { opened += it.id },
                                newChapter = {},
                                openPack = {},
                                openLedger = {},
                                openAi = {},
                                preview = {},
                                openChanges = {},
                                openRules = {},
                                organizeUnsynced = {},
                                openChapterMenu = { menu += it.id },
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun bigCardsShowWhatMattersAndTheRingSummarizesTheBook() {
        val work = mixedWork()
        setOverview(work, mutableListOf())
        // 继续写：上次写的章名；设定包：文档数；账本：没收的伏笔数；写作 AI：服务商。
        compose.onNode(hasText("继续写") and hasText("第60章 渡口60")).assertExists()
        compose.onNode(hasText("设定包") and hasText("7 份文档")).assertExists()
        compose.onNode(hasText("故事账本") and hasText("4 条伏笔没收")).assertExists()
        compose.onNode(hasText("写作 AI") and hasText("DeepSeek · 写作建议已开")).assertExists()
        // 进度环：定稿 24 / 60，旁边写着；有每天的目标时再写今天写了多少。
        compose.onNodeWithText("定稿 24 / 共 60 章").assertExists()
        compose.onNodeWithContentDescription("定稿 24 / 共 60 章", substring = true).assertExists()
        compose.onNodeWithText("今天写了 1200 字 · 目标 3000").assertExists()
    }

    @Test
    fun aiCardSaysNotSetWithoutAProvider() {
        val chapter = WorkChapter("c", "第一章 夜渡", words = 26, status = ChapterStatus.DRAFT)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    WritingWorkOverview(
                        work = Work("w", "长夜", volumes = listOf(Volume("v", "正文", listOf(chapter)))),
                        packCount = 0, ledgerCount = 0, aiAdviceEnabled = false, pendingChanges = 0, unsyncedChapters = 0,
                        ruleCount = null, busy = false,
                        actions = WritingWorkOverviewActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
        }
        compose.onNode(hasText("写作 AI") and hasText("未设置")).assertExists()
        compose.onNode(hasText("设定包") and hasText("还没有文档")).assertExists()
        compose.onNode(hasText("故事账本") and hasText("还没有记录")).assertExists()
    }

    @Test
    fun tappingATileOpensThatChapterAndLongPressAsksForTheMenu() {
        val opened = mutableListOf<String>()
        val menu = mutableListOf<String>()
        setOverview(mixedWork(), opened, menu)
        val first = compose.onNodeWithContentDescription("第一卷 20 章", substring = true)
        // 一格 18dp、缝 4dp：第 2 格的中心在 (31dp, 9dp)。
        val (x, y) = with(compose.density) { 31.dp.toPx() to 9.dp.toPx() }
        first.performTouchInput { click(Offset(x, y)) }
        assertEquals(listOf("c2"), opened)
        first.performTouchInput { longClick(Offset(x, y)) }
        assertEquals(listOf("c2"), menu)
        // 第二卷的第 1 格是第 21 章。
        compose.onNodeWithContentDescription("第二卷 20 章", substring = true).performTouchInput {
            click(Offset(with(compose.density) { 9.dp.toPx() }, with(compose.density) { 9.dp.toPx() }))
        }
        assertEquals(listOf("c2", "c21"), opened)
    }

    @Test
    fun eachVolumeIsDescribedAndEachChapterCanBeOpenedFromTheAccessibilityActions() {
        val opened = mutableListOf<String>()
        setOverview(mixedWork(), opened)
        // 每卷一段文字说明：状态分布和没整理的章数。
        compose.onNodeWithContentDescription("第一卷 20 章：定稿 8、修订 4、草稿 4、细纲 4，2 章没整理").assertExists()
        compose.onNodeWithContentDescription("第三卷 20 章：定稿 8、修订 4、草稿 4、细纲 4").assertExists()
        // 逐章：自定义操作，名字里有章名和状态。
        val node = compose.onNodeWithContentDescription("第一卷 20 章", substring = true).fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(20, actions.size)
        val third = actions.single { it.label.startsWith("打开第3章 渡口3") }
        assertEquals("打开第3章 渡口3，草稿，没整理", third.label)
        third.action()
        assertEquals(listOf("c3"), opened)
        // 图例：四种状态和“没整理”。
        listOf("细纲", "草稿", "修订", "定稿（锁定）", "没整理").forEach { compose.onNodeWithText(it).assertExists() }
    }

    /** 少于 10 章：只有环和数字，不画色块、不排图例；10 章起才有。 */
    @Test
    fun fewChaptersShowNoMapOrLegend() {
        val nine = Work("w", "长夜", volumes = listOf(Volume("v", "正文", (1..9).map { WorkChapter("c$it", "第${it}章", words = 10, status = ChapterStatus.DRAFT) })))
        var work by androidx.compose.runtime.mutableStateOf(nine)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        WritingWorkOverview(
                            work = work, packCount = 0, ledgerCount = 0, aiAdviceEnabled = false, pendingChanges = 0, unsyncedChapters = 0,
                            ruleCount = null, busy = false, actions = WritingWorkOverviewActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("定稿 0 / 共 9 章").assertExists()
        compose.onNodeWithContentDescription("正文 9 章", substring = true).assertDoesNotExist()
        compose.onNodeWithText("定稿（锁定）").assertDoesNotExist()
        compose.onNodeWithText("没整理").assertDoesNotExist()
        work = Work("w", "长夜", volumes = listOf(Volume("v", "正文", (1..10).map { WorkChapter("c$it", "第${it}章", words = 10, status = ChapterStatus.DRAFT) })))
        compose.onNodeWithContentDescription("正文 10 章", substring = true).assertExists()
        compose.onNodeWithText("定稿（锁定）").assertExists()
        assertEquals(10, CHAPTER_MAP_MIN)
    }

    /** 点进度卡的标题区改作品信息；体检有冷了的线时进度卡上一枚胶囊打开体检表；故事至今、体检在“更多功能”里。 */
    @Test
    fun titleAreaEditsInfoAndTheReviewToolsLiveInMore() {
        val called = mutableListOf<String>()
        val chapter = WorkChapter("c", "第一章 夜渡", words = 26, status = ChapterStatus.DRAFT)
        compose.setContent {
            LingYueTheme {
                ProvideGroupedColors {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        WritingWorkOverview(
                            work = Work("w", "长夜", author = "沈默", volumes = listOf(Volume("v", "正文", listOf(chapter)))),
                            packCount = 1, ledgerCount = 1, aiAdviceEnabled = false, pendingChanges = 0, unsyncedChapters = 0,
                            ruleCount = 0, busy = false,
                            story = com.xycs.lingyuereader.data.StoryDigest("林月过了河。\n老周没回来。", "c", 1, edited = true),
                            pulse = "2 条冷了",
                            pulseWarn = true,
                            pulseAlerts = 2,
                            actions = WritingWorkOverviewActions(
                                {}, {}, {}, {}, {}, {}, {}, {}, {},
                                editInfo = { called += "info" },
                                openStory = { called += "story" },
                                openPulse = { called += "pulse" },
                            ),
                        )
                    }
                }
            }
        }
        // 标题区能点，读屏念“改作品信息”。
        compose.onNode(hasText("定稿 0 / 共 1 章") and androidx.compose.ui.test.hasClickAction()).assertExists()
        compose.onNodeWithText("定稿 0 / 共 1 章").performClick()
        compose.onNodeWithText("2 条线索冷了 ›").performClick()
        assertEquals(listOf("info", "pulse"), called)
        compose.onNodeWithText("故事至今").assertDoesNotExist()
        compose.onNodeWithText("更多功能").performScrollTo().performClick()
        compose.onNodeWithText("2 句 · 写到第 1 章 · 你改过的").assertExists()
        compose.onNodeWithText("故事至今").performScrollTo().performClick()
        compose.onNodeWithText("线索与节奏体检").performScrollTo().performClick()
        assertEquals(listOf("info", "pulse", "story", "pulse"), called)
    }
}
