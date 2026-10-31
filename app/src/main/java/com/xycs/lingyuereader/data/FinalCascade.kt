package com.xycs.lingyuereader.data

/**
 * 级联核对的结果：这一章产生的账本条目里原句找不到了的（[recheck]），后面各章可能受影响的条目（[affected]：后面那一条 → 它牵扯的这一章的那一条）。
 */
data class CascadeCheck(val recheck: List<LedgerEntry>, val affected: List<Pair<LedgerEntry, LedgerEntry>>)

/** 做完以后给作者看的一句：标了几条待复核、哪些标了过期、挂了几条待同步。 */
data class CascadeReport(
    val recheck: Int = 0,
    val affected: Int = 0,
    /** 原句找不到了、却没能在账本里标上待复核的条数（账本写不进去）。 */
    val recheckFailed: Int = 0,
    val summary: Boolean = false,
    val story: Boolean = false,
    val volume: Boolean = false,
) {
    val isEmpty: Boolean get() = recheck == 0 && recheckFailed == 0 && affected == 0 && !summary && !story && !volume

    fun message(title: String): String = buildList {
        if (recheck > 0) add("$recheck 条账本依据找不到了，标了待复核")
        if (recheckFailed > 0) add("$recheckFailed 条账本依据找不到了，但账本没写进去、没标上待复核，去账本里核一下")
        val stale = listOfNotNull("摘要".takeIf { summary }, "故事至今".takeIf { story }, "卷摘要".takeIf { volume })
        if (stale.isNotEmpty()) add(stale.joinToString("、") + "标了过期，下次收尾时重新整理")
        if (affected > 0) add("后面 $affected 条账本条目可能受影响，挂在变更登记的待同步里")
    }.let { if (it.isEmpty()) "《$title》改过了，账本、摘要都对得上" else "《$title》改过了：" + it.joinToString("；") }
}

/**
 * 定稿章解锁改完、重新定稿时的级联核对，全部在本机做，不发请求：
 * 1. 这一章产生的账本条目（[LedgerEntry.chapterId] 是这一章的），用原句逐条在新正文里找，找不到的标“待复核”（不删、不改内容）；
 * 2. 这一章的摘要、所在卷的卷摘要、写到这一章或更后面的故事至今标过期，下次收尾时重新整理（交接卡按正文指纹本来就会判过期）；
 * 3. 后面各章里牵扯到这一章那些条目的（同一人同一方面的状态、数值，同一个人知道的相近的事，相近的事件和伏笔，这一章埋下、后面推进或收了的伏笔），
 *    挂到变更登记的“待同步”下面，让作者逐条看。
 */
object FinalCascade {
    /** 原句被改了一两个字还算在：两个字一组的重合占短的一方这么多就算（见 [ForeshadowMoves.similarity]）。 */
    const val KEEP = 0.8

    /** 后面的条目和这一章的条目内容这么像，算牵扯到（事件、伏笔、知情）。 */
    const val RELATED = 0.6

    /** 变更登记里最多挂几条：再多也看不过来，多的合成一条说还有几条。 */
    const val MAX_CHANGES = 10

    /**
     * 原句还在不在正文里：整段原样在（规范化后），或者按句子核对：原句的每一句都在正文里，或者和正文里某一句足够像（改了一两个字、换了标点）。
     * 像不像只按原句这一句的字算（两个字一组，原句这句的有多少留在正文那句里）：正文里一个短句恰好是原句的一部分，不算原句还在。
     */
    fun quoteKept(text: String, quote: String): Boolean {
        val q = ChapterSyncs.normalize(quote).trim('"', '\'')
        if (q.isEmpty()) return true
        if (q in ChapterSyncs.normalize(text)) return true
        val t = bare(text)
        val lines = sentences(text).map(::bare).filter { it.length >= 2 }
        return sentences(quote).map(::bare).filter { it.length >= 2 }.all { qs ->
            qs in t || lines.any { coverage(qs, it) >= KEEP }
        }
    }

    private fun sentences(s: String) = s.split(Regex("(?<=[。！？!?…])|\\n"))

    /** 比句子用的写法：规范化，去掉标点和引号。 */
    private fun bare(s: String) = ChapterSyncs.normalize(s).replace(Regex("[\\p{P}\\p{S}]"), "")

    /** 原句的一句 [q] 有多少（两个字一组）留在正文的一句 [line] 里。 */
    private fun coverage(q: String, line: String): Double {
        val gq = q.windowed(2).toSet()
        return (gq intersect line.windowed(2).toSet()).size.toDouble() / gq.size
    }

