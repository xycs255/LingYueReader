package com.xycs.lingyuereader.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 讲书的形式：一个人讲，或两个人聊。 */
enum class TalkStyle(val label: String) { SOLO("一人讲解"), DUO("两人对谈") }

/** 讲哪部分：刚读的这一章，或读到这里为止的全书（最近的部分）。 */
enum class TalkScope(val label: String) { CHAPTER("讲本章"), READ("讲读过的部分") }

/** 讲书稿的一句：谁说（0 主讲，1 搭档）、说什么。 */
data class TalkLine(val speaker: Int, val text: String)

data class Talk(val title: String, val lines: List<TalkLine>, val at: Long = 0L)

/**
 * AI 讲书：先让模型把原文讲成一篇口语稿（一人讲解，或两人对谈的播客），再一句句用语音读出来。
 * 只用给出的原文，不剧透。稿子按“范围+形式”存在书目录里（ai-talks.json），重听不再花钱。
 */
object AiTalk {
    const val FILE = "ai-talks.json"
    const val LIMIT = 16_000

    /** 两个人的称呼。 */
    val NAMES = listOf("主讲", "搭档")

    fun key(scope: TalkScope, style: TalkStyle, chapter: Int, title: String): String = "${scope.name}-${style.name}-$chapter-$title"

    fun prompt(style: TalkStyle, scope: TalkScope, chapterTitle: String, text: String): String = buildString {
        append(if (scope == TalkScope.CHAPTER) "下面是「$chapterTitle」的原文：\n\n" else "下面是读者到「$chapterTitle」为止最近读过的内容，【】里是章名：\n\n")
        append(text).append("\n\n")
        when (style) {
            TalkStyle.SOLO -> append("把它讲成一段适合收听的讲书稿：一位主讲人用口语讲清发生了什么、人物和细节的妙处，穿插少量原文引用，像说书或电台节目。")
            TalkStyle.DUO -> append("把它做成一期两人对谈的读书播客：主讲（speaker 0）熟悉内容、负责讲，搭档（speaker 1）提问、接话、说感受，一来一回，自然口语。")
        }
        append("全稿 1200 到 2000 字。每句不超过 80 字，适合朗读，不用括号注释和表情。只根据上面的原文，不提后面的情节。\n")
        append("只输出 JSON：{\"title\":\"一个标题\",\"lines\":[{\"s\":0,\"t\":\"一句话\"}]}")
    }

    /** 解析讲书稿：长句按句末标点拆短（朗读一句一句地合成，太长等得久）。 */
    fun parse(answer: String, style: TalkStyle): Talk? {
        val o = jsonIn(answer) ?: return null
        val a = o.optJSONArray("lines") ?: return null
        val lines = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.flatMap { l ->
            val speaker = if (style == TalkStyle.SOLO) 0 else l.optInt("s", 0).coerceIn(0, 1)
            split(l.optString("t").trim()).map { TalkLine(speaker, it) }
        }
        if (lines.isEmpty()) return null
        return Talk(o.optString("title").trim().ifEmpty { "AI 讲书" }, lines)
    }

    /** 超过 [MAX_LINE] 字的句子按句末标点再拆。 */
    internal fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_LINE) return listOf(text)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in "。！？；!?;" && buf.length >= 20) {
                out += buf.toString().trim()
                buf.setLength(0)
            }
        }
        if (buf.isNotBlank()) out += buf.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private const val MAX_LINE = 80

    /** 显示用：读不出时按没有存过的稿子算（坏文件已另存一份）。 */
    fun read(file: File?): Map<String, Talk> = runCatching { load(file) }.getOrDefault(emptyMap())

    /** 改了要存回去时用：连坏文件的副本都存不下就抛出（见 [AiFiles.load]）。 */
    fun load(file: File?): Map<String, Talk> = AiFiles.load(file) { text ->
        val o = JSONObject(text)
        o.keys().asSequence().associate { k ->
            val t = o.getJSONObject(k)
            val a = t.getJSONArray("lines")
            k to Talk(t.getString("title"), (0 until a.length()).map { i -> a.getJSONObject(i).let { TalkLine(it.getInt("s"), it.getString("t")) } }, t.optLong("at"))
        }
    }.orEmpty()

    /** 原子写（见 [AiFiles.write]）：写不成就抛出。 */
    fun write(file: File?, map: Map<String, Talk>) {
        val o = JSONObject()
        // 只留最近的 30 篇。
        map.entries.sortedByDescending { it.value.at }.take(30).forEach { (k, t) ->
            o.put(k, JSONObject().put("title", t.title).put("at", t.at).put("lines", JSONArray().apply { t.lines.forEach { put(JSONObject().put("s", it.speaker).put("t", it.text)) } }))
        }
        AiFiles.write(file, o.toString())
    }
}
