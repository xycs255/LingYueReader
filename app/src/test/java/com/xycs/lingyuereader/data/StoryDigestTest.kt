package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 分层前情（故事至今、卷摘要）：拼装顺序、防剧透、字数不比只用章摘要多（200 章、5 卷的《渡灯》）、什么时候该整理、
 * 怎么读 AI 回的 JSON、存盘和备份、旧数据、删卷。人名、文字都是造的。
 */
class StoryDigestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val people = """
        # 人物档案

        | 人物 | 身份 |
        |---|---|
        | 林月 | 药铺女儿 |
        | 沈默 | 外乡人 |
        | 老周 | 撑船的 |
        | 杜娘 | 茶馆老板 |
        | 顾长风 | 镖师 |
    """.trimIndent()
    private val docs = listOf(PackDoc("c", PackKind.CHARACTER, "人物档案", 3, false) to people)
    private val names = listOf("林月", "沈默", "老周", "杜娘", "顾长风")

    /** 一章的摘要：约两百字，轮流写到几个人，写得像真的章摘要。 */
    private fun summaryOf(n: Int): String {
        val a = names[n % names.size]
        val b = names[(n + 2) % names.size]
        return "第${n}章里，${a}在渡灯镇的河埠头等船，${b}带来北岸的消息：灯会上丢的那盏铜灯在旧仓里找到了，灯座下刻着一个陌生的记号。" +
            "${a}不信，连夜去旧仓查看，发现记号和三年前那桩沉船案的账册对得上。两人在仓门口吵了一架，${b}说这事最好别再碰，" +
            "${a}却决定第二天去找撑船的问清楚。章末，仓顶传来脚步声，有人在暗处看着他们。"
    }

    /** 200 章、5 卷的作品：每卷 40 章，章 id 是 c1…c200，卷 id 是 v1…v5。 */
    private fun bigWork(): Work = Work("w", "渡灯", volumes = (1..5).map { v ->
        Volume("v$v", "第${v}卷", ((v - 1) * 40 + 1..v * 40).map { WorkChapter("c$it", "第${it}章", words = 3_000, status = ChapterStatus.FINAL) })
    })

    private fun summaries(work: Work, upTo: Int) = work.volumes.flatMap { v -> v.chapters.map { it to v.id } }
        .filter { (c, _) -> c.id.drop(1).toInt() < upTo }
        .map { (c, vid) -> val n = c.id.drop(1).toInt(); ChapterSummary(n, c.title, summaryOf(n), vid) }

    private fun pastChars(b: ChapterBrief) = b.items.filter { it.group == "前情" }.sumOf { it.text.length }

    private val story = (1..20).joinToString("\n") { "第${it * 9}章前后，林月和沈默一步步查清了渡灯镇沉船案的一环，老周和杜娘各自藏着一段旧事。" }
    private fun volumeText(v: Int) = "第${v}卷：林月在渡灯镇追查铜灯记号，和沈默从互相提防到并肩查案；老周交出半本账册，杜娘的茶馆成了接头处；" +
        "顾长风押镖北上，留下沉船案的一条线索；卷末，旧仓失火，记号的主人第一次露面。".repeat(3)

    @Test
    fun layeredPastComesInOrderAndReplacesOlderChapterSummaries() {
        val work = bigWork()
        val chapter = 170
        val layers = StoryDigests.layers(work, "c$chapter", chapter, Digests(
            StoryDigest(story, "c160", 160),
            (1..5).associate { "v$it" to VolumeDigest(volumeText(it), emptyList()) },
        ))!!
        // 只带本章所在卷（第五卷）之前的卷；本卷的卷摘要不带。
        assertEquals(listOf("v1", "v2", "v3", "v4"), layers.volumes.map { it.volumeId })
        assertEquals(story, layers.story)
        val b = ChapterBriefs.build(docs, "林月推开仓门。", "林月和沈默去旧仓", chapter = chapter, volume = 5, summaries = summaries(work, chapter), layers = layers)
        val past = b.items.filter { it.group == "前情" }
        // 故事至今 → 往卷（近的先保字数，排的时候按卷的先后）→ 本卷最近几章 → 写到出场人物的旧章。
        assertEquals("故事至今", past.first().title)
        val volumes = past.filter { it.reason == ChapterBriefs.VOLUME_REASON }
        assertTrue(volumes.isNotEmpty())
        assertEquals(volumes.map { it.title }.sorted(), volumes.map { it.title })
        val recent = past.filter { it.reason == "最近几章" }
        assertEquals((165..169).toList().reversed(), recent.map { it.title.substringAfter("第 ").substringBefore(" 章").toInt() })
        assertTrue(past.indexOf(volumes.last()) < past.indexOf(recent.first()))
        // 往卷有卷摘要，往卷的章摘要不再单列。
        past.filter { it.reason.startsWith("写到") }.forEach { assertTrue(it.title.substringAfter("第 ").substringBefore(" 章").toInt() > 160) }
        // 章目录式的前情（拟细纲、能查设定包时）按同一个顺序排。
        val sorted = past.sortedBy { ChapterBriefs.pastOrder(it) }
        assertEquals("故事至今", sorted.first().title)
        assertEquals(ChapterBriefs.VOLUME_REASON, sorted[1].reason)
    }

    @Test
    fun nothingFromLaterChaptersLeaksIntoThePast() {
        val work = bigWork()
        val digests = Digests(StoryDigest(story, "c120", 120), (1..5).associate { "v$it" to VolumeDigest(volumeText(it), emptyList()) })
        // 回头写第 100 章：故事至今写到了第 120 章，不能用；第三卷（本卷）、后两卷的卷摘要也不能用。
        val back = StoryDigests.layers(work, "c100", 100, digests)!!
        assertEquals("", back.story)
        assertEquals(listOf("v1", "v2"), back.volumes.map { it.volumeId })
        // 写到的那章就是本章：也不能用（梗概里有本章的结果）。
        assertEquals("", StoryDigests.layers(work, "c120", 120, digests)!!.story)
        assertEquals(story, StoryDigests.layers(work, "c121", 121, digests)!!.story)
        // 写到的那章被删了：按记下的章号比。
        val gone = work.copy(volumes = work.volumes.map { v -> v.copy(chapters = v.chapters.filterNot { it.id == "c120" }) })
        assertEquals("", StoryDigests.layers(gone, "c119", 119, digests)!!.story)
        assertEquals(story, StoryDigests.layers(gone, "c121", 121, digests)!!.story)
        // 只认定稿（项目包流程）：卷摘要用到的章有一章不是定稿就不用这一卷。
        val draft = work.copy(volumes = work.volumes.map { v -> v.copy(chapters = v.chapters.map { if (it.id == "c5") it.copy(status = ChapterStatus.DRAFT) else it }) })
        val confirmed = Digests(null, mapOf("v1" to VolumeDigest(volumeText(1), listOf("c4", "c5")), "v2" to VolumeDigest(volumeText(2), listOf("c41"))))
        assertEquals(listOf("v2"), StoryDigests.layers(draft, "c100", 100, confirmed, confirmedOnly = true)!!.volumes.map { it.volumeId })
        assertEquals(listOf("v1", "v2"), StoryDigests.layers(draft, "c100", 100, confirmed)!!.volumes.map { it.volumeId })
        // 第一卷的章：前面没有卷，也没有故事至今。
        assertNull(StoryDigests.layers(work, "c3", 3, Digests(null, mapOf("v1" to VolumeDigest("x")))))
    }

    /**
     * 200 章、5 卷：改前（只用章摘要）和改后（分层）的前情字数。改后不能比改前多。
     * 结果写进测试输出，提交说明里引用。
     */
    @Test
    fun theLayeredPastIsNoLongerThanChapterSummariesAlone() {
        val work = bigWork()
        val digests = Digests(StoryDigest(story, "c199", 199), (1..5).associate { "v$it" to VolumeDigest(volumeText(it), emptyList()) })
        val report = StringBuilder()
        listOf(41, 120, 200).forEach { chapter ->
            val sums = summaries(work, chapter)
            val volume = (chapter - 1) / 40 + 1
            val before = ChapterBriefs.build(docs, "林月推开仓门，老周跟在后面。", "林月和沈默去旧仓", chapter = chapter, volume = volume, summaries = sums)
            val layers = StoryDigests.layers(work, "c$chapter", chapter, digests.copy(story = StoryDigest(story, "c${chapter - 1}", chapter - 1)))
            val after = ChapterBriefs.build(docs, "林月推开仓门，老周跟在后面。", "林月和沈默去旧仓", chapter = chapter, volume = volume, summaries = sums, layers = layers)
            report.append("第 $chapter 章：只用章摘要 ${pastChars(before)} 字（${before.items.count { it.group == "前情" }} 条），")
                .append("分层 ${pastChars(after)} 字（${after.items.count { it.group == "前情" }} 条）\n")
            assertTrue("第 $chapter 章分层前情 ${pastChars(after)} 字，比只用章摘要的 ${pastChars(before)} 字多", pastChars(after) <= pastChars(before))
            // 改后的确用上了分层：有故事至今，往卷的章摘要不再单列。
            assertTrue(after.items.any { it.title == "故事至今" })
        }
        // 整份本章资料也不比以前长。
        val sums = summaries(work, 200)
        val before = ChapterBriefs.build(docs, "林月推开仓门。", "林月和沈默去旧仓", chapter = 200, volume = 5, summaries = sums).render()!!
        val after = ChapterBriefs.build(docs, "林月推开仓门。", "林月和沈默去旧仓", chapter = 200, volume = 5, summaries = sums,
            layers = StoryDigests.layers(work, "c200", 200, digests)).render()!!
        assertTrue(after.length <= before.length)
        report.append("第 200 章整份本章资料：改前 ${before.length} 字，改后 ${after.length} 字\n")
        println(report)
        File(System.getProperty("java.io.tmpdir"), "lingyue-layered-past.txt").writeText(report.toString())
    }

    @Test
    fun withoutLayersThePastIsExactlyAsBefore() {
        val work = bigWork()
        val sums = summaries(work, 50)
        val a = ChapterBriefs.build(docs, "林月推开仓门。", "", chapter = 50, volume = 2, summaries = sums)
        val b = ChapterBriefs.build(docs, "林月推开仓门。", "", chapter = 50, volume = 2, summaries = sums, layers = PastLayers())
        assertEquals(a.render(), b.render())
    }

    @Test
    fun theStartOfAVolumeStillCarriesTheLastChaptersOfThePreviousOne() {
        val work = bigWork()
        val layers = StoryDigests.layers(work, "c81", 81, Digests(null, mapOf("v2" to VolumeDigest(volumeText(2)))))!!
        val b = ChapterBriefs.build(docs, "林月推开仓门。", "", chapter = 81, volume = 3, summaries = summaries(work, 81), layers = layers)
        val recent = b.items.filter { it.reason == "最近几章" }.map { it.title.substringAfter("第 ").substringBefore(" 章").toInt() }
        assertEquals(listOf(80, 79), recent)
    }

    @Test
    fun theStoreBriefUsesTheLayers() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("渡灯")
        val v1 = w.volumes.single().id
        s.renameVolume(w.id, v1, "第一卷 灯会")
        val first = w.chapters.single().id
        val ids = mutableListOf(first)
        repeat(5) { ids += s.addChapter(w.id, v1)!!.second.id }
        val v2 = s.addVolume(w.id, "第二卷 旧仓")!!.volumes.last().id
        repeat(3) { ids += s.addChapter(w.id, v2)!!.second.id }
        ids.forEachIndexed { i, id -> s.saveCard(w.id, id, ChapterCard(summary = "第${i + 1}章：林月查到第${i + 1}个记号。")) }
        s.addPackDoc(w.id, PackKind.CHARACTER, "人物档案", people)
        s.updateDigests(w.id) { Digests(StoryDigest("林月一路查记号，查到了旧仓。", ids[6], 7), mapOf(v1 to VolumeDigest("第一卷里，林月在灯会上查出六个记号。"))) }
        val brief = s.brief(w.id, ids[8])!!
        val past = brief.items.filter { it.group == "前情" }
        assertEquals("故事至今", past.first().title)
        assertTrue(past.any { it.title == "第一卷 灯会（卷摘要）" })
        // 第一卷的章摘要被卷摘要替掉，只留卷首接得上的两章。
        assertFalse(past.any { "第3个记号" in it.text })
        assertTrue(past.any { "第8个记号" in it.text })
        // 写第一卷的章：没有往卷，故事至今写到第 7 章，也不用。
        assertTrue(s.brief(w.id, ids[3])!!.items.none { it.title == "故事至今" || it.reason == ChapterBriefs.VOLUME_REASON })
    }

    @Test
    fun theStoryWaitsForEnoughChaptersAndTheVolumeForTenSummariesOrItsEnd() {
        val work = bigWork()
        val sums = (1..200).associate { "c$it" to summaryOf(it) }
        // 前五章：最近几章本来就全带，不整理故事至今。
        val early = StoryDigests.plan(work, "c5", "第五章的摘要", sums.filterKeys { it.drop(1).toInt() < 5 }, Digests())
        assertFalse(early.story)
        val sixth = StoryDigests.plan(work, "c6", "第六章的摘要", sums.filterKeys { it.drop(1).toInt() < 6 }, Digests())
        assertTrue(sixth.story)
        assertEquals(6, sixth.newChapters.size)
        assertEquals("第六章的摘要", sixth.newChapters.last().second)
        // 已有故事至今：只并进上次写到的那章之后的（上次没成的这次一起补）。
        val old = Digests(StoryDigest("旧梗概。", "c30", 30))
        val next = StoryDigests.plan(work, "c33", "第三十三章", sums, old)
        assertEquals(listOf("第31章", "第32章", "第33章"), next.newChapters.map { it.first })
        assertEquals("旧梗概。", next.oldStory)
        // 回头重收旧章：不让梗概倒退。
        assertFalse(StoryDigests.plan(work, "c20", "第二十章", sums, old).story)
        // 作者改过的：不动。
        assertFalse(StoryDigests.plan(work, "c33", "第三十三章", sums, Digests(StoryDigest("我写的。", "c30", 30, edited = true))).story)
        // 卷摘要：一卷里有摘要的章到 10 章先汇总一次；之后每多 10 章再汇总；没到不汇总。
        val nine = StoryDigests.plan(work, "c9", "九", sums.filterKeys { it.drop(1).toInt() < 9 }, Digests())
        assertNull(nine.volumeId)
        val ten = StoryDigests.plan(work, "c10", "十", sums.filterKeys { it.drop(1).toInt() < 10 }, Digests())
        assertEquals("v1", ten.volumeId)
        assertEquals((1..10).map { "c$it" }, ten.volumeChapterIds)
        val done10 = Digests(volumes = mapOf("v1" to VolumeDigest("前十章。", (1..10).map { "c$it" })))
        assertNull(StoryDigests.plan(work, "c15", "十五", sums.filterKeys { it.drop(1).toInt() < 15 }, done10).volumeId)
        assertEquals("v1", StoryDigests.plan(work, "c20", "二十", sums.filterKeys { it.drop(1).toInt() < 20 }, done10).volumeId)
        // 一卷写完（每章都有摘要）：汇总；汇总过全卷的不再汇总。
        val last = StoryDigests.plan(work, "c40", "四十", sums, done10)
        assertEquals("v1", last.volumeId)
        assertEquals(40, last.volumeChapterIds.size)
        assertNull(StoryDigests.plan(work, "c40", "四十", sums, Digests(volumes = mapOf("v1" to VolumeDigest("全卷。", (1..40).map { "c$it" })))).volumeId)
        // 作者改过的卷摘要：不动。
        assertNull(StoryDigests.plan(work, "c40", "四十", sums, Digests(volumes = mapOf("v1" to VolumeDigest("我写的。", edited = true)))).volumeId)
        // 只有一两章的卷写完了也不汇总。
        val tiny = Work("w", "渡灯", volumes = listOf(Volume("v1", "第一卷", listOf(WorkChapter("a", "第一章"), WorkChapter("b", "第二章")))))
        assertNull(StoryDigests.plan(tiny, "b", "二", mapOf("a" to "一"), Digests()).volumeId)
    }

    @Test
    fun theAnswerIsReadStrictly() {
        val (s, v) = StoryDigests.parse("好的：\n```json\n{\"story\":[\"林月到了渡灯镇。\",\"沈默来了。\"],\"volume\":\"第一卷的事。\"}\n```", true, true)
        assertEquals("林月到了渡灯镇。\n沈默来了。", s)
        assertEquals("第一卷的事。", v)
        // 写成一整段的也认，按句号拆。
        assertEquals("林月到了。\n沈默走了。", StoryDigests.parse("{\"story\":\"林月到了。沈默走了。\"}", true, false).first)
        // 最多 20 句。
        val many = (1..30).joinToString(",") { "\"第${it}句。\"" }
        assertEquals(StoryDigests.STORY_SENTENCES, StoryDigests.parse("{\"story\":[$many]}", true, false).first!!.lines().size)
        // 读不出、要的没给：报错（收尾照常，下次再补）。
        listOf("不是 JSON" to (true to false), "{\"volume\":\"x\"}" to (true to false), "{\"story\":[\"a\"]}" to (true to true), "{\"story\":[]}" to (true to false)).forEach { (answer, want) ->
            try {
                StoryDigests.parse(answer, want.first, want.second)
                fail("应当读不出：$answer")
            } catch (_: StoryDigests.ParseError) {
            }
        }
    }

    @Test
    fun digestsAreStoredBackedUpAndClearedWithTheirVolume() {
        val dir = tmp.newFolder()
        val s = WorkStore(dir)
        val w = s.create("渡灯")
        val v1 = w.volumes.single().id
        val v2 = s.addVolume(w.id, "第二卷")!!.volumes.last().id
        assertTrue(s.digests(w.id).isEmpty)
        s.updateDigests(w.id) { Digests(StoryDigest("林月到了渡灯镇。", w.chapters[0].id, 1, edited = true, at = 5), mapOf(v1 to VolumeDigest("第一卷。", listOf(w.chapters[0].id)), v2 to VolumeDigest("第二卷。"))) }
        val saved = s.digests(w.id)
        assertTrue(saved.story!!.edited)
        assertEquals(listOf(w.chapters[0].id), saved.volumes.getValue(v1).chapters)
        // 不在了的卷不存。
        s.updateDigests(w.id) { it.copy(volumes = it.volumes + ("nope" to VolumeDigest("没有这卷。"))) }
        assertFalse("nope" in s.digests(w.id).volumes)
        // 随备份走。
        val backup = String(s.backup()!!)
        val other = WorkStore(tmp.newFolder())
        other.restoreBackup(backup)
        assertEquals(saved, other.digests(w.id))
        // 旧备份没有这一项：保留本机的。
        val old = JSONObject(backup).apply {
            getJSONArray("works").getJSONObject(0).apply { remove("digests"); put("updatedAt", optLong("updatedAt") + 60_000) }
        }.toString()
        other.updateDigests(w.id) { it.copy(story = StoryDigest("本机的。", "", 0)) }
        other.restoreBackup(old)
        assertEquals("本机的。", other.digests(w.id).story!!.text)
        // 删卷：卷摘要一起清掉。
        s.deleteVolume(w.id, v2)
        assertFalse(v2 in s.digests(w.id).volumes)
        assertTrue(v1 in s.digests(w.id).volumes)
        // 旧格式（缺字段）照样读；坏文件当作空的。
        assertEquals(Digests(StoryDigest("只有内容。")), StoryDigests.fromJson(JSONObject("{\"story\":{\"text\":\"只有内容。\"}}")))
        File(dir, "works/${w.id}/digests.json").writeText("{坏的")
        assertTrue(s.digests(w.id).isEmpty)
    }
}
