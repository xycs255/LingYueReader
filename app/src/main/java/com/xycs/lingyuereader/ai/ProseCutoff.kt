package com.xycs.lingyuereader.ai

/**
 * 写正文时在本机收住：任务卡里写了上限，模型照样可能一口气写到两倍。流式收到的字数（不算空白，同 [com.xycs.lingyuereader.data.WordCount]）
 * 超过 [limit] 后，到下一个句末（。！？…，连同紧跟的后引号）就抛 [ProseCut]，带上到句末为止的文字：
 * 调用方接住它当作这次的回答；抛出时请求还在读，连接随之断开，服务商不再往下写（也不再计费）。
 * 引号、括号里的句号不算句末（话还没说完），免得收在半句对白上。
 * [ignore] 是服务商、客户端自己追加的说明（“回答太长，后面被截断了”这类），不算正文。
 */
class ProseCutoff(val limit: Int, private val ignore: Set<String> = emptySet()) {
    private val text = StringBuilder()
    private var count = 0
    private var depth = 0
    /** 已经到了句末（引号、括号外），等下一个字看看是不是接着的句末标点：句末之后的位置。 */
    private var pending = -1

    /** 工具查完重来、或换了一轮回答：从头数。 */
    fun reset() {
        text.setLength(0)
        count = 0
        depth = 0
        pending = -1
    }

    /** 收一段；字数过了上限又到了句末就抛 [ProseCut]。 */
    fun feed(piece: String) {
        if (piece.isEmpty() || piece in ignore) return
        for (ch in piece) {
            if (pending >= 0) {
                // 省略号、问叹连用（“……”“？！”）还是这一句的；别的字来了，句子就是在上一个句末结束的。
                if (ch !in END) throw ProseCut(text.substring(0, pending))
                text.append(ch)
                count++
                pending = text.length
                continue
            }
            text.append(ch)
            if (!ch.isWhitespace()) count++
            when (ch) {
                in OPEN -> depth++
                in CLOSE -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (count <= limit) continue
            val previous = text.getOrNull(text.length - 2)
            val ends = (ch in END && depth == 0) || (ch in CLOSE && depth == 0 && previous != null && previous in END)
            if (ends) pending = text.length
        }
    }

    companion object {
        private const val END = "。！？!?…"
        private const val OPEN = "“「『（(《"
        private const val CLOSE = "”」』）)》"
    }
}

/** 本机收住了：[text] 是收到句末为止的原文（还没清理）。不是 [kotlinx.coroutines.CancellationException]：调用方当作正常写完。 */
class ProseCut(val text: String) : RuntimeException("写长了，在句末收住")
