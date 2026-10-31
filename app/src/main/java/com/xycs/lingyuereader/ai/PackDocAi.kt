package com.xycs.lingyuereader.ai

import com.xycs.lingyuereader.data.PackBudget
import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore

/** 设定文档的三种 AI 帮手：起草（新建或空文档）、按要求改、精简。 */
enum class DocAiKind(val label: String, val running: String) {
    DRAFT("AI 起草", "AI 正在起草"),
    REVISE("AI 改", "AI 正在按要求改"),
    CONDENSE("AI 精简", "AI 正在精简"),
}

/**
 * 设定文档 AI 结果的本机检查：写入前先过一遍格式（空的、明显截断的不能写），精简后核对专名和数字还在不在，
 * 精简默认压到多少字。都在本机算，不联网。
 */
object PackDocAi {
    /** 精简时告诉 AI 必须留下的专名、数字最多列几个（多了请求太长，也没用）。 */
    const val KEEP_MAX = 80

    /** 精简目标最少多少字：再短就不是精简，是重写了。 */
    const val TARGET_MIN = 100

    /** 空文档：什么都没写，或者只有新建时带的那份模板。 */
    fun isBlank(text: String, kind: PackKind): Boolean =
        text.isBlank() || text.trim() == PackImport.template(kind).trim()

    /**
     * 去掉模型偶尔加的整篇代码块包裹和“好的，以下是……”这类开场白；设定文档是 Markdown，空行、标题、表格都留着。
     */
    fun clean(answer: String): String {
        var lines = answer.trim().lines()
        val opener = Regex("""^(好的|好|当然|以下是|下面是)[^\n]{0,30}[：:]\s*$""")
        while (lines.isNotEmpty() && (lines.first().isBlank() || opener.matches(lines.first().trim()))) lines = lines.drop(1)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines = lines.dropLast(1)
        if (lines.size >= 2 && lines.first().trim().startsWith("```") && lines.last().trim() == "```") {
            lines = lines.subList(1, lines.size - 1)
        }
        return lines.joinToString("\n").trim('\n').trimEnd()
    }

    /**
     * 结果能不能写进文档：不能写时返回原因（给作者看），能写返回空。
     * 空的不能写；明显截断的不能写：代码块只开了没收、最后一行停在逗号顿号冒号这类半句话的标点上、表格行没收尾。
     * 模型到了长度上限的那种截断，连接层已经报错（整份请求），到不了这里。
     */
    fun problem(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "AI 没写出内容，没有写入"
        if (t.lines().count { it.trim().startsWith("```") } % 2 == 1) return "AI 的结果像是没写完（代码块没收尾），没有写入；可以重来"
        val last = t.lines().last().trim()
        if (last.isNotEmpty() && last.last() in CUT_ENDS) return "AI 的结果像是没写完（最后一句停在“${last.takeLast(12)}”），没有写入；可以重来"
        if (last.startsWith("|") && !last.endsWith("|")) return "AI 的结果像是没写完（表格最后一行没收尾），没有写入；可以重来"
        return null
    }

    /** 停在这些标点上的结尾，多半是写到一半断了。 */
    private const val CUT_ENDS = "，、：:；;（(“「《—-+*"

