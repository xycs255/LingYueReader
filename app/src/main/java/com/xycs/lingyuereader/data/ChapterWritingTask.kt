package com.xycs.lingyuereader.data

import java.security.MessageDigest

/** One job has one stage. A chapter-writing template is not an instruction to its reviewer. */
enum class WritingPhase { CREATE, REVIEW, REVISE, ASSIST, SYNC }

/**
 * The immutable brief for one requested chapter, separate from the candidate manuscript and its review.
 * [previous] contains only the immediately preceding chapter explicitly marked FINAL by the author.
 * [context] is selected reference material, not a claim that every planned event has already happened.
 */
data class ChapterWritingTask(
    val title: String,
    val chapter: Int?,
    val volume: Int,
    val instruction: String,
    val targetLength: String,
    val previous: String,
    val previousLabel: String,
    val card: ChapterCard,
    val sourceNames: List<String>,
    val warnings: List<String>,
    val blocking: List<String>,
    val project: Boolean,
    val fingerprint: String,
    val context: String = "",
) {
    fun render(): String = buildString {
        append("【本次章节任务】\n第 ").append(volume).append(" 卷，第 ").append(chapter ?: "待核").append(" 章：").append(title).append('\n')
        append("本次额外要求：").append(instruction.ifBlank { "无" }).append('\n')
        append("目标篇幅：").append(targetLength).append('\n')
        append("已定前文或结果：").append(previousLabel).append('\n')
        append("当前材料：").append(sourceNames.joinToString("、").ifBlank { "作者的细纲卡" }).append('\n')
        append("资料分工：本次明确要求优先；目标章细纲和用户已定前文确定本章事实与结果，合订本只补核实际涉及的跨章固定事实。")
        append("同版独立细纲与内嵌细纲只算一份事实；硬事实冲突不得用优先级口号掩盖，应列明原出处。\n")
        append("规划、参考样章、草稿和待核增量均不是已经发生的正文事实。流程检查通过也不等于作者已定。\n")
        fun field(label: String, value: String) { if (value.isNotBlank()) append(label).append("：\n").append(value.trim()).append('\n') }
        field("本章事实、关键选择与结果（同时核时地、在场与知情、资源权限及代价）", card.facts)
        field("承接边界与章末去向", card.boundary)
        field("视角人物", card.pov)
        field("时间与倒计时", card.countdown)
        field("章末钩子", card.hook)
        field("本章推进的线", card.threads)
        if (previous.isNotBlank()) field("用户已定前章结尾（正文事实）", previous)
        if (context.isNotBlank()) field("按本章挑选的资料（其中规划只用于约束，不冒充已发生）", context)
        if (warnings.isNotEmpty()) field("资料提示", warnings.joinToString("\n") { "- $it" })
        if (blocking.isNotEmpty()) field("必须补齐的硬资料", blocking.joinToString("\n") { "- $it" })
        append("普通观察、衔接、对白与生活细节由作者组织；不按栏目逐项表演，不为固定场次数、快慢配额、入口轮换或微伏笔改写既定章界。\n")
    }.trim()
}

/**
 * Select the current stage before constructing either the prompt prefix or read_doc/search tools.
 * Compilations with explicit “来源文件” boundaries are expanded so embedded stage templates cannot
 * leak through as an apparently ordinary reference document. Independent copies win exact duplicates.
 */
object ProjectSources {
    private val sourceMark = Regex("^来源文件[：:]\\s*(.+?)\\s*$")
    private val collectionTitle = Regex("^【\\d{1,3}】.+$")
    private val decoration = Regex("^[═=─━—_-]{4,}$")
    private val archived = Regex("(?:^|[（(_/\\s-])(?:历史版本|旧版|归档|废弃)(?=$|[）)\\s_.-]|\\d|项目包|总纲|细纲|稿|备份)")
    private val reference = Regex("验证样章|参考样章|未定样章|试写样章|阅读边界|学习笔记|写作学习|维护记录")

    fun phaseOf(title: String): WritingPhase? {
        if (!Regex("提示词|(?i:prompt)").containsMatchIn(title)) return null
        return when {
            Regex("审读|审稿|审核|复核|(?i:review)").containsMatchIn(title) -> WritingPhase.REVIEW
            Regex("修订|修稿|改稿|(?i:revis)").containsMatchIn(title) -> WritingPhase.REVISE
            Regex("正文|创作|写章|写作|(?i:draft|creat)").containsMatchIn(title) -> WritingPhase.CREATE
            else -> null
        }
    }

