package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.data.Beat
import com.xycs.lingyuereader.data.ChapterStatus

/**
 * 编辑器底栏的四个标签。[description] 是读屏念的（只有一个字的“写”念“AI 写作”，“更多”和右上角“⋯”分开叫“更多工具”），
 * 为空时念 [label]。
 */
internal enum class EditorToolGroup(val label: String, val description: String? = null) {
    WRITE("写", "AI 写作"), CHECK("查"), ORGANIZE("整理"), MORE("更多", "更多工具")
}

internal enum class EditorRecommendationKind {
    PENDING_AI, UNLOCK, CONFIGURE_AI, DRAFT_CHAPTER, REVISE_SELECTION, REVIEW_CHAPTER, WRITE_BEAT, FINISH_CHAPTER, CONTINUE,
    // 下面几样只在 ✦ 按位置推荐（[sparkActions]）里出现。
    EXPAND_SELECTION, CONDENSE_SELECTION, DECLICHE_SELECTION, POLISH_PARAGRAPH, EXPAND_PARAGRAPH,
}

internal data class EditorRecommendation(
    val kind: EditorRecommendationKind,
    val label: String,
    val reason: String,
    /** AI 面板在上方时，此处只显示状态；所有采纳和关闭操作仍由原面板完成。 */
    val enabled: Boolean = true,
)

/** 推荐是到现有安全流程的入口，不会自行采纳候选稿或把整理结果写入账本。 */
internal fun editorRecommendation(
    text: CharSequence,
    selection: TextRange,
    status: ChapterStatus?,
    aiReady: Boolean,
    inline: InlineWrite? = null,
    aiState: WriteAiState? = null,
    /** 本章细纲卡里第一拍没写的（序号和这一拍写什么）；没有节拍、都写过了为空。 */
    nextBeat: Beat? = null,
    /** 看着像写完了（细纲卡的节拍都写过了，或字数到了目标篇幅的下限）：为什么这么看，见 [finishHint]；不像为空。 */
    finished: String? = null,
    /** 本章细纲卡缺“本章事实与结果”或没有节拍：推荐写整章草稿时说一句 AI 会先补上（一键写本章）。 */
    cardMissing: Boolean = false,
): EditorRecommendation {
    if (inline != null) {
        val label = when {
            inline.error != null -> "续写失败"
            inline.loading -> "AI 正在续写"
            else -> "核对续写结果"
        }
        val reason = when {
            inline.error != null -> "在上方重写或撤回"
            inline.loading -> "正文在上方生成"
            else -> "在上方采纳或撤回"
        }
        return EditorRecommendation(EditorRecommendationKind.PENDING_AI, label, reason, enabled = false)
    }
    if (aiState != null) {
        val task = aiState.task
        val label = when {
            aiState.error != null -> "${task.title}失败"
            aiState.loading -> when (task) {
                is WriteAiTask.Edit -> "正在${task.title}"
                is WriteAiTask.Idea -> "正在构思灵感"
                WriteAiTask.Review -> "正在生成点评"
            }
            else -> when (task) {
                is WriteAiTask.Edit -> "核对${task.title}候选"
                is WriteAiTask.Idea -> "查看灵感结果"
                WriteAiTask.Review -> "查看读者点评"
            }
        }
        val reason = when {
            aiState.error != null -> "在上方重试或关闭"
            aiState.loading -> "结果在上方面板生成"
            task is WriteAiTask.Edit -> "在上方采纳或放弃"
            task is WriteAiTask.Idea -> "在上方插入或关闭"
            else -> "在上方查看或关闭"
        }
        return EditorRecommendation(EditorRecommendationKind.PENDING_AI, label, reason, enabled = false)
    }
    if (status == ChapterStatus.FINAL) return EditorRecommendation(EditorRecommendationKind.UNLOCK, "解锁修改", "定稿正文已锁定")
    if (!aiReady) return EditorRecommendation(EditorRecommendationKind.CONFIGURE_AI, "设置写作 AI", "连接后才会发送请求")
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    if (start < end && text.subSequence(start, end).isNotBlank()) {
        return EditorRecommendation(EditorRecommendationKind.REVISE_SELECTION, "改写选中文字", "已选 ${end - start} 字")
    }
    if (text.isBlank()) return EditorRecommendation(EditorRecommendationKind.DRAFT_CHAPTER, "AI 写整章草稿",
        if (cardMissing) "缺细纲、节拍也行：AI 先拟、先拆，再逐拍写" else "先看细纲，再生成候选稿")
    if (status == ChapterStatus.REVISION) return EditorRecommendation(EditorRecommendationKind.REVIEW_CHAPTER, "三道审稿", "本章正在修订")
    // 细纲卡还有没写的拍：一点就从光标处写下一拍，不弹面板（3 个方向、范本按记住的来）。
    if (nextBeat != null) return EditorRecommendation(EditorRecommendationKind.WRITE_BEAT, "写第 ${nextBeat.index} 拍", nextBeat.text)
    // 没有可写的拍了，看着也写完了：一点进收尾页（定稿、整理入账、写摘要一起做）。
    if (finished != null) return EditorRecommendation(EditorRecommendationKind.FINISH_CHAPTER, "这章写完了", finished)
    return EditorRecommendation(EditorRecommendationKind.CONTINUE, "AI 接着写", "打开 AI 写面板，从光标处写")
}

