package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/** 一条净化规则：把匹配到的文字换成 [replacement]（留空即删除）。 */
data class ReplaceRule(
    val pattern: String,
    val replacement: String = "",
    /** 为真时 [pattern] 是正则（逐行多行模式，^ $ 匹配行首行尾），否则按原文查找。 */
    val regex: Boolean = false,
    val enabled: Boolean = true,
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

    /** 依次应用启用的净化规则。写错的正则跳过，不影响其他规则。 */
    fun clean(text: String): String = replace.filter { it.enabled && it.pattern.isNotEmpty() }.fold(text) { acc, r ->
        if (r.regex) {
            compile(r.pattern)?.let { re -> runCatching { re.replace(acc, r.replacement) }.getOrDefault(acc) } ?: acc
        } else {
            acc.replace(r.pattern, r.replacement)
        }
    }

    /** 分章判断：有自定义规则就用它（只看 [MAX_TITLE] 字以内的行），否则用内置识别。 */
    fun titleMatcher(): (String) -> Boolean {
        val re = toc.takeIf { it.isNotBlank() }?.let(::compile) ?: return ChapterTitles::isTxtChapterTitle
        return { line -> line.length <= MAX_TITLE && re.containsMatchIn(line) }
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

        /** 统计净化规则在文字里匹配到几处，并给出几个“前后文”示例（匹配部分用【】标出）。 */
        fun preview(rule: ReplaceRule, text: String, samples: Int = 3): Pair<Int, List<String>> {
            if (rule.pattern.isEmpty()) return 0 to emptyList()
            val ranges = if (rule.regex) {
                val re = compile(rule.pattern) ?: return 0 to emptyList()
                re.findAll(text).filter { it.range.last >= it.range.first }.map { it.range }.take(100_000).toList()
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
    }
}
