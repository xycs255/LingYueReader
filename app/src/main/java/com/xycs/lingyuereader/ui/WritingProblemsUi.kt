package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.data.ChapterReview
import com.xycs.lingyuereader.data.ReviewGate
import com.xycs.lingyuereader.data.ReviewIssue
import com.xycs.lingyuereader.data.RuleHit
import com.xycs.lingyuereader.data.RuleReport
import top.yukonga.miuix.kmp.basic.Text

// ---------- 编辑器里的“问题”面板 ----------

/** 问题面板顶上的筛选：全部，和问题的四个来源。 */
internal enum class ProblemFilter(val label: String) {
    ALL("全部"),
    /** 设定包里检查规则的命中（本机算）。 */
    RULE("规则"),
    /** 内置 AI 腔清单和我的黑名单的命中（本机算），一处算一条。 */
    CLICHE("AI 腔"),
    /** 写作建议，和去 AI 腔给出的改法。 */
    SUGGEST("建议"),
    /** 本章最近一次三道审稿里还没处理的（事实关、写法关；规则关和“规则”重复，不再列）。 */
    REVIEW("审稿"),
}

/**
 * 问题面板里的一条。四个来源各自原有的数据，不另算一遍：规则、AI 腔来自本机检查的结果，
 * 建议来自写作建议的列表，审稿来自最近一次审稿结果（状态写回同一份，审稿页上看到的一样）。
 */
internal sealed interface Problem {
    val filter: ProblemFilter
    /** 列表里认这一条用的（正文一改位置就变，不拿位置认）。 */
    val key: Any
    /** 排序用：在正文里的位置；整章的（字数不够这类）排最前，找不到原句的排最后。 */
    val order: Int

    /** 一条检查规则：可能有几处，也可能没有位置（字数、对白占比这类整章的）。 */
    data class Rule(val hit: RuleHit) : Problem {
        override val filter get() = ProblemFilter.RULE
        override val key: Any get() = "rule" to hit.key
        override val order get() = hit.ranges.firstOrNull()?.first ?: -1
    }

    /** 一处 AI 腔（内置清单或我的黑名单）：[nth] 是这条规则的第几处。 */
    data class Cliche(val hit: RuleHit, val range: IntRange, val nth: Int) : Problem {
        override val filter get() = ProblemFilter.CLICHE
        override val key: Any get() = Triple("cliche", hit.key, nth)
        override val order get() = range.first
    }

    /** 一条写作建议（或去 AI 腔的改法）。[at] 是在正文里的位置，那段改掉了为空。 */
    data class Suggest(val issue: WriteIssue, val at: Int?) : Problem {
        override val filter get() = ProblemFilter.SUGGEST
        override val key: Any get() = "suggest\u0000${issue.paragraph}\u0000${issue.wrong}"
        override val order get() = at ?: Int.MAX_VALUE
    }

    /** 审稿里一处还没处理的问题。[at] 是原句在正文里的位置，找不到（改过了）为空。 */
    data class Review(val gate: ReviewGate, val issue: ReviewIssue, val at: Int?) : Problem {
        override val filter get() = ProblemFilter.REVIEW
        override val key: Any get() = listOf("review", gate, issue.quote, issue.problem)
        override val order get() = at ?: Int.MAX_VALUE
    }
}

/**
 * 本章所有的问题，按在正文里的先后排（同一处按规则、AI 腔、建议、审稿）。
 * [report] 是本机检查（检查规则加 AI 腔、黑名单）的结果；[suggestions] 是正文里显示着的写作建议；
 * [review] 是本章最近一次审稿，只取事实关、写法关里没处理的。
 */
