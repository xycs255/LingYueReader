package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/** 文风范例的三类：一段叙述描写、一段对白多的、一段动作场面。 */
enum class SampleKind(val label: String) {
    NARRATION("叙述描写"),
    DIALOGUE("对白"),
    ACTION("动作场面"),
}

/** 挑出的一段文风范例：出自哪一章、哪一类、原文。 */
data class StyleSample(val chapterId: String, val chapterTitle: String, val kind: SampleKind, val text: String) {
    /** 认这一段用的键：章 id 加文字指纹。那一章改过、这段文字变了，就对不上了（“不用这段”也随之失效）。 */
    val key: String get() = chapterId + ":" + StyleSamples.fingerprint(text)
}

/** 挑段用的一章：按目录顺序给。 */
data class SampleChapter(val id: String, val title: String, val text: String)

/**
 * 挑段的设置（作品的 AI 设置里存着）：[limit] 总字数上限（0 是不带），[skip] 是作者点了“不用这段”的键，
 * [shift] 是各类点了几次“换一段”（按 [SampleKind] 的名字）。
 * 下面两项是这一次写的时候才有的：[avoid] 是这次要写的场面（本章细纲、节拍），和它高度重合的段不挑，免得模型照着抄；
 * [shown] 是请求里已经带了的正文（上一章结尾），里面的段不再当范例。
 */
data class SampleOptions(
    val limit: Int = StyleSamples.DEFAULT_LIMIT,
    val skip: Set<String> = emptySet(),
    val shift: Map<String, Int> = emptyMap(),
    val avoid: String = "",
    val shown: String = "",
)

/** 挑段的结果：[fallback] 为真表示没有定稿章、退回用了字数最多的几章。 */
data class SamplePick(val samples: List<StyleSample>, val fallback: Boolean = false, val sources: Int = 0) {
    val chars: Int get() = samples.sumOf { it.text.length }
}

/**
 * 从作者定稿的章节里挑几段当文风范例，交给写作 AI 学写法（句式、节奏、用词、对白方式）。全在本机算，不联网。
 *
 * 规则：
 * - 只从当前章之前的章节里挑（写第 N 章时不拿第 N 章和之后的，防剧透）；只用定稿章，
 *   没有定稿章时（作者开着“退回”）用之前字数最多的 [FALLBACK_CHAPTERS] 章，都没有就不带；
 * - 每章按段落切出 200–500 字的片段（相邻几段合成一片，一段太长在句末截断），用简单的特征给片段打分：
 *   引号里的字占比（对白）、短句比例和动作字密度、感叹号破折号（动作）、长句和少引号（叙述描写）；
 * - 对白戏、动作冲突戏优先：按对白、动作、叙述的次序轮流挑分最高的，叙述描写至多一段，挑到总字数上限放不下为止；
 * - 不挑章节开头那几段，不挑以天气、景物起笔的段（模型最爱照着它起笔，开头就写雾），
 *   不挑和这次要写的场面高度重合的段（[SampleOptions.avoid]），不挑请求里已经带了的段（[SampleOptions.shown]）；
 * - 结果只由章节内容和设置决定：同样的内容挑出同样的片段，提示词前缀稳定，服务商能缓存。
 */
object StyleSamples {
    /** 作品设置里可选的总字数上限：关、800、1500、2500、3000、5000。 */
    val LIMITS = listOf(0, 800, 1500, 2500, 3000, 5000)
    const val DEFAULT_LIMIT = 2_500

    /** 以前的默认值：存着它、又没另存新字段的作品当作没改过（见 WorkAssist 的读取）。 */
    const val OLD_DEFAULT_LIMIT = 1_500

    /** 章节开头这么多段不挑：开头多是铺垫和景物，拿来当范例，模型就照着它起笔。 */
    const val OPENING_PARAGRAPHS = 3

    /** 和这次要写的场面共有这么多个少见的二元组（人名这类到处都有的不算），就算高度重合。 */
    const val OVERLAP = 8
    /** 一段最少、最多多少字。 */
    const val MIN = 200
    const val MAX = 500
    /** 没有定稿章时，退回用字数最多的几章。 */
    const val FALLBACK_CHAPTERS = 3
    /** 定稿章太多时只看最近的这么多章（越近越像现在的写法，也省得每次读一整部）。 */
    const val MAX_SOURCES = 30

    /** 文风范例在提示词里的开头一句（测试、找位置用）。 */
    const val HEADER = "【文风范例】下面是作者自己写的几段。照这个语感写：句子节奏、对话方式、叙述的口气。不要照抄内容。"

