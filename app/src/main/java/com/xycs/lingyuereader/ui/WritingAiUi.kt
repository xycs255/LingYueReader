package com.xycs.lingyuereader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.BibleProposal
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.TextDiff
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.data.BibleEntry
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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

    /** 在光标处直接写（先显示、采纳才进正文）。 */
    data object Write : AiBarItem {
        override val label = "AI 接着写"
    }

    data object Ideas : AiBarItem {
        override val label = "灵感…"
    }

    data object Review : AiBarItem {
        override val label = "读者点评"
    }

    data object Consistency : AiBarItem {
        override val label = "矛盾检查"
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
    AiBarItem.Ideas,
    AiBarItem.Review,
    AiBarItem.Consistency,
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
        return WriteAiTask.Edit(action, "", start, start, instruction)
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
 * 续写插在光标处，光标在一段中间或段末时另起一段。
 */
internal fun applyEdit(state: TextFieldState, task: WriteAiTask.Edit, result: String): EditApply {
    val text = state.text.toString()
    if (task.action == WriteAction.CONTINUE) {
        val at = task.start.coerceIn(0, text.length)
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

/** 面板底下的一个按钮。 */
@Composable
private fun PanelButton(label: String, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val c = LocalGroupedColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (strong) scheme.primary else c.fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (strong) scheme.onPrimary else if (enabled) c.label else c.tertiary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 写作助手的结果面板：改稿类先显示对比（删的划掉、加的标绿），可以切到“改后”看全文；
 * 采纳才改稿。灵感、点评、矛盾检查只给人看，灵感可以插到光标处。
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LingYueIcons.Sparkle, contentDescription = null, tint = c.tint, modifier = Modifier.size(18.dp))
            Text(task.title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            Text(
                if (state.loading) lookup?.let { "  正在查：$it" } ?: "  生成中……" else "  $model 生成，可能有误",
                color = c.tertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(LingYueIcons.Close, contentDescription = "关闭 AI 面板", tint = c.secondary) }
        }
        val edit = task as? WriteAiTask.Edit
        val canDiff = edit != null && edit.original.isNotEmpty() && !state.loading && state.error == null
        if (canDiff) {
            Row(Modifier.padding(vertical = 4.dp)) {
                SegmentedControl(listOf(true to "对比", false to "改后"), showDiff) { showDiff = it }
            }
        }
        // 对比在后台算，同样的原文和结果只算一次（长段落比一次要一会儿，不能卡住界面）；算好之前先显示改后的全文。
        val original = edit?.original.orEmpty()
        val compared by produceState<AnnotatedString?>(null, canDiff, original, state.text) {
            value = null
            if (!canDiff) return@produceState
            val pieces = withContext(Dispatchers.Default) { TextDiff.diff(original, state.text) }
            value = pieces?.let { diffText(it, Color(0xFFE5484D), Color(0xFF2F9E44)) }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            val body: AnnotatedString = compared?.takeIf { canDiff && showDiff } ?: AnnotatedString(state.text)
            if (state.text.isNotEmpty()) Text(body, color = c.label, fontSize = 16.sp, lineHeight = 26.sp)
            else if (state.loading) Text("正在想……", color = c.secondary, fontSize = 15.sp)
            state.error?.let { Text("出错了：$it", color = c.destructive, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (canDiff && state.text.trim() == edit!!.original.trim()) {
                Text("AI 觉得这段不用改。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val done = !state.loading && state.error == null && state.text.isNotBlank()
            if (edit != null) {
                PanelButton(if (edit.action == WriteAction.CONTINUE) "接在后面" else "采纳", strong = true, enabled = done) { onAccept(state.text) }
            } else if (task is WriteAiTask.Idea) {
                PanelButton("插到光标处", strong = true, enabled = done) { onInsert(state.text) }
            }
            PanelButton("复制", enabled = done) { copy(context, state.text) }
            PanelButton("再来一次", enabled = !state.loading) { onRetry() }
            PanelButton(if (edit != null) "不用了" else "关闭") { onClose() }
        }
    }
}

/** 写作建议找出的问题：一条条列出，“改”就换掉，“忽略”就不再提。 */
@Composable
internal fun IssuesPanel(realtime: RealtimeState, onFix: (WriteIssue) -> Unit, onIgnore: (WriteIssue) -> Unit, onClose: () -> Unit) {
    val c = LocalGroupedColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可能的问题", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(LingYueIcons.Close, contentDescription = "收起", tint = c.secondary) }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            if (realtime.issues.isEmpty()) Text("都改好了。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            realtime.issues.forEach { i ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFFE5484D), textDecoration = TextDecoration.LineThrough)) { append(i.wrong) }
                            append("  →  ")
                            withStyle(SpanStyle(color = Color(0xFF2F9E44), fontWeight = FontWeight.Medium)) { append(i.right) }
                        },
                        color = c.label,
                        fontSize = 16.sp,
                    )
                    val at = i.paragraph.indexOf(i.wrong)
                    val snippet = i.paragraph.substring((at - 12).coerceAtLeast(0), (at + i.wrong.length + 12).coerceAtMost(i.paragraph.length))
                    Text(
                        (if (i.reason.isNotBlank()) i.reason + "：" else "") + "……$snippet……",
                        color = c.secondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton("改", strong = true) { onFix(i) }
                        PanelButton("忽略") { onIgnore(i) }
                    }
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

/** 各类建议的颜色：错字红、设定橙、规范蓝、口吻紫。 */
internal fun suggestColor(kind: String): Color = when (SuggestKind.of(kind)) {
    SuggestKind.TYPO -> Color(0xFFE5484D)
    SuggestKind.CANON -> Color(0xFFFF9500)
    SuggestKind.STYLE -> Color(0xFF007AFF)
    SuggestKind.VOICE -> Color(0xFFAF52DE)
}

/**
 * AI 在编辑器里写：键盘上方一条——在写（或在查设定）时能停，写完能采纳、重写、撤回。
 * 写的字显示在正文的光标处（浅色），采纳前不算正文。
 */
@Composable
internal fun InlineBar(inline: InlineWrite, lookup: String?, onStop: () -> Unit, onAccept: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val c = LocalGroupedColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LingYueIcons.Sparkle, contentDescription = null, tint = c.tint, modifier = Modifier.size(18.dp))
            val words = WordCount.of(inline.text)
            Text(
                when {
                    inline.error != null -> "出错了：${inline.error}"
                    inline.loading && lookup != null -> "正在查：$lookup"
                    inline.loading -> if (words == 0) "AI 正在想……" else "AI 正在写 · $words 字"
                    else -> "AI 写了 $words 字（浅色部分），采纳才放进正文" + (inline.note?.let { "；$it" } ?: "")
                },
                color = if (inline.error != null) c.destructive else c.label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (inline.loading) {
                PanelButton("停止") { onStop() }
            } else {
                PanelButton("采纳", strong = true, enabled = inline.text.isNotBlank() && inline.error == null) { onAccept() }
                PanelButton("重写") { onRetry() }
            }
            PanelButton("撤回") { onDiscard() }
        }
    }
}

