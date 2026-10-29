package com.xycs.lingyuereader.data

/**
 * 设定包页顶上那根“AI 每次带多少”的条：写正文时随请求带上的设定资料，按类分段；本章资料、知识库各自的上限；
 * 放不下、被截掉的文档。数字都从写正文、审读真正用的那几样（作者说明、写作规范与检查规则、本章资料、知识库）算出来，
 * 不另估一遍：估的和发的对不上，作者照着删资料反而删错。
 */
data class PackBudget(
    /** 按哪一章算的（本章资料按章挑）；作品还没有章为空。 */
    val chapterTitle: String?,
    /** 写正文带上的资料，按类分段，多的在前；加起来就是 [proseChars]。 */
    val slices: List<BudgetSlice>,
    /** 本章资料（按章挑的那一份）实际多少字。 */
    val briefChars: Int,
    /** 写正文时本章资料最多带多少字（作品设的本章资料上限的一半，见 [com.xycs.lingyuereader.WritingAi]）。 */
    val briefLimit: Int,
    /** 作品设的本章资料上限（设置里那个数）。 */
    val briefSetting: Int,
    /** 知识库（审稿、整理时整份放进去的常用文档）实际多少字。 */
    val knowledgeChars: Int,
    /** 知识库最多放多少字。 */
    val knowledgeLimit: Int,
    /** 放不下、被截掉的。 */
    val misses: List<BudgetMiss>,
) {
    val proseChars: Int get() = slices.sumOf { it.chars }
}

/** 一段：哪一类（[kind] 为空是前情、账本、开头说明这些不属于设定包某一类的）、多少字。 */
data class BudgetSlice(val kind: PackKind?, val label: String, val chars: Int)

/** 放不下的一份：标题、哪一类、原来多少字、为什么。[whole] 为真是整份没带，否则是截掉了后面。 */
data class BudgetMiss(val title: String, val kind: PackKind?, val chars: Int, val reason: String, val whole: Boolean)

/** 九宫格里一类的统计：几份、多少字、其中几份常用。 */
data class PackKindStat(val kind: PackKind, val docs: Int, val words: Int, val always: Int) {
    val empty: Boolean get() = docs == 0

    /** 卡片上的小标记：都常用、都按需、有常用有按需；空的类按这类新建时的默认。 */
    val mode: String get() = when {
        docs == 0 -> if (kind.always) "常用" else "按需"
        always == docs -> "常用"
        always == 0 -> "按需"
        else -> "常用 $always"
    }
}

object PackBudgets {
    /** 每一类的统计，顺序同 [PackKind]（没有文档的类也列出来，九宫格里淡显）。 */
    fun stats(docs: List<PackDoc>): List<PackKindStat> {
        val byKind = docs.groupBy { it.kind }
        return PackKind.entries.map { k ->
            val list = byKind[k].orEmpty()
            PackKindStat(k, list.size, list.sumOf { it.words }, list.count { it.always })
        }
    }

    /** 本章资料里的一项属于哪一类：整份带上和切块的用文档的类名，人物卡、察觉、写作规范节选各自对上。 */
    fun kindOf(group: String): PackKind? = when (group) {
        "人物" -> PackKind.CHARACTER
        "察觉" -> PackKind.AWARENESS
        "写作规范" -> PackKind.STYLE
        else -> PackKind.entries.firstOrNull { it.label == group }
    }

    /** 本章资料截短时在后面加的那句（见 [ChapterBriefs]）。 */
    private const val CUT_MARK = "……（后面省略）"

