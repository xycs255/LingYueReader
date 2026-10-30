package com.xycs.lingyuereader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.TextDiff
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.data.BibleEntry
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.beats
import com.xycs.lingyuereader.data.BibleKind
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ---------- 编辑器里的 AI ----------

/** 编辑器 AI 操作条上的一项。 */
internal sealed interface AiBarItem {
    val label: String

    data class Edit(val action: WriteAction) : AiBarItem {
        override val label get() = action.label
    }

    data object Custom : AiBarItem {
        override val label = "按要求改…"
    }

    /** 打开“AI 写”面板：在光标处写（先显示、采纳才进正文）。 */
    data object Write : AiBarItem {
        override val label = "AI 写…"
    }

    data object Review : AiBarItem {
        override val label = "读者点评"
    }

    /** 本章有 AI 腔、黑名单命中时才出现：把标出的段落发给 AI，只改那几处。 */
    data object Cliche : AiBarItem {
        override val label = "去 AI 腔"
    }
}

internal val AI_BAR = listOf(
    AiBarItem.Write,
    AiBarItem.Edit(WriteAction.POLISH),
    AiBarItem.Edit(WriteAction.VIVID),
    AiBarItem.Edit(WriteAction.EXPAND),
    AiBarItem.Edit(WriteAction.CONDENSE),
    AiBarItem.Edit(WriteAction.PROOFREAD),
    AiBarItem.Custom,
    AiBarItem.Review,
)

/**
 * 改哪段：选中了文字就改选中的；没选中就改光标所在的那一段（续写是在光标处接着写）。
 * 返回空表示没什么可改（光标在空行上）。
 */
internal fun editTarget(text: String, selection: TextRange, action: WriteAction, instruction: String = ""): WriteAiTask.Edit? {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    if (action == WriteAction.CONTINUE) {
        if (text.substring(0, start).isBlank()) return null
        // A continuation has no original span to relocate after an edit. Keep the whole
        // source snapshot so acceptance cannot silently insert at a stale absolute offset.
        return WriteAiTask.Edit(action, "", start, start, instruction, continuationBase = text)
    }
    if (end > start) {
        val picked = text.substring(start, end)
        if (picked.isBlank()) return null
        return WriteAiTask.Edit(action, picked, start, end, instruction)
    }
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) 0 else if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    if (line.isBlank()) return null
    return WriteAiTask.Edit(action, line, lineStart, lineEnd, instruction)
}

/** 采纳改稿的结果：换好了、原文找不到了、原位置附近有不止一处一样的文字（不猜，不换）。 */
internal enum class EditApply { DONE, MISSING, AMBIGUOUS }

/** 原文不在原来的位置上时，只在原位置前后这么多字里找。 */
private const val EDIT_NEAR = 1_000

/**
 * 采纳：把 [result] 换进正文。位置上的文字还是原文就按位置换；正文改过了（等结果时作者在前面加了字），
 * 只在原位置附近找原文：找到一处才换，几处一样的不换（可能在几页之前，换错了不容易发现）。
 * 续写只在正文仍与生成时一致时插在光标处；任何变化都需重新生成，以免误插到旧坐标。
 * 光标在一段中间或段末时另起一段。
 */
internal fun applyEdit(state: TextFieldState, task: WriteAiTask.Edit, result: String): EditApply {
    val text = state.text.toString()
    if (task.action == WriteAction.CONTINUE) {
        if (task.continuationBase != text || task.start !in 0..text.length || task.end != task.start) {
            return EditApply.MISSING
        }
        val at = task.start
        val needBreak = at > 0 && text[at - 1] != '\n'
        val needAfter = at < text.length && text[at] != '\n'
        val insert = (if (needBreak) "\n" else "") + result + (if (needAfter) "\n" else "")
        state.edit {
            replace(at, at, insert)
            selection = TextRange(at + insert.length - if (needAfter) 1 else 0)
        }
        return EditApply.DONE
    }
    val start = if (task.end <= text.length && text.substring(task.start, task.end) == task.original) {
        task.start
    } else {
        val from = (task.start - EDIT_NEAR).coerceAtLeast(0)
        val to = (task.end + EDIT_NEAR).coerceAtMost(text.length)
        val hits = generateSequence(text.indexOf(task.original, from)) { text.indexOf(task.original, it + 1) }
            .takeWhile { it >= 0 && it + task.original.length <= to }.take(2).toList()
        when (hits.size) {
            0 -> return EditApply.MISSING
            1 -> hits[0]
            else -> return EditApply.AMBIGUOUS
        }
    }
    state.edit {
        replace(start, start + task.original.length, result)
        selection = TextRange(start + result.length)
    }
    return EditApply.DONE
}

/** 采纳一条写作建议：在那段里把错的换成对的。段落已经改掉时返回假。 */
internal fun applyIssue(state: TextFieldState, issue: WriteIssue): Boolean {
    val text = state.text.toString()
    val p = text.indexOf(issue.paragraph)
    if (p < 0) return false
    val w = issue.paragraph.indexOf(issue.wrong)
    if (w < 0) return false
    state.edit { replace(p + w, p + w + issue.wrong.length, issue.right) }
    return true
}

/** 对比：删掉的红色划线，加上的绿底。 */
internal fun diffText(pieces: List<TextDiff.Piece>, removed: Color, added: Color): AnnotatedString = buildAnnotatedString {
    pieces.forEach { p ->
        when (p.kind) {
            TextDiff.Kind.SAME -> append(p.text)
            TextDiff.Kind.DELETED -> withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(p.text) }
            TextDiff.Kind.INSERTED -> withStyle(SpanStyle(background = added.copy(alpha = 0.22f), color = added)) { append(p.text) }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("灵阅", text))
}

/** 面板里的一行“正在……”：行内小转圈加次要色的字（减少动效时转圈换成不动的三个点）。 */
@Composable
internal fun PanelBusy(text: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner()
        Text(text, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
    }
}

/**
 * 写作助手的结果面板：改稿类先显示对比（删的划掉、加的标绿），可以切到“改后”看全文；
 * 采纳才改稿。灵感、点评只给人看，灵感可以插到光标处。
 * 流式输出时跟着滚到底；“正在想”和结果、对比和改后之间原地淡过去（高矮由面板平滑跟上）。
 */
