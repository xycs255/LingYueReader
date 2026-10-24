package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import kotlin.math.ln

/** 读者在书里读到哪：第几章第几块（含这一块）。只发这之前的原文，免得剧透。 */
data class ReadPos(val chapter: Int, val block: Int)

/** 问这本书时引用的一段原文：在第几章第几块开始。 */
data class AiSource(val index: Int, val chapter: Int, val chapterTitle: String, val block: Int, val text: String)

object AiPrompts {
    /** 发给模型的原文最多这么多字（约等于一两万 token），够前情提要，也不至于太贵。 */
    const val RECAP_CHARS = 16_000
    const val CONTEXT_CHARS = 600

    fun system(title: String, author: String?, length: AnswerLength = AnswerLength.MEDIUM): String = buildString {
        append("你是阅读软件“灵阅”里的读书助手。读者正在读《").append(title).append("》")
        if (!author.isNullOrBlank()) append("（").append(author).append("）")
        append("。\n")
        append("- 用简体中文回答，清楚、简洁，不说客套话；用纯文本，不用 Markdown 标题和表格。\n")
        append("- 只依据给出的原文和常识回答。即使你知道这本书，也绝不透露读者还没读到的情节、人物结局或谜底。\n")
        append("- 不确定时直说不确定，不要编造。\n")
        append("- ").append(length.instruction)
    }

    /** 划选后问 AI：选中的文字连同所在段落一起发，模型才知道语境。 */
    fun selection(chapterTitle: String, context: String, selected: String, question: String): String = buildString {
        if (chapterTitle.isNotBlank()) append("章节：").append(chapterTitle).append('\n')
        if (context.isNotBlank() && context.trim() != selected.trim()) append("上下文：").append(context.trim()).append('\n')
        append("选中的文字：「").append(selected.trim()).append("」\n\n")
        append(question.trim())
    }

    /** 查词时的 AI 释义。 */
    fun gloss(word: String, sentence: String): String = buildString {
        append("词语：").append(word.trim()).append('\n')
        if (sentence.isNotBlank()) append("原句：").append(sentence.trim()).append('\n')
        append("\n用中文解释这个词：第一行写拼音（外文词写音标）和词性；")
        append(if (sentence.isNotBlank()) "接着用一两句话说它在原句里的意思；" else "接着用一两句话说它的基本意思；")
        append("有常用的其他义项再简短列一两条。总共不超过 120 字，不要复述原句。")
    }

    fun recap(text: String, chapterTitle: String): String = buildString {
        append("下面是读者最近读过的内容，截止到他现在停下的地方（「").append(chapterTitle).append("」）。【】里是章名。\n\n")
        append(text)
        append("\n\n请写一段前情提要，帮读者接上：先用 3 到 6 条要点讲清主要人物、发生了什么、还悬着的问题，")
        append("再用一句话说读者停在哪里。只根据上面的原文，不要猜测后面的情节。")
    }

    fun askBook(sources: List<AiSource>, question: String): String = buildString {
        append("下面是从读者已读部分里找出的相关原文片段，按在书中的先后排列：\n\n")
        sources.forEach { s -> append('[').append(s.index).append("]（").append(s.chapterTitle).append("）").append(s.text).append("\n\n") }
        append("读者的问题：").append(question.trim()).append("\n\n")
        append("只根据这些片段回答；用到哪个片段，就在那句话末尾标上编号，如 [2]。")
        append("片段里找不到答案时，直说“读过的部分里没有提到”，可以讲讲相关的线索。")
    }

    /** 某块的正文；图片、分隔线没有字。 */
    private fun textOf(b: Block): String = b.plainText

    /**
     * 前情提要用的原文：从读者停下的地方往回取，最多 [limit] 字；跨章时插入【章名】。
     * 只取 [pos] 之前（含当前块）的内容。
     */
    fun recentText(content: BookContent, pos: ReadPos, limit: Int = RECAP_CHARS): String {
        val parts = ArrayDeque<String>()
        var used = 0
        var ch = pos.chapter.coerceIn(0, content.chapters.lastIndex.coerceAtLeast(0))
        if (content.chapters.isEmpty()) return ""
        var first = true
        while (ch >= 0 && used < limit) {
            val chapter = content.chapters[ch]
            val blocks = chapter.blocks
            val last = if (first) pos.block.coerceIn(-1, blocks.lastIndex) else blocks.lastIndex
            var b = last
            val chapterParts = ArrayDeque<String>()
            while (b >= 0 && used < limit) {
                val t = textOf(blocks[b]).trim()
                if (t.isNotEmpty()) {
                    val room = limit - used
                    val piece = if (t.length > room) "……" + t.takeLast(room) else t
                    chapterParts.addFirst(piece)
                    used += piece.length
                }
                b--
            }
            if (chapterParts.isNotEmpty()) {
                parts.addFirst(chapterParts.joinToString("\n"))
                parts.addFirst("【${chapter.title}】")
            }
            first = false
            ch--
        }
        return parts.joinToString("\n")
    }

