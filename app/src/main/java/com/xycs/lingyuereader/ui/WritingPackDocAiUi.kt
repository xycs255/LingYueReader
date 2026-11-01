package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.ai.DocAiKind
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text

/**
 * 设定文档的一次 AI 帮手：哪一种（[kind]）、作者的一句要求、精简到约多少字（[target]）。
 * [selection] 是打开对话框那一刻编辑器里选中的那段（在对话框里打字时编辑器的选区会变，所以当时就记下），
 * [onlySelection] 是只改它还是改全文。
 */
internal data class DocAiAsk(
    val kind: DocAiKind,
    val request: String = "",
    val selection: IntRange? = null,
    val onlySelection: Boolean = true,
    val target: Int = 0,
) {
    /** 这次要改的那段：没选或选了改全文为空。 */
    val part: IntRange? get() = selection?.takeIf { onlySelection && kind == DocAiKind.REVISE }
}

/**
 * 一次 AI 帮手跑的状态：[original] 是开始时编辑器里的全文（对比、写入前存历史版本都用它），[streamed] 是已经流出来多少字，
 * [result] 是过了格式检查的新全文（null 是还在跑或出错了），[missing] 是精简后核对出来找不到的专名、数字。
 */
internal data class DocAiRun(
    val ask: DocAiAsk,
    val original: String,
    val streamed: Int = 0,
    val result: String? = null,
    val missing: List<String> = emptyList(),
    val error: String? = null,
) {
    val running: Boolean get() = result == null && error == null
}

/**
 * 填要求的对话框：起草、按要求改填一句话（改的时候有选中的文字可以只改选中的），精简填“约多少字”。[words] 是文档现在的字数。
 */
@Composable
internal fun DocAiAskDialog(ask: DocAiAsk, words: Int, onDismiss: () -> Unit, onGo: (DocAiAsk) -> Unit) {
    val c = LocalGroupedColors.current
    val selection = ask.selection.takeIf { ask.kind == DocAiKind.REVISE }
    var request by remember(ask) { mutableStateOf(ask.request) }
    var onlySelection by remember(ask) { mutableStateOf(ask.onlySelection) }
    var target by remember(ask) { mutableStateOf(ask.target.takeIf { it > 0 }?.toString().orEmpty()) }
    val title = when (ask.kind) {
        DocAiKind.DRAFT -> "让 AI 起草"
        DocAiKind.REVISE -> "AI 按要求改"
        DocAiKind.CONDENSE -> "AI 精简"
    }
    val summary = when (ask.kind) {
        DocAiKind.DRAFT -> "AI 会参考作品信息、同类和相关的设定、规划树和已写章节的摘要（不带正文全文），按这一类文档的格式写。先给你看，点“用这个”才写入。"
        DocAiKind.REVISE -> "改完先给你看对比，采纳才写入；写入前的一版存进历史版本。"
        DocAiKind.CONDENSE -> "设定事实一个不丢（人名、数值、关系、规则），只删重复和描写性的话。精简后在本机核对专名和数字，丢了的会列出来。"
    }
    GlassDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ask.kind) {
                DocAiKind.DRAFT, DocAiKind.REVISE -> {
                    GroupedTextField(
                        value = request,
                        onValueChange = { request = it },
                        label = if (ask.kind == DocAiKind.DRAFT) "要求，比如：主角的金手指" else "要求，比如：把势力划分改成三方",
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ask.kind == DocAiKind.REVISE && selection != null) {
                        SegmentedControl(listOf(true to "只改选中的 ${selection.last - selection.first + 1} 字", false to "改全文"), onlySelection) { onlySelection = it }
                    }
                }
                DocAiKind.CONDENSE -> {
                    GroupedTextField(
                        value = target,
                        onValueChange = { v -> target = v.filter { it.isDigit() }.take(7) },
                        label = "精简到约多少字",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("现在 $words 字。默认是这份文档在“AI 每次带多少”里放得下的字数，放得下时是原长的一半。",
                        color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
                }
            }
        }
        val number = target.toIntOrNull() ?: 0
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                onGo(ask.copy(request = request.trim(), onlySelection = onlySelection, target = number))
            },
            confirmText = when (ask.kind) {
                DocAiKind.DRAFT -> "起草"
                DocAiKind.REVISE -> "改"
                DocAiKind.CONDENSE -> "精简"
            },
            confirmEnabled = when (ask.kind) {
                DocAiKind.DRAFT -> true
                DocAiKind.REVISE -> request.isNotBlank()
                DocAiKind.CONDENSE -> number in 1 until words.coerceAtLeast(2)
            },
        )
    }
}

