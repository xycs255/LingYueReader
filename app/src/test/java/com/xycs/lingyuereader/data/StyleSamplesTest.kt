package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.AiToolWire
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 文风范例：从定稿章里挑段（稳定、三类、字数上限、防剧透、不用这段、退回字数最多的章）和进提示词的位置。 */
class StyleSamplesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        /** 一段叙述描写（引号少、句子长）。 */
        val NARRATION = listOf(
            "渡口在镇子的东头，河面宽阔，早晨的雾气贴着水面缓缓移动，把对岸的柳树和石阶都揉成一片灰白。老周的船系在最外面那根木桩上，船身被多年的河水泡得发黑，船头挂着一盏没点的风灯，随着波浪轻轻摇晃。",
            "杜娘的茶棚搭在渡口旁边的土坡上，几张旧桌子摆得歪歪斜斜，炉子里的炭火一直没有熄，铜壶里的水总是将开未开，冒着细细的白汽，来往的客人在这里坐一坐，听她讲些镇上的闲话，再慢慢走下石阶去等船。",
            "镇上的人都说，这条河从前没有这么宽，是那年大水冲垮了上游的堤坝，河道才一点点往两边吃进来，吃掉了半条街的铺子和一座老庙，只剩下庙前那棵歪脖子槐树，至今还站在水边，每到春天照样开出一树白花。",
        )

        /** 对白多的一段。 */
        val DIALOGUE = listOf(
            "“你又来了。”林月放下手里的药篓，“这回要什么？”",
            "“一副治风寒的药。”沈默说，“给老周的，他昨夜淋了雨，咳了一宿，到天亮还没停下来。”",
            "“老周病了？”林月皱起眉头，“上个月才好，怎么又病了？你们几个大男人，就不知道替他挡一挡雨吗？”",
            "“不急，”沈默看着门外的雨，“我在这里坐一会儿，等雨小些再走。你慢慢配，别弄错了分量。”",
            "“我什么时候弄错过？”林月瞪了他一眼，“倒是你，上回的伤好了没有？给我看看。”",
        )

        /** 动作场面。 */
        val ACTION = listOf(
            "黑影从船篷后扑出来。沈默侧身一闪。刀光擦着耳边过去。他抬脚踹在那人膝上，那人踉跄着翻倒，又翻身跃起，挥刀直砍！沈默拔刀格挡，火星四溅。",
            "两人在窄窄的船板上缠斗，船身剧烈摇晃。他抓住对方手腕，猛地一拽，借势撞过去——那人跌进河里。又一个人从岸上冲下来。沈默推开老周，转身迎上去，一刀劈在对方刀背上！",
            "那人退了半步，又扑上来。沈默矮身闪过，反手一刀刺向他肋下。对方横刀去挡，被震得虎口发麻。沈默不等他站稳，抬腿踢中他手腕，刀脱手飞出，落进河里。那人转身就跑。沈默追了两步，停下了。",
        )

        fun chapterText(variant: String = "") =
            (NARRATION + DIALOGUE + ACTION).joinToString("\n") { it + variant }
    }

    private fun options(limit: Int = 1500, skip: Set<String> = emptySet(), shift: Map<String, Int> = emptyMap()) = SampleOptions(limit, skip, shift)

    private val sources = listOf(
        SampleChapter("c1", "第一章 渡口", chapterText()),
        SampleChapter("c2", "第二章 茶馆", chapterText("")),
    )

    @Test
    fun sameChaptersGiveSameSamplesAndCoverAllKinds() {
        val a = StyleSamples.pick(sources, options())
        val b = StyleSamples.pick(sources.map { it.copy() }, options())
        assertEquals(a, b)
        assertEquals(StyleSamples.render(a), StyleSamples.render(b))
        assertEquals(SampleKind.entries.toSet(), a.map { it.kind }.toSet())
        // 对白那段真是对白、动作那段真有动作。
        assertTrue(a.first { it.kind == SampleKind.DIALOGUE }.text.contains("“"))
        assertTrue(a.first { it.kind == SampleKind.ACTION }.text.contains("拔刀") || a.first { it.kind == SampleKind.ACTION }.text.contains("撞"))
        assertFalse(a.first { it.kind == SampleKind.NARRATION }.text.contains("“"))
    }

    @Test
    fun respectsLimitAndPieceSize() {
        for (limit in listOf(800, 1500, 3000)) {
            val picked = StyleSamples.pick(sources, options(limit))
            assertTrue("$limit：${picked.sumOf { it.text.length }}", picked.sumOf { it.text.length } <= limit)
            picked.forEach { assertTrue(it.text.length in StyleSamples.MIN..StyleSamples.MAX) }
            assertTrue(picked.isNotEmpty())
        }
        assertTrue(StyleSamples.pick(sources, options(3000)).size > StyleSamples.pick(sources, options(800)).size)
        assertTrue(StyleSamples.pick(sources, options(0)).isEmpty())
        // 本机模型带三分之一，不到一段就不带。
        assertEquals(500, StyleSamples.localLimit(1500))
        assertEquals(0, StyleSamples.localLimit(500))
    }

    @Test
    fun onlyEarlierFinalChaptersAndFallback() {
        val chapters = listOf(
            WorkChapter("c1", "第一章", 900, status = ChapterStatus.FINAL),
            WorkChapter("c2", "第二章", 3000, status = ChapterStatus.DRAFT),
            WorkChapter("c3", "第三章", 800, status = ChapterStatus.DRAFT),
            WorkChapter("c4", "第四章", 900, status = ChapterStatus.FINAL),
        )
        // 写第三章：只看它前面的，第四章的定稿不拿。
        val (s3, fb3) = StyleSamples.sources(chapters, 2, fallback = true)
        assertEquals(listOf("c1"), s3.map { it.id })
        assertFalse(fb3)
        // 写下一章（设置页预览）：前面的定稿都算。
        assertEquals(listOf("c1", "c4"), StyleSamples.sources(chapters, null, true).first.map { it.id })
        // 没有定稿章：退回字数最多的几章（按目录顺序），作者关了退回就不带。
        val drafts = chapters.map { it.copy(status = ChapterStatus.DRAFT) }
        val (fb, isFallback) = StyleSamples.sources(drafts, 3, fallback = true)
        assertTrue(isFallback)
        assertEquals(listOf("c1", "c2", "c3"), fb.map { it.id })
        assertTrue(StyleSamples.sources(drafts, 3, fallback = false).first.isEmpty())
        // 写第一章：前面什么都没有。
        assertTrue(StyleSamples.sources(chapters, 0, true).first.isEmpty())
    }

    @Test
    fun skippedSampleStaysSkippedAndShiftPicksAnother() {
        // 800 字：一类一段。
        fun opt(skip: Set<String> = emptySet(), shift: Map<String, Int> = emptyMap()) = options(800, skip, shift)
        val sources = sources + SampleChapter("c3", "第三章 夜雨", chapterText("雨"))
        val first = StyleSamples.pick(sources, opt())
        val dialogue = first.first { it.kind == SampleKind.DIALOGUE }
        val skipped = StyleSamples.pick(sources, opt(skip = setOf(dialogue.key)))
        assertTrue(skipped.none { it.key == dialogue.key })
        // 再挑一次还是不挑它（键只看章和文字）。
        assertTrue(StyleSamples.pick(sources, opt(skip = setOf(dialogue.key))).none { it.key == dialogue.key })
        val shifted = StyleSamples.pick(sources, opt(shift = mapOf(SampleKind.DIALOGUE.name to 1)))
        assertNotEquals(dialogue.key, shifted.first { it.kind == SampleKind.DIALOGUE }.key)

        // 存进作品的 AI 设置，重新读出来还在。
        val store = WorkStore(tmp.newFolder("w"))
        val w = store.create("渡口")
        store.updateAssist(w.id) { it.copy(sampleSkip = listOf(dialogue.key), sampleShift = mapOf("ACTION" to 2), sampleLimit = 800, sampleFallback = false) }
        val back = store.assist(w.id)
        assertEquals(listOf(dialogue.key), back.sampleSkip)
        assertEquals(mapOf("ACTION" to 2), back.sampleShift)
        assertEquals(800, back.sampleLimit)
        assertFalse(back.sampleFallback)
        // 旧的设置文件没有这几项：默认 1500 字、退回开着。
        assertEquals(StyleSamples.DEFAULT_LIMIT, WorkAssist().sampleLimit)
    }

    @Test
    fun cacheRoundTripAndSignature() {
        val picked = SamplePick(StyleSamples.pick(sources, options()), fallback = true, sources = 2)
        val raw = StyleSamples.encodeCache(listOf("sig" to picked))
        assertEquals(listOf("sig" to picked), StyleSamples.decodeCache(raw))
        assertTrue(StyleSamples.decodeCache("坏了").isEmpty())
        val c = listOf(WorkChapter("c1", "第一章", 900, 1L, ChapterStatus.FINAL))
        val sig = StyleSamples.signature(c, false, options())
        assertEquals(sig, StyleSamples.signature(c.map { it.copy() }, false, options()))
        assertNotEquals(sig, StyleSamples.signature(c.map { it.copy(updatedAt = 2L) }, false, options()))
        assertNotEquals(sig, StyleSamples.signature(c, false, options(skip = setOf("x"))))
    }

    @Test
    fun promptCarriesSamplesBetweenStyleAndChapterHints() {
        val samples = StyleSamples.render(StyleSamples.pick(sources, options()))!!
        val assist = WorkAssist(style = "第三人称，冷峻")
        val one = WritePrompts.system("渡口", assist, "本章提示：林月守渡口", examples = true, samples = samples)
        val two = WritePrompts.system("渡口", assist, "本章提示：林月守渡口", examples = true, samples = samples)
        assertEquals(one, two)
        val at = one.indexOf(StyleSamples.HEADER)
        assertTrue(at > one.indexOf("第三人称，冷峻"))
        assertTrue(at < one.indexOf("本章提示"))
        // 本章提示变了，范例以前的开头不变（服务商按相同开头缓存）。
        val other = WritePrompts.system("渡口", assist, "本章提示：沈默过河", examples = true, samples = samples)
        val end = at + samples.length
        assertEquals(one.substring(0, end), other.substring(0, end))
        assertFalse(WritePrompts.system("渡口", assist, "本章提示").contains(StyleSamples.HEADER))
        // 挑错不带；改稿、续写带。
        assertFalse(WritePrompts.usesSamples(WriteAction.PROOFREAD))
        listOf(WriteAction.POLISH, WriteAction.VIVID, WriteAction.EXPAND, WriteAction.CONDENSE, WriteAction.CUSTOM, WriteAction.CONTINUE)
            .forEach { assertTrue(it.name, WritePrompts.usesSamples(it)) }
    }

    @Test
    fun anthropicMarksSecondCacheBreakpointAfterSamples() {
        val c = AiConfig(enabled = true, preset = AiPresets.CUSTOM, protocol = AiProtocol.ANTHROPIC, baseUrl = "https://example.com", model = "claude-opus-5-5")
        val system = "知识库……" + "范例……" + "本章提示"
        val o = JSONObject(AiToolWire.body(c, system, JSONArray(), emptyList(), cachePrefix = 5, cacheExtra = 9))
        val blocks = o.getJSONArray("system")
        assertEquals(3, blocks.length())
        assertEquals("知识库……", blocks.getJSONObject(0).getString("text"))
        assertEquals("范例……", blocks.getJSONObject(1).getString("text"))
        assertTrue(blocks.getJSONObject(1).has("cache_control"))
        assertNull(blocks.getJSONObject(2).optJSONObject("cache_control"))
        // 没有第二处时和以前一样两块。
        assertEquals(2, JSONObject(AiToolWire.body(c, system, JSONArray(), emptyList(), cachePrefix = 5)).getJSONArray("system").length())
    }
}
