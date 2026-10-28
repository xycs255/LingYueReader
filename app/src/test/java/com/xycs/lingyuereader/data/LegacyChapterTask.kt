package com.xycs.lingyuereader.data

import java.security.MessageDigest

/*
 * 改前（fec434e）的设定包筛选和本章任务编译，原样抄来只改了名字：测试拿它和现在带缓存的实现比，
 * 同一输入编出来的任务（连同指纹）必须一模一样。别改这里的算法。
 */
object LegacyProjectSources {
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

    fun pieces(doc: PackDoc, text: String): List<Pair<PackDoc, String>> {
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
fun WorkStore.legacyWritingTask(workId: String, chapterId: String, instruction: String = "", lengthOverride: String = ""): ChapterWritingTask? {
    val work = get(workId) ?: return null
    val chapter = work.chapter(chapterId) ?: return null
    val number = chapterNo(work, chapterId)
    val catalogVolume = work.volumeNumber(chapterId)
    val rawDocs = pack(workId).map { it to packText(workId, it.id) }
    val docs = LegacyProjectSources.forPhase(rawDocs, WritingPhase.CREATE)
    val entries = legacyOutlineEntries(workId)
    val found = chapterOutlineEntries(work, chapterId, entries)
    val volume = found.firstNotNullOfOrNull { it.volume } ?: catalogVolume
    val planned = plannedCard(workId, chapterId, entries)
    val card = planned.card
    val writingAssist = assist(workId)
    val project = LegacyProjectSources.hasProject(rawDocs)
    val blocking = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    warnings += LegacyProjectSources.sourceWarnings(rawDocs)
    if (volume != catalogVolume) warnings += "作品目录与细纲的卷次不同，本次按目标细纲定位第 $volume 卷；请核对卷章归属。"
    if (project && card.facts.isBlank()) blocking += "本章尚无明确的事实与结果，请补齐细纲卡或导入现行目标章细纲。"
    val manual = this.card(workId, chapterId).let { !it.outlineEmpty && it.auto == 0 }
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
        docs.filter { it.first.kind != PackKind.INSTRUCTION && LegacyProjectSources.phaseOf(it.first.title) == null },
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
        LegacyProjectSources.fingerprintSources(rawDocs).flatMap { listOf(it.first.title, it.first.kind.name, it.second) }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(state.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    return ChapterWritingTask(chapter.title, number, volume, instruction.trim(), length, previous, previousLabel, card, sources, warnings, blocking, project, fingerprint, context)
}

private val LEGACY_OUTLINE_KINDS = listOf(PackKind.VOLUME, PackKind.INVARIANT, PackKind.OUTLINE, PackKind.CANON)

/** 改前的 outlineEntries：每次整份读设定包、重筛一遍。 */
fun WorkStore.legacyOutlineEntries(workId: String): List<OutlineEntry> =
    LegacyProjectSources.forPhase(pack(workId).map { it to packText(workId, it.id) }, WritingPhase.CREATE)
        .filter { it.first.kind in LEGACY_OUTLINE_KINDS }.sortedBy { LEGACY_OUTLINE_KINDS.indexOf(it.first.kind) * 100 + it.first.priority }.flatMapIndexed { rank, (d, text) ->
        ChapterOutlines.parse(listOf(d.title to text)).map { it.copy(rank = rank) }
    }.distinctBy { listOf(it.from, it.to, it.title, it.volume, it.text.replace(Regex("\\s|　"), "")) }
