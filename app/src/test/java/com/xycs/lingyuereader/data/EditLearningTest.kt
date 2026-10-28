package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.WritePrompts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 从改稿里学的本机部分：记原稿有上限、删章清掉、备份恢复带上、旧数据能读；本机比对反复删同一个说法才提黑名单候选，
 * 只删一次的不提；已经拒绝过、已经在名单里的不再提；“这段不行”同一原因点满 3 次提成规则；AI 归纳的 JSON 能读、格式不对如实报错；
 * 学到的规则写正文、改稿时带上。文本都是自造的，人名虚构。
 */
class EditLearningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ban = "嘴角勾起一抹笑意"

    /** 一章 AI 原稿和定稿：[cuts] 段里作者删掉了 [ban]，其余段原样。 */
    private fun chapter(cuts: Int, rest: Int = 4): Pair<String, String> {
        val ai = ArrayList<String>()
        val fin = ArrayList<String>()
        repeat(cuts) { i ->
            ai += "林月${ban}，把第${i + 1}只茶碗推到沈默面前，说今晚渡口不太平。"
            fin += "林月把第${i + 1}只茶碗推到沈默面前，说今晚渡口不太平。"
        }
        repeat(rest) { i ->
            val p = "老周在船头收第${i + 1}道缆绳，杜娘隔着雾喊他回来吃饭。"
            ai += p
            fin += p
        }
        return ai.joinToString("\n") to fin.joinToString("\n")
    }

    private fun observe(ai: String, final: String): List<EditLearning.Observation> {
        val edits = EditLearning.compare(listOf(AiOriginal(1, ai)), final)
        assertNotNull(edits)
        return EditLearning.observe(edits!!, final)
    }

    private fun learn(data: LearnData, chapterId: String, ai: String, final: String) =
        EditLearning.tally(data, chapterId, observe(ai, final), 10)

    @Test
    fun aPhraseDeletedAgainAcrossChaptersBecomesABanCandidate() {
        val (a1, f1) = chapter(cuts = 2)
        val (a2, f2) = chapter(cuts = 1)
        var data = learn(LearnData(), "c1", a1, f1)
        // 只有一章：还不提。
        assertTrue(EditLearning.candidates(data, WorkAssist()).isEmpty())
        data = learn(data, "c2", a2, f2)
        val c = EditLearning.candidates(data, WorkAssist()).single { it.kind == LearnKind.BAN }
        assertEquals(ban, c.text)
        assertEquals("ban:$ban", c.key)
        // 例子：原来那一句、作者改成的那一句。
        assertTrue(ban in c.before)
        assertFalse(ban in c.after)
        assertTrue("茶碗" in c.after)
        assertTrue("2 章" in c.source && "3 处" in c.source)
        // 同一章再收尾一次是重算，不叠加。
        val again = learn(data, "c2", a2, f2)
        assertEquals(3, again.tallies.single { it.key == "ban:$ban" }.total)
    }

    @Test
    fun aPhraseDeletedOnlyOnceIsNotProposed() {
        val (a1, f1) = chapter(cuts = 1)
        val (a2, f2) = chapter(cuts = 0)
        val data = learn(learn(LearnData(), "c1", a1, f1), "c2", a2, f2)
        assertTrue(EditLearning.candidates(data, WorkAssist()).none { it.kind == LearnKind.BAN })
    }

    @Test
    fun aPhraseTheAuthorStillUsesIsNotBanned() {
        // 删了两处，定稿里自己又写了三处：只是那两处不合适。
        val (a1, f1) = chapter(cuts = 2)
        val kept = f1 + "\n" + List(3) { "杜娘${ban}，转身进了后厨。" }.joinToString("\n")
        val (a2, f2) = chapter(cuts = 2)
        val data = learn(learn(LearnData(), "c1", a1, kept), "c2", a2, f2)
        // 第一章站不住，只剩一章，不提。
        assertTrue(EditLearning.candidates(data, WorkAssist()).none { it.kind == LearnKind.BAN })
    }

    @Test
    fun dismissedOrAlreadyListedCandidatesAreNotProposedAgain() {
        val (a1, f1) = chapter(cuts = 2)
        val (a2, f2) = chapter(cuts = 2)
        val data = learn(learn(LearnData(), "c1", a1, f1), "c2", a2, f2)
        assertEquals(1, EditLearning.candidates(data, WorkAssist()).count { it.kind == LearnKind.BAN })
        // 已经在黑名单里，或者被黑名单里更短的说法盖住。
        assertTrue(EditLearning.candidates(data, WorkAssist(banned = listOf(ban))).none { it.kind == LearnKind.BAN })
        assertTrue(EditLearning.candidates(data, WorkAssist(banned = listOf("嘴角勾起"))).none { it.kind == LearnKind.BAN })
        // 作者没勾过：不再提。
        val dismissed = EditLearning.dismiss(data, listOf("ban:$ban"))
        assertTrue(EditLearning.candidates(dismissed, WorkAssist()).none { it.kind == LearnKind.BAN })
        // 已经在学到的规则里的规则也不再提。
        val rule = FixReason.FLAT.rule!!
        val reasons = (1..3).fold(LearnData()) { d, _ -> EditLearning.countReasons(d, listOf(FixReason.FLAT), "林月说：“走吧。”") }
        assertTrue(EditLearning.candidates(reasons, WorkAssist()).any { it.text == rule })
        assertTrue(EditLearning.candidates(reasons, WorkAssist(learned = listOf(rule))).none { it.text == rule })
    }

    @Test
    fun aReasonPickedThreeTimesBecomesARuleWithAnExample() {
        var data = LearnData()
        repeat(2) { data = EditLearning.countReasons(data, listOf(FixReason.FLAT, FixReason.WORDY), "沈默说：“我去渡口。”") }
        assertTrue(EditLearning.candidates(data, WorkAssist()).isEmpty())
        data = EditLearning.countReasons(data, listOf(FixReason.FLAT), "沈默说：“我去渡口。”")
        data = EditLearning.reasonExample(data, listOf(FixReason.FLAT), "沈默说：“我去渡口。”", "沈默攥紧了刀柄：“我去渡口。”")
        val c = EditLearning.candidates(data, WorkAssist()).single()
        assertEquals("写对白时给人物补一句当下的情绪或动作。", c.text)
        assertEquals(LearnKind.RULE, c.kind)
        assertEquals("沈默攥紧了刀柄：“我去渡口。”", c.after)
        assertTrue("3 次" in c.source && "没情绪" in c.source)
        // “其他”没有固定说法，点多少次都不提。
        repeat(5) { data = EditLearning.countReasons(data, listOf(FixReason.OTHER)) }
        assertEquals(1, EditLearning.candidates(data, WorkAssist()).size)
    }

    @Test
    fun cuttingTheExplanationAfterDialogueInTwoChaptersBecomesARule() {
        fun one(): Pair<String, String> {
            val ai = List(3) { i -> "“第${i + 1}趟船走了。”老周说，他心里其实很怕杜娘发火。" }
            val fin = List(3) { i -> "“第${i + 1}趟船走了。”" }
            return (ai + "雾从江面上漫过来，渡口的灯笼一盏盏亮了。").joinToString("\n") to
                (fin + "雾从江面上漫过来，渡口的灯笼一盏盏亮了。").joinToString("\n")
        }
        val (a, f) = one()
        val first = observe(a, f).single { it.key == "rule:dialogue-tail" }
        assertEquals(3, first.hits)
        assertTrue(first.holds)
        val data = learn(learn(LearnData(), "c1", a, f), "c2", a, f)
        assertTrue(EditLearning.candidates(data, WorkAssist()).any { it.text.startsWith("对白说完就收住") })
    }

    @Test
    fun recordingIsCappedPerChapterAndPerWork() {
        var data = EditLearning.record(LearnData(), "c1", "林月推开门。", 1)
        // 同一段连着记两次只算一次。
        assertEquals(data, EditLearning.record(data, "c1", "林月推开门。", 2))
        repeat(30) { i -> data = EditLearning.record(data, "c1", "第${i}段" + "雾".repeat(1_000), 10L + i) }
        val c1 = data.originals.getValue("c1")
        assertTrue(c1.sumOf { it.text.length } <= EditLearning.CHAPTER_CHARS)
        // 留的是最近的。
        assertTrue(c1.last().text.startsWith("第29段"))
        // 一段太长的只留开头 2 万字。
        val huge = EditLearning.record(LearnData(), "c2", "江".repeat(30_000), 1)
        assertEquals(EditLearning.CHAPTER_CHARS, huge.originals.getValue("c2").single().text.length)
        // 整章替换：以前记的作废。
        val replaced = EditLearning.record(data, "c1", "沈默走了。", 99, replace = true)
        assertEquals(listOf("沈默走了。"), replaced.originals.getValue("c1").map { it.text })
        // 全作品超了上限：丢最早的。
        var work = LearnData()
        repeat(15) { i -> work = EditLearning.record(work, "ch$i", "雨".repeat(19_000), i.toLong()) }
        assertTrue(work.originals.values.sumOf { l -> l.sumOf { it.text.length } } <= EditLearning.WORK_CHARS)
        assertNull(work.originals["ch0"])
        assertNotNull(work.originals["ch14"])
    }

    @Test
    fun theStoreKeepsOriginalsClearsThemWithTheChapterAndCarriesThemInBackups() {
        val dirs = listOf(tmp.newFolder("a"), tmp.newFolder("b"))
        val a = WorkStore(dirs[0])
        val w = a.create("渡口")
        val c1 = w.chapters.single().id
        val c2 = a.addChapter(w.id, w.volumes.single().id)!!.second.id
        a.recordAiText(w.id, c1, "林月推开门。")
        a.recordAiText(w.id, c2, "沈默站在雨里。")
        a.updateLearning(w.id) { EditLearning.dismiss(EditLearning.countReasons(it, listOf(FixReason.THIN)), listOf("ban:仿佛")) }
        // 原子写入：没有剩下的临时文件。
        val file = File(dirs[0], "works/${w.id}/learn.json")
        assertTrue(file.isFile)
        assertFalse(File(file.parentFile, "learn.json.tmp").exists())
        assertEquals(listOf("林月推开门。"), WorkStore(dirs[0]).learning(w.id).originals.getValue(c1).map { it.text })

        // 删章：这章的原稿清掉，别的章不动。
        a.deleteChapter(w.id, c1)
        assertNull(a.learning(w.id).originals[c1])
        assertNotNull(a.learning(w.id).originals[c2])

        // 备份恢复带上。
        val backup = String(a.backup()!!)
        assertTrue(JSONObject(backup).getJSONArray("works").getJSONObject(0).has("learn"))
        val b = WorkStore(dirs[1])
        assertEquals(1, b.restoreBackup(backup))
        val restored = b.learning(w.id)
        assertEquals(listOf("沈默站在雨里。"), restored.originals.getValue(c2).map { it.text })
        assertEquals(1, restored.reasons[FixReason.THIN.name])
        assertEquals(listOf("ban:仿佛"), restored.dismissed)

        // 旧备份没有这一项：恢复照常，本机的留着。
        // （备份比本机新才恢复。）
        val old = JSONObject(backup).apply {
            getJSONArray("works").getJSONObject(0).apply { remove("learn"); put("updatedAt", getLong("updatedAt") + 60_000) }
        }
        b.recordAiText(w.id, c2, "杜娘关了门。")
        assertEquals(1, b.restoreBackup(old.toString()))
        assertEquals("杜娘关了门。", b.learning(w.id).originals.getValue(c2).last().text)

        // 删作品：整份没了。
        a.delete(w.id)
        assertFalse(file.exists())
    }

    @Test
    fun oldOrPartialDataStillReads() {
        val dir = tmp.newFolder("old")
        val store = WorkStore(dir)
        val w = store.create("渡口")
        // 没有这份文件：空的。
        assertEquals(LearnData(), store.learning(w.id))
        // 只有一部分项、带着坏条目：缺的空着，坏的跳过。
        File(dir, "works/${w.id}/learn.json").writeText("""{"originals":{"${w.chapters[0].id}":[{"at":1,"text":"林月推开门。"},{"at":2}]},"reasons":{"FLAT":2,"NOPE":9}}""")
        val d = store.learning(w.id)
        assertEquals(1, d.originals.getValue(w.chapters[0].id).size)
        assertEquals(mapOf("FLAT" to 2), d.reasons)
        assertTrue(d.tallies.isEmpty())
        // 坏文件：当作空的，原件另存，不闪退。
        File(dir, "works/${w.id}/learn.json").writeText("{坏")
        assertEquals(LearnData(), store.learning(w.id))
        // 旧的作品 AI 设置没有学到的规则：空的；新的存得下、读得回。
        assertTrue(store.assist(w.id).learned.isEmpty())
        store.updateAssist(w.id) { it.learn("对白说完就收住。").learn("长句拆短。") }
        assertEquals(listOf("对白说完就收住。", "长句拆短。"), WorkStore(dir).assist(w.id).learned)
        store.updateAssist(w.id) { it.unlearn("长句拆短。") }
        assertEquals(listOf("对白说完就收住。"), WorkStore(dir).assist(w.id).learned)
        // 收尾的“从改稿里学”默认勾，旧数据缺这一项也是勾上。
        assertTrue(FinishOptions().learn)
        val f = File(dir, "works/${w.id}/assist.json")
        f.writeText(JSONObject(f.readText()).put("finish", JSONObject().put("review", true)).toString())
        assertTrue(WorkStore(dir).assist(w.id).finish.learn)
    }

    @Test
    fun aLongChapterComparesQuickly() {
        // 一万多字、几百段，改了一半：对齐、数改法都要快。
        val ai = List(400) { i -> "第${i}段：林月${ban}，沿着渡口的石阶往下走，雾里只听得见老周的橹声和远处杜娘的吆喝。" }
        val fin = ai.mapIndexed { i, p -> if (i % 2 == 0) p.replace(ban, "") else p }
        val started = System.nanoTime()
        val edits = EditLearning.compare(listOf(AiOriginal(1, ai.joinToString("\n"))), fin.joinToString("\n"))!!
        val observed = EditLearning.observe(edits, fin.joinToString("\n"))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("比对用了 $ms 毫秒", ms < 5_000)
        assertTrue(ai.joinToString("").length > 10_000)
        assertEquals(200, edits.pairs.size)
        assertEquals(200, observed.single { it.key == "ban:$ban" }.hits)
    }

    @Test
    fun aiInductionParsesJsonAndRejectsMalformedAnswers() {
        val pairs = listOf(
            "林月${ban}，把茶碗推过去。" to "林月把茶碗推过去。",
            "“走吧。”沈默说，他心里其实很怕。" to "“走吧。”",
        )
        val answer = """好的：{"rules":[{"kind":"ban","text":"$ban","p":1},{"kind":"rule","text":"对白后面别解释人物心思","p":2},""" +
            """{"kind":"rule","text":"把茶碗推过去","p":1},{"kind":"rule","text":"没编号的","p":9}]}"""
        val got = EditLearning.parseAi(answer, pairs)
        assertEquals(listOf(LearnKind.BAN to ban, LearnKind.RULE to "对白后面别解释人物心思"), got.map { it.kind to it.text })
        assertEquals("AI 归纳", got[1].source)
        assertEquals("“走吧。”", got[1].after)
        // 不是约定的 JSON：照实报错，不把原文当规则。
        val e = assertThrows(EditLearning.ParseError::class.java) { EditLearning.parseAi("作者喜欢短句，删掉了很多修饰。", pairs) }
        assertTrue("JSON" in e.message!!)
        assertThrows(EditLearning.ParseError::class.java) { EditLearning.parseAi("""{"rule":[]}""", pairs) }
        assertTrue(EditLearning.parseAi("""{"rules":[]}""", pairs).isEmpty())
        // 发给 AI 的不超过上限。
        val big = EditLearning.ChapterEdits(emptyList(), emptyList(), List(100) { "雾".repeat(200) to "雨".repeat(100) }, emptyList())
        assertTrue(EditLearning.pairsForAi(big).sumOf { it.first.length + it.second.length } <= EditLearning.AI_CHARS)
        assertTrue("只输出 JSON" in WritePrompts.learnRules(pairs, emptyList()))
    }

    @Test
    fun learnedRulesGoIntoProseAndEditTailsButOnlyTheRecentOnes() {
        val rules = List(15) { "第${it}条：对白说完就收住" }
        val assist = WorkAssist(learned = rules)
        val prose = WritePrompts.proseTail(assist, null)
        assertTrue(WritePrompts.LEARNED_HEADER in prose)
        assertTrue(prose.indexOf(WritePrompts.PROSE_HEADER) < prose.indexOf(WritePrompts.LEARNED_HEADER))
        assertTrue("第14条" in prose && "第3条" in prose)
        assertFalse("第2条：" in prose)
        val edit = WritePrompts.voiceTail(assist, null)
        assertTrue(WritePrompts.LEARNED_HEADER in edit && "第14条" in edit)
        // 没学到的：不多出这一节。
        assertFalse(WritePrompts.LEARNED_HEADER in WritePrompts.proseTail(WorkAssist(), null))
        assertFalse(WritePrompts.LEARNED_HEADER in WritePrompts.voiceTail(WorkAssist(), null))
    }
}
