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
import com.xycs.lingyuereader.data.BibleEntry
import com.xycs.lingyuereader.data.BibleKind
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    AiBarItem.Edit(WriteAction.POLISH),
    AiBarItem.Edit(WriteAction.VIVID),
    AiBarItem.Edit(WriteAction.EXPAND),
    AiBarItem.Edit(WriteAction.CONDENSE),
    AiBarItem.Edit(WriteAction.PROOFREAD),
    AiBarItem.Edit(WriteAction.CONTINUE),
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

/**
 * 采纳：把 [result] 换进正文。位置上的文字还是原文就按位置换；正文改过了就按原文找。
 * 续写插在光标处，光标在一段中间或段末时另起一段。返回是否换成了。
 */
internal fun applyEdit(state: TextFieldState, task: WriteAiTask.Edit, result: String): Boolean {
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
        return true
    }
    val range = if (task.end <= text.length && text.substring(task.start, task.end) == task.original) task.start until task.end
    else text.indexOf(task.original).takeIf { it >= 0 }?.let { it until it + task.original.length } ?: return false
    state.edit {
        replace(range.first, range.last + 1, result)
        selection = TextRange(range.first + result.length)
    }
    return true
}

/** 改一处实时检查找出的错：在那段里把错的换成对的。段落已经改掉时返回假。 */
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
                if (state.loading) "  生成中……" else "  $model 生成，可能有误",
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
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            val body: AnnotatedString = when {
                canDiff && showDiff -> TextDiff.diff(edit!!.original, state.text)?.let { diffText(it, Color(0xFFE5484D), Color(0xFF2F9E44)) }
                    ?: AnnotatedString(state.text)
                else -> AnnotatedString(state.text)
            }
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

/** 实时检查找出的问题：一条条列出，“改”就换掉，“忽略”就不再提。 */
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
 * 一部作品的 AI 设置：实时检查（默认关）、文风要求、记住的改法、设定集入口。
 */
@Composable
fun WorkAiScreen(vm: AppViewModel, workId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val ai by vm.aiConfig.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val assist = assists[workId] ?: WorkAssist()
    var style by remember(assists[workId] != null) { mutableStateOf(assist.style) }
    var clearing by remember { mutableStateOf(false) }
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
                            "也可以要灵感、请 AI 以读者视角点评本章、检查前后矛盾。结果先给你看对比，点“采纳”才改稿。",
                    ) {
                        row {
                            if (ai.ready) ValueRow("服务商", "${ai.providerLabel} · ${ai.model}", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE))
                            else NavRow("AI 助手", value = if (!ai.enabled) "未开启" else "未填密钥", icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE)) { vm.open(Screen.Ai) }
                        }
                        row {
                            NavRow("设定集", value = if (assist.entries.isEmpty()) "空" else "${assist.entries.size} 条", icon = LingYueIcons.Book, iconBackground = Color(0xFF34C759)) {
                                vm.open(Screen.WorkBible(workId))
                            }
                        }
                    }
                    GroupedSection(
                        header = "实时检查",
                        footer = "开启后，停笔几秒，灵阅会自动把刚写或改过的段落（每次最多 ${com.xycs.lingyuereader.WritingAi.REALTIME_BATCH} 段）发给上面的 AI 服务商，" +
                            "挑出错别字、用错的词和标点，显示在编辑器里由你决定改不改。按服务商的价格计费。只对《${work?.title.orEmpty()}》生效；关着时不会自动发送任何内容。",
                    ) {
                        row {
                            ToggleRow("写作时实时检查", assist.realtime, icon = LingYueIcons.Timer, iconBackground = Color(0xFFFF9500)) { on ->
                                vm.writingAi.updateAssist(workId) { it.copy(realtime = on) }
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
                        footer = "你采纳过的修改会记下最近 ${WorkAssist.KEEP_EXAMPLES} 条，润色时挑几条给 AI 当例子，改得更像你。只存在这台手机上（随灵阅备份）。",
                    ) {
                        row { ValueRow("记住的采纳", "${assist.accepted.size} 条") }
                        row { ValueRow("没用上的建议", "${assist.rejected} 次") }
                        if (assist.accepted.isNotEmpty() || assist.rejected > 0) {
                            row { ActionRow("清除记录", destructive = true) { clearing = true } }
                        }
                    }
                }
            }
            if (clearing) {
                GlassDialog(show = true, title = "清除记录", summary = "记住的采纳和统计都会清掉，不影响稿子和设定集。", onDismissRequest = { clearing = false }) {
                    DialogButtons(confirm = "清除", onDismiss = { clearing = false }) {
                        clearing = false
                        vm.writingAi.updateAssist(workId) { it.copy(accepted = emptyList(), rejected = 0) }
                    }
                }
            }
        }
    }
}

// ---------- 设定集 ----------

/**
 * 设定集：人物、世界观、大纲、伏笔……写作时 AI 会参考它（改稿、灵感、矛盾检查）。
 * 可以手动加，也可以让 AI 从正文里整理人物，勾选后加进来。
 */
