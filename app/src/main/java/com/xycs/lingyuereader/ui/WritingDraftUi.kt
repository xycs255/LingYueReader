package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 草稿那张卡片第一行右边：在写（转圈、写到哪 / 在查什么），写完按检查规则的结果。 */
private data class DraftStatus(val loading: Boolean, val lookup: String?, val hits: Int?)

/** 草稿页的主按钮是哪一个：停止、写本章、重写一版。 */
private enum class DraftMode { STOP, WRITE, REWRITE }

/**
 * AI 写本章：按细纲卡、本章资料、上一章结尾、写作规范和检查规则写一版草稿。边写边显示，写完存成草稿；
 * 作者看过以后放进正文（替换或接在后面，原来的正文先存一版），也可以接着写、重写。AI 写的字不算码字数。
 */
@Composable
fun ChapterDraftScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val drafts by vm.writingAi.drafts.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var instruction by rememberSaveable(chapterId) { mutableStateOf("") }
    var placing by remember { mutableStateOf(false) }
    var card by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadDraft(workId, chapterId)
        card = vm.writing.cards(workId, chapterId).first.card
    }
    val draft = drafts[workId to chapterId]
    val text = draft?.text.orEmpty()
    val loading = draft?.loading == true
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // 写完以后按检查规则看一遍（本机算）。
    val hits by produceState<Int?>(null, text, loading) {
        value = if (loading || text.isBlank()) null else vm.writing.rules(workId).takeIf { !it.isEmpty }?.let { CheckRules.check(text, it).hits.size }
    }
    val snackbar = rememberWritingMessages(vm)
    val locked = chapter?.locked == true
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()
    var showSent by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 边写边显示时跟着滚到底，看得到新写的字；作者往上翻着看时（离底超过一屏多）不打扰。减少动效时直接跳过去。
    LaunchedEffect(text.length, loading) {
        if (!loading) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (last.index != info.totalItemsCount - 1) return@LaunchedEffect
        val below = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        if (below in 1..FOLLOW_LIMIT) {
            if (Effects.noMotion) listState.scrollBy(below.toFloat()) else listState.animateScrollBy(below.toFloat(), Motion.standard(Motion.SHORT))
        }
    }

    SubPageScaffold("AI 写本章", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().imePadding().overScrollVertical(),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                GroupedSection(
                    footer = when {
                        locked -> "这一章已定稿，不能让 AI 重写。要改先解锁并登记原因。"
                        !aiConfig.ready -> "先在“我的 → AI 助手”里设置服务商。"
                        card?.isEmpty == true -> "这一章还没有细纲卡：AI 只能凭前文和设定猜着写。先写细纲卡更稳。"
                        else -> "按细纲卡、本章资料、上一章结尾、写作规范和检查规则写；篇幅按检查规则里的字数。写好的是草稿，看过再放进正文。"
                    },
                ) {
                    row {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextField(
                                value = instruction,
                                onValueChange = { instruction = it },
                                label = "这一章想怎么写（可空）· 比如：多写对白，节奏快一点",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
                            )
                        }
                    }
                    if (card?.isEmpty == true) row { NavRow("先写细纲卡") { vm.open(Screen.ChapterCard(workId, chapterId)) } }
                    row {
                        val mode = when {
                            loading -> DraftMode.STOP
                            text.isBlank() -> DraftMode.WRITE
                            else -> DraftMode.REWRITE
                        }
                        // 写本章 ↔ 停止 ↔ 重写一版：原地淡入淡出，不一下换字。
                        AnimatedContent(mode, transitionSpec = { Motion.fadeThrough() }, label = "draftAction") { m ->
                            when (m) {
                                DraftMode.STOP -> ActionRow("停止", destructive = true) { vm.writingAi.stopDraft(workId, chapterId) }
                                DraftMode.WRITE -> ActionRow("写本章", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, instruction) }
                                DraftMode.REWRITE -> ActionRow("重写一版", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, instruction) }
                            }
                        }
                    }
                    if (text.isNotBlank() && !loading) {
                        row { ActionRow("接着写", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, more = true) } }
                    }
                    if (sent != null && sent?.label in setOf("写本章", "接着写")) {
                        row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.size} 字" }) { showSent = true } }
                    }
                }
                // 草稿那一组：开始写时长出来，不要这版时收起。组名只写“草稿”，字数和进度写在卡片第一行。
                AnimatedVisibility(
                    visible = text.isNotBlank() || loading || draft?.error != null,
                    enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                    exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                ) {
                    GroupedSection(header = "草稿") {
                        row {
                            Column {
                                DraftStatusRow(WordCount.of(text), DraftStatus(loading, lookup, hits))
                                if (!loading) UsageNote(draft?.usage, draft?.cost, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                            }
                        }
                        draft?.error?.let { e -> row { ErrorText("没写成：$e", Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                        if (text.isNotBlank()) {
                            row {
                                SelectionContainer {
                                    Text(
                                        text,
                                        color = c.label,
                                        fontSize = 17.sp,
                                        lineHeight = 30.sp,
                                        style = androidx.compose.ui.text.TextStyle(textIndent = TextIndent(firstLine = 2.em)),
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    )
                                }
                            }
                        }
                        if (text.isNotBlank() && !loading) {
                            row { ActionRow("放进正文", enabled = !locked) { placing = true } }
                            row { ActionRow("不要这版", destructive = true) { vm.writingAi.clearDraft(workId, chapterId) } }
                        }
                    }
                }
            }
        }
        if (showSent) sent?.let { SentDialog(it) { showSent = false } }
        if (placing) {
            val empty = (chapter?.words ?: 0) == 0
            fun place(replace: Boolean) {
                placing = false
                vm.writing.applyDraft(workId, chapterId, text, replace) {
                    vm.writingAi.clearDraft(workId, chapterId)
                    vm.showMessage("已放进正文")
                    vm.back()
                }
            }
            GlassDialog(
                show = true,
                title = "放进正文",
                summary = if (empty) "这一章还没有正文，草稿直接放进去。" else "正文现在有 ${chapter?.words} 字。原来的正文会先存一个历史版本。AI 写的字不算码字数。",
                onDismissRequest = { placing = false },
            ) {
                if (empty) {
                    DialogButtons(onDismiss = { placing = false }, onConfirm = { place(true) }, confirmText = "放进正文")
                } else {
                    // 两种放法一行一项，点了就放。
                    DialogOptionList(listOf(true to "替换正文", false to "接在正文后面"), onPick = { place(it) })
                    DialogOkButton("取消", primary = false) { placing = false }
                }
            }
        }
    }
}

/** 离底多远以内才跟着滚（像素，约一屏多）：再远说明作者往上翻着看，不拽回去。 */
private const val FOLLOW_LIMIT = 1600

/** 草稿卡片第一行：左边字数，右边在写的转圈和进度，或写完后按检查规则的结果（合规则用成功色，有不合的用提醒色）。 */
@Composable
private fun DraftStatusRow(words: Int, status: DraftStatus) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Text("$words 字", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        AnimatedContent(
            status,
            transitionSpec = { Motion.fadeThrough() },
            contentKey = { if (it.loading) "busy" else "done:${it.hits}" },
            contentAlignment = Alignment.CenterEnd,
            label = "draftStatus",
        ) { s ->
            when {
                s.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner()
                    Text(s.lookup?.let { "正在查：$it" } ?: "正在写……", color = c.secondary, fontSize = 14.sp, maxLines = 1)
                }
                s.hits == 0 -> Tag("合规则", c.success)
                s.hits != null -> Tag("${s.hits} 条不合规则", c.warning)
            }
        }
    }
}
