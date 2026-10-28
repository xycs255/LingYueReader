package com.xycs.lingyuereader.data

/**
 * 细纲卡里的一拍：[index] 是序号（从 1 数），[text] 是这一拍发生什么（去掉了行首序号和行尾篇幅），
 * [length] 是行尾写的篇幅（字），没写为空。
 */
data class Beat(val index: Int, val text: String, val length: Int? = null)

/**
 * 写某一拍时要告诉 AI 的：这一拍、上一拍（已经写过）、下一拍（不要写到）、共几拍、这一拍写多少字。
 * [length] 是这一拍行尾写的篇幅，没写时按全章目标篇幅除以拍数估的；都算不出来为空。
 */
data class BeatTask(val beat: Beat, val total: Int, val previous: Beat?, val next: Beat?, val length: Int?)

/**
 * 节拍：细纲卡下面再细一层，一行一拍。作者让 AI 写“这一章的某一段”时，按拍告诉它写到哪一步、哪里停。
 * 行首的序号（“1.”“①”“- ”“第一拍：”）只是作者的写法，解析时去掉；行尾的“（800字）”“（约 800 字）”是这一拍的篇幅，
 * 取出来单独用，显示和发给 AI 时去掉，免得 AI 把它当正文。
 */
object Beats {
    /** 最多认多少拍：再多就不是“拍”而是流水账了，也防备粘进来整段大纲。 */
    const val MAX = 30

    private val bullet = Regex("^(?:[-*•·+]|[①-⑳]|[（(][0-9０-９一二三四五六七八九十]+[)）]|[0-9０-９]+\\s*[.．、)）:：]|第[0-9０-９一二三四五六七八九十]+拍\\s*[：:、.．]?)\\s*")
    private val tail = Regex("[（(]\\s*(?:约|大约|大概)?\\s*([0-9０-９]+)\\s*(?:个)?(?:汉)?字?\\s*(?:左右|上下)?\\s*[)）]\\s*$")
    private val number = Regex("[0-9０-９]+")
    private val rangeMark = Regex("[0-9０-９]\\s*(?:[—－–\\-~～至到]|-)\\s*[0-9０-９]")

    /** 一行一拍；空行、只有序号的行不算。 */
    fun parse(text: String): List<Beat> = text.lines().asSequence()
        .map { strip(it) }
        .mapNotNull { line ->
            val m = tail.find(line)
            val body = (if (m != null) line.substring(0, m.range.first) else line).trim().trimEnd('，', ',', '；', ';')
            if (body.isEmpty()) null else body to m?.groupValues?.get(1)?.let(::digits)?.takeIf { it > 0 }
        }
        .take(MAX)
        .mapIndexed { i, (body, length) -> Beat(i + 1, body, length) }
        .toList()

    /** 去掉行首序号、Markdown 的加粗和前后空白（全角空格也算）。 */
    private fun strip(raw: String): String {
        var line = raw.replace("**", "").trim().trim('　').trim()
        // “- 1. 沈默……”：列表符号后面还有序号，最多去两层。
        repeat(2) { line = line.replaceFirst(bullet, "").trim() }
        return line
    }

    private fun digits(raw: String): Int? =
        raw.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").toIntOrNull()

    /**
     * 全章目标篇幅（“3000 到 5000 字”“不少于 3000 字”）按 [count] 拍平均分，取整到 50 字；
     * 篇幅里没有字数（“依本章细纲……”）时为空。
     */
    fun share(chapterLength: String, count: Int): Int? {
        if (count <= 0 || '字' !in chapterLength) return null
        val values = number.findAll(chapterLength).mapNotNull { digits(it.value) }.filter { it in 100..1_000_000 }.toList()
        if (values.isEmpty()) return null
        val total = if (values.size >= 2 && rangeMark.containsMatchIn(chapterLength)) (values[0] + values[1]) / 2 else values[0]
        return ((total.toDouble() / count / 50).let(Math::round) * 50).toInt().coerceAtLeast(100)
    }

    /** 写第 [number] 拍（从 1 数）要带的上下文；篇幅没写就按 [chapterLength] 平均分。 */
    fun task(beats: List<Beat>, number: Int, chapterLength: String): BeatTask {
        val beat = beats[number - 1]
        return BeatTask(beat, beats.size, beats.getOrNull(number - 2), beats.getOrNull(number), beat.length ?: share(chapterLength, beats.size))
    }

    /**
     * “AI 写整章草稿”从第几拍写起（从 1 数）；为空时照原来整章写。
     * 本章有 [count] 拍：从头写（[more] 为假，或还没有候选稿）从第 1 拍写起；接着写时看候选稿记的进度 [progress]——
     * 不是逐拍写的稿子、或各拍都写完了，照原来整章接着写；写到一半的，从下一拍接着写。
     * 节拍条数和写的时候不一样了，已写的几拍对不上现在的哪几拍，不猜，请作者从头写。
     */
    fun resume(count: Int, more: Boolean, candidate: String, progress: DraftBeats?): Int? {
        if (count == 0) return null
        if (!more || candidate.isBlank()) return 1
        if (progress == null || progress.written >= progress.total) return null
        check(progress.total == count) {
            "节拍改过了：这版候选稿按 ${progress.total} 拍写到第 ${progress.written} 拍，现在是 $count 拍；请从头写一版"
        }
        return progress.written + 1
    }

    /**
     * 正文已经写到了前几拍（编辑器里“写到本章结尾”用，作者没在细纲卡里勾“已写”时）：从第一拍起，
     * 一拍的字（二元组）有一半以上在 [text] 里出现就算写了，遇到没写的就停。
     */
    fun covered(beats: List<Beat>, text: String): Int {
        val written = PackRanking.bigrams(text)
        var n = 0
        for (b in beats) {
            val grams = PackRanking.bigrams(b.text)
            if (grams.isEmpty() || grams.count { it in written } * 2 < grams.size) break
            n++
        }
        return n
    }

    /** 第一拍没写的（默认选它）；都写了为空。 */
    fun firstUndone(card: ChapterCard): Int? {
        val done = card.beatsDone.toSet()
        return beats(card).firstOrNull { it.index !in done }?.index
    }
}

/** 这张细纲卡的节拍。 */
fun beats(card: ChapterCard): List<Beat> = Beats.parse(card.beats)

/**
 * 改节拍文字：条数变了，原来记的“第几拍已写”可能对不上了，简单起见清空；条数没变就留着
 * （多半是改了措辞或篇幅）。
 */
fun ChapterCard.withBeats(text: String): ChapterCard =
    if (Beats.parse(text).size == Beats.parse(beats).size) copy(beats = text) else copy(beats = text, beatsDone = emptyList())

/** 标第 [number] 拍写没写（作者手动勾，或采纳了 AI 写的这一拍）。序号超出现在的拍数就不记。 */
fun ChapterCard.withBeatDone(number: Int, done: Boolean): ChapterCard {
    val count = Beats.parse(beats).size
    val next = (if (done) beatsDone + number else beatsDone - number).filter { it in 1..count }.distinct().sorted()
    return if (next == beatsDone) this else copy(beatsDone = next)
}
