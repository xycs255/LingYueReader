package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.CheckRule
import com.xycs.lingyuereader.data.RuleSource
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.data.withChapterAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 作品页色块带和规划树拖动的纯计算：坐标点中哪章、每卷的读屏说明、落点、字数小条。 */
class WritingTreeMapTest {
    private fun ch(id: String, title: String, status: ChapterStatus = ChapterStatus.DRAFT) = WorkChapter(id, title, status = status)

    @Test
    fun tileCoordinatesMapToTheChapterUnderTheFinger() {
        // 一格 18、缝 4：一行放得下 (100 + 4) / 22 = 4 格。
        val grid = TileGrid.fit(100f, 18f, 4f)
        assertEquals(4, grid.columns)
        assertEquals(3, grid.rows(10))
        assertEquals(18f * 3 + 4f * 2, grid.height(10))
        assertEquals(0, grid.indexAt(1f, 1f, 10))
        assertEquals(1, grid.indexAt(22f, 0f, 10))
        // 格子右边、下边的缝算这一格，不落空。
        assertEquals(0, grid.indexAt(20f, 20f, 10))
        assertEquals(5, grid.indexAt(23f, 23f, 10))
        assertEquals(9, grid.indexAt(22f, 44f + 2f, 10))
        // 最后一格后面的空位、出了范围：没点中。
        assertNull(grid.indexAt(44f + 1f, 44f + 1f, 10))
        assertNull(grid.indexAt(-1f, 3f, 10))
        assertNull(grid.indexAt(3f, 70f, 10))
        assertNull(grid.indexAt(4 * 22f + 1f, 0f, 10))
        assertNull(grid.indexAt(1f, 1f, 0))
        // 格子的左上角和点中算法对得上。
        repeat(10) { i -> val o = grid.origin(i); assertEquals(i, grid.indexAt(o.x + 9f, o.y + 9f, 10)) }
        // 章多时格子缩小。
        assertTrue(TileGrid.tileFor(600).first < TileGrid.tileFor(60).first)
    }

    @Test
    fun eachVolumeHasASpokenSummaryAndEachChapterAnAction() {
        val chapters = List(12) { ch("f$it", "第${it + 1}章", ChapterStatus.FINAL) } +
            List(3) { ch("r$it", "修$it", ChapterStatus.REVISION) } +
            List(15) { ch("d$it", "草$it", ChapterStatus.DRAFT) }
        val volume = Volume("v1", "第一卷", chapters)
        assertEquals("第一卷 30 章：定稿 12、修订 3、草稿 15，2 章没整理", volumeMapSummary(volume, setOf("d0", "d1", "nope")))
        assertEquals("第一卷 30 章：定稿 12、修订 3、草稿 15", volumeMapSummary(volume, emptySet()))
        assertEquals("第二卷：还没有章", volumeMapSummary(Volume("v2", "第二卷"), emptySet()))
        assertEquals("打开第3章 夜渡，草稿，没整理", chapterTileLabel(ch("x", "第3章 夜渡"), unsynced = true))
        assertEquals("打开雪夜，定稿（锁定）", chapterTileLabel(ch("y", "雪夜", ChapterStatus.FINAL), unsynced = false))
    }

    private val work = Work(
        "w", "长夜",
        volumes = listOf(
            Volume("v1", "第一卷", listOf(ch("a", "雪夜"), ch("b", "渡口"), ch("c", "夜渡"))),
            Volume("v2", "第二卷", listOf(ch("d", "客栈"))),
        ),
    )

    /** 列表里排着的行：每行 50 高，顺序和规划树一样。 */
    private fun slots(vararg keys: String) = keys.mapIndexed { i, k -> TreeSlot(k, i * 50f, i * 50f + 50f) }

    private val expanded = slots("tree:heading", "tree:outline", "volume:v1", "a", "b", "c", "add:v1", "volume:v2", "d", "add:v2", "tree:hint")

    @Test
    fun dropTargetsFollowTheFingerAcrossChaptersVolumesAndEnds() {
        fun at(y: Float, dragged: String = "a", collapsed: Set<String> = emptySet(), rows: List<TreeSlot> = expanded) =
            treeDropAt(work, dragged, collapsed, rows, y)!!

        // 拖着雪夜：手指在“夜渡”下半 → 放在夜渡后面（去掉雪夜后第一卷的第 2 个）。
        at(5 * 50f + 40f).let { assertEquals(TreeDrop("v1", 2, "c", before = false), it) }
        // 在“渡口”上半：放在渡口前面，也就是原位，不画线、不改。
        assertTrue(at(4 * 50f + 5f).same)
        // 在自己身上：原位。
        assertTrue(at(3 * 50f + 30f).same)
        // “新的一章”：本卷末尾。
        assertEquals(TreeDrop("v1", 2, "add:v1", before = true), at(6 * 50f + 10f))
        // 第二卷卷名的下半：第二卷卷首；上半：第一卷末尾。
        assertEquals(TreeDrop("v2", 0, "volume:v2", before = false), at(7 * 50f + 40f))
        assertEquals(TreeDrop("v1", 2, "volume:v2", before = true), at(7 * 50f + 5f))
        // 列表最上面（总纲那几行）算第一卷卷首，最下面算最后一卷末尾。
        assertEquals(TreeDrop("v1", 0, "volume:v1", before = false, same = true), at(10f))
        assertEquals(TreeDrop("v2", 1, "add:v2", before = true), at(10_000f))
        // 收起的卷：放到那一卷末尾。
        val folded = slots("tree:outline", "volume:v1", "a", "b", "c", "add:v1", "volume:v2", "tree:hint")
        assertEquals(TreeDrop("v2", 1, "volume:v2", before = false), at(6 * 50f + 10f, collapsed = setOf("v2"), rows = folded))
        // 拖着客栈到“雪夜”上半：第一卷卷首。
        assertEquals(TreeDrop("v1", 0, "a", before = true), at(3 * 50f + 5f, dragged = "d"))

        // 落点和数据接口用同一种“第几个”：照落点移过去就是想要的顺序。
        val moved = work.withChapterAt("a", "v1", 2)!!
        assertEquals(listOf("b", "c", "a"), moved.volumes[0].chapters.map { it.id })
        // 拖着的章不在作品里：没有落点。
        assertNull(treeDropAt(work, "nope", emptySet(), expanded, 10f))
    }

    @Test
    fun lengthBarAndCardTexts() {
        val source = RuleSource("检查规则", 1, "字数：3000—5000")
        assertNull(lengthFraction(1200, null))
        assertEquals(0.24f to false, lengthFraction(1200, CheckRule.Length(3000, 5000, false, source)))
        assertEquals(1f to true, lengthFraction(5200, CheckRule.Length(3000, 5000, false, source)))
        // 只有下限：按下限算，到了也不算超。
        assertEquals(1f to false, lengthFraction(3600, CheckRule.Length(3000, null, false, source)))
        assertFalse(lengthFraction(100, CheckRule.Length(null, 4000, false, source))!!.second)

        assertEquals("还没有记录", ledgerValue(0, PromiseCounts(0, 0, 0)))
        assertEquals("3 条伏笔没收", ledgerValue(9, PromiseCounts(5, 3, 0)))
        assertEquals("伏笔都收了", ledgerValue(9, PromiseCounts(5, 0, 0)))
        assertEquals("9 条", ledgerValue(9, PromiseCounts(0, 0, 0)))
        assertEquals("9 条", ledgerValue(9, null))
        assertNull(ledgerValue(null, null))
        assertEquals("12.3" to "万字", wordsParts(123_456))
        assertEquals("8650" to "字", wordsParts(8650))
    }
}
