package com.xycs.lingyuereader.dict

import android.util.LruCache

/**
 * 给一段文字的每个汉字配上拼音（注音用）。多音字按词定音：先在词典里找从这里开始最长的词
 * （“银行”的“行”读 háng），找不到再取这个字最常用的读音。非汉字没有拼音。
 */
class PinyinAnnotator(
    /** 词的拼音（带声调符号、空格分开），词典里没有时为空。 */
    private val wordReading: (String) -> String?,
    /** 单字最常用的读音。 */
    private val charReading: (String) -> String?,
) {
    private val cache = LruCache<String, Array<String?>>(512)

    /** 结果与 [text] 等长，第 i 个是第 i 个字的拼音。 */
    fun annotate(text: String): Array<String?> {
        cache.get(text)?.let { return it }
        val out = arrayOfNulls<String>(text.length)
        var i = 0
        while (i < text.length) {
            if (!WordFinder.isHan(text[i])) {
                i++
                continue
            }
            var matched = 1
            val limit = minOf(MAX_WORD, text.length - i)
            for (len in limit downTo 2) {
                if (!(i until i + len).all { WordFinder.isHan(text[it]) }) continue
                val syllables = wordReading(text.substring(i, i + len))?.split(' ')
                if (syllables != null && syllables.size == len) {
                    for (k in 0 until len) out[i + k] = syllables[k]
                    matched = len
                    break
                }
            }
            if (matched == 1) out[i] = charReading(text[i].toString())
            i += matched
        }
        cache.put(text, out)
        return out
    }

    companion object {
        private const val MAX_WORD = 4

        /** 用内置的 CC-CEDICT 和 Unihan。词典里同一个词有几种读音时，优先不是专名（小写开头）的那个。 */
        fun of(cedict: Cedict, unihan: Unihan): PinyinAnnotator = PinyinAnnotator(
            wordReading = { word ->
                val entries = cedict.lookup(word)
                (entries.firstOrNull { it.pinyin.firstOrNull()?.isLowerCase() == true } ?: entries.firstOrNull())?.pinyin?.lowercase()
            },
            charReading = { c -> unihan[c]?.readings?.firstOrNull() },
        )
    }
}
