package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.data.ChapterMove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/** 规划树移动后的“撤销”提示：连拖两次只留最新一次的提示，点的“撤销”撤的就是最近这一次；提示文字说清目录位置和章号。 */
class UndoTipsTest {
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "等不到：$what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aNewTipClosesTheOldOneSoUndoAlwaysMeansTheLatestMove() {
        val host = SnackbarHostState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tips = UndoTips(host)
        val undone = mutableListOf<String>()
        fun newest() = runBlocking { host.newestSnackbarData()?.visuals?.message }
        fun oldest() = runBlocking { host.oldestSnackbarData()?.visuals?.message }
        try {
            tips.show(scope, "已把《雪夜》移到第 3 章") { synchronized(undone) { undone += "雪夜" } }
            waitFor("第一条提示") { newest() == "已把《雪夜》移到第 3 章" }
            tips.show(scope, "已把《渡口》移到第 5 章") { synchronized(undone) { undone += "渡口" } }
            // 旧的关掉了：屏幕上只剩最新的一条。
            waitFor("只剩第二条") { newest() == "已把《渡口》移到第 5 章" && oldest() == "已把《渡口》移到第 5 章" }
            runBlocking { host.newestSnackbarData()!!.performAction() }
            waitFor("撤销") { synchronized(undone) { undone.isNotEmpty() } }
            Thread.sleep(100)
            assertEquals(listOf("渡口"), synchronized(undone) { undone.toList() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theTipSaysTheCatalogPlaceWhenNumbersArePinned() {
        val plain = ChapterMove("a", "雪夜", emptyList(), emptyList(), position = 3)
        assertEquals("已把《雪夜》移到第 3 章", moveTip(plain))
        // 固定了章号：这一章按设定仍是第 1 章，提示说的是目录位置。
        val pinned = ChapterMove("a", "雪夜", emptyList(), emptyList(), mapOf("a" to "1", "b" to "2"), position = 4)
        val tip = moveTip(pinned)
        assertEquals("已把《雪夜》移到目录第 4 个，章号仍按第 1 章（2 章固定了细纲章号）", tip)
        assertFalse("移到第 4 章" in tip)
        // 原来写着“楔子”的，章号从“第N章（楔子）”里读。
        val named = ChapterMove("a", "渡口", emptyList(), emptyList(), mapOf("a" to "第2章（楔子）"), position = 1)
        assertTrue("章号仍按第 2 章" in moveTip(named))
    }
}
