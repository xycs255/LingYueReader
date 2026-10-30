package com.xycs.lingyuereader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.BookFormat
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BlankLines
import com.xycs.lingyuereader.data.FormatChange
import com.xycs.lingyuereader.data.FormatHint
import com.xycs.lingyuereader.data.FormatKind
import com.xycs.lingyuereader.data.FormatNote
import com.xycs.lingyuereader.data.FormatResult
import com.xycs.lingyuereader.data.FormatSettings
import com.xycs.lingyuereader.data.QuoteStyle
import com.xycs.lingyuereader.data.TypingFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ---------- 输入时自动排版 ----------

/**
 * 输入时自动排版：作者打进去的字提交了（输入法不在组合中）以后，紧接着把它就地换掉（规则见 [TypingFormat]）。
 * 换的这一下不在作者那一下编辑里改，而是单独的一步编辑：撤销时第一次先撤销自动转换（像系统的自动更正），再撤销打的字本身。
 * 撤销重做、快捷标点条、采纳 AI 写的字都不是在键盘上打的，不经过这里；拼音还没上屏（有组合）时不换，上屏那一下再看。
 * [settings] 返回空表示这会儿不换（定稿、只读）。
 */
internal class TypingFormatInput(
    private val state: TextFieldState,
    private val scope: CoroutineScope,
    private val settings: () -> FormatSettings?,
) : InputTransformation {
    /** 最近一下打的字：在正文 [text] 的 [start, end)。 */
    private class Pending(val start: Int, val end: Int, val text: String)

    private var pending: Pending? = null
    private var posted = false

    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        if (settings()?.onInput != true) return
        val list = changes
        if (list.changeCount == 0) return
        var start = Int.MAX_VALUE
        var end = -1
        for (i in 0 until list.changeCount) {
            val r = list.getRange(i)
            start = minOf(start, r.min)
            end = maxOf(end, r.max)
        }
        // 删字（退格）不换：作者退掉自动换的字时不再换回来。
        if (end <= start) return
        pending = Pending(start, end, toString())
        if (!posted) {
            posted = true
            // 等到下一帧再换：这一下编辑那时已经落进正文（编辑里不能再套一次编辑），作者也看得到打出来的是什么。
            scope.launch {
                withFrameNanos { }
                posted = false
                apply()
            }
        }
    }

    /** 把最近一下打的字换掉：正文没再变、输入法没在组合时才换。 */
    fun apply() {
        val p = pending ?: return
        pending = null
        val s = settings() ?: return
        if (!s.onInput || state.composition != null) return
        val text = state.text
        if (text.length != p.text.length || text.toString() != p.text) return
        val fix = TypingFormat.fix(text, p.start, p.end, s.quoteStyle) ?: return
        val sel = state.selection
        val delta = fix.text.length - (fix.end - fix.start)
        fun move(i: Int) = when {
            i >= fix.end -> i + delta
            i > fix.start -> fix.start + fix.text.length
            else -> i
        }
        state.edit {
            replace(fix.start, fix.end, fix.text)
            selection = TextRange(move(sel.start), move(sel.end))
        }
    }
}

// ---------- 编辑器里的排版预览 ----------

