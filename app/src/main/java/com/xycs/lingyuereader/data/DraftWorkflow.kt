package com.xycs.lingyuereader.data

import org.json.JSONObject
import java.security.MessageDigest

/** Candidate manuscripts never become confirmed story state merely because these checks passed. */
enum class DraftStage(val label: String) {
    IDLE("未审读"), PREPARING("核对本章任务"), CREATING("创作正文"),
    REVIEWING("审读本稿"), REVISING("按核实意见修订"), CHECKING("本机检查"),
    READY("可供采纳"), NEEDS_REVIEW("需要核对"), STOPPED("已停止，稿件保留"), ERROR("本次未完成"),
}

data class DraftFinding(
    val kind: String,
    val quote: String,
    val problem: String,
    val basis: String,
    val fix: String = "",
    val required: Boolean = false,
) {
    fun render(): String = buildString {
        append(if (required) "必改" else "建议").append(" · ").append(kind).append("：").append(problem)
        if (quote.isNotBlank()) append("\n原句：").append(quote)
        if (basis.isNotBlank()) append("\n依据：").append(basis)
        if (fix.isNotBlank()) append("\n可用改法：").append(fix)
    }
}

/** A report belongs to exactly one manuscript and one material snapshot. No persisted implicit pass. */
data class DraftAssessment(
    val manuscript: String,
    val materials: String,
    val summary: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val findings: List<DraftFinding> = emptyList(),
    val localIssues: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val round: Int = 0,
) {
    val passed: Boolean get() = missing.isEmpty() && findings.none { it.required } && localIssues.isEmpty()
    fun matches(text: String, fingerprint: String): Boolean = manuscript == DraftChecks.hash(text) && materials == fingerprint
    fun render(requiredOnly: Boolean = false): String = buildString {
        if (!requiredOnly && summary.isNotEmpty()) append("最影响本章的问题：\n").append(summary.joinToString("\n") { "- $it" }).append("\n\n")
        if (missing.isNotEmpty()) append("资料缺项（未判定为事实错误）：\n").append(missing.joinToString("\n") { "- $it" }).append("\n\n")
        val shown = if (requiredOnly) findings.filter { it.required } else findings
        shown.forEach { append(it.render()).append("\n\n") }
        if (localIssues.isNotEmpty()) append("本机检查需要处理：\n").append(localIssues.joinToString("\n") { "- $it" }).append("\n\n")
        if (!requiredOnly && notes.isNotEmpty()) append("供作者核对：\n").append(notes.joinToString("\n") { "- $it" }).append('\n')
    }.trim()
}