    fun check(text: String, chapterId: String, chapterNo: Int, ledger: List<LedgerEntry>): CascadeCheck {
        val own = ledger.filter { it.chapterId == chapterId }
        val recheck = own.filter { it.quote.isNotBlank() && !it.recheck && !quoteKept(text, it.quote) }
        fun later(e: LedgerEntry) = (e.from ?: 0) > chapterNo
        fun related(o: LedgerEntry, e: LedgerEntry): Boolean = when (o.kind) {
            LedgerKind.STATE, LedgerKind.HOLD -> e.kind == o.kind && o.subject.isNotBlank() && o.aspect.isNotBlank() &&
                e.subject.trim() == o.subject.trim() && e.aspect.trim() == o.aspect.trim()
            LedgerKind.KNOW -> e.kind == LedgerKind.KNOW && e.subject.trim() == o.subject.trim() && ForeshadowMoves.similarity(e.text, o.text) >= RELATED
            LedgerKind.EVENT, LedgerKind.PROMISE -> e.kind != LedgerKind.TIME && ForeshadowMoves.similarity(e.text, o.text) >= RELATED
            LedgerKind.TIME -> false
        }
        val affected = LinkedHashMap<String, Pair<LedgerEntry, LedgerEntry>>()
        own.forEach { o ->
            ledger.filter { e -> e.id != o.id && e.chapterId != chapterId && later(e) && related(o, e) }
                .forEach { e -> affected.putIfAbsent(e.id, e to o) }
            // 这一章埋下的伏笔，后面推进了、收了：那几章写的是按这一章的埋法接的。
            if (o.kind == LedgerKind.PROMISE && (o.lastAdvanced ?: 0) > chapterNo || o.kind == LedgerKind.PROMISE && (o.closed ?: 0) > chapterNo) {
                affected.putIfAbsent(o.id, o to o)
            }
        }
        // 收在后面章的伏笔：账本里那一条的 chapterId 是收的那一章，埋在这一章（from 是这一章）。
        ledger.filter { it.kind == LedgerKind.PROMISE && it.from == chapterNo && it.chapterId != chapterId && it.chapterId.isNotEmpty() }
            .forEach { affected.putIfAbsent(it.id, it to it) }
        return CascadeCheck(recheck, affected.values.sortedBy { it.first.from ?: 0 })
    }

    /** 变更登记里说一条可能受影响的：哪一章的哪一条，牵扯的是这一章的哪一条。 */
    fun reason(number: Int, title: String, later: LedgerEntry, own: LedgerEntry): String = buildString {
        append("第 ").append(number).append(" 章「").append(title).append("」定稿后又改过；")
        val at = later.from?.let { "第 $it 章" } ?: "后面"
        val what = (if (later.subject.isNotBlank()) later.subject + (if (later.aspect.isNotBlank()) "·" + later.aspect else "") + "：" else "") + later.text
        if (later.id == own.id) append("这一章埋下的伏笔「").append(own.text.take(40)).append("」在").append(at).append("推进或收了，看看还接得上吗")
        else append(at).append("的账本条目「").append(what.take(60)).append("」接的是这一章的「").append(own.text.take(40)).append("」，可能要跟着改")
    }
}

/**
 * 重新定稿的这一章（[chapterId]）要不要做级联核对：这一章最近一次登记是解锁，且解锁时记的正文指纹和现在的不一样（旧登记没记指纹的也做）。
 * [text] 是现在的正文（编辑器里还没存的也算）。
 */
fun WorkStore.refinalPending(workId: String, chapterId: String, text: String? = null): Boolean {
    val last = changes(workId).filter { it.chapterId == chapterId && (it.kind == ChangeKind.UNLOCK || it.kind == ChangeKind.FINALIZE) }
        .maxByOrNull { it.at } ?: return false
    if (last.kind != ChangeKind.UNLOCK) return false
    val now = ChapterSyncs.hash(text ?: text(workId, chapterId))
    return last.textHash == null || last.textHash != now
}

/**
 * 做级联核对（[FinalCascade]）：标待复核、标过期、挂待同步。账本一批改动、存一个历史版本；都在调用方的后台线程里做。
 * 哪一步写不进去不挡别的步。
 */