@Composable
internal fun WriteAiPanel(
    state: WriteAiState,
    model: String,
    onAccept: (String) -> Unit,
    /** AI 正在查设定包的什么（查完为空）。 */
    lookup: String? = null,
    onInsert: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val context = LocalContext.current
    val task = state.task
    var showDiff by remember(task) { mutableStateOf(true) }
    val edit = task as? WriteAiTask.Edit
    val canDiff = edit != null && edit.original.isNotEmpty() && !state.loading && state.error == null
    // 对比在后台算，同样的原文和结果只算一次（长段落比一次要一会儿，不能卡住界面）；算好之前先显示改后的全文。
    val original = edit?.original.orEmpty()
    val removed = c.destructive
    val added = c.success
    val compared by produceState<AnnotatedString?>(null, canDiff, original, state.text, removed, added) {
        value = null
        if (!canDiff) return@produceState
        val pieces = withContext(Dispatchers.Default) { TextDiff.diff(original, state.text) }
        value = pieces?.let { diffText(it, removed, added) }
    }
    val done = !state.loading && state.error == null && state.text.isNotBlank()
    InlinePanel(
        title = task.title,
        onClose = onClose,
        icon = LingYueIcons.Sparkle,
        status = if (state.loading) lookup?.let { "正在查：$it" } ?: "生成中……" else "$model 生成，可能有误",
        closeLabel = "关闭 AI 面板",
        actions = {
            if (edit != null) {
                PillButton(if (edit.action == WriteAction.CONTINUE) "接在后面" else "采纳", { onAccept(state.text) }, style = PillStyle.Filled, enabled = done)
            } else if (task is WriteAiTask.Idea) {
                PillButton("插到光标处", { onInsert(state.text) }, style = PillStyle.Filled, enabled = done)
            }
            PillButton("复制", { copy(context, state.text) }, enabled = done)
            PillButton("再来一次", onRetry, enabled = !state.loading)
            PillButton(if (edit != null) "不用了" else "关闭", onClose)
        },
    ) {
        AnimatedVisibility(canDiff, enter = Effects.enter(fadeIn(Motion.standard(Motion.SHORT))), exit = Effects.exit(fadeOut(Motion.exit()))) {
            Row(Modifier.padding(vertical = 4.dp)) {
                SegmentedControl(listOf(true to "对比", false to "改后"), showDiff) { showDiff = it }
            }
        }
        val scroll = rememberScrollState()
        // 结果长出来时跟着滚到底；作者往上翻着看时不打扰。减少动效时直接跳到底。
        LaunchedEffect(state.text.length) {
            if (state.loading && scroll.maxValue - scroll.value < 600) {
                if (Effects.noMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
            }
        }
        val waiting = state.loading && state.text.isEmpty()
        // 高矮交给面板去平滑，这里只淡入淡出（两层都量高矮会一前一后地拖）。
        AnimatedContent(waiting, transitionSpec = { Motion.fadeThrough() using null }, label = "aiWaiting") { w ->
            if (w) {
                PanelBusy("正在想……", Modifier.padding(vertical = 10.dp))
            } else {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(scroll).padding(vertical = 6.dp)) {
                    if (state.text.isNotEmpty()) {
                        val diffShown = canDiff && showDiff && compared != null
                        AnimatedContent(diffShown, transitionSpec = { Motion.fadeThrough() using null }, label = "aiDiff") { d ->
                            val body = compared?.takeIf { d } ?: AnnotatedString(state.text)
                            Text(body, color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                        }
                    }
                    state.error?.let { ErrorText(it, Modifier.padding(top = 6.dp)) }
                    if (canDiff && state.text.trim() == edit!!.original.trim()) {
                        Text("AI 觉得这段不用改。", color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.padding(top = 6.dp))
                    }
                    UsageNote(state.usage, state.cost, Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** 建议在正文里的位置：找到那一段，再在段里找那几个字。找不到的（段已经改了）不标。 */
internal fun locateIssues(text: String, issues: List<WriteIssue>): List<Pair<IntRange, WriteIssue>> = issues.mapNotNull { i ->
    val p = text.indexOf(i.paragraph).takeIf { it >= 0 } ?: return@mapNotNull null
    val w = i.paragraph.indexOf(i.wrong).takeIf { it >= 0 } ?: return@mapNotNull null
    val start = p + w
    (start until start + i.wrong.length) to i
}

/** 口吻类建议的基准色：紫，离红（删除）、橙（提醒）都远；和强调色撞了再转开。 */
private val VoiceLight = Color(0xFF7B4FA6)
private val VoiceDark = Color(0xFFB89BE0)

/** AI 腔的基准色：青，离红、橙、紫都远；和强调色、另外几类撞了再转开。 */
private val ClicheLight = Color(0xFF00796B)
private val ClicheDark = Color(0xFF5CC8BA)

/**
 * 各类建议的颜色，从语义色和强调色来，四类分得开：错字用删除色、设定用提醒色、规范用强调色、口吻用紫。
 * 删除色、提醒色在配色里已经和强调色转开了（[distinctFrom]），口吻再和这三种转开，两两色差都 ≥ [SEMANTIC_MIN_DELTA]。
 * 都调到卡片底和页面底上读得清（≥ 4.5:1），深色、Monet、自选强调色下也一样。颜色之外还有 [suggestShape] 和文字标签。
 */
internal fun suggestColor(kind: String, c: GroupedColors): Color = suggestPalette(c).getValue(SuggestKind.of(kind))

/**
 * 一套配色下五类建议的颜色，按配色记住（只跟主题有关）：每打一个字，正文标记、问题面板的每一行都要取一次，
 * 现算一次口吻、AI 腔要试几十个色相、每个都算色差和对比度，自选青色、紫色强调色时一次上百微秒。
 */
internal fun suggestPalette(c: GroupedColors): Map<SuggestKind, Color> =
    synchronized(suggestPalettes) { suggestPalettes[c] } ?: computeSuggestPalette(c).also { fresh ->
        synchronized(suggestPalettes) { suggestPalettes[c] = fresh }
    }

/** 最近用过的几套配色（浅色、深色、阅读面板、换强调色时的前后两套）。 */
private val suggestPalettes = object : LinkedHashMap<GroupedColors, Map<SuggestKind, Color>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GroupedColors, Map<SuggestKind, Color>>?) = size > 8
}

private fun computeSuggestPalette(c: GroupedColors): Map<SuggestKind, Color> {
    val cards = listOf(c.cell, c.background)
    val voice = voiceColor(c, cards)
    return SuggestKind.entries.associateWith { k ->
        when (k) {
            SuggestKind.TYPO -> c.destructive
            SuggestKind.CANON -> c.warning
            SuggestKind.STYLE -> c.tint
            SuggestKind.VOICE -> voice
            SuggestKind.CLICHE -> (if (c.dark) ClicheDark else ClicheLight).distinctFrom(listOf(c.tint, c.destructive, c.warning, voice), cards)
        }.readableOn(cards)
    }
}

private fun voiceColor(c: GroupedColors, cards: List<Color>): Color =
    (if (c.dark) VoiceDark else VoiceLight).distinctFrom(listOf(c.tint, c.destructive, c.warning), cards)

/** 各类建议的形状：标签上颜色之外再带一个形状，色弱、灰阶的墨水屏上也分得出是哪类。 */
internal fun suggestShape(kind: String): String = when (SuggestKind.of(kind)) {
    SuggestKind.TYPO -> "●"
    SuggestKind.CANON -> "▲"
    SuggestKind.STYLE -> "■"
    SuggestKind.VOICE -> "◆"
    SuggestKind.CLICHE -> "★"
}

/** 正文里一类建议的划线线形：颜色之外再分线形，色弱、灰阶的墨水屏上也分得出（静止的线，减少动效时也一样）。 */
internal enum class SuggestLine(val label: String) {
    WAVY("波浪线"),
    DOUBLE("双线"),
    DASHED("虚线"),
    DOTTED("点线"),
    DASH_DOT("点划线"),
}

/** 各类建议在正文里的划线：错字波浪线、设定双线、规范虚线、口吻点线、AI 腔点划线（和标签上的 ●▲■◆★ 一一对应）。 */
internal fun suggestLine(kind: String): SuggestLine = when (SuggestKind.of(kind)) {
    SuggestKind.TYPO -> SuggestLine.WAVY
    SuggestKind.CANON -> SuggestLine.DOUBLE
    SuggestKind.STYLE -> SuggestLine.DASHED
    SuggestKind.VOICE -> SuggestLine.DOTTED
    SuggestKind.CLICHE -> SuggestLine.DASH_DOT
}

/** 在 [y]（基线下方）从 [fromX] 到 [toX] 按 [line] 画一道划线；线宽、间距按 dp 算，各密度下看起来一样。 */
internal fun DrawScope.drawSuggestLine(line: SuggestLine, color: Color, fromX: Float, toX: Float, y: Float) {
    if (toX <= fromX) return
    val stroke = 1.5.dp.toPx()
    when (line) {
        SuggestLine.WAVY -> {
            // 半个波长 3dp、上下各 1.5dp：一眼看得出是波浪，不像粗线。
            val half = 3.dp.toPx()
            val amp = 1.5.dp.toPx()
            val path = Path().apply {
                moveTo(fromX, y)
                var x = fromX
                var up = true
                while (x < toX) {
                    val next = minOf(x + half, toX)
                    quadraticTo((x + next) / 2f, if (up) y - amp * 2f else y + amp * 2f, next, y)
                    x = next
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = 1.2.dp.toPx()))
        }
        SuggestLine.DOUBLE -> {
            val w = 1.dp.toPx()
            drawLine(color, Offset(fromX, y - 1.5.dp.toPx()), Offset(toX, y - 1.5.dp.toPx()), strokeWidth = w)
            drawLine(color, Offset(fromX, y + 1.5.dp.toPx()), Offset(toX, y + 1.5.dp.toPx()), strokeWidth = w)
        }
        SuggestLine.DASHED -> drawLine(
            color, Offset(fromX, y), Offset(toX, y), strokeWidth = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx())),
        )
        // 圆头、长度近乎为零的“段”就是一颗颗圆点。
        SuggestLine.DOTTED -> drawLine(
            color, Offset(fromX + stroke, y), Offset(toX, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 4.dp.toPx())),
        )
        // 一长划一圆点：长划和虚线一样长，点是圆头、近乎为零长的一段。
        SuggestLine.DASH_DOT -> drawLine(
            color, Offset(fromX, y), Offset(toX, y), strokeWidth = stroke, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx(), 0.01f, 3.dp.toPx())),
        )
    }
}