object DraftChecks {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Malformed answers and fabricated quotations are errors, never an empty successful review. */
    fun parse(answer: String, text: String, fingerprint: String): DraftAssessment {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "审读回答不是要求的格式，尚未通过审读" }
        val root = try { JSONObject(answer.substring(start, end + 1)) }
        catch (e: Exception) { throw IllegalArgumentException("审读回答无法解析，尚未通过审读", e) }
        fun strings(key: String): List<String> {
            val a = root.optJSONArray(key) ?: throw IllegalArgumentException("审读回答缺少 $key，尚未通过审读")
            require(a.length() <= 100) { "审读回答条目过多，请重新审读" }
            return (0 until a.length()).map { i ->
                require(a.get(i) is String) { "审读回答的 $key 格式有误" }
                a.getString(i).trim()
            }.filter { it.isNotBlank() }
        }
        val summary = strings("summary").take(3)
        val missing = strings("missing")
        val items = root.optJSONArray("issues") ?: throw IllegalArgumentException("审读回答缺少 issues，尚未通过审读")
        require(items.length() <= 100) { "审读意见过多，请重新审读" }
        val findings = (0 until items.length()).map { n ->
            val i = items.getJSONObject(n)
            val kind = i.getString("kind")
            require(kind in setOf("FACT", "CRAFT")) { "审读意见类别无法识别" }
            val quote = i.getString("quote").trim()
            val problem = i.getString("problem").trim()
            val basis = i.getString("basis").trim()
            require(quote.isNotBlank() && quote in text) { "审读意见的原句在本稿中找不到，请重新审读" }
            require(problem.isNotBlank() && basis.isNotBlank()) { "审读意见没有问题说明或依据，尚未通过审读" }
            require(i.get("required") is Boolean) { "审读意见没有区分必改与建议" }
            DraftFinding(kind, quote, problem, basis, i.optString("fix"), i.getBoolean("required"))
        }
        return DraftAssessment(hash(text), fingerprint, summary, missing, findings)
    }

    /** Numerical targets are overridable per chapter; elastic targets remain notes, not hard quotas. */
    fun local(text: String, rules: RuleSet, target: String): Pair<List<String>, List<String>> {
        val single = Regex("^(?:约|大约|目标(?:字数|篇幅)?[：:]?|恰好|严格|正好)?\\s*([0-9０-９零〇一二两三四五六七八九十百千万]+)\\s*(汉字|字)?\\s*(?:左右|上下)?$").matchEntire(target.trim())
        val goal = single?.groupValues?.get(1)?.let { CheckRules.cn(it.replace('〇', '零')) }?.takeIf { it > 0 }
        val singleRule = goal?.let { CheckRule.Length(it, it, single?.groupValues?.get(2) == "汉字", RuleSource("本章目标篇幅", 1, target)) }
        val targetRule = CheckRules.parse("字数：$target", "本章目标篇幅").rules.filterIsInstance<CheckRule.Length>().firstOrNull() ?: singleRule
        val elastic = Regex("约|左右|弹性|建议|试写").containsMatchIn(target) || (singleRule != null && !Regex("恰好|严格|正好").containsMatchIn(target))
        val base = if (targetRule == null) rules else rules.copy(rules = rules.rules.filterNot { it is CheckRule.Length } + if (elastic) emptyList() else listOf(targetRule))
        val issues = CheckRules.check(text, base).hits.map { it.message }.toMutableList()
        val notes = mutableListOf<String>()
        if (rules.unknown.isNotEmpty()) notes += "${rules.unknown.size} 条检查规则尚未识别，需作者核对；本机未对这些规则给出通过结论"
        if (targetRule != null && elastic) notes += CheckRules.check(text, RuleSet(listOf(targetRule))).hits.map { "弹性篇幅：${it.message}" }
        if (text.isBlank()) issues += "本稿没有完整正文"
        if (Regex("(?m)^\\s*(?:#{1,6}\\s+|```|【(?:写作说明|创作说明|审读报告|修订说明)】|(?:分析|写作思路|审稿报告|修订说明)[：:])").containsMatchIn(text)) {
            issues += "正文混入分析、报告或 Markdown 标记，请分开保存"
        }
        if (Regex("\\*\\*[^*\\n]+\\*\\*").containsMatchIn(text)) issues += "正文含 Markdown 强调标记，请保留纯正文"
        if (text.trimStart().startsWith("{") && Regex("\"(?:issues|summary|missing|analysis)\"\\s*:").containsMatchIn(text)) issues += "本稿返回的是分析或审读数据，尚未得到完整正文"
        if (Regex("\\[(?:待补|待填写|此处填写|角色[AB甲乙])[^\\]]*]|TODO|（回答太长，后面被截断了。\uFF09|（模型中途停止了回答。\uFF09").containsMatchIn(text)) {
            issues += "本稿含占位说明或未完成标记，不能作为完整章节"
        }
        return issues.distinct() to notes.distinct()
    }
}

data class DraftWorkflowResult(val text: String, val assessment: DraftAssessment, val stage: DraftStage)

/** Three actual stages. Re-review after a revision; do not reuse the earlier draft's report. */
object DraftWorkflow {
    const val MAX_REVISIONS = 1

    suspend fun run(
        task: ChapterWritingTask,
        rules: RuleSet,
        initial: String? = null,
        create: suspend () -> String,
        review: suspend (String) -> String,
        revise: suspend (String, String) -> String,
        onStage: suspend (DraftStage, String, DraftAssessment?) -> Unit = { _, _, _ -> },
    ): DraftWorkflowResult {
        require(task.blocking.isEmpty()) { task.blocking.joinToString("；") }
        var text = initial?.takeIf { it.isNotBlank() } ?: run {
            onStage(DraftStage.CREATING, "", null)
            create().trim().also { require(it.isNotBlank()) { "AI 没有返回正文" } }
        }
        for (round in 0..MAX_REVISIONS) {
            onStage(DraftStage.CHECKING, text, null)
            val (local, notes) = DraftChecks.local(text, rules, task.targetLength)
            onStage(DraftStage.REVIEWING, text, null)
            val assessment = DraftChecks.parse(review(text), text, task.fingerprint).copy(localIssues = local, notes = notes, round = round)
            if (assessment.passed || assessment.missing.isNotEmpty() || round == MAX_REVISIONS) {
                val stage = if (assessment.passed) DraftStage.READY else DraftStage.NEEDS_REVIEW
                onStage(stage, text, assessment)
                return DraftWorkflowResult(text, assessment, stage)
            }
            onStage(DraftStage.REVISING, text, assessment)
            val revised = revise(text, assessment.render(requiredOnly = true)).trim()
            require(revised.isNotBlank()) { "修订未返回完整正文，原稿已保留" }
            if (revised == text) {
                onStage(DraftStage.NEEDS_REVIEW, text, assessment)
                return DraftWorkflowResult(text, assessment, DraftStage.NEEDS_REVIEW)
            }
            text = revised
        }
        error("无法完成本章流程")
    }
}