/**
 * AI 帮手的结果页（盖在编辑器上）：跑的时候显示进度和“停止”；出来以后起草给预览，改和精简给按行对比（复用草稿页的对比），
 * 精简另列核对结果。采纳（“用这个”）才写入，也可以重来、改一改要求、放弃。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocAiPane(
    run: DocAiRun,
    padding: PaddingValues,
    onStop: () -> Unit,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val kind = run.ask.kind
    var view by remember(run.result) { mutableStateOf(DraftVersionView.DIFFERENCE) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val about = when (kind) {
                DocAiKind.CONDENSE -> "精简到约 ${run.ask.target} 字"
                else -> "要求：" + run.ask.request.ifBlank { "按文档类型写一份完整的初稿" } +
                    if (run.ask.part != null) "（只改选中的一段）" else if (kind == DocAiKind.REVISE) "（改全文）" else ""
            }
            Text(about, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
            val result = run.result
            AnimatedContent(Triple(run.running, run.error != null, result != null), transitionSpec = { Motion.fadeThrough() using null }, label = "docAi") { (running, failed, _) ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        running -> PanelBusy(if (run.streamed > 0) "${kind.running}……已写 ${run.streamed} 字" else "${kind.running}……")
                        failed -> ErrorText(run.error.orEmpty())
                        result != null -> DocAiResult(run, result, view) { view = it }
                    }
                }
            }
        }
        // 底下一排按钮：360dp 上放不下一行时折到第二行。
        FlowRow(
            Modifier.fillMaxWidth().background(c.background).padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = padding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (run.running) {
                PillButton("停止", onStop, style = PillStyle.Filled)
            } else {
                if (run.result != null) PillButton(if (kind == DocAiKind.DRAFT) "用这个" else "采纳", onAccept, style = PillStyle.Filled)
                PillButton("重来", onRetry)
                PillButton("改一改要求", onEdit)
                PillButton("放弃", onDiscard)
            }
        }
    }
}

/** 结果本身：起草是全文预览，改和精简是对比（可以切到新稿全文），精简再列核对结果。 */
@Composable
private fun DocAiResult(run: DocAiRun, result: String, view: DraftVersionView, onView: (DraftVersionView) -> Unit) {
    val c = LocalGroupedColors.current
    val kind = run.ask.kind
    val compare = kind != DocAiKind.DRAFT && run.original.isNotBlank()
    if (kind == DocAiKind.CONDENSE) {
        Text("原稿 ${WordCount.of(run.original)} 字 → 新稿 ${WordCount.of(result)} 字（目标约 ${run.ask.target} 字）",
            color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
        DocAiMissing(run.missing)
    }
    if (compare && result.trim() == run.original.trim()) {
        Text("AI 没改动这份文档。", color = c.secondary, fontSize = TypeScale.Caption)
    }
    if (compare) {
        SegmentedControl(listOf(DraftVersionView.DIFFERENCE to "对比", DraftVersionView.CURRENT to "新稿"), view, onSelect = onView)
    }
    Column(Modifier.fillMaxWidth().background(c.cell, RoundedCornerShape(Radius.Medium))) {
        // 比之前去掉末尾的空行：原稿结尾多一个换行时，对比里会多出一块空的“原稿中”。
        DraftRevisionBody(
            if (compare) view else DraftVersionView.CURRENT, result.trimEnd(), run.original.trimEnd().takeIf { compare }, "原稿",
            removedLabel = "原稿中", addedLabel = "新稿中", currentLabel = "新稿", indent = false,
        )
    }
}

/** 精简后的核对：都在给一句放心话；丢了的用提醒色列出来，采纳前看一眼。 */
@Composable
private fun DocAiMissing(missing: List<String>) {
    val c = LocalGroupedColors.current
    if (missing.isEmpty()) {
        Text("核对过：原稿里的专名、数字新稿里都还在。", color = c.success, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
        return
    }
    val head = "新稿里找不到这 ${missing.size} 处专名、数字，采纳前看看是不是删掉了："
    Column(
        Modifier.fillMaxWidth().background(c.warning.copy(alpha = 0.10f), RoundedCornerShape(Radius.Medium)).padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(head, color = c.warning, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
        Text(missing.joinToString("、"), color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
    }
}
