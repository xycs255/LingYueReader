package com.xycs.lingyuereader.data

/** 体检表里一行是什么：细纲卡里写的一条线、一条伏笔、一个主要人物。 */
enum class PulseKind(val label: String) { THREAD("线"), FORESHADOW("伏笔"), PERSON("人物") }

/** 一行的状况：在推进、冷了（很久没推进）、原地踏步（连着几章挂着名、却没写出变化）、暂缓（作者说了先放着）。 */
enum class PulseState(val label: String) { OK("在推进"), COLD("冷了"), STALLED("原地踏步"), DEFERRED("暂缓") }

/**
 * 体检表的一行。[lastNo]、[lastTitle] 是最近一次推进的章（没推进过为空），[idle] 是从那一章到现在写到的这一章隔了几章，
 * [note] 是给作者看的一句为什么这么判。
 */
data class PulseRow(
    val kind: PulseKind,
    val name: String,
    val state: PulseState,
    val lastNo: Int? = null,
    val lastTitle: String = "",
    val idle: Int = 0,
    val note: String = "",
) {
    /** 带进下一章的作者要求时写的那一句。 */
    fun request(): String = when (kind) {
        PulseKind.THREAD -> "推进「$name」这条线"
        PulseKind.FORESHADOW -> "推进伏笔「$name」"
        PulseKind.PERSON -> "让${name}出场，往前走一步"
    } + if (state == PulseState.STALLED) "，写出实在的变化（已经连着几章只是带了一笔）" else if (idle > 0) "（已经 $idle 章没动）" else ""
}

/** 体检要用的一章：按目录先后，只到现在写到的那一章（有正文的最后一章）为止。 */
data class PulseChapter(
    val id: String,
    val number: Int,
    val title: String,
    val threads: String = "",
    val change: String = "",
    val moves: List<ForeshadowMove> = emptyList(),
)

/**
 * 线索与节奏体检（本机算，不花钱）：用细纲卡的“推进的线”“本章改变了什么”“伏笔动作”和故事账本，
 * 算每条线、每条没收的伏笔、每个主要人物最近一次推进是在哪一章，标出“冷了”和“原地踏步”。只是提醒，不拦什么。
 *
 * 怎么算“推进”：
 * - 线：那一章的细纲卡把它写进了“推进的线”，或者“本章改变了什么”里写到了它；
 * - 伏笔：账本里它埋下的那一章、记下的最近一次实质推进（[LedgerEntry.lastAdvanced]，正文里只提一句不算），以及细纲卡伏笔动作里
 *   对它“埋 / 推进 / 回收”的章（“暂缓”不算推进，最近一次是暂缓的、账本里标了暂缓的标“暂缓”，不催）；
 * - 人物：账本里以他为主语、从那一章起记下的状态、事件、知情，或者“本章改变了什么”里写到了他。
 */
object StoryPulse {
    /**
     * 线隔多少章没推进算“冷了”：网文一条支线十来章不动，读者就忘了它在干什么；比伏笔短，伏笔本来就要埋得久。
     */
    const val COLD_THREAD = 10

    /** 伏笔隔多少章没动算“冷了”：长篇伏笔埋二三十章很正常，三十章还没任何动静再提（借 InkOS“停滞”的思路，取得宽）。 */
    const val COLD_FORESHADOW = 30

    /** 主要人物隔多少章没有新状态、新变化算“冷了”。 */
    const val COLD_PERSON = 15

    /**
     * 连着几章挂在“推进的线”里，而这几章的“本章改变了什么”都写了、却一句没写到它，算“原地踏步”：挂名不等于推进。
     * “本章改变了什么”空着的章不算数（没写不等于没变，旧章多半没有这一栏），宁可少报。
     */
    const val STALL_RUN = 3

    /** 主要人物取几个：账本里在最多章出现过的几位，至少在 [MIN_PERSON_CHAPTERS] 章里有记录。 */
    const val MAIN_PEOPLE = 8
    const val MIN_PERSON_CHAPTERS = 3