    /** 让模型把问题改写成检索词：补上人名、别称、同义说法；追问里的“他”“那件事”换成具体的。 */
    fun keywords(question: String, previous: List<String>): String = buildString {
        if (previous.isNotEmpty()) {
            append("之前的问题：\n")
            previous.takeLast(3).forEach { append("- ").append(it).append('\n') }
        }
        append("现在的问题：").append(question.trim()).append("\n\n")
        append("为了在小说原文里检索相关段落，列出 3 到 12 个检索词：问题里的人名、地名、物件、事件，它们的别称和同义说法；")
        append("追问里的代词换成具体的人或事。只输出用空格分隔的词，不要解释。")
    }

    /** 回答里引用的片段编号 [n]，按出现先后、不重复。 */
    fun citations(answer: String): List<Int> =
        Regex("""\[(\d{1,2})]""").findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
}

/**
 * 问这本书时找相关原文：把已读部分切成几百字一段，按问题里的词（中文按相邻两字）用 BM25 打分，取最相关的几段。
 * 只收 [ReadPos] 之前的内容，问到后面的情节也找不到。
 */
class BookIndex private constructor(
    private val passages: List<Passage>,
    private val df: Map<String, Int>,
    private val avgLength: Double,
) {
    private class Passage(val chapter: Int, val chapterTitle: String, val block: Int, val text: String, val terms: Map<String, Int>, val length: Int)

    val size: Int get() = passages.size

    /** 各段原文（建向量索引用）。 */
    val texts: List<String> get() = passages.map { it.text }

    /** 和 [question] 最相关的至多 [limit] 段，按在书中的先后排好、编号从 1 起。 */
    fun search(question: String, limit: Int = 8): List<AiSource> = sources(ranked(question, limit))

    /** 按 BM25 分数从高到低，至多 [limit] 段的下标。 */
    fun ranked(question: String, limit: Int = 30): List<Int> {
        // 一般按两字词找；问的只有一个字（“伞”）时才按单字找。
        val all = terms(question).keys
        val query = all.filter { it.length >= 2 && it !in STOP }.ifEmpty { all.filter { it.length == 1 } }
        if (query.isEmpty() || passages.isEmpty()) return emptyList()
        val n = passages.size.toDouble()
        val scored = passages.mapIndexedNotNull { i, p ->
            var score = 0.0
            for (t in query) {
                val f = p.terms[t] ?: continue
                val d = df[t] ?: continue
                val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
                score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * p.length / avgLength))
            }
            if (score > 0) i to score else null
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** 把选出的段落按在书中的先后排好、编号从 1 起。 */
    fun sources(indices: List<Int>): List<AiSource> = indices.distinct().sorted().mapIndexed { k, i ->
        val p = passages[i]
        AiSource(k + 1, p.chapter, p.chapterTitle, p.block, p.text)
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val PASSAGE = 400

        /** 问句里常见、不说明问的是什么的两字词。 */
        private val STOP = setOf("什么", "为什", "么要", "怎么", "怎样", "哪里", "哪个", "这个", "那个", "是不", "不是", "有没", "没有", "一下", "现在", "到底", "为何", "如何", "他们", "我们", "这里", "那里", "的是", "了吗", "是谁", "谁是")

        fun build(content: BookContent, pos: ReadPos): BookIndex {
            val passages = ArrayList<Passage>()
            val last = pos.chapter.coerceAtMost(content.chapters.lastIndex)
            for (ch in 0..last) {
                val chapter = content.chapters[ch]
                val blocks = chapter.blocks
                val end = if (ch == last) pos.block.coerceAtMost(blocks.lastIndex) else blocks.lastIndex
                val buf = StringBuilder()
                var start = -1
                fun flush() {
                    if (buf.isNotBlank()) {
                        val text = buf.toString().trim()
                        val t = terms(text)
                        passages += Passage(ch, chapter.title, start, text, t, t.values.sum())
                    }
                    buf.setLength(0)
                    start = -1
                }
                for (b in 0..end) {
                    val t = blocks[b].plainText.trim()
                    if (t.isEmpty()) continue
                    if (start < 0) start = b
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(t)
                    if (buf.length >= PASSAGE) flush()
                }
                flush()
            }
            val df = HashMap<String, Int>()
            passages.forEach { p -> p.terms.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
            val avg = if (passages.isEmpty()) 1.0 else passages.sumOf { it.length }.toDouble() / passages.size
            return BookIndex(passages, df, avg.coerceAtLeast(1.0))
        }

        /** 中文按单字和相邻两字，拉丁字母和数字按整词（小写）。 */
        internal fun terms(text: String): Map<String, Int> {
            val out = HashMap<String, Int>()
            var prev: Char? = null
            val word = StringBuilder()
            fun endWord() {
                if (word.length >= 2) word.toString().lowercase().let { out[it] = (out[it] ?: 0) + 1 }
                word.setLength(0)
            }
            for (c in text) {
                when {
                    Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> {
                        endWord()
                        c.toString().let { out[it] = (out[it] ?: 0) + 1 }
                        prev?.let { p -> "$p$c".let { out[it] = (out[it] ?: 0) + 1 } }
                        prev = c
                    }
                    c.isLetterOrDigit() -> {
                        prev = null
                        word.append(c)
                    }
                    else -> {
                        endWord()
                        prev = null
                    }
                }
            }
            endWord()
            return out
        }
    }
}
