package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/** 细纲卡“伏笔动作”里一条的动作：埋下、推进、回收、暂缓。 */
enum class MoveAction(val label: String) {
    PLANT("埋"),
    ADVANCE("推进"),
    PAYOFF("回收"),
    DEFER("暂缓");

    /** 推进了这条伏笔（埋、推进、回收都算；暂缓只是挂着）。线索体检按这个算最近一次推进。 */
    val moves: Boolean get() = this != DEFER
}

/**
 * 细纲卡里的一条伏笔动作：做什么（[action]）、一行说明（[text]）。对得上故事账本里某条伏笔时记着它的 id（[ledgerId]），
 * 对不上就只是自由文字。
 */
data class ForeshadowMove(val action: MoveAction, val text: String, val ledgerId: String = "") {
    /** 写成一行：“推进：灯芯的下落”。编辑框里一行一条，读回来见 [ForeshadowMoves.parse]。 */
    fun line(): String = action.label + "：" + text.trim()
}

/** 伏笔动作怎么写成文字、怎么从文字读回来、怎么对上账本里的伏笔。 */
object ForeshadowMoves {
    /** 一行开头的动作：“埋”“埋下”“推进”“回收”“收”“暂缓”“缓”，后面跟冒号。 */
    private val head = Regex("^(埋下|埋|推进|回收|收回|收|暂缓|缓)\\s*[：:]\\s*")

    /** 一条最多多少字：说明只要一行，再长是把正文抄进来了。 */
    private const val MAX_TEXT = 120

    /** 一张卡最多几条：一章里埋、推、收的伏笔多了，正文也写不过来。 */
    const val MAX = 8

    fun format(moves: List<ForeshadowMove>): String = moves.joinToString("\n") { it.line() }

    /**
     * 编辑框、AI 回的文字读回来：一行一条，行首写动作；一行里用“；”隔开的几条也拆开（AI 常写在一行里）。
     * 没写动作的行算“推进”（写在伏笔动作里，总是要动它）。“无”和空行不要。[previous] 是原来的几条：
     * 说明没变的沿用原来记的账本 id。
     */
    fun parse(text: String, previous: List<ForeshadowMove> = emptyList()): List<ForeshadowMove> {
        val ids = previous.filter { it.ledgerId.isNotEmpty() }.associate { key(it.action, it.text) to it.ledgerId }
        return text.lines().flatMap { raw -> raw.split(Regex("[；;](?=\\s*(?:埋下|埋|推进|回收|收回|收|暂缓|缓)\\s*[：:])")) }
            .mapNotNull { raw ->
                val line = raw.trim().removePrefix("-").removePrefix("·").removePrefix("•").trim()
                if (line.isEmpty() || line in setOf("无", "（无）", "暂无")) return@mapNotNull null
                val m = head.find(line)
                val action = when (m?.groupValues?.get(1)) {
                    "埋", "埋下" -> MoveAction.PLANT
                    "回收", "收", "收回" -> MoveAction.PAYOFF
                    "暂缓", "缓" -> MoveAction.DEFER
                    else -> MoveAction.ADVANCE
                }
                val body = (if (m != null) line.substring(m.range.last + 1) else line).trim().trimEnd('。', '；', ';').take(MAX_TEXT)
                if (body.isEmpty()) null else ForeshadowMove(action, body, ids[key(action, body)].orEmpty())
            }.distinctBy { key(it.action, it.text) }.take(MAX)
    }

    private fun key(action: MoveAction, text: String) = action.name + "|" + ChapterSyncs.normalize(text)

    /**
     * 对上账本里的伏笔：还没记 id 的，找和它连着一样的字最多的那条伏笔（规范化后一条包含另一条，或者有连续 [LINK_CHARS] 个字一样）。
     * 不按零散的字算：伏笔里多半带着人名，“老周的账册”和“老周的船”零散地看很像，其实是两件事。
     * 两条一样像的不挂（拿不准）：宁可当自由文字，也不把动作挂到别的伏笔上。
     */
    fun link(moves: List<ForeshadowMove>, ledger: List<LedgerEntry>): List<ForeshadowMove> {
        if (moves.none { it.ledgerId.isEmpty() }) return moves
        val promises = ledger.filter { it.kind == LedgerKind.PROMISE && !it.secret }
        if (promises.isEmpty()) return moves
        return moves.map { m ->
            if (m.ledgerId.isNotEmpty()) return@map m
            val scored = promises.map { it to common(m.text, it.text) }.filter { it.second >= LINK_CHARS }.sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return@map m
            if (scored.getOrNull(1)?.second == best.second) m else m.copy(ledgerId = best.first.id)
        }
    }

    /** 两句话里最长的一段连着一样的字有几个（规范化后；一条包含另一条时是短的那条的长度）。 */
    internal fun common(a: String, b: String): Int {
        val x = ChapterSyncs.normalize(a)
        val y = ChapterSyncs.normalize(b)
        var best = 0
        val row = IntArray(y.length + 1)
        for (i in 1..x.length) {
            var prev = 0
            for (j in 1..y.length) {
                val keep = row[j]
                row[j] = if (x[i - 1] == y[j - 1]) prev + 1 else 0
                if (row[j] > best) best = row[j]
                prev = keep
            }
        }
        return best
    }

    /** 连续几个字一样才算同一条伏笔：四个字（“半本账册”“铜灯记号”）基本能认出是哪件事，三个字常常只是人名加一个字。 */
    private const val LINK_CHARS = 4

    /** 两句话像不像（0 到 1）：包含算 1，否则按两个字一组的重合占短的一方的比例。 */
    internal fun similarity(a: String, b: String): Double {
        val x = ChapterSyncs.normalize(a)
        val y = ChapterSyncs.normalize(b)
        if (x.length < 2 || y.length < 2) return 0.0
        if (x in y || y in x) return 1.0
        val gx = x.windowed(2).toSet()
        val gy = y.windowed(2).toSet()
        return (gx intersect gy).size.toDouble() / minOf(gx.size, gy.size)
    }

    fun toJson(moves: List<ForeshadowMove>): JSONArray = JSONArray().apply {
        moves.forEach { put(JSONObject().put("action", it.action.name).put("text", it.text).put("id", it.ledgerId)) }
    }

    /** 读细纲卡里存的几条；旧卡片没有这一项为空，认不出的条目跳过。 */
    fun fromJson(a: JSONArray?): List<ForeshadowMove> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val action = MoveAction.entries.firstOrNull { it.name == o.optString("action") } ?: return@mapNotNull null
        val text = o.optString("text").trim()
        if (text.isEmpty()) null else ForeshadowMove(action, text, o.optString("id"))
    }
}
