package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 线索与节奏体检（本机）：线冷了、线原地踏步、伏笔冷了和暂缓、主要人物冷了；旧章没写“本章改变了什么”不误报。人名虚构。 */
class StoryPulseTest {
    private fun ch(n: Int, threads: String = "", change: String = "", moves: List<ForeshadowMove> = emptyList()) =
        PulseChapter("c$n", n, "第${n}章", threads, change, moves)

    @Test
    fun aThreadUntouchedForTenChaptersGoesCold() {
        val chapters = (1..20).map { n ->
            ch(n, threads = listOfNotNull("明线：林月查灯会旧案", "暗线：沈默的身世".takeIf { n == 4 }).joinToString("；"))
        }
        val rows = StoryPulse.check(chapters, emptyList())
        val dark = rows.single { it.name == "暗线" }
        assertEquals(PulseState.COLD, dark.state)
        assertEquals(4, dark.lastNo)
        assertEquals(16, dark.idle)
        assertTrue(dark.note.contains("16 章没推进"))
        // 冷了的排前面。
        assertEquals("暗线", rows.first().name)
        val main = rows.single { it.name == "明线" }
        assertEquals(PulseState.OK, main.state)
        assertEquals(20, main.lastNo)
        // 带进下一章的那一句。
        assertEquals("推进「暗线」这条线（已经 16 章没动）", dark.request())
        // 九章没动：还不算冷。
        val nine = (1..13).map { n -> ch(n, threads = if (n == 4) "暗线" else "明线") }
        assertEquals(PulseState.OK, StoryPulse.check(nine, emptyList()).single { it.name == "暗线" }.state)
        // “本章改变了什么”里写到了也算推进。
        val mentioned = (1..20).map { n -> ch(n, threads = if (n == 4) "暗线" else "明线", change = if (n == 15) "林月知道了暗线那头的人是谁" else "") }
        assertEquals(15, StoryPulse.check(mentioned, emptyList()).single { it.name == "暗线" }.lastNo)
    }

    @Test
    fun aThreadListedButNeverChangedIsStalled() {
        // 最后三章都挂着“灯会旧案”，“本章改变了什么”都写了，却都在说别的事。
        val chapters = (1..8).map { n ->
            ch(n, threads = "灯会旧案", change = if (n >= 6) "杜娘和顾长风吵翻了，第${n}章" else "")
        }
        val row = StoryPulse.check(chapters, emptyList()).single { it.name == "灯会旧案" }
        assertEquals(PulseState.STALLED, row.state)
        assertTrue(row.note.contains("最近 3 章"))
        assertTrue(row.request().contains("写出实在的变化"))
        // 其中一章写到了它：不算原地踏步。
        val moved = chapters.mapIndexed { i, c -> if (i == 6) c.copy(change = "林月查清了灯会旧案的一角") else c }
        assertEquals(PulseState.OK, StoryPulse.check(moved, emptyList()).single().state)
        // 旧章没写“本章改变了什么”：不知道变没变，不报。
        val old = (1..8).map { n -> ch(n, threads = "灯会旧案") }
        assertEquals(PulseState.OK, StoryPulse.check(old, emptyList()).single().state)
        // 只挂了两章：不到三章不报。
        val two = (1..8).map { n -> ch(n, threads = if (n >= 7) "灯会旧案" else "别的线", change = "杜娘和顾长风吵翻了") }
        assertEquals(PulseState.OK, StoryPulse.check(two, emptyList()).single { it.name == "灯会旧案" }.state)
    }