/** 选写多少：写下一场、写到本章结尾、写 500 字左右、按要求写。 */
@Composable
internal fun InlineModeDialog(onDismiss: () -> Unit, onPick: (InlineMode) -> Unit) {
    val c = LocalGroupedColors.current
    GlassDialog(show = true, title = "AI 接着写", summary = "从光标处往下写，带着设定包、细纲卡和写作规范；写的字先显示，采纳才放进正文", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineMode.entries.forEach { m ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(m) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Text(m.label, color = c.label, fontSize = 16.sp)
                }
            }
        }
        TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/**
 * 光标落在一条建议上：键盘上方显示这条——哪类、原因、依据、改成什么；可以采纳、不改、这类以后不提。
 */
@Composable
internal fun SuggestionBar(issue: WriteIssue, onFix: () -> Unit, onIgnore: () -> Unit, onMute: () -> Unit) {
    val c = LocalGroupedColors.current
    val kind = SuggestKind.of(issue.kind)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(suggestColor(issue.kind).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(kind.label, color = suggestColor(issue.kind), fontSize = 12.sp)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(0xFFE5484D), textDecoration = TextDecoration.LineThrough)) { append(issue.wrong) }
                    append("  →  ")
                    withStyle(SpanStyle(color = Color(0xFF2F9E44), fontWeight = FontWeight.Medium)) { append(issue.right) }
                },
                color = c.label,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        val why = listOf(issue.reason, issue.source.takeIf { it.isNotBlank() }?.let { "依据：$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  ")
        if (why.isNotBlank()) Text(why, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("采纳", strong = true) { onFix() }
            PanelButton("不改") { onIgnore() }
            PanelButton("${kind.label}类不提") { onMute() }
        }
    }
}

