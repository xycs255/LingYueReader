package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 整理入账时“像不像同一条”的比对：以前每条提议都和账本里同类的每一条比，每比一次都跑两遍正则、把人名表扫一遍，
 * 是提议数 × 账本条数 × 人名数。长篇写到后期（几千条账本、几百个人名），整理一章要卡几秒。
 * 这里用 40 条提议 × 3000 条账本、200 个人名量；挑出来的结果（丢掉哪些、标哪条可能重复、哪些改成冲突）和改前一样。
 * 改前（57585c7）：这组数据在桌面 JVM 上约 390–640 毫秒（审查时另一组数据量过 2.2–3.3 秒），手机上还要慢几倍。
 */
class SyncPlanCostTest {
    private val surnames = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许"
    private val given = "一二三四五六七八九十"
    private val names = surnames.flatMap { s -> given.map { g -> "$s$g" } }
    private val places = listOf("渡口", "茶馆", "白沙渡", "青石镇", "码头", "药铺", "城门", "河边")
    private val deeds = listOf("丢了", "找到", "藏起", "送来", "拿走", "烧了", "换走", "看见")
    private val things = listOf("账本", "玉佩", "灯笼", "药方", "腰牌", "信", "船票", "银子")

    private fun line(r: Random, numbered: Boolean): String {
        val who = names[r.nextInt(names.size)]
        val n = if (numbered) "${r.nextInt(2, 40)}两" else ""
        return "$who 在${places[r.nextInt(places.size)]}${deeds[r.nextInt(deeds.size)]}$n${things[r.nextInt(things.size)]}"
    }

    private fun ledger(): List<LedgerEntry> {
        val r = Random(42)
        return (0 until 3_000).map { i ->
            val kind = when (i % 10) { 7 -> LedgerKind.PROMISE; 8 -> LedgerKind.KNOW; 9 -> LedgerKind.TIME; else -> LedgerKind.EVENT }
            val text = line(r, i % 3 == 0)
            LedgerEntry(id = "e$i", kind = kind, text = text, subject = if (kind == LedgerKind.KNOW) names[i % names.size] else "",
                from = 1 + i % 60, origin = LedgerOrigin.SYNC)
        }
    }

    private fun proposals(ledger: List<LedgerEntry>): List<SyncItem> {
        val r = Random(7)
        return (0 until 40).map { i ->
            val group = when (i % 10) { 7 -> SyncGroup.PROMISE; 8 -> SyncGroup.TIME; 9 -> SyncGroup.AWARENESS; else -> SyncGroup.EVENT }
            // 有的照抄账本（应当丢掉），有的改了一两个字（可能重复），其余是新的。
            val base = ledger[r.nextInt(ledger.size)]
            val text = when (i % 3) {
                0 -> base.text
                1 -> base.text.replace(" 在", "又在")
                else -> line(r, i % 4 == 0)
            }
            val who = names[r.nextInt(names.size)]
            if (group == SyncGroup.AWARENESS) {
                SyncItem(group, "$who：$text", "原句", PackKind.AWARENESS, name = who,
                    fields = listOf("人物" to who, "知道什么" to text, "从第几章起" to "第61章", "消息从哪来" to ""), chapterId = "c", chapter = 61)
            } else {
                val kind = when (group) { SyncGroup.EVENT -> LedgerKind.EVENT; SyncGroup.PROMISE -> LedgerKind.PROMISE; else -> LedgerKind.TIME }
                SyncItem(group, text, "原句", PackKind.CANON, text = text, chapterId = "c", chapter = 61,
                    entry = LedgerEntry(id = "p$i", kind = kind, text = text, from = if (kind == LedgerKind.TIME) 1 + i % 60 else 61, origin = LedgerOrigin.SYNC))
            }
        }
    }

    private fun docs() = listOf(
        PackDoc("c", PackKind.CHARACTER, "人物档案") to "| 人物 | 身份 |\n| --- | --- |\n" + names.joinToString("") { "| $it | 渡口的人 |\n" },
    )

    private fun signature(planned: List<SyncItem>): String = planned.joinToString("\n") { p ->
        listOf(p.group, p.title, p.place, p.maybeSame?.id, p.reveals?.id, p.entry?.kind, p.entry?.from, p.text).joinToString("|")
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Test
    fun fortyProposalsAgainstThreeThousandEntries() {
        val ledger = ledger()
        val items = proposals(ledger)
        val docs = docs()
        PackSync.plan(items.take(4), docs, ledger)
        val times = (0 until 3).map {
            val t = System.nanoTime()
            val planned = PackSync.plan(items, docs, ledger)
            ((System.nanoTime() - t) / 1_000_000) to planned
        }
        val planned = times.first().second
        println("整理入账查重（40 条提议 × 3000 条账本、${names.size} 个人名）：改前约 390–640 毫秒；改后 " + times.joinToString("、") { "${it.first} 毫秒" })
        // 结果和改前一样：丢掉的、标“可能重复”的、改成冲突的都对得上（改前跑出来的摘要）。
        assertEquals(BEFORE, "${planned.size} ${planned.count { it.maybeSame != null }} ${sha(signature(planned))}")
        assertTrue("改后 ${times.minOf { it.first }} 毫秒", times.minOf { it.first } < 150)
    }

    companion object {
        private const val BEFORE = "30 8 f9e4c92f588917e7"
    }
}
