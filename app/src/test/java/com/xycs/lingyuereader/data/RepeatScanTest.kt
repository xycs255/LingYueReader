package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 本书高频说法、句式单调（本机）：用造的文字验证该报的报出来、不该报的不报（对白、人名地名、常用词、只在一章里的复沓、
 * 黑名单里的、点过“不再提”的），以及 200 章的作品算得快。底子是从一大把字里随机抽的“乱码叙述”，自己不会凑出重复的说法。
 */
class RepeatScanTest {
    /** 抽底子用的字：常见汉字，去掉人名里的字，免得凑出名字。 */
    private val pool = ("天地山水风云日月星河江湖草木花鸟虫鱼春夏秋冬东南西北金银铜铁石土田林竹松柏桃李梅兰菊荷柳" +
        "门窗墙瓦桥路街巷城镇村楼台亭阁船车马牛羊犬鸡鸭鹅米面茶酒油盐酱醋布衣帽鞋袜被褥灯火烛炉锅碗瓢盆" +
        "红黄蓝绿紫黑白灰青赤橙粉高低长短大小多少深浅远近冷热轻重快慢新旧早晚明暗厚薄宽窄尖圆方正歪斜").toList()

    private fun filler(random: Random, sentences: Int): String = (1..sentences).joinToString("") {
        val n = random.nextInt(4, 31)
        (1..n).map { pool[random.nextInt(pool.size)] }.joinToString("") + "。"
    }

    private val names = listOf("林月", "沈默", "老周", "杜娘", "顾长风", "渡灯镇")

    /** 一章：几段乱码叙述，按需要插进固定的说法、对白、人名。 */
    private fun chapter(seed: Int, inserts: List<String> = emptyList()): String {
        val r = Random(seed)
        val paragraphs = (1..12).map { filler(r, 10) }.toMutableList()
        inserts.forEachIndexed { i, s -> paragraphs[i % paragraphs.size] = paragraphs[i % paragraphs.size] + s }
        return paragraphs.joinToString("\n")
    }

    private fun scan(texts: List<String>, banned: List<String> = emptyList(), dismissed: Set<String> = emptySet()): List<RepeatPhrase> {
        val grams = texts.mapIndexed { i, t -> "第${i + 1}章" to RepeatScan.grams(t, names) }
        return RepeatScan.phrases(grams, texts.last(), names, banned, dismissed)
    }

    @Test
    fun aTicSpreadOverSeveralChaptersIsReportedAndNoiseIsNot() {
        val tic = "嘴角勾起一抹弧度。"
        val texts = (1..10).map { n ->
            val inserts = mutableListOf<String>()
            // 口癖：第 3、5、7、9、10 章各两次（五章，十次）。
            if (n in setOf(3, 5, 7, 9, 10)) inserts += listOf("林月$tic", "沈默$tic")
            // 对白里的口头禅：每章三次，在引号里，不报。
            inserts += List(3) { "老周说：“这船怕是撑不过今夜了。”" }
            // 人名地名：每章很多次，不报。
            inserts += List(4) { "顾长风回到渡灯镇。" }
            // 常用词：每章很多次（前后的字各不相同），不报。
            inserts += List(5) { k -> "${pool[(n * 7 + k * 3) % pool.size]}${pool[(n * 11 + k * 5) % pool.size]}知道${pool[(n * 13 + k) % pool.size]}${pool[(n * 3 + k * 7) % pool.size]}。" }
            // 只在一章里反复（复沓）：不报。
            if (n == 10) inserts += List(8) { "风一阵紧过一阵。" }
            chapter(n, inserts)
        }
        val found = scan(texts)
        val phrases = found.map { it.phrase }
        assertTrue("应当报出口癖：$phrases", phrases.any { "勾起一抹弧度" in it })
        val tic1 = found.first { "勾起一抹弧度" in it.phrase }
        assertEquals(10, tic1.total)
        assertEquals(listOf("第3章", "第5章", "第7章", "第9章", "第10章"), tic1.spread.map { it.first })
        assertEquals(2, tic1.here.size)
        // 位置对得上当前这一章的正文。
        tic1.here.forEach { r -> assertEquals(tic1.phrase, texts.last().substring(r.first, r.last + 1)) }
        // 被长说法包住、次数差不多的短说法不另报。
        assertFalse(phrases.any { it != tic1.phrase && it in tic1.phrase })
        listOf("撑不过", "今夜", "这船", "顾长风", "渡灯", "回到", "知道").forEach { noise ->
            assertFalse("不该报：$noise（报了 $phrases）", phrases.any { noise in it })
        }
        assertFalse(phrases.any { "紧过一阵" in it })
        assertFalse(phrases.any { "长风" in it || "渡灯" in it })
        // 底子是随机的：除了口癖，什么都不报。
        assertTrue("误报了：$phrases", phrases.all { it in tic1.phrase || tic1.phrase in it })
    }