/**
 * 这一章看着写完了没有（推荐“这章写完了”用）：细纲卡有节拍且都写过了，或字数到了目标篇幅的下限
 * （检查规则里写的“字数”；按汉字算的按汉字数）。都不满足为空。正文空的、已定稿的由 [editorRecommendation] 先挡掉。
 */
internal fun finishHint(beatCount: Int, beatsLeft: Boolean, words: Int, minWords: Int?): String? = when {
    beatCount > 0 && !beatsLeft -> "细纲卡的 $beatCount 拍都写完了"
    minWords != null && minWords > 0 && words >= minWords -> "已写 $words 字，到了目标篇幅"
    else -> null
}


// ---------- 底栏四个标签 ----------

/**
 * 编辑器里能点到的每一样（盘点里的每一项）。[label] 是新位置上显示的名字，[legacy] 是旧菜单、旧工具条里的叫法，
 * 用来核对旧入口一个不少都有了去处。
 */
internal enum class EditorEntry(val label: String, val legacy: List<String> = listOf(label)) {
    AI_WRITE("AI 写…"),
    IDEAS("灵感", listOf("书灵", "灵感")),
    DRAFT("整章草稿", listOf("AI 写整章草稿")),
    REVISE("改稿", listOf("润色", "更生动", "扩写", "缩写", "挑错", "按要求改…", "读者点评", "去 AI 腔")),
    PUNCTUATION("标点", listOf("快捷标点")),
    PROBLEMS("问题"),
    BRIEF("本章资料"),
    LEDGER("故事账本"),
    REVIEW("三道审稿"),
    CARD("细纲卡"),
    FINISH("这章写完了"),
    FORMAT("自动排版"),
    HISTORY("历史版本"),
    STATUS("章节状态", listOf("章节状态", "解锁修改")),
    RENAME("改章名"),
    PREVIEW("预览本章"),
    LISTEN("听本章"),
    NEW_CHAPTER("写下一章"),
    SYNC("整理本章进设定包", listOf("整理本章进设定包", "整理入账")),
    REFERENCE("从范本找写法", listOf("从范本找写法", "范本写法")),
    PACK("设定包"),
    PREV_CHAPTER("上一章"),
    NEXT_CHAPTER("下一章"),
}

/**
 * 一个标签展开后的那排大图标。[locked]：定稿章没有“这章写完了”，“章节状态”叫“解锁修改”（名字见 [entryLabel]）；
 * [lastChapter]：最后一章才有“写下一章”。一排三到五个，360dp 宽的屏上也排得下一行。
 */
internal fun editorTabEntries(group: EditorToolGroup, locked: Boolean, lastChapter: Boolean): List<EditorEntry> = when (group) {
    EditorToolGroup.WRITE -> listOf(EditorEntry.AI_WRITE, EditorEntry.IDEAS, EditorEntry.DRAFT, EditorEntry.REVISE, EditorEntry.PUNCTUATION)
    EditorToolGroup.CHECK -> listOf(EditorEntry.PROBLEMS, EditorEntry.BRIEF, EditorEntry.LEDGER, EditorEntry.REVIEW)
    EditorToolGroup.ORGANIZE -> listOfNotNull(EditorEntry.CARD, EditorEntry.FINISH.takeIf { !locked }, EditorEntry.FORMAT, EditorEntry.HISTORY)
    EditorToolGroup.MORE -> listOfNotNull(EditorEntry.STATUS, EditorEntry.RENAME, EditorEntry.PREVIEW, EditorEntry.LISTEN, EditorEntry.NEW_CHAPTER.takeIf { lastChapter })
}

