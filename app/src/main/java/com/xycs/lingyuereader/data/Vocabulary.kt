package com.xycs.lingyuereader.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 生词本里的一个词：查词时收进来，带上释义摘要和它在书里的原句。 */
data class VocabWord(
    val word: String,
    /** 读音（拼音等），可能为空。 */
    val reading: String,
    /** 释义摘要。 */
    val gloss: String,
    /** 原句。 */
    val context: String,
    val bookId: String,
    val bookTitle: String,
    val addedAt: Long,
    /** 查过几次（收入后再查会累加）。 */
    val lookups: Int = 1,
    val mastered: Boolean = false,
    val reviewedAt: Long = 0,
    /** 复习间隔（天）：0 表示刚收进来或上次没记住。 */
    val interval: Int = 0,
    /** 下次该复习的时间；0 表示随时。 */
    val due: Long = 0,
) {
    /** 学习中、且到了该复习的时候。 */
    fun isDue(now: Long): Boolean = !mastered && due <= now
}

/** 生词本，存在 files/vocabulary.json。按词去重，后收的覆盖原句与释义。 */
class Vocabulary(private val file: File) {
    private val atomicFile = AtomicFile(file)

    @Synchronized
    fun list(): List<VocabWord> = runCatching { parse(atomicFile.readFully().toString(Charsets.UTF_8)) }.getOrDefault(emptyList())

    fun contains(word: String): Boolean = list().any { it.word == word }

    @Synchronized
    fun add(w: VocabWord) = update { list ->
        val old = list.firstOrNull { it.word == w.word }
        if (old == null) {
            listOf(w) + list
        } else {
            listOf(w.copy(addedAt = old.addedAt, lookups = old.lookups + 1, mastered = false)) + list.filterNot { it.word == w.word }
        }
    }

    /** 已收入的词又查了一次。 */
    fun touch(word: String) = update { list -> list.map { if (it.word == word) it.copy(lookups = it.lookups + 1) else it } }

    fun remove(word: String) = update { list -> list.filterNot { it.word == word } }

    fun setMastered(word: String, mastered: Boolean, now: Long = System.currentTimeMillis()) = update { list ->
        list.map { if (it.word == word) it.copy(mastered = mastered, reviewedAt = now, interval = if (mastered) it.interval else 0, due = if (mastered) it.due else 0) else it }
    }

    /**
     * 复习了一次（按遗忘曲线排下次）：记得就把间隔拉长（1 天 → 3 天 → 约 2.5 倍），到期日从当天零点算起；
     * 没记住就从头来，今天还会再出现。间隔超过两个月就算掌握了。
     */
    fun review(word: String, known: Boolean, now: Long = System.currentTimeMillis()) = update { list ->
        list.map { w ->
            if (w.word != word) return@map w
            if (!known) return@map w.copy(interval = 0, due = now, reviewedAt = now, mastered = false)
            val interval = nextInterval(w.interval)
            w.copy(interval = interval, due = startOfDay(now) + interval * DAY, reviewedAt = now, mastered = interval > MASTERED_AFTER)
        }
    }

    /** 恢复备份：按词合并，两边都有时取较新的。 */
    fun merge(json: String) = update { list ->
        val incoming = runCatching { parse(json) }.getOrDefault(emptyList())
        val byWord = LinkedHashMap<String, VocabWord>()
        (list + incoming).forEach { w ->
            val old = byWord[w.word]
            if (old == null || maxOf(w.addedAt, w.reviewedAt) > maxOf(old.addedAt, old.reviewedAt)) byWord[w.word] = w
        }
        byWord.values.sortedByDescending { it.addedAt }
    }

    @Synchronized
    fun raw(): ByteArray? = if (file.isFile || File(file.path + ".bak").isFile) atomicFile.readFully() else null

    fun toMarkdown(words: List<VocabWord> = list()): String = buildString {
        append("# 生词本\n\n")
        words.forEach { w ->
            append("## ").append(w.word)
            if (w.reading.isNotBlank()) append("（").append(w.reading).append("）")
            append("\n\n")
            if (w.gloss.isNotBlank()) append(w.gloss).append("\n\n")
            if (w.context.isNotBlank()) append("> ").append(w.context).append("\n>\n> ——《").append(w.bookTitle).append("》\n\n")
        }
    }

    @Synchronized
    private fun update(transform: (List<VocabWord>) -> List<VocabWord>) {
        val array = JSONArray()
        transform(list()).forEach { w ->
            array.put(
                JSONObject().put("word", w.word).put("reading", w.reading).put("gloss", w.gloss).put("context", w.context)
                    .put("bookId", w.bookId).put("bookTitle", w.bookTitle).put("addedAt", w.addedAt)
                    .put("lookups", w.lookups).put("mastered", w.mastered).put("reviewedAt", w.reviewedAt)
                    .put("interval", w.interval).put("due", w.due),
            )
        }
        file.parentFile?.mkdirs()
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
            check(atomicFile.readFully().contentEquals(bytes)) { "无法保存生词本" }
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun parse(json: String): List<VocabWord> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val word = o.optString("word").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VocabWord(
                word = word,
                reading = o.optString("reading"),
                gloss = o.optString("gloss"),
                context = o.optString("context"),
                bookId = o.optString("bookId"),
                bookTitle = o.optString("bookTitle"),
                addedAt = o.optLong("addedAt"),
                lookups = o.optInt("lookups", 1),
                mastered = o.optBoolean("mastered"),
                reviewedAt = o.optLong("reviewedAt"),
                interval = o.optInt("interval"),
                due = o.optLong("due"),
            )
        }
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** 间隔超过这么多天就算掌握。 */
        const val MASTERED_AFTER = 60

        fun nextInterval(current: Int): Int = when {
            current <= 0 -> 1
            current == 1 -> 3
            else -> kotlin.math.round(current * 2.5).toInt()
        }

        fun startOfDay(time: Long): Long = java.util.Calendar.getInstance().apply {
            timeInMillis = time
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** 取 [range] 所在的那一句作原句，太长就截取词前后各一段。 */
        fun contextOf(text: String, range: IntRange, maxLength: Int = 80): String {
            if (text.isEmpty()) return ""
            val enders = "。！？!?；;…\n"
            var start = range.first.coerceIn(0, text.length)
            while (start > 0 && text[start - 1] !in enders) start--
            var end = (range.last + 1).coerceIn(0, text.length)
            while (end < text.length && text[end] !in enders) end++
            if (end < text.length) end++ // 带上句末标点
            // 句末的引号也带上。
            while (end < text.length && text[end] in "”’」』\"") end++
            var s = text.substring(start, end).trim()
            if (s.length > maxLength) {
                val wordStart = range.first - start
                val from = (wordStart - maxLength / 2).coerceIn(0, s.length - maxLength)
                s = (if (from > 0) "…" else "") + s.substring(from, from + maxLength) + (if (from + maxLength < s.length) "…" else "")
            }
            return s
        }
    }
}