    @Test
    fun foreshadowsGoColdUnlessDeferredAndCountTheirMoves() {
        val ledger = listOf(
            LedgerEntry(id = "p1", kind = LedgerKind.PROMISE, text = "老周把半本账册藏了起来", from = 2),
            LedgerEntry(id = "p2", kind = LedgerKind.PROMISE, text = "顾长风的真实来历", from = 3),
            LedgerEntry(id = "p3", kind = LedgerKind.PROMISE, text = "铜灯底座的记号", from = 4),
            // 收了的、只有作者知道的：不列。
            LedgerEntry(id = "p4", kind = LedgerKind.PROMISE, text = "杜娘的旧伤", from = 2, done = true, closed = 10),
            LedgerEntry(id = "p5", kind = LedgerKind.PROMISE, text = "沈默其实是卧底", from = 2, secret = true),
        )
        val chapters = (1..40).map { n ->
            ch(n, moves = when (n) {
                20 -> listOf(ForeshadowMove(MoveAction.DEFER, "顾长风的来历先放着", "p2"))
                30 -> listOf(ForeshadowMove(MoveAction.ADVANCE, "铜灯底座的记号又出现了", "p3"))
                else -> emptyList()
            })
        }
        val rows = StoryPulse.check(chapters, ledger).filter { it.kind == PulseKind.FORESHADOW }
        assertEquals(setOf("老周把半本账册藏了起来", "顾长风的真实来历", "铜灯底座的记号"), rows.map { it.name }.toSet())
        val book = rows.single { it.name.startsWith("老周") }
        assertEquals(PulseState.COLD, book.state)
        assertEquals(2, book.lastNo)
        assertEquals(PulseState.DEFERRED, rows.single { it.name.startsWith("顾长风") }.state)
        val lamp = rows.single { it.name.startsWith("铜灯") }
        assertEquals(PulseState.OK, lamp.state)
        assertEquals(30, lamp.lastNo)
        // 细纲卡里自由文字埋下的、账本里没有的：也列，后面说的像它的算同一条。
        val free = (1..40).map { n ->
            ch(n, moves = when (n) {
                3 -> listOf(ForeshadowMove(MoveAction.PLANT, "河底沉着一口铁箱"))
                else -> emptyList()
            })
        }
        val box = StoryPulse.check(free, emptyList()).single { it.kind == PulseKind.FORESHADOW }
        assertEquals(PulseState.COLD, box.state)
        assertEquals(3, box.lastNo)
    }

    @Test
    fun mainCharactersGoColdWhenTheLedgerStopsMentioningThem() {
        val ledger = (1..30).flatMap { n ->
            listOfNotNull(
                LedgerEntry(kind = LedgerKind.STATE, text = "在渡口第${n}天", subject = "林月", aspect = "位置", from = n),
                LedgerEntry(kind = LedgerKind.EVENT, text = "老周撑船第${n}次", subject = "老周", from = n).takeIf { n <= 10 },
                // 只出现两章的不算主要人物。
                LedgerEntry(kind = LedgerKind.EVENT, text = "杜娘来过", subject = "杜娘", from = n).takeIf { n == 5 || n == 6 },
            )
        }
        val rows = StoryPulse.check((1..30).map { ch(it) }, ledger).filter { it.kind == PulseKind.PERSON }
        assertEquals(setOf("林月", "老周"), rows.map { it.name }.toSet())
        assertEquals(PulseState.OK, rows.single { it.name == "林月" }.state)
        val zhou = rows.single { it.name == "老周" }
        assertEquals(PulseState.COLD, zhou.state)
        assertEquals(10, zhou.lastNo)
        assertEquals(20, zhou.idle)
        assertTrue(zhou.request().startsWith("让老周"))
    }

    @Test
    fun threadNamesComeFromTheThreadsField() {
        assertEquals(listOf("明线" to "林月查灯会旧案", "暗线" to "沈默的身世", "顾长风的弧线" to ""),
            StoryPulse.threadNames("明线：林月查灯会旧案；暗线：沈默的身世\n顾长风的弧线"))
        assertTrue(StoryPulse.threadNames("无").isEmpty())
        assertTrue(StoryPulse.check(emptyList(), emptyList()).isEmpty())
        assertNull(StoryPulse.check(listOf(ch(1)), emptyList()).firstOrNull())
        assertFalse(StoryPulse.check(listOf(ch(1, threads = "明线")), emptyList()).isEmpty())
    }
}