/** 右上“⋯”里只留不常用的几样（三道审稿“查”里也有：收尾页、问题面板也能去审稿，这里留一项给习惯从菜单找的）。 */
internal val EDITOR_OVERFLOW = listOf(EditorEntry.REVIEW, EditorEntry.SYNC, EditorEntry.REFERENCE, EditorEntry.PACK)

/** 顶栏章名左右的箭头。 */
internal val EDITOR_TOP_BAR = listOf(EditorEntry.PREV_CHAPTER, EditorEntry.NEXT_CHAPTER)

internal fun entryLabel(entry: EditorEntry, locked: Boolean): String =
    if (entry == EditorEntry.STATUS && locked) "解锁修改" else entry.label

/** 推荐“这章写完了”的另一种情况：字数到了目标篇幅下限的九成、还差一点（到了下限由 [finishHint] 说）。 */
internal fun nearFinishHint(words: Int, minWords: Int?): String? =
    if (minWords != null && minWords > 0 && words < minWords && words * 10 >= minWords * 9) "已写 $words 字，接近目标 $minWords 字" else null

/** 选中的字有这么多以上时推荐“缩写”，少的推荐“扩写”。 */
internal const val LONG_SELECTION = 120

/** 光标在哪：选中了字、空章、空行、段末、章末（后面只剩空白）、段中间。 */
internal enum class CaretSpot { SELECTION, EMPTY_CHAPTER, BLANK_LINE, PARAGRAPH_END, CHAPTER_END, MID_PARAGRAPH }

/** [from, to) 里是不是只有空白。只往后看到第一个不是空白的字，长章也不整段复制。 */
private fun CharSequence.blankIn(from: Int, to: Int): Boolean {
    for (i in from until to) if (!this[i].isWhitespace()) return false
    return true
}

internal fun caretSpot(text: CharSequence, selection: TextRange): CaretSpot {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    if (start < end && !text.blankIn(start, end)) return CaretSpot.SELECTION
    if (text.blankIn(0, text.length)) return CaretSpot.EMPTY_CHAPTER
    if (text.blankIn(end, text.length)) return CaretSpot.CHAPTER_END
    var lineStart = end
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    var lineEnd = end
    while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
    if (text.blankIn(lineStart, lineEnd)) return CaretSpot.BLANK_LINE
    if (text.blankIn(end, lineEnd)) return CaretSpot.PARAGRAPH_END
    return CaretSpot.MID_PARAGRAPH
}

/**
 * ✦ 按光标所在的位置给 1–3 个推荐，第一个就是底栏 ✦ 上显示、一点就做的那个。只看正文和光标，不发请求、不算重的东西。
 * - AI 在写、有结果等采纳、定稿、还没设置 AI：只有一项状态（同 [editorRecommendation]）。
 * - 选中了文字：润色；缩写（选得多）或扩写；选中的命中 AI 腔时再给去 AI 腔（[selectionCliche]）。
 * - 空章：写本章（进整章草稿页）。
 * - 修订中的章：先给三道审稿。
 * - 章末：有没写的拍时写第 N 拍；看着写完了（[finished]：节拍都写过、字数到了或接近目标）时这章写完了；再是接着写。
 * - 空行、段末：写第 N 拍（有的话），接着写。
 * - 段中间：润色这段、扩写这段。
 */
