package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.data.PulseKind
import com.xycs.lingyuereader.data.PulseRow
import com.xycs.lingyuereader.data.PulseState
import com.xycs.lingyuereader.data.StoryDigest
import com.xycs.lingyuereader.data.StoryDigests
import com.xycs.lingyuereader.data.StoryPulse
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

// ---------- 分层前情、线索体检：作品页上的入口和对话框 ----------

/**
 * 作品页“更多功能”里的“故事至今”一行：写着写到第几章、作者改过没有，没有时说收尾时会自动整理。点了看、改。
 */
@Composable
internal fun TreeStoryRow(story: StoryDigest?, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        RowIcon(LingYueIcons.Notes, c.tint)
        Column(Modifier.weight(1f)) {
            Text("故事至今", color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    story == null -> "还没有：“这章写完了”收尾时自动整理"
                    else -> "${story.text.lines().count { it.isNotBlank() }} 句" +
                        (if (story.throughNo > 0) " · 写到第 ${story.throughNo} 章" else "") + if (story.edited) " · 你改过的" else ""
                },
                color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** 作品页“更多功能”里的“线索与节奏体检”一行：[note] 是一句结果（“2 条冷了”），还没算时为空。 */
@Composable
internal fun TreePulseRow(note: String?, warn: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow(onClick = onClick) {
        RowIcon(LingYueIcons.Threads, c.tint)
        Column(Modifier.weight(1f)) {
            Text("线索与节奏体检", color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
            Text(note ?: "每条线、伏笔、主要人物最近一次推进在哪一章", color = if (warn) c.warning else c.secondary, fontSize = TypeScale.Footnote,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** 体检结果的一句话：冷了几条、原地踏步几条；都没有时说一切在推进。 */
internal fun pulseNote(rows: List<PulseRow>): String {
    val cold = rows.count { it.state == PulseState.COLD }
    val stalled = rows.count { it.state == PulseState.STALLED }
    return when {
        rows.isEmpty() -> "细纲卡写了推进的线、账本里有记录后才有内容"
        cold == 0 && stalled == 0 -> "${rows.size} 项都在推进"
        else -> listOfNotNull("$cold 条冷了".takeIf { cold > 0 }, "$stalled 条原地踏步".takeIf { stalled > 0 }).joinToString("，")
    }
}

/**
 * 看、改故事至今或一卷的卷摘要。[edited] 为真是作者改过的（收尾时不再自动更新），这时多一个“改回自动”。
 * 存下的就是“你改过的”；清空再存是删掉，下次收尾重新整理。
 */
@Composable
internal fun DigestEditDialog(
    title: String,
    summary: String,
    initial: String,
    edited: Boolean,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResume: (() -> Unit)? = null,
) {
    // 对话框开着时后台收尾可能刚整理完前情、[initial] 变了。作者没动过（[typed] 为空）就跟着显示新的；
    // 动过了不盖掉正在打的字，只提示一句，让作者自己选用哪一份。[base] 是作者开始改时看到的那一份。
    var typed by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf(initial) }
    val text = typed ?: initial
    val c = LocalGroupedColors.current
    GlassDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        GroupedTextField(value = text, onValueChange = {
            if (typed == null) base = initial
            typed = it
        }, label = label, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 360.dp))
        if (typed != null && initial != base) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("前情刚被自动更新过。存下会用你改的这一份。", color = c.warning, fontSize = TypeScale.Caption, modifier = Modifier.weight(1f))
                PillButton("换成新的", { typed = null })
            }
        }
        if (edited && onResume != null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(TAG_EDITED, c.warning)
                Text("收尾时不会盖掉", color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.weight(1f))
                PillButton("改回自动", { onResume(); onDismiss() })
            }
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onSave(text); onDismiss() }, confirmText = "存下", confirmEnabled = text != initial)
    }
}

/** 作者改过的前情挂的标签。 */
internal const val TAG_EDITED = "你改过的"

