package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.ProseLengths
import com.xycs.lingyuereader.data.ProsePack
import com.xycs.lingyuereader.data.SampleChapter
import com.xycs.lingyuereader.data.SampleOptions
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.beats
import org.junit.Test
import kotlin.random.Random

/**
 * 写正文时会跑的这些（任务卡、上一章结尾、按正文估写到第几拍、去掉整章字数、防剧透、本机检查、文风范例、边收边清理）
 * 喂乱七八糟的输入（拍号越界、空细纲、全是标点、超长的段），一个都不能抛异常：写正文的协程里漏一个就是闪退。
 */
class ProseFuzzTest {
    private val alphabet = "林月沈默老周杜娘顾长风渡口船药方说道看了一会儿盯着张了张嘴到底没说出不是而是仿佛一丝字章拍第一二三四五六七八九十百千万零〇0123456789０１２３４５６７８９。，、；：！？…“”「」（）()-—~～ #*\n\n\n　 字数：至多禁用短段叙述对白……"
    private fun r(rnd: Random, n: Int) = String(CharArray(rnd.nextInt(n + 1)) { alphabet[rnd.nextInt(alphabet.length)] })

    @Test
    fun fuzz() {
        val rnd = Random(42)
        val fails = mutableListOf<String>()
        repeat(3000) { i ->
            val a = r(rnd, 300); val b = r(rnd, 300); val c = r(rnd, 3000)
            fun t(name: String, f: () -> Unit) { try { f() } catch (e: Throwable) { if (fails.size < 40) fails += "$name#$i: $e\n  a=${a.take(80)}\n  b=${b.take(80)}" } }
            val card = ChapterCard(facts = a, beats = b, hook = r(rnd, 30), pov = r(rnd, 5), boundary = r(rnd, 20), countdown = r(rnd, 10),
                beatsDone = List(rnd.nextInt(4)) { rnd.nextInt(-2, 40) })
            val task = ProseTask(if (rnd.nextBoolean()) null else rnd.nextInt(-3, 100), r(rnd, 20), card, ProseScope.entries.random(rnd),
                beat = if (rnd.nextBoolean()) null else rnd.nextInt(-3, 40), length = r(rnd, 10), opening = rnd.nextBoolean(), previous = rnd.nextBoolean(),
                after = rnd.nextBoolean(), previousHook = r(rnd, 20), instruction = r(rnd, 20), direction = if (rnd.nextBoolean()) null else WriteDirection.entries.random(rnd))
            t("taskCard") { WritePrompts.taskCard(task) }
            t("ending") { WritePrompts.ending(c); WritePrompts.ending(c, rnd.nextInt(0, 2000), rnd.nextInt(0, 2000)) }
            t("covered") { Beats.covered(beats(card), c) }
            t("strip") { ProseLengths.strip(c); ProseLengths.strip(a) }
            t("tidy") { ProsePack.tidy(c) }
            t("remaining") { WritePrompts.remaining(a, c) }
            t("upTo") { ChapterBriefs.upTo(c, rnd.nextInt(-2, 50), rnd.nextInt(-2, 5)) }
            t("check") { CheckRules.check(c, AiCliche.builtin + CheckRules.parse(a)) }
            t("tail") { WritePrompts.proseTail(WorkAssist(flavor = a, style = b, banned = listOf(r(rnd, 4))), c, b) }
            t("continueAt") { WritePrompts.continueAt(c, b, task, a, b); WritePrompts.draft(c, task, a, b); WritePrompts.draftMore(c, task, a, b) }
            t("cleanDraft") {
                val stream = WritePrompts.DraftStream()
                c.chunked(rnd.nextInt(1, 8)).forEach { stream.append(it) }
                check(stream.text() == WritePrompts.cleanDraft(c)) { "边收边清理和整段清理不一样" }
            }
            if (i % 30 == 0) t("pick") {
                StyleSamples.pick(List(5) { SampleChapter("c$it", "第${it}章", r(rnd, 6000)) },
                    SampleOptions(limit = listOf(0, 800, 1500, 2500, 3000, 5000).random(rnd), avoid = a, shown = c, shift = mapOf("DIALOGUE" to rnd.nextInt(-5, 5))))
            }
        }
        println(fails.joinToString("\n"))
        check(fails.isEmpty()) { fails.joinToString("\n") }
    }
}