    private fun name(path: String) = path.substringAfterLast('/').substringBeforeLast('.').trim()
    private fun normalized(text: String) = text.removePrefix("\uFEFF").replace(Regex("\\s|　"), "")

    private fun pieces(doc: PackDoc, text: String): List<Pair<PackDoc, String>> {
        val lines = text.removePrefix("\uFEFF").lines()
        val markers = lines.mapIndexedNotNull { i, line -> sourceMark.matchEntire(line.trim())?.let { i to it.groupValues[1] } }
        if (markers.size < 2) return listOf(doc to text)
        // An explicit source line must be part of an actual compilation, not two prose quotations.
        if (!doc.title.contains("合订本") && markers.none { (i, _) -> i > 0 && collectionTitle.matches(lines[i - 1].trim()) }) return listOf(doc to text)
        fun start(i: Int): Int {
            var at = i
            if (at > 0 && collectionTitle.matches(lines[at - 1].trim())) at--
            if (at > 0 && decoration.matches(lines[at - 1].trim())) at--
            return at
        }
        return markers.mapIndexedNotNull { index, (line, path) ->
            val end = markers.getOrNull(index + 1)?.first?.let(::start) ?: lines.size
            val body = lines.subList(line + 1, end).dropWhile { it.isBlank() || decoration.matches(it.trim()) }
                .dropLastWhile { it.isBlank() || decoration.matches(it.trim()) }.joinToString("\n").trim()
            if (body.isBlank()) return@mapIndexedNotNull null
            val classified = PackImport.classify(path, body)
            val label = name(path)
            val kind = if (path.startsWith("参考/") || reference.containsMatchIn(label)) PackKind.REFERENCE else classified.kind
            doc.copy(id = "${doc.id}::$path", title = label, kind = kind, priority = kind.priority, always = kind.always, words = body.length) to body
        }
    }

    fun forPhase(docs: List<Pair<PackDoc, String>>, phase: WritingPhase): List<Pair<PackDoc, String>> {
        // Keep independent sources before their compilation copies regardless of import order.
        val ordered = docs.sortedWith(compareBy<Pair<PackDoc, String>>(
            { (_, text) -> text.lineSequence().filter { sourceMark.matches(it.trim()) }.take(2).count() >= 2 },
            { it.first.priority }, { it.first.title },
        ))
        val expanded = ordered.flatMap { (d, t) -> pieces(d, t) }
        val independent = expanded.filter { "::" !in it.first.id }.map { it.first.kind.name + "|" + normalized(it.first.title) }.toSet()
        val seen = HashSet<String>()
        return expanded.mapNotNull { (d, text) ->
            if (archived.containsMatchIn(d.title)) return@mapNotNull null
            // The independently supplied current outline is authoritative; a stale embedded copy
            // must not append a second or contradictory event. sourceWarnings reports the mismatch.
            if ("::" in d.id && d.kind.name + "|" + normalized(d.title) in independent) return@mapNotNull null
            val template = phaseOf(d.title)
            if (template != null && template != phase) return@mapNotNull null
            val isReference = reference.containsMatchIn(d.title) || d.kind == PackKind.TECHNIQUE || d.kind == PackKind.REFERENCE
            val body = text.trim()
            // Identical material imported under different filenames is still one source of facts.
            val key = d.kind.name + "|" + normalized(body)
            if (!seen.add(key)) return@mapNotNull null
            val material = if (isReference && reference.containsMatchIn(d.title)) d.copy(kind = PackKind.REFERENCE, always = false) else d
            val qualifier = "【资料性质：方法或背景参考，不是用户已定正文；不得把其中样章与规划当作已发生事实】"
            material to if (isReference && !body.startsWith(qualifier)) "$qualifier\n$body" else body
        }
    }