fun WorkStore.cascadeFinal(workId: String, chapterId: String): CascadeReport {
    val work = get(workId) ?: return CascadeReport()
    val chapter = work.chapter(chapterId) ?: return CascadeReport()
    val number = chapterNo(work, chapterId) ?: (work.chapters.indexOfFirst { it.id == chapterId } + 1)
    val text = text(workId, chapterId)
    val ledger = runCatching { ledger(workId) }.getOrDefault(emptyList())
    val found = FinalCascade.check(text, chapterId, number, ledger)
    var report = CascadeReport()
    // 真标上了的才算：账本在读和写之间被别处改过（[LedgerConflict]）就重读一遍再标一次；还是写不进去，照实报没标上。
    var marked = emptyList<LedgerEntry>()
    if (found.recheck.isNotEmpty()) {
        fun mark(list: List<LedgerEntry>) = list.also { applyLedger(workId, it.map { e -> LedgerOp.Update(e.copy(recheck = true), expected = e) }, "改定稿后核对第 $number 章") }
        runCatching { mark(found.recheck) }
            .recoverCatching { mark(FinalCascade.check(text, chapterId, number, ledger(workId)).recheck) }
            .onSuccess { marked = it; report = report.copy(recheck = it.size) }
            .onFailure { report = report.copy(recheckFailed = found.recheck.size) }
    }
    runCatching {
        val card = card(workId, chapterId)
        // 解锁以后摘要换过（收尾时写了新摘要）：新摘要按改后的正文写，不标过期。旧登记没记摘要指纹的照旧标。
        val unlock = changes(workId).filter { it.chapterId == chapterId && it.kind == ChangeKind.UNLOCK }.maxByOrNull { it.at }
        val rewritten = unlock?.summaryHash != null && unlock.summaryHash != ChapterSyncs.hash(card.summary)
        if (card.summary.isNotBlank() && !card.summaryStale && !rewritten) {
            updateCard(workId, chapterId) { it.copy(summaryStale = true) }
            report = report.copy(summary = true)
        }
    }
    runCatching {
        val index = work.chapters.indexOfFirst { it.id == chapterId }
        val volumeId = work.volumes.firstOrNull { v -> v.chapters.any { it.id == chapterId } }?.id
        val d = digests(workId)
        val story = d.story?.let { s ->
            val at = work.chapters.indexOfFirst { it.id == s.throughId }
            !s.edited && !s.stale && (if (at >= 0) at >= index else s.throughNo >= number)
        } == true
        val volume = volumeId != null && d.volumes[volumeId]?.let { !it.edited && !it.stale } == true
        if (story || volume) {
            updateDigests(workId) { x ->
                x.copy(
                    story = if (story) x.story?.copy(stale = true) else x.story,
                    volumes = if (volume && volumeId != null) x.volumes + (volumeId to x.volumes.getValue(volumeId).copy(stale = true)) else x.volumes,
                )
            }
            report = report.copy(story = story, volume = volume)
        }
    }
    val now = System.currentTimeMillis()
    runCatching {
        if (marked.isNotEmpty()) addChange(workId, ChangeEntry(WorkStore.newId(), now, ChangeKind.CASCADE, chapterId, chapter.title,
            "第 $number 章定稿后又改过：这一章记进账本的 ${marked.size} 条，原句在正文里找不到了，已在账本里标“待复核”：" +
                marked.take(5).joinToString("；") { it.text.take(30) } + if (marked.size > 5) "……" else "",
            canon = true))
        else if (report.recheckFailed > 0) addChange(workId, ChangeEntry(WorkStore.newId(), now, ChangeKind.CASCADE, chapterId, chapter.title,
            "第 $number 章定稿后又改过：这一章记进账本的 ${found.recheck.size} 条，原句在正文里找不到了，但账本没写进去，没能标“待复核”，请到账本里逐条核对：" +
                found.recheck.take(5).joinToString("；") { it.text.take(30) } + if (found.recheck.size > 5) "……" else "",
            canon = true))
        found.affected.take(FinalCascade.MAX_CHANGES).forEach { (later, own) ->
            val title = work.chapter(later.chapterId)?.title ?: later.from?.let { "第 $it 章" }.orEmpty()
            addChange(workId, ChangeEntry(WorkStore.newId(), now, ChangeKind.CASCADE, later.chapterId.takeIf { work.chapter(it) != null }.orEmpty(), title,
                FinalCascade.reason(number, chapter.title, later, own), canon = true))
        }
        val rest = found.affected.size - FinalCascade.MAX_CHANGES
        if (rest > 0) addChange(workId, ChangeEntry(WorkStore.newId(), now, ChangeKind.CASCADE, chapterId, chapter.title,
            "第 $number 章定稿后又改过：后面还有 $rest 条账本条目可能受影响，没有一条条列出；可以在账本里按人物翻到后面的章看。", canon = true))
        report = report.copy(affected = found.affected.size)
    }
    return report
}
