package com.xycs.lingyuereader.data

/**
 * 从导入词典（MDict）的一条 HTML 释义里挑出能放进生词本的读音和短释义。
 *
 * 网上流传的汉典、百度汉语类词典，一条往往先是目录（“1 基本信息 2 基本解释 2.1 基本字义……”）、
 * 再是部首笔画编码，后面才是释义；整条去掉标签直接截前几十个字，存下来的全是这些。
 * 这里按段落看：去掉目录、小标题和部首笔画这类信息行，优先取带义项编号（①、1.、〈名〉）的段落，取前两条。
 */
object DictGloss {
    data class Summary(val reading: String, val gloss: String)

    private val pinyin = Regex("""(?:拼音|读音|注音|Pinyin)\s*[:：]\s*([a-zA-Zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ][a-zA-Zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ ,，、]*)""")
    private val sense = Regex("""^\s*(?:[①-⑳]|\d{1,2}\s*[.．、)）]|[(（]\d{1,2}[)）]|〈[^〉]{1,3}〉|<[^>]{1,3}>|\[[^\]]{1,3}]|【[^】]{1,3}】|[◎●•◆])""")
    private val tocEntry = Regex("""\d+(?:\.\d+)*\s*[^\d\s.]{2,8}""")
    private val meta = listOf("部首", "笔画", "五笔", "仓颉", "郑码", "四角", "统一码", "Unicode", "UNICODE", "笔顺", "字源", "字形", "结构", "繁体", "简体", "异体", "注音", "拼音", "读音")

    fun summarize(html: String, max: Int = 80): Summary {
        val lines = HtmlText.toBlocks(html)
            .mapNotNull {
                when (it) {
                    is Block.Para -> it.text
                    is Block.ListItem -> it.text
                    is Block.Quote -> it.text
                    is Block.Heading -> null
                    else -> null
                }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val reading = lines.firstNotNullOfOrNull { line ->
            pinyin.find(line)?.groupValues?.get(1)?.trim()?.trimEnd(',', '，', '、')?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        return Summary(reading, pick(lines, max))
    }

    /** 生词本里早先存下的释义：是目录、编码这类就不显示。 */
    fun clean(gloss: String, max: Int = 80): String = pick(listOf(gloss.trim()), max, headings = false)

    private fun pick(lines: List<String>, max: Int, headings: Boolean = true): String {
        val useful = lines.filterNot { isToc(it) || isMeta(it) || headings && isHeading(it) }
        val senses = useful.filter { sense.containsMatchIn(it) }
        val text = senses.ifEmpty { useful }.take(2).joinToString("；")
        return if (text.length > max) text.take(max - 1).trimEnd() + "…" else text
    }

    private fun isToc(line: String): Boolean =
        line.startsWith("目录") || tocEntry.findAll(line).count() >= 3 && line.count { it.isDigit() } * 4 >= line.length / 3

    private fun isMeta(line: String): Boolean {
        val hits = meta.count { it in line }
        return hits >= 2 || hits == 1 && line.length <= 24 && meta.any { line.startsWith(it) }
    }

    /** “基本信息”“基本字义”“方言集汇”这类小标题：很短，没有标点。 */
    private fun isHeading(line: String): Boolean =
        line.length <= 8 && line.none { it in "，。；：,.;:！？!?（）()、" }
}
