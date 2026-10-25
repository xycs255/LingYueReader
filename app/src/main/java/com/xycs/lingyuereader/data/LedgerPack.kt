package com.xycs.lingyuereader.data

import java.util.UUID

/**
 * 故事账本和设定包之间：从察觉表、伏笔表导入账本，把账本导出成 Markdown（导出成设定包里的一份资料）。
 * 导出的知情、伏笔两张表，列名照察觉表、伏笔表的写法：复制回察觉表、伏笔表，灵阅照样认得出来。
 */
object LedgerExport {
    /** 账本拼成 Markdown：时间线、人物状态、发生的事、知情、伏笔各一张表（没有的类别不列）。 */
    fun markdown(entries: List<LedgerEntry>): String {
        val out = StringBuilder("# ").append(LEDGER_EXPORT_TITLE).append("\n\n")
        if (entries.isEmpty()) return out.append("故事账本还是空的。\n").toString()
        out.append("灵阅从故事账本导出，共 ${entries.size} 条。“知情”“伏笔”两张表照察觉表、伏笔表的写法，复制过去也认得出来。\n")
        fun cell(s: String) = s.replace("|", "／").replace(Regex("\\s*\\n\\s*"), " ").trim()
        fun ch(n: Int?) = n?.let { "第${it}章" }.orEmpty()
        // 从第几章起：只知道第几卷的写“第2卷”，两样都有的写“第2卷第12章”（读回来章号、卷号都认）。
        fun since(e: LedgerEntry) = (e.volume?.let { "第${it}卷" }.orEmpty()) + ch(e.from)
        fun author(e: LedgerEntry) = if (e.secret) AUTHOR_ONLY else ""
        fun sorted(kind: LedgerKind) = entries.filter { it.kind == kind }.sortedWith(compareBy({ it.from ?: -1 }, { it.volume ?: -1 }))
        fun table(title: String, header: List<String>, rows: List<List<String>>) {
            if (rows.isEmpty()) return
            out.append("\n## ").append(title).append("\n\n")
            out.append("| ").append(header.joinToString(" | ")).append(" |\n")
            out.append("|").append(header.joinToString("|") { "---" }).append("|\n")
            rows.forEach { r -> out.append("| ").append(r.joinToString(" | ") { cell(it) }).append(" |\n") }
        }
        // 小标题带序号：察觉表把小标题里的人名当“谁”，“知情”两个字会被当成人名。
        table("一、时间线", listOf("时间", "从第几章起"), sorted(LedgerKind.TIME).map { listOf(it.text + author(it), since(it)) })
        table(
            "二、人物状态", listOf("人物", "方面", "状态", "从第几章起", "到第几章止"),
            sorted(LedgerKind.STATE).sortedBy { it.subject }.map { listOf(it.subject, it.aspect, it.text + author(it), since(it), ch(it.until)) },
        )
        table("三、发生的事", listOf("发生的事", "从第几章起", "到第几章止"), sorted(LedgerKind.EVENT).map { listOf(it.text + author(it), since(it), ch(it.until)) })
        // 只有作者知道的写在“真相”那一格：察觉表把这类格子当视角人物不知道的。
        table(
            "四、知情", listOf("人物", "知道什么", "从第几章起", "消息从哪来", "真相（只有作者知道）"),
            sorted(LedgerKind.KNOW).map { listOf(it.subject, if (it.secret) "" else it.text, since(it), it.via, if (it.secret) it.text else "") },
        )
        table(
            "五、伏笔", listOf("伏笔", "第几章埋", "计划第几章收", "状态"),
            sorted(LedgerKind.PROMISE).map { e ->
                listOf(
                    e.text + author(e),
                    if (e.from != null) ch(e.from) else e.volume?.let { "第${it}卷" }.orEmpty(),
                    ch(e.payoff),
                    if (e.done) "已回收" + (e.closed?.let { "（第${it}章）" } ?: "") else "",
                )
            },
        )
        return out.toString()
    }
}

