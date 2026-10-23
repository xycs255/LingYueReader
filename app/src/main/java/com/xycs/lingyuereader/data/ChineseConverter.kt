package com.xycs.lingyuereader.data

import java.io.InputStream

/** 阅读时的繁简转换方向。 */
enum class ChineseConvert(val label: String) {
    NONE("不转换"),
    TO_SIMPLIFIED("繁转简"),
    TO_TRADITIONAL("简转繁"),
}

/**
 * 繁简转换：按 OpenCC 的词组与单字表做最长匹配（先词组、后单字，避免“头发→頭髮”被逐字转成“頭發”）。
 * 表里只收转换前后长度相同的条目（见 tools/build_opencc.py），所以转换不改变文字长度，
 * 书签、批注、选区的位置都不受影响。
 */
class ChineseConverter(input: InputStream) {
    private val table = HashMap<String, String>(65_536)

    /** 有词组以它开头的字：只有遇到这些字才尝试多字匹配。 */
    private val phraseHeads = HashSet<Char>()
    private var longest = 1

    init {
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val key = line.substring(0, tab)
            val value = line.substring(tab + 1)
            if (key.length != value.length) return@forEachLine
            table[key] = value
            if (key.length > 1 && !(key.length == 2 && key[0].isHighSurrogate())) {
                phraseHeads += key[0]
                if (key.length > longest) longest = key.length
            }
        }
    }

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        var out: CharArray? = null
        var i = 0
        while (i < text.length) {
            var matched = 0
            if (text[i] in phraseHeads) {
                var len = minOf(longest, text.length - i)
                while (len > 1) {
                    val v = table[text.substring(i, i + len)]
                    if (v != null) {
                        if (out == null) out = text.toCharArray()
                        v.toCharArray(out, i)
                        matched = len
                        break
                    }
                    len--
                }
            }
            if (matched == 0) {
                // 单字（含扩展区的代理对）。
                val width = if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
                val v = table[text.substring(i, i + width)]
                if (v != null) {
                    if (out == null) out = text.toCharArray()
                    v.toCharArray(out, i)
                }
                matched = width
            }
            i += matched
        }
        return out?.let(::String) ?: text
    }

    /** 转换一整本书：章节正文仍按需加载，读到哪章才转换哪章。 */
    fun convert(content: BookContent): BookContent = content.copy(
        title = content.title?.let(::convert),
        author = content.author?.let(::convert),
        description = content.description?.let(::convert),
        chapters = content.chapters.map { c -> c.mapped(convert(c.title)) { blocks -> blocks.map(::convert) } },
        notes = content.notes.mapValues { convert(it.value) },
    )

    fun convert(block: Block): Block = when (block) {
        is Block.Para -> block.copy(text = convert(block.text))
        is Block.Heading -> block.copy(text = convert(block.text))
        is Block.Quote -> block.copy(text = convert(block.text))
        is Block.ListItem -> block.copy(text = convert(block.text))
        is Block.Table -> block.copy(rows = block.rows.map { row -> row.map(::convert) })
        is Block.Image -> block.copy(alt = convert(block.alt))
        Block.Rule, Block.SceneBreak -> block
    }
}