    /**
     * 拼一份 [PackBudget]。[author] 是作者写给 AI 的说明，[norms] 是写作规范与检查规则连起来、去掉整章字数以后的那一份，
     * [normPieces] 是它连起来之前的各段（两段之间隔一个空行），每段又按文档分成几节（属于哪一类、原文）；[brief]、[material] 是本章资料和它拼成的文字；[knowledge] 是知识库。
     * 各段的字数加起来一定等于真正发出去的 `author + norms + material`：拆段时多出、少掉的几个换行记在这一类最大的那段上。
     */
    fun build(
        chapterTitle: String?,
        author: String,
        norms: String,
        normPieces: List<List<Pair<PackKind, String>>>,
        brief: ChapterBrief?,
        material: String,
        briefLimit: Int,
        briefSetting: Int,
        knowledge: AiPack?,
        knowledgeDocs: List<Pair<PackDoc, String>>,
    ): PackBudget {
        // 发出去的时候三样各自去掉首尾空白（见 [com.xycs.lingyuereader.ai.WritePrompts.proseSystem]），这里照样算。
        val raw = mutableListOf<Pair<PackKind?, Int>>()
        if (author.isNotBlank()) raw += PackKind.INSTRUCTION to author.trim().length

        // 写作规范与检查规则：各段照发出去时的处理（去掉整章字数、去掉重复的标题）各算各的。
        val norm = normPieces.flatMapIndexed { i, piece ->
            piece.mapIndexed { j, (k, t) ->
                k to ProsePack.tidy(ProseLengths.strip(t)).length + if (i < normPieces.lastIndex && j == piece.lastIndex) 2 else 0
            }
        }
        raw += fix(norm, norms.trim().length)

        // 本章资料：按拼出来的各段算，去掉首尾空白的差额记在最大的一段上。
        val parts = brief?.renderParts(prose = true, waitingMax = ChapterBrief.WAITING).orEmpty().map { p ->
            val kind = when (p.key) {
                BriefPart.Key.ITEM -> p.item?.let { kindOf(it.group) }
                BriefPart.Key.FORESHADOW -> PackKind.FORESHADOW
                BriefPart.Key.NOT_YET -> PackKind.AWARENESS
                else -> null
            }
            kind to p.text.length
        }
        raw += fix(parts, material.trim().length)

        val slices = raw.groupBy({ it.first }, { it.second }).map { (k, n) -> BudgetSlice(k, k?.label ?: OTHER, n.sum()) }
            .filter { it.chars > 0 }.sortedByDescending { it.chars }

        val misses = mutableListOf<BudgetMiss>()
        knowledge?.overflow?.forEach { d ->
            val chars = knowledgeDocs.firstOrNull { it.first.id == d.id }?.second?.length ?: 0
            misses += BudgetMiss(
                d.title, d.kind, chars,
                "标了常用，审稿、整理时要整份放进知识库，但知识库上限 ${words(knowledge.budget)} 放不下（这份 ${words(chars)}）。" +
                    "现在改成 AI 用到时自己查；想整份带上，调大“知识库上限”，或把它改成“按需”、拆成几份小的。",
                whole = true,
            )
        }
        brief?.items.orEmpty().filter { it.text.endsWith(CUT_MARK) }.forEach { item ->
            misses += BudgetMiss(
                item.title, kindOf(item.group), item.text.length - CUT_MARK.length,
                "写这一章时按章挑的资料最多 ${words(briefLimit)}，这一段只带了前 ${words(item.text.length - CUT_MARK.length)}，后面截掉了。" +
                    "要多带，调大“本章资料上限”，或把这份资料拆细（一节一个标题）。",
                whole = false,
            )
        }
        return PackBudget(
            chapterTitle, slices, material.trim().length, briefLimit, briefSetting,
            // 和知识库判断放不放得下用的是同一个数：工作说明加整份放进去的常用文档，原文字数。
            knowledge?.let { k -> (k.instructions + k.knowledge).sumOf { d -> knowledgeDocs.firstOrNull { it.first.id == d.id }?.second?.length ?: 0 } } ?: 0,
            knowledge?.budget ?: 0, misses,
        )
    }

    /** 各段加起来要等于 [total]：差额（换行、首尾空白）记在最大的一段上。 */
    private fun fix(parts: List<Pair<PackKind?, Int>>, total: Int): List<Pair<PackKind?, Int>> {
        if (parts.isEmpty()) return if (total > 0) listOf(null to total) else emptyList()
        val diff = total - parts.sumOf { it.second }
        if (diff == 0) return parts
        val at = parts.indices.maxBy { parts[it].second }
        return parts.mapIndexed { i, p -> if (i == at) p.first to (p.second + diff).coerceAtLeast(0) else p }
    }

    /**
     * 写作规范、称谓与语气的节选（[ChapterBriefs.styleFor] 拼的，每份以“【标题】”或“【标题（节选）】”起头）按文档切开，
     * 各节记到那份文档的类上；对不上标题的记在写作规范上。切开的几节连起来还是原文。
     */
    fun splitStyle(text: String, docs: List<PackDoc>): List<Pair<PackKind, String>> {
        if (text.isEmpty()) return emptyList()
        val starts = docs.filter { it.kind == PackKind.STYLE || it.kind == PackKind.VOICE }.mapNotNull { d ->
            listOf("【${d.title}】\n", "【${d.title}（节选）】\n").mapNotNull { head ->
                var at = text.indexOf(head)
                while (at > 0 && text[at - 1] != '\n') at = text.indexOf(head, at + 1)
                at.takeIf { it >= 0 }
            }.minOrNull()?.let { it to d.kind }
        }.distinctBy { it.first }.sortedBy { it.first }
        if (starts.isEmpty()) return listOf(PackKind.STYLE to text)
        val out = mutableListOf<Pair<PackKind, String>>()
        if (starts[0].first > 0) out += PackKind.STYLE to text.substring(0, starts[0].first)
        starts.forEachIndexed { i, (at, kind) -> out += kind to text.substring(at, starts.getOrNull(i + 1)?.first ?: text.length) }
        return out
    }

    /** 不属于设定包某一类的那段的名字。 */
    const val OTHER = "前情、账本等"

    /** 字数给人看：一万以上写“万字”。 */
    fun words(n: Int): String = if (n >= 10_000) "%.1f 万字".format(n / 10_000f) else "$n 字"
}
