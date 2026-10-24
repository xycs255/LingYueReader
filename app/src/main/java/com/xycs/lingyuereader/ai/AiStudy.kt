package com.xycs.lingyuereader.ai

import org.json.JSONArray
import org.json.JSONObject

/** 读书笔记、生词本的提示词和回答解析。 */
object AiStudy {
    /** 读书笔记最多发多少字的划线批注（太多时留最近的）。 */
    const val NOTES_CHARS = 24_000
    /** 一次补释义最多几个词。 */
    const val GLOSS_BATCH = 30

    const val NOTES_FILE = "ai-notes.md"

    /** [marks] 是这本书的划线批注（Markdown，按章排好）。 */
    fun notes(title: String, marks: String): String = buildString {
        val material = if (marks.length > NOTES_CHARS) "……（前面略）\n" + marks.takeLast(NOTES_CHARS) else marks
        append("下面是读者读《").append(title).append("》时的划线（> 引用的是原文）和批注。\n\n")
        append(material)
        append("\n\n请据此整理一篇读书笔记，用 Markdown：\n")
        append("1. 开头一段：这些划线和批注反映出读者关注什么。\n")
        append("2. 按章节分节：每节先用一两句话说这一章读者划出的要点，再列摘句（原文照引，不改字），再整理读者的想法（保留原意，理顺语句）。\n")
        append("3. 最后一节“总结与思考”：串起各章的线索，提两三个值得再想的问题。\n")
        append("只根据上面的材料，不编造原文没有的情节，不剧透后文。")
    }

    fun example(word: String, gloss: String, context: String): String = buildString {
        append("词语：").append(word).append('\n')
        if (gloss.isNotBlank()) append("释义：").append(gloss.take(300)).append('\n')
        if (context.isNotBlank()) append("读到它的原句：").append(context).append('\n')
        append("\n帮读者记住这个词：先用一句话说它在原句里的意思和用法要点，再给两个日常的例句（每句不超过 30 字），")
        append("最后如有易混的近义词，用一句话区分。用纯文本，总共不超过 150 字。")
    }

    /** 一批词一起补释义：带原句，按语境释义。 */
    fun glosses(words: List<Pair<String, String>>): String = buildString {
        append("给下面每个词写简短的中文释义（按它在原句里的意思，30 字以内）和拼音（外文词给音标）。\n\n")
        words.forEachIndexed { i, (w, ctx) ->
            append(i + 1).append(". ").append(w)
            if (ctx.isNotBlank()) append("　原句：").append(ctx.take(120))
            append('\n')
        }
        append("\n只输出 JSON：{\"items\":[{\"word\":\"\",\"reading\":\"\",\"gloss\":\"\"}]}")
    }

    /** 解析补释义的回答：词 → (读音, 释义)；只收请求里有的词。 */
    fun parseGlosses(answer: String, asked: Set<String>): Map<String, Pair<String, String>> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val items = runCatching { JSONObject(answer.substring(start, end + 1)).optJSONArray("items") }.getOrNull() ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { o ->
            val w = o.optString("word").trim()
            val gloss = o.optString("gloss").trim()
            if (w in asked && gloss.isNotEmpty()) w to (o.optString("reading").trim() to gloss) else null
        }.toMap()
    }
}
