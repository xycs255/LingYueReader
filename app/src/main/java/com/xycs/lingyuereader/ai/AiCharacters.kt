package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.BookContent
import com.xycs.lingyuereader.data.spokenText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 人物表里的一个人：名字、别称、身份、截至读者读到处的经历、和别人的关系。 */
data class BookCharacter(
    val name: String,
    val aliases: List<String> = emptyList(),
    val role: String = "",
    val summary: String = "",
    val relations: List<Pair<String, String>> = emptyList(),
) {
    /** 查词时认人名：名字或别称完全相同。 */
    fun matches(word: String): Boolean {
        val w = word.trim()
        return w.isNotEmpty() && (w == name || w in aliases)
    }
}

/** 一本书的人物表：整理到当前块的原文 UTF-16 字符坐标为止。旧记录的整块边界仍可读取。 */
data class CharacterTable(val characters: List<BookCharacter>, val chapter: Int, val block: Int, val updatedAt: Long, val char: Int = Int.MAX_VALUE) {
    fun find(word: String): BookCharacter? = characters.firstOrNull { it.matches(word) }
}

/**
 * 人物表：从书开头（或上次整理到的地方）到读者读到的地方，分段发给模型，每段让它在已有的人物表上增补修改。
 * 只发读过的部分，模型也被要求不写后面的情节。存在书目录里（ai-characters.json）。
 */
object AiCharacters {
    const val FILE = "ai-characters.json"
    /** 每次发多少字。 */
    const val CHUNK = 15_000
    /** 人物表最多留多少人：太长了每次都要连表一起发，费钱。 */
    const val MAX = 60

    /** 一段要发的原文及其末尾坐标：每段整理完就存盘，中途取消下次接着来。 */
    class Chunk(val text: String, val end: ReadPos)

    /** 还没整理的原文，按 [CHUNK] 切好；[from] 之前的文字不再发，当前块最多到 [to.char]。 */
    fun chunks(content: BookContent, from: ReadPos?, to: ReadPos): List<Chunk> {
        val out = ArrayList<Chunk>()
        val buf = StringBuilder()
        var lastPos: ReadPos? = null
        fun flush() {
            if (buf.isNotBlank()) out += Chunk(buf.toString(), lastPos!!)
            buf.setLength(0)
        }
        val startChapter = from?.chapter ?: 0
        for (ch in startChapter..to.chapter.coerceAtMost(content.chapters.lastIndex)) {
            val chapter = content.chapters[ch]
            val blocks = chapter.blocks
            val first = if (from != null && ch == from.chapter) from.block + if (from.char == Int.MAX_VALUE) 1 else 0 else 0
            val last = if (ch == to.chapter) to.block.coerceAtMost(blocks.lastIndex) else blocks.lastIndex
            if (first > last) continue
            var header = "【${chapter.title}】"
            for (b in first..last) {
                val raw = blocks[b].spokenText ?: blocks[b].plainText
                var cursor = if (from != null && ch == from.chapter && b == from.block) from.char.coerceIn(0, raw.length) else 0
                val end = if (ch == to.chapter && b == to.block) to.char.coerceIn(0, raw.length) else raw.length
                if (cursor >= end || raw.substring(cursor, end).isBlank()) continue
                if (buf.isNotEmpty() && end - cursor + header.length + 2 <= CHUNK && buf.length + end - cursor + header.length + 2 > CHUNK) {
                    flush()
                    header = "【${chapter.title}（续）】"
                }
                while (cursor < end) {
                    if (buf.isNotEmpty() && buf.length + header.length + 2 >= CHUNK) {
                        flush()
                        header = "【${chapter.title}（续）】"
                    }
                    if (header.isNotEmpty()) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(header)
                        header = ""
                    }
                    val count = minOf(end - cursor, CHUNK - buf.length - 1)
                    if (count <= 0) {
                        flush()
                        header = "【${chapter.title}（续）】"
                        continue
                    }
                    buf.append('\n').append(raw, cursor, cursor + count)
                    cursor += count
                    lastPos = if (cursor == raw.length) ReadPos(ch, b) else ReadPos(ch, b, cursor)
                    if (cursor < end && buf.length >= CHUNK) {
                        flush()
                        header = "【${chapter.title}（续）】"
                    }
                }
            }
        }
        flush()
        return out
    }

    fun prompt(current: List<BookCharacter>, text: String): String = buildString {
        append("下面是一部小说里接着读到的一段原文。请在已有的人物表上增补和修改，输出完整的新人物表。\n\n")
        append("已有的人物表：")
        append(if (current.isEmpty()) "（空）" else encode(current).toString())
        append("\n\n原文：\n").append(text).append("\n\n")
        append("要求：\n")
        append("- 只收有名有姓、或反复出现的人物，最多 $MAX 人，按重要程度排；龙套不收。\n")
        append("- name 用最常用的称呼；aliases 列别名、字号、昵称、尊称；role 一句话说身份；summary 两三句话说到目前为止的经历；")
        append("relations 列和表里其他人的关系。\n")
        append("- 只根据已有人物表和这段原文，不要写这段之后的情节。\n")
        append("- 只输出 JSON，格式：{\"characters\":[{\"name\":\"\",\"aliases\":[],\"role\":\"\",\"summary\":\"\",\"relations\":[{\"name\":\"\",\"relation\":\"\"}]}]}")
    }

    /** 从模型的回答里取出人物表：容忍前后多余的话和 ```json 包裹。 */
    fun parse(answer: String): List<BookCharacter> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型没有按格式回答" }
        val o = JSONObject(answer.substring(start, end + 1))
        val a = o.optJSONArray("characters") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decodeOne) }.distinctBy { it.name }.take(MAX)
    }

    private fun decodeOne(o: JSONObject): BookCharacter? {
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        val aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty().filter { it.isNotEmpty() && it != name }
        val relations = o.optJSONArray("relations")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                val n = r.optString("name").trim()
                val rel = r.optString("relation").trim()
                if (n.isEmpty() || rel.isEmpty()) null else n to rel
            }
        }.orEmpty()
        return BookCharacter(name, aliases.distinct(), o.optString("role").trim(), o.optString("summary").trim(), relations)
    }

    fun encode(list: List<BookCharacter>): JSONArray = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject().put("name", c.name).put("aliases", JSONArray(c.aliases)).put("role", c.role).put("summary", c.summary)
                    .put("relations", JSONArray().apply { c.relations.forEach { (n, r) -> put(JSONObject().put("name", n).put("relation", r)) } }),
            )
        }
    }

    fun read(file: File?): CharacterTable? {
        val f = file ?: return null
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val a = o.getJSONArray("characters")
        return CharacterTable((0 until a.length()).mapNotNull { decodeOne(a.getJSONObject(it)) }, o.getInt("chapter"), o.getInt("block"), o.getLong("at"), o.optInt("char", Int.MAX_VALUE))
    }

    fun write(file: File?, table: CharacterTable) {
        val f = file ?: throw IOException("找不到人物表保存位置")
        if (f.parentFile?.isDirectory == false && f.parentFile?.mkdirs() != true) throw IOException("无法创建人物表目录")
        val tmp = File(f.parentFile, f.name + ".tmp")
        val bytes = JSONObject().put("characters", encode(table.characters)).put("chapter", table.chapter).put("block", table.block)
            .put("char", table.char).put("at", table.updatedAt).toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }
}
