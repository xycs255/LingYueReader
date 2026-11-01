package com.xycs.lingyuereader.data

/**
 * 本书高频说法：最近几章叙述里反复出现的一个说法。[total] 是这几章里一共几次，[spread] 是分布在哪几章、各几次（章名 → 次数，按目录先后），
 * [here] 是它在当前这一章正文里的位置（跳到位置用）。
 */
data class RepeatPhrase(val phrase: String, val total: Int, val spread: List<Pair<String, Int>>, val here: List<IntRange>) {
    /** “不再提”记在改稿学习的“不再提”里，用这个键。 */
    val key: String get() = RepeatScan.PHRASE_KEY + phrase
}

/** 句式单调的几种：句长太齐、连着几段开头一样、“他/她”开头的句子太多。 */
enum class MonotonyKind(val label: String) { SENTENCE_LENGTH("句长太齐"), SAME_OPENING("段首重复"), PRONOUN_OPENING("他她开头") }

/** 当前这一章的一处句式单调：[message] 是给作者看的一句，[ranges] 是相关的位置（整章的为空）。 */
data class Monotony(val kind: MonotonyKind, val message: String, val ranges: List<IntRange> = emptyList(), val detail: String = "") {
    /** 不再提：按种类记（作者觉得这种不算问题，整本书都不再提），段首重复按开头的字记。 */
    val key: String get() = RepeatScan.MONO_KEY + kind.name + if (kind == MonotonyKind.SAME_OPENING) ":$detail" else ""
}

/** 一次检查的结果：本书高频说法和当前这一章的句式单调。 */
data class RepeatReport(val phrases: List<RepeatPhrase> = emptyList(), val monotony: List<Monotony> = emptyList()) {
    val isEmpty: Boolean get() = phrases.isEmpty() && monotony.isEmpty()
}

/**
 * 一章叙述部分（引号外）的 n-gram 计数，只跟这一章的正文、要排除的名字有关：章没改就不用重算（见 [RepeatScan.Cache]）。
 */
class ChapterGrams internal constructor(val counts: Map<String, Int>)

/**
 * 本机查“本书高频说法”和句式单调（不花钱、不联网）。
 *
 * DeepSeek 写久了会形成这本书自己的口癖：某个比喻、某个动作、某种句尾。内置清单查不到，黑名单要作者先发现。
 * 这里把最近 [WINDOW] 章叙述部分（引号外，对白不查：人物口头禅是有意的）里 2 到 8 个字的说法数一遍，
 * 跨章反复出现、又不是人名地名和常用词的列出来。阈值都偏高：宁可少报，不误报。
 */
object RepeatScan {
    /** 看最近几章（含当前这一章）。 */
    const val WINDOW = 10

    /** 说法最短、最长几个字：一个字说不成口癖，再长就是整句照抄了（照抄另有黑名单和改稿学习管）。 */
    const val MIN_GRAM = 2
    const val MAX_GRAM = 8

    /**
     * 至少在几章里出现：只在一章里反复的多半是作者有意的复沓、一场戏里的道具，不算口癖。三章起才像是写顺手了的习惯。
     */
    const val MIN_CHAPTERS = 3

    /**
     * 这几章里一共至少几次，按长短分：越短的说法越容易是正常用词，门槛越高。
     * - 两个字：20 次（十章里平均每章两次，常用双音词早被 [COMMON] 排掉，剩下的“微微”“缓缓”这类才够得上）；
     * - 三个字：12 次；
     * - 四到五个字：6 次（四字以上一字不差地复现，正常行文很少见，十章六次已经很扎眼）；
     * - 六个字以上：5 次。
     */
    fun minTotal(length: Int): Int = when {
        length <= 2 -> 20
        length == 3 -> 12
        length <= 5 -> 6
        else -> 5
    }

    /** 一次最多列几条：列多了作者看不过来，分最高的先列。 */
    const val MAX_PHRASES = 10

    /**
     * 短说法被长说法包住、次数又差不多（短的次数里有七成以上是长的那几次）时只报长的：“嘴角勾起一抹”报了，就不再单报“勾起一抹”。
     */
    private const val CONTAINED_SHARE = 0.7

    /**
     * 句长变异系数（标准差 / 平均句长）低于这个就算“句长太齐”。人写的叙述一般在 0.5 以上，长短句交替；
     * 0.3 以下几乎每句都一样长，读着像打拍子。至少要 [MIN_SENTENCES] 句才算，短章不评。
     */
    const val SENTENCE_CV = 0.3
    const val MIN_SENTENCES = 40

