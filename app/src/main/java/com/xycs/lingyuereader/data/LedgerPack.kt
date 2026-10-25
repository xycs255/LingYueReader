package com.xycs.lingyuereader.data

/**
 * 故事账本和设定包之间：从察觉表、伏笔表导入账本，把账本导出成 Markdown（导出成设定包里的一份资料）。
 * TODO(账本接入)：下面是占位，由“接入本章资料”那一步实现。
 */
object LedgerExport {
    /** 账本拼成 Markdown：时间线、人物状态、发生的事、知情、伏笔各一张表。 */
    fun markdown(entries: List<LedgerEntry>): String = entries.joinToString("\n") { "- " + it.kind.label + "：" + it.text }
}

/** 设定包的察觉表、伏笔表里有、账本里还没有的条目（导入前给作者看）。 */
fun WorkStore.ledgerImportPreview(workId: String): List<LedgerEntry> = emptyList()

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