/** 故事至今对话框的说明。 */
internal const val STORY_SUMMARY = "不超过 ${StoryDigests.STORY_SENTENCES} 句的全书梗概，“这章写完了”收尾时按新写的章摘要自动更新。" +
    "写正文、拟细纲时放在前情最前面，只用在它写到的那章之后的章。你改过的不会被自动盖掉。"

/** 卷摘要对话框的说明。 */
internal const val VOLUME_SUMMARY = "从这一卷各章的摘要汇总，300 到 600 字；一卷有 ${StoryDigests.VOLUME_EVERY} 章摘要、写完一卷时收尾顺带整理。" +
    "写后面几卷时代替这一卷的章摘要带给 AI。你改过的不会被自动盖掉。"

/**
 * 线索与节奏体检表：每行一条线、伏笔或主要人物，写着最近一次推进在第几章、现在怎样；冷了、原地踏步的排前面，
 * 下面一行为什么这么判，再下面“带进下一章”（写进下一章的作者要求，写本章、拟细纲时照办）。
 */
@Composable
internal fun PulseDialog(rows: List<PulseRow>?, onDismiss: () -> Unit, onCarry: (PulseRow) -> Unit) {
    GlassDialog(
        show = true,
        title = "线索与节奏体检",
        summary = "按细纲卡的推进的线、本章改变了什么、伏笔动作和故事账本算，本机算、不花钱。线 ${StoryPulse.COLD_THREAD} 章、" +
            "人物 ${StoryPulse.COLD_PERSON} 章、伏笔 ${StoryPulse.COLD_FORESHADOW} 章没推进算冷了；连着 ${StoryPulse.STALL_RUN} 章挂着名却没写到它的变化算原地踏步。",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            when {
                rows == null -> LoadingRow("正在体检……")
                rows.isEmpty() -> EmptyRow("还没有可查的：细纲卡写了“推进的线”、账本里有人物和伏笔后再来看。")
                else -> rows.forEach { r -> PulseLine(r, onCarry) }
            }
        }
        // 只是看：一个“知道了”就够，不放两个都是关掉的键。
        DialogOkButton("知道了", onClick = onDismiss)
    }
}

@Composable
private fun PulseLine(r: PulseRow, onCarry: (PulseRow) -> Unit) {
    val c = LocalGroupedColors.current
    val color = when (r.state) {
        PulseState.COLD, PulseState.STALLED -> c.warning
        PulseState.DEFERRED -> c.secondary
        PulseState.OK -> c.success
    }
    // 读屏：名字和两枚标签那一行念成一句（“线索林月，冷了，最近推进在第 3 章”），里面的零碎不再各念一遍；
    // 说明和“带进下一章”按钮仍各是一个节点，按钮不被夹在一串碎片中间。
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.clearAndSetSemantics {
            contentDescription = "${r.kind.label}${r.name}，${r.state.label}" + (r.lastNo?.let { "，最近推进在第 $it 章" } ?: "")
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(r.kind.label, categoryColor(if (r.kind == PulseKind.FORESHADOW) CategoryTone.CYAN else if (r.kind == PulseKind.PERSON) CategoryTone.PURPLE else CategoryTone.SUCCESS, c))
            Text(r.name, color = c.label, fontSize = TypeScale.Body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Tag(r.state.label, color)
        }
        Text(
            listOfNotNull(r.lastNo?.let { "最近推进：第 $it 章" + r.lastTitle.takeIf { t -> t.isNotBlank() }?.let { t -> "《$t》" }.orEmpty() } ?: "还没推进过",
                r.note.takeIf { it.isNotBlank() }).joinToString(" · "),
            color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, modifier = Modifier.padding(top = 2.dp),
        )
        if (r.state == PulseState.COLD || r.state == PulseState.STALLED) {
            Row(Modifier.padding(top = 4.dp)) { PillButton("带进下一章", { onCarry(r) }, style = PillStyle.Tinted) }
        }
    }
}