internal fun collectProblems(text: String, report: RuleReport?, suggestions: List<WriteIssue>, review: ChapterReview?): List<Problem> {
    val out = mutableListOf<Problem>()
    report?.hits.orEmpty().forEach { h ->
        if (AiCliche.isVoice(h)) h.ranges.forEachIndexed { i, r -> out += Problem.Cliche(h, r, i) }
        else out += Problem.Rule(h)
    }
    val located = locateIssues(text, suggestions).associate { (r, i) -> i to r.first }
    suggestions.forEach { out += Problem.Suggest(it, located[it]) }
    review?.gates.orEmpty().filter { it.gate != ReviewGate.RULES }.forEach { g ->
        g.issues.filter { it.open }.forEach { i ->
            out += Problem.Review(g.gate, i, i.quote.takeIf { it.isNotBlank() }?.let { q -> text.indexOf(q).takeIf { it >= 0 } })
        }
    }
    // 列表按键认条目，键重复 LazyColumn 直接闪退：上面几个来源各自已经去了重，这里再兜一次（旧盘上的、以后新加的来源）。
    return out.distinctBy { it.key }.sortedWith(compareBy({ it.order }, { it.filter.ordinal }))
}

/** 每类几条（[ProblemFilter.ALL] 是合计）。 */
internal fun problemCounts(problems: List<Problem>): Map<ProblemFilter, Int> =
    ProblemFilter.entries.associateWith { f -> if (f == ProblemFilter.ALL) problems.size else problems.count { it.filter == f } }

/** 审稿问题“按这条让 AI 改”交给 AI 的话：问题和改法，只改这一句（审稿页和问题面板共用）。 */
internal fun reviewFixInstruction(issue: ReviewIssue): String =
    listOf(issue.problem, issue.fix).filter { it.isNotBlank() }.joinToString("。改法：", prefix = "审稿意见：") + "。只改这一句和必要的上下文。"

/** 审稿那两关的标签：事实关照写作建议“设定”一类的颜色和 ▲，写法关照“规范”一类的颜色和 ■（这两类原来就是查这些的）。 */
private fun ReviewGate.kind(): SuggestKind = if (this == ReviewGate.FACT) SuggestKind.CANON else SuggestKind.STYLE

/** 问题面板里各类能做的事：都是现有的操作，面板只是汇总的入口。 */
internal class ProblemActions(
    /** 规则、AI 腔：选中正文里的这一处（规则有几处时依次选下一处）。 */
    val jump: (RuleHit, IntRange?) -> Unit,
    /** AI 腔：这处原文加进我的黑名单。 */
    val ban: (String) -> Unit,
    /** 建议：改、忽略、这类不提。 */
    val fix: (WriteIssue) -> Unit,
    val ignore: (WriteIssue) -> Unit,
    val mute: (String) -> Unit,
    /** 审稿：去原文、标已改 / 不改、按这条让 AI 改。 */
    val jumpReview: (ReviewIssue) -> Unit,
    val markReview: (ReviewGate, ReviewIssue, String) -> Unit,
    val fixReview: ((ReviewGate, ReviewIssue) -> Unit)?,
)

/**
 * 编辑器里的“问题”面板：本章查出来的问题都列在这里——检查规则、AI 腔、写作建议（含去 AI 腔的改法）、
 * 审稿还没处理的。顶上按来源筛选（带条数，没有的类不显示），每条带类别标签、原文和说明，以及这一类原有的操作。
 * 正在查写作建议、去 AI 腔，没查成，也写在这里。正文里的标记和光标落上去时的建议条照旧，这里是汇总。
 */