    @Test
    fun belowTheThresholdsNothingIsReported() {
        val tic = "眼底掠过一丝暗芒。"
        // 只出现在两章：不到三章，不报。
        val two = (1..10).map { n -> chapter(n, if (n in setOf(9, 10)) List(4) { tic } else emptyList()) }
        assertTrue(scan(two).isEmpty())
        // 三章、但一共只有四次（六个字以上的门槛是五次，四五个字是六次）：不报。
        val few = (1..10).map { n -> chapter(n, when (n) { 8 -> listOf(tic, tic); 9 -> listOf(tic); 10 -> listOf(tic); else -> emptyList() }) }
        assertTrue(scan(few).isEmpty())
        // 够了，但本章没有：不报（问题面板只管当前这一章）。
        val notHere = (1..10).map { n -> chapter(n, if (n in setOf(5, 6, 7, 8)) List(2) { tic } else emptyList()) }
        assertTrue(scan(notHere).isEmpty())
        // 章太少（不到三章）：不报。
        assertTrue(scan((1..2).map { chapter(it, List(5) { tic }) }).isEmpty())
    }

    @Test
    fun bannedAndDismissedPhrasesAreLeftOut() {
        val tic = "嘴角勾起一抹弧度。"
        val texts = (1..10).map { n -> chapter(n, if (n >= 6) List(2) { tic } else emptyList()) }
        val found = scan(texts)
        assertEquals(found.toString(), 1, found.size)
        val key = found.single().key
        assertTrue(key.startsWith(RepeatScan.PHRASE_KEY))
        // 已经在黑名单里（或被黑名单里更短的说法盖住）：不报。
        assertTrue(scan(texts, banned = listOf("勾起一抹")).isEmpty())
        // 点过“不再提”：不报。
        assertTrue(scan(texts, dismissed = setOf(key)).isEmpty())
    }

    @Test
    fun monotonyIsFlaggedOnlyWhenItIsExtreme() {
        // 四十多句、每句都是十个字上下，九成用“他”开头，连着五段“他”开头。
        val flat = (1..9).joinToString("\n") { p -> (1..5).joinToString("") { s -> "他走到门口看了一眼天色。".let { if (s > 1 && (p + s) % 7 == 0) "风吹过窗外那片竹林子。" else it } } }
        val found = RepeatScan.monotony(flat)
        assertTrue(found.any { it.kind == MonotonyKind.SENTENCE_LENGTH })
        val pronoun = found.single { it.kind == MonotonyKind.PRONOUN_OPENING }
        assertTrue(pronoun.ranges.isNotEmpty())
        val opening = found.single { it.kind == MonotonyKind.SAME_OPENING }
        assertEquals(9, opening.ranges.size)
        opening.ranges.forEach { assertEquals("他", flat.substring(it.first, it.last + 1)) }
        // 不再提：按种类记，以后不报。
        assertTrue(RepeatScan.monotony(flat, setOf(pronoun.key, opening.key, RepeatScan.MONO_KEY + MonotonyKind.SENTENCE_LENGTH.name)).isEmpty())
        // 长短句交替、开头各不相同的叙述：什么都不报。
        val varied = chapter(42)
        assertTrue(RepeatScan.monotony(varied).isEmpty())
        // 三段同一个开头是常见的排比：不报；对白段不算。
        val three = "他推开门。\n他坐下。\n他喝了口茶。\n“他来了。”\n“他走了。”\n风停了。"
        assertTrue(RepeatScan.monotony(three).none { it.kind == MonotonyKind.SAME_OPENING })
    }

    @Test
    fun twoHundredChaptersStayFastAndUnchangedChaptersAreNotRecounted() {
        val texts = (1..200).map { chapter(it, if (it % 3 == 0) List(2) { "嘴角勾起一抹弧度。" } else emptyList()) }
        val cache = RepeatScan.Cache()
        var counted = 0
        fun gramsOf(i: Int) = cache.get("c$i", "stamp$i") { counted++; RepeatScan.grams(texts[i], names) }
        val start = System.nanoTime()
        // 依次在每一章打开编辑器：每次看最近十章。
        (9 until 200).forEach { i ->
            val window = (i - 9..i).map { j -> "第${j + 1}章" to gramsOf(j) }
            RepeatScan.phrases(window, texts[i], names)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // 每章只数一次（缓存按章、按修改时间认）。
        assertEquals(200, counted)
        assertTrue("200 章用了 $ms 毫秒", ms < 20_000)
        // 一章没改：再查一次不重算。
        cache.get("c199", "stamp199") { counted++; RepeatScan.grams(texts[199], names) }
        assertEquals(200, counted)
        // 改了：重算这一章。
        cache.get("c199", "changed") { counted++; RepeatScan.grams(texts[199], names) }
        assertEquals(201, counted)
    }

    @Test
    fun narrativeKeepsPositionsAndDropsDialogue() {
        val t = "林月说：“走吧。”他点头。\n「回来」她笑了。"
        val n = RepeatScan.narrative(t)
        assertEquals(t.length, n.length)
        assertFalse("走吧" in n)
        assertFalse("回来" in n)
        assertTrue("他点头" in n)
        assertTrue("她笑了" in n)
        assertFalse(RepeatScan.plausible("的了"))
        assertFalse(RepeatScan.plausible("了一下"))
        assertFalse(RepeatScan.plausible("知道"))
        assertFalse(RepeatScan.plausible("哈哈哈"))
        assertTrue(RepeatScan.plausible("微微"))
        assertTrue(RepeatScan.plausible("勾起一抹弧度"))
    }
}
