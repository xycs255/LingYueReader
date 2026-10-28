package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** AI 腔清单（本机检查）、引号里的说法不拆、作品的“查 AI 腔”开关和我的黑名单。 */
class AiClicheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hits(text: String, assist: WorkAssist = WorkAssist()) = CheckRules.check(text, AiCliche.rules(assist)).hits

    @Test
    fun builtinListIsReadCompletelyAndSized() {
        val set = AiCliche.builtin
        // 每一行都读得懂（引擎不支持的写法不收）。
        assertTrue(set.unknown.map { it.text }.toString(), set.unknown.isEmpty())
        val terms = set.rules.filterIsInstance<CheckRule.Count>().sumOf { it.terms.size }
        assertTrue("收了 $terms 条", terms in 40..70)
        assertTrue(set.rules.all { it.source.doc == AiCliche.DOC })
        // 套话只查叙述。
        assertTrue(set.rules.filterIsInstance<CheckRule.Count>().all { it.scope == RuleScope.NARRATION })
    }

    @Test
    fun narrationClichesAreMarkedDialogueIsNot() {
        val text = "林月嘴角勾起一抹笑意，眼中闪过一丝寒光。\n“你嘴角勾起来干什么？”沈默问。"
        val found = hits(text)
        val messages = found.joinToString { it.message }
        assertTrue(messages, "「嘴角勾起」" in messages)
        assertTrue(messages, "「眼中闪过一丝」" in messages)
        // 对白里的不算：只标第一段那一处。
        val mouth = found.first { "嘴角勾起" in it.message }
        assertEquals(1, mouth.ranges.size)
        assertEquals("嘴角勾起", text.substring(mouth.ranges[0].first, mouth.ranges[0].last + 1))
        assertTrue(found.all(AiCliche::isVoice))
        assertTrue(AiCliche.count(found) >= 3)
    }

    @Test
    fun commonWordsOnlyCountPastTheLimit() {
        // “仿佛”每章至多 3：三次不标，四次四处都标出来。
        val three = "他仿佛没听见。\n灯仿佛暗了。\n门仿佛开着。"
        assertTrue(hits(three).none { "「仿佛」" in it.message })
        val four = three + "\n雨仿佛停了。"
        assertEquals(4, hits(four).first { "「仿佛」" in it.message }.ranges.size)
        // “知道”本身不算，句首点题的“他知道，”才算。
        assertTrue(hits("他知道这条路。").isEmpty())
        assertTrue(hits("雨停了。他知道，这一回躲不过去了。").any { "他知道，" in it.message })
        // 除外的固定说法不算（各两次，超过“至多 1”也不标）。
        assertTrue(hits("林月情不自禁笑了，沈默也情不自禁笑了。莫名其妙，真是莫名其妙。").isEmpty())
        assertTrue(hits("他不禁一笑，又不禁摇头。").any { "「不禁」" in it.message })
    }

    @Test
    fun quotedTermsKeepTheirCommas() {
        val set = CheckRules.parse("禁用：“他知道，”、你好\n至多 1：「是吗，好」", "规则")
        assertEquals(listOf("他知道，", "你好"), (set.rules[0] as CheckRule.Count).terms.map { it.label })
        assertEquals(listOf("是吗，好"), (set.rules[1] as CheckRule.Count).terms.map { it.label })
        // 没加引号的照旧按逗号拆开。
        assertEquals(listOf("甲", "乙"), (CheckRules.parse("禁用：甲，乙").rules[0] as CheckRule.Count).terms.map { it.label })
    }

    @Test
    fun switchAndBlacklist() {
        val text = "林月嘴角勾起。\n“说来话长。”杜娘说。"
        val off = WorkAssist(aiCliche = false)
        assertTrue(hits(text, off).isEmpty())
        // 黑名单全文禁用：对白里也查，来源是“我的黑名单”。
        val banned = hits(text, off.ban("说来话长"))
        assertEquals(1, banned.size)
        assertTrue(AiCliche.isBanned(banned[0]))
        assertEquals(AiCliche.BANNED_DOC, banned[0].rule.source.doc)
        // 带逗号的说法也能进黑名单。
        assertEquals(1, hits("他说：好，好。", off.ban("好，好")).size)
    }

    @Test
    fun blacklistIsTrimmedDedupedAndCapped() {
        var a = WorkAssist()
        a = a.ban("  嘴角勾起 ").ban("").ban("眼中闪过").ban("嘴角勾起")
        // 去空白、去重，重复加的挪到最后。
        assertEquals(listOf("眼中闪过", "嘴角勾起"), a.banned)
        repeat(WorkAssist.KEEP_BANNED + 5) { a = a.ban("说法$it") }
        assertEquals(WorkAssist.KEEP_BANNED, a.banned.size)
        // 超出时丢最旧的。
        assertEquals("说法5", a.banned.first())
        assertEquals("说法${WorkAssist.KEEP_BANNED + 4}", a.banned.last())
        assertFalse("说法7" in a.unban("说法7").banned)
    }

    @Test
    fun fieldsSurviveJsonBackupAndOldDataDefaults() {
        val dirs = listOf(tmp.newFolder("a"), tmp.newFolder("b"))
        val a = WorkStore(dirs[0])
        val w = a.create("渡口")
        a.updateAssist(w.id) { it.copy(aiCliche = false).ban("嘴角勾起").ban("落针可闻") }
        assertEquals(listOf("嘴角勾起", "落针可闻"), WorkStore(dirs[0]).assist(w.id).banned)
        assertFalse(WorkStore(dirs[0]).assist(w.id).aiCliche)
        // 备份恢复带上。
        val b = WorkStore(dirs[1])
        assertEquals(1, b.restoreBackup(String(a.backup()!!)))
        assertEquals(listOf("嘴角勾起", "落针可闻"), b.assist(w.id).banned)
        assertFalse(b.assist(w.id).aiCliche)
        // 旧数据没有这两项：默认查 AI 腔，黑名单空。
        val file = File(dirs[0], "works/${w.id}/assist.json")
        val old = JSONObject(file.readText()).apply { remove("aiCliche"); remove("banned") }
        file.writeText(old.toString())
        val read = WorkStore(dirs[0]).assist(w.id)
        assertTrue(read.aiCliche)
        assertTrue(read.banned.isEmpty())
    }

    @Test
    fun paragraphsAndSentencesForTheRewrite() {
        val text = "沈默推门。\n  林月嘴角勾起，眼中闪过一丝笑。她坐下了。\n\n顾长风倒吸一口凉气。"
        val ranges = hits(text).flatMap { it.ranges }
        val picked = AiCliche.paragraphs(text, ranges)
        assertEquals(listOf("林月嘴角勾起，眼中闪过一丝笑。她坐下了。", "顾长风倒吸一口凉气。"), picked.map { it.first })
        assertTrue("嘴角勾起" in picked[0].second && "眼中闪过一丝" in picked[0].second)
        // 段落是正文里一字不差的一段（写作建议按它找回位置）。
        picked.forEach { assertTrue(it.first in text) }
        assertEquals(1, AiCliche.paragraphs(text, ranges, limit = 1).size)
        assertEquals("林月嘴角勾起，眼中闪过一丝笑。", AiCliche.sentence(picked[0].first, "嘴角勾起"))
        assertEquals("她说：“走吧。”", AiCliche.sentence("林月到了。她说：“走吧。”", "走吧"))
        assertEquals("嘴角勾起", AiCliche.bannable(" 嘴角勾起 "))
        assertEquals(null, AiCliche.bannable("嘴"))
        assertEquals(null, AiCliche.bannable("字".repeat(21)))
    }

    /** 文艺克制腔：硬造余味的单句短段、“张了张嘴”“到底没喊出”“盯着……看了一会儿”，只限次数；吐槽、玩梗不标。 */
    @Test
    fun restrainedArtsyLinesAreCountedButJokesAreNot() {
        val once = "林月张了张嘴。她转身去了渡口，船上没人。\n“周伯？”她喊了一声。"
        assertTrue(hits(once).none { "张了张嘴" in it.message })
        val twice = "林月张了张嘴，到底没喊出声。\n沈默也张了张嘴。\n她盯着那条船看了一会儿，又盯着桩子看了一会儿。"
        val found = hits(twice)
        assertTrue(found.joinToString { it.message }, found.any { "张了张嘴" in it.message })
        assertTrue(found.any { "盯着……看了一会儿" in it.message })
        assertTrue(found.all(AiCliche::isVoice))
        // 只有一句的短段：一章两处以内不标，多了才标；对白不算。
        val short = "林月推开门，屋里没人。\n墙是凉的。\n灶也是凉的。\n“走吧。”\n“好。”"
        assertTrue(hits(short).none { "字以内的段" in it.message })
        val more = short + "\n风停了。"
        val para = hits(more).first { "字以内的段" in it.message }
        assertEquals(3, para.ranges.size)
        // 叙述者吐槽、网络梗是作者要的口吻，不算 AI 腔。
        val jokes = "好家伙，这波属于是血赚。林月心想，离谱，太离谱了。\n顾长风这人吧，主打一个阴阳怪气，绝了。"
        assertTrue(hits(jokes).joinToString { it.message }, hits(jokes).isEmpty())
    }
}