    /** Same logical independent/bundled source with different content needs a version check. */
    fun sourceWarnings(docs: List<Pair<PackDoc, String>>): List<String> =
        docs.flatMap { (d, t) -> pieces(d, t) }.filterNot { archived.containsMatchIn(it.first.title) }
            .groupBy { it.first.kind.name + "|" + normalized(it.first.title) }.values.mapNotNull { copies ->
                if (copies.none { "::" in it.first.id } || copies.none { "::" !in it.first.id } || copies.map { normalized(it.second) }.distinct().size < 2) null
                else "《${copies.first().first.title}》的独立文件与合订本内嵌内容不同，本次只取独立文件，版本差异待核；不把两份事实叠加。"
            }

    /** A review must be invalidated when any active stage template or rule changes. */
    fun fingerprintSources(docs: List<Pair<PackDoc, String>>): List<Pair<PackDoc, String>> =
        listOf(WritingPhase.CREATE, WritingPhase.REVIEW, WritingPhase.REVISE).flatMap { forPhase(docs, it) }
            .distinctBy { it.first.id to it.second }

    fun hasProject(docs: List<Pair<PackDoc, String>>): Boolean =
        docs.any { (d, t) -> phaseOf(d.title) != null || d.kind == PackKind.VOLUME ||
            ((d.title.contains("合订本") || t.contains("来源文件：")) && (t.contains("正文创作提示词") || t.contains("章节审读提示词"))) }
}

