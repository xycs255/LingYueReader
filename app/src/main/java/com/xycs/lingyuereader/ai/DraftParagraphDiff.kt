package com.xycs.lingyuereader.ai

/** Paragraph-level comparison for full chapter candidates. Unchanged paragraphs stay aligned. */
object DraftParagraphDiff {
    data class Piece(val kind: TextDiff.Kind, val paragraphs: List<String>)

    /**
     * Encode identical paragraphs as one character each, then reuse the bounded text diff.
     * A whole chapter may have thousands of changed characters but only a few changed paragraphs.
     * Null means that the difference is too large to align safely; callers should show both
     * complete versions without guessing paragraph matches.
     */
    fun diff(initial: String, current: String, maxEdits: Int = 2_000): List<Piece>? {
        val before = initial.split('\n')
        val after = current.split('\n')
        val codes = HashMap<String, Char>()
        val paragraphs = ArrayList<String>()
        fun encode(lines: List<String>): String? {
            val encoded = StringBuilder(lines.size)
            for (line in lines) {
                val code = codes[line] ?: run {
                    if (paragraphs.size >= Char.MAX_VALUE.code) return null
                    paragraphs += line
                    (paragraphs.size - 1).toChar().also { codes[line] = it }
                }
                encoded.append(code)
            }
            return encoded.toString()
        }
        val a = encode(before) ?: return null
        val b = encode(after) ?: return null
        return TextDiff.diff(a, b, maxEdits)?.map { part ->
            Piece(part.kind, part.text.map { paragraphs[it.code] })
        }
    }
}
