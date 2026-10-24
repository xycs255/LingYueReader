package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.Block
import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.InlineText
import com.xycs.lingyuereader.data.spokenText
import org.json.JSONObject
import java.io.File

/** 译成什么。 */
enum class TranslateTarget(val label: String, val instruction: String) {
    CHINESE("中文", "译成通顺的简体中文"),
    VERNACULAR("白话", "把文言或古白话译成通俗的现代汉语"),
    ENGLISH("English", "translate into natural English"),
}

/** 一本书的译文：第几章 → (章名, 第几块 → 译文)。章名用来核对（改稿后章序可能变了）。 */
data class Translations(
    val show: Boolean = true,
    val target: TranslateTarget = TranslateTarget.CHINESE,
    val chapters: Map<Int, Pair<String, Map<Int, String>>> = emptyMap(),
) {
    fun of(chapter: Int, title: String): Map<Int, String>? = chapters[chapter]?.takeIf { it.first == title }?.second
}

/**
 * 整章翻译：一章的段落按 [BATCH] 字一批编号发出，回答按编号对回段落；译文存在书目录里（ai-translation.json），
 * 对照显示时接在原文段落后面（见 [InlineText.withTranslation]），原文的字位不变，书签批注和进度都不受影响。
 */
object AiTranslate {
    const val FILE = "ai-translation.json"
    const val BATCH = 2_500

    /** 默认译成什么：正文以汉字为主时译白话，否则译中文。 */
    fun defaultTarget(content: BookContent, chapter: Int): TranslateTarget {
        val text = content.chapters.getOrNull(chapter)?.blocks?.take(40)?.joinToString("") { it.plainText }.orEmpty()
        if (text.isEmpty()) return TranslateTarget.CHINESE
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (han * 3 > text.count { it.isLetter() }) TranslateTarget.VERNACULAR else TranslateTarget.CHINESE
    }

    /** 一章里要翻译的段落（块号 → 原文），按 [BATCH] 字分批。 */
    fun batches(blocks: List<Block>): List<List<Pair<Int, String>>> {
        val out = ArrayList<List<Pair<Int, String>>>()
        var cur = ArrayList<Pair<Int, String>>()
        var size = 0
        blocks.forEachIndexed { i, b ->
            val text = b.spokenText?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            if (size + text.length > BATCH && cur.isNotEmpty()) {
                out += cur
                cur = ArrayList()
                size = 0
            }
            cur += i to text
            size += text.length
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun prompt(batch: List<Pair<Int, String>>, target: TranslateTarget): String = buildString {
        append("下面是一本书里连续的几段，每段前有编号。请").append(target.instruction).append("。\n")
        append("逐段对应、不合并不拆分，保留编号，格式是“[[编号]] 译文”，每段一行。只输出译文，不加说明。\n\n")
        batch.forEachIndexed { k, (_, text) -> append("[[").append(k + 1).append("]] ").append(text.replace('\n', ' ')).append('\n') }
    }

    /** 按编号把回答对回块号；缺的段落不填。 */
    fun parse(answer: String, batch: List<Pair<Int, String>>): Map<Int, String> {
        val out = HashMap<Int, String>()
        val marks = Regex("""\[\[(\d{1,3})]]""").findAll(answer).toList()
        marks.forEachIndexed { i, m ->
            val k = m.groupValues[1].toInt() - 1
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else answer.length
            val text = answer.substring(m.range.last + 1, end).trim()
            val block = batch.getOrNull(k)?.first ?: return@forEachIndexed
            if (text.isNotEmpty()) out[block] = text
        }
        return out
    }

    /** 把译文接到段落后面（[content] 是不带译文的正文）；没有译文的章节原样不动，章节仍按需加载。 */
    fun apply(content: BookContent, t: Translations): BookContent {
        if (!t.show || t.chapters.isEmpty()) return content
        return content.copy(
            chapters = content.chapters.mapIndexed { i, ch ->
                val map = t.of(i, ch.title) ?: return@mapIndexed ch
                ch.mapped(ch.title) { blocks ->
                    blocks.mapIndexed { b, block ->
                        val tr = map[b] ?: return@mapIndexed block
                        when (block) {
                            is Block.Para -> block.copy(text = InlineText.withTranslation(block.text, tr))
                            is Block.Quote -> block.copy(text = InlineText.withTranslation(block.text, tr))
                            is Block.ListItem -> block.copy(text = InlineText.withTranslation(block.text, tr))
                            else -> block
                        }
                    }
                }
            },
        )
    }

    fun read(file: File?): Translations = runCatching {
        val o = JSONObject(file!!.readText())
        val chapters = o.getJSONObject("chapters")
        Translations(
            show = o.optBoolean("show", true),
            target = runCatching { TranslateTarget.valueOf(o.getString("target")) }.getOrDefault(TranslateTarget.CHINESE),
            chapters = chapters.keys().asSequence().associate { key ->
                val c = chapters.getJSONObject(key)
                val blocks = c.getJSONObject("blocks")
                key.toInt() to (c.getString("title") to blocks.keys().asSequence().associate { b -> b.toInt() to blocks.getString(b) })
            },
        )
    }.getOrDefault(Translations())

    fun write(file: File?, t: Translations) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val chapters = JSONObject()
            t.chapters.forEach { (i, pair) ->
                val blocks = JSONObject()
                pair.second.forEach { (b, text) -> blocks.put(b.toString(), text) }
                chapters.put(i.toString(), JSONObject().put("title", pair.first).put("blocks", blocks))
            }
            f.writeText(JSONObject().put("show", t.show).put("target", t.target.name).put("chapters", chapters).toString())
        }
    }
}