    /** 连着几段（叙述段，对白段不算）开头的字一样就提：三段常见（排比、连续动作），四段起才像是写顺手了。 */
    const val SAME_OPENING_RUN = 4

    /**
     * “他/她”开头的叙述句占比超过这个就提：正常叙述里人称开头大约两三成，四成以上读起来就是“他……。他……。她……。”。
     * 至少 [MIN_PRONOUN_SENTENCES] 句叙述才算。
     */
    const val PRONOUN_SHARE = 0.4
    const val MIN_PRONOUN_SENTENCES = 30

    /** “不再提”的键前缀（记在改稿学习的“不再提”里，随备份走）。 */
    const val PHRASE_KEY = "repeat:"
    const val MONO_KEY = "mono:"

    /** 只由这些字组成的说法不报：都是虚词、代词、量词，拼在一起也只是正常的句子骨架。 */
    private const val FUNCTION_CHARS = "的了着过地得们是在有和与及就也都还又不没很太更最这那哪个一二两三些么什怎呢吗吧啊呀他她它我你您自己把被从向对给让为以而且但却并只才已到上下来去里中后前时说道看想要会能可将之其此所如若则即便于"

    /** 头一个字是这些的多半是从句子中间截出来的碎片（“了一下”“着他的”），不报。 */
    private const val BAD_START = "的了着过地得们和与及就也都还又却并而"

    /** 末一个字是这些的同理（“看着他的”“慢慢地”）。 */
    private const val BAD_END = "的地得和与及就也都还又却并而把被从向对给让在"

    /**
     * 常用双音、三音词：正常行文里到处都是，次数再多也不是口癖。只列 2、3 个字的；更长的说法不查这张表。
     * 这张表宁宽勿窄：漏报一个口癖，作者还能自己加黑名单；误报一个“知道”，整个功能就没人看了。
     */
    private val COMMON: Set<String> = """
        知道 什么 没有 自己 一个 我们 他们 她们 你们 这个 那个 这样 那样 怎么 为什么 时候 已经 还是 可以 因为 所以 但是 如果 就是 不是
        只是 还有 一样 一起 一下 一声 一眼 一边 一点 一些 现在 刚才 然后 东西 事情 地方 问题 开始 觉得 起来 出来 过来 下来 回来 上来 进来
        出去 过去 下去 回去 上去 进去 看着 看到 看见 听到 听见 想到 说话 说道 问道 心里 身上 身边 面前 手里 眼前 门口 一切 这里 那里 哪里
        这些 那些 有些 有人 没人 别人 大家 人们 自己的 一个人 两个人 的时候 的人 的事 一会儿 一阵 一时 一直 一定 一般 突然 忽然 终于 依然 仍然
        立刻 马上 慢慢 渐渐 不过 而且 并且 或者 虽然 只要 只有 即使 于是 接着 随后 之后 之前 以后 以前 后来 原来 本来 似乎 好像 仿佛 也许
        大概 可能 应该 必须 需要 能够 不能 不会 不要 不用 不敢 不想 不知 不过 不再 不了 不住 不出 不到 不见 没想到 怎么回事 怎么样 为什么要
        这么 那么 多么 多少 几个 每个 所有 整个 一次 两次 第一 第二 自然 当然 果然 竟然 居然 显然 确实 其实 甚至 反而 而是 只好 只得 不得不
        点头 摇头 抬头 低头 回头 转身 起身 站起 坐下 走进 走出 走过 走到 来到 回到 到了 看了 说了 想了 听了 笑了 哭了 愣了 有了 没了 好了
        他的 她的 我的 你的 它的 他们的 她们的 我们的 自己 声音 眼睛 脸上 嘴里 心中 手中 眼中 身子 身体 脑子 脑袋 时间 晚上 早上 今天 明天 昨天
    """.trim().split(Regex("\\s+")).toSet()