/**
 * 一键排版的预览（键盘上方的浮层面板）：“共 N 处：标点 12、引号 4……”，每类一颗胶囊，点一下不排这类（再点排回来）；
 * 下面一处处前后对比（删掉的红色划线、加上的标绿，照改稿对比的样子），最后是只提示不改的：配不上的引号、段末缺标点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatPanel(
    result: FormatResult?,
    skip: Set<FormatKind>,
    onToggle: (FormatKind) -> Unit,
    onApply: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val included = result?.changes?.filter { it.kind !in skip }.orEmpty()
    InlinePanel(
        title = "自动排版",
        onClose = onClose,
        icon = LingYueIcons.Tune,
        status = result?.let { "共 ${included.size} 处" },
        closeLabel = "关闭排版",
        actions = {
            PillButton(if (included.isEmpty()) "排版" else "排版 ${included.size} 处", onApply, style = PillStyle.Filled, enabled = included.isNotEmpty())
            PillButton("排版设置", onSettings)
        },
    ) {
        if (result == null) {
            PanelBusy("正在看哪里要排……", Modifier.padding(vertical = 10.dp))
            return@InlinePanel
        }
        val counts = result.counts
        Text(
            if (counts.isEmpty()) "这一章不用排版。"
            else "共 ${included.size} 处：" + counts.filterKeys { it !in skip }.entries.joinToString("、") { (k, n) -> "${k.label} $n" }.ifEmpty { "都勾掉了" },
            color = c.label,
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (counts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.forEach { (k, n) ->
                    val on = k !in skip
                    PillButton(
                        "${k.label} $n",
                        { onToggle(k) },
                        modifier = Modifier.semantics {
                            selected = on
                            stateDescription = if (on) "要排" else "不排"
                        },
                        style = if (on) PillStyle.Tinted else PillStyle.Plain,
                    )
                }
            }
        }
        val removed = c.destructive
        val added = c.success
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(vertical = 4.dp)) {
            items(included.take(MAX_SHOWN), key = { "${it.kind}:${it.start}:${it.end}:${it.after}" }) { ch ->
                Row(Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()).fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Tag(ch.kind.label, c.tint, Modifier.padding(top = 2.dp))
                    Text(changeSnippet(result.original, ch, removed, added), color = c.label, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (included.size > MAX_SHOWN) {
                item(key = "more") { Text("还有 ${included.size - MAX_SHOWN} 处没列出来。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            }
            if (result.notes.isNotEmpty()) {
                item(key = "notesHeader") {
                    Text("提示（不会自动改）", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(result.notes.take(MAX_SHOWN), key = { "note:${it.hint}:${it.at}" }) { n ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Tag(n.hint.label, c.warning, Modifier.padding(top = 2.dp))
                        Text(noteSnippet(result.text, n, c.warning), color = c.label, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** 预览里最多列这么多处。 */
private const val MAX_SHOWN = 300

/** 看不见的字换成看得见的记号：空格 ␣、全角空格 □、制表符 ⇥、换行 ↵。 */
private fun visible(s: String): String = buildString {
    s.forEach {
        append(
            when (it) {
                ' ' -> '␣'
                '　' -> '□'
                '\t' -> '⇥'
                '\n' -> '↵'
                else -> it
            },
        )
    }
}

/** 一处改动的前后对比：前后各带几个字，删掉的红色划线，加上的绿底（和改稿对比 [diffText] 一个样子）。 */
internal fun changeSnippet(original: String, ch: FormatChange, removed: Color, added: Color, radius: Int = 10): AnnotatedString = buildAnnotatedString {
    val lineStart = original.lastIndexOf('\n', ch.start - 1) + 1
    val lineEnd = original.indexOf('\n', ch.end).let { if (it < 0) original.length else it }
    val a = maxOf(lineStart, ch.start - radius)
    val b = minOf(lineEnd, ch.end + radius).coerceAtLeast(ch.end)
    if (a > lineStart) append("…")
    append(original, a, ch.start)
    if (ch.before.isNotEmpty()) withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(visible(ch.before)) }
    if (ch.after.isNotEmpty()) withStyle(SpanStyle(background = added.copy(alpha = 0.22f), color = added)) { append(visible(ch.after)) }
    append(original, ch.end, b)
    if (b < lineEnd) append("…")
}