    /**
     * 挑哪几章：[chapters] 是全书按目录顺序的章，[before] 是当前章的位置（只用它之前的；为空时全书都算，设置页预览用）。
     * 返回要读正文的章和是不是退回用了字数最多的章。
     */
    fun sources(chapters: List<WorkChapter>, before: Int?, fallback: Boolean): Pair<List<WorkChapter>, Boolean> {
        val earlier = chapters.take((before ?: chapters.size).coerceIn(0, chapters.size)).filter { it.words > 0 }
        val finals = earlier.filter { it.status == ChapterStatus.FINAL }
        if (finals.isNotEmpty()) return finals.takeLast(MAX_SOURCES) to false
        if (!fallback) return emptyList<WorkChapter>() to false
        // 字数一样时取靠前的章：结果稳定。保持目录顺序。
        val top = earlier.withIndex().sortedWith(compareByDescending<IndexedValue<WorkChapter>> { it.value.words }.thenBy { it.index })
            .take(FALLBACK_CHAPTERS).sortedBy { it.index }.map { it.value }
        return top to top.isNotEmpty()
    }

    /** 一段候选：哪一章、从第几段到第几段（不含）、文字和各类的分。 */
    private class Piece(val chapter: Int, val from: Int, val to: Int, val text: String, val scores: Map<SampleKind, Double>)

    /** 从 [chapters]（已按 [sources] 选好、按目录顺序）里挑。 */
    fun pick(chapters: List<SampleChapter>, options: SampleOptions): List<StyleSample> {
        if (options.limit < MIN) return emptyList()
        // 一段最长多少：总量小时每段短些，三类都能放下。
        val longest = (options.limit / SampleKind.entries.size).coerceIn(MIN, MAX)
        val all = chapters.flatMapIndexed { ci, c -> pieces(ci, c.text, longest) }
        val similar = overlapping(all, options.avoid)
        val pieces = all.filter { p ->
            p.from >= OPENING_PARAGRAPHS && !sceneryOpening(p.text) && p !in similar && !shownIn(p, options.shown)
        }
        val ranked = ORDER.associateWith { kind ->
            pieces.filter { kind in it.scores }
                .sortedWith(compareByDescending<Piece> { it.scores.getValue(kind) }.thenBy { it.chapter }.thenBy { it.from })
                // 相邻的候选大半重叠：只留分高的那片，“换一段”才真换到别处。
                .fold(mutableListOf<Piece>()) { kept, p ->
                    if (kept.none { q -> q.chapter == p.chapter && p.from < q.to && q.from < p.to }) kept += p
                    kept
                }
        }
        val chosen = mutableListOf<Pair<SampleKind, Piece>>()
        var total = 0
        // 对白、动作、叙述轮流挑，直到放不下；叙述描写至多一段。
        while (true) {
            var added = false
            for (kind in ORDER) {
                if (kind == SampleKind.NARRATION && chosen.any { it.first == kind }) continue
                val usable = ranked.getValue(kind).filter { p ->
                    sample(chapters, kind, p).key !in options.skip &&
                        chosen.none { (_, q) -> q.chapter == p.chapter && p.from < q.to && q.from < p.to }
                }
                if (usable.isEmpty()) continue
                // “换一段”：每点一次往后挪一个（挪到底再从头来）。只对这一类的第一段起作用。
                val round = chosen.count { it.first == kind }
                val shift = if (round == 0) (options.shift[kind.name] ?: 0) else 0
                val p = usable[Math.floorMod(shift, usable.size)].takeIf { total + it.text.length <= options.limit }
                    ?: usable.firstOrNull { total + it.text.length <= options.limit }
                    ?: continue
                chosen += kind to p
                total += p.text.length
                added = true
            }
            if (!added) break
        }
        // 按书里的先后排：读起来顺，顺序也稳定。
        return chosen.sortedWith(compareBy({ it.second.chapter }, { it.second.from })).map { (kind, p) -> sample(chapters, kind, p) }
    }

    /** 挑的次序：对白戏、动作冲突戏在前。 */
    private val ORDER = listOf(SampleKind.DIALOGUE, SampleKind.ACTION, SampleKind.NARRATION)

    /** 天气、景物（只看第一句开头那几个字）。 */
    private val scenery = Regex("雾|雨|雪|风|云|天色|天光|月光|日光|阳光|晨光|夕阳|晚霞|黄昏|夜色|暮色|河面|江面|水面|湖面")