/** 一类建议的标签：形状 + 名字，用这类的颜色；读屏只念“某类建议”，不念形状。 */
@Composable
internal fun SuggestTag(kind: String, c: GroupedColors, modifier: Modifier = Modifier) {
    val k = SuggestKind.of(kind)
    Tag("${suggestShape(kind)} ${k.label}", suggestColor(kind, c), modifier.clearAndSetSemantics { contentDescription = "${k.label}类建议" })
}

/**
 * AI 在编辑器里写：键盘上方一条——在写（或在查设定）时能停，写完能采纳、重写、撤回。
 * 写的字显示在正文的光标处（浅色），采纳前不算正文。在写、写好、出错之间原地淡过去；写的时候带一个小转圈。
 * 出几个方向的版本时，上面一排分段控件切换各版（每段带“写着、写好、出错”）；采纳、重写对选中的那一版，
 * 别的版还在写时也能“停止”，撤回几版都不要；花费是几版合计。
 */
@Composable
internal fun InlineBar(
    inline: InlineWrite,
    lookup: String?,
    onStop: () -> Unit,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    /** AI 写的字里有几处 AI 腔、黑名单命中（本机算的）；只提醒，不拦采纳。出几个版本时按选中的那一版算。 */
    cliche: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    val c = LocalGroupedColors.current
    val phase = when {
        inline.error != null -> 2
        inline.loading -> 0
        else -> 1
    }
    InlinePanel(
        title = "AI 续写",
        onClose = null,
        icon = LingYueIcons.Sparkle,
        actions = {
            if (inline.loading) {
                PillButton("停止", onStop)
            } else {
                PillButton("采纳", onAccept, style = PillStyle.Filled,
                    enabled = inline.text.isNotBlank() && inline.error == null &&
                        (inline.referenceAnalysisId == null || inline.referenceValidated))
                PillButton("重写", onRetry)
                // 出几个版本时这一版写好了、别的版还在写：也能停下它们。
                if (inline.anyLoading) PillButton("停止", onStop)
            }
            PillButton("撤回", onDiscard)
        },
    ) {
        if (inline.variants.isNotEmpty()) {
            SegmentedControl(
                inline.variants.mapIndexed { i, v -> i to "${v.label}·${variantStatus(v)}" },
                inline.selected,
                modifier = Modifier.padding(bottom = 4.dp),
                onSelect = onSelect,
            )
        }
        AnimatedContent(phase, transitionSpec = { Motion.fadeThrough() using null }, label = "inlinePhase") { p ->
            val words = WordCount.of(inline.text)
            val line = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            when (p) {
                // 出错也照常显示花了多少（比如上限全用在思考上、一个字没写，钱照样花了）。
                2 -> Column(line) {
                    ErrorText(inline.error.orEmpty())
                    UsageNote(inline.usage, inline.cost, Modifier.padding(top = 2.dp))
                }
                0 -> PanelBusy(
                    when {
                        lookup != null -> "正在查：$lookup"
                        words == 0 -> "AI 正在想……"
                        else -> "AI 正在写 · $words 字"
                    },
                    line,
                )
                else -> Column(line) {
                    Text(
                        "AI 写了 $words 字（浅色部分），采纳才放进正文" + (inline.note?.let { "；$it" } ?: "") +
                            (if (inline.referenceAnalysisId != null && !inline.referenceValidated) "；范本核验未完成，请重写" else ""),
                        color = c.label,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                    )
                    if (cliche > 0) {
                        Text("这段有 $cliche 处 AI 腔", color = c.warning, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine, modifier = Modifier.padding(top = 2.dp))
                    }
                    UsageNote(inline.usage, inline.cost, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/**
 * 写完以后的一行小字：“约 ¥0.003 · 缓存命中 92%”（没有单价时写 token 数）。次要色、12sp，不打扰；
 * 没有用量（还在写、本机模型）时什么也不显示。
 */
@Composable
internal fun UsageNote(usage: com.xycs.lingyuereader.ai.AiUsage?, cost: com.xycs.lingyuereader.ai.AiCost?, modifier: Modifier = Modifier) {
    val text = usage?.let { com.xycs.lingyuereader.ai.AiPricing.brief(it, cost ?: com.xycs.lingyuereader.ai.AiCost()) } ?: return
    Text(text, color = LocalGroupedColors.current.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** 出几个版本时一版的状态，跟在分段控件的方向名后面。 */
private fun variantStatus(v: com.xycs.lingyuereader.InlineVariant): String = when {
    v.error != null -> "出错"
    v.loading -> "写着"
    else -> "写好"
}

/** 作品 AI 设置“写正文时先思考”的说明。 */
internal const val THINKING_NOTE = "关着：更快、更省，‘写正文的温度’才起作用。开着：AI 先想再写，贵、慢，适合复杂场面。目前只对官方 DeepSeek V4 起作用。"

/** 出 3 个方向的版本：选项后面的说明。 */
internal const val VARIANTS_NOTE = "冲突升级、按下不发、意外插入各写一版，你挑一版采纳；花费约 3 倍"

/** “AI 写”面板选好的写法：写第几拍（[InlineMode.BEAT]）或写多少、出不出 3 个方向、照不照范本写。 */
internal data class AiWriteChoice(val mode: InlineMode, val beat: Int? = null, val variants: Boolean = false, val reference: Boolean = false)

/** 出 3 个方向的版本只对“写这一拍”“写下一场”有效：写到本章结尾、按字数、按要求写篇幅不定，三版对不齐。 */
internal fun InlineMode.allowsVariants(): Boolean = this == InlineMode.BEAT || this == InlineMode.SCENE

/** “写多少”一节的几项（“写这一拍”另列在上面的节拍里）。 */
private val AI_WRITE_MODES = listOf(InlineMode.SCENE, InlineMode.TO_END, InlineMode.WORDS, InlineMode.CUSTOM)

/**
 * “AI 写”面板：让 AI 在光标处写正文只有这一个入口。从上到下：本章细纲卡有节拍时先列“写这一拍”（已写的打勾、淡一点，
 * 默认选第一拍没写的），再是“写多少”，然后“出 3 个方向的版本”（按作品记住，只对写这一拍、写下一场有效），
 * 书灵里选了范本时最后一行“照范本写法”（默认开）。节拍和写法是一组单选，选好点下面的按钮才写。
 */
@Composable
internal fun AiWriteDialog(
    /** 本章细纲卡；没有或没有节拍时不显示“写这一拍”。 */
    card: ChapterCard?,
    /** 上次勾没勾“出 3 个方向的版本”（按作品记住），[onVariants] 在勾、取消时记下来。 */
    variants: Boolean,
    onVariants: (Boolean) -> Unit,
    /** 书灵里选的范本名；空时不显示范本那一行。 */
    reference: String?,
    useReference: Boolean,
    onUseReference: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** 本章还没有节拍时“先拆节拍”：去细纲卡拆（拆好的给作者看过再用）。空时不显示。 */
    onSplitBeats: (() -> Unit)? = null,
    onPick: (AiWriteChoice) -> Unit,
) {
    val list = card?.let(::beats).orEmpty()
    val done = card?.beatsDone.orEmpty().toSet()
    // 选中的是哪一拍（模式为 BEAT）或哪种写法；有没写的拍先选它。
    var chosen by remember(card) {
        mutableStateOf(card?.takeIf { list.isNotEmpty() }?.let { Beats.firstUndone(it) }?.let { InlineMode.BEAT to it } ?: (InlineMode.SCENE to null))
    }
    var several by remember { mutableStateOf(variants) }
    val (mode, beat) = chosen
    GlassDialog(show = true, title = "AI 写", summary = "从光标处往下写，带着设定包、细纲卡和写作规范；写的字先显示，采纳才放进正文", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (list.isNotEmpty()) {
                DialogSectionLabel("写这一拍")
                list.forEach { b ->
                    MarkedOptionRow(
                        "${b.index}. ${b.text}" + (b.length?.let { "（约 $it 字）" } ?: ""),
                        selected = mode == InlineMode.BEAT && beat == b.index,
                        done = b.index in done,
                        doneLabel = "已写",
                    ) { chosen = InlineMode.BEAT to b.index }
                }
            }
            // 还没有节拍：给一个“先拆节拍”，拆好以后这里就能一拍一拍写；不拆也照样按下面的写法写。
            if (list.isEmpty() && onSplitBeats != null) {
                DialogSectionLabel("写这一拍")
                ActionRow("本章还没有节拍 · 先拆节拍") { onSplitBeats() }
            }
            DialogSectionLabel("写多少")
            AI_WRITE_MODES.forEach { m ->
                MarkedOptionRow(m.label, selected = mode == m, done = false, doneLabel = "") { chosen = m to null }
            }
            // 关键的一段：按三个方向各写一版，作者挑。默认不勾，勾了按作品记住。
            CheckboxRow(
                "出 3 个方向的版本（花费约 3 倍）", several,
                subtitle = if (mode.allowsVariants()) VARIANTS_NOTE.substringBefore("；") else "只对“写这一拍”“写下一场”有效",
                enabled = mode.allowsVariants(), dimChecked = false,
            ) { on ->
                several = on
                onVariants(on)
            }
            if (reference != null) {
                ToggleRow("照范本写法：$reference", useReference, subtitle = "只借叙事和对白的手法，写完核验没有照抄原文") { onUseReference(it) }
            }
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                onPick(AiWriteChoice(mode, beat, several && mode.allowsVariants(), reference != null && useReference))
            },
            // 写法选项的名字已经在上面，按钮不再重复一遍（读屏、找按钮都不混）。
            confirmText = if (mode == InlineMode.BEAT) "写第 $beat 拍" else "开始写",
        )
    }
}

/**
 * 光标落在一条建议上：键盘上方显示这条——哪类、原因、依据、改成什么；可以采纳、不改、这类以后不提。
 * 光标挪到另一条建议上时面板不动，里面的字原地淡过去。
 */
@Composable
internal fun SuggestionBar(issue: WriteIssue, onFix: () -> Unit, onIgnore: () -> Unit, onMute: (() -> Unit)?) {
    val c = LocalGroupedColors.current
    val kind = SuggestKind.of(issue.kind)
    InlinePanel(
        title = "写作建议",
        onClose = null,
        icon = LingYueIcons.Sparkle,
        actions = {
            PillButton("采纳", onFix, style = PillStyle.Filled)
            PillButton("不改", onIgnore)
            // 去 AI 腔是作者点了才查的，不在设置里开关，没有“这类不提”。
            if (onMute != null && kind.realtime) PillButton("${kind.label}类不提", onMute)
        },
    ) {
        AnimatedContent(issue, transitionSpec = { Motion.fadeThrough() using null }, label = "suggestion") { shown ->
            Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuggestTag(shown.kind, c)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = c.destructive, textDecoration = TextDecoration.LineThrough)) { append(shown.wrong) }
                            append("  →  ")
                            withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.Medium)) { append(shown.right) }
                        },
                        color = c.label,
                        fontSize = TypeScale.Body,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                val why = listOf(shown.reason, shown.source.takeIf { it.isNotBlank() }?.let { "依据：$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  ")
                if (why.isNotBlank()) Text(why, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * 光标落在一处不合检查规则的地方：说是哪条规则（本机检查，不花钱）。[tag] 是来源：作者的“规则”，
 * 或者“AI 腔”（内置清单）、“我的黑名单”。AI 腔那几处带 [onBan]：“以后都不要”，把这处原文加进黑名单。
 */
@Composable
internal fun RuleNote(text: String, tag: String = "规则", onBan: (() -> Unit)? = null, onOpen: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().background(c.background).clickable { onOpen() }.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tag(tag, if (tag == "规则") c.warning else suggestColor(SuggestKind.CLICHE.name, c))
        Text(text, color = c.label, fontSize = TypeScale.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (onBan != null) PillButton("以后都不要", onBan, modifier = Modifier.padding(start = 8.dp))
        else Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** 输入一句话的对话框（按要求改、起名、随便问）。 */
@Composable
internal fun AskDialog(title: String, hint: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = text, onValueChange = { text = it }, label = hint, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 160.dp))
        DialogButtons(onDismiss = onDismiss, onConfirm = { onConfirm(text.trim()) }, confirmText = confirm, confirmEnabled = text.isNotBlank())
    }
}

// ---------- 作品的 AI 设置 ----------

/** “这部书的口味”的占位提示。 */
/** “缺细纲、节拍时 AI 先补上”的说明：作品 AI 设置首屏和“AI 写整章草稿”页是同一个开关。 */
internal const val DRAFT_PREP_NOTE = "细纲卡没有“本章事实与结果”先拟一份，没有节拍先拆，存进细纲卡等你确认；你写了的不动。关了就有什么写什么。"

internal const val FLAVOR_EXAMPLE = "轻松带吐槽的穿越历史爽文，主角爱用历史知识算计人，叙述者偶尔玩梗。"

/**
 * 作品 AI 设置里边打边存的一段文字：停一下再写盘；粘贴后立即返回、切后台、离开这页时不等防抖，立即补存。
 * 只存这一项（[read] 读、[write] 写），不覆盖同时改的其他选项。
 */
@Composable
private fun rememberAssistText(vm: AppViewModel, workId: String, read: (WorkAssist) -> String, write: (WorkAssist, String) -> WorkAssist): MutableState<String> {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val loaded = assists[workId]
    val field = remember(workId, loaded != null) { mutableStateOf(loaded?.let(read).orEmpty()) }
    LaunchedEffect(field.value) {
        val saved = vm.writingAi.assists.value[workId] ?: return@LaunchedEffect
        if (field.value == read(saved)) return@LaunchedEffect
        delay(600)
        vm.writingAi.updateAssist(workId) { write(it, field.value) }
    }
    val latest by rememberUpdatedState(field)
    fun saveNow() {
        val saved = vm.writingAi.assists.value[workId] ?: return
        val text = latest.value
        if (text != read(saved)) vm.writingAi.updateAssist(workId) { write(it, text) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveNow() }
    DisposableEffect(workId) {
        onDispose { saveNow() }
    }
    return field
}

/**
 * 一部作品的 AI 设置：首屏只放最常用的几项（服务商、写正文的温度和先思考、这部书的口味、我的黑名单、从改稿学到的、
 * 对文风的要求、一键写本章的准备），其余一项不少地收进“高级设置”（[WorkAiAdvancedScreen]）。
 */
@Composable
fun WorkAiScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val ai by vm.aiConfig.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val assist = assists[workId] ?: WorkAssist()
    val styleField = remember(workId, assists[workId] != null) { mutableStateOf(assist.style) }
    var style by styleField
    var temperatureOpen by remember { mutableStateOf(false) }
    // 文风要求边打边存（停一下再写盘）。
    LaunchedEffect(style) {
        if (assists[workId] == null || style == assist.style) return@LaunchedEffect
        delay(600)
        vm.writingAi.updateAssist(workId) { it.copy(style = style) }
    }
    // 粘贴后立即返回、切后台时，不能等防抖计时。只补存这页的文风，不覆盖同时改的其他选项。
    val latestStyleField by rememberUpdatedState(styleField)
    fun saveStyleNow() {
        val saved = vm.writingAi.assists.value[workId] ?: return
        val text = latestStyleField.value
        if (text != saved.style) vm.writingAi.updateAssist(workId) { it.copy(style = text) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveStyleNow() }
    DisposableEffect(workId) {
        onDispose { saveStyleNow() }
    }
    var flavor by rememberAssistText(vm, workId, { it.flavor }) { a, text -> a.copy(flavor = text) }

    SubPageScaffold(
        title = "写作 AI",
        onBack = { vm.back() },
    ) { padding ->
        val c = LocalGroupedColors.current
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    // 首屏只放最常调的：能不能写（服务商）、怎么写（温度、先思考、口味、文风）、别写什么（黑名单、学到的）、一键写本章的准备。
                    GroupedSection(
                        footer = "在编辑器底栏“写”里：“AI 写…”从光标处写正文，“改稿”润色、扩写、缩写、挑错、读者点评，问主意在“灵感”里；" +
                            "前后矛盾在“查”里的“三道审稿”事实关查。结果先给你看，点“采纳”才改稿。按章挑出的本章资料会带给 AI。",
                    ) {
                        row {
                            if (ai.ready) ValueRow("服务商", "${ai.providerLabel} · ${ai.model}", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE))
                            else NavRow("设置 AI 服务", value = if (!ai.enabled) "未开启" else "未填密钥", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE)) { vm.open(Screen.Ai) }
                        }
                    }
                    GroupedSection(
                        header = "写正文",
                        footer = "温度越高越不套路，也越容易跑偏。这两项只影响写正文和改稿（AI 写、AI 写整章草稿、项目包流程的修订、润色扩写这类），审稿、整理不受影响；" +
                            "推理和思考模式的模型可能不认温度。",
                    ) {
                        row { NavRow("写正文的温度", value = temperatureLabel(assist.temperature)) { temperatureOpen = true } }
                        row {
                            ToggleRow("写正文时先思考", assist.thinking, subtitle = THINKING_NOTE) { on ->
                                vm.writingAi.updateAssist(workId) { it.copy(thinking = on) }
                            }
                        }
                    }
                    GroupedSection(header = "这部书的口味", footer = "一两句话说清这部书读起来是什么味道。AI 写正文时照这个口味写。") {
                        row {
                            TextField(
                                value = flavor,
                                onValueChange = { flavor = it },
                                label = FLAVOR_EXAMPLE,
                                useLabelAsPlaceholder = true,
                                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 64.dp, max = 160.dp),
                            )
                        }
                    }
                    GroupedSection(
                        header = "AI 腔",
                        footer = "“我的黑名单”里的说法正文里出现就标出（对白里也算），AI 写的时候一个都不用。" +
                            "“从改稿学到的”是收尾时从你的改稿里学到、你勾过的写法规则，AI 写正文、改稿时照着写。",
                    ) {
                        row {
                            NavRow("我的黑名单", value = if (assist.banned.isEmpty()) "空" else "${assist.banned.size} 条") {
                                vm.open(Screen.WorkBanned(workId))
                            }
                        }
                        row {
                            NavRow("从改稿学到的", value = if (assist.learned.isEmpty()) "空" else "${assist.learned.size} 条") {
                                vm.open(Screen.WorkLearned(workId))
                            }
                        }
                    }
                    GroupedSection(header = "文风", footer = "AI 改稿、写正文时都按这里写的来，比如“第一人称，口语化，少用成语”“对话多、短句为主”。") {
                        row {
                            TextField(
                                value = style,
                                onValueChange = { style = it },
                                label = "对文风的要求",
                                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 80.dp, max = 200.dp),
                            )
                        }
                    }
                    GroupedSection(header = "一键写本章") {
                        row {
                            ToggleRow("缺细纲、节拍时 AI 先补上", checked = assist.draftPrep, subtitle = DRAFT_PREP_NOTE) { on ->
                                vm.writingAi.updateAssist(workId) { it.copy(draftPrep = on) }
                            }
                        }
                    }
                    // 其余的收进二级页：值和存储都不变，只是换个地方放。
                    GroupedSection(footer = "文风范例、查 AI 腔、写作建议、AI 用量在“高级设置”里，和知识库、本章资料的上限放在一起。") {
                        row { NavRow("高级设置") { vm.open(Screen.WorkAiAdvanced(workId)) } }
                    }
                    // AI 记录常回来看（标有用、没用），放在首屏最底下，不再藏进高级设置。
                    GroupedSection(footer = "灵感、读者点评、AI 写的章都记在这里（最近 ${com.xycs.lingyuereader.data.WriteRecord.KEEP} 条），可以标有用、没用。") {
                        row { NavRow("AI 记录") { vm.open(Screen.WorkAiLog(workId)) } }
                    }
                }
            }
            if (temperatureOpen) {
                GlassDialog(show = true, title = "写正文的温度", summary = "越高越不套路，也越容易跑偏；只用在写正文、改稿上。拿不准就用标准 1.0", onDismissRequest = { temperatureOpen = false }) {
                    DialogOptionList(
                        WorkAssist.TEMPERATURES.map { it to temperatureLabel(it) },
                        selected = assist.temperature,
                        description = { t -> if (t == 1.1f) "最高到这里：再高 AI 会写成读不通的乱码" else null },
                        onPick = { t ->
                            temperatureOpen = false
                            vm.writingAi.updateAssist(workId) { it.copy(temperature = t) }
                        },
                    )
                    TextButton(text = "取消", onClick = { temperatureOpen = false }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
    }
}

/**
 * 作品 AI 设置的“高级设置”：不常调的从首屏收进来——知识库、本章资料的上限，
 * 文风范例（开关、带多少、挑了哪几段），查 AI 腔，写作建议（开关、查哪几类、频率），学你的改法（计数、清除），
 * AI 用量、最近一次发给 AI 的内容。设定包、写作范本是内容，不放在设置页（作品页、编辑器里进）；AI 记录在首屏最底下。
 */
@Composable
fun WorkAiAdvancedScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val assist = assists[workId] ?: WorkAssist()
    val work = vm.writing.works.collectAsStateWithLifecycle().value.firstOrNull { it.id == workId }
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }
    SubPageScaffold("高级设置", onBack = { vm.back() }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                GroupedSection(
                    header = "带多少资料",
                    footer = "知识库（设定包里标了“常用”的文档）整份放进每次请求，最多放多少字。按模型：按选的服务商的上下文算" +
                        "（DeepSeek、通义千问、Kimi 约 10 万字，Claude 约 10 万字）。放不下的从最大的一份起改成 AI 按需查；" +
                        "放得越多，AI 越不用查，但每次请求越大。DeepSeek 会尝试缓存相同的开头，实际命中以用量记录为准。" +
                        "本章资料字数上限也影响联网项目写作；本机模型不能自己查，会按章挑着带。设定包页顶上能看到现在每次带多少、哪几份放不下。",
                ) {
                    row {
                        PickerRow(
                            "知识库上限",
                            WorkAssist.KNOWLEDGE_LIMITS.map { it to if (it == 0) "按模型" else "${it / 10_000} 万字" },
                            assist.knowledgeLimit,
                        ) { v -> vm.writingAi.updateAssist(workId) { it.copy(knowledgeLimit = v) } }
                    }
                    row {
                        PickerRow("本章资料上限", WorkAssist.BRIEF_LIMITS.map { it to "${it / 1000} 千字" }, assist.briefLimit) { v ->
                            vm.writingAi.updateAssist(workId) { it.copy(briefLimit = v) }
                        }
                    }
                }
                StyleSamplesSection(vm, workId, assist)
                StyleSamplesMoreSection(vm, workId, assist, header = null)
                GroupedSection(
                    header = "AI 腔",
                    footer = "本机按内置清单标出套话，不花钱；AI 写正文、改稿时也叮嘱它别这样写。",
                ) {
                    row {
                        ToggleRow("查 AI 腔", assist.aiCliche) { on ->
                            vm.writingAi.updateAssist(workId) { it.copy(aiCliche = on) }
                        }
                    }
                }
                GroupedSection(
                    header = "写作建议",
                    footer = "开启后，你停笔几秒，灵阅会把打开这一章以后新写或改过的段落（每次最多 ${com.xycs.lingyuereader.WritingAi.REALTIME_BATCH} 段）连同设定包发给上面的 AI 服务商，" +
                        "按勾选的几类挑问题，在正文里划线标出；光标点到划线处看建议，采纳才改。规范、口吻要设定包里有依据，拿不准的不提；和设定对不上的在“三道审稿”的事实关里查；" +
                        "只查错字时不带设定包；本机模型只查错字。检查规则不合的地方在本机标出，不花钱。按服务商的价格计费。" +
                        "开了“写作时给建议”，下面两项才用得上：停笔后两次检查之间至少隔多久、一天最多查几次。" +
                        "只对《${work?.title.orEmpty()}》生效；关着时不会自动发送任何内容。",
                ) {
                    row {
                        // 开了以后下面带出几行“查某类”，都不带图标，这一行也不带。
                        ToggleRow("写作时给建议", assist.realtime) { on ->
                            vm.writingAi.updateAssist(workId) { it.copy(realtime = on) }
                        }
                    }
                    if (assist.realtime) {
                        SuggestKind.entries.filter { it.realtime }.forEach { k ->
                            row {
                                ToggleRow("查${k.label}", k.name in assist.suggestKinds) { on ->
                                    vm.writingAi.updateAssist(workId) { a -> a.copy(suggestKinds = if (on) (a.suggestKinds + k.name).distinct() else a.suggestKinds - k.name) }
                                }
                            }
                        }
                    }
                    row {
                        PickerRow("两次至少隔", WorkAssist.SUGGEST_GAPS.map { it to "$it 秒" }, assist.suggestGap) { v ->
                            vm.writingAi.updateAssist(workId) { it.copy(suggestGap = v) }
                        }
                    }
                    row {
                        PickerRow("一天最多", WorkAssist.SUGGEST_CAPS.map { it to if (it == 0) "不限" else "$it 次" }, assist.suggestCap) { v ->
                            vm.writingAi.updateAssist(workId) { it.copy(suggestCap = v) }
                        }
                    }
                }
                GroupedSection(
                    header = "学你的改法",
                    footer = "你采纳过的修改会记下最近 ${WorkAssist.KEEP_EXAMPLES} 条，润色时挑几条给 AI 当例子，改得更像你；" +
                        "没采纳的改法记下最近 ${WorkAssist.KEEP_REJECTED} 条，告诉 AI 别这样改。只存在这台手机上（随灵阅备份）。",
                ) {
                    row { ValueRow("记住的采纳", "${assist.accepted.size} 条") }
                    row { ValueRow("记住的没采纳", "${assist.declined.size} 条") }
                    row { ValueRow("没用上的建议", "${assist.rejected} 次") }
                    if (assist.accepted.isNotEmpty() || assist.rejected > 0 || assist.declined.isNotEmpty()) {
                        row { ActionRow("清除记录", destructive = true) { clearing = true } }
                    }
                }
                GroupedSection(
                    header = "记录",
                    footer = "“AI 用量”按天、按功能记这部作品花了多少（只有数字）。" +
                        "“发给 AI 的内容”是最近一次请求的原文：系统提示、带上的设定资料和要求，想知道 AI 为什么这样写时看看；也写着这次用了多少 token、约多少钱。",
                ) {
                    // 同一张卡片里的行要么都带图标、要么都不带：这几行都不带。
                    row {
                        val usage by vm.aiUsage.collectAsStateWithLifecycle()
                        val today = com.xycs.lingyuereader.ai.UsageBook.total(usage.days[java.time.LocalDate.now()].orEmpty().filter { it.work == workId })
                        NavRow(
                            "AI 用量",
                            value = if (today.usage.isEmpty) "今天还没用" else "今天 " + (com.xycs.lingyuereader.ai.AiPricing.money(today.cost)?.let { "约 $it" } ?: "${today.usage.requests} 次"),
                        ) { vm.open(Screen.AiUsage(workId)) }
                    }
                    row {
                        NavRow("最近一次发给 AI 的内容", value = sent?.let { "${it.label} · ${it.size} 字" } ?: "还没有", enabled = sent != null) { showSent = true }
                    }
                }
            }
        }
        if (clearing) {
            GlassDialog(show = true, title = "清除记录", summary = "记住的采纳和统计都会清掉，不影响稿子和设定包。", onDismissRequest = { clearing = false }) {
                DialogButtons(
                    onDismiss = { clearing = false },
                    onConfirm = {
                        clearing = false
                        vm.writingAi.updateAssist(workId) { it.copy(accepted = emptyList(), declined = emptyList(), rejected = 0) }
                    },
                    confirmText = "清除",
                    destructive = true,
                )
            }
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
    }
}