/** 一条提示所在的那几个字：配不上的那个引号标出来，段末缺标点的标出段末。 */
private fun noteSnippet(text: String, n: FormatNote, mark: Color): AnnotatedString = buildAnnotatedString {
    val lineStart = text.lastIndexOf('\n', n.at - 1) + 1
    val lineEnd = text.indexOf('\n', n.at).let { if (it < 0) text.length else it }
    val a = maxOf(lineStart, n.at - 14)
    val b = minOf(lineEnd, n.at + 8)
    if (a > lineStart) append("…")
    append(text, a, n.at)
    when (n.hint) {
        FormatHint.UNPAIRED -> {
            withStyle(SpanStyle(color = mark, background = mark.copy(alpha = 0.16f))) { append(text, n.at, (n.at + 1).coerceAtMost(lineEnd)) }
            append(text, (n.at + 1).coerceAtMost(b), b)
        }
        FormatHint.NO_END -> {
            append(text, n.at, b)
            withStyle(SpanStyle(color = mark, background = mark.copy(alpha = 0.16f))) { append("□") }
        }
    }
    if (b < lineEnd) append("…")
}

// ---------- 排版设置 ----------

/**
 * 一部作品的排版设置：常用的输入时自动排版、引号风格直接露出；一键排版的各条规则（开关，每行一句说明）、空行怎么排收在“更多排版规则”里，点开才列出。
 * 存在作品的 AI 设置旁边（[com.xycs.lingyuereader.data.WorkAssist.format]），随备份走。
 */
