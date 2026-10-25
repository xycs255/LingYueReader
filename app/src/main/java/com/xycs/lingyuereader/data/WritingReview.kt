package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 三道审稿，照“先核事实、因果、视角和承接，再审写法，最后对照规则”的次序：
 * 前两道请 AI 对照设定资料和写作规范挑问题，第三道用本机的检查规则。
 */
enum class ReviewGate(val label: String, val hint: String) {
    FACT("事实关", "对照设定资料、细纲卡、察觉表和上一章：事实、因果、视角、承接、人物有没有提前知道"),
    CRAFT("写法关", "只按设定包里的写作规范、称谓与语气：对白、旁白、场面、套话"),
    RULES("规则关", "检查规则，在本机算"),
}

/** 一处问题：原文里的一句、问题在哪、怎么改（可无）；[state] 是作者标的：已改、不改，空着是还没处理。 */
data class ReviewIssue(val quote: String, val problem: String, val fix: String = "", val state: String = "") {
    val open: Boolean get() = state.isEmpty()

    companion object {
        const val FIXED = "fixed"
        const val IGNORED = "ignored"
    }
}

/** 一道审稿的结果：问题，或出错的原因。 */
data class GateResult(val gate: ReviewGate, val issues: List<ReviewIssue> = emptyList(), val error: String? = null, val at: Long = 0)

/** 一章最近一次审稿：审的是哪一版正文（[textHash]），各道的结果。 */
data class ChapterReview(val textHash: Int, val at: Long, val gates: List<GateResult>) {
    fun gate(g: ReviewGate): GateResult? = gates.firstOrNull { it.gate == g }

    fun toJson(): JSONObject = JSONObject().put("textHash", textHash).put("at", at).put(
        "gates",
        JSONArray().apply {
            gates.forEach { g ->
                put(
                    JSONObject().put("gate", g.gate.name).put("at", g.at).put("error", g.error ?: JSONObject.NULL).put(
                        "issues",
                        JSONArray().apply { g.issues.forEach { put(JSONObject().put("quote", it.quote).put("problem", it.problem).put("fix", it.fix).put("state", it.state)) } },
                    ),
                )
            }
        },
    )

    companion object {
        fun fromJson(o: JSONObject): ChapterReview {
            val gates = o.optJSONArray("gates") ?: JSONArray()
            return ChapterReview(
                o.optInt("textHash"),
                o.optLong("at"),
                (0 until gates.length()).mapNotNull { i ->
                    val g = gates.optJSONObject(i) ?: return@mapNotNull null
                    val gate = runCatching { ReviewGate.valueOf(g.optString("gate")) }.getOrNull() ?: return@mapNotNull null
                    val issues = g.optJSONArray("issues") ?: JSONArray()
                    GateResult(
                        gate,
                        (0 until issues.length()).mapNotNull { j ->
                            issues.optJSONObject(j)?.let { ReviewIssue(it.optString("quote"), it.optString("problem"), it.optString("fix"), it.optString("state")) }
                        },
                        if (g.isNull("error")) null else g.optString("error"),
                        g.optLong("at"),
                    )
                },
            )
        }
    }
}

/**
 * 写作 AI 的一条记录（灵感、读者点评、矛盾检查、写本章）：哪一章、做了什么、AI 的回答，以及作者觉得有没有用
 * （[rating] 1 有用、-1 没用、0 没评）。存在本机，方便回头翻，也方便作者判断哪些提示词好用。
 */
data class WriteRecord(
    val id: String,
    val at: Long,
    val chapterId: String,
    val chapterTitle: String,
    val kind: String,
    val ask: String,
    val text: String,
    val rating: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("chapterId", chapterId).put("chapterTitle", chapterTitle)
        .put("kind", kind).put("ask", ask).put("text", text).put("rating", rating)

    companion object {
        const val KEEP = 100

        fun fromJson(o: JSONObject): WriteRecord? = o.optString("id").takeIf { it.isNotEmpty() }?.let {
            WriteRecord(it, o.optLong("at"), o.optString("chapterId"), o.optString("chapterTitle"), o.optString("kind"), o.optString("ask"), o.optString("text"), o.optInt("rating"))
        }
    }
}