/** 写正文的温度给人看的说法：空是服务商默认，几档常用的带一句；别的（备份里来的）只写数。 */
internal fun temperatureLabel(t: Float?): String = when (t) {
    null -> "服务商默认"
    0.7f -> "稳一点 0.7"
    0.9f -> "略稳 0.9"
    1.0f -> "标准 1.0"
    1.1f -> "放开些 1.1"
    else -> t.toString()
}

/** AI 整理出的人物：勾选要加的（默认全选），已有的标“更新”。 */
@Composable
internal fun ProposalsDialog(list: List<BibleProposal>, onDismiss: () -> Unit, onAdd: (List<BibleProposal>) -> Unit) {
    var chosen by remember { mutableStateOf(list.map { it.name }.toSet()) }
    val c = LocalGroupedColors.current
    GlassDialog(show = true, title = "整理出 ${list.size} 个人物", summary = "勾选要加进设定包人物档案的；已有的人物，人物档案里他那一节会换成新的说明（原来的文字先存一个历史版本）。", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            list.forEach { p ->
                val toggle = { chosen = if (p.name in chosen) chosen - p.name else chosen + p.name }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Medium)).clickable { toggle() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(state = ToggleableState(p.name in chosen), onClick = { toggle() })
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.name + if (p.existing) "（更新）" else "", color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium)
                        Text(p.text, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
                    }
                }
            }
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onAdd(list.filter { it.name in chosen }) }, confirmText = "加入人物档案")
    }
}

