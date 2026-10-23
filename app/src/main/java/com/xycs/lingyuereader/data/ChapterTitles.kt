package com.xycs.lingyuereader.data

object ChapterTitles {

    private const val NUM = "零〇一二两三四五六七八九十百千万\\d"
    private val numbered = Regex("""第\s*([$NUM]+)\s*[章节回]""")
    private val partMark = Regex("""[（(]\s*([上中下])\s*[)）]""")

    /**
     * TXT 里单独成行的章名：第X章/节/回/卷、序章、楔子、番外、Chapter 12 等。
     * 限制长度，免得把正文里以“第一次”开头的句子当成章名。
     */
    private val txtTitle = Regex(
        """^(第\s*[$NUM]+\s*[章节回卷集部篇幕](?:[\s　：:·.、—\-].{0,30}|.{0,24})|""" +
            """(?:序章|序言|序|楔子|引子|前言|后记|尾声|终章|番外)(?:[\s　：:·.、—\-].{0,30}|\d{0,3})|""" +
            """(?:Chapter|CHAPTER)\s+\d+.{0,40})$""",
    )

    fun isTxtChapterTitle(line: String): Boolean =
        line.length <= 40 && txtTitle.matches(line) && !line.endsWith("。") && !line.endsWith("，")

    fun numberOf(title: String): Int? = numbered.find(title)?.groupValues?.get(1)?.let(::parseChineseNumber)

    fun partOf(title: String): Int = when (partMark.find(title)?.groupValues?.get(1)) {
        "上" -> 1
        "中" -> 2
        "下" -> 3
        else -> 0
    }

    fun chapter(title: String, blocks: List<Block>): Chapter =
        Chapter(title, blocks, numberOf(title), partOf(title))

    /** 支持“三十四”“一百零二”“两百”“一万二千”“18”等写法。 */
    fun parseChineseNumber(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
        var total = 0
        var section = 0
        var pending = 0
        for (c in s) {
            val d = digits[c]
            val u = units[c]
            when {
                d != null -> pending = d
                u != null -> {
                    section += (if (pending == 0) 1 else pending) * u
                    pending = 0
                }
                c == '万' -> {
                    total += (section + pending).coerceAtLeast(1) * 10000
                    section = 0
                    pending = 0
                }
                else -> return null
            }
        }
        return total + section + pending
    }
}
