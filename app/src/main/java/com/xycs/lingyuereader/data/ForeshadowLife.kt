package com.xycs.lingyuereader.data

/**
 * 伏笔走到哪一步：埋下（还没推进过）、推进中、暂缓（作者说先放着）、已收。
 * 埋下、推进中、已收按账本推断（[Foreshadows.state]），只有“暂缓”要作者设：推断不出“先放着”。
 */
enum class PromiseState(val label: String) { PLANTED("埋下"), ADVANCING("推进中"), PAUSED("暂缓"), CLOSED("已收") }

/** 拟细纲时带上的一条旧伏笔：可以推进或回收，不强制。[overdue] 为真是计划收的章已经过了，否则是停滞了 [idle] 章。 */
data class StaleForeshadow(val entry: LedgerEntry, val overdue: Boolean, val idle: Int, val last: Int?)

/**
 * 伏笔的生命周期：区分“提了一句”和“推进了”。最近一次推进（[LedgerEntry.lastAdvanced]）只认两种依据：
 * 细纲卡伏笔动作里写了“推进 / 回收”的、整理入账时 AI 标了“推进”并附上原句的。正文里再提一句、人物想起来一下都不算，
 * 不然挂了一百章的伏笔看起来一直“在动”，停滞的永远查不出来。
 */
object Foreshadows {
    /** 隔多少章没推进算停滞：和线索体检的伏笔“冷了”同一个数（长篇伏笔埋二三十章很正常，取得宽）。 */
    const val STALE = StoryPulse.COLD_FORESHADOW

    /** 拟细纲时最多带几条旧伏笔：多了 AI 每章都想收一点，反而乱。 */
    const val PLAN_MAX = 3

    /**
     * 这条伏笔现在的状态：收了是已收；作者设了暂缓（还没收）是暂缓；推进过是推进中，否则是埋下。
     * [advanced] 是另外算的最近推进章（比如细纲卡里的伏笔动作，见 [cardAdvances]），和账本里记的取晚的。
     */
    fun state(e: LedgerEntry, advanced: Int? = null): PromiseState = when {
        e.done -> PromiseState.CLOSED
        e.state == PromiseState.PAUSED -> PromiseState.PAUSED
        lastAdvanced(e, advanced) != null -> PromiseState.ADVANCING
        else -> PromiseState.PLANTED
    }

    /** 账本里记的和另外算的（[advanced]）取晚的一章。 */
    fun lastAdvanced(e: LedgerEntry, advanced: Int? = null): Int? = listOfNotNull(e.lastAdvanced, advanced).maxOrNull()

    /**
     * 作者在账本里手动改状态：改成已收是收了（收在哪章不知道就空着）；从已收改回别的是重新打开；暂缓记下来；
     * 埋下、推进中只是清掉作者设的暂缓（推进中要有推进的章，没有就记成 [chapter] 这一章，[chapter] 也空着就只清暂缓）。
     */
    fun withState(e: LedgerEntry, to: PromiseState, chapter: Int? = null): LedgerEntry = when (to) {
        PromiseState.CLOSED -> e.copy(done = true, state = null)
        PromiseState.PAUSED -> e.copy(done = false, closed = null, state = PromiseState.PAUSED)
        PromiseState.ADVANCING -> e.copy(done = false, closed = null, state = null, lastAdvanced = e.lastAdvanced ?: chapter)
        PromiseState.PLANTED -> e.copy(done = false, closed = null, state = null, lastAdvanced = null)
    }

    /**
     * 细纲卡里的伏笔动作：账本 id → 在第几章“推进 / 回收”过（取最晚的）。[cards] 是（章号，这一章的伏笔动作），
     * 只给已经写了正文的章（细纲卡写了推进、正文还没写，不算推进），还要在 [before] 这一章之前（防剧透）。
     * “埋”不算推进（埋下是 [LedgerEntry.from]），“暂缓”也不算。
     */
    fun cardAdvances(cards: List<Pair<Int, List<ForeshadowMove>>>, before: Int = Int.MAX_VALUE): Map<String, Int> {
        val out = HashMap<String, Int>()
        cards.filter { it.first in 1 until before }.forEach { (no, moves) ->
            moves.filter { it.ledgerId.isNotEmpty() && (it.action == MoveAction.ADVANCE || it.action == MoveAction.PAYOFF) }
                .forEach { m -> out[m.ledgerId] = maxOf(out[m.ledgerId] ?: 0, no) }
        }
        return out
    }