@Composable
internal fun ProblemsPanel(
    problems: List<Problem>,
    filter: ProblemFilter,
    onFilter: (ProblemFilter) -> Unit,
    text: String,
    report: RuleReport?,
    hasRules: Boolean,
    realtime: RealtimeState,
    realtimeOn: Boolean,
    review: ChapterReview?,
    actions: ProblemActions,
    onClose: () -> Unit,
    /** 有 AI 腔、黑名单命中、章没定稿时：一键去 AI 腔。 */
    onCliche: (() -> Unit)?,
    onOpenRules: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val counts = problemCounts(problems)
    // 没有的类不显示；正在看的那类改完了（0 条）也留着，免得筛选一下跳走。
    val shown = ProblemFilter.entries.filter { it == ProblemFilter.ALL || it == filter || (counts[it] ?: 0) > 0 }
    val list = if (filter == ProblemFilter.ALL) problems else problems.filter { it.filter == filter }
    // 底下一排：全部、AI 腔下“去 AI 腔”（复用一键去 AI 腔），规则下“管理规则”，审稿下去审稿页（重新审、看全部）。
    val cliche = onCliche?.takeIf { filter == ProblemFilter.ALL || filter == ProblemFilter.CLICHE }
    val hasActions = cliche != null || filter == ProblemFilter.RULE || filter == ProblemFilter.REVIEW
    InlinePanel(
        "问题",
        onClose = onClose,
        closeLabel = "收起",
        actions = if (!hasActions) null else {
            {
                if (cliche != null) PillButton("去 AI 腔", cliche, style = PillStyle.Filled)
                if (filter == ProblemFilter.RULE) PillButton("管理规则", onOpenRules)
                if (filter == ProblemFilter.REVIEW) PillButton("三道审稿", onOpenReview)
            }
        },
    ) {
        // 正在查、没查成：写作建议和去 AI 腔的状态（写作建议关着时只有去 AI 腔的）。
        val line = Modifier.padding(vertical = 4.dp)
        when {
            realtime.cliche -> PanelBusy("正在去 AI 腔……", line)
            realtime.clicheError != null -> ErrorText("去 AI 腔没成：${realtime.clicheError}", line)
            realtime.clicheNote != null && realtime.issues.none { it.kind == SuggestKind.CLICHE.name } ->
                Text(realtime.clicheNote, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = line)
        }
        if (realtimeOn) {
            when {
                realtime.checking -> PanelBusy("正在看刚写的段落……", line)
                realtime.error != null -> ErrorText("写作建议没查成：${realtime.error}", line)
                realtime.waiting != null -> Text(realtime.waiting, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = line)
            }
        }
        if (shown.size > 2 || filter != ProblemFilter.ALL) {
            SegmentedControl(
                shown.map { f -> f to "${f.label} ${counts[f] ?: 0}" },
                filter,
                modifier = Modifier.padding(bottom = 2.dp),
                onSelect = onFilter,
            )
        }
        // 各筛选下的一行说明：规则下是本章统计，审稿下是哪一次审的。
        if (filter == ProblemFilter.RULE && report != null) {
            Text(statsLine(report.stats), color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        if (filter == ProblemFilter.REVIEW) {
            Text(
                review?.let { "上次审稿：${whenLabel(it.at)} · 事实关、写法关没处理的" } ?: "还没有审过：三道审稿审完，没处理的问题列在这里",
                color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        AnimatedContent(list.isEmpty(), transitionSpec = { Motion.fadeThrough() using null }, label = "problemsEmpty") { empty ->
            if (empty) {
                val note = when {
                    filter == ProblemFilter.RULE && !hasRules ->
                        "还没有检查规则。在设定包里写一份“检查规则”，比如篇幅、口头禅每章至多几次、禁用词，灵阅就能逐章检查。"
                    filter == ProblemFilter.RULE && report == null -> null
                    filter == ProblemFilter.REVIEW && review == null -> null
                    problems.isEmpty() -> "没有问题。"
                    else -> "这一类都处理了。"
                }
                if (note == null && filter == ProblemFilter.RULE) PanelBusy("正在检查……", Modifier.padding(vertical = 8.dp))
                else if (note != null) {
                    Text(note, color = c.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(list, key = { it.key }) { p ->
                        ProblemRow(p, text, actions, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()))
                    }
                }
            }
        }
    }
}

/** 问题面板的一条：类别标签和原文（或改法）一行，下面灰色的说明，再下面这一类的几个操作。 */
@Composable
private fun ProblemRow(p: Problem, text: String, actions: ProblemActions, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    fun quote(r: IntRange?): String? = r?.takeIf { it.first >= 0 && it.last < text.length }?.let { text.substring(it.first, it.last + 1) }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (p) {
                is Problem.Rule -> Tag("规则", c.warning)
                is Problem.Cliche -> Tag(if (AiCliche.isBanned(p.hit)) AiCliche.BANNED_DOC else "AI 腔", suggestColor(SuggestKind.CLICHE.name, c))
                is Problem.Suggest -> SuggestTag(p.issue.kind, c)
                is Problem.Review -> {
                    val k = p.gate.kind()
                    Tag("${suggestShape(k.name)} ${p.gate.label}", suggestColor(k.name, c), Modifier.semantics { contentDescription = "审稿${p.gate.label}" })
                }
            }
            val head: AnnotatedString = when (p) {
                is Problem.Rule -> AnnotatedString(p.hit.message)
                is Problem.Cliche -> AnnotatedString(quote(p.range)?.let { "「$it」" } ?: p.hit.message)
                is Problem.Suggest -> buildAnnotatedString {
                    withStyle(SpanStyle(color = c.destructive, textDecoration = TextDecoration.LineThrough)) { append(p.issue.wrong) }
                    append("  →  ")
                    withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.Medium)) { append(p.issue.right) }
                }
                is Problem.Review -> AnnotatedString(p.issue.problem)
            }
            Text(head, color = c.label, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        val detail = when (p) {
            // 规则的说明里已经带着原文（“「没抬头」1 处，不用”）时不再重复。
            is Problem.Rule -> p.hit.ranges.firstOrNull()?.let(::quote)?.takeIf { "「$it」" !in p.hit.message }
                ?.let { "「$it」" + if (p.hit.ranges.size > 1) " 等 ${p.hit.ranges.size} 处" else "" }
            is Problem.Cliche -> p.hit.message.takeIf { quote(p.range) != null }
            is Problem.Suggest -> {
                val i = p.issue
                val at = i.paragraph.indexOf(i.wrong).coerceAtLeast(0)
                val snippet = i.paragraph.substring((at - 12).coerceAtLeast(0), (at + i.wrong.length + 12).coerceAtMost(i.paragraph.length))
                listOfNotNull(i.reason.takeIf { it.isNotBlank() }, i.source.takeIf { it.isNotBlank() }?.let { "依据：$it" }, "……$snippet……").joinToString("  ")
            }
            is Problem.Review -> listOfNotNull(
                p.issue.quote.takeIf { it.isNotBlank() }?.let { "「$it」" + if (p.at == null) "（正文里没找到，可能改过了）" else "" },
                p.issue.fix.takeIf { it.isNotBlank() }?.let { "改：$it" },
            ).joinToString("  ").ifBlank { null }
        }
        if (detail != null) {
            Text(detail, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (p) {
                is Problem.Rule -> if (p.hit.ranges.isNotEmpty()) PillButton("跳到位置", { actions.jump(p.hit, null) })
                is Problem.Cliche -> {
                    PillButton("跳到位置", { actions.jump(p.hit, p.range) })
                    // 内置清单的命中才能加黑名单（黑名单里的已经在了）；原文 2–20 字。
                    val phrase = quote(p.range)?.takeIf { p.hit.rule.source.doc == AiCliche.DOC }?.let(AiCliche::bannable)
                    if (phrase != null) PillButton("以后都不要", { actions.ban(phrase) })
                }
                is Problem.Suggest -> {
                    PillButton("改", { actions.fix(p.issue) }, style = PillStyle.Filled)
                    PillButton("忽略", { actions.ignore(p.issue) })
                    // 去 AI 腔是作者点了才查的，不在设置里开关，没有“这类不提”。
                    val k = SuggestKind.of(p.issue.kind)
                    if (k.realtime) PillButton("${k.label}类不提", { actions.mute(p.issue.kind) })
                }
                is Problem.Review -> {
                    if (p.issue.quote.isNotBlank()) PillButton("跳到原文", { actions.jumpReview(p.issue) })
                    PillButton("已改", { actions.markReview(p.gate, p.issue, ReviewIssue.FIXED) })
                    PillButton("不改", { actions.markReview(p.gate, p.issue, ReviewIssue.IGNORED) })
                    val ai = actions.fixReview
                    if (ai != null && p.issue.quote.isNotBlank()) PillButton("按这条让 AI 改", { ai(p.gate, p.issue) }, style = PillStyle.Tinted)
                }
            }
        }
    }
}
