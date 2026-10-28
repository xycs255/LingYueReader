package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 规划树拖动调顺序的数据接口：移到某卷某位置、撤销，以及按“第几章”引用的记录在移动后仍指向同一章。 */
class ChapterMoveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = WorkStore(tmp.newFolder())

    /** 两卷：第一卷 a b c，第二卷 d e。章名不写“第几章”，章号按目录顺序算。 */
    private fun twoVolumes(s: WorkStore, names: List<String> = listOf("雪夜", "渡口", "夜渡", "客栈", "旧账")): Pair<Work, List<String>> {
        val w = s.create("长夜")
        val v1 = w.volumes[0].id
        s.renameChapter(w.id, w.chapters[0].id, names[0])
        val ids = mutableListOf(w.chapters[0].id)
        ids += s.addChapter(w.id, v1, names[1])!!.second.id
        ids += s.addChapter(w.id, v1, names[2])!!.second.id
        val v2 = s.addVolume(w.id, "第二卷")!!.volumes[1].id
        ids += s.addChapter(w.id, v2, names[3])!!.second.id
        ids += s.addChapter(w.id, v2, names[4])!!.second.id
        return s.get(w.id)!! to ids
    }

    private fun titles(s: WorkStore, workId: String) = s.get(workId)!!.volumes.map { v -> v.chapters.map { it.title } }

    @Test
    fun movesWithinAVolumeAcrossVolumesToTheEndsAndLeavesTheSamePlaceAlone() {
        val s = store()
        val (w, ids) = twoVolumes(s)
        val (v1, v2) = w.volumes.map { it.id }

        // 本卷内：雪夜移到第一卷末尾。
        val within = s.moveChapterTo(w.id, ids[0], v1, 2)!!
        assertEquals(listOf(listOf("渡口", "夜渡", "雪夜"), listOf("客栈", "旧账")), titles(s, w.id))
        assertEquals(3, within.position)
        assertEquals("雪夜", within.title)

        // 跨卷到卷首；超出的位置收到两头。
        s.moveChapterTo(w.id, ids[0], v2, -5)
        assertEquals(listOf(listOf("渡口", "夜渡"), listOf("雪夜", "客栈", "旧账")), titles(s, w.id))
        s.moveChapterTo(w.id, ids[1], v2, 99)
        assertEquals(listOf(listOf("夜渡"), listOf("雪夜", "客栈", "旧账", "渡口")), titles(s, w.id))
        // 到全书最前。
        val head = s.moveChapterTo(w.id, ids[4], v1, 0)!!
        assertEquals(1, head.position)
        assertEquals(listOf(listOf("旧账", "夜渡"), listOf("雪夜", "客栈", "渡口")), titles(s, w.id))

        // 拖回原位：什么都不写，作品的修改时间也不变。
        val before = s.get(w.id)!!
        assertNull(s.moveChapterTo(w.id, ids[2], v1, 1))
        assertEquals(before, s.get(w.id))
        // 章或卷不在了：不动。
        assertNull(s.moveChapterTo(w.id, "nope", v1, 0))
        assertNull(s.moveChapterTo(w.id, ids[2], "nope", 0))
        assertEquals(before, s.get(w.id))
    }

    @Test
    fun undoRestoresTheOrderButNeverOverwritesALaterChange() {
        val s = store()
        val (w, ids) = twoVolumes(s)
        val v2 = w.volumes[1].id
        val original = s.get(w.id)!!.order()

        val move = s.moveChapterTo(w.id, ids[1], v2, 1)!!
        // 之后改了章名、写了正文：不碰顺序，撤销照样可以，改的东西留着。
        s.renameChapter(w.id, ids[1], "渡口（改）")
        s.saveText(w.id, ids[3], "林月推开门。")
        val undone = s.undoChapterMove(w.id, move)
        assertEquals(original, undone.order())
        assertEquals("渡口（改）", undone.chapter(ids[1])!!.title)
        assertTrue(undone.chapter(ids[3])!!.words > 0)

        // 移动后又移动了别的章：目录已经不是这次移动后的样子，撤销被拒绝，后来的顺序原样留着。
        val again = s.moveChapterTo(w.id, ids[0], v2, 0)!!
        s.moveChapterTo(w.id, ids[4], w.volumes[0].id, 0)
        val later = s.get(w.id)!!.order()
        try {
            s.undoChapterMove(w.id, again)
            throw AssertionError("应当拒绝撤销")
        } catch (e: ChapterMoveConflict) {
            assertTrue(e.message!!.contains("没有撤销"))
        }
        assertEquals(later, s.get(w.id)!!.order())

        // 加了一章也算改过目录。
        val third = s.moveChapterTo(w.id, ids[2], v2, 0)!!
        s.addChapter(w.id, v2, "归途")
        val withNew = s.get(w.id)!!.order()
        assertTrue(runCatching { s.undoChapterMove(w.id, third) }.exceptionOrNull() is ChapterMoveConflict)
        assertEquals(withNew, s.get(w.id)!!.order())
    }

    @Test
    fun numberedRecordsStillPointAtTheSameChapterAfterAMove() {
        val s = store()
        val (w, ids) = twoVolumes(s)
        val v2 = w.volumes[1].id
        // 账本里第 2 章埋下、计划第 4 章收的伏笔；设定包里的伏笔表也按章号写。
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.PROMISE, text = "半块玉佩", from = 2, payoff = 4))), "测试")
        s.addPackDoc(w.id, PackKind.FORESHADOW, "伏笔表", "| 伏笔 | 埋 | 收 |\n| 半块玉佩 | 2 | 4 |")
        val numbers = ids.associateWith { s.chapterNo(s.get(w.id)!!, it) }
        assertEquals(listOf(1, 2, 3, 4, 5), ids.map { numbers[it] })

        // 渡口（第 2 章）拖到第二卷末尾：中间几章的目录位置都变了。
        val move = s.moveChapterTo(w.id, ids[1], v2, 2)!!
        val moved = s.get(w.id)!!
        assertEquals(listOf("雪夜", "夜渡", "客栈", "旧账", "渡口"), moved.chapters.map { it.title })
        // 每章按设定算的章号不变：账本、伏笔表里的“第 2 章”“第 4 章”仍是原来那两章。
        ids.forEach { assertEquals(numbers[it], s.chapterNo(moved, it)) }
        assertEquals(1 to 0, ChapterTreeMarks.promiseCounts(s.ledger(w.id), s.chapterNo(moved, ids[1])))
        assertEquals(0 to 1, ChapterTreeMarks.promiseCounts(s.ledger(w.id), s.chapterNo(moved, ids[3])))
        // 只固定了章号会变的几章（第一章没动），写在细纲卡的细纲章号里。
        assertEquals(mapOf(ids[1] to "2", ids[2] to "3", ids[3] to "4", ids[4] to "5"), move.pinned)
        assertEquals("", s.card(w.id, ids[0]).plan)
        assertEquals("2", s.card(w.id, ids[1]).plan)

        // 撤销：顺序回来，固定的章号清掉；作者之后自己改过的那一张留着。
        s.saveCard(w.id, ids[4], s.card(w.id, ids[4]).copy(plan = "8"))
        s.undoChapterMove(w.id, move)
        assertEquals(listOf("雪夜", "渡口", "夜渡", "客栈", "旧账"), s.get(w.id)!!.chapters.map { it.title })
        assertEquals("", s.card(w.id, ids[1]).plan)
        assertEquals("", s.card(w.id, ids[3]).plan)
        assertEquals("8", s.card(w.id, ids[4]).plan)
        assertEquals(listOf(1, 2, 3, 4), ids.take(4).map { s.chapterNo(s.get(w.id)!!, it) })
    }

    @Test
    fun withoutNumberedRecordsOrWithNumberedTitlesNothingIsPinned() {
        // 没有账本、没有设定包：没有东西按章号引用，章号照目录顺序走，不往细纲卡里写。
        val bare = store()
        val (w, ids) = twoVolumes(bare)
        val move = bare.moveChapterTo(w.id, ids[0], w.volumes[1].id, 2)!!
        assertTrue(move.pinned.isEmpty())
        assertEquals(5, bare.chapterNo(bare.get(w.id)!!, ids[0]))
        assertTrue(ids.all { bare.card(w.id, it).plan.isEmpty() })

        // 章名写着“第几章”：章号按章名算，移动不改章号，也不用固定。
        val titled = store()
        val (t, tids) = twoVolumes(titled, listOf("第1章 雪夜", "第2章 渡口", "第3章 夜渡", "第4章 客栈", "第5章 旧账"))
        titled.addPackDoc(t.id, PackKind.VOLUME, "卷纲细纲", "### 第2章 渡口\n事实：林月到渡口。")
        val titledMove = titled.moveChapterTo(t.id, tids[1], t.volumes[1].id, 2)!!
        assertTrue(titledMove.pinned.isEmpty())
        assertEquals(2, titled.chapterNo(titled.get(t.id)!!, tids[1]))
    }

    @Test
    fun finalizedChaptersLockTheirTextButNotTheirPlace() {
        val s = store()
        val (w, ids) = twoVolumes(s)
        s.saveText(w.id, ids[0], "林月推开门，风雪灌进来。")
        s.setStatus(w.id, ids[0], ChapterStatus.FINAL)
        assertTrue(s.get(w.id)!!.chapter(ids[0])!!.locked)

        val move = s.moveChapterTo(w.id, ids[0], w.volumes[1].id, 1)
        assertNotNull(move)
        val moved = s.get(w.id)!!
        assertEquals(w.volumes[1].id, moved.volumeOf(ids[0])!!.id)
        // 还是定稿，正文没动。
        assertEquals(ChapterStatus.FINAL, moved.chapter(ids[0])!!.status)
        assertEquals("林月推开门，风雪灌进来。", s.text(w.id, ids[0]))
        // 上下箭头（备用方式）也照样能挪定稿的章。
        s.moveChapter(w.id, ids[0], -1)
        assertEquals(0, s.get(w.id)!!.volumes[1].chapters.indexOfFirst { it.id == ids[0] })
    }

    @Test
    fun arrowMovesKeepNumberedReferencesToo() {
        val s = store()
        val (w, ids) = twoVolumes(s)
        s.applyLedger(w.id, listOf(LedgerOp.Add(LedgerEntry(kind = LedgerKind.EVENT, text = "渡口封航", from = 3))), "测试")
        s.moveChapter(w.id, ids[2], -1)
        val moved = s.get(w.id)!!
        assertEquals(listOf("雪夜", "夜渡", "渡口"), moved.volumes[0].chapters.map { it.title })
        assertEquals(3, s.chapterNo(moved, ids[2]))
        assertEquals(2, s.chapterNo(moved, ids[1]))
    }
}