/** 光标落在一处不合检查规则的地方：说是哪条规则（本机检查，不花钱）。 */
@Composable
internal fun RuleNote(text: String, onOpen: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().background(c.background).clickable { onOpen() }.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x33FFCC00)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("规则", color = Color(0xFFB8860B), fontSize = 12.sp)
        }
        Text(text, color = c.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** 输入一句话的对话框（按要求改、起名、随便问）。 */
@Composable
internal fun AskDialog(title: String, hint: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = text, onValueChange = { text = it }, label = hint, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 160.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = confirm,
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 选灵感的哪一种。 */
@Composable
internal fun IdeasDialog(onDismiss: () -> Unit, onPick: (IdeaKind) -> Unit) {
    GlassDialog(show = true, title = "灵感", summary = "根据写到这里的内容和设定集给建议，不会改你的稿子。", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IdeaKind.entries.forEach { k ->
                TextButton(text = k.label, onClick = { onPick(k) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- 作品的 AI 设置 ----------

/**
 * 一部作品的 AI 设置：写作建议（默认关）、文风要求、记住的改法、设定集入口。
 */
@Composable
fun WorkAiScreen(vm: AppViewModel, workId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val ai by vm.aiConfig.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    LaunchedEffect(workId) {
        vm.writingAi.loadAssist(workId)
        vm.writing.loadPack(workId)
    }
    val assist = assists[workId] ?: WorkAssist()
    var style by remember(assists[workId] != null) { mutableStateOf(assist.style) }
    var clearing by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()
    // 文风要求边打边存（停一下再写盘）。
    LaunchedEffect(style) {
        if (assists[workId] == null || style == assist.style) return@LaunchedEffect
        delay(600)
        vm.writingAi.updateAssist(workId) { it.copy(style = style) }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "写作 AI",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(
                        footer = "在编辑器里点键盘上方的 ✦ 换成 AI 操作：选中文字（或把光标放在那段）润色、扩写、缩写、挑错、续写；" +
                            "也可以要灵感、请 AI 以读者视角点评本章、检查前后矛盾。结果先给你看对比，点“采纳”才改稿。按章挑出的本章资料会带给 AI。",
                    ) {
                        row {
                            if (ai.ready) ValueRow("服务商", "${ai.providerLabel} · ${ai.model}", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE))
                            else NavRow("AI 助手", value = if (!ai.enabled) "未开启" else "未填密钥", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE)) { vm.open(Screen.Ai) }
                        }
                        row {
                            val docs = packs[workId]
                            NavRow("设定包", value = docs?.let { if (it.isEmpty()) "空" else "${it.size} 份" }, icon = LingYueIcons.Notes, iconBackground = Color(0xFF007AFF)) {
                                vm.open(Screen.WorkPack(workId))
                            }
                        }
                    }
                    GroupedSection(
                        header = "记录",
                        footer = "灵感、读者点评、矛盾检查、AI 写的章都记在这里（最近 ${com.xycs.lingyuereader.data.WriteRecord.KEEP} 条），可以标有用、没用。" +
                            "“发给 AI 的内容”是最近一次请求的原文：系统提示、带上的设定资料和要求，想知道 AI 为什么这样写时看看。",
                    ) {
                        row { NavRow("AI 记录", icon = LingYueIcons.History, iconBackground = Color(0xFF8E8E93)) { vm.open(Screen.WorkAiLog(workId)) } }
                        row {
                            NavRow("最近一次发给 AI 的内容", value = sent?.let { "${it.label} · ${it.size} 字" } ?: "还没有", enabled = sent != null) { showSent = true }
                        }
                    }
                    GroupedSection(
                        header = "设定包",
                        footer = "知识库（设定包里标了“常用”的文档）整份放进每次请求，最多放多少字。按模型：按选的服务商的上下文算" +
                            "（DeepSeek、通义千问、Kimi 约 10 万字，Claude 约 10 万字）。放不下的从最大的一份起改成 AI 按需查；" +
                            "放得越多，AI 越不用查，但每次请求越大（相同的开头服务商会缓存，后面几次便宜很多）。" +
                            "本章资料字数上限只给本机模型用：它不能自己查，按章挑着带。",
                    ) {
                        row {
                            PickerRow(
                                "知识库上限",
                                WorkAssist.KNOWLEDGE_LIMITS.map { it to if (it == 0) "按模型" else "${it / 10_000} 万字" },
                                assist.knowledgeLimit,
                            ) { v -> vm.writingAi.updateAssist(workId) { it.copy(knowledgeLimit = v) } }
                        }
                        row {
                            PickerRow("本章资料字数上限", WorkAssist.BRIEF_LIMITS.map { it to "${it / 1000} 千字" }, assist.briefLimit) { v ->
                                vm.writingAi.updateAssist(workId) { it.copy(briefLimit = v) }
                            }
                        }
                    }
                    GroupedSection(
                        header = "写作建议",
                        footer = "开启后，你停笔几秒，灵阅会把打开这一章以后新写或改过的段落（每次最多 ${com.xycs.lingyuereader.WritingAi.REALTIME_BATCH} 段）连同设定包发给上面的 AI 服务商，" +
                            "按勾选的几类挑问题，在正文里划线标出；光标点到划线处看建议，采纳才改。设定、规范、口吻要设定包里有依据，拿不准的不提；" +
                            "只查错字时不带设定包；本机模型只查错字。检查规则不合的地方在本机标出，不花钱。按服务商的价格计费，两次之间至少隔几秒、一天最多几次可以在下面设。" +
                            "只对《${work?.title.orEmpty()}》生效；关着时不会自动发送任何内容。",
                    ) {
                        row {
                            ToggleRow("写作时给建议", assist.realtime, icon = LingYueIcons.Timer, iconBackground = Color(0xFFFF9500)) { on ->
                                vm.writingAi.updateAssist(workId) { it.copy(realtime = on) }
                            }
                        }
                        if (assist.realtime) {
                            SuggestKind.entries.forEach { k ->
                                row {
                                    ToggleRow("查${k.label}", k.name in assist.suggestKinds) { on ->
                                        vm.writingAi.updateAssist(workId) { a -> a.copy(suggestKinds = if (on) (a.suggestKinds + k.name).distinct() else a.suggestKinds - k.name) }
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
                    }
                    GroupedSection(header = "文风", footer = "AI 改稿、续写时都按这里写的来，比如“第一人称，口语化，少用成语”“对话多、短句为主”。") {
                        row {
                            TextField(
                                value = style,
                                onValueChange = { style = it },
                                label = "对文风的要求",
                                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 80.dp, max = 200.dp),
                            )
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
                }
            }
            if (clearing) {
                GlassDialog(show = true, title = "清除记录", summary = "记住的采纳和统计都会清掉，不影响稿子和设定集。", onDismissRequest = { clearing = false }) {
                    DialogButtons(confirm = "清除", onDismiss = { clearing = false }) {
                        clearing = false
                        vm.writingAi.updateAssist(workId) { it.copy(accepted = emptyList(), declined = emptyList(), rejected = 0) }
                    }
                }
            }
            if (showSent) sent?.let { SentDialog(it) { showSent = false } }
        }
    }
}

/** AI 整理出的人物：勾选要加的（默认全选），已有的标“更新”。 */
@Composable
internal fun ProposalsDialog(list: List<BibleProposal>, onDismiss: () -> Unit, onAdd: (List<BibleProposal>) -> Unit) {
    var chosen by remember { mutableStateOf(list.map { it.name }.toSet()) }
    val c = LocalGroupedColors.current
    GlassDialog(show = true, title = "整理出 ${list.size} 个人物", summary = "勾选要加进设定集的；已有的人物，人物档案里他那一节会换成新的说明（原来的文字先存一个历史版本）。", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            list.forEach { p ->
                val toggle = { chosen = if (p.name in chosen) chosen - p.name else chosen + p.name }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { toggle() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(state = ToggleableState(p.name in chosen), onClick = { toggle() })
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.name + if (p.existing) "（更新）" else "", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(p.text, color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
        DialogButtons(confirm = "加入设定集", onDismiss = onDismiss) { onAdd(list.filter { it.name in chosen }) }
    }
}