internal fun sparkActions(
    text: CharSequence,
    selection: TextRange,
    status: ChapterStatus?,
    aiReady: Boolean,
    inline: InlineWrite? = null,
    aiState: WriteAiState? = null,
    nextBeat: Beat? = null,
    finished: String? = null,
    cardMissing: Boolean = false,
    selectionCliche: Boolean = false,
): List<EditorRecommendation> {
    val base = editorRecommendation(text, selection, status, aiReady, inline, aiState, nextBeat, finished, cardMissing)
    if (base.kind == EditorRecommendationKind.PENDING_AI || base.kind == EditorRecommendationKind.UNLOCK ||
        base.kind == EditorRecommendationKind.CONFIGURE_AI
    ) return listOf(base)
    val spot = caretSpot(text, selection)
    if (spot == CaretSpot.SELECTION) {
        val n = selection.max.coerceAtMost(text.length) - selection.min.coerceIn(0, text.length)
        return listOfNotNull(
            EditorRecommendation(EditorRecommendationKind.REVISE_SELECTION, "润色", "已选 $n 字"),
            if (n >= LONG_SELECTION) EditorRecommendation(EditorRecommendationKind.CONDENSE_SELECTION, "缩写", "选得多，精简一些")
            else EditorRecommendation(EditorRecommendationKind.EXPAND_SELECTION, "扩写", "补动作、神态和细节"),
            EditorRecommendation(EditorRecommendationKind.DECLICHE_SELECTION, "去 AI 腔", "选中的文字里有 AI 腔").takeIf { selectionCliche },
        )
    }
    if (spot == CaretSpot.EMPTY_CHAPTER) return listOf(EditorRecommendation(EditorRecommendationKind.DRAFT_CHAPTER, "写本章", base.reason))
    val list = mutableListOf<EditorRecommendation>()
    if (status == ChapterStatus.REVISION) list += EditorRecommendation(EditorRecommendationKind.REVIEW_CHAPTER, "三道审稿", "本章正在修订")
    val beat = nextBeat?.let { EditorRecommendation(EditorRecommendationKind.WRITE_BEAT, "写第 ${it.index} 拍", it.text) }
    val more = EditorRecommendation(EditorRecommendationKind.CONTINUE, "AI 接着写", "从光标处写下一场")
    when (spot) {
        CaretSpot.CHAPTER_END -> {
            beat?.let { list += it }
            finished?.let { list += EditorRecommendation(EditorRecommendationKind.FINISH_CHAPTER, "这章写完了", it) }
            list += more
        }
        CaretSpot.BLANK_LINE, CaretSpot.PARAGRAPH_END -> {
            beat?.let { list += it }
            list += more
        }
        else -> {
            list += EditorRecommendation(EditorRecommendationKind.POLISH_PARAGRAPH, "润色这段", "光标所在的这一段")
            list += EditorRecommendation(EditorRecommendationKind.EXPAND_PARAGRAPH, "扩写这段", "补动作、神态和细节")
        }
    }
    return list.take(3)
}

/** 底栏上方展开的那一排：某个标签的大图标，或 ✦ 的几项推荐。 */
internal sealed interface EditorRow {
    data class Tab(val group: EditorToolGroup) : EditorRow
    data object Spark : EditorRow
}

/** “写”标签里点了“改稿”“标点”时，那一排换成改稿的几样、快捷标点；收起标签回到大图标。 */
internal enum class WriteSub { TILES, REVISE, PUNCTUATION }

/** 编辑器大图标、推荐用的图标：每样一个，同一排里分得开。 */
internal fun entryIcon(entry: EditorEntry): ImageVector = when (entry) {
    EditorEntry.AI_WRITE -> LingYueIcons.Sparkle
    EditorEntry.IDEAS -> LingYueIcons.Bulb
    EditorEntry.DRAFT -> LingYueIcons.Write
    EditorEntry.REVISE -> LingYueIcons.Polish
    EditorEntry.PUNCTUATION -> LingYueIcons.Punctuation
    EditorEntry.PROBLEMS -> LingYueIcons.Highlight
    EditorEntry.BRIEF -> LingYueIcons.Info
    EditorEntry.LEDGER -> LingYueIcons.Book
    EditorEntry.REVIEW -> LingYueIcons.Search
    EditorEntry.CARD -> LingYueIcons.ListView
    EditorEntry.FINISH -> LingYueIcons.Check
    EditorEntry.FORMAT -> LingYueIcons.Format
    EditorEntry.HISTORY -> LingYueIcons.History
    EditorEntry.STATUS -> LingYueIcons.Flag
    EditorEntry.RENAME -> LingYueIcons.Rename
    EditorEntry.PREVIEW -> LingYueIcons.Preview
    EditorEntry.LISTEN -> LingYueIcons.VolumeUp
    EditorEntry.NEW_CHAPTER -> LingYueIcons.Add
    EditorEntry.SYNC -> LingYueIcons.Download
    EditorEntry.REFERENCE -> LingYueIcons.Notes
    EditorEntry.PACK -> LingYueIcons.Folder
    EditorEntry.PREV_CHAPTER -> LingYueIcons.ChevronBackward
    EditorEntry.NEXT_CHAPTER -> LingYueIcons.ChevronForward
}

