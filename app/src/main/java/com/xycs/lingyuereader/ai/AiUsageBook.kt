package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.DurableFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一格用量：某部作品（[work] 空是阅读）、某个功能（[AiFeature] 的名字）在一天（或一个月）里加起来用了多少。
 * 只有数字，不存请求内容。
 */
data class UsageRow(
    val work: String,
    val feature: String,
    val usage: AiUsage = AiUsage(),
    val cost: AiCost = AiCost(),
) {
    operator fun plus(o: UsageRow) = copy(usage = usage + o.usage, cost = cost + o.cost)

    fun toJson(): JSONObject = JSONObject()
        .put("work", work).put("feature", feature)
        .put("req", usage.requests).put("est", usage.estimatedRequests)
        .put("in", usage.input).put("hit", usage.cached).put("out", usage.output).put("write", usage.cacheWrite)
        .put("cny", cost.cny).put("usd", cost.usd).put("unpriced", cost.unpriced)

    companion object {
        fun fromJson(o: JSONObject): UsageRow = UsageRow(
            o.optString("work"),
            o.optString("feature"),
            AiUsage(o.optLong("in"), o.optLong("hit"), o.optLong("out"), o.optLong("write"), o.optInt("req"), o.optInt("est")),
            AiCost(o.optDouble("cny", 0.0), o.optDouble("usd", 0.0), o.optInt("unpriced")),
        )
    }
}

/**
 * 用量账本：最近 [KEEP_DAYS] 天按天记明细（每天按作品、功能分格），更早的并成按月的汇总。
 * 不可变：[add]、[compact] 返回新的一份。
 */
data class UsageBook(
    val days: Map<LocalDate, List<UsageRow>> = emptyMap(),
    val months: Map<YearMonth, List<UsageRow>> = emptyMap(),
    /** 每天花费提醒已经提醒过的那天（一天只提醒一次）。 */
    val alerted: String = "",
) {
    /** 记一笔：加进 [day] 那天、[work] 和 [feature] 那一格。 */
    fun add(day: LocalDate, work: String, feature: AiFeature, usage: AiUsage, cost: AiCost): UsageBook {
        val rows = days[day].orEmpty()
        val row = UsageRow(work, feature.name, usage, cost)
        val merged = if (rows.any { it.work == work && it.feature == feature.name }) rows.map { if (it.work == work && it.feature == feature.name) it + row else it } else rows + row
        return copy(days = days + (day to merged))
    }

    /** 早于 [today] 往前 [KEEP_DAYS] 天的明细并进按月汇总（同一月同一格加在一起）。 */
    fun compact(today: LocalDate): UsageBook {
        val cutoff = today.minusDays((KEEP_DAYS - 1).toLong())
        val old = days.filterKeys { it.isBefore(cutoff) }
        if (old.isEmpty()) return this
        val months = months.toMutableMap()
        old.forEach { (day, rows) ->
            val m = YearMonth.from(day)
            months[m] = merge(months[m].orEmpty() + rows)
        }
        return copy(days = days - old.keys, months = months)
    }

    /** [from] 到 [to]（含）各天的明细；整月都早于明细的，用按月汇总补上（[from] 为空是全部）。 */
    fun rows(from: LocalDate?, to: LocalDate): List<UsageRow> {
        val inDays = days.filterKeys { (from == null || !it.isBefore(from)) && !it.isAfter(to) }.values.flatten()
        val inMonths = months.filterKeys { m -> from == null || !m.atDay(1).isBefore(from.withDayOfMonth(1)) }.values.flatten()
        return merge(inDays + inMonths)
    }

    fun day(d: LocalDate): UsageRow = total(days[d].orEmpty())

    companion object {
        /** 明细留多少天。 */
        const val KEEP_DAYS = 90

        /** 同一格（作品、功能）加在一起。 */
        fun merge(rows: List<UsageRow>): List<UsageRow> =
            rows.groupBy { it.work to it.feature }.map { (k, list) -> list.fold(UsageRow(k.first, k.second)) { a, b -> a + b } }

        fun total(rows: List<UsageRow>): UsageRow = rows.fold(UsageRow("", "")) { a, b -> a + b }

        fun toJson(b: UsageBook): String = JSONObject()
            .put("days", JSONObject().apply { b.days.toSortedMap().forEach { (d, rows) -> put(d.toString(), JSONArray().apply { rows.forEach { put(it.toJson()) } }) } })
            .put("months", JSONObject().apply { b.months.toSortedMap().forEach { (m, rows) -> put(m.toString(), JSONArray().apply { rows.forEach { put(it.toJson()) } }) } })
            .put("alerted", b.alerted)
            .toString()

        fun fromJson(raw: String): UsageBook {
            val o = JSONObject(raw)
            fun rows(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.let(UsageRow::fromJson) }
            val days = o.optJSONObject("days")?.let { d -> d.keys().asSequence().mapNotNull { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { it to rows(d.optJSONArray(k)) } }.toMap() }.orEmpty()
            val months = o.optJSONObject("months")?.let { d -> d.keys().asSequence().mapNotNull { k -> runCatching { YearMonth.parse(k) }.getOrNull()?.let { it to rows(d.optJSONArray(k)) } }.toMap() }.orEmpty()
            return UsageBook(days, months, o.optString("alerted"))
        }
    }
}

/**
 * 用量账本存在 [file]（应用私有目录的 ai-usage.json）。只记数字；不进灵阅的备份（和 AI 设置一样只留在这台手机上），
 * 读不出时另存一份坏文件、从空的开始。
 */
class UsageStore(private val file: File, private val today: () -> LocalDate = LocalDate::now) {
    private var cached: UsageBook? = null

    @Synchronized
    fun load(): UsageBook = cached ?: read().also { cached = it }

    private fun read(): UsageBook {
        if (!file.isFile) return UsageBook()
        return try {
            UsageBook.fromJson(file.readText())
        } catch (e: Exception) {
            DurableFiles.keepDamaged(file)
            UsageBook()
        }
    }

    /** 记一次请求；返回记好的账本。 */
    @Synchronized
    fun add(work: String, feature: AiFeature, usage: AiUsage, cost: AiCost): UsageBook = save(load().add(today(), work, feature, usage, cost).compact(today()))

    /** 记下今天提醒过了。 */
    @Synchronized
    fun markAlerted(day: LocalDate): UsageBook = save(load().copy(alerted = day.toString()))

    /** 清空（用量页“清除用量记录”）。 */
    @Synchronized
    fun clear(): UsageBook = save(UsageBook())

    private fun save(b: UsageBook): UsageBook {
        cached = b
        file.parentFile?.mkdirs()
        DurableFiles.write(file, UsageBook.toJson(b).toByteArray())
        return b
    }

    companion object {
        const val FILE = "ai-usage.json"
    }
}