@Composable
fun WorkFormatSettingsScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val f = assists[workId]?.format ?: FormatSettings()
    fun update(t: (FormatSettings) -> FormatSettings) = vm.writingAi.updateAssist(workId) { it.copy(format = t(it.format)) }
    val snackbar = rememberWritingMessages(vm)
    var moreOpen by rememberSaveable(workId) { mutableStateOf(false) }
    SubPageScaffold("排版设置", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "typing") {
                GroupedSection(
                    footer = "打出 ... 或第三个点变成“……”，-- 变成“——”；汉字后面紧跟着打的半角 , . ? ! : ; 换成全角（数字后面的点不换，好打小数）；" +
                        "\" ' 按这一段里的配对变成开引号或闭引号。拼音还没上屏时不动；换得不对，点一次撤销就回到换之前。键盘上方的快捷标点照旧。",
                ) {
                    row { ToggleRow("输入时自动排版", f.onInput, subtitle = "边打边把标点换成中文的样子") { on -> update { it.copy(onInput = on) } } }
                }
            }
            // 常用的只露两样：输入时自动排版（上面）和引号风格；一键排版的其余规则收进“更多排版规则”，值都不变。
            item(key = "quoteStyle") {
                GroupedSection(header = "引号风格", footer = "输入时自动排版和一键排版都按这个风格换引号。") {
                    row { SegmentedRow(QuoteStyle.entries.map { it to it.label }, f.quoteStyle) { v -> update { it.copy(quoteStyle = v) } } }
                }
            }
            item(key = "more") {
                GroupedSection(
                    footer = "编辑器“整理 → 自动排版”和作品页“排版…”（全书排版）按这些规则排：先列出改了哪些，可以按类勾掉再排；" +
                        "排之前存一个历史版本，排版改的不算码字数。“***”“——”这类单独一行的场景分隔原样不动。",
                ) {
                    row { ExpandRow("更多排版规则", moreOpen, value = if (moreOpen) null else "$FORMAT_MORE_RULES 项") { moreOpen = !moreOpen } }
                }
            }
            if (moreOpen) {
                item(key = "space") {
                    GroupedSection(header = "一键排版", modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                        row {
                            ToggleRow("段首段尾空白", f.trim, subtitle = "去掉段尾的空白和段首普通的缩进（1–2 个全角空格或几个半角空格），段首缩进是编辑器显示时加的，不存进正文；诗歌、落款那样排的留着") { on ->
                                update { it.copy(trim = on) }
                            }
                        }
                        row {
                            ToggleRow("段内多余空格", f.spaces, subtitle = "汉字、中文标点之间的半角空格去掉（“第三章 归来”也会排成“第三章归来”），全角空格留着；英文单词、数字之间的压成一个") { on -> update { it.copy(spaces = on) } }
                        }
                        row {
                            ToggleRow("中英文之间加空格", f.mixedSpace, subtitle = "汉字和英文、数字之间加一个空格；网文一般不加") { on -> update { it.copy(mixedSpace = on) } }
                        }
                    }
                }
                item(key = "blank") {
                    GroupedSection(header = "空行", footer = "压成一行：连续几个空行只留一个；不留：段与段之间一个空行也不留。全文开头结尾的空行都去掉。",
                        modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                        row { SegmentedRow(BlankLines.entries.map { it to it.label }, f.blankLines) { v -> update { it.copy(blankLines = v) } } }
                    }
                }
                item(key = "punct") {
                    GroupedSection(header = "标点", modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                        row {
                            ToggleRow("标点换全角", f.punct, subtitle = "中文里的 , . ? ! : ; ( ) 换成 ，。？！：；（）；小数、时间、“1.”这类编号、网址、文件名、代码、:) 和英文句子不动") { on ->
                                update { it.copy(punct = on) }
                            }
                        }
                        row {
                            ToggleRow("省略号、破折号", f.marks, subtitle = "... 。。。 ··· 单个… 换成……；-- 单个— ── 换成——") { on -> update { it.copy(marks = on) } }
                        }
                        row {
                            ToggleRow("合并重复标点", f.repeats, subtitle = "，，换成，、。。换成。；有意叠用的！！！？？留着") { on -> update { it.copy(repeats = on) } }
                        }
                        if (f.repeats) {
                            row { ToggleRow("！？最多留 3 个", f.limitBangs, subtitle = "！！！！！换成！！！") { on -> update { it.copy(limitBangs = on) } } }
                        }
                        row {
                            ToggleRow("段末缺标点时提示", f.endHint, subtitle = "以汉字结尾的段、引号里最后是汉字的对话：只在预览里提示，不自动补") { on ->
                                update { it.copy(endHint = on) }
                            }
                        }
                    }
                }
                item(key = "quote") {
                    GroupedSection(header = "引号", modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                        row {
                            ToggleRow("统一引号", f.quotes, subtitle = "中文里的直引号按前后的引号和位置换成开引号或闭引号；已有的中文引号不改方向，「」风格下按里外层换成「」『』；判断不了、配不上的不动，预览里标出来") { on ->
                                update { it.copy(quotes = on) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** “更多排版规则”里收着几项：段首段尾空白、段内多余空格、中英文空格、空行、标点换全角、省略号破折号、合并重复标点、！？最多 3 个、段末缺标点提示、统一引号。 */
private const val FORMAT_MORE_RULES = 10

// ---------- 全书排版 ----------

/**
 * 全书排版：先看各章要改几处（按类计数，可以关掉一类），定稿的章不排并列出来；点“排版”按目录一章章排，有进度、可以停。
 * 每章排之前存一个历史版本，排版改的不算码字数。
 */
@Composable
fun WorkFormatScreen(vm: AppViewModel, workId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val runAll by vm.writing.formatRun.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val settings = assists[workId]?.format
    val run = runAll?.takeIf { it.workId == workId }
    val running = run?.running == true
    var skip by remember { mutableStateOf(emptySet<FormatKind>()) }
    var preview by remember { mutableStateOf<BookFormat?>(null) }
    // 设置改了、排完了（正文变了）都重看一遍；排的时候不看。
    LaunchedEffect(settings, running) {
        if (settings == null || running) return@LaunchedEffect
        preview = vm.writing.previewFormat(workId, settings)
    }
    val snackbar = rememberWritingMessages(vm)
    val title = works.firstOrNull { it.id == workId }?.title
    SubPageScaffold(
        "全书排版",
        onBack = {
            vm.writing.clearFormatRun()
            vm.back()
        },
        actions = { BarTextAction("设置", { vm.open(Screen.WorkFormatSettings(workId)) }) },
        snackbar = snackbar,
    ) { padding ->
        val c = LocalGroupedColors.current
        val p = preview
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            if (run != null) {
                item(key = "run") {
                    GroupedSection(footer = "每章排之前的正文存了一个历史版本；排版改的不算码字数。") {
                        if (running) {
                            row { TwoLine("正在排版 ${(run.done + 1).coerceAtMost(run.total)}/${run.total}", run.current, trailing = { Spinner() }) }
                            row { ActionRow("停止", destructive = true) { vm.writing.stopFormat() } }
                        } else {
                            row {
                                TwoLine(
                                    if (run.stopped) "排到第 ${run.done} 章停了" else "排好了",
                                    "改了 ${run.changed} 章、${run.edits} 处" + if (run.skipped.isNotEmpty()) "；定稿的 ${run.skipped.size} 章没排" else "",
                                )
                            }
                        }
                    }
                }
            }
            if (p == null) {
                item(key = "loading") { LoadingState("正在看各章……") }
                return@LazyColumn
            }
            val counts = FormatKind.entries.mapNotNull { k -> p.chapters.sumOf { ch -> ch.result.changes.count { it.kind == k } }.takeIf { it > 0 }?.let { k to it } }
            val included = counts.filter { it.first !in skip }.sumOf { it.second }
            val todo = p.chapters.filter { ch -> ch.result.changes.any { it.kind !in skip } }
            item(key = "summary") {
                if (counts.isEmpty()) {
                    GroupedSection(footer = title?.let { "《$it》按现在的排版设置不用改。" }) { row { EmptyRow("各章都排好了") } }
                } else {
                    GroupedSection(header = "共 $included 处 · ${todo.size} 章", footer = "关掉的这类不排。规则在右上角“设置”里改。") {
                        counts.forEach { (k, n) ->
                            row { ToggleRow(k.label, k !in skip, subtitle = "$n 处", enabled = !running) { on -> skip = if (on) skip - k else skip + k } }
                        }
                    }
                    PrimaryButton(
                        if (todo.isEmpty()) "排版" else "排版 ${todo.size} 章",
                        { if (settings != null) vm.writing.formatAll(workId, settings, settings.kinds - skip) },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        enabled = todo.isNotEmpty() && !running && settings != null,
                    )
                }
            }
            val unpaired = p.chapters.sumOf { ch -> ch.result.notes.count { it.hint == FormatHint.UNPAIRED } }
            val noEnd = p.chapters.sumOf { ch -> ch.result.notes.count { it.hint == FormatHint.NO_END } }
            if (unpaired + noEnd > 0) {
                item(key = "hints") {
                    GroupedSection(header = "提示", footer = "只是提示，不会自动改；在编辑器里“整理 → 自动排版”能看到是哪几段。") {
                        if (unpaired > 0) row { ValueRow(FormatHint.UNPAIRED.label, "$unpaired 处") }
                        if (noEnd > 0) row { ValueRow(FormatHint.NO_END.label, "$noEnd 处") }
                    }
                }
            }
            if (todo.isNotEmpty()) {
                item(key = "chaptersHeader") { GroupedListHeader("要改的章") }
                items(todo, key = { it.chapter.id }) { ch ->
                    val n = ch.result.changes.count { it.kind !in skip }
                    GroupedListCell(ch === todo.first(), ch === todo.last(), Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                        NavRow(ch.chapter.title, value = "$n 处") { vm.open(Screen.Editor(workId, ch.chapter.id)) }
                    }
                }
            }
            if (p.locked.isNotEmpty()) {
                item(key = "locked") {
                    Text(
                        "定稿的 ${p.locked.size} 章不排：${p.locked.joinToString("、") { it.title }}。要排先解锁。",
                        color = c.secondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
