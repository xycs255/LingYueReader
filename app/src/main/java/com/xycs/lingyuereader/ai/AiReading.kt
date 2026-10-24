package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.spokenText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 速读的范围：读过的章节、从当前起接下来 10 章、全书。 */
enum class SummaryRange(val label: String) { READ("读过的"), NEXT("接下来 10 章"), ALL("全书") }

/** 一章的正文（不含 AI 译文）；太长时取开头和结尾各一半，中间用省略号接上。 */
fun chapterExcerpt(chapter: Chapter, limit: Int): String {
    val text = chapter.blocks.mapNotNull { b -> b.spokenText?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    if (text.length <= limit) return text
    return text.take(limit / 2) + "\n……（中间略）……\n" + text.takeLast(limit / 2)
}

/** 从模型回答里取出 JSON 对象：容忍前后多余的话和 ```json 包裹。 */
internal fun jsonIn(answer: String): JSONObject? {
    val start = answer.indexOf('{')
    val end = answer.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull()
}

/**
 * 章节速读：每章一段几十字的摘要，显示在目录里。几章合成一次请求（每次至多 [PACK] 字），
 * 长章节取开头和结尾。存在书目录里（ai-summaries.json），按章名核对。
 */
object AiSummaries {
    const val FILE = "ai-summaries.json"
    const val PACK = 16_000
    const val PER_CHAPTER = 12_000

    /** 一次请求：哪几章、拼好的原文。 */
    class Pack(val chapters: List<Int>, val text: String)

    fun packs(content: BookContent, indices: List<Int>): List<Pack> {
        val out = ArrayList<Pack>()
        val ids = ArrayList<Int>()
        val buf = StringBuilder()
        indices.distinct().sorted().forEach { i ->
            val ch = content.chapters.getOrNull(i) ?: return@forEach
            val text = chapterExcerpt(ch, PER_CHAPTER)
            if (text.isBlank()) return@forEach
            val piece = "【第 ${i + 1} 章 ${ch.title}】\n$text\n\n"
            if (buf.length + piece.length > PACK && ids.isNotEmpty()) {
                out += Pack(ids.toList(), buf.toString())
                ids.clear()
                buf.setLength(0)
            }
            ids += i
            buf.append(piece)
        }
        if (ids.isNotEmpty()) out += Pack(ids.toList(), buf.toString())
        return out
    }

    /** 这几章大约多少字（给读者估算用）。 */
    fun size(content: BookContent, indices: List<Int>): Int = indices.sumOf { content.chapters.getOrNull(it)?.charCount ?: 0 }

    fun prompt(pack: Pack): String = buildString {
        append("下面是一本书里的几章原文，【】里是章序号和章名。\n\n")
        append(pack.text)
        append("给每章写一段速读摘要：60 字以内，说清这一章发生了什么、讲了什么，不评论。")
        append("只输出 JSON：{\"summaries\":[{\"i\":章序号,\"s\":\"摘要\"}]}")
    }

    /** 按章序号对回；只收这次请求里有的章。返回的键是从 0 起的章下标。 */
    fun parse(answer: String, pack: Pack): Map<Int, String> {
        val a = jsonIn(answer)?.optJSONArray("summaries") ?: return emptyMap()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val i = o.optInt("i", -1) - 1
            val s = o.optString("s").trim()
            if (i in pack.chapters && s.isNotEmpty()) i to s else null
        }.toMap()
    }

    /** 章下标 → (章名, 摘要)。 */
    fun read(file: File?): Map<Int, Pair<String, String>> = runCatching {
        val o = JSONObject(file!!.readText())
        o.keys().asSequence().associate { k -> o.getJSONObject(k).let { k.toInt() to (it.getString("t") to it.getString("s")) } }
    }.getOrDefault(emptyMap())

    fun write(file: File?, map: Map<Int, Pair<String, String>>) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val o = JSONObject()
            map.forEach { (i, p) -> o.put(i.toString(), JSONObject().put("t", p.first).put("s", p.second)) }
            f.writeText(o.toString())
        }
    }

    /** 只留章名对得上的（改稿后章序可能变了）。 */
    fun valid(content: BookContent, map: Map<Int, Pair<String, String>>): Map<Int, String> =
        map.filter { (i, p) -> content.chapters.getOrNull(i)?.title == p.first }.mapValues { it.value.second }
}

/** 伴读：本章要点、几道思考题。 */
data class ChapterCompanion(val points: List<String>, val questions: List<String>)

/**
 * AI 伴读：读完一章，给出本章要点和几道思考题，点一道题和 AI 讨论（只看读过的部分）。
 * 按章存在书目录里（ai-companion.json）。
 */
object AiCompanion {
    const val FILE = "ai-companion.json"
    const val LIMIT = 16_000

    fun prompt(chapterTitle: String, text: String, previous: String?): String = buildString {
        if (!previous.isNullOrBlank()) append("前情（上一章的速读）：").append(previous).append("\n\n")
        append("读者刚读完「").append(chapterTitle).append("」，原文如下：\n\n").append(text).append("\n\n")
        append("请当他的伴读：\n")
        append("1. points：本章要点 3 到 5 条，每条一句话，抓住情节推进、人物变化和值得留意的细节；\n")
        append("2. questions：3 道开放的思考题，引读者回味本章、联系前文，不问后面会怎样，不剧透。\n")
        append("只输出 JSON：{\"points\":[\"\"],\"questions\":[\"\"]}")
    }

    fun parse(answer: String): ChapterCompanion? {
        val o = jsonIn(answer) ?: return null
        fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        val c = ChapterCompanion(list("points"), list("questions"))
        return c.takeIf { it.points.isNotEmpty() || it.questions.isNotEmpty() }
    }

    fun read(file: File?): Map<Int, Pair<String, ChapterCompanion>> = runCatching {
        val o = JSONObject(file!!.readText())
        o.keys().asSequence().associate { k ->
            val c = o.getJSONObject(k)
            fun list(name: String) = c.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } }
            k.toInt() to (c.getString("t") to ChapterCompanion(list("points"), list("questions")))
        }
    }.getOrDefault(emptyMap())

    fun write(file: File?, map: Map<Int, Pair<String, ChapterCompanion>>) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val o = JSONObject()
            map.forEach { (i, p) ->
                o.put(i.toString(), JSONObject().put("t", p.first).put("points", JSONArray(p.second.points)).put("questions", JSONArray(p.second.questions)))
            }
            f.writeText(o.toString())
        }
    }
}
