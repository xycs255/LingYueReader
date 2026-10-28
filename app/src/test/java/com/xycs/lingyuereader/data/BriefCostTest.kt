package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.management.ManagementFactory
import java.security.MessageDigest

/**
 * 写正文时每次请求挑本章资料的开销：以前本章资料的缓存键带着整章正文，改一个字就要重挑；挑一次要把整份设定包重新切块、
 * 每块建字符串二元组集合；节选写作规范为了拿出场人物又另挑一整份。用一百二十万字上下的虚构设定包（[LargePack]）量。
 * 改前（57585c7）：一次请求（写正文的资料 + 出场人物）分配约 310 MB、400–520 毫秒。
 */
class BriefCostTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** 改前挑出来的本章资料（渲染后的文字取 SHA-256 前 16 位）和出场人物，逐条对：结果一字不差。 */
    private val before = """
第3章 雾渡3|0|true|null 31841c9da24e212c [老周, 林月, 沈默, 白沙渡, 漕帮, 杜娘, 顾长风]
第3章 雾渡3|0|true|6000 9d64b7ddcb8aa12b [老周, 林月, 沈默, 白沙渡, 漕帮, 杜娘, 顾长风]
第3章 雾渡3|0|false|null 2b6ca8537b759245 [老周, 林月, 沈默, 白沙渡, 漕帮, 杜娘, 顾长风]
第3章 雾渡3|0|false|6000 15566ffe123d02b4 [老周, 林月, 沈默, 白沙渡, 漕帮, 杜娘, 顾长风]
第3章 雾渡3|1038|true|null 6557bef64a31c1e4 [林月, 沈默, 老周, 杜娘, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|1038|true|6000 c17575eb6d809cbc [林月, 沈默, 老周, 杜娘, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|1038|false|null e8c7d021e12dcfd4 [林月, 沈默, 老周, 杜娘, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|1038|false|6000 44188d2d9c607ed3 [林月, 沈默, 老周, 杜娘, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|805|true|null 813fa3661e325a02 [老周, 林月, 杜娘, 沈默, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|805|true|6000 e27c6dec8bc33a19 [老周, 林月, 杜娘, 沈默, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|805|false|null b33414bd746ec186 [老周, 林月, 杜娘, 沈默, 白沙渡, 顾长风, 漕帮, 刘三]
第3章 雾渡3|805|false|6000 3bed0288e68e208c [老周, 林月, 杜娘, 沈默, 白沙渡, 顾长风, 漕帮, 刘三]
第25章 雾渡25|0|true|null 3f3232849b858160 [林月, 杜娘, 沈默, 老周, 顾长风]
第25章 雾渡25|0|true|6000 5e7932acd3125994 [林月, 杜娘, 沈默, 老周, 顾长风]
第25章 雾渡25|0|false|null 8714f18d28c979aa [林月, 老周, 杜娘]
第25章 雾渡25|0|false|6000 1e4617385d9f1463 [林月, 老周, 杜娘]
第25章 雾渡25|1038|true|null 7e05620127f0e919 [林月, 杜娘, 沈默, 老周, 顾长风, 白沙渡, 漕帮, 刘三]
第25章 雾渡25|1038|true|6000 de1ae6053543e73e [林月, 杜娘, 沈默, 老周, 顾长风, 白沙渡, 漕帮, 刘三]
第25章 雾渡25|1038|false|null b1bfab60861b0509 [林月, 杜娘, 沈默, 老周, 顾长风, 白沙渡, 漕帮, 刘三]
第25章 雾渡25|1038|false|6000 40bda20c03306630 [林月, 杜娘, 沈默, 老周, 顾长风, 白沙渡, 漕帮, 刘三]
第25章 雾渡25|805|true|null 25417a0d23f012af [杜娘, 林月, 沈默, 老周, 顾长风, 刘三, 白沙渡]
第25章 雾渡25|805|true|6000 040695fb3eab55d4 [杜娘, 林月, 沈默, 老周, 顾长风, 刘三, 白沙渡]
第25章 雾渡25|805|false|null 826c1f2d1cc8e171 [杜娘, 林月, 老周, 沈默, 顾长风, 刘三, 白沙渡]
第25章 雾渡25|805|false|6000 b399762caaa7c7a5 [杜娘, 林月, 老周, 沈默, 顾长风, 刘三, 白沙渡]
""".trim()

    @Test fun eachProseRequestNoLongerReselectsTheWholePack() {
        val store = WorkStore(tmp.newFolder())
        val (workId, chapterId) = LargePack.build(store)
        val body = LargePack.res("ch3_beats.txt")
        // 先热一遍（类加载、正则编译、设定包这一版的切块）。
        store.brief(workId, chapterId, body + "热", 6000, prose = true)
        store.briefPresent(workId, chapterId, body + "热")
        val costs = (0 until 3).map { r ->
            // 每次请求正文都不一样（作者又写了几个字）。
            val text = body + "林月又看了一眼$r"
            System.gc()
            val id = Thread.currentThread().id
            val a = mx.getThreadAllocatedBytes(id)
            val t = System.nanoTime()
            // 写正文的一次请求：本章资料（prose）加节选写作规范要的出场人物。
            store.brief(workId, chapterId, text, 6000, prose = true)
            store.briefPresent(workId, chapterId, text)
            ((mx.getThreadAllocatedBytes(id) - a) / 1_000_000) to ((System.nanoTime() - t) / 1_000_000)
        }
        println("写正文一次请求挑资料（设定包 ${LargePack.totalChars(store, workId)} 字）：改前约 310 MB、400–520 毫秒；改后 " +
            costs.joinToString("；") { "${it.first} MB、${it.second} 毫秒" })
        val alloc = costs.minOf { it.first }
        assertTrue("改后一次请求分配 $alloc MB", alloc < 40)
        assertTrue("改后一次请求 ${costs.minOf { it.second }} 毫秒", costs.minOf { it.second } < 300)
    }

    @Test fun theSameMaterialAsBefore() {
        val store = WorkStore(tmp.newFolder())
        val (workId, _) = LargePack.build(store)
        val body = LargePack.res("ch3_beats.txt")
        val chapters = store.get(workId)!!.chapters
        val out = mutableListOf<String>()
        for (c in listOf(chapters[2], chapters.last())) for (text in listOf("", body, body.take(800) + "沈默和杜娘")) for (prose in listOf(true, false)) for (limit in listOf(null, 6000)) {
            val b = store.brief(workId, c.id, text, limit, prose = prose)!!
            out += "${c.title}|${text.length}|$prose|$limit ${sha(b.render(prose = prose).orEmpty())} ${b.present}"
            // 节选写作规范用的出场人物和不写正文时挑资料认的一样。
            if (!prose) assertEquals(b.present, store.briefPresent(workId, c.id, text))
        }
        assertEquals(before, out.joinToString("\n"))
    }

    @Test fun editingTextThatDoesNotMatterReusesTheMaterial() {
        val store = WorkStore(tmp.newFolder())
        val (workId, chapterId) = LargePack.build(store, finals = 2)
        val head = "林月推开门。".repeat(400)
        val tail = "沈默在船上等她。".repeat(200)
        val first = store.brief(workId, chapterId, head + tail, prose = true)
        // 改的是开头（不在结尾那段、没改人名出现的次数）：同一份。
        assertSame(first, store.brief(workId, chapterId, head.replaceFirst("推开门", "推开窗") + tail, prose = true))
        // 多写了一次人名：出场次数变了，重挑。
        val more = store.brief(workId, chapterId, head + "老周" + tail, prose = true)
        assertTrue(more !== first)
    }
}
