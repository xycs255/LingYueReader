package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 编本章任务的内存：真机上编“AI 写整章草稿”的任务时内存溢出闪退。这里用一百二十万字上下的虚构设定包
 * （一份五十多万字、带“来源文件：”分节的合订本，加几十份普通文档）量改前（[legacyWritingTask]，fec434e 原样）和改后。
 */
class ChapterTaskMemoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val heap = ManagementFactory.getMemoryMXBean()

    /** 一次的数字：本线程分配了多少、用了多久、堆占用比开始时最多高出多少（每毫秒采样，含还没回收的垃圾）。 */
    private data class Cost(val allocMb: Long, val ms: Long, val peakMb: Long)

    private fun <T> measure(block: () -> T): Pair<T, Cost> {
        System.gc(); System.gc()
        val base = heap.heapMemoryUsage.used
        var peak = base
        val running = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) {
            while (running.get()) { peak = maxOf(peak, heap.heapMemoryUsage.used); Thread.sleep(1) }
        }
        val id = Thread.currentThread().id
        val a = mx.getThreadAllocatedBytes(id)
        val t = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - t) / 1_000_000
        val alloc = mx.getThreadAllocatedBytes(id) - a
        running.set(false)
        sampler.join()
        peak = maxOf(peak, heap.heapMemoryUsage.used)
        return result to Cost(alloc / 1_000_000, ms, (peak - base) / 1_000_000)
    }

    @Test fun compilingWithABigPackNoLongerFloodsMemory() {
        val store = WorkStore(tmp.newFolder())
        val (workId, chapterId) = LargePack.build(store)
        val total = LargePack.totalChars(store, workId)
        assertTrue("设定包 $total 字", total in 1_000_000..1_500_000)
        assertTrue(store.pack(workId).any { it.title == "设定合订本" && store.packText(workId, it.id).length > 500_000 })
        // 先各热一遍（类加载、正则编译），只比稳定以后的数字。
        store.legacyWritingTask(workId, chapterId)
        val (old, before) = measure { store.legacyWritingTask(workId, chapterId)!! }
        store.packCache.clear()
        val (cold, first) = measure { store.writingTask(workId, chapterId)!! }
        val (warm, again) = measure { store.writingTask(workId, chapterId)!! }
        val (other, changed) = measure { store.writingTask(workId, chapterId, "对白更生活一些", "3000—4000字")!! }
        println("编本章任务（设定包 $total 字、${store.pack(workId).size} 份文档）：")
        println("  改前每次：分配 ${before.allocMb} MB，${before.ms} 毫秒，堆峰值 +${before.peakMb} MB")
        println("  改后第一次（读设定包、解析）：分配 ${first.allocMb} MB，${first.ms} 毫秒，堆峰值 +${first.peakMb} MB")
        println("  改后再编（设定包没变）：分配 ${again.allocMb} MB，${again.ms} 毫秒，堆峰值 +${again.peakMb} MB")
        println("  改后换了要求、篇幅：分配 ${changed.allocMb} MB，${changed.ms} 毫秒，堆峰值 +${changed.peakMb} MB")
        // 编出来的一模一样，包括指纹。
        assertEquals(old, cold)
        assertEquals(old, warm)
        assertEquals(store.legacyWritingTask(workId, chapterId, "对白更生活一些", "3000—4000字"), other)
        assertTrue("改前 ${before.allocMb} MB", before.allocMb > 400)
        // 第一次要读设定包、拆合订本、挑本章资料（挑资料本身的算法没动），之后设定包不变就不再算。
        assertTrue("改后第一次 ${first.allocMb} MB，改前 ${before.allocMb} MB", first.allocMb < 350 && first.allocMb * 2 < before.allocMb)
        assertTrue("改后再编 ${again.allocMb} MB", again.allocMb < 25)
        assertTrue("换了要求 ${changed.allocMb} MB", changed.allocMb < 25)
        assertTrue("改后再编堆峰值 ${again.peakMb} MB", again.peakMb < 60)
        assertEquals(1, store.packCache.reads.get())
    }

    @Test fun theSameInputCompilesTheSameTaskAsBefore() {
        val store = WorkStore(tmp.newFolder())
        val (workId, chapterId) = LargePack.build(store, finals = 3)
        val chapters = store.get(workId)!!.chapters
        for (c in chapters) for ((ask, length) in listOf("" to "", "本次不少于6500汉字" to "", "" to "7200—8400字", "对白更生活一些" to "依细纲")) {
            assertEquals("${c.title} $ask $length", store.legacyWritingTask(workId, c.id, ask, length), store.writingTask(workId, c.id, ask, length))
        }
        // 小设定包（测试用的项目包）也一样。
        val small = store.create("小包")
        listOf("工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION, "章节审读提示词" to PackKind.INSTRUCTION,
            "第一卷细纲" to PackKind.VOLUME, "人物档案" to PackKind.CHARACTER, "检查规则" to PackKind.RULES)
            .forEach { (name, kind) -> store.addPackDoc(small.id, kind, name, LargePack.res("pack/$name.md")) }
        store.renameChapter(small.id, small.chapters[0].id, "第1章 雾渡")
        assertEquals(store.legacyWritingTask(small.id, small.chapters[0].id), store.writingTask(small.id, small.chapters[0].id))
        // 没有设定包、没有这一章。
        val empty = store.create("空")
        assertEquals(store.legacyWritingTask(empty.id, empty.chapters[0].id), store.writingTask(empty.id, empty.chapters[0].id))
        assertNull(store.writingTask(empty.id, "没有这一章"))
    }

    @Test fun theCacheIsReusedUntilThePackChanges() {
        val dir = tmp.newFolder()
        val store = WorkStore(dir)
        val (workId, chapterId) = LargePack.build(store, finals = 2)
        val first = store.writingTask(workId, chapterId)!!
        val sources = store.packSources(workId)
        assertSame(sources, store.packSources(workId))
        store.writingTask(workId, chapterId, "对白更生活一些")
        store.outlineEntries(workId)
        store.brief(workId, chapterId, prose = true)
        assertEquals("设定包没变：只读一次", 1, store.packCache.reads.get())

        // 改正文：失效重算，结果跟着变。
        val rules = store.pack(workId).first { it.kind == PackKind.STYLE }
        store.savePackText(workId, rules.id, store.packText(workId, rules.id) + "\n对白里不用书面语。")
        val edited = store.writingTask(workId, chapterId)!!
        assertEquals(2, store.packCache.reads.get())
        assertNotEquals(first.fingerprint, edited.fingerprint)
        assertEquals(store.legacyWritingTask(workId, chapterId), edited)

        // 改标题、类别：也算改了。
        store.updatePackDoc(workId, rules.id) { it.copy(title = "写作规范（现行）") }
        assertEquals(store.legacyWritingTask(workId, chapterId), store.writingTask(workId, chapterId))
        assertEquals(3, store.packCache.reads.get())

        // 新加、删掉文档。
        val added = store.addPackDoc(workId, PackKind.CANON, "渡口规矩", "卯时开渡，酉时收船。")
        assertEquals(store.legacyWritingTask(workId, chapterId), store.writingTask(workId, chapterId))
        store.deletePackDoc(workId, added.id)
        assertEquals(store.legacyWritingTask(workId, chapterId), store.writingTask(workId, chapterId))
        assertEquals(5, store.packCache.reads.get())

        // 另一个 WorkStore（另一个窗口）改了同一目录：这边也要看得出来。
        WorkStore(dir).savePackText(workId, rules.id, "只剩一句：对白要短。")
        val outside = store.writingTask(workId, chapterId)!!
        assertEquals(6, store.packCache.reads.get())
        assertEquals(store.legacyWritingTask(workId, chapterId), outside)

        // 不改设定包、只改细纲卡和作品：不重读设定包，但任务照样跟着变。
        store.saveCard(workId, chapterId, store.card(workId, chapterId).copy(hook = "船上灯灭了。"))
        val recarded = store.writingTask(workId, chapterId)!!
        assertEquals(6, store.packCache.reads.get())
        assertNotEquals(outside.fingerprint, recarded.fingerprint)
        assertEquals(store.legacyWritingTask(workId, chapterId), recarded)
    }

    @Test fun onlyTheCurrentWorkIsKept() {
        val store = WorkStore(tmp.newFolder())
        val (a, chapterA) = LargePack.build(store, finals = 1)
        val b = store.create("另一部")
        store.addPackDoc(b.id, PackKind.CANON, "世界设定", "雾河两岸。")
        store.writingTask(a, chapterA)
        val version = store.packSources(a).version
        store.writingTask(b.id, b.chapters[0].id)
        assertNull("换了作品，旧的那份不留", store.packCache.cached(a, version))
        assertNotNull(store.packCache.cached(b.id, store.packSources(b.id).version))
    }

    /** 只编一份文档的各种写法（换行、BOM、半角冒号、没有标题行、正文里引用“来源文件”），筛出来的和改前一样。 */
    @Test fun splittingCompilationsMatchesTheOldScanner() {
        val bodies = listOf("林月在渡口等船。", "## 规则\n卯时开渡。", "【参考样章】\n沈默推门。", "正文创作提示词：写短句。")
        fun bundle(sep: String, colon: String, titles: Boolean, bom: Boolean) = (if (bom) "\uFEFF" else "") + bodies.mapIndexed { i, b ->
            (if (titles) "═════════$sep【${i + 1}】第${i + 1}份$sep" else "") + "来源文件$colon 资料/第${i + 1}份.md$sep════════$sep$sep$b$sep"
        }.joinToString(sep)
        val cases = mutableListOf<Pair<PackDoc, String>>()
        for (sep in listOf("\n", "\r\n", "\r")) for (colon in listOf("：", ":")) for (titles in listOf(true, false)) for (bom in listOf(false, true))
            for (title in listOf("项目合订本", "随笔")) cases += PackDoc("d${cases.size}", PackKind.REFERENCE, title) to bundle(sep, colon, titles, bom)
        cases += PackDoc("q", PackKind.CANON, "引文") to "他说：来源文件：在柜子里。\n她说：来源文件：烧了。"
        cases += PackDoc("p", PackKind.CANON, "普通") to "没有来源行的普通设定。\n第二行。"
        cases += PackDoc("o", PackKind.VOLUME, "第一卷细纲") to "### 第1章 雾渡\n事实：林月到渡口。"
        for ((doc, text) in cases) {
            assertEquals(doc.title + text.take(30), LegacyProjectSources.pieces(doc, text), ProjectSources.pieces(doc, text))
        }
        for (phase in WritingPhase.entries) assertEquals(LegacyProjectSources.forPhase(cases, phase), ProjectSources.forPhase(cases, phase))
        assertEquals(LegacyProjectSources.sourceWarnings(cases), ProjectSources.sourceWarnings(cases))
        assertEquals(LegacyProjectSources.fingerprintSources(cases), ProjectSources.fingerprintSources(cases))
        assertEquals(LegacyProjectSources.hasProject(cases), ProjectSources.hasProject(cases))
        // 没有“来源文件”的大文档：原样返回同一个字符串，不拆行。
        val plain = "林月在渡口等船。\n".repeat(50_000)
        val id = Thread.currentThread().id
        val a = mx.getThreadAllocatedBytes(id)
        val split = ProjectSources.pieces(PackDoc("big", PackKind.CANON, "大设定"), plain)
        assertTrue("不拆行：分配 ${mx.getThreadAllocatedBytes(id) - a} 字节", mx.getThreadAllocatedBytes(id) - a < 100_000)
        assertSame(plain, split.single().second)
    }

    @Test fun streamedFingerprintEqualsJoiningEverything() {
        fun old(parts: List<String>) = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
        val emoji = "🌊"
        val long = "雾河".repeat(5_000) + emoji
        val cases = listOf(
            emptyList(), listOf(""), listOf("", ""), listOf("林月", "沈默"), listOf(long, long),
            // 代理对正好跨在八千字的分段上，以及孤立的代理字符（照 toByteArray 换成“?”）。
            listOf("a".repeat(8_191) + emoji + "b"), listOf("a".repeat(8_190) + emoji),
            listOf("x\uD83C", "\uDF0Ay"), listOf("孤\uD83C"), listOf("\uDF0A孤"), listOf("a".repeat(8_191) + "\uD83C"),
        )
        for (parts in cases) assertEquals(parts.toString().take(40), old(parts), sha256Joined(parts.asSequence()))
    }

    @Test fun cheapChecksSkipDocumentsWithoutSourceLines() {
        assertFalse(ProjectSources.compilation("没有来源行。\n".repeat(1_000)))
        assertFalse(ProjectSources.compilation("来源文件：一份.md\n正文"))
        assertTrue(ProjectSources.compilation("来源文件：一份.md\n正文\n来源文件：两份.md\n正文"))
    }
}