    /** 以天气、景物起笔：第一句前 [SCENERY_HEAD] 个字里写天气景物，又没有对白、没有动作。 */
    internal fun sceneryOpening(text: String): Boolean {
        val first = text.trim().split(Regex("(?<=[。！？!?…\\n])")).first()
        if (quotedChars(first) > 0 || first.any { it in ACTION_CHARS }) return false
        return scenery.find(first)?.let { it.range.first < SCENERY_HEAD } == true
    }

    private const val SCENERY_HEAD = 12

    /**
     * 和这次要写的场面（[avoid]）高度重合的片段：共有 [OVERLAP] 个以上的二元组。到处都有的二元组（三成以上的片段里都有，
     * 多是人名、常用词）不算，只算少见的。
     */
    private fun overlapping(pieces: List<Piece>, avoid: String): Set<Piece> {
        val target = PackRanking.bigrams(avoid)
        if (target.isEmpty() || pieces.isEmpty()) return emptySet()
        val grams = pieces.map { PackRanking.bigrams(it.text) }
        val df = HashMap<String, Int>()
        grams.forEach { g -> g.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val common = maxOf(2, (pieces.size * 0.3).toInt())
        return pieces.indices.filter { i -> grams[i].count { it in target && (df[it] ?: 0) < common } >= OVERLAP }.map { pieces[it] }.toSet()
    }

    /** 片段里有一段（十个字以上）已经在请求里了。 */
    private fun shownIn(p: Piece, shown: String): Boolean =
        shown.isNotBlank() && p.text.lines().map { it.trim() }.any { it.length >= 10 && it in shown }

    private fun sample(chapters: List<SampleChapter>, kind: SampleKind, p: Piece): StyleSample =
        chapters[p.chapter].let { StyleSample(it.id, it.title, kind, p.text) }

    /** 一章切成候选片段：从每一段起，合上后面几段到够 [MIN] 字，不超过 [longest]。 */
    private fun pieces(chapter: Int, text: String, longest: Int): List<Piece> {
        val paragraphs = text.lines().map { it.trim().trim('　').trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Piece>()
        var i = 0
        while (i < paragraphs.size) {
            val first = paragraphs[i]
            val body: String
            var end = i + 1
            if (first.length > longest) {
                // 一段就太长：在句末截断。
                body = cutAtSentence(first, longest) ?: run { i++; continue }
            } else {
                val sb = StringBuilder(first)
                while (sb.length < MIN && end < paragraphs.size && sb.length + 1 + paragraphs[end].length <= longest) {
                    sb.append('\n').append(paragraphs[end])
                    end++
                }
                body = sb.toString()
            }
            if (body.length >= MIN) out += Piece(chapter, i, end, body, score(body, if (first.length > longest) listOf(body) else paragraphs.subList(i, end)))
            i++
        }
        return out
    }

    /** 在 [max] 字以内找最后一个句末（不短于 [MIN]），截到那里；找不到返回空。 */
    private fun cutAtSentence(p: String, max: Int): String? {
        val window = p.take(max)
        val at = window.indexOfLast { it in "。！？…" }.let { j ->
            // 句末后面紧跟的收引号一起带上。
            if (j >= 0 && j + 1 < window.length && window[j + 1] in "”」』") j + 1 else j
        }
        return if (at + 1 >= MIN) window.substring(0, at + 1) else null
    }

    /** 动作场面常见的字（简单的特征，不分词）。 */
    private const val ACTION_CHARS = "冲扑挥砍劈刺踢踹抓拽扯推撞跃跳翻滚闪躲拔掷甩砸挡拦追奔跑扑抡斩戳撑蹬攥按"

    /**
     * 给一片打分：整片符合某一类、而且这一片里至少 [DOMINANT] 的字出自本身也像这一类的段落，才有那一类的分
     * （免得几句对白接一段打斗，被当成“动作场面”）。
     */
    private fun score(text: String, paragraphs: List<String>): Map<SampleKind, Double> {
        val whole = score(text)
        if (paragraphs.size <= 1) return whole
        val each = paragraphs.map { it to score(it) }
        return whole.filterKeys { kind -> each.filter { kind in it.second }.sumOf { it.first.length } >= DOMINANT * paragraphs.sumOf { it.length } }
    }

    /** 一片里至少多少比例的字要出自像这一类的段落。 */
    private const val DOMINANT = 0.6

    /** 给一段文字打分：符合某一类才有那一类的分。 */
    internal fun score(text: String): Map<SampleKind, Double> {
        val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val quoted = quotedChars(text).toDouble() / chars
        val sentences = text.split(Regex("[。！？!?…\\n]+")).map { it.trim('“', '”', '「', '」', ' ', '　') }.filter { it.isNotEmpty() }
        val count = sentences.size.coerceAtLeast(1)
        val shortRatio = sentences.count { it.length <= 10 }.toDouble() / count
        val avgLen = sentences.sumOf { it.length }.toDouble() / count
        val actionDensity = text.count { it in ACTION_CHARS } * 100.0 / chars
        val bangDash = (text.count { it == '！' || it == '!' } + Regex("——").findAll(text).count()) * 100.0 / chars
        val out = HashMap<SampleKind, Double>()
        if (quoted >= 0.35) out[SampleKind.DIALOGUE] = quoted
        if (quoted <= 0.3 && actionDensity >= 1.5) out[SampleKind.ACTION] = shortRatio + actionDensity * 0.15 + bangDash * 0.1
        if (quoted <= 0.08) out[SampleKind.NARRATION] = (avgLen / 40).coerceAtMost(1.0) + (1 - shortRatio) * 0.5 - actionDensity * 0.05
        return out
    }

    /** 引号（“” 「」 『』）里的字数。 */
    private fun quotedChars(text: String): Int {
        var depth = 0
        var n = 0
        for (ch in text) {
            when (ch) {
                '“', '「', '『' -> depth++
                '”', '」', '』' -> depth = (depth - 1).coerceAtLeast(0)
                else -> if (depth > 0 && !ch.isWhitespace()) n++
            }
        }
        return n
    }

    /** 文字指纹：内容一样指纹就一样（跨进程、跨设备都一样，不用 hashCode 的随机性）。 */
    fun fingerprint(text: String): String {
        val crc = java.util.zip.CRC32()
        crc.update(text.toByteArray(Charsets.UTF_8))
        return java.lang.Long.toHexString(crc.value)
    }

    /** 接在请求最后（写法要求里，见 WritePrompts.voiceTail）的一节；没有范例时为空。 */
    fun render(samples: List<StyleSample>): String? {
        if (samples.isEmpty()) return null
        return buildString {
            append(HEADER).append('\n')
            samples.forEach { s ->
                append("【").append(s.kind.label).append(" · 出自「").append(s.chapterTitle).append("」】\n")
                append(s.text.trim()).append('\n')
            }
        }.trimEnd()
    }

    /** 本机模型上下文小：只带三分之一（1500 字时 500 字，大约一段），不够一段就不带。 */
    fun localLimit(limit: Int): Int = (limit / 3).let { if (it < MIN) 0 else it }

    // ---------- 挑选结果的缓存（作品目录下的 style-samples.json，不进备份，随时能重算） ----------

    /** 缓存最多记几份（写不同的章时来源不同，各存一份）。 */
    const val CACHE_KEEP = 8

    /**
     * 缓存的键：来源各章的 id、字数、修改时间、状态，加上设置。章没变就不用重读正文重挑。
     */
    fun signature(sources: List<WorkChapter>, fallback: Boolean, options: SampleOptions): String = fingerprint(
        buildString {
            sources.forEach { append(it.id).append('/').append(it.words).append('/').append(it.updatedAt).append('/').append(it.status.name).append(';') }
            append(fallback).append('|').append(options.limit).append('|')
            if (options.avoid.isNotBlank()) append(fingerprint(options.avoid)).append('|')
            if (options.shown.isNotBlank()) append(fingerprint(options.shown)).append('|')
            options.skip.sorted().forEach { append(it).append(',') }
            append('|')
            options.shift.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append(',') }
        },
    )

    fun encodeCache(entries: List<Pair<String, SamplePick>>): String = JSONArray().apply {
        entries.takeLast(CACHE_KEEP).forEach { (sig, pick) ->
            put(
                JSONObject().put("sig", sig).put("fallback", pick.fallback).put("sources", pick.sources).put("samples", JSONArray().apply {
                    pick.samples.forEach { s -> put(JSONObject().put("chapterId", s.chapterId).put("title", s.chapterTitle).put("kind", s.kind.name).put("text", s.text)) }
                }),
            )
        }
    }.toString()

    fun decodeCache(raw: String?): List<Pair<String, SamplePick>> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ss = o.optJSONArray("samples") ?: JSONArray()
            val samples = (0 until ss.length()).mapNotNull { j ->
                val s = ss.optJSONObject(j) ?: return@mapNotNull null
                val kind = runCatching { SampleKind.valueOf(s.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                StyleSample(s.optString("chapterId"), s.optString("title"), kind, s.optString("text"))
            }
            o.optString("sig") to SamplePick(samples, o.optBoolean("fallback"), o.optInt("sources"))
        }
    }.getOrDefault(emptyList())
}