/**
 * 我的黑名单：作者不想再看到的说法。编辑器里点到 AI 腔点“以后都不要”加进来，这里也能手动加、删。
 * 最近加的排在最上面；本机全文禁用（对白里也查），AI 写正文时一个都不用。
 */
@Composable
fun WorkBannedScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val banned = assists[workId]?.banned.orEmpty()
    var input by remember(workId) { mutableStateOf("") }
    val phrase = input.trim()
    val canAdd = phrase.isNotEmpty() && phrase !in banned
    fun add() {
        if (!canAdd) return
        vm.writingAi.updateAssist(workId) { it.ban(phrase) }
        input = ""
    }
    SubPageScaffold("我的黑名单", onBack = { vm.back() }) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "add") {
                GroupedSection(
                    header = "加一条",
                    footer = "写进来的说法，正文里出现就标出来（对白里也算），AI 写正文、改稿时一个都不用。" +
                        "最多记 ${WorkAssist.KEEP_BANNED} 条，满了丢最早加的。在编辑器里点到标出的 AI 腔，点“以后都不要”也能加。",
                ) {
                    row {
                        TextField(
                            value = input,
                            // 一条说法不跨段：换行去掉。
                            onValueChange = { input = it.replace("\n", "") },
                            label = "不想再看到的说法",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                    row { ActionRow("加入黑名单", enabled = canAdd) { add() } }
                }
            }
            item(key = "list") {
                GroupedSection(header = if (banned.isEmpty()) null else "${banned.size} 条") {
                    if (banned.isEmpty()) row { EmptyRow("还没有。") }
                    banned.asReversed().forEach { b ->
                        row {
                            GroupedRow {
                                Text(b, color = c.label, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.writingAi.updateAssist(workId) { it.unban(b) } }) {
                                    Icon(LingYueIcons.Delete, contentDescription = "从黑名单删去「$b」", tint = c.destructive)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从改稿学到的：“这章写完了”时从作者的改稿里学到、作者勾过的写法规则。这里能看、能删、能手动加（照“我的黑名单”）。
 * 最近加的排在最上面；AI 写正文、改稿时带最近的 [WorkAssist.PROMPT_LEARNED] 条。
 */
@Composable
fun WorkLearnedScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val learned = assists[workId]?.learned.orEmpty()
    var input by remember(workId) { mutableStateOf("") }
    val rule = input.trim()
    val canAdd = rule.isNotEmpty() && rule !in learned
    fun add() {
        if (!canAdd) return
        vm.writingAi.updateAssist(workId) { it.learn(rule) }
        input = ""
    }
    SubPageScaffold("从改稿学到的", onBack = { vm.back() }) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "add") {
                GroupedSection(
                    header = "加一条",
                    footer = "写一句写法要求（比如“对白后面不要再解释人物的心思”），AI 写正文、改稿时照着写，审稿、拟细纲、整理不用。" +
                        "带最近的 ${WorkAssist.PROMPT_LEARNED} 条，最多记 ${WorkAssist.KEEP_LEARNED} 条，满了丢最早的。" +
                        "“这章写完了”时勾上“从这章的改稿里学”，学到的你勾了也记在这里。",
                ) {
                    row {
                        TextField(
                            value = input,
                            // 一条规则不跨段：换行去掉。
                            onValueChange = { input = it.replace("\n", "") },
                            label = "一条写法规则",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                    row { ActionRow("加进去", enabled = canAdd) { add() } }
                }
            }
            item(key = "list") {
                GroupedSection(header = if (learned.isEmpty()) null else "${learned.size} 条") {
                    if (learned.isEmpty()) row { EmptyRow("还没有。") }
                    learned.asReversed().forEach { r ->
                        row {
                            GroupedRow {
                                Text(r, color = c.label, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.writingAi.updateAssist(workId) { it.unlearn(r) } }) {
                                    Icon(LingYueIcons.Delete, contentDescription = "删去「$r」", tint = c.destructive)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
