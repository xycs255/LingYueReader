package com.xycs.lingyuereader.data

/** 朗读的一句：第几块里 [start, end) 这段文字（按去掉段首缩进后的显示文字计）。 */
data class Utterance(val block: Int, val start: Int, val end: Int, val text: String)

object Sentences {

    /** 句末标点后面可能跟着的引号、括号，一并算进这一句。 */
    private val sentenceEnd = Regex("""[。！？!?；;…]+[”’」』）)\]"']*""")

    /** 过长的句子（没有标点的大段）再按逗号或长度切开，免得一次送给语音引擎的文字太长。 */
    private const val MAX_LENGTH = 120

    /** 从 [fromBlock] 的第 [fromChar] 个字开始，把一章拆成一句一句。 */
    fun split(blocks: List<Block>, fromBlock: Int = 0, fromChar: Int = 0): List<Utterance> {
        val out = mutableListOf<Utterance>()
        for (b in fromBlock.coerceAtLeast(0) until blocks.size) {
            val text = blocks[b].displayText ?: continue
            var start = if (b == fromBlock) fromChar.coerceIn(0, text.length) else 0
            // 从句子中间开始时，退回到这句开头。
            if (start > 0) start = sentenceStart(text, start)
            var cursor = start
            for (m in sentenceEnd.findAll(text, start)) {
                addPieces(out, b, text, cursor, m.range.last + 1)
                cursor = m.range.last + 1
            }
            if (cursor < text.length) addPieces(out, b, text, cursor, text.length)
        }
        return out
    }

    private fun sentenceStart(text: String, at: Int): Int {
        var last = 0
        for (m in sentenceEnd.findAll(text)) {
            if (m.range.last + 1 > at) break
            last = m.range.last + 1
        }
        return last
    }

    private fun addPieces(out: MutableList<Utterance>, block: Int, text: String, from: Int, to: Int) {
        var s = from
        while (s < to) {
            var e = to
            if (e - s > MAX_LENGTH) {
                val comma = text.lastIndexOfAny(charArrayOf('，', ',', '、', '：'), s + MAX_LENGTH)
                e = if (comma > s) comma + 1 else s + MAX_LENGTH
            }
            val piece = text.substring(s, e)
            if (piece.isNotBlank()) {
                // 去掉首尾空白，但位置仍对应原文。
                val lead = piece.length - piece.trimStart().length
                val trail = piece.length - piece.trimEnd().length
                out += Utterance(block, s + lead, e - trail, piece.trim())
            }
            s = e
        }
    }
}
