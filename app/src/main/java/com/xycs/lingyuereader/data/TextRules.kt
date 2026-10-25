package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 一条净化规则：把匹配到的文字换成 [replacement]（留空即删除）。 */
data class ReplaceRule(
    val pattern: String,
    val replacement: String = "",
    /** 为真时 [pattern] 是正则（逐行多行模式，^ $ 匹配行首行尾），否则按原文查找。 */
    val regex: Boolean = false,
    val enabled: Boolean = true,
)

/** 用户写的正则在这段文字上跑得太久（多半是嵌套的重复，如 `(\w+\s?)+$`，在长行上反复回溯），已放弃。 */
class RegexTooSlowException(val pattern: String) : RuntimeException("正则太慢，请改写：$pattern")

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
     * 给了全文 [text] 时，先在一次限时里把全文的短行都判一遍（整本只换一次线程）；没判过的行再逐行限时判。
     * 太慢的正则抛 [RegexTooSlowException]。
     */
    fun titleMatcher(text: String = ""): (String) -> Boolean {
        val re = toc.takeIf { it.isNotBlank() }?.let(::compile) ?: return ChapterTitles::isTxtChapterTitle
        // 和 TxtParser 一样去掉行首行尾的空白；去法对不上时，下面逐行判，结果一样，只是慢一点。
        val known: Map<String, Boolean> = if (text.isEmpty()) emptyMap() else guarded(toc, text) { timed ->
            text.lineSequence().map { it.trim().trim('　').trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_TITLE }
                .distinct()
                .associateWith { re.containsMatchIn(timed(it)) }
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

        /** 一次正则操作（整段文字一起算）最多跑多久。测试里可以调短。 */
        @Volatile
        internal var timeoutMillis = 5_000L

        /** 跑正则的线程：太慢的那次停不下来时只能丢下它，不能占着协程的线程池。 */
        private val regexThreads = Executors.newCachedThreadPool { task -> Thread(task, "regex-guard").apply { isDaemon = true } }

        /** 已经超时过的“正则 + 文字”，不再重跑（每重跑一次，可能又多一条停不下来的线程）。 */
        private val slow = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 64
        }

        /**
         * 限时跑一次正则操作，超时抛 [RegexTooSlowException]；调用的线程被中断（协程取消）时抛 InterruptedException。
         * [block] 拿 timed(文字) 去匹配：包了一层到点就抛异常的 CharSequence，正则引擎逐字读文字时（JVM）能马上停下。
         * Android 的正则引擎（ICU）先把文字复制成 String、在本地代码里匹配，读不到这一层，
         * 所以另起线程跑、调用方只等到期限为止；那条线程停不下来，只能丢下它。
         */
        private fun <T> guarded(pattern: String, text: String, block: ((CharSequence) -> CharSequence) -> T): T {
            val key = "${pattern.length}:$pattern|${text.length}:${text.hashCode()}"
            if (synchronized(slow) { slow.containsKey(key) }) throw RegexTooSlowException(pattern)
            val limit = timeoutMillis
            val deadline = System.nanoTime() + limit * 1_000_000
            val task = regexThreads.submit(Callable { block { chars -> TimedChars(chars, deadline, pattern) } })
            try {
                return task.get(limit, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                task.cancel(true)
                synchronized(slow) { slow[key] = Unit }
                throw RegexTooSlowException(pattern)
            } catch (failed: ExecutionException) {
                val cause = failed.cause ?: failed
                if (cause is RegexTooSlowException) synchronized(slow) { slow[key] = Unit }
                throw cause
            } catch (interrupted: InterruptedException) {
                task.cancel(true)
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
