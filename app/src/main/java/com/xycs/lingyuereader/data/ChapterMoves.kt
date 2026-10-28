package com.xycs.lingyuereader.data

/**
 * 规划树上拖动一章（[WorkStore.moveChapterTo]）留下的一份记录，撤销时拿它比对（[WorkStore.undoChapterMove]）。
 *
 * 章在各处是怎么被引用的（查过的结论）：
 * - 按章 id：正文、细纲卡、审稿结果、候选稿和它的来源、创作初稿、历史版本、变更登记、整理标记、AI 原稿记录，
 *   以及账本条目上“从哪一章整理出来的”。这些跟着章走，移动不受影响。
 * - 按“第几章”：故事账本的起止章、计划收章、收在哪章；设定包里作者写的细纲、伏笔表、察觉表等文档；细纲卡里的细纲章号。
 *   章号按 [chapterNo] 算：细纲卡写了细纲章号就按它，章名写着“第X章”就按章名，都没有才按目录顺序。
 *
 * 所以只有“既没有细纲章号、章名里也没写第几章”的章，移动后章号会变，账本和设定包里按章号写的东西会错位到别的章上。
 * 设定包是作者写的原文，不能替作者改；账本里的章号也是照设定包记的。移动时就把这些章原来的章号写进它们的细纲卡
 * （细纲章号，见 [ChapterCard.plan]，本来就是给“发布顺序和设定章号不一致”用的），引用仍指向同一章；撤销时再清掉。
 * 作品里还没有账本、也没有设定包时没有东西按章号引用，不写，章号照目录顺序走。
 *
 * [before]、[after] 是移动前后每卷的章 id 顺序；[pinned] 是这次写进细纲卡的细纲章号（章 id → 写进去的值）；
 * [position] 是移到了全书目录的第几个（从 1 数），提示里说“移到第 N 章”。
 */
data class ChapterMove(
    val chapterId: String,
    val title: String,
    val before: List<Pair<String, List<String>>>,
    val after: List<Pair<String, List<String>>>,
    val pinned: Map<String, String> = emptyMap(),
    val position: Int,
)

/** 撤销移动时目录已经又改过了（又移动、加删了章或卷）：不拿旧顺序覆盖后来的修改。 */
class ChapterMoveConflict : IllegalStateException("目录顺序之后又改过了，没有撤销；请直接拖到想要的位置")

/** 每卷的章 id 顺序，移动和撤销比对用。 */
fun Work.order(): List<Pair<String, List<String>>> = volumes.map { v -> v.id to v.chapters.map { it.id } }

/**
 * 把 [chapterId] 移到 [volumeId] 卷的第 [index] 个（从 0 数，按去掉这一章以后的那一卷算，超出的收到两头）。
 * 卷或章不在了返回空；位置没变返回原作品。纯函数，[WorkStore.moveChapterTo] 和界面的落点计算共用。
 */
fun Work.withChapterAt(chapterId: String, volumeId: String, index: Int): Work? {
    val chapter = chapter(chapterId) ?: return null
    if (volumes.none { it.id == volumeId }) return null
    val removed = volumes.map { v -> v.copy(chapters = v.chapters.filterNot { it.id == chapterId }) }
    val next = removed.map { v ->
        if (v.id != volumeId) v
        else v.copy(chapters = v.chapters.toMutableList().apply { add(index.coerceIn(0, size), chapter) })
    }
    return copy(volumes = next)
}

/**
 * 规划树上一章的小标记：按设定算的章号 [number]、细纲卡有没有写（[outline]）、几拍（[beats]）、
 * 有没有 AI 拟的还没确认（[aiPending]），账本里这一章埋下的伏笔 [planted] 和计划这一章收的伏笔 [payoff]。
 */
data class ChapterTreeMarks(
    val number: Int? = null,
    val outline: Boolean = false,
    val beats: Int = 0,
    val aiPending: Boolean = false,
    val planted: Int = 0,
    val payoff: Int = 0,
) {
    companion object {
        fun of(card: ChapterCard, number: Int?, ledger: List<LedgerEntry>): ChapterTreeMarks {
            val (planted, payoff) = promiseCounts(ledger, number)
            return ChapterTreeMarks(number, !card.outlineEmpty, beats(card).size, card.aiOutline || card.aiBeats, planted, payoff)
        }

        /** 账本里第 [number] 章埋下的伏笔、计划在第 [number] 章收（还没收）的伏笔各几条；章号不知道时都是 0。 */
        fun promiseCounts(ledger: List<LedgerEntry>, number: Int?): Pair<Int, Int> {
            if (number == null) return 0 to 0
            val promises = ledger.filter { it.kind == LedgerKind.PROMISE }
            return promises.count { it.from == number } to promises.count { !it.done && it.payoff == number }
        }
    }
}