    /** 线名：“明线：林月查灯会旧案”取冒号前的“明线”，没有冒号取整条（太长的不算线名，是一句话）。 */
    internal fun threadNames(text: String): List<Pair<String, String>> =
        text.split(Regex("[、，,；;\\n/]")).map { it.trim().trimEnd('。') }.filter { it.isNotEmpty() && it != "无" }.mapNotNull { item ->
            val colon = item.indexOfFirst { it == '：' || it == ':' }
            val name = (if (colon in 1..12) item.substring(0, colon) else item).trim()
            if (name.length !in 2..16) null else name to (if (colon > 0) item.substring(colon + 1).trim() else "")
        }

    private fun mentions(text: String, name: String, detail: String = ""): Boolean =
        text.isNotBlank() && (name in text || (detail.length >= 4 && ForeshadowMoves.similarity(detail, text) >= 0.5))

    /**
     * 算体检表：[chapters] 按目录先后、到现在写到的这一章为止；[ledger] 是故事账本。冷了、原地踏步的排前面，隔得久的在前。
     */
    fun check(chapters: List<PulseChapter>, ledger: List<LedgerEntry>): List<PulseRow> {
        if (chapters.isEmpty()) return emptyList()
        val now = chapters.lastIndex
        val atNo = chapters.withIndex().associate { it.value.number to it.index }
        fun indexOf(no: Int?): Int? = no?.let { n -> atNo[n] ?: chapters.indexOfLast { it.number <= n }.takeIf { it >= 0 } }
        val rows = mutableListOf<PulseRow>()

        // 线：按线名归到一起。
        val threadDetail = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        chapters.forEachIndexed { i, c -> threadNames(c.threads).forEach { (n, d) -> threadDetail.getOrPut(n) { mutableListOf() } += i to d } }
        threadDetail.forEach { (name, listed) ->
            val details = listed.map { it.second }.filter { it.isNotEmpty() }
            val advanced = chapters.indices.filter { i -> listed.any { it.first == i } || mentions(chapters[i].change, name) }
            val last = advanced.lastOrNull()
            // 原地踏步：最近 STALL_RUN 章都挂着它，都写了“本章改变了什么”，却都没写到它。
            val tail = (now - STALL_RUN + 1..now).toList()
            val stalled = tail.first() >= 0 && tail.all { i -> listed.any { it.first == i } } && tail.all { i ->
                val ch = chapters[i].change
                ch.isNotBlank() && !mentions(ch, name) && details.none { d -> mentions(ch, d.take(12), d) }
            }
            val idle = last?.let { now - it } ?: (now + 1)
            val state = when {
                stalled -> PulseState.STALLED
                idle >= COLD_THREAD -> PulseState.COLD
                else -> PulseState.OK
            }
            val note = when (state) {
                PulseState.STALLED -> "最近 $STALL_RUN 章都挂在“推进的线”里，“本章改变了什么”却都没写到它"
                PulseState.COLD -> "已经 $idle 章没推进（超过 $COLD_THREAD 章算冷了）"
                else -> ""
            }
            rows += PulseRow(PulseKind.THREAD, name, state, last?.let { chapters[it].number }, last?.let { chapters[it].title }.orEmpty(), idle, note)
        }

        // 伏笔：账本里没收的，加细纲卡里自由文字埋下的。
        val currentNo = chapters[now].number
        val promises = ledger.filter { it.kind == LedgerKind.PROMISE && !it.secret && it.openAt(currentNo) }
        fun touches(id: String, text: String): List<Pair<Int, ForeshadowMove>> = chapters.indices.flatMap { i ->
            chapters[i].moves.filter { m -> (id.isNotEmpty() && m.ledgerId == id) || (m.ledgerId.isEmpty() && ForeshadowMoves.similarity(m.text, text) >= 0.6) }.map { i to it }
        }
        // 伏笔：埋下、账本里记的最近一次实质推进（[LedgerEntry.lastAdvanced]，只提一句不算）、细纲卡里的伏笔动作。
        // 作者在账本里设了“暂缓”的，和细纲卡最近一次写了暂缓的一样，不催。
        fun foreshadowRow(name: String, planted: Int?, moves: List<Pair<Int, ForeshadowMove>>, advanced: Int? = null, paused: Boolean = false) {
            val points = (listOfNotNull(planted, advanced) + moves.filter { it.second.action.moves }.map { it.first }).sorted()
            val last = points.lastOrNull()
            val lastMove = moves.maxByOrNull { it.first }
            val idle = last?.let { now - it } ?: (now + 1)
            val deferred = paused || lastMove != null && lastMove.second.action == MoveAction.DEFER && (last == null || lastMove.first >= last)
            val state = when {
                deferred -> PulseState.DEFERRED
                idle >= COLD_FORESHADOW -> PulseState.COLD
                else -> PulseState.OK
            }
            val note = when (state) {
                PulseState.DEFERRED -> if (paused) "账本里标了暂缓，不催" else "第 ${chapters[lastMove!!.first].number} 章的伏笔动作写了暂缓，不催"
                PulseState.COLD -> "已经 $idle 章没动（超过 $COLD_FORESHADOW 章算冷了）"
                else -> ""
            }
            rows += PulseRow(PulseKind.FORESHADOW, name, state, last?.let { chapters[it].number }, last?.let { chapters[it].title }.orEmpty(), idle, note)
        }
        val linked = mutableSetOf<Pair<Int, ForeshadowMove>>()
        promises.forEach { p ->
            val moves = touches(p.id, p.text)
            linked += moves
            foreshadowRow(p.text.trim().trimEnd('。').take(30), indexOf(p.from), moves,
                p.lastAdvanced?.takeIf { it <= currentNo }?.let(::indexOf), p.state == PromiseState.PAUSED)
        }
        // 细纲卡里埋下、账本里还没有的：按“埋”那一条认，后面说的像它的算同一条。
        chapters.indices.flatMap { i -> chapters[i].moves.filter { it.action == MoveAction.PLANT && it.ledgerId.isEmpty() }.map { i to it } }
            .filter { it !in linked }.distinctBy { ChapterSyncs.normalize(it.second.text) }.forEach { (i, m) ->
                val moves = touches("", m.text).filter { it.first > i && it !in linked }
                if (moves.none { it.second.action == MoveAction.PAYOFF }) foreshadowRow(m.text.take(30), i, moves)
            }

        // 主要人物：账本里在最多章出现过的几位。
        fun subjects(e: LedgerEntry) = e.subject.split('、', '，', ',', '／', '/', '；', ';').map { it.trim() }.filter { it.length in 2..8 }
        val byPerson = HashMap<String, MutableSet<Int>>()
        ledger.filter { !it.secret && it.kind != LedgerKind.PROMISE && it.kind != LedgerKind.TIME }.forEach { e ->
            val i = indexOf(e.from) ?: return@forEach
            if (i > now) return@forEach
            subjects(e).forEach { byPerson.getOrPut(it) { mutableSetOf() } += i }
        }
        byPerson.filter { it.value.size >= MIN_PERSON_CHAPTERS }.entries.sortedByDescending { it.value.size }.take(MAIN_PEOPLE).forEach { (name, seen) ->
            val points = seen + chapters.indices.filter { mentions(chapters[it].change, name) }
            val last = points.maxOrNull()
            val idle = last?.let { now - it } ?: (now + 1)
            val state = if (idle >= COLD_PERSON) PulseState.COLD else PulseState.OK
            rows += PulseRow(PulseKind.PERSON, name, state, last?.let { chapters[it].number }, last?.let { chapters[it].title }.orEmpty(), idle,
                if (state == PulseState.COLD) "已经 $idle 章没有新的状态和变化（超过 $COLD_PERSON 章算冷了）" else "")
        }
        val rank = mapOf(PulseState.STALLED to 0, PulseState.COLD to 1, PulseState.DEFERRED to 2, PulseState.OK to 3)
        return rows.sortedWith(compareBy<PulseRow> { rank.getValue(it.state) }.thenByDescending { it.idle }.thenBy { it.kind.ordinal })
    }
}