@Composable
fun WorkBibleScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val assist = assists[workId] ?: WorkAssist()
    var editing by remember { mutableStateOf<BibleEntry?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var proposals by remember { mutableStateOf<List<BibleProposal>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = rememberWritingMessages(vm)

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "设定集",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = { editing = BibleEntry("", BibleKind.CHARACTER, "", "") }, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(LingYueIcons.Add, contentDescription = "添加设定")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(footer = "AI 改稿、给灵感、检查前后矛盾时会参考设定集。整理人物会把最近写的正文（最多约 1.5 万字）发给 AI 服务商。") {
                        row {
                            NavRow("用 AI 整理人物", value = if (extracting) "整理中……" else null, icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE), enabled = !extracting) {
                                extracting = true
                                scope.launch {
                                    try {
                                        proposals = vm.writingAi.extractCharacters(workId)
                                        if (proposals.isNullOrEmpty()) error = "没有整理出新的人物"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: "整理失败"
                                    } finally {
                                        extracting = false
                                    }
                                }
                            }
                        }
                    }
                    if (assist.entries.isEmpty()) {
                        EmptyState(
                            LingYueIcons.Book,
                            "设定集还是空的",
                            "把人物、世界观、大纲和埋下的伏笔记在这里。写到后面，AI 能帮你对照有没有前后矛盾。",
                            action = "添加一条" to { editing = BibleEntry("", BibleKind.CHARACTER, "", "") },
                        )
                    }
                    BibleKind.entries.forEach { kind ->
                        val list = assist.entries.filter { it.kind == kind }
                        if (list.isEmpty()) return@forEach
                        GroupedSection(header = kind.label) {
                            list.forEach { e ->
                                row {
                                    NavRow(e.name.ifBlank { "（无标题）" }, subtitle = e.text.take(60).ifBlank { null }) { editing = e }
                                }
                            }
                        }
                    }
                }
            }
            editing?.let { e ->
                BibleEntryDialog(
                    e,
                    onDismiss = { editing = null },
                    onDelete = if (e.id.isEmpty()) null else {
                        {
                            editing = null
                            vm.writingAi.updateAssist(workId) { a -> a.copy(entries = a.entries.filterNot { it.id == e.id }) }
                        }
                    },
                ) { saved ->
                    editing = null
                    vm.writingAi.updateAssist(workId) { a ->
                        if (saved.id.isEmpty()) a.copy(entries = a.entries + saved.copy(id = WorkStore.newId()))
                        else a.copy(entries = a.entries.map { if (it.id == saved.id) saved else it })
                    }
                }
            }
            proposals?.takeIf { it.isNotEmpty() }?.let { list ->
                ProposalsDialog(list, onDismiss = { proposals = null }) { picked ->
                    proposals = null
                    vm.writingAi.addProposals(workId, picked)
                }
            }
            error?.let { msg ->
                GlassDialog(show = true, title = "整理人物", summary = msg, onDismissRequest = { error = null }) {
                    TextButton(text = "好", onClick = { error = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
private fun BibleEntryDialog(entry: BibleEntry, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (BibleEntry) -> Unit) {
    var kind by remember { mutableStateOf(entry.kind) }
    var name by remember { mutableStateOf(entry.name) }
    var text by remember { mutableStateOf(entry.text) }
    GlassDialog(show = true, title = if (entry.id.isEmpty()) "添加设定" else "编辑设定", onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BibleKind.entries.forEach { k ->
                val selected = k == kind
                val scheme = MiuixTheme.colorScheme
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) scheme.primary else LocalGroupedColors.current.fill)
                        .clickable { kind = k }
                        .semantics { contentDescription = "类别${k.label}" }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(k.label, color = if (selected) scheme.onPrimary else LocalGroupedColors.current.label, fontSize = 14.sp) }
            }
        }
        TextField(value = name, onValueChange = { name = it }, label = if (kind == BibleKind.CHARACTER) "名字" else "标题", singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(value = text, onValueChange = { text = it }, label = "内容", modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 96.dp, max = 240.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onDelete != null) TextButton(text = "删除", onClick = onDelete, modifier = Modifier.weight(1f))
            else TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = "保存",
                enabled = name.isNotBlank() || text.isNotBlank(),
                onClick = { onSave(entry.copy(kind = kind, name = name.trim(), text = text.trim())) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** AI 整理出的人物：勾选要加的（默认全选），已有的标“更新”。 */
@Composable
private fun ProposalsDialog(list: List<BibleProposal>, onDismiss: () -> Unit, onAdd: (List<BibleProposal>) -> Unit) {
    var chosen by remember { mutableStateOf(list.map { it.name }.toSet()) }
    val c = LocalGroupedColors.current
    GlassDialog(show = true, title = "整理出 ${list.size} 个人物", summary = "勾选要加进设定集的；已有的人物会用新的说明替换。", onDismissRequest = onDismiss) {
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
