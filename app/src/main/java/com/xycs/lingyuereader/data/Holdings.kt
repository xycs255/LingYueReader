package com.xycs.lingyuereader.data

/**
 * 对账查出的一处对不上：[title] 一句话说是什么问题；[first]、[second] 是两处账本条目（各带原句和章），
 * [chapterIds] 是牵涉的章（问题面板按当前这一章挑）。
 */
data class HoldIssue(val title: String, val first: LedgerEntry, val second: LedgerEntry) {
    val chapterIds: Set<String> get() = setOf(first.chapterId, second.chapterId).filter { it.isNotEmpty() }.toSet()
    val key: String get() = "hold\u0000${first.id}\u0000${second.id}"
}

/**
 * 数值与持有（[LedgerKind.HOLD]）：境界、等级、灵石这类随章变化的数值，和关键物品在谁手里。
 * 一条记一个人（[LedgerEntry.subject]）的一项（[LedgerEntry.aspect]）在这一章的“期初 → 变化 → 期末”。
 * 本机对账（[check]）：同一项上一次记的期末要等于这一次的期初；一件物品同一时间只能在一个人手里。
 */
object Holdings {
    /** 物品“没有了”的说法：期末写成这样的，不算在这个人手里。 */
    private val GONE = Regex("^(无|没有|没了|已?失去|已?交出|已?送出|已?丢失?|丢了|不在|被夺|被抢|被偷|已?毁|毁了|碎了|0|零)")

    /** 物品的这一条是不是还在 [LedgerEntry.subject] 手里。 */
    fun held(e: LedgerEntry): Boolean = e.item && !GONE.containsMatchIn(e.text.trim())

    /** 比数值用的写法：去掉空白、标点，统一全半角。“筑基 初期”和“筑基初期”算一样。 */
    internal fun norm(s: String): String = ChapterSyncs.normalize(s).replace(Regex("[\\p{P}\\p{S}]"), "")

    /** 同一人同一项的键（不分物品、数值）。 */
    private fun key(e: LedgerEntry) = e.subject.trim() + "|" + e.aspect.trim()

    private val order = compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt })

    /**
     * 每个人每一项最新的一条（按从第几章起，同一章取后改的）；物品只留还在手里的。按人、项排。
     * [entries] 由调用方先筛好“到这一章为止”的。
     */
    fun current(entries: List<LedgerEntry>): List<LedgerEntry> =
        entries.filter { it.kind == LedgerKind.HOLD && it.subject.isNotBlank() && it.aspect.isNotBlank() }
            .groupBy(::key).values.map { it.maxWith(order) }
            .filter { !it.item || held(it) }
            .sortedWith(compareBy({ it.subject }, { it.item }, { it.aspect }))

    /** 写进请求、界面上看的一行：“林月·灵石：120 → 花去 30 → 90”“林月·青铜灯：持有”。 */
    fun line(e: LedgerEntry): String = buildString {
        append(e.subject.trim()).append("·").append(e.aspect.trim()).append("：")
        if (e.item) append(if (held(e)) "在他手里" else e.text.trim())
        else {
            if (e.before.isNotBlank() && norm(e.before) != norm(e.text)) append(e.before.trim()).append(" → ")
            if (e.delta.isNotBlank()) append(e.delta.trim()).append(" → ")
            append(e.text.trim())
        }
        e.from?.let { append("（第 ").append(it).append(" 章）") }
    }

    /**
     * 本机对账，全部在本机算：
     * 1. 同一人同一项，按章排，后一条写了期初的，期初要等于前一条的期末（规范化后比）；
     * 2. 物品：按章走一遍，同一章记完以后，同一件物品（[LedgerEntry.aspect] 相同）在两个以上的人手里，报一处，给出两人各自的原句。
     *    交给别人的那一章，把原来持有人那一条写成“交出”就不算。
     * 只有作者知道的不查。结果按后一处的章排。
     */
    fun check(entries: List<LedgerEntry>): List<HoldIssue> {
        val holds = entries.filter { it.kind == LedgerKind.HOLD && !it.secret && it.subject.isNotBlank() && it.aspect.isNotBlank() }
        val out = mutableListOf<HoldIssue>()
        holds.groupBy(::key).values.forEach { group ->
            group.sortedWith(order).zipWithNext().forEach { (a, b) ->
                if (b.before.isBlank() || a.text.isBlank()) return@forEach
                // 物品比“在不在手里”（期末写“持有”、下一章期初写“拿着”是一回事）；数值比写法。
                val differs = if (a.item && b.item) held(a) != heldWord(b.before) else norm(b.before) != norm(a.text)
                if (differs) {
                    out += HoldIssue("${b.subject}的「${b.aspect}」：${chapterOf(b)}期初写的「${b.before.trim()}」，和${chapterOf(a)}记的期末「${a.text.trim()}」对不上", a, b)
                }
            }
        }
        // 物品：按章依次记下谁手里有，每章记完查一次。
        val items = holds.filter { it.item }.sortedWith(order)
        val holders = HashMap<String, LinkedHashMap<String, LedgerEntry>>()
        val reported = HashSet<Pair<String, String>>()
        items.groupBy { it.from ?: -1 }.toSortedMap().forEach { (_, batch) ->
            batch.forEach { e ->
                val who = holders.getOrPut(e.aspect.trim()) { LinkedHashMap() }
                if (held(e)) who[e.subject.trim()] = e else who.remove(e.subject.trim())
            }
            holders.forEach { (thing, who) ->
                if (who.size < 2) return@forEach
                val list = who.values.sortedWith(order)
                val (a, b) = list[list.size - 2] to list.last()
                if (reported.add(thing to (a.id + b.id))) {
                    out += HoldIssue("「$thing」同时在${a.subject}和${b.subject}手里：${chapterOf(b)}记${b.subject}拿着，${chapterOf(a)}记的${a.subject}还没交出", a, b)
                }
            }
        }
        return out.distinctBy { it.key }.sortedBy { it.second.from ?: -1 }
    }

    /** 期初写的是不是“有”（物品：期初写“持有”、期末写“没有”这类的比较）。 */
    private fun heldWord(s: String): Boolean = s.isNotBlank() && !GONE.containsMatchIn(s.trim())

    private fun chapterOf(e: LedgerEntry) = e.from?.let { "第 $it 章" } ?: "开头"
}
