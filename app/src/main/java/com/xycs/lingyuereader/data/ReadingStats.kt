package com.xycs.lingyuereader.data

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDate

/** 某一天的阅读：时长（毫秒）、读过的字数，以及各本书的时长。 */
data class DayStats(val millis: Long = 0, val chars: Long = 0, val books: Map<String, Long> = emptyMap())

/**
 * 阅读统计，存在 files/stats.json：按天记时长与字数。
 * 字数只算顺着往下读的部分（跳章、拖进度条不算），用来估计个人阅读速度。
 */
class ReadingStats internal constructor(private val file: File, private val atomicFile: AtomicFile) {
    constructor(file: File) : this(file, AtomicFile(file))

    @Synchronized
    fun days(): Map<LocalDate, DayStats> {
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyMap()
        // 文件存在时，读盘失败不能当作空统计：下一次更新会覆盖原有记录。
        return try {
            parse(atomicFile.readFully().toString(Charsets.UTF_8))
        } catch (error: Exception) {
            DurableFiles.keepDamaged(file)
            throw IllegalStateException("阅读统计读取失败，原文件已保留，未写入新数据", error)
        }
    }

    @Synchronized
    fun addTime(bookId: String, millis: Long, day: LocalDate = LocalDate.now()) {
        if (millis <= 0) return
        update(day) { it.copy(millis = it.millis + millis, books = it.books + (bookId to (it.books[bookId] ?: 0) + millis)) }
    }

    @Synchronized
    fun addChars(chars: Long, day: LocalDate = LocalDate.now()) {
        if (chars <= 0) return
        update(day) { it.copy(chars = it.chars + chars) }
    }

    /**
     * 恢复备份：按天合并，同一天取较大的数（同一份数据恢复两次不会翻倍）。
     * 本机统计坏了（读得出文件、解析不了）时直接采用备份里的：坏文件先另存，这正是“从备份恢复”要修的情况。
     * 读盘本身失败（不是内容坏）仍然报错，不覆盖。
     */
    @Synchronized
    fun merge(json: String) {
        val incoming = parse(json)
        val local = if (!file.exists() && !File(file.path + ".bak").exists()) emptyMap() else {
            val bytes = atomicFile.readFully()
            try {
                parse(bytes.toString(Charsets.UTF_8))
            } catch (error: Exception) {
                check(DurableFiles.keptDamaged(file, bytes)) { "本机阅读统计已损坏，且无法另存原文件，未恢复" }
                emptyMap()
            }
        }
        val all = local.toMutableMap()
        incoming.forEach { (day, d) ->
            val old = all[day]
            all[day] = if (old == null) d else DayStats(
                maxOf(old.millis, d.millis),
                maxOf(old.chars, d.chars),
                (old.books.keys + d.books.keys).associateWith { maxOf(old.books[it] ?: 0, d.books[it] ?: 0) },
            )
        }
        write(all)
    }

    /** 在替换任何本机书籍前先验证备份结构；不读取或修改本机统计。 */
    fun validateBackup(json: String) {
        parse(json)
    }

    @Synchronized
    fun raw(): ByteArray? = if (file.isFile || File(file.path + ".bak").isFile) atomicFile.readFully() else null

    private fun update(day: LocalDate, transform: (DayStats) -> DayStats) {
        val all = days().toMutableMap()
        all[day] = transform(all[day] ?: DayStats())
        write(all)
    }

    private fun write(all: Map<LocalDate, DayStats>) {
        val days = JSONObject()
        all.toSortedMap().forEach { (day, d) ->
            days.put(
                day.toString(),
                JSONObject().put("ms", d.millis).put("chars", d.chars).put("books", JSONObject().apply { d.books.forEach { (k, v) -> put(k, v) } }),
            )
        }
        file.parentFile?.mkdirs()
        val bytes = JSONObject().put("days", days).toString().toByteArray(Charsets.UTF_8)
        val output: FileOutputStream = atomicFile.startWrite()
        try {
            output.write(bytes)
        } catch (error: Exception) {
            // 仅写入阶段可以回滚；旧版 Android 的 finishWrite 已删除 .bak。
            atomicFile.failWrite(output)
            throw error
        }
        atomicFile.finishWrite(output)
        check(atomicFile.readFully().contentEquals(bytes)) { "无法保存阅读统计" }
    }

    private fun parse(json: String): Map<LocalDate, DayStats> {
        val days = JSONObject(json).getJSONObject("days")
        return days.keys().asSequence().associate { key ->
            val day = LocalDate.parse(key)
            val o = days.getJSONObject(key)
            val books = if (o.has("books")) o.getJSONObject("books") else null
            day to DayStats(
                nonNegativeLong(o, "ms"),
                nonNegativeLong(o, "chars"),
                books?.keys()?.asSequence()?.associateWith { nonNegativeLong(books, it) } ?: emptyMap(),
            )
        }
    }

    /** 旧版可缺字段；已写出的字段若坏了，不能当 0 后继续覆盖整份统计。 */
    private fun nonNegativeLong(json: JSONObject, key: String): Long {
        if (!json.has(key)) return 0L
        val value = json.get(key)
        require(value is Number) { "阅读统计中的 $key 不是数字" }
        val number = try {
            BigDecimal(value.toString()).toBigIntegerExact().let { whole ->
                require(whole.bitLength() <= 63) { "阅读统计中的 $key 超出整数范围" }
                whole.toLong()
            }
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("阅读统计中的 $key 不是有效整数", error)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("阅读统计中的 $key 不是有效整数", error)
        }
        require(number >= 0) { "阅读统计中的 $key 不能为负数" }
        return number
    }

    companion object {
        /** 没有足够数据时按每分钟 400 字估算（中文小说的常见阅读速度）。 */
        const val DEFAULT_CHARS_PER_MINUTE = 400

        /** 个人阅读速度（字/分钟）：最近 30 天有字数记录的日子，总字数除以总时长；不足 10 分钟的数据就用默认值。 */
        fun charsPerMinute(days: Map<LocalDate, DayStats>, today: LocalDate = LocalDate.now()): Int {
            val recent = days.filter { (day, d) -> !day.isBefore(today.minusDays(30)) && d.chars > 0 }
            val millis = recent.values.sumOf { it.millis }
            if (millis < 10 * 60_000) return DEFAULT_CHARS_PER_MINUTE
            val speed = recent.values.sumOf { it.chars } * 60_000 / millis
            return speed.toInt().coerceIn(100, 2000)
        }

        /** 截至 [today] 连续有阅读的天数（今天还没读就从昨天算起）。 */
        fun streak(days: Map<LocalDate, DayStats>, today: LocalDate = LocalDate.now()): Int {
            fun read(d: LocalDate) = (days[d]?.millis ?: 0) >= 60_000
            var day = if (read(today)) today else today.minusDays(1)
            var n = 0
            while (read(day)) {
                n++
                day = day.minusDays(1)
            }
            return n
        }

        /** [from]..[to]（含）之间的总时长。 */
        fun total(days: Map<LocalDate, DayStats>, from: LocalDate, to: LocalDate): Long =
            days.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sumOf { it.millis }
    }
}