internal fun sparkIcon(kind: EditorRecommendationKind): ImageVector = when (kind) {
    EditorRecommendationKind.REVISE_SELECTION, EditorRecommendationKind.POLISH_PARAGRAPH -> LingYueIcons.Polish
    EditorRecommendationKind.EXPAND_SELECTION, EditorRecommendationKind.EXPAND_PARAGRAPH -> LingYueIcons.Add
    EditorRecommendationKind.CONDENSE_SELECTION -> LingYueIcons.Remove
    EditorRecommendationKind.DECLICHE_SELECTION -> LingYueIcons.Highlight
    EditorRecommendationKind.DRAFT_CHAPTER -> LingYueIcons.Write
    EditorRecommendationKind.REVIEW_CHAPTER -> LingYueIcons.Search
    EditorRecommendationKind.WRITE_BEAT -> LingYueIcons.ListView
    EditorRecommendationKind.FINISH_CHAPTER -> LingYueIcons.Check
    EditorRecommendationKind.UNLOCK -> LingYueIcons.Flag
    else -> LingYueIcons.Sparkle
}

/** 选中文字后，系统菜单（剪切、复制、粘贴……）后面加的几样。 */
internal enum class SelectionAction(val label: String) {
    POLISH("润色"), EXPAND("扩写"), CONDENSE("缩写"), DECLICHE("去 AI 腔"), ASK("问 AI")
}

/** 选中菜单加哪几样：去 AI 腔只在选中的文字命中了本机标出的 AI 腔时给（[cliche]）。 */
internal fun selectionMenu(cliche: Boolean): List<SelectionAction> =
    SelectionAction.entries.filter { it != SelectionAction.DECLICHE || cliche }

/**
 * 编辑器底栏：左边四个文字标签（[TextTab]），右边 ✦ 推荐（第一项一点就做；还有别的时右边一个“+N”展开）。
 * 点标签，标签上面展开一排（[content]，一般是 [ToolTileGrid] 的大图标），从底边升起；再点同一个收起。
 * [compact]（矮屏、键盘弹出）时展开的那排不另起一行，和一个“收起”的标签合在底栏这一行里，正文多留几行。
 */
