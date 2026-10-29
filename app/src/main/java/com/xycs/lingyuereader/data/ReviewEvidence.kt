package com.xycs.lingyuereader.data

import java.security.MessageDigest

/** The exact, local material snapshot against which a review's source quotation is checked. */
data class ReviewSource(
    val ref: String,
    val label: String,
    val text: String,
    /** Task fields are separate passages so a quotation cannot span unrelated brief fields. */
    val passages: List<String> = listOf(text),
    val supportsFact: Boolean = true,
    /** Only explicit writing instructions may justify an automatic CRAFT revision. */
    val craftPassages: List<String> = emptyList(),
) {
    val supportsCraft: Boolean get() = craftPassages.isNotEmpty()
}

class ReviewEvidence(val sources: List<ReviewSource>) {
    val taskRef: String? get() = sources.firstOrNull { it.ref.startsWith("T_") }?.ref

    /** A source match locates the quotation; it does not prove the model's interpretation of it. */
    fun locates(ref: String, quote: String, kind: String = "FACT"): Boolean {
        val fragment = normalize(quote)
        if (fragment.count { it.isLetterOrDigit() } < MIN_QUOTE_CHARS) return false
        val source = sources.firstOrNull { it.ref == ref } ?: return false
        val passages = when (kind) {
            "FACT" -> source.passages.takeIf { source.supportsFact }
            "CRAFT" -> source.craftPassages
            else -> null
        } ?: return false
        return passages.any { fragment in normalize(it) }
    }

    companion object {
        const val MIN_QUOTE_CHARS = 6
        private val FACT_PACK_KINDS = setOf(PackKind.OUTLINE, PackKind.INVARIANT, PackKind.VOLUME,
            PackKind.CANON, PackKind.CHARACTER, PackKind.AWARENESS, PackKind.FORESHADOW)
        private val CRAFT_PACK_KINDS = setOf(PackKind.INSTRUCTION, PackKind.STYLE, PackKind.VOICE,
            PackKind.RULES)

        fun ref(prefix: String, id: String, text: String): String {
            val input = "$prefix\u0000$id\u0000$text".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            return prefix + "_" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun task(task: ChapterWritingTask): ReviewSource {
            // The brief also contains inferred handoff labels, plans, warnings and selected background.
            // Those are useful context but cannot by themselves prove a FACT complaint.
            val passages = listOf(task.instruction, task.card.facts, task.card.boundary,
                task.card.pov, task.card.countdown, task.card.hook, task.card.threads, task.previous)
                .map { it.trim() }.filter { it.isNotBlank() }
            val text = passages.joinToString("\n")
            return ReviewSource(ref("T", task.fingerprint, passages.joinToString("\u0000")),
                "本次明确要求、目标章细纲卡及已定前章摘录", text, passages,
                craftPassages = listOf(task.instruction.trim()).filter { it.isNotBlank() })
        }

        /**
         * 未定稿的上一章结尾（[ChapterWritingTask.previousDraft]）：审读时能看到、能拿来看衔接，但不是已定前文——
         * 既不能当 FACT 依据，也不能当写法依据。没带时为空。
         */
        fun tentative(task: ChapterWritingTask): ReviewSource? = task.previousDraft.trim().takeIf { it.isNotEmpty() }?.let { text ->
            ReviewSource(ref("D", task.fingerprint, text), "未定稿的上一章结尾（只看衔接，不作事实依据）", text, supportsFact = false)
        }

        fun pack(doc: PackDoc, text: String): ReviewSource =
            ReviewSource(ref("P", doc.id, text), "《${doc.title}》", text,
                supportsFact = doc.kind in FACT_PACK_KINDS,
                craftPassages = if (doc.kind in CRAFT_PACK_KINDS) listOf(text) else emptyList())

        fun chapter(chapter: WorkChapter, text: String): ReviewSource =
            ReviewSource(ref("C", chapter.id, text), "已定正文《${chapter.title}》", text)

        private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }
    }
}

/** Use the same stage filter and FINAL-only boundary as the review's pack lookup tools. */
fun WorkStore.reviewEvidence(workId: String, chapterId: String, task: ChapterWritingTask): ReviewEvidence {
    val work = get(workId)
    val before = work?.chapters?.indexOfFirst { it.id == chapterId } ?: -1
    val chapters = if (before >= 0) work!!.chapters.take(before).filter { it.status == ChapterStatus.FINAL } else emptyList()
    val docs = packSources(workId).forPhase(WritingPhase.REVIEW)
        .filter { (doc, text) -> text.isNotBlank() && doc.kind !in setOf(PackKind.REFERENCE, PackKind.TECHNIQUE) }
    return ReviewEvidence(listOf(ReviewEvidence.task(task)) + listOfNotNull(ReviewEvidence.tentative(task)) +
        docs.map { (doc, text) -> ReviewEvidence.pack(doc, text) } +
        chapters.map { ReviewEvidence.chapter(it, text(workId, it.id)) })
}