    /**
     * 一章里引号外的叙述部分：引号里的字换成换行（不让对白和叙述拼成一个说法），长度和原文一样，位置对得上。
     * 认中文引号“”「」『』，英文双引号成对认。
     */
    fun narrative(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        var ascii = false
        for (ch in text) {
            when {
                ch == '“' || ch == '「' || ch == '『' -> { depth++; out.append('\n') }
                (ch == '”' || ch == '」' || ch == '』') && depth > 0 -> { depth--; out.append('\n') }
                ch == '"' -> { ascii = !ascii; out.append('\n') }
                ch == '\n' -> { depth = 0; ascii = false; out.append('\n') }
                depth > 0 || ascii -> out.append('\n')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun han(ch: Char) = ch in '一'..'鿿' || ch == '〇'

    /** 名字、地名的位置换成换行，n-gram 不跨过它们：“林月看着”不会数出“月看着”。 */
    private fun maskNames(text: String, names: Collection<String>): String {
        if (names.isEmpty()) return text
        val chars = text.toCharArray()
        names.filter { it.length >= 2 }.sortedByDescending { it.length }.forEach { n ->
            var at = text.indexOf(n)
            while (at >= 0) {
                for (i in at until at + n.length) chars[i] = '\n'
                at = text.indexOf(n, at + n.length)
            }
        }
        return String(chars)
    }

    /** 一章叙述部分里 2 到 8 个字的说法各几次（名字的位置断开）。 */
    fun grams(text: String, names: Collection<String>): ChapterGrams {
        val t = maskNames(narrative(text), names)
        val counts = HashMap<String, Int>()
        var i = 0
        while (i < t.length) {
            if (!han(t[i])) { i++; continue }
            var j = i
            while (j < t.length && han(t[j])) j++
            for (s in i until j) for (n in MIN_GRAM..MAX_GRAM) {
                if (s + n > j) break
                val g = t.substring(s, s + n)
                counts[g] = (counts[g] ?: 0) + 1
            }
            i = j
        }
        return ChapterGrams(counts)
    }

    /** 一个说法算不算候选：不全是虚词，不是从句子中间截的碎片，不是常用词。 */
    internal fun plausible(g: String): Boolean {
        if (g.all { it in FUNCTION_CHARS }) return false
        if (g.first() in BAD_START || g.last() in BAD_END) return false
        if (g.length <= 3 && g in COMMON) return false
        // 叠字之外，同一个字占一半以上的（“哈哈哈”“嗯嗯”）是拟声，不算。
        if (g.length >= 3 && g.groupingBy { it }.eachCount().values.max() * 2 > g.length) return false
        return true
    }

    /**
     * 查最近几章：[chapters] 是（章名，n-gram）按目录先后，最后一项是当前这一章；[current] 是当前这一章的正文（定位用）。
     * [names] 是人名、地名（[grams] 里已经断开了，这里再挡一次被名字包住的），[banned] 是我的黑名单（已经在黑名单里的不再报），
     * [dismissed] 是作者点过“不再提”的键。
     */
    fun phrases(
        chapters: List<Pair<String, ChapterGrams>>,
        current: String,
        names: Collection<String> = emptyList(),
        banned: Collection<String> = emptyList(),
        dismissed: Set<String> = emptySet(),
    ): List<RepeatPhrase> {
        if (chapters.size < MIN_CHAPTERS) return emptyList()
        val here = chapters.last().second.counts
        val candidates = here.keys.filter { g ->
            val total = chapters.sumOf { it.second.counts[g] ?: 0 }
            total >= minTotal(g.length) && chapters.count { (it.second.counts[g] ?: 0) > 0 } >= MIN_CHAPTERS && plausible(g) &&
                names.none { n -> n.length >= 2 && (n in g || g in n) }
        }
        val totals = candidates.associateWith { g -> chapters.sumOf { it.second.counts[g] ?: 0 } }
        val accepted = mutableListOf<String>()
        candidates.sortedWith(compareByDescending<String> { it.length }.thenByDescending { totals.getValue(it) }).forEach { g ->
            val inside = accepted.any { h -> g in h && totals.getValue(h) >= CONTAINED_SHARE * totals.getValue(g) }
            if (!inside) accepted += g
        }
        // 长的放进来以后，被它包住、次数差不多的短的已经挡掉；反过来，长的只是短的一小部分（短的另有很多次）时两个都留着。
        val narrativeText = narrative(current)
        // 黑名单里的、点过“不再提”的最后才去掉：先去掉的话，被它包住的短说法（“一抹弧度”）就会冒出来顶替它。
        val shown = accepted.filter { g -> banned.none { b -> b.isNotBlank() && (b in g || g in b) } && PHRASE_KEY + g !in dismissed }
        return shown.sortedByDescending { totals.getValue(it) * it.length }.take(MAX_PHRASES).map { g ->
            val spread = chapters.mapNotNull { (title, grams) -> grams.counts[g]?.takeIf { it > 0 }?.let { title to it } }
            RepeatPhrase(g, totals.getValue(g), spread, positions(narrativeText, g))
        }
    }

    private fun positions(text: String, g: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var at = text.indexOf(g)
        while (at >= 0) {
            out += at until at + g.length
            at = text.indexOf(g, at + g.length)
        }
        return out
    }

    /** 当前这一章的句式单调（只看叙述）。 */
    fun monotony(text: String, dismissed: Set<String> = emptySet()): List<Monotony> {
        val out = mutableListOf<Monotony>()
        val n = narrative(text)
        // 句子：按句末标点和换行切开，带位置；太短的（一两个字的拟声、残句）不算。
        val sentences = Regex("[^。！？!?…\\n]+[。！？!?…]*").findAll(n)
            .map { m -> val s = m.value.trim(); m.range.first + (m.value.length - m.value.trimStart().length) to s }
            .filter { (_, s) -> s.count(::han) >= 3 }.toList()
        if (sentences.size >= MIN_SENTENCES) {
            val lengths = sentences.map { it.second.count(::han).toDouble() }
            val mean = lengths.average()
            val sd = kotlin.math.sqrt(lengths.sumOf { (it - mean) * (it - mean) } / lengths.size)
            val cv = if (mean > 0) sd / mean else 1.0
            if (cv < SENTENCE_CV) out += Monotony(MonotonyKind.SENTENCE_LENGTH,
                "叙述的句子长短太齐：${sentences.size} 句平均 ${mean.toInt()} 字，长短差得很少，读着像打拍子；穿插几句短句或长句")
        }
        if (sentences.size >= MIN_PRONOUN_SENTENCES) {
            val starts = sentences.filter { (_, s) -> s.length >= 2 && (s[0] == '他' || s[0] == '她') && s[1] != '们' && s[1] != '的' }
            val share = starts.size.toDouble() / sentences.size
            if (share >= PRONOUN_SHARE) out += Monotony(MonotonyKind.PRONOUN_OPENING,
                "叙述里 ${starts.size} / ${sentences.size} 句用“他”“她”开头（${(share * 100).toInt()}%），句式一个样；换成动作、环境或别的主语开头",
                starts.take(20).map { (at, _) -> at until at + 1 })
        }
        // 段首重复：叙述段（不以引号开头）连着几段用同一个字（他、她、我、你、它）或同两个字开头。
        var offset = 0
        val paragraphs = text.split('\n').map { p -> (offset to p).also { offset += p.length + 1 } }
        fun opener(p: String): String? {
            val t = p.trimStart(' ', '　', '\t')
            if (t.length < 4 || t[0] in "“「『\"" || !han(t[0])) return null
            return if (t[0] in "他她我你它") t.substring(0, 1) else t.substring(0, 2).takeIf { it.all(::han) }
        }
        var run = mutableListOf<Pair<Int, String>>()
        fun flush() {
            if (run.size >= SAME_OPENING_RUN) {
                val head = opener(run.first().second).orEmpty()
                val ranges = run.map { (at, p) -> val lead = p.length - p.trimStart(' ', '　', '\t').length; (at + lead) until (at + lead + head.length) }
                out += Monotony(MonotonyKind.SAME_OPENING, "连着 ${run.size} 段都用“$head”开头；换个开头，或者把其中两段并起来", ranges, head)
            }
            run = mutableListOf()
        }
        paragraphs.filter { it.second.isNotBlank() }.forEach { (at, p) ->
            val o = opener(p)
            if (o == null) { flush(); return@forEach }
            val head = run.firstOrNull()?.let { opener(it.second) }
            if (head != null && head != o) flush()
            run += at to p
        }
        flush()
        return out.filter { it.key !in dismissed && MONO_KEY + it.kind.name !in dismissed }
    }

    /**
     * 各章的 n-gram 记在这里：按（章 id、指纹）认，章没改就直接用（作品页、编辑器里来回切不重算）。当前这一章正文在变时
     * 只重算这一章。最多记 [MAX] 章，超了丢最早用的。
     */
    class Cache(private val max: Int = MAX) {
        private val map = object : LinkedHashMap<String, Pair<String, ChapterGrams>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ChapterGrams>>?) = size > max
        }

        @Synchronized
        fun get(id: String, stamp: String, compute: () -> ChapterGrams): ChapterGrams {
            map[id]?.takeIf { it.first == stamp }?.let { return it.second }
            val fresh = compute()
            map[id] = stamp to fresh
            return fresh
        }

        @Synchronized
        fun size(): Int = map.size

        companion object { const val MAX = 40 }
    }
}
