package com.xycs.lingyuereader.data

/**
 * AI 腔：模型写小说时爱用的套话。内置一份清单，用检查规则的写法写（见 [CheckRules]），在本机查，不联网、不花钱；
 * 作者自己划掉的说法记在作品的黑名单里（[WorkAssist.banned]），同样在本机查。
 *
 * 清单宁缺毋滥：只收一看就是套话的说法。人物在对白里说套话是允许的，所以神态、情绪、氛围、点题这几类只查叙述；
 * “仿佛”“一丝”这类常用词不禁，只限每章的次数。“文艺克制腔”（单句成段硬造余味、“张了张嘴”）也只限次数；
 * 叙述者吐槽、玩梗是作者要的口吻，不收。
 */
object AiCliche {
    /** 内置清单的来源名：编辑器里命中显示成“AI 腔”，和作者自己的检查规则分开。 */
    const val DOC = "AI 腔清单"

    /** 黑名单的来源名。 */
    const val BANNED_DOC = "我的黑名单"

    /** 从正文里挑出来加进黑名单的说法，至少、至多几个字。 */
    const val BAN_MIN = 2
    const val BAN_MAX = 20

    /** 内置清单的规则原文。 */
    const val TEXT = """# AI 腔清单
# 灵阅内置，本机检查，不联网。“叙述”开头的只查引号外面的，人物在对白里这样说不算。

# 神态套话
叙述禁用：嘴角勾起、嘴角微微上扬／嘴角微扬、勾起一抹、眼中闪过一丝、眸中闪过、眼底闪过、眸光一闪／眸光微闪
叙述禁用：瞳孔骤缩／瞳孔猛地一缩／瞳孔微缩、目光一凝、眼神一凛、眉头微皱／微微皱眉、嘴角抽了抽／嘴角微微抽搐、似笑非笑

# 情绪套话
叙述禁用：心中一凛／心头一凛、心头一震／心中一震、五味杂陈、百感交集、心中暗道／心里暗道、倒吸一口凉气／倒吸了一口凉气
叙述禁用：一股莫名的、心中涌起一股、心底涌起一股
叙述至多 1：不由得、不禁（除外：情不自禁）、莫名（除外：莫名其妙）

# 氛围套话
叙述禁用：空气……凝固、时间仿佛……静止／时间仿佛……停止、死一般的寂静／死一样的寂静、落针可闻／针落可闻
叙述禁用：一股无形的压力、气氛……降到了冰点

# 比喻和虚词堆砌
叙述至多 3：仿佛、如同、一丝（除外：一丝不苟、一丝不挂）、微微、淡淡的
叙述至多 2：宛如、犹如、一抹、某种、几分（除外：几分钟、几分之）
叙述至多 1：仿佛……一般／仿佛……似的、带着一丝、像是在诉说／仿佛在诉说

# 总结、点题、解释句
叙述禁用：“他知道，”、“她知道，”、或许这就是／也许这就是、难以言喻、说不清道不明、那是一种……的感觉、命运的齿轮
叙述至多 1：这一刻、终于明白、不是……而是……

# 文艺克制腔：硬造余味、欲言又止。网络梗、叙述者的吐槽不算 AI 腔，不收。
叙述至多 1：张了张嘴、到底没说出／到底没喊出／到底没问出、盯着……看了一会儿
叙述短段：6 字以内至多 2 处
"""

    /** 内置清单（读一次就够）。 */
    val builtin: RuleSet by lazy { CheckRules.parse(TEXT, DOC) }

    /**
     * 编辑器里要和设定包的检查规则合在一起查的：[WorkAssist.aiCliche] 开着时的内置清单，加上黑名单。
     * 黑名单全文禁用（对白里也查）：是作者自己点的。
     */
    fun rules(assist: WorkAssist?): RuleSet {
        if (assist == null) return RuleSet()
        val list = if (assist.aiCliche) builtin else RuleSet()
        return list + banned(assist.banned)
    }

    /** 黑名单变成一条“禁用”规则；一个说法一个词（不按顿号拆，说法里可以带逗号）。 */
    fun banned(phrases: List<String>): RuleSet {
        val terms = phrases.filter { it.isNotBlank() }.map { RuleTerm(listOf(it)) }
        if (terms.isEmpty()) return RuleSet()
        return RuleSet(listOf(CheckRule.Count(terms, null, 0, false, RuleScope.ALL, RuleSource(BANNED_DOC, 1, "禁用：" + phrases.joinToString("、")))))
    }

    /** 这处命中是不是 AI 腔（内置清单或黑名单），不是作者自己定的规则。 */
    fun isVoice(hit: RuleHit): Boolean = hit.rule.source.doc == DOC || hit.rule.source.doc == BANNED_DOC

    /** 命中来自黑名单。 */
    fun isBanned(hit: RuleHit): Boolean = hit.rule.source.doc == BANNED_DOC

    /** 几处 AI 腔：按正文里标出的位置数（“至多 3”超了，几处都标出来，也都算）。 */
    fun count(hits: List<RuleHit>): Int = hits.filter(::isVoice).sumOf { it.ranges.size }

    /** 能不能把这段原文加进黑名单：去掉两头空白后 [BAN_MIN]–[BAN_MAX] 个字，不跨段。 */
    fun bannable(phrase: String): String? = phrase.trim().takeIf { it.length in BAN_MIN..BAN_MAX && '\n' !in it }

    /**
     * 去 AI 腔要发给 AI 的段落：含 [ranges] 的段（原文，去掉两头空白），按在正文里的先后，最多 [limit] 段；
     * 每段带上标出来的那几处说法（去重）。
     */
    fun paragraphs(text: String, ranges: List<IntRange>, limit: Int = 20): List<Pair<String, List<String>>> {
        val out = LinkedHashMap<IntRange, MutableList<String>>()
        ranges.filter { it.first >= 0 && it.last < text.length && !it.isEmpty() }.sortedBy { it.first }.forEach { r ->
            val start = text.lastIndexOf('\n', r.first).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', r.first).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            val lead = line.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) return@forEach
            val trail = line.indexOfLast { !it.isWhitespace() }
            val key = (start + lead)..(start + trail)
            if (key !in out && out.size >= limit) return@forEach
            val phrase = text.substring(r.first, minOf(r.last + 1, end))
            out.getOrPut(key) { mutableListOf() }.let { if (phrase !in it) it += phrase }
        }
        return out.map { (r, phrases) -> text.substring(r.first, r.last + 1) to phrases.toList() }
    }

    /** [paragraph] 里 [phrase] 所在的那一句（到句号、问号、叹号为止，带上句末标点和后引号）；找不到时返回整段。 */
    fun sentence(paragraph: String, phrase: String): String {
        val at = paragraph.indexOf(phrase)
        if (at < 0) return paragraph
        val ends = "。！？!?…"
        var start = at
        while (start > 0 && paragraph[start - 1] !in ends) start--
        var end = at + phrase.length
        while (end < paragraph.length && paragraph[end] !in ends) end++
        while (end < paragraph.length && (paragraph[end] in ends || paragraph[end] in "”」』")) end++
        return paragraph.substring(start, end).trim()
    }
}