@Composable
internal fun EditorBottomBar(
    row: EditorRow?,
    compact: Boolean,
    spark: List<EditorRecommendation>,
    /** 正文还没读出来时 ✦ 显示“载入中”、不能点。 */
    ready: Boolean,
    /** 本章的问题数：大于 0 时挂在“查”上。 */
    problems: Int = 0,
    onRow: (EditorRow?) -> Unit,
    onSpark: (EditorRecommendation) -> Unit,
    content: @Composable (EditorRow) -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(Modifier.fillMaxWidth().background(c.background)) {
        AnimatedContent(
            row.takeIf { !compact },
            transitionSpec = {
                // 换一个标签只换里面的图标（淡入淡出），开、收才升起、落下。
                val swap = initialState != null && targetState != null
                (if (swap) Effects.enter(fadeIn(Motion.standard(Motion.SHORT))) togetherWith Effects.exit(fadeOut(Motion.exit()))
                else Motion.edgeEnter() togetherWith Motion.edgeExit()) using (
                    if (Effects.noMotion) null else SizeTransform(clip = true) { _, _ -> if (swap) snap() else Motion.standard(Motion.MEDIUM) }
                )
            },
            contentAlignment = Alignment.BottomCenter,
            label = "editorRow",
        ) { r ->
            if (r == null) Spacer(Modifier.fillMaxWidth())
            else Box(
                Modifier.fillMaxWidth().semantics { paneTitle = rowTitle(r) }.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            ) { content(r) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        AnimatedContent(
            row.takeIf { compact },
            transitionSpec = { Effects.enter(fadeIn(Motion.standard(Motion.SHORT))) togetherWith Effects.exit(fadeOut(Motion.exit())) },
            label = "editorBar",
        ) { merged ->
            Row(
                // 矮屏时这一行压到 52dp（大图标本身的高），正文多露一点。
                Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 56.dp).padding(horizontal = 4.dp, vertical = if (compact) 0.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (merged != null) {
                    TextTab(rowLabel(merged), selected = true, onClick = { onRow(null) }, description = "收起${rowTitle(merged)}")
                    Box(Modifier.weight(1f).padding(start = 2.dp, end = 4.dp)) { content(merged) }
                } else {
                    EditorToolGroup.entries.forEach { g ->
                        val tab = EditorRow.Tab(g)
                        val count = problems.takeIf { g == EditorToolGroup.CHECK && it > 0 }
                        TextTab(
                            g.label,
                            selected = row == tab,
                            onClick = { onRow(if (row == tab) null else tab) },
                            description = if (count != null) "查，$count 处问题" else g.description,
                            badge = count?.let { if (it > 99) "99+" else "$it" },
                        )
                    }
                    val first = spark.first()
                    SparkChip(first, enabled = ready && first.enabled, loading = !ready, modifier = Modifier.weight(1f).padding(start = 4.dp)) { onSpark(first) }
                    if (ready && first.enabled && spark.size > 1) {
                        val open = row == EditorRow.Spark
                        MoreSparkButton(spark.size - 1, open) { onRow(if (open) null else EditorRow.Spark) }
                    }
                }
            }
        }
    }
}

private fun rowLabel(r: EditorRow): String = when (r) {
    is EditorRow.Tab -> r.group.label
    EditorRow.Spark -> "✦"
}

private fun rowTitle(r: EditorRow): String = when (r) {
    is EditorRow.Tab -> "“${r.group.label}”"
    EditorRow.Spark -> "推荐"
}

/**
 * ✦ 推荐：淡强调色底、强调色字（“✦ 名字”，下面一行灰色理由），不铺整条实心色；还没设置写作 AI 时是一枚描边的次要按钮、不带理由。
 * AI 在写、等采纳时灰底，只显示状态、不能点。
 */
@Composable
private fun SparkChip(rec: EditorRecommendation, enabled: Boolean, loading: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val label = if (loading) "载入中" else rec.label
    val setup = enabled && rec.kind == EditorRecommendationKind.CONFIGURE_AI
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(
                when {
                    setup -> Modifier.border(1.dp, c.tertiary.copy(alpha = 0.45f), shape)
                    enabled -> Modifier.background(c.tint.copy(alpha = 0.12f))
                    else -> Modifier.background(c.fill)
                },
            )
            .background(pressHighlight(pressed && enabled))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (enabled) "智能推荐：$label，${rec.reason}" else "当前状态：$label，${rec.reason}"
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (setup) {
            Text(label, color = c.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✦ $label", color = if (enabled) c.tint else c.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!loading) Text(rec.reason, color = c.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** ✦ 右边的“+N”：还有 N 项推荐，点了在上面展开全部（再点收起）。 */
@Composable
private fun MoreSparkButton(more: Int, open: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .widthIn(min = 44.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(pressHighlight(source.collectIsPressedAsState().value))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (open) "收起推荐" else "还有 $more 项推荐" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (open) "收起" else "+$more", color = c.tint, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** “写”那排换成的一排小钮（改稿的几样、快捷标点）：最左一个返回，其余放不下左右滑。 */
@Composable
internal fun EditorChipRow(backDescription: String, onBack: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backDescription },
            contentAlignment = Alignment.Center,
        ) { Icon(LingYueIcons.ChevronBackward, contentDescription = null, tint = c.secondary, modifier = Modifier.size(20.dp)) }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** [EditorChipRow] 里的一个：看着 40dp 高的灰底圆角块，能点 48dp。 */
@Composable
internal fun EditorChip(text: String, description: String? = null, fontSize: Int = 14, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .pressScale(source, 0.95f)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = c.label, fontSize = fontSize.sp, maxLines = 1) }
    }
}

/** 正文下沿浮着的一次性提示（卡片底色、细边），右边“知道了”。 */
@Composable
internal fun EditorTip(text: String, onDismiss: () -> Unit) {
    val c = LocalGroupedColors.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.cell)
            .border(1.dp, c.separator, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = c.label, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(text = "知道了", onClick = onDismiss)
    }
}