/** Compile from current data every time; never persist a candidate as accepted story state. */
fun WorkStore.writingTask(workId: String, chapterId: String, instruction: String = "", lengthOverride: String = ""): ChapterWritingTask? {
    val work = get(workId) ?: return null
    val chapter = work.chapter(chapterId) ?: return null
    val number = chapterNo(work, chapterId)
    val catalogVolume = work.volumeNumber(chapterId)
    val rawDocs = pack(workId).map { it to packText(workId, it.id) }
    val docs = ProjectSources.forPhase(rawDocs, WritingPhase.CREATE)
    val entries = outlineEntries(workId)
    val found = chapterOutlineEntries(work, chapterId, entries)
    val volume = found.firstNotNullOfOrNull { it.volume } ?: catalogVolume
    val planned = plannedCard(workId, chapterId, entries)
    val card = planned.card
    val writingAssist = assist(workId)
    val project = ProjectSources.hasProject(rawDocs)
    val blocking = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    warnings += ProjectSources.sourceWarnings(rawDocs)
    if (volume != catalogVolume) warnings += "作品目录与细纲的卷次不同，本次按目标细纲定位第 $volume 卷；请核对卷章归属。"
    if (project && card.facts.isBlank()) blocking += "本章尚无明确的事实与结果，请补齐细纲卡或导入现行目标章细纲。"
    val manual = this.card(workId, chapterId).let { !it.isEmpty && it.auto == 0 }
    if (project && found.isNotEmpty() && found.none { it.single } && !manual) {
        blocking += "目前只有第 ${found.first().from}—${found.first().to} 章章组，请先在细纲卡划定本章的起止与结果。"
    }
    if (planned.outline != null && planned.source == null) warnings += "细纲文档与手改细纲卡不同，本次按作者手改卡执行；如属硬事实冲突，请先核清。"
    val index = work.chapters.indexOfFirst { it.id == chapterId }
    val prior = work.chapters.getOrNull(index - 1)
    val previous = prior?.takeIf { it.status == ChapterStatus.FINAL }?.let { text(workId, it.id).takeLast(6_000) }.orEmpty()
    val previousLabel = when {
        prior == null && (number ?: 1) <= 1 -> "开篇，没有已定前章；以本章细纲为准"
        prior == null -> "以细纲结果承接；目录中没有可核验的前章正文"
        prior.status == ChapterStatus.FINAL && previous.isNotBlank() -> "用户定稿《${prior.title}》"
        else -> "以细纲结果承接；紧邻前章尚无可核验的用户定稿正文"
    }
    if (prior != null && previous.isBlank()) warnings += "前章草稿、未审样章和流程通过记录均不自动视为用户已定前文。"
    val outlineLength = if (manual) ProjectLengths.chapter(card.facts) ?: found.firstNotNullOfOrNull { ProjectLengths.chapter(it.text) }
        else found.firstNotNullOfOrNull { ProjectLengths.chapter(it.text) } ?: ProjectLengths.chapter(card.facts)
    val lengthDocs = docs.filter { (d, text) ->
        d.kind in setOf(PackKind.INSTRUCTION, PackKind.STYLE, PackKind.VOICE, PackKind.OUTLINE) ||
            (d.kind == PackKind.VOLUME && (ProjectLengths.volumeOf(d.title + "\n" + text.take(300))?.let { it == volume } != false))
    }.map { (d, text) -> d to if (d.kind == PackKind.VOLUME) ProjectLengths.header(text) else text }
    val length = lengthOverride.trim().takeIf { it.isNotEmpty() && it !in setOf("依细纲", "依本章细纲") }
        ?: ProjectLengths.find(instruction)
        ?: outlineLength
        ?: number?.let { n -> ProjectLengths.scoped(lengthDocs.map { it.second }, n) }
        ?: ProjectLengths.general(lengthDocs.sortedBy { if (it.first.kind == PackKind.VOLUME) 0 else 1 }.map { it.second })
        ?: docs.filter { it.first.kind == PackKind.RULES }.asSequence().flatMap { CheckRules.parse(it.second).rules.asSequence() }
            .filterIsInstance<CheckRule.Length>().firstOrNull()?.let(ProjectLengths::of)
        ?: "依本章细纲；未规定时写足关键选择与后果，不为统一字数注水"
    val confirmedSummaries = work.chapters.take(index).filter { it.status == ChapterStatus.FINAL }.mapNotNull { c ->
        val summary = this.card(workId, c.id).summary
        if (summary.isBlank()) null else chapterNo(work, c.id)?.let { ChapterSummary(it, c.title, summary) }
    }
    val currentLedger = ledger(workId)
    val context = ChapterBriefs.build(
        docs.filter { it.first.kind != PackKind.INSTRUCTION && ProjectSources.phaseOf(it.first.title) == null },
        text = "", card = listOf(card.facts, card.boundary, card.pov, card.hook, card.threads).joinToString("\n"),
        chapter = number ?: index + 1, volume = volume, limit = writingAssist.briefLimit,
        summaries = confirmedSummaries, style = true, ledger = currentLedger,
    ).render().orEmpty()
    val sources = (found.map { it.doc } + docs.filter { it.first.kind != PackKind.REFERENCE && it.first.kind != PackKind.TECHNIQUE }.map { it.first.title }).distinct()
    val (sampleSources, sampleFallback) = StyleSamples.sources(work.chapters, index, writingAssist.sampleFallback)
    val sampleSignature = StyleSamples.signature(sampleSources, sampleFallback, writingAssist.sampleOptions)
    val state = listOf(work.title, chapter.title, number.toString(), volume.toString(), instruction.trim(), length, card.facts, card.boundary, card.pov, card.countdown, card.hook, card.threads, previous, context) +
        warnings + blocking + listOf(
            // 润色的采纳、不采纳记录只是文风范例，与审读依据无关：不进指纹，免得改别章时让所有章的审读失效。
            StoryLedger.toJson(currentLedger).toString(), writingAssist.style, sampleSignature,
            writingAssist.sampleLimit.toString(), writingAssist.sampleFallback.toString(),
            writingAssist.sampleSkip.sorted().toString(), writingAssist.sampleShift.toSortedMap().toString(),
        ) +
        work.chapters.take(index).filter { it.status == ChapterStatus.FINAL }.flatMap { c -> listOf(c.id, c.title, c.words.toString(), c.updatedAt.toString(), this.card(workId, c.id).summary) } +
        ProjectSources.fingerprintSources(rawDocs).flatMap { listOf(it.first.title, it.first.kind.name, it.second) }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(state.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    return ChapterWritingTask(chapter.title, number, volume, instruction.trim(), length, previous, previousLabel, card, sources, warnings, blocking, project, fingerprint, context)
}

/** Read a scoped length, never treat a book-wide opening exception as the length for every chapter. */
internal object ProjectLengths {
    private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万]+"
    private val range = Regex("(?:约|大约|目标(?:篇幅|字数)?[：:]?\\s*)?($NUM)\\s*[—－–\\-~～至到]\\s*($NUM)\\s*(?:个)?(汉字|字)")
    private val atLeast = Regex("(?:不少于|至少|不低于)\\s*($NUM)\\s*(?:个)?(汉字|字)")
    private val atMost = Regex("(?:不超过|至多|最多)\\s*($NUM)\\s*(?:个)?(汉字|字)")
    private val minimumSuffix = Regex("($NUM)\\s*(汉字|字)\\s*(?:起步|以上|起)")
    private val approximate = Regex("(?:约|大约)\\s*($NUM)\\s*(汉字|字)|($NUM)\\s*(汉字|字)(?:左右|上下)")
    private val labelled = Regex("(?:篇幅|字数|目标字数)[：:]?\\s*($NUM)(?:\\s*[—－–\\-~～至到]\\s*($NUM))?\\s*(汉字|字)?")
    private val chapterRef = Regex("第[^章第\\n]{1,24}?章")
    private val actualChapterStart = Regex("^\\s*(?:#{1,6}\\s+|[-*+]\\s+(?:\\*\\*)?)(?:第${NUM}章(?:\\s|《|[（(]|$)|楔子)")
    private fun number(raw: String) = CheckRules.cn(raw.replace('〇', '零'))?.takeIf { it in 1..1_000_000 }
    private val chapterCue = Regex("(?:(?:本章|全章|整章|章节|全文|正文)?(?:目标)?(?:篇幅|字数|汉字数)|本章|全章|整章|全文|正文)")
    private val localScope = Regex("对白|对话|段落|每段|单段|一小段|片段|摘要|示例|范例|样例|样章|摘录|局部|增加|补写|补充|删减|单场|每场")
    private val lengthLead = Regex("^[\\s：:*_]*(?:(?:先按|按现行细纲|控制在|保持在|规定为|目标为|定为|应为|要求|仍|为|以|按|可以|可|允许|须|需|应|要|写到|写至|写足|达到)\\s*)*")
    private val lengthValue = Regex("(?:(?:约|大约|不少于|至少|不低于|不超过|至多|最多)\\s*)?$NUM\\s*(?:[—－–\\-~～至到]\\s*$NUM\\s*)?(?:个)?(?:汉字|字)(?:\\s*(?:起步|以上|起|左右|上下))?")
    private val unitlessValue = Regex("($NUM)(?:\\s*[—－–\\-~～至到]\\s*($NUM))?(?=$|[\\s，,；;。)）】])")

    /** A length must follow its scope directly; never scan across prose to an unrelated number. */
    private fun leadingLength(text: String, fieldUnit: String? = null, flexible: Boolean = false): String? {
        val value = text.replace("**", "").replace("__", "").replaceFirst(lengthLead, "")
        val amount = lengthValue.find(value)?.takeIf { it.range.first == 0 }?.value
        if (amount != null) return find(amount + if (flexible) "（弹性）" else "") ?: labelled.find("字数：$amount")?.let { m ->
            number(m.groupValues[1])?.let { "$it${m.groupValues[3]}" }
        }
        // Only an explicit word-count field supplies a missing unit, including Chinese numerals.
        if (fieldUnit == null) return null
        val field = unitlessValue.find(value)?.takeIf { it.range.first == 0 } ?: return null
        val lo = number(field.groupValues[1]) ?: return null
        val hi = field.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number)
        if (hi != null && hi < lo) return null
        return lo.toString() + (hi?.let { "—$it" } ?: "") + fieldUnit
    }

    /** A paragraph example's length is not the chapter's target, nor is an editorial increment. */
    fun chapter(text: String): String? = text.split(Regex("[。；;\\n]")).firstNotNullOfOrNull { line ->
        chapterCue.findAll(line).firstNotNullOfOrNull { cue ->
            val begin = maxOf(line.lastIndexOf('，', cue.range.first), line.lastIndexOf(',', cue.range.first)) + 1
            val prefix = line.substring(begin, cue.range.first)
            if (localScope.containsMatchIn(prefix)) null
            else leadingLength(line.substring(cue.range.last + 1), fieldUnit = when {
                "汉字数" in cue.value -> "汉字"
                "字数" in cue.value -> "字"
                else -> null
            }, flexible = "弹性" in line)
        }
    }

    /** Project-wide defaults apply only after chapter-specific ranges and exceptions. */
    fun general(texts: List<String>): String? {
        val scope = Regex("每章|单章|常规章(?:节)?|其余章(?:节)?|其他章(?:节)?")
        for (text in texts) for (line in text.split(Regex("[。；;\\n]"))) {
            for (match in scope.findAll(line)) {
                val before = line.substring(0, match.range.first).takeLast(8)
                if (Regex("(?:不必|无需|不要|不得|不是|并非|不能|不以)\\s*$").containsMatchIn(before)) continue
                val after = line.substring(match.range.last + 1)
                leadingLength(after, flexible = "弹性" in line)?.let { return it }
            }
        }
        return null
    }

    /** Only the volume's introductory clauses may supply general chapter-range requirements. */
    fun header(text: String): String = text.lineSequence().takeWhile { !actualChapterStart.containsMatchIn(it) }.joinToString("\n")
    fun volumeOf(text: String): Int? = Regex("第($NUM)卷").find(text)?.groupValues?.get(1)?.let(::number)

    /** Narrower exceptions win, and each range receives only the length in its own clause. */
    fun scoped(texts: List<String>, chapter: Int): String? {
        data class Candidate(val size: Int, val source: Int, val order: Int, val length: String)
        val candidates = mutableListOf<Candidate>()
        texts.forEachIndexed { source, text ->
            var order = 0
            text.split(Regex("[。；;！？\\n]")).forEach { sentence ->
                val refs = chapterRef.findAll(sentence).toList()
                val lengths = refs.mapIndexed { i, ref -> scopedLength(sentence.substring(ref.range.last + 1, refs.getOrNull(i + 1)?.range?.first ?: sentence.length)) }
                // Several enumerated chapter ranges may share a trailing “允许…字” clause.
                val shared = lengths.lastOrNull()?.takeIf { lengths.dropLast(1).all { it == null } }
                refs.forEachIndexed { i, ref ->
                    val numbers = ChapterRefs.find(ref.value)
                    val length = lengths[i] ?: shared
                    if (chapter in numbers && length != null) candidates += Candidate(numbers.size, source, order, length)
                    order++
                }
            }
        }
        return candidates.minWithOrNull(compareBy<Candidate>({ it.size }, { it.source }, { -it.order }))?.length
    }

    private fun scopedLength(text: String): String? {
        leadingLength(text, flexible = "弹性" in text)?.let { return it }
        // Enumerated chapter exceptions may explain their scenes before an explicit allowance.
        // A bare “，约十五万字” in a volume heading is an aggregate budget, not a chapter target.
        return text.split(Regex("[，,]")).firstNotNullOfOrNull { clause ->
            chapter(clause) ?: general(listOf(clause)) ?: clause.trim().takeIf {
                Regex("^(?:允许|可以|可按|可写|先按|仍)").containsMatchIn(it)
            }?.let { leadingLength(it, flexible = "弹性" in text) }
        }
    }

    fun find(text: String): String? {
        range.find(text)?.let {
            val flexible = it.value.trimStart().startsWith("约") || it.value.trimStart().startsWith("大约") || "弹性" in text
            val lo = number(it.groupValues[1]); val hi = number(it.groupValues[2])
            if (lo != null && hi != null && hi >= lo) return (if (flexible) "约 " else "") + "$lo—$hi${it.groupValues[3]}"
        }
        atLeast.find(text)?.let { m -> number(m.groupValues[1])?.let { return "不少于 $it ${m.groupValues[2]}" } }
        atMost.find(text)?.let { m -> number(m.groupValues[1])?.let { return "不超过 $it ${m.groupValues[2]}" } }
        minimumSuffix.find(text)?.let { m -> number(m.groupValues[1])?.let { return "不少于 $it ${m.groupValues[2]}" } }
        approximate.find(text)?.let { m ->
            val value = m.groupValues[1].ifBlank { m.groupValues[3] }
            val unit = m.groupValues[2].ifBlank { m.groupValues[4] }
            number(value)?.let { return "约 $it$unit" }
        }
        labelled.find(text)?.let { m -> number(m.groupValues[1])?.let { lo ->
            val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number)
            return lo.toString() + (if (hi == null) "" else "—$hi") + m.groupValues[3].ifEmpty { "字" }
        } }
        return null
    }
    fun of(rule: CheckRule.Length): String = buildString {
        when {
            rule.min != null && rule.max != null -> append(rule.min).append("—").append(rule.max)
            rule.min != null -> append("不少于 ").append(rule.min)
            rule.max != null -> append("不超过 ").append(rule.max)
        }
        append(if (rule.han) " 汉字" else " 字")
    }.trim()
}