object LedgerImport {
    /**
     * 设定包里能导进账本的条目：察觉表每条按“谁”拆成每人一条知情（视角人物不知道的那格另成一条只有作者知道的），
     * 伏笔表每条一条伏笔。人物从人物档案、称谓与语气文档认，写的是别名的换成本名。依据填设定包里那一行（那一项）原文。
     */
    fun entries(docs: List<Pair<PackDoc, String>>): List<LedgerEntry> {
        val live = docs.filter { it.second.isNotBlank() }
        val characters = ChapterBriefs.characters(live.filter { it.first.kind == PackKind.CHARACTER || it.first.kind == PackKind.VOICE }.map { it.first.title to it.second })
        val canonical = characters.flatMap { c -> c.names.map { it to c.name } }.toMap()
        val out = mutableListOf<LedgerEntry>()
        val awareness = live.filter { it.first.kind == PackKind.AWARENESS }.map { it.first.title to it.second }
        ChapterBriefs.knowledge(awareness, canonical.keys).forEach { k ->
            // 写明的人（小节标题、“谁”那一列）里只认人物档案里有的：“## 其他”这类小标题不是人。没写明的看内容里提到谁。
            fun people(names: Collection<String>) = names.filter { canonical.isEmpty() || it in canonical }
            val who = people(k.holders).ifEmpty { people(k.who) }.map { canonical[it] ?: it }.distinct()
            // 只写了“真相”那格的行，没有人物知道的内容。
            val what = k.what.ifBlank { if (k.hiddenWhat.isBlank()) k.text else "" }
            fun know(subject: String, text: String, secret: Boolean) = LedgerEntry(
                id = UUID.randomUUID().toString(), kind = LedgerKind.KNOW, text = text.trim(), subject = subject,
                from = k.chapter, volume = k.volume, via = if (secret) "" else k.via, secret = secret, quote = k.quote, origin = LedgerOrigin.IMPORT,
            )
            if (what.isNotBlank()) who.ifEmpty { listOf("") }.forEach { out += know(it, what, false) }
            if (k.hiddenWhat.isNotBlank()) out += know(who.singleOrNull().orEmpty(), k.hiddenWhat, true)
        }
        val foreshadowDocs = live.filter { it.first.kind == PackKind.FORESHADOW }.map { it.first.title to it.second }
        ChapterBriefs.foreshadows(foreshadowDocs).forEach { f ->
            val line = foreshadowDocs.firstOrNull { it.first == f.doc }?.second?.lines()?.getOrNull(f.line)?.trim().orEmpty()
            // 导出时只有作者知道的伏笔后面带着“（只有作者知道）”，读回来还是只有作者知道的。
            val secret = f.what.trim().endsWith(AUTHOR_ONLY)
            out += LedgerEntry(
                kind = LedgerKind.PROMISE, text = f.what.trim().removeSuffix(AUTHOR_ONLY), from = f.planted, payoff = f.payoff,
                done = f.done, closed = if (f.done) f.closed else null, secret = secret, quote = line, origin = LedgerOrigin.IMPORT,
            )
        }
        return out.filter { it.text.isNotEmpty() }
    }

    /** 两条是不是同一条：[LedgerEntry.key] 相同，或同类、同一人、内容规范化以后一方包含另一方。 */
    fun same(a: LedgerEntry, b: LedgerEntry): Boolean =
        a.key == b.key || (a.kind == b.kind && a.subject.trim() == b.subject.trim() && a.secret == b.secret && ChapterBriefs.same(a.text, b.text))

    /** 去掉账本里已有的、同一批前面已经有的。 */
    fun fresh(entries: List<LedgerEntry>, existing: List<LedgerEntry>): List<LedgerEntry> {
        val seen = existing.toMutableList()
        return entries.filter { e -> seen.none { same(it, e) }.also { if (it) seen += e } }
    }
}

/** 设定包的察觉表、伏笔表里有、账本里还没有的条目（导入前给作者看）。 */
fun WorkStore.ledgerImportPreview(workId: String): List<LedgerEntry> =
    LedgerImport.fresh(LedgerImport.entries(pack(workId).map { it to packText(workId, it.id) }), ledger(workId))

/** 把作者勾选的导入条目写进账本，返回加了几条。 */
fun WorkStore.importLedger(workId: String, entries: List<LedgerEntry>): Int =
    applyLedger(workId, entries.map { LedgerOp.Add(it.copy(origin = LedgerOrigin.IMPORT)) }, "从设定包导入")

/** 把账本导出成设定包里的一份资料（《故事账本（导出）》，按需查）；已有就换掉内容。返回那份文档。 */
fun WorkStore.exportLedgerToPack(workId: String): PackDoc {
    val text = LedgerExport.markdown(ledger(workId))
    val existing = pack(workId).firstOrNull { it.title == LEDGER_EXPORT_TITLE }
    return if (existing != null) savePackText(workId, existing.id, text) ?: existing
    else addPackDoc(workId, PackKind.REFERENCE, LEDGER_EXPORT_TITLE, text, always = false)
}

const val LEDGER_EXPORT_TITLE = "故事账本（导出）"

/** 导出时只有作者知道的条目后面加的标记。 */
private const val AUTHOR_ONLY = "（只有作者知道）"
