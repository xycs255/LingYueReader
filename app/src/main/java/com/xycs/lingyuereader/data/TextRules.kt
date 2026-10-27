package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** 一条净化规则：把匹配到的文字换成 [replacement]（留空即删除）。 */
data class ReplaceRule(
    val pattern: String,
    val replacement: String = "",
    /** 为真时 [pattern] 是正则（逐行多行模式，^ $ 匹配行首行尾），否则按原文查找。 */
    val regex: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 用户写的正则在这段文字上跑得太久（多半是嵌套的重复，如 `(\w+\s?)+$`，在长行上反复回溯），已放弃。
 * [disabled] 为真：这条正则超时后线程停不下来（Android 上的 ICU 本地匹配），已按写法停用，再用直接报这个、不再起线程。
 * [busy] 为真：后台停不下来的正则已经有 [TextRules.MAX_STUCK] 条，暂时不再跑新的正则（[pattern] 是这次要跑的），重启应用后恢复。
 */
class RegexTooSlowException(val pattern: String, val busy: Boolean = false, val disabled: Boolean = false) :
    RuntimeException(
        when {
            busy -> "后台有好几条太慢的正则停不下来，暂时不能再跑正则，重启应用后恢复：$pattern"
            disabled -> "这条规则太慢，已停用，请改写：$pattern"
            else -> "正则太慢，请改写：$pattern"
        },
    )

/** 分章规则的预设。 */
data class TocPreset(val label: String, val pattern: String)

/**
 * 一本 TXT/Markdown 书的文本规则：解析前先按净化规则清理文字，再按分章规则（为空则自动识别）分章。
 * 存在书籍目录的 rules.json 里，改了会让缓存失效、重新解析。
 */
data class TextRules(
    val replace: List<ReplaceRule> = emptyList(),
    /** 自定义分章正则，匹配到的整行作为章名；为空时用内置的自动识别。 */
    val toc: String = "",
) {
    val isEmpty: Boolean get() = toc.isBlank() && replace.none { it.enabled }

    /** 依次应用启用的净化规则。写错的正则跳过，不影响其他规则；太慢的正则抛 [RegexTooSlowException]。 */
    fun clean(text: String): String = replace.filter { it.enabled && it.pattern.isNotEmpty() }.fold(text) { acc, r ->
        if (r.regex) {
            val re = compile(r.pattern) ?: return@fold acc
            try {
                guarded(r.pattern, acc) { timed -> re.replace(timed(acc), r.replacement) }
            } catch (slow: RegexTooSlowException) {
                throw slow
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (broken: Throwable) {
                acc // 替换串写错（如引用了不存在的分组）：跳过这条
            }
        } else {
            acc.replace(r.pattern, r.replacement)
        }
    }

    /**
     * 分章判断：有自定义规则就用它（只看 [MAX_TITLE] 字以内的行），否则用内置识别。
     * 给了全文 [text] 时，先把全文的短行按小批（每批 [TITLE_BATCH] 行）判一遍，每批各自限时——
     * 大书在慢手机上整本加起来跑得久，但正常的规则每批都很快；只有哪一批超时才算太慢。没判过的行再逐行限时判。
     * 太慢的正则抛 [RegexTooSlowException]。
     */
    fun titleMatcher(text: String = ""): (String) -> Boolean {
        val re = toc.takeIf { it.isNotBlank() }?.let(::compile) ?: return ChapterTitles::isTxtChapterTitle
        // 和 TxtParser 一样去掉行首行尾的空白；去法对不上时，下面逐行判，结果一样，只是慢一点。
        val known: Map<String, Boolean> = if (text.isEmpty()) emptyMap() else HashMap<String, Boolean>().also { known ->
            text.lineSequence().map { it.trim().trim('　').trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_TITLE }
                .distinct()
                .chunked(TITLE_BATCH)
                .forEach { batch ->
                    known += guarded(toc, batch.joinToString("\n"), timeoutMillis) { timed -> batch.associateWith { re.containsMatchIn(timed(it)) } }
                }
        }
        return { line -> line.length <= MAX_TITLE && (known[line] ?: guarded(toc, line) { timed -> re.containsMatchIn(timed(line)) }) }
    }

    fun toJson(): JSONObject = JSONObject().put("toc", toc).put(
        "replace",
        JSONArray().apply {
            replace.forEach { r ->
                put(JSONObject().put("pattern", r.pattern).put("replacement", r.replacement).put("regex", r.regex).put("enabled", r.enabled))
            }
        },
    )

    companion object {
        const val MAX_TITLE = 60

        /** 试分章时每批判多少行（每批各自限时）。 */
        internal const val TITLE_BATCH = 2_000

        fun fromJson(o: JSONObject): TextRules {
            val arr = o.optJSONArray("replace") ?: JSONArray()
            return TextRules(
                replace = (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    ReplaceRule(r.optString("pattern"), r.optString("replacement"), r.optBoolean("regex"), r.optBoolean("enabled", true))
                },
                toc = o.optString("toc"),
            )
        }

        /** 编译正则；写错了返回 null。 */
        fun compile(pattern: String): Regex? = runCatching { Regex(pattern, RegexOption.MULTILINE) }.getOrNull()

        /** 正则的错误说明，没错返回 null。 */
        fun error(pattern: String): String? = runCatching { Regex(pattern); null }.getOrElse { e ->
            e.message?.lineSequence()?.firstOrNull() ?: "正则写得不对"
        }

        /** 常见的分章格式。 */
        val TOC_PRESETS = listOf(
            TocPreset("第X章 / 回 / 节", """^\s*第\s*[0-9零〇一二两三四五六七八九十百千万]+\s*[章回节]"""),
            TocPreset("第X卷 与 第X章", """^\s*第\s*[0-9零〇一二两三四五六七八九十百千万]+\s*[卷章回节部集]"""),
            TocPreset("【章名】", """^\s*[【\[].{1,40}[】\]]\s*$"""),
            TocPreset("数字序号：1、 001 1.", """^\s*\d{1,4}\s*[、.．：:\s]\s*\S.{0,40}$"""),
            TocPreset("Chapter N", """^\s*(Chapter|CHAPTER|Part|PART)\s+\w+"""),
            TocPreset("单独一行的短标题（不超过 15 字、不带句末标点）", """^[^。！？，、；“”]{1,15}$"""),
        )

        /** 统计净化规则在文字里匹配到几处，并给出几个“前后文”示例（匹配部分用【】标出）。太慢的正则抛 [RegexTooSlowException]。 */
        fun preview(rule: ReplaceRule, text: String, samples: Int = 3): Pair<Int, List<String>> {
            if (rule.pattern.isEmpty()) return 0 to emptyList()
            val ranges = if (rule.regex) {
                val re = compile(rule.pattern) ?: return 0 to emptyList()
                guarded(rule.pattern, text) { timed ->
                    re.findAll(timed(text)).filter { it.range.last >= it.range.first }.map { it.range }.take(100_000).toList()
                }
            } else {
                buildList {
                    var i = text.indexOf(rule.pattern)
                    while (i >= 0 && size < 100_000) {
                        add(i until i + rule.pattern.length)
                        i = text.indexOf(rule.pattern, i + rule.pattern.length)
                    }
                }
            }
            val examples = ranges.take(samples).map { r ->
                val before = text.substring(maxOf(0, r.first - 12), r.first).substringAfterLast('\n')
                val after = text.substring(r.last + 1, minOf(text.length, r.last + 13)).substringBefore('\n')
                "$before【${text.substring(r.first, r.last + 1).replace("\n", "↵")}】$after"
            }
            return ranges.size to examples
        }

        /** 一次正则操作最多跑多久（整段文字一起算的按字数放宽，见 [limitFor]）。测试里可以调短。 */
        @Volatile
        internal var timeoutMillis = 5_000L

        /** 整段文字一起算的正则（净化、统计）：每 100 万字多给一份 [timeoutMillis]，大书在慢手机上正常的规则不会被判太慢。 */
        internal fun limitFor(length: Int): Long = timeoutMillis * (1 + length / 1_000_000)

        /**
         * 正则的写法明显会灾难性回溯时，给一句说明，否则为 null：套在一起的重复，如 `(a+)+`、`(a*)*`、`(\w+\s?)+`
         * （括号里只有一处不限次数的重复、其余都可有可无，括号外又不限次数地重复），以及 `(a|a)*` 这种重复里有两个一样的分支。
         * 只认得出最常见的几种，宁可漏报，不误报正常的写法；占有量词（`++`）和固化分组（`(?>…)`）不回溯，不算。
         */
        fun risky(pattern: String): String? =
            runCatching { RegexRisk(pattern).scan() }.getOrNull()?.let { "有套在一起的重复 $it，在长行上可能回溯很久、跑不完" }

        /** 跑正则的线程：太慢的那次停不下来时只能丢下它，不能占着协程的线程池。 */
        private val regexThreads = Executors.newCachedThreadPool { task -> Thread(task, "regex-guard").apply { isDaemon = true } }

        /** 停不下来的正则线程最多几条：到了这个数，新的正则直接拒绝（busy），重启应用后恢复。 */
        const val MAX_STUCK = 2

        /**
         * 跑正则的通道，共 [MAX_STUCK] 条：每次正则占一条，线程真跑完才放开。停不下来的线程一直占着自己那条，
         * 所以卡住的线程最多 [MAX_STUCK] 条；只卡住一条时，别的书、别的规则照常走剩下的通道。
         */
        private val lanes = Semaphore(MAX_STUCK)

        /** 现在停不下来的线程有几条（它们跑完会减回去）。 */
        private val stuckCount = AtomicInteger(0)

        /** 现在停不下来的线程有几条（测试用）。 */
        internal val stuckNow: Int get() = stuckCount.get()

        /**
         * 一次正则：[state] 0 还没开始、1 在跑、2 跑完（或没开始就取消了）；[stuck] 是过了期限还停不下来、已经算进 [stuckCount]。
         * 调用方不等了（超时、取消）而它还在跑，就放进 [abandoned]，等它跑完或被认定停不下来。
         */
        private class Run(val pattern: String, val deadline: Long) {
            private var state = 0
            private var stuck = false
            val done = CountDownLatch(1)

            val running: Boolean @Synchronized get() = state == 1

            /** 线程开始跑：还没被取消才跑。 */
            @Synchronized
            fun begin(): Boolean = (state == 0).also { if (it) state = 1 }

            /** 调用方不等了而它还没开始：直接作废，返回真（由调用方放开通道）。 */
            @Synchronized
            fun cancelBeforeStart(): Boolean = (state == 0).also {
                if (it) {
                    state = 2
                    done.countDown()
                }
            }

            /** 线程跑完（正常、出错、终于停下）。 */
            @Synchronized
            fun finish() {
                state = 2
                if (stuck) stuckCount.decrementAndGet()
                done.countDown()
            }

            /** 认定它停不下来：还在跑、之前没认定过时记一笔，返回真。 */
            @Synchronized
            fun markStuck(): Boolean = (state == 1 && !stuck).also {
                if (it) {
                    stuck = true
                    stuckCount.incrementAndGet()
                }
            }
        }

        /** 调用方已经不等了、可能还在跑的那几次。 */
        private val abandoned = HashSet<Run>()

        /** 过了期限多久还没停，才算停不下来（JVM 上读文字的那一层很快就让它停下）。 */
        private const val GRACE_NANOS = 300_000_000L

        /** 已经超时过的“正则 + 文字”，不再重跑（每重跑一次，可能又多一条停不下来的线程）。 */
        private val slow = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 64
        }

        /** 停不下来过的正则（按写法记）：再用直接报“这条规则太慢，已停用”，不再起线程；别的正则不受影响。 */
        private val disabled = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 64
        }

        /** 这条正则是否因为停不下来被停用了。 */
        fun isDisabled(pattern: String): Boolean = synchronized(disabled) { disabled.containsKey(pattern) }

        /** 认定 [run] 停不下来：记进 [stuckCount]，停用它的写法。 */
        private fun markStuck(run: Run): Boolean {
            synchronized(abandoned) { abandoned.remove(run) }
            if (!run.markStuck()) return false
            synchronized(disabled) { disabled[run.pattern] = Unit }
            return true
        }

        /** 看一遍不等了的那几次：过了期限加宽限还在跑的，认定停不下来。 */
        private fun sweep() {
            val now = System.nanoTime()
            val overdue = synchronized(abandoned) { abandoned.filter { now - it.deadline > GRACE_NANOS } }
            overdue.forEach { if (it.running) markStuck(it) else synchronized(abandoned) { abandoned.remove(it) } }
        }

        /**
         * 限时跑一次正则操作，超时抛 [RegexTooSlowException]；调用的线程被中断（协程取消）时抛 InterruptedException。
         * [block] 拿 timed(文字) 去匹配：包了一层到点就抛异常的 CharSequence，正则引擎逐字读文字时（JVM）能马上停下。
         * Android 的正则引擎（ICU）先把文字复制成 String、在本地代码里匹配，读不到这一层，也不响应中断，
         * 所以另起线程跑、调用方只等到期限为止；那条线程停不下来，只能丢下它，让它在后台自己跑完。
         * 丢下以后过了宽限还停不下来的，只停用这一条正则（按写法记，再用直接报 disabled），别的正则、别的书照常能跑；
         * 停不下来的线程同时最多 [MAX_STUCK] 条（每条占着一个通道），到了上限新的请求抛 busy，不再起线程。
         * 期限 [limit] 默认按 [text] 的字数放宽（[limitFor]）。
         */
        internal fun <T> guarded(pattern: String, text: String, limit: Long = limitFor(text.length), block: ((CharSequence) -> CharSequence) -> T): T {
            val key = "${pattern.length}:$pattern|${text.length}:${text.hashCode()}"
            while (true) {
                sweep()
                if (isDisabled(pattern)) throw RegexTooSlowException(pattern, disabled = true)
                if (synchronized(slow) { slow.containsKey(key) }) throw RegexTooSlowException(pattern)
                if (stuckCount.get() >= MAX_STUCK) throw RegexTooSlowException(pattern, busy = true)
                if (lanes.tryAcquire(20, TimeUnit.MILLISECONDS)) break
            }
            val deadline = System.nanoTime() + limit * 1_000_000
            val run = Run(pattern, deadline)
            val task = try {
                regexThreads.submit(
                    Callable {
                        if (!run.begin()) return@Callable null
                        try {
                            block { chars -> TimedChars(chars, deadline, pattern) }
                        } finally {
                            run.finish()
                            synchronized(abandoned) { abandoned.remove(run) }
                            lanes.release()
                        }
                    },
                )
            } catch (rejected: Exception) {
                lanes.release()
                throw rejected
            }
            // 调用方不等了：还没开始的，自己放开通道；已经在跑的，记下来，等它跑完再放开。
            fun giveUp() {
                task.cancel(true)
                if (run.cancelBeforeStart()) lanes.release() else synchronized(abandoned) { if (run.running) abandoned += run }
            }
            try {
                @Suppress("UNCHECKED_CAST")
                return task.get(limit, TimeUnit.MILLISECONDS) as T
            } catch (timeout: TimeoutException) {
                giveUp()
                synchronized(slow) { slow[key] = Unit }
                // JVM 上读文字那一层很快让它停下；过了宽限还停不下来（Android 的 ICU），停用这一条正则。
                if (!run.done.await(GRACE_NANOS, TimeUnit.NANOSECONDS) && markStuck(run)) throw RegexTooSlowException(pattern, disabled = true)
                throw RegexTooSlowException(pattern)
            } catch (failed: ExecutionException) {
                val cause = failed.cause ?: failed
                if (cause is RegexTooSlowException) synchronized(slow) { slow[key] = Unit }
                throw cause
            } catch (interrupted: InterruptedException) {
                giveUp()
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }
}

/** 正则引擎读文字时顺便看表：过了期限，或线程被中断（已经没人等结果），就抛异常让匹配停下。 */
private class TimedChars(private val text: CharSequence, private val deadline: Long, private val pattern: String) : CharSequence {
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if ((++reads and 0xFFF) == 0 &&(System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted)) {
            throw RegexTooSlowException(pattern)
        }
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TimedChars(text.subSequence(startIndex, endIndex), deadline, pattern)

    override fun toString(): String = text.toString()
}

/**
 * 按正则的结构找明显会灾难性回溯的写法（见 [TextRules.risky]）：逐个读“单元”（字、转义、字符类、括号），
 * 记下每个分支里不限次数的重复有几处、必须匹配的单元有几个。找到了返回那一段写法，没找到返回 null。
 */
private class RegexRisk(private val p: String) {
    private var i = 0

    /** 一层括号：[alts] 是各分支的原文，[unbounded]、[required] 是当前分支里不限次数的单元数、必须匹配的单元数。 */
    private class Group(val start: Int, val atomic: Boolean, val zeroWidth: Boolean) {
        val alts = mutableListOf(StringBuilder())
        var unbounded = 0
        var required = 0

        /** 有一个分支只靠一处不限次数的重复（其余可有可无）就能匹配：外面再套一层重复，切法就有指数多种。 */
        var selfRepeat = false

        fun endAlt() {
            if (unbounded == 1 && required == 0) selfRepeat = true
            unbounded = 0
            required = 0
        }
    }

    fun scan(): String? {
        val stack = ArrayDeque<Group>().apply { addLast(Group(0, atomic = false, zeroWidth = false)) }
        while (i < p.length) {
            val atomStart = i
            var group: Group? = null
            var zeroWidth = false
            when (val ch = p[i]) {
                '\\' -> zeroWidth = escape()
                '[' -> charClass()
                '(' -> {
                    if (p.startsWith("(?", i) && flagsOnly()) continue
                    val look = p.startsWith("(?=", i) || p.startsWith("(?!", i) || p.startsWith("(?<=", i) || p.startsWith("(?<!", i)
                    val atomic = p.startsWith("(?>", i)
                    stack.addLast(Group(i, atomic, look))
                    i = when {
                        p.startsWith("(?<=", i) || p.startsWith("(?<!", i) -> i + 4
                        p.startsWith("(?<", i) -> p.indexOf('>', i).let { if (it < 0) return null else it + 1 }
                        // (?:  (?=  (?!  (?>  (?i:  (?-m:
                        p.startsWith("(?", i) -> {
                            var j = i + 2
                            while (j < p.length && (p[j].isLetter() || p[j] == '-')) j++
                            if (j >= p.length || p[j] !in ":=!>") return null
                            j + 1
                        }
                        else -> i + 1
                    }
                    continue
                }
                ')' -> {
                    if (stack.size < 2) return null
                    group = stack.removeLast()
                    group.endAlt()
                    zeroWidth = group.zeroWidth
                    i++
                }
                '|' -> {
                    val g = stack.last()
                    g.endAlt()
                    g.alts += StringBuilder()
                    i++
                    continue
                }
                '^', '$' -> {
                    zeroWidth = true
                    i++
                }
                else -> {
                    i += Character.charCount(p.codePointAt(i))
                    if (ch == '*' || ch == '+' || ch == '?' || ch == '{' && quantifierAt(i - 1) != null) return null // 量词前面没有东西：写错了
                }
            }
            val q = quantifier()
            val text = p.substring(group?.start ?: atomStart, i)
            val parent = stack.last()
            if (group != null && q != null && q.unbounded && !q.possessive && !group.atomic && !group.zeroWidth) {
                // 一样的分支：`(a|a)*`。
                val alts = group.alts.map { it.toString() }
                if (group.selfRepeat || alts.size > 1 && alts.toSet().size < alts.size) return text
            }
            val unbounded = q != null && q.unbounded && !q.possessive || group != null && group.selfRepeat && !group.atomic
            val optional = zeroWidth || q != null && q.min == 0
            if (unbounded) parent.unbounded++ else if (!optional) parent.required++
            parent.alts.last().append(text)
        }
        return null
    }

    private class Quantifier(val min: Int, val unbounded: Boolean, val possessive: Boolean)

    /** 读单元后面的量词（含懒惰的 `?`、占有的 `+`），没有为 null。 */
    private fun quantifier(): Quantifier? {
        if (i >= p.length) return null
        val q = when (p[i]) {
            '*' -> Quantifier(0, true, false).also { i++ }
            '+' -> Quantifier(1, true, false).also { i++ }
            '?' -> Quantifier(0, false, false).also { i++ }
            '{' -> quantifierAt(i)?.also { i = p.indexOf('}', i) + 1 } ?: return null
            else -> return null
        }
        return when {
            i < p.length && p[i] == '+' -> Quantifier(q.min, q.unbounded, true).also { i++ }
            i < p.length && p[i] == '?' -> q.also { i++ }
            else -> q
        }
    }

    /** `{n}`、`{n,}`、`{n,m}`；不是这几种（照字面的花括号）为 null。 */
    private fun quantifierAt(at: Int): Quantifier? {
        val end = p.indexOf('}', at)
        if (end < 0) return null
        val m = Regex("""(\d+)(,(\d*))?""").matchEntire(p.substring(at + 1, end)) ?: return null
        val min = m.groupValues[1].toIntOrNull() ?: return null
        val unbounded = m.groupValues[2].isNotEmpty() && m.groupValues[3].isEmpty()
        return Quantifier(min, unbounded, false)
    }

    /** 读一个转义，返回它是不是零宽的（`\b` 这类）。 */
    private fun escape(): Boolean {
        val next = p.getOrNull(i + 1) ?: run { i++; return false }
        i += 2
        when (next) {
            'p', 'P', 'x', 'N' -> if (p.getOrNull(i) == '{') i = p.indexOf('}', i).let { if (it < 0) p.length else it + 1 } else if (next != 'x') i++ else i += 2
            'k' -> if (p.getOrNull(i) == '<') i = p.indexOf('>', i).let { if (it < 0) p.length else it + 1 }
            'Q' -> i = p.indexOf("\\E", i).let { if (it < 0) p.length else it + 2 }
            'u' -> i += 4
            'b', 'B', 'A', 'z', 'Z', 'G' -> return true
        }
        i = i.coerceAtMost(p.length)
        return false
    }

    /** 读一个字符类 `[…]`（可以套着 `[a-z&&[^e]]`）。 */
    private fun charClass() {
        var depth = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '\\' -> i++
                ch == '[' -> {
                    depth++
                    // 开头的 ^ 和紧跟着的 ] 照字面算。
                    if (p.getOrNull(i + 1) == '^') i++
                    if (p.getOrNull(i + 1) == ']') i++
                }
                ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        i++
                        return
                    }
                }
            }
            i++
        }
    }

    /** `(?i)`、`(?-m)` 这种只改选项的括号：跳过，返回真。 */
    private fun flagsOnly(): Boolean {
        var j = i + 2
        while (j < p.length && (p[j].isLetter() || p[j] == '-')) j++
        if (j > i + 2 && p.getOrNull(j) == ')') {
            i = j + 1
            return true
        }
        return false
    }
}