    /**
     * 原文里的专名和数字：精简前告诉 AI 必须留下，精简后核对新稿里还有没有。专名从这几处认：
     * 标题（“## 林月”）、条目开头的名词（“- 青云宗：”）、表格每一格里短的词、加粗的词、书名号引号里的短词，再加上 [known]（人物档案里的人名）里原文出现的。
     * 数字：阿拉伯数字（连着单位），以及带单位的中文数字（“三百年”“十七岁”）。
     */
    fun keyTerms(text: String, known: Collection<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        val term = """[\p{IsHan}A-Za-z0-9·]{2,12}"""
        text.lines().forEach { raw ->
            val line = raw.trim()
            Regex("""^#{1,6}\s*($term)\s*$""").find(line)?.let { out += it.groupValues[1] }
            Regex("""^(?:[-*+]|\d+[.、])\s*(?:\*\*)?($term)(?:\*\*)?\s*[：:]""").find(line)?.let { out += it.groupValues[1] }
            if (line.startsWith("|") && !Regex("""^\|[\s:|-]+\|?$""").matches(line)) {
                line.trim('|').split('|').map { it.trim() }.filter { Regex("""^$term$""").matches(it) && it !in TABLE_WORDS }.forEach { out += it }
            }
        }
        Regex("""\*\*([^*\n]{2,12})\*\*""").findAll(text).forEach { out += it.groupValues[1].trim() }
        Regex("""[《「『“]([^》」』”\n]{2,12})[》」』”]""").findAll(text).forEach { out += it.groupValues[1].trim() }
        known.filter { it.length >= 2 && it in text }.forEach { out += it }
        // 列表的序号（“1. ”“2、”）不算数字。
        val body = text.replace(Regex("""(?m)^\s*\d+[.、]\s*"""), "")
        Regex("""\d+(?:\.\d+)?[%％]?[\p{IsHan}]?""").findAll(body).forEach { m ->
            val v = m.value.trim()
            out += if (v.last().isDigit() || v.last() == '%' || v.last() == '％' || v.last() in NUMBER_UNITS) v else v.dropLast(1).trim()
        }
        Regex("""[零一二两三四五六七八九十百千万亿]{1,8}[$NUMBER_UNITS]""").findAll(body)
            .filter { it.value.length >= 3 || it.value.first() !in "一两" }.forEach { out += it.value }
        return out.filter { it.isNotBlank() && it !in TABLE_WORDS }
    }

    /** 中文数字后面跟着这些字才算数字（单说“一”太常见，不算）。 */
    private const val NUMBER_UNITS = "岁年月日天章卷层级阶重品代世倍成里丈尺寸斤两万亿人名座城"

    /** 表头里常见的词：不算专名。 */
    private val TABLE_WORDS = setOf("人物", "姓名", "名字", "想要", "害怕", "知道什么", "从第几章起", "消息从哪来", "伏笔", "埋下", "回收", "状态", "身份", "关系", "备注", "说明")

    /** 精简后丢了的：原文的专名、数字在新稿里找不到的。 */
    fun missing(original: String, condensed: String, known: Collection<String> = emptyList()): List<String> =
        keyTerms(original, known).filter { it !in condensed }

    /**
     * “精简到约 N 字”的默认值：这份文档在“AI 每次带多少”里放不下或被截短时，取预算里能放下的字数；
     * 能放下或算不出时取原长的一半。不低于 [TARGET_MIN]（原长不到它的两倍时取一半）。
     */
    fun condenseTarget(title: String, words: Int, budget: PackBudget?): Int {
        val half = words / 2
        val misses = budget?.misses.orEmpty().filter { it.title.substringBefore(" › ") == title }
        val fit = when {
            misses.isEmpty() || budget == null -> null
            misses.any { it.whole } -> (budget.knowledgeLimit - budget.knowledgeChars).takeIf { it > 0 }
            // 截短的那份：[com.xycs.lingyuereader.data.BudgetMiss.chars] 记的就是带上了的前半截有多少字。
            else -> misses.minOf { it.chars }.takeIf { it > 0 }
        }
        val target = fit?.takeIf { it < words } ?: half
        // 短文档（不到两倍下限）就压一半，不然默认值比原文还长。
        return target.coerceAtLeast(minOf(TARGET_MIN, half.coerceAtLeast(1)))
    }
}

/**
 * AI 的结果写进设定文档：先把写入前的那一版存进历史版本（和最近一版一样就不重复存），再写。
 * 改坏了在“历史版本”里能回到写入前；“撤回”也走这里，撤回前的那一版同样留着。
 */
fun WorkStore.saveDocFromAi(workId: String, docId: String, before: String, after: String) {
    val key = docKey(docId)
    if (before.isNotBlank()) snapshot(workId, key, before)
    requireNotNull(savePackText(workId, docId, after)) { "设定文档不在了" }
}
