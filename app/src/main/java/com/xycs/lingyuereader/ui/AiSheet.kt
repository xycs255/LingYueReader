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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.ai.AiAssistant
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPrompts
import com.xycs.lingyuereader.ai.AiSession
import com.xycs.lingyuereader.ai.AiSource
import com.xycs.lingyuereader.ai.AiTask
import com.xycs.lingyuereader.ai.AiTurn
import com.xycs.lingyuereader.ai.QuickAsk
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 面板里要做的事，由阅读界面接到 ViewModel。 */
internal class AiActions(
    val ask: (String) -> Unit,
    val askKind: (QuickAsk) -> Unit,
    val retry: () -> Unit,
    val regenerate: () -> Unit,
    /** 点引用的原文：跳到第几章第几块。 */
    val jump: (chapter: Int, block: Int) -> Unit,
    /** 划选问 AI 的回答存成批注；不是划选来的对话时为空。 */
    val saveNote: ((String) -> Unit)? = null,
)

/**
 * AI 面板：划选后问 AI、前情提要、问这本书共用。上面是选中的文字或说明，中间一问一答（回答边到边显示），
 * 底下快捷问题和输入框。回答下面写明由哪家服务商的哪个模型生成。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSheet(session: AiSession, config: AiConfig, palette: ReaderPalette, tint: Color, actions: AiActions) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        val task = session.task
        val loading = session.turns.lastOrNull()?.loading == true
        val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            val title = when (task) {
                is AiTask.Selection -> "问 AI"
                is AiTask.Recap -> AiAssistant.RECAP
                is AiTask.AskBook -> "问这本书"
            }
            SheetNavBar(
                title,
                back = null,
                action = if (task is AiTask.Recap && !loading && session.turns.isNotEmpty()) "重新生成" to actions.regenerate else null,
            ) {}
            val scroll = rememberScrollState()
            // 回答长出来时跟着滚到底；读者往上翻着看时不打扰。减少动效时直接跳到底。
            val last = session.turns.lastOrNull()
            LaunchedEffect(session.turns.size, last?.answer?.length, last?.error) {
                if (scroll.maxValue - scroll.value < 600) {
                    if (Effects.noMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (task) {
                    is AiTask.Selection -> QuoteBlock(task.text)
                    is AiTask.Recap -> if (session.cachedAt > 0) {
                        Text(
                            "这是之前在本章生成的提要（${java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(session.cachedAt))}）。读了新内容可以点“重新生成”。",
                            color = c.secondary,
                            fontSize = TypeScale.Caption,
                            lineHeight = TypeScale.CaptionLine,
                        )
                    }
                    is AiTask.AskBook -> if (session.turns.isEmpty()) {
                        Text(
                            "只在你读过的部分里找答案，并标出引用的原文，不会剧透后面的内容。",
                            color = c.secondary,
                            fontSize = TypeScale.Caption,
                            lineHeight = TypeScale.CaptionLine,
                        )
                        // 胶囊能点的范围比看着的高 12dp，上下不再另加间距。
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("目前出场的主要人物有哪些？", "他们之间是什么关系？", "这一章讲了什么？").forEach { q ->
                                Chip(q, enabled = true) { actions.ask(q) }
                            }
                        }
                    }
                }
                session.turns.forEachIndexed { i, turn ->
                    // 前情提要的第一轮就是提要本身，不显示“问题”。
                    if (!(task is AiTask.Recap && i == 0)) QuestionBubble(turn.question)
                    Answer(turn, actions)
                }
            }
            if (task is AiTask.Selection) {
                // 快捷问题在设置里可以改、可以加，多了就横着滑。
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    config.asks.forEach { k -> Chip(k.label, enabled = !loading, strong = session.turns.isEmpty()) { actions.askKind(k) } }
                }
            }
            AskField(
                hint = when (task) {
                    is AiTask.Selection -> "问点别的……"
                    is AiTask.Recap -> "接着问……"
                    is AiTask.AskBook -> "问问这本书……"
                },
                enabled = !loading,
                onSend = actions.ask,
            )
            Text(
                "${config.providerLabel} · ${config.model} 生成，可能有误",
                color = c.tertiary,
                fontSize = TypeScale.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 12.dp),
            )
        }
    }
}

/** 选中的文字：左边一道竖线，像引文。 */
@Composable
private fun QuoteBlock(text: String) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(Radius.Full).background(c.tint.copy(alpha = 0.6f)))
        Text(
            text,
            color = c.secondary,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun QuestionBubble(text: String) {
    val c = LocalGroupedColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = c.label,
            fontSize = TypeScale.Body,
            lineHeight = TypeScale.BodyLine,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(Radius.Large))
                .background(c.fill)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** 一段回答：边到边显示；可以选中复制；问这本书时下面列出引用的原文，点了跳过去。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answer(turn: AiTurn, actions: AiActions) {
    val c = LocalGroupedColors.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (turn.answer.isNotEmpty()) {
            SelectionContainer {
                Text(cited(turn.answer, c.tint), color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
            }
        }
        when {
            turn.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                ErrorText(turn.error, Modifier.weight(1f))
                Text(
                    "重试",
                    color = c.tint,
                    fontSize = TypeScale.Body,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.Medium)).clickable(role = Role.Button, onClick = actions.retry).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            turn.loading -> Thinking(turn.answer.isEmpty(), turn.status)
            else -> {
                val cited = AiPrompts.citations(turn.answer).mapNotNull { n -> turn.sources.firstOrNull { it.index == n } }
                if (cited.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cited.forEach { s -> SourceRow(s) { actions.jump(s.chapter, s.block) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AnswerAction("复制") { copy(context, turn.answer) }
                    actions.saveNote?.let { save -> AnswerAction("存为批注") { save(turn.answer) } }
                }
            }
        }
    }
}

/** 回答下面的小字操作：复制、存为批注。 */
@Composable
private fun AnswerAction(label: String, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Text(
        label,
        color = c.secondary,
        fontSize = TypeScale.Caption,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/** 回答里的 [2] 用强调色标出，和下面的出处对上。 */
private fun cited(answer: String, tint: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    Regex("""\[(\d{1,2})]""").findAll(answer).forEach { m ->
        append(answer.substring(at, m.range.first))
        withStyle(SpanStyle(color = tint, fontSize = TypeScale.Footnote, fontWeight = FontWeight.Medium)) { append(m.value) }
        at = m.range.last + 1
    }
    append(answer.substring(at))
}

/** 引用的一段原文：编号、章名、头几行；点了跳到书里那一段。 */
@Composable
private fun SourceRow(s: AiSource, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Medium))
            .background(c.cell)
            .clickable(onClickLabel = "跳到原文", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text("[${s.index}]", color = c.tint, fontSize = TypeScale.Caption, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(s.chapterTitle, color = c.label, fontSize = TypeScale.Caption, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.text.replace('\n', ' '), color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 6.dp).size(14.dp).align(Alignment.CenterVertically))
    }
}

/** 等回答时：还没字时说“正在思考”，字出来了只留一个小点在末尾闪。 */
@Composable
private fun Thinking(empty: Boolean, status: String? = null) {
    val c = LocalGroupedColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner()
        if (empty) Text(status ?: "正在思考……", color = c.secondary, fontSize = TypeScale.Caption)
    }
}

/** 快捷问题、示例问题：胶囊按钮（[PillButton]）。[strong] 时铺强调色，告诉读者从这里开始；其余淡强调色。 */
@Composable
private fun Chip(label: String, enabled: Boolean, strong: Boolean = false, onClick: () -> Unit) {
    PillButton(label, onClick, style = if (strong) PillStyle.Filled else PillStyle.Tinted, enabled = enabled)
}

/** 底部的输入框和发送钮。 */
@Composable
private fun AskField(hint: String, enabled: Boolean, onSend: (String) -> Unit) {
    val c = LocalGroupedColors.current
    var text by remember { mutableStateOf("") }
    val send = {
        if (enabled && text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(Radius.XLarge))
            .background(c.cell)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupedTextField(
            value = text,
            onValueChange = { text = it },
            label = hint,
            useLabelAsPlaceholder = true,
            maxLines = 4,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = TypeScale.Body),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        val ready = enabled && text.isNotBlank()
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (ready) c.tint else c.fill)
                .clickable(enabled = ready, role = Role.Button) { send() }
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LingYueIcons.Send, contentDescription = null, tint = if (ready) c.onTint else c.tertiary, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("灵阅", text))
}
