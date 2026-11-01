package com.xycs.lingyuereader.data

/**
 * “AI 查 AI 腔”的固定问题：查的是语义层面的 AI 腔（短语清单查不到的那种），一类一问。
 * [ask] 是发给 AI 的那一问（自己写的，不照搬别人的提示词）；[reason] 是接到“这段不行”时算哪个原因（见 [FixReason]）。
 */
enum class ToneKind(val label: String, val ask: String, val reason: FixReason) {
    LABEL("情绪贴标签", "情绪是直接写出来的（“他很愤怒”“她心头一暖”），而不是靠动作、对白、细节让读者看出来", FixReason.FLAT),
    EXPLAIN("替读者总结", "一场戏已经演明白了，叙述又跟一句话把意思、情绪、用意解释一遍", FixReason.WORDY),
    EXPOSE("对白交代设定", "人物把彼此都知道的事说出来，其实是说给读者听的设定、背景", FixReason.OFF_CHARACTER),
    UPLIFT("段尾升华", "段落结尾总要加一句感慨、道理、意味深长的结论，好几段都这样", FixReason.WORDY),
    SIMILE("比喻扎堆", "几句之内接连用比喻，或者比喻和人物、场面不搭，只是为了显得有文采", FixReason.STIFF),
    GENERIC("放哪都行", "这句话换成任何人物、任何一本书都成立，看不出是这个人、这个场面", FixReason.STIFF),
    SUMMARY("概述代替场面", "本该一来一回演出来的冲突、转折，用一两句概述带过去了", FixReason.THIN),
    ;

    companion object {
        /** AI 回的类别：认名字或中文标签，认不出的算“放哪都行”。 */
        fun of(raw: String): ToneKind {
            val k = raw.trim()
            return entries.firstOrNull { it.name.equals(k, ignoreCase = true) || it.label == k }
                ?: entries.firstOrNull { k.isNotEmpty() && (it.label in k || k in it.label) } ?: GENERIC
        }
    }
}

/** AI 腔审读的一条：原句（正文里找得到的）、哪一类、问题在哪、怎么改。只是建议，不算必改。 */
data class ToneIssue(val quote: String, val kind: ToneKind, val problem: String, val fix: String = "") {
    val key: String get() = "tone\u0000${kind.name}\u0000$quote"

    /** 接到“这段不行”时交给 AI 的话：原因的固定改法，加上这一条的意见。 */
    fun instruction(): String = buildString {
        append("这段不行（").append(kind.reason.label).append("）：").append(kind.reason.fix)
        append(" 具体是：").append(problem.trim().trimEnd('。')).append("。")
        if (fix.isNotBlank()) append("改法：").append(fix.trim().trimEnd('。')).append("。")
        append("只改这一段，意思和情节不变。")
    }
}