    /**
     * 写第 [chapter] 章（拟细纲）时可以推进或回收的旧伏笔，最多 [limit] 条：先挑超期的（计划收的章过了，最早该收的在前），
     * 再挑停滞的（离最近一次推进、没推进过就离埋下，超过 [STALE] 章，隔得最久的在前）。暂缓的、收了的、只有作者知道的不挑。
     * 防剧透：只看这一章之前埋下的，推进只算这一章之前的（[advances] 由调用方按 [cardAdvances] 的 `before` 算好）。
     */
    fun stale(entries: List<LedgerEntry>, chapter: Int, advances: Map<String, Int> = emptyMap(), volume: Int? = null, limit: Int = PLAN_MAX): List<StaleForeshadow> {
        val open = entries.filter { e ->
            e.kind == LedgerKind.PROMISE && !e.secret && e.state != PromiseState.PAUSED && e.openAt(chapter - 1, volume) &&
                (e.from == null || e.from < chapter)
        }
        fun last(e: LedgerEntry) = listOfNotNull(e.lastAdvanced?.takeIf { it < chapter }, advances[e.id]).maxOrNull()
        val overdue = open.filter { e -> e.payoff != null && e.payoff < chapter }.sortedBy { it.payoff }
            .map { e -> StaleForeshadow(e, true, chapter - (last(e) ?: e.from ?: 0), last(e)) }
        val stalled = (open - overdue.map { it.entry }.toSet()).mapNotNull { e ->
            val since = last(e) ?: e.from ?: return@mapNotNull null
            val idle = chapter - since
            if (idle > STALE) StaleForeshadow(e, false, idle, last(e)) else null
        }.sortedByDescending { it.idle }
        return (overdue + stalled).take(limit)
    }

    /** 拟细纲请求里的一行：“灯会旧案的账册（第 3 章埋；计划第 20 章收，已经过了）”。不说后面的事。 */
    fun planLine(s: StaleForeshadow): String = buildString {
        append(s.entry.text.trim().trimEnd('。'))
        val parts = mutableListOf<String>()
        s.entry.from?.let { parts += "第 $it 章埋" }
        s.last?.let { parts += "最近推进在第 $it 章" }
        if (s.overdue) parts += "计划第 ${s.entry.payoff} 章收，已经过了" else parts += "已经 ${s.idle} 章没推进"
        append("（").append(parts.joinToString("；")).append("）")
    }
}

/**
 * 定稿时把这一章细纲卡里写了“推进”的伏笔动作（对得上账本 id 的）记成账本里的最近推进（[LedgerOp.Advance]）：
 * 细纲卡写了推进、正文也写了才算，所以等定稿再记，账本“伏笔”里的“最近推进”和线索体检、拟细纲看到的一致。
 * 没有这样的动作不写账本（不多存一个历史版本）。返回记了几条。
 */
fun WorkStore.advanceFromCard(workId: String, chapterId: String): Int {
    val work = get(workId) ?: return 0
    if (text(workId, chapterId).isBlank()) return 0
    val card = card(workId, chapterId)
    val number = work.chapterNo(chapterId, card) ?: return 0
    val ids = card.moves.filter { it.action == MoveAction.ADVANCE && it.ledgerId.isNotEmpty() }.map { it.ledgerId }.distinct()
    if (ids.isEmpty()) return 0
    val ledger = ledger(workId)
    val todo = ids.filter { id -> ledger.any { it.id == id && it.kind == LedgerKind.PROMISE && !it.done && (it.lastAdvanced ?: Int.MIN_VALUE) < number } }
    if (todo.isEmpty()) return 0
    applyLedger(workId, todo.map { LedgerOp.Advance(it, number) }, "定稿第 $number 章：记下细纲卡里的伏笔推进")
    return todo.size
}
